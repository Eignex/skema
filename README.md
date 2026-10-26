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

A Kotlin Multiplatform library for typed schema declarations and serializable definitions.
`Schema<C>` builds declarations; `SchemaDef<C>` carries named configuration entries
through kotlinx.serialization to consumers in other processes.

Used by [kumulant](https://github.com/Eignex/kumulant),
[klause](https://github.com/Eignex/klause), and [combo](https://github.com/Eignex/combo).

## Getting started

```kotlin
implementation("com.eignex:skema:0.3.0")
```

Define serializable configuration types and declarators that return typed handles:

```kotlin
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

`add` uses an explicit wire name; `register` takes the property name. Explicit names
stay stable across property renames. Both forms register the same configuration data.

Serialize the definition with `SchemaJson`, which uses a `$type` discriminator and
omits defaults and nulls:

```kotlin
val serializer = SchemaDef.serializer(FormField.serializer())
val wire = SchemaJson.encodeToString(serializer, SignupFormSchema.definition())
// {"entries":{"acceptsTos":{"$type":"Bool"},"age":{"$type":"Int","min":13,"max":120}}}
```

A consumer shares the `FormField` vocabulary and reads entries by name without
needing `SignupFormSchema`:

```kotlin
val def = SchemaJson.decodeFromString(serializer, wire)
for ((name, config) in def.entries) when (config) {
    is BoolField -> renderCheckbox(name)
    is IntField -> renderSlider(name, config.min, config.max)
}
```

Use a serializable wrapper for a different root field or additional metadata:

```kotlin
@Serializable data class SignupForm(val fields: Map<String, FormField>)

val form = SignupForm(SignupFormSchema.definition().entries)
SchemaJson.encodeToString(SignupForm.serializer(), form)
// {"fields":{"acceptsTos":{"$type":"Bool"},"age":{"$type":"Int","min":13,"max":120}}}
```

## Compact JSON

Opt a vocabulary into `com.eignex.skema.serialization.CompactSerializer` to encode
nodes with operator keys:

| Shape | JSON |
|---|---|
| Named fields | `{"offset":{"amount":2.0}}` |
| Empty payload | `"input"` |
| Numeric literal | `2.0` |
| Single operand | `{"negate":"input"}` |
| Positional operands | `{"plus":["input",2.0]}` |

```kotlin
@Serializable(with = ExprSerializer::class)
sealed interface Expr

@Serializable data object Input : Expr
@Serializable data class Literal(val value: Double) : Expr
@Serializable data class Plus(val left: Expr, val right: Expr) : Expr

object ExprSerializer : CompactSerializer<Expr>(
    "example.Expr",
    Expr::class,
    nodes = {
        listOf(
            jsonNode<Input>("input"),
            jsonNode<Literal>("literal", JsonNodeShape.Literal, "value"),
            jsonNode<Plus>("plus", JsonNodeShape.Operands, "left", "right"),
        )
    },
)

val wire = SchemaJson.encodeToString<Expr>(Plus(Input, Literal(2.0)))
// {"plus":["input",2.0]}
val decoded = SchemaJson.decodeFromString<Expr>(wire)
```

Export a JSON Schema for the compact vocabulary or a whole schema document:

```kotlin
val expressionSpec = ExprSerializer.toJsonSchema()
val catalog = JsonNodeCatalog { listOf(ExprSerializer) }
val documentSpec = SchemaDef.serializer(ExprSerializer).toJsonSchema(catalog)
```

These specifications describe the serialized JSON, including recursive operands and
strict field layouts. Custom payload mappings implement `jsonSchema(node, body)` to
describe their wire format. Constructor invariants require explicit schema constraints.

Give each vocabulary a stable descriptor name. Share a lazy `JsonNodeCatalog` for
cross-vocabulary references; its optional resolver handles shared operator names.
Catalogs are independent, with no global registration.

Body serializers must describe objects. Built-in literal and operand shapes must
cover every body field and emit operand defaults and nulls; literals must be non-null
numbers. Named-field defaults and nested nodes follow the caller's JSON settings.
For other payload layouts, use `JsonPayloadMapping` and its `fieldPath` function.
Pass an explicit body serializer to `jsonNode` when generic arguments require it.

Decoding rejects unknown fields, malformed operands and out-of-range integers even
with lenient JSON settings, reporting paths such as `$.plus[1].offset.amount`.
Both `Json` and `SchemaJson` use compact encoding; non-JSON formats retain the
sealed-class representation. Compact nodes can contain ordinary sealed values such as
`JsonSpec`; mixed trees retain their configured discriminators.

## Diff and composition

`SchemaDef.diff(other)` reports added, removed and changed entries:

```kotlin
val incoming = SchemaJson.decodeFromString(serializer, wire)
val diff = SignupFormSchema.definition().diff(incoming)
if (!diff.isEmpty) error("Schema changed: $diff")
```

Combine definitions with `+`; overlapping names throw. `namespaced` prefixes names
with a dot-separated namespace:

```kotlin
val users = userFieldsSchema.definition().namespaced("user")       // user.email
val billing = billingFieldsSchema.definition().namespaced("billing") // billing.address
val app = users + billing
```

## JSON Schema

`JsonSpec` represents JSON Schema draft 2020-12: primitives, arrays, objects,
composition, conditionals, references and annotations. Definitions using this
vocabulary export directly with `toJsonSchema()`:

```kotlin
val form = SchemaDef<JsonSpec>(mapOf(
    "acceptsTos" to JsonSpec.Bool,
    "age" to JsonSpec.Int(min = 13, max = 120),
))

form.toJsonSchema()
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

Nest variants to describe structured data:

```kotlin
val user = JsonSpec.Object(
    properties = mapOf(
        "id" to JsonSpec.Long(min = 1L),
        "name" to JsonSpec.Annotated(JsonSpec.Str(minLength = 1), description = "Display name"),
        "email" to JsonSpec.Nullable(JsonSpec.Str(format = "email")),
        "tags" to JsonSpec.Array(items = JsonSpec.Str(), uniqueItems = true),
    ),
    required = listOf("id", "name"),
)
```

Use `JsonSpec.Ref("#/\$defs/User")` for shared or recursive types and pass
`defs = mapOf("User" to user)` to `toJsonSchema()` to populate `$defs`.

Other vocabularies supply a per-entry mapper: serialization descriptors describe
configuration fields, so they cannot infer the validation rules those fields imply.

```kotlin
SignupFormSchema.definition().toJsonSchema { config -> when (config) {
    BoolField -> JsonSpec.Bool.toJsonSchema()
    is IntField -> JsonSpec.Int(config.min, config.max).toJsonSchema()
}}
```

`SchemaDef` also supports other kotlinx.serialization formats, including CBOR and
ProtoBuf. Generating `.proto` definitions requires a separate vocabulary and is
outside this library's scope.
