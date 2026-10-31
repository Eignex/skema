@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import com.eignex.skema.TestSpec
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Serializable(with = SchemaWireSerializer::class)
private sealed interface SchemaWire

@Serializable private data object SchemaInput : SchemaWire

@Serializable private data class SchemaNumber(val value: Long) : SchemaWire

@Serializable private data class SchemaNegate(val child: SchemaWire) : SchemaWire

@Serializable private data class SchemaPlus(val left: SchemaWire, val right: SchemaWire) : SchemaWire

@Serializable private data class SchemaOptions(val child: SchemaWire?, val strict: Boolean = false) : SchemaWire

@Serializable private data class SchemaLabels(val values: Map<String, String> = emptyMap()) : SchemaWire

@Serializable private data class SchemaLegacy(val spec: TestSpec) : SchemaWire

@Serializable private data class SchemaMapped(val name: String, val negated: Boolean = false) : SchemaWire

@Serializable private data class SchemaBox<T>(val value: T)

@Serializable private data class SchemaBoxes(val text: SchemaBox<String>, val number: SchemaBox<Int>)

private object SchemaMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonArray(
        listOf(body.getValue("name"), body["negated"] ?: JsonPrimitive(false)),
    )
    override fun decode(payload: JsonElement): JsonObject =
        JsonObject(mapOf("name" to payload.jsonArray[0], "negated" to payload.jsonArray[1]))

    override fun payloadSchema(body: JsonObject): JsonObject {
        val fields = body.getValue("properties").jsonObject
        return buildJsonObject {
            put("type", "array")
            put("prefixItems", JsonArray(listOf(fields.getValue("name"), fields.getValue("negated"))))
            put("minItems", 2)
            put("maxItems", 2)
            put("items", false)
        }
    }
}

@Serializable private data class SchemaDecimal(val value: Double) : SchemaWire

@Serializable private data class SchemaTuned(val rate: Double = 0.5) : SchemaWire

private object DecimalMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body.getValue("value")

    override fun decode(payload: JsonElement): JsonObject = JsonObject(mapOf("value" to payload))

    override fun payloadSchema(body: JsonObject): JsonObject =
        body.getValue("properties").jsonObject.getValue("value").jsonObject
}

private object TunedMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonObject(body.mapKeys { "r" })

    override fun decode(payload: JsonElement): JsonObject {
        val fields = payload.jsonObject
        fields.keys.firstOrNull { it != "r" }?.let { throw SerializationException("unknown field '$it'") }
        return JsonObject(fields.mapKeys { "rate" })
    }

    override fun payloadSchema(body: JsonObject): JsonObject {
        val rate = body.getValue("properties").jsonObject.getValue("rate")
        return JsonObject(body + ("properties" to JsonObject(mapOf("r" to rate))))
    }
}

private object FieldsMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body

    override fun decode(payload: JsonElement): JsonObject = payload.jsonObject

    override fun payloadSchema(body: JsonObject): JsonObject = body
}

private object MappedWireSerializer : CompactSerializer<SchemaWire>("MappedWire", SchemaWire::class, nodes = {
    listOf(
        jsonNode<SchemaDecimal>("decimal", JsonNodeShape.Literal, "value", mapping = DecimalMapping),
        jsonNode<SchemaTuned>("tuned", mapping = TunedMapping),
        jsonNode<SchemaMapped>("ref", mapping = FieldsMapping, fixed = mapOf("negated" to JsonPrimitive(false))),
        jsonNode<SchemaMapped>("notRef", mapping = FieldsMapping, fixed = mapOf("negated" to JsonPrimitive(true))),
    )
})

private object SchemaWireSerializer : CompactSerializer<SchemaWire>("SchemaWire", SchemaWire::class, nodes = {
    listOf(
        jsonNode<SchemaInput>("input"),
        jsonNode<SchemaNumber>("number", JsonNodeShape.Literal, "value"),
        jsonNode<SchemaNegate>("negate", JsonNodeShape.Operand, "child"),
        jsonNode<SchemaPlus>("plus", JsonNodeShape.Operands, "left", "right"),
        jsonNode<SchemaOptions>("options"),
        jsonNode<SchemaLabels>("labels", JsonNodeShape.Operand, "values"),
        jsonNode<SchemaLegacy>("legacy"),
        jsonNode<SchemaMapped>("mapped", mapping = SchemaMapping),
    )
})

