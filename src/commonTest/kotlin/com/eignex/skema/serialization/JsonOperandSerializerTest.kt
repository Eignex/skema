@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonOperandSerializerTest {
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

    private fun transformedDefaultsSerializer(): CompactSerializer<EncodingValue> =
        CompactSerializer("TransformedDefaults", EncodingValue::class) {
            listOf(
                JsonNode(TransformedDefaults::class, "defaults", JsonNodeShape.Operands, listOf("count", "optional")) {
                    TransformedDefaultsSerializer
                },
            )
        }
}
