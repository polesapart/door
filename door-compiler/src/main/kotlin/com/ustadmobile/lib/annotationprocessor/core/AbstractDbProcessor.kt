package com.ustadmobile.lib.annotationprocessor.core

import androidx.room.*
import com.squareup.kotlinpoet.*
import com.ustadmobile.door.jdbc.*
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.tools.Diagnostic
import java.io.File
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.element.*
import com.ustadmobile.door.annotation.*
import io.ktor.http.HttpStatusCode
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.ustadmobile.door.*
import com.ustadmobile.door.entities.ChangeLog
import com.ustadmobile.door.ext.minifySql
import com.ustadmobile.lib.annotationprocessor.core.ext.toSql
import io.github.aakira.napier.Napier
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.statement.HttpStatement
import io.ktor.http.Headers

//here in a comment because it sometimes gets removed by 'optimization of parameters'
// import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy


val SQL_NUMERIC_TYPES = listOf(BYTE, SHORT, INT, LONG, FLOAT, DOUBLE)

val PARAM_NAME_OFFSET = "offset"

val PARAM_NAME_LIMIT = "limit"

fun defaultSqlQueryVal(typeName: TypeName) = if(typeName in SQL_NUMERIC_TYPES) {
    "0"
}else if(typeName == BOOLEAN){
    "false"
}else {
    "null"
}


/**
 * Determine if the given
 */
@Deprecated("Use TypeNameExt.isHttpQueryQueryParam instead")
internal fun isQueryParam(typeName: TypeName) = typeName in QUERY_SINGULAR_TYPES
        || (typeName is ParameterizedTypeName
        && (typeName.rawType == List::class.asClassName() && typeName.typeArguments[0] in QUERY_SINGULAR_TYPES))


/**
 * Given a list of parameters, get a list of those that get not pass as query parameters over http.
 * This is any parameters except primitive types, strings, or lists and arrays thereof
 *
 * @param params List of parameters to check for which ones cannot be passed as query parameters
 * @return List of parameters from the input list which cannot be passed as http query parameters
 */
internal fun getHttpBodyParams(params: List<ParameterSpec>) = params.filter {
    !isQueryParam(it.type) && !isContinuationParam(it.type)
}


/**
 * Given a list of http parameters, find the first, if any, which should be sent as the http body
 */
internal fun getRequestBodyParam(params: List<ParameterSpec>) = params.firstOrNull {
    !isQueryParam(it.type) && !isContinuationParam(it.type)
}


internal val CLIENT_GET_MEMBER_NAME = MemberName("io.ktor.client.request", "get")

internal val CLIENT_POST_MEMBER_NAME = MemberName("io.ktor.client.request", "post")

internal val BODY_MEMBER_NAME = MemberName("io.ktor.client.call", "body")

internal val BODY_OR_NULL_MEMBER_NAME = MemberName("com.ustadmobile.door.ext", "bodyOrNull")

internal val CLIENT_GET_NULLABLE_MEMBER_NAME = MemberName("com.ustadmobile.door.ext", "getOrNull")

internal val CLIENT_POST_NULLABLE_MEMBER_NAME = MemberName("com.ustadmobile.door.ext", "postOrNull")

internal val CLIENT_RECEIVE_MEMBER_NAME = MemberName("io.ktor.client.call", "receive")

internal val CLIENT_PARAMETER_MEMBER_NAME = MemberName("io.ktor.client.request", "parameter")

internal val CLIENT_HTTPSTMT_RECEIVE_MEMBER_NAME = MemberName("io.ktor.client.call", "receive")

