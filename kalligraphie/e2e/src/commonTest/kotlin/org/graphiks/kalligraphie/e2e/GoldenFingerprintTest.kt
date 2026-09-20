package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoldenFingerprintTest {
    @Test
    fun ofDerivesTheFingerprintFromTheCanonicalBytes() {
        val scene = GoldenScene("glyph.outline.smoke", GoldenSceneFamily.GLYPH_OUTLINE, 2, 2, setOf("smoke"))
        val image = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val fingerprint = GoldenFingerprint.of(scene, image)
        assertEquals("glyph.outline.smoke", fingerprint.sceneId)
        assertEquals(GoldenSceneFamily.GLYPH_OUTLINE, fingerprint.family)
        assertEquals(2, fingerprint.width)
        assertEquals(2, fingerprint.height)
        assertEquals(PixelFormat.ALPHA_8, fingerprint.format)
        assertEquals(sha256Hex(byteArrayOf(1, 2, 3, 4)), fingerprint.sha256)
    }

    @Test
    fun rejectsAMalformedDigest() {
        assertFailsWith<IllegalArgumentException> {
            GoldenFingerprint("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, "NOT-A-DIGEST")
        }
    }
}
