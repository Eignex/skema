package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
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

    /** Array of field values in the declared operand order. */
    Operands,
}

/** Reversible mapping between ordinary serialized fields and a node's JSON payload. */
interface JsonPayloadMapping {
    /** Converts body fields to the wire payload. */
    fun encode(body: JsonObject): JsonElement

    /** Restores serialized fields, throwing [SerializationException] for malformed input. */
    fun decode(payload: JsonElement): JsonObject

    /**
     * Schema for the payload, given the [body] schema without fixed fields. Skema adds the
     * operator key, and a bare-name shorthand when this is an inline object schema without
     * required fields; literals stand alone.
     */
    fun payloadSchema(body: JsonObject): JsonObject =
        error("Custom payload mappings require an explicit payload schema")
}

/** A concrete type and its compact JSON representation. */
class JsonNode(
    val type: KClass<*>,
    /** Operator key or shorthand name on the wire. */
    val name: String,
    val shape: JsonNodeShape = JsonNodeShape.Fields,
    /** Serialized field names for single or positional operands. */
    val operands: List<String> = emptyList(),
    val mapping: JsonPayloadMapping? = null,
    /**
     * Fields implied by [name] and omitted from the payload; selects among nodes sharing [type].
     * The body serializer must emit fixed fields and operands, including defaults;
     * `@EncodeDefault(NEVER)` is unsupported for these fields.
     */
    val fixed: Map<String, JsonElement> = emptyMap(),
    serializer: () -> KSerializer<*>,
) {
    internal val payloadMapping: JsonPayloadMapping by lazy { mapping ?: shape.payloadMapping(name, operands) }

    @Suppress("UNCHECKED_CAST")
    internal val serializer: KSerializer<Any> by lazy {
        (serializer() as KSerializer<Any>).also { validateDescriptor(it.descriptor) }
    }

    internal val encodingSerializer: KSerializer<Any> by lazy {
        if (mapping == null && shape != JsonNodeShape.Fields) {
            JsonOperandSerializer(this.serializer)
        } else {
            this.serializer
        }
    }

    private val integerLiteral by lazy {
        shape == JsonNodeShape.Literal && literalDescriptor(this.serializer.descriptor).isInteger()
    }

    init {
        require(name.isNotEmpty()) { "A JSON node must have a name" }
        require(shape !in listOf(JsonNodeShape.Literal, JsonNodeShape.Operand) || operands.size == 1) {
            "Literal and single-operand nodes require one field"
        }
        require(operands.distinct().size == operands.size) { "Operand fields must be distinct" }
        require(operands.none { it in fixed }) { "Fixed fields cannot be operands" }
    }

    private fun validateDescriptor(descriptor: SerialDescriptor) {
        require(descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
            "JSON node '$name' requires an object body serializer"
        }
        val fields = descriptor.elementNames.toList()
        require(fields.containsAll(fixed.keys)) { "JSON node '$name' fixes unknown fields: ${fixed.keys - fields}" }
        require(fields.containsAll(operands)) { "JSON node '$name' has unknown operands: ${operands - fields}" }
        if (mapping != null || shape == JsonNodeShape.Fields) return
        require(operands.toSet() + fixed.keys == fields.toSet()) {
            "JSON node '$name' operands must cover every body field: $fields"
        }
        if (shape == JsonNodeShape.Literal) {
            val literal = literalDescriptor(descriptor)
            require(!literal.isNullable && (literal.isInteger() || literal.isFloatingPoint())) {
                "JSON literal node '$name' requires a non-null numeric field"
            }
        }
    }

    private fun literalDescriptor(body: SerialDescriptor): SerialDescriptor =
        body.getElementDescriptor(body.getElementIndex(operands.single()))

    internal fun encode(body: JsonObject): JsonElement =
        payloadMapping.encode(if (fixed.isEmpty()) body else JsonObject(body - fixed.keys))

    internal fun decode(payload: JsonElement): JsonObject {
        val body = payloadMapping.decode(if (integerLiteral) (payload as JsonPrimitive).integral() else payload)
        if (fixed.isEmpty()) return body
        body.keys.firstOrNull { it in fixed }?.let {
            throw SerializationException(
                "'$name' does not accept field '$it'",
            )
        }
        return JsonObject(body + fixed)
    }
}

/** Declares a node using the serializer for [T]. */
inline fun <reified T : Any> jsonNode(
    name: String,
    shape: JsonNodeShape = JsonNodeShape.Fields,
    vararg operands: String,
    mapping: JsonPayloadMapping? = null,
    fixed: Map<String, JsonElement> = emptyMap(),
): JsonNode = JsonNode(T::class, name, shape, operands.toList(), mapping, fixed) { serializer<T>() }

/** Declares a node with an explicitly supplied body serializer. */
inline fun <reified T : Any> jsonNode(name: String, noinline serializer: () -> KSerializer<T>): JsonNode =
    JsonNode(T::class, name, serializer = serializer)
