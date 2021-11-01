package com.ustadmobile.lib.annotationprocessor.core

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.ustadmobile.door.DoorDataSourceFactory
import com.ustadmobile.door.DoorLiveData
import com.ustadmobile.door.DoorDbType
import com.ustadmobile.door.jdbc.TypesKmp
import javax.annotation.processing.ProcessingEnvironment

/**
 * Provides the appropriate SQL type name as a string for this type name
 *
 * @param dbType Integer constant as per DoorDbType
 */
internal fun TypeName.toSqlType(dbType: Int = 0) = when {
    //SQLite types should be as they are generated by Room on Android. SQLite does not apply
    //size limits
    dbType == DoorDbType.SQLITE && this in listOf(BOOLEAN, BYTE, SHORT, INT, LONG) -> "INTEGER"
    dbType == DoorDbType.SQLITE && this in listOf(FLOAT, DOUBLE) -> "REAL"
    dbType == DoorDbType.SQLITE && this == String::class.asClassName() -> "TEXT"

    //Otherwise (e.g. Postgres) map strictly between the variable type and the SQL type
    this == BOOLEAN -> "BOOL"
    this == BYTE -> "SMALLINT"
    this == SHORT -> "SMALLINT"
    this == INT ->  "INTEGER"
    this == LONG -> "BIGINT"
    this == FLOAT -> "FLOAT"
    this == DOUBLE -> "DOUBLE PRECISION"
    this == String::class.asClassName() -> "TEXT"

    else -> "ERR_UNSUPPORTED_TPYE-$this"
}

/**
 * Map a Kotlin Type to JDBC Types constant
 */
fun TypeName.toSqlTypesInt() = when {
    this == BOOLEAN -> TypesKmp.BOOLEAN
    this == BYTE -> TypesKmp.SMALLINT
    this == SHORT -> TypesKmp.SMALLINT
    this == INT -> TypesKmp.INTEGER
    this == LONG -> TypesKmp.BIGINT
    this == FLOAT -> TypesKmp.FLOAT
    this == DOUBLE -> TypesKmp.DOUBLE
    this == String::class.asClassName() -> TypesKmp.LONGVARCHAR
    this == String::class.asClassName().copy(nullable = true) -> TypesKmp.LONGVARCHAR
    else -> throw IllegalArgumentException("Could not get sqlTypeInt for: $this")
}

/**
 * Given a string of the SQL column type, get the default value (0 for numbers, null for strings, false for boolean)
 */
@Suppress("UNUSED_PARAMETER")
internal fun String.sqlTypeDefaultValue(dbType: Int) = this.trim().uppercase().let {
    when(it) {
        "BOOL" -> "false"
        "TEXT" -> "null"
        else -> "0"
    }
}

internal fun TypeName.isDataSourceFactory(paramTypeFilter: (List<TypeName>) -> Boolean = {true}): Boolean {
    return this is ParameterizedTypeName
            && this.rawType == DoorDataSourceFactory::class.asClassName()
            && paramTypeFilter(this.typeArguments)
}


internal fun TypeName.isDataSourceFactoryOrLiveData() = this is ParameterizedTypeName
        && (this.isDataSourceFactory() ||  this.rawType == DoorLiveData::class.asClassName())

fun TypeName.isListOrArray() = (this is ClassName && this.canonicalName =="kotlin.Array")
        || (this is ParameterizedTypeName && this.rawType == List::class.asClassName())

fun TypeName.isList() = (this is ParameterizedTypeName && this.rawType == List::class.asClassName())
        || (this == List::class.asClassName())

/**
 * Determines whether or not this TypeName is nullable when it is used as a return type for a select
 * query. This will return false for any primitive, false for List types (which must be an empty list
 * when there is no result), false for DataSource.Factory, and true for Strings and singular entity
 * types.
 */
val TypeName.isNullableAsSelectReturnResult
    get() = this != UNIT
            && !PRIMITIVE.contains(this)
            && !(this is ParameterizedTypeName)

/**
 * Determines whether or not this TypeName's generic type argument is nullable when used as a return
 * type for a select query. This is useful for LiveData types. It applies the logic for
 * isNullableAsSelectReturnResult to the type argument for LiveData
 */
val TypeName.isNullableParameterTypeAsSelectReturnResult
    get() = this.isLiveData() && unwrapLiveDataOrDataSourceFactory().isNullableAsSelectReturnResult


/**
 * If this TypeName represents a List of a type e.g. List<Foo> then return the classname for a
 * singular Foo. If this typename is not a list, just return it
 */
//TODO: Make this handle arrays as well
internal fun TypeName.asComponentClassNameIfList() : ClassName {
    return if(this is ParameterizedTypeName && this.rawType == List::class.asClassName()) {
        val typeArg = this.typeArguments[0]
        if(typeArg is WildcardTypeName) {
            typeArg.outTypes[0] as ClassName
        }else {
            typeArg as ClassName
        }
    }else {
        this as ClassName
    }
}


