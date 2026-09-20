package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class GoldenVerifierTest {
    private fun image(value: Byte): GoldenImage = GoldenImage.alpha8(1, 1, byteArrayOf(value))

    private fun manifestOf(vararg pairs: Pair<GoldenScene, GoldenImage>): GoldenManifest =
        GoldenManifest.of(pairs.map { (scene, img) -> GoldenFingerprint.of(scene, img) })

    @Test
    fun reportsMatchedWhenTheDigestAgrees() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val manifest = manifestOf(scene to image(1))
        val result = GoldenVerifier.verify(listOf(scene), mapOf("s" to image(1)), manifest)
        assertEquals(listOf<GoldenComparison>(GoldenComparison.Matched("s")), result)
    }

    @Test
    fun reportsMismatchWithBothDigests() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val manifest = manifestOf(scene to image(1))
        val result = assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to image(2)), manifest).single(),
        )
        assertEquals(sha256Hex(byteArrayOf(1)), result.expectedSha256)
        assertEquals(sha256Hex(byteArrayOf(2)), result.actualSha256)
    }

    @Test
    fun reportsAMissingEntryForANewScene() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val result = assertIs<GoldenComparison.MissingInManifest>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to image(1)), GoldenManifest.of(emptyList())).single(),
        )
        assertEquals("s", result.sceneId)
    }

    @Test
    fun reportsAStaleEntryWithNoScene() {
        val stale = GoldenScene("stale", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val result = assertIs<GoldenComparison.StaleManifestEntry>(
            GoldenVerifier.verify(emptyList(), emptyMap(), manifestOf(stale to image(1))).single(),
        )
        assertEquals("stale", result.sceneId)
    }

    @Test
    fun reportsAStaleEntryWhenTheSceneWasNotRendered() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val result = assertIs<GoldenComparison.StaleManifestEntry>(
            GoldenVerifier.verify(listOf(scene), emptyMap(), manifestOf(scene to image(1))).single(),
        )
        assertEquals("s", result.sceneId)
    }

    @Test
    fun reportsMismatchWhenTheRenderedSizeDoesNotMatchTheSceneFrame() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val rendered = GoldenImage.alpha8(2, 1, byteArrayOf(1, 2))
        val manifest = GoldenManifest.of(listOf(GoldenFingerprint.of(scene, rendered)))
        val result = assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to rendered), manifest).single(),
        )
        assertEquals(1, result.expectedWidth)
        assertEquals(1, result.expectedHeight)
        assertEquals(2, result.actualWidth)
    }

    @Test
    fun firstDifferenceLocatesTheOffendingByte() {
        val expected = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val actual = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 9, 4))
        val difference = GoldenImageDiff.firstDifference(expected, actual)
        assertEquals(2, difference?.byteOffset)
        assertEquals(0, difference?.x)
        assertEquals(1, difference?.y)
    }

    @Test
    fun firstDifferenceIsNullForIdenticalImages() {
        val image = GoldenImage.alpha8(1, 1, byteArrayOf(7))
        assertNull(GoldenImageDiff.firstDifference(image, image))
    }

    @Test
    fun reportsAnUncataloguedManifestEntryEvenWhenAnImageWasRendered() {
        val ghost = GoldenScene("ghost", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val manifest = manifestOf(ghost to image(1))
        val result = assertIs<GoldenComparison.StaleManifestEntry>(
            GoldenVerifier.verify(emptyList(), mapOf("ghost" to image(1)), manifest).single(),
        )
        assertEquals("ghost", result.sceneId)
    }
}
