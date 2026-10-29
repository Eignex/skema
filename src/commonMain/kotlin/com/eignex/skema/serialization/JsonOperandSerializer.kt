@file:OptIn(
    kotlinx.serialization.ExperimentalSerializationApi::class,
    kotlinx.serialization.SealedSerializationApi::class,
)

package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonEncoder

internal class JsonOperandSerializer(private val body: KSerializer<Any>) : KSerializer<Any> by body {
    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as JsonEncoder
        body.serialize(
            object : JsonEncoder by jsonEncoder {
                override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
                    val composite = encoder.beginStructure(descriptor)
                    return object : CompositeEncoder by composite {
                        override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean =
                            true

                        override fun <T : Any> encodeNullableSerializableElement(
                            descriptor: SerialDescriptor,
                            index: Int,
                            serializer: SerializationStrategy<T>,
                            value: T?,
                        ) {
                            val nullable = object : SerializationStrategy<T?> {
                                override val descriptor: SerialDescriptor = serializer.descriptor.nullable

                                override fun serialize(encoder: Encoder, value: T?) {
                                    if (value == null) {
                                        encoder.encodeNull()
                                    } else {
                                        encoder.encodeSerializableValue(serializer, value)
                                    }
                                }
                            }
                            composite.encodeSerializableElement(descriptor, index, nullable, value)
                        }
                    }
                }
            },
            value,
        )
    }
}
