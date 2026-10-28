@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaJson
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private sealed interface EdgeValue

@Serializable private data class EdgeFixedUnsigned(val tag: UInt) : EdgeValue

@Serializable
private data class EdgeNeverDefault(@EncodeDefault(EncodeDefault.Mode.NEVER) val value: Int = 7) : EdgeValue

@Serializable @JvmInline
private value class EdgeNullableInline(val value: String?)

@Serializable private data class EdgeInline(val value: EdgeNullableInline) : EdgeValue

@Serializable @JvmInline
private value class EdgeNullableInt(val value: Int?)

@Serializable private data class EdgeNumericInline(val value: EdgeNullableInt) : EdgeValue

@Serializable private data class EdgeNumber(val value: Double) : EdgeValue

@Serializable private data class EdgeInteger(val value: Int) : EdgeValue

@Serializable private data class EdgeNamed(val camelCase: String) : EdgeValue

/** Regressions for inline values, omitted fields, configuration guards and literal resolution. */
class CompactEdgeCasesTest {
    @Test fun fixedUnsignedFieldsSelectTheirNode() {
        val serializer = CompactSerializer("EdgeFixed", EdgeValue::class) {
            listOf(jsonNode<EdgeFixedUnsigned>("max", fixed = mapOf("tag" to JsonPrimitive(UInt.MAX_VALUE))))
        }
        val value = EdgeFixedUnsigned(UInt.MAX_VALUE)
        val encoded = SchemaJson.encodeToString(serializer, value)
        assertEquals("\"max\"", encoded)
        assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
    }

    @Test fun neverDefaultPositionalOperandsReportOmissions() {
        val serializer = CompactSerializer("EdgeNeverDefault", EdgeValue::class) {
            listOf(jsonNode<EdgeNeverDefault>("operand", JsonNodeShape.Operand, "value"))
        }
        val error = assertFailsWith<SerializationException> {
            SchemaJson.encodeToString(serializer, EdgeNeverDefault())
        }
        assertTrue("operand 'value' was omitted" in error.message.orEmpty(), error.message)
        assertTrue("@EncodeDefault(NEVER)" in error.message.orEmpty(), error.message)
        val value = EdgeNeverDefault(8)
        val encoded = SchemaJson.encodeToString(serializer, value)
        assertEquals("""{"operand":8}""", encoded)
        assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
    }

    @Test fun neverDefaultFixedFieldsReportOmissions() {
        val serializer = CompactSerializer("EdgeNeverFixed", EdgeValue::class) {
            listOf(jsonNode<EdgeNeverDefault>("fixed", fixed = mapOf("value" to JsonPrimitive(7))))
        }
        val error = assertFailsWith<SerializationException> {
            SchemaJson.encodeToString(serializer, EdgeNeverDefault())
        }
        assertTrue("fixed fields [value] were omitted" in error.message.orEmpty(), error.message)
        assertTrue("@EncodeDefault(NEVER)" in error.message.orEmpty(), error.message)
    }

    @Test fun nullableInlineValuesRoundTrip() {
        val serializer = CompactSerializer("EdgeInline", EdgeValue::class) {
            listOf(jsonNode<EdgeInline>("inline"))
        }
        val json = Json { explicitNulls = true }
        val value = EdgeInline(EdgeNullableInline(null))
        val encoded = json.encodeToString(serializer, value)
        assertEquals(value, json.decodeFromString(serializer, encoded))
    }

    @Test fun nullableNumericInlineValuesRoundTrip() {
        val serializer = CompactSerializer("EdgeNumericInline", EdgeValue::class) {
            listOf(jsonNode<EdgeNumericInline>("numericInline"))
        }
        val json = Json { explicitNulls = true }
        val value = EdgeNumericInline(EdgeNullableInt(null))
        val encoded = json.encodeToJsonElement(serializer, value)
        assertEquals(value, json.decodeFromJsonElement(serializer, encoded))
    }

    @Test fun literalAmbiguityUsesCatalogResolver() {
        val catalog = JsonNodeCatalog(resolve = { candidates, _, _ -> candidates.first() })
        val serializer = CompactSerializer("EdgeLiterals", EdgeValue::class, catalog) {
            listOf(
                jsonNode<EdgeNumber>("number", JsonNodeShape.Literal, "value"),
                jsonNode<EdgeInteger>("integer", JsonNodeShape.Literal, "value"),
            )
        }
        val encoded = SchemaJson.encodeToString(serializer, EdgeNumber(2.5))
        assertEquals(EdgeNumber(2.5), SchemaJson.decodeFromString(serializer, encoded))
    }

    @Test fun compactFieldsRejectUnsupportedJsonConfigurations() {
        val serializer = CompactSerializer("EdgeNamed", EdgeValue::class) {
            listOf(jsonNode<EdgeNamed>("named"))
        }
        val cases = listOf(
            Json { namingStrategy = JsonNamingStrategy.SnakeCase } to "naming strategies",
            Json { classDiscriminatorMode = ClassDiscriminatorMode.ALL_JSON_OBJECTS } to "polymorphic",
            Json { classDiscriminatorMode = ClassDiscriminatorMode.NONE } to "polymorphic",
        )
        for ((json, message) in cases) {
            val encoding = assertFailsWith<SerializationException> {
                json.encodeToString(serializer, EdgeNamed("test"))
            }
            val decoding = assertFailsWith<SerializationException> {
                json.decodeFromString(serializer, """{"named":{"camelCase":"test"}}""")
            }
            assertTrue(message in encoding.message.orEmpty(), encoding.message)
            assertTrue(message in decoding.message.orEmpty(), decoding.message)
        }
    }

    @Test fun nullableInlineLiteralDeclarationsAreRejected() {
        val serializer = CompactSerializer("InvalidInlineLiteral", EdgeValue::class) {
            listOf(jsonNode<EdgeNumericInline>("literal", JsonNodeShape.Literal, "value"))
        }
        val error = assertFailsWith<IllegalArgumentException> { serializer.descriptor }
        assertTrue("non-null numeric field" in error.message.orEmpty(), error.message)
    }

    @Test fun literalAmbiguityWithoutResolverIsExplicit() {
        val serializer = CompactSerializer("UnresolvedLiterals", EdgeValue::class) {
            listOf(
                jsonNode<EdgeNumber>("number", JsonNodeShape.Literal, "value"),
                jsonNode<EdgeInteger>("integer", JsonNodeShape.Literal, "value"),
            )
        }
        val error = assertFailsWith<SerializationException> {
            SchemaJson.decodeFromString(serializer, "2.5")
        }
        assertTrue("ambiguous" in error.message.orEmpty(), error.message)
    }
}
