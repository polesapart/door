package com.ustadmobile.door.annotation

//Array literal is not supported on Javascript compilation
@Suppress("ReplaceArrayOfWithLiteral")
@Target(AnnotationTarget.CLASS)

/**
 * Trigger annotation that supports creating triggers on both SQLite and Postgres.
 *
 * ON SQLite this will simply create a trigger using the normal create trigger syntax.
 *
 * ON Postgres, it will create a trigger and a function. Postgres does not allow a when
 * clause to contain queries like SQLite does. Here a function will be generated that
 * will run the function.
 *
 * Triggers are used by the ReplicateEntity ReceiveView (see @ReplicateEntity) as part of handling replication.
 *
 * Migration: All triggers created by Door according to Trigger annotation will be dropped and new triggers will be
 * created after migration runs. Therefor migrations do not need to handle triggers and receive views, this will be
 * handled automatically.
 *
 * The following templates are available for convenience that can be used in SQL statements. NEW will refer to the
 * incoming replication data.
 *
 * %TABLE_AND_FIELD_NAMES% will be expanded to EntityName(field1, field2, field3...). Fields will be in the same order in
 * which they are declared on the entity.
 * %NEW_VALUES% - will be expanded to NEW.field1, NEW.field2, NEW.field3 . Fields will be in the same order in
 * which they are declared on the entity.
 * %NEW_LAST_MODIFIED_GREATER_THAN_EXISTING% - will be expanded to a where clause that will check if the new last
 * modified is greater than the existing entity in the local database, if present.
 * %NEW_ETAG_NOT_EQUAL_TO_EXISTING% - will be expanded to a where clause that will check that the new etag field is not
 * equal to the existing entity in the local database, if present
 *
 */
annotation class Trigger(

    /** Base name for the trigger. All triggers will be prefixed with NAME_PREFIX in the database itself  */
    val name: String,

    /** When the trigger runs e.g. BEFORE, AFTER, INSTEAD_OF */
    val order: Order,

    /** e.g. UPDATE, INSERT, DELETE */
    val events: Array<Event>,

    /**
     * The table that the trigger will be attached to. By default this is the table name of the
     * entity on which the trigger annotation is present. This should be set to the On.RECEIVEVIEW
     * for the trigger to work when data is received from a remote node as per the REPLICATION README.
     */
    val on: On = On.ENTITY,

    /**
     * A list of SQL statements that should run. These SQL statements may use NEW. and OLD. prefixes
     * depending on which event they are acting upon.
     */
    val sqlStatements: Array<String>,

    /**
     * A list of SQL statements that should run on Postgres. If present, then these will be used instead of sqlStatements
     * when running on Postgres.
     */
    val postgreSqlStatements: Array<String> = arrayOf(),

    /**
     * An SQL query that evaluates as a Boolean to determine if the sqlStatements should run.
     */
    val conditionSql: String = "",

    /**
     * An SQL query that evaluates as a Boolean to determine if the sql statements should run. If this is not a blank
     * string, it will be used instead of conditionSql on postgres.
     */
    val conditionSqlPostgres: String = "",

    ) {

    enum class Event(val sqlKeyWord: String) {
        INSERT("INSERT"), UPDATE("UPDATE"), DELETE("DELETE")
    }

    enum class Order(val sqlStr: String) {
        AFTER("AFTER"), BEFORE("BEFORE"), INSTEAD_OF("INSTEAD OF")
    }

    enum class On { ENTITY, RECEIVEVIEW }

    companion object {

        /**
         * Prefix on all triggers that are added by Door. This is used to find and delete triggers before database updates
         */
        const val NAME_PREFIX = "_d"

    }
}
