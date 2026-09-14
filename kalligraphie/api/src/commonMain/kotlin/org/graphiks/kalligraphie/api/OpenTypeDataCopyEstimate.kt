package org.graphiks.kalligraphie.api

/**
 * Allocation-free preflight for copying one immutable OpenType source.
 * Providers must report non-negative values and [maxOwnedCopyBytes] >= [sourceBytes].
 * Admission validates these promises before any copying; object overhead and GC are excluded.
 */
public data class OpenTypeDataCopyEstimate(
    /** Exact byte length of the immutable face source, before defensive copying. */
    public val sourceBytes: Long,
    /** Conservative bound of all simultaneous source/buffer copies made by copyOpenTypeData. */
    public val maxOwnedCopyBytes: Long,
)