@Serializable
private data class GenericSchemaBox<T>(val value: T)

@Serializable
private data class ContextualGenericSchema(@Contextual val box: GenericSchemaBox<Int>)

@Serializable
private data class IntegerSchemas(val signed: Int, val unsigned: UInt, val keys: Map<Long, Char>)

@Serializable(with = QuestionSchemaSerializer::class)
private sealed interface QuestionSchemaNode

@Serializable
private data object QuestionSchemaLeaf : QuestionSchemaNode

@Serializable
private data class QuestionSchemaDocument(val value: QuestionSchemaNode, val nullable: QuestionSchemaNode?)

private object QuestionSchemaSerializer : CompactSerializer<QuestionSchemaNode>(
    "Release?",
    QuestionSchemaNode::class,
    nodes = { listOf(jsonNode<QuestionSchemaLeaf>("leaf")) },
)

class JsonSchemaTest {
    @Test
    fun `schema definitions use compact entry schemas through an explicit catalog`() {
        val catalog = JsonNodeCatalog { listOf(SchemaWireSerializer) }
        val serializer = SchemaDef.serializer(SchemaWireSerializer)

        val document = serializer.toJsonSchema(catalog)

        val definition = resolve(document, document)
        val entries = definition.getValue("properties").jsonObject.getValue("entries").jsonObject
        val entry = resolve(entries.getValue("additionalProperties").jsonObject, document)
        assertTrue(entry.containsKey("anyOf"))
        assertEquals(JsonPrimitive(false), definition["additionalProperties"])
    }

    @Test
    fun `nullable field requirements follow explicit null settings`() {
        for (explicit in listOf(false, true)) {
            val document = SchemaWireSerializer.toJsonSchema(Json { explicitNulls = explicit })
            val options = resolve(document, document).getValue("anyOf").jsonArray.flatMap { branch ->
                branch.jsonObject["anyOf"]?.jsonArray ?: listOf(branch)
            }.map { it.jsonObject }
                .single { it["properties"]?.jsonObject?.containsKey("options") == true }
                .getValue("properties").jsonObject.getValue("options").jsonObject
            val required = options["required"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }

            assertEquals(explicit, "child" in required)
            assertFalse("strict" in required)
            assertEquals(JsonPrimitive(false), options["additionalProperties"])
            val child = options.getValue("properties").jsonObject.getValue("child").jsonObject
            assertEquals(document["${'$'}ref"], child.getValue("anyOf").jsonArray[0].jsonObject["${'$'}ref"])
        }
    }

    @Test
    fun `generic instantiations get separate definitions`() {
        val document = SchemaBoxes.serializer().toJsonSchema(JsonNodeCatalog())
        val properties = resolve(document, document).getValue("properties").jsonObject
        val text = resolve(properties.getValue("text").jsonObject, document)
        val number = resolve(properties.getValue("number").jsonObject, document)

        assertEquals(
            JsonPrimitive("string"),
            text.getValue("properties").jsonObject.getValue("value").jsonObject["type"],
        )
        assertEquals(
            JsonPrimitive("integer"),
            number.getValue("properties").jsonObject.getValue("value").jsonObject["type"],
        )
    }

    @Test
    fun `integer schemas are plain apart from unsigned minimums and digit keys`() {
        val document = IntegerSchemas.serializer().toJsonSchema(JsonNodeCatalog())
        val properties = resolve(document, document).getValue("properties").jsonObject

        assertEquals(buildJsonObject { put("type", "integer") }, properties["signed"])
        assertEquals(
            buildJsonObject {
                put("type", "integer")
                put("minimum", 0)
            },
            properties["unsigned"],
        )
        val keys = properties.getValue("keys").jsonObject
        assertEquals(buildJsonObject { put("pattern", "^-?[0-9]+$") }, keys["propertyNames"])
        assertEquals(JsonPrimitive(1), keys.getValue("additionalProperties").jsonObject["maxLength"])
    }

