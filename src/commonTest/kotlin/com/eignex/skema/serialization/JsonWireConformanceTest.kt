@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaJson
import kotlinx.serialization.Contextual
import kotlinx.serialization.ContextualSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@Serializable(with = WireValueSerializer::class)
private sealed interface WireValue

@Serializable
private data class RawJsonValues(
    val primitive: JsonPrimitive,
    val nil: JsonNull,
    val record: JsonObject,
    val array: JsonArray,
    val arbitrary: JsonElement,
) : WireValue

@Serializable
private data class UnsignedValues(val byte: UByte, val short: UShort, val int: UInt, val long: ULong) : WireValue

@Serializable
private data class UnsignedLiteral(val value: ULong) : WireValue

@Serializable
@JvmInline
private value class UnsignedId(val value: ULong)

@Serializable
private data class WrappedUnsigned(val id: UnsignedId) : WireValue

@Serializable
@JvmInline
private value class NullableCount(val value: Int?)

@Serializable
private data class WrappedNullableCount(val value: NullableCount) : WireValue

@Serializable
private data class WireSettings(val flag: Boolean = false)

@Serializable
private data class WireContextual(@Contextual val settings: WireSettings?)

@Serializable
private data class MappedSettings(val options: WireSettings) : WireValue

@Serializable
private enum class WireKey { First, Second }

private class SettingsMapping(private val transform: (JsonObject) -> JsonObject = { it }) : JsonPayloadMapping {
    override fun encode(body: JsonObject): JsonElement = body.getValue("options")

    override fun decode(payload: JsonElement, path: String): JsonObject = JsonObject(mapOf("options" to payload))

    override fun payloadSchema(body: JsonObject): JsonObject =
        transform(body.getValue("properties").jsonObject.getValue("options").jsonObject)
}

private object WireValueSerializer : CompactSerializer<WireValue>("WireValue", WireValue::class, nodes = {
    listOf(
        jsonNode<RawJsonValues>("raw"),
        jsonNode<UnsignedValues>("unsigned"),
        jsonNode<UnsignedLiteral>("literal", JsonNodeShape.Literal, "value"),
        jsonNode<WrappedUnsigned>("wrapped"),
        jsonNode<WrappedNullableCount>("nullableCount"),
        jsonNode<MappedSettings>("mapped", mapping = SettingsMapping()),
    )
})

class JsonWireConformanceTest {
    @Test
    fun `contextual schemas agree with strict decoding and encoded values`() {
        val json = Json {
            serializersModule = SerializersModule { contextual(WireSettings.serializer()) }
        }
        val serializer = WireContextual.serializer()
        val valid = listOf(null, WireSettings(), WireSettings(true)).map { settings ->
            val value = WireContextual(settings)
            val encoded = json.encodeToJsonElement(serializer, value)
            assertEquals(value, json.decodeFromJsonElement(serializer, encoded, JsonNodeCatalog()))
            encoded
        }
        val invalid = listOf(
            """{"settings":{"flag":"true"}}""",
            """{"settings":{"extra":1}}""",
            """{"settings":[]}""",
            """{}""",
        ).map { wire ->
            val element = json.parseToJsonElement(wire)
            assertFailsWith<SerializationException> {
                json.decodeFromJsonElement(serializer, element, JsonNodeCatalog())
            }
            element
        }
        schemaExamples("contextual settings", serializer.toJsonSchema(JsonNodeCatalog(), json), valid, invalid)
    }

    @Test
    fun `nullable numeric inline values preserve null in decoding and generated schemas`() {
        for (explicit in listOf(false, true)) {
            val json = Json { explicitNulls = explicit }
            val valid = listOf(null, 0, Int.MAX_VALUE).map { count ->
                val value: WireValue = WrappedNullableCount(NullableCount(count))
                val encoded = json.encodeToJsonElement(WireValueSerializer, value)
                assertEquals(value, json.decodeFromJsonElement(WireValueSerializer, encoded))
                encoded
            }
            schemaExamples(
                "nullable inline explicitNulls=$explicit",
                WireValueSerializer.toJsonSchema(json),
                valid,
                emptyList(),
            )
        }
    }

