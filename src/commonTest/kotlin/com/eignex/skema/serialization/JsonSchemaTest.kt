package com.eignex.skema.serialization

import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import com.eignex.skema.TestSpec
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable(with = WireSerializer::class)
private sealed interface Wire

@Serializable private data object WireInput : Wire

@Serializable private data class WireNumber(val value: Long) : Wire

@Serializable private data class WireNegate(val child: Wire) : Wire

@Serializable private data class WirePlus(val left: Wire, val right: Wire) : Wire

@Serializable private data class WireOptions(val child: Wire?, val strict: Boolean = false) : Wire

@Serializable private data class WireLabels(val values: Map<String, String> = emptyMap()) : Wire

@Serializable private data class WireLegacy(val spec: TestSpec) : Wire

@Serializable private data class WireRef(val name: String, val negated: Boolean = false) : Wire

@Serializable private data class WireTuned(val rate: Double = 0.5) : Wire

// Renames the only field, so the payload schema is custom yet still admits the empty object.
private object TunedMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonObject(body.mapKeys { "r" })

    override fun decode(payload: JsonElement): JsonObject = JsonObject(payload.jsonObject.mapKeys { "rate" })

    override fun payloadSchema(body: JsonObject): JsonObject {
        val rate = body.getValue("properties").jsonObject.getValue("rate")
        return JsonObject(body + ("properties" to JsonObject(mapOf("r" to rate))))
    }
}

private object WireSerializer : CompactSerializer<Wire>("Wire/v~1?", Wire::class, nodes = {
    listOf(
        jsonNode<WireInput>("input"),
        jsonNode<WireNumber>("number", JsonNodeShape.Literal, "value"),
        jsonNode<WireNegate>("negate", JsonNodeShape.Operand, "child"),
        jsonNode<WirePlus>("plus", JsonNodeShape.Operands, "left", "right"),
        jsonNode<WireOptions>("options"),
        jsonNode<WireLabels>("labels", JsonNodeShape.Operand, "values"),
        jsonNode<WireLegacy>("legacy"),
        jsonNode<WireRef>("ref", JsonNodeShape.Operand, "name", fixed = mapOf("negated" to JsonPrimitive(false))),
        jsonNode<WireRef>("notRef", fixed = mapOf("negated" to JsonPrimitive(true))),
        jsonNode<WireTuned>("tuned", mapping = TunedMapping),
    )
})

@Serializable private data class Holder<T>(val value: T)

@Serializable
private data class Shapes(
    val signed: Int,
    val unsigned: UInt,
    val keys: Map<Long, Char>,
    val text: Holder<String>,
    val number: Holder<Int>,
    @Contextual val contextual: Holder<Boolean>,
    val wire: Wire?,
)

private val wireCatalog = JsonNodeCatalog { listOf(WireSerializer) }

private val contextualJson = Json {
    serializersModule = SerializersModule { contextual(Holder::class) { Holder.serializer(Boolean.serializer()) } }
}

private fun JsonObject.definition(schema: JsonObject = this): JsonObject {
    val reference = schema["\$ref"]?.jsonPrimitive?.content ?: return schema
    return getValue("\$defs").jsonObject.getValue(reference.substringAfterLast('/')).jsonObject
}

private fun JsonObject.property(name: String): JsonObject = getValue("properties").jsonObject.getValue(name).jsonObject

private fun JsonObject.branches(): List<JsonObject> = getValue("anyOf").jsonArray.map { it.jsonObject }

// Operator schemas of a compact vocabulary, looking inside the anyOf that adds a bare-name shorthand.
private fun JsonObject.operator(name: String): JsonObject =
    definition().branches().flatMap { it["anyOf"]?.jsonArray?.map { branch -> branch.jsonObject } ?: listOf(it) }
        .single { name in it["properties"]?.jsonObject.orEmpty() }.property(name)

class JsonSchemaTest {
    @Test
    fun `primitive collection generic and contextual schemas follow their serializers`() {
        val document = Shapes.serializer().toJsonSchema(wireCatalog, contextualJson)
        val shapes = document.definition()

        assertEquals(buildJsonObject { put("type", "integer") }, shapes.property("signed"))
        assertEquals(JsonPrimitive(0), shapes.property("unsigned")["minimum"])
        assertEquals(JsonPrimitive("^-?[0-9]+$"), shapes.property("keys")["propertyNames"]?.jsonObject?.get("pattern"))
        for ((name, expected) in listOf("text" to "string", "number" to "integer", "contextual" to "boolean")) {
            val box = document.definition(shapes.property(name))
            assertEquals(JsonPrimitive(expected), box.property("value")["type"], name)
        }
        assertEquals(listOf("signed", "unsigned", "keys", "text", "number", "contextual", "wire"), shapes.required())
    }

