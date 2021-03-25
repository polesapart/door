package com.ustadmobile.door

import com.ustadmobile.door.jdbc.Connection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import wrappers.SQLiteDatasourceJs

actual abstract class DoorDatabase actual constructor() {

    internal lateinit var dataSource: SQLiteDatasourceJs

    internal lateinit var webWorkerPath: String

    abstract val dbVersion: Int

    val initCompletable = CompletableDeferred<Boolean>()

    suspend fun openConnection() : Connection {
        awaitReady()
        return dataSource.getConnection()
    }

    private suspend fun awaitReady() {
        initCompletable.await()
    }

    open suspend fun createAllTables() {
        //Generated code will actually run this
    }

    actual abstract fun clearAllTables()

    actual open fun runInTransaction(runnable: Runnable) {

    }

}