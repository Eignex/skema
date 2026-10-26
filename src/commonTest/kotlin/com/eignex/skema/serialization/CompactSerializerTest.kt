@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.protobuf.ProtoBuf
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
private data class Negate(val value: Expression) : Expression

@Serializable
private data class Plus(val left: Expression, val right: Expression) : Expression

@Serializable
private data class Offset(val amount: Double = 1.5) : Expression

@Serializable
private data class Configured(val options: Options, val inner: Expression) : Expression

@Serializable
private data class Options(val mode: Mode, val values: List<Map<String, Double?>>)

@Serializable
private enum class Mode { Fast, Accurate }

private object ExpressionSerializer : CompactSerializer<Expression>("Expression", Expression::class, nodes = {
    listOf(
        jsonNode("input") { Input.serializer() },
        jsonNode<NumberLiteral>("number", JsonNodeShape.Literal, "value"),
        jsonNode<Negate>("negate", JsonNodeShape.Operand, "value"),
        jsonNode<Plus>("plus", JsonNodeShape.Operands, "left", "right"),
        jsonNode<Offset>("offset"),
        jsonNode<Configured>("configured"),
    )
})

@Serializable(with = MappedSerializer::class)
private sealed interface Mapped

@Serializable
private data class MappedOptions(val options: Options) : Mapped

private object OptionsMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body.getValue("options")

    override fun decode(payload: JsonElement, path: String): JsonObject = JsonObject(mapOf("options" to payload))

    override fun fieldPath(field: String, path: String): String = path
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
    resolve = { candidates, payload, _ ->
        candidates.single { it.type == if ("text" in payload.jsonObject) TextChoice::class else NumericChoice::class }
    },
) { listOf(ChoiceSerializer) }

private object ChoiceSerializer : CompactSerializer<Choice>("Choice", Choice::class, choiceCatalog, {
    listOf(jsonNode<TextChoice>("choice"), jsonNode<NumericChoice>("choice"))
})

@Serializable(with = EnvelopeSerializer::class)
private sealed interface Envelope

@Serializable
private data class Box(val expression: Expression?) : Envelope

private val envelopeCatalog = JsonNodeCatalog { listOf(ExpressionSerializer) }

private object EnvelopeSerializer : CompactSerializer<Envelope>("Envelope", Envelope::class, envelopeCatalog, {
    listOf(jsonNode<Box>("box"))
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
    fun `malformed recursive operands report compact paths under lenient settings`() {
        val lenient = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }
        val cases = listOf(
            """{"plus":["input"]}""" to "$.plus",
            """{"plus":["input",{"offset":{"amount":"2"}}]}""" to "$.plus[1].offset.amount",
            """{"negate":{"offset":{"extra":1}}}""" to "$.negate.offset.extra",
            """{"plus":["input",{"missing":{}}]}""" to "$.plus[1].missing",
            """{"type":"Input"}""" to "$.type",
        )

        for ((payload, path) in cases) {
            val error = assertFailsWith<SerializationException> { lenient.decodeFromString<Expression>(payload) }
            assertTrue(path in error.message.orEmpty(), error.message)
        }
    }

    @Test
    fun `records lists maps enums and nullable numbers round trip`() {
        val expression: Expression = Configured(Options(Mode.Fast, listOf(mapOf("a" to 2.5, "b" to null))), Input)

        val encoded = Json.encodeToString(expression)

        assertEquals(expression, Json.decodeFromString<Expression>(encoded))
    }

    @Test
    fun `invalid record members report their nested paths`() {
        val cases = listOf(
            """{"configured":{"inner":"input","options":{"mode":"Unknown","values":[]}}}""" to
                "$.configured.options.mode",
            """{"configured":{"inner":"input","options":{"mode":"Fast","values":[{"a":"2"}]}}}""" to
                "$.configured.options.values[0].a",
            """{"configured":{"options":{"mode":"Fast","values":[]}}}""" to "$.configured.inner",
        )

        for ((payload, path) in cases) {
            val error = assertFailsWith<SerializationException> { Json.decodeFromString<Expression>(payload) }
            assertTrue(path in error.message.orEmpty(), error.message)
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
    fun `custom payload mappings preserve compact validation paths`() {
        val error = assertFailsWith<SerializationException> {
            Json.decodeFromString<Mapped>("""{"mapped":{"mode":"Unknown","values":[]}}""")
        }

        assertTrue("$.mapped.mode" in error.message.orEmpty(), error.message)
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

        assertTrue("$.choice: ambiguous operator" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `catalog resolvers must select a candidate`() {
        val catalog = JsonNodeCatalog(resolve = { _, _, _ -> jsonNode<Input>("unsupported") })
        val serializer = CompactSerializer("InvalidResolver", Choice::class, catalog) { ChoiceSerializer.nodes }

        val error = assertFailsWith<SerializationException> {
            Json.decodeFromString(serializer, """{"choice":{"number":3}}""")
        }

        assertTrue("$.choice: resolver selected an unsupported node" in error.message.orEmpty(), error.message)
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

    @Test
    fun `catalog validation retains paths across nullable vocabulary boundaries`() {
        val error = assertFailsWith<SerializationException> {
            Json.decodeFromString<Envelope>("""{"box":{"expression":{"negate":{"offset":{"amount":"2"}}}}}""")
        }

        assertTrue("$.box.expression.negate.offset.amount" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `CBOR retains the sealed representation`() {
        val expression: Expression = Plus(Input, NumberLiteral(2.5))

        val encoded = Cbor.encodeToByteArray(expression)

        assertEquals(expression, Cbor.decodeFromByteArray<Expression>(encoded))
    }

    @Test
    fun `ProtoBuf retains the sealed representation`() {
        val expression: Expression = Plus(Input, NumberLiteral(2.5))

        val encoded = ProtoBuf.encodeToByteArray(expression)

        assertEquals(expression, ProtoBuf.decodeFromByteArray<Expression>(encoded))
    }
}
