@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class FixedFieldSelectionTest {
    @Serializable
    private sealed interface EncodingValue

    @Serializable
    private data class TransformedRecord(val value: Int) : EncodingValue

    private object TransformedRecordSerializer : JsonTransformingSerializer<TransformedRecord>(
        TransformedRecord.serializer(),
    ) {
        override fun transformSerialize(element: JsonElement): JsonElement = element
    }

    @Serializable
    private data class InlineUnsignedRecord(val byte: UByte, val short: UShort, val int: UInt, val long: ULong) :
        EncodingValue

    private object InlineUnsignedRecordSerializer :
        KSerializer<InlineUnsignedRecord> by InlineUnsignedRecord.serializer() {
        override fun serialize(encoder: Encoder, value: InlineUnsignedRecord) {
            val composite = encoder.beginStructure(descriptor)
            composite.encodeInlineElement(descriptor, 0).encodeByte(value.byte.toByte())
            composite.encodeInlineElement(descriptor, 1).encodeShort(value.short.toShort())
            composite.encodeInlineElement(descriptor, 2).encodeInt(value.int.toInt())
            composite.encodeInlineElement(descriptor, 3).encodeLong(value.long.toLong())
            composite.endStructure(descriptor)
        }
    }

    @Test
    fun `JSON transforming bodies can select fixed fields`() {
        val serializer = CompactSerializer("TransformedFixed", EncodingValue::class) {
            listOf(
                JsonNode(TransformedRecord::class, "fixed", fixed = mapOf("value" to JsonPrimitive(7))) {
                    TransformedRecordSerializer
                },
            )
        }
        val value = TransformedRecord(7)

        val encoded = Json.encodeToString(serializer, value)

        assertEquals("\"fixed\"", encoded)
        assertEquals(value, Json.decodeFromString(serializer, encoded))
    }

    @Test
    fun `fixed inline unsigned fields retain their full ranges`() {
        val value = InlineUnsignedRecord(UByte.MAX_VALUE, UShort.MAX_VALUE, UInt.MAX_VALUE, ULong.MAX_VALUE)
        val fixed = mapOf(
            "byte" to JsonPrimitive(value.byte),
            "short" to JsonPrimitive(value.short),
            "int" to JsonPrimitive(value.int),
            "long" to JsonPrimitive(value.long),
        )
        for (body in listOf(InlineUnsignedRecord.serializer(), InlineUnsignedRecordSerializer)) {
            val serializer = CompactSerializer("FixedUnsigned", EncodingValue::class) {
                listOf(JsonNode(InlineUnsignedRecord::class, "maximum", fixed = fixed) { body })
            }

            val encoded = Json.encodeToString(serializer, value)

            assertEquals("\"maximum\"", encoded)
            assertEquals(value, Json.decodeFromString(serializer, encoded))
        }
    }
}
