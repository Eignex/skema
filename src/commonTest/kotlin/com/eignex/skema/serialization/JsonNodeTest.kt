@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonNodeTest {
    @Serializable
    private sealed interface EncodingValue

    @Serializable
    private data class TransformedDefaults(val count: Int = 7, val optional: String? = null) : EncodingValue

    private object TransformedDefaultsSerializer : JsonTransformingSerializer<TransformedDefaults>(
        TransformedDefaults.serializer(),
    ) {
        override fun transformSerialize(element: JsonElement): JsonElement = element
    }

    @Test
    fun `JSON transforming bodies must emit their operands`() {
        val error = assertFailsWith<SerializationException> {
            Json.encodeToString(transformedDefaultsSerializer(), TransformedDefaults())
        }

        assertTrue("operand 'count' was omitted" in error.message.orEmpty(), error.message)
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
