package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/** Compact representation of a node's serialized fields. */
enum class JsonNodeShape {
    /** Object containing the ordinary named fields. */
    Fields,

    /** Bare numeric literal, without an enclosing node name. */
    Literal,

    /** Value of a single named field. */
    Operand,

    /** Array of named fields in their declared operand order. */
    Operands,
}

/** Reversible mapping between ordinary serialized fields and a node's JSON payload. */
interface JsonPayloadMapping {
    /** Maps serialized fields to a compact payload. */
    fun encode(body: JsonObject): JsonElement

    /** Restores serialized fields, reporting malformed input at [path]. */
    fun decode(payload: JsonElement, path: String): JsonObject

    /** Location of a serialized field in the compact payload. */
    fun fieldPath(field: String, path: String): String = "$path.$field"
}

/** One concrete implementation in a compact polymorphic vocabulary. */
class JsonNode(
    /** Concrete Kotlin implementation used when encoding. */
    val type: KClass<*>,
    /** Operator key or shorthand name on the wire. */
    val name: String,
    /** Built-in payload shape. */
    val shape: JsonNodeShape = JsonNodeShape.Fields,
    /** Serialized field names for single or positional operands. */
    val operands: List<String> = emptyList(),
    /** Optional domain-specific payload mapping. */
    val mapping: JsonPayloadMapping? = null,
    serializer: () -> KSerializer<*>,
) {
    @Suppress("UNCHECKED_CAST")
    internal val serializer: KSerializer<Any> by lazy { serializer() as KSerializer<Any> }

    init {
        require(name.isNotEmpty()) { "A JSON node must have a name" }
        require(shape !in listOf(JsonNodeShape.Literal, JsonNodeShape.Operand) || operands.size == 1) {
            "Literal and single-operand nodes require one field"
        }
        require(operands.distinct().size == operands.size) { "Operand fields must be distinct" }
    }

    internal fun encode(body: JsonObject): JsonElement = mapping?.encode(body) ?: when (shape) {
        JsonNodeShape.Fields -> body
        JsonNodeShape.Literal, JsonNodeShape.Operand -> body.getValue(operands.single())
        JsonNodeShape.Operands -> JsonArray(operands.map { body.getValue(it) })
    }

    internal fun decode(payload: JsonElement, path: String): JsonObject = mapping?.decode(
        payload,
        path,
    ) ?: when (shape) {
        JsonNodeShape.Fields -> payload.objectAt(path)

        JsonNodeShape.Literal, JsonNodeShape.Operand -> JsonObject(mapOf(operands.single() to payload))

        JsonNodeShape.Operands -> {
            val array = payload as? JsonArray ?: invalid(path, "expected an operand array")
            if (array.size != operands.size) invalid(path, "expected ${operands.size} operands")
            JsonObject(operands.zip(array).toMap())
        }
    }

    internal fun fieldPath(field: String, path: String): String = mapping?.fieldPath(field, path) ?: when (shape) {
        JsonNodeShape.Literal, JsonNodeShape.Operand -> path
        JsonNodeShape.Operands -> "$path[${operands.indexOf(field)}]"
        JsonNodeShape.Fields -> "$path.$field"
    }

    internal fun pathAt(path: String): String = if (shape == JsonNodeShape.Literal) path else "$path.$name"
}

/** Declares a node using its generated body serializer. */
inline fun <reified T : Any> jsonNode(
    name: String,
    shape: JsonNodeShape = JsonNodeShape.Fields,
    vararg operands: String,
    mapping: JsonPayloadMapping? = null,
): JsonNode = JsonNode(T::class, name, shape, operands.toList(), mapping) { serializer<T>() }

/** Declares a node with an explicitly supplied body serializer. */
inline fun <reified T : Any> jsonNode(name: String, noinline serializer: () -> KSerializer<T>): JsonNode =
    JsonNode(T::class, name, serializer = serializer)
