package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.checkedRangeEnd
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.slice

public object MetricsReader {
    public fun readGlyphMetrics(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
        glyphId: GlyphId,
        layoutSize: Float,
    ): FontOperationResult<GlyphMetrics> {
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }

        val hheaRecord = parsedFont.tableRecords["hhea"] ?: return failure(missingTable("hhea"))
        val hhea = slice(sourceBytes, hheaRecord)
            ?: return failure(FontError.OutOfBounds("Table hhea exceeds source length.", tableLocation("hhea")))
        val hmtxRecord = parsedFont.tableRecords["hmtx"] ?: return failure(missingTable("hmtx"))
        val hmtx = slice(sourceBytes, hmtxRecord)
            ?: return failure(FontError.OutOfBounds("Table hmtx exceeds source length.", tableLocation("hmtx")))
        val numberOfHMetrics = readUInt16(hhea, 34)?.toInt()
            ?: return failure(FontError.InvalidFontData("hhea.numberOfHMetrics is truncated.", tableLocation("hhea")))
        if (numberOfHMetrics <= 0 || numberOfHMetrics > parsedFont.metadata.glyphCount) {
            return failure(FontError.InvalidFontData("hhea.numberOfHMetrics is invalid.", tableLocation("hhea")))
        }

        val metrics = if (glyphId.value < numberOfHMetrics) {
            val offset = glyphId.value * 4
            if (checkedRangeEnd(offset, 4, hmtx.size) == null) {
                return failure(FontError.OutOfBounds("hmtx longHorMetric record is truncated.", tableLocation("hmtx")))
            }
            HorizontalMetrics(
                advanceWidth = readUInt16(hmtx, offset)?.toInt()
                    ?: return failure(FontError.OutOfBounds("hmtx advanceWidth is truncated.", tableLocation("hmtx"))),
                leftSideBearing = readInt16(hmtx, offset + 2)
                    ?: return failure(FontError.OutOfBounds("hmtx leftSideBearing is truncated.", tableLocation("hmtx"))),
            )
        } else {
            val lastMetricOffset = (numberOfHMetrics - 1) * 4
            if (checkedRangeEnd(lastMetricOffset, 4, hmtx.size) == null) {
                return failure(FontError.OutOfBounds("hmtx longHorMetric record is truncated.", tableLocation("hmtx")))
            }
            val advanceWidth = readUInt16(hmtx, lastMetricOffset)?.toInt()
                ?: return failure(FontError.OutOfBounds("hmtx advanceWidth is truncated.", tableLocation("hmtx")))
            val lsbOffset = numberOfHMetrics * 4 + (glyphId.value - numberOfHMetrics) * 2
            if (checkedRangeEnd(lsbOffset, 2, hmtx.size) == null) {
                return failure(FontError.OutOfBounds("hmtx trailing leftSideBearing is truncated.", tableLocation("hmtx")))
            }
            HorizontalMetrics(
                advanceWidth = advanceWidth,
                leftSideBearing = readInt16(hmtx, lsbOffset)
                    ?: return failure(FontError.OutOfBounds("hmtx trailing leftSideBearing is truncated.", tableLocation("hmtx"))),
            )
        }

        val advanceWidth = scaleDesignUnit(metrics.advanceWidth, layoutSize, parsedFont.metadata.unitsPerEm)
            ?: return failure(FontError.GeometryOverflow("advanceWidth could not be represented as a finite LayoutUnit."))
        val leftSideBearing = scaleDesignUnit(metrics.leftSideBearing, layoutSize, parsedFont.metadata.unitsPerEm)
            ?: return failure(FontError.GeometryOverflow("leftSideBearing could not be represented as a finite LayoutUnit."))

        return FontOperationResult.Success(
            GlyphMetrics(
                advanceWidthDesignUnits = metrics.advanceWidth,
                leftSideBearingDesignUnits = metrics.leftSideBearing,
                advanceWidth = advanceWidth,
                leftSideBearing = leftSideBearing,
                bounds = DesignBounds.empty,
            ),
        )
    }

    private fun scaleDesignUnit(value: Int, layoutSize: Float, unitsPerEm: Int): LayoutUnit? {
        val scaled = value.toFloat() * layoutSize / unitsPerEm.toFloat()
        return if (scaled.isFinite()) LayoutUnit(scaled) else null
    }

    private fun missingTable(tag: String): FontError.MissingRequiredTable = FontError.MissingRequiredTable(tag)

    private fun tableLocation(tag: String): FontDiagnosticLocation = FontDiagnosticLocation.Table(tag)

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
}

private data class HorizontalMetrics(
    val advanceWidth: Int,
    val leftSideBearing: Int,
)
