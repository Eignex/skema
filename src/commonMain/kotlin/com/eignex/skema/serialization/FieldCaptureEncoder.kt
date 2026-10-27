@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule

// Reads only the named top-level fields, so selecting a node never encodes its other operands.
internal class FieldCaptureEncoder(private val json: Json, private val fields: Set<String>) : AbstractEncoder() {
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
