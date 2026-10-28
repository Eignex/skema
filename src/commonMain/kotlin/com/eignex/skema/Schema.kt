package com.eignex.skema

import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/** Schema builder with explicit names via [add] or property names via [register]. */
@Suppress("AbstractClassCanBeConcreteClass")
abstract class Schema<C : Any> {
    private val mutableEntries = LinkedHashMap<String, C>()

    /** Read-only view of registered entries, in declaration order. See [definition] for a snapshot. */
    val entries: Map<String, C> get() = mutableEntries

    protected fun add(name: String, config: C) {
        addPublished(name, config)
    }

    // Inlined register calls from other packages cannot access a protected JVM method.
    @PublishedApi
    internal fun addPublished(name: String, config: C) {
        require(name !in mutableEntries) {
            "Duplicate entry name '$name' in ${this::class.simpleName ?: "schema"}"
        }
        mutableEntries[name] = config
    }

    /** Registers under the delegated property's name and returns its key. */
    protected inline fun <Key> register(config: C, crossinline keyOf: (name: String) -> Key) =
        PropertyDelegateProvider<Schema<C>, ReadOnlyProperty<Schema<C>, Key>> { _, property ->
            val name = property.name
            addPublished(name, config)
            val key = keyOf(name)
            ReadOnlyProperty { _, _ -> key }
        }

    /** Override to enforce cross-entry invariants; called by [definition]. */
    protected open fun validate(entries: Map<String, C>) {}

    /** Calls [validate] and returns a shallow snapshot of the entries. */
    open fun definition(): SchemaDef<C> {
        validate(mutableEntries)
        return SchemaDef(LinkedHashMap(mutableEntries))
    }
}
