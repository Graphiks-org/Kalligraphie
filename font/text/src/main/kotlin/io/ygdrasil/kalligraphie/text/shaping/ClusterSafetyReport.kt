package io.ygdrasil.kalligraphie.text.shaping

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

public data class ClusterSafetyFixture(
    val fixtureName: String,
    val sourceText: String,
    val legacyGate: String? = null,
    val syntheticSplitRanges: List<IntRange> = emptyList(),
)

public data class ClusterSafetyDumpRef(
    val dumpId: String,
    val dumpSha256: String,
)

public data class ClusterSafetyInvariantResult(
    val name: String,
    val clusterRange: IntRange,
    val segmentRanges: List<IntRange>,
    val passed: Boolean,
    val diagnostic: ShapingDiagnostic?,
)

public data class ClusterSafetyCase(
    val fixtureName: String,
    val sourceText: String,
    val inputTextHash: String,
    val legacyGate: String?,
    val invariants: List<ClusterSafetyInvariantResult>,
    val diagnostics: List<ShapingDiagnostic>,
)

public data class ClusterSafetyReport(
    val unicodeVersion: String,
    val sourceDumpRefs: List<ClusterSafetyDumpRef>,
    val cases: List<ClusterSafetyCase>,
) {
    public fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"cluster-safety-report\",\n")
        append("  \"ownerTickets\": [\"KFONT-M5-005\"],\n")
        appendJsonField("unicodeVersion", unicodeVersion, comma = true)
        appendJsonField("sourceTextHashAlgorithm", CLUSTER_SAFETY_SOURCE_HASH_ALGORITHM, comma = true)
        append("  \"sourceDumpRefs\": [\n")
        append(sourceDumpRefs.joinToString(",\n") { ref -> ref.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"cases\": [\n")
        append(cases.joinToString(",\n") { case -> case.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"nonClaims\": [\n")
        append(ClusterSafetyNonClaims.joinToString(",\n") { nonClaim -> "    ${jsonString(nonClaim)}" })
        append("\n  ]\n")
        append("}\n")
    }
}