/**
 * Generates a CodeBlock that will make KTOR HTTP Client Request for a DAO method. It will set
 * the correct URL (e.g. endpoint/DatabaseName/DaoName/methodName and parameters (including the request body
 * if required). It will decide between using get or post based on the parameters.
 *
 * @param httpEndpointVarName the variable name that contains the base http endpoint to start with for the url
 * @param dbPathVarName the variable name that contains the name of the database
 * @param daoName the DAO name (e.g. simple class name of the DAO class)
 * @param methodName the name of the method that is being queried
 * @param httpStatementVarName the variable name that will be added to the codeblock that will contain
 * the http statement object
 * @param httpResponseHeadersVarName the variable name that will be used to capture the http headers
 * received from the response (after the .execute call is done)
 * @param httpResultType the type of response expected from the other end (e.g. the result type of
 * the method)
 * @param params a list of the parameters (e.g. from the method signature) that need to be sent
 * @param useKotlinxListSerialization if true, the generated code will use the Kotlinx Json object
 * to serialize and deserialize lists. This is because the Javascript client (using Kotlinx serialization)
 * will not automatically handle .receive<List<Entity>>
 * @param kotlinxSerializationJsonVarName if useKotlinxListSerialization, thne this is the variable
 * name that will be used to access the Json object to serialize or deserialize.
 * entities. If false, we will use the local change sequence number.
 *
 * REPLACE WITH CodeBlockExt.addKtorRequestForFunction
 */
