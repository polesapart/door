package com.ustadmobile.door.jdbc

actual interface Statement {

    fun executeUpdate(sql: String): Int

    actual fun close()

    actual fun isClosed(): Boolean

    actual fun getConnection(): Connection

    actual fun getGeneratedKeys(): ResultSet

}