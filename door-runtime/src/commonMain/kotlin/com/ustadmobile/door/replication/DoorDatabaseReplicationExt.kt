package com.ustadmobile.door.replication

import com.ustadmobile.door.DoorDatabaseRepository
import com.ustadmobile.door.annotation.ReplicateEntity
import com.ustadmobile.door.entities.DoorNode
import com.ustadmobile.door.entities.OutgoingReplication
import com.ustadmobile.door.ext.*
import com.ustadmobile.door.jdbc.ext.*
import com.ustadmobile.door.message.DoorMessage
import com.ustadmobile.door.nodeevent.NodeEvent
import com.ustadmobile.door.nodeevent.NodeEventManager
import com.ustadmobile.door.room.RoomDatabase
import com.ustadmobile.door.util.TransactionMode
import io.github.aakira.napier.Napier
import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlin.math.absoluteValue

private data class ReplicateEntityPrimaryKeys(
    val pk1: Long,
    val pk2: Long,
    val orUid: Long,
)

/**
 * Select a list of DoorReplicateEntity(s) for the given table id and list of primary keys to select.
 */
private suspend fun RoomDatabase.selectDoorReplicateEntitiesByTableIdAndPrimaryKeys(
    tableId: Int,
    primaryKeysList: List<ReplicateEntityPrimaryKeys>,
): List<DoorReplicationEntity> {
    val entityMetaData = this::class.doorDatabaseMetadata().requireReplicateEntityMetaData(tableId)
    val entityFieldsTypeMap = entityMetaData.entityFieldsTypeMap

    return prepareAndUseStatementAsync(
        sql = entityMetaData.selectEntityByPrimaryKeysSql,
        readOnly = true,
    ) { stmt ->
        primaryKeysList.mapNotNull { primaryKeys ->
            stmt.setLong(1, primaryKeys.pk1)
            stmt.executeQueryAsyncKmp().useResults { result ->
                result.mapNextRow(null) { mapResult ->
                    DoorReplicationEntity(
                        tableId = tableId,
                        orUid = primaryKeys.orUid,
                        entity = mapResult.rowToJsonObject(entityFieldsTypeMap),
                    )
                }
            }
        }
    }
}

/**
 * Select the DoorReplicateEntity (e.g. including the full Json of the entity data) for a list of NodeEvent(s) that
 * represent replication events (e.g. something new was inserted into the OutgoingRelpication table). This is
 * used as part of converting the NodeEvent into a DoorMessage that can be transmitted to another node. This is done
 * lazily because the NodeEvent will only be transmitted if there is a sender which wants to transmit this event (e.g.
 * probably only when the other node is connected to this node).
 */
//To be deprecated - we won't be using this using the invalidate-post cycle
suspend fun RoomDatabase.selectDoorReplicationEntitiesForEvents(
    events: Iterable<NodeEvent>,
): List<DoorReplicationEntity> {
    return events.runningSplitBy { it.tableId }.map { tableEvents ->
        val tableId = tableEvents.first().tableId
        selectDoorReplicateEntitiesByTableIdAndPrimaryKeys(tableId,
            tableEvents.map { ReplicateEntityPrimaryKeys(it.key1, it.key2, 0) }
        )
    }.flatten()
}

/**
 * Select a list of DoorReplicateEntity of the replications that are pending for a particular destination node.
 */
suspend fun RoomDatabase.selectPendingOutgoingReplicationsByDestNodeId(
    nodeId: Long,
    limit: Int = 1000
): List<DoorReplicationEntity> {
    val pendingReplications = prepareAndUseStatementAsync(
        sql = """
            SELECT OutgoingReplication.*
              FROM OutgoingReplication
             WHERE OutgoingReplication.destNodeId = ?
          ORDER BY OutgoingReplication.orUid ASC
             LIMIT ?
        """,
        readOnly = true
    ) { stmt ->
        stmt.setLong(1, nodeId)
        stmt.setInt(2, limit)

        stmt.executeQueryAsyncKmp().useResults { results ->
            results.mapRows { result ->
                OutgoingReplication(
                    orUid = result.getLong("orUid"),
                    destNodeId = result.getLong("destNodeId"),
                    orTableId = result.getInt("orTableId"),
                    orPk1 = result.getLong("orPk1"),
                    orPk2 = result.getLong("orPk2")
                )
            }
        }
    }

    if(pendingReplications.isEmpty())
        return emptyList()

    return pendingReplications.runningSplitBy { it.orTableId }.map { tableIdPendingList ->
        val tableId = tableIdPendingList.first().orTableId

        selectDoorReplicateEntitiesByTableIdAndPrimaryKeys(tableId,
            pendingReplications.map { ReplicateEntityPrimaryKeys(it.orPk1, it.orPk2, it.orUid) })
    }.flatten()
}

/**
 * Delete from the OutgoingReplication table when the destination node acknowledges receipt of the entities. This should
 * be part of a transaction
 *
 * @param nodeId the remote node id
 * @param receivedUids a list of OutgoingReplication uids received (as per orUid)
 */
