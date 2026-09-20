package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.Kalligraphie
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
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
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
internal fun openOutlineFixture(bytes: ByteArray, layoutSize: LayoutUnit = LayoutUnit(2_048f)): E2eFontFixture {
    val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
        Kalligraphie.embedded(
            sourceBytes = bytes,
            provenance = FontSourceProvenance(declaredName = "e2e golden fixture"),
        ),
    ).value
    val requirements = FontAccessRequirementsSnapshot.renderable(listOf(outlineProfile()))
    val resolver = assertIs<FontOperationResult.Success<FontAssetResolverHandle>>(catalog.openAssetResolver()).value
    try {
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(catalog.faces.single().id, requirements),
        ).value
        val instance = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize)),
        ).value
        val asset = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
            instance.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements),
        ).value
        return E2eFontFixture(instance, asset, resolver)
    } catch (error: Throwable) {
        resolver.close()
        throw error
    }
}

/** Resolves the outline of [codePoint] through the instance, then the render asset. */
internal fun E2eFontFixture.outlineOf(codePoint: Int): GlyphOutlineIR {
    val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
        instance.resolveGlyph(codePoint),
    ).value.glyphId
    val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
        asset.resolveGlyph(FontGlyphRequest(glyph)),
    ).value
    return assertIs<GlyphRepresentation.Outline>(representation).outline
}

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
