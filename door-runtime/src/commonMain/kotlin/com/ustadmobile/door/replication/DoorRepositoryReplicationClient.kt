package com.ustadmobile.door.replication

import com.ustadmobile.door.DoorConstants
import com.ustadmobile.door.RepositoryConfig
import com.ustadmobile.door.ext.*
import com.ustadmobile.door.message.DoorMessage
import com.ustadmobile.door.nodeevent.NodeEventManager
import com.ustadmobile.door.room.RoomDatabase
import com.ustadmobile.door.util.systemTimeInMillis
import io.github.aakira.napier.Napier
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.Volatile


/**
 * The DoorRepositoryReplicationClient will connect with another Door node via HTTP to:
 *
 *  - send outgoing replications from this node which are destined for the remote node we are connected to
 *  - receive outgoing replications from the other remote node which are destined for this node
 *
 * Replications are sent and received STRICTLY in the order that they were inserted into the OutgoingReplication table.
 *
 * DoorRepositoryReplicationClient relies on the nodeEventManager to know when it needs to re-query for pending outgoing or
 * incoming replication. It will observe the incomingMessages, if the message comes from the remote node and indicates
 * that there are new replications to fetch, then fetching replications from the remote node will be initiated. It will
 * also observe outgoingEvents. If an event indicates that there are new replications to send to the remote node, then
 * sending replications to the remote node will be initiated.
 *
 * @param localNodeId - the Id of this node (the node we are running on) - not the id of the remote node on the other side
 * @param localNodeAuth - the auth string for this node that is required for requests to the remote node.
 * @param httpClient Ktor HTTP Client (MUST be using Kotlinx Json serializer)
 * @param repoEndpointUrl the url of the other node as per RepositoryConfig.endpoint
 * @param scope CoroutineScope for the repository
 * @param nodeEventManager the NodeEventManager for the local database that we will use to watch for pending
 *        incoming/outgoing replication
 * @param onMarkAcknowledgedAndGetNextOutgoingReplications a function that will mark
 * @param retryInterval the auto retry period
 */
