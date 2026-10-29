@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable
private data class StructuredKey(val id: Int)

@Serializable
private data class StructuredValue(val count: Int)

@Serializable
@JvmInline
private value class StructuredInlineKey(val key: StructuredKey)

@Serializable
@JvmInline
private value class PrimitiveInlineKey(val id: Int)

@Serializable
private data class StructuredMapHolder(val values: List<Map<StructuredKey, StructuredValue>>)

@Serializable(with = StructuredCompactKeySerializer::class)
private sealed interface StructuredCompactKey

@Serializable
private data class StructuredCompactId(val id: Int) : StructuredCompactKey

private object StructuredCompactKeySerializer : CompactSerializer<StructuredCompactKey>(
    "StructuredCompactKey",
    StructuredCompactKey::class,
    nodes = { listOf(jsonNode<StructuredCompactId>("key", JsonNodeShape.Operand, "id")) },
)

class StructuredMapTest {
    private val json = Json { allowStructuredMapKeys = true }

    @Test
    fun `structured and nullable keys round trip through alternating arrays`() {
        val serializer = MapSerializer(StructuredKey.serializer().nullable, StructuredValue.serializer())
        val value = linkedMapOf(StructuredKey(1) to StructuredValue(2), null to StructuredValue(3))
        val encoded = json.encodeToJsonElement(serializer, value)
        assertEquals("[{\"id\":1},{\"count\":2},null,{\"count\":3}]", encoded.toString())
        assertEquals(value, json.decodeFromJsonElement(serializer, encoded, JsonNodeCatalog()))
        val empty = json.parseToJsonElement("[]")
        assertEquals(emptyMap(), json.decodeFromJsonElement(serializer, empty, JsonNodeCatalog()))
    }

    @Test
    fun `inline key carriers select the same map representation as Json`() {
        val structured = MapSerializer(StructuredInlineKey.serializer(), String.serializer())
        val values = mapOf(StructuredInlineKey(StructuredKey(1)) to "value")
        val array = json.encodeToJsonElement(structured, values)
        assertEquals("[{\"id\":1},\"value\"]", array.toString())
        assertEquals(values, json.decodeFromJsonElement(structured, array, JsonNodeCatalog()))

        val primitive = MapSerializer(PrimitiveInlineKey.serializer(), String.serializer())
        val primitiveValues = mapOf(PrimitiveInlineKey(2) to "value")
        val record = json.encodeToJsonElement(primitive, primitiveValues)
        assertEquals("{\"2\":\"value\"}", record.toString())
        assertEquals(primitiveValues, json.decodeFromJsonElement(primitive, record, JsonNodeCatalog()))
        failsAt(
            "$",
        ) { json.decodeFromJsonElement(primitive, json.parseToJsonElement("[2,\"value\"]"), JsonNodeCatalog()) }
    }

    @Test
    fun `contextual carriers distinguish object and array maps`() {
        val format = Json(json) {
            serializersModule = SerializersModule {
                contextual(StructuredKey.serializer())
                contextual(Int.serializer())
            }
        }
        val structured = MapSerializer(ContextualSerializer(StructuredKey::class), String.serializer())
        val structuredValue = mapOf(StructuredKey(1) to "value")
        val array = format.encodeToJsonElement(structured, structuredValue)
        assertEquals("[{\"id\":1},\"value\"]", array.toString())
        assertEquals(structuredValue, format.decodeFromJsonElement(structured, array, JsonNodeCatalog()))

        val primitive = MapSerializer(ContextualSerializer(Int::class), String.serializer())
        val record = format.encodeToJsonElement(primitive, mapOf(2 to "value"))
        assertEquals("{\"2\":\"value\"}", record.toString())
        assertEquals(mapOf(2 to "value"), format.decodeFromJsonElement(primitive, record, JsonNodeCatalog()))
        failsAt("$.2.0") {
            format.decodeFromJsonElement(primitive, format.parseToJsonElement("{\"2.0\":\"value\"}"), JsonNodeCatalog())
        }
    }

