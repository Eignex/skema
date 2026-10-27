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
 * As in JSON Schema's `integer`, integer fields accept any integral number token within their
 * range, such as `2.0` or `1e2`, and encode it as a plain integer token.
 */
open class CompactSerializer<T : Any>(
    /** Stable descriptor name, shared with the ordinary sealed serializer. */
    val serialName: String,
    base: KClass<T>,
    internal val catalog: JsonNodeCatalog = JsonNodeCatalog(),
    nodes: () -> List<JsonNode>,
) : KSerializer<T> {
    /** Concrete nodes, evaluated lazily to support recursive vocabularies. */
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

    /** Selects the concrete node for a compact value, applying the catalog's ambiguity resolver. */
    fun selectNode(element: JsonElement, path: String = "$"): JsonNode = select(element, path).first

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
        val candidates = nodes.filter { it.type == value::class }
        if (candidates.isEmpty()) invalid("$", "unsupported $serialName implementation")
        val node = candidates.singleOrNull()?.takeIf { it.fixed.isEmpty() } ?: run {
            val fields = candidates.flatMapTo(mutableSetOf()) { it.fixed.keys }
            val values = candidates.first().fieldValues(value, encoder.json, fields)
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
        val element = decoder.decodeJsonElement()
        val parentPath = (decoder as? JsonPathDecoder)?.path ?: "$"
        val (node, payload) = select(element, parentPath)
        val path = node.pathAt(parentPath)
        val shape = JsonShape(this, catalog)
        val body = shape.validateRecord(node.serializer.descriptor, node.decode(payload, path), path) { field ->
            node.fieldPath(field, path)
        }
        try {
            val serializer = JsonPathSerializer(node.serializer, body, path, shape) { field ->
                node.fieldPath(field, path)
            }
            return decoder.json.decodeFromJsonElement(serializer, body) as T
        } catch (error: SerializationException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw SerializationException("$path: ${error.message}", error)
        }
    }
}
