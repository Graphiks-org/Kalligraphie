package org.graphiks.kalligraphie.e2e.golden

import kotlin.math.roundToInt
import kotlin.test.assertIs
import org.graphiks.kalligraphie.EditableParagraphFacade
import org.graphiks.kalligraphie.EditableParagraphFacadeRequest
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CoverageStatus
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.ParagraphStyleSnapshot
import org.graphiks.kalligraphie.api.ParagraphStyleSpan
import org.graphiks.kalligraphie.api.PositionedGlyphRun
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.catalog.AMIRI
import org.graphiks.kalligraphie.e2e.catalog.LIBERATION_SANS
import org.graphiks.kalligraphie.e2e.catalog.WORK_SANS
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus
import org.graphiks.kalligraphie.raster.A8Image
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult

/**
 * Composes one paragraph whose spans keep their own face and their own variation instance.
 *
 * The scene answers the question no single-face line can: *does a style carried by a span really
 * change what is drawn, while the rest of the paragraph keeps another face?* The resolution policy
 * has three candidates — Work Sans, Liberation Sans, Amiri, the last one declared as the last
 * resort — and every Latin scalar of the text is mapped by both Latin families. A middle candidate
 * could therefore only win if a span promoted it, which is exactly what the scene asserts.
 *
 * Three premises are asserted rather than assumed, because a scene that stopped honouring spans
 * would still produce a plausible image:
 *
 * * the unprefixed word keeps Work Sans while the span-preferred word really draws Liberation,
 * * the two Work Sans runs (`wght` 400 and the span's `wght` 700) carry different instance keys
 *   *and* different ink, the same word drawn twice so only the weight can explain the difference,
 * * the Arabic fragment resolves to Amiri, which only coverage can select because it is never
 *   promoted as a preference.
 *
 * The canvas is cropped to the ink of every run at once, on the same rule the composed lines use.
 * One render asset is opened per exact [FontInstanceKey] and each run is rasterized with its own
 * instance, so two runs of the same family at two weights cannot share one asset.
 */
internal object PerSpanStyleScene {
    private const val PIXELS_PER_EM = 48f
    private const val PADDING = 2

    /**
     * The paragraph, in logical order, chosen so every span is one word and two words repeat.
     *
     * `Wide` (unprefixed, Work Sans 400), `Libre` (face span, Liberation), `Wide` (variation span,
     * Work Sans 700), `عربي` (Arabic, Amiri last resort).
     */
    private const val TEXT = "Wide Libre Wide عربي"

    /** Scalar offset of the unprefixed Work Sans word. */
    private const val DEFAULT_WORK_START = 0

    /** Scalar offset of the face-preferred Liberation word. */
    private const val PREFERRED_LIBERATION_START = 5

    /** Scalar offsets of the face-preferred Liberation word, half-open. */
    private const val PREFERRED_LIBERATION_END = 10

    /** Scalar offset of the variation-styled Work Sans word. */
    private const val BOLD_WORK_START = 11

    /** Scalar offsets of the variation-styled Work Sans word, half-open. */
    private const val BOLD_WORK_END = 15

    /** Scalar offset of the Arabic fragment. */
    private const val ARABIC_START = 16