    @Test
    fun `nested structured keys and values normalize integers and retain array paths`() {
        val serializer = StructuredMapHolder.serializer()
        val valid = json.parseToJsonElement("""{"values":[[{"id":1e0},{"count":2.0}]]}""")
        assertEquals(
            StructuredMapHolder(listOf(mapOf(StructuredKey(1) to StructuredValue(2)))),
            json.decodeFromJsonElement(serializer, valid, JsonNodeCatalog()),
        )
        for ((wire, path) in listOf(
            """{"values":[[{"id":"1"},{"count":2}]]}""" to "$.values[0][0].id",
            """{"values":[[{"id":1},{"count":2.5}]]}""" to "$.values[0][1].count",
        )) {
            failsAt(path) { json.decodeFromJsonElement(serializer, json.parseToJsonElement(wire), JsonNodeCatalog()) }
        }
    }

    @Test
    fun `contextual array keys normalize using their selected serializers`() {
        val format = Json(json) { serializersModule = SerializersModule { contextual(StructuredKey.serializer()) } }
        val serializer = MapSerializer(ContextualSerializer(StructuredKey::class), String.serializer())
        assertEquals(
            mapOf(StructuredKey(2) to "value"),
            format.decodeFromJsonElement(
                serializer,
                format.parseToJsonElement("""[{"id":2.0},"value"]"""),
                JsonNodeCatalog(),
            ),
        )
        failsAt("$[0].id") {
            format.decodeFromJsonElement(
                serializer,
                format.parseToJsonElement("""[{"id":"2"},"value"]"""),
                JsonNodeCatalog(),
            )
        }
    }

    @Test
    fun `contextual fallback keys retain Json array representation`() {
        val key = ContextualSerializer(Int::class, Int.serializer(), emptyArray())
        val serializer = MapSerializer(key, String.serializer())
        val encoded = json.encodeToJsonElement(serializer, mapOf(2 to "value"))
        assertEquals("[2,\"value\"]", encoded.toString())
        assertEquals(mapOf(2 to "value"), json.decodeFromJsonElement(serializer, encoded, JsonNodeCatalog()))
        assertEquals(
            mapOf(2 to "value"),
            json.decodeFromJsonElement(serializer, json.parseToJsonElement("[2.0,\"value\"]"), JsonNodeCatalog()),
        )
        failsAt("$[0]") {
            json.decodeFromJsonElement(serializer, json.parseToJsonElement("[\"2\",\"value\"]"), JsonNodeCatalog())
        }
    }

    @Test
    fun `compact array keys retain paths and ordinary numeric validation`() {
        val serializer = MapSerializer(StructuredCompactKeySerializer, String.serializer())
        val catalog = JsonNodeCatalog { listOf(StructuredCompactKeySerializer) }
        val value = mapOf<StructuredCompactKey, String>(StructuredCompactId(2) to "value")
        assertEquals(
            value,
            json.decodeFromJsonElement(serializer, json.parseToJsonElement("""[{"key":2.0},"value"]"""), catalog),
        )
        val encoded = json.encodeToJsonElement(serializer, value)
        assertEquals(value, json.decodeFromJsonElement(serializer, encoded, catalog))
        failsAt("$[0].key") {
            json.decodeFromJsonElement(serializer, json.parseToJsonElement("""[{"key":"2"},"value"]"""), catalog)
        }
    }

    @Test
    fun `structured maps reject malformed arrays and require their configuration`() {
        val serializer = MapSerializer(StructuredKey.serializer(), String.serializer())
        for ((wire, path) in listOf(
            "{}" to "$",
            "[1,\"value\"]" to "$[0]",
            """[{"id":1}]""" to "$[0]",
            """[{"id":1},"value",{"id":2}]""" to "$[2]",
            """[null,"value"]""" to "$[0]",
        )) {
            failsAt(path) { json.decodeFromJsonElement(serializer, json.parseToJsonElement(wire), JsonNodeCatalog()) }
        }
        failsAt("$") {
            Json.decodeFromJsonElement(serializer, json.parseToJsonElement("""[{"id":1},"value"]"""), JsonNodeCatalog())
        }
    }

    private fun failsAt(path: String, block: () -> Unit) {
        val error = assertFailsWith<SerializationException>(block = block)
        assertTrue("$path:" in error.message.orEmpty(), error.message)
    }
}
