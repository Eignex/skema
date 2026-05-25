package com.eignex.skema

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class PKey(val name: String)

@Suppress("AbstractClassCanBeConcreteClass")
private abstract class PrimSchema : Schema<JsonSpec>() {
    protected fun bool() = register(JsonSpec.Bool) { PKey(it) }
    protected fun int(min: Int, max: Int) = register(JsonSpec.Int(min, max)) { PKey(it) }
    protected fun str(pattern: String? = null) = register(JsonSpec.Str(pattern = pattern)) { PKey(it) }
}

private object SignupSchema : PrimSchema() {
    val acceptsTos by bool()
    val age by int(13, 120)
    val handle by str(pattern = "^[a-z]+$")
}

@Suppress("LargeClass")
class JsonSpecTest {
    @Test
    fun `emits JSON Schema for a JsonSpec-typed schema`() {
        val js = SignupSchema.definition().toJsonSchema()

        assertEquals("https://json-schema.org/draft/2020-12/schema", js["\$schema"]!!.toString().trim('"'))
        val props = js["properties"] as JsonObject
        assertEquals(buildJsonObject { put("type", "boolean") }, props["acceptsTos"])
        assertEquals(
            buildJsonObject {
                put("type", "integer")
                put("minimum", 13)
                put("maximum", 120)
            },
            props["age"],
        )
        assertEquals(
            buildJsonObject {
                put("type", "string")
                put("pattern", "^[a-z]+$")
            },
            props["handle"],
        )
        assertEquals("[\"acceptsTos\",\"age\",\"handle\"]", js["required"]!!.toString())
        assertEquals("false", js["additionalProperties"]!!.toString())
    }

    @Test
    fun `mapper overload works for a custom vocabulary`() {
        val def = SchemaDef(mapOf("flag" to "BOOL", "n" to "INT"))
        val js = def.toJsonSchema { tag ->
            when (tag) {
                "BOOL" -> JsonSpec.Bool.toJsonSchema()
                "INT" -> JsonSpec.Int().toJsonSchema()
                else -> error("unknown: $tag")
            }
        }
        val props = js["properties"] as JsonObject
        assertEquals(buildJsonObject { put("type", "boolean") }, props["flag"])
        assertEquals(buildJsonObject { put("type", "integer") }, props["n"])
    }

    @Test
    fun `Null variant emits null type`() {
        assertEquals(buildJsonObject { put("type", "null") }, JsonSpec.Null.toJsonSchema())
    }

