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

A Kotlin Multiplatform library for schemas that work two ways at once. Declare a schema as a singleton object on the producer side and you get typed compile-time access to every field; serialize the same definition to JSON, YAML, ProtoBuf, or any other kotlinx-serialization format, and a downstream consumer that doesn't share your Kotlin code can decode the schema and walk it by name. Both paths share the same SchemaDef wire shape, so producers and consumers mix and match without reinventing either side.

If you only need typed access, write a data class. If you only need wire data, write a sealed Serializable interface. skema is for the case where both are required.

Used by [kumulant](https://github.com/Eignex/kumulant) (streaming statistics), [klause](https://github.com/Eignex/klause) (constraint solver), and [combo](https://github.com/Eignex/combo) (multi-armed bandit), but not coupled to any of them; the library is generic plumbing.

## A complete example

```kotlin
implementation("com.eignex:skema:0.3.0")
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

    protected fun bool() = register(BoolField, ::BoolKey)
    protected fun int(min: Int, max: Int) = register(IntField(min, max)) { IntKey(it, min, max) }
}
```

A concrete schema is a singleton object. Mix-and-match assignment and delegation per property; both produce the same wire entry:

```kotlin
object SignupFormSchema : FormSchema() {
    val acceptsTos = bool("acceptsTos")
    val age       by int(13, 120)  // delegate: name from the property
}

data class SignupResponse(val acceptsTos: Boolean, val age: Int)

val response = SignupResponse(acceptsTos = true, age = 27)
response.age  // typed Int

val wire: String = SchemaJson.encodeToString(SchemaDef.serializer(FormField.serializer()), SignupFormSchema.definition())
// {"entries":{
//   "acceptsTos":{"$type":"Bool"},
//   "age":{"$type":"Int","min":13,"max":120}
// }}
```

Pick whichever form suits each entry: assignment makes the wire name explicit and survives property renames without changing the schema; the delegate form keeps the call site terse when the property name is the wire name. Schemas are plain singletons either way.

A downstream consumer (different process, no SignupFormSchema class) decodes the same wire string and walks the entries by name:

```kotlin
val def = SchemaJson.decodeFromString(SchemaDef.serializer(FormField.serializer()), wire)
for ((name, config) in def.entries) when (config) {
    is BoolField -> renderCheckbox(name)
    is IntField  -> renderSlider(name, config.min, config.max)
}
```

The same schema serves both sides without the consumer needing the producer's Kotlin code. That's the win.

The default wire wraps entries under a field called `entries`. To ship under a different root name, or to add adjunct fields (klause carries `vars` plus `constraints`, for example), define a Serializable wrapper and expose it as a separate method:

```kotlin
@Serializable data class SignupForm(val fields: Map<String, FormField>)

object SignupFormSchema : FormSchema() {
    val acceptsTos = bool("acceptsTos")
    val age        by int(13, 120)
    fun signupDef() = SignupForm(entries)
}

SchemaJson.encodeToString(SignupForm.serializer(), SignupFormSchema.signupDef())
// {"fields":{"acceptsTos":{"$type":"Bool"},"age":{"$type":"Int","min":13,"max":120}}}
```

## Diff and composition

`SchemaDef.diff(other)` reports per-entry adds, removes, and changes between two schemas. Use it to detect drift between producer and consumer versions, or to gate a schema migration:

```kotlin
val current = SignupFormSchema.definition()
val incoming = SchemaJson.decodeFromString(SchemaDef.serializer(FormField.serializer()), wire)
val diff = current.diff(incoming)
if (!diff.isEmpty) error("Schema drifted: added=${diff.added.keys}, removed=${diff.removed.keys}, changed=${diff.changed.keys}")
```

Two schemas combine with `+`, with names prefixed via `namespaced(prefix)`. The combination throws on overlap, so plugin-style modules can compose without silent collisions:

```kotlin
val users   = userFieldsSchema.namespaced("user")     // user.email, user.phone
val billing = billingFieldsSchema.namespaced("billing") // billing.address, ...
val app     = users + billing
```

## JSON Schema

Schemas describe data, so the obvious next question is whether you can hand a consumer a [json-schema.org](https://json-schema.org) document instead of skema's own wire form. You can, but skema can only do it automatically when it knows the vocabulary. The descriptor for `IntField(min, max)` says "object with two ints"; nothing about it implies "the validated value is an integer between those bounds." That mapping is semantic, not structural, and has to come from somewhere.

skema ships a built-in vocabulary called JsonSpec that mirrors JSON Schema draft 2020-12. It is opt-in by type. A schema whose config parameter is JsonSpec gets toJsonSchema for free, and schemas with other config types fall back to the mapper overload that takes a per-entry lambda. The variants cover the JSON Schema surface:

- Primitives: Bool, Null, Int, Long, Num, Str, Enum, Const. Numeric variants carry the usual `min`, `max`, `exclusiveMin`, `exclusiveMax`, and `multipleOf`; strings carry `minLength`, `maxLength`, `pattern`, and a free-form `format` annotation for things like date-time, email, or uuid.
- Compound shapes: Array (with `items`, `prefixItems`, `minItems`, `maxItems`, `uniqueItems`) and Object (with `properties`, `required`, the additional-properties pair, and property-count bounds).
- Composition: OneOf, AnyOf, AllOf, Not, with Nullable as ergonomic sugar for "this spec or null".
- Conditional: IfThenElse with a condition and optional then/otherwise branches.
- References: Ref paired with a `defs` parameter on toJsonSchema that populates the root `$defs` block.
- Annotations: Annotated decorates any variant with title, description, default, examples, deprecated, readOnly, writeOnly, and comment, none of which change the values the spec accepts.

A schema whose entries are JsonSpec values gets a JSON Schema for free:

```kotlin
abstract class FormSchema : Schema<JsonSpec>() {
    protected fun bool() = register(JsonSpec.Bool, ::BoolKey)
    protected fun int(min: Int, max: Int) =
        register(JsonSpec.Int(min, max)) { IntKey(it, min, max) }
}

object SignupFormSchema : FormSchema() {
    val acceptsTos by bool()
    val age        by int(13, 120)
}

SignupFormSchema.definition().toJsonSchema()
// {
//   "$schema": "https://json-schema.org/draft/2020-12/schema",
//   "type": "object",
//   "properties": {
//     "acceptsTos": {"type": "boolean"},
//     "age": {"type": "integer", "minimum": 13, "maximum": 120}
//   },
//   "additionalProperties": false,
//   "required": ["acceptsTos", "age"]
// }
```

Compound shapes nest naturally. The recursion is plain data and serializes to the same wire bytes as every other SchemaDef, so a consumer that prefers JsonSpec over the rendered JSON Schema document gets a structured tree it can walk instead of parsing JSON Schema by hand. The example below assembles a user record from the variants above, wrapping the display name with a description and allowing the email to be null:

```kotlin
val user = JsonSpec.Object(
    properties = mapOf(
        "id"    to JsonSpec.Long(min = 1L),
        "name"  to JsonSpec.Annotated(JsonSpec.Str(minLength = 1), description = "Display name"),
        "email" to JsonSpec.Nullable(JsonSpec.Str(format = "email")),
        "tags"  to JsonSpec.Array(items = JsonSpec.Str(), uniqueItems = true),
    ),
    required = listOf("id", "name"),
)
```

Shared and recursive sub-schemas live in `$defs`. Pass them to toJsonSchema and refer to them from anywhere in the tree with a Ref pointer like `#/$defs/User`; the resulting document is a single self-contained JSON Schema that a consumer can resolve without out-of-band knowledge.

```kotlin
mySchema.definition().toJsonSchema(
    defs = mapOf("User" to user),
)
```

For a domain-specific vocabulary, supply a per-entry mapper. The no-arg form on a non-JsonSpec schema is a compile error, not a runtime throw, so you can't forget; mixed hierarchies that carry a JsonSpec alongside custom variants reuse the built-in branch inside the mapper, and you only write JSON Schema for the parts skema doesn't already know.

```kotlin
mySchema.definition().toJsonSchema { config -> when (config) {
    BoolField   -> JsonSpec.Bool.toJsonSchema()
    is IntField -> JsonSpec.Int(config.min, config.max).toJsonSchema()
}}
```

JSON Schema is the only schema dialect skema ships. Protobuf wire output is already covered by kotlinx-serialization-protobuf since SchemaDef is `@Serializable`, and generating .proto IDL would need a different vocabulary (field numbers, proto-specific primitives) and so is not in scope here.
