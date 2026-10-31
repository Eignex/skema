package com.eignex.skema.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import kotlin.test.Test
import kotlin.test.assertTrue

class JsonSchemaValidationTest {
    // Exact numbers, so Long boundaries are not rounded through binary64.
    private val mapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    @Test
    fun `generated schemas are valid draft 2020-12 and agree with the encoder`() {
        val metaSchema = factory.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"))
        for (example in schemaExamples()) {
            val schema = mapper.readTree(example.schema.toString())
            assertTrue(metaSchema.validate(schema).isEmpty(), "${example.name}: invalid schema")
            val validator = factory.getSchema(schema)
            val instances = example.valid.map { it.toString() to true } + example.invalid.map { it to false }
            for ((instance, valid) in instances) {
                val errors = validator.validate(mapper.readTree(instance))
                assertTrue(errors.isEmpty() == valid, "${example.name}: $instance; $errors")
            }
        }
    }
}
