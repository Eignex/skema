package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Built-in primitive config vocabulary. Use as the type parameter of [Schema]
 * to get [toJsonSchema] without writing a mapper. For domain-specific configs,
 * define your own sealed type and use the [SchemaDef.toJsonSchema] overload
 * that takes a per-entry mapper lambda.
 */
@Serializable
sealed interface Primitive {
    /** Boolean value. Maps to `{"type":"boolean"}`. */
    @Serializable
    @SerialName("Bool")
    data object Bool : Primitive

    /** Integer value, optionally bounded. Maps to `{"type":"integer"}` plus `minimum`/`maximum`. */
    @Serializable
    @SerialName("Int")
    data class Int(
        /** Inclusive lower bound, or `null` for unbounded. */
        val min: kotlin.Int? = null,
        /** Inclusive upper bound, or `null` for unbounded. */
        val max: kotlin.Int? = null,
        /** Exclusive lower bound, or `null` for unbounded. */
        val exclusiveMin: kotlin.Int? = null,
        /** Exclusive upper bound, or `null` for unbounded. */
        val exclusiveMax: kotlin.Int? = null,
        /** Value must be a multiple of this, or `null` for no constraint. */
        val multipleOf: kotlin.Int? = null,
    ) : Primitive

    /** Floating-point value, optionally bounded. Maps to `{"type":"number"}` plus `minimum`/`maximum`. */
    @Serializable
    @SerialName("Number")
    data class Num(
        /** Inclusive lower bound, or `null` for unbounded. */
        val min: Double? = null,
        /** Inclusive upper bound, or `null` for unbounded. */
        val max: Double? = null,
        /** Exclusive lower bound, or `null` for unbounded. */
        val exclusiveMin: Double? = null,
        /** Exclusive upper bound, or `null` for unbounded. */
        val exclusiveMax: Double? = null,
        /** Value must be a multiple of this, or `null` for no constraint. */
        val multipleOf: Double? = null,
    ) : Primitive

    /** String value, optionally constrained by length and pattern. */
    @Serializable
    @SerialName("String")
    data class Str(
        /** Minimum length, or `null` for unbounded. */
        val minLength: kotlin.Int? = null,
        /** Maximum length, or `null` for unbounded. */
        val maxLength: kotlin.Int? = null,
        /** Regex pattern the value must match, or `null` for no constraint. */
        val pattern: String? = null,
    ) : Primitive

    /** String value drawn from a fixed set. Maps to `{"type":"string","enum":[...]}`. */
    @Serializable
    @SerialName("Enum")
    data class Enum(
        /** Allowed values, in declaration order. */
        val values: List<String>,
    ) : Primitive
}

/** JSON Schema fragment describing the value this primitive validates. */
fun Primitive.toJsonSchema(): JsonObject = when (this) {
    Primitive.Bool -> buildJsonObject { put("type", "boolean") }

    is Primitive.Int -> buildJsonObject {
        put("type", "integer")
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        exclusiveMin?.let { put("exclusiveMinimum", it) }
        exclusiveMax?.let { put("exclusiveMaximum", it) }
        multipleOf?.let { put("multipleOf", it) }
    }

    is Primitive.Num -> buildJsonObject {
        put("type", "number")
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        exclusiveMin?.let { put("exclusiveMinimum", it) }
        exclusiveMax?.let { put("exclusiveMaximum", it) }
        multipleOf?.let { put("multipleOf", it) }
    }

    is Primitive.Str -> buildJsonObject {
        put("type", "string")
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        pattern?.let { put("pattern", it) }
    }

    is Primitive.Enum -> buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }
}

/**
 * JSON Schema (draft 2020-12) for a schema whose entries are [Primitive]s.
 * All entries are emitted as required; post-process the result if a different
 * required-set is needed.
 */
fun SchemaDef<Primitive>.toJsonSchema(): JsonObject = toJsonSchema { it.toJsonSchema() }

/**
 * JSON Schema (draft 2020-12) for an arbitrary config vocabulary. Supply a
 * mapper that turns each entry's config into a JSON Schema fragment for the
 * value it validates. For mixed hierarchies, call [Primitive.toJsonSchema]
 * inside the lambda for the primitive branches.
 */
fun <C : Any> SchemaDef<C>.toJsonSchema(map: (C) -> JsonObject): JsonObject = buildJsonObject {
    put("\$schema", "https://json-schema.org/draft/2020-12/schema")
    put("type", "object")
    putJsonObject("properties") {
        entries.forEach { (name, c) -> put(name, map(c)) }
    }
    put("additionalProperties", false)
    put("required", buildJsonArray { entries.keys.forEach { add(it) } })
}
