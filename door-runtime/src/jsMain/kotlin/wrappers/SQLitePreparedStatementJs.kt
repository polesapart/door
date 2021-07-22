package wrappers

import com.ustadmobile.door.jdbc.PreparedStatement
import com.ustadmobile.door.jdbc.ResultSet
import com.ustadmobile.door.jdbc.SQLException
import com.ustadmobile.door.jdbc.StatementConstantsKmp
import com.ustadmobile.door.jdbc.types.Date
import kotlin.js.json

class SQLitePreparedStatementJs(
    connection: SQLiteConnectionJs,
    private val sql: String,
    autoGeneratedKeys: Int = StatementConstantsKmp.NO_GENERATED_KEYS
): SqliteStatementJs(connection, autoGeneratedKeys), PreparedStatement {

    internal var params: Array<Any?>? = arrayOf()

    override suspend fun executeQueryAsyncInt(): ResultSet {
        return connection.datasource.sendQuery(sql, params)
    }

    override fun setBoolean(index: Int, value: Boolean) {
        addParam(index, value)
    }

    override fun setByte(index: Int, value: Byte) {
        addParam(index, value)
    }

    override fun setShort(index: Int, value: Short) {
        addParam(index, value)
    }

    override fun setString(index: Int, value: String?) {
        addParam(index, value)
    }

    override fun setBytes(index: Int, value: ByteArray) {
        addParam(index, value)
    }

    override fun setDate(index: Int, value: Date) {
        addParam(index, value)
    }

    override fun setTime(index: Int, value: Any) {
        addParam(index, value)
    }

    override fun setObject(index: Int, value: Any?) {
        addParam(index, value)
    }

    override fun setArray(index: Int, array: com.ustadmobile.door.jdbc.Array) {
        throw SQLException("SQLite does not support arrays")
    }

    override fun setInt(index: Int, value: Int) {
        addParam(index, value)
    }

    override fun setLong(index: Int, value: Long) {
        addParam(index, value)
    }

    override fun setFloat(index: Int, value: Float) {
        addParam(index, value)
    }

    override fun setDouble(index: Int, value: Double) {
        addParam(index, value)
    }

    override fun setBigDecimal(index: Int, value: Any) {
        addParam(index, value)
    }

    override fun executeUpdate(): Int {
        throw Exception("This can not be used on JS, only for JVM")
    }

    override suspend fun executeUpdateAsync(): Int {
        return connection.datasource.sendUpdate(sql, params)
    }

    override fun executeQuery(): ResultSet {
        throw Exception("This can not be used on JS, only for JVM")
    }

    private fun addParam(index:Int, value: Any?){
        val mParams = params
        if (value != null && mParams != null && index > 0) {
            mParams[index - 1] = value
        }
    }

    override fun close() {
        params = null
    }
}