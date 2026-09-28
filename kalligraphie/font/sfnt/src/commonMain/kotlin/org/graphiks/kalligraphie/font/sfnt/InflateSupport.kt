package org.graphiks.kalligraphie.font.sfnt

/**
 * Synchronous, bounded decompression behind the PNG and SVG-in-OT decoders.
 *
 * The contract is deliberately small and blocking: the decoders run inside a synchronous
 * composition pipeline and cannot await the browser's `DecompressionStream`. Implementations must
 * apply [maxOutputBytes] *incrementally*: a stream that inflates past the bound is refused with the
 * excess bounded by at most one read chunk (8 KiB) and never returned to the caller, and
 * implementations must verify integrity checks before returning success.
 *
 * Known strictness divergence: the web `inflateZlib` actual rejects trailing bytes after the zlib
 * Adler-32, while the JVM/native Okio actual ignores them. Reconciling the two is a Phase 4 item.
 */
internal interface InflateSupport {
    /** Inflates one zlib (RFC 1950) stream, never producing more than [maxOutputBytes]. */
    fun inflateZlib(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome

    /**
     * Inflates exactly one gzip (RFC 1952) member, never producing more than [maxOutputBytes].
     *
     * Concatenated members and trailing bytes after the trailer must be rejected, matching Okio's
     * `GzipSource` acceptance rules.
     */
    fun gunzip(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome
}

internal sealed interface InflateOutcome {
    class Success(val bytes: ByteArray) : InflateOutcome
    class Malformed(val detail: String) : InflateOutcome
    class LimitExceeded(val observed: Long, val maximum: Long) : InflateOutcome
}

internal expect fun platformInflateSupport(): InflateSupport
