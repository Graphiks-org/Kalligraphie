@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontMetrics
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
import org.graphiks.kalligraphie.font.sfnt.variation.MvarData
import org.graphiks.kalligraphie.font.sfnt.variation.MvarReader
import org.graphiks.kalligraphie.font.sfnt.variation.VvarData
import org.graphiks.kalligraphie.font.sfnt.variation.VvarReader
import org.graphiks.kalligraphie.font.sfnt.variationFailure
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

        /**
         * Upper bound on the total number of `(glyphId, orderedAxes)` instanced-bound pairs retained
         * per face.
         *
         * A total-per-face bound rather than a per-glyph count: 512 keeps the retained [DesignBounds]
         * bounded on a long-lived shared face while staying large enough that the paragraph projection
         * path's rereads of one line's glyphs at one location stay hot. A face read at many instances
         * (or a large CJK face at one instance) can exceed it, which only causes recomputation — see
         * `InstancedInkBoundsCache` for the eviction policy.
         */
        private const val INSTANCED_INK_BOUNDS_CAPACITY = 512
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

    private val mvarResult: FontOperationResult<MvarData?> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        metricsVariationAxisTags()?.let { axisTags ->
            val record = parsedFont.tableRecords["MVAR"] ?: return@let FontOperationResult.Success(null)
            val table = slice(sourceBytes, record)
                ?: return@let failure(
                    FontError.OutOfBounds("Table MVAR exceeds source length.", FontDiagnosticLocation.Table("MVAR")),
                )
            when (val result = MvarReader.read(table, axisTags.size)) {
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
     * collapses an unparseable `fvar` to an empty list and `normalize()` reports the failure. This
     * deliberately diverges from the outline route, where `decodePortableOutline` and
     * `GlyfReader.prepareGvar` return a typed `fvar` failure for the same face: a non-default metric
     * read on a malformed-`fvar` face silently returns the base metrics instead of failing. This
     * asymmetry is accepted rather than propagated here. Cancellation is deliberately not threaded
     * because `by lazy` would memoize a `Cancelled` result permanently, matching the existing CFF2
     * axis-tag cache.
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
        val metricsGlyphId = when (val result = GlyfReader.horizontalMetricsGlyphId(glyphData, glyphId)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val deltas = when (val result = trueTypeHorizontalDeltas(glyphData, hvar, metricsGlyphId, checkNotNull(location))) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val bounds = when (val result = instancedInkBounds(glyphData, glyphId, normalizedAxes, location)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return MetricsReader.readGlyphMetrics(metrics, bounds, metricsGlyphId, layoutSize, deltas)
    }

    private val instancedInkBoundsCache = InstancedInkBoundsCache(capacity = INSTANCED_INK_BOUNDS_CAPACITY)

    /**
     * Returns the requested glyph's instanced ink bounds on the TrueType route.
     *
     * The bounds come from the varied outline points (`GlyfReader.readGlyphOutline`), which are the
     * same points the render route certifies; `gvar` phantom points are carried separately and are
     * never part of the ink bbox, matching the static `glyf` header semantics. The value is memoized
     * per `(glyphId, orderedAxes)` because the paragraph projection path reads glyph metrics once per
     * glyph per candidate line; the cache is bounded and holds only immutable [DesignBounds].
     *
     * The metric route asks for these bounds on every non-default read, so an advance-only caller pays
     * one outline decode per unique `(glyphId, ordered normalized location)` pair on a cache miss: the
     * memo amortizes repeated glyphs at one location but does not make the first read of each pair free.
     *
     * Feeding these bounds into the metric route is what makes a non-default `metrics()` materialize
     * the outline under [metricsOutlineProfile]; that read can now fail for a glyph whose outline
     * exceeds the profile even when its `glyf` header is readable. This is the accepted trade-off of
     * replacing the static header bbox on the varied path.
     */
    private fun instancedInkBounds(
        glyphData: PreparedGlyphData,
        glyphId: GlyphId,
        normalizedAxes: List<FontAxisCoordinate>,
        location: MetricLocation,
    ): FontOperationResult<DesignBounds> {
        val cacheKey = InstancedInkBoundsKey(glyphId.value, location.orderedAxes)
        instancedInkBoundsCache.get(cacheKey)?.let {
            return FontOperationResult.Success(it)
        }
        val outline = when (
            val result = GlyfReader.readGlyphOutline(
                glyphData,
                glyphId,
                metricsOutlineProfile(),
                CancellationToken.none,
                normalizedAxes,
            )
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        instancedInkBoundsCache.put(cacheKey, outline.bounds)
        return FontOperationResult.Success(outline.bounds)
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

    /** `VVAR` advance-height and top side-bearing deltas for [metricsGlyphId], zero without `VVAR`. */
    private fun vvarDeltas(
        vvar: VvarData?,
        metricsGlyphId: GlyphId,
        orderedAxes: List<Double>,
    ): MetricVariationDeltas {
        if (vvar == null) return MetricVariationDeltas()
        return MetricVariationDeltas(
            advance = vvar.advanceHeightDelta(metricsGlyphId.value, orderedAxes),
            sideBearing = vvar.topSideBearingDelta(metricsGlyphId.value, orderedAxes),
        )
    }

    /**
     * Horizontal variation deltas for a TrueType glyph: `HVAR` when present, otherwise the
     * metrics-source glyph's `gvar` phantom-point deltas.
     *
     * [metricsGlyphId] is the glyph returned by [GlyfReader.horizontalMetricsGlyphId], i.e. the
     * component that supplies a composite's `hmtx` base when its `USE_MY_METRICS` flag is set. Both
     * the `HVAR` row and the phantom fallback are read for that same glyph, so a redirected
     * composite never mixes a component's base advance with its own variation delta.
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
    ): FontOperationResult<MetricVariationDeltas> = trueTypeVariationDeltas(
        glyphData = glyphData,
        metricsGlyphId = metricsGlyphId,
        location = location,
        variationTableDeltas = hvar?.let { hvarDeltas(it, metricsGlyphId, location.orderedAxes) },
        phantomAdvanceDelta = { it.horizontalAdvanceDelta },
    )

    /**
     * Vertical variation deltas for a TrueType glyph: `VVAR` when present, otherwise the glyph's
     * `gvar` phantom-point deltas (`verticalAdvanceDelta = topY - bottomY`).
     *
     * `USE_MY_METRICS` is horizontal-only, and no vertical metrics-glyph redirect exists in this
     * codebase ([GlyfReader] exposes only `horizontalMetricsGlyphId`), so [metricsGlyphId] is the
     * requested glyph on this path and no composite redirect is applied.
     */
    private fun trueTypeVerticalDeltas(
        glyphData: PreparedGlyphData,
        vvar: VvarData?,
        metricsGlyphId: GlyphId,
        location: MetricLocation,
    ): FontOperationResult<MetricVariationDeltas> = trueTypeVariationDeltas(
        glyphData = glyphData,
        metricsGlyphId = metricsGlyphId,
        location = location,
        variationTableDeltas = vvar?.let { vvarDeltas(it, metricsGlyphId, location.orderedAxes) },
        phantomAdvanceDelta = { it.verticalAdvanceDelta },
    )

    /**
     * Resolves a TrueType glyph's variation advance: [variationTableDeltas] when the `HVAR`/`VVAR`
     * table supplied them, otherwise [phantomAdvanceDelta] applied to the glyph's `gvar` phantom
     * points. A malformed `gvar` fails the varied metric read, while a glyph with no `gvar` entry
     * yields a zero advance delta.
     */
    private fun trueTypeVariationDeltas(
        glyphData: PreparedGlyphData,
        metricsGlyphId: GlyphId,
        location: MetricLocation,
        variationTableDeltas: MetricVariationDeltas?,
        phantomAdvanceDelta: (GlyphVariationPhantoms) -> Double,
    ): FontOperationResult<MetricVariationDeltas> {
        if (variationTableDeltas != null) return FontOperationResult.Success(variationTableDeltas)
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
            MetricVariationDeltas(advance = outline.variationPhantoms?.let(phantomAdvanceDelta) ?: 0.0),
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
            vvarDeltas(vvar, glyphId, location.orderedAxes)
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
     * Orders the instance's tag-keyed [normalizedAxes] into the face's `fvar` axis order.
     *
     * Returns an empty list for an omitted location (an empty [normalizedAxes]) and for a face
     * without a usable `fvar`, so that path never reads `fvar`. An explicitly selected default
     * design location on a variable face does read `fvar` here and returns a list of zeros; the
     * COLR write path then treats an all-zero list as no variation and parses no variation store.
     * A malformed `fvar` on a non-empty selection propagates its typed failure. This is the shared
     * `fvar`-order mapping already used by the `gvar`, CFF2 and metric routes, exposed to the COLR
     * consumer, which lives in a module that cannot see `orderedNormalizedAxes`.
     */
    public fun orderedVariationAxes(normalizedAxes: List<FontAxisCoordinate>): FontOperationResult<List<Double>> {
        if (normalizedAxes.isEmpty()) return FontOperationResult.Success(emptyList())
        val axisTags = when (val result = variationAxisTagsResult) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (axisTags.isEmpty()) return FontOperationResult.Success(emptyList())
        return FontOperationResult.Success(orderedNormalizedAxes(axisTags, normalizedAxes))
    }

    /**
     * Reads the instance's font-wide metrics in design units.
     *
     * The defaults come from `OS/2` (falling back to `hhea` for the vertical extents and `post` for
     * the underline) and the `MVAR` deltas are applied afterwards. An empty [normalizedAxes] list is
     * the default instance and adds no `MVAR` parsing.
     *
     * @param normalizedAxes instance location in `fvar` axis order.
     * @return font-wide metrics, or a typed table failure.
     */
    public fun readFontMetrics(
        normalizedAxes: List<FontAxisCoordinate> = emptyList(),
    ): FontOperationResult<FontMetrics> {
        val orderedAxes = if (normalizedAxes.isEmpty()) {
            emptyList()
        } else {
            metricLocation(normalizedAxes)?.orderedAxes ?: emptyList()
        }
        val mvar = if (orderedAxes.isEmpty() || orderedAxes.all { it == 0.0 }) {
            null
        } else {
            when (val result = mvarResult) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
        }
        return FontMetricsReader.read(sourceBytes, parsedFont, mvar, orderedAxes)
    }

    /**
     * Whether [normalizedAxes] selects a non-default instance.
     *
     * An empty location and an all-zero location both name the default instance, whose static
     * composite (`glyf` or CFF2) coincides with `VARC`'s default, so neither consults `VARC`.
     */
    private fun usesNonDefaultVariationLocation(normalizedAxes: List<FontAxisCoordinate>): Boolean =
        normalizedAxes.any { it.value != 0f }

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
        if (parsedFont.hasVarcTable && usesNonDefaultVariationLocation(normalizedAxes)) {
            return variationFailure(
                code = "font.variation.varc-unsupported",
                message = "The VARC variable-composite table is not supported; a non-default instance would render the static composite.",
                tag = "VARC",
            )
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

/** Immutable memo key: one `(glyphId, ordered normalized location)` pair. */
private data class InstancedInkBoundsKey(
    val glyphId: Int,
    val orderedAxes: List<Double>,
)

/**
 * Bounded copy-on-write memo for instanced ink bounds.
 *
 * `commonMain` has no `synchronized` and no access-ordered `LinkedHashMap` constructor, so this is an
 * [AtomicReference] over an immutable insertion-ordered [LinkedHashMap] snapshot (the same
 * `kotlin.concurrent.atomics` idiom as `PreparedTrueTypeFont.glyphDataCache`). Each [put] publishes a
 * fresh snapshot and evicts the eldest entry once the total number of stored `(glyphId, orderedAxes)`
 * pairs exceeds [capacity]; keying on the pair means one glyph at many locations still counts.
 *
 * The eviction is insertion-ordered, not access-ordered, so a [get] never refreshes an entry: a pair
 * touched on every line is still retired once [capacity] newer pairs have been inserted. That is a
 * real thrash risk for a face read at many instances (or a large CJK face at a single instance), where
 * the working set of `(glyphId, location)` pairs exceeds the cap and pairs are recomputed after every
 * eviction. It is performance-only: an evicted pair is recomputed to the same [DesignBounds] on the
 * next read, and the bound is what keeps a long-lived shared face from retaining an unbounded number
 * of decoded bounds whatever the glyph and location mix.
 */
@OptIn(ExperimentalAtomicApi::class)
private class InstancedInkBoundsCache(private val capacity: Int) {
    private val entries = AtomicReference<Map<InstancedInkBoundsKey, DesignBounds>>(emptyMap())

    fun get(key: InstancedInkBoundsKey): DesignBounds? = entries.load()[key]

    fun put(key: InstancedInkBoundsKey, bounds: DesignBounds) {
        while (true) {
            val current = entries.load()
            val next = LinkedHashMap(current)
            next.remove(key)
            next[key] = bounds
            while (next.size > capacity) {
                val eldest = next.entries.iterator()
                if (eldest.hasNext()) { eldest.next(); eldest.remove() } else break
            }
            if (entries.compareAndSet(current, next)) return
        }
    }
}
