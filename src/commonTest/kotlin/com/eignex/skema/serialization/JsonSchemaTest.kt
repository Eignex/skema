@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.JsonSpec
import com.eignex.skema.SchemaDef
import com.eignex.skema.SchemaJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
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

@Serializable private data class SchemaLegacy(val spec: JsonSpec) : SchemaWire

@Serializable private data class SchemaMapped(val name: String, val negated: Boolean = false) : SchemaWire

@Serializable private data class SchemaBox<T>(val value: T)

@Serializable private data class SchemaBoxes(val text: SchemaBox<String>, val number: SchemaBox<Int>)

private object SchemaMapping : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = JsonArray(
        listOf(body.getValue("name"), body["negated"] ?: JsonPrimitive(false)),
    )
    override fun decode(payload: JsonElement, path: String): JsonObject =
        JsonObject(mapOf("name" to payload.jsonArray[0], "negated" to payload.jsonArray[1]))

    override fun jsonSchema(node: JsonNode, body: JsonObject): JsonObject = buildJsonObject {
        val fields = body.getValue("properties").jsonObject
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(node.name) {
                put("type", "array")
                put("prefixItems", JsonArray(listOf(fields.getValue("name"), fields.getValue("negated"))))
                put("minItems", 2)
                put("maxItems", 2)
                put("items", false)
            }
        }
        put("required", JsonArray(listOf(JsonPrimitive(node.name))))
        put("additionalProperties", false)
    }
}

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

class JsonSchemaTest {
    @Test
    fun `compact schemas describe numeric literals and recursive operands`() {
        val document = SchemaWireSerializer.toJsonSchema()
        val root = resolve(document, document)
        val branches = root.getValue("anyOf").jsonArray
        val integer = branches.single { it.jsonObject["type"] == JsonPrimitive("integer") }.jsonObject
        val plus = branches.single { it.jsonObject["properties"]?.jsonObject?.containsKey("plus") == true }.jsonObject
        val tuple = plus.getValue("properties").jsonObject.getValue("plus").jsonObject

        assertEquals(JsonPrimitive("https://json-schema.org/draft/2020-12/schema"), document["${'$'}schema"])
        assertEquals(JsonPrimitive(Long.MIN_VALUE), integer["minimum"])
        assertEquals(JsonPrimitive(Long.MAX_VALUE), integer["maximum"])
        assertEquals(JsonPrimitive(2), tuple["minItems"])
        assertEquals(JsonPrimitive(2), tuple["maxItems"])
        assertEquals(JsonPrimitive(false), tuple["items"])
        assertEquals(
            listOf(document["${'$'}ref"], document["${'$'}ref"]),
            tuple["prefixItems"]!!.jsonArray.map {
                it.jsonObject["${'$'}ref"]
            },
        )
    }

    @Test
    fun `schema definitions use compact entry schemas through an explicit catalog`() {
        val catalog = JsonNodeCatalog { listOf(SchemaWireSerializer) }
        val serializer = SchemaDef.serializer(SchemaWireSerializer)
        val value = SchemaDef<SchemaWire>(mapOf("result" to SchemaPlus(SchemaInput, SchemaNumber(2))))

        val document = serializer.toJsonSchema(catalog)

        val definition = resolve(document, document)
        val entries = definition.getValue("properties").jsonObject.getValue("entries").jsonObject
        val entry = resolve(entries.getValue("additionalProperties").jsonObject, document)
        assertTrue(entry.containsKey("anyOf"))
        assertEquals(JsonPrimitive(false), definition["additionalProperties"])
        assertEquals(value, SchemaJson.decodeFromString(serializer, SchemaJson.encodeToString(serializer, value)))
    }

    @Test
    fun `all generated references resolve within the document`() {
        val document = SchemaWireSerializer.toJsonSchema()
        val definitions = document.getValue("${'$'}defs").jsonObject
        fun visit(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    value["${'$'}ref"]?.let { reference ->
                        val name = reference.jsonPrimitive.content.removePrefix("#/${'$'}defs/")
                        assertTrue(name in definitions, reference.toString())
                    }
                    value.values.forEach(::visit)
                }

                is JsonArray -> value.forEach(::visit)

                else -> Unit
            }
        }

        visit(document)
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
    fun `single map operands include empty payload shorthands`() {
        val document = SchemaWireSerializer.toJsonSchema()
        val branches = resolve(document, document).getValue("anyOf").jsonArray
        val labels = branches.map { it.jsonObject }.single { branch ->
            branch["anyOf"]?.jsonArray?.any { it.jsonObject["const"] == JsonPrimitive("labels") } == true
        }

        assertTrue(labels.getValue("anyOf").jsonArray.any { it.jsonObject["const"] == JsonPrimitive("labels") })
        assertEquals("\"labels\"", SchemaJson.encodeToString<SchemaWire>(SchemaLabels()))
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
    fun `nullable root serializers retain null alternatives`() {
        val catalog = JsonNodeCatalog { listOf(SchemaWireSerializer) }

        val document = SchemaWireSerializer.nullable.toJsonSchema(catalog)

        val branches = document.getValue("anyOf").jsonArray.map { it.jsonObject }
        assertEquals(2, branches.size)
        assertTrue(branches.any { it["type"] == JsonPrimitive("null") })
        assertTrue(branches.any { it.containsKey("${'$'}ref") })
    }

    @Test
    fun `descriptor names with URI characters produce resolvable references`() {
        val serializer = CompactSerializer("example/expr~v1", SchemaWire::class) {
            listOf(jsonNode<SchemaInput>("input"))
        }

        val document = serializer.toJsonSchema()

        assertEquals(JsonPrimitive("#/\$defs/example_expr_v1"), document["${'$'}ref"])
        assertTrue(resolve(document, document).containsKey("anyOf"))
    }

    @Test
    fun `mapped schemas describe the mapped wire payload`() {
        val document = SchemaWireSerializer.toJsonSchema()
        val mapped = resolve(document, document).getValue("anyOf").jsonArray.map { it.jsonObject }
            .single { it["properties"]?.jsonObject?.containsKey("mapped") == true }
            .getValue("properties").jsonObject.getValue("mapped").jsonObject

        assertEquals(JsonPrimitive("array"), mapped["type"])
        assertEquals(JsonPrimitive("boolean"), mapped["prefixItems"]!!.jsonArray[1].jsonObject["type"])
        assertEquals(
            """{"mapped":["enabled",true]}""",
            SchemaJson.encodeToString<SchemaWire>(SchemaMapped("enabled", true)),
        )
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
    fun `mappings without schema declarations fail explicitly`() {
        val mapping = object : JsonPayloadMapping {
            override fun encode(body: JsonObject): JsonElement = body
            override fun decode(payload: JsonElement, path: String): JsonObject = payload.jsonObject
        }
        val serializer = CompactSerializer("MissingMapping", SchemaWire::class) {
            listOf(jsonNode<SchemaMapped>("mapped", mapping = mapping))
        }

        val error = assertFailsWith<IllegalStateException> { serializer.toJsonSchema() }

        assertTrue("requires an explicit schema" in error.message.orEmpty(), error.message)
    }

    private fun resolve(schema: JsonObject, document: JsonObject): JsonObject {
        val reference = schema["${'$'}ref"]?.jsonPrimitive?.content ?: return schema
        return document.getValue("${'$'}defs").jsonObject.getValue(reference.removePrefix("#/${'$'}defs/")).jsonObject
    }
}