class DoorRepositoryReplicationClient(
    private val localNodeId: Long,
    private val localNodeAuth: String,
    private val httpClient: HttpClient,
    private val repoEndpointUrl: String,
    scope: CoroutineScope,
    private val nodeEventManager: NodeEventManager<*>,
    private val onMarkAcknowledgedAndGetNextOutgoingReplications: OnMarkAcknowledgedAndGetNextOutgoingReplications,
    private val retryInterval: Int = 10_000,
) {

    data class ClientState(
        val initialized: Boolean = false,
    )

    constructor(
        db: RoomDatabase,
        repositoryConfig: RepositoryConfig,
        scope: CoroutineScope,
        nodeEventManager: NodeEventManager<*>,
        retryInterval: Int,
    ): this (
        localNodeId = db.doorWrapperNodeId,
        localNodeAuth = repositoryConfig.auth,
        httpClient = repositoryConfig.httpClient,
        repoEndpointUrl = repositoryConfig.endpoint,
        scope = scope,
        nodeEventManager = nodeEventManager,
        onMarkAcknowledgedAndGetNextOutgoingReplications = DefaultOnMarkAcknowledgedAndGetNextOutgoingReplications(db),
        retryInterval = retryInterval,
    )

    private val logPrefix = "[DoorRepositoryReplicationClient localNodeId=$localNodeId endpoint=$repoEndpointUrl]"

    private val _state = MutableStateFlow(ClientState())

    val state: Flow<ClientState> = _state.asStateFlow()

    init {
        Napier.d(
            tag = DoorTag.LOG_TAG,
            message = "$logPrefix init"
        )
    }

    /**
     * Functional interface that will run one (single) transaction to acknowledge entities received by the remote node
     * and then query for remaining pending replications (if any, up to the batch size)
     */
    interface OnMarkAcknowledgedAndGetNextOutgoingReplications {

        /**
         * @param receivedAck replicated entities that have been acknowledged by the remote node that should be marked
         *        as processed in our database (e.g. deleted from OutgoingReplication).
         * @param nodeId the id of the remote node that we want to get outgoing replications for
         * @param batchSize the maximum number of pending replication entities to return
         */
        suspend operator fun invoke(
            nodeId: Long,
            receivedAck: ReplicationReceivedAck,
            batchSize: Int
        ): List<DoorReplicationEntity>
    }

    class DefaultOnMarkAcknowledgedAndGetNextOutgoingReplications(
        private val db: RoomDatabase,
    ) : OnMarkAcknowledgedAndGetNextOutgoingReplications{
        override suspend fun invoke(
            nodeId: Long,
            receivedAck: ReplicationReceivedAck,
            batchSize: Int
        ): List<DoorReplicationEntity> {
            return db.withDoorTransactionAsync {
                if(receivedAck.replicationUids.isNotEmpty())
                    db.acknowledgeReceivedReplications(nodeId, receivedAck.replicationUids)

                db.selectPendingOutgoingReplicationsByDestNodeId(nodeId, batchSize)
            }
        }
    }

    /**
     * The time (as per the other node) that we have most recently received all pending outgoing replications
     */
    @Volatile
    var lastReceiveCompleteTime: Long = 0


    private val fetchPendingReplicationsJob : Job

    private val sendPendingReplicationsJob: Job

    private val collectEventsJob: Job

    private val fetchNotifyChannel = Channel<Unit>(capacity = 1)

    private val sendNotifyChannel = Channel<Unit>(capacity = 1)

    private val batchSize = 1000

    private val remoteNodeId = CompletableDeferred<Long>()

    init {
        //Get the door node id of the remote endpoint.
        scope.launch {
            while(isActive && !remoteNodeId.isCompleted && !remoteNodeId.isCancelled) {
                try {
                    Napier.v(tag = DoorTag.LOG_TAG) {
                        "$logPrefix getRemoteNodeId : requesting node id of server"
                    }
                    val remoteNodeIdResponse = httpClient.get {
                        doorNodeIdHeader(localNodeId, localNodeAuth)
                        setRepoUrl(repoEndpointUrl, "$REPLICATION_PATH/nodeId")
                    }

                    val nodeIdHeaderVal = remoteNodeIdResponse.headers[DoorConstants.HEADER_NODE_ID]?.toLong()
                    Napier.v(tag = DoorTag.LOG_TAG) {
                        "$logPrefix getRemoteNodeId : got server node id: status=${remoteNodeIdResponse.status} $nodeIdHeaderVal"
                    }
                    if(nodeIdHeaderVal != null) {
                        _state.update { prev ->
                            prev.copy(initialized = true)
                        }

                        remoteNodeId.complete(nodeIdHeaderVal)
                    }else {
                        throw IllegalStateException("$logPrefix getRemoteNodeId : server did not provide node id")
                    }
                }catch(e: Exception) {
                    if(e !is CancellationException) {
                        Napier.w(tag = DoorTag.LOG_TAG, throwable = e) {
                            "$logPrefix getRemoteNodeId : exception getting remote node id"
                        }
                        delay(retryInterval.toLong())
                    }
                }
            }
        }

        fetchPendingReplicationsJob = scope.launch {
            runFetchLoop()
        }

        sendPendingReplicationsJob = scope.launch {
            runSendLoop()
        }

        collectEventsJob = scope.launch {
            val remoteNodeIdVal = remoteNodeId.await()
            launch {
                nodeEventManager.outgoingEvents.collect { events ->
                    if(events.any { it.toNode == remoteNodeIdVal && it.what == DoorMessage.WHAT_REPLICATION }) {
                        sendNotifyChannel.trySend(Unit)
                    }
                }
            }

            launch {
                nodeEventManager.incomingMessages.collect { message ->
                    if(message.fromNode == remoteNodeIdVal && message.what == DoorMessage.WHAT_REPLICATION) {
                        fetchNotifyChannel.trySend(Unit)
                    }
                }
            }

        }

        fetchNotifyChannel.trySend(Unit)
        sendNotifyChannel.trySend(Unit)
    }

    private suspend fun CoroutineScope.runSendLoop() {
        val remoteNodeIdVal = remoteNodeId.await()

        val outgoingReplicationsToAck = mutableListOf<Long>()
        while(isActive) {
            try {
                if(outgoingReplicationsToAck.isEmpty()) {
                    sendNotifyChannel.receive()
                }

                Napier.v(tag = DoorTag.LOG_TAG) {
                    "$logPrefix : runSendLoop : querying db to mark ${outgoingReplicationsToAck.size} entities as " +
                            "acknowledged by server and get next batch of replications to send"
                }
                val outgoingReplications = onMarkAcknowledgedAndGetNextOutgoingReplications(
                    nodeId = remoteNodeIdVal,
                    receivedAck = ReplicationReceivedAck(
                        replicationUids = outgoingReplicationsToAck,
                    ),
                    batchSize = batchSize
                )
                Napier.v(tag = DoorTag.LOG_TAG) {
                    "$logPrefix : runSendLoop : found ${outgoingReplications.size} pending outgoing replications " +
                            "to send"
                }
                outgoingReplicationsToAck.clear()

                if(outgoingReplications.isNotEmpty()) {
                    Napier.v(tag = DoorTag.LOG_TAG) {
                        "$logPrefix : runSendLoop : sending ${outgoingReplications.size} to server "
                    }
                    val replicationResponse = httpClient.post {
                        setRepoUrl(repoEndpointUrl, "$REPLICATION_PATH/message")
                        doorNodeIdHeader(localNodeId, localNodeAuth)
                        contentType(ContentType.Application.Json)
                        setBody(DoorMessage(
                            what = DoorMessage.WHAT_REPLICATION,
                            fromNode = localNodeId,
                            toNode = remoteNodeIdVal,
                            replications = outgoingReplications,
                        ))
                    }

                    val replicationReceivedAck: ReplicationReceivedAck = replicationResponse.body()

                    Napier.v(tag = DoorTag.LOG_TAG) {
                        "$logPrefix : runSendLoop : received reply from server status= ${replicationResponse.status} " +
                                " acknowledges ${replicationReceivedAck.replicationUids.size} entities"
                    }

                    outgoingReplicationsToAck.addAll(replicationReceivedAck.replicationUids)
                }
            }catch(e: Exception) {
                if(e !is CancellationException) {
                    Napier.d(
                        tag = DoorTag.LOG_TAG,
                        message =  { "$logPrefix exception sending outgoing replications" },
                        throwable = e
                    )
                    delay(retryInterval.toLong())
                }
            }
        }
    }

    private suspend fun CoroutineScope.runFetchLoop() {
        val acknowledgementsToSend = mutableListOf<Long>()

        while(isActive) {
            try {
                if(acknowledgementsToSend.isEmpty()) {
                    fetchNotifyChannel.receive() //wait for the invalidation signal if there is nothing we need to acknowledge
                }

                Napier.v(tag = DoorTag.LOG_TAG) {
                    "$logPrefix : runFetchLoop: acknowledging ${acknowledgementsToSend.size} entities received and " +
                            "request next batch of pending replications"
                }
                val entitiesReceivedResponse = httpClient.post {
                    doorNodeIdHeader(localNodeId, localNodeAuth)
                    setRepoUrl(repoEndpointUrl, "$REPLICATION_PATH/ackAndGetPendingReplications")
                    contentType(ContentType.Application.Json)
                    setBody(ReplicationReceivedAck(acknowledgementsToSend))
                }
                Napier.v(tag = DoorTag.LOG_TAG) {
                    "$logPrefix : runFetchLoop: received response status = ${entitiesReceivedResponse.status}"
                }
                acknowledgementsToSend.clear()

                if(entitiesReceivedResponse.status == HttpStatusCode.OK) {
                    val entitiesReceivedMessage: DoorMessage = entitiesReceivedResponse.body()
                    Napier.v(tag = DoorTag.LOG_TAG) {
                        "$logPrefix : runFetchLoop: received ${entitiesReceivedMessage.replications.size} replications incoming"
                    }
                    nodeEventManager.onIncomingMessageReceived(entitiesReceivedMessage)
                    acknowledgementsToSend.addAll(entitiesReceivedMessage.replications.map { it.orUid })
                    Napier.v(tag = DoorTag.LOG_TAG) {
                        "$logPrefix : runFetchLoop: delivered ${entitiesReceivedMessage.replications.size} replications to node event manager"
                    }
                }

                if(entitiesReceivedResponse.status == HttpStatusCode.NoContent) {
                    lastReceiveCompleteTime = entitiesReceivedResponse.responseTime.timestamp // to be 100% sure - would be better to timestamp the transaction
                }
            }catch(e: Exception) {
                if(e !is CancellationException) {
                    Napier.v(
                        tag= DoorTag.LOG_TAG,
                        message = { "DoorRepositoryReplicationClient: : runFetchLoop: exception (probably offline): $e"},
                        throwable = e
                    )
                    delay(retryInterval.toLong())
                }
            }
        }
    }


    fun close() {
        sendNotifyChannel.cancel()
        fetchPendingReplicationsJob.cancel()
        collectEventsJob.cancel()
        fetchNotifyChannel.close()
        sendNotifyChannel.close()
        remoteNodeId.cancel()
    }

    companion object {

        /**
         * The path from the database endpoint e.g. https://server.com/DbName/ to the replication endpoint
         */
        const val REPLICATION_PATH = "replication"
    }

}