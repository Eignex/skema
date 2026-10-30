@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.*

internal fun SerialDescriptor.classDiscriminator(json: Json): String =
    annotations.filterIsInstance<JsonClassDiscriminator>().firstOrNull()?.discriminator
        ?: json.configuration.classDiscriminator

internal fun SerialDescriptor.isJsonFieldRequired(index: Int, json: Json): Boolean =
    !isElementOptional(index) && (json.configuration.explicitNulls || !getElementDescriptor(index).isNullable)

internal fun SerialDescriptor.isFloatingPoint(): Boolean = when {
    isInline -> getElementDescriptor(0).isFloatingPoint()
    else -> kind == PrimitiveKind.FLOAT || kind == PrimitiveKind.DOUBLE
}

internal fun SerialDescriptor.nonNullableSerialName(): String =
    if (isNullable) serialName.removeSuffix("?") else serialName

/** JSON tree types, which decode any value of their kind, including null. */
internal val jsonTreeSerialNames: Set<String> = setOf(
    JsonElement.serializer().descriptor.serialName,
    JsonPrimitive.serializer().descriptor.serialName,
    JsonNull.serializer().descriptor.serialName,
)

internal fun SerialDescriptor.acceptsJsonNull(): Boolean = isNullable ||
    nonNullableSerialName() in jsonTreeSerialNames ||
    (isInline && getElementDescriptor(0).acceptsJsonNull())
