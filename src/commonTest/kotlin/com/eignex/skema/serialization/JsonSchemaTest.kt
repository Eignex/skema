@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import com.eignex.skema.TestSpec
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
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
    override fun decode(payload: JsonElement, path: String): JsonObject =
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

    override fun decode(payload: JsonElement, path: String): JsonObject = JsonObject(mapOf("value" to payload))

    override fun fieldPath(field: String, path: String): String = path

    override fun payloadSchema(body: JsonObject): JsonObject =
        body.getValue("properties").jsonObject.getValue("value").jsonObject
}

private object TunedMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonObject(body.mapKeys { "r" })

    override fun decode(payload: JsonElement, path: String): JsonObject {
        val fields = payload.objectAt(path)
        fields.keys.firstOrNull { it != "r" }?.let { invalid("$path.$it", "unknown field '$it'") }
        return JsonObject(fields.mapKeys { "rate" })
    }

    override fun fieldPath(field: String, path: String): String = "$path.r"

    override fun payloadSchema(body: JsonObject): JsonObject {
        val rate = body.getValue("properties").jsonObject.getValue("rate")
        return JsonObject(body + ("properties" to JsonObject(mapOf("r" to rate))))
    }
}

private object FieldsMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body

    override fun decode(payload: JsonElement, path: String): JsonObject = payload.objectAt(path)

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
    fun `compact schemas accept encoded nodes and reject invalid payloads`() {
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
        schemaExamples(
            "recursive compact vocabulary",
            SchemaWireSerializer.toJsonSchema(),
            values.map { SchemaJson.encodeToJsonElement(SchemaWireSerializer, it) },
            invalid,
        )
    }

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
    fun `integer schemas are plain and decoding accepts integral tokens in range`() {
        val serializer = SchemaBox.serializer(Int.serializer())
        val document = serializer.toJsonSchema(JsonNodeCatalog())
        val value = resolve(document, document).getValue("properties").jsonObject.getValue("value").jsonObject
        val admitted = listOf("2147483647", "2147483647.0", "2.147483647e9", "-2.147483648E+9", "100e-2", "-0.0")
        val refused = listOf("2147483648", "2.147483648e9", "-2147483649.0", "2.5", "1e-1", "\"2\"")

        val accepted = (admitted + refused).filter { token ->
            val element = SchemaJson.parseToJsonElement("""{"value":$token}""")
            runCatching { SchemaJson.decodeFromJsonElement(serializer, element, JsonNodeCatalog()) }.isSuccess
        }

        assertEquals(buildJsonObject { put("type", "integer") }, value)
        assertEquals(admitted, accepted)
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
    fun `mapped payload schemas omit fixed fields and require their operands`() {
        val document = MappedWireSerializer.toJsonSchema()
        val branches = resolve(document, document).getValue("anyOf").jsonArray.map { it.jsonObject }
        val ref = branches.single { it["properties"]?.jsonObject?.containsKey("ref") == true }
            .getValue("properties").jsonObject.getValue("ref").jsonObject

        assertEquals(setOf("name"), ref.getValue("properties").jsonObject.keys)
        assertEquals(JsonArray(listOf(JsonPrimitive("name"))), ref["required"])
        val shorthands = branches.flatMap { it["anyOf"]?.jsonArray.orEmpty() }.mapNotNull { it.jsonObject["const"] }
        assertEquals(listOf(JsonPrimitive("tuned")), shorthands)
        assertEquals(
            """{"notRef":{"name":"a"}}""",
            SchemaJson.encodeToString(MappedWireSerializer, SchemaMapped("a", true)),
        )
    }

    @Test
    fun `mappings without payload schemas fail explicitly`() {
        val mapping = object : JsonPayloadMapping {
            override fun encode(body: JsonObject): JsonElement = body
            override fun decode(payload: JsonElement, path: String): JsonObject = payload.jsonObject
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

    @Test
    fun `contextual generic providers explain their schema limitation`() {
        val json = Json {
            serializersModule = SerializersModule {
                contextual(GenericSchemaBox::class) { arguments ->
                    @Suppress("UNCHECKED_CAST")
                    GenericSchemaBox.serializer(arguments.single() as KSerializer<Any?>)
                }
            }
        }
        val serializer = ContextualGenericSchema.serializer()
        val value = ContextualGenericSchema(GenericSchemaBox(2))
        val encoded = json.encodeToJsonElement(serializer, value)
        assertEquals(value, json.decodeFromJsonElement(serializer, encoded, JsonNodeCatalog()))

        val error = assertFailsWith<IllegalArgumentException> { serializer.toJsonSchema(JsonNodeCatalog(), json) }

        assertTrue("contextual descriptors do not retain type arguments" in error.message.orEmpty())
        assertTrue("explicit JSON Schema" in error.message.orEmpty())
        assertTrue(error.cause is NoSuchElementException)
    }

    @Test
    fun `compact schema names with URI characters produce valid references`() {
        val serializers = listOf(
            QuestionSchemaSerializer,
            CompactSerializer("example/expr~v1", QuestionSchemaNode::class) {
                listOf(jsonNode<QuestionSchemaLeaf>("leaf"))
            },
        )
        for (serializer in serializers) {
            val schema = serializer.toJsonSchema()
            assertCompactDefinition(schema, schema)
            val encoded = Json.encodeToJsonElement(serializer, QuestionSchemaLeaf)
            assertEquals(JsonPrimitive("leaf"), encoded)
            assertEquals(QuestionSchemaLeaf, Json.decodeFromJsonElement(serializer, encoded))
            schemaExamples("compact root ${serializer.serialName}", schema, listOf(encoded))
        }
    }

    @Test
    fun `nullable compact schemas preserve question mark names through catalogs`() {
        val catalog = JsonNodeCatalog { listOf(QuestionSchemaSerializer) }
        val serializer = QuestionSchemaSerializer.nullable
        val schema = serializer.toJsonSchema(catalog)
        val branches = schema.getValue("anyOf").jsonArray
        assertCompactDefinition(branches.first().jsonObject, schema)
        val encoded = listOf(QuestionSchemaLeaf, null).map { value ->
            Json.encodeToJsonElement(serializer, value).also {
                assertEquals(value, Json.decodeFromJsonElement(serializer, it, catalog))
            }
        }
        schemaExamples("question mark nullable compact root", schema, encoded)
    }

    @Test
    fun `nested compact schemas resolve question mark names through catalogs`() {
        val catalog = JsonNodeCatalog { listOf(QuestionSchemaSerializer) }
        val serializer = QuestionSchemaDocument.serializer()
        val schema = serializer.toJsonSchema(catalog)
        val definition = resolve(schema, schema)
        val properties = definition.getValue("properties").jsonObject
        assertCompactDefinition(properties.getValue("value").jsonObject, schema)
        val encoded = listOf(QuestionSchemaLeaf, null).map { nullable ->
            val value = QuestionSchemaDocument(QuestionSchemaLeaf, nullable)
            Json.encodeToJsonElement(serializer, value).also {
                assertEquals(value, Json.decodeFromJsonElement(serializer, it, catalog))
            }
        }
        schemaExamples("question mark nested compact", schema, encoded)
    }

    private fun assertCompactDefinition(schema: JsonObject, document: JsonObject) {
        val branches = resolve(schema, document).getValue("anyOf").jsonArray
        assertTrue(branches.any { it.jsonObject["const"] == JsonPrimitive("leaf") })
    }

    private fun schemaExamples(
        name: String,
        schema: JsonObject,
        valid: List<JsonElement>,
        invalid: List<JsonElement> = listOf(JsonObject(emptyMap())),
    ) {
        val fixture = buildJsonObject {
            put("name", name)
            put("schema", schema)
            put("valid", JsonArray(valid))
            put("invalid", JsonArray(invalid))
        }
        println("JSON_SCHEMA_CASE:$fixture")
    }

    private fun resolve(schema: JsonObject, document: JsonObject): JsonObject {
        val reference = schema["${'$'}ref"]?.jsonPrimitive?.content ?: return schema
        return document.getValue("${'$'}defs").jsonObject.getValue(reference.removePrefix("#/${'$'}defs/")).jsonObject
    }
}
