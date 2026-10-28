@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaJson
import com.eignex.skema.typedSchema
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

/** JSON Schema draft 2020-12 for this vocabulary's compact JSON representation. */
fun CompactSerializer<*>.toJsonSchema(json: Json = SchemaJson): JsonObject =
    JsonSchemaGenerator(json, catalog, this).generate(descriptor)

/**
 * JSON Schema draft 2020-12 for a serialized document. [catalog] must list its compact
 * vocabularies, including those nested in records, collections and schema definitions.
 * Custom payload mappings supply [JsonPayloadMapping.payloadSchema]. Constructor invariants
 * and domain validation rules are not inferred. Contextual types require serializers registered
 * in the caller's module. Floating-point, nullable and structured map
 * keys require an explicit schema; string, enum, boolean, character and integer keys are supported.
 */
fun KSerializer<*>.toJsonSchema(catalog: JsonNodeCatalog, json: Json = SchemaJson): JsonObject =
    JsonSchemaGenerator(json, catalog, this as? CompactSerializer<*>).generate(descriptor)

private class JsonSchemaGenerator(
    private val json: Json,
    private val catalog: JsonNodeCatalog,
    private val root: CompactSerializer<*>?,
) {
    private data class Definition(val descriptor: SerialDescriptor, val owner: CompactSerializer<*>?)
    private data class Shorthand(val schema: JsonObject, val name: String, val payload: JsonObject)

    private val names = mutableMapOf<Definition, String>()
    private val definitions = linkedMapOf<String, JsonObject>()
    private val shorthands = mutableListOf<Shorthand>()

    fun generate(descriptor: SerialDescriptor): JsonObject {
        require(json.configuration.classDiscriminatorMode == ClassDiscriminatorMode.POLYMORPHIC) {
            "JSON Schema generation requires polymorphic class discriminators"
        }
        require(json.configuration.namingStrategy == null) { "JSON naming strategies require explicit schemas" }
        val schema = schema(descriptor, root)
        val document = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            schema.forEach { (name, value) -> put(name, value) }
            if (definitions.isNotEmpty()) put("\$defs", JsonObject(definitions))
        }
        return includeShorthands(document, document).jsonObject
    }

    private fun schema(
        descriptor: SerialDescriptor,
        owner: CompactSerializer<*>?,
        nonNull: Boolean = false,
    ): JsonObject {
        val name = descriptor.serialName.removeSuffix("?")
        if (name == JsonElement.serializer().descriptor.serialName) return buildJsonObject {}
        if (descriptor.isNullable && !nonNull) {
            return alternatives(listOf(schema(descriptor, owner, true), typedSchema("null")))
        }
        when (name) {
            JsonNull.serializer().descriptor.serialName -> return typedSchema("null")

            JsonPrimitive.serializer().descriptor.serialName -> return buildJsonObject {
                put("type", JsonArray(listOf("string", "number", "boolean", "null").map(::JsonPrimitive)))
            }
        }
        if (descriptor.kind == SerialKind.CONTEXTUAL) return schema(contextualDescriptor(descriptor), owner)
        descriptor.integerBounds()?.let { bounds ->
            return typedSchema("integer") {
                put("minimum", JsonUnquotedLiteral(bounds.minimum))
                put("maximum", JsonUnquotedLiteral(bounds.maximum))
            }
        }
        if (descriptor.isInline) return schema(descriptor.getElementDescriptor(0), owner)
        val compact = if (name == owner?.serialName) {
            owner
        } else {
            owner?.catalog?.find(descriptor) ?: catalog.find(descriptor)
        }
        if (compact != null) return reference(descriptor, compact) { compact(compact) }
        return when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> reference(descriptor, owner) { record(descriptor, owner) }

            StructureKind.LIST -> typedSchema("array") {
                put("items", schema(descriptor.getElementDescriptor(0), owner))
            }

            StructureKind.MAP -> typedSchema("object") {
                put("additionalProperties", schema(descriptor.getElementDescriptor(1), owner))
                val keySchema = mapKeySchema(descriptor.getElementDescriptor(0))
                if (keySchema.isNotEmpty()) put("propertyNames", keySchema)
            }

            is PolymorphicKind -> reference(descriptor, owner) { polymorphic(descriptor, owner) }

            SerialKind.ENUM -> enumeration(descriptor)

            PrimitiveKind.BOOLEAN -> typedSchema("boolean")

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typedSchema("number") {
                val maximum = descriptor.numberBounds()!!.maximum
                put("minimum", JsonUnquotedLiteral("-$maximum"))
                put("maximum", JsonUnquotedLiteral(maximum))
            }

            PrimitiveKind.CHAR -> typedSchema("string") {
                put("minLength", 1)
                put("maxLength", 1)
                put("pattern", CHAR_PATTERN)
            }

            PrimitiveKind.STRING -> typedSchema("string")

            else -> error("Cannot derive JSON Schema for '${descriptor.serialName}'")
        }
    }

    private fun reference(
        descriptor: SerialDescriptor,
        owner: CompactSerializer<*>?,
        build: () -> JsonObject,
    ): JsonObject {
        val key = Definition(descriptor.nullable, owner)
        val name = names[key] ?: run {
            val base = descriptor.serialName.removeSuffix("?").replace(Regex("[^A-Za-z0-9_.-]"), "_")
            var candidate = base
            var suffix = 2
            while (candidate in definitions) candidate = "${base}_${suffix++}"
            names[key] = candidate
            definitions[candidate] = buildJsonObject {}
            definitions[candidate] = build()
            candidate
        }
        return buildJsonObject { put("\$ref", "#/\$defs/$name") }
    }

    private fun record(descriptor: SerialDescriptor, owner: CompactSerializer<*>?): JsonObject = typedSchema("object") {
        val properties = buildJsonObject {
            for (index in 0 until descriptor.elementsCount) {
                put(descriptor.getElementName(index), schema(descriptor.getElementDescriptor(index), owner))
            }
        }
        put("properties", properties)
        val required = (0 until descriptor.elementsCount).filter { index ->
            val child = descriptor.getElementDescriptor(index)
            !descriptor.isElementOptional(index) && (json.configuration.explicitNulls || !child.isNullable)
        }
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(descriptor.getElementName(it)) }))
        }
        put("additionalProperties", false)
    }

    private fun compact(serializer: CompactSerializer<*>): JsonObject {
        require(serializer.nodes.isNotEmpty()) { "Cannot generate an empty vocabulary: ${serializer.serialName}" }
        val schemas = serializer.nodes.map { node ->
            val descriptor = node.serializer.descriptor
            val body = record(descriptor, serializer).withoutFields(node.fixed.keys)
            val properties = body.getValue("properties").jsonObject
            val payload = node.mapping?.payloadSchema(body) ?: when (node.shape) {
                JsonNodeShape.Fields -> body
                JsonNodeShape.Literal, JsonNodeShape.Operand -> properties.getValue(node.operands.single()).jsonObject
                JsonNodeShape.Operands -> tuple(node.operands.map { properties.getValue(it) })
            }
            if (node.shape == JsonNodeShape.Literal) return@map payload
            val named = operator(node.name, payload)
            shorthands += Shorthand(named, node.name, payload)
            named
        }
        return alternatives(schemas)
    }

    private fun mapKeySchema(descriptor: SerialDescriptor): JsonObject {
        require(
            !descriptor.acceptsJsonNull(),
        ) { "Nullable map keys require an explicit JSON Schema: ${descriptor.serialName}" }
        if (descriptor.kind == SerialKind.CONTEXTUAL) return mapKeySchema(contextualDescriptor(descriptor))
        descriptor.integerBounds()?.let { bounds ->
            return buildJsonObject { put("pattern", bounds.keyPattern()) }
        }
        if (descriptor.isInline) return mapKeySchema(descriptor.getElementDescriptor(0))
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> buildJsonObject {}

            SerialKind.ENUM -> enumeration(descriptor)

            PrimitiveKind.BOOLEAN -> buildJsonObject {
                put("enum", JsonArray(listOf(JsonPrimitive("true"), JsonPrimitive("false"))))
            }

            PrimitiveKind.CHAR -> buildJsonObject { put("pattern", CHAR_PATTERN) }

            else -> error("Map keys require an explicit JSON Schema: ${descriptor.serialName}")
        }
    }

    private fun contextualDescriptor(descriptor: SerialDescriptor): SerialDescriptor =
        requireNotNull(json.serializersModule.getContextualDescriptor(descriptor)) {
            "Contextual type requires a registered serializer or an explicit JSON Schema: ${descriptor.capturedKClass}"
        }

    // Defer this pass until recursive definitions are complete; their placeholders admit everything.
    private fun includeShorthands(element: JsonElement, document: JsonObject): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { includeShorthands(it, document) })

        is JsonObject -> {
            val rewritten = JsonObject(element.mapValues { (_, value) -> includeShorthands(value, document) })
            val shorthand = shorthands.firstOrNull { it.schema === element }
            if (shorthand != null && admitsEmptyObject(shorthand.payload, document)) {
                alternatives(listOf(rewritten, constant(shorthand.name)))
            } else {
                rewritten
            }
        }

        else -> element
    }

    private fun polymorphic(descriptor: SerialDescriptor, owner: CompactSerializer<*>?): JsonObject {
        val subtypes = if (descriptor.kind == PolymorphicKind.SEALED) {
            require(
                descriptor.elementsCount == 2,
            ) { "Custom polymorphic type requires an explicit schema: ${descriptor.serialName}" }
            val values = descriptor.getElementDescriptor(1)
            (0 until values.elementsCount).map { values.getElementDescriptor(it) }
        } else {
            json.serializersModule.getPolymorphicDescriptors(descriptor)
        }
        require(subtypes.isNotEmpty()) { "No declared subtypes for '${descriptor.serialName}'" }
        val discriminator = descriptor.classDiscriminator(json)
        val schemas = subtypes.map { subtype ->
            if (json.configuration.useArrayPolymorphism) {
                tuple(listOf(constant(subtype.serialName), schema(subtype, owner)))
            } else {
                require(subtype.kind == StructureKind.CLASS || subtype.kind == StructureKind.OBJECT) {
                    "Object polymorphism requires an object body: ${subtype.serialName}"
                }
                val body = record(subtype, owner)
                val properties = body.getValue("properties").jsonObject +
                    (discriminator to constant(subtype.serialName))
                val required = body["required"]?.jsonArray.orEmpty() + JsonPrimitive(discriminator)
                JsonObject(body + mapOf("properties" to JsonObject(properties), "required" to JsonArray(required)))
            }
        }
        return alternatives(schemas)
    }
}