internal fun generateKtorRequestCodeBlockForMethod(httpEndpointVarName: String = "_endpoint",
                                                   dbPathVarName: String,
                                                   daoName: String,
                                                   methodName: String,
                                                   httpStatementVarName: String = "_httpStatement",
                                                   httpResponseHeadersVarName: String = "_httpResponseHeaders",
                                                   httpResultVarName: String = "_httpResult",
                                                   requestBuilderCodeBlock: CodeBlock = CodeBlock.of(""),
                                                   httpResultType: TypeName,
                                                   params: List<ParameterSpec>,
                                                   useKotlinxListSerialization: Boolean = false,
                                                   kotlinxSerializationJsonVarName: String = "",
                                                   useMultipartPartsVarName: String? = null,
                                                   addVersionAndNodeIdArg: String? = "_repo"): CodeBlock {

    //Begin creation of the HttpStatement call that will set the URL, parameters, etc.
    val nonQueryParams = getHttpBodyParams(params)
    val codeBlock = CodeBlock.builder()
            .beginControlFlow("val $httpStatementVarName = _httpClient.%M<%T>",
                    if(nonQueryParams.isEmpty()) CLIENT_GET_MEMBER_NAME else CLIENT_POST_MEMBER_NAME,
                    HttpStatement::class)
            .beginControlFlow("url")
            .add("%M($httpEndpointVarName)\n", MemberName("io.ktor.http", "takeFrom"))
            .add("encodedPath = \"\${encodedPath}\${$dbPathVarName}/%L/%L\"\n", daoName, methodName)
            .endControlFlow()
            .add(requestBuilderCodeBlock)



    codeBlock.takeIf { addVersionAndNodeIdArg != null }?.add("%M($addVersionAndNodeIdArg)\n",
            MemberName("com.ustadmobile.door.ext", "doorNodeAndVersionHeaders"))

    params.filter { isQueryParam(it.type) }.forEach {
        val paramType = it.type
        val isList = paramType is ParameterizedTypeName && paramType.rawType == List::class.asClassName()

        val paramsCodeblock = CodeBlock.builder()
        var paramVarName = it.name
        if(isList) {
            paramsCodeblock.add("${it.name}.forEach { ")
            paramVarName = "it"
            if(paramType != String::class.asClassName()) {
                paramVarName += ".toString()"
            }
        }

        paramsCodeblock.add("%M(%S, $paramVarName)\n",
                MemberName("io.ktor.client.request", "parameter"),
                it.name)
        if(isList) {
            paramsCodeblock.add("} ")
        }
        paramsCodeblock.add("\n")
        codeBlock.addWithNullCheckIfNeeded(it.name, it.type,
                paramsCodeblock.build())
    }

    val requestBodyParam = getRequestBodyParam(params)

    if(requestBodyParam != null) {
        val requestBodyParamType = requestBodyParam.type

        val writeBodyCodeBlock = if(useMultipartPartsVarName != null) {
            CodeBlock.of("body = %T($useMultipartPartsVarName)\n",
                     MultiPartFormDataContent::class)
        }else if(useKotlinxListSerialization && requestBodyParamType is ParameterizedTypeName
                && requestBodyParamType.rawType == List::class.asClassName()) {
            val entityComponentType = resolveEntityFromResultType(requestBodyParamType).javaToKotlinType()
            val serializerFnCodeBlock = if(entityComponentType in QUERY_SINGULAR_TYPES) {
                CodeBlock.of("%M()", MemberName("kotlinx.serialization", "serializer"))
            }else {
                CodeBlock.of("serializer()")
            }
            CodeBlock.of("body = %T(_json.stringify(%T.%L.%M, ${requestBodyParam.name}), %T.Application.Json.%M())\n",
                TextContent::class, entityComponentType,
                    serializerFnCodeBlock,
                    MemberName("kotlinx.serialization.builtins", "list"),
                    ContentType::class,
                    MemberName("com.ustadmobile.door.ext", "withUtf8Charset"))
        }else {
            CodeBlock.of("body = %M().write(${requestBodyParam.name}, %T.Application.Json.%M())\n",
                    MemberName("io.ktor.client.plugins.json", "defaultSerializer"),
                    ContentType::class, MemberName("com.ustadmobile.door.ext", "withUtf8Charset"))
        }

        codeBlock.addWithNullCheckIfNeeded(requestBodyParam.name, requestBodyParam.type,
                writeBodyCodeBlock)
    }

    codeBlock.endControlFlow()

    //End creation of the HttpStatement

    codeBlock.add("var $httpResponseHeadersVarName: %T? = null\n", Headers::class)

    val receiveCodeBlock = if(useKotlinxListSerialization && httpResultType is ParameterizedTypeName
            && httpResultType.rawType == List::class.asClassName() ) {
        val serializerFnCodeBlock = if(httpResultType.typeArguments[0].javaToKotlinType() in QUERY_SINGULAR_TYPES) {
            CodeBlock.of("%M()", MemberName("kotlinx.serialization", "serializer"))
        }else {
            CodeBlock.of("serializer()")
        }
        CodeBlock.of("$kotlinxSerializationJsonVarName.parse(%T.%L.%M, $httpStatementVarName.%M<String>())\n",
                httpResultType.typeArguments[0], serializerFnCodeBlock,
                MemberName("kotlinx.serialization.builtins", "list"),
                CLIENT_RECEIVE_MEMBER_NAME)
    }else{
        CodeBlock.Builder().beginControlFlow("$httpStatementVarName.execute")
                .add(" response ->\n")
                .add("$httpResponseHeadersVarName = response.headers\n")
                .apply { takeIf { httpResultType.isNullable }
                        ?.beginControlFlow(
                            "if(response.status == %T.NoContent)", HttpStatusCode::class)
                            ?.add("null\n")
                            ?.nextControlFlow("else")
                }
                .add("response.%M<%T>()\n", CLIENT_HTTPSTMT_RECEIVE_MEMBER_NAME, httpResultType)
                .apply { takeIf { httpResultType.isNullable }?.endControlFlow() }
                .endControlFlow()
                .build()

    }

    codeBlock.add("val $httpResultVarName = ")
    codeBlock.add(receiveCodeBlock)


    return codeBlock.build()
}


/**
 * Will add the given codeblock, and surround it with if(varName != null) if the given typename
 * is nullable
 */
fun CodeBlock.Builder.addWithNullCheckIfNeeded(varName: String, typeName: TypeName,
                                               codeBlock: CodeBlock): CodeBlock.Builder {
    if(typeName.isNullable)
        beginControlFlow("if($varName != null)")

    add(codeBlock)

    if(typeName.isNullable)
        endControlFlow()

    return this
}

fun getEntityPrimaryKey(entityEl: TypeElement) = entityEl.enclosedElements
        .firstOrNull { it.kind == ElementKind.FIELD && it.getAnnotation(PrimaryKey::class.java) != null}