    @Test
    fun `ordinary sealed values keep the configured discriminator in generated schemas`() {
        val document = SchemaWireSerializer.toJsonSchema(Json { classDiscriminator = "kind" })
        val definitions = document.getValue("${'$'}defs").jsonObject
        val sealed = definitions.values.map { it.jsonObject }.filter { it.containsKey("anyOf") }.flatMap {
            it.getValue("anyOf").jsonArray.map { branch -> branch.jsonObject }
        }

        assertTrue(
            sealed.any { branch ->
                branch["properties"]?.jsonObject?.get("kind")?.jsonObject?.get("const") == JsonPrimitive("Int")
            },
        )
    }

    @Test
    fun `ordinary sealed schema branches follow array polymorphism`() {
        val document = SchemaWireSerializer.toJsonSchema(Json { useArrayPolymorphism = true })
        val branches = document.getValue("${'$'}defs").jsonObject.values.map { it.jsonObject }
            .filter { it.containsKey("anyOf") }.flatMap { it.getValue("anyOf").jsonArray }
        val subtype = branches.map { it.jsonObject }.single {
            it["prefixItems"]?.jsonArray?.firstOrNull()?.jsonObject?.get("const") == JsonPrimitive("Int")
        }

        assertEquals(JsonPrimitive("array"), subtype["type"])
        assertEquals(JsonPrimitive(2), subtype["maxItems"])
        assertEquals(JsonPrimitive(false), subtype["items"])
    }

