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

/** Compact JSON form, sealed form elsewhere. Needs polymorphic discriminators and no naming strategy. */
open class CompactSerializer<T : Any>(
    /** Shared with the sealed fallback. */
    val serialName: String,
    base: KClass<T>,
    internal val catalog: JsonNodeCatalog = JsonNodeCatalog(),
    nodes: () -> List<JsonNode>,
) : KSerializer<T> {
    /** Lazy, for recursive vocabularies. */
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

    /** Uses the catalog resolver on ambiguity. */
    fun selectNode(element: JsonElement): JsonNode = select(element, lenient = false).first

    private fun select(element: JsonElement, lenient: Boolean): Pair<JsonNode, JsonElement> {
        if (element is JsonPrimitive && !element.isString && element.doubleOrNull != null) {
            return catalog.select(literalNodes, element) { "unexpected number $element" } to element
        }
        // Lenient parsing yields unquoted names as non-string primitives.
        val obj = if (element is JsonPrimitive && (element.isString || (lenient && element !is JsonNull))) {
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
        requireSharedSerializer(candidates, value)
        // Forces defaults so fixed fields are always present.
        val body = json.encodeToJsonElement(JsonOperandSerializer(candidates.first().serializer), value).jsonObject
        return candidates.singleOrNull { node -> node.implies(body, value, json) }
            ?: throw SerializationException("no unique $serialName node matches $value")
    }

    private fun requireSharedSerializer(candidates: List<JsonNode>, value: T) {
        if (candidates.any { it.serializer.descriptor != candidates.first().serializer.descriptor }) {
            throw SerializationException(
                "ambiguous $serialName nodes for ${value::class}: their serializers differ, " +
                    "and type arguments are erased at runtime",
            )
        }
    }

    private fun JsonNode.implies(body: JsonObject, value: T, json: Json): Boolean {
        if (fixed.keys.all { it in body }) return fixed.all { (field, fixed) -> body[field].matches(fixed) }
        // @EncodeDefault(NEVER) fields stay absent at their default, so compare the decoded value instead.
        return try {
            json.decodeFromJsonElement(serializer, JsonObject(body + fixed)) == value
        } catch (_: SerializationException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    // Numbers match by value: 1 matches 1.0.
    private fun JsonElement?.matches(fixed: JsonElement): Boolean =
        this == fixed || number()?.let { it == fixed.number() } == true

    private fun JsonElement?.number(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.integral()

    override fun deserialize(decoder: Decoder): T {
        if (decoder !is JsonDecoder) return decoder.decodeSerializableValue(legacy)
        val (node, payload) = select(decoder.decodeJsonElement(), decoder.json.configuration.isLenient)
        return decoder.json.decodeFromJsonElement(node.serializer, node.decode(payload)) as T
    }
}
