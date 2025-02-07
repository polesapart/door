package com.ustadmobile.door.sqljsjdbc

import com.ustadmobile.door.ext.DoorTag
import com.ustadmobile.door.jdbc.ResultSet
import com.ustadmobile.door.jdbc.SQLException
import com.ustadmobile.door.jdbc.Statement
import com.ustadmobile.door.jdbc.StatementConstantsKmp
import io.github.aakira.napier.Napier

open class SQLiteStatementJs(
    protected val connection: SQLiteConnectionJs,
    val autoGeneratedKeys: Int = StatementConstantsKmp.NO_GENERATED_KEYS
) : Statement {

    private var closed: Boolean = false

    //This is set when running executeUpdateAsync
    protected var lastGeneratedKey: ResultSet? = null

    protected var queryTimeoutSecs: Int = 0

    override fun executeUpdate(sql: String): Int {
        throw SQLException("Synchronous SQL not supported!")
    }

    override suspend fun executeUpdateAsyncJs(sql: String): Int {
        Napier.v("SqliteJs: updateAsyncJs: $sql\n", tag = DoorTag.LOG_TAG)
        return connection.datasource.sendUpdate(connection, sql, emptyArray()).numRowsChanged
    }

    override fun close() {
        //nothing to do really
        closed = true
    }

    override fun isClosed() = closed

    override fun getConnection() = connection

    override fun getGeneratedKeys(): ResultSet {
        return lastGeneratedKey ?: SQLiteResultSet(arrayOf())
    }

    override fun setQueryTimeout(seconds: Int) {
        queryTimeoutSecs = seconds
    }
}