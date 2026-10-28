package com.eignex.skema

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON Schema draft 2020-12 vocabulary for [Schema]. Renders schemas without
 * validating constraints or instance data. Variants containing [JsonElement]
 * require JSON serialization.
 */
@Serializable
sealed interface JsonSpec {
    /** Boolean value. */
    @Serializable
    @SerialName("Bool")
    data object Bool : JsonSpec

    /** Only the JSON null value. */
    @Serializable
    @SerialName("Null")
    data object Null : JsonSpec

    /** JSON Schema integer; no implicit 32-bit bounds. */
    @Serializable
    @SerialName("Int")
    data class Int(
        /** Inclusive lower bound. */
        val min: kotlin.Int? = null,
        /** Inclusive upper bound. */
        val max: kotlin.Int? = null,
        /** Exclusive lower bound. */
        val exclusiveMin: kotlin.Int? = null,
        /** Exclusive upper bound. */
        val exclusiveMax: kotlin.Int? = null,
        /** Required divisor; must be positive when supplied. */
        val multipleOf: kotlin.Int? = null,
    ) : JsonSpec

    /** JSON Schema integer; no implicit 64-bit bounds. */
    @Serializable
    @SerialName("Long")
    data class Long(
        /** Inclusive lower bound. */
        val min: kotlin.Long? = null,
        /** Inclusive upper bound. */
        val max: kotlin.Long? = null,
        /** Exclusive lower bound. */
        val exclusiveMin: kotlin.Long? = null,
        /** Exclusive upper bound. */
        val exclusiveMax: kotlin.Long? = null,
        /** Required divisor; must be positive when supplied. */
        val multipleOf: kotlin.Long? = null,
    ) : JsonSpec

    /** JSON Schema number. */
    @Serializable
    @SerialName("Number")
    data class Num(
        /** Inclusive lower bound. */
        val min: Double? = null,
        /** Inclusive upper bound. */
        val max: Double? = null,
        /** Exclusive lower bound. */
        val exclusiveMin: Double? = null,
        /** Exclusive upper bound. */
        val exclusiveMax: Double? = null,
        /** Required divisor; must be positive when supplied. */
        val multipleOf: Double? = null,
    ) : JsonSpec

    /** String constraints. */
    @Serializable
    @SerialName("String")
    data class Str(
        /** Minimum string length. */
        val minLength: kotlin.Int? = null,
        /** Maximum string length. */
        val maxLength: kotlin.Int? = null,
        /** Regular expression constraint. */
        val pattern: String? = null,
        /** JSON Schema format annotation, such as email or date-time. */
        val format: String? = null,
    ) : JsonSpec

    /** String restricted to [values]. */
    @Serializable
    @SerialName("Enum")
    data class Enum(
        /** Allowed string values. */
        val values: List<String>,
    ) : JsonSpec

    /** [additionalPropertiesSpec] takes precedence over [additionalPropertiesAllowed]. */
    @Serializable
    @SerialName("Object")
    data class Object(
        /** Schemas for named properties. */
        val properties: Map<String, JsonSpec> = emptyMap(),
        /** Names of properties that must be present. */
        val required: List<String> = emptyList(),
        /** Unlisted properties are allowed when null or true. */
        val additionalPropertiesAllowed: Boolean? = null,
        /** Schema for unlisted properties. */
        val additionalPropertiesSpec: JsonSpec? = null,
        /** Minimum property count. */
        val minProperties: kotlin.Int? = null,
        /** Maximum property count. */
        val maxProperties: kotlin.Int? = null,
    ) : JsonSpec

    /** Array with optional positional and shared element constraints. */
    @Serializable
    @SerialName("Array")
    data class Array(
        /** Applies after [prefixItems], or to all elements without a prefix. Null leaves them unconstrained. */
        val items: JsonSpec? = null,
        /** Positional schemas for leading elements; must be non-empty when supplied. */
        val prefixItems: List<JsonSpec>? = null,
        /** Minimum array length. */
        val minItems: kotlin.Int? = null,
        /** Maximum array length. */
        val maxItems: kotlin.Int? = null,
        /** Requires distinct elements when true. */
        val uniqueItems: Boolean? = null,
    ) : JsonSpec

    /** Matches a single JSON value. */
    @Serializable
    @SerialName("Const")
    data class Const(
        /** The required JSON value. */
        val value: JsonElement,
    ) : JsonSpec

