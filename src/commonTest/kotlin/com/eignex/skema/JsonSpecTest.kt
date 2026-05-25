package com.eignex.skema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun emitsJsonSchemaForJsonSpecSchema() {
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
    fun mapperOverloadWorksForCustomVocabulary() {
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
    fun nullVariantEmitsNullType() {
        assertEquals(buildJsonObject { put("type", "null") }, JsonSpec.Null.toJsonSchema())
    }

    @Test
    fun longVariantEmitsIntegerWithLongBounds() {
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
    fun exclusiveBoundsAndMultipleOfRender() {
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
    fun strFormatAndMinLengthRender() {
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
    fun constEmitsConstField() {
        val js = JsonSpec.Const(JsonPrimitive("v1")).toJsonSchema()
        assertEquals(buildJsonObject { put("const", JsonPrimitive("v1")) }, js)
    }

    @Test
    fun nullableWrapsInAnyOfWithNull() {
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
    fun annotatedAddsAnnotationsAroundInner() {
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
    fun arrayWithItemsAndUniqueness() {
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
    fun objectWithPropertiesAndRequired() {
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
    fun oneOfAnyOfAllOfNotRender() {
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
    fun refAndDefsRender() {
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
    fun ifThenElseRenders() {
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
}
