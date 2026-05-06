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

A Kotlin Multiplatform library for schemas that work two ways at once. Declare a schema as a singleton object on the producer side and you get typed compile-time access to every field; serialize the same definition to JSON, YAML, ProtoBuf, or any other kotlinx-serialization format, and a downstream consumer that doesn't share your Kotlin code can decode the schema and walk it by name. Both paths terminate at the same Named entry envelope and SchemaDef wire wrapper, so producers and consumers mix and match without reinventing either side.

If you only need typed access, write a data class. If you only need wire data, write a sealed Serializable interface. skema is for the case where both are required.

Used by [kumulant](https://github.com/Eignex/kumulant) (streaming statistics), [klause](https://github.com/Eignex/klause) (constraint solver), and [combo](https://github.com/Eignex/combo) (multi-armed bandit), but not coupled to any of them; the library is generic plumbing.

## A complete example

```kotlin
implementation("com.eignex:skema:0.1.0")
```

Define a vocabulary of config types and a base schema with name-taking declarators. The convention is borrowed directly from JetBrains' [Exposed](https://github.com/JetBrains/Exposed):

```kotlin
@Serializable sealed interface FormField
@Serializable @SerialName("Bool") data object BoolField : FormField
@Serializable @SerialName("Int")  data class IntField(val min: Int, val max: Int) : FormField

sealed interface FieldKey { val name: String }
data class BoolKey(override val name: String) : FieldKey
data class IntKey(override val name: String, val min: Int, val max: Int) : FieldKey

abstract class FormSchema : Schema<FormField>() {
    protected fun bool(name: String): BoolKey {
        add(name, BoolField)
        return BoolKey(name)
    }
    protected fun int(name: String, min: Int, max: Int): IntKey {
        add(name, IntField(min, max))
        return IntKey(name, min, max)
    }
}
```

A concrete schema is a singleton object that uses assignment instead of property delegation. Compile-time access works on the schema; instances are a hand-rolled data class:

```kotlin
object SignupFormSchema : FormSchema() {
    val acceptsTos = bool("acceptsTos")
    val age        = int("age", 13, 120)
}

data class SignupResponse(val acceptsTos: Boolean, val age: Int)

val response = SignupResponse(acceptsTos = true, age = 27)
response.age  // typed Int

val wire: String = SchemaJson.encodeToString(SchemaDef.serializer(FormField.serializer()), SignupFormSchema.definition())
// {"entries":[
//   {"name":"acceptsTos","config":{"$type":"Bool"}},
//   {"name":"age","config":{"$type":"Int","min":13,"max":120}}
// ]}
```

The name string is repeated once in the declaration rather than implicit in a property delegate. That's the Kotlin price of skipping reflection; in exchange, schemas are plain singletons that can be referenced from anywhere without instantiation, and renaming a property no longer silently changes the wire form.

A downstream consumer (different process, no SignupFormSchema class) decodes the same wire string and walks the entries by name:

```kotlin
val def = SchemaJson.decodeFromString(SchemaDef.serializer(FormField.serializer()), wire)
for ((name, config) in def.entries) when (config) {
    is BoolField -> renderCheckbox(name)
    is IntField  -> renderSlider(name, config.min, config.max)
}
```

The same schema serves both sides without the consumer needing the producer's Kotlin code. That's the win.

## Schema vs instance

The schema is shape (names, types, per-entry config) and is what skema owns. An instance carries values conforming to a schema (assignments, accumulator state, response payloads) and lives in the consuming library. The example above keeps them separate: SignupFormSchema is the schema, SignupResponse is the instance, materialization is the user's call.

The schema's typed keys come into play when an instance can't be a hand-rolled data class because the schema isn't known at compile time. kumulant accepts any user-defined StatSchema and stores accumulators in a name-keyed map; lookup is `instance[schema.someStat]`. klause does the same for solver-assigned variables. skema doesn't impose a shape on instances, only on the schema description.
