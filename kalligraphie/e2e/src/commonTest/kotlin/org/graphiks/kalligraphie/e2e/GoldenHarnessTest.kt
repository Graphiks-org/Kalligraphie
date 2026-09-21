package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GoldenHarnessTest {
    @Test
    fun aDeliberateMutationIsDetected() {
        val scene = GoldenScene("harness.mutated", GoldenSceneFamily.GLYPH_OUTLINE, 2, 2)
        val original = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val manifest = GoldenManifest.of(listOf(GoldenFingerprint.of(scene, original)))
        val mutated = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 5))

        val result = assertIs<GoldenComparison.Mismatch>(
            GoldenVerifier.verify(listOf(scene), mapOf("harness.mutated" to mutated), manifest).single(),
        )
        assertTrue(result.expectedSha256 != result.actualSha256)
    }

    @Test
    fun theManifestRoundTripsUnderTheHarness() {
        val scene = GoldenScene("harness.roundtrip", GoldenSceneFamily.COMPOSED_LINE, 4, 1)
        val image = GoldenImage.rgba8(4, 1, ByteArray(16) { index -> index.toByte() })
        val manifest = GoldenManifest.of(listOf(GoldenFingerprint.of(scene, image)))
        val parsed = assertIs<GoldenManifestParseResult.Parsed>(
            GoldenManifest.parse(GoldenManifest.serialize(manifest)),
        )
        assertEquals(manifest, parsed.manifest)
        assertEquals(
            listOf<GoldenComparison>(GoldenComparison.Matched("harness.roundtrip")),
            GoldenVerifier.verify(listOf(scene), mapOf("harness.roundtrip" to image), parsed.manifest),
        )
    }

    @Test
    fun theCanonicalizationGuardRejectsAStaleManifest() {
        val text = "kalligraphie.golden/v1 canonicalization=999\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(text))
        assertEquals(GoldenDiagnosticCode.CANONICALIZATION_VERSION_MISMATCH, rejected.code)
    }
}