    @Test
    fun `nullable fields are required only with explicit nulls`() {
        for (explicit in listOf(false, true)) {
            val options = WireSerializer.toJsonSchema(Json { explicitNulls = explicit }).operator("options")

            assertEquals(if (explicit) listOf("child") else emptyList(), options.required())
        }
    }

    @Test
    fun `ordinary sealed children follow the discriminator configuration`() {
        val tagged = WireSerializer.toJsonSchema(Json { classDiscriminator = "kind" })
        val arrays = WireSerializer.toJsonSchema(Json { useArrayPolymorphism = true })
        val subtypes = { document: JsonObject ->
            document.getValue("\$defs").jsonObject.getValue(TestSpec.serializer().descriptor.serialName).jsonObject
                .branches()
        }

        assertTrue(subtypes(tagged).all { "kind" in it.required() })
        assertTrue(subtypes(arrays).all { it["type"] == JsonPrimitive("array") && it["maxItems"] == JsonPrimitive(2) })
    }

    @Test
    fun `payload schemas omit fixed fields and offer the shorthand only for empty payloads`() {
        val document = WireSerializer.toJsonSchema()
        val shorthands = document.definition().branches().flatMap { it["anyOf"]?.jsonArray.orEmpty() }
            .mapNotNull { it.jsonObject["const"]?.jsonPrimitive?.content }

        assertEquals(JsonPrimitive("string"), document.operator("ref")["type"])
        assertEquals(setOf("name"), document.operator("notRef").getValue("properties").jsonObject.keys)
        assertEquals(setOf("r"), document.operator("tuned").getValue("properties").jsonObject.keys)
        assertEquals(listOf("input", "options", "tuned"), shorthands)
    }

    @Test
    fun `custom mappings without payload schemas fail explicitly`() {
        val mapping = object : JsonPayloadMapping {
            override fun encode(body: JsonObject): JsonElement = body

            override fun decode(payload: JsonElement): JsonObject = payload.jsonObject
        }
        val serializer = CompactSerializer(
            "Unmapped",
            Wire::class,
        ) { listOf(jsonNode<WireRef>("ref", mapping = mapping)) }

        assertFailsWith<IllegalStateException> { serializer.toJsonSchema() }
    }

    private fun JsonObject.required() = this["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
}

/** A generated schema with encoded values it must accept and payloads it must reject. */
internal class SchemaExample(
    val name: String,
    val schema: JsonObject,
    val valid: List<JsonElement>,
    val invalid: List<String>,
)

/** Validated against draft 2020-12 by the JVM tests. */
internal fun schemaExamples(): List<SchemaExample> {
    val values = listOf(
        WireInput, WireNumber(Long.MIN_VALUE), WireNumber(Long.MAX_VALUE), WireNegate(WireInput),
        WirePlus(WireInput, WireNumber(2)), WireOptions(null), WireOptions(WireInput, strict = true), WireLabels(),
        WireLabels(mapOf("label" to "value")), WireLegacy(TestSpec.Int(min = 0)), WireRef("a"), WireRef("a", true),
        WireTuned(), WireTuned(0.25),
    ).map { SchemaJson.encodeToJsonElement(WireSerializer, it) }
    val invalid = listOf(
        "{}",
        "\"unknown\"",
        "\"ref\"",
        """{"input":{},"negate":"input"}""",
        """{"plus":["input"]}""",
        """{"plus":["input",1,2]}""",
        """{"notRef":{"name":"a","negated":true}}""",
        """{"tuned":{"rate":1}}""",
    )
    val definition = SchemaDef(mapOf("a" to WireNegate(WireInput), "b" to WireNumber(1)))
    val shapes = Shapes(1, 2u, mapOf(-3L to 'c'), Holder("t"), Holder(4), Holder(true), null)
    return listOf(
        SchemaExample("compact vocabulary", WireSerializer.toJsonSchema(), values, invalid),
        SchemaExample(
            "nullable compact root",
            WireSerializer.nullable.toJsonSchema(wireCatalog),
            values + JsonNull,
            invalid,
        ),
        SchemaExample(
            "schema definition",
            SchemaDef.serializer(WireSerializer).toJsonSchema(wireCatalog),
            listOf(SchemaJson.encodeToJsonElement(SchemaDef.serializer(WireSerializer), definition)),
            listOf("""{"entries":{"a":{}}}""", """{"entries":{},"extra":1}"""),
        ),
        SchemaExample(
            "records",
            Shapes.serializer().toJsonSchema(wireCatalog, contextualJson),
            listOf(contextualJson.encodeToJsonElement(Shapes.serializer(), shapes)),
            listOf(
                """{"signed":1,"unsigned":-1,"keys":{},"text":{"value":""},"number":{"value":1},""" +
                    """"contextual":{"value":true},"wire":null}""",
            ),
        ),
    )
}
