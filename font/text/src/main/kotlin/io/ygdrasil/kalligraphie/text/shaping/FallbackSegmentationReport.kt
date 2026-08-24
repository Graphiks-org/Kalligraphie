package io.ygdrasil.kalligraphie.text.shaping

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import io.ygdrasil.kalligraphie.font.FallbackEvidenceCase
import io.ygdrasil.kalligraphie.font.ResolvedFontRunEvidence
import io.ygdrasil.kalligraphie.font.defaultFallbackClusterEvidenceBundle
import io.ygdrasil.kalligraphie.font.defaultFallbackClusterEvidenceCases

public data class FallbackSegmentationDumpRef(
    val dumpId: String,
    val dumpSha256: String,
)

public data class FallbackSegmentationInvariantResult(
    val name: String,
    val clusterTextRanges: List<IntRange>,
    val fallbackRunRanges: List<IntRange>,
    val passed: Boolean,
    val diagnostic: ShapingDiagnostic?,
)

public data class FallbackSegmentationFixtureRef(
    val dumpId: String,
    val fixtureId: String,
    val section: String? = null,
)

public data class FallbackSegmentationHostDependentMarker(
    val name: String,
    val normativeStatus: String,
    val hostDependent: Boolean,
    val hostDependentSourceRef: FallbackSegmentationFixtureRef,
)

public data class FallbackSegmentationCase(
    val fixtureName: String,
    val sourceText: String,
    val inputTextHash: String,
    val legacyGate: String?,
    val clusterFixtureName: String,
    val fallbackFixtureId: String,
    val sourceDumpIds: List<String>,
    val decisionTraceRef: FallbackSegmentationFixtureRef,
    val resolvedRunsRef: FallbackSegmentationFixtureRef,
    val fixtureAssetRef: FallbackSegmentationFixtureRef,
    val hostDependent: Boolean,
    val invariant: FallbackSegmentationInvariantResult,
    val diagnostics: List<ShapingDiagnostic>,
)

public data class FallbackSegmentationReport(
    val unicodeVersion: String,
    val sourceDumpRefs: List<FallbackSegmentationDumpRef>,
    val cases: List<FallbackSegmentationCase>,
    val hostDependentMarkers: List<FallbackSegmentationHostDependentMarker>,
) {
    public fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"fallback-segmentation-report\",\n")
        append("  \"ownerTickets\": [\"KFONT-M7-004\"],\n")
        appendJsonField("unicodeVersion", unicodeVersion, comma = true)
        appendJsonField("sourceTextHashAlgorithm", FALLBACK_SEGMENTATION_SOURCE_HASH_ALGORITHM, comma = true)
        append("  \"sourceDumpRefs\": [\n")
        append(sourceDumpRefs.joinToString(",\n") { ref -> ref.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"cases\": [\n")
        append(cases.joinToString(",\n") { case -> case.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"hostDependentMarkers\": [\n")
        append(hostDependentMarkers.joinToString(",\n") { marker -> marker.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"nonClaims\": [\n")
        append(FallbackSegmentationNonClaims.joinToString(",\n") { nonClaim -> "    ${jsonString(nonClaim)}" })
        append("\n  ]\n")
        append("}\n")
    }
}

public fun defaultFallbackSegmentationReport(): FallbackSegmentationReport {
    val clusterCasesByFixtureName = defaultClusterSafetyReport().cases.associateBy { case -> case.fixtureName }
    val fallbackCasesByFixtureId = defaultFallbackClusterEvidenceCases().associateBy { case -> case.fixtureId }
    val sourceDumpRefs = defaultFallbackSegmentationDumpRefs()
    val clusterReport = defaultClusterSafetyReport()

    return FallbackSegmentationReport(
            unicodeVersion = clusterReport.unicodeVersion,
            sourceDumpRefs = sourceDumpRefs.sortedBy { ref -> ref.dumpId },
            cases =
                defaultFallbackSegmentationFixtureSpecs()
                    .sortedBy { spec -> spec.fixtureName }
                .map { spec ->
                    val clusterCase =
                        requireNotNull(clusterCasesByFixtureName[spec.clusterFixtureName]) {
                            "Missing cluster-safety case ${spec.clusterFixtureName}."
                        }
                    val fallbackCase =
                        requireNotNull(fallbackCasesByFixtureId[spec.fallbackFixtureId]) {
                            "Missing fallback cluster case ${spec.fallbackFixtureId}."
                        }
                    buildFallbackSegmentationCase(spec, clusterCase, fallbackCase)
                },
            hostDependentMarkers = listOf(
                FallbackSegmentationHostDependentMarker(
                    name = "host-dependent-system-fallback",
                    normativeStatus = "non-normative",
                    hostDependent = true,
                    hostDependentSourceRef = FallbackSegmentationFixtureRef(
                        dumpId = "fallback-decision-trace",
                        fixtureId = "host-dependent-system-fallback",
                    ),
                ),
            ),
    )
}

public fun defaultFallbackSegmentationReportJson(): String =
    defaultFallbackSegmentationReport().toCanonicalJson()

private data class FallbackSegmentationFixtureSpec(
    val fixtureName: String,
    val clusterFixtureName: String,
    val fallbackFixtureId: String,
    val legacyGate: String? = null,
)

private fun defaultFallbackSegmentationFixtureSpecs(): List<FallbackSegmentationFixtureSpec> =
    listOf(
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-arabic-mark.txt",
            clusterFixtureName = "cluster-arabic-mark.txt",
            fallbackFixtureId = "fallback-cluster-arabic-mark",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-cjk-vs.txt",
            clusterFixtureName = "cluster-cjk-variation-selector.txt",
            fallbackFixtureId = "fallback-cluster-cjk-vs",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-devanagari.txt",
            clusterFixtureName = "cluster-devanagari-conjunct.txt",
            fallbackFixtureId = "fallback-cluster-devanagari",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-emoji-zwj.txt",
            clusterFixtureName = "cluster-emoji-family-zwj.txt",
            fallbackFixtureId = "fallback-cluster-emoji-zwj",
            legacyGate = "scaledemoji",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-latin-mark.txt",
            clusterFixtureName = "cluster-negative-split.txt",
            fallbackFixtureId = "fallback-cluster-latin-mark",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-negative-split.txt",
            clusterFixtureName = "cluster-emoji-family-zwj.txt",
            fallbackFixtureId = "fallback-cluster-negative-split",
            legacyGate = "scaledemoji",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-skin-tone.txt",
            clusterFixtureName = "cluster-emoji-skin-tone.txt",
            fallbackFixtureId = "fallback-cluster-skin-tone",
            legacyGate = "scaledemoji",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-thai.txt",
            clusterFixtureName = "cluster-thai-tone.txt",
            fallbackFixtureId = "fallback-cluster-thai",
        ),
        FallbackSegmentationFixtureSpec(
            fixtureName = "fallback-cluster-vs15-vs16.txt",
            clusterFixtureName = "cluster-vs15-vs16.txt",
            fallbackFixtureId = "fallback-cluster-vs15-vs16",
            legacyGate = "scaledemoji",
        ),
    )

