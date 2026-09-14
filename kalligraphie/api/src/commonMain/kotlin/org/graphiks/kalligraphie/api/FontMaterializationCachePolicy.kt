package org.graphiks.kalligraphie.api

/**
 * Simultaneous per-face and per-capture bounds for font materialization retention.
 *
 * Admission and least-recently-used eviction are coordinated atomically within one captured
 * catalog. An oversized result is returned without retention. Only complete immutable
 * successes and independently owned platform contexts are eligible; cancellation and operational
 * failures are never cached. Retention
 * never changes route selection, representation identity, certificates or diagnostics.
 * Closing the last resolver or asset lease of a face releases that face's evictable entries.
 * An explicitly supplied [FontCacheScope] additionally bounds aggregate retention across captures;
 * without one, each captured catalog retains its independent budget.
 *
 * The historical byte-only constructor and [maxEvictableBytesPerFace] getter remain available.
 * This data class intentionally does not retain source or binary compatibility for its generated
 * [copy] and destructuring operations: the first component is now [FontCacheBudget], not [Long],
 * and [copy] takes [perFace] and [perCatalog]. JVM consumers compiled against the former generated
 * operations must migrate to the structured budgets and recompile.
 */
public data class FontMaterializationCachePolicy(
    /** Limits shared by all retained representations of one captured face. */
    public val perFace: FontCacheBudget,
    /** Aggregate limits shared by all captured faces of one catalog. */
    public val perCatalog: FontCacheBudget,
) {
    /**
     * Preserves the byte-only per-face policy. Other dimensions and the catalog aggregate
     * remain practically unbounded. Zero disables retention; negative values are rejected.
     */
    public constructor(maxEvictableBytesPerFace: Long) : this(
        perFace = FontCacheBudget(maxEvictableBytesPerFace, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
        perCatalog = FontCacheBudget(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE),
    )

    /** Historical per-face byte limit; use [perFace] and [perCatalog] for all configured bounds. */
    public val maxEvictableBytesPerFace: Long
        get() = perFace.retainedBytes

    /** Standard policies for portable font materialization caches. */
    public companion object {
        /** Disables retention of materialized representations while preserving all render results. */
        public val disabled: FontMaterializationCachePolicy = FontMaterializationCachePolicy(0L)
    }
}