/**
 * The parent class of all processors that generate implementations for door. Child processors
 * should implement process.
 */
abstract class AbstractDbProcessor: AbstractProcessor() {

    protected lateinit var messager: Messager

    protected var dbConnection: Connection? = null

    /**
     * When we generate the code for a Query annotation function that performs an update or delete,
     * we use this so that we can match the case of the table name. This will be setup by
     * AnnotationProcessorWrapper calling processDb.
     */
    protected var allKnownEntityNames = mutableListOf<String>()

    /**
     * Provides a map that can be used to find the TypeElement for a given table name. This will be
     * setup by AnnotationProcessorWrapper calling processDb.
     */
    protected var allKnownEntityTypesMap = mutableMapOf<String, TypeElement>()

    /**
     * Initiates internal info about the databases that are being processed, then calls the main
     * process function. This is called by AnnotationProcessorWrapper
     *
     * @param annotations as per the main annotation processor process method
     * @param roundEnv as per the main annotation processor process method
     * @param dbConnection a JDBC Connection object that can be used to run queries
     * @param allKnownEntityNames as per the allKnownEntityNames property
     * @param allKnownEntityTypesMap as per the allKnownEntityTypesMap property
     */
    fun processDb(annotations: MutableSet<out TypeElement>,
                           roundEnv: RoundEnvironment,
                           dbConnection: Connection,
                           allKnownEntityNames: MutableList<String>,
                           allKnownEntityTypesMap: MutableMap<String, TypeElement>)  : Boolean {
        this.allKnownEntityNames = allKnownEntityNames
        this.allKnownEntityTypesMap = allKnownEntityTypesMap
        this.dbConnection = dbConnection
        return process(annotations, roundEnv)
    }

    override fun init(p0: ProcessingEnvironment) {
        super.init(p0)
        messager = p0.messager
    }

    /**
     * Add triggers that will insert into the ChangeLog table
     */
    protected fun CodeBlock.Builder.addReplicateEntityChangeLogTrigger(
        entityType: TypeElement,
        sqlListVar: String,
        dbProductType: Int,
    ) : CodeBlock.Builder{
        val replicateEntity = entityType.getAnnotation(ReplicateEntity::class.java)
        val primaryKeyEl = entityType.entityPrimaryKey
            ?: throw IllegalArgumentException("addReplicateEntityChangeLogTrigger ${entityType.qualifiedName} has NO PRIMARY KEY!")

        data class TriggerParams(val opName: String, val prefix: String, val opCode: Int) {
            val opPrefix = opName.lowercase().substring(0, 3)
        }

        if(dbProductType == DoorDbType.SQLITE) {
            val triggerParams = listOf(
                TriggerParams("INSERT", "NEW", ChangeLog.CHANGE_UPSERT),
                TriggerParams("UPDATE", "NEW", ChangeLog.CHANGE_UPSERT),
                TriggerParams("DELETE", "OLD", ChangeLog.CHANGE_DELETE)
            )

            triggerParams.forEach { params ->
                /*
                Note: REPLACE INTO etc. does not work because the conflict policy will be determined by the statement
                triggering this as per https://sqlite.org/lang_createtrigger.html Section 2.
                "An ON CONFLICT clause may be specified as part of an UPDATE or INSERT action within the body of the
                trigger. However if an ON CONFLICT clause is specified as part of the statement causing the trigger to
                 fire, then conflict handling policy of the outer statement is used instead."
                 */
                add("$sqlListVar += %S\n",
                    """
                CREATE TRIGGER ch_${params.opPrefix}_${replicateEntity.tableId}
                       AFTER ${params.opName} ON ${entityType.entityTableName}
                BEGIN
                       INSERT INTO ChangeLog(chTableId, chEntityPk, chType)
                       SELECT ${replicateEntity.tableId} AS chTableId, 
                              ${params.prefix}.${primaryKeyEl.simpleName} AS chEntityPk, 
                              ${params.opCode} AS chType
                        WHERE NOT EXISTS(
                              SELECT chTableId 
                                FROM ChangeLog 
                               WHERE chTableId = ${replicateEntity.tableId}
                                 AND chEntityPk = ${params.prefix}.${primaryKeyEl.simpleName}); 
                END
                """.minifySql())
            }
        }else {
            val triggerParams = listOf(
                TriggerParams("UPDATE OR INSERT", "NEW", ChangeLog.CHANGE_UPSERT),
                TriggerParams("DELETE", "OLD", ChangeLog.CHANGE_DELETE))
            triggerParams.forEach { params ->
                add("$sqlListVar += %S\n",
                    """
               CREATE OR REPLACE FUNCTION 
               ch_${params.opPrefix}_${replicateEntity.tableId}_fn() RETURNS TRIGGER AS $$
               BEGIN
               INSERT INTO ChangeLog(chTableId, chEntityPk, chType)
                       VALUES (${replicateEntity.tableId}, ${params.prefix}.${primaryKeyEl.simpleName}, ${params.opCode})
               ON CONFLICT(chTableId, chEntityPk) DO UPDATE
                       SET chType = ${params.opCode};
               RETURN NULL;
               END $$
               LANGUAGE plpgsql         
            """.minifySql())
                add("$sqlListVar += %S\n",
                    """
            CREATE TRIGGER ch_${params.opPrefix}_${replicateEntity.tableId}_trig 
                   AFTER ${params.opName} ON ${entityType.entityTableName}
                   FOR EACH ROW
                   EXECUTE PROCEDURE ch_${params.opPrefix}_${replicateEntity.tableId}_fn();
            """.minifySql())
            }


        }

        return this
    }

