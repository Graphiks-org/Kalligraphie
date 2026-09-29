@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.font.sfnt.InflateOutcome
import org.graphiks.kalligraphie.font.sfnt.platformInflateSupport

/**
 * Validates the portable encoder against the platform decoder — Okio's `InflaterSource` on the JVM,
 * the portable inflater on web — so a defect here fails the round-trip instead of producing a stream
 * only this encoder's sibling decoder can read.
 */
class ZlibEncoderTest {
    @Test
    fun roundTripsEveryShapeThroughThePlatformInflater() {
        val samples = listOf(
            ByteArray(0),
            byteArrayOf(0),
            byteArrayOf(1, 2),
            byteArrayOf(1, 2, 3),
            ByteArray(54),
            ByteArray(4096) { index -> (index % 251).toByte() },
            ByteArray(1 shl 16) { index -> (index * 31 % 256).toByte() },
        )
        for (sample in samples) {
            val compressed = ZlibEncoder.encode(sample)
            val outcome = platformInflateSupport().inflateZlib(compressed, sample.size.toLong() + 1)
            val decoded = assertIs<InflateOutcome.Success>(outcome, "inflate failed for ${sample.size} bytes")
            assertContentEquals(sample, decoded.bytes, "round-trip failed for ${sample.size} bytes")
        }
    }

    @Test
    fun compressesARepeatedRunBelowItsLength() {
        // The WOFF fixtures rely on this: a table is only recorded as compressed when the stream is
        // genuinely shorter, which is what exercises the reader's inflate path.
        assertTrue(ZlibEncoder.encode(ByteArray(54)).size < 54)
    }
}
