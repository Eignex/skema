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

skema is for libraries whose users want both a typed Kotlin schema (defined as a class so call sites get compile-time keys like `snap[schema.requests].sum`) and an external wire schema (HTTP payloads, YAML cloud configs) decoded into the same definition surface and accessed by name. Both paths terminate at the same `SchemaDef<C>` / `Named<C>` data shape, so producers and consumers can mix and match without reinventing the envelope. If you only need one mode you don't need skema; just write a sealed interface and a `Json {}` config.

## Usage

```kotlin
implementation("com.eignex:skema:0.1.0")
```

A consuming library defines its config sealed type, a typed key the property delegate returns, and a base schema with typed delegates on top of `register`:

```kotlin
@Serializable sealed interface ToyVar
@Serializable @SerialName("Bool") data object BoolToy : ToyVar
@Serializable @SerialName("Int")  data class IntToy(val min: Int, val max: Int) : ToyVar

sealed interface ToyKey { val name: String }
data class BoolKey(override val name: String) : ToyKey
data class IntKey(override val name: String, val min: Int, val max: Int) : ToyKey

abstract class ToyBaseSchema : LiveSchema<ToyVar>() {
    protected fun bool() = register(BoolToy, keyOf = { BoolKey(it) })
    protected fun int(min: Int, max: Int) = register(IntToy(min, max), keyOf = { IntKey(it, min, max) })
}
```

End users subclass that base and declare entries as properties:

```kotlin
class MySchema : ToyBaseSchema() {
    val flag  by bool()
    val score by int(0, 100)
}

val schema = MySchema()
schema.flag       // BoolKey(name = "flag")
schema.score      // IntKey(name = "score", min = 0, max = 100)
schema.entries    // [Named("flag", BoolToy), Named("score", IntToy(0, 100))]
SchemaJson.encodeToString(SchemaDef.serializer(ToyVar.serializer()), schema.definition())
// {"entries":[{"name":"flag","config":{"$type":"Bool"}},…]}
```

## Schema vs instance

A schema describes shape: names, types, per-entry config. An instance carries values that conform to a schema: assignments for variables, accumulator state for stats, etc. skema owns the schema side; the consuming library defines what an instance looks like and how it materializes from a schema.

A toy library backs an instance with a name-keyed map and gives it typed accessors:

```kotlin
data class ToyInstance(val values: Map<String, Any>) {
    operator fun get(key: BoolKey): Boolean = values.getValue(key.name) as Boolean
    operator fun get(key: IntKey): Int = values.getValue(key.name) as Int
}

fun ToyBaseSchema.randomInstance(): ToyInstance = ToyInstance(
    entries.associate { (name, config) ->
        name to when (config) {
            is BoolToy -> Random.nextBoolean()
            is IntToy  -> Random.nextInt(config.min, config.max + 1)
        }
    },
)
```

Reading then lines up cleanly:

```kotlin
val schema = MySchema()
val instance = schema.randomInstance()
instance[schema.score]   // Int, typed at the call site
instance[schema.flag]    // Boolean
```

Real consumers replace `ToyInstance` with `kumulant.StatGroup` (state accumulating from a stream), `klause` solver assignments, etc. skema doesn't know or care which.

## Dynamic path

When there's no schema class on the consumer side, walk `entries` or look up by name on `SchemaDef`:

```kotlin
val def: SchemaDef<ToyVar> = SchemaJson.decodeFromString(serializer(), wireText)
def.names                  // ["flag", "score"]
def["score"]               // IntToy(0, 100)
for ((name, config) in def.entries) when (config) {
    is BoolToy -> println(name)
    is IntToy  -> println("$name in ${config.min}..${config.max}")
}
```
