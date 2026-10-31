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

internal fun SerialDescriptor.nonNullableSerialName(): String =
    if (isNullable) serialName.removeSuffix("?") else serialName

internal fun SerialDescriptor.isInteger(): Boolean = when {
    isInline -> getElementDescriptor(0).let { !it.isNullable && it.isInteger() }
    else -> kind in setOf(PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG)
}

internal fun SerialDescriptor.isUnsigned(): Boolean =
    nonNullableSerialName() in setOf("kotlin.UByte", "kotlin.UShort", "kotlin.UInt", "kotlin.ULong") ||
        (isInline && getElementDescriptor(0).isUnsigned())

internal fun SerialDescriptor.isFloatingPoint(): Boolean = when {
    isInline -> getElementDescriptor(0).let { !it.isNullable && it.isFloatingPoint() }
    else -> kind == PrimitiveKind.FLOAT || kind == PrimitiveKind.DOUBLE
}

private val JSON_NUMBER = Regex("""(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?""")

/** Rewrites integral tokens like 2.0 or 1e2 as integers. */
internal fun JsonPrimitive.integral(): JsonPrimitive {
    val groups = JSON_NUMBER.matchEntire(content)?.groupValues ?: return this
    val (sign, whole, fraction) = groups.subList(1, 4)
    val digits = (whole + fraction).trimStart('0')
    if (digits.isEmpty()) return JsonPrimitive(0)
    val significant = digits.trimEnd('0')
    // Long so extreme exponents cannot overflow past the bounds check.
    val power = (groups[4].ifEmpty { "0" }.toLongOrNull() ?: return this) - fraction.length +
        (digits.length - significant.length)
    // Left for the decoder to reject.
    if (power < 0 || significant.length + power > MAX_INTEGER_DIGITS) return this
    return JsonUnquotedLiteral(sign + significant + "0".repeat(power.toInt()))
}

private const val MAX_INTEGER_DIGITS = 20
