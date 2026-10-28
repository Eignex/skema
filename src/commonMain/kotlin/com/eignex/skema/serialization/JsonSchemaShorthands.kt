package com.eignex.skema.serialization

import com.eignex.skema.typedSchema
import kotlinx.serialization.json.*

internal class JsonSchemaShorthands {
    private data class Shorthand(val schema: JsonObject, val name: String, val payload: JsonObject)

    private val shorthands = mutableListOf<Shorthand>()

    fun operator(name: String, payload: JsonObject): JsonObject {
        val schema = typedSchema("object") {
            put("properties", buildJsonObject { put(name, payload) })
            put("required", JsonArray(listOf(JsonPrimitive(name))))
            put("additionalProperties", false)
        }
        shorthands += Shorthand(schema, name, payload)
        return schema
    }

    // Run after recursive definitions are complete; their placeholders admit everything.
    fun includeIn(document: JsonObject): JsonObject {
        val evaluator = EmptyObjectSchema(document)
        fun rewrite(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map { rewrite(it) })

            is JsonObject -> {
                val rewritten = JsonObject(element.mapValues { (_, value) -> rewrite(value) })
                val shorthand = shorthands.firstOrNull { it.schema === element }
                if (shorthand != null && evaluator.accepts(shorthand.payload)) {
                    buildJsonObject {
                        put("anyOf", JsonArray(listOf(rewritten, buildJsonObject { put("const", shorthand.name) })))
                    }
                } else {
                    rewritten
                }
            }

            else -> element
        }
        return rewrite(document).jsonObject
    }
}

private class EmptyObjectSchema(private val document: JsonObject) {
    // Evaluate only the empty object. Property, item and primitive constraints are vacuous here.
    fun accepts(schema: JsonElement, references: Set<String> = emptySet()): Boolean {
        if (schema is JsonPrimitive) return schema.boolean
        val body = schema.jsonObject
        val type = body["type"]
        if (type != null && type != JsonPrimitive("object") &&
            (type !is JsonArray || JsonPrimitive("object") !in type)
        ) {
            return false
        }
        if (!body["required"]?.jsonArray.isNullOrEmpty()) return false
        if ((body["minProperties"]?.jsonPrimitive?.int ?: 0) > 0) return false
        val empty = JsonObject(emptyMap())
        if ("const" in body && body["const"] != empty) return false
        if ("enum" in body && empty !in body.getValue("enum").jsonArray) return false
        require("\$dynamicRef" !in body) { "Dynamic payload references require an explicit inline schema" }
        body["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            require(reference.startsWith("#/") && reference !in references) {
                "Cannot resolve payload schema reference: $reference"
            }
            val target = resolve(reference)
            if (!accepts(target, references + reference)) return false
        }
        fun branchAccepts(value: JsonElement): Boolean = accepts(value, references)
        if (body["allOf"]?.jsonArray?.all(::branchAccepts) == false) return false
        if (body["anyOf"]?.jsonArray?.any(::branchAccepts) == false) return false
        if (body["oneOf"]?.jsonArray?.count(::branchAccepts)?.let { it != 1 } == true) return false
        if (body["not"]?.let(::branchAccepts) == true) return false
        body["if"]?.let { condition ->
            val branch = body[if (branchAccepts(condition)) "then" else "else"]
            if (branch != null && !branchAccepts(branch)) return false
        }
        return true
    }

    private fun resolve(reference: String): JsonElement {
        val parts = reference.removePrefix("#/").split('/')
        return parts.fold(document as JsonElement) { value, part ->
            val key = part.replace("~1", "/").replace("~0", "~")
            when (value) {
                is JsonObject -> value.getValue(key)
                is JsonArray -> value[key.toInt()]
                else -> error("Cannot resolve payload schema reference: $reference")
            }
        }
    }
}
