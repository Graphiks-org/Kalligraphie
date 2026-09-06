package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticData
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.VerticalGlyphMetrics
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.checkedRangeEnd
import org.graphiks.kalligraphie.font.sfnt.readInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.slice

/** Decodes OpenType `vhea` and `vmtx` metrics without retaining mutable font storage. */
internal object VerticalMetricsReader {
    /** Prepares immutable vertical-metric table views for repeated glyph reads. */
    internal fun prepare(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
    ): FontOperationResult<PreparedVerticalMetricsData> {
        val vheaRecord = parsedFont.tableRecords["vhea"] ?: return failure(FontError.MissingRequiredTable("vhea"))
        val vhea = slice(sourceBytes, vheaRecord)
            ?: return failure(FontError.OutOfBounds("Table vhea exceeds source length.", tableLocation("vhea")))
        val vmtxRecord = parsedFont.tableRecords["vmtx"] ?: return failure(FontError.MissingRequiredTable("vmtx"))
        val vmtx = slice(sourceBytes, vmtxRecord)
            ?: return failure(FontError.OutOfBounds("Table vmtx exceeds source length.", tableLocation("vmtx")))
        val numberOfLongVerMetrics = readUInt16(vhea, NUMBER_OF_LONG_VER_METRICS_OFFSET)?.toInt()
            ?: return failure(FontError.InvalidFontData("vhea.numberOfLongVerMetrics is truncated.", tableLocation("vhea")))
        if (numberOfLongVerMetrics <= 0 || numberOfLongVerMetrics > parsedFont.metadata.glyphCount) {
            return failure(FontError.InvalidFontData("vhea.numberOfLongVerMetrics is invalid.", tableLocation("vhea")))
        }
        return FontOperationResult.Success(
            PreparedVerticalMetricsData(
                vmtx = vmtx,
                numberOfLongVerMetrics = numberOfLongVerMetrics,
                glyphCount = parsedFont.metadata.glyphCount,
                unitsPerEm = parsedFont.metadata.unitsPerEm,
            ),
        )
    }

    /** Reads one glyph's vertical advance and top side bearing at [layoutSize]. */
    internal fun readGlyphMetrics(
        prepared: PreparedVerticalMetricsData,
        glyphId: GlyphId,
        layoutSize: Float,
    ): FontOperationResult<VerticalGlyphMetrics> {
        if (!layoutSize.isFinite()) {
            return failure(FontError.InvalidInstanceDescriptor("layoutSize must be finite."))
        }
        if (glyphId.value !in 0 until prepared.glyphCount) return failure(FontError.GlyphOutOfRange(glyphId.value))
        val metrics = if (glyphId.value < prepared.numberOfLongVerMetrics) {
            val offset = glyphId.value.toLong() * LONG_VERTICAL_METRIC_SIZE
            if (checkedRangeEnd(offset, LONG_VERTICAL_METRIC_SIZE, prepared.vmtx.size) == null) {
                return failure(
                    FontError.OutOfBounds("vmtx longVerMetric record is truncated.", tableLocation("vmtx")),
                    FontDiagnosticData(offset = offset, length = LONG_VERTICAL_METRIC_SIZE),
                )
            }
            val position = offset.toInt()
            VerticalMetrics(
                advanceHeight = readUInt16(prepared.vmtx, position)?.toInt()
                    ?: return failure(FontError.OutOfBounds("vmtx advanceHeight is truncated.", tableLocation("vmtx"))),
                topSideBearing = readInt16(prepared.vmtx, position + 2)
                    ?: return failure(FontError.OutOfBounds("vmtx topSideBearing is truncated.", tableLocation("vmtx"))),
            )
        } else {
            val lastMetricOffset = (prepared.numberOfLongVerMetrics.toLong() - 1L) * LONG_VERTICAL_METRIC_SIZE
            if (checkedRangeEnd(lastMetricOffset, LONG_VERTICAL_METRIC_SIZE, prepared.vmtx.size) == null) {
                return failure(
                    FontError.OutOfBounds("vmtx final longVerMetric record is truncated.", tableLocation("vmtx")),
                    FontDiagnosticData(offset = lastMetricOffset, length = LONG_VERTICAL_METRIC_SIZE),
                )
            }
            val advanceHeight = readUInt16(prepared.vmtx, lastMetricOffset.toInt())?.toInt()
                ?: return failure(FontError.OutOfBounds("vmtx final advanceHeight is truncated.", tableLocation("vmtx")))
            val bearingOffset = prepared.numberOfLongVerMetrics.toLong() * LONG_VERTICAL_METRIC_SIZE +
                (glyphId.value.toLong() - prepared.numberOfLongVerMetrics.toLong()) * SHORT_BEARING_SIZE
            if (checkedRangeEnd(bearingOffset, SHORT_BEARING_SIZE, prepared.vmtx.size) == null) {
                return failure(
                    FontError.OutOfBounds("vmtx trailing topSideBearing is truncated.", tableLocation("vmtx")),
                    FontDiagnosticData(offset = bearingOffset, length = SHORT_BEARING_SIZE),
                )
            }
            VerticalMetrics(
                advanceHeight = advanceHeight,
                topSideBearing = readInt16(prepared.vmtx, bearingOffset.toInt())
                    ?: return failure(FontError.OutOfBounds("vmtx trailing topSideBearing is truncated.", tableLocation("vmtx"))),
            )
        }
        val advanceHeight = scale(metrics.advanceHeight, layoutSize, prepared.unitsPerEm)
            ?: return failure(FontError.GeometryOverflow("advanceHeight could not be represented as a finite LayoutUnit."))
        val topSideBearing = scale(metrics.topSideBearing, layoutSize, prepared.unitsPerEm)
            ?: return failure(FontError.GeometryOverflow("topSideBearing could not be represented as a finite LayoutUnit."))
        return FontOperationResult.Success(VerticalGlyphMetrics(advanceHeight, topSideBearing))
    }

    private fun scale(value: Int, layoutSize: Float, unitsPerEm: Int): LayoutUnit? {
        val scaled = value.toDouble() * layoutSize.toDouble() / unitsPerEm.toDouble()
        val narrowed = scaled.toFloat()
        return if (scaled.isFinite() && narrowed.isFinite()) LayoutUnit(narrowed) else null
    }

    private fun tableLocation(tag: String): FontDiagnosticLocation = FontDiagnosticLocation.Table(tag)

    private fun failure(
        error: FontError,
        diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic()),
    ): FontOperationResult.Failure = FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())

    private fun failure(error: FontError, data: FontDiagnosticData): FontOperationResult.Failure =
        failure(error, listOf(error.toDiagnostic(data)))
}

/** Immutable vertical-metric views read from one SFNT face. */
internal data class PreparedVerticalMetricsData(
    val vmtx: ByteArray,
    val numberOfLongVerMetrics: Int,
    val glyphCount: Int,
    val unitsPerEm: Int,
)

private data class VerticalMetrics(
    val advanceHeight: Int,
    val topSideBearing: Int,
)

private const val NUMBER_OF_LONG_VER_METRICS_OFFSET: Int = 34
private const val LONG_VERTICAL_METRIC_SIZE: Long = 4L
private const val SHORT_BEARING_SIZE: Long = 2L
