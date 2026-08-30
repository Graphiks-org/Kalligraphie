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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Shared immutable decoding state for one parsed TrueType face.
 *
 * The source bytes are retained privately and the decoded `cmap`, `loca`,
 * `glyf`, `maxp`, `hhea`, and `hmtx` views are initialized at most once.
 * The caches are safe to share, so one prepared face can be used by
 * concurrent layout instances and render handles. A deterministic success or
 * failure is cached; cancellation is returned to the current caller and is
 * never cached, so a later call can retry the cold preparation.
 *
 * The [ParsedTrueTypeFont] must describe the same bytes supplied to the
 * public constructor. The class is an internal decoding owner for the caller:
 * it copies the source once, exposes only immutable result snapshots, and is
 * safe to share between concurrent mapping, metric, and outline operations.
 * It does not close the caller's handles; the owner of a render asset controls
 * the lifetime of this object through its resource lease.
 */
@OptIn(ExperimentalAtomicApi::class)
public class PreparedTrueTypeFont internal constructor(
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
) {
    /**
     * Captures [source] once and shares its immutable bytes across all cached
     * reads.
     *
     * @param source validated or unvalidated source bytes to retain by copy.
     * @param parsedFont metadata and table ranges produced for the same source.
     */
    public constructor(source: FontSource, parsedFont: ParsedTrueTypeFont) : this(source.copyBytes(), parsedFont)

    private val glyphDataCache = AtomicReference<FontOperationResult<PreparedGlyphData>?>(null)

    private val cmapResult: FontOperationResult<UnicodeCmapLookup> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val record = parsedFont.tableRecords["cmap"]
            ?: return@lazy failure(FontError.MissingRequiredTable("cmap"))
        val cmapTable = slice(sourceBytes, record)
            ?: return@lazy failure(
                FontError.OutOfBounds("Table cmap exceeds source length.", FontDiagnosticLocation.Table("cmap")),
            )
        CmapReader.readUnicodeCmap(cmapTable, parsedFont.metadata.glyphCount)
    }

    private val metricsResult: FontOperationResult<PreparedMetricsData> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MetricsReader.prepare(sourceBytes, parsedFont)
    }

    private fun glyphData(cancellationToken: CancellationToken): FontOperationResult<PreparedGlyphData> {
        glyphDataCache.load()?.let { return it }
        val result = GlyfReader.prepare(sourceBytes, parsedFont, cancellationToken)
        if (result !is FontOperationResult.Cancelled) {
            glyphDataCache.compareAndSet(null, result)
        }
        return glyphDataCache.load() ?: result
    }

    /**
     * Resolves a Unicode scalar without rescanning the face's `cmap` table.
     *
     * @param codePoint Unicode scalar value to resolve.
     * @return a glyph resolution, a typed malformed-data failure, or a missing
     * glyph result with its diagnostic.
     */
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

    /**
     * Reads glyph metrics using the face-level cached metric and offset tables.
     *
     * @param glyphId glyph identifier in the parsed face.
     * @param layoutSize requested positive layout size in the public unit.
     * @return metrics scaled to [layoutSize], or a typed range, descriptor, or
     * table-data failure.
     */
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
        val glyphData = when (val result = glyphData(CancellationToken.none)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return MetricsReader.readGlyphMetrics(metrics, glyphData, glyphId, layoutSize)
    }

    /**
     * Reads one bounded outline using the face-level cached glyph tables.
     *
     * Cold preparation observes [cancellationToken] while copying and
     * indexing tables. A cancellation result is never stored in the cache;
     * a later call can therefore retry and publish a complete immutable
     * preparation. The returned outline is in design units and preserves
     * fractional coordinates produced by transforms and implicit points.
     *
     * @param glyphId glyph identifier in the parsed face.
     * @param profile byte, contour, point, and composite limits to enforce.
     * @param cancellationToken cooperative cancellation signal.
     * @return a bounded outline, a typed data or limit failure, or cancellation
     * without partial output.
     */
    public fun readGlyphOutline(
        glyphId: GlyphId,
        profile: OutlineProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        val glyphData = when (val result = glyphData(cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return GlyfReader.readGlyphOutline(glyphData, glyphId, profile, cancellationToken)
    }
}