    @Test
    fun `floating point decoding rejects tokens that overflow to infinity`() {
        scalarExamples(
            Float.serializer(),
            listOf("0", "-0.0", "3.4028235e38", "-3.4028235e38", "1.4e-45"),
            listOf("\"1\"", "true"),
            listOf(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE),
        )
        scalarExamples(
            Double.serializer(),
            listOf("0", "-0.0", "1.7976931348623157e308", "-1.7976931348623157e308", "4.9e-324"),
            listOf("\"1\"", "true"),
            listOf(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MIN_VALUE),
        )
        for (token in listOf("1e100", "-1e100")) invalidWire(Float.serializer(), token)
        for (token in listOf("1e400", "-1e400")) invalidWire(Double.serializer(), token)
    }

    @Test
    fun `character schemas accept single UTF16 code units only`() {
        scalarExamples(
            Char.serializer(),
            listOf("\"a\"", "\"\\n\"", "\"\\u0000\"", "\"\\ud800\"", "\"\\udfff\""),
            listOf("\"😀\"", "\"ab\"", "\"\""),
            listOf('a', '\n', '\u0000', '\uD800', '\uDFFF'),
        )
    }

    @Test
    fun `concrete JSON tree values round trip inside compact records`() {
        val values = listOf(JsonPrimitive(7), JsonPrimitive(2.5), JsonPrimitive(true), JsonPrimitive("text"), JsonNull)
        val encoded = values.map { primitive ->
            roundTrip<WireValue>(
                WireValueSerializer,
                RawJsonValues(
                    primitive,
                    JsonNull,
                    buildJsonObject { put("nested", JsonNull) },
                    buildJsonArray {
                        add(1)
                        add(JsonNull)
                    },
                    buildJsonArray { add(buildJsonObject { put("x", false) }) },
                ),
            )
        }
        val invalid = listOf(
            """{"raw":{"primitive":[],"nil":null,"record":{},"array":[],"arbitrary":null}}""",
            """{"raw":{"primitive":{},"nil":null,"record":{},"array":[],"arbitrary":null}}""",
            """{"raw":{"primitive":1,"nil":false,"record":{},"array":[],"arbitrary":null}}""",
            """{"raw":{"primitive":1,"nil":null,"record":null,"array":[],"arbitrary":null}}""",
        ).map { invalidWire(WireValueSerializer, it) }

        schemaExamples("raw JSON records", WireValueSerializer.toJsonSchema(), encoded, invalid)
    }

    @Test
    fun `raw primitives and nulls retain their semantics at document roots`() {
        val primitives = listOf(JsonPrimitive(7), JsonPrimitive(true), JsonPrimitive("text"), JsonNull)
        primitives.forEach { roundTrip(JsonPrimitive.serializer(), it) }
        roundTrip(JsonNull.serializer(), JsonNull)
        roundTrip(JsonPrimitive.serializer().nullable, null)
        schemaExamples(
            "raw primitive root",
            JsonPrimitive.serializer().toJsonSchema(JsonNodeCatalog()),
            primitives,
            listOf(JsonArray(emptyList()), JsonObject(emptyMap())),
        )
        schemaExamples(
            "raw null root",
            JsonNull.serializer().toJsonSchema(JsonNodeCatalog()),
            listOf(JsonNull),
            listOf(JsonPrimitive("null"), JsonPrimitive(false)),
        )
    }

    @Test
    fun `all unsigned boundaries round trip without signed overflow`() {
        val values = listOf(
            UnsignedValues(0u, 0u, 0u, 0u),
            UnsignedValues(UByte.MAX_VALUE, UShort.MAX_VALUE, UInt.MAX_VALUE, ULong.MAX_VALUE),
        )
        val encoded = values.map { roundTrip<WireValue>(WireValueSerializer, it) }
        listOf(
            """{"unsigned":{"byte":256,"short":0,"int":0,"long":0}}""",
            """{"unsigned":{"byte":0,"short":65536,"int":0,"long":0}}""",
            """{"unsigned":{"byte":0,"short":0,"int":4294967296,"long":0}}""",
            """{"unsigned":{"byte":0,"short":0,"int":0,"long":18446744073709551616}}""",
        ).forEach { invalidWire(WireValueSerializer, it) }
        val invalid = listOf(
            """{"unsigned":{"byte":0,"short":0,"int":0,"long":-1}}""",
            """{"unsigned":{"byte":0,"short":0,"int":0,"long":"1"}}""",
        ).map { invalidWire(WireValueSerializer, it) }

        schemaExamples("unsigned boundaries", WireValueSerializer.toJsonSchema(), encoded, invalid)
    }

