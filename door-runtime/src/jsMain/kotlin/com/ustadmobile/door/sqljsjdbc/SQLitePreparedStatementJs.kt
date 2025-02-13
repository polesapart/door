package com.ustadmobile.door.sqljsjdbc

import com.ustadmobile.door.jdbc.PreparedStatement
import com.ustadmobile.door.jdbc.ResultSet
import com.ustadmobile.door.jdbc.SQLException
import com.ustadmobile.door.jdbc.StatementConstantsKmp
import com.ustadmobile.door.jdbc.types.Date


class SQLitePreparedStatementJs(
    connection: SQLiteConnectionJs,
    private val sql: String,
    autoGeneratedKeys: Int = StatementConstantsKmp.NO_GENERATED_KEYS
): SQLiteStatementJs(connection, autoGeneratedKeys), PreparedStatement {

    private val params: Array<Any?> = arrayOf()

    override suspend fun executeQueryAsyncInt(): ResultSet {
        return connection.datasource.sendQuery(connection, sql, params)
    }

    override fun setBoolean(index: Int, value: Boolean) {
        params[index - 1] = if(value) 1 else 0
    }

    override fun setByte(index: Int, value: Byte) {
        params[index - 1] = value
    }

    override fun setShort(index: Int, value: Short) {
        params[index - 1] = value
    }

    override fun setString(index: Int, value: String?) {
        params[index - 1] = value
    }

    override fun setBytes(index: Int, value: ByteArray) {
        params[index - 1] = value
    }

    override fun setDate(index: Int, value: Date) {
        throw SQLException("This is currently not supported")
    }

    override fun setTime(index: Int, value: Any) {
        throw SQLException("This is currently not supported")
    }

    /**
     * By design, this will be used only if we are binding NULL values,
     * but there are some edge cases that might use this
     * i.e When arguments are represented as Array<Any>
     */
    override fun setObject(index: Int, value: Any?) {
        when(value){
            null -> {
                params[index - 1] = null
            }
            is Boolean -> setBoolean(index, value)
            is Byte -> setByte(index, value)
            is Short -> setShort(index, value)
            is String -> setString(index, value)
            is ByteArray -> setBytes(index, value)
            is Long -> setLong(index, value)
            is Int -> setInt(index, value)
        }
    }


    override fun setNull(parameterIndex: Int, sqlType: Int) {
        params[parameterIndex - 1] = null
    }

    override fun setArray(index: Int, array: com.ustadmobile.door.jdbc.Array) {
        throw SQLException("SQLite does not support arrays")
    }

    override fun setInt(index: Int, value: Int) {
        params[index - 1] = value
    }

    /**
     * JS doesn't support 64 bit, so inserting a long to a web worker won't pass it,
     * Instead we use eval to turn it into a bigint.
     *
     * @see <a href="https://www.sqlite.org/datatype3.html">Datatypes</a>
     */
    override fun setLong(index: Int, value: Long) {
        params[index - 1] = if(value in 0..1) eval("Number(${value})") else  eval("${value}n")
    }

    override fun setFloat(index: Int, value: Float) {
        params[index - 1] = value
    }

    override fun setDouble(index: Int, value: Double) {
        params[index - 1] = value
    }

    override fun setBigDecimal(index: Int, value: Any) {
        throw SQLException("This is currently not supported")
    }

    override fun executeUpdate(): Int {
        throw Exception("executeUpdate: (not-async) This can not be used on JS, only for JVM")
    }

    override suspend fun executeUpdateAsync(): Int {
        val generateKeys = autoGeneratedKeys == StatementConstantsKmp.RETURN_GENERATED_KEYS
        val result = connection.datasource.sendUpdate(connection, sql, params, generateKeys)
        lastGeneratedKey = result.autoGeneratedKey
        return 1
    }

    override fun executeQuery(): ResultSet {
        throw Exception("executeQuery (non-async): This can not be used on JS, only for JVM")
    }

    override fun close() {}
}