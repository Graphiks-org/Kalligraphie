package io.ygdrasil.kalligraphie.text.shaping

import java.security.MessageDigest

public const val TEXT_SHAPING_SCRIPT_UNSUPPORTED_DIAGNOSTIC_CODE: String =
    "text.shaping.script-unsupported"
public const val TEXT_SHAPING_SCRIPT_RUN_AMBIGUOUS_DIAGNOSTIC_CODE: String =
    "text.shaping.script-run-ambiguous"

public data class ScriptItemizationRun(
    public val clusterRange: IntRange,
    public val utf16Range: IntRange,
    public val codePointRange: IntRange,
    public val selectedScript: String,
    public val openTypeScriptTags: List<String>,
    public val extensionCandidates: List<String>,
    public val languageHint: String?,
    public val reason: String,
)

public data class ScriptItemizationResult(
    public val unicodeVersion: String,
    public val sourceTextHash: String,
    public val runs: List<ScriptItemizationRun>,
    public val diagnostics: List<ShapingDiagnostic>,
)

public data class ScriptFixtureDumpInput(
    public val fixtureName: String,
    public val sourceText: String,
    public val result: ScriptItemizationResult,
)

public data class ScriptRunsDump(
    public val unicodeVersion: String,
    public val inputs: List<ScriptFixtureDumpInput>,
) {
    public fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"script-runs\",\n")
        append("  \"ownerTickets\": [\"KFONT-M5-004\"],\n")
        appendJsonField("unicodeVersion", unicodeVersion, comma = true)
        appendJsonField("sourceTextHashAlgorithm", "SHA-256", comma = true)
        append("  \"inputs\": [\n")
        append(inputs.joinToString(",\n") { input -> input.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"nonClaims\": [\n")
        append(ScriptItemizationNonClaims.joinToString(",\n") { nonClaim -> "    ${jsonString(nonClaim)}" })
        append("\n  ]\n")
        append("}\n")
    }
}

