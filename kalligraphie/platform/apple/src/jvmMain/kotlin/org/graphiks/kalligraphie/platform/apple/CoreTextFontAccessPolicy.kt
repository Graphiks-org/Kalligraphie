package org.graphiks.kalligraphie.platform.apple

/**
 * Finite admission limits for captured sources and simultaneous controlled temporary buffers.
 * Values must be non-negative and strictly less than Long.MAX_VALUE. This does not bound private
 * OS allocations, object overhead, delayed GC, or caller-retained native owners as an aggregate.
 */
public data class CoreTextFontAccessPolicy(
    /** Maximum immutable source length for each face. */
    public val maxSourceBytesPerFace: Long,
    /** Maximum complete source-byte total captured by a snapshot. */
    public val maxCapturedSourceBytes: Long,
    /** Maximum simultaneously admitted controlled copying/creation bytes across its resolvers. */
    public val maxTransientOwnedBytes: Long,
) {
    init { require(listOf(maxSourceBytesPerFace, maxCapturedSourceBytes, maxTransientOwnedBytes).all { it >= 0 && it < Long.MAX_VALUE }) }
}
