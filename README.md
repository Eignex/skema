<p align="center">
  <a href="https://eignex.com/">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner-white.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg">
      <img alt="Eignex" src="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg" style="max-width: 100%; width: 22em;">
    </picture>
  </a>
</p>

# skema

[![Maven Central](https://img.shields.io/maven-central/v/com.eignex/skema.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.eignex/skema)
[![Build](https://github.com/eignex/skema/actions/workflows/build.yml/badge.svg)](https://github.com/eignex/skema/actions/workflows/build.yml)
[![codecov](https://codecov.io/gh/eignex/skema/branch/main/graph/badge.svg)](https://codecov.io/gh/eignex/skema)
[![License](https://img.shields.io/github/license/eignex/skema)](https://github.com/eignex/skema/blob/main/LICENSE)

A Kotlin Multiplatform library for declaring typed schemas and sharing them as
serializable data. Used by [kumulant](https://github.com/Eignex/kumulant),
[klause](https://github.com/Eignex/klause), and [combo](https://github.com/Eignex/combo).

## Installation

Add to your JVM dependencies or Multiplatform commonMain dependencies:

```kotlin
implementation("com.eignex:skema:0.3.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
```

skema uses compile-only serialization dependencies, so your application supplies the
runtime. Apply the Kotlin serialization plugin at the same version as your Kotlin
plugin.

## Usage

Define the configuration types your schema accepts, then register entries with
helpers that return typed keys:

```kotlin
import com.eignex.skema.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable sealed interface FormField
@Serializable @SerialName("Bool") data object BoolField : FormField
@Serializable @SerialName("Int") data class IntField(val min: Int, val max: Int) : FormField

data class BoolKey(val name: String)
data class IntKey(val name: String, val min: Int, val max: Int)

abstract class FormSchema : Schema<FormField>() {
    protected fun bool(name: String): BoolKey {
        add(name, BoolField)
        return BoolKey(name)
    }

    protected fun int(min: Int, max: Int) =
        register(IntField(min, max)) { IntKey(it, min, max) }
}

object SignupFormSchema : FormSchema() {
    val acceptsTos = bool("acceptsTos")
    val age by int(13, 120)
}
```

Use add for an explicit name or register to take the property name. Duplicate names
throw. Calling definition() validates the entries and takes a snapshot for serialization:

```kotlin
val serializer = SchemaDef.serializer(FormField.serializer())
val wire = SchemaJson.encodeToString(serializer, SignupFormSchema.definition())
val definition = SchemaJson.decodeFromString(serializer, wire)
```

The JSON contains the entry names and configuration:

```json
{"entries":{"acceptsTos":{"$type":"Bool"},"age":{"$type":"Int","min":13,"max":120}}}
```

Consumers share the configuration types and read definition.entries without needing
the original schema object. Applications handle field values and input validation.

Definitions also support [diffs](src/commonMain/kotlin/com/eignex/skema/SchemaDiff.kt),
[composition and namespaces](src/commonMain/kotlin/com/eignex/skema/SchemaComposition.kt),
and [JSON Schema export](src/commonMain/kotlin/com/eignex/skema/JsonSpec.kt).
Namespacing renames entry keys only; references embedded in configurations keep
their old names, so rewrite them with the owning library's traversal.
[Compact JSON](src/commonMain/kotlin/com/eignex/skema/serialization/CompactSerializer.kt)
and [serializer-derived JSON Schema](src/commonMain/kotlin/com/eignex/skema/serialization/JsonSchema.kt)
are available in source but not yet in the 0.3.0 release.

Serializer-derived schemas support string, enum, boolean, character, and signed or
unsigned integer map keys. Floating-point, nullable, and structured map keys require
an explicit schema. Strict decoding checks integer map keys as decimal digits with
an optional minus sign for signed types, within the key type's range.

Compact JSON requires the default polymorphic discriminator mode and no JSON naming
strategy. Operands and fixed fields must be emitted by their body serializer; avoid
`@EncodeDefault(NEVER)` on these fields. Unsupported configurations and omitted fields
raise explicit serialization errors. Ambiguous numeric literals use the catalog resolver.

Generated floating-point schemas and strict decoding use finite bounds that include
the decimal encodings of `Float.MAX_VALUE` and `Double.MAX_VALUE`. Character schemas
accept one UTF-16 code unit, matching Kotlin `Char`.

## Validation

Run `./gradlew check lintDocs` for the multiplatform tests, API checks, and documentation
lint. CI also validates generated JSON Schemas against the encoder and decoder examples
using an independent draft 2020-12 validator. To run that check locally after `jvmTest`:

```sh
python3 -m venv /tmp/skema-schema-tests
/tmp/skema-schema-tests/bin/pip install -r scripts/requirements-schema-tests.txt
/tmp/skema-schema-tests/bin/python scripts/check-json-schemas.py
```
