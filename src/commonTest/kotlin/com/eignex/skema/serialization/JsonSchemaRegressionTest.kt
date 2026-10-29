package com.eignex.skema.serialization

import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
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

class JsonSchemaRegressionTest {
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
    fun `compact schema names preserve literal trailing question marks`() {
        val schema = QuestionSchemaSerializer.toJsonSchema()
        assertCompactDefinition(schema, schema)
        val encoded = Json.encodeToJsonElement(QuestionSchemaSerializer, QuestionSchemaLeaf)
        assertEquals(JsonPrimitive("leaf"), encoded)
        assertEquals(QuestionSchemaLeaf, Json.decodeFromJsonElement(QuestionSchemaSerializer, encoded))
        schemaExamples("question mark compact root", schema, listOf(encoded))
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

    private fun resolve(schema: JsonObject, document: JsonObject): JsonObject {
        val reference = schema.getValue("\$ref").jsonPrimitive.content.substringAfterLast('/')
        return document.getValue("\$defs").jsonObject.getValue(reference).jsonObject
    }

    private fun schemaExamples(name: String, schema: JsonObject, valid: List<JsonElement>) {
        val fixture = buildJsonObject {
            put("name", name)
            put("schema", schema)
            put("valid", JsonArray(valid))
            put("invalid", JsonArray(listOf(JsonObject(emptyMap()))))
        }
        println("JSON_SCHEMA_CASE:$fixture")
    }
}
