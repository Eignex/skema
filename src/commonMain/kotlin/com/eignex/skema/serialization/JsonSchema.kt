@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import com.eignex.skema.SchemaJson
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
 * are not inferred. Map keys must be strings, enums, booleans, characters or integers.
 */
fun KSerializer<*>.toJsonSchema(catalog: JsonNodeCatalog, json: Json = SchemaJson): JsonObject =
    JsonSchemaGenerator(json, catalog, this as? CompactSerializer<*>).generate(descriptor)

private class JsonSchemaGenerator(
    private val json: Json,
    private val catalog: JsonNodeCatalog,
    private val root: CompactSerializer<*>?,
) {
    private data class Definition(val descriptor: SerialDescriptor, val owner: CompactSerializer<*>?)

    private val names = mutableMapOf<Definition, String>()
    private val definitions = linkedMapOf<String, JsonObject>()

    fun generate(descriptor: SerialDescriptor): JsonObject {
        require(json.configuration.classDiscriminatorMode == ClassDiscriminatorMode.POLYMORPHIC) {
            "JSON Schema generation requires polymorphic class discriminators"
        }
        require(json.configuration.namingStrategy == null) { "JSON naming strategies require explicit schemas" }
        val schema = schema(descriptor, root)
        return buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            schema.forEach { (name, value) -> put(name, value) }
            if (definitions.isNotEmpty()) put("\$defs", JsonObject(definitions))
        }
    }

    private fun schema(
        descriptor: SerialDescriptor,
        owner: CompactSerializer<*>?,
        nonNull: Boolean = false,
    ): JsonObject {
        val name = descriptor.nonNullableSerialName()
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
        if (descriptor.isInteger()) return typedSchema("integer") { if (descriptor.isUnsigned()) put("minimum", 0) }
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

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typedSchema("number")

            PrimitiveKind.CHAR -> typedSchema("string") { singleCharacter() }

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
            val base = descriptor.nonNullableSerialName().replace(Regex("[^A-Za-z0-9_.-]"), "_")
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
            descriptor.isJsonFieldRequired(index, json)
        }
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(descriptor.getElementName(it)) }))
        }
        put("additionalProperties", false)
    }

    private fun compact(serializer: CompactSerializer<*>): JsonObject {
        require(serializer.nodes.isNotEmpty()) { "Cannot generate an empty vocabulary: ${serializer.serialName}" }
        val schemas = serializer.nodes.map { node ->
            val body = record(node.serializer.descriptor, serializer).withoutFields(node.fixed.keys)
            val payload = node.payloadMapping.payloadSchema(body)
            val operator = typedSchema("object") {
                put("properties", buildJsonObject { put(node.name, payload) })
                put("required", JsonArray(listOf(JsonPrimitive(node.name))))
                put("additionalProperties", false)
            }
            when {
                node.shape == JsonNodeShape.Literal -> payload

                // Encoding writes an empty payload object as the bare operator name.
                node.shape == JsonNodeShape.Fields && payload["type"] == JsonPrimitive("object") &&
                    "required" !in payload -> alternatives(listOf(operator, constant(node.name)))

                else -> operator
            }
        }
        return alternatives(schemas)
    }

    private fun mapKeySchema(descriptor: SerialDescriptor): JsonObject {
        if (descriptor.kind == SerialKind.CONTEXTUAL) return mapKeySchema(contextualDescriptor(descriptor))
        if (descriptor.isInteger()) {
            return buildJsonObject { put("pattern", if (descriptor.isUnsigned()) "^[0-9]+$" else "^-?[0-9]+$") }
        }
        if (descriptor.isInline) return mapKeySchema(descriptor.getElementDescriptor(0))
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> buildJsonObject {}

            SerialKind.ENUM -> enumeration(descriptor)

            PrimitiveKind.BOOLEAN -> buildJsonObject {
                put("enum", JsonArray(listOf(JsonPrimitive("true"), JsonPrimitive("false"))))
            }

            PrimitiveKind.CHAR -> buildJsonObject { singleCharacter() }

            else -> error("Map keys require an explicit JSON Schema: ${descriptor.serialName}")
        }
    }

    private fun contextualDescriptor(descriptor: SerialDescriptor): SerialDescriptor =
        requireNotNull(json.serializersModule.getContextualDescriptor(descriptor)) {
            "Contextual type requires a registered serializer: ${descriptor.capturedKClass}"
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
                tupleSchema(listOf(constant(subtype.serialName), schema(subtype, owner)))
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

private fun JsonObjectBuilder.singleCharacter() {
    put("minLength", 1)
    put("maxLength", 1)
}

private fun enumeration(descriptor: SerialDescriptor): JsonObject = typedSchema("string") {
    put("enum", JsonArray(descriptor.elementNames.map(::JsonPrimitive)))
}

private fun constant(value: String): JsonObject = buildJsonObject { put("const", value) }

internal fun tupleSchema(items: List<JsonElement>): JsonObject = typedSchema("array") {
    if (items.isNotEmpty()) put("prefixItems", JsonArray(items))
    put("minItems", items.size)
    put("maxItems", items.size)
    put("items", false)
}

private fun alternatives(schemas: List<JsonObject>): JsonObject = when (schemas.size) {
    1 -> schemas.single()
    else -> buildJsonObject { put("anyOf", JsonArray(schemas)) }
}

internal fun typedSchema(type: String, constraints: JsonObjectBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("type", type)
    constraints()
}
