package com.eignex.skema.serialization

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Validate the schema examples emitted by JsonWireConformanceTest against draft 2020-12. */
fun main(args: Array<String>) {
    val reportDir = File(args.firstOrNull() ?: "build/test-results/jvmTest")
    // Keep integral decimal/exponent tokens and floating-point bounds exact, including ULong.MAX_VALUE.
    val mapper = ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
    val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    val metaSchema = factory.getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"))
    val parser = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder()
    var groups = 0
    var examples = 0
    val failures = mutableListOf<String>()
    val reports = reportDir.listFiles { file -> file.name.startsWith("TEST-") && file.extension == "xml" }
        .orEmpty().sortedBy { it.name }
    for (report in reports) {
        val outputs = parser.parse(report).getElementsByTagName("system-out")
        for (index in 0 until outputs.length) {
            for (line in outputs.item(index).textContent.lineSequence()) {
                if (!line.startsWith("JSON_SCHEMA_CASE:")) continue
                val case = mapper.readTree(line.removePrefix("JSON_SCHEMA_CASE:"))
                val name = case.required("name").asText()
                val schemaNode = case.required("schema")
                val schemaErrors = metaSchema.validate(schemaNode)
                check(schemaErrors.isEmpty()) { "$name: invalid schema: ${schemaErrors.joinToString("; ")}" }
                val schema = factory.getSchema(schemaNode)
                groups++
                for ((category, expected) in listOf("valid" to true, "invalid" to false)) {
                    for (instance in case.required(category)) {
                        examples++
                        val errors = schema.validate(instance)
                        if (errors.isEmpty() != expected) {
                            failures += "$name: expected $category: $instance; ${errors.joinToString("; ")}"
                        }
                    }
                }
            }
        }
    }
    check(groups > 0) { "No JSON Schema examples found in $reportDir; run ./gradlew jvmTest first" }
    check(failures.isEmpty()) { failures.joinToString("\n") }
    println("Validated $examples JSON Schema examples in $groups groups (draft 2020-12)")
}
