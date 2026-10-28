package com.eignex.skema.serialization

import kotlinx.serialization.SerializationException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun assertSerializationFails(
    prefix: String,
    message: String? = null,
    block: () -> Unit,
): SerializationException {
    val error = assertFailsWith<SerializationException>(message, block)
    assertTrue(error.message.orEmpty().startsWith(prefix), error.message)
    return error
}