suspend fun RoomDatabase.acknowledgeReceivedReplications(
    nodeId: Long,
    receivedUids: List<Long>,
) {
    prepareAndUseStatementAsync(
        sql = """
        DELETE FROM ${OutgoingReplication::class.simpleName}
              WHERE orUid = ?
                AND destNodeId = ?
        """,
        readOnly = false
    ) { stmt ->
        receivedUids.forEach { uid ->
            stmt.setLong(1, uid)
            stmt.setLong(2, nodeId)
            stmt.executeUpdateAsyncKmp()
        }
    }
}

/**
 * The client will send a list of pending replications that it wishes to acknowledge. After the pending replications
 * have been cleared, the return body will contain the next batch of pending replications. The loop thus works as
 * follows:
 *
 *  1. Client connects - initially sends empty list of pending replications to ack
 *  2. Server responds with batch of pending replications as list of DoorReplicationEntity (by default up to 1000
 *     entities)
 *  3. Client handles received replications, and calls ackAndGetPendingReplications again including the list of
 *     replication uids processed in Step 2
 *  4. Server deletes pending replications, responds with next batch of pending replications
 *
 *  Process repeats until the list of pending replications is empty. The client will listen for new notification of
 *  new pending replications via a channel (e.g. Server Sent Events) which can also trigger the loop.
 */
suspend fun RoomDatabase.acknowledgeReceivedReplicationsAndSelectNextPendingBatch(
    nodeId: Long,
    receivedAck: ReplicationReceivedAck,
    limit: Int = 1000,
): DoorMessage {
    val transactionMode = if(receivedAck.replicationUids.isEmpty()) {
        TransactionMode.READ_ONLY
    }else {
        TransactionMode.READ_WRITE
    }

    val pendingReplications = withDoorTransactionAsync(transactionMode) {
        if(receivedAck.replicationUids.isNotEmpty()) {
            acknowledgeReceivedReplications(nodeId, receivedAck.replicationUids)
        }

        selectPendingOutgoingReplicationsByDestNodeId(nodeId = nodeId, limit = limit)
    }

    return DoorMessage(
        what = DoorMessage.WHAT_REPLICATION_PUSH,
        fromNode = doorWrapperNodeId,
        toNode = nodeId,
        replications = pendingReplications,
    )
}

/**
 *
 */
suspend fun RoomDatabase.insertEntitiesFromMessage(
    message: DoorMessage,
) {
    val dbMetadata = this::class.doorDatabaseMetadata()
    val hasReplicationOpTable = "ReplicationOperation" in dbMetadata.allTables
    message.replications.runningSplitBy { it.tableId }.forEach { tableEntities ->
        val tableId = tableEntities.first().tableId
        val entityMetaData = dbMetadata.requireReplicateEntityMetaData(tableId)
        if(hasReplicationOpTable) {
            prepareAndUseStatementAsync(
                sql = """
                    INSERT INTO ReplicationOperation(repOpRemoteNodeId, repOpTableId, repOpStatus)
                           VALUES(?, ?, ?)
                    """,
                readOnly = false,
            ) { stmt ->
                stmt.setLong(1, message.fromNode)
                stmt.setInt(2, tableId)
                stmt.setInt(3, 0)
                stmt.executeUpdateAsyncKmp()
            }
        }

        if(entityMetaData.remoteInsertStrategy == ReplicateEntity.RemoteInsertStrategy.INSERT_INTO_RECEIVE_VIEW) {
            prepareAndUseStatementAsync(
                sql = entityMetaData.insertIntoReceiveViewSql,
                readOnly = false,
            ) { stmt ->
                tableEntities.forEach { entity ->
                    stmt.setAllFromJsonObject(entity.entity, entityMetaData.entityFields)

                    //Set the fromNodeId, which is always last
                    stmt.setLong(entityMetaData.entityFields.size + 1, message.fromNode)
                    stmt.executeUpdateAsyncKmp()
                }
            }
        }

        if(hasReplicationOpTable) {
            prepareAndUseStatementAsync(
                sql = """
                    DELETE FROM ReplicationOperation
                          WHERE repOpRemoteNodeId = ?
                            AND repOpTableId = ?
                    """,
                readOnly = false,
            ) { stmt ->
                stmt.setLong(1, message.fromNode)
                stmt.setInt(2, tableId)
                stmt.executeUpdateAsyncKmp()
            }
        }
    }
}

internal suspend fun RoomDatabase.getDoorNodeAuth(nodeId : Long): String? {
    return prepareAndUseStatementAsync(
        sql = """SELECT auth
            FROM DoorNode
            WHERE nodeId = ?
            """,
        readOnly = true,
    ) { stmt ->

        stmt.setLong(1, nodeId)

        stmt.executeQueryAsyncKmp().useResults { results ->
            results.mapRows { it.getString(1) }.firstOrNull()
        }
    }
}

internal suspend fun RoomDatabase.insertNewDoorNode(node: DoorNode) {
    prepareAndUseStatementAsync("INSERT INTO DoorNode(nodeId, auth, rel) VALUES(?, ?, ?)") { stmt ->
        stmt.setLong(1, node.nodeId)
        stmt.setString(2, node.auth)
        stmt.setInt(3, node.rel)
        stmt.executeUpdateAsyncKmp()
    }
}