    /**
     * Add a ReceiveView for the given EntityTypeElement.
     */
    protected fun CodeBlock.Builder.addCreateReceiveView(
        entityTypeEl: TypeElement,
        sqlListVar: String
    ): CodeBlock.Builder {
        val trkrEl = entityTypeEl.getReplicationTracker(processingEnv)
        val receiveViewAnn = entityTypeEl.getAnnotation(ReplicateReceiveView::class.java)
        val viewName = receiveViewAnn?.name ?: "${entityTypeEl.entityTableName}$SUFFIX_DEFAULT_RECEIVEVIEW"
        val sql = receiveViewAnn?.value ?: """
            SELECT ${entityTypeEl.simpleName}.*, ${trkrEl.entityTableName}.*
              FROM ${entityTypeEl.simpleName}
                   LEFT JOIN ${trkrEl.simpleName} ON ${trkrEl.entityTableName}.${trkrEl.replicationTrackerForeignKey.simpleName} = 
                        ${entityTypeEl.entityTableName}.${entityTypeEl.entityPrimaryKey?.simpleName}
        """.minifySql()
        add("$sqlListVar += %S\n", "CREATE VIEW $viewName AS $sql")
        return this
    }



    protected fun CodeBlock.Builder.addCreateTriggersCode(
        entityType: TypeElement,
        stmtListVar: String,
        dbProductType: Int
    ): CodeBlock.Builder {
        Napier.d("Door Wrapper: addCreateTriggersCode ${entityType.simpleName}")
        entityType.getAnnotationsByType(Triggers::class.java).firstOrNull()?.value?.forEach { trigger ->
            trigger.toSql(entityType, dbProductType).forEach { sqlStr ->
                add("$stmtListVar += %S\n", sqlStr)
            }
        }

        return this
    }


