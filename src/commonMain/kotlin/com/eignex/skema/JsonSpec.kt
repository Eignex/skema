package com.eignex.skema

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Built-in JSON-Schema-shaped config vocabulary. Use as the type parameter of
 * [Schema] to get [toJsonSchema] without writing a mapper. For domain-specific
 * configs, define your own sealed type and use the [SchemaDef.toJsonSchema]
 * overload that takes a per-entry mapper lambda.
 */
@Serializable
sealed interface JsonSpec {
    /** Boolean value. Maps to `{"type":"boolean"}`. */
    @Serializable
    @SerialName("Bool")
    data object Bool : JsonSpec

    /** Null value. Maps to `{"type":"null"}`. Useful as a branch in [Nullable]-style composition. */
    @Serializable
    @SerialName("Null")
    data object Null : JsonSpec

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
    ) : JsonSpec

    /** 64-bit integer value, optionally bounded. Maps to `{"type":"integer"}` plus `minimum`/`maximum`. */
    @Serializable
    @SerialName("Long")
    data class Long(
        /** Inclusive lower bound, or `null` for unbounded. */
        val min: kotlin.Long? = null,
        /** Inclusive upper bound, or `null` for unbounded. */
        val max: kotlin.Long? = null,
        /** Exclusive lower bound, or `null` for unbounded. */
        val exclusiveMin: kotlin.Long? = null,
        /** Exclusive upper bound, or `null` for unbounded. */
        val exclusiveMax: kotlin.Long? = null,
        /** Value must be a multiple of this, or `null` for no constraint. */
        val multipleOf: kotlin.Long? = null,
    ) : JsonSpec

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
    ) : JsonSpec

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
        /**
         * JSON Schema `format` annotation (`date-time`, `email`, `uri`, `uuid`, ...), or `null`.
         * Free-form: JSON Schema treats unknown formats as annotations rather than errors.
         */
        val format: String? = null,
    ) : JsonSpec

    /** String value drawn from a fixed set. Maps to `{"type":"string","enum":[...]}`. */
    @Serializable
    @SerialName("Enum")
    data class Enum(
        /** Allowed values, in declaration order. */
        val values: List<String>,
    ) : JsonSpec

    /**
     * Object with named properties. [additionalPropertiesAllowed] gates extras; set
     * [additionalPropertiesSpec] to constrain extras to a schema instead of a boolean.
     * If both are set, the spec wins.
     */
    @Serializable
    @SerialName("Object")
    data class Object(
        /** Named properties and their specs. */
        val properties: Map<String, JsonSpec> = emptyMap(),
        /** Property names that must be present. */
        val required: List<String> = emptyList(),
        /** Whether properties not listed in [properties] are allowed. Default true (per JSON Schema). */
        val additionalPropertiesAllowed: Boolean? = null,
        /** Spec that all additional (unlisted) properties must match. */
        val additionalPropertiesSpec: JsonSpec? = null,
        /** Minimum number of properties. */
        val minProperties: kotlin.Int? = null,
        /** Maximum number of properties. */
        val maxProperties: kotlin.Int? = null,
    ) : JsonSpec

    /**
     * Array of values. [items] constrains every element; [prefixItems] constrains
     * the first N positionally (tuple-style); set both for a tuple with a trailing
     * homogeneous tail.
     */
    @Serializable
    @SerialName("Array")
    data class Array(
        /** Spec for every element, or `null` for unconstrained. */
        val items: JsonSpec? = null,
        /** Positional specs for leading elements. */
        val prefixItems: List<JsonSpec>? = null,
        /** Minimum array length. */
        val minItems: kotlin.Int? = null,
        /** Maximum array length. */
        val maxItems: kotlin.Int? = null,
        /** If true, elements must be unique. */
        val uniqueItems: Boolean? = null,
    ) : JsonSpec

    /** Value constrained to a single constant. Maps to `{"const": value}`. */
    @Serializable
    @SerialName("Const")
    data class Const(
        /** The required value, as a [JsonElement]. */
        val value: JsonElement,
    ) : JsonSpec

    /** Value must match exactly one of the listed specs. */
    @Serializable
    @SerialName("OneOf")
    data class OneOf(
        /** Branches; exactly one must match. */
        val branches: List<JsonSpec>,
    ) : JsonSpec

    /** Value must match at least one of the listed specs. */
    @Serializable
    @SerialName("AnyOf")
    data class AnyOf(
        /** Branches; at least one must match. */
        val branches: List<JsonSpec>,
    ) : JsonSpec

    /** Value must match all of the listed specs. */
    @Serializable
    @SerialName("AllOf")
    data class AllOf(
        /** Branches; all must match. */
        val branches: List<JsonSpec>,
    ) : JsonSpec

    /** Value must NOT match the given spec. */
    @Serializable
    @SerialName("Not")
    data class Not(
        /** The spec the value must fail. */
        val spec: JsonSpec,
    ) : JsonSpec

    /**
     * Wraps a [JsonSpec] so the value may also be null. Renders as
     * `{"anyOf":[<inner>, {"type":"null"}]}`. Equivalent to `OneOf(inner, Null)`
     * but spells the intent out.
     */
    @Serializable
    @SerialName("Nullable")
    data class Nullable(
        /** The non-null spec. */
        val inner: JsonSpec,
    ) : JsonSpec

    /**
     * Wraps another [JsonSpec] with JSON Schema annotations. Annotations decorate
     * the inner spec without changing the values it accepts.
     */
    @Serializable
    @SerialName("Annotated")
    data class Annotated(
        /** The spec being annotated. */
        val inner: JsonSpec,
        /** Short human-readable label. */
        val title: String? = null,
        /** Longer human-readable explanation. */
        val description: String? = null,
        /** Default value, used by generators and form-renderers. */
        val default: JsonElement? = null,
        /** Example values, used by documentation. */
        val examples: List<JsonElement>? = null,
        /** Marks the value as deprecated. */
        val deprecated: Boolean? = null,
        /** Marks the value as read-only (e.g., server-assigned). */
        val readOnly: Boolean? = null,
        /** Marks the value as write-only (e.g., passwords). */
        val writeOnly: Boolean? = null,
        /** Non-validating comment, ignored by validators. */
        val comment: String? = null,
    ) : JsonSpec
}

