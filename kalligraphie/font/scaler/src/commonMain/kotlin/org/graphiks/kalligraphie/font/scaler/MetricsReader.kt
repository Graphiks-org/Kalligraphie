package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticData
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LayoutBounds
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
        if (!layoutSize.isFinite()) {
            return failure(FontError.InvalidInstanceDescriptor("layoutSize must be finite."))
        }
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
            val offset = glyphId.value.toLong() * 4L
            if (checkedRangeEnd(offset, 4L, hmtx.size) == null) {
                return failure(
                    FontError.OutOfBounds("hmtx longHorMetric record is truncated.", tableLocation("hmtx")),
                    FontDiagnosticData(offset = offset, length = 4L),
                )
            }
            val checkedOffset = offset.toInt()
            HorizontalMetrics(
                advanceWidth = readUInt16(hmtx, checkedOffset)?.toInt()
                    ?: return failure(FontError.OutOfBounds("hmtx advanceWidth is truncated.", tableLocation("hmtx"))),
                leftSideBearing = readInt16(hmtx, checkedOffset + 2)
                    ?: return failure(FontError.OutOfBounds("hmtx leftSideBearing is truncated.", tableLocation("hmtx"))),
            )
        } else {
            val lastMetricOffset = (numberOfHMetrics.toLong() - 1L) * 4L
            if (checkedRangeEnd(lastMetricOffset, 4L, hmtx.size) == null) {
                return failure(
                    FontError.OutOfBounds("hmtx longHorMetric record is truncated.", tableLocation("hmtx")),
                    FontDiagnosticData(offset = lastMetricOffset, length = 4L),
                )
            }
            val advanceWidth = readUInt16(hmtx, lastMetricOffset.toInt())?.toInt()
                ?: return failure(FontError.OutOfBounds("hmtx advanceWidth is truncated.", tableLocation("hmtx")))
            val lsbOffset = numberOfHMetrics.toLong() * 4L +
                (glyphId.value.toLong() - numberOfHMetrics.toLong()) * 2L
            if (checkedRangeEnd(lsbOffset, 2L, hmtx.size) == null) {
                return failure(
                    FontError.OutOfBounds("hmtx trailing leftSideBearing is truncated.", tableLocation("hmtx")),
                    FontDiagnosticData(offset = lsbOffset, length = 2L),
                )
            }
            HorizontalMetrics(
                advanceWidth = advanceWidth,
                leftSideBearing = readInt16(hmtx, lsbOffset.toInt())
                    ?: return failure(FontError.OutOfBounds("hmtx trailing leftSideBearing is truncated.", tableLocation("hmtx"))),
            )
        }

        val bounds = when (val result = readGlyphBounds(sourceBytes, parsedFont, glyphId)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }

        val advanceWidth = scaleDesignUnit(metrics.advanceWidth, layoutSize, parsedFont.metadata.unitsPerEm)
            ?: return failure(FontError.GeometryOverflow("advanceWidth could not be represented as a finite LayoutUnit."))
        val leftSideBearing = scaleDesignUnit(metrics.leftSideBearing, layoutSize, parsedFont.metadata.unitsPerEm)
            ?: return failure(FontError.GeometryOverflow("leftSideBearing could not be represented as a finite LayoutUnit."))
        val scaledBounds = scaleBounds(bounds, layoutSize, parsedFont.metadata.unitsPerEm)
            ?: return failure(FontError.GeometryOverflow("Glyph bounds could not be represented as finite LayoutUnit values."))

        return FontOperationResult.Success(
            GlyphMetrics(
                advanceWidthDesignUnits = metrics.advanceWidth,
                leftSideBearingDesignUnits = metrics.leftSideBearing,
                advanceWidth = advanceWidth,
                leftSideBearing = leftSideBearing,
                bounds = bounds,
                scaledBounds = scaledBounds,
            ),
        )
    }

    private fun scaleDesignUnit(value: Int, layoutSize: Float, unitsPerEm: Int): LayoutUnit? {
        val scaled = value.toDouble() * layoutSize.toDouble() / unitsPerEm.toDouble()
        val narrowed = scaled.toFloat()
        return if (scaled.isFinite() && narrowed.isFinite()) LayoutUnit(narrowed) else null
    }

    private fun scaleBounds(bounds: DesignBounds, layoutSize: Float, unitsPerEm: Int): LayoutBounds? {
        val minX = scaleDesignUnit(bounds.minX, layoutSize, unitsPerEm) ?: return null
        val minY = scaleDesignUnit(bounds.minY, layoutSize, unitsPerEm) ?: return null
        val maxX = scaleDesignUnit(bounds.maxX, layoutSize, unitsPerEm) ?: return null
        val maxY = scaleDesignUnit(bounds.maxY, layoutSize, unitsPerEm) ?: return null
        return LayoutBounds(minX, minY, maxX, maxY)
    }

    private fun readGlyphBounds(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
        glyphId: GlyphId,
    ): FontOperationResult<DesignBounds> {
        val glyfRecord = parsedFont.tableRecords["glyf"] ?: return failure(missingTable("glyf"))
        val glyf = slice(sourceBytes, glyfRecord)
            ?: return failure(
                FontError.OutOfBounds("Table glyf exceeds source length.", tableLocation("glyf")),
                FontDiagnosticData(offset = glyfRecord.offset, length = glyfRecord.length),
            )
        val loca = when (val result = LocaReader.readLoca(sourceBytes, parsedFont, glyf.size)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val range = when (val result = loca.rangeForGlyph(glyphId)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (range.start == range.endExclusive) return FontOperationResult.Success(DesignBounds.empty)
        if (checkedRangeEnd(range.start.toLong(), GLYF_HEADER_LENGTH, glyf.size) == null ||
            range.start.toLong() + GLYF_HEADER_LENGTH > range.endExclusive.toLong()
        ) {
            return failure(
                FontError.OutOfBounds("Glyph header bounds are truncated.", FontDiagnosticLocation.Glyph(glyphId.value)),
                FontDiagnosticData(offset = range.start.toLong(), length = GLYF_HEADER_LENGTH),
            )
        }
        val bounds = DesignBounds(
            minX = readInt16(glyf, range.start + 2)
                ?: return failure(FontError.OutOfBounds("Glyph xMin is truncated.", FontDiagnosticLocation.Glyph(glyphId.value))),
            minY = readInt16(glyf, range.start + 4)
                ?: return failure(FontError.OutOfBounds("Glyph yMin is truncated.", FontDiagnosticLocation.Glyph(glyphId.value))),
            maxX = readInt16(glyf, range.start + 6)
                ?: return failure(FontError.OutOfBounds("Glyph xMax is truncated.", FontDiagnosticLocation.Glyph(glyphId.value))),
            maxY = readInt16(glyf, range.start + 8)
                ?: return failure(FontError.OutOfBounds("Glyph yMax is truncated.", FontDiagnosticLocation.Glyph(glyphId.value))),
        )
        if (bounds.minX > bounds.maxX || bounds.minY > bounds.maxY) {
            return failure(FontError.InvalidFontData("Glyph ink bounds are inverted.", FontDiagnosticLocation.Glyph(glyphId.value)))
        }
        return FontOperationResult.Success(bounds)
    }

    private fun missingTable(tag: String): FontError.MissingRequiredTable = FontError.MissingRequiredTable(tag)

    private fun tableLocation(tag: String): FontDiagnosticLocation = FontDiagnosticLocation.Table(tag)

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())

    private fun failure(error: FontError, data: FontDiagnosticData): FontOperationResult.Failure =
        failure(error, listOf(error.toDiagnostic(data)))
}

private data class HorizontalMetrics(
    val advanceWidth: Int,
    val leftSideBearing: Int,
)

private const val GLYF_HEADER_LENGTH = 10L
