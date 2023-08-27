package com.ustadmobile.door.roomjdbc

import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLWarning
import java.sql.Statement

open class StatementRoomJdbc(
    internal val roomConnection: ConnectionRoomJdbc
): Statement {

    override fun <T : Any?> unwrap(iface: Class<T>?): T {
        TODO("Not yet implemented")
    }

    override fun isWrapperFor(iface: Class<*>?): Boolean {
        TODO("Not yet implemented")
    }

    override fun close() {

    }

    override fun executeQuery(sql: String): ResultSet {
        return ResultSetRoomJdbc(roomConnection.roomDb.query(sql, arrayOf()), this)
    }

    override fun executeUpdate(sql: String): Int {
        val compiledStmt = roomConnection.roomDb.openHelper.writableDatabase.compileStatement(sql)
        val result = compiledStmt.executeUpdateDelete()
        compiledStmt.close()
        return result
    }

    override fun executeUpdate(sql: String, autoGeneratedKeys: Int): Int {
        TODO("Not yet implemented")
    }

    override fun executeUpdate(sql: String?, columnIndexes: IntArray?): Int {
        TODO("Not yet implemented")
    }

    override fun executeUpdate(sql: String?, columnNames: Array<out String>?): Int {
        TODO("Not yet implemented")
    }

    override fun getMaxFieldSize(): Int {
        TODO("Not yet implemented")
    }

    override fun setMaxFieldSize(max: Int) {
        TODO("Not yet implemented")
    }

    override fun getMaxRows(): Int {
        TODO("Not yet implemented")
    }

    override fun setMaxRows(max: Int) {
        TODO("Not yet implemented")
    }

    override fun setEscapeProcessing(enable: Boolean) {
        TODO("Not yet implemented")
    }

    override fun getQueryTimeout(): Int {
        TODO("Not yet implemented")
    }

    override fun setQueryTimeout(seconds: Int) {
        TODO("Not yet implemented")
    }

    override fun cancel() {
        TODO("Not yet implemented")
    }

    override fun getWarnings(): SQLWarning {
        TODO("Not yet implemented")
    }

    override fun clearWarnings() {
        TODO("Not yet implemented")
    }

    override fun setCursorName(name: String?) {
        TODO("Not yet implemented")
    }

    override fun execute(sql: String?): Boolean {
        TODO("Not yet implemented")
    }

    override fun execute(sql: String?, autoGeneratedKeys: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun execute(sql: String?, columnIndexes: IntArray?): Boolean {
        TODO("Not yet implemented")
    }

    override fun execute(sql: String?, columnNames: Array<out String>?): Boolean {
        TODO("Not yet implemented")
    }

    override fun getResultSet(): ResultSet {
        TODO("Not yet implemented")
    }

    override fun getUpdateCount(): Int {
        TODO("Not yet implemented")
    }

    override fun getMoreResults(): Boolean {
        TODO("Not yet implemented")
    }

    override fun getMoreResults(current: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun setFetchDirection(direction: Int) {
        TODO("Not yet implemented")
    }

    override fun getFetchDirection(): Int {
        TODO("Not yet implemented")
    }

    override fun setFetchSize(rows: Int) {
        TODO("Not yet implemented")
    }

    override fun getFetchSize(): Int {
        TODO("Not yet implemented")
    }

    override fun getResultSetConcurrency(): Int {
        TODO("Not yet implemented")
    }

    override fun getResultSetType(): Int {
        TODO("Not yet implemented")
    }

    override fun addBatch(sql: String?) {
        TODO("Not yet implemented")
    }

    override fun clearBatch() {
        TODO("Not yet implemented")
    }

    override fun executeBatch(): IntArray {
        TODO("Not yet implemented")
    }

    override fun getConnection(): Connection {
        return roomConnection
    }

    override fun getGeneratedKeys(): ResultSet {
        TODO("Not yet implemented")
    }

    override fun getResultSetHoldability(): Int {
        TODO("Not yet implemented")
    }

    override fun isClosed(): Boolean {
        return false
    }

    override fun setPoolable(poolable: Boolean) {

    }

    override fun isPoolable(): Boolean {
        return true
    }



}