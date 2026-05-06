package com.eignex.skema

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Builder base for any schema'd Eignex library. Subclasses (e.g. kumulant
 * `StatSchema`, klause `VariableSchema`) expose library-specific declarators
 * that call [add] (assignment form, explicit name) or [register] (delegate
 * form, name captured from the property).
 *
 * The pure-data view of this schema is [definition], a [SchemaDef] that
 * serializes through any kotlinx-serialization format. Two paths:
 *
 *  - **Class is the source of truth.** Producer and consumer share the
 *    schema class as code; build with `MySchema()`, serialize
 *    `definition()` for transport only. No deserialization on the
 *    consumer side.
 *  - **Wire is the source of truth.** No class on the consumer side;
 *    decode `SchemaDef<C>` and use a library-specific materializer to
 *    build the live form. Access by name only.
 *
 * skema supports both shapes from the same definition surface.
 */
abstract class Schema<C : Any> {
    private val mutableEntries = mutableListOf<Named<C>>()
    val entries: List<Named<C>> get() = mutableEntries

    /** Append an entry. Throws on duplicate names. */
    protected fun add(name: String, config: C) {
        require(mutableEntries.none { it.name == name }) {
            "Duplicate entry name '$name' in ${this::class.simpleName ?: "schema"}"
        }
        mutableEntries.add(Named(name, config))
    }

    /**
     * Property-delegate variant of [add]: captures the property name and
     * returns a typed [Key]. Use when you'd rather not repeat the name
     * (`val flag by bool()` instead of `val flag = bool("flag")`).
     */
    protected inline fun <Key> register(
        config: C,
        crossinline keyOf: (name: String) -> Key,
    ) = PropertyDelegateProvider<Schema<C>, ReadOnlyProperty<Schema<C>, Key>> { _, property ->
        val name = property.name
        add(name, config)
        val key = keyOf(name)
        ReadOnlyProperty { _, _ -> key }
    }

    /** Override to enforce cross-entry invariants. Called by [definition]; throw on violation. */
    protected open fun validate(entries: List<Named<C>>) {}

    /**
     * Pure-data, serializable view of this schema. Override only when the
     * library needs a wider wire shape (e.g. klause adds a separate
     * `constraints` list).
     */
    open fun definition(): SchemaDef<C> {
        validate(mutableEntries)
        return SchemaDef(mutableEntries.toList())
    }
}
