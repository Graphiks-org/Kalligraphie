package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class J14DetachedAssetContractTest {
    @Test
    fun detachedAssetResolvesAfterResolverAndAttachedHandleClose() {
        val catalog = catalogFor(fixtureBytes())
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(FontFaceRequest(0), FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
        val attached = success(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        )
        val detached = success(attached.detach())

        assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        assertIs<FontOperationResult.Success<Unit>>(resolver.close())
        assertIs<FontOperationResult.Success<Unit>>(attached.close())
        assertIs<FontOperationResult.Success<Unit>>(attached.close())

        val attachedResult = attached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none)
        assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(attachedResult).error)

        val representation = success(detached.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.none))
        val outline = assertIs<GlyphRepresentation.Outline>(representation).outline
        assertEquals(36, outline.glyphId)
        assertEquals(2048, outline.unitsPerEm)
        assertEquals(4, outline.bounds.minX)
        assertEquals(1362, outline.bounds.maxX)
    }

    @Test
    fun closedResolverRejectsNewAttachedAssets() {
        val opened = openRenderableFont(fixtureBytes(), 2048f)
        assertIs<FontOperationResult.Success<Unit>>(opened.resolver.close())

        val result = opened.instance.acquireRenderAsset(
            opened.resolver,
            FontRenderVariantKey.default,
            FontAccessRequirementsSnapshot.renderable(outlineProfile()),
        )

        assertIs<FontError.ResourceClosed>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun instanceKeysAreStableAndDistinctBySourceAndSize() {
        val bytes = fixtureBytes()
        val face = faceFor(bytes)
        val sameA = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f)))).key
        val sameB = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f)))).key
        val differentSize = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1024f)))).key
        val mutated = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x01).toByte() }
        val differentSource = success(faceFor(mutated).instantiate(FontInstanceDescriptor(LayoutUnit(2048f)))).key

        assertEquals(sameA, sameB)
        assertNotEquals(sameA, differentSize)
        assertNotEquals(sameA, differentSource)
    }

    @Test
    fun rejectsNonPositiveInstanceSizeAsTypedFailure() {
        val result = faceFor(fixtureBytes()).instantiate(FontInstanceDescriptor(LayoutUnit(0f)))

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.invalid-instance-descriptor", failure.error.code)
    }

    @Test
    fun cancelledOutlineResolutionPublishesNoPartialRepresentation() {
        val asset = openRenderableFont(fixtureBytes(), 2048f).asset

        val result = asset.resolveGlyph(FontGlyphRequest(GlyphId(36)), CancellationToken.cancelled)

        assertIs<FontOperationResult.Cancelled>(result)
    }

    private fun openRenderableFont(bytes: ByteArray, size: Float): J14RenderableFont {
        val catalog = catalogFor(bytes)
        val resolver = success(catalog.openAssetResolver())
        val face = success(catalog.resolveFace(FontFaceRequest(0), FontAccessRequirementsSnapshot.renderable(outlineProfile())))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(size))))
        val asset = success(
            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, FontAccessRequirementsSnapshot.renderable(outlineProfile())),
        )
        return J14RenderableFont(resolver, instance, asset)
    }

    private fun faceFor(bytes: ByteArray): FontFace =
        success(catalogFor(bytes).resolveFace(FontFaceRequest(0), FontAccessRequirementsSnapshot.layoutOnly()))

    private fun catalogFor(bytes: ByteArray): FontCatalogSnapshot =
        success(Kalligraphie.embedded(bytes, FontSourceProvenance(declaredName = "Liberation Sans Regular")))

    private fun outlineProfile(): OutlineProfile =
        OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 256,
            maxPoints = 16_384,
            maxCompositeDepth = 8,
            maxCompositeComponents = 256,
        )

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

private data class J14RenderableFont(
    val resolver: FontAssetResolverHandle,
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
)
