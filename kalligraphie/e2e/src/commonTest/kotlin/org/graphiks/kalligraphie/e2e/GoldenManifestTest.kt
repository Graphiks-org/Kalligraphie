package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GoldenManifestTest {
    private val a = GoldenFingerprint("a", GoldenSceneFamily.GLYPH_OUTLINE, 3, 4, PixelFormat.ALPHA_8, "11".repeat(32))
    private val b = GoldenFingerprint("b", GoldenSceneFamily.COMPOSED_LINE, 10, 2, PixelFormat.RGBA_8888, "22".repeat(32))

    @Test
    fun roundTripsThroughTheCanonicalText() {
        val manifest = GoldenManifest.of(listOf(b, a))
        val text = GoldenManifest.serialize(manifest)
        assertEquals(
            "kalligraphie.golden/v1 canonicalization=1\n" +
                "a\tGLYPH_OUTLINE\t3x4\tALPHA_8\tsha256:${"11".repeat(32)}\n" +
                "b\tCOMPOSED_LINE\t10x2\tRGBA_8888\tsha256:${"22".repeat(32)}\n",
            text,
        )
        val parsed = assertIs<GoldenManifestParseResult.Parsed>(GoldenManifest.parse(text))
        assertEquals(manifest, parsed.manifest)
    }

    @Test
    fun rejectsADifferentCanonicalizationVersion() {
        val text = "kalligraphie.golden/v1 canonicalization=2\na\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(text))
        assertEquals(GoldenDiagnosticCode.CANONICALIZATION_VERSION_MISMATCH, rejected.code)
    }

    @Test
    fun rejectsDuplicateIds() {
        val line = "a\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(
            GoldenManifest.parse("kalligraphie.golden/v1 canonicalization=1\n$line$line"),
        )
        assertEquals(GoldenDiagnosticCode.MANIFEST_DUPLICATE_ID, rejected.code)
    }

    @Test
    fun rejectsAMalformedLine() {
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(
            GoldenManifest.parse("kalligraphie.golden/v1 canonicalization=1\nnot-a-record\n"),
        )
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun rejectsAMissingHeader() {
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(
            GoldenManifest.parse("a\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\n"),
        )
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun rejectsUnsortedEntries() {
        val swapped = "kalligraphie.golden/v1 canonicalization=1\n" +
            "b\tCOMPOSED_LINE\t10x2\tRGBA_8888\tsha256:${"22".repeat(32)}\n" +
            "a\tGLYPH_OUTLINE\t3x4\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(swapped))
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }
}
