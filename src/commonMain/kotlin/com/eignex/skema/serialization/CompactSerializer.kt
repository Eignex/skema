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
 * Opt-in polymorphic serializer using operator keys, empty-object names and numeric literals
 * in JSON. Other formats retain the ordinary sealed-class representation. JSON decoding
 * validates the compact shape, including nested catalog types, regardless of lenient settings.
 */
open class CompactSerializer<T : Any>(
    /** Stable descriptor name, shared with the ordinary sealed serializer. */
    val serialName: String,
    base: KClass<T>,
    private val catalog: JsonNodeCatalog = JsonNodeCatalog(),
    nodes: () -> List<JsonNode>,
) : KSerializer<T> {
    /** Concrete nodes, evaluated lazily to support recursive vocabularies. */
    val nodes: List<JsonNode> by lazy(nodes)

    private val legacy by lazy {
        SealedClassSerializer(
            serialName,
            base,
            this.nodes.map { it.type as KClass<out T> }.toTypedArray(),
            this.nodes.map { it.serializer as KSerializer<out T> }.toTypedArray(),
        )
    }

    override val descriptor: SerialDescriptor get() = legacy.descriptor

    /** Selects the concrete node for a compact value, applying the catalog's ambiguity resolver. */
    fun selectNode(element: JsonElement, path: String = "$"): JsonNode = select(element, path).first

    internal fun validate(element: JsonElement, path: String) {
        JsonShape(this, catalog).validateJson(descriptor, element, path)
    }

    internal fun select(element: JsonElement, path: String): Pair<JsonNode, JsonElement> {
        if (element is JsonPrimitive && !element.isString && element.doubleOrNull != null) {
            val literal = nodes.singleOrNull { it.shape == JsonNodeShape.Literal }
                ?: invalid(path, "expected an operator object")
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
        val node = when (candidates.size) {
            0 -> invalid("$path.$operator", "unknown operator or reference '$operator'")
            1 -> candidates.single()
            else -> catalog.resolve(candidates, payload, "$path.$operator")
        }
        return node to payload
    }

    override fun serialize(encoder: Encoder, value: T) {
        if (encoder !is JsonEncoder) {
            encoder.encodeSerializableValue(legacy, value)
            return
        }
        val node = nodes.singleOrNull { it.type == value::class }
            ?: invalid("$", "unsupported $serialName implementation")
        val body = encoder.json.encodeToJsonElement(node.serializer, value).jsonObject
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
        val element = decoder.decodeJsonElement()
        validate(element, "$")
        val (node, payload) = select(element, "$")
        val path = node.pathAt("$")
        try {
            return decoder.json.decodeFromJsonElement(node.serializer, node.decode(payload, path)) as T
        } catch (error: IllegalArgumentException) {
            throw SerializationException("$path: ${error.message}", error)
        }
    }
}
