@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
    kotlinx.serialization.SealedSerializationApi::class,
)

package com.eignex.skema.serialization

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.json.*

internal class JsonPathSerializer<T>(
    private val body: DeserializationStrategy<T>,
    private val element: JsonElement,
    private val path: String,
    private val shape: JsonShape,
    private val fieldPath: (String) -> String = { "$path.$it" },
) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor get() = body.descriptor

    override fun deserialize(decoder: Decoder): T = try {
        JsonPathDecoder(decoder as JsonDecoder, element, path, shape, fieldPath).decodeSerializableValue(body)
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
    private val shape: JsonShape,
    private val fieldPath: (String) -> String = { "$path.$it" },
) : JsonDecoder by decoder {
    @Suppress("UNCHECKED_CAST")
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T =
        if (deserializer is AbstractPolymorphicSerializer<*>) {
            decodePolymorphic(deserializer) as T
        } else {
            deserializer.deserialize(this)
        }

    private fun <T : Any> decodePolymorphic(deserializer: AbstractPolymorphicSerializer<T>): T {
        val type: JsonElement?
        val typePath: String
        val body: JsonElement
        val bodyPath: String
        if (json.configuration.useArrayPolymorphism) {
            val array = element as? JsonArray ?: invalid(path, "expected a polymorphic array")
            if (array.size != 2) invalid(path, "expected a type name and value")
            type = array[0]
            typePath = "$path[0]"
            body = array[1]
            bodyPath = "$path[1]"
        } else {
            val discriminator = deserializer.descriptor.annotations.filterIsInstance<JsonClassDiscriminator>()
                .firstOrNull()?.discriminator ?: json.configuration.classDiscriminator
            val record = element.objectAt(path)
            type = record[discriminator]
            typePath = "$path.$discriminator"
            body = JsonObject(record - discriminator)
            bodyPath = path
        }
        val hasType = type != null && type != JsonNull
        if ((json.configuration.useArrayPolymorphism || hasType) && (type !is JsonPrimitive || !type.isString)) {
            invalid(typePath, "expected a type name")
        }
        val name = (type as? JsonPrimitive)?.contentOrNull
        val selected = deserializer.findPolymorphicSerializerOrNull(this, name)
            ?: invalid(typePath, "unknown polymorphic type '${name ?: "<missing>"}'")
        shape.validateJson(selected.descriptor, body, bodyPath)
        return json.decodeFromJsonElement(JsonPathSerializer(selected, body, bodyPath, shape), body)
    }

    override fun <T : Any> decodeNullableSerializableValue(deserializer: DeserializationStrategy<T?>): T? =
        if (deserializer.descriptor.isNullable || decodeNotNullMark()) {
            decodeSerializableValue(deserializer)
        } else {
            decodeNull()
        }

    override fun decodeInline(descriptor: SerialDescriptor): Decoder {
        val inline = decoder.decodeInline(descriptor)
        return if (inline is JsonDecoder) JsonPathDecoder(inline, element, path, shape, fieldPath) else inline
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
                    JsonPathSerializer(deserializer, value, childPath, shape),
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
                    JsonPathSerializer(deserializer, value, childPath, shape),
                    previousValue,
                )
            }
        }
    }
}
