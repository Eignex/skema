package com.eignex.skema

/**
 * Generic typed-schema round-trip: rebuild the typed [LiveSchema] subclass
 * from a wire-decoded [def], verify the wire matches what [factory]
 * declares, and produce a library-specific live value via [materialize].
 *
 * Library-side wrappers thin this to taste, e.g. kumulant:
 * ```
 * fun <T : StatSchema> StatSchemaDef.bindTo(factory: () -> T, c: Concurrency) =
 *     bindTyped(this, factory, definitionOf = { it.definition() }) {
 *         StatGroup(stats = materializeSeries(c), concurrency = c)
 *     }.let { (s, g) -> TypedSchema(s, g) }
 * ```
 *
 * The factory runs under [LiveSchema.skeleton], so it produces typed
 * delegate keys without allocating live state — only [materialize] does
 * that, fed by [def].
 *
 * Wire-vs-local equality is strict: any drift between [def] and
 * `definitionOf(schema)` throws with both values surfaced for diff.
 */
inline fun <T : LiveSchema<C>, C : Any, D, Live> bindTyped(
    def: D,
    crossinline factory: () -> T,
    crossinline definitionOf: (T) -> D,
    crossinline materialize: () -> Live,
): Pair<T, Live> {
    val schema = LiveSchema.skeleton(factory)
    val expected = definitionOf(schema)
    require(def == expected) {
        "Wire schema differs from ${schema::class.simpleName}: expected $expected, got $def"
    }
    return schema to materialize()
}