private fun buildFallbackSegmentationCase(
    spec: FallbackSegmentationFixtureSpec,
    clusterCase: ClusterSafetyCase,
    fallbackCase: FallbackEvidenceCase,
): FallbackSegmentationCase {
    val clusterRanges =
        clusterCase.invariants
            .first { invariant -> invariant.name == "grapheme-cluster-invariants" }
            .segmentRanges
    val fallbackRunRanges = fallbackCase.runs.map { run -> run.toInclusiveRange() }
    val invariant = evaluateFallbackBoundaryInvariant(clusterRanges, fallbackRunRanges)
    val diagnostics =
        buildList {
            addAll(fallbackCase.diagnostics.map { code -> fallbackDiagnostic(code, clusterRanges.firstOrNull() ?: 0..0) })
            invariant.diagnostic?.let(::add)
        }.distinctBy { diagnostic -> diagnostic.code to diagnostic.textRange }

    return FallbackSegmentationCase(
        fixtureName = spec.fixtureName,
        sourceText = fallbackCase.request.text,
        inputTextHash = fallbackCase.request.text.sourceTextHash(),
        legacyGate = spec.legacyGate,
        clusterFixtureName = spec.clusterFixtureName,
        fallbackFixtureId = spec.fallbackFixtureId,
        sourceDumpIds = FALLBACK_SEGMENTATION_SOURCE_DUMP_IDS,
        decisionTraceRef = FallbackSegmentationFixtureRef(
            dumpId = "fallback-fixture",
            fixtureId = spec.fallbackFixtureId,
            section = "decisions",
        ),
        resolvedRunsRef = FallbackSegmentationFixtureRef(
            dumpId = "fallback-fixture",
            fixtureId = spec.fallbackFixtureId,
            section = "runs",
        ),
        fixtureAssetRef = FallbackSegmentationFixtureRef(
            dumpId = "fallback-fixture",
            fixtureId = spec.fallbackFixtureId,
        ),
        hostDependent = false,
        invariant = invariant,
        diagnostics = diagnostics,
    )
}

private fun evaluateFallbackBoundaryInvariant(
    clusterRanges: List<IntRange>,
    fallbackRunRanges: List<IntRange>,
): FallbackSegmentationInvariantResult {
    val sortedClusters = clusterRanges.sortedBy { range -> range.first }
    val sortedRuns = fallbackRunRanges.sortedBy { range -> range.first }
    for (runRange in sortedRuns) {
        val overlappingClusters = sortedClusters.filter { clusterRange -> clusterRange.overlaps(runRange) }
        if (overlappingClusters.isEmpty()) {
            return failedFallbackInvariant(
                clusterRanges = sortedClusters,
                fallbackRunRanges = sortedRuns,
                textRange = runRange,
                reason = "fallback run range ${runRange.toRangeLabel()} does not align to any grapheme cluster",
            )
        }
        val expectedRange = overlappingClusters.first().first..overlappingClusters.last().last
        if (runRange != expectedRange) {
            return failedFallbackInvariant(
                clusterRanges = sortedClusters,
                fallbackRunRanges = sortedRuns,
                textRange = runRange,
                reason = "fallback run range ${runRange.toRangeLabel()} splits cluster coverage ${expectedRange.toRangeLabel()}",
            )
        }
    }
    return FallbackSegmentationInvariantResult(
        name = "resolved-run-boundaries-align",
        clusterTextRanges = sortedClusters,
        fallbackRunRanges = sortedRuns,
        passed = true,
        diagnostic = null,
    )
}

private fun failedFallbackInvariant(
    clusterRanges: List<IntRange>,
    fallbackRunRanges: List<IntRange>,
    textRange: IntRange,
    reason: String,
): FallbackSegmentationInvariantResult =
    FallbackSegmentationInvariantResult(
        name = "resolved-run-boundaries-align",
        clusterTextRanges = clusterRanges,
        fallbackRunRanges = fallbackRunRanges,
        passed = false,
        diagnostic = ShapingDiagnostic(
            code = TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE,
            message = "Fallback segmentation invariant failed for UTF-16 range ${textRange.toRangeLabel()}: $reason.",
            textRange = textRange,
        ),
    )

private fun FallbackSegmentationDumpRef.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("dumpId", dumpId)).append(", ")
    append(jsonPair("dumpSha256", dumpSha256))
    append("}")
}

private fun FallbackSegmentationCase.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("fixtureName", fixtureName)).append(", ")
    append(jsonPair("sourceText", sourceText)).append(", ")
    append(jsonPair("inputTextHash", inputTextHash)).append(", ")
    append(jsonPair("gate", legacyGate)).append(", ")
    append(jsonPair("clusterFixtureName", clusterFixtureName)).append(", ")
    append(jsonPair("fallbackFixtureId", fallbackFixtureId)).append(", ")
    append(jsonString("sourceDumpIds")).append(": ").append(sourceDumpIds.toJsonStringArray()).append(", ")
    append(jsonString("decisionTraceRef")).append(": ").append(decisionTraceRef.toCanonicalJson()).append(", ")
    append(jsonString("resolvedRunsRef")).append(": ").append(resolvedRunsRef.toCanonicalJson()).append(", ")
    append(jsonString("fixtureAssetRef")).append(": ").append(fixtureAssetRef.toCanonicalJson()).append(", ")
    append(jsonString("hostDependent")).append(": ").append(hostDependent).append(", ")
    append(jsonString("invariant")).append(": ").append(invariant.toCanonicalJson()).append(", ")
    append(jsonString("diagnostics")).append(": ").append(diagnostics.toDiagnosticArrayJson())
    append("}")
}

private fun FallbackSegmentationFixtureRef.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("dumpId", dumpId)).append(", ")
    append(jsonPair("fixtureId", fixtureId))
    if (section != null) {
        append(", ")
        append(jsonPair("section", section))
    }
    append("}")
}

private fun FallbackSegmentationHostDependentMarker.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("name", name)).append(", ")
    append(jsonPair("normativeStatus", normativeStatus)).append(", ")
    append(jsonString("hostDependent")).append(": ").append(hostDependent).append(", ")
    append(jsonString("hostDependentSourceRef")).append(": ").append(hostDependentSourceRef.toCanonicalJson())
    append("}")
}

private fun FallbackSegmentationInvariantResult.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("name", name)).append(", ")
    append(jsonString("clusterTextRanges")).append(": ").append(clusterTextRanges.toRangeArrayJson()).append(", ")
    append(jsonString("fallbackRunRanges")).append(": ").append(fallbackRunRanges.toRangeArrayJson()).append(", ")
    append(jsonString("passed")).append(": ").append(passed).append(", ")
    append(jsonString("diagnostic")).append(": ").append(diagnostic?.toCanonicalJson() ?: "null")
    append("}")
}

private fun List<String>.toJsonStringArray(): String =
    joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }

private fun List<IntRange>.toRangeArrayJson(): String =
    joinToString(prefix = "[", postfix = "]") { range -> jsonString(range.toRangeLabel()) }

private fun List<ShapingDiagnostic>.toDiagnosticArrayJson(): String =
    joinToString(prefix = "[", postfix = "]") { diagnostic -> diagnostic.toCanonicalJson() }

private fun ShapingDiagnostic.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("code", code)).append(", ")
    append(jsonPair("severity", "refusal")).append(", ")
    append(jsonPair("textRange", textRange?.toRangeLabel())).append(", ")
    append(jsonPair("message", message))
    append("}")
}

private fun fallbackDiagnostic(code: String, textRange: IntRange): ShapingDiagnostic =
    when (code) {
        "font.fallback-glyph-unavailable" ->
            ShapingDiagnostic(
                code = code,
                message = "Fallback cluster case has no deterministic glyph coverage for the full cluster range ${textRange.toRangeLabel()}.",
                textRange = textRange,
            )
        "text.shaping.emoji-sequence-unsupported" ->
            ShapingDiagnostic(
                code = code,
                message = "Emoji sequence fallback stays refused until the whole cluster can be handled without splitting.",
                textRange = textRange,
            )
        "text.shaping.fallback-missing" ->
            ShapingDiagnostic(
                code = code,
                message = "Fallback cluster case still routes through a missing-glyph shaping diagnostic.",
                textRange = textRange,
            )
        "text.fallback.cluster-split-forbidden" ->
            ShapingDiagnostic(
                code = code,
                message = "Fallback cluster case refuses the whole cluster rather than splitting grapheme coverage.",
                textRange = textRange,
            )
        "text.fallback.emoji-fallback-unavailable" ->
            ShapingDiagnostic(
                code = code,
                message = "Emoji fallback remains refused until the full cluster can be handled without partial fallback.",
                textRange = textRange,
            )
        else ->
            ShapingDiagnostic(
                code = code,
                message = "Fallback segmentation report propagated stable diagnostic $code.",
                textRange = textRange,
            )
    }

private fun ResolvedFontRunEvidence.toInclusiveRange(): IntRange =
    start..(end - 1)

private fun defaultFallbackSegmentationDumpRefs(): List<FallbackSegmentationDumpRef> =
    listOf(
        "font/fixtures/expected/unicode/cluster-safety-report.json" to "cluster-safety-report",
        "font/fixtures/expected/fallback/fallback-decision-trace.json" to "fallback-decision-trace",
        "font/fixtures/expected/fallback/resolved-font-runs.json" to "resolved-font-runs",
        "font/fixtures/expected/shaping/shaped-glyph-run.json" to "shaped-glyph-run",
    ).map { (relativePath, dumpId) ->
        FallbackSegmentationDumpRef(
            dumpId = dumpId,
            dumpSha256 = sha256(projectRoot().resolve(relativePath).let(Files::readString)),
        )
    }

private fun String.sourceTextHash(): String {
    val bytes = ByteArray(length * 2)
    forEachIndexed { index, char ->
        val value = char.code
        bytes[index * 2] = (value ushr 8).toByte()
        bytes[index * 2 + 1] = value.toByte()
    }
    return sha256(bytes)
}

private fun sha256(text: String): String =
    sha256(text.toByteArray(Charsets.UTF_8))

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

private fun projectRoot(): Path =
    generateSequence(Paths.get("").toAbsolutePath()) { path -> path.parent }
        .first { path -> Files.exists(path.resolve("settings.gradle.kts")) }

private fun StringBuilder.appendJsonField(name: String, value: String, comma: Boolean) {
    append("  ").append(jsonString(name)).append(": ").append(jsonString(value))
    if (comma) append(",")
    append("\n")
}

private fun jsonPair(name: String, value: String?): String =
    jsonString(name) + ": " + if (value == null) "null" else jsonString(value)

private fun jsonString(value: String): String = buildString {
    append('"')
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
    append('"')
}

private fun IntRange.toRangeLabel(): String =
    if (isEmpty()) "" else "$first..$last"

private fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last

private const val FALLBACK_SEGMENTATION_SOURCE_HASH_ALGORITHM = "sha256-utf16-code-units"

private val FALLBACK_SEGMENTATION_SOURCE_DUMP_IDS =
    listOf(
        "cluster-safety-report",
        "fallback-decision-trace",
        "resolved-font-runs",
        "shaped-glyph-run",
    )

private val FallbackSegmentationNonClaims = listOf(
    "no-complete-target-support-claim",
    "no-cluster-safe-fallback-support-claim",
    "no-emoji-rendering-claim",
    "no-platform-font-fallback-claim",
    "no-gpu-text-route-claim",
    "no-scaledemoji-retirement",
)
