@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Charset of a CFF face: the SID (or CID for CID-keyed fonts) of every glyph
 * after the implicit glyph 0.
 *
 * A predefined charset (ISOAdobe, Expert, ExpertSubset) is stored by its CFF
 * predefined offset; an explicit charset stores one SID per glyph from glyph 1 to
 * glyph `glyphCount - 1`.
 */
internal sealed interface CffCharset {
    /** One of the three CFF predefined charsets, identified by its offset `0..2`. */
    data class Predefined(val offset: Int) : CffCharset

    /** Explicit charset: `sids[i]` is the SID/CID of glyph `i + 1`. */
    data class Explicit(val sids: List<Int>) : CffCharset
}

/** Reads CFF charset formats 0, 1 and 2, plus the predefined charsets. */
internal object CffCharsetReader {
    /** Predefined ISOAdobe charset offset. */
    const val ISO_ADOBE: Int = 0

    /** Predefined Expert charset offset. */
    const val EXPERT: Int = 1

    /** Predefined ExpertSubset charset offset. */
    const val EXPERT_SUBSET: Int = 2

    private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF ")

    /**
     * Reads the charset beginning at [offset] for a face with [glyphCount] glyphs.
     *
     * Glyph 0 (`.notdef`) is implicit and never listed. A negative [glyphCount] or
     * a [glyphCount] of `0` is rejected as invalid face structure.
     *
     * @return the parsed charset, or a typed failure for a truncated range,
     * an out-of-range glyph count, or an unknown charset format.
     */
    fun read(bytes: ByteArray, offset: Int, glyphCount: Int): FontOperationResult<CffCharset> {
        if (glyphCount < 1) return failure("CFF charset requires at least one glyph.")
        val sidCount = glyphCount - 1
        if (offset in ISO_ADOBE..EXPERT_SUBSET) return FontOperationResult.Success(CffCharset.Predefined(offset))
        if (offset < 0 || offset >= bytes.size) return failure("CFF charset offset is outside the source.")
        val format = bytes[offset].toInt() and 0xFF
        return when (format) {
            0 -> readFormatZero(bytes, offset + 1, sidCount)
            1 -> readRanges(bytes, offset + 1, sidCount, smallCount = true)
            2 -> readRanges(bytes, offset + 1, sidCount, smallCount = false)
            else -> failure("CFF charset format $format is not defined.")
        }
    }

    private fun readFormatZero(bytes: ByteArray, start: Int, sidCount: Int): FontOperationResult<CffCharset> {
        val end = start + sidCount * 2
        if (end > bytes.size) return failure("CFF charset format 0 is truncated.")
        val sids = ArrayList<Int>(sidCount)
        for (index in 0 until sidCount) sids.add(readUInt16(bytes, start + index * 2))
        return FontOperationResult.Success(CffCharset.Explicit(sids))
    }

    private fun readRanges(
        bytes: ByteArray,
        start: Int,
        sidCount: Int,
        smallCount: Boolean,
    ): FontOperationResult<CffCharset> {
        val sids = ArrayList<Int>(sidCount)
        var position = start
        while (sids.size < sidCount) {
            if (position + 2 > bytes.size) return failure("CFF charset range is truncated.")
            val first = readUInt16(bytes, position)
            position += 2
            val leftCount: Int
            if (smallCount) {
                if (position >= bytes.size) return failure("CFF charset range count is truncated.")
                leftCount = bytes[position].toInt() and 0xFF
                position += 1
            } else {
                if (position + 2 > bytes.size) return failure("CFF charset range count is truncated.")
                leftCount = readUInt16(bytes, position)
                position += 2
            }
            if (sids.size + leftCount + 1 > sidCount) return failure("CFF charset ranges exceed the glyph count.")
            for (step in 0..leftCount) sids.add(first + step)
        }
        return FontOperationResult.Success(CffCharset.Explicit(sids))
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun failure(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.InvalidFontData(message, location))
}