@Suppress("unused") //This can be used by generated code
internal suspend fun RoomDatabase.selectDoorNodeExists(nodeId: Long): Boolean {
    return prepareAndUseStatementAsync(
        sql = """
            SELECT EXISTS(
                   SELECT nodeId 
                     FROM DoorNode
                    WHERE nodeId = ?) 
            """,
        readOnly = true,
    ) { stmt ->
        stmt.setLong(1, nodeId)
        stmt.executeQueryAsyncKmp().useResults { results -> results.mapRows {
            it.getBoolean(1)
        } }.first()
    }
}



/**
 * Handle a pull replication response received. This function is used by generated repositories.
 */
@Suppress("unused")
suspend fun RoomDatabase.onClientRepoDoorMessageHttpResponse(
    httpResponse: HttpResponse
) {
    val nodeEventManager: NodeEventManager<*> = doorWrapper.nodeEventManager
    when (httpResponse.status) {
        HttpStatusCode.OK -> {
            val message: DoorMessage = httpResponse.body()
            Napier.v(tag = DoorTag.LOG_TAG) {
                "$this : onClientRepoDoorMessageHttpResponse : handle message with " +
                        "${message.replications.size} replications"
            }
            nodeEventManager.onIncomingMessageReceived(message)
        }
        HttpStatusCode.NotModified, HttpStatusCode.NoContent -> {
            Napier.v(tag = DoorTag.LOG_TAG) {
                "$this - http response was not modified or no content, no need to do anything"
            }
        }
        else -> {
            throw IllegalStateException("$this - unexpected response status - ${httpResponse.status}")
        }
    }
}


private fun createChangeMonitorTriggerSql(
    entityMetaData: ReplicationEntityMetaData,
    remoteNodeId: Long,
    operation: String
): String {
    val triggerName =  "_d_ch_monitor_${entityMetaData.tableId}_${remoteNodeId.absoluteValue}" +
            "_${operation.substring(0, 2).lowercase()}"
    return """
            CREATE TEMP TRIGGER IF NOT EXISTS $triggerName 
            AFTER $operation ON ${entityMetaData.entityTableName}
            FOR EACH ROW
            BEGIN
                INSERT INTO OutgoingReplication(destNodeId, orTableId, orPk1, orPk2)
                VALUES ($remoteNodeId, ${entityMetaData.tableId}, NEW.${entityMetaData.entityPrimaryKeyFieldName}, 0);
            END
            """
}

private fun dropChangeMonitorTriggerSql(
    entityMetaData: ReplicationEntityMetaData,
    remoteNodeId: Long,
    operation: String
): String {val triggerName =  "_d_ch_monitor_${entityMetaData.tableId}_${remoteNodeId.absoluteValue}" +
        "_${operation.substring(0, 2).lowercase()}"
    return "DROP TRIGGER IF EXISTS $triggerName"
}

/**
 * Put any changes to the given table name into the OutgoingReplications outbox.
 *
 *
 */
@Suppress("unused") //This function is called by generated code
suspend fun <R> DoorDatabaseRepository.withRepoChangeMonitorAsync(
    tableName: String,
    block: suspend () -> R,
): R {
    val entityMetaData = db::class.doorDatabaseMetadata().replicateEntities.values.first {
        it.entityTableName == tableName
    }

    val remoteNodeId = remoteNodeIdOrFake()
    return db.withDoorTransactionAsync(transactionMode = TransactionMode.READ_WRITE) {
        db.prepareAndUseStatementAsync(createChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "INSERT")) { stmt ->
            stmt.executeUpdateAsyncKmp()
        }

        db.prepareAndUseStatementAsync(createChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "UPDATE")) {stmt ->
            stmt.executeUpdateAsyncKmp()
        }

        val result = block()

        db.prepareAndUseStatementAsync(dropChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "INSERT")) { stmt ->
            stmt.executeUpdateAsyncKmp()
        }
        db.prepareAndUseStatementAsync(dropChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "UPDATE")) { stmt ->
            stmt.executeUpdateAsyncKmp()
        }

        result
    }
}

fun <R> DoorDatabaseRepository.withRepoChangeMonitor(
    tableName: String,
    block: () -> R
) :R {
    val entityMetaData = db::class.doorDatabaseMetadata().replicateEntities.values.first {
        it.entityTableName == tableName
    }
    val remoteNodeId = remoteNodeIdOrFake()

    return db.withDoorTransaction(transactionMode = TransactionMode.READ_WRITE) {
        db.prepareAndUseStatement(createChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "INSERT")) { stmt ->
            stmt.executeUpdate()
        }

        db.prepareAndUseStatement(createChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "UPDATE")) {stmt ->
            stmt.executeUpdate()
        }

        val result = block()

        db.prepareAndUseStatement(dropChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "INSERT")) { stmt ->
            stmt.executeUpdate()
        }
        db.prepareAndUseStatement(dropChangeMonitorTriggerSql(entityMetaData, remoteNodeId, "UPDATE")) { stmt ->
            stmt.executeUpdate()
        }

        result
    }
}

