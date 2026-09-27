package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus
import kotlin.math.roundToInt
import kotlin.test.assertIs
import org.graphiks.kalligraphie.JvmEditableParagraphFacade
import org.graphiks.kalligraphie.JvmEditableParagraphFacadeRequest
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
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.raster.A8Image
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.OutlineRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult

/**
 * Composes real text lines through the paragraph facade and rasterizes them.
 *
 * The three fixture fonts form one ordered fallback catalog, so a mixed line
 * exercises shaping, BiDi, and multi-face fallback before every positioned
 * glyph is rasterized and flipped onto the canvas. The composed canvas is the
 * canonical golden image: canonicalization never flips again.
 */
internal object ComposedLineScenes {
    private const val PIXELS_PER_EM = 48f
    private const val PADDING = 2

    private val FONT_PATHS = listOf(
        "/fonts/liberation/LiberationSans-Regular.ttf",
        "/fonts/amiri/Amiri-Regular.ttf",
        "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf",
    )

    /**
     * Renders [text] as a composed coverage canvas using the ordered fallback catalog.
     *
     * @param language Unicode and shaping language tag applied to the paragraph.
     * @param baseDirection explicit paragraph base direction.
     * @param requiredFaces asserts that the layout participates with exactly that many distinct faces;
     * `0` disables the check.
     * @param fontPaths the ordered fallback catalog, most preferred first; defaults to the three
     * fixture faces the script scenes share.
     * @param variation design-coordinate instance selection applied to every face of the catalog,
     * or `null` for each face's default instance.
     */
    fun line(
        corpus: FixtureCorpus,
        text: String,
        language: String,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        requiredFaces: Int = 0,
        fontPaths: List<String> = FONT_PATHS,
        variation: FontVariationCoordinates? = null,
    ): GoldenImage = toInkBox(placeLine(corpus, text, language, baseDirection, requiredFaces, fontPaths, variation))

    /**
     * Lays [text] out through the paragraph facade and rasterizes every positioned glyph.
     *
     * Each glyph keeps the coordinates of the single line the facade produced, so a caller can fold
     * them into that line's own ink box through [toInkBox] or place several layouts on one canvas
     * on a shared baseline grid — which is how the weight ladder lines five instances up.
     */
    internal fun placeLine(
        corpus: FixtureCorpus,
        text: String,
        language: String,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        requiredFaces: Int = 0,
        fontPaths: List<String> = FONT_PATHS,
        variation: FontVariationCoordinates? = null,
    ): List<PlacedGlyph> {
        require(text.isNotEmpty()) { "line text must not be empty." }
        require(language.isNotBlank()) { "line language must not be blank." }
        require(fontPaths.isNotEmpty()) { "a composed line needs at least one face." }
        return openMultiFaceFixture(corpus, fontPaths, variation).use { fixture ->
            val line = layoutLine(fixture, text, language, baseDirection, variation)
            placeGlyphs(fixture, line, requiredFaces, text)
        }
    }

    private fun placeGlyphs(fixture: MultiFaceFixture, line: LineLayout, requiredFaces: Int, text: String): List<PlacedGlyph> {
        val placed = ArrayList<PlacedGlyph>()
        val facesUsed = LinkedHashSet<FontFaceId>()
        line.positionedGlyphRuns.forEach { run ->
            val faceId = run.fontInstanceKey.face
            facesUsed += faceId
            val asset = fixture.assets[faceId] ?: error("line used an unexpected face $faceId")
            run.glyphs.forEach glyphLoop@{ glyph ->
                val resolution = when (val outcome = asset.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId))) {
                    is FontOperationResult.Success -> outcome.value
                    is FontOperationResult.Failure -> error(
                        "line '$text' glyph ${glyph.shapedGlyph.glyphId.value} resolution failed: ${outcome.error.code}",
                    )

                    is FontOperationResult.Cancelled -> error(
                        "line '$text' glyph ${glyph.shapedGlyph.glyphId.value} resolution was cancelled",
                    )
                }
                val outline = when (resolution) {
                    is GlyphRepresentation.Outline -> resolution.outline
                    is GlyphRepresentation.Empty -> return@glyphLoop
                    else -> error("line '$text' glyph ${glyph.shapedGlyph.glyphId.value} is not an outline")
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
        }
        check(placed.isNotEmpty()) { "line '$text' produced no ink" }
        if (requiredFaces > 0) {
            check(facesUsed.size == requiredFaces) {
                "line used ${facesUsed.size} faces instead of the expected $requiredFaces: $facesUsed"
            }
        }
        return placed
    }

