package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun ofDerivesDimensionsFromTheImageNotTheSceneFrame() {
        val scene = GoldenScene("glyph.outline.frame-mismatch", GoldenSceneFamily.GLYPH_OUTLINE, 3, 3)
        val image = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val fingerprint = GoldenFingerprint.of(scene, image)
        assertEquals(2, fingerprint.width)
        assertEquals(2, fingerprint.height)
    }

    @Test
    fun diagnosticCodesAreUniqueAndNamespaced() {
        val codes = GoldenDiagnosticCode.entries.map { entry -> entry.code }
        assertEquals(codes.distinct().size, codes.size)
        assertTrue(codes.all { code -> code.startsWith("e2e.") })
    }

    @Test
    fun rejectsAMalformedDigest() {
        assertFailsWith<IllegalArgumentException> {
            GoldenFingerprint("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, "NOT-A-DIGEST")
        }
    }

    @Test
    fun rejectsADigestOfCorrectLengthWithANonHexCharacter() {
        assertFailsWith<IllegalArgumentException> {
            GoldenFingerprint("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, "g" + "a".repeat(63))
        }
    }

    @Test
    fun rejectsAnUppercaseDigest() {
        assertFailsWith<IllegalArgumentException> {
            GoldenFingerprint("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, "A".repeat(64))
        }
    }

    @Test
    fun fingerprintsWithEqualFieldsAreEqual() {
        val digest = "ab".repeat(32)
        val one = GoldenFingerprint("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, digest)
        val two = GoldenFingerprint("s", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, digest)
        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
    }
}
