@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.eignex.skema.serialization

import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.JsonPrimitive

// Bounds include the shortest decimal encodings of finite maxima on all targets. Compare
// decimal digits exactly so a token just outside the schema cannot round back inside it.
internal class JsonNumberBounds(val maximum: String) {
    private val groups = JSON_NUMBER.matchEntire(maximum)!!.groupValues
    private val digits = groups[2] + groups[3]
    private val order = groups[4].toLong() + groups[2].length

    fun contains(value: JsonPrimitive): Boolean {
        if (value.isString) return false
        val parts = JSON_NUMBER.matchEntire(value.content)?.groupValues ?: return false
        val significant = (parts[2] + parts[3]).trimStart('0')
        if (significant.isEmpty()) return true
        val exponent = parts[4].ifEmpty { "0" }.toLongOrNull()
            ?: return parts[4].startsWith('-')
        val limit = order - significant.length + parts[3].length
        if (exponent != limit) return exponent < limit
        val length = maxOf(significant.length, digits.length)
        return significant.padEnd(length, '0') <= digits.padEnd(length, '0')
    }
}

private val FLOAT_BOUNDS = JsonNumberBounds("3.4028235e38")
private val DOUBLE_BOUNDS = JsonNumberBounds("1.7976931348623157e308")

internal fun SerialDescriptor.numberBounds(): JsonNumberBounds? {
    if (isInline) return getElementDescriptor(0).numberBounds()
    return when (kind) {
        PrimitiveKind.FLOAT -> FLOAT_BOUNDS
        PrimitiveKind.DOUBLE -> DOUBLE_BOUNDS
        else -> null
    }
}
