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
 * Encodes JSON with operator keys, names for empty objects, and numeric literals.
 * Other formats use the sealed-class representation. Compact JSON is validated
 * even when the caller uses lenient settings.
 * Integer fields accept any integral number token in range, such as `2.0` or `1e2`,
 * and encode it as a plain integer token.
 * JSON naming strategies and non-polymorphic discriminator modes are unsupported and rejected
 * before encoding or decoding a compact value.
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
            val candidates = nodes.filter { it.shape == JsonNodeShape.Literal }
            val literal = catalog.select(candidates, element, path, "expected an operator object")
            return literal to element
        }
        val obj = if (element is JsonPrimitive && element.isString) {
            JsonObject(mapOf(element.content to JsonObject(emptyMap())))
        } else {
            element.objectAt(path)
        }
        if (obj.size != 1) invalid(path, "expected exactly one operator")
        val (operator, payload) = obj.entries.single()
        val candidates = nodes.filter { it.name == operator && it.shape != JsonNodeShape.Literal }
        val node = catalog.select(candidates, payload, "$path.$operator", "unknown operator or reference '$operator'")
        return node to payload
    }

    override fun serialize(encoder: Encoder, value: T) {
        if (encoder !is JsonEncoder) {
            encoder.encodeSerializableValue(legacy, value)
            return
        }
        checkJsonConfiguration(encoder.json)
        val candidates = nodes.filter { it.type == value::class }
        if (candidates.isEmpty()) invalid("$", "unsupported $serialName implementation")
        val node = candidates.singleOrNull()?.takeIf { it.fixed.isEmpty() } ?: run {
            val fields = candidates.flatMapTo(mutableSetOf()) { it.fixed.keys }
            val values = candidates.first().fieldValues(value, encoder.json, fields)
            val missing = fields - values.keys
            if (missing.isNotEmpty()) {
                invalid(
                    "$",
                    "fixed fields $missing were omitted by their body serializer; do not use @EncodeDefault(NEVER)",
                )
            }
            candidates.singleOrNull { candidate -> candidate.fixed.all { (field, fixed) -> values[field] == fixed } }
                ?: invalid("$", "no unique $serialName node matches the fixed fields")
        }
        val body = encoder.json.encodeToJsonElement(node.encodingSerializer, value).jsonObject
        val payload = node.encode(body)
        encoder.encodeJsonElement(
            when {
                node.shape == JsonNodeShape.Literal -> payload
                payload is JsonObject && payload.isEmpty() -> JsonPrimitive(node.name)
                else -> JsonObject(mapOf(node.name to payload))
            },
        )
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
        val body = shape.validateRecord(node.serializer.descriptor, node.decode(payload, path), path, fieldPath)
        return withJsonPath(path) {
            val serializer = JsonPathSerializer(node.serializer, body, path, shape, fieldPath)
            decoder.json.decodeFromJsonElement(serializer, body) as T
        }
    }
}

private fun checkJsonConfiguration(json: Json) {
    if (json.configuration.namingStrategy != null) invalid("$", "Compact JSON does not support JSON naming strategies")
    if (json.configuration.classDiscriminatorMode != ClassDiscriminatorMode.POLYMORPHIC) {
        invalid("$", "Compact JSON requires polymorphic class discriminators")
    }
}
