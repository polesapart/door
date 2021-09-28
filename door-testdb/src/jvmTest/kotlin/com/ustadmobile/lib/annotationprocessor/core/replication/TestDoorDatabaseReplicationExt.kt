package com.ustadmobile.lib.annotationprocessor.core.replication

import com.ustadmobile.door.DatabaseBuilder
import com.ustadmobile.door.entities.DoorNode
import com.ustadmobile.door.ext.doorDatabaseMetadata
import com.ustadmobile.door.replication.ReplicationEntityMetaData.Companion.KEY_PRIMARY_KEY
import com.ustadmobile.door.replication.ReplicationEntityMetaData.Companion.KEY_VERSION_ID
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import repdb.RepDb
import repdb.RepEntity
import com.ustadmobile.door.replication.findPendingReplicationTrackers
import com.ustadmobile.door.replication.checkPendingReplicationTrackers
import com.ustadmobile.door.util.systemTimeInMillis
import kotlinx.serialization.json.*
import org.junit.Assert

class TestDoorDatabaseReplicationExt {


    lateinit var db: RepDb

    @Before
    fun setup() {
        db = DatabaseBuilder.databaseBuilder(Any(), RepDb::class, "RepDb").build()
            .apply {
                clearAllTables()
            }
    }

    @Test
    fun givenPendingReplicationsForNode_whenFindPendingReplicationTrackersCalled_thenShouldReturnJsonList() {
        //Insert door node for known client
        db.repDao.insertDoorNode(DoorNode().apply {
            nodeId = 42
            auth = "secret"
        })

        //insert an entity into the rep entity table
        val repEntityUid = db.repDao.insert(RepEntity().apply {
            reNumField = 57
            reString = "magic plus 15"
        })

        //Make it generate the replication tracker for the given DoorNode
        runBlocking { db.repDao.updateReplicationTrackers() }

        val pendingTrackers = runBlocking {
            db.findPendingReplicationTrackers(RepDb::class.doorDatabaseMetadata(),
                42, RepEntity.TABLE_ID)
        }

        Assert.assertEquals("Found one pending tracker", 1, pendingTrackers.size)
        val firstPrimaryKey = (pendingTrackers[0] as JsonObject).get("primaryKey") as JsonPrimitive
        Assert.assertEquals("Pending tracker matches expected uid",repEntityUid,
            firstPrimaryKey.long)
    }

    @Test
    fun givenPendingReplicationTrackersForNode_whenCheckPendingReplicationTrackersCalled_thenShouldReturnThoseAlreadyUpToDate() {
        //Insert door node for known client
        db.repDao.insertDoorNode(DoorNode().apply {
            nodeId = 42
            auth = "secret"
        })

        val time1 = systemTimeInMillis()

        val time2 = time1 + 1000

        //insert an entity into the rep entity table
        val repEntityUid1 = db.repDao.insert(RepEntity().apply {
            reNumField = 57
            reString = "magic plus 15"
            reLastChangeTime = time1
        })

        val repEntityUid2 = db.repDao.insert(RepEntity().apply {
            reNumField = 57
            reString = "magic plus 15"
            reLastChangeTime = time2
        })

        runBlocking {
            db.repDao.updateReplicationTrackers()
        }

        val pendingReplicationJson = JsonArray(listOf(
            JsonObject(mapOf(KEY_PRIMARY_KEY to JsonPrimitive(repEntityUid1),
                KEY_VERSION_ID to JsonPrimitive(time1)
            )),
            JsonObject(mapOf(KEY_PRIMARY_KEY to JsonPrimitive(repEntityUid2),
                KEY_VERSION_ID to JsonPrimitive(time1 - 1000)))
        ))

        val alreadyUpdatedResult = runBlocking {
            db.checkPendingReplicationTrackers(RepDb::class,
                RepDb::class.doorDatabaseMetadata(),
                pendingReplicationJson, RepEntity.TABLE_ID)
        }

        Assert.assertEquals("Found one entity already up to date", 1, alreadyUpdatedResult.size)
        Assert.assertEquals("Entity pk is first item", repEntityUid1,
            (alreadyUpdatedResult[0] as JsonObject).get(KEY_PRIMARY_KEY)?.jsonPrimitive?.long)
    }

}