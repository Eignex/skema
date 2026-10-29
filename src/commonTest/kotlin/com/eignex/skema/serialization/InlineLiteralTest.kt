package com.eignex.skema.serialization

import com.eignex.skema.SchemaJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private sealed interface InlineLiteral

@Serializable @JvmInline
private value class FloatAmount(val value: Float)

@Serializable @JvmInline
private value class DoubleAmount(val value: Double)

@Serializable
private data class FloatLiteral(val amount: FloatAmount) : InlineLiteral

@Serializable
private data class DoubleLiteral(val amount: DoubleAmount) : InlineLiteral

class InlineLiteralTest {
    @Test
    fun `inline floating point literals round trip with numeric schemas`() {
        val cases = listOf(
            jsonNode<FloatLiteral>("float", JsonNodeShape.Literal, "amount") to FloatLiteral(FloatAmount(1.5f)),
            jsonNode<DoubleLiteral>("double", JsonNodeShape.Literal, "amount") to DoubleLiteral(DoubleAmount(1.5)),
        )
        for ((node, value) in cases) {
            val serializer = CompactSerializer("InlineLiteral", InlineLiteral::class) { listOf(node) }
            val encoded = SchemaJson.encodeToString(serializer, value)
            assertEquals("1.5", encoded)
            assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
            val document = serializer.toJsonSchema()
            val ref = document.getValue("\$ref").jsonPrimitive.content.substringAfterLast('/')
            val literal = document.getValue("\$defs").jsonObject.getValue(ref).jsonObject
            assertEquals(JsonPrimitive("number"), literal["type"])
        }
    }
}
