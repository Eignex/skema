package com.eignex.skema

/**
 * Round-trip a wire-decoded [def] back to a typed [LiveSchema] subclass.
 * [factory] runs under [LiveSchema.skeleton] so it produces typed delegate
 * keys without allocating live state; [materialize] supplies the live
 * value, fed by [def]. Wire-vs-local equality is strict — drift throws
 * with both values surfaced.
 *
 * Library wrappers thin this. Kumulant:
 * ```
 * fun <T : StatSchema> StatSchemaDef.bindTo(factory: () -> T, c: Concurrency) =
 *     bindTyped(this, factory, definitionOf = { it.definition() }) {
 *         StatGroup(stats = materializeSeries(c), concurrency = c)
 *     }
 * ```
 */
inline fun <S : LiveSchema<C>, C : Any, D, L> bindTyped(
    def: D,
    crossinline factory: () -> S,
    crossinline definitionOf: (S) -> D,
    crossinline materialize: () -> L,
): Bound<S, L> {
    val schema = LiveSchema.skeleton(factory)
    val expected = definitionOf(schema)
    require(def == expected) {
        "Wire schema differs from ${schema::class.simpleName}: expected $expected, got $def"
    }
    return Bound(schema, materialize())
}