public class ClusterSafetySuite(
    private val unicodeDataSet: UnicodeDataSet = PinnedUnicodeDataSetResources.load(),
    private val expectedUnicodeVersion: String = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
) {
    private val clusterer = GraphemeClusterer(unicodeDataSet, expectedUnicodeVersion)
    private val bidiResolver = DefaultBidiResolver(unicodeDataSet, expectedUnicodeVersion)
    private val scriptItemizer = ScriptExtensionsItemizer(unicodeDataSet, expectedUnicodeVersion)

    public fun evaluate(fixtures: List<ClusterSafetyFixture>, sourceDumpRefs: List<ClusterSafetyDumpRef>): ClusterSafetyReport =
        ClusterSafetyReport(
            unicodeVersion = unicodeDataSet.version.value,
            sourceDumpRefs = sourceDumpRefs.sortedBy { ref -> ref.dumpId },
            cases = fixtures.sortedBy { fixture -> fixture.fixtureName }.map(::evaluateFixture),
        )

    private fun evaluateFixture(fixture: ClusterSafetyFixture): ClusterSafetyCase {
        val segmentation = clusterer.segment(fixture.sourceText)
        val bidi = bidiResolver.resolveDetailed(ShapingRequest(fixture.sourceText), requireParagraphOrdering = false)
        val script = scriptItemizer.itemize(fixture.sourceText)
        val invariants = mutableListOf<ClusterSafetyInvariantResult>()
        invariants += graphemeInvariantResult(segmentation.clusters, fixture.sourceText)
        invariants += boundaryInvariantResult(
            name = "bidi-run-boundaries-align",
            text = fixture.sourceText,
            clusters = segmentation.clusters,
            segmentRanges = bidi.runs.map { run -> run.logicalUtf16Range },
        )
        invariants += boundaryInvariantResult(
            name = "script-run-boundaries-align",
            text = fixture.sourceText,
            clusters = segmentation.clusters,
            segmentRanges = script.runs.map { run -> run.utf16Range },
        )
        if (fixture.syntheticSplitRanges.isNotEmpty()) {
            invariants += boundaryInvariantResult(
                name = "synthetic-split-boundaries",
                text = fixture.sourceText,
                clusters = segmentation.clusters,
                segmentRanges = fixture.syntheticSplitRanges,
            )
        }

        val diagnostics = buildList {
            addAll(segmentation.diagnostics)
            addAll(bidi.diagnostics)
            addAll(script.diagnostics)
            addAll(invariants.mapNotNull { invariant -> invariant.diagnostic })
        }.distinctBy { diagnostic -> diagnostic.code to diagnostic.textRange }

        return ClusterSafetyCase(
            fixtureName = fixture.fixtureName,
            sourceText = fixture.sourceText,
            inputTextHash = fixture.sourceText.sourceTextHash(),
            legacyGate = fixture.legacyGate,
            invariants = invariants,
            diagnostics = diagnostics,
        )
    }

    private fun graphemeInvariantResult(
        clusters: List<GraphemeCluster>,
        text: String,
    ): ClusterSafetyInvariantResult {
        val diagnostics = validateGraphemeClusterInvariants(text, clusters)
        val clusterRange = if (clusters.isEmpty()) 0..0 else clusters.first().clusterIndex..clusters.last().clusterIndex
        return ClusterSafetyInvariantResult(
            name = "grapheme-cluster-invariants",
            clusterRange = clusterRange,
            segmentRanges = clusters.map { cluster -> cluster.utf16Range },
            passed = diagnostics.isEmpty(),
            diagnostic = diagnostics.firstOrNull(),
        )
    }

    private fun boundaryInvariantResult(
        name: String,
        text: String,
        clusters: List<GraphemeCluster>,
        segmentRanges: List<IntRange>,
    ): ClusterSafetyInvariantResult {
        val sortedRanges = segmentRanges.sortedBy { range -> range.first }
        if (clusters.isEmpty()) {
            return ClusterSafetyInvariantResult(
                name = name,
                clusterRange = 0..0,
                segmentRanges = sortedRanges,
                passed = sortedRanges.isEmpty(),
                diagnostic = if (sortedRanges.isEmpty()) null else clusterBoundaryDiagnostic(
                    textRange = sortedRanges.first(),
                    reason = "segment range ${sortedRanges.first().toRangeLabel()} has no grapheme cluster coverage",
                ),
            )
        }

        val allClusterIndexes = clusters.map { cluster -> cluster.clusterIndex }.toSet()
        val coveredClusterIndexes = linkedSetOf<Int>()
        var previousRange: IntRange? = null
        for (range in sortedRanges) {
            if (previousRange != null && previousRange.last >= range.first) {
                return failedInvariant(
                    name = name,
                    clusters = clusters,
                    segmentRanges = sortedRanges,
                    textRange = range,
                    reason = "segment range ${range.toRangeLabel()} overlaps previous range ${previousRange.toRangeLabel()}",
                )
            }
            val overlappingClusters = clusters.filter { cluster -> cluster.utf16Range.overlaps(range) }
            if (overlappingClusters.isEmpty()) {
                return failedInvariant(
                    name = name,
                    clusters = clusters,
                    segmentRanges = sortedRanges,
                    textRange = range,
                    reason = "segment range ${range.toRangeLabel()} does not align to any grapheme cluster",
                )
            }
            val expectedRange = overlappingClusters.minOf { cluster -> cluster.utf16Range.first }..
                overlappingClusters.maxOf { cluster -> cluster.utf16Range.last }
            if (range != expectedRange) {
                return failedInvariant(
                    name = name,
                    clusters = clusters,
                    segmentRanges = sortedRanges,
                    textRange = range,
                    reason = "segment range ${range.toRangeLabel()} splits grapheme cluster coverage ${expectedRange.toRangeLabel()}",
                )
            }
            overlappingClusters.forEach { cluster -> coveredClusterIndexes += cluster.clusterIndex }
            previousRange = range
        }

        if (coveredClusterIndexes != allClusterIndexes) {
            val missingCluster = clusters.first { cluster -> cluster.clusterIndex !in coveredClusterIndexes }
            return failedInvariant(
                name = name,
                clusters = clusters,
                segmentRanges = sortedRanges,
                textRange = missingCluster.utf16Range,
                reason = "cluster ${missingCluster.clusterIndex} range ${missingCluster.utf16Range.toRangeLabel()} is not covered by segment ranges",
            )
        }

        return ClusterSafetyInvariantResult(
            name = name,
            clusterRange = clusters.first().clusterIndex..clusters.last().clusterIndex,
            segmentRanges = sortedRanges,
            passed = true,
            diagnostic = null,
        )
    }

    private fun failedInvariant(
        name: String,
        clusters: List<GraphemeCluster>,
        segmentRanges: List<IntRange>,
        textRange: IntRange,
        reason: String,
    ): ClusterSafetyInvariantResult {
        val overlappingClusters = clusters.filter { cluster -> cluster.utf16Range.overlaps(textRange) }
        val clusterRange = if (overlappingClusters.isEmpty()) {
            clusters.first().clusterIndex..clusters.last().clusterIndex
        } else {
            overlappingClusters.minOf { cluster -> cluster.clusterIndex }..
                overlappingClusters.maxOf { cluster -> cluster.clusterIndex }
        }
        return ClusterSafetyInvariantResult(
            name = name,
            clusterRange = clusterRange,
            segmentRanges = segmentRanges,
            passed = false,
            diagnostic = clusterBoundaryDiagnostic(textRange, reason),
        )
    }
}

