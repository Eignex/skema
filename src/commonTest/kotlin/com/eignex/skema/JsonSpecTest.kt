package com.eignex.skema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonSpecTest {
    @Test
    fun `schema exports require every entry forbid extras and resolve supplied definitions`() {
        val definition = SchemaDef(
            linkedMapOf(
                "enabled" to JsonSpec.Bool,
                "age" to JsonSpec.Int(min = 13, max = 120),
                "owner" to JsonSpec.Ref("#/\$defs/User"),
            ),
        )
        val schema = definition.toJsonSchema(
            defs = mapOf(
                "User" to JsonSpec.Object(
                    properties = mapOf("name" to JsonSpec.Str()),
                    required = listOf("name"),
                    additionalPropertiesAllowed = false,
                ),
            ),
        )

        assertEquals(
            Json.parseToJsonElement(
                """{
                  "${'$'}schema":"https://json-schema.org/draft/2020-12/schema",
                  "type":"object",
                  "properties":{"enabled":{"type":"boolean"},"age":{"type":"integer","minimum":13,"maximum":120},
                    "owner":{"${'$'}ref":"#/${'$'}defs/User"}},
                  "required":["enabled","age","owner"],"additionalProperties":false,
                  "${'$'}defs":{"User":{"type":"object","properties":{"name":{"type":"string"}},
                    "required":["name"],"additionalProperties":false}}
                }""",
            ),
            schema,
        )
    }

    @Test
    fun `mapper overload exports a custom vocabulary without unused definitions`() {
        val schema = SchemaDef(mapOf("flag" to "BOOL")).toJsonSchema { tag ->
            assertEquals("BOOL", tag)
            JsonSpec.Bool.toJsonSchema()
        }

        assertEquals(
            Json.parseToJsonElement(
                """{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object",
                  "properties":{"flag":{"type":"boolean"}},"required":["flag"],"additionalProperties":false}""",
            ),
            schema,
        )
    }

    @Test
    fun `numeric schemas preserve bounds exclusive limits and divisors`() {
        assertSchema(
            """{"type":"integer","exclusiveMinimum":0,"exclusiveMaximum":100,"multipleOf":5}""",
            JsonSpec.Int(exclusiveMin = 0, exclusiveMax = 100, multipleOf = 5),
        )
        assertSchema(
            """{"type":"integer","minimum":1,"maximum":9007199254740993,
              "exclusiveMinimum":0,"exclusiveMaximum":9007199254740994,"multipleOf":1000}""",
            JsonSpec.Long(
                min = 1,
                max = 9_007_199_254_740_993,
                exclusiveMin = 0,
                exclusiveMax = 9_007_199_254_740_994,
                multipleOf = 1000,
            ),
        )
        assertSchema(
            """{"type":"number","minimum":-1.5,"maximum":2.5,
              "exclusiveMinimum":0.5,"exclusiveMaximum":1.5,"multipleOf":0.25}""",
            JsonSpec.Num(min = -1.5, max = 2.5, exclusiveMin = 0.5, exclusiveMax = 1.5, multipleOf = 0.25),
        )
    }

    @Test
    fun `string schemas retain constraints formats and enumerated values`() {
        assertSchema(
            """{"type":"string","minLength":3,"maxLength":32,"pattern":"^[a-z]+$","format":"email"}""",
            JsonSpec.Str(minLength = 3, maxLength = 32, pattern = "^[a-z]+$", format = "email"),
        )
        assertSchema(
            """{"type":"string","enum":["red","green","blue"]}""",
            JsonSpec.Enum(listOf("red", "green", "blue")),
        )
    }

    @Test
    fun `nested composition renders each branch and nullability without losing constraints`() {
        val spec = JsonSpec.IfThenElse(
            condition = JsonSpec.AnyOf(
                listOf(
                    JsonSpec.OneOf(listOf(JsonSpec.Bool, JsonSpec.Const(JsonPrimitive("admin")))),
                    JsonSpec.AllOf(listOf(JsonSpec.Int(min = 0), JsonSpec.Int(max = 100))),
                ),
            ),
            then = JsonSpec.Nullable(JsonSpec.Str()),
            otherwise = JsonSpec.Not(JsonSpec.Null),
        )

        assertSchema(
            """{
              "if":{"anyOf":[{"oneOf":[{"type":"boolean"},{"const":"admin"}]},
                {"allOf":[{"type":"integer","minimum":0},{"type":"integer","maximum":100}]}]},
              "then":{"anyOf":[{"type":"string"},{"type":"null"}]},"else":{"not":{"type":"null"}}
            }""",
            spec,
        )
    }

    @Test
    fun `conditional schemas omit absent branches`() {
        assertSchema(
            """{"if":{"type":"boolean"},"then":{"type":"null"}}""",
            JsonSpec.IfThenElse(JsonSpec.Bool, then = JsonSpec.Null),
        )
        assertSchema(
            """{"if":{"type":"boolean"},"else":{"type":"null"}}""",
            JsonSpec.IfThenElse(JsonSpec.Bool, otherwise = JsonSpec.Null),
        )
    }

    @Test
    fun `array schemas combine prefix trailing items size and uniqueness`() {
        assertSchema(
            """{"type":"array","items":{"type":"integer","minimum":0},
              "prefixItems":[{"type":"integer"},{"type":"string"}],"minItems":1,"maxItems":4,"uniqueItems":true}""",
            JsonSpec.Array(
                items = JsonSpec.Int(min = 0),
                prefixItems = listOf(JsonSpec.Int(), JsonSpec.Str()),
                minItems = 1,
                maxItems = 4,
                uniqueItems = true,
            ),
        )
    }

    @Test
    fun `object additional property schemas take precedence alongside property count bounds`() {
        assertSchema(
            """{"type":"object","additionalProperties":{"type":"string"},"minProperties":1,"maxProperties":5}""",
            JsonSpec.Object(
                additionalPropertiesAllowed = false,
                additionalPropertiesSpec = JsonSpec.Str(),
                minProperties = 1,
                maxProperties = 5,
            ),
        )
    }

    @Test
    fun `schemas omit absent optional constraints`() {
        assertSchema("""{"type":"integer"}""", JsonSpec.Int())
        assertSchema("""{"type":"integer"}""", JsonSpec.Long())
        assertSchema("""{"type":"number"}""", JsonSpec.Num())
        assertSchema("""{"type":"string"}""", JsonSpec.Str())
        assertSchema("""{"type":"array"}""", JsonSpec.Array())
        assertSchema("""{"type":"object"}""", JsonSpec.Object())
    }

    @Test
    fun `annotations preserve the inner schema and every documentation field`() {
        assertSchema(
            """{"type":"integer","title":"Age","description":"Years since birth","default":0,
              "examples":[13,42],"deprecated":false,"readOnly":true,
              "writeOnly":false,"${'$'}comment":"internal note"}""",
            JsonSpec.Annotated(
                inner = JsonSpec.Int(),
                title = "Age",
                description = "Years since birth",
                default = JsonPrimitive(0),
                examples = listOf(JsonPrimitive(13), JsonPrimitive(42)),
                deprecated = false,
                readOnly = true,
                writeOnly = false,
                comment = "internal note",
            ),
        )
    }

    @Test
    fun `every JsonSpec variant preserves its wire name and payload`() {
        val variants = listOf(
            "Bool" to JsonSpec.Bool,
            "Null" to JsonSpec.Null,
            "Int" to JsonSpec.Int(min = 1, max = 10, exclusiveMin = 0, exclusiveMax = 11, multipleOf = 2),
            "Long" to JsonSpec.Long(min = 1L, max = 10L, exclusiveMin = 0L, exclusiveMax = 11L, multipleOf = 2L),
            "Number" to JsonSpec.Num(min = 0.0, max = 10.0, exclusiveMin = -1.0, exclusiveMax = 11.0, multipleOf = 0.5),
            "String" to JsonSpec.Str(minLength = 1, maxLength = 10, pattern = "[a-z]", format = "email"),
            "Enum" to JsonSpec.Enum(listOf("a", "b")),
            "Object" to JsonSpec.Object(
                properties = mapOf("flag" to JsonSpec.Bool),
                required = listOf("flag"),
                additionalPropertiesAllowed = false,
                minProperties = 1,
                maxProperties = 2,
            ),
            "Array" to JsonSpec.Array(
                items = JsonSpec.Str(),
                prefixItems = listOf(JsonSpec.Int()),
                minItems = 1,
                maxItems = 3,
                uniqueItems = true,
            ),
            "Const" to JsonSpec.Const(JsonNull),
            "Ref" to JsonSpec.Ref("#/\$defs/User"),
            "OneOf" to JsonSpec.OneOf(listOf(JsonSpec.Bool, JsonSpec.Null)),
            "AnyOf" to JsonSpec.AnyOf(listOf(JsonSpec.Bool, JsonSpec.Null)),
            "AllOf" to JsonSpec.AllOf(listOf(JsonSpec.Int(min = 0), JsonSpec.Int(max = 10))),
            "Not" to JsonSpec.Not(JsonSpec.Null),
            "IfThenElse" to JsonSpec.IfThenElse(JsonSpec.Bool, then = JsonSpec.Int(), otherwise = JsonSpec.Str()),
            "Nullable" to JsonSpec.Nullable(JsonSpec.Null),
            "Annotated" to JsonSpec.Annotated(
                JsonSpec.Int(),
                title = "Age",
                description = "Years",
                default = JsonNull,
                examples = listOf(JsonNull, JsonPrimitive(42)),
                deprecated = false,
                readOnly = true,
                writeOnly = false,
                comment = "note",
            ),
        )

        for ((wireName, spec) in variants) {
            val wire = SchemaJson.encodeToString(JsonSpec.serializer(), spec)
            val encoded = SchemaJson.parseToJsonElement(wire) as JsonObject
            assertEquals(wireName, encoded["\$type"]?.jsonPrimitive?.contentOrNull, wireName)
            val decoded = SchemaJson.decodeFromString(JsonSpec.serializer(), wire)
            assertEquals(spec, decoded, wireName)
        }
    }

    @Test
    fun `annotation defaults distinguish absent values from explicit JSON null`() {
        for (encodeDefaults in listOf(false, true)) {
            for (explicitNulls in listOf(false, true)) {
                val json = Json {
                    schemaJsonConfig()
                    this.encodeDefaults = encodeDefaults
                    this.explicitNulls = explicitNulls
                }
                for (default in listOf(null, JsonNull)) {
                    val spec: JsonSpec = JsonSpec.Annotated(JsonSpec.Bool, default = default)
                    val wire = json.encodeToString(JsonSpec.serializer(), spec)

                    assertEquals(spec, json.decodeFromString(JsonSpec.serializer(), wire))
                    val rendered = spec.toJsonSchema()
                    assertEquals(default != null, "default" in rendered)
                    if (default != null) assertEquals(JsonNull, rendered["default"])
                }
            }
        }
    }

    private fun assertSchema(expected: String, spec: JsonSpec) {
        assertEquals(Json.parseToJsonElement(expected), spec.toJsonSchema())
    }
}
