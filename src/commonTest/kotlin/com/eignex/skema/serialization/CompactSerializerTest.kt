@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
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
private data class NumberLiteral(val value: Double) : Expression

@Serializable
private data class LongLiteral(val value: Long) : Expression

@Serializable
private data class Negate(val value: Expression) : Expression

@Serializable
private data class Plus(val left: Expression, val right: Expression) : Expression

@Serializable
private data class Offset(val amount: Double = 1.5) : Expression

@Serializable
private data class DefaultOperand(val value: Expression = Input) : Expression

@Serializable
private data class DefaultOperands(val left: Expression = Input, val right: Expression = Input) : Expression

@Serializable
private data class DefaultLiteral(val value: Int = 7) : Expression

@Serializable
private data class NullableOperand(val value: Expression? = null) : Expression

@Serializable
private data class NullableOperands(val left: Expression? = null, val right: Expression? = Input) : Expression

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
private data class Counted(val value: Expression) : Expression

@Serializable
private data class Wrapped(val children: List<Map<String, Expression?>>) : Expression

@Serializable
private data class Enveloped(val envelope: Envelope) : Expression

@Serializable
private data class PrimitiveRecord(val values: List<Map<String, PrimitiveValues>>) : Expression

@Serializable
private data class PrimitiveValues(val byte: Byte, val short: Short, val int: Int, val long: Long, val char: Char)

private object CountingMapping : JsonPayloadMapping {
    var calls: Int = 0

    override fun encode(body: JsonObject): JsonElement = body.getValue("value")

    override fun decode(payload: JsonElement): JsonObject {
        calls++
        return JsonObject(mapOf("value" to payload))
    }
}

@Serializable
private data class Configured(val options: Options, val inner: Expression) : Expression

@Serializable
private data class Options(val mode: Mode, val values: List<Map<String, Double?>>)

@Serializable
private enum class Mode { Fast, Accurate }

private val expressionCatalog = JsonNodeCatalog { listOf(EnvelopeSerializer) }

private object ExpressionSerializer : CompactSerializer<Expression>(
    "Expression",
    Expression::class,
    expressionCatalog,
    nodes = {
        listOf(
            jsonNode("input") { Input.serializer() },
            jsonNode<NumberLiteral>("number", JsonNodeShape.Literal, "value"),
            jsonNode<Negate>("negate", JsonNodeShape.Operand, "value"),
            jsonNode<Plus>("plus", JsonNodeShape.Operands, "left", "right"),
            jsonNode<Offset>("offset"),
            jsonNode<Configured>("configured"),
            jsonNode<DefaultOperand>("defaultOperand", JsonNodeShape.Operand, "value"),
            jsonNode<DefaultOperands>("defaultOperands", JsonNodeShape.Operands, "left", "right"),
            jsonNode<NullableOperand>("nullable", JsonNodeShape.Operand, "value"),
            jsonNode<NullableOperands>("nullableOperands", JsonNodeShape.Operands, "left", "right"),
            jsonNode<Renamed>("renamed", JsonNodeShape.Operand, "operand"),
            jsonNode<InlineWrapped>("inline"),
            jsonNode<Positive>("positive"),
            jsonNode<Flagged>("flagged"),
            jsonNode<Counted>("counted", mapping = CountingMapping),
            jsonNode<Wrapped>("wrapped"),
            jsonNode<Enveloped>("envelope", JsonNodeShape.Operand, "envelope"),
            jsonNode<PrimitiveRecord>("primitives"),
        )
    },
)

@Serializable(with = MappedSerializer::class)
private sealed interface Mapped

@Serializable
private data class MappedOptions(val options: Options) : Mapped

private object OptionsMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body.getValue("options")

    override fun decode(payload: JsonElement): JsonObject = JsonObject(mapOf("options" to payload))
}

private object FlaggedMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonArray(
        listOf(body.getValue("name"), body["negated"] ?: JsonPrimitive(false)),
    )

    override fun decode(payload: JsonElement): JsonObject {
        if (payload !is JsonArray || payload.size != 2) throw SerializationException("expected [name, negated]")
        return JsonObject(mapOf("name" to payload[0], "negated" to payload[1]))
    }
}

