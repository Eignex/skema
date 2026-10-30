package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
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

    /** Restores serialized fields, reporting malformed input at [path]. */
    fun decode(payload: JsonElement, path: String): JsonObject

    /** Location of a serialized field in the compact payload. */
    fun fieldPath(field: String, path: String): String = "$path.$field"

    /**
     * Schema for the payload, given the [body] schema without fixed fields. Skema adds the
     * operator key and a bare-name shorthand for objects without required fields; literals
     * stand alone. References used to determine shorthand must resolve within the document.
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
    internal val payloadMapping: JsonPayloadMapping by lazy { mapping ?: shape.payloadMapping(operands) }

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
        if (mapping != null || shape == JsonNodeShape.Fields) return
        require(operands.toSet() + fixed.keys == fields.toSet()) {
            "JSON node '$name' operands must cover every body field: $fields"
        }
        if (shape == JsonNodeShape.Literal) {
            val literal = descriptor.getElementDescriptor(descriptor.getElementIndex(operands.single()))
            require(
                !literal.acceptsJsonNull() && (literal.integerBounds() != null || literal.isFloatingPoint()),
            ) { "JSON literal node '$name' requires a non-null numeric field" }
        }
    }

    internal fun fieldValues(value: Any, json: Json, fields: Set<String>): Map<String, JsonElement> =
        FieldCaptureEncoder(json, fields).also { it.encodeSerializableValue(serializer, value) }.values

    internal fun encode(body: JsonObject): JsonElement {
        val fields = if (fixed.isEmpty()) body else JsonObject(body - fixed.keys)
        if (mapping == null && shape != JsonNodeShape.Fields) {
            operands.firstOrNull { it !in fields }?.let { field ->
                invalid(
                    "$.$name",
                    "operand '$field' was omitted by its body serializer; do not use @EncodeDefault(NEVER)",
                )
            }
        }
        return payloadMapping.encode(fields)
    }

    internal fun decode(payload: JsonElement, path: String): JsonObject {
        val body = payloadMapping.decode(payload, path)
        if (fixed.isEmpty()) return body
        body.keys.firstOrNull { it in fixed }?.let { invalid(fieldPath(it, path), "unknown field '$it'") }
        return JsonObject(body + fixed)
    }

    internal fun fieldPath(field: String, path: String): String = payloadMapping.fieldPath(field, path)

    internal fun pathAt(path: String): String = if (shape == JsonNodeShape.Literal) path else "$path.$name"
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