    /** Renders the per-span paragraph: one line, four styled runs, one coverage canvas. */
    fun scene(corpus: FixtureCorpus): GoldenImage = openFixture(corpus).use { fixture ->
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(TEXT.toCharArray())),
        ).snapshot
        val spans = ParagraphStyleSnapshot(
            listOf(
                ParagraphStyleSpan(
                    range = range(snapshot, PREFERRED_LIBERATION_START, PREFERRED_LIBERATION_END),
                    face = fixture.liberationFace,
                ),
                ParagraphStyleSpan(
                    range = range(snapshot, BOLD_WORK_START, BOLD_WORK_END),
                    variation = FontVariationCoordinates(listOf(FontVariationCoordinate(tag = "wght", value = 700f))),
                ),
            ),
        )
        val runs = layout(fixture, snapshot, spans)
        val assets = runs.map { run ->
            fixture.assets[run.fontInstanceKey]
                ?: error("the paragraph shaped an instance no span expected: ${run.fontInstanceKey}")
        }
        val placedByRun = runs.mapIndexed { index, run -> rasterize(run, assets[index]) }
        val coverageByRun = placedByRun.map(::coverageOf)

        val defaultIndex = runs.indexCovering(snapshot.textIndexAtScalarBoundary(DEFAULT_WORK_START))
        val liberationIndex = runs.indexCovering(snapshot.textIndexAtScalarBoundary(PREFERRED_LIBERATION_START))
        val boldIndex = runs.indexCovering(snapshot.textIndexAtScalarBoundary(BOLD_WORK_START))
        val arabicIndex = runs.indexCovering(snapshot.textIndexAtScalarBoundary(ARABIC_START))

        assertFace("the unprefixed word", runs[defaultIndex], fixture.workSansFace)
        assertFace("the face-preferred word", runs[liberationIndex], fixture.liberationFace)
        assertFace("the variation-styled word", runs[boldIndex], fixture.workSansFace)
        assertFace("the Arabic fragment", runs[arabicIndex], fixture.amiriFace)

        val facesUsed = runs.map { run -> run.fontInstanceKey.face }.toSet()
        check(facesUsed == setOf(fixture.workSansFace, fixture.liberationFace, fixture.amiriFace)) {
            "the paragraph drew $facesUsed instead of all three policy faces: the fallback path changed"
        }
        check(runs[defaultIndex].fontInstanceKey != runs[boldIndex].fontInstanceKey) {
            "the two Work Sans runs share one instance key: the span variation did not reach the instance"
        }
        check(runs[defaultIndex].fontInstanceKey.geometry.normalizedAxes.isEmpty()) {
            "the unprefixed Work Sans run carries a variation it never asked for"
        }
        check(runs[boldIndex].fontInstanceKey.geometry.normalizedAxes.isNotEmpty()) {
            "the variation-styled Work Sans run carries no variation axis"
        }
        check(coverageByRun[boldIndex] > coverageByRun[defaultIndex]) {
            "the same word carries ${coverageByRun[boldIndex]} coverage at wght 700 and " +
                "${coverageByRun[defaultIndex]} at wght 400: the variation did not reach the outlines"
        }

        toInkBox(placedByRun.flatten())
    }

    /** Asserts that [run] resolves to [expected] rather than merely differing from its neighbours. */
    private fun assertFace(what: String, run: PositionedGlyphRun, expected: FontFaceId) {
        check(run.fontInstanceKey.face == expected) {
            "$what resolved to ${run.fontInstanceKey.face} instead of $expected"
        }
    }

    /** Returns the single run whose source range covers [index]. */
    private fun List<PositionedGlyphRun>.indexCovering(index: TextIndex): Int {
        val matches = indices.filter { position ->
            val range = this[position].sourceRun.range
            range.start <= index && index < range.endExclusive
        }
        check(matches.size == 1) { "expected exactly one run to cover $index, found ${matches.size}" }
        return matches.single()
    }

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int) =
        TextRange(
            snapshot.textIndexAtScalarBoundary(start),
            snapshot.textIndexAtScalarBoundary(endExclusive),
        )

    /** Lays the paragraph out and returns its single final line's positioned runs. */
    private fun layout(
        fixture: Fixture,
        snapshot: TextSnapshot,
        spans: ParagraphStyleSnapshot,
    ): List<PositionedGlyphRun> {
        val request = EditableParagraphFacadeRequest(
            snapshot = snapshot,
            sourceRange = snapshot.range,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(4_096f), LayoutUnit(64f)),
                lineMetrics = LineVerticalMetrics(LayoutUnit(PIXELS_PER_EM), LayoutUnit(12f)),
            ),
            baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en",
            fontCatalog = fixture.catalog,
            resolutionPolicy = fixture.policy,
            fontInstanceDescriptor = FontInstanceDescriptor(layoutSize = LayoutUnit(PIXELS_PER_EM)),
            features = emptyList(),
            materialization = EditableLineMaterialization.LayoutOnly,
            continuation = null,
            cancellationToken = CancellationToken.none,
            operationProfile = EditorOperationProfile.unbounded,
            styleSpans = spans,
        )
        val result = when (val outcome = EditableParagraphFacade.layout(request)) {
            is ParagraphLayoutResult.Success -> outcome
            is ParagraphLayoutResult.Failure -> error(
                "the per-span paragraph failed: ${outcome.error.code}: ${outcome.error.message}",
            )

            is ParagraphLayoutResult.Cancelled -> error("the per-span paragraph was cancelled")
        }
        check(result.coverageStatus == CoverageStatus.COMPLETE) {
            "the per-span paragraph did not cover its complete source range: ${result.coverageStatus}"
        }
        check(result.layout.lines.size == 1) {
            "the per-span paragraph produced ${result.layout.lines.size} lines instead of one"
        }
        return result.layout.lines.single().positionedGlyphRuns
    }

    /**
     * Rasterizes every glyph of [run] with [asset], the render asset of that run's exact instance.
     *
     * The asset is not looked up by face: two runs of one family at two weights are two instances,
     * and drawing them through one asset would erase the variation the scene exists to prove.
     */
    private fun rasterize(run: PositionedGlyphRun, asset: FontRenderAssetHandle): List<PlacedGlyph> {
        val placed = ArrayList<PlacedGlyph>()
        run.glyphs.forEach glyphLoop@{ glyph ->
            val resolution = when (val outcome = asset.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId))) {
                is FontOperationResult.Success -> outcome.value
                is FontOperationResult.Failure -> error(
                    "glyph ${glyph.shapedGlyph.glyphId.value} resolution failed: ${outcome.error.code}",
                )

                is FontOperationResult.Cancelled -> error(
                    "glyph ${glyph.shapedGlyph.glyphId.value} resolution was cancelled",
                )
            }
            val outline = when (resolution) {
                is GlyphRepresentation.Outline -> resolution.outline
                is GlyphRepresentation.Empty -> return@glyphLoop
                else -> error("glyph ${glyph.shapedGlyph.glyphId.value} is not an outline")
            }
            val image = assertIs<RasterResult.Success<A8Image>>(
                GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(PIXELS_PER_EM.toDouble())),
            ).value
            if (image.width == 0 || image.height == 0) return@glyphLoop
            placed += PlacedGlyph(
                image = image,
                penX = glyph.origin.x.value.roundToInt(),
                baselineY = glyph.origin.y.value.roundToInt(),
            )
        }
        return placed
    }

    /** Draws [placed] on the smallest canvas that holds their real ink, with [PADDING] around it. */
    private fun toInkBox(placed: List<PlacedGlyph>): GoldenImage {
        check(placed.isNotEmpty()) { "the per-span paragraph produced no ink" }
        // The framing measures real ink, not the raster bounds: a glyph whose raster carries a
        // transparent bearing would otherwise leave more than [PADDING] at the edge, and the
        // materializer's auto-sized reframe refuses a negative offset.
        val ink = placed.inkInCanvas() ?: error("the per-span paragraph produced no ink to lay out")
        val canvas = A8Canvas(ink.width + 2 * PADDING, ink.height + 2 * PADDING)
        placed.forEach { item ->
            canvas.drawCoverage(
                image = item.image,
                penX = item.penX - ink.minX + PADDING,
                baselineY = item.baselineY - ink.minY + PADDING,
            )
        }
        return canvas.toGoldenImage()
    }

    /** Sums the coverage of one run, the ink the two Work Sans runs compare. */
    private fun coverageOf(placed: List<PlacedGlyph>): Long =
        placed.sumOf { glyph -> glyph.image.copyPixels().sumOf { sample -> (sample.toInt() and 0xFF).toLong() } }

    /** The corpus families, the resolver and the exact instance assets the scene draws through. */
    private class Fixture(
        val catalog: FontCatalogSnapshot,
        val policy: FontResolutionPolicySnapshot,
        val workSansFace: FontFaceId,
        val liberationFace: FontFaceId,
        val amiriFace: FontFaceId,
        val assets: Map<FontInstanceKey, FontRenderAssetHandle>,
        private val resolver: FontAssetResolverHandle,
    ) : AutoCloseable {
        override fun close() {
            try {
                assets.values.forEach { asset -> assertIs<FontOperationResult.Success<Unit>>(asset.close()) }
            } finally {
                assertIs<FontOperationResult.Success<Unit>>(resolver.close())
            }
        }
    }

    /** Opens the three-face catalog and acquires one render asset per expected instance key. */
    private fun openFixture(corpus: FixtureCorpus): Fixture {
        val paths = listOf(WORK_SANS, LIBERATION_SANS, AMIRI)
        val sources = paths.map { path -> FontSource(corpus.bytes(path), FontSourceProvenance(path)) }
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            catalog.openAssetResolver(),
        ).value
        val assets = LinkedHashMap<FontInstanceKey, FontRenderAssetHandle>()
        try {
            val faces = sources.map { source ->
                assertIs<FontOperationResult.Success<FontFace>>(
                    catalog.resolveFace(FontFaceId(source.id, 0), outlineRequirements()),
                ).value
            }
            val bold = FontVariationCoordinates(listOf(FontVariationCoordinate(tag = "wght", value = 700f)))
            val descriptors = listOf(
                faces[0] to FontInstanceDescriptor(layoutSize = LayoutUnit(PIXELS_PER_EM)),
                faces[0] to FontInstanceDescriptor(layoutSize = LayoutUnit(PIXELS_PER_EM), variation = bold),
                faces[1] to FontInstanceDescriptor(layoutSize = LayoutUnit(PIXELS_PER_EM)),
                faces[2] to FontInstanceDescriptor(layoutSize = LayoutUnit(PIXELS_PER_EM)),
            )
            for ((face, descriptor) in descriptors) {
                val instance = assertIs<FontOperationResult.Success<FontInstance>>(
                    face.instantiate(descriptor),
                ).value
                assets[instance.key] = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
                    instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, outlineRequirements()),
                ).value
            }
            val faceIds = sources.map { source -> FontFaceId(source.id, 0) }
            val policy = FontResolutionPolicySnapshot(
                generation = catalog.generation,
                policyId = "e2e-per-span-style",
                version = "1",
                candidates = faceIds.map(::FontResolutionCandidate),
                lastResortFace = faceIds.last(),
            )
            return Fixture(
                catalog = catalog,
                policy = policy,
                workSansFace = faceIds[0],
                liberationFace = faceIds[1],
                amiriFace = faceIds[2],
                assets = assets,
                resolver = resolver,
            )
        } catch (error: Throwable) {
            assets.values.forEach { asset -> runCatching { asset.close() } }
            resolver.close()
            throw error
        }
    }
}