public class ScriptExtensionsItemizer(
    private val unicodeDataSet: UnicodeDataSet,
    private val expectedUnicodeVersion: String = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
) {
    public fun itemize(text: String): ScriptItemizationResult {
        val sourceTextHash = text.sourceTextHashForScriptItemization()
        if (unicodeDataSet.version.value != expectedUnicodeVersion) {
            return ScriptItemizationResult(
                unicodeVersion = unicodeDataSet.version.value,
                sourceTextHash = sourceTextHash,
                runs = emptyList(),
                diagnostics = listOf(
                    UnicodeDataVersionMismatchDiagnostic(
                        expectedUnicodeVersion = expectedUnicodeVersion,
                        actualUnicodeVersion = unicodeDataSet.version.value,
                        subject = "script-itemization",
                        textRange = text.indices.takeUnless { it.isEmpty() },
                    ).toShapingDiagnostic(),
                ),
            )
        }

        val graphemeResult = GraphemeClusterer(unicodeDataSet, expectedUnicodeVersion).segment(text)
        if (graphemeResult.clusters.isEmpty()) {
            return ScriptItemizationResult(
                unicodeVersion = unicodeDataSet.version.value,
                sourceTextHash = sourceTextHash,
                runs = emptyList(),
                diagnostics = graphemeResult.diagnostics,
            )
        }

        val clusters = graphemeResult.clusters.map { cluster ->
            clusterFacts(text, cluster, graphemeResult.clusters)
        }
        val diagnostics = graphemeResult.diagnostics.toMutableList()
        diagnostics += clusters.mapNotNull { cluster -> cluster.diagnostic }
        clusters.filter { it.openTypeScriptTags.isEmpty() && it.selectedScript.isTargetMatrixCandidate() }
            .forEach { facts ->
                diagnostics += ShapingDiagnostic(
                    code = TEXT_SHAPING_SCRIPT_UNSUPPORTED_DIAGNOSTIC_CODE,
                    message = "Script ${facts.selectedScript} is outside the current KFONT required matrix.",
                    textRange = facts.utf16Range,
                )
            }

        return ScriptItemizationResult(
            unicodeVersion = unicodeDataSet.version.value,
            sourceTextHash = sourceTextHash,
            runs = mergeRuns(clusters),
            diagnostics = diagnostics,
        )
    }

    public fun dumpFixtures(fixtures: List<Pair<String, String>>): ScriptRunsDump =
        ScriptRunsDump(
            unicodeVersion = unicodeDataSet.version.value,
            inputs = fixtures.sortedBy { it.first }.map { (fixtureName, sourceText) ->
                ScriptFixtureDumpInput(
                    fixtureName = fixtureName,
                    sourceText = sourceText,
                    result = itemize(sourceText),
                )
            },
        )

    private fun clusterFacts(
        text: String,
        cluster: GraphemeCluster,
        allClusters: List<GraphemeCluster>,
    ): ScriptClusterFacts {
        val codePoints = codePointFacts(text, cluster.utf16Range)
        val strongScripts = codePoints.map { it.script }.filter { it.isStrongScriptCode() }.distinct()
        val extensionCandidates = codePoints.flatMap { it.extensionCandidates }.distinct().sorted()
        val emojiCandidate = codePoints.any { it.isEmoji }

        val extensionResolution = if (strongScripts.isEmpty() && !emojiCandidate && extensionCandidates.isNotEmpty()) {
            selectExtensionCandidate(text, extensionCandidates, cluster, allClusters)
        } else {
            null
        }
        val contextResolution = if (strongScripts.isEmpty() && !emojiCandidate && extensionResolution == null) {
            selectContextScript(text, cluster, allClusters)
        } else {
            null
        }
        val selected = when {
            strongScripts.isNotEmpty() -> strongScripts.first()
            emojiCandidate -> "Zsye"
            extensionResolution != null -> extensionResolution.selectedScript
            contextResolution != null -> contextResolution.selectedScript
            else -> "Zyyy"
        }
        val reason = when {
            extensionResolution != null -> extensionResolution.reason
            contextResolution != null -> contextResolution.reason
            selected !in supportedOpenTypeTags -> "unsupported-script"
            strongScripts.isNotEmpty() -> "strong-script"
            emojiCandidate -> "emoji-script"
            else -> "context-missing"
        }
        val candidates = when {
            extensionCandidates.isNotEmpty() -> extensionCandidates
            emojiCandidate -> listOf("Zsye")
            strongScripts.isNotEmpty() -> strongScripts
            else -> listOf(selected)
        }.distinct().sorted()

        return ScriptClusterFacts(
            clusterRange = cluster.clusterIndex..cluster.clusterIndex,
            utf16Range = cluster.utf16Range,
            codePointRange = cluster.codePointRange,
            selectedScript = selected,
            openTypeScriptTags = supportedOpenTypeTags[selected].orEmpty(),
            extensionCandidates = candidates,
            reason = reason,
            diagnostic = extensionResolution?.diagnostic?.invoke(cluster.utf16Range)
                ?: contextResolution?.diagnostic?.invoke(cluster.utf16Range),
        )
    }

    private fun selectExtensionCandidate(
        text: String,
        extensionCandidates: List<String>,
        cluster: GraphemeCluster,
        allClusters: List<GraphemeCluster>,
    ): ScriptResolution {
        val previous = nearestStrongScriptBefore(text, cluster.clusterIndex, allClusters)
        val next = nearestStrongScriptAfter(text, cluster.clusterIndex, allClusters)
        if (previous in extensionCandidates && next in extensionCandidates && previous != next) {
            return ambiguousScriptExtensionResolution(extensionCandidates)
        }
        if (previous in extensionCandidates) {
            return ScriptResolution(previous!!, "script-extension")
        }
        if (next in extensionCandidates) {
            return ScriptResolution(next!!, "script-extension")
        }
        if (extensionCandidates.size > 1) {
            return ambiguousScriptExtensionResolution(extensionCandidates)
        }
        return ScriptResolution(extensionCandidates.single(), "script-extension")
    }

    private fun ambiguousScriptExtensionResolution(extensionCandidates: List<String>): ScriptResolution =
        ScriptResolution(
            selectedScript = "Zyyy",
            reason = "script-extension-ambiguous",
            diagnostic = { textRange ->
                ShapingDiagnostic(
                    code = TEXT_SHAPING_SCRIPT_RUN_AMBIGUOUS_DIAGNOSTIC_CODE,
                    message = "Script_Extensions candidates ${extensionCandidates.joinToString(",")} need strong context.",
                    textRange = textRange,
                )
            },
        )

    private fun selectContextScript(
        text: String,
        cluster: GraphemeCluster,
        allClusters: List<GraphemeCluster>,
    ): ScriptResolution {
        val previous = nearestStrongScriptBefore(text, cluster.clusterIndex, allClusters)
        val next = nearestStrongScriptAfter(text, cluster.clusterIndex, allClusters)
        if (previous != null && next != null && previous != next) {
            return ScriptResolution(
                selectedScript = "Zyyy",
                reason = "context-ambiguous",
                diagnostic = { textRange ->
                    ShapingDiagnostic(
                        code = TEXT_SHAPING_SCRIPT_RUN_AMBIGUOUS_DIAGNOSTIC_CODE,
                        message = "Common or Inherited cluster has conflicting strong context $previous/$next.",
                        textRange = textRange,
                    )
                },
            )
        }
        if (previous != null) {
            return ScriptResolution(previous, "context-script")
        }
        if (next != null) {
            return ScriptResolution(next, "context-script")
        }
        return ScriptResolution(
            selectedScript = "Zyyy",
            reason = "context-missing",
            diagnostic = { textRange ->
                ShapingDiagnostic(
                    code = TEXT_SHAPING_SCRIPT_RUN_AMBIGUOUS_DIAGNOSTIC_CODE,
                    message = "Common or Inherited cluster needs strong script context.",
                    textRange = textRange,
                )
            },
        )
    }

    private fun nearestStrongScriptBefore(
        text: String,
        clusterIndex: Int,
        allClusters: List<GraphemeCluster>,
    ): String? =
        allClusters.asSequence()
            .filter { it.clusterIndex < clusterIndex }
            .sortedByDescending { it.clusterIndex }
            .mapNotNull { strongScriptForCluster(text, it) }
            .firstOrNull()

    private fun nearestStrongScriptAfter(
        text: String,
        clusterIndex: Int,
        allClusters: List<GraphemeCluster>,
    ): String? =
        allClusters.asSequence()
            .filter { it.clusterIndex > clusterIndex }
            .sortedBy { it.clusterIndex }
            .mapNotNull { strongScriptForCluster(text, it) }
            .firstOrNull()

    private fun strongScriptForCluster(text: String, cluster: GraphemeCluster): String? =
        codePointFacts(text, cluster.utf16Range)
            .map { it.script }
            .firstOrNull { it.isStrongScriptCode() }

    private fun codePointFacts(text: String, utf16Range: IntRange): List<ScriptCodePointFacts> {
        val facts = mutableListOf<ScriptCodePointFacts>()
        var index = utf16Range.first
        var codePointIndex = 0
        while (index <= utf16Range.last) {
            val codePoint = Character.codePointAt(text, index)
            val charCount = Character.charCount(codePoint)
            val script = unicodeDataSet.script.valueAt(codePoint)
            val extensions = unicodeDataSet.scriptExtensions.valueAt(codePoint)
            val isEmoji = unicodeDataSet.emojiProperties.emoji.valueAt(codePoint) ||
                unicodeDataSet.emojiProperties.extendedPictographic.valueAt(codePoint)
            facts += ScriptCodePointFacts(
                codePoint = codePoint,
                codePointIndex = codePointIndex,
                script = script,
                extensionCandidates = extensions,
                isEmoji = isEmoji,
            )
            index += charCount
            codePointIndex += 1
        }
        return facts
    }

    private fun mergeRuns(clusters: List<ScriptClusterFacts>): List<ScriptItemizationRun> {
        val runs = mutableListOf<MutableScriptRun>()
        for (cluster in clusters) {
            val last = runs.lastOrNull()
            if (last != null && last.canAppend(cluster)) {
                last.append(cluster)
            } else {
                runs += MutableScriptRun(cluster)
            }
        }
        return runs.map { it.toImmutable() }
    }
}

