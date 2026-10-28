package com.eignex.skema.serialization

import kotlinx.serialization.json.*

internal fun JsonNodeShape.payloadMapping(operands: List<String>): JsonPayloadMapping = when (this) {
    JsonNodeShape.Fields -> FieldsPayloadMapping
    JsonNodeShape.Literal, JsonNodeShape.Operand -> OperandPayloadMapping(operands.single())
    JsonNodeShape.Operands -> OperandsPayloadMapping(operands)
}

private object FieldsPayloadMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body

    override fun decode(payload: JsonElement, path: String): JsonObject = payload.objectAt(path)

    override fun payloadSchema(body: JsonObject): JsonObject = body
}

private class OperandPayloadMapping(private val operand: String) : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body.getValue(operand)

    override fun decode(payload: JsonElement, path: String): JsonObject = JsonObject(mapOf(operand to payload))

    override fun fieldPath(field: String, path: String): String = path

    override fun payloadSchema(body: JsonObject): JsonObject =
        body.getValue("properties").jsonObject.getValue(operand).jsonObject
}

private class OperandsPayloadMapping(private val operands: List<String>) : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonArray(operands.map { body.getValue(it) })

    override fun decode(payload: JsonElement, path: String): JsonObject {
        val array = payload as? JsonArray ?: invalid(path, "expected an operand array")
        if (array.size != operands.size) invalid(path, "expected ${operands.size} operands")
        return JsonObject(operands.zip(array).toMap())
    }

    override fun fieldPath(field: String, path: String): String = "$path[${operands.indexOf(field)}]"

    override fun payloadSchema(body: JsonObject): JsonObject {
        val properties = body.getValue("properties").jsonObject
        return tupleSchema(operands.map { properties.getValue(it) })
    }
}