    /**
     * Generate a codeblock with the JDBC code required to perform a query and return the given
     * result type
     *
     * @param returnType the return type of the query
     * @param queryVars: map of String (variable name) to the type of parameter. Used to set
     * parameters on the preparedstatement
     * @param querySql The actual query SQL itself (e.g. as per the Query annotation)
     * @param enclosing TypeElement (e.g the DAO) in which it is enclosed, used to resolve parameter types
     * @param method The method that this implementation is being generated for. Used for error reporting purposes
     * @param resultVarName The variable name for the result of the query (this will be as per resultType,
     * with any wrapping (e.g. LiveData) removed.
     */
    //TODO: Check for invalid combos. Cannot have querySql and rawQueryVarName as null. Cannot have rawquery doing update
    fun CodeBlock.Builder.addJdbcQueryCode(
        returnType: TypeName,
        queryVars: Map<String, TypeName>,
        querySql: String?,
        enclosing: TypeElement?,
        method: ExecutableElement?,
        resultVarName: String = "_result",
        rawQueryVarName: String? = null,
        suspended: Boolean = false,
        querySqlPostgres: String? = null
    ): CodeBlock.Builder {
        // The result, with any wrapper (e.g. LiveData or DataSource.Factory) removed
        val resultType = resolveQueryResultType(returnType)

        // The individual entity type e.g. Entity or String etc
        val entityType = resolveEntityFromResultType(resultType)

        val entityTypeElement = if(entityType is ClassName) {
            processingEnv.elementUtils.getTypeElement(entityType.canonicalName)
        } else {
            null
        }

        val resultEntityField = if(entityTypeElement != null) {
            ResultEntityField(null, "_entity", entityTypeElement.asClassName(),
                    entityTypeElement, processingEnv)
        }else {
            null
        }

        val isUpdateOrDelete = querySql != null && querySql.isSQLAModifyingQuery()


        val preparedStatementSql = querySql?.replaceQueryNamedParamsWithQuestionMarks()

        if(preparedStatementSql != null) {
            val namedParams = preparedStatementSql.getSqlQueryNamedParameters()

            val missingParams = namedParams.filter { it !in queryVars.keys }
            if(missingParams.isNotEmpty()) {
                messager.printMessage(Diagnostic.Kind.ERROR,
                        "On ${enclosing?.qualifiedName}.${method?.simpleName} has the following named " +
                        "params in query that are not parameters of the function: ${missingParams.joinToString()}")
            }
        }

        val preparedStatementSqlPostgres = querySqlPostgres?.replaceQueryNamedParamsWithQuestionMarks()
            ?: querySql?.replaceQueryNamedParamsWithQuestionMarks()?.sqlToPostgresSql()


        if(resultType != UNIT)
            add("var $resultVarName = ${defaultVal(resultType)}\n")

        if(rawQueryVarName == null) {
            add("val _stmtConfig = %T(%S ", PreparedStatementConfig::class, preparedStatementSql)
            if(queryVars.any { it.value.javaToKotlinType().isListOrArray() })
                add(",hasListParams = true")

            if(preparedStatementSql != preparedStatementSqlPostgres)
                add(", postgreSql = %S", preparedStatementSqlPostgres)

            add(")\n")

        }else {
            add("val _stmtConfig = %T($rawQueryVarName.getSql(), hasListParams = $rawQueryVarName.%M())\n",
                PreparedStatementConfig::class, MemberName("com.ustadmobile.door.ext", "hasListOrArrayParams"))
        }


        beginControlFlow("_db.%M(_stmtConfig)", prepareAndUseStatmentMemberName(suspended))
        add("_stmt ->\n")

        if(querySql != null) {
            var paramIndex = 1
            val queryVarsNotSubstituted = mutableListOf<String>()
            querySql.getSqlQueryNamedParameters().forEach {
                val paramType = queryVars[it]
                if(paramType == null ) {
                    queryVarsNotSubstituted.add(it)
                }else if(paramType.javaToKotlinType().isListOrArray()) {
                    //val con = null as Connection
                    val arrayTypeName = sqlArrayComponentTypeOf(paramType.javaToKotlinType())
                    add("_stmt.setArray(${paramIndex++}, _stmt.getConnection().%M(%S, %L.toTypedArray()))\n",
                        MemberName("com.ustadmobile.door.ext", "createArrayOrProxyArrayOf"),
                        arrayTypeName, it)
                }else {
                    add("_stmt.set${paramType.javaToKotlinType().preparedStatementSetterGetterTypeName}(${paramIndex++}, " +
                            "${it})\n")
                }
            }

            if(queryVarsNotSubstituted.isNotEmpty()) {
                logMessage(Diagnostic.Kind.ERROR,
                        "Parameters in query not found in method signature: ${queryVarsNotSubstituted.joinToString()}",
                        enclosing, method)
                return this
            }
        }else {
            add("$rawQueryVarName.bindToPreparedStmt(_stmt, _db, _stmt.getConnection())\n")
        }

        val resultSet: ResultSet?
        val execStmt: Statement?
        try {
            execStmt = dbConnection?.createStatement()

            if(isUpdateOrDelete) {
                //This can't be. An update will not be done using a RawQuery (that would just be done using execSQL)
                if(querySql == null)
                    throw IllegalStateException("QuerySql cannot be null")

                /*
                 Run this query now so that we would get an exception if there is something wrong with it.
                 */
                execStmt?.executeUpdate(querySql.replaceQueryNamedParamsWithDefaultValues(queryVars))
                add("val _numUpdates = _stmt.")
                if(suspended) {
                    add("%M()\n", MemberName("com.ustadmobile.door.jdbc.ext", "executeUpdateAsyncKmp"))
                }else {
                    add("executeUpdate()\n")
                }

                if(resultType != UNIT) {
                    add("$resultVarName = _numUpdates\n")
                }
            }else {
                if(suspended) {
                    beginControlFlow("_stmt.%M().%M",
                        MEMBERNAME_ASYNC_QUERY, MEMBERNAME_RESULTSET_USERESULTS)
                }else {
                    beginControlFlow("_stmt.executeQuery().%M",
                        MEMBERNAME_RESULTSET_USERESULTS)
                }

                add(" _resultSet ->\n")

                val colNames = mutableListOf<String>()
                if(querySql != null) {
                    resultSet = execStmt?.executeQuery(querySql.replaceQueryNamedParamsWithDefaultValues(queryVars))
                    val metaData = resultSet!!.metaData
                    for(i in 1 .. metaData.columnCount) {
                        colNames.add(metaData.getColumnName(i))
                    }
                }

                val entityVarName = "_entity"

                if(entityType !in QUERY_SINGULAR_TYPES && rawQueryVarName != null) {
                    add("val _columnIndexMap = _resultSet.%M()\n",
                        MemberName("com.ustadmobile.door.ext", "columnIndexMap"))
                }


                if(resultType.isListOrArray()) {
                    beginControlFlow("while(_resultSet.next())")
                }else {
                    beginControlFlow("if(_resultSet.next())")
                }

                if(QUERY_SINGULAR_TYPES.contains(entityType)) {
                    add("val $entityVarName = _resultSet.get${entityType.preparedStatementSetterGetterTypeName}(1)\n")
                }else {
                    add(resultEntityField!!.createSetterCodeBlock(rawQuery = rawQueryVarName != null,
                            colIndexVarName = "_columnIndexMap"))
                }

                if(resultType.isListOrArray()) {
                    add("$resultVarName.add(_entity)\n")
                }else {
                    add("$resultVarName = _entity\n")
                }

                endControlFlow()
                endControlFlow() //end use of resultset
            }
        }catch(e: SQLException) {
            logMessage(Diagnostic.Kind.ERROR,
                    "Exception running query SQL '$querySql' : ${e.message}",
                    enclosing = enclosing, element = method,
                    annotation = method?.annotationMirrors?.firstOrNull {it.annotationType.asTypeName() == Query::class.asTypeName()})
        }

        endControlFlow()

        return this
    }


