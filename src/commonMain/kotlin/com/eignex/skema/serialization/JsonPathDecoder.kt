@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.InternalSerializationApi::class,
    kotlinx.serialization.SealedSerializationApi::class,
)

package com.eignex.skema.serialization

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.AbstractPolymorphicSerializer
import kotlinx.serialization.json.*

/**
 * Strictly decodes [element], reporting errors at paths from the document root.
 * [catalog] must include all nested compact vocabularies. Integer fields accept integral
 * tokens by value; integer map keys require decimal digits within the key type's range,
 * with an optional minus sign for signed types. Delegated compact descriptors use the
 * catalog's canonical serializer. Content-based polymorphic serializers
 * and custom polymorphic serializers without an exposed subtype are rejected.
 * JSON transforming serializers decode their own input with this Json, as they would
 * without strict decoding; compact vocabularies they contain still validate themselves.
 */
fun <T> Json.decodeFromJsonElement(
    deserializer: DeserializationStrategy<T>,
    element: JsonElement,
    catalog: JsonNodeCatalog,
): T {
    val shape = JsonShape(deserializer as? CompactSerializer<*>, catalog, this)
    return decodeFromJsonElement(JsonPathSerializer(deserializer, element, "$", shape), element)
}

internal class JsonPathSerializer<T>(
    private val body: DeserializationStrategy<T>,
    private val element: JsonElement,
    private val path: String,
    private val shape: JsonShape,
    private val fieldPath: (String) -> String = { "$path.$it" },
    private val mapKey: Boolean = false,
) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor get() = body.descriptor

    override fun deserialize(decoder: Decoder): T = withJsonPath(path) {
        JsonPathDecoder(decoder as JsonDecoder, element, path, shape, fieldPath, mapKey).decodeSerializableValue(body)
    }
}

