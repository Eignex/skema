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
 * and domain validation rules are not inferred.
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
        val name = descriptor.serialName.removeSuffix("?")
        if (name == JsonElement.serializer().descriptor.serialName) return buildJsonObject {}
        if (descriptor.isNullable && !nonNull) {
            return alternatives(listOf(schema(descriptor, owner, true), typed("null")))
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

            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schema(descriptor.getElementDescriptor(0), owner))
            }

            StructureKind.MAP -> buildJsonObject {
                val key = descriptor.getElementDescriptor(0)
                require(key.kind is PrimitiveKind || key.kind == SerialKind.ENUM) {
                    "Structured map keys require an explicit JSON Schema: ${descriptor.serialName}"
                }
                put("type", "object")
                put("additionalProperties", schema(descriptor.getElementDescriptor(1), owner))
                if (key.kind == SerialKind.ENUM) put("propertyNames", enumeration(key))
            }

            is PolymorphicKind -> reference(descriptor, owner) { polymorphic(descriptor, owner) }

            SerialKind.ENUM -> enumeration(descriptor)

            PrimitiveKind.BOOLEAN -> typed("boolean")

            PrimitiveKind.BYTE -> integer(Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong())

            PrimitiveKind.SHORT -> integer(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())

            PrimitiveKind.INT -> integer(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())

            PrimitiveKind.LONG -> integer(Long.MIN_VALUE, Long.MAX_VALUE)

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typed("number")

            PrimitiveKind.CHAR -> buildJsonObject {
                put("type", "string")
                put("minLength", 1)
                put("maxLength", 1)
            }

            PrimitiveKind.STRING -> typed("string")

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

    private fun record(descriptor: SerialDescriptor, owner: CompactSerializer<*>?): JsonObject = buildJsonObject {
        put("type", "object")
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
            val shorthand = if (node.mapping == null && node.shape == JsonNodeShape.Operand) {
                val index = descriptor.getElementIndex(node.operands.single())
                acceptsEmptyObject(descriptor.getElementDescriptor(index), serializer)
            } else {
                admitsEmptyObject(payload)
            }
            val named = operator(node.name, payload)
            if (shorthand) alternatives(listOf(named, constant(node.name))) else named
        }
        return alternatives(schemas)
    }

    private fun acceptsEmptyObject(descriptor: SerialDescriptor, owner: CompactSerializer<*>): Boolean {
        val name = descriptor.serialName.removeSuffix("?")
        if (name == JsonElement.serializer().descriptor.serialName) return true
        val compact = name == owner.serialName || owner.catalog.find(descriptor) != null ||
            catalog.find(descriptor) != null
        if (compact) return false
        if (descriptor.isInline) return acceptsEmptyObject(descriptor.getElementDescriptor(0), owner)
        return when (descriptor.kind) {
            StructureKind.MAP -> true

            StructureKind.CLASS, StructureKind.OBJECT -> (0 until descriptor.elementsCount).all { index ->
                val child = descriptor.getElementDescriptor(index)
                descriptor.isElementOptional(index) || (!json.configuration.explicitNulls && child.isNullable)
            }

            else -> false
        }
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
        val discriminator = descriptor.annotations.filterIsInstance<JsonClassDiscriminator>()
            .firstOrNull()?.discriminator ?: json.configuration.classDiscriminator
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

// Only inline object schemas qualify, so a payload behind a reference or extra keywords keeps the operator form.
private fun admitsEmptyObject(schema: JsonObject): Boolean {
    if (schema.keys == setOf("anyOf")) {
        return schema.getValue("anyOf").jsonArray.any { it is JsonObject && admitsEmptyObject(it) }
    }
    val type = schema["type"] ?: return schema.isEmpty()
    return type == JsonPrimitive("object") && objectKeywords.containsAll(schema.keys) &&
        schema["required"]?.jsonArray.isNullOrEmpty()
}

private val objectKeywords =
    setOf("type", "properties", "required", "additionalProperties", "propertyNames", "title", "description")

private fun typed(type: String): JsonObject = buildJsonObject { put("type", type) }

private fun integer(minimum: Long, maximum: Long): JsonObject = buildJsonObject {
    put("type", "integer")
    put("minimum", minimum)
    put("maximum", maximum)
}

private fun enumeration(descriptor: SerialDescriptor): JsonObject = buildJsonObject {
    put("type", "string")
    put("enum", JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) }))
}

private fun constant(value: String): JsonObject = buildJsonObject { put("const", value) }

private fun operator(name: String, payload: JsonObject): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { put(name, payload) })
    put("required", JsonArray(listOf(JsonPrimitive(name))))
    put("additionalProperties", false)
}

private fun tuple(items: List<JsonElement>): JsonObject = buildJsonObject {
    put("type", "array")
    if (items.isNotEmpty()) put("prefixItems", JsonArray(items))
    put("minItems", items.size)
    put("maxItems", items.size)
    put("items", false)
}

private fun alternatives(schemas: List<JsonObject>): JsonObject = when (schemas.size) {
    1 -> schemas.single()
    else -> buildJsonObject { put("anyOf", JsonArray(schemas)) }
}