/**
 * If the given TypeName represents typed LiveData or a DataSource Factory, unwrap it to the
 * raw type.
 *
 * In the case of LiveData this is simply the first parameter type.
 * E.g. LiveData<Foo> will return 'Foo', LiveData<List<Foo>> will return List<Foo>
 *
 * In the case of a DataSourceFactory, this will be a list of the first parameter type (as a
 * DataSource.Factory is providing a list)
 * E.g. DataSource.Factory<Foo> will unwrap as List<Foo>
 */
fun TypeName.unwrapLiveDataOrDataSourceFactory()  =
    when {
        this is ParameterizedTypeName && rawType == DoorLiveData::class.asClassName() -> typeArguments[0]
        this is ParameterizedTypeName && rawType == DoorDataSourceFactory::class.asClassName() ->
            List::class.asClassName().parameterizedBy(typeArguments[1])
        else -> this
    }

/**
 * Unwrap the component type of an array or list
 */
fun TypeName.unwrapListOrArrayComponentType() =
        if(this is ParameterizedTypeName &&
                (this.rawType == List::class.asClassName() || this.rawType == ClassName("kotlin", "Array"))) {
            val typeArg = typeArguments[0]
            if(typeArg is WildcardTypeName) {
                typeArg.outTypes[0]
            }else {
                typeArg
            }
        }else {
            this
        }

/**
 * Unwrap everything that could be wrapping query return types. This will unwrap DataSource.Factory,
 * LiveData, List, and Array to give the singular type. This can be useful if you want to know
 * the type of entity that is being used.
 */
fun TypeName.unwrapQueryResultComponentType() = unwrapLiveDataOrDataSourceFactory().unwrapListOrArrayComponentType()

/**
 * Determine if this typename represents LiveData
 */
fun TypeName.isLiveData() = this is ParameterizedTypeName && rawType == DoorLiveData::class.asClassName()

fun TypeName.hasAttachments(processingEnv: ProcessingEnvironment): Boolean {
    if(this is ClassName){
        return processingEnv.elementUtils.getTypeElement(canonicalName)?.entityHasAttachments == true
    }else {
        return false
    }
}


/**
 * Check if this TypeName should be sent as query parameters when passed over http. This is true
 * for primitive types, strings, and lists thereof. It is false for other types (e.g. entities themselves)
 */
fun TypeName.isHttpQueryQueryParam(): Boolean {
    return this in QUERY_SINGULAR_TYPES
            || (this is ParameterizedTypeName
            && (this.rawType == List::class.asClassName() && this.typeArguments[0] in QUERY_SINGULAR_TYPES))
}

/**
 * Gets the default value for this typename. This is 0 for primitive numbers, false for booleans,
 * null for strings, empty listOf for list types
 */
fun TypeName.defaultTypeValueCode(): CodeBlock {
    val codeBlock = CodeBlock.builder()
    val kotlinType = javaToKotlinType()
    when(kotlinType) {
        INT -> codeBlock.add("0")
        LONG -> codeBlock.add("0L")
        BYTE -> codeBlock.add("0.toByte()")
        FLOAT -> codeBlock.add("0.toFloat()")
        DOUBLE -> codeBlock.add("0.toDouble()")
        BOOLEAN -> codeBlock.add("false")
        String::class.asTypeName() -> codeBlock.add("null as String?")
        else -> {
            if(kotlinType is ParameterizedTypeName && kotlinType.rawType == List::class.asClassName()) {
                codeBlock.add("mutableListOf<%T>()", kotlinType.typeArguments[0])
            }else {
                codeBlock.add("null as %T?", this)
            }
        }
    }

    return codeBlock.build()
}

fun TypeName.defaultSqlValue(dbProductType: Int): String {
    val kotlinType = javaToKotlinType()
    return when(kotlinType) {
        BOOLEAN -> if(dbProductType == DoorDbType.SQLITE) "0" else "false"
        BYTE -> "0"
        SHORT -> "0"
        INT -> "0"
        LONG -> "0"
        FLOAT -> "0"
        DOUBLE -> "0"
        else -> "null"
    }
}

/**
 * Get the name of the function that would be used to set this kind of parameter on a PreparedStatement
 * e.g. setInt, setDouble, setBoolean etc.
 */
val TypeName.preparedStatementSetterGetterTypeName: String
    get() = when(this.javaToKotlinType()) {
            INT ->  "Int"
            BYTE -> "Byte"
            LONG ->  "Long"
            FLOAT -> "Float"
            DOUBLE -> "Double"
            BOOLEAN -> "Boolean"
            String::class.asTypeName() ->  "String"
            String::class.asTypeName().copy(nullable = true)  -> "String"
            else -> {
                if(this.javaToKotlinType().isListOrArray()) {
                    "Array"
                }else {
                     "UNKNOWN"
                }
            }
        }

fun TypeName.isArrayType(): Boolean = (this is ParameterizedTypeName && this.rawType.canonicalName == "kotlin.Array")
