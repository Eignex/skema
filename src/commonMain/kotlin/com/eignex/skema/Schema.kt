package com.eignex.skema

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Builder base for any schema'd Eignex library. Subclasses expose
 * library-specific declarators that call [add] (assignment form) or
 * [register] (delegate form). [definition] returns the wire form.
 */
abstract class Schema<C : Any> {
    private val mutableEntries = LinkedHashMap<String, C>()
    val entries: Map<String, C> get() = mutableEntries

    protected fun add(name: String, config: C) {
        addPublished(name, config)
    }

    /**
     * Backing implementation for [add] and [register]. `register` is `inline`,
     * so its body executes from a synthetic class in the consumer's package —
     * JVM `protected` blocks that path with `IllegalAccessError`.
     * `@PublishedApi internal` keeps the symbol off the public source API
     * while letting inlined call sites reach it.
     */
    @PublishedApi
    internal fun addPublished(name: String, config: C) {
        require(name !in mutableEntries) {
            "Duplicate entry name '$name' in ${this::class.simpleName ?: "schema"}"
        }
        mutableEntries[name] = config
    }

    /**
     * Property-delegate variant of [add]; captures the property name and
     * returns a typed [Key]. `val flag by bool()` instead of
     * `val flag = bool("flag")`.
     */
    protected inline fun <Key> register(
        config: C,
        crossinline keyOf: (name: String) -> Key,
    ) = PropertyDelegateProvider<Schema<C>, ReadOnlyProperty<Schema<C>, Key>> { _, property ->
        val name = property.name
        addPublished(name, config)
        val key = keyOf(name)
        ReadOnlyProperty { _, _ -> key }
    }

    /** Override to enforce cross-entry invariants; called by [definition]. */
    protected open fun validate(entries: Map<String, C>) {}

    /**
     * Pure-data, serializable view of this schema. Override to ship under
     * a different root field name or to add adjunct fields.
     */
    open fun definition(): SchemaDef<C> {
        validate(mutableEntries)
        return SchemaDef(LinkedHashMap(mutableEntries))
    }
}