    @Test
    fun `unsigned integral decimal and exponent tokens normalize exactly`() {
        val accepted = listOf("18446744073709551615.0", "1.8446744073709551615e19", "184467440737095516150e-1")
        for (token in accepted) {
            val element = SchemaJson.parseToJsonElement(token)
            assertEquals(
                UnsignedLiteral(ULong.MAX_VALUE),
                SchemaJson.decodeFromJsonElement(WireValueSerializer, element),
            )
        }
        val encoded = roundTrip<WireValue>(WireValueSerializer, UnsignedLiteral(ULong.MAX_VALUE))
        assertEquals("18446744073709551615", encoded.toString())
        roundTrip<WireValue>(WireValueSerializer, WrappedUnsigned(UnsignedId(ULong.MAX_VALUE)))
        listOf("18446744073709551616.0", "1.8446744073709551616e19").forEach { invalidWire(WireValueSerializer, it) }
        val invalid = listOf("-1", "1e-1").map { invalidWire(WireValueSerializer, it) }

        schemaExamples(
            "unsigned literals",
            WireValueSerializer.toJsonSchema(),
            accepted.map(SchemaJson::parseToJsonElement) + encoded,
            invalid,
        )
    }

    @Test
    fun `mapped empty records admit shorthand through references and composition`() {
        val transforms: List<(JsonObject) -> JsonObject> = listOf(
            { it },
            { buildJsonObject { put("allOf", JsonArray(listOf(it))) } },
            { buildJsonObject { put("anyOf", JsonArray(listOf(it, buildJsonObject { put("type", "array") }))) } },
            { buildJsonObject { put("oneOf", JsonArray(listOf(it, buildJsonObject { put("type", "array") }))) } },
            { buildJsonObject { put("not", buildJsonObject { put("not", it) }) } },
            {
                buildJsonObject {
                    put("if", false)
                    put("then", false)
                    put("else", it)
                }
            },
            { JsonObject(it + ("description" to JsonPrimitive("settings"))) },
        )
        for ((index, transform) in transforms.withIndex()) {
            val serializer = CompactSerializer("MappedSettings$index", WireValue::class) {
                listOf(jsonNode<MappedSettings>("mapped", mapping = SettingsMapping(transform)))
            }
            val encoded = roundTrip<WireValue>(serializer, MappedSettings(WireSettings()))
            assertEquals(JsonPrimitive("mapped"), encoded)
            schemaExamples(
                "mapped shorthand $index",
                serializer.toJsonSchema(),
                listOf(encoded, roundTrip<WireValue>(serializer, MappedSettings(WireSettings(true)))),
                listOf(JsonPrimitive("unknown")),
            )
        }
    }

    @Test
    fun `integer map keys require digits and decode within each type's range`() {
        mapExamples(
            Byte.serializer(),
            listOf("0", "127", "-128", "000127", "-0"),
            listOf("1.0"),
            outOfRange = listOf("128", "-129"),
        )
        mapExamples(Short.serializer(), listOf("32767", "-32768"), emptyList(), outOfRange = listOf("32768", "-32769"))
        mapExamples(
            Int.serializer(),
            listOf("2147483647", "-2147483648"),
            emptyList(),
            outOfRange = listOf("2147483648", "-2147483649"),
        )
        mapExamples(
            Long.serializer(),
            listOf("9223372036854775807", "-9223372036854775808"),
            emptyList(),
            outOfRange = listOf("9223372036854775808"),
        )
        mapExamples(UByte.serializer(), listOf("0", "255"), listOf("-1"), outOfRange = listOf("256"))
        mapExamples(UShort.serializer(), listOf("65535"), emptyList(), outOfRange = listOf("65536"))
        mapExamples(UInt.serializer(), listOf("4294967295"), emptyList(), outOfRange = listOf("4294967296"))
        mapExamples(
            ULong.serializer(),
            listOf("18446744073709551615"),
            emptyList(),
            outOfRange = listOf("18446744073709551616"),
        )
        mapExamples(
            ContextualSerializer(Int::class),
            listOf("2147483647", "-2147483648"),
            listOf("2.0"),
            json = Json { serializersModule = SerializersModule { contextual(Int.serializer()) } },
            outOfRange = listOf("2147483648", "-2147483649"),
        )
    }

