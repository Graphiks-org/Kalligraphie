package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.assertIs

/** Owns one render asset and its resolver lease; [close] releases the asset, then the resolver lease. */
internal class E2eFontFixture(
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
    private val resolver: FontAssetResolverHandle,
) : AutoCloseable {
    override fun close() {
        try {
            assertIs<FontOperationResult.Success<Unit>>(asset.close())
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        }
    }
}

/** Opens an outline-capable fixture for [bytes]. */
internal fun openOutlineFixture(bytes: ByteArray, layoutSize: LayoutUnit = LayoutUnit(2_048f)): E2eFontFixture =
    openRenderableFixture(bytes, outlineRequirements(), FontRenderVariantSnapshot.default, layoutSize)

/** Opens a fixture whose asset is acquired under [requirements] and [renderVariant]. */
internal fun openRenderableFixture(
    bytes: ByteArray,
    requirements: FontAccessRequirementsSnapshot,
    renderVariant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
    layoutSize: LayoutUnit = LayoutUnit(2_048f),
): E2eFontFixture {
    val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
        Kalligraphie.embedded(
            sourceBytes = bytes,
            provenance = FontSourceProvenance(declaredName = "e2e golden fixture"),
        ),
    ).value
    val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(catalog.openAssetResolver()).value
    try {
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(catalog.faces.single().id, requirements),
        ).value
        val instance = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize)),
        ).value
        val asset = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
            instance.acquireRenderAsset(resolver, renderVariant, requirements),
        ).value
        return E2eFontFixture(instance, asset, resolver)
    } catch (error: Throwable) {
        resolver.close()
        throw error
    }
}

/** Resolves [codePoint] through the instance, then the render asset. */
internal fun E2eFontFixture.representationOf(codePoint: Int): GlyphRepresentation {
    val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
        instance.resolveGlyph(codePoint),
    ).value.glyphId
    return assertIs<FontOperationResult.Success<GlyphRepresentation>>(
        asset.resolveGlyph(FontGlyphRequest(glyph)),
    ).value
}

/** Resolves the outline of [codePoint] through the instance, then the render asset. */
internal fun E2eFontFixture.outlineOf(codePoint: Int): GlyphOutlineIR =
    assertIs<GlyphRepresentation.Outline>(representationOf(codePoint)).outline

/** Resolves the paint graph of [codePoint] through the instance, then the render asset. */
internal fun E2eFontFixture.paintOf(codePoint: Int): GlyphPaintIR =
    assertIs<GlyphRepresentation.Paint>(representationOf(codePoint)).paint

/** Resolves the bitmap of [codePoint] through the instance, then the render asset. */
internal fun E2eFontFixture.bitmapOf(codePoint: Int): BitmapGlyphIR =
    assertIs<GlyphRepresentation.Bitmap>(representationOf(codePoint)).bitmap

internal fun outlineRequirements(): FontAccessRequirementsSnapshot =
    FontAccessRequirementsSnapshot.renderable(listOf(outlineProfile()))

internal fun outlineProfile(): OutlineProfile = OutlineProfile(
    maxBytes = 1_000_000,
    maxContours = 256,
    maxPoints = 16_384,
    maxCompositeDepth = 16,
    maxCompositeComponents = 256,
)

internal fun paintRequirements(): FontAccessRequirementsSnapshot =
    FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))

internal fun paintProfile(): PaintGraphProfile = PaintGraphProfile(
    acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
    acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
    limits = PaintGraphLimits(
        maxNodes = 8,
        maxReferences = 6,
        maxDepth = 2,
        maxSourceBytes = 200_000,
        maxPaths = 6,
        maxPalettes = 9,
        maxPaletteEntries = 2_000,
        maxColorRecords = 2_000,
        maxBaseGlyphRecords = 3_000,
        maxLayerRecords = 30_000,
    ),
    outlineProfile = outlineProfile(),
)

internal fun bitmapRequirements(): FontAccessRequirementsSnapshot =
    FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile()))

internal fun bitmapProfile(): BitmapProfile = BitmapProfile(
    strike = BitmapStrike(16, 16, 1),
    acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
    acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
    limits = BitmapLimits(
        maxStrikes = 3,
        maxIndexSubtables = 16,
        maxRecordCount = 16,
        maxIndexTableBytes = 16_384,
        maxSourceTableBytes = 16_384,
        maxWidth = 16,
        maxHeight = 16,
        maxPixels = 256,
        maxCompressedBytes = 64,
        maxTotalCompressedBytes = 1_024,
        maxDecodedBytes = 256,
        maxTotalDecodedBytes = 1_024,
    ),
)

internal fun fixtureBytes(path: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream(path)) { "fixture resource $path is missing" }
        .use { input -> input.readBytes() }
