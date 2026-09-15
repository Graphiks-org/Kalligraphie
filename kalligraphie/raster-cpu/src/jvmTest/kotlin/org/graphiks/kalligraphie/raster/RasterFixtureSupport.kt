package org.graphiks.kalligraphie.raster

import java.security.MessageDigest
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.assertIs

/** Owns one render asset and its resolver lease; [close] releases the asset, then the resolver lease. */
internal class RasterFixture(
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
    private val resolver: FontAssetResolverHandle,
) : AutoCloseable {
    override fun close() {
        assertIs<FontOperationResult.Success<Unit>>(asset.close())
        assertIs<FontOperationResult.Success<Unit>>(resolver.close())
    }
}

/** Opens a fixture; on acquisition failure the resolver lease is released before the error is rethrown. */
internal fun openRasterFixture(
    bytes: ByteArray,
    requirements: FontAccessRequirementsSnapshot,
    variant: FontRenderVariantKey = FontRenderVariantKey.default,
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
            face.instantiate(FontInstanceDescriptor(LayoutUnit(2_048f))),
        ).value
        val asset = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
            instance.acquireRenderAsset(resolver, variant, requirements),
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

internal fun fixtureBytes(path: String): ByteArray =
    checkNotNull(object {}.javaClass.getResourceAsStream(path)) { "fixture resource $path is missing" }
        .use { input -> input.readBytes() }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