private data class ScriptCodePointFacts(
    val codePoint: Int,
    val codePointIndex: Int,
    val script: String,
    val extensionCandidates: List<String>,
    val isEmoji: Boolean,
)

private data class ScriptResolution(
    val selectedScript: String,
    val reason: String,
    val diagnostic: ((IntRange) -> ShapingDiagnostic)? = null,
)

private data class ScriptClusterFacts(
    val clusterRange: IntRange,
    val utf16Range: IntRange,
    val codePointRange: IntRange,
    val selectedScript: String,
    val openTypeScriptTags: List<String>,
    val extensionCandidates: List<String>,
    val reason: String,
    val diagnostic: ShapingDiagnostic? = null,
)

private class MutableScriptRun(first: ScriptClusterFacts) {
    private var clusterFirst = first.clusterRange.first
    private var clusterLast = first.clusterRange.last
    private var utf16First = first.utf16Range.first
    private var utf16Last = first.utf16Range.last
    private var codePointFirst = first.codePointRange.first
    private var codePointLast = first.codePointRange.last
    private val candidates = first.extensionCandidates.toMutableSet()
    private var reason = first.reason
    private val selectedScript = first.selectedScript
    private val openTypeScriptTags = first.openTypeScriptTags

    fun canAppend(cluster: ScriptClusterFacts): Boolean =
        selectedScript == cluster.selectedScript &&
            openTypeScriptTags == cluster.openTypeScriptTags &&
            utf16Last + 1 == cluster.utf16Range.first