    fun logMessage(kind: Diagnostic.Kind, message: String, enclosing: TypeElement? = null,
                   element: Element? = null, annotation: AnnotationMirror? = null) {
        val messageStr = "DoorDb: ${enclosing?.qualifiedName}#${element?.simpleName} $message "
        if(annotation != null && element != null) {
            messager.printMessage(kind, messageStr, element, annotation)
        }else if(element != null) {
            messager.printMessage(kind, messageStr, element)
        }else {
            messager.printMessage(kind, messageStr)
        }
    }

    /**
     * Write the given FileSpec to the directories specified in the annotation processor arguments.
     * Paths should be separated by the path separator character (platform dependent - e.g. :
     * on Unix, ; on Windows)
     */
    protected fun FileSpec.writeToDirsFromArg(argName: String, useFilerAsDefault: Boolean = true) {
        writeToDirsFromArg(listOf(argName), useFilerAsDefault)
    }

    protected fun FileSpec.writeToDirsFromArg(argNames: List<String>, useFilerAsDefault: Boolean = true) {
        val outputArgDirs = argNames.flatMap {argName ->
            processingEnv.options[argName]?.split(File.pathSeparator)
                    ?: if(useFilerAsDefault) { listOf("filer") } else { listOf() }
        }

        outputArgDirs.forEach {
            val outputPath = if(it == "filer") {
                processingEnv.options["kapt.kotlin.generated"]!!
            }else {
                it
            }

            writeTo(File(outputPath))
        }
    }

