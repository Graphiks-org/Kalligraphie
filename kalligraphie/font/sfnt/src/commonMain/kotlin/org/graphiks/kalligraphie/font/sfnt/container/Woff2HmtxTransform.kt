@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Reconstructs the WOFF2 §5.4 transformed `hmtx` table.
 *
 * The transformed table is one `flags` byte followed by `numberOfHMetrics` 16-bit advance widths
 * and, for each side-bearing array that [flags] does not omit, the corresponding signed
 * bearings:
 *
 * - Bit 0 set means the proportional `lsb[]` array is absent and every entry is the glyph's
 *   `xMin`; bit 1 set means the trailing `leftSideBearing[]` array is absent and every entry is
 *   the glyph's `xMin`. Bits 2-7 are reserved and must be zero, and at least one of bits 0/1 must
 *   be set (a transform that omits nothing is not applicable).
 * - The trailing array has `numGlyphs - numberOfHMetrics` entries and is empty when the counts are
 *   equal. An empty glyph's derived bearing is 0, which is exactly the `xMin` a caller supplies
 *   for it.
 *
 * The result is the normal interleaved `hmtx` table: `numberOfHMetrics` advance/`lsb` pairs
 * followed by the trailing `leftSideBearing` values. It is charged against
 * [WoffDecodeLimits.maxDecodedFontBytes]; any structural violation is
 * `font.woff2.transform-failed`.
 */
internal object Woff2HmtxTransform {
    private const val TRANSFORM_FAILED: String = "font.woff2.transform-failed"
    private const val FLAG_LSB_ABSENT: Int = 0x01
    private const val FLAG_TRAILING_ABSENT: Int = 0x02
    private const val RESERVED_FLAGS: Int = 0xFC
    private const val HEADER_BYTES: Int = 1

    /** Reconstructs [transformed], or returns a typed rejection. */
    fun reconstruct(
        transformed: ByteArray,
        numberOfHMetrics: Int,
        numGlyphs: Int,
        xMinByGlyph: IntArray,
        limits: WoffDecodeLimits,
    ): FontOperationResult<ByteArray> {
        if (limits.maxDecodedFontBytes < 0L) {
            return limitExceeded("The WOFF2 decoded-size limit must be non-negative.")
        }
        return try {
            FontOperationResult.Success(
                reconstruct(
                    transformed = transformed,
                    numberOfHMetrics = numberOfHMetrics,
                    numGlyphs = numGlyphs,
                    xMinByGlyph = xMinByGlyph,
                    maxDecodedFontBytes = limits.maxDecodedFontBytes,
                ),
            )
        } catch (limit: DecodedLimitException) {
            limitExceeded(limit.message ?: "The WOFF2 hmtx decoded limit was exceeded.")
        } catch (invalid: TransformException) {
            failure(invalid.message ?: "The WOFF2 hmtx transform is malformed.")
        }
    }

    private fun reconstruct(
        transformed: ByteArray,
        numberOfHMetrics: Int,
        numGlyphs: Int,
        xMinByGlyph: IntArray,
        maxDecodedFontBytes: Long,
    ): ByteArray {
        if (transformed.size < HEADER_BYTES) {
            throw TransformException("The transformed WOFF2 hmtx table is truncated.")
        }
        val reader = Reader(transformed)
        val flags = reader.u8()
        if (flags and RESERVED_FLAGS != 0) {
            throw TransformException("The transformed WOFF2 hmtx flags $flags set a reserved bit.")
        }
        if (flags and (FLAG_LSB_ABSENT or FLAG_TRAILING_ABSENT) == 0) {
            throw TransformException("The transformed WOFF2 hmtx flags $flags omit neither side-bearing array.")
        }
        if (numGlyphs < 0) {
            throw TransformException("The WOFF2 hmtx numGlyphs must be non-negative.")
        }
        if (numberOfHMetrics <= 0 || numberOfHMetrics > numGlyphs) {
            throw TransformException(
                "The WOFF2 hmtx numberOfHMetrics $numberOfHMetrics is not within 1..$numGlyphs.",
            )
        }
        if (xMinByGlyph.size < numGlyphs) {
            throw TransformException(
                "The WOFF2 hmtx xMin source has ${xMinByGlyph.size} entries for $numGlyphs glyphs.",
            )
        }

        val trailingCount = numGlyphs - numberOfHMetrics
        val outputSize = numberOfHMetrics.toLong() * 4L + trailingCount.toLong() * 2L
        if (outputSize > maxDecodedFontBytes) {
            throw DecodedLimitException(
                "The reconstructed WOFF2 hmtx table would be $outputSize bytes, over the " +
                    "$maxDecodedFontBytes-byte limit.",
            )
        }

        val advances = IntArray(numberOfHMetrics) { reader.u16() }
        val proportionalBearings = if (flags and FLAG_LSB_ABSENT != 0) {
            IntArray(numberOfHMetrics) { xMinByGlyph[it] }
        } else {
            IntArray(numberOfHMetrics) { reader.i16() }
        }
        val trailingBearings = if (flags and FLAG_TRAILING_ABSENT != 0) {
            IntArray(trailingCount) { xMinByGlyph[numberOfHMetrics + it] }
        } else {
            IntArray(trailingCount) { reader.i16() }
        }
        if (reader.remaining() != 0) {
            throw TransformException(
                "The transformed WOFF2 hmtx table has ${reader.remaining()} trailing bytes.",
            )
        }

        val out = ByteArray(outputSize.toInt())
        var offset = 0
        for (index in 0 until numberOfHMetrics) {
            offset = writeU16(out, offset, advances[index])
            offset = writeU16(out, offset, proportionalBearings[index])
        }
        for (bearing in trailingBearings) {
            offset = writeU16(out, offset, bearing)
        }
        return out
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int): Int {
        target[offset] = (value ushr 8 and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
        return offset + 2
    }

    private fun failure(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(
            FontError.FontDataFailure(TRANSFORM_FAILED, message, FontDiagnosticLocation.Source),
        )

    private fun limitExceeded(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Source))

    /** A bounds-checked big-endian read cursor over the transformed table. */
    private class Reader(private val bytes: ByteArray) {
        private var position: Int = 0

        fun u8(): Int {
            if (position >= bytes.size) throw truncated()
            return bytes[position++].toInt() and 0xFF
        }

        fun u16(): Int {
            if (position + 2 > bytes.size) throw truncated()
            val value = ((bytes[position].toInt() and 0xFF) shl 8) or (bytes[position + 1].toInt() and 0xFF)
            position += 2
            return value
        }

        fun i16(): Int = u16().toShort().toInt()

        fun remaining(): Int = bytes.size - position

        private fun truncated(): TransformException =
            TransformException("The transformed WOFF2 hmtx table is truncated.")
    }

    private class TransformException(message: String) : Exception(message)

    private class DecodedLimitException(message: String) : Exception(message)
}
