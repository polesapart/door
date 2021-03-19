package com.ustadmobile.door

import com.ustadmobile.door.jdbc.Connection
import com.ustadmobile.door.jdbc.PreparedStatement
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import wrappers.SQLiteConnectionJs
import wrappers.SQLiteDatasourceJs

actual abstract class DoorDatabase actual constructor() {

    internal lateinit var dataSource: SQLiteDatasourceJs

    val initCompletable = CompletableDeferred<Boolean>()

    fun openConnection() : Connection {
        return SQLiteConnectionJs(dataSource)
    }

    private suspend fun awaitReady() {
        initCompletable.await()
    }

    open suspend fun createAllTables() {
        //Generated code will actually run this
    }

    suspend fun doSomeQuery(sql: String) {
        awaitReady()

        //do the query
    }


    actual abstract fun clearAllTables()

    actual open fun runInTransaction(runnable: Runnable) {

    }

}