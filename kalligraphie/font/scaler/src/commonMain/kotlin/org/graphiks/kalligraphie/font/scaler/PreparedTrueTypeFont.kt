@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.VerticalGlyphMetrics
import org.graphiks.kalligraphie.font.scaler.cff.Cff2Reader
import org.graphiks.kalligraphie.font.scaler.cff.Cff2Table
import org.graphiks.kalligraphie.font.scaler.cff.CffReader
import org.graphiks.kalligraphie.font.scaler.cff.CffTable
import org.graphiks.kalligraphie.font.sfnt.FontFlavor
import org.graphiks.kalligraphie.font.sfnt.FvarReader
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.slice
import org.graphiks.kalligraphie.font.sfnt.variation.HvarData
import org.graphiks.kalligraphie.font.sfnt.variation.HvarReader
import org.graphiks.kalligraphie.font.sfnt.variation.VvarData
import org.graphiks.kalligraphie.font.sfnt.variation.VvarReader
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
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
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

    /** @suppress Creates sibling decoders sharing one private defensive source copy. */
    public companion object {
        /** @suppress Assembly-only factory; no mutable backing escapes. */
        public fun prepareFaces(source: FontSource, faces: List<ParsedTrueTypeFont>): List<PreparedTrueTypeFont> {
            val capturedBytes = source.copyBytes()
            return faces.map { PreparedTrueTypeFont(capturedBytes, it) }
        }
    }

    private val glyphDataCache = AtomicReference<FontOperationResult<PreparedGlyphData>?>(null)

    /**
     * Returns a defensive copy of the immutable source bytes retained for this face.
     *
     * The returned array is caller-owned and can be mutated without changing this shared
     * decoder state. The operation is safe to invoke concurrently.
     */
    public fun copySourceBytes(): ByteArray = sourceBytes.copyOf()

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

    private val verticalMetricsResult: FontOperationResult<PreparedVerticalMetricsData> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        VerticalMetricsReader.prepare(sourceBytes, parsedFont)
    }

    private val cffTableResult: FontOperationResult<CffTable> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val record = parsedFont.tableRecords["CFF "] ?: return@lazy failure(FontError.MissingRequiredTable("CFF "))
        val table = slice(sourceBytes, record)
            ?: return@lazy failure(
                FontError.OutOfBounds("Table CFF exceeds source length.", FontDiagnosticLocation.Table("CFF ")),
            )
        CffTable.read(table, 0)
    }

    private val cff2TableResult: FontOperationResult<Cff2Table> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val record = parsedFont.tableRecords["CFF2"] ?: return@lazy failure(FontError.MissingRequiredTable("CFF2"))
        val table = slice(sourceBytes, record)
            ?: return@lazy failure(
                FontError.OutOfBounds("Table CFF2 exceeds source length.", FontDiagnosticLocation.Table("CFF2")),
            )
        Cff2Table.read(table, 0)
    }

    private val variationAxisTagsResult: FontOperationResult<List<String>> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val record = parsedFont.tableRecords["fvar"] ?: return@lazy FontOperationResult.Success(emptyList())
        val table = slice(sourceBytes, record)
            ?: return@lazy failure(
                FontError.OutOfBounds("Table fvar exceeds source length.", FontDiagnosticLocation.Table("fvar")),
            )
        when (val result = FvarReader.read(table)) {
            is FontOperationResult.Success -> FontOperationResult.Success(result.value.axes.map { it.tag })
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

    private val hvarResult: FontOperationResult<HvarData?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        metricsVariationAxisTags()?.let { axisTags ->
            val record = parsedFont.tableRecords["HVAR"] ?: return@let FontOperationResult.Success(null)
            val table = slice(sourceBytes, record)
                ?: return@let failure(
                    FontError.OutOfBounds("Table HVAR exceeds source length.", FontDiagnosticLocation.Table("HVAR")),
                )
            when (val result = HvarReader.read(table, axisTags.size)) {
                is FontOperationResult.Success -> FontOperationResult.Success(result.value)
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        } ?: FontOperationResult.Success(null)
    }

    private val vvarResult: FontOperationResult<VvarData?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        metricsVariationAxisTags()?.let { axisTags ->
            val record = parsedFont.tableRecords["VVAR"] ?: return@let FontOperationResult.Success(null)
            val table = slice(sourceBytes, record)
                ?: return@let failure(
                    FontError.OutOfBounds("Table VVAR exceeds source length.", FontDiagnosticLocation.Table("VVAR")),
                )
            when (val result = VvarReader.read(table, axisTags.size)) {
                is FontOperationResult.Success -> FontOperationResult.Success(result.value)
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        } ?: FontOperationResult.Success(null)
    }

    /**
     * Returns the face's `fvar` axis tags, or `null` when the face is not variable or its `fvar`
     * cannot be parsed.
     *
     * The metric-variation tables are meaningless without `fvar`, so a variable-table read is only
     * attempted when `fvar` is present and parses. A malformed `fvar` collapses to `null` here
     * (default metrics), matching the face-level metadata philosophy where `variationAxes()`
     * collapses an unparseable `fvar` to an empty list and `normalize()` reports the failure.
     * Cancellation is deliberately not threaded because `by lazy` would memoize a `Cancelled`
     * result permanently, matching the existing CFF2 axis-tag cache.
     */
    private fun metricsVariationAxisTags(): List<String>? {
        if (parsedFont.tableRecords["fvar"] == null) return null
        return when (val result = variationAxisTagsResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> null
            is FontOperationResult.Cancelled -> null
        }
    }

    private fun decodePortableOutline(
        glyphId: GlyphId,
        profile: OutlineProfile,
        cancellationToken: CancellationToken,
        normalizedAxes: List<FontAxisCoordinate> = emptyList(),
    ): FontOperationResult<ScalerGlyphOutline> = when (parsedFont.flavor) {
        FontFlavor.CFF -> {
            val record = parsedFont.tableRecords["CFF "] ?: return failure(FontError.MissingRequiredTable("CFF "))
            val tableBytes = slice(sourceBytes, record)
                ?: return failure(FontError.OutOfBounds("Table CFF exceeds source length.", FontDiagnosticLocation.Table("CFF ")))
            val table = when (val result = cffTableResult) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            CffReader.readGlyphOutline(
                tableBytes, table, glyphId.value, parsedFont.metadata.unitsPerEm, profile, cancellationToken,
            )
        }

        FontFlavor.CFF2 -> {
            val record = parsedFont.tableRecords["CFF2"] ?: return failure(FontError.MissingRequiredTable("CFF2"))
            val tableBytes = slice(sourceBytes, record)
                ?: return failure(FontError.OutOfBounds("Table CFF2 exceeds source length.", FontDiagnosticLocation.Table("CFF2")))
            val table = when (val result = cff2TableResult) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val axisTags = if (normalizedAxes.isEmpty()) {
                emptyList()
            } else {
                when (val result = variationAxisTagsResult) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
            }
            Cff2Reader.readGlyphOutline(
                tableBytes, table, glyphId.value, parsedFont.metadata.unitsPerEm, profile, cancellationToken,
                axisTags = axisTags,
                normalizedAxes = normalizedAxes,
            )
        }

        FontFlavor.TRUETYPE -> FontOperationResult.Failure(
            FontError.UnsupportedRepresentationProfile("TrueType faces do not use the portable CFF outline route."),
        )
    }

    private fun metricsOutlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 64 * 1024 * 1024,
        maxContours = 1_000_000,
        maxPoints = 10_000_000,
        maxCompositeDepth = 32,
        maxCompositeComponents = 1_000_000,
    )

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
     * Resolves one declared Unicode variation sequence without rescanning the face's `cmap` table.
     *
     * @param codePoint base Unicode scalar value.
     * @param variationSelector standard or ideographic variation selector paired with [codePoint].
     * @return the selected variation glyph, a typed malformed-data failure, or glyph zero when
     * the exact pair is not declared by the face.
     */
    public fun resolveGlyph(
        codePoint: Int,
        variationSelector: Int,
    ): FontOperationResult<GlyphResolution> =
        when (val result = cmapResult) {
            is FontOperationResult.Success -> when (val lookup = result.value.resolveGlyphId(codePoint, variationSelector)) {
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
     * @param normalizedAxes instance location in `fvar` axis order; an empty list is the default
     * instance and adds no table parsing.
     * @return metrics scaled to [layoutSize], or a typed range, descriptor, or table-data failure.
     */
    public fun readGlyphMetrics(
        glyphId: GlyphId,
        layoutSize: Float,
        normalizedAxes: List<FontAxisCoordinate> = emptyList(),
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
        val location = metricLocation(normalizedAxes)
        val orderedAxes = location?.orderedAxes
        if (parsedFont.flavor != FontFlavor.TRUETYPE) {
            val outline = when (
                val result = decodePortableOutline(glyphId, metricsOutlineProfile(), CancellationToken.none, normalizedAxes)
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (orderedAxes == null || orderedAxes.all { it == 0.0 }) {
                return MetricsReader.readGlyphMetrics(metrics, outline.bounds, glyphId, layoutSize)
            }
            val hvar = when (val result = hvarResult) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val deltas = hvarDeltas(hvar, glyphId, orderedAxes)
            return MetricsReader.readGlyphMetrics(metrics, outline.bounds, glyphId, layoutSize, deltas)
        }
        val glyphData = when (val result = glyphData(CancellationToken.none)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (orderedAxes == null || orderedAxes.all { it == 0.0 }) {
            return MetricsReader.readGlyphMetrics(metrics, glyphData, glyphId, layoutSize)
        }
        val hvar = when (val result = hvarResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val deltas = when (val result = trueTypeHorizontalDeltas(glyphData, hvar, glyphId, checkNotNull(location))) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return MetricsReader.readGlyphMetrics(metrics, glyphData, glyphId, layoutSize, deltas)
    }

    /** Returns the face's axis tags and the location's coordinates in that order, or `null` for the default. */
    private fun metricLocation(normalizedAxes: List<FontAxisCoordinate>): MetricLocation? {
        if (normalizedAxes.isEmpty()) return null
        val axisTags = metricsVariationAxisTags() ?: return null
        return MetricLocation(axisTags, orderedNormalizedAxes(axisTags, normalizedAxes))
    }

    /** `HVAR` advance and left side-bearing deltas for [metricsGlyphId], zero without `HVAR`. */
    private fun hvarDeltas(
        hvar: HvarData?,
        metricsGlyphId: GlyphId,
        orderedAxes: List<Double>,
    ): MetricVariationDeltas {
        if (hvar == null) return MetricVariationDeltas()
        return MetricVariationDeltas(
            advance = hvar.advanceWidthDelta(metricsGlyphId.value, orderedAxes),
            sideBearing = hvar.leftSideBearingDelta(metricsGlyphId.value, orderedAxes),
        )
    }

    /**
     * Horizontal variation deltas for a TrueType glyph: `HVAR` when present, otherwise the
     * metrics-source glyph's `gvar` phantom-point deltas.
     *
     * The left phantom point is `xMin - lsb` and the right is `xMin - lsb + advanceWidth`, so only
     * the advance is recoverable from the phantom deltas; the side bearing stays at its `hmtx` value
     * in the fallback path.
     */
    private fun trueTypeHorizontalDeltas(
        glyphData: PreparedGlyphData,
        hvar: HvarData?,
        metricsGlyphId: GlyphId,
        location: MetricLocation,
    ): FontOperationResult<MetricVariationDeltas> {
        if (hvar != null) {
            return FontOperationResult.Success(hvarDeltas(hvar, metricsGlyphId, location.orderedAxes))
        }
        val outline = when (
            val result = GlyfReader.readGlyphOutline(
                glyphData,
                metricsGlyphId,
                metricsOutlineProfile(),
                CancellationToken.none,
                axisCoordinates(location),
            )
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return FontOperationResult.Success(
            MetricVariationDeltas(advance = outline.variationPhantoms?.horizontalAdvanceDelta ?: 0.0),
        )
    }

    /**
     * Vertical variation deltas for a TrueType glyph: `VVAR` when present, otherwise the glyph's
     * `gvar` phantom-point deltas (`verticalAdvanceDelta = topY - bottomY`).
     */
    private fun trueTypeVerticalDeltas(
        glyphData: PreparedGlyphData,
        vvar: VvarData?,
        glyphId: GlyphId,
        location: MetricLocation,
    ): FontOperationResult<MetricVariationDeltas> {
        if (vvar != null) {
            return FontOperationResult.Success(
                MetricVariationDeltas(
                    advance = vvar.advanceHeightDelta(glyphId.value, location.orderedAxes),
                    sideBearing = vvar.topSideBearingDelta(glyphId.value, location.orderedAxes),
                ),
            )
        }
        val outline = when (
            val result = GlyfReader.readGlyphOutline(
                glyphData,
                glyphId,
                metricsOutlineProfile(),
                CancellationToken.none,
                axisCoordinates(location),
            )
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return FontOperationResult.Success(
            MetricVariationDeltas(advance = outline.variationPhantoms?.verticalAdvanceDelta ?: 0.0),
        )
    }

    /** Rebuilds a tag-keyed coordinate list from a resolved [MetricLocation] for a `gvar`/CFF2 read. */
    private fun axisCoordinates(location: MetricLocation): List<FontAxisCoordinate> =
        location.axisTags.mapIndexed { index, tag -> FontAxisCoordinate(tag, location.orderedAxes[index].toFloat()) }

    /** The face's `fvar` axis tags and the location's coordinates in that axis order. */
    private data class MetricLocation(
        val axisTags: List<String>,
        val orderedAxes: List<Double>,
    )

    /**
     * Reads one OpenType `vhea`/`vmtx` metric record scaled to [layoutSize].
     *
     * Missing vertical tables, malformed data, and unknown glyph identifiers are reported as
     * typed failures. The cache is immutable and shared across concurrent callers.
     *
     * @param glyphId glyph identifier in the parsed face.
     * @param layoutSize requested positive layout size in the public unit.
     * @param normalizedAxes instance location in `fvar` axis order; an empty list is the default
     * instance and adds no table parsing.
     */
    public fun readVerticalGlyphMetrics(
        glyphId: GlyphId,
        layoutSize: Float,
        normalizedAxes: List<FontAxisCoordinate> = emptyList(),
    ): FontOperationResult<VerticalGlyphMetrics> {
        if (!layoutSize.isFinite()) {
            return failure(FontError.InvalidInstanceDescriptor("layoutSize must be finite."))
        }
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        val metrics = when (val result = verticalMetricsResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val location = metricLocation(normalizedAxes)
        if (location == null || location.orderedAxes.all { it == 0.0 }) {
            return VerticalMetricsReader.readGlyphMetrics(metrics, glyphId, layoutSize)
        }
        val vvar = when (val result = vvarResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val deltas = if (parsedFont.flavor != FontFlavor.TRUETYPE) {
            MetricVariationDeltas(
                advance = vvar?.advanceHeightDelta(glyphId.value, location.orderedAxes) ?: 0.0,
                sideBearing = vvar?.topSideBearingDelta(glyphId.value, location.orderedAxes) ?: 0.0,
            )
        } else {
            val glyphData = when (val result = glyphData(CancellationToken.none)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            when (val result = trueTypeVerticalDeltas(glyphData, vvar, glyphId, location)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
        }
        return VerticalMetricsReader.readGlyphMetrics(metrics, glyphId, layoutSize, deltas)
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
        normalizedAxes: List<FontAxisCoordinate> = emptyList(),
    ): FontOperationResult<ScalerGlyphOutline> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId.value !in 0 until parsedFont.metadata.glyphCount) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        if (parsedFont.flavor != FontFlavor.TRUETYPE) {
            return decodePortableOutline(glyphId, profile, cancellationToken, normalizedAxes)
        }
        val glyphData = when (val result = glyphData(cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return GlyfReader.readGlyphOutline(glyphData, glyphId, profile, cancellationToken, normalizedAxes)
    }
}
