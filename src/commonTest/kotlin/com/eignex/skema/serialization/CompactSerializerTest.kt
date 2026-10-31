@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable(with = ExpressionSerializer::class)
private sealed interface Expression

@Serializable
private data object Input : Expression

@Serializable
@JvmInline
private value class Amount(val value: Double)

@Serializable
private data class NumberLiteral(val value: Amount) : Expression

@Serializable
private data class Negate(val value: Expression) : Expression

@Serializable
private data class Plus(val left: Expression, val right: Expression) : Expression

@Serializable
private data class Offset(val amount: Double = 1.5) : Expression

@Serializable
private data class Defaults(val left: Expression = Input, val right: Expression? = null) : Expression

@Serializable
private data class Renamed(@SerialName("operand") val value: Expression) : Expression

@Serializable
@JvmInline
private value class InlineExpression(val value: Expression)

@Serializable
private data class InlineWrapped(val value: InlineExpression) : Expression

@Serializable
private data class Positive(val value: Int) : Expression {
    init {
        require(value > 0) { "value must be positive" }
    }
}

@Serializable
private data class Flagged(val name: String, val negated: Boolean = false) : Expression

@Serializable
private data class Enveloped(val envelope: Envelope) : Expression

private object FlaggedMapping : JsonPayloadMapping {
    // Custom mappings see the body as the caller's Json encodes it, so defaults may be absent.
    override fun encode(body: JsonObject): JsonElement =
        JsonArray(listOf(body.getValue("name"), body["negated"] ?: JsonPrimitive(false)))

    override fun decode(payload: JsonElement): JsonObject =
        JsonObject(mapOf("name" to payload.jsonArray[0], "negated" to payload.jsonArray[1]))
}

private object ExpressionSerializer : CompactSerializer<Expression>("Expression", Expression::class, nodes = {
    listOf(
        jsonNode<Input>("input"),
        jsonNode<NumberLiteral>("number", JsonNodeShape.Literal, "value"),
        jsonNode<Negate>("negate", JsonNodeShape.Operand, "value"),
        jsonNode<Plus>("plus", JsonNodeShape.Operands, "left", "right"),
        jsonNode<Offset>("offset"),
        jsonNode<Defaults>("defaults", JsonNodeShape.Operands, "left", "right"),
        jsonNode<Renamed>("renamed", JsonNodeShape.Operand, "operand"),
        jsonNode<InlineWrapped>("inline"),
        jsonNode<Positive>("positive"),
        jsonNode<Flagged>("flag", mapping = FlaggedMapping),
        jsonNode<Enveloped>("envelope", JsonNodeShape.Operand, "envelope"),
    )
})

@Serializable(with = EnvelopeSerializer::class)
private sealed interface Envelope

@Serializable
private data class Box(val expression: Expression?) : Envelope

@Serializable
private data class GenericBox<T>(val value: T) : Envelope

private object EnvelopeSerializer : CompactSerializer<Envelope>("Envelope", Envelope::class, nodes = {
    listOf(jsonNode<Box>("box"), jsonNode("generic") { GenericBox.serializer(ExpressionSerializer) })
})

@Serializable(with = ComparisonSerializer::class)
private sealed interface Comparison

@Serializable
private data class Bound(val value: Int) : Comparison

@Serializable
private data class LongBound(val value: Long) : Comparison

@Serializable
private data class Compare(val left: Comparison, val op: CompareOp, val right: Comparison) : Comparison

@Serializable
private data class Reference(val name: String, val negated: Boolean = false) : Comparison

@Serializable
private data class Interval(val low: Int, val high: Int, val closed: Boolean = true) : Comparison

@Serializable
private enum class CompareOp { Less, Greater, Equal }

private fun fixed(field: String, value: String) = mapOf(field to JsonPrimitive(value))

private fun fixed(field: String, value: Boolean) = mapOf(field to JsonPrimitive(value))

private object ComparisonSerializer : CompactSerializer<Comparison>("Comparison", Comparison::class, nodes = {
    listOf(
        jsonNode<Bound>("bound", JsonNodeShape.Literal, "value"),
        jsonNode<Compare>("lt", JsonNodeShape.Operands, "left", "right", fixed = fixed("op", "Less")),
        jsonNode<Compare>("gt", JsonNodeShape.Operands, "left", "right", fixed = fixed("op", "Greater")),
        jsonNode<Reference>("ref", JsonNodeShape.Operand, "name", fixed = fixed("negated", false)),
        jsonNode<Reference>("notRef", JsonNodeShape.Operand, "name", fixed = fixed("negated", true)),
        jsonNode<Interval>("closed", fixed = fixed("closed", true)),
        jsonNode<Interval>("open", fixed = fixed("closed", false)),
    )
})

