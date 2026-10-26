@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.SealedSerializationApi::class,
)

package com.eignex.skema.serialization

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.*

internal class JsonPathSerializer<T>(
    private val body: DeserializationStrategy<T>,
    private val element: JsonElement,
    private val path: String,
    private val fieldPath: (String) -> String = { "$path.$it" },
) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor get() = body.descriptor

    override fun deserialize(decoder: Decoder): T = try {
        body.deserialize(JsonPathDecoder(decoder as JsonDecoder, element, path, fieldPath))
    } catch (error: SerializationException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw SerializationException("$path: ${error.message}", error)
    }
}

internal class JsonPathDecoder(
    private val decoder: JsonDecoder,
    private val element: JsonElement,
    val path: String,
    private val fieldPath: (String) -> String = { "$path.$it" },
) : JsonDecoder by decoder {
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
        deserializer.deserialize(this)

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? =
        if (deserializer.descriptor.isNullable || decodeNotNullMark()) {
            decodeSerializableValue(deserializer)
        } else {
            decodeNull()
        }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        val inline = decoder.decodeInline(descriptor)
        return if (inline is JsonDecoder) JsonPathDecoder(inline, element, path, fieldPath) else inline
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val composite = decoder.beginStructure(descriptor)
        val entries = if (descriptor.kind == StructureKind.MAP) element.jsonObject.entries.toList() else emptyList()
        fun child(index: Int): Pair<JsonElement, String> = when (descriptor.kind) {
            StructureKind.LIST -> element.jsonArray[index] to "$path[$index]"

            StructureKind.MAP -> {
                val entry = entries[index / 2]
                (if (index % 2 == 0) JsonPrimitive(entry.key) else entry.value) to "$path.${entry.key}"
            }

            else -> {
                val name = descriptor.getElementName(index)
                (element.jsonObject[name] ?: JsonNull) to fieldPath(name)
            }
        }
        return object : CompositeDecoder by composite {
            override fun <T> decodeSerializableElement(
                descriptor: SerialDescriptor,
                index: Int,
                deserializer: DeserializationStrategy<T>,
                previousValue: T?,
            ): T {
                val (value, childPath) = child(index)
                return composite.decodeSerializableElement(
                    descriptor,
                    index,
                    JsonPathSerializer(deserializer, value, childPath),
                    previousValue,
                )
            }

            override fun <T : Any> decodeNullableSerializableElement(
                descriptor: SerialDescriptor,
                index: Int,
                deserializer: DeserializationStrategy<T?>,
                previousValue: T?,
            ): T? {
                val (value, childPath) = child(index)
                return composite.decodeNullableSerializableElement(
                    descriptor,
                    index,
                    JsonPathSerializer(deserializer, value, childPath),
                    previousValue,
                )
            }
        }
    }
}
