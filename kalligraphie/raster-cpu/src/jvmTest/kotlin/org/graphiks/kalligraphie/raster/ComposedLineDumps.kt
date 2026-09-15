package org.graphiks.kalligraphie.raster

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
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
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
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ParagraphLayoutResult
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion

/**
 * Composes real text lines through the paragraph facade and rasterizes them.
 *
 * The three fixture fonts form one ordered fallback catalog, so a mixed line
 * exercises shaping, BiDi, and multi-face fallback before every positioned
 * glyph is rasterized and flipped onto the canvas.
 */
internal object ComposedLineDumps {
    private const val PIXELS_PER_EM = 48f
    private const val PADDING = 2

    private val FONT_PATHS = listOf(
        "/fonts/liberation/LiberationSans-Regular.ttf",
        "/fonts/amiri/Amiri-Regular.ttf",
        "/fonts/noto-devanagari/NotoSansDevanagari-Regular.ttf",
    )

    /** Renders [text] as a flipped P5 PGM using the ordered fallback catalog. */
    fun line(
        text: String,
        language: String,
        baseDirection: BaseDirection = BaseDirection.LEFT_TO_RIGHT,
        requiredFaces: Int = 0,
    ): Dump = openMultiFaceFixture().use { fixture ->
        val line = layoutLine(fixture, text, language, baseDirection)
        val rendered = renderLine(fixture, line, requiredFaces)
        Dump(
            bytes = rendered,
            note = "composed by the paragraph facade, ${PIXELS_PER_EM.toInt()} pixels per em, flipped vertically",
        )
    }

    private class MultiFaceFixture(
        val catalog: FontCatalogSnapshot,
        val resolver: FontAssetResolverHandle,
        val assets: Map<FontFaceId, FontRenderAssetHandle>,
    ) : AutoCloseable {
        override fun close() {
            try {
                assets.values.forEach { asset -> assertIs<FontOperationResult.Success<Unit>>(asset.close()) }
            } finally {
                assertIs<FontOperationResult.Success<Unit>>(resolver.close())
            }
        }
    }

    private fun openMultiFaceFixture(): MultiFaceFixture {
        val sources = FONT_PATHS.map { path ->
            FontSource(sourceBytes = fixtureBytes(path), provenance = FontSourceProvenance(path))
        }
        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
            Kalligraphie.embedded(sources),
        ).value
        val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
            catalog.openAssetResolver(),
        ).value
        val assets = LinkedHashMap<FontFaceId, FontRenderAssetHandle>()
        try {
            val requirements = FontAccessRequirementsSnapshot.renderable(listOf(outlineProfile()))
            sources.forEach { source ->
                val faceId = FontFaceId(source.id, 0)
                val face = assertIs<FontOperationResult.Success<FontFace>>(
                    catalog.resolveFace(faceId, requirements),
                ).value
                val instance = assertIs<FontOperationResult.Success<FontInstance>>(
                    face.instantiate(FontInstanceDescriptor(LayoutUnit(PIXELS_PER_EM))),
                ).value
                assets[faceId] = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
                    instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements),
                ).value
            }
            return MultiFaceFixture(catalog, resolver, assets)
        } catch (error: Throwable) {
            try {
                assets.values.forEach { asset -> asset.close() }
            } finally {
                resolver.close()
            }
            throw error
        }
    }

    private fun layoutLine(
        fixture: MultiFaceFixture,
        text: String,
        language: String,
        baseDirection: BaseDirection,
    ): LineLayout {
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(outlineProfile()))
        val faces = fixture.assets.keys.toList()
        val policy = FontResolutionPolicySnapshot(
            generation = fixture.catalog.generation,
            policyId = "raster-dump-lines",
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
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(PIXELS_PER_EM)),
            features = emptyList(),
            materialization = EditableLineMaterialization.Renderable(
                fixture.resolver,
                FontRenderVariantSnapshot.default,
                requirements,
            ),
            continuation = null,
            cancellationToken = CancellationToken.none,
            operationProfile = EditorOperationProfile.unbounded,
        )
        val result = assertIs<ParagraphLayoutResult.Success>(JvmEditableParagraphFacade.layout(request))
        check(result.coverageStatus == CoverageStatus.COMPLETE) {
            "line '$text' did not cover its complete source range: ${result.coverageStatus}"
        }
        return result.layout.lines.single()
    }

    private fun renderLine(fixture: MultiFaceFixture, line: LineLayout, requiredFaces: Int): ByteArray {
        class Placed(val image: A8Image, val penX: Int, val baselineY: Int)

        val placed = ArrayList<Placed>()
        val facesUsed = LinkedHashSet<FontFaceId>()
        line.positionedGlyphRuns.forEach { run ->
            val faceId = run.fontInstanceKey.face
            facesUsed += faceId
            val asset = fixture.assets[faceId] ?: error("line used an unexpected face $faceId")
            run.glyphs.forEach { glyph ->
                val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                    asset.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId)),
                ).value
                if (representation !is GlyphRepresentation.Outline) return@forEach
                val image = assertIs<RasterResult.Success<A8Image>>(
                    GlyphRasterizer.rasterizeOutline(
                        representation.outline,
                        OutlineRasterRequest(PIXELS_PER_EM.toDouble()),
                    ),
                ).value
                if (image.width == 0 || image.height == 0) return@forEach
                placed += Placed(
                    image = image,
                    penX = (line.baseline.x.value + glyph.origin.x.value).roundToInt(),
                    baselineY = (line.baseline.y.value + glyph.origin.y.value).roundToInt(),
                )
            }
        }
        check(placed.isNotEmpty()) { "line produced no ink" }
        if (requiredFaces > 0) {
            check(facesUsed.size == requiredFaces) {
                "line used ${facesUsed.size} faces instead of the expected $requiredFaces: $facesUsed"
            }
        }
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
        return canvas.toPgm()
    }
}