private val longBoundSerializer = CompactSerializer("LongBound", Comparison::class) {
    listOf(jsonNode<LongBound>("long", JsonNodeShape.Literal, "value"))
}

@Serializable(with = ChoiceSerializer::class)
private sealed interface Choice

@Serializable
private data class TextChoice(val text: String) : Choice

@Serializable
private data class NumericChoice(val number: Int) : Choice

private val choiceNodes = listOf(jsonNode<TextChoice>("choice"), jsonNode<NumericChoice>("choice"))

private object ChoiceSerializer : CompactSerializer<Choice>(
    "Choice",
    Choice::class,
    JsonNodeCatalog(resolve = { candidates, payload ->
        candidates.single { it.type == if ("text" in payload.jsonObject) TextChoice::class else NumericChoice::class }
    }),
    { choiceNodes },
)

@Suppress("UNCHECKED_CAST")
private fun KSerializer<*>.erased() = this as KSerializer<Any>

class CompactSerializerTest {
    private val wires = listOf(
        ExpressionSerializer.erased() to listOf(
            Input to "\"input\"",
            NumberLiteral(Amount(2.5)) to "2.5",
            Negate(Input) to """{"negate":"input"}""",
            Plus(Input, NumberLiteral(Amount(2.5))) to """{"plus":["input",2.5]}""",
            Offset(2.5) to """{"offset":{"amount":2.5}}""",
            Offset() to "\"offset\"",
            Defaults() to """{"defaults":["input",null]}""",
            Defaults(Offset(), Input) to """{"defaults":["offset","input"]}""",
            Renamed(Input) to """{"renamed":"input"}""",
            InlineWrapped(InlineExpression(Negate(Input))) to """{"inline":{"value":{"negate":"input"}}}""",
            Flagged("a", negated = true) to """{"flag":["a",true]}""",
            Flagged("a") to """{"flag":["a",false]}""",
            Enveloped(Box(null)) to """{"envelope":"box"}""",
            Enveloped(Box(Enveloped(GenericBox<Expression>(Input)))) to
                """{"envelope":{"box":{"expression":{"envelope":{"generic":{"value":"input"}}}}}}""",
        ),
        ComparisonSerializer.erased() to listOf(
            Compare(Bound(1), CompareOp.Less, Reference("x")) to """{"lt":[1,{"ref":"x"}]}""",
            Compare(Bound(1), CompareOp.Greater, Bound(2)) to """{"gt":[1,2]}""",
            Reference("x", negated = true) to """{"notRef":"x"}""",
            Interval(1, 2) to """{"closed":{"low":1,"high":2}}""",
            Interval(1, 2, closed = false) to """{"open":{"low":1,"high":2}}""",
        ),
        ChoiceSerializer.erased() to listOf(
            TextChoice("hello") to """{"choice":{"text":"hello"}}""",
            NumericChoice(3) to """{"choice":{"number":3}}""",
        ),
    )

    @Test
    fun `every shape round trips through its compact wire form`() {
        val policies = listOf(false, true).flatMap { defaults ->
            listOf(false, true).map { nulls ->
                Json {
                    encodeDefaults = defaults
                    explicitNulls = nulls
                }
            }
        }
        for ((serializer, cases) in wires) {
            for ((value, wire) in cases) {
                assertEquals(wire, SchemaJson.encodeToString(serializer, value))
                assertEquals(value, SchemaJson.decodeFromString(serializer, wire))
                for (json in policies) {
                    assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
                }
            }
        }
        val defaults = Json { encodeDefaults = true }
        assertEquals("""{"offset":{"amount":1.5}}""", defaults.encodeToString<Expression>(Offset()))
        val definition = SchemaDef<Expression>(mapOf("result" to Negate(Input)))
        val serializer = SchemaDef.serializer(ExpressionSerializer)
        assertEquals("""{"entries":{"result":{"negate":"input"}}}""", SchemaJson.encodeToString(serializer, definition))
    }

    @Test
    fun `binary formats keep the sealed representation`() {
        for ((serializer, cases) in wires) {
            for ((value, _) in cases) {
                assertEquals(value, Cbor.decodeFromByteArray(serializer, Cbor.encodeToByteArray(serializer, value)))
                assertEquals(
                    value,
                    ProtoBuf.decodeFromByteArray(serializer, ProtoBuf.encodeToByteArray(serializer, value)),
                )
            }
        }
    }

