package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

/** Wire form of a node's fields. */
enum class JsonNodeShape {
    /** Object of named fields, or a value class's underlying value. */
    Fields,

    /** Bare number, without the node name. */
    Literal,

    /** One field's value. */
    Operand,

    /** Array of field values in operand order. */
    Operands,
}

/** Maps a node's fields to and from its payload. */
interface JsonPayloadMapping {
    /** Fields to payload; defaults may be absent, as the caller's Json encodes them. */
    fun encode(body: JsonObject): JsonElement

    /** Throws [SerializationException] on malformed input. */
    fun decode(payload: JsonElement): JsonObject

    /** Payload schema, given the [body] schema minus fixed fields. */
    fun payloadSchema(body: JsonObject): JsonObject =
        error("Custom payload mappings require an explicit payload schema")
}

/** A concrete type and its compact JSON form. */
class JsonNode(
    val type: KClass<*>,
    /** Operator key or bare name. */
    val name: String,
    val shape: JsonNodeShape = JsonNodeShape.Fields,
    /** Fields for the operand shapes; never @EncodeDefault(NEVER), as they must always encode. */
    val operands: List<String> = emptyList(),
    val mapping: JsonPayloadMapping? = null,
    /** Implied by [name] and left off the wire; tells apart nodes of one [type]. */
    val fixed: Map<String, JsonElement> = emptyMap(),
    serializer: () -> KSerializer<*>,
) {
    internal val payloadMapping: JsonPayloadMapping by lazy {
        val field = inlineField
        when {
            mapping != null -> mapping

            // A value-class fields node carries the underlying value.
            shape == JsonNodeShape.Fields && field != null && field !in fixed ->
                JsonNodeShape.Operand.payloadMapping(name, listOf(field))

            else -> shape.payloadMapping(name, operands)
        }
    }

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

    // Value classes encode as their underlying value; nodes treat it as a one-field object.
    private val inlineField: String? by lazy {
        this.serializer.descriptor.takeIf { it.isInline }?.getElementName(0)
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

    internal fun encode(body: JsonObject): JsonElement {
        if (mapping == null && shape != JsonNodeShape.Fields) {
            operands.firstOrNull { it !in body }?.let {
                throw SerializationException(
                    "'$name' operand '$it' was not encoded; operands cannot use @EncodeDefault(NEVER)",
                )
            }
        }
        return payloadMapping.encode(if (fixed.isEmpty()) body else JsonObject(body - fixed.keys))
    }

    internal fun wrapBody(encoded: JsonElement): JsonObject =
        inlineField?.let { JsonObject(mapOf(it to encoded)) } ?: encoded.jsonObject

    internal fun unwrapBody(body: JsonObject): JsonElement = inlineField?.let {
        body[it] ?: throw SerializationException("'$name' is missing field '$it'")
    } ?: body

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

/** Declares a node with [T]'s serializer. */
inline fun <reified T : Any> jsonNode(
    name: String,
    shape: JsonNodeShape = JsonNodeShape.Fields,
    vararg operands: String,
    mapping: JsonPayloadMapping? = null,
    fixed: Map<String, JsonElement> = emptyMap(),
): JsonNode = JsonNode(T::class, name, shape, operands.toList(), mapping, fixed) { serializer<T>() }

/** Declares a fields node with a custom serializer. */
inline fun <reified T : Any> jsonNode(name: String, noinline serializer: () -> KSerializer<T>): JsonNode =
    JsonNode(T::class, name, serializer = serializer)
