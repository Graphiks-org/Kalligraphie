package org.graphiks.kalligraphie.raster

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.assertIs

/** Owns one render asset and its resolver lease; [close] releases the asset, then the resolver lease. */
internal class RasterFixture(
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

/** Opens a fixture; on acquisition failure the resolver lease is released before the error is rethrown. */
internal fun openRasterFixture(
    bytes: ByteArray,
    requirements: FontAccessRequirementsSnapshot,
    renderVariant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
    layoutSize: LayoutUnit = LayoutUnit(2_048f),
): RasterFixture {
    val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
        Kalligraphie.embedded(
            sourceBytes = bytes,
            provenance = FontSourceProvenance(declaredName = "raster conformance fixture"),
        ),
    ).value
    val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(
        catalog.openAssetResolver(),
    ).value
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
        return RasterFixture(instance, asset, resolver)
    } catch (error: Throwable) {
        resolver.close()
        throw error
    }
}

internal fun outlineRequirements(): FontAccessRequirementsSnapshot =
    FontAccessRequirementsSnapshot.renderable(listOf(outlineProfile()))

internal fun outlineProfile(): OutlineProfile = OutlineProfile(
    maxBytes = 1_000_000,
    maxContours = 256,
    maxPoints = 16_384,
    maxCompositeDepth = 16,
    maxCompositeComponents = 256,
)

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

/** The bitmap conformance and demonstration routes share this default profile. */
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

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

/** Locates the repository root by walking up from the test working directory. */
internal fun rasterRepositoryRoot(): Path {
    var candidate: Path? = Path.of("").toAbsolutePath().normalize()
    while (candidate != null) {
        if (Files.exists(candidate.resolve(".git"))) return candidate
        candidate = candidate.parent
    }
    error("Could not locate the repository root from the test working directory.")
}