    @Test
    fun `Long variant emits integer type with long bounds`() {
        val js = JsonSpec.Long(min = 1L, max = 9_999_999_999L).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "integer")
                put("minimum", 1L)
                put("maximum", 9_999_999_999L)
            },
            js,
        )
    }

    @Test
    fun `Num renders exclusive bounds and multipleOf`() {
        val js = JsonSpec.Num(exclusiveMin = 0.0, multipleOf = 0.25).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "number")
                put("exclusiveMinimum", 0.0)
                put("multipleOf", 0.25)
            },
            js,
        )
    }

    @Test
    fun `Str renders format and minLength`() {
        val js = JsonSpec.Str(minLength = 3, format = "email").toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "string")
                put("minLength", 3)
                put("format", "email")
            },
            js,
        )
    }

    @Test
    fun `Const emits a const field`() {
        val js = JsonSpec.Const(JsonPrimitive("v1")).toJsonSchema()
        assertEquals(buildJsonObject { put("const", JsonPrimitive("v1")) }, js)
    }

    @Test
    fun `Nullable wraps inner in anyOf with null`() {
        val js = JsonSpec.Nullable(JsonSpec.Int(min = 0)).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put(
                    "anyOf",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 0)
                            },
                        )
                        add(buildJsonObject { put("type", "null") })
                    },
                )
            },
            js,
        )
    }

    @Test
    fun `Annotated adds annotation fields around inner`() {
        val js = JsonSpec.Annotated(
            inner = JsonSpec.Str(),
            title = "Handle",
            description = "User handle",
            deprecated = true,
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "string")
                put("title", "Handle")
                put("description", "User handle")
                put("deprecated", true)
            },
            js,
        )
    }

    @Test
    fun `Array renders items, minItems and uniqueItems`() {
        val js = JsonSpec.Array(
            items = JsonSpec.Int(min = 0),
            minItems = 1,
            uniqueItems = true,
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "array")
                put(
                    "items",
                    buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                    },
                )
                put("minItems", 1)
                put("uniqueItems", true)
            },
            js,
        )
    }

    @Test
    fun `Object renders properties, required and additionalProperties false`() {
        val js = JsonSpec.Object(
            properties = mapOf("a" to JsonSpec.Bool, "b" to JsonSpec.Int()),
            required = listOf("a"),
            additionalPropertiesAllowed = false,
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put("a", buildJsonObject { put("type", "boolean") })
                        put("b", buildJsonObject { put("type", "integer") })
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive("a")) })
                put("additionalProperties", false)
            },
            js,
        )
    }

    @Test
    fun `OneOf and Not render`() {
        val oneOf = JsonSpec.OneOf(listOf(JsonSpec.Bool, JsonSpec.Null)).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put(
                    "oneOf",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "boolean") })
                        add(buildJsonObject { put("type", "null") })
                    },
                )
            },
            oneOf,
        )
        val not = JsonSpec.Not(JsonSpec.Bool).toJsonSchema()
        assertEquals(buildJsonObject { put("not", buildJsonObject { put("type", "boolean") }) }, not)
    }

    @Test
    fun `Ref pointer and root defs render`() {
        val schema = object : PrimSchema() {
            val owner by register(JsonSpec.Ref("#/\$defs/User")) { PKey(it) }
        }
        val js = schema.definition().toJsonSchema(
            defs = mapOf("User" to JsonSpec.Object(properties = mapOf("id" to JsonSpec.Int()))),
        )
        val props = js["properties"] as JsonObject
        assertEquals(buildJsonObject { put("\$ref", "#/\$defs/User") }, props["owner"])
        val defs = js["\$defs"] as JsonObject
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { put("id", buildJsonObject { put("type", "integer") }) })
            },
            defs["User"],
        )
    }

    @Test
    fun `IfThenElse renders all three branches`() {
        val js = JsonSpec.IfThenElse(
            condition = JsonSpec.Const(JsonPrimitive("admin")),
            then = JsonSpec.Bool,
            otherwise = JsonSpec.Null,
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("if", buildJsonObject { put("const", JsonPrimitive("admin")) })
                put("then", buildJsonObject { put("type", "boolean") })
                put("else", buildJsonObject { put("type", "null") })
            },
            js,
        )
    }

    @Test
    fun `IfThenElse omits missing then or otherwise branches`() {
        val onlyThen = JsonSpec.IfThenElse(JsonSpec.Bool, then = JsonSpec.Null).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("if", buildJsonObject { put("type", "boolean") })
                put("then", buildJsonObject { put("type", "null") })
            },
            onlyThen,
        )
        val onlyOtherwise = JsonSpec.IfThenElse(JsonSpec.Bool, otherwise = JsonSpec.Null).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("if", buildJsonObject { put("type", "boolean") })
                put("else", buildJsonObject { put("type", "null") })
            },
            onlyOtherwise,
        )
    }

    @Test
    fun `Int renders exclusive bounds and multipleOf`() {
        val js = JsonSpec.Int(exclusiveMin = 0, exclusiveMax = 100, multipleOf = 5).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "integer")
                put("exclusiveMinimum", 0)
                put("exclusiveMaximum", 100)
                put("multipleOf", 5)
            },
            js,
        )
    }

    @Test
    fun `Long renders exclusive bounds and multipleOf`() {
        val js = JsonSpec.Long(
            exclusiveMin = 0L,
            exclusiveMax = 1_000_000_000_000L,
            multipleOf = 1_000L,
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "integer")
                put("exclusiveMinimum", 0L)
                put("exclusiveMaximum", 1_000_000_000_000L)
                put("multipleOf", 1_000L)
            },
            js,
        )
    }

    @Test
    fun `Enum renders as a string type with the listed values`() {
        val js = JsonSpec.Enum(listOf("red", "green", "blue")).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "string")
                put(
                    "enum",
                    buildJsonArray {
                        add(JsonPrimitive("red"))
                        add(JsonPrimitive("green"))
                        add(JsonPrimitive("blue"))
                    },
                )
            },
            js,
        )
    }

    @Test
    fun `Str renders maxLength and pattern`() {
        val js = JsonSpec.Str(maxLength = 32, pattern = "^[a-z]+$").toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "string")
                put("maxLength", 32)
                put("pattern", "^[a-z]+$")
            },
            js,
        )
    }

    @Test
    fun `unbounded primitives omit optional fields`() {
        assertEquals(buildJsonObject { put("type", "integer") }, JsonSpec.Int().toJsonSchema())
        assertEquals(buildJsonObject { put("type", "integer") }, JsonSpec.Long().toJsonSchema())
        assertEquals(buildJsonObject { put("type", "number") }, JsonSpec.Num().toJsonSchema())
        assertEquals(buildJsonObject { put("type", "string") }, JsonSpec.Str().toJsonSchema())
    }

    @Test
    fun `Annotated carries every documentation field`() {
        val js = JsonSpec.Annotated(
            inner = JsonSpec.Int(),
            title = "Age",
            description = "Years since birth",
            default = JsonPrimitive(0),
            examples = listOf(JsonPrimitive(13), JsonPrimitive(42)),
            deprecated = false,
            readOnly = true,
            writeOnly = false,
            comment = "internal note",
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "integer")
                put("title", "Age")
                put("description", "Years since birth")
                put("default", JsonPrimitive(0))
                put(
                    "examples",
                    buildJsonArray {
                        add(JsonPrimitive(13))
                        add(JsonPrimitive(42))
                    },
                )
                put("deprecated", false)
                put("readOnly", true)
                put("writeOnly", false)
                put("\$comment", "internal note")
            },
            js,
        )
    }

    @Test
    fun `Array renders prefixItems and maxItems`() {
        val js = JsonSpec.Array(
            prefixItems = listOf(JsonSpec.Int(), JsonSpec.Str()),
            maxItems = 4,
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "array")
                put(
                    "prefixItems",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "integer") })
                        add(buildJsonObject { put("type", "string") })
                    },
                )
                put("maxItems", 4)
            },
            js,
        )
    }

    @Test
    fun `Array without items emits a bare array type`() {
        assertEquals(buildJsonObject { put("type", "array") }, JsonSpec.Array().toJsonSchema())
    }

    @Test
    fun `Object additionalPropertiesSpec wins over additionalPropertiesAllowed`() {
        val js = JsonSpec.Object(
            additionalPropertiesAllowed = false,
            additionalPropertiesSpec = JsonSpec.Str(),
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put("additionalProperties", buildJsonObject { put("type", "string") })
            },
            js,
        )
    }

    @Test
    fun `Object renders minProperties and maxProperties`() {
        val js = JsonSpec.Object(
            minProperties = 1,
            maxProperties = 5,
        ).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put("type", "object")
                put("minProperties", 1)
                put("maxProperties", 5)
            },
            js,
        )
    }

    @Test
    fun `Object with no fields emits a bare object type`() {
        assertEquals(buildJsonObject { put("type", "object") }, JsonSpec.Object().toJsonSchema())
    }

    @Test
    fun `AnyOf and AllOf render`() {
        val anyOf = JsonSpec.AnyOf(listOf(JsonSpec.Int(), JsonSpec.Str())).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put(
                    "anyOf",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "integer") })
                        add(buildJsonObject { put("type", "string") })
                    },
                )
            },
            anyOf,
        )
        val allOf = JsonSpec.AllOf(listOf(JsonSpec.Int(min = 0), JsonSpec.Int(max = 100))).toJsonSchema()
        assertEquals(
            buildJsonObject {
                put(
                    "allOf",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "integer")
                                put("minimum", 0)
                            },
                        )
                        add(
                            buildJsonObject {
                                put("type", "integer")
                                put("maximum", 100)
                            },
                        )
                    },
                )
            },
            allOf,
        )
    }

    @Test
    fun `deep nesting renders recursively`() {
        val spec = JsonSpec.Array(
            items = JsonSpec.Object(
                properties = mapOf(
                    "tag" to JsonSpec.OneOf(
                        listOf(JsonSpec.Const(JsonPrimitive("a")), JsonSpec.Const(JsonPrimitive("b"))),
                    ),
                    "value" to JsonSpec.Nullable(JsonSpec.Int(min = 0)),
                ),
                required = listOf("tag"),
            ),
        )
        val js = spec.toJsonSchema()
        val items = js["items"] as JsonObject
        val props = items["properties"] as JsonObject
        val tag = props["tag"] as JsonObject
        assertEquals(
            buildJsonArray {
                add(buildJsonObject { put("const", JsonPrimitive("a")) })
                add(buildJsonObject { put("const", JsonPrimitive("b")) })
            },
            tag["oneOf"],
        )
        val value = props["value"] as JsonObject
        val anyOf = value["anyOf"]!!.toString()
        assertTrue("integer" in anyOf && "null" in anyOf, "expected nullable int union, got: $anyOf")
    }

    @Test
    fun `JsonSpec round-trips through kotlinx serialization`() {
        val original: JsonSpec = JsonSpec.Object(
            properties = mapOf(
                "id" to JsonSpec.Long(min = 1L),
                "kind" to JsonSpec.Enum(listOf("a", "b")),
                "tags" to JsonSpec.Array(items = JsonSpec.Str(minLength = 1), uniqueItems = true),
                "ref" to JsonSpec.Ref("#/\$defs/Other"),
                "doc" to JsonSpec.Annotated(JsonSpec.Bool, description = "flag"),
            ),
            required = listOf("id"),
        )
        val json = Json {
            classDiscriminator = "\$type"
            encodeDefaults = false
            explicitNulls = false
        }
        val wire = json.encodeToString(JsonSpec.serializer(), original)
        val decoded = json.decodeFromString(JsonSpec.serializer(), wire)
        assertEquals(original, decoded)
        assertEquals(original.toJsonSchema(), decoded.toJsonSchema())
    }

    @Test
    fun `empty defs map does not emit a defs field`() {
        val js = SignupSchema.definition().toJsonSchema()
        assertEquals(false, js.containsKey("\$defs"))
    }
}
