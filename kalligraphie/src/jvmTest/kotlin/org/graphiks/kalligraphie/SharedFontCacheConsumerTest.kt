package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SharedFontCacheConsumerTest {
    @Test
    fun independentlyCapturedOutlinesAndBitmapsSurviveSharedPressureAndScopeClosure() {
        for (budget in budgets()) {
            val scope = Kalligraphie.fontCacheScope(budget)
            val outline = open("liberation/LiberationSans-Regular.ttf", "Independent outline capture", outlineProfile(), scope)
            val bitmap = open("skia-ebdt-format1/ebdt_fmt1.ttf", "Independent bitmap capture", bitmapProfile(), scope)
            val otherBitmap = open("skia-ebdt-format1/ebdt_fmt1.ttf", "Another bitmap capture", bitmapProfile(), scope)
            val detached = success(outline.asset.detach())
            try {
                repeat(3) {
                    assertOutline(outline.asset)
                    assertBitmap(bitmap.asset)
                    assertBitmap(otherBitmap.asset)
                    success(outline.asset.resolveGlyph(FontGlyphRequest(GlyphId(7))))
                    assertOutline(detached)
                }
                assertIs<FontOperationResult.Cancelled>(
                    detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.cancelled),
                )
                success(outline.asset.close())
                success(outline.resolver.close())
                success(scope.close())
                assertOutline(detached)
                assertBitmap(bitmap.asset)
                val later = open("liberation/LiberationSans-Regular.ttf", "Capture with a closed scope", outlineProfile(), scope)
                try {
                    assertOutline(later.asset)
                    assertOutline(later.asset)
                } finally {
                    later.close()
                }
                success(scope.close())
                assertOutline(detached)
            } finally {
                detached.close()
                outline.close()
                bitmap.close()
                otherBitmap.close()
                scope.close()
            }
        }
    }

    @Test
    fun independentPaintCapturesKeepAuditedPaletteColorsAfterPressureAndOwnerClosure() {
        for (budget in budgets()) {
            val scope = Kalligraphie.fontCacheScope(budget)
            val zero = open("bungee-color/BungeeColor-Regular.ttf", "First Bungee capture", paintProfile(), scope, 0)
            val one = open("bungee-color/BungeeColor-Regular.ttf", "Second Bungee capture", paintProfile(), scope, 1)
            val detached = success(one.asset.detach())
            try {
                repeat(3) {
                    assertPaint(zero.asset, listOf(GlyphColor(201, 9, 0), GlyphColor(255, 149, 128)))
                    assertPaint(one.asset, listOf(GlyphColor(255, 255, 255), GlyphColor(232, 232, 231)))
                }
                one.close()
                success(scope.close())
                assertPaint(detached, listOf(GlyphColor(255, 255, 255), GlyphColor(232, 232, 231)))
                assertPaint(zero.asset, listOf(GlyphColor(201, 9, 0), GlyphColor(255, 149, 128)))
            } finally {
                detached.close()
                zero.close()
                one.close()
                scope.close()
            }
        }
    }

    private fun budgets() = listOf(
        FontCacheBudget(0, 0, 0, 0),
        FontCacheBudget(1, Long.MAX_VALUE, 0, 0),
        FontCacheBudget(8_000, Long.MAX_VALUE, 0, 0),
        FontCacheBudget(1_000_000, 169, 0, 0),
        FontCacheBudget(1_000_000, 0, 0, 0),
    )

    private fun open(
        fixture: String,
        provenance: String,
        profile: GlyphRepresentationProfile,
        scope: FontCacheScope,
        palette: Int = 0,
    ): Opened {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/fonts/$fixture")).use { it.readBytes() }
        val policy = FontMaterializationCachePolicy(1_000_000)
        val catalog = success(if (profile is OutlineProfile) {
            Kalligraphie.embedded(bytes, FontSourceProvenance(provenance), policy, scope)
        } else {
            Kalligraphie.embedded(listOf(FontSource(bytes, FontSourceProvenance(provenance))), policy, scope)
        })
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(profile))
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        return Opened(resolver, success(instance.acquireRenderAsset(
            resolver, if (profile is PaintGraphProfile) FontRenderVariantSnapshot(cpalPaletteIndex = palette)
            else FontRenderVariantSnapshot.default, requirements,
        )))
    }

    private class Opened(val resolver: FontAssetResolverHandle, val asset: FontRenderAssetHandle) {
        fun close() { asset.close(); resolver.close() }
    }

    private fun assertOutline(asset: FontRenderAssetHandle) {
        val outline = assertIs<GlyphRepresentation.Outline>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36))))).outline
        assertEquals(36, outline.glyphId)
        assertEquals(2048, outline.unitsPerEm)
        assertEquals(4, outline.bounds.minX)
        assertEquals(0, outline.bounds.minY)
        assertEquals(1362, outline.bounds.maxX)
        assertEquals(1409, outline.bounds.maxY)
        assertEquals(2, outline.contours.size)
    }

    private fun assertBitmap(asset: FontRenderAssetHandle) {
        val bitmap = assertIs<GlyphRepresentation.Bitmap>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(3))))).bitmap
        assertEquals(BitmapStrike(16, 16), bitmap.strike)
        assertEquals(13, bitmap.width)
        assertEquals(13, bitmap.height)
        assertEquals(0, bitmap.originX)
        assertEquals(13, bitmap.originY)
        assertEquals(12, bitmap.metrics.advanceX)
        assertEquals(BitmapPixelFormat.ALPHA_8, bitmap.pixelFormat)
        assertContentEquals(listOf(
            ".............", "....#####....", "..#########..", ".##########..",
            ".###########.", ".###########.", "############.", ".###########.",
            ".###########.", ".###########.", "..#########..", "...#######...", ".....##......",
        ).flatMap { row -> row.map { if (it == '#') 255.toByte() else 0 } }.toByteArray(), bitmap.copyDecodedPixels())
    }

    private fun assertPaint(asset: FontRenderAssetHandle, colors: List<GlyphColor>) {
        val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(43))))).paint
        val solids = paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>()
        assertEquals(listOf(292, 293), solids.map { it.outline.glyphId })
        assertEquals(colors, solids.map { it.color })
    }

    private fun outlineProfile() = OutlineProfile(maxBytes = 1_000_000, maxContours = 256,
        maxPoints = 16_384, maxCompositeDepth = 8, maxCompositeComponents = 256)

    private fun bitmapProfile() = BitmapProfile(
        strike = BitmapStrike(16, 16), acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(3, 16, 16, 16_384, 16_384, 16, 16, 256, 64, 1_024, 256, 1_024),
    )

    private fun paintProfile() = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(maxNodes = 3, maxReferences = 2, maxDepth = 2, maxSourceBytes = 16_384,
            maxPaths = 2, maxPalettes = 9, maxPaletteEntries = 2, maxColorRecords = 16,
            maxDecodedPaletteBytes = 72, maxBaseGlyphRecords = 288, maxLayerRecords = 576),
        outlineProfile = OutlineProfile(maxBytes = 1_000_000, maxContours = 1_024,
            maxPoints = 65_536, maxCompositeDepth = 16, maxCompositeComponents = 256),
    )

    private fun <T> success(result: FontOperationResult<T>): T = assertIs<FontOperationResult.Success<T>>(result).value
}