    @Test
    fun `integer literals accept integral tokens exactly and reject everything else`() {
        val accepted = mapOf(
            "2.0" to 2,
            "1e2" to 100,
            "1.5E+1" to 15,
            "100e-2" to 1,
            "-0.0" to 0,
            "2147483647.0" to Int.MAX_VALUE,
            "-2.147483648e9" to Int.MIN_VALUE,
        )
        for ((token, number) in accepted) {
            val decoded = SchemaJson.decodeFromString<Comparison>("""{"lt":[$token,{"ref":"x"}]}""")
            assertEquals("""{"lt":[$number,{"ref":"x"}]}""", SchemaJson.encodeToString(decoded), token)
        }
        val long = SchemaJson.decodeFromString(longBoundSerializer, "9.223372036854775807e18")
        assertEquals(LongBound(Long.MAX_VALUE), long)
        for (token in listOf("2.5", "1e-1", "\"2\"", "2147483648.0", "2.147483648e9", "-2147483649", "1e400")) {
            assertFailsWith<SerializationException>(token) {
                SchemaJson.decodeFromString<Comparison>("""{"lt":[$token,{"ref":"x"}]}""")
            }
        }
    }

    @Test
    fun `malformed payloads name what is wrong`() {
        val unresolved = CompactSerializer("Unresolved", Choice::class) { choiceNodes }
        val cases = listOf(
            Triple(ExpressionSerializer, """{"plus":["input"]}""", "'plus' expects 2 operands"),
            Triple(ExpressionSerializer, """{"plus":{"left":"input","right":"input"}}""", "'plus' expects 2 operands"),
            Triple(ExpressionSerializer, """{"offset":[]}""", "'offset' expects an object"),
            Triple(ExpressionSerializer, "{}", "expected exactly one operator"),
            Triple(ExpressionSerializer, """{"input":{},"offset":{}}""", "expected exactly one operator"),
            Triple(ExpressionSerializer, "true", "expected an operator"),
            Triple(ExpressionSerializer, "\"bogus\"", "unknown operator 'bogus'"),
            Triple(ExpressionSerializer, """{"plus":["input",{"positive":{"value":0}}]}""", "value must be positive"),
            Triple(EnvelopeSerializer, "2.5", "unexpected number 2.5"),
            Triple(
                ComparisonSerializer,
                """{"open":{"low":1,"high":2,"closed":true}}""",
                "'open' does not accept field 'closed'",
            ),
            Triple(unresolved, """{"choice":{"number":3}}""", "ambiguous operator 'choice'"),
        )
        for ((serializer, payload, message) in cases) {
            val error = assertFailsWith<SerializationException>(payload) {
                SchemaJson.decodeFromString(serializer.erased(), payload)
            }
            assertTrue(message in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `encoding rejects values the vocabulary cannot represent`() {
        val onlyInput = CompactSerializer("OnlyInput", Expression::class) { listOf(jsonNode<Input>("input")) }

        assertFailsWith<SerializationException> { SchemaJson.encodeToString(onlyInput, Offset()) }
        assertFailsWith<SerializationException> {
            SchemaJson.encodeToString<Comparison>(Compare(Bound(1), CompareOp.Equal, Bound(2)))
        }
    }

    @Test
    fun `invalid node declarations fail on first use`() {
        assertFailsWith<IllegalArgumentException> {
            jsonNode<Reference>("ref", JsonNodeShape.Operand, "name", fixed = fixed("name", "x"))
        }
        val nodes = listOf(
            jsonNode<TextChoice>("text", JsonNodeShape.Literal, "text"),
            jsonNode<Box>("box", JsonNodeShape.Literal, "expression"),
            jsonNode<Flagged>("flag", JsonNodeShape.Operand, "name"),
            jsonNode<Flagged>("flag", JsonNodeShape.Operands, "name", "missing"),
            jsonNode<Bound>("bound", JsonNodeShape.Literal, "value", fixed = mapOf("missing" to JsonNull)),
        )
        for (node in nodes) {
            assertFailsWith<IllegalArgumentException>(node.name) { node.serializer }
        }
    }

    @Test
    fun `catalog resolvers receive the candidates and payload`() {
        var received: Pair<List<JsonNode>, JsonElement>? = null
        val nodes = listOf(
            jsonNode<NumericChoice>("first", JsonNodeShape.Literal, "number"),
            jsonNode<NumericChoice>("second", JsonNodeShape.Literal, "number"),
        )
        val catalog = JsonNodeCatalog(resolve = { candidates, payload ->
            received = candidates to payload
            candidates.last()
        })
        val serializer = CompactSerializer("Literals", Choice::class, catalog) { nodes }

        assertEquals(nodes.last(), serializer.selectNode(JsonPrimitive(3)))
        assertEquals(nodes to JsonPrimitive(3), received)
    }
}
