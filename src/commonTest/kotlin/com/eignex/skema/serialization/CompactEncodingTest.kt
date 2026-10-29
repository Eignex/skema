@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private sealed interface EncodingValue

@Serializable
private data class TransformedRecord(val value: Int) : EncodingValue

@Serializable
private data class TransformedDefaults(val count: Int = 7, val optional: String? = null) : EncodingValue

private object TransformedRecordSerializer : JsonTransformingSerializer<TransformedRecord>(
    TransformedRecord.serializer(),
) {
    override fun transformSerialize(element: JsonElement): JsonElement = element
}

private object TransformedDefaultsSerializer : JsonTransformingSerializer<TransformedDefaults>(
    TransformedDefaults.serializer(),
) {
    override fun transformSerialize(element: JsonElement): JsonElement = element
}

@Serializable
private data class InlineUnsignedRecord(val byte: UByte, val short: UShort, val int: UInt, val long: ULong) :
    EncodingValue

private object InlineUnsignedRecordSerializer : KSerializer<InlineUnsignedRecord> by InlineUnsignedRecord.serializer() {
    override fun serialize(encoder: Encoder, value: InlineUnsignedRecord) {
        val composite = encoder.beginStructure(descriptor)
        composite.encodeInlineElement(descriptor, 0).encodeByte(value.byte.toByte())
        composite.encodeInlineElement(descriptor, 1).encodeShort(value.short.toShort())
        composite.encodeInlineElement(descriptor, 2).encodeInt(value.int.toInt())
        composite.encodeInlineElement(descriptor, 3).encodeLong(value.long.toLong())
        composite.endStructure(descriptor)
    }
}

private object CountingOperandSerializer : KSerializer<Int> by Int.serializer() {
    var calls: Int = 0

    override fun serialize(encoder: Encoder, value: Int) {
        calls++
        encoder.encodeInt(value)
    }
}

@Serializable
private data class CapturedRecord(
    val fixed: Boolean,
    @Serializable(with = CountingOperandSerializer::class) val operand: Int,
) : EncodingValue

class CompactEncodingTest {
    @Test
    fun `JSON transforming bodies retain JSON encoders for every built-in shape`() {
        val value = TransformedRecord(7)
        val cases = listOf(
            JsonNodeShape.Fields to """{"node":{"value":7}}""",
            JsonNodeShape.Operand to """{"node":7}""",
            JsonNodeShape.Operands to """{"node":[7]}""",
            JsonNodeShape.Literal to "7",
        )
        for ((shape, expected) in cases) {
            val serializer = CompactSerializer("TransformedRecord", EncodingValue::class) {
                listOf(
                    JsonNode(TransformedRecord::class, "node", shape, listOf("value")) {
                        TransformedRecordSerializer
                    },
                )
            }

            val encoded = Json.encodeToString(serializer, value)

            assertEquals(expected, encoded, shape.toString())
            assertEquals(value, Json.decodeFromString(serializer, encoded), shape.toString())
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
    fun `JSON transforming bodies retain emitted defaults and null operands`() {
        val json = Json {
            encodeDefaults = true
            explicitNulls = true
        }
        val serializer = transformedDefaultsSerializer()
        val value = TransformedDefaults()

        val encoded = json.encodeToString(serializer, value)

        assertEquals("""{"defaults":[7,null]}""", encoded)
        assertEquals(value, json.decodeFromString(serializer, encoded))
    }

    @Test
    fun `JSON transforming bodies must emit their operands`() {
        val error = assertFailsWith<SerializationException> {
            Json.encodeToString(transformedDefaultsSerializer(), TransformedDefaults())
        }

        assertTrue("operand 'count' was omitted" in error.message.orEmpty(), error.message)
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

    @Test
    fun `fixed selection skips other generated fields`() {
        val serializer = CompactSerializer("CapturedRecord", EncodingValue::class) {
            listOf(jsonNode<CapturedRecord>("captured", fixed = mapOf("fixed" to JsonPrimitive(true))))
        }
        val value = CapturedRecord(fixed = true, operand = 5)
        CountingOperandSerializer.calls = 0

        val encoded = Json.encodeToString(serializer, value)

        assertEquals("""{"captured":{"operand":5}}""", encoded)
        assertEquals(1, CountingOperandSerializer.calls)
        assertEquals(value, Json.decodeFromString(serializer, encoded))
    }

    private fun transformedDefaultsSerializer(): CompactSerializer<EncodingValue> =
        CompactSerializer("TransformedDefaults", EncodingValue::class) {
            listOf(
                JsonNode(TransformedDefaults::class, "defaults", JsonNodeShape.Operands, listOf("count", "optional")) {
                    TransformedDefaultsSerializer
                },
            )
        }
}