private object MappedSerializer : CompactSerializer<Mapped>("Mapped", Mapped::class, nodes = {
    listOf(jsonNode<MappedOptions>("mapped", mapping = OptionsMapping))
})

@Serializable(with = ChoiceSerializer::class)
private sealed interface Choice

@Serializable
private data class TextChoice(val text: String) : Choice

@Serializable
private data class NumericChoice(val number: Int) : Choice

private val choiceCatalog = JsonNodeCatalog(
    resolve = { candidates, payload ->
        candidates.single { it.type == if ("text" in payload.jsonObject) TextChoice::class else NumericChoice::class }
    },
) { listOf(ChoiceSerializer) }

private object ChoiceSerializer : CompactSerializer<Choice>("Choice", Choice::class, choiceCatalog, {
    listOf(jsonNode<TextChoice>("choice"), jsonNode<NumericChoice>("choice"))
})

@Serializable(with = ComparisonSerializer::class)
private sealed interface Comparison

@Serializable
private data class Bound(val value: Int) : Comparison

@Serializable
private data class Compare(val left: Comparison, val op: CompareOp, val right: Comparison) : Comparison

@Serializable
private data class Reference(val name: String, val negated: Boolean = false) : Comparison

@Serializable
private data class Interval(val low: Int, val high: Int, val closed: Boolean = true) : Comparison

@Serializable
private enum class CompareOp { Less, Greater, Equal }

private object ComparisonSerializer : CompactSerializer<Comparison>("Comparison", Comparison::class, nodes = {
    listOf(
        jsonNode<Bound>("bound", JsonNodeShape.Literal, "value"),
        jsonNode<Compare>("lt", JsonNodeShape.Operands, "left", "right", fixed = mapOf("op" to JsonPrimitive("Less"))),
        jsonNode<Compare>(
            "gt",
            JsonNodeShape.Operands,
            "left",
            "right",
            fixed = mapOf("op" to JsonPrimitive("Greater")),
        ),
        jsonNode<Reference>("ref", JsonNodeShape.Operand, "name", fixed = mapOf("negated" to JsonPrimitive(false))),
        jsonNode<Reference>("notRef", JsonNodeShape.Operand, "name", fixed = mapOf("negated" to JsonPrimitive(true))),
        jsonNode<Interval>("closed", fixed = mapOf("closed" to JsonPrimitive(true))),
        jsonNode<Interval>("open", fixed = mapOf("closed" to JsonPrimitive(false))),
    )
})

@Serializable(with = EnvelopeSerializer::class)
private sealed interface Envelope

@Serializable
private data class Box(val expression: Expression?) : Envelope

@Serializable
private data class GenericBox<T>(val value: T) : Envelope

private val envelopeCatalog = JsonNodeCatalog { listOf(ExpressionSerializer) }

private object EnvelopeSerializer : CompactSerializer<Envelope>("Envelope", Envelope::class, envelopeCatalog, {
    listOf(
        jsonNode<Box>("box"),
        jsonNode("generic") { GenericBox.serializer(ExpressionSerializer) },
    )
})

class CompactSerializerTest {
    @Test
    fun `built-in shapes round trip through schema JSON`() {
        val cases = listOf(
            Input to "\"input\"",
            NumberLiteral(2.5) to "2.5",
            Negate(Input) to """{"negate":"input"}""",
            Plus(Input, NumberLiteral(2.5)) to """{"plus":["input",2.5]}""",
            Offset(2.5) to """{"offset":{"amount":2.5}}""",
        )

        for ((expression, expected) in cases) {
            assertEquals(expected, SchemaJson.encodeToString<Expression>(expression))
            assertEquals(expression, SchemaJson.decodeFromString<Expression>(expected))
        }
    }

