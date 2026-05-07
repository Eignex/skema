package com.eignex.skema.consumer

import com.eignex.skema.Schema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private sealed interface Field

@Serializable
@SerialName("Bool")
private data object BoolField : Field

private data class BoolKey(val name: String)

private abstract class FormSchema : Schema<Field>() {
    protected fun bool() = register(BoolField, ::BoolKey)
}

private object DemoFormSchema : FormSchema() {
    val accept by bool()
}

/**
 * Must live outside `com.eignex.skema`: that's what makes [Schema.register]'s
 * inlined body land in a synthetic class outside Schema's package, exercising
 * the JVM-protected access path that same-package tests would mask.
 */
class CrossPackageRegisterTest {

    @Test
    fun `register works when inlined into a subclass declared in a different package`() {
        assertEquals(BoolKey("accept"), DemoFormSchema.accept)
        assertEquals(setOf("accept"), DemoFormSchema.entries.keys)
    }
}