    @Test
    fun `string enum boolean and character map keys retain their wire constraints`() {
        mapExamples(String.serializer(), listOf("arbitrary", "", "1\n"), emptyList(), numeric = false)
        mapExamples(WireKey.serializer(), listOf("First", "Second"), listOf("Other"), numeric = false)
        mapExamples(Boolean.serializer(), listOf("true", "false"), listOf("1", "TRUE"), numeric = false)
        mapExamples(Char.serializer(), listOf("a", "\n"), listOf("", "ab", "😀"), numeric = false)
    }

    @Test
    fun `unsupported map keys fail schema generation explicitly`() {
        for (key in listOf(
            Float.serializer(),
            Double.serializer(),
            Int.serializer().nullable,
            WireSettings.serializer(),
        )) {
            val error = assertFails {
                MapSerializer(key, String.serializer()).toJsonSchema(JsonNodeCatalog())
            }
            assertTrue("explicit JSON Schema" in error.message.orEmpty(), error.message)
        }
    }

    private fun <T> roundTrip(serializer: KSerializer<T>, value: T, json: Json = SchemaJson): JsonElement {
        val element = json.encodeToJsonElement(serializer, value)
        assertEquals(value, json.decodeFromJsonElement(serializer, element, JsonNodeCatalog()))
        return element
    }

    private fun <T> invalidWire(serializer: KSerializer<T>, wire: String, json: Json = SchemaJson): JsonElement {
        val element = json.parseToJsonElement(wire)
        assertFailsWith<SerializationException> {
            json.decodeFromJsonElement(serializer, element, JsonNodeCatalog())
        }
        return element
    }

    private fun <T> scalarExamples(
        serializer: KSerializer<T>,
        valid: List<String>,
        invalid: List<String>,
        values: List<T>,
    ) {
        val accepted = valid.map { wire ->
            val element = SchemaJson.parseToJsonElement(wire)
            SchemaJson.decodeFromJsonElement(serializer, element, JsonNodeCatalog())
            element
        } + values.map { roundTrip(serializer, it) }
        val rejected = invalid.map { invalidWire(serializer, it) }
        schemaExamples(serializer.descriptor.serialName, serializer.toJsonSchema(JsonNodeCatalog()), accepted, rejected)
    }

    private fun <K> mapExamples(
        key: KSerializer<K>,
        valid: List<String>,
        invalid: List<String>,
        numeric: Boolean = true,
        json: Json = SchemaJson,
        outOfRange: List<String> = emptyList(),
    ) {
        val serializer = MapSerializer(key, String.serializer())
        fun record(name: String): JsonObject = buildJsonObject { put(name, "value") }
        val good = valid.map { name ->
            val element = record(name)
            val value = json.decodeFromJsonElement(serializer, element, JsonNodeCatalog())
            roundTrip(serializer, value, json)
            element
        }
        val bad = (invalid + if (numeric) listOf("bogus", "") else emptyList()).map { name ->
            invalidWire(serializer, record(name).toString(), json)
        }
        // The schema only constrains the digits, so these are rejected by decoding alone.
        for (name in outOfRange + if (numeric) listOf("1\n") else emptyList()) {
            invalidWire(serializer, record(name).toString(), json)
        }
        schemaExamples("map ${key.descriptor.serialName}", serializer.toJsonSchema(JsonNodeCatalog(), json), good, bad)
    }

    private fun schemaExamples(name: String, schema: JsonObject, valid: List<JsonElement>, invalid: List<JsonElement>) {
        // The schema CI check reads these cases from the JVM test XML, including cached test outputs.
        val example = buildJsonObject {
            put("name", name)
            put("schema", schema)
            put("valid", JsonArray(valid))
            put("invalid", JsonArray(invalid))
        }.toString()
        // Escape UTF-16 surrogates so XML/UTF-8 output preserves the exact character fixtures.
        val escaped = buildString {
            for (character in example) {
                if (character.isSurrogate()) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        println("JSON_SCHEMA_CASE:$escaped")
    }
}