    @Test
    fun `schema definitions preserve compact entry values`() {
        val definition = SchemaDef<Expression>(mapOf("result" to Plus(Input, NumberLiteral(2.5))))
        val serializer = SchemaDef.serializer(ExpressionSerializer)

        val encoded = SchemaJson.encodeToString(serializer, definition)

        assertEquals("""{"entries":{"result":{"plus":["input",2.5]}}}""", encoded)
        assertEquals(definition, SchemaJson.decodeFromString(serializer, encoded))
    }

    @Test
    fun `encode defaults controls shorthand without changing the operator key`() {
        val defaults = Json { encodeDefaults = true }
        val expression: Expression = Offset()

        assertEquals("\"offset\"", Json.encodeToString(expression))
        assertEquals("""{"offset":{"amount":1.5}}""", defaults.encodeToString(expression))
        assertEquals(expression, Json.decodeFromString<Expression>(defaults.encodeToString(expression)))
    }

    @Test
    fun `built-in operands retain defaults and nulls under suppressed JSON defaults`() {
        val cases = listOf(
            DefaultOperand() to """{"defaultOperand":"input"}""",
            DefaultOperand(Offset()) to """{"defaultOperand":"offset"}""",
            DefaultOperands() to """{"defaultOperands":["input","input"]}""",
            DefaultLiteral() to "7",
            NullableOperand() to """{"nullable":null}""",
        )

        for ((value, expected) in cases) {
            val serializer = if (value is DefaultLiteral) {
                CompactSerializer("DefaultLiteral", Expression::class) {
                    listOf(jsonNode<DefaultLiteral>("defaultLiteral", JsonNodeShape.Literal, "value"))
                }
            } else {
                ExpressionSerializer
            }
            val encoded = SchemaJson.encodeToString(serializer, value)
            assertEquals(expected, encoded)
            assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
        }
    }

    @Test
    fun `positional null operands retain their positions`() {
        val cases = listOf(
            NullableOperands() to """{"nullableOperands":[null,"input"]}""",
            NullableOperands(Input, null) to """{"nullableOperands":["input",null]}""",
            NullableOperands(null, null) to """{"nullableOperands":[null,null]}""",
        )

        for ((value, expected) in cases) {
            val encoded = SchemaJson.encodeToString<Expression>(value)

            assertEquals(expected, encoded)
            assertEquals(value, SchemaJson.decodeFromString<Expression>(encoded))
        }
    }

    @Test
    fun `positional operands follow the declared wire order`() {
        val serializer = CompactSerializer("Expression", Expression::class) {
            listOf(jsonNode<Plus>("reversed", JsonNodeShape.Operands, "right", "left"))
        }
        val value = Plus(Input, NumberLiteral(2.5))

        val encoded = SchemaJson.encodeToString(serializer, value)

        assertEquals("""{"reversed":[2.5,"input"]}""", encoded)
        assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
    }

    @Test
    fun `operands use serialized field names`() {
        val value: Expression = Renamed(Plus(Input, NumberLiteral(2.5)))

        val encoded = SchemaJson.encodeToString(value)

        assertEquals("""{"renamed":{"plus":["input",2.5]}}""", encoded)
        assertEquals(value, SchemaJson.decodeFromString<Expression>(encoded))
    }

    @Test
    fun `long literals retain values beyond binary64 integer precision`() {
        val serializer = CompactSerializer("LongExpression", Expression::class) {
            listOf(jsonNode<LongLiteral>("long", JsonNodeShape.Literal, "value"))
        }
        for (number in listOf(Long.MIN_VALUE, -9007199254740993L, 9007199254740993L, Long.MAX_VALUE)) {
            val value = LongLiteral(number)

            val encoded = SchemaJson.encodeToString(serializer, value)

            assertEquals(number.toString(), encoded)
            assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
        }
    }

