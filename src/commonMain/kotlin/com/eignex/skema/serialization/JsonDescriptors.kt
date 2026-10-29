@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.*

internal fun SerialDescriptor.classDiscriminator(json: Json): String =
    annotations.filterIsInstance<JsonClassDiscriminator>().firstOrNull()?.discriminator
        ?: json.configuration.classDiscriminator

internal fun SerialDescriptor.isJsonFieldRequired(index: Int, json: Json): Boolean =
    !isElementOptional(index) && (json.configuration.explicitNulls || !getElementDescriptor(index).isNullable)

internal fun SerialDescriptor.nonNullableSerialName(): String =
    if (isNullable) serialName.removeSuffix("?") else serialName

internal fun SerialDescriptor.acceptsJsonNull(): Boolean = isNullable || when (nonNullableSerialName()) {
    JsonElement.serializer().descriptor.serialName,
    JsonPrimitive.serializer().descriptor.serialName,
    JsonNull.serializer().descriptor.serialName,
    -> true

    else -> isInline && getElementDescriptor(0).acceptsJsonNull()
}
