package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.sfnt.CMapTable
import io.ygdrasil.kalligraphie.font.sfnt.DefaultOpenTypeFaceParser
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGdefTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposPairTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposSingleTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubTable
import io.ygdrasil.kalligraphie.text.shaping.BasicBidiResolver
import io.ygdrasil.kalligraphie.text.shaping.BasicOpenTypeShapingEngine
import io.ygdrasil.kalligraphie.text.shaping.CMapGlyphMapper
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataGenerator
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataSetResources
import io.ygdrasil.kalligraphie.text.shaping.RequiredScriptFeaturePolicies
import io.ygdrasil.kalligraphie.text.shaping.RuntimeGposLookupTrace
import io.ygdrasil.kalligraphie.text.shaping.RuntimeGsubLookupTrace
import io.ygdrasil.kalligraphie.text.shaping.ScriptExtensionsItemizer
import io.ygdrasil.kalligraphie.text.shaping.ShapingRequest
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_CURSIVE_ATTACHMENT_UNAVAILABLE_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_MARK_POSITIONING_UNAVAILABLE_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_PARAGRAPH_BIDI_REQUIRED_DIAGNOSTIC_CODE

class ArabicShapingFixtureTest {
    @Test
    fun basicOpenTypeShapingEngineAppliesVendoredArabicJoiningForms() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440703",
            relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
        )
        val text = "\u0633\u0644\u0627\u0645"
        val rawGlyphIds = listOf('\u0633'.code, '\u0644'.code, '\u0627'.code, '\u0645'.code)
            .map { codePoint -> requireNotNull(face.cmap.lookupGlyphId(codePoint)) }
        val rawVisualOrderGlyphIds = rawGlyphIds.reversed()
        val result = engineFor(face).shape(
            ShapingRequest(
                text = text,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        val shapedRun = result.glyphRuns.single()

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals(4, shapedRun.clusters.size)
        assertEquals(4, shapedRun.glyphIds.size)
        assertFalse(
            shapedRun.glyphIds == rawVisualOrderGlyphIds,
            "Arabic joining fixture should prove more than RTL reordering of raw cmap glyph IDs.",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineAppliesVendoredArabicMarkPositioning() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440704",
            relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0627\u064E",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        val shapedRun = result.glyphRuns.single()
        val baseCluster = shapedRun.clusters.first { cluster -> cluster.textRange == 0..0 }
        val markCluster = shapedRun.clusters.first { cluster -> cluster.textRange == 1..1 }

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals(2, shapedRun.clusters.size)
        assertTrue(baseCluster.advanceX > 0f, "Arabic base cluster should keep a positive advance.")
        assertTrue(
            markCluster.advanceX == 0f || markCluster.offsetX != 0f || markCluster.offsetY != 0f,
            "Arabic mark fixture should prove positioning or zero-advance on the mark glyph cluster itself.",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineChangesVendoredArabicLamAlefSequenceWithoutClosingLigatureGate() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440705",
            relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
        )
        val text = "\u0644\u0627"
        val rawGlyphIds = listOf('\u0644'.code, '\u0627'.code)
            .map { codePoint -> requireNotNull(face.cmap.lookupGlyphId(codePoint)) }
        val result = engineFor(face).shape(
            ShapingRequest(
                text = text,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        val rawVisualOrderGlyphIds = rawGlyphIds.reversed()
        val shapedRun = result.glyphRuns.single()

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals(2, shapedRun.glyphIds.size)
        assertTrue(
            shapedRun.glyphIds.zip(rawVisualOrderGlyphIds).all { (shapedGlyphId, rawGlyphId) -> shapedGlyphId != rawGlyphId },
            "Arabic lam-alef bounded runtime check should prove both visual-order component glyph IDs change beyond pure RTL reordering without closing the ticket-local ligature gate.",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineEmitsParagraphBidiDiagnosticForVendoredArabicMixedFixture() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440702",
            relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
        )
        val mixedBidiText = readProjectFile("font/fixtures/expected/shaping/arabic-mixed-bidi.txt").trimEnd()
        val result = engineFor(face).shape(
            ShapingRequest(
                text = mixedBidiText,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertTrue(result.glyphRuns.isNotEmpty(), "Mixed bidi diagnostic should not be described as an empty-run refusal.")
        assertContains(
            result.diagnostics.map { it.code },
            TEXT_SHAPING_PARAGRAPH_BIDI_REQUIRED_DIAGNOSTIC_CODE,
        )
    }

    @Test
    fun basicOpenTypeShapingEngineAppliesVendoredArabicLamAlefFixture() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440707",
            relativePath = "font/fixtures/fonts/shaping/arabic-lam-alef.otf",
        )
        val text = "\u0644\u0627"
        val rawGlyphIds = listOf('\u0644'.code, '\u0627'.code)
            .map { codePoint -> requireNotNull(face.cmap.lookupGlyphId(codePoint)) }
        val result = engineFor(face).shape(
            ShapingRequest(
                text = text,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        val shapedRun = result.glyphRuns.single()

        assertEquals(1, result.glyphRuns.size)
        assertEquals(2, shapedRun.glyphIds.size)
        assertEquals(2, shapedRun.clusters.size)
        val rawVisualOrderGlyphIds = rawGlyphIds.reversed()
        assertFalse(
            shapedRun.glyphIds == rawVisualOrderGlyphIds,
            "Arabic lam-alef fixture should prove more than RTL reordering of raw cmap glyph IDs.",
        )
        assertTrue(
            result.diagnostics.any { it.code == TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE },
            "lam-alef fixture without GPOS should emit arabic-cursive-unsupported.",
        )
        assertTrue(
            result.diagnostics.any { it.code == TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE },
            "lam-alef fixture without GPOS should emit arabic-mark-unsupported.",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineAppliesVendoredArabicCursiveAttachment() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440708",
            relativePath = "font/fixtures/fonts/shaping/arabic-marks-cursive.otf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0644\u0645",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        val shapedRun = result.glyphRuns.single()

        assertEquals(emptyList(), result.diagnostics)
        assertEquals(1, result.glyphRuns.size)
        assertEquals(2, shapedRun.clusters.size)
        val rawGlyphIds = listOf('\u0644'.code, '\u0645'.code)
            .map { codePoint -> requireNotNull(face.cmap.lookupGlyphId(codePoint)) }
        val rawVisualOrderGlyphIds = rawGlyphIds.reversed()
        assertFalse(
            shapedRun.glyphIds == rawVisualOrderGlyphIds,
            "Arabic marks-cursive fixture should prove GSUB joining form substitution beyond pure RTL reordering.",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineEmitsArabicCursiveUnsupportedDiagnostic() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440709",
            relativePath = "font/fixtures/fonts/shaping/arabic-missing-cursive.otf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0628",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(1, result.glyphRuns.size)
        assertTrue(
            result.diagnostics.map { it.code }.contains(TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE),
            "arabic-missing-cursive.otf should emit arabic-cursive-unsupported diagnostic. Got: ${result.diagnostics}",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineEmitsArabicMarkUnsupportedDiagnostic() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440710",
            relativePath = "font/fixtures/fonts/shaping/arabic-missing-mark.otf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0627\u064E",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(1, result.glyphRuns.size)
        assertTrue(
            result.diagnostics.map { it.code }.contains(TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE),
            "arabic-missing-mark.otf should emit arabic-mark-unsupported diagnostic. Got: ${result.diagnostics}",
        )
    }

    @Test
    fun basicOpenTypeShapingEngineEmitsReviewedGenericGdefRequiredDiagnosticForArabicBasePlusMarkFixture() {
        val face = parsedFixtureFace(
            uuid = "550e8400-e29b-41d4-a716-446655440706",
            relativePath = "font/fixtures/fonts/shaping/gpos-missing-gdef.otf",
        )
        val result = engineFor(face).shape(
            ShapingRequest(
                text = "\u0627\u064E",
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )

        assertEquals(1, result.glyphRuns.size)
        assertEquals(
            listOf(TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE),
            result.diagnostics.map { it.code },
        )
        assertEquals(listOf(20f, 20f), result.glyphRuns.single().clusters.map { it.advanceX })
    }

    @Test
    fun arabicShapingReportGoldenExistsAndTracksFixtureWave() {
        val report = readJsonProjectFile("font/fixtures/expected/shaping/arabic-shaping-report.json")
        val cases = report.requiredObjectList("cases")

        assertEquals(1L, report.requiredLong("schemaVersion"))
        assertEquals("arabic-shaping-report", report.requiredString("dumpId"))
        assertEquals(listOf("KFONT-M6-007"), report.requiredStringList("ownerTickets"))
        assertEquals("arabic-shaping-fixtures", report.requiredString("fixtureId"))
        assertEquals(
            listOf("joining-forms", "marks", "missing-mark-gdef-required", "mixed-bidi-paragraph-required", "lam-alef-fixture", "marks-cursive-fixture", "missing-cursive-fixture", "missing-mark-fixture"),
            cases.map { it.requiredString("caseId") },
        )
        assertEquals("positive", cases[0].requiredString("status"))
        assertEquals("positive", cases[1].requiredString("status"))
        assertEquals("diagnostic", cases[2].requiredString("status"))
        assertEquals("diagnostic", cases[3].requiredString("status"))
        assertEquals("bounded", cases[4].requiredString("status"))
        assertEquals("positive", cases[5].requiredString("status"))
        assertEquals("diagnostic", cases[6].requiredString("status"))
        assertEquals("diagnostic", cases[7].requiredString("status"))
        assertEquals(null, cases[1]["requiredDiagnostics"])
        assertEquals(
            listOf(TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE),
            cases[2].requiredStringList("requiredDiagnostics"),
        )
        assertEquals(
            "font/fixtures/fonts/shaping/gpos-missing-gdef.otf",
            cases[2].requiredString("fixtureFont"),
        )
        assertEquals(
            listOf(TEXT_SHAPING_PARAGRAPH_BIDI_REQUIRED_DIAGNOSTIC_CODE),
            cases[3].requiredStringList("requiredDiagnostics"),
        )
        assertEquals(
            listOf(
                "font/fixtures/fonts/shaping/arabic-lam-alef.otf",
                "font/fixtures/fonts/shaping/arabic-marks-cursive.otf",
                "font/fixtures/fonts/shaping/arabic-missing-cursive.otf",
                "font/fixtures/fonts/shaping/arabic-missing-mark.otf",
            ),
            report.requiredStringList("fixtureShapingFonts"),
        )
        assertEquals(
            listOf(
                "text.shaping.arabic-cursive-unsupported",
                "text.shaping.arabic-mark-unsupported",
            ),
            cases[4].requiredStringList("requiredDiagnostics"),
        )
        assertEquals(
            listOf(
                "text.shaping.arabic-cursive-unsupported",
            ),
            cases[6].requiredStringList("requiredDiagnostics"),
        )
        assertEquals(
            listOf(
                "text.shaping.arabic-mark-unsupported",
            ),
            cases[7].requiredStringList("requiredDiagnostics"),
        )
        assertEquals(
            listOf(
                "full-arabic-feature-policy-evidence",
            ),
            report.requiredObjectList("remainingGates").map { it.requiredString("remainingGateId") },
        )
        assertEquals(
            listOf(
                "no-arabic-shaping-support-claim",
                "no-complex-shaping-support-claim",
                "no-native-shaper-oracle-claim",
                "no-cpu-or-gpu-rendering-claim",
            ),
            report.requiredStringList("nonClaims"),
        )
        assertNoSupportPromotionClaims(report)
    }

    @Test
    fun arabicShapedGlyphRunGoldenMatchesRepoGolden() {
        val actual = arabicShapedGlyphRunEvidenceJson()
        val expected = readProjectFile("font/fixtures/expected/shaping/arabic-shaped-glyph-run.json")

        assertEquals(expected.trimEnd(), actual.trimEnd())
        assertContains(actual, """"dumpId": "arabic-shaped-glyph-run"""")
        assertContains(actual, """"fixtureFamilyId": "arabic-shaping-fixtures"""")
        assertContains(actual, """"caseId": "lam-alef-bounded-runtime-divergence"""")
        assertContains(actual, """"bidiLevel": 1""")
        assertContains(actual, TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE)
    }

    @Test
    fun arabicShapingPlanGoldenMatchesRepoGolden() {
        val actual = arabicShapingPlanEvidenceJson()
        val expected = readProjectFile("font/fixtures/expected/shaping/arabic-shaping-plan.json")

        assertEquals(expected.trimEnd(), actual.trimEnd())
        assertContains(actual, """"dumpId": "arabic-shaping-plan"""")
        assertContains(actual, """"fixtureFamilyId": "arabic-shaping-fixtures"""")
        assertContains(actual, """"requiredDefaults": ["init", "medi", "fina", "isol", "rlig", "liga", "calt", "mark", "mkmk", "curs"]""")
        assertContains(actual, """"requiredRefusals": ["mark", "mkmk", "cursive-attachment"]""")
    }

    @Test
    fun arabicGsubTraceGoldenMatchesRepoGolden() {
        val actual = arabicGsubTraceEvidenceJson()
        val expected = readProjectFile("font/fixtures/expected/shaping/arabic-gsub-trace.json")

        assertEquals(expected.trimEnd(), actual.trimEnd())
        assertContains(actual, """"dumpId": "arabic-gsub-trace"""")
        assertContains(actual, """"caseId": "joining-forms"""")
        assertContains(actual, """"caseId": "lam-alef-bounded-runtime-divergence"""")
    }

    @Test
    fun arabicGposTraceGoldenMatchesRepoGolden() {
        val actual = arabicGposTraceEvidenceJson()
        val expected = readProjectFile("font/fixtures/expected/shaping/arabic-gpos-trace.json")

        assertEquals(expected.trimEnd(), actual.trimEnd())
        assertContains(actual, """"dumpId": "arabic-gpos-trace"""")
        assertContains(actual, """"caseId": "marks"""")
        assertContains(actual, TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE)
    }

    private fun engineFor(face: ParsedFixtureFace): BasicOpenTypeShapingEngine =
        BasicOpenTypeShapingEngine(
            glyphMapper = CMapGlyphMapper(cmapsByTypefaceId = mapOf(face.typefaceId to face.cmap)),
            gsubTablesByTypefaceId = face.gsub?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gdefTablesByTypefaceId = face.gdef?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gposTablesByTypefaceId = face.gpos?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gposSingleTablesByTypefaceId = face.gposSingles?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            gposPairTablesByTypefaceId = face.gposPairs?.let { mapOf(face.typefaceId to it) }.orEmpty(),
            kernUnitsPerEmByTypefaceId = mapOf(face.typefaceId to face.unitsPerEm),
        )

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun readJsonProjectFile(relativePath: String): Map<String, Any?> =
        jsonObject(JsonParser(readProjectFile(relativePath)).parse(), relativePath)

    private fun arabicShapedGlyphRunEvidenceJson(): String {
        val cases = listOf(
            buildArabicShapedGlyphRunCase(
                caseId = "joining-forms",
                status = "positive",
                reportRef = "arabic-shaping-report#joining-forms",
                uuid = "550e8400-e29b-41d4-a716-446655440703",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0633\u0644\u0627\u0645",
            ),
            buildArabicShapedGlyphRunCase(
                caseId = "marks",
                status = "positive",
                reportRef = "arabic-shaping-report#marks",
                uuid = "550e8400-e29b-41d4-a716-446655440704",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0627\u064E",
            ),
            buildArabicShapedGlyphRunCase(
                caseId = "lam-alef-bounded-runtime-divergence",
                status = "bounded",
                reportRef = "arabic-shaping-report#lam-alef-positive-evidence",
                uuid = "550e8400-e29b-41d4-a716-446655440705",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0644\u0627",
            ),
            buildArabicShapedGlyphRunCase(
                caseId = "missing-mark-gdef-required",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-mark-gdef-required",
                uuid = "550e8400-e29b-41d4-a716-446655440706",
                relativePath = "font/fixtures/fonts/shaping/gpos-missing-gdef.otf",
                inputText = "\u0627\u064E",
            ),
            buildArabicShapedGlyphRunCase(
                caseId = "lam-alef-fixture",
                status = "bounded",
                reportRef = "arabic-shaping-report#lam-alef-positive-evidence",
                uuid = "550e8400-e29b-41d4-a716-446655440707",
                relativePath = "font/fixtures/fonts/shaping/arabic-lam-alef.otf",
                inputText = "\u0644\u0627",
            ),
            buildArabicShapedGlyphRunCase(
                caseId = "marks-cursive-fixture",
                status = "positive",
                reportRef = "arabic-shaping-report#marks",
                uuid = "550e8400-e29b-41d4-a716-446655440708",
                relativePath = "font/fixtures/fonts/shaping/arabic-marks-cursive.otf",
                inputText = "\u0644\u0645",
            ),
            buildArabicShapedGlyphRunCase(
                caseId = "missing-cursive-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-cursive",
                uuid = "550e8400-e29b-41d4-a716-446655440709",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-cursive.otf",
                inputText = "\u0628",
            ),
            buildArabicShapedGlyphRunCase(
                caseId = "missing-mark-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-mark",
                uuid = "550e8400-e29b-41d4-a716-446655440710",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-mark.otf",
                inputText = "\u0627\u064E",
            ),
        )
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"arabic-shaped-glyph-run\",\n")
            append("  \"ownerTickets\": [\"KFONT-M6-007\"],\n")
            append("  \"fixtureFamilyId\": \"arabic-shaping-fixtures\",\n")
            append("  \"cases\": [\n")
            append(cases.joinToString(",\n") { it.toCanonicalJson().prependIndent("    ") })
            append("\n  ],\n")
            append(
                "  \"nonClaims\": [\"producer-only\", \"no-arabic-shaping-support-claim\", " +
                    "\"no-complex-shaping-support-claim\", \"no-native-shaper-oracle-claim\", " +
                    "\"no-cpu-or-gpu-rendering-claim\"]\n",
            )
            append("}\n")
        }
    }

    private fun arabicShapingPlanEvidenceJson(): String {
        val cases = listOf(
            buildArabicShapingPlanCase(
                caseId = "joining-forms",
                status = "positive",
                reportRef = "arabic-shaping-report#joining-forms",
                uuid = "550e8400-e29b-41d4-a716-446655440703",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0633\u0644\u0627\u0645",
            ),
            buildArabicShapingPlanCase(
                caseId = "marks",
                status = "positive",
                reportRef = "arabic-shaping-report#marks",
                uuid = "550e8400-e29b-41d4-a716-446655440704",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0627\u064E",
            ),
            buildArabicShapingPlanCase(
                caseId = "lam-alef-bounded-runtime-divergence",
                status = "bounded",
                reportRef = "arabic-shaping-report#lam-alef-positive-evidence",
                uuid = "550e8400-e29b-41d4-a716-446655440705",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0644\u0627",
            ),
            buildArabicShapingPlanCase(
                caseId = "missing-mark-gdef-required",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-mark-gdef-required",
                uuid = "550e8400-e29b-41d4-a716-446655440706",
                relativePath = "font/fixtures/fonts/shaping/gpos-missing-gdef.otf",
                inputText = "\u0627\u064E",
                expectedDiagnostics = listOf(TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE),
            ),
            buildArabicShapingPlanCase(
                caseId = "lam-alef-fixture",
                status = "bounded",
                reportRef = "arabic-shaping-report#lam-alef-positive-evidence",
                uuid = "550e8400-e29b-41d4-a716-446655440707",
                relativePath = "font/fixtures/fonts/shaping/arabic-lam-alef.otf",
                inputText = "\u0644\u0627",
                expectedDiagnostics = listOf(TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE, TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE),
            ),
            buildArabicShapingPlanCase(
                caseId = "marks-cursive-fixture",
                status = "positive",
                reportRef = "arabic-shaping-report#marks",
                uuid = "550e8400-e29b-41d4-a716-446655440708",
                relativePath = "font/fixtures/fonts/shaping/arabic-marks-cursive.otf",
                inputText = "\u0644\u0645",
            ),
            buildArabicShapingPlanCase(
                caseId = "missing-cursive-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-cursive",
                uuid = "550e8400-e29b-41d4-a716-446655440709",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-cursive.otf",
                inputText = "\u0628",
                expectedDiagnostics = listOf(TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE, TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE),
            ),
            buildArabicShapingPlanCase(
                caseId = "missing-mark-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-mark",
                uuid = "550e8400-e29b-41d4-a716-446655440710",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-mark.otf",
                inputText = "\u0627\u064E",
                expectedDiagnostics = listOf(TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE, TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE),
            ),
        )
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"arabic-shaping-plan\",\n")
            append("  \"ownerTickets\": [\"KFONT-M6-007\"],\n")
            append("  \"fixtureFamilyId\": \"arabic-shaping-fixtures\",\n")
            append("  \"unicodeVersion\": ").append(jsonString(PinnedUnicodeDataGenerator.PinnedUnicodeVersion)).append(",\n")
            append("  \"sourceTextHashAlgorithm\": \"SHA-256-UTF-8\",\n")
            append("  \"cases\": [\n")
            append(cases.joinToString(",\n") { it.toCanonicalJson().prependIndent("    ") })
            append("\n  ],\n")
            append(
                "  \"nonClaims\": [\"producer-only\", \"no-arabic-shaping-support-claim\", " +
                    "\"no-complex-shaping-support-claim\", \"no-native-shaper-oracle-claim\", " +
                    "\"no-cpu-or-gpu-rendering-claim\"]\n",
            )
            append("}\n")
        }
    }

    private fun arabicGsubTraceEvidenceJson(): String {
        val cases = listOf(
            buildArabicGsubTraceCase(
                caseId = "joining-forms",
                status = "positive",
                reportRef = "arabic-shaping-report#joining-forms",
                uuid = "550e8400-e29b-41d4-a716-446655440703",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0633\u0644\u0627\u0645",
            ),
            buildArabicGsubTraceCase(
                caseId = "lam-alef-bounded-runtime-divergence",
                status = "bounded",
                reportRef = "arabic-shaping-report#lam-alef-positive-evidence",
                uuid = "550e8400-e29b-41d4-a716-446655440705",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0644\u0627",
            ),
            buildArabicGsubTraceCase(
                caseId = "lam-alef-fixture",
                status = "bounded",
                reportRef = "arabic-shaping-report#lam-alef-positive-evidence",
                uuid = "550e8400-e29b-41d4-a716-446655440707",
                relativePath = "font/fixtures/fonts/shaping/arabic-lam-alef.otf",
                inputText = "\u0644\u0627",
            ),
            buildArabicGsubTraceCase(
                caseId = "marks-cursive-fixture",
                status = "positive",
                reportRef = "arabic-shaping-report#marks",
                uuid = "550e8400-e29b-41d4-a716-446655440708",
                relativePath = "font/fixtures/fonts/shaping/arabic-marks-cursive.otf",
                inputText = "\u0644\u0645",
            ),
            buildArabicGsubTraceCase(
                caseId = "missing-cursive-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-cursive",
                uuid = "550e8400-e29b-41d4-a716-446655440709",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-cursive.otf",
                inputText = "\u0628",
            ),
            buildArabicGsubTraceCase(
                caseId = "missing-mark-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-mark",
                uuid = "550e8400-e29b-41d4-a716-446655440710",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-mark.otf",
                inputText = "\u0627\u064E",
            ),
        )
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"arabic-gsub-trace\",\n")
            append("  \"ownerTickets\": [\"KFONT-M6-007\"],\n")
            append("  \"fixtureFamilyId\": \"arabic-shaping-fixtures\",\n")
            append("  \"cases\": [\n")
            append(cases.joinToString(",\n") { it.toCanonicalJson().prependIndent("    ") })
            append("\n  ],\n")
            append(
                "  \"nonClaims\": [\"producer-only\", \"no-arabic-shaping-support-claim\", " +
                    "\"no-complex-shaping-support-claim\", \"no-native-shaper-oracle-claim\", " +
                    "\"no-cpu-or-gpu-rendering-claim\"]\n",
            )
            append("}\n")
        }
    }

    private fun arabicGposTraceEvidenceJson(): String {
        val cases = listOf(
            buildArabicGposTraceCase(
                caseId = "marks",
                status = "positive",
                reportRef = "arabic-shaping-report#marks",
                uuid = "550e8400-e29b-41d4-a716-446655440704",
                relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
                inputText = "\u0627\u064E",
            ),
            buildArabicGposTraceCase(
                caseId = "missing-mark-gdef-required",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-mark-gdef-required",
                uuid = "550e8400-e29b-41d4-a716-446655440706",
                relativePath = "font/fixtures/fonts/shaping/gpos-missing-gdef.otf",
                inputText = "\u0627\u064E",
            ),
            buildArabicGposTraceCase(
                caseId = "lam-alef-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#lam-alef-positive-evidence",
                uuid = "550e8400-e29b-41d4-a716-446655440707",
                relativePath = "font/fixtures/fonts/shaping/arabic-lam-alef.otf",
                inputText = "\u0644\u0627",
            ),
            buildArabicGposTraceCase(
                caseId = "marks-cursive-fixture",
                status = "positive",
                reportRef = "arabic-shaping-report#marks",
                uuid = "550e8400-e29b-41d4-a716-446655440708",
                relativePath = "font/fixtures/fonts/shaping/arabic-marks-cursive.otf",
                inputText = "\u0644\u0645",
            ),
            buildArabicGposTraceCase(
                caseId = "missing-cursive-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-cursive",
                uuid = "550e8400-e29b-41d4-a716-446655440709",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-cursive.otf",
                inputText = "\u0628",
            ),
            buildArabicGposTraceCase(
                caseId = "missing-mark-fixture",
                status = "diagnostic",
                reportRef = "arabic-shaping-report#missing-mark",
                uuid = "550e8400-e29b-41d4-a716-446655440710",
                relativePath = "font/fixtures/fonts/shaping/arabic-missing-mark.otf",
                inputText = "\u0627\u064E",
            ),
        )
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"arabic-gpos-trace\",\n")
            append("  \"ownerTickets\": [\"KFONT-M6-007\"],\n")
            append("  \"fixtureFamilyId\": \"arabic-shaping-fixtures\",\n")
            append("  \"cases\": [\n")
            append(cases.joinToString(",\n") { it.toCanonicalJson().prependIndent("    ") })
            append("\n  ],\n")
            append(
                "  \"nonClaims\": [\"producer-only\", \"no-arabic-shaping-support-claim\", " +
                    "\"no-complex-shaping-support-claim\", \"no-native-shaper-oracle-claim\", " +
                    "\"no-cpu-or-gpu-rendering-claim\"]\n",
            )
            append("}\n")
        }
    }

    private fun parsedFixtureFace(
        uuid: String,
        relativePath: String,
    ): ParsedFixtureFace {
        val typefaceId = TypefaceID(Uuid.parse(uuid))
        val path = projectRoot().resolve(relativePath)
        val source = FontSource(
            id = FontSourceID(Uuid.parse(uuid.replaceRange(uuid.length - 1, uuid.length, "0"))),
            kind = FontSourceKind.FILE,
            displayName = path.fileName.toString(),
            bytes = Files.readAllBytes(path),
        )
        val parsed = DefaultOpenTypeFaceParser().parse(source)
        assertEquals(emptyList(), parsed.diagnostics, relativePath)
        return ParsedFixtureFace(
            typefaceId = typefaceId,
            cmap = parsed.cmap,
            unitsPerEm = requireNotNull(parsed.metrics.unitsPerEm),
            gsub = parsed.layout.gsub,
            gdef = parsed.layout.gdef,
            gpos = parsed.layout.gpos,
            gposSingles = parsed.layout.gposSingles,
            gposPairs = parsed.layout.gposPairs,
        )
    }

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }

    private data class ParsedFixtureFace(
        val typefaceId: TypefaceID,
        val cmap: CMapTable,
        val unitsPerEm: Int,
        val gsub: OpenTypeGsubTable?,
        val gdef: OpenTypeGdefTable?,
        val gpos: OpenTypeGposTable?,
        val gposSingles: OpenTypeGposSingleTable?,
        val gposPairs: OpenTypeGposPairTable?,
    )

    private data class ArabicShapedGlyphRunCase(
        val caseId: String,
        val status: String,
        val reportRef: String,
        val fixtureFont: String,
        val inputText: String,
        val typefaceId: TypefaceID,
        val glyphRun: io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun,
        val bidiLevel: Int,
        val diagnostics: List<io.ygdrasil.kalligraphie.text.shaping.ShapingDiagnostic>,
    )

    private data class ArabicShapingPlanCase(
        val caseId: String,
        val status: String,
        val reportRef: String,
        val fixtureFont: String,
        val inputText: String,
        val sourceTextHash: String,
        val typefaceId: TypefaceID,
        val scriptRun: io.ygdrasil.kalligraphie.text.shaping.ScriptItemizationRun,
        val bidiLevel: Int,
        val bidiDirection: String,
        val languageSystem: String,
        val enabledFeatures: List<String>,
        val defaultedFeatures: List<String>,
        val requiredRefusals: List<String>,
        val expectedDiagnostics: List<String>,
        val hasGdef: Boolean,
        val hasGsub: Boolean,
        val hasGpos: Boolean,
    )

    private data class ArabicGsubTraceCase(
        val caseId: String,
        val status: String,
        val reportRef: String,
        val fixtureFont: String,
        val inputText: String,
        val typefaceId: TypefaceID,
        val featureOrder: List<String>,
        val inputGlyphIds: List<Int>,
        val outputGlyphIds: List<Int>,
        val lookups: List<RuntimeGsubLookupTrace>,
        val diagnostics: List<io.ygdrasil.kalligraphie.text.shaping.ShapingDiagnostic>,
    )

    private data class ArabicGposTraceCase(
        val caseId: String,
        val status: String,
        val reportRef: String,
        val fixtureFont: String,
        val inputText: String,
        val typefaceId: TypefaceID,
        val featureOrder: List<String>,
        val glyphIds: List<Int>,
        val before: List<List<Long>>,
        val after: List<List<Long>>,
        val lookups: List<RuntimeGposLookupTrace>,
        val diagnostics: List<io.ygdrasil.kalligraphie.text.shaping.ShapingDiagnostic>,
    )

    private fun buildArabicShapedGlyphRunCase(
        caseId: String,
        status: String,
        reportRef: String,
        uuid: String,
        relativePath: String,
        inputText: String,
    ): ArabicShapedGlyphRunCase {
        val face = parsedFixtureFace(uuid = uuid, relativePath = relativePath)
        val result = engineFor(face).shape(
            ShapingRequest(
                text = inputText,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals(1, result.glyphRuns.size, caseId)
        return ArabicShapedGlyphRunCase(
            caseId = caseId,
            status = status,
            reportRef = reportRef,
            fixtureFont = relativePath,
            inputText = inputText,
            typefaceId = face.typefaceId,
            glyphRun = result.glyphRuns.single(),
            bidiLevel = result.glyphRuns.single().bidiLevel,
            diagnostics = result.diagnostics,
        )
    }

    private fun buildArabicShapingPlanCase(
        caseId: String,
        status: String,
        reportRef: String,
        uuid: String,
        relativePath: String,
        inputText: String,
        expectedDiagnostics: List<String> = emptyList(),
    ): ArabicShapingPlanCase {
        val face = parsedFixtureFace(uuid = uuid, relativePath = relativePath)
        val unicodeData = PinnedUnicodeDataSetResources.load()
        val scriptRun = ScriptExtensionsItemizer(unicodeData).itemize(inputText).runs.single()
        val featureSet = RequiredScriptFeaturePolicies.resolve(scriptRun = scriptRun, requested = emptyList())
        val bidiRun = BasicBidiResolver().resolve(
            ShapingRequest(
                text = inputText,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        ).single()
        val arabicPolicy = RequiredScriptFeaturePolicies.rows.first { row -> row.scriptFamily == "Arabic" }
        return ArabicShapingPlanCase(
            caseId = caseId,
            status = status,
            reportRef = reportRef,
            fixtureFont = relativePath,
            inputText = inputText,
            sourceTextHash = sha256Utf8(inputText),
            typefaceId = face.typefaceId,
            scriptRun = scriptRun,
            bidiLevel = bidiRun.level,
            bidiDirection = if (bidiRun.isRightToLeft) "RTL" else "LTR",
            languageSystem = featureSet.languageSystem ?: "dflt",
            enabledFeatures = featureSet.enabled.map { feature -> feature.tag },
            defaultedFeatures = featureSet.defaulted.map { feature -> feature.tag },
            requiredRefusals = arabicPolicy.refusalWhenMissing,
            expectedDiagnostics = expectedDiagnostics,
            hasGdef = face.gdef != null,
            hasGsub = face.gsub != null,
            hasGpos = face.gpos != null,
        )
    }

    private fun buildArabicGsubTraceCase(
        caseId: String,
        status: String,
        reportRef: String,
        uuid: String,
        relativePath: String,
        inputText: String,
    ): ArabicGsubTraceCase {
        val face = parsedFixtureFace(uuid = uuid, relativePath = relativePath)
        val trace = engineFor(face).shapeWithRuntimeTrace(
            ShapingRequest(
                text = inputText,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals(1, trace.result.glyphRuns.size, caseId)
        val traceRun = trace.runs.single()
        return ArabicGsubTraceCase(
            caseId = caseId,
            status = status,
            reportRef = reportRef,
            fixtureFont = relativePath,
            inputText = inputText,
            typefaceId = face.typefaceId,
            featureOrder = traceRun.featureOrder,
            inputGlyphIds = traceRun.inputGlyphIds,
            outputGlyphIds = trace.result.glyphRuns.single().glyphIds,
            lookups = trace.gsubLookups,
            diagnostics = trace.result.diagnostics,
        )
    }

    private fun buildArabicGposTraceCase(
        caseId: String,
        status: String,
        reportRef: String,
        uuid: String,
        relativePath: String,
        inputText: String,
    ): ArabicGposTraceCase {
        val face = parsedFixtureFace(uuid = uuid, relativePath = relativePath)
        val trace = engineFor(face).shapeWithRuntimeTrace(
            ShapingRequest(
                text = inputText,
                typefaceId = face.typefaceId,
                fontSize = 20f,
            ),
        )
        assertEquals(1, trace.result.glyphRuns.size, caseId)
        val traceRun = trace.runs.single()
        val glyphRun = trace.result.glyphRuns.single()
        return ArabicGposTraceCase(
            caseId = caseId,
            status = status,
            reportRef = reportRef,
            fixtureFont = relativePath,
            inputText = inputText,
            typefaceId = face.typefaceId,
            featureOrder = traceRun.featureOrder,
            glyphIds = glyphRun.glyphIds,
            before = traceRun.preGposClusterMetrics.map { metric ->
                listOf(metric.advanceX.toTenthsJson().toLong(), metric.offsetX.toTenthsJson().toLong(), metric.offsetY.toTenthsJson().toLong())
            },
            after = glyphRun.clusters.map { cluster ->
                listOf(cluster.advanceX.toTenthsJson().toLong(), cluster.offsetX.toTenthsJson().toLong(), cluster.offsetY.toTenthsJson().toLong())
            },
            lookups = trace.gposLookups,
            diagnostics = trace.result.diagnostics,
        )
    }

    private fun ArabicShapedGlyphRunCase.toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"caseId\": ").append(jsonString(caseId)).append(",\n")
        append("  \"ticketId\": \"KFONT-M6-007\",\n")
        append("  \"status\": ").append(jsonString(status)).append(",\n")
        append("  \"reportRef\": ").append(jsonString(reportRef)).append(",\n")
        append("  \"fixtureFont\": ").append(jsonString(fixtureFont)).append(",\n")
        append("  \"inputText\": ").append(jsonString(inputText)).append(",\n")
        append("  \"typefaceId\": ").append(jsonString(typefaceId.value.toString())).append(",\n")
        append("  \"bidiLevel\": ").append(bidiLevel).append(",\n")
        append("  \"glyphIds\": ").append(glyphRun.glyphIds.toCanonicalIntArrayJson()).append(",\n")
        append("  \"clusters\": ").append(glyphRun.clusters.toCanonicalClusterArrayJson()).append(",\n")
        append("  \"clusterMetrics\": ").append(glyphRun.clusters.toCanonicalClusterMetricsJson()).append(",\n")
        append("  \"advanceX10\": ").append(glyphRun.advanceX.toTenthsJson())
        if (diagnostics.isNotEmpty()) {
            append(",\n")
            append("  \"diagnostics\": ").append(diagnostics.toCanonicalDiagnosticsJson())
        }
        append("\n}")
    }

    private fun ArabicShapingPlanCase.toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"caseId\": ").append(jsonString(caseId)).append(",\n")
        append("  \"ticketId\": \"KFONT-M6-007\",\n")
        append("  \"status\": ").append(jsonString(status)).append(",\n")
        append("  \"reportRef\": ").append(jsonString(reportRef)).append(",\n")
        append("  \"fixtureFont\": ").append(jsonString(fixtureFont)).append(",\n")
        append("  \"inputText\": ").append(jsonString(inputText)).append(",\n")
        append("  \"sourceTextHash\": ").append(jsonString(sourceTextHash)).append(",\n")
        append("  \"textRange\": ").append(jsonString(scriptRun.utf16Range.toRangeLabel())).append(",\n")
        append("  \"typefaceId\": ").append(jsonString(typefaceId.value.toString())).append(",\n")
        append("  \"scriptRun\": ").append(scriptRun.toCanonicalJson()).append(",\n")
        append("  \"bidi\": {\"level\": ").append(bidiLevel).append(", \"direction\": ").append(jsonString(bidiDirection)).append("},\n")
        append("  \"languageSystem\": ").append(jsonString(languageSystem)).append(",\n")
        append("  \"requiredDefaults\": ").append(enabledFeatures.toCanonicalStringArrayJson()).append(",\n")
        append("  \"defaultedFeatures\": ").append(defaultedFeatures.toCanonicalStringArrayJson()).append(",\n")
        append("  \"requiredRefusals\": ").append(requiredRefusals.toCanonicalStringArrayJson()).append(",\n")
        append("  \"tableAvailability\": {\"gdef\": ").append(hasGdef).append(", \"gsub\": ").append(hasGsub).append(", \"gpos\": ").append(hasGpos).append("}")
        if (expectedDiagnostics.isNotEmpty()) {
            append(",\n")
            append("  \"expectedDiagnostics\": ").append(expectedDiagnostics.toCanonicalStringArrayJson())
        }
        append("\n}")
    }

    private fun ArabicGsubTraceCase.toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"caseId\": ").append(jsonString(caseId)).append(",\n")
        append("  \"ticketId\": \"KFONT-M6-007\",\n")
        append("  \"status\": ").append(jsonString(status)).append(",\n")
        append("  \"reportRef\": ").append(jsonString(reportRef)).append(",\n")
        append("  \"fixtureFont\": ").append(jsonString(fixtureFont)).append(",\n")
        append("  \"inputText\": ").append(jsonString(inputText)).append(",\n")
        append("  \"typefaceId\": ").append(jsonString(typefaceId.value.toString())).append(",\n")
        append("  \"featureOrder\": ").append(featureOrder.toCanonicalStringArrayJson()).append(",\n")
        append("  \"inputGlyphIds\": ").append(inputGlyphIds.toCanonicalIntArrayJson()).append(",\n")
        append("  \"outputGlyphIds\": ").append(outputGlyphIds.toCanonicalIntArrayJson()).append(",\n")
        append("  \"lookups\": ").append(lookups.toCanonicalGsubLookupArrayJson())
        if (diagnostics.isNotEmpty()) {
            append(",\n")
            append("  \"diagnostics\": ").append(diagnostics.toCanonicalDiagnosticsJson())
        }
        append("\n}")
    }

    private fun ArabicGposTraceCase.toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"caseId\": ").append(jsonString(caseId)).append(",\n")
        append("  \"ticketId\": \"KFONT-M6-007\",\n")
        append("  \"status\": ").append(jsonString(status)).append(",\n")
        append("  \"reportRef\": ").append(jsonString(reportRef)).append(",\n")
        append("  \"fixtureFont\": ").append(jsonString(fixtureFont)).append(",\n")
        append("  \"inputText\": ").append(jsonString(inputText)).append(",\n")
        append("  \"typefaceId\": ").append(jsonString(typefaceId.value.toString())).append(",\n")
        append("  \"featureOrder\": ").append(featureOrder.toCanonicalStringArrayJson()).append(",\n")
        append("  \"glyphIds\": ").append(glyphIds.toCanonicalIntArrayJson()).append(",\n")
        append("  \"before\": ").append(before.toCanonicalLongMatrixJson()).append(",\n")
        append("  \"after\": ").append(after.toCanonicalLongMatrixJson()).append(",\n")
        append("  \"lookups\": ").append(lookups.toCanonicalGposLookupArrayJson())
        if (diagnostics.isNotEmpty()) {
            append(",\n")
            append("  \"diagnostics\": ").append(diagnostics.toCanonicalDiagnosticsJson())
        }
        append("\n}")
    }

    private fun List<Int>.toCanonicalIntArrayJson(): String =
        joinToString(prefix = "[", postfix = "]")

    private fun List<String>.toCanonicalStringArrayJson(): String =
        joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }

    private fun List<List<Long>>.toCanonicalLongMatrixJson(): String =
        joinToString(prefix = "[", postfix = "]") { row -> row.joinToString(prefix = "[", postfix = "]") }

    private fun List<io.ygdrasil.kalligraphie.text.shaping.GlyphCluster>.toCanonicalClusterArrayJson(): String =
        joinToString(prefix = "[", postfix = "]") { cluster ->
            """{"textRange":${jsonString(cluster.textRange.toRangeLabel())},"glyphRange":${jsonString(cluster.glyphRange.toRangeLabel())}}"""
        }

    private fun List<io.ygdrasil.kalligraphie.text.shaping.GlyphCluster>.toCanonicalClusterMetricsJson(): String =
        joinToString(prefix = "[", postfix = "]") { cluster ->
            "[${cluster.advanceX.toTenthsJson()}, ${cluster.offsetX.toTenthsJson()}, ${cluster.offsetY.toTenthsJson()}]"
        }

    private fun List<io.ygdrasil.kalligraphie.text.shaping.ShapingDiagnostic>.toCanonicalDiagnosticsJson(): String =
        joinToString(prefix = "[", postfix = "]") { diagnostic ->
            buildString {
                append("{")
                append(""""code": """).append(jsonString(diagnostic.code))
                append(", ").append(""""message": """).append(jsonString(diagnostic.message))
                diagnostic.textRange?.let { range ->
                    append(", ").append(""""textRange": """).append(jsonString(range.toRangeLabel()))
                }
                append("}")
            }
        }

    private fun Float.toTenthsJson(): String =
        (this * 10f).roundToLong().toString()

    private fun List<RuntimeGsubLookupTrace>.toCanonicalGsubLookupArrayJson(): String =
        joinToString(prefix = "[", postfix = "]") { lookup ->
            buildString {
                append("{")
                append(""""lookupIndex": ${lookup.lookupIndex}""")
                append(", ").append(""""lookupType": ${lookup.lookupType}""")
                append(", ").append(""""featureTag": """).append(jsonString(lookup.featureTag))
                append(", ").append(""""inputGlyphIds": """).append(lookup.inputGlyphIds.toCanonicalIntArrayJson())
                append(", ").append(""""outputGlyphIds": """).append(lookup.outputGlyphIds.toCanonicalIntArrayJson())
                lookup.contextFormat?.let { contextFormat ->
                    append(", ").append(""""contextFormat": """).append(contextFormat)
                }
                append(", ").append(""""clusterAction": """).append(jsonString(lookup.clusterAction))
                append("}")
            }
        }

    private fun List<RuntimeGposLookupTrace>.toCanonicalGposLookupArrayJson(): String =
        joinToString(prefix = "[", postfix = "]") { lookup ->
            buildString {
                append("{")
                append(""""lookupIndex": ${lookup.lookupIndex}""")
                append(", ").append(""""lookupType": ${lookup.lookupType}""")
                append(", ").append(""""featureTag": """).append(jsonString(lookup.featureTag))
                append(", ").append(""""matchedGlyphIds": """).append(lookup.matchedGlyphIds.toCanonicalIntArrayJson())
                if (lookup.glyphClasses.isNotEmpty()) {
                    append(", ").append(""""glyphClasses": """).append(lookup.glyphClasses.toCanonicalIntArrayJson())
                }
                lookup.markClass?.let { markClass ->
                    append(", ").append(""""markClass": """).append(markClass)
                }
                if (lookup.anchorFormats.isNotEmpty()) {
                    append(", ").append(""""anchorFormats": """).append(lookup.anchorFormats.toCanonicalIntArrayJson())
                }
                if (lookup.attachmentVector.isNotEmpty()) {
                    append(", ").append(""""attachmentVector": """).append(lookup.attachmentVector.toCanonicalIntArrayJson())
                }
                if (lookup.cursiveChain.isNotEmpty()) {
                    append(", ").append(""""cursiveChain": """).append(lookup.cursiveChain.toCanonicalIntMatrixJson())
                }
                append("}")
            }
        }

    private fun List<List<Int>>.toCanonicalIntMatrixJson(): String =
        joinToString(prefix = "[", postfix = "]") { row -> row.joinToString(prefix = "[", postfix = "]") }

    private fun io.ygdrasil.kalligraphie.text.shaping.ScriptItemizationRun.toCanonicalJson(): String = buildString {
        append("{")
        append(""""clusterRange": """).append(jsonString(clusterRange.toRangeLabel()))
        append(", ").append(""""utf16Range": """).append(jsonString(utf16Range.toRangeLabel()))
        append(", ").append(""""codePointRange": """).append(jsonString(codePointRange.toRangeLabel()))
        append(", ").append(""""selectedScript": """).append(jsonString(selectedScript))
        append(", ").append(""""openTypeScriptTags": """).append(openTypeScriptTags.toCanonicalStringArrayJson())
        append(", ").append(""""extensionCandidates": """).append(extensionCandidates.toCanonicalStringArrayJson())
        append(", ").append(""""languageHint": """).append(languageHint?.let(::jsonString) ?: "null")
        append(", ").append(""""reason": """).append(jsonString(reason))
        append("}")
    }

    private fun IntRange.toRangeLabel(): String =
        if (isEmpty()) "" else "$first..$last"

    private fun sha256Utf8(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun jsonString(value: String): String =
        "\"" + jsonStringContent(value) + "\""

    private fun jsonStringContent(value: String): String = buildString {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
    }

    private fun Map<String, Any?>.requiredLong(key: String): Long =
        this[key] as? Long ?: error("Expected $key to be a number")

    private fun Map<String, Any?>.requiredString(key: String): String =
        this[key] as? String ?: error("Expected $key to be a string")

    private fun Map<String, Any?>.requiredObjectList(key: String): List<Map<String, Any?>> =
        requiredList(key).mapIndexed { index, value -> jsonObject(value, "$key[$index]") }

    private fun Map<String, Any?>.requiredStringList(key: String): List<String> =
        requiredList(key).mapIndexed { index, value ->
            value as? String ?: error("Expected $key[$index] to be a string")
        }

    private fun Map<String, Any?>.requiredList(key: String): List<Any?> =
        jsonList(this[key], key)

    @Suppress("UNCHECKED_CAST")
    private fun jsonObject(value: Any?, path: String): Map<String, Any?> =
        (value as? Map<String, Any?>) ?: error("Expected $path to be a JSON object")

    @Suppress("UNCHECKED_CAST")
    private fun jsonList(value: Any?, path: String): List<Any?> =
        (value as? List<Any?>) ?: error("Expected $path to be a JSON array")

    private fun assertNoSupportPromotionClaims(value: Any?, path: String = "$", insideNonClaims: Boolean = false) {
        when (value) {
            is Map<*, *> -> value.forEach { (key, child) ->
                assertTrue(key is String, "Expected JSON object key at $path to be a string")
                assertFalse(key == "supportClaim", "Unexpected supportClaim key at $path")
                assertNoSupportPromotionClaims(child, "$path.$key", insideNonClaims = key == "nonClaims")
            }
            is List<*> -> value.forEachIndexed { index, child ->
                assertNoSupportPromotionClaims(child, "$path[$index]", insideNonClaims)
            }
            is String -> if (!insideNonClaims) {
                val normalized = value.lowercase()
                assertFalse(normalized.contains("supportclaim"), "Unexpected support claim value at $path: $value")
                assertFalse(
                    normalized.contains("supported") &&
                        !normalized.contains("unsupported") &&
                        !normalized.contains("not supported"),
                    "Unexpected support promotion value at $path: $value",
                )
            }
        }
    }

    private class JsonParser(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            val value = parseValue()
            skipWhitespace()
            require(index == source.length) { "Unexpected trailing JSON content at offset $index" }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val result = linkedMapOf<String, Any?>()
            if (consumeIf('}')) return result
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                if (consumeIf('}')) return result
                expect(',')
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val result = mutableListOf<Any?>()
            if (consumeIf(']')) return result
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (consumeIf(']')) return result
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                val ch = source[index++]
                when (ch) {
                    '"' -> return result.toString()
                    '\\' -> result.append(parseEscape())
                    else -> result.append(ch)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseEscape(): Char =
            when (val escaped = source.getOrNull(index++) ?: error("Unterminated JSON escape")) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    val hex = source.substring(index, index + 4)
                    index += 4
                    hex.toInt(16).toChar()
                }
                else -> error("Unsupported JSON escape \\$escaped at offset ${index - 1}")
            }

        private fun parseNumber(): Long {
            val start = index
            if (peek() == '-') index += 1
            while (peekOrNull()?.isDigit() == true) index += 1
            require(start < index) { "Expected JSON value at offset $index" }
            return source.substring(start, index).toLong()
        }

        private fun parseLiteral(token: String, value: Any?): Any? {
            require(source.startsWith(token, index)) { "Expected $token at offset $index" }
            index += token.length
            return value
        }

        private fun skipWhitespace() {
            while (peekOrNull()?.isWhitespace() == true) index += 1
        }

        private fun expect(expected: Char) {
            require(consumeIf(expected)) { "Expected '$expected' at offset $index" }
        }

        private fun consumeIf(expected: Char): Boolean =
            if (peekOrNull() == expected) {
                index += 1
                true
            } else {
                false
            }

        private fun peek(): Char =
            peekOrNull() ?: error("Unexpected end of JSON input at offset $index")

        private fun peekOrNull(): Char? =
            source.getOrNull(index)
    }
}
