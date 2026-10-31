@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)
@file:Suppress("UNCHECKED_CAST")

package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SealedClassSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.reflect.KClass

/**
 * Encodes JSON as operator objects, names for empty objects, or numeric literals;
 * other formats use sealed-class serialization. Node bodies decode with the caller's Json.
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
    fun selectNode(element: JsonElement): JsonNode = select(element).first

    private fun select(element: JsonElement): Pair<JsonNode, JsonElement> {
        if (element is JsonPrimitive && !element.isString && element.doubleOrNull != null) {
            return catalog.select(literalNodes, element) { "unexpected number $element" } to element
        }
        val obj = if (element is JsonPrimitive && element.isString) {
            JsonObject(mapOf(element.content to JsonObject(emptyMap())))
        } else {
            element as? JsonObject ?: throw SerializationException("expected an operator, got $element")
        }
        val (operator, payload) = obj.entries.singleOrNull()
            ?: throw SerializationException("expected exactly one operator, got ${obj.keys}")
        val node = catalog.select(operatorsByName[operator].orEmpty(), payload) { "unknown operator '$operator'" }
        return node to payload
    }

    override fun serialize(encoder: Encoder, value: T) {
        if (encoder !is JsonEncoder) {
            encoder.encodeSerializableValue(legacy, value)
            return
        }
        val node = nodeFor(value, encoder.json)
        val payload = node.encode(encoder.json.encodeToJsonElement(node.encodingSerializer, value).jsonObject)
        encoder.encodeJsonElement(
            when {
                node.shape == JsonNodeShape.Literal -> payload
                node.shape == JsonNodeShape.Fields && payload == JsonObject(emptyMap()) -> JsonPrimitive(node.name)
                else -> JsonObject(mapOf(node.name to payload))
            },
        )
    }

    private fun nodeFor(value: T, json: Json): JsonNode {
        val candidates = nodesByType[value::class]
            ?: throw SerializationException("unsupported $serialName implementation ${value::class}")
        candidates.singleOrNull()?.takeIf { it.fixed.isEmpty() }?.let { return it }
        // Forces defaults, so fixed fields are present however the caller configures Json.
        val body = json.encodeToJsonElement(JsonOperandSerializer(candidates.first().serializer), value).jsonObject
        return candidates.singleOrNull { node -> node.fixed.all { (field, fixed) -> body[field] == fixed } }
            ?: throw SerializationException("no unique $serialName node matches $value")
    }

    override fun deserialize(decoder: Decoder): T {
        if (decoder !is JsonDecoder) return decoder.decodeSerializableValue(legacy)
        val (node, payload) = select(decoder.decodeJsonElement())
        return decoder.json.decodeFromJsonElement(node.serializer, node.decode(payload)) as T
    }
}