    @Test
    fun `integral number tokens decode by value and encode as integer tokens`() {
        val cases = mapOf(
            "2.0" to 2,
            "1e2" to 100,
            "1.5E+1" to 15,
            "100e-2" to 1,
            "-0.0" to 0,
            "2147483647.0" to Int.MAX_VALUE,
            "-2.147483648e9" to Int.MIN_VALUE,
        )
        for ((token, number) in cases) {
            val decoded = SchemaJson.decodeFromString<Comparison>("""{"lt":[$token,{"ref":"x"}]}""")

            assertEquals(Compare(Bound(number), CompareOp.Less, Reference("x")), decoded, token)
            assertEquals("""{"lt":[$number,{"ref":"x"}]}""", SchemaJson.encodeToString(decoded), token)
        }
    }

    @Test
    fun `integral long tokens decode exactly beyond binary64 integer precision`() {
        val serializer = CompactSerializer("LongExpression", Expression::class) {
            listOf(jsonNode<LongLiteral>("long", JsonNodeShape.Literal, "value"))
        }
        val cases = mapOf(
            "9007199254740993.0" to 9007199254740993L,
            "9.007199254740993e15" to 9007199254740993L,
            "9.223372036854775807e18" to Long.MAX_VALUE,
            "-9223372036854775808.000" to Long.MIN_VALUE,
        )

        for ((token, number) in cases) {
            assertEquals(LongLiteral(number), SchemaJson.decodeFromString(serializer, token), token)
        }
    }

    @Test
    fun `integer literals reject fractional quoted and out of range tokens`() {
        for (token in listOf("2.5", "1e-1", "\"2\"", "2147483648.0", "2.147483648e9", "-2147483649", "1e400")) {
            assertFailsWith<SerializationException>(token) {
                SchemaJson.decodeFromString<Comparison>("""{"lt":[$token,{"ref":"x"}]}""")
            }
        }
    }

    @Test
    fun `mixed expressions round trip across JSON default and null policies`() {
        val value: Expression = Wrapped(
            listOf(
                linkedMapOf(
                    "empty" to null,
                    "defaults" to DefaultOperands(NullableOperand(), Offset()),
                    "flags" to Flagged("enabled", true),
                    "inline" to InlineWrapped(InlineExpression(Renamed(Input))),
                ),
                mapOf("nested" to Enveloped(Box(Plus(Input, NumberLiteral(-2.5))))),
            ),
        )
        for (defaults in listOf(false, true)) {
            for (nulls in listOf(false, true)) {
                val json = Json {
                    encodeDefaults = defaults
                    explicitNulls = nulls
                }

                val encoded = json.encodeToString(value)

                assertEquals(value, json.decodeFromString<Expression>(encoded))
            }
        }
    }

    @Test
    fun `explicit body serializers retain generic compact operands`() {
        val value: Envelope = GenericBox<Expression>(Plus(Input, NumberLiteral(2.5)))

        val encoded = SchemaJson.encodeToString(value)

        assertEquals("""{"generic":{"value":{"plus":["input",2.5]}}}""", encoded)
        assertEquals(value, SchemaJson.decodeFromString<Envelope>(encoded))
    }

    @Test
    fun `lazy catalogs support mutually recursive vocabularies`() {
        val value: Expression = Enveloped(Box(Enveloped(GenericBox<Expression>(Input))))

        val encoded = SchemaJson.encodeToString(value)

        assertEquals("""{"envelope":{"box":{"expression":{"envelope":{"generic":{"value":"input"}}}}}}""", encoded)
        assertEquals(value, SchemaJson.decodeFromString<Expression>(encoded))
    }

