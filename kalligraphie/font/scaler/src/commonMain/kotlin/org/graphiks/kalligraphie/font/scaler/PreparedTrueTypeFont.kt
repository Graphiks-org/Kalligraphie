package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.slice

/**
 * Shared immutable decoding state for one parsed TrueType face.
 *
 * The source bytes are retained privately and the decoded `cmap`, `loca`,
 * `glyf`, `maxp`, `hhea`, and `hmtx` views are initialized at most once. The
 * lazy caches are synchronized, so one prepared face can safely be shared by
 * concurrent layout instances and render handles. A failed preparation is
 * cached as well, which keeps malformed input deterministic and avoids
 * repeating the same table scan.
 */
public class PreparedTrueTypeFont internal constructor(
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
) {
    /** Captures [source] once and shares its immutable bytes across all cached reads. */
    public constructor(source: FontSource, parsedFont: ParsedTrueTypeFont) : this(source.copyBytes(), parsedFont)

    private val cmapResult: FontOperationResult<UnicodeCmapLookup> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val record = parsedFont.tableRecords["cmap"]
            ?: return@lazy failure(FontError.MissingRequiredTable("cmap"))
        val cmapTable = slice(sourceBytes, record)
            ?: return@lazy failure(
                FontError.OutOfBounds("Table cmap exceeds source length.", FontDiagnosticLocation.Table("cmap")),
            )
        CmapReader.readUnicodeCmap(cmapTable, parsedFont.metadata.glyphCount)
    }

    private val glyphDataResult: FontOperationResult<PreparedGlyphData> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GlyfReader.prepare(sourceBytes, parsedFont)
    }

    private val metricsResult: FontOperationResult<PreparedMetricsData> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MetricsReader.prepare(sourceBytes, parsedFont)
    }

    /** Resolves a Unicode scalar without rescanning the face's `cmap` table. */
    public fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> =
        when (val result = cmapResult) {
            is FontOperationResult.Success -> when (val lookup = result.value.resolveGlyphId(codePoint)) {
                is FontOperationResult.Success -> FontOperationResult.Success(
                    GlyphResolution(codePoint, lookup.value.glyphId),
                    lookup.diagnostics,
                )
                is FontOperationResult.Failure -> lookup
                is FontOperationResult.Cancelled -> lookup
            }
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }

    /** Reads glyph metrics using the face-level cached metric and offset tables. */
    public fun readGlyphMetrics(
        glyphId: GlyphId,
        layoutSize: Float,
    ): FontOperationResult<GlyphMetrics> {
        if (!layoutSize.isFinite()) {
            return failure(FontError.InvalidInstanceDescriptor("layoutSize must be finite."))
        }
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        val metrics = when (val result = metricsResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val glyphData = when (val result = glyphDataResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return MetricsReader.readGlyphMetrics(metrics, glyphData, glyphId, layoutSize)
    }

    /** Reads one bounded outline using the face-level cached glyph tables. */
    public fun readGlyphOutline(
        glyphId: GlyphId,
        profile: OutlineProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        val glyphData = when (val result = glyphDataResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return GlyfReader.readGlyphOutline(glyphData, glyphId, profile, cancellationToken)
    }
}
