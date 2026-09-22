package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile

class SyntheticGeometryRoutePolicyTest {
    @Test
    fun rejectsSyntheticGeometryOnTheColrV0PaintRoute() {
        val catalog = success(Kalligraphie.embedded(colrV0FixtureBytes(), FontSourceProvenance("EmojiTwo COLRv0")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val resolver = success(catalog.openAssetResolver())
        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(
                face.instantiate(
                    FontInstanceDescriptor(
                        layoutSize = LayoutUnit(2_048f),
                        geometry = FontGeometryParameters(syntheticBold = true),
                    ),
                ),
            )

            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot(cpalPaletteIndex = 0), requirements),
            )
            assertEquals("font.geometry.synthetic-unsupported-route", failure.error.code)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsSyntheticGeometryOnTheColourBitmapRoute() {
        val catalog = success(Kalligraphie.embedded(cbdtFixtureBytes(), FontSourceProvenance("Skia CBDT colour fixture")))
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(colourBitmapProfile(16)))
        val resolver = success(catalog.openAssetResolver())
        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(
                face.instantiate(
                    FontInstanceDescriptor(
                        layoutSize = LayoutUnit(16f),
                        geometry = FontGeometryParameters(syntheticItalic = true),
                    ),
                ),
            )

            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )
            assertEquals("font.geometry.synthetic-unsupported-route", failure.error.code)
        } finally {
            resolver.close()
        }
    }

    private fun paintProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 8,
            maxReferences = 6,
            maxDepth = 2,
            maxSourceBytes = 200_000,
            maxPaths = 6,
            maxPalettes = 2,
            maxPaletteEntries = 2_000,
            maxColorRecords = 2_000,
            maxBaseGlyphRecords = 3_000,
            maxLayerRecords = 30_000,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 16,
            maxCompositeComponents = 256,
        ),
    )

    private fun colourBitmapProfile(ppem: Int): BitmapProfile {
        val maxDimension = ppem * 2
        val pixels = maxDimension * maxDimension
        return BitmapProfile(
            strike = BitmapStrike(ppem, ppem, 32),
            acceptedPixelFormats = listOf(BitmapPixelFormat.RGBA_8888),
            acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
            limits = BitmapLimits(
                maxStrikes = 3,
                maxIndexSubtables = 4,
                maxRecordCount = 16,
                maxIndexTableBytes = 16_384,
                maxSourceTableBytes = 65_536,
                maxWidth = maxDimension,
                maxHeight = maxDimension,
                maxPixels = pixels,
                maxCompressedBytes = 65_536,
                maxTotalCompressedBytes = 65_536,
                maxDecodedBytes = pixels * 4,
                maxTotalDecodedBytes = pixels * 4 * 4,
            ),
        )
    }

    private fun colrV0FixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/emoji-two-colr-v0/EmojiTwoCOLRv0.ttf")) {
            "EmojiTwo COLR version 0 fixture is missing"
        }.use { it.readBytes() }

    private fun cbdtFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/skia-cbdt/cbdt.ttf")) {
            "Skia CBDT fixture is missing"
        }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
