package org.graphiks.kalligraphie.api

/**
 * Simultaneous per-face and per-catalog bounds for portable representation retention.
 *
 * Admission and least-recently-used eviction are coordinated atomically within one captured
 * catalog. An oversized result is returned without retention. Only complete immutable
 * successes are eligible; cancellation and operational failures are never cached. Retention
 * never changes route selection, representation identity, certificates or diagnostics.
 * Closing the last resolver or asset lease of a face releases that face's evictable entries.
 * Catalogs do not share a provider-wide or engine-wide budget.
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
