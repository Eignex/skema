package com.eignex.skema.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

internal class JsonShape(private val owner: CompactSerializer<*>, private val catalog: JsonNodeCatalog) {
    fun validateJson(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        if (element == JsonNull) {
            if (!descriptor.isNullable) invalid(path, "null is not allowed")
            return
        }
        if (descriptor.isInline) {
            validateJson(descriptor.getElementDescriptor(0), element, path)
            return
        }
        val name = descriptor.serialName.removeSuffix("?")
        val serializer = if (name == owner.serialName) owner else catalog.find(descriptor)
        if (serializer != null) {
            if (serializer !== owner) {
                serializer.validate(element, path)
                return
            }
            val (node, payload) = serializer.select(element, path)
            val nodePath = node.pathAt(path)
            val body = node.decode(payload, nodePath)
            validateRecord(node.serializer.descriptor, body, nodePath) { field -> node.fieldPath(field, nodePath) }
            return
        }
        when (descriptor.kind) {
            StructureKind.CLASS, StructureKind.OBJECT -> validateRecord(descriptor, element.objectAt(path), path)

            StructureKind.LIST -> {
                val array = element as? JsonArray ?: invalid(path, "expected an array")
                val child = descriptor.getElementDescriptor(0)
                array.forEachIndexed { index, value -> validateJson(child, value, "$path[$index]") }
            }

            StructureKind.MAP -> element.objectAt(path).forEach { (key, value) ->
                validateJson(descriptor.getElementDescriptor(1), value, "$path.$key")
            }

            SerialKind.ENUM -> {
                val literal = element as? JsonPrimitive
                val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
                if (literal?.isString != true || literal.content !in names) invalid(path, "expected one of $names")
            }

            else -> validatePrimitive(descriptor, element, path)
        }
    }

    private fun validateRecord(
        descriptor: SerialDescriptor,
        body: JsonObject,
        path: String,
        fieldPath: (String) -> String = { "$path.$it" },
    ) {
        val fields = (0 until descriptor.elementsCount).associateBy { descriptor.getElementName(it) }
        body.keys.firstOrNull { it !in fields }?.let { invalid("$path.$it", "unknown field '$it'") }
        for ((name, index) in fields) {
            val value = body[name]
            val child = descriptor.getElementDescriptor(index)
            val required = !descriptor.isElementOptional(index) && !child.isNullable
            if (value == null) {
                if (required) invalid(fieldPath(name), "missing operand '$name'")
            } else {
                validateJson(child, value, fieldPath(name))
            }
        }
    }

    private fun validatePrimitive(descriptor: SerialDescriptor, element: JsonElement, path: String) {
        val primitive = element as? JsonPrimitive ?: invalid(path, "expected ${descriptor.kind}")
        val valid = when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> !primitive.isString && primitive.booleanOrNull != null

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                !primitive.isString && primitive.longOrNull != null

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> !primitive.isString && primitive.doubleOrNull != null

            PrimitiveKind.CHAR, PrimitiveKind.STRING -> primitive.isString

            else -> true
        }
        if (!valid) invalid(path, "expected ${descriptor.kind}")
    }
}

internal fun JsonElement.objectAt(path: String): JsonObject = this as? JsonObject ?: invalid(path, "expected an object")

internal fun invalid(path: String, message: String): Nothing = throw SerializationException("$path: $message")
