package com.eignex.skema.serialization

import com.eignex.skema.SchemaJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private sealed interface JsonBodyValue

@Serializable
private data class JsonBodyRecord(val value: Int) : JsonBodyValue

private object JsonBodySerializer : JsonTransformingSerializer<JsonBodyRecord>(JsonBodyRecord.serializer())

class JsonBodySerializerTest {
    @Test
    fun `JSON body serializers support positional operands`() {
        val serializer = CompactSerializer("JsonBodyOperand", JsonBodyValue::class) {
            listOf(
                JsonNode(
                    JsonBodyRecord::class,
                    "operand",
                    JsonNodeShape.Operand,
                    listOf("value"),
                ) { JsonBodySerializer },
            )
        }
        val value = JsonBodyRecord(7)
        val encoded = SchemaJson.encodeToString(serializer, value)
        assertEquals("""{"operand":7}""", encoded)
        assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
    }

    @Test
    fun `JSON body serializers support fixed fields`() {
        val serializer = CompactSerializer("JsonBodyFixed", JsonBodyValue::class) {
            listOf(
                JsonNode(
                    JsonBodyRecord::class,
                    "fixed",
                    fixed = mapOf("value" to JsonPrimitive(7)),
                ) { JsonBodySerializer },
            )
        }
        val value = JsonBodyRecord(7)
        val encoded = SchemaJson.encodeToString(serializer, value)
        assertEquals("\"fixed\"", encoded)
        assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
    }
}
