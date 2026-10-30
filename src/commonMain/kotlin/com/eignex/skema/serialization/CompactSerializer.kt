@file:OptIn(
    kotlinx.serialization.InternalSerializationApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)
@file:Suppress("UNCHECKED_CAST")

package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedClassSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

/**
 * Encodes JSON as operator objects, names for empty objects, or numeric literals;
 * other formats use sealed-class serialization. Decoding is strict regardless of caller settings.
 * Integer fields accept integral tokens in range (`2.0`, `1e2`) and encode as plain integers.
 * Numeric body fields must be finite, even when the caller allows special floating-point values.
 * Requires polymorphic class discriminators and no JSON naming strategy.
 */
open class CompactSerializer<T : Any>(
    /** Stable descriptor name, shared with the ordinary sealed serializer. */
    val serialName: String,
    base: KClass<T>,
    internal val catalog: JsonNodeCatalog = JsonNodeCatalog(),
    nodes: () -> List<JsonNode>,
) : KSerializer<T> {
    /** Loaded lazily to allow recursive vocabularies. */
    val nodes: List<JsonNode> by lazy(nodes)

    private val nodesByType by lazy { this.nodes.groupBy { it.type } }
    private val operatorsByName by lazy { this.nodes.filter { it.shape != JsonNodeShape.Literal }.groupBy { it.name } }
    private val literalNodes by lazy { this.nodes.filter { it.shape == JsonNodeShape.Literal } }

    private val legacy by lazy {
        val implementations = this.nodes.distinctBy { it.type }
        SealedClassSerializer(
            serialName,
            base,
            implementations.map { it.type as KClass<out T> }.toTypedArray(),
            implementations.map { it.serializer as KSerializer<out T> }.toTypedArray(),
        )
    }

    override val descriptor: SerialDescriptor get() = legacy.descriptor

    /** Uses the catalog resolver when multiple nodes match. */
    fun selectNode(element: JsonElement, path: String = "$"): JsonNode = select(element, path).first

    internal fun select(element: JsonElement, path: String): Pair<JsonNode, JsonElement> {
        if (element is JsonPrimitive && !element.isString && element.doubleOrNull != null) {
            val literal = catalog.select(literalNodes, element, path, "expected an operator object")
            return literal to element
        }
        val obj = if (element is JsonPrimitive && element.isString) {
            JsonObject(mapOf(element.content to JsonObject(emptyMap())))
        } else {
            element.objectAt(path)
        }
        if (obj.size != 1) invalid(path, "expected exactly one operator")
        val (operator, payload) = obj.entries.single()
        val candidates = operatorsByName[operator].orEmpty()
        val node = catalog.select(candidates, payload, "$path.$operator", "unknown operator or reference '$operator'")
        return node to payload
    }

    override fun serialize(encoder: Encoder, value: T) {
        if (encoder !is JsonEncoder) {
            encoder.encodeSerializableValue(legacy, value)
            return
        }
        checkJsonConfiguration(encoder.json)
        val json = if (encoder.json.configuration.allowSpecialFloatingPointValues) {
            Json(encoder.json) { allowSpecialFloatingPointValues = false }
        } else {
            encoder.json
        }
        val node = selectNodeForValue(value, json)
        val body = json.encodeToJsonElement(node.encodingSerializer, value).jsonObject
        val payload = node.encode(body)
        encoder.encodeJsonElement(
            when {
                node.shape == JsonNodeShape.Literal -> payload
                payload is JsonObject && payload.isEmpty() -> JsonPrimitive(node.name)
                else -> JsonObject(mapOf(node.name to payload))
            },
        )
    }

    private fun selectNodeForValue(value: T, json: Json): JsonNode {
        val candidates = nodesByType[value::class].orEmpty()
        if (candidates.isEmpty()) invalid("$", "unsupported $serialName implementation")
        return candidates.singleOrNull()?.takeIf { it.fixed.isEmpty() } ?: run {
            val fields = candidates.flatMapTo(mutableSetOf()) { it.fixed.keys }
            val values = candidates.first().fieldValues(value, json, fields)
            val missing = fields - values.keys
            if (missing.isNotEmpty()) {
                invalid(
                    "$",
                    "fixed fields $missing were omitted by their body serializer; do not use @EncodeDefault(NEVER)",
                )
            }
            val matches = candidates.filter { candidate ->
                candidate.fixed.all { (field, fixed) -> sameJsonValue(values[field], fixed) }
            }
            // A node fixing a strict superset of another match's fields is the more specific choice.
            matches.singleOrNull { candidate ->
                matches.all { other ->
                    other === candidate ||
                        (other.fixed.size < candidate.fixed.size && candidate.fixed.keys.containsAll(other.fixed.keys))
                }
            } ?: invalid("$", "no unique $serialName node matches the fixed fields")
        }
    }

    override fun deserialize(decoder: Decoder): T {
        if (decoder !is JsonDecoder) return decoder.decodeSerializableValue(legacy)
        checkJsonConfiguration(decoder.json)
        val element = decoder.decodeJsonElement()
        val parentPath = (decoder as? JsonPathDecoder)?.path ?: "$"
        val (node, payload) = select(element, parentPath)
        val path = node.pathAt(parentPath)
        val shape = JsonShape(this, catalog, decoder.json)
        val fieldPath: (String) -> String = { node.fieldPath(it, path) }
        val body = node.decode(payload, path)
        return withJsonPath(path) {
            val serializer = JsonPathSerializer(node.serializer, body, path, shape, fieldPath)
            decoder.json.decodeFromJsonElement(serializer, body) as T
        }
    }
}

// Fixed values match by JSON value, so numbers compare equal however they are written.
private fun sameJsonValue(value: JsonElement?, fixed: JsonElement): Boolean = when {
    value is JsonObject && fixed is JsonObject ->
        value.keys == fixed.keys && value.all { (name, entry) -> sameJsonValue(entry, fixed.getValue(name)) }

    value is JsonArray && fixed is JsonArray ->
        value.size == fixed.size && value.indices.all { sameJsonValue(value[it], fixed[it]) }

    value is JsonPrimitive && fixed is JsonPrimitive && !value.isString && !fixed.isString ->
        canonicalNumber(value.content)?.let { it == canonicalNumber(fixed.content) } ?: (value == fixed)

    else -> value == fixed
}

private fun checkJsonConfiguration(json: Json) {
    if (json.configuration.namingStrategy != null) invalid("$", "Compact JSON does not support JSON naming strategies")
    if (json.configuration.classDiscriminatorMode != ClassDiscriminatorMode.POLYMORPHIC) {
        invalid("$", "Compact JSON requires polymorphic class discriminators")
    }
}
