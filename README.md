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

[![License](https://img.shields.io/github/license/Eignex/skema)](https://github.com/Eignex/skema/blob/main/LICENSE)

> This repository is intended for internal use, but feel free to use however you want.

Shared schema-serialization plumbing for Eignex libraries: kumulant, klause, combo.

---

## Overview

skema is the small, format-agnostic core that other Eignex schema libraries
build on. It defines how a property-delegate-style schema becomes wire data
and how that wire data binds back to a typed schema instance.

The library is plumbing: it does not define a variable vocabulary or a stat
catalog. Those live in consumer libraries that subclass LiveSchema with
their own delegate methods and config types.

The core works with any kotlinx.serialization SerialFormat: JSON,
ProtoBuf, Cbor, and so on. The @type-discriminator contract applies to
JSON only; binary formats use tag numbers.

Named is the pure-data envelope pairing a property-style name with a
config value. LiveSchema is the builder base; property delegates collect
entries, and a reentrant skeleton mode suppresses materialization while
still capturing the schema shape. SchemaJson gives the recommended Json
configuration for Eignex schema payloads. bindTyped is the round-trip
helper that rebuilds a typed LiveSchema from a wire-decoded definition
and verifies wire/local equality.

---

## Installation

Gradle Kotlin DSL:

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.3.0"
}

dependencies {
    implementation("com.eignex:skema:<version>")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
}
```

The serialization core is required by every consumer; the JSON artifact is
only needed if you use SchemaJson. Other formats (protobuf, cbor) work
just as well, since skema is format-agnostic.

---

## Named

The generic envelope that every Eignex schema entry flows through. Both
halves of a schema declaration (variables, stats, constraints, anything
declared with a property delegate) live in named entries.

```kotlin
@Serializable
data class Named<C>(val name: String, val config: C)
```

Serialize a list of named entries with any SerialFormat to get the on-wire
representation of a schema.

---

## LiveSchema

Builder base for any schema'd Eignex library. Subclasses declare
library-specific delegate methods that call register with a materializer
lambda for any live state that should be built alongside the wire-data
entry.

```kotlin
class ToySchema : LiveSchema<ToyVar>() {
    val materialized = mutableListOf<String>()

    val flag  by register(BoolToy,        keyOf = { it }) { name -> materialized += name }
    val score by register(IntToy(0, 100), keyOf = { it }) { name -> materialized += name }
}

val s = ToySchema()
s.entries       // [Named("flag", BoolToy), Named("score", IntToy(0, 100))]
s.materialized  // ["flag", "score"]
```

register uses a PropertyDelegateProvider, so the property's source name
becomes the entry name with no manual string passing.

LiveSchema.skeleton(::ToySchema) constructs the schema with skeleton mode
on. The materializer lambda is skipped and only entries is populated. The
flag is reentrant (nested schemas constructed inside a skeleton block are
also skeletons), and the prior flag is restored on exit.

An add(name, config) method is exposed for delegates that don't fit the
register shape.

---

## SchemaJson

Recommended Json configuration for Eignex schema payloads. Apply to any
Json builder for the standard contract.

```kotlin
val myJson = Json {
    schemaJsonConfig()
    prettyPrint = true   // your own overrides
}

// Or use the pre-configured instance directly:
SchemaJson.encodeToString(Named.serializer(ToyVar.serializer()), entry)
// {"name":"flag","config":{"@type":"Bool"}}
```

| Setting            | Value  |
| ------------------ | ------ |
| classDiscriminator | @type  |
| encodeDefaults     | false  |
| explicitNulls      | false  |

Binary formats (ProtoBuf, Cbor) use tag-number polymorphism and do not
need this config.

---

## bindTyped

Generic typed-schema round-trip: rebuild the typed LiveSchema subclass
from a wire-decoded def, verify the wire matches what the factory
declares, and produce a library-specific live value via materialize.

```kotlin
val (rebuilt, live) = bindTyped(
    def = wire,
    factory = ::ToySchema,
    definitionOf = { it.entries },
    materialize = { /* build live state from def */ "live!" },
)
```

The factory runs under skeleton mode, so it produces typed delegate keys
without allocating live state; only materialize does that, fed by def.
Wire-vs-local equality is strict: any drift between def and the local
definition throws with both values surfaced for diff.

Library-side wrappers thin this to taste. For example, kumulant:

```kotlin
fun <T : StatSchema> StatSchemaDef.bindTo(factory: () -> T, c: Concurrency) =
    bindTyped(this, factory, definitionOf = { it.definition() }) {
        StatGroup(stats = materializeSeries(c), concurrency = c)
    }.let { (s, g) -> TypedSchema(s, g) }
```