    companion object {
        val CLASSNAME_CONNECTION = ClassName("com.ustadmobile.door.jdbc", "Connection")

        val CLASSNAME_PREPARED_STATEMENT = ClassName("com.ustadmobile.door.jdbc", "PreparedStatement")

        val CLASSNAME_STATEMENT = ClassName("com.ustadmobile.door.jdbc", "Statement")

        val CLASSNAME_SQLEXCEPTION = ClassName("com.ustadmobile.door.jdbc", "SQLException")

        val CLASSNAME_DATASOURCE = ClassName("com.ustadmobile.door.jdbc", "DataSource")

        val CLASSNAME_EXCEPTION = ClassName("kotlin", "Exception")

        val CLASSNAME_RUNTIME_EXCEPTION = ClassName("kotlin", "RuntimeException")

        val CLASSNAME_ILLEGALARGUMENTEXCEPTION = ClassName("kotlin", "IllegalArgumentException")

        val CLASSNAME_ILLEGALSTATEEXCEPTION = ClassName("kotlin", "IllegalStateException")

        val MEMBERNAME_ASYNC_QUERY = MemberName("com.ustadmobile.door.jdbc.ext", "executeQueryAsyncKmp")

        val MEMBERNAME_RESULTSET_USERESULTS = MemberName("com.ustadmobile.door.jdbc.ext", "useResults")

        val MEMBERNAME_MUTABLE_LINKEDLISTOF = MemberName("com.ustadmobile.door.ext", "mutableLinkedListOf")

        val MEMBERNAME_PREPARE_AND_USE_STMT_ASYNC = MemberName("com.ustadmobile.door.ext",
            "prepareAndUseStatementAsync")

        val MEMBERNAME_PREPARE_AND_USE_STMT = MemberName("com.ustadmobile.door.ext", "prepareAndUseStatement")

        internal fun prepareAndUseStatmentMemberName(suspended: Boolean) = if(suspended)
            MEMBERNAME_PREPARE_AND_USE_STMT_ASYNC
        else
            MEMBERNAME_PREPARE_AND_USE_STMT

        const val SUFFIX_DEFAULT_RECEIVEVIEW = "_ReceiveView"

        const val PGSECTION_COMMENT_PREFIX = "/*psql"

        const val NOTPGSECTION_COMMENT_PREFIX = "--notpsql"

        const val NOTPGSECTION_END_COMMENT_PREFIX = "--endnotpsql"

        val MEMBERNAME_EXEC_UPDATE_ASYNC = MemberName("com.ustadmobile.door.jdbc.ext", "executeUpdateAsyncKmp")

        val MEMBERNAME_ENCODED_PATH = MemberName("io.ktor.http", "encodedPath")

        val MEMBERNAME_CLIENT_SET_BODY = MemberName("io.ktor.client.request", "setBody")
    }

}