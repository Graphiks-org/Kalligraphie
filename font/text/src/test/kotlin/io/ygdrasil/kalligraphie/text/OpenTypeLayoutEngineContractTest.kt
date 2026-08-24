package io.ygdrasil.kalligraphie.text

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.text.shaping.GraphemeClusterer
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeDirectGlyphInput
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeLayoutEngine
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeLayoutEngineContract
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeLookupTraceRequest
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeRunInput
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeShapingPlanCase
import io.ygdrasil.kalligraphie.text.shaping.OpenTypeTableAvailability
import io.ygdrasil.kalligraphie.text.shaping.PinnedUnicodeDataSetResources
import io.ygdrasil.kalligraphie.text.shaping.RequiredScriptFeaturePolicies
import io.ygdrasil.kalligraphie.text.shaping.ResolvedFeatureSet
import io.ygdrasil.kalligraphie.text.shaping.ScriptExtensionsItemizer
import io.ygdrasil.kalligraphie.text.shaping.ShapingFeatureRequest
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_FEATURE_UNSUPPORTED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_LOOKUP_TYPE_UNSUPPORTED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.TEXT_SHAPING_SCRIPT_UNSUPPORTED_DIAGNOSTIC_CODE
import io.ygdrasil.kalligraphie.text.shaping.openTypeShapingPlanCasesToCanonicalJson

class OpenTypeLayoutEngineContractTest {
    private val unicodeData = PinnedUnicodeDataSetResources.load()
    private val typefaceId = TypefaceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440601"))
    private val engine = OpenTypeLayoutEngineContract { _, codePoint ->
        when (codePoint) {
            'A'.code -> 36
            'B'.code -> 37
            else -> null
        }
    }

    @Test
    fun noOpLatinContractPreservesClustersFeaturesAndTraceRefs() {
        val result = engine.shape(latinRunInput())

        assertEquals(listOf(36, 37), result.shapedRun.glyphs.map { it.glyphId })
        assertEquals(listOf(0..0, 1..1), result.shapedRun.clusters.map { it.sourceUtf16Range })
        assertEquals("Latn", result.shapingPlan.scriptRun.selectedScript)
        assertEquals(listOf("latn"), result.shapingPlan.scriptRun.openTypeScriptTags)
        assertEquals(
            listOf("ccmp", "locl", "liga", "rlig", "clig", "calt", "kern", "mark", "mkmk"),
            result.shapingPlan.features.enabled.map { it.tag },
        )
        assertEquals(listOf("ccmp", "locl", "rlig", "clig", "calt", "mark", "mkmk"), result.shapingPlan.features.defaulted.map { it.tag })
        assertEquals(emptyList(), result.shapingPlan.features.unsupported)
        assertEquals("dflt", result.shapingPlan.languageSystem)
        assertEquals("opentype-layout-contract-gsub-trace", result.shapingPlan.gsubTraceRef)
        assertEquals("opentype-layout-contract-gpos-trace", result.shapingPlan.gposTraceRef)
        assertEquals("no-op-contract", result.gsubTrace.events.single().decision)
        assertEquals("no-op-contract", result.gposTrace.events.single().decision)
        assertEquals(emptyList(), result.diagnostics)
    }

    @Test
    fun directGlyphInputBypassesGsubAndKeepsSyntheticClusterFacts() {
        val result = engine.shape(
            latinRunInput(
                tableAvailability = OpenTypeTableAvailability(gdef = false, gsub = false, gpos = false),
                directGlyphInput = OpenTypeDirectGlyphInput(
                    glyphIds = listOf(501, 502),
                    sourceUtf16Ranges = listOf(0..0, 1..1),
                ),
            ),
        )

        assertEquals(listOf(501, 502), result.shapedRun.glyphs.map { it.glyphId })
        assertEquals(listOf("direct-glyph-input", "direct-glyph-input"), result.shapedRun.glyphs.map { it.source })
        assertEquals("direct-glyph-input-bypass", result.gsubTrace.events.single().decision)
        assertEquals("direct-glyph-input-bypass", result.gposTrace.events.single().decision)
        assertTrue(result.shapingPlan.directGlyphInput)
        assertFalse(result.diagnostics.map { it.code }.contains(TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE))
        assertEquals(emptyList(), result.gsubTrace.diagnostics)
        assertEquals(emptyList(), result.gposTrace.diagnostics)
    }

    @Test
    fun directGlyphInputRequiresTypefaceAndMatchingSyntheticClusterFacts() {
        val missingTypeface = engine.shape(
            latinRunInput(
                typefaceId = null,
                directGlyphInput = OpenTypeDirectGlyphInput(
                    glyphIds = listOf(501, 502),
                    sourceUtf16Ranges = listOf(0..0, 1..1),
                ),
            ),
        )
        assertTrue(missingTypeface.diagnostics.map { it.code }.contains(TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE))

        val mismatchedSyntheticClusters = engine.shape(
            latinRunInput(
                directGlyphInput = OpenTypeDirectGlyphInput(
                    glyphIds = listOf(501, 502),
                    sourceUtf16Ranges = listOf(0..0),
                ),
            ),
        )
        assertTrue(
            mismatchedSyntheticClusters.diagnostics.map { it.code }
                .contains(TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE),
        )
        assertEquals(listOf(0..0), mismatchedSyntheticClusters.shapedRun.glyphs.map { it.sourceUtf16Range })
        assertEquals(listOf(0..0), mismatchedSyntheticClusters.shapedRun.clusters.map { it.sourceUtf16Range })
    }

    @Test
    fun contractDiagnosticsCoverMissingTablesUnsupportedLookupsFallbackAndClusterInvariant() {
        val missingTables = engine.shape(
            latinRunInput(
                tableAvailability = OpenTypeTableAvailability(gdef = false, gsub = false, gpos = false),
            ),
        )
        val missingTableDiagnostics = missingTables.diagnostics
            .filter { it.code == TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE }
            .map { it.message }
        assertEquals(
            listOf(
                "GSUB table is missing for requested feature set calt,ccmp,clig,kern,liga,locl,mark,mkmk,rlig.",
                "GPOS table is missing for requested feature set calt,ccmp,clig,kern,liga,locl,mark,mkmk,rlig.",
                "GDEF table is missing for mark or ligature feature data.",
            ),
            missingTableDiagnostics,
        )

        val unsupportedAndMalformed = engine.shape(
            latinRunInput(
                featureSet = ResolvedFeatureSet(
                    requested = listOf(ShapingFeatureRequest("salt", 1)),
                    enabled = emptyList(),
                    disabled = listOf(ShapingFeatureRequest("salt", 1)),
                ),
                lookupTraceRequests = listOf(
                    OpenTypeLookupTraceRequest(stage = "GSUB", lookupId = "lookup-7", lookupType = 7, status = "unsupported"),
                    OpenTypeLookupTraceRequest(stage = "GPOS", lookupId = "lookup-2", lookupType = 2, status = "malformed"),
                ),
            ),
        )
        assertTrue(unsupportedAndMalformed.diagnostics.map { it.code }.contains(TEXT_SHAPING_FEATURE_UNSUPPORTED_DIAGNOSTIC_CODE))
        assertTrue(unsupportedAndMalformed.diagnostics.map { it.code }.contains(TEXT_SHAPING_LOOKUP_TYPE_UNSUPPORTED_DIAGNOSTIC_CODE))
        assertTrue(unsupportedAndMalformed.diagnostics.map { it.code }.contains(TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE))
        assertEquals(
            listOf(TEXT_SHAPING_LOOKUP_TYPE_UNSUPPORTED_DIAGNOSTIC_CODE),
            unsupportedAndMalformed.gsubTrace.diagnostics.map { it.code },
        )
        assertEquals(
            listOf(TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE),
            unsupportedAndMalformed.gposTrace.diagnostics.map { it.code },
        )

        val unsupportedScript = engine.shape(unsupportedScriptInput())
        assertEquals(TEXT_SHAPING_SCRIPT_UNSUPPORTED_DIAGNOSTIC_CODE, unsupportedScript.diagnostics.single().code)

        val missingFallback = engine.shape(latinRunInput(typefaceId = null))
        assertTrue(missingFallback.diagnostics.map { it.code }.contains(TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE))

        val clusterInvariant = engine.shape(
            latinRunInput(
                clusterOverride = GraphemeClusterer(unicodeData).segment("A").clusters.single().copy(utf16Range = 3..3).let(::listOf),
            ),
        )
        assertTrue(clusterInvariant.diagnostics.map { it.code }.contains(TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE))
    }

    @Test
    fun shapingContractGoldensAreCanonicalAndNonClaiming() {
        val simpleLatin = engine.shape(latinRunInput())
        val bundle = simpleLatin.toEvidenceBundle()
        val shapingPlanCases = shapingPlanCasesJson()

        assertEquals(readProjectFile("font/fixtures/expected/shaping/shaping-plan.json"), shapingPlanCases)
        assertEquals(
            readProjectFile("font/fixtures/expected/shaping/opentype-layout-contract-gsub-trace.json"),
            bundle.gsubTraceJson,
        )
        assertEquals(
            readProjectFile("font/fixtures/expected/shaping/opentype-layout-contract-gpos-trace.json"),
            bundle.gposTraceJson,
        )
        assertEquals(
            readProjectFile("font/fixtures/expected/shaping/opentype-layout-contract-shaped-glyph-run.json"),
            bundle.shapedGlyphRunJson,
        )

        listOf(
            shapingPlanCases,
            bundle.shapingPlanJson,
            bundle.gsubTraceJson,
            bundle.gposTraceJson,
            bundle.shapedGlyphRunJson,
        ).forEach { json ->
            assertTrue(json.contains("\"ownerTickets\": [\"KFONT-M6-001\"]"))
            assertTrue(json.contains("\"unicodeVersion\": \"16.0.0\""))
            assertTrue(json.contains("\"no-gsub-gpos-lookup-implementation-claim\""))
            assertTrue(json.contains("\"no-native-shaper-oracle-claim\""))
            assertTrue(json.contains("\"no-gpu-text-route-claim\""))
            assertFalse(json.contains("HarfBuzz", ignoreCase = true))
            assertFalse(json.contains("FreeType", ignoreCase = true))
        }
    }

    @Test
    fun shapingPlanGoldenEnumeratesRequiredContractCases() {
        val shapingPlan = readProjectFile("font/fixtures/expected/shaping/shaping-plan.json")

        assertTrue(shapingPlan.contains("\"cases\": ["))
        listOf(
            "\"caseId\": \"simple-latin\"",
            "\"caseId\": \"direct-glyph-run\"",
            "\"caseId\": \"unsupported-script\"",
            "\"caseId\": \"missing-table\"",
            "\"caseId\": \"unsupported-discretionary-feature\"",
        ).forEach { requiredCase ->
            assertTrue(
                actual = shapingPlan.contains(requiredCase),
                message = "shaping-plan.json is missing required contract case $requiredCase",
            )
        }
        assertTrue(shapingPlan.contains("\"directGlyphInput\": true"))
        assertTrue(shapingPlan.contains("\"defaulted\": ["))
        assertTrue(shapingPlan.contains("\"unsupported\": ["))
        assertTrue(shapingPlan.contains("\"languageSystem\": \"dflt\""))
        assertTrue(shapingPlan.contains(TEXT_SHAPING_SCRIPT_UNSUPPORTED_DIAGNOSTIC_CODE))
        assertTrue(shapingPlan.contains(TEXT_SHAPING_ENGINE_CONTRACT_MISSING_DIAGNOSTIC_CODE))
    }

    @Test
    fun featurePolicyMatrixGoldenPinsRequiredScriptRowsAndEvidenceTracking() {
        val matrixPath = projectRoot().resolve("font/fixtures/expected/shaping/feature-policy-matrix.json")
        assertTrue(
            actual = Files.exists(matrixPath),
            message = "feature-policy-matrix.json should be checked in for KFONT-M6-006",
        )
        val matrix = Files.readString(matrixPath)

        listOf(
            "\"scriptFamily\": \"Latin\"",
            "\"scriptFamily\": \"Greek\"",
            "\"scriptFamily\": \"Cyrillic\"",
            "\"scriptFamily\": \"Hebrew\"",
            "\"scriptFamily\": \"Arabic\"",
            "\"scriptFamily\": \"Devanagari\"",
            "\"scriptFamily\": \"Thai\"",
            "\"scriptFamily\": \"CJK\"",
            "\"scriptFamily\": \"Emoji\"",
        ).forEach { requiredRow ->
            assertTrue(
                actual = matrix.contains(requiredRow),
                message = "feature-policy-matrix.json is missing required policy row $requiredRow",
            )
        }
        assertTrue(matrix.contains("\"ownerTickets\": [\"KFONT-M6-006\"]"))
        assertTrue(matrix.contains("\"no-complete-target-support-claim\""))
        assertTrue(matrix.contains("\"no-native-shaper-oracle-claim\""))

        val dumpIndex = readProjectFile("font/fixtures/expected/pure-kotlin-text/dump-evidence-index.json")
        assertTrue(dumpIndex.contains("\"dumpId\": \"feature-policy-matrix\""))

        val manifest = readProjectFile("font/fixtures/expected/pure-kotlin-text/fixture-evidence-manifest.json")
        assertTrue(manifest.contains("\"reports/font/fixtures/expected/shaping/feature-policy-matrix.json\""))

        val dashboard = readProjectFile("font/fixtures/expected/pure-kotlin-text/font-claim-dashboard.json")
        assertTrue(dashboard.contains("\"reports/font/fixtures/expected/shaping/feature-policy-matrix.json\""))
    }

    @Test
    fun lookupTraceGoldensCarryRequiredRunFacts() {
        listOf(
            readProjectFile("font/fixtures/expected/shaping/opentype-layout-contract-gsub-trace.json"),
            readProjectFile("font/fixtures/expected/shaping/opentype-layout-contract-gpos-trace.json"),
        ).forEach { trace ->
            assertTrue(trace.contains("\"typefaceId\": \"550e8400-e29b-41d4-a716-446655440601\""))
            assertTrue(trace.contains("\"scriptRun\": {"))
            assertTrue(trace.contains("\"selectedScript\": \"Latn\""))
            assertTrue(trace.contains("\"openTypeScriptTags\": [\"latn\"]"))
            assertTrue(trace.contains("\"features\": {"))
            assertTrue(trace.contains("\"requested\": [{\"tag\": \"liga\", \"value\": 1}, {\"tag\": \"kern\", \"value\": 1}]"))
            assertTrue(trace.contains("\"enabled\": [{\"tag\": \"ccmp\", \"value\": 1}, {\"tag\": \"locl\", \"value\": 1}, {\"tag\": \"liga\", \"value\": 1}, {\"tag\": \"rlig\", \"value\": 1}, {\"tag\": \"clig\", \"value\": 1}, {\"tag\": \"calt\", \"value\": 1}, {\"tag\": \"kern\", \"value\": 1}, {\"tag\": \"mark\", \"value\": 1}, {\"tag\": \"mkmk\", \"value\": 1}]"))
            assertTrue(trace.contains("\"disabled\": []"))
            assertTrue(trace.contains("\"defaulted\": [{\"tag\": \"ccmp\", \"value\": 1}, {\"tag\": \"locl\", \"value\": 1}, {\"tag\": \"rlig\", \"value\": 1}, {\"tag\": \"clig\", \"value\": 1}, {\"tag\": \"calt\", \"value\": 1}, {\"tag\": \"mark\", \"value\": 1}, {\"tag\": \"mkmk\", \"value\": 1}]"))
            assertTrue(trace.contains("\"unsupported\": []"))
        }
    }

    @Test
    fun dumpIndexManifestAndDashboardTrackOpenTypeLayoutContractWithoutSupportPromotion() {
        val dumpIndex = readProjectFile("font/fixtures/expected/pure-kotlin-text/dump-evidence-index.json")
        assertTrue(dumpIndex.contains("\"dumpId\": \"shaping-plan\""))
        assertTrue(dumpIndex.contains("\"ownerTicket\": \"KFONT-M6-001\""))
        assertTrue(dumpIndex.contains("\"reports/font/fixtures/expected/shaping/opentype-layout-contract-shaped-glyph-run.json\""))

        val manifest = readProjectFile("font/fixtures/expected/pure-kotlin-text/fixture-evidence-manifest.json")
        assertTrue(manifest.contains("\"familyId\": \"opentype-layout-contract\""))
        assertTrue(manifest.contains("\"no-complex-shaping-support-claim\""))

        val dashboard = readProjectFile("font/fixtures/expected/pure-kotlin-text/font-claim-dashboard.json")
        assertTrue(dashboard.contains("\"surfaceId\": \"complex-shaping\""))
        assertTrue(dashboard.contains("\"reports/font/fixtures/expected/shaping/shaping-plan.json\""))
        assertTrue(dashboard.contains("\"claimPromotionAllowed\": false"))
    }

    private fun latinRunInput(
        typefaceId: TypefaceID? = this.typefaceId,
        featureSet: ResolvedFeatureSet = ResolvedFeatureSet(
            requested = listOf(ShapingFeatureRequest("liga", 1), ShapingFeatureRequest("kern", 1)),
            enabled = listOf(
                ShapingFeatureRequest("ccmp", 1),
                ShapingFeatureRequest("locl", 1),
                ShapingFeatureRequest("liga", 1),
                ShapingFeatureRequest("rlig", 1),
                ShapingFeatureRequest("clig", 1),
                ShapingFeatureRequest("calt", 1),
                ShapingFeatureRequest("kern", 1),
                ShapingFeatureRequest("mark", 1),
                ShapingFeatureRequest("mkmk", 1),
            ),
            disabled = emptyList(),
            defaulted = listOf(
                ShapingFeatureRequest("ccmp", 1),
                ShapingFeatureRequest("locl", 1),
                ShapingFeatureRequest("rlig", 1),
                ShapingFeatureRequest("clig", 1),
                ShapingFeatureRequest("calt", 1),
                ShapingFeatureRequest("mark", 1),
                ShapingFeatureRequest("mkmk", 1),
            ),
            unsupported = emptyList(),
            languageSystem = "dflt",
        ),
        tableAvailability: OpenTypeTableAvailability = OpenTypeTableAvailability(),
        lookupTraceRequests: List<OpenTypeLookupTraceRequest> = emptyList(),
        directGlyphInput: OpenTypeDirectGlyphInput? = null,
        clusterOverride: List<io.ygdrasil.kalligraphie.text.shaping.GraphemeCluster>? = null,
    ): OpenTypeRunInput {
        val text = "AB"
        val clusters = clusterOverride ?: GraphemeClusterer(unicodeData).segment(text).clusters
        val scriptRun = ScriptExtensionsItemizer(unicodeData).itemize(text).runs.single()
        return OpenTypeRunInput(
            text = text,
            typefaceId = typefaceId,
            clusters = clusters,
            bidiLevel = 0,
            direction = "LTR",
            scriptRun = scriptRun,
            features = featureSet,
            tableAvailability = tableAvailability,
            fallbackRun = typefaceId?.value?.toString(),
            lookupTraceRequests = lookupTraceRequests,
            directGlyphInput = directGlyphInput,
        )
    }

    private fun unsupportedScriptInput(): OpenTypeRunInput =
        "\u10A0".let { text ->
            val clusters = GraphemeClusterer(unicodeData).segment(text).clusters
            val scriptRun = ScriptExtensionsItemizer(unicodeData).itemize(text).runs.single()
            latinRunInput(
                featureSet = RequiredScriptFeaturePolicies.resolve(
                    scriptRun = scriptRun,
                    requested = listOf(ShapingFeatureRequest("liga", 1), ShapingFeatureRequest("kern", 1)),
                ),
            ).copy(
                text = text,
                clusters = clusters,
                scriptRun = scriptRun,
            )
        }

    private fun shapingPlanCasesJson(): String =
        openTypeShapingPlanCasesToCanonicalJson(
            listOf(
                OpenTypeShapingPlanCase("simple-latin", engine.shape(latinRunInput())),
                OpenTypeShapingPlanCase(
                    "direct-glyph-run",
                    engine.shape(
                        latinRunInput(
                            directGlyphInput = OpenTypeDirectGlyphInput(
                                glyphIds = listOf(501, 502),
                                sourceUtf16Ranges = listOf(0..0, 1..1),
                            ),
                        ),
                    ),
                ),
                OpenTypeShapingPlanCase("unsupported-script", engine.shape(unsupportedScriptInput())),
                OpenTypeShapingPlanCase(
                    "missing-table",
                    engine.shape(
                        latinRunInput(
                            tableAvailability = OpenTypeTableAvailability(gdef = false, gsub = false, gpos = false),
                        ),
                    ),
                ),
                OpenTypeShapingPlanCase(
                    "unsupported-discretionary-feature",
                    engine.shape(
                        latinRunInput(
                            featureSet = RequiredScriptFeaturePolicies.resolve(
                                scriptRun = ScriptExtensionsItemizer(unicodeData).itemize("AB").runs.single(),
                                requested = listOf(ShapingFeatureRequest("salt", 1)),
                            ),
                        ),
                    ),
                ),
            ),
        )

    private fun readProjectFile(relativePath: String): String =
        Files.readString(projectRoot().resolve(relativePath))

    private fun projectRoot(): Path =
        generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("settings.gradle.kts")) }
}
