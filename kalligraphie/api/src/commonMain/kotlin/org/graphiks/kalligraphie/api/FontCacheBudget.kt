package org.graphiks.kalligraphie.api

/**
 * Independent upper bounds for evictable font representation retention.
 *
 * Every limit is non-negative. [Long.MAX_VALUE] leaves a dimension practically unbounded.
 * These limits exclude source snapshots and caller-owned render assets; they do not bound
 * total process memory or change glyph materialization results.
 */
public data class FontCacheBudget(
    /** Maximum conservatively estimated bytes retained by cached keys, results and diagnostics. */
    public val retainedBytes: Long,
    /** Maximum retained decoded bitmap pixels, counted as width times height, not byte count. */
    public val decodedPixels: Long,
    /** Maximum estimated bytes of cache-owned native resources; portable entries charge zero. */
    public val nativeBytes: Long,
    /** Maximum cache-owned native allocations; caller-owned handles are excluded. */
    public val nativeAllocations: Long,
) {
    init {
        require(retainedBytes >= 0L) { "retainedBytes must not be negative." }
        require(decodedPixels >= 0L) { "decodedPixels must not be negative." }
        require(nativeBytes >= 0L) { "nativeBytes must not be negative." }
        require(nativeAllocations >= 0L) { "nativeAllocations must not be negative." }
    }
}
