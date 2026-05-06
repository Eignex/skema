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

Shared schema-serialization plumbing for Eignex libraries ([kumulant](https://github.com/Eignex/kumulant), [klause](https://github.com/Eignex/klause), [combo](https://github.com/Eignex/combo)). Format-agnostic core: Named entries, a LiveSchema property-delegate builder with reentrant skeleton mode, a SchemaJson configuration with the standard @type discriminator, and a bindTyped round-trip helper. Works with any kotlinx.serialization SerialFormat; binary formats use tag numbers instead of @type.

## Usage

```kotlin
implementation("com.eignex:skema:0.1.0")
```

A consuming library defines its config sealed type and a base schema that exposes typed delegates on top of register:

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

The end user subclasses that base and declares schema entries as properties:

```kotlin
class MySchema : ToyBaseSchema() {
    val flag  by bool()
    val score by int(0, 100)
}
```

End-user usage: instantiate, serialize entries with any SerialFormat, then round-trip back through bindTyped:

```kotlin
val s = MySchema()
s.entries  // [Named("flag", BoolToy), Named("score", IntToy(0, 100))]

val (rebuilt, live) = bindTyped(
    def = s.entries,
    factory = ::MySchema,
    definitionOf = { it.entries },
    materialize = { /* build live state */ },
)
```
