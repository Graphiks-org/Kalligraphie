@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * Independent resource bounds applied while decoding a managed font container.
 *
 * Neither bound is derived from a declared container field, so a malformed or hostile source
 * cannot drive an allocation. [maxDecodedFontBytes] caps every produced buffer (a table, a
 * decompressed font-data block and the reassembled SFNT); [maxWorkingBytes] bounds the maximum
 * Brotli back-reference distance (window reach) and a transform's live streams.
 */
@KalligraphieInternalApi
public class WoffDecodeLimits(
    /** Maximum size in bytes of any single produced buffer. */
    public val maxDecodedFontBytes: Long,
    /** Maximum working-set size in bytes during decoding. */
    public val maxWorkingBytes: Long,
) {
    /** Standard bound presets. */
    public companion object {
        private const val EMBEDDED_LIMIT: Long = 64L * 1024L * 1024L

        /** Default for embedded ingestion, which has no caller-provided options object. */
        public val EMBEDDED: WoffDecodeLimits = WoffDecodeLimits(EMBEDDED_LIMIT, EMBEDDED_LIMIT)

        /** Capture bound equal to the caller's source-byte budget in both dimensions. */
        public fun forCapture(maxSourceBytes: Int): WoffDecodeLimits =
            WoffDecodeLimits(maxSourceBytes.toLong(), maxSourceBytes.toLong())
    }
}
