@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontMetrics
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.slice
import org.graphiks.kalligraphie.font.sfnt.variation.MvarData
import org.graphiks.kalligraphie.font.sfnt.variation.MvarValueTags

/**
 * Builds the font-wide [FontMetrics] of a portable face at one instance.
 *
 * The defaults come from `OS/2` (with an `hhea` fallback for the vertical extents and a `post`
 * fallback for the underline), and the `MVAR` deltas are applied afterwards. A missing or too-short
 * optional table falls back to its secondary source or to zero rather than failing, matching the
 * face-level metadata philosophy that an unparseable optional table collapses instead of
 * rejecting the whole face.
 */
internal object FontMetricsReader {
    internal fun read(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
        mvar: MvarData?,
        orderedAxes: List<Double>,
    ): FontOperationResult<FontMetrics> {
        val os2 = tableSlice(sourceBytes, parsedFont, "OS/2")
        val hhea = tableSlice(sourceBytes, parsedFont, "hhea")
        val post = tableSlice(sourceBytes, parsedFont, "post")

        val os2Version = os2?.let { readUInt16(it, OS2_VERSION_OFFSET)?.toInt() }
        val typoAscender = os2?.takeIf { it.size >= OS2_TYPO_END }?.let { readInt16(it, OS2_TYPO_ASCENDER_OFFSET) }
        val typoDescender = os2?.takeIf { it.size >= OS2_TYPO_END }?.let { readInt16(it, OS2_TYPO_DESCENDER_OFFSET) }
        val typoLineGap = os2?.takeIf { it.size >= OS2_TYPO_END }?.let { readInt16(it, OS2_TYPO_LINE_GAP_OFFSET) }

        val ascender = typoAscender
            ?: hhea?.let { readInt16(it, HHEA_ASCENDER_OFFSET) }
            ?: 0
        val descender = typoDescender
            ?: hhea?.let { readInt16(it, HHEA_DESCENDER_OFFSET) }
            ?: 0
        val lineGap = typoLineGap
            ?: hhea?.let { readInt16(it, HHEA_LINE_GAP_OFFSET) }
            ?: 0

        val hasVerticalMetrics = os2 != null && os2Version != null && os2Version >= 2 && os2.size >= OS2_CAP_HEIGHT_END
        val xHeight = if (hasVerticalMetrics) readInt16(os2, OS2_X_HEIGHT_OFFSET) ?: 0 else 0
        val capHeight = if (hasVerticalMetrics) readInt16(os2, OS2_CAP_HEIGHT_OFFSET) ?: 0 else 0

        val underlinePosition = post?.takeIf { it.size >= POST_UNDERLINE_END }
            ?.let { readInt16(it, POST_UNDERLINE_POSITION_OFFSET) } ?: 0
        val underlineThickness = post?.takeIf { it.size >= POST_UNDERLINE_END }
            ?.let { readInt16(it, POST_UNDERLINE_THICKNESS_OFFSET) } ?: 0

        return FontOperationResult.Success(
            FontMetrics(
                ascender = (ascender + delta(mvar, MvarValueTags.HORIZONTAL_ASCENDER, orderedAxes)).toFloat(),
                descender = (descender + delta(mvar, MvarValueTags.HORIZONTAL_DESCENDER, orderedAxes)).toFloat(),
                lineGap = (lineGap + delta(mvar, MvarValueTags.HORIZONTAL_LINE_GAP, orderedAxes)).toFloat(),
                underlinePosition = (underlinePosition + delta(mvar, MvarValueTags.UNDERLINE_OFFSET, orderedAxes)).toFloat(),
                underlineThickness = (underlineThickness + delta(mvar, MvarValueTags.UNDERLINE_SIZE, orderedAxes)).toFloat(),
                xHeight = (xHeight + delta(mvar, MvarValueTags.X_HEIGHT, orderedAxes)).toFloat(),
                capHeight = (capHeight + delta(mvar, MvarValueTags.CAP_HEIGHT, orderedAxes)).toFloat(),
            ),
        )
    }

    private fun tableSlice(sourceBytes: ByteArray, parsedFont: ParsedTrueTypeFont, tag: String): ByteArray? {
        val record = parsedFont.tableRecords[tag] ?: return null
        return slice(sourceBytes, record)
    }

    private fun delta(mvar: MvarData?, valueTag: String, orderedAxes: List<Double>): Double =
        mvar?.delta(valueTag, orderedAxes) ?: 0.0

    private const val OS2_VERSION_OFFSET = 0
    private const val OS2_TYPO_ASCENDER_OFFSET = 68
    private const val OS2_TYPO_DESCENDER_OFFSET = 70
    private const val OS2_TYPO_LINE_GAP_OFFSET = 72
    private const val OS2_TYPO_END = 78
    private const val OS2_X_HEIGHT_OFFSET = 86
    private const val OS2_CAP_HEIGHT_OFFSET = 88
    private const val OS2_CAP_HEIGHT_END = 90
    private const val HHEA_ASCENDER_OFFSET = 4
    private const val HHEA_DESCENDER_OFFSET = 6
    private const val HHEA_LINE_GAP_OFFSET = 8
    private const val POST_UNDERLINE_POSITION_OFFSET = 8
    private const val POST_UNDERLINE_THICKNESS_OFFSET = 10
    private const val POST_UNDERLINE_END = 12
}
