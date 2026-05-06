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
 * serializes through any kotlinx-serialization format. Override
 * [definition] to use a wrapper with a different root field name or to
 * carry adjunct lists alongside the main entries.
 */
abstract class Schema<C : Any> {
    private val mutableEntries = LinkedHashMap<String, C>()
    val entries: Map<String, C> get() = mutableEntries

    /** Append an entry. Throws on duplicate names. */
    protected fun add(name: String, config: C) {
        require(name !in mutableEntries) {
            "Duplicate entry name '$name' in ${this::class.simpleName ?: "schema"}"
        }
        mutableEntries[name] = config
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
    protected open fun validate(entries: Map<String, C>) {}

    /**
     * Pure-data, serializable view of this schema. Default wraps
     * [entries] in [SchemaDef] (root field name "entries"); override to
     * use a wrapper with a different name or adjunct fields.
     */
    open fun definition(): SchemaDef<C> {
        validate(mutableEntries)
        return SchemaDef(LinkedHashMap(mutableEntries))
    }
}
