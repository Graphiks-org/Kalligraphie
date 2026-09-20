package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

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

    @Test
    fun rejectsASceneIdWithATab() {
        assertFailsWith<IllegalArgumentException> {
            GoldenFingerprint("a\tb", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, "11".repeat(32))
        }
    }

    @Test
    fun rejectsASceneIdWithALineBreak() {
        assertFailsWith<IllegalArgumentException> {
            GoldenScene("a\nb", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1)
        }
    }

    @Test
    fun rejectsAnInjectedSixFieldRecord() {
        val injected = "kalligraphie.golden/v1 canonicalization=1\n" +
            "a\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\textra\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(injected))
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun rejectsNonCanonicalNumbers() {
        val paddedDimension = "kalligraphie.golden/v1 canonicalization=1\n" +
            "a\tGLYPH_OUTLINE\t04x5\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        assertEquals(
            GoldenDiagnosticCode.MANIFEST_MALFORMED,
            assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(paddedDimension)).code,
        )
        val paddedVersion = "kalligraphie.golden/v1 canonicalization=01\n"
        assertEquals(
            GoldenDiagnosticCode.MANIFEST_MALFORMED,
            assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(paddedVersion)).code,
        )
    }

    @Test
    fun acceptsCrlfLineEndings() {
        val text = (GoldenManifest.serialize(GoldenManifest.of(listOf(a, b)))).replace("\n", "\r\n")
        val parsed = assertIs<GoldenManifestParseResult.Parsed>(GoldenManifest.parse(text))
        assertEquals(GoldenManifest.of(listOf(a, b)), parsed.manifest)
    }

    @Test
    fun roundTripsAdversarialButValidIds() {
        val ids = listOf("a-b.c_1", "latin-grec-японська", "z".repeat(200), "0", "a~b")
        val entries = ids.mapIndexed { index, id ->
            GoldenFingerprint(id, GoldenSceneFamily.GLYPH_OUTLINE, index + 1, index + 1, PixelFormat.ALPHA_8, "11".repeat(32))
        }
        val manifest = GoldenManifest.of(entries)
        val parsed = assertIs<GoldenManifestParseResult.Parsed>(GoldenManifest.parse(GoldenManifest.serialize(manifest)))
        assertEquals(manifest, parsed.manifest)
    }

    @Test
    fun rejectsACarriageReturnInsideTheIdWithoutThrowing() {
        val text = "kalligraphie.golden/v1 canonicalization=1\n" +
            "a\rb\tGLYPH_OUTLINE\t1x1\tALPHA_8\tsha256:${"11".repeat(32)}\n"
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(text))
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun rejectsEmptyInput() {
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(GoldenManifest.parse(""))
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun parsesAHeaderOnlyManifestAsEmpty() {
        val parsed = assertIs<GoldenManifestParseResult.Parsed>(
            GoldenManifest.parse("kalligraphie.golden/v1 canonicalization=1\n"),
        )
        assertEquals(GoldenManifest.of(emptyList()), parsed.manifest)
    }

    @Test
    fun rejectsAPlusSignedVersion() {
        val rejected = assertIs<GoldenManifestParseResult.Rejected>(
            GoldenManifest.parse("kalligraphie.golden/v1 canonicalization=+1\n"),
        )
        assertEquals(GoldenDiagnosticCode.MANIFEST_MALFORMED, rejected.code)
    }

    @Test
    fun ofRejectsDuplicateIds() {
        val entry = GoldenFingerprint("dup", GoldenSceneFamily.GLYPH_OUTLINE, 1, 1, PixelFormat.ALPHA_8, "11".repeat(32))
        assertFailsWith<IllegalArgumentException> { GoldenManifest.of(listOf(entry, entry)) }
    }

    @Test
    fun fingerprintOfReturnsTheRegisteredEntryOrNull() {
        val manifest = GoldenManifest.of(listOf(a, b))
        assertEquals(a, manifest.fingerprintOf("a"))
        assertNull(manifest.fingerprintOf("missing"))
    }
}
