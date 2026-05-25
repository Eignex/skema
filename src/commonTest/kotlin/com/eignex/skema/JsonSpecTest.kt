package com.eignex.skema

import kotlinx.serialization.json.JsonObject
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
}