private fun JsonObject.withoutFields(fields: Set<String>): JsonObject {
    if (fields.isEmpty()) return this
    val properties = getValue("properties").jsonObject - fields
    val required = this["required"]?.jsonArray.orEmpty().filter { it.jsonPrimitive.content !in fields }
    val rest = this - "properties" - "required"
    return buildJsonObject {
        rest.forEach { (name, value) -> put(name, value) }
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) put("required", JsonArray(required))
    }
}

// Evaluate only the empty object. Property, item and primitive constraints are vacuous here.
private fun admitsEmptyObject(
    schema: JsonElement,
    document: JsonObject,
    references: Set<String> = emptySet(),
): Boolean {
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
        val target = reference.removePrefix("#/").split('/').fold(document as JsonElement) { value, part ->
            val key = part.replace("~1", "/").replace("~0", "~")
            when (value) {
                is JsonObject -> value.getValue(key)
                is JsonArray -> value[key.toInt()]
                else -> error("Cannot resolve payload schema reference: $reference")
            }
        }
        if (!admitsEmptyObject(target, document, references + reference)) return false
    }
    fun accepts(value: JsonElement): Boolean = admitsEmptyObject(value, document, references)
    if (body["allOf"]?.jsonArray?.all(::accepts) == false) return false
    if (body["anyOf"]?.jsonArray?.any(::accepts) == false) return false
    if (body["oneOf"]?.jsonArray?.count(::accepts)?.let { it != 1 } == true) return false
    if (body["not"]?.let(::accepts) == true) return false
    body["if"]?.let { condition ->
        val branch = body[if (accepts(condition)) "then" else "else"]
        if (branch != null && !accepts(branch)) return false
    }
    return true
}

private const val CHAR_PATTERN = "^[\\u0000-\\uFFFF](?![\\s\\S])"

private fun enumeration(descriptor: SerialDescriptor): JsonObject = typedSchema("string") {
    put("enum", JsonArray(descriptor.elementNames.map(::JsonPrimitive)))
}

private fun constant(value: String): JsonObject = buildJsonObject { put("const", value) }

private fun operator(name: String, payload: JsonObject): JsonObject = typedSchema("object") {
    put("properties", buildJsonObject { put(name, payload) })
    put("required", JsonArray(listOf(JsonPrimitive(name))))
    put("additionalProperties", false)
}

private fun tuple(items: List<JsonElement>): JsonObject = typedSchema("array") {
    if (items.isNotEmpty()) put("prefixItems", JsonArray(items))
    put("minItems", items.size)
    put("maxItems", items.size)
    put("items", false)
}

private fun alternatives(schemas: List<JsonObject>): JsonObject = when (schemas.size) {
    1 -> schemas.single()
    else -> buildJsonObject { put("anyOf", JsonArray(schemas)) }
}