    @Test
    fun `fixed fields are omitted from payload schemas`() {
        val serializer = CompactSerializer("Fixed", SchemaWire::class) {
            listOf(
                jsonNode<SchemaMapped>(
                    "ref",
                    JsonNodeShape.Operand,
                    "name",
                    fixed = mapOf("negated" to JsonPrimitive(false)),
                ),
                jsonNode<SchemaMapped>("notRef", fixed = mapOf("negated" to JsonPrimitive(true))),
            )
        }

        val document = serializer.toJsonSchema()

        val branches = resolve(document, document).getValue("anyOf").jsonArray.map {
            it.jsonObject.getValue("properties").jsonObject
        }
        val ref = branches.single { "ref" in it }.getValue("ref").jsonObject
        val notRef = branches.single { "notRef" in it }.getValue("notRef").jsonObject
        assertEquals(JsonPrimitive("string"), ref["type"])
        assertEquals(setOf("name"), notRef.getValue("properties").jsonObject.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("name"))), notRef["required"])
    }

    @Test
    fun `mapped literal payloads keep their bare number schema`() {
        val document = MappedWireSerializer.toJsonSchema()
        val branches = resolve(document, document).getValue("anyOf").jsonArray

        assertTrue(buildJsonObject { put("type", "number") } in branches)
        assertEquals("2.5", SchemaJson.encodeToString(MappedWireSerializer, SchemaDecimal(2.5)))
        assertEquals(SchemaDecimal(2.5), SchemaJson.decodeFromString(MappedWireSerializer, "2.5"))
    }

    @Test
    fun `mapped payloads admitting the empty object include the name shorthand`() {
        val document = MappedWireSerializer.toJsonSchema()
        val shorthand = buildJsonObject { put("const", "tuned") }
        val tuned = resolve(document, document).getValue("anyOf").jsonArray.map { it.jsonObject }
            .single { branch -> branch["anyOf"]?.jsonArray?.contains(shorthand) == true }
        val payload = tuned.getValue("anyOf").jsonArray[0].jsonObject
            .getValue("properties").jsonObject.getValue("tuned").jsonObject

        assertEquals(setOf("r"), payload.getValue("properties").jsonObject.keys)
        assertEquals("\"tuned\"", SchemaJson.encodeToString(MappedWireSerializer, SchemaTuned()))
        assertEquals(SchemaTuned(), SchemaJson.decodeFromString(MappedWireSerializer, "\"tuned\""))
        assertEquals(SchemaTuned(0.25), SchemaJson.decodeFromString(MappedWireSerializer, """{"tuned":{"r":0.25}}"""))
    }

    @Test
    fun `mappings without payload schemas fail explicitly`() {
        val mapping = object : JsonPayloadMapping {
            override fun encode(body: JsonObject): JsonElement = body
            override fun decode(payload: JsonElement): JsonObject = payload.jsonObject
        }
        val serializer = CompactSerializer("MissingMapping", SchemaWire::class) {
            listOf(jsonNode<SchemaMapped>("mapped", mapping = mapping))
        }

        val error = assertFailsWith<IllegalStateException> { serializer.toJsonSchema() }

        assertTrue("explicit payload schema" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `contextual providers independent of type arguments retain schema support`() {
        val json = Json {
            serializersModule = SerializersModule {
                contextual(GenericSchemaBox::class) { GenericSchemaBox.serializer(Int.serializer()) }
            }
        }
        val schema = ContextualGenericSchema.serializer().toJsonSchema(JsonNodeCatalog(), json)
        val box = resolve(schema, schema).getValue("properties").jsonObject.getValue("box").jsonObject
        val value = resolve(box, schema).getValue("properties").jsonObject.getValue("value").jsonObject

        assertEquals(JsonPrimitive("integer"), value["type"])
    }

    private fun resolve(schema: JsonObject, document: JsonObject): JsonObject {
        val reference = schema["${'$'}ref"]?.jsonPrimitive?.content ?: return schema
        return document.getValue("${'$'}defs").jsonObject.getValue(reference.removePrefix("#/${'$'}defs/")).jsonObject
    }
}

/** A generated schema with encoded values it must accept and payloads it must reject. */
internal class SchemaExample(
    val name: String,
    val schema: JsonObject,
    val valid: List<JsonElement>,
    val invalid: List<JsonElement> = listOf(JsonObject(emptyMap())),
)

/** Validated against draft 2020-12 by the JVM tests. */
internal fun schemaExamples(): List<SchemaExample> {
    val values = listOf<SchemaWire>(
        SchemaInput,
        SchemaNumber(Long.MIN_VALUE),
        SchemaNumber(Long.MAX_VALUE),
        SchemaNegate(SchemaInput),
        SchemaPlus(SchemaInput, SchemaNumber(2)),
        SchemaOptions(null),
        SchemaOptions(SchemaInput, strict = true),
        SchemaLabels(),
        SchemaLabels(mapOf("label" to "value")),
        SchemaLegacy(TestSpec.Int(min = 0)),
        SchemaMapped("enabled", true),
    )
    val invalid = listOf(
        "{}",
        "\"unknown\"",
        """{"input":{},"negate":"input"}""",
        """{"plus":["input"]}""",
        """{"plus":["input",1,2]}""",
        """{"mapped":["enabled"]}""",
        """{"mapped":["enabled","true"]}""",
        """{"mapped":["enabled",true],"extra":1}""",
    ).map(SchemaJson::parseToJsonElement)
    val questionCatalog = JsonNodeCatalog { listOf(QuestionSchemaSerializer) }
    val renamed = CompactSerializer("example/expr~v1", QuestionSchemaNode::class) {
        listOf(jsonNode<QuestionSchemaLeaf>("leaf"))
    }
    return listOf(
        SchemaExample(
            "recursive compact vocabulary",
            SchemaWireSerializer.toJsonSchema(),
            values.map { SchemaJson.encodeToJsonElement(SchemaWireSerializer, it) },
            invalid,
        ),
        SchemaExample(
            "mapped vocabulary",
            MappedWireSerializer.toJsonSchema(),
            listOf(
                SchemaDecimal(2.5),
                SchemaTuned(),
                SchemaTuned(0.25),
                SchemaMapped("a"),
                SchemaMapped("a", true),
            ).map { SchemaJson.encodeToJsonElement(MappedWireSerializer, it) },
        ),
    ) + listOf(QuestionSchemaSerializer, renamed).map { serializer ->
        SchemaExample(serializer.serialName, serializer.toJsonSchema(), listOf(JsonPrimitive("leaf")))
    } + SchemaExample(
        "nullable compact root",
        QuestionSchemaSerializer.nullable.toJsonSchema(questionCatalog),
        listOf(JsonPrimitive("leaf"), JsonNull),
    ) + SchemaExample(
        "nested compact",
        QuestionSchemaDocument.serializer().toJsonSchema(questionCatalog),
        listOf(QuestionSchemaLeaf, null).map {
            Json.encodeToJsonElement(
                QuestionSchemaDocument.serializer(),
                QuestionSchemaDocument(QuestionSchemaLeaf, it),
            )
        },
    )
}