public fun defaultClusterSafetyReport(): ClusterSafetyReport =
    ClusterSafetySuite().evaluate(
        fixtures = defaultClusterSafetyFixtures(),
        sourceDumpRefs = defaultClusterSafetyDumpRefs(),
    )

public fun defaultClusterSafetyReportJson(): String =
    defaultClusterSafetyReport().toCanonicalJson()

private fun defaultClusterSafetyFixtures(): List<ClusterSafetyFixture> = listOf(
    ClusterSafetyFixture(
        fixtureName = "cluster-arabic-mark.txt",
        sourceText = "\u0627\u0651",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-cjk-ivs-mixed-kana.txt",
        sourceText = "\u4E00\uDB40\uDD00\u30A2",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-cjk-ivs-supplementary.txt",
        sourceText = "\u4E00\uDB40\uDD00",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-cjk-variation-selector.txt",
        sourceText = "\u4E00\u3003\uFE0F",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-devanagari-conjunct.txt",
        sourceText = "\u0915\u094D\u0937\u093E",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-emoji-family-zwj.txt",
        sourceText = "\uD83D\uDC66\uD83C\uDFFB\u200D\uD83D\uDC66",
        legacyGate = "scaledemoji",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-emoji-skin-tone.txt",
        sourceText = "\uD83D\uDC66\uD83C\uDFFB",
        legacyGate = "scaledemoji",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-mixed-bidi.txt",
        sourceText = "abc \u05D0\u05D1!",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-negative-split.txt",
        sourceText = "A\u0301",
        syntheticSplitRanges = listOf(0..0, 1..1),
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-thai-tone.txt",
        sourceText = "\u0E01\u0E49",
    ),
    ClusterSafetyFixture(
        fixtureName = "cluster-vs15-vs16.txt",
        sourceText = "\u2764\uFE0E\u2764\uFE0F",
        legacyGate = "scaledemoji",
    ),
)

private fun defaultClusterSafetyDumpRefs(): List<ClusterSafetyDumpRef> =
    listOf(
        "font/fixtures/expected/unicode/bidi-runs.json" to "bidi-runs",
        "font/fixtures/expected/unicode/script-runs.json" to "script-runs",
        "font/fixtures/expected/unicode/unicode-segments.json" to "unicode-segments",
    ).map { (relativePath, dumpId) ->
        ClusterSafetyDumpRef(
            dumpId = dumpId,
            dumpSha256 = sha256(projectRoot().resolve(relativePath).let(Files::readString)),
        )
    }

private fun ClusterSafetyDumpRef.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("dumpId", dumpId)).append(", ")
    append(jsonPair("dumpSha256", dumpSha256))
    append("}")
}

private fun ClusterSafetyCase.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("fixtureName", fixtureName)).append(", ")
    append(jsonPair("sourceText", sourceText)).append(", ")
    append(jsonPair("inputTextHash", inputTextHash)).append(", ")
    append(jsonPair("gate", legacyGate)).append(", ")
    append(jsonString("invariants")).append(": ").append(invariants.toInvariantArrayJson()).append(", ")
    append(jsonString("diagnostics")).append(": ").append(diagnostics.toDiagnosticArrayJson())
    append("}")
}

private fun List<ClusterSafetyInvariantResult>.toInvariantArrayJson(): String =
    joinToString(prefix = "[", postfix = "]") { invariant -> invariant.toCanonicalJson() }

private fun ClusterSafetyInvariantResult.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("name", name)).append(", ")
    append(jsonPair("clusterRange", clusterRange.toRangeLabel())).append(", ")
    append(jsonString("segmentRanges")).append(": ").append(segmentRanges.toRangeArrayJson()).append(", ")
    append(jsonString("passed")).append(": ").append(passed).append(", ")
    append(jsonString("diagnostic")).append(": ").append(diagnostic?.toCanonicalJson() ?: "null")
    append("}")
}

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

private fun clusterBoundaryDiagnostic(textRange: IntRange, reason: String): ShapingDiagnostic =
    ShapingDiagnostic(
        code = TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE,
        message = "Cluster boundary invariant failed for UTF-16 range ${textRange.toRangeLabel()}: $reason.",
        textRange = textRange,
    )

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

private const val CLUSTER_SAFETY_SOURCE_HASH_ALGORITHM = "sha256-utf16-code-units"

private val ClusterSafetyNonClaims = listOf(
    "no-complete-target-support-claim",
    "no-complete-uax29-claim",
    "no-complete-uax9-claim",
    "no-complete-script-itemization-claim",
    "no-shaping-support-promotion",
    "no-fallback-support-claim",
    "no-emoji-route-support-claim",
    "no-gpu-text-route-claim",
)
