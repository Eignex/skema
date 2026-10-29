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
serializable data.

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
Namespacing prefixes entry keys; applications must update references inside configurations.

[Compact JSON](src/commonMain/kotlin/com/eignex/skema/serialization/CompactSerializer.kt)
encodes nodes as operator objects, names, or numeric literals.
[Serializer-derived JSON Schema](src/commonMain/kotlin/com/eignex/skema/serialization/JsonSchema.kt)
and [strict decoding](src/commonMain/kotlin/com/eignex/skema/serialization/JsonPathDecoder.kt)
use a `JsonNodeCatalog` for nested compact vocabularies. Compact JSON requires the default
polymorphic discriminator mode and no JSON naming strategy.

## Build validation

Routine builds run `./gradlew check lintDocs koverXmlReport` on JDK 17 for JVM
and Linux x64. Gradle runs on JDK 21, as required by kbuild 1.3.6.
Tagged releases enable `-Ptargets.full`, rerunning Linux/web
checks alongside Windows and macOS/iOS simulator tests in parallel. Publication
waits for every platform to pass. The build workflow's `fullTargets` input runs
the same checks without publishing.

Gradle dependencies, task outputs, the host's Kotlin Native toolchain and encrypted
configuration state are cached. Configuration state requires the encryption secret.
Automatic main snapshots publish all targets after the routine build passes.

ABI references live in `api/` and `api-full/`. Update both with
`./gradlew updateKotlinAbi` and `./gradlew updateKotlinAbi -Ptargets.full`.
Commit JavaScript and Wasm lockfiles under `kotlin-js-store/`; update them with
`./gradlew kotlinUpgradeYarnLock kotlinWasmUpgradeYarnLock -Ptargets.full`.

### Tagged releases

Push an explicit SemVer tag: `vX.Y.Z` publishes to Maven Central and creates a
GitHub release; `vX.Y.Z-rc.N` publishes all targets and checksums to a GitHub
prerelease only. RCs never publish to Maven. Versions come directly from tags;
there is no manual release mode or automatic RC numbering.

```sh
git tag v0.3.1-rc.1
git push origin v0.3.1-rc.1
```