    /** JSON Schema reference, such as #/$defs/User or an absolute URI. */
    @Serializable
    @SerialName("Ref")
    data class Ref(
        /** JSON pointer or URI to the referenced schema. */
        val pointer: String,
    ) : JsonSpec

    /** Exactly one branch must match. */
    @Serializable
    @SerialName("OneOf")
    data class OneOf(
        /** Non-empty list of schemas. */
        val branches: List<JsonSpec>,
    ) : JsonSpec

    /** At least one branch must match. */
    @Serializable
    @SerialName("AnyOf")
    data class AnyOf(
        /** Non-empty list of schemas. */
        val branches: List<JsonSpec>,
    ) : JsonSpec

    /** All branches must match. */
    @Serializable
    @SerialName("AllOf")
    data class AllOf(
        /** Non-empty list of schemas. */
        val branches: List<JsonSpec>,
    ) : JsonSpec

    /** The value must fail [spec]. */
    @Serializable
    @SerialName("Not")
    data class Not(val spec: JsonSpec) : JsonSpec

    /** Applies [then] when [condition] matches, or [otherwise] when it does not. Either branch may be omitted. */
    @Serializable
    @SerialName("IfThenElse")
    data class IfThenElse(val condition: JsonSpec, val then: JsonSpec? = null, val otherwise: JsonSpec? = null) :
        JsonSpec

    /** Allows null as well as [inner], without making an object property optional. */
    @Serializable
    @SerialName("Nullable")
    data class Nullable(val inner: JsonSpec) : JsonSpec

    /** Adds annotations without changing which values [inner] accepts. */
    @Serializable
    @SerialName("Annotated")
    data class Annotated(
        val inner: JsonSpec,
        /** Display label. */
        val title: String? = null,
        /** Description for readers. */
        val description: String? = null,
        /** Kotlin null omits the annotation; [JsonNull] preserves an explicit JSON null. */
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = JsonAnnotationDefaultSerializer::class)
        val default: JsonElement? = null,
        /** Example values. */
        val examples: List<JsonElement>? = null,
        /** Deprecation annotation. */
        val deprecated: Boolean? = null,
        /** Marks server-managed values. */
        val readOnly: Boolean? = null,
        /** Marks values intended only for input. */
        val writeOnly: Boolean? = null,
        /** Comment for schema readers, ignored by validators. */
        val comment: String? = null,
    ) : JsonSpec
}

// Preserve explicit JsonNull defaults, which the standard nullable serializer collapses to Kotlin null.
private object JsonAnnotationDefaultSerializer : KSerializer<JsonElement?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable

    override fun serialize(encoder: Encoder, value: JsonElement?) {
        encoder.encodeSerializableValue(JsonElement.serializer(), value ?: JsonNull)
    }

    override fun deserialize(decoder: Decoder): JsonElement = decoder.decodeSerializableValue(JsonElement.serializer())
}

/** Renders this spec as a JSON Schema fragment. */
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

    is JsonSpec.Ref -> buildJsonObject { put("\$ref", pointer) }

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

    is JsonSpec.IfThenElse -> buildJsonObject {
        put("if", condition.toJsonSchema())
        then?.let { put("then", it.toJsonSchema()) }
        otherwise?.let { put("else", it.toJsonSchema()) }
    }

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

/** Exports all entries as required properties, forbids extras, and places [defs] in $defs. */
fun SchemaDef<JsonSpec>.toJsonSchema(defs: Map<String, JsonSpec> = emptyMap()): JsonObject =
    toJsonSchema(defs = defs) { it.toJsonSchema() }

/**
 * Maps each config to a field-value schema. All entries are required, extras are
 * forbidden, and [defs] populates $defs.
 */
fun <C : Any> SchemaDef<C>.toJsonSchema(defs: Map<String, JsonSpec> = emptyMap(), map: (C) -> JsonObject): JsonObject =
    buildJsonObject {
        put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        put("type", "object")
        putJsonObject("properties") {
            entries.forEach { (name, c) -> put(name, map(c)) }
        }
        put("additionalProperties", false)
        put("required", buildJsonArray { entries.keys.forEach { add(it) } })
        if (defs.isNotEmpty()) {
            putJsonObject("\$defs") {
                defs.forEach { (n, s) -> put(n, s.toJsonSchema()) }
            }
        }
    }
