package com.ustadmobile.door.ext

import com.ustadmobile.door.jdbc.PreparedStatement
import com.ustadmobile.door.jdbc.TypesKmp
import com.ustadmobile.door.replication.ReplicationFieldMetaData
import kotlinx.serialization.json.*

fun PreparedStatement.setJsonPrimitive(
    index: Int,
    type: Int,
    jsonPrimitive: JsonPrimitive
) {
    if(jsonPrimitive is JsonNull) {
        setObject(index, null)
        return
    }

    when(type) {
        TypesKmp.INTEGER -> setInt(index, jsonPrimitive.int)
        TypesKmp.SMALLINT -> setShort(index, jsonPrimitive.int.toShort())
        TypesKmp.BIGINT -> setLong(index, jsonPrimitive.long)
        TypesKmp.FLOAT -> setFloat(index, jsonPrimitive.float)
        TypesKmp.REAL -> setFloat(index, jsonPrimitive.float)
        TypesKmp.DOUBLE -> setDouble(index, jsonPrimitive.double)
        TypesKmp.BOOLEAN -> setBoolean(index, jsonPrimitive.boolean)
        TypesKmp.VARCHAR -> setString(index, jsonPrimitive.content)
        TypesKmp.LONGVARCHAR -> setString(index, jsonPrimitive.content)
    }
}

fun PreparedStatement.setAllFromJsonObject(
    jsonObject: JsonObject,
    entityFieldsMetaData: List<ReplicationFieldMetaData>,
    startIndex: Int = 1
) {
    entityFieldsMetaData.forEachIndexed { index, field ->
        setJsonPrimitive(index + startIndex, field.fieldType, jsonObject.getOrThrow(field.fieldName).jsonPrimitive)
    }
}
