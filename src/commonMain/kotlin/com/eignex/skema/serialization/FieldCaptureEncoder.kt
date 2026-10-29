@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.SealedSerializationApi::class,
)

package com.eignex.skema.serialization

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule

// Structured body serializers can skip other operands while capturing the named top-level fields.
internal class FieldCaptureEncoder(override val json: Json, private val fields: Set<String>) :
    AbstractEncoder(),
    JsonEncoder {
    val values = mutableMapOf<String, JsonElement>()
    private var depth = 0
    private var field: String? = null

    override val serializersModule: SerializersModule get() = json.serializersModule

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        depth++
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        depth--
    }

    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = true

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        field = descriptor.getElementName(index).takeIf { depth == 1 && it in fields }
        return field != null
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        val name = field
        if (name == null) {
            serializer.serialize(this, value)
        } else {
            values[name] = json.encodeToJsonElement(serializer, value)
        }
    }

    override fun encodeNull() {
        field?.let { values[it] = JsonNull }
    }

    override fun encodeJsonElement(element: JsonElement) {
        val name = field
        if (name != null) {
            values[name] = element
        } else {
            values.putAll(element.objectAt("$").filterKeys { it in fields })
        }
    }

    override fun encodeInline(descriptor: SerialDescriptor): Encoder {
        if (!descriptor.isInline) return this
        if (descriptor.serialName !in setOf("kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong")) {
            return this
        }
        return object : AbstractEncoder() {
            override val serializersModule: SerializersModule get() = json.serializersModule

            // JS represents Byte, Short and Int as numbers, so runtime type checks lose their width.
            override fun encodeByte(value: Byte) {
                field?.let { values[it] = JsonPrimitive(value.toUByte()) }
            }

            override fun encodeShort(value: Short) {
                field?.let { values[it] = JsonPrimitive(value.toUShort()) }
            }

            override fun encodeInt(value: Int) {
                field?.let { values[it] = JsonPrimitive(value.toUInt()) }
            }

            override fun encodeLong(value: Long) {
                field?.let { values[it] = JsonPrimitive(value.toULong()) }
            }
        }
    }

    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) {
        field?.let { values[it] = JsonPrimitive(enumDescriptor.getElementName(index)) }
    }

    override fun encodeValue(value: Any) {
        val name = field ?: return
        values[name] = when (value) {
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
