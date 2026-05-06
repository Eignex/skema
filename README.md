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

Shared schema-serialization plumbing for Eignex libraries ([kumulant](https://github.com/Eignex/kumulant), [klause](https://github.com/Eignex/klause), [combo](https://github.com/Eignex/combo)). Lets a producer declare a schema as a Kotlin class for compile-time access, ship the same schema as JSON or YAML, and have a downstream consumer that doesn't share the class iterate the wire form by name. The two paths share a `Named<C>` entry envelope and a `SchemaDef<C>` wire wrapper, so producers and consumers can mix and match without reinventing either side.

If you only ever need typed access, write a data class. If you only ever need wire data, write a sealed `@Serializable` interface. skema is for the case where both are required.

## A complete example

```kotlin
implementation("com.eignex:skema:0.1.0")
```

Define a vocabulary of config types and a base schema with name-taking declarators. The convention is borrowed directly from JetBrains' [Exposed](https://github.com/JetBrains/Exposed), where SQL columns are declared as `val name = varchar("name", 50)` on a `Table` singleton; the same shape works here for any schema entry:

```kotlin
@Serializable sealed interface ToyVar
@Serializable @SerialName("Bool") data object BoolToy : ToyVar
@Serializable @SerialName("Int")  data class IntToy(val min: Int, val max: Int) : ToyVar

sealed interface ToyKey { val name: String }
data class BoolKey(override val name: String) : ToyKey
data class IntKey(override val name: String, val min: Int, val max: Int) : ToyKey

abstract class ToyBaseSchema : LiveSchema<ToyVar>() {
    protected fun bool(name: String): BoolKey {
        add(name, BoolToy)
        return BoolKey(name)
    }
    protected fun int(name: String, min: Int, max: Int): IntKey {
        add(name, IntToy(min, max))
        return IntKey(name, min, max)
    }
}
```

The producer declares a schema as a singleton object, with assignment instead of property delegation. Compile-time access works on the schema; instances are a hand-rolled data class:

```kotlin
object FormSchema : ToyBaseSchema() {
    val acceptsTos = bool("acceptsTos")
    val age        = int("age", 13, 120)
}

data class FormResponse(val acceptsTos: Boolean, val age: Int)

val response = FormResponse(acceptsTos = true, age = 27)
response.age  // typed Int

val wire: String = SchemaJson.encodeToString(SchemaDef.serializer(ToyVar.serializer()), FormSchema.definition())
// {"entries":[
//   {"name":"acceptsTos","config":{"$type":"Bool"}},
//   {"name":"age","config":{"$type":"Int","min":13,"max":120}}
// ]}
```

The name string is repeated once (in the declaration) rather than implicit in a `by`-delegate. That's the Kotlin price of skipping reflection; in exchange, schemas are plain singletons that can be referenced from anywhere without instantiation, and renaming a property no longer silently changes the wire form.

A downstream consumer (different process, no `FormSchema` class) decodes the same wire string and walks the entries by name:

```kotlin
val def = SchemaJson.decodeFromString(SchemaDef.serializer(ToyVar.serializer()), wire)
for ((name, config) in def.entries) when (config) {
    is BoolToy -> renderCheckbox(name)
    is IntToy  -> renderSlider(name, config.min, config.max)
}
```

The same schema serves both sides without the consumer needing the producer's Kotlin code. That's the win.

## Schema vs instance

The schema is shape (names, types, per-entry config) and is what skema owns. An instance carries values conforming to a schema (assignments, accumulator state, response payloads) and lives in the consuming library. The example above keeps them separate: `FormSchema` is the schema, `FormResponse` is the instance, materialization is the user's call.

The schema's typed keys (`FormSchema.acceptsTos`, `FormSchema.age`) come into play when an instance can't be a hand-rolled data class because the schema isn't known at compile time. kumulant accepts any user-defined `StatSchema` and stores accumulators in a name-keyed map; lookup is `instance[FormSchema.someStat]`. klause does the same for solver-assigned variables. skema doesn't impose a shape on instances, only on the schema description.
