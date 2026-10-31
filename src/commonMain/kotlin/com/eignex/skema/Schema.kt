package com.eignex.skema

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/** Builds named entries via [add] or [register]. */
@Suppress("AbstractClassCanBeConcreteClass")
abstract class Schema<C : Any> {
    private val mutableEntries = LinkedHashMap<String, C>()

    /** Live view, in declaration order. */
    val entries: Map<String, C> get() = mutableEntries

    protected fun add(name: String, config: C) {
        addPublished(name, config)
    }

    // Reachable from inlined register calls in other packages.
    @PublishedApi
    internal fun addPublished(name: String, config: C) {
        require(name !in mutableEntries) {
            "Duplicate entry name '$name' in ${this::class.simpleName ?: "schema"}"
        }
        mutableEntries[name] = config
    }

    /** Registers under the property's name. */
    protected inline fun <Key> register(config: C, crossinline keyOf: (name: String) -> Key) =
        PropertyDelegateProvider<Schema<C>, ReadOnlyProperty<Schema<C>, Key>> { _, property ->
            val name = property.name
            addPublished(name, config)
            val key = keyOf(name)
            ReadOnlyProperty { _, _ -> key }
        }

    /** Checks cross-entry invariants. */
    protected open fun validate(entries: Map<String, C>) {}

    /** Validates and returns a snapshot. */
    open fun definition(): SchemaDef<C> {
        validate(mutableEntries)
        return SchemaDef(LinkedHashMap(mutableEntries))
    }
}
