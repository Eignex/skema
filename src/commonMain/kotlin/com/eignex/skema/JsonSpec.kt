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
import kotlinx.serialization.json.JsonObjectBuilder
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
        val min: kotlin.Int? = null,
        val max: kotlin.Int? = null,
        val exclusiveMin: kotlin.Int? = null,
        val exclusiveMax: kotlin.Int? = null,
        /** Required divisor; must be positive when supplied. */
        val multipleOf: kotlin.Int? = null,
    ) : JsonSpec

    /** JSON Schema integer; no implicit 64-bit bounds. */
    @Serializable
    @SerialName("Long")
    data class Long(
        val min: kotlin.Long? = null,
        val max: kotlin.Long? = null,
        val exclusiveMin: kotlin.Long? = null,
        val exclusiveMax: kotlin.Long? = null,
        /** Required divisor; must be positive when supplied. */
        val multipleOf: kotlin.Long? = null,
    ) : JsonSpec

    /** JSON Schema number. */
    @Serializable
    @SerialName("Number")
    data class Num(
        val min: Double? = null,
        val max: Double? = null,
        val exclusiveMin: Double? = null,
        val exclusiveMax: Double? = null,
        /** Required divisor; must be positive when supplied. */
        val multipleOf: Double? = null,
    ) : JsonSpec

    /** String constraints. */
    @Serializable
    @SerialName("String")
    data class Str(
        val minLength: kotlin.Int? = null,
        val maxLength: kotlin.Int? = null,
        val pattern: String? = null,
        /** JSON Schema format annotation, such as email or date-time. */
        val format: String? = null,
    ) : JsonSpec

    /** String restricted to [values]. */
    @Serializable
    @SerialName("Enum")
    data class Enum(val values: List<String>) : JsonSpec

    /** [additionalPropertiesSpec] takes precedence over [additionalPropertiesAllowed]. */
    @Serializable
    @SerialName("Object")
    data class Object(
        val properties: Map<String, JsonSpec> = emptyMap(),
        val required: List<String> = emptyList(),
        /** Unlisted properties are allowed when null or true. */
        val additionalPropertiesAllowed: Boolean? = null,
        val additionalPropertiesSpec: JsonSpec? = null,
        val minProperties: kotlin.Int? = null,
        val maxProperties: kotlin.Int? = null,
    ) : JsonSpec

    /** Positional prefix schemas followed by shared [items] constraints. */
    @Serializable
    @SerialName("Array")
    data class Array(
        /** Applies after [prefixItems], or to all elements without a prefix. Null leaves them unconstrained. */
        val items: JsonSpec? = null,
        /** Positional schemas for leading elements; must be non-empty when supplied. */
        val prefixItems: List<JsonSpec>? = null,
        val minItems: kotlin.Int? = null,
        val maxItems: kotlin.Int? = null,
        val uniqueItems: Boolean? = null,
    ) : JsonSpec

    /** Matches a single JSON value. */
    @Serializable
    @SerialName("Const")
    data class Const(val value: JsonElement) : JsonSpec

    /** JSON Schema reference, such as #/$defs/User or an absolute URI. */
    @Serializable
    @SerialName("Ref")
    data class Ref(val pointer: String) : JsonSpec

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
        val title: String? = null,
        val description: String? = null,
        /** Kotlin null omits the annotation; [JsonNull] preserves an explicit JSON null. */
        @OptIn(ExperimentalSerializationApi::class)
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        @Serializable(with = JsonAnnotationDefaultSerializer::class)
        val default: JsonElement? = null,
        val examples: List<JsonElement>? = null,
        val deprecated: Boolean? = null,
        val readOnly: Boolean? = null,
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
    JsonSpec.Bool -> typedSchema("boolean")

    JsonSpec.Null -> typedSchema("null")

    is JsonSpec.Int -> numericSchema("integer", min, max, exclusiveMin, exclusiveMax, multipleOf)

    is JsonSpec.Long -> numericSchema("integer", min, max, exclusiveMin, exclusiveMax, multipleOf)

    is JsonSpec.Num -> numericSchema("number", min, max, exclusiveMin, exclusiveMax, multipleOf)

    is JsonSpec.Str -> typedSchema("string") {
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        pattern?.let { put("pattern", it) }
        format?.let { put("format", it) }
    }

    is JsonSpec.Enum -> typedSchema("string") {
        put("enum", buildJsonArray { values.forEach { add(it) } })
    }

    is JsonSpec.Object -> typedSchema("object") {
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

    is JsonSpec.Array -> typedSchema("array") {
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

    is JsonSpec.OneOf -> compositionSchema("oneOf", branches)

    is JsonSpec.AnyOf -> compositionSchema("anyOf", branches)

    is JsonSpec.AllOf -> compositionSchema("allOf", branches)

    is JsonSpec.Not -> buildJsonObject { put("not", spec.toJsonSchema()) }

    is JsonSpec.IfThenElse -> buildJsonObject {
        put("if", condition.toJsonSchema())
        then?.let { put("then", it.toJsonSchema()) }
        otherwise?.let { put("else", it.toJsonSchema()) }
    }

    is JsonSpec.Nullable -> compositionSchema("anyOf", listOf(inner, JsonSpec.Null))

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

internal fun typedSchema(type: String, constraints: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("type", type)
    constraints()
}

private fun numericSchema(
    type: String,
    min: Number?,
    max: Number?,
    exclusiveMin: Number?,
    exclusiveMax: Number?,
    multipleOf: Number?,
): JsonObject = typedSchema(type) {
    min?.let { put("minimum", it) }
    max?.let { put("maximum", it) }
    exclusiveMin?.let { put("exclusiveMinimum", it) }
    exclusiveMax?.let { put("exclusiveMaximum", it) }
    multipleOf?.let { put("multipleOf", it) }
}

private fun compositionSchema(keyword: String, branches: List<JsonSpec>): JsonObject = buildJsonObject {
    put(keyword, buildJsonArray { branches.forEach { add(it.toJsonSchema()) } })
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