internal class JsonPathDecoder(
    private val decoder: JsonDecoder,
    private val element: JsonElement,
    val path: String,
    private val shape: JsonShape,
    private val fieldPath: (String) -> String = { "$path.$it" },
    private val mapKey: Boolean = false,
) : JsonDecoder by decoder {
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        // Transformers read their raw input and decode the result outside this decoder.
        if (deserializer is JsonTransformingSerializer<*>) return deserializer.deserialize(this)
        // The nullable wrapper delegates back here with its value serializer.
        if (deserializer::class != nullableSerializerClass) {
            val checked = if (mapKey) {
                shape.validateMapKey(deserializer.descriptor, element.jsonPrimitive.content, path)
                element
            } else {
                shape.validateJson(deserializer.descriptor, element, path, fieldPath)
            }
            // Normalized children must reach Json's tree decoder, so decode the checked copy.
            if (checked !== element) {
                return json.decodeFromJsonElement(
                    JsonPathSerializer(deserializer, checked, path, shape, fieldPath, mapKey),
                    checked,
                )
            }
        }
        return decodeChecked(deserializer)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> decodeChecked(deserializer: DeserializationStrategy<T>): T =
        if (deserializer is JsonContentPolymorphicSerializer<*>) {
            invalid(path, "Content-based polymorphic serializers do not expose their subtype for strict decoding")
        } else if (deserializer is AbstractPolymorphicSerializer<*>) {
            decodePolymorphic(deserializer) as T
        } else if (deserializer.requiresExposedSubtype()) {
            val compact = shape.compactSerializer(deserializer.descriptor)
            if (compact == null || compact.descriptor !== deserializer.descriptor) {
                invalid(path, "Custom polymorphic serializers do not expose their subtype for strict decoding")
            }
            // Reuse the registered decoder so wrappers cannot bypass compact payload validation.
            compact.deserialize(this) as T
        } else {
            // ContextualSerializer resolves its type and decodes it back through this decoder.
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
            val discriminator = deserializer.descriptor.classDiscriminator(json)
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
        return if (inline is JsonDecoder) JsonPathDecoder(inline, element, path, shape, fieldPath, mapKey) else inline
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        val composite = decoder.beginStructure(descriptor)
        val mapElements = if (descriptor.kind == StructureKind.MAP) JsonMapElements(element, path) else null
        fun valueAt(index: Int): Pair<JsonElement, String> = when (descriptor.kind) {
            StructureKind.LIST -> element.jsonArray[index] to "$path[$index]"

            StructureKind.MAP -> mapElements!!.child(index)

            else -> {
                val name = shape.fieldName(descriptor, index, element.jsonObject, path)
                (element.jsonObject[name] ?: JsonNull) to fieldPath(name)
            }
        }
        fun isMapKey(index: Int) = mapElements?.isObjectKey(index) == true
        fun <T> child(index: Int, deserializer: DeserializationStrategy<T>): JsonPathSerializer<T> {
            val (value, childPath) = valueAt(index)
            return JsonPathSerializer(deserializer, value, childPath, shape, mapKey = isMapKey(index))
        }

        // Json reads primitive elements directly, without a serializer passing through this decoder.
        fun check(index: Int) {
            val (value, childPath) = valueAt(index)
            val child = descriptor.getElementDescriptor(index)
            if (isMapKey(index)) {
                shape.validateMapKey(child, value.jsonPrimitive.content, childPath)
            } else {
                shape.validateJson(child, value, childPath)
            }
        }
        return object : CompositeDecoder by composite {
            override fun decodeBooleanElement(descriptor: SerialDescriptor, index: Int): Boolean {
                check(index)
                return composite.decodeBooleanElement(descriptor, index)
            }

            override fun decodeByteElement(descriptor: SerialDescriptor, index: Int): Byte {
                check(index)
                return composite.decodeByteElement(descriptor, index)
            }

            override fun decodeShortElement(descriptor: SerialDescriptor, index: Int): Short {
                check(index)
                return composite.decodeShortElement(descriptor, index)
            }

            override fun decodeIntElement(descriptor: SerialDescriptor, index: Int): Int {
                check(index)
                return composite.decodeIntElement(descriptor, index)
            }

            override fun decodeLongElement(descriptor: SerialDescriptor, index: Int): Long {
                check(index)
                return composite.decodeLongElement(descriptor, index)
            }

            override fun decodeFloatElement(descriptor: SerialDescriptor, index: Int): Float {
                check(index)
                return composite.decodeFloatElement(descriptor, index)
            }

            override fun decodeDoubleElement(descriptor: SerialDescriptor, index: Int): Double {
                check(index)
                return composite.decodeDoubleElement(descriptor, index)
            }

            override fun decodeCharElement(descriptor: SerialDescriptor, index: Int): Char {
                check(index)
                return composite.decodeCharElement(descriptor, index)
            }

            override fun decodeStringElement(descriptor: SerialDescriptor, index: Int): String {
                check(index)
                return composite.decodeStringElement(descriptor, index)
            }

            override fun decodeInlineElement(descriptor: SerialDescriptor, index: Int): Decoder {
                check(index)
                return composite.decodeInlineElement(descriptor, index)
            }

            override fun <T> decodeSerializableElement(
                descriptor: SerialDescriptor,
                index: Int,
                deserializer: DeserializationStrategy<T>,
                previousValue: T?,
            ): T = composite.decodeSerializableElement(descriptor, index, child(index, deserializer), previousValue)

            override fun <T : Any> decodeNullableSerializableElement(
                descriptor: SerialDescriptor,
                index: Int,
                deserializer: DeserializationStrategy<T?>,
                previousValue: T?,
            ): T? = composite.decodeNullableSerializableElement(
                descriptor,
                index,
                child(index, deserializer),
                previousValue,
            )
        }
    }
}

// Recognize the standard nullable wrapper through its public factory. It delegates back
// through this decoder, whereas JSON transformers can decode their delegate outside it.
private val nullableSerializerClass = String.serializer().nullable::class

private fun DeserializationStrategy<*>.requiresExposedSubtype(): Boolean {
    if (descriptor.kind !is PolymorphicKind || this is CompactSerializer<*> || this::class == nullableSerializerClass) {
        return false
    }
    return descriptor.nonNullableSerialName() !in setOf(
        JsonElement.serializer().descriptor.serialName,
        JsonPrimitive.serializer().descriptor.serialName,
        JsonNull.serializer().descriptor.serialName,
    )
}