    fun append(cluster: ScriptClusterFacts) {
        clusterLast = cluster.clusterRange.last
        utf16Last = cluster.utf16Range.last
        codePointLast = cluster.codePointRange.last
        candidates += cluster.extensionCandidates
        reason = when {
            cluster.reason == "script-extension" || reason == "script-extension" -> "script-extension"
            cluster.reason == "context-script" || reason == "context-script" -> "context-script"
            cluster.reason == "script-extension-ambiguous" || reason == "script-extension-ambiguous" ->
                "script-extension-ambiguous"
            cluster.reason == "context-ambiguous" || reason == "context-ambiguous" -> "context-ambiguous"
            else -> reason
        }
    }

    fun toImmutable(): ScriptItemizationRun =
        ScriptItemizationRun(
            clusterRange = clusterFirst..clusterLast,
            utf16Range = utf16First..utf16Last,
            codePointRange = codePointFirst..codePointLast,
            selectedScript = selectedScript,
            openTypeScriptTags = openTypeScriptTags,
            extensionCandidates = candidates.sorted(),
            languageHint = null,
            reason = reason,
        )
}

private val supportedOpenTypeTags = mapOf(
    "Arab" to listOf("arab"),
    "Cyrl" to listOf("cyrl"),
    "Deva" to listOf("deva", "dev2"),
    "Grek" to listOf("grek"),
    "Hang" to listOf("hang"),
    "Hani" to listOf("hani"),
    "Hebr" to listOf("hebr"),
    "Hira" to listOf("hira"),
    "Kana" to listOf("kana"),
    "Latn" to listOf("latn"),
    "Thai" to listOf("thai"),
    "Zsye" to listOf("Zsye"),
    "Zsym" to listOf("Zsym"),
)

private val ScriptItemizationNonClaims = listOf(
    "no-complete-target-support-claim",
    "no-complete-ucd-claim",
    "no-complete-gsub-gpos-claim",
    "no-shaping-support-promotion",
    "no-paragraph-support-claim",
    "no-gpu-text-route-claim",
)

private fun String.isStrongScriptCode(): Boolean =
    this != "Zyyy" && this != "Zinh"

private fun String.isTargetMatrixCandidate(): Boolean =
    this != "Zyyy" && this != "Zinh"

private fun ScriptFixtureDumpInput.toCanonicalJson(): String = buildString {
    append("{\n")
    appendJsonField("fixtureName", fixtureName, comma = true)
    appendJsonField("sourceText", sourceText, comma = true)
    appendJsonField("inputTextHash", result.sourceTextHash, comma = true)
    append("  \"runs\": [\n")
    append(result.runs.joinToString(",\n") { run -> run.toCanonicalJson().prependIndent("    ") })
    append("\n  ],\n")
    append("  \"diagnostics\": [")
    if (result.diagnostics.isNotEmpty()) {
        append("\n")
        append(result.diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
        append("\n  ")
    }
    append("]\n")
    append("}")
}

private fun ScriptItemizationRun.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("clusterRange", clusterRange.toRangeLabel())).append(", ")
    append(jsonPair("utf16Range", utf16Range.toRangeLabel())).append(", ")
    append(jsonPair("codePointRange", codePointRange.toRangeLabel())).append(", ")
    append(jsonPair("selectedScript", selectedScript)).append(", ")
    append(jsonString("openTypeScriptTags")).append(": ").append(jsonStringList(openTypeScriptTags)).append(", ")
    append(jsonString("extensionCandidates")).append(": ").append(jsonStringList(extensionCandidates)).append(", ")
    append(jsonPair("languageHint", languageHint)).append(", ")
    append(jsonPair("reason", reason))
    append("}")
}

private fun ShapingDiagnostic.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonPair("code", code)).append(", ")
    append(jsonPair("severity", "refusal")).append(", ")
    append(jsonPair("textRange", textRange?.toRangeLabel())).append(", ")
    append(jsonPair("message", message))
    append("}")
}

private fun String.sourceTextHashForScriptItemization(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

private fun StringBuilder.appendJsonField(name: String, value: String, comma: Boolean) {
    append("  ").append(jsonPair(name, value))
    if (comma) append(",")
    append("\n")
}

private fun jsonPair(name: String, value: String?): String =
    jsonString(name) + ": " + if (value == null) "null" else jsonString(value)

private fun jsonStringList(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }

private fun jsonString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20 || char.isSurrogate()) {
                    append("\\u").append(char.code.toString(16).uppercase().padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}

private fun IntRange.toRangeLabel(): String = "$first..$last"