/** JSON Schema fragment describing the value this primitive validates. */
fun JsonSpec.toJsonSchema(): JsonObject = when (this) {
    JsonSpec.Bool -> buildJsonObject { put("type", "boolean") }

    JsonSpec.Null -> buildJsonObject { put("type", "null") }

    is JsonSpec.Int -> buildJsonObject {
        put("type", "integer")
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        exclusiveMin?.let { put("exclusiveMinimum", it) }
        exclusiveMax?.let { put("exclusiveMaximum", it) }
        multipleOf?.let { put("multipleOf", it) }
    }

    is JsonSpec.Long -> buildJsonObject {
        put("type", "integer")
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        exclusiveMin?.let { put("exclusiveMinimum", it) }
        exclusiveMax?.let { put("exclusiveMaximum", it) }
        multipleOf?.let { put("multipleOf", it) }
    }

    is JsonSpec.Num -> buildJsonObject {
        put("type", "number")
        min?.let { put("minimum", it) }
        max?.let { put("maximum", it) }
        exclusiveMin?.let { put("exclusiveMinimum", it) }
        exclusiveMax?.let { put("exclusiveMaximum", it) }
        multipleOf?.let { put("multipleOf", it) }
    }

    is JsonSpec.Str -> buildJsonObject {
        put("type", "string")
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        pattern?.let { put("pattern", it) }
        format?.let { put("format", it) }
    }

    is JsonSpec.Enum -> buildJsonObject {
        put("type", "string")
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }

    is JsonSpec.Object -> buildJsonObject {
        put("type", "object")
        if (properties.isNotEmpty()) {
            putJsonObject("properties") {
                properties.forEach { (n, s) -> put(n, s.toJsonSchema()) }
            }
        }
        if (required.isNotEmpty()) {
            put("required", buildJsonArray { required.forEach(::add) })
        }
        when {
            additionalPropertiesSpec != null -> put("additionalProperties", additionalPropertiesSpec.toJsonSchema())
            additionalPropertiesAllowed != null -> put("additionalProperties", additionalPropertiesAllowed)
        }
        minProperties?.let { put("minProperties", it) }
        maxProperties?.let { put("maxProperties", it) }
    }

    is JsonSpec.Array -> buildJsonObject {
        put("type", "array")
        items?.let { put("items", it.toJsonSchema()) }
        prefixItems?.let { pi ->
            put("prefixItems", buildJsonArray { pi.forEach { add(it.toJsonSchema()) } })
        }
        minItems?.let { put("minItems", it) }
        maxItems?.let { put("maxItems", it) }
        uniqueItems?.let { put("uniqueItems", it) }
    }

    is JsonSpec.Const -> buildJsonObject { put("const", value) }

    is JsonSpec.OneOf -> buildJsonObject {
        put("oneOf", buildJsonArray { branches.forEach { add(it.toJsonSchema()) } })
    }

    is JsonSpec.AnyOf -> buildJsonObject {
        put("anyOf", buildJsonArray { branches.forEach { add(it.toJsonSchema()) } })
    }

    is JsonSpec.AllOf -> buildJsonObject {
        put("allOf", buildJsonArray { branches.forEach { add(it.toJsonSchema()) } })
    }

    is JsonSpec.Not -> buildJsonObject { put("not", spec.toJsonSchema()) }

    is JsonSpec.Nullable -> buildJsonObject {
        put(
            "anyOf",
            buildJsonArray {
                add(inner.toJsonSchema())
                add(buildJsonObject { put("type", "null") })
            },
        )
    }

    is JsonSpec.Annotated -> buildJsonObject {
        inner.toJsonSchema().forEach { (k, v) -> put(k, v) }
        title?.let { put("title", it) }
        description?.let { put("description", it) }
        default?.let { put("default", it) }
        examples?.let { put("examples", buildJsonArray { it.forEach(::add) }) }
        deprecated?.let { put("deprecated", it) }
        readOnly?.let { put("readOnly", it) }
        writeOnly?.let { put("writeOnly", it) }
        comment?.let { put("\$comment", it) }
    }
}

/**
 * JSON Schema (draft 2020-12) for a schema whose entries are [JsonSpec]s.
 * All entries are emitted as required; post-process the result if a different
 * required-set is needed.
 */
fun SchemaDef<JsonSpec>.toJsonSchema(): JsonObject = toJsonSchema { it.toJsonSchema() }

/**
 * JSON Schema (draft 2020-12) for an arbitrary config vocabulary. Supply a
 * mapper that turns each entry's config into a JSON Schema fragment for the
 * value it validates. For mixed hierarchies, call [JsonSpec.toJsonSchema]
 * inside the lambda for the [JsonSpec] branches.
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
