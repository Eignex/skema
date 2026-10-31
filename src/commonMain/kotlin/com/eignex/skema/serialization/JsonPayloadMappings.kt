package com.eignex.skema.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

internal fun JsonNodeShape.payloadMapping(name: String, operands: List<String>): JsonPayloadMapping = when (this) {
    JsonNodeShape.Fields -> FieldsPayloadMapping(name)
    JsonNodeShape.Literal, JsonNodeShape.Operand -> OperandPayloadMapping(operands.single())
    JsonNodeShape.Operands -> OperandsPayloadMapping(name, operands)
}

private class FieldsPayloadMapping(private val name: String) : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body

    override fun decode(payload: JsonElement): JsonObject =
        payload as? JsonObject ?: throw SerializationException("'$name' expects an object, got $payload")

    override fun payloadSchema(body: JsonObject): JsonObject = body
}

private class OperandPayloadMapping(private val operand: String) : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body.getValue(operand)

    override fun decode(payload: JsonElement): JsonObject = JsonObject(mapOf(operand to payload))

    override fun payloadSchema(body: JsonObject): JsonObject =
        body.getValue("properties").jsonObject.getValue(operand).jsonObject
}

private class OperandsPayloadMapping(private val name: String, private val operands: List<String>) :
    JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonArray(operands.map { body.getValue(it) })

    override fun decode(payload: JsonElement): JsonObject {
        if (payload !is JsonArray || payload.size != operands.size) {
            throw SerializationException("'$name' expects ${operands.size} operands, got $payload")
        }
        return JsonObject(operands.zip(payload).toMap())
    }

    override fun payloadSchema(body: JsonObject): JsonObject {
        val properties = body.getValue("properties").jsonObject
        return tupleSchema(operands.map { properties.getValue(it) })
    }
}
