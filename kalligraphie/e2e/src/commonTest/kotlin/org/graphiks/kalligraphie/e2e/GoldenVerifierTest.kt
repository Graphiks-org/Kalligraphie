package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun rejectsACataloguedSceneWithoutARenderedImage() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        assertFailsWith<IllegalArgumentException> {
            GoldenVerifier.verify(listOf(scene), emptyMap(), manifestOf(scene to image(1)))
        }
    }

    @Test
    fun rejectsADuplicateCataloguedSceneId() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        assertFailsWith<IllegalArgumentException> {
            GoldenVerifier.verify(listOf(scene, scene), mapOf("s" to image(1)), manifestOf(scene to image(1)))
        }
    }

    @Test
    fun reportsMismatchWhenTheRecordedFamilyDiffers() {
        val scene = GoldenScene("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        val recorded = GoldenFingerprint(
            "s", GoldenSceneFamily.GLYPH_BITMAP, 1, 1, PixelFormat.ALPHA_8, sha256Hex(byteArrayOf(1)),
        )
        assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("s" to image(1)), GoldenManifest.of(listOf(recorded))).single(),
        )
    }

    @Test
    fun firstDifferenceLocatesAnRgbaChannel() {
        val expected = GoldenImage.rgba8(2, 1, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        val actual = GoldenImage.rgba8(2, 1, byteArrayOf(1, 2, 3, 4, 5, 9, 7, 8))
        val difference = GoldenImageDiff.firstDifference(expected, actual)
        assertEquals(5, difference?.byteOffset)
        assertEquals(1, difference?.x)
        assertEquals(0, difference?.y)
    }

    @Test
    fun firstDifferenceRejectsMismatchedDimensions() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImageDiff.firstDifference(
                GoldenImage.alpha8(1, 1, byteArrayOf(1)),
                GoldenImage.alpha8(2, 1, byteArrayOf(1, 2)),
            )
        }
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
}