    /**
     * Draws [placed] on the smallest canvas that holds their ink, with [PADDING] on every side.
     *
     * The canvas is the canonical golden image: the glyphs are flipped while they are drawn, so the
     * image is in image orientation and canonicalization never flips again.
     */
    private fun toInkBox(placed: List<PlacedGlyph>): GoldenImage {
        val minX = placed.minOf { item -> item.penX + item.image.left }
        val minY = placed.minOf { item -> item.baselineY - (item.image.top + item.image.height) }
        val maxX = placed.maxOf { item -> item.penX + item.image.left + item.image.width }
        val maxY = placed.maxOf { item -> item.baselineY - item.image.top }
        val canvas = A8Canvas(maxX - minX + 2 * PADDING, maxY - minY + 2 * PADDING)
        placed.forEach { item ->
            canvas.drawCoverage(
                image = item.image,
                penX = item.penX - minX + PADDING,
                baselineY = item.baselineY - minY + PADDING,
            )
        }
        return canvas.toGoldenImage()
    }

    private class MultiFaceFixture(
        val catalog: FontCatalogSnapshot,
        val resolver: FontAssetResolverHandle,
        val assets: LinkedHashMap<FontFaceId, FontRenderAssetHandle>,
    ) : AutoCloseable {
        override fun close() {
            val results = assets.values.map { asset -> asset.close() }
            try {
                results.forEach { result -> assertIs<FontOperationResult.Success<Unit>>(result) }
            } finally {
                assertIs<FontOperationResult.Success<Unit>>(resolver.close())
            }
        }
    }

    private fun openMultiFaceFixture(
        corpus: FixtureCorpus,
        fontPaths: List<String>,
        variation: FontVariationCoordinates?,
    ): MultiFaceFixture {
        val sources = fontPaths.map { path ->
            FontSource(sourceBytes = corpus.bytes(path), provenance = FontSourceProvenance(path))
        }
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            catalog.openAssetResolver(),
        ).value
        val assets = LinkedHashMap<FontFaceId, FontRenderAssetHandle>()
        try {
            sources.forEach { source ->
                val faceId = FontFaceId(source.id, 0)
                val face = assertIs<FontOperationResult.Success<FontFace>>(
                    catalog.resolveFace(faceId, outlineRequirements()),
                ).value
                val instance = assertIs<FontOperationResult.Success<FontInstance>>(
                    face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(PIXELS_PER_EM), variation = variation)),
                ).value
                assets[faceId] = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
                    instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, outlineRequirements()),
                ).value
            }
            return MultiFaceFixture(catalog, resolver, assets)
        } catch (error: Throwable) {
            assets.values.forEach { asset -> runCatching { asset.close() } }
            resolver.close()
            throw error
        }
    }

    private fun layoutLine(
        fixture: MultiFaceFixture,
        text: String,
        language: String,
        baseDirection: BaseDirection,
        variation: FontVariationCoordinates?,
    ): LineLayout {
        val faces = fixture.assets.keys.toList()
        val policy = FontResolutionPolicySnapshot(
            generation = fixture.catalog.generation,
            policyId = "e2e-composed-lines",
            version = "1",
            candidates = faces.map(::FontResolutionCandidate),
            lastResortFace = faces.last(),
        )
        val snapshot = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot
        val request = JvmEditableParagraphFacadeRequest(
            snapshot = snapshot,
            sourceRange = snapshot.range,
            constraints = HorizontalParagraphConstraints(
                region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(4_096f), LayoutUnit(64f)),
                lineMetrics = LineVerticalMetrics(LayoutUnit(PIXELS_PER_EM), LayoutUnit(12f)),
            ),
            baseDirection = baseDirection,
            language = language,
            fontCatalog = fixture.catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(layoutSize = LayoutUnit(PIXELS_PER_EM), variation = variation),
            features = emptyList(),
            materialization = EditableLineMaterialization.Renderable(
                fixture.resolver,
                FontRenderVariantSnapshot.default,
                outlineRequirements(),
            ),
            continuation = null,
            cancellationToken = CancellationToken.none,
            operationProfile = EditorOperationProfile.unbounded,
        )
        val result = when (val outcome = JvmEditableParagraphFacade.layout(request)) {
            is ParagraphLayoutResult.Success -> outcome
            is ParagraphLayoutResult.Failure -> error(
                "line '$text' failed: ${outcome.error.code}: ${outcome.error.message} " +
                    outcome.diagnostics.joinToString { diagnostic -> diagnostic.code },
            )

            is ParagraphLayoutResult.Cancelled -> error("line '$text' was cancelled")
        }
        check(result.coverageStatus == CoverageStatus.COMPLETE) {
            "line '$text' did not cover its complete source range: ${result.coverageStatus}"
        }
        check(result.layout.lines.size == 1) {
            "line '$text' produced ${result.layout.lines.size} lines"
        }
        return result.layout.lines.single()
    }
}