    @Test
    fun `constructor invariants become serialization errors`() {
        val error = assertFailsWith<SerializationException> {
            SchemaJson.decodeFromString<Expression>("""{"plus":["input",{"positive":{"value":0}}]}""")
        }

        assertTrue("value must be positive" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `positional operands reject wrong payload kinds and arities`() {
        val payloads = listOf("null", "{}", "\"input\"", "[]", "[\"input\"]", "[\"input\",\"input\",\"input\"]")

        for (payload in payloads) {
            assertFailsWithMessage("'plus' expects 2 operands", payload) {
                SchemaJson.decodeFromString<Expression>("""{"plus":$payload}""")
            }
        }
    }

    @Test
    fun `operator values reject empty multi key and non object payloads`() {
        val payloads = listOf("{}", "[]", "true", "false", "null", """{"input":{},"offset":{}}""")

        for (payload in payloads) {
            assertFailsWithMessage("Expression operator", payload) { SchemaJson.decodeFromString<Expression>(payload) }
        }
    }

    @Test
    fun `numeric literals require a registered literal node`() {
        val serializer = CompactSerializer("OnlyInput", Expression::class) { listOf(jsonNode<Input>("input")) }

        val error = assertFailsWith<SerializationException> { SchemaJson.decodeFromString(serializer, "2.5") }

        assertEquals("OnlyInput has no numeric literals", error.message)
    }

    @Test
    fun `encoding rejects implementations absent from the vocabulary`() {
        val serializer = CompactSerializer("OnlyInput", Expression::class) { listOf(jsonNode<Input>("input")) }

        val error = assertFailsWith<SerializationException> { SchemaJson.encodeToString(serializer, Offset(2.5)) }

        assertTrue("unsupported OnlyInput implementation" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `literal declarations reject text and nullable fields`() {
        val text = CompactSerializer("TextLiteral", Choice::class) {
            listOf(jsonNode<TextChoice>("text", JsonNodeShape.Literal, "text"))
        }
        val nullable = CompactSerializer("NullableLiteral", Expression::class) {
            listOf(jsonNode<NullableOperand>("nullable", JsonNodeShape.Literal, "value"))
        }

        val errors = listOf(
            assertFailsWith<IllegalArgumentException> { SchemaJson.encodeToString(text, TextChoice("a")) },
            assertFailsWith<IllegalArgumentException> { SchemaJson.encodeToString(nullable, NullableOperand()) },
        )
        for (error in errors) {
            assertTrue("requires a non-null numeric field" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `built-in operand declarations reject uncovered and unknown body fields`() {
        val cases = listOf(
            jsonNode<Flagged>("flagged", JsonNodeShape.Operand, "name"),
            jsonNode<Flagged>("flagged", JsonNodeShape.Operands, "name"),
            jsonNode<Flagged>("flagged", JsonNodeShape.Operands, "name", "missing"),
        )

        for (node in cases) {
            val serializer = CompactSerializer("InvalidFields", Expression::class) { listOf(node) }
            val error = assertFailsWith<IllegalArgumentException> {
                SchemaJson.encodeToString(serializer, Flagged("enabled", true))
            }
            assertTrue("operands must cover every body field" in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `recursive mappings decode once per node`() {
        val depth = 32
        var value: Expression = Input
        repeat(depth) { value = Counted(value) }
        val encoded = SchemaJson.encodeToString(ExpressionSerializer, value)
        CountingMapping.calls = 0

        val decoded = SchemaJson.decodeFromString(ExpressionSerializer, encoded)

        assertEquals(value, decoded)
        assertEquals(depth, CountingMapping.calls)
    }

    @Test
    fun `primitive boundary values round trip`() {
        val values = listOf(
            PrimitiveValues(Byte.MIN_VALUE, Short.MIN_VALUE, Int.MIN_VALUE, Long.MIN_VALUE, '\u0000'),
            PrimitiveValues(Byte.MAX_VALUE, Short.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, '\uffff'),
        )
        for (value in values) {
            val expression: Expression = PrimitiveRecord(listOf(mapOf("item" to value)))

            val encoded = SchemaJson.encodeToString(expression)

            assertEquals(expression, SchemaJson.decodeFromString<Expression>(encoded))
        }
    }

    @Test
    fun `records lists maps enums and nullable numbers round trip`() {
        val expression: Expression = Configured(Options(Mode.Fast, listOf(mapOf("a" to 2.5, "b" to null))), Input)

        val encoded = Json.encodeToString(expression)

        assertEquals(expression, Json.decodeFromString<Expression>(encoded))
    }

    @Test
    fun `invalid record members are rejected`() {
        val payloads = listOf(
            """{"configured":{"inner":"input","options":{"mode":"Unknown","values":[]}}}""",
            """{"configured":{"inner":"input","options":{"mode":"Fast","values":[{"a":"x"}]}}}""",
            """{"configured":{"options":{"mode":"Fast","values":[]}}}""",
            """{"configured":{"inner":"input","options":{"mode":"Fast","values":[]},"extra":1}}""",
        )

        for (payload in payloads) {
            assertFailsWith<SerializationException>(payload) { Json.decodeFromString<Expression>(payload) }
        }
    }

    @Test
    fun `custom payload mappings round trip ordinary fields`() {
        val value: Mapped = MappedOptions(Options(Mode.Fast, emptyList()))
        val expected = """{"mapped":{"mode":"Fast","values":[]}}"""

        assertEquals(expected, Json.encodeToString(value))
        assertEquals(value, Json.decodeFromString<Mapped>(expected))
    }

    @Test
    fun `custom array mappings preserve additional option fields`() {
        val serializer = CompactSerializer("MappedFlags", Expression::class) {
            listOf(jsonNode<Flagged>("flag", mapping = FlaggedMapping))
        }
        for (negated in listOf(false, true)) {
            val value = Flagged("enabled", negated)

            val encoded = SchemaJson.encodeToString(serializer, value)

            assertEquals("""{"flag":["enabled",$negated]}""", encoded)
            assertEquals(value, SchemaJson.decodeFromString(serializer, encoded))
        }
    }

    @Test
    fun `catalog resolver distinguishes implementations sharing an operator`() {
        val cases = listOf<Choice>(TextChoice("hello"), NumericChoice(3))

        for (choice in cases) {
            assertEquals(choice, Json.decodeFromString<Choice>(Json.encodeToString(choice)))
        }
    }

    @Test
    fun `ambiguous operators require a catalog resolver`() {
        val serializer = CompactSerializer("Unresolved", Choice::class) { ChoiceSerializer.nodes }

        val error = assertFailsWith<SerializationException> {
            Json.decodeFromString(serializer, """{"choice":{"number":3}}""")
        }

        assertEquals("ambiguous operator 'choice'", error.message)
    }

    @Test
    fun `catalog resolvers receive the candidates and payload`() {
        val cases = listOf(
            ChoiceSerializer.nodes to JsonObject(mapOf("choice" to JsonObject(mapOf("number" to JsonPrimitive(3))))),
            listOf(
                jsonNode<NumericChoice>("first", JsonNodeShape.Literal, "number"),
                jsonNode<NumericChoice>("second", JsonNodeShape.Literal, "number"),
            ) to JsonPrimitive(3),
        )
        for ((nodes, element) in cases) {
            var receivedNodes: List<JsonNode>? = null
            var receivedPayload: JsonElement? = null
            val catalog = JsonNodeCatalog(resolve = { candidates, payload ->
                receivedNodes = candidates
                receivedPayload = payload
                candidates.last()
            })
            val serializer = CompactSerializer("ResolverInput", Choice::class, catalog) { nodes }

            assertEquals(nodes.last(), serializer.selectNode(element))
            assertEquals(nodes, receivedNodes)
            assertEquals(if (element is JsonObject) element.getValue("choice") else element, receivedPayload)
        }
    }

    @Test
    fun `fixed fields select operator keys for one implementation`() {
        val cases = listOf(
            Compare(Bound(1), CompareOp.Less, Reference("x")) to """{"lt":[1,{"ref":"x"}]}""",
            Compare(Bound(1), CompareOp.Greater, Bound(2)) to """{"gt":[1,2]}""",
            Reference("x", negated = true) to """{"notRef":"x"}""",
            Interval(1, 2) to """{"closed":{"low":1,"high":2}}""",
            Interval(1, 2, closed = false) to """{"open":{"low":1,"high":2}}""",
        )

        for ((comparison, expected) in cases) {
            assertEquals(expected, SchemaJson.encodeToString<Comparison>(comparison))
            assertEquals(comparison, SchemaJson.decodeFromString<Comparison>(expected))
        }
    }

    @Test
    fun `encoding rejects values without a matching fixed node`() {
        val error = assertFailsWith<SerializationException> {
            SchemaJson.encodeToString<Comparison>(Compare(Bound(1), CompareOp.Equal, Bound(2)))
        }

        assertTrue(error.message.orEmpty().startsWith("no unique Comparison node matches"), error.message)
    }

    @Test
    fun `fixed fields are rejected inside payloads`() {
        assertFailsWithMessage("'closed' does not accept field 'closed'") {
            SchemaJson.decodeFromString<Comparison>("""{"closed":{"low":1,"high":2,"closed":false}}""")
        }
    }

    @Test
    fun `fixed declarations reject operand and unknown fields`() {
        assertFailsWith<IllegalArgumentException> {
            jsonNode<Reference>("ref", JsonNodeShape.Operand, "name", fixed = mapOf("name" to JsonPrimitive("x")))
        }
        val serializer = CompactSerializer("UnknownFixed", Comparison::class) {
            listOf(jsonNode<Bound>("bound", JsonNodeShape.Literal, "value", fixed = mapOf("missing" to JsonNull)))
        }

        val error = assertFailsWith<IllegalArgumentException> { SchemaJson.encodeToString(serializer, Bound(1)) }

        assertTrue("fixes unknown fields" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `binary formats retain one sealed tag per fixed implementation`() {
        val comparisons = listOf(
            Compare(Bound(1), CompareOp.Equal, Reference("x", negated = true)),
            Interval(1, 2, closed = false),
        )

        for (comparison in comparisons) {
            val cbor = Cbor.encodeToByteArray(ComparisonSerializer, comparison)
            val proto = ProtoBuf.encodeToByteArray(ComparisonSerializer, comparison)

            assertEquals(comparison, Cbor.decodeFromByteArray(ComparisonSerializer, cbor))
            assertEquals(comparison, ProtoBuf.decodeFromByteArray(ComparisonSerializer, proto))
        }
    }

    @Test
    fun `catalogs resolve the same descriptor independently`() {
        val first = CompactSerializer("Expression", Expression::class) { listOf(jsonNode<Input>("first")) }
        val second = CompactSerializer("Expression", Expression::class) { listOf(jsonNode<Input>("second")) }
        val firstCatalog = JsonNodeCatalog { listOf(first) }
        val secondCatalog = JsonNodeCatalog { listOf(second) }

        assertEquals(first, firstCatalog.find(ExpressionSerializer.descriptor))
        assertEquals(second, secondCatalog.find(ExpressionSerializer.descriptor))
        assertEquals(Input, Json.decodeFromString(first, "\"first\""))
        assertEquals(Input, Json.decodeFromString(second, "\"second\""))
        assertFailsWith<SerializationException> { Json.decodeFromString(first, "\"second\"") }
    }

    private val binaryExpressions = listOf(
        Input, NumberLiteral(2.5), Negate(Input), Plus(Input, NumberLiteral(2.5)),
        DefaultOperand(), DefaultOperands(), NullableOperand(), NullableOperands(),
        Flagged("enabled", true), InlineWrapped(InlineExpression(Input)), Enveloped(Box(Input)),
    )

    @Test
    fun `CBOR retains the sealed representation`() {
        for (expression in binaryExpressions) {
            val encoded = Cbor.encodeToByteArray(ExpressionSerializer, expression)

            assertEquals(expression, Cbor.decodeFromByteArray(ExpressionSerializer, encoded))
        }
    }

    @Test
    fun `ProtoBuf retains the sealed representation`() {
        for (expression in binaryExpressions) {
            val encoded = ProtoBuf.encodeToByteArray(ExpressionSerializer, expression)

            assertEquals(expression, ProtoBuf.decodeFromByteArray(ExpressionSerializer, encoded))
        }
    }
}

private fun assertFailsWithMessage(expected: String, message: String? = null, block: () -> Unit) {
    val error = assertFailsWith<SerializationException>(message, block)
    assertTrue(expected in error.message.orEmpty(), error.message)
}
