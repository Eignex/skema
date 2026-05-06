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

> This repository is intended for internal use, but feel free to use however you want.

Shared schema-serialization plumbing for Eignex libraries ([kumulant](https://github.com/Eignex/kumulant), [klause](https://github.com/Eignex/klause), [combo](https://github.com/Eignex/combo)). Format-agnostic core: a `Named<C>` entry envelope, a `LiveSchema` property-delegate builder, a `SchemaDef<C>` wire wrapper, and a `SchemaJson` configuration with a `$type` discriminator (unquoted in YAML). Works with any kotlinx.serialization SerialFormat; binary formats use tag numbers instead.

## When to use it

skema is for libraries whose users want **both** of:

- **Typed Kotlin schemas defined as classes**, so call sites get compile-time keys: `snap[schema.requests].sum`. The class is the source of truth; serialization is just transport.
- **External wire schemas** (HTTP payloads, YAML cloud configs) decoded into the same definition surface and accessed by name. No Kotlin class on the consumer side.

Both paths terminate at the same `SchemaDef<C>` / `Named<C>` data shape, so producers and consumers can mix and match without reinventing the envelope. If you only need one mode you don't need skema — just write a sealed interface and a `Json {}` config.

## Usage

```kotlin
implementation("com.eignex:skema:0.1.0")
```

A consuming library defines its config sealed type and a base schema that exposes typed delegates on top of `register`:

```kotlin
@Serializable sealed interface ToyVar
@Serializable @SerialName("Bool") data object BoolToy : ToyVar
@Serializable @SerialName("Int")  data class IntToy(val min: Int, val max: Int) : ToyVar

abstract class ToyBaseSchema : LiveSchema<ToyVar>() {
    val materialized = mutableListOf<String>()
    protected fun bool() = register(BoolToy, keyOf = { it }) { name -> materialized += name }
    protected fun int(min: Int, max: Int) =
        register(IntToy(min, max), keyOf = { it }) { name -> materialized += name }
}
```

End users subclass that base and declare schema entries as properties:

```kotlin
class MySchema : ToyBaseSchema() {
    val flag  by bool()
    val score by int(0, 100)
}

val s = MySchema()
s.entries          // [Named("flag", BoolToy), Named("score", IntToy(0, 100))]
s.definition()     // SchemaDef(entries=[…])
SchemaJson.encodeToString(SchemaDef.serializer(ToyVar.serializer()), s.definition())
// {"entries":[{"name":"flag","config":{"$type":"Bool"}},…]}
```

The wire form decodes back into a `SchemaDef<ToyVar>` that downstream code can iterate by name without ever instantiating `MySchema` — the dynamic path.
