package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile

/**
 * The script resolution half of one Unicode analysis, over this module's generated tables.
 *
 * It implements the contract's documented policy exactly: a scalar whose script is Unknown stays
 * Unknown; paired punctuation attaches to its determinable enclosing script; Common and Inherited
 * scalars attach to the surrounding explicit script context when one determines them; and a scalar
 * with `Script_Extensions` candidates takes the preceding explicit script when it is one of them,
 * then the following one, then the language's likely script, then its own script, and otherwise
 * the lexicographically first candidate. This is the same policy the JVM reference implements on
 * ICU's script property lookups; the tables and the shape of the data are what differ.
 */
internal object PortableScriptResolver {

    /**
     * Resolves one script per scalar and splits the snapshot into runs of one script and language.
     *
     * [bracketPairs] are the BD16 pairs the BiDi resolution already found; attributing the paired
     * punctuation's script from them keeps the two resolutions consistent without recomputing the
     * pairing.
     */
    internal fun scriptLanguageRuns(
        snapshot: TextSnapshot,
        language: ParsedLanguage,
        bracketPairs: List<UnicodeBidiStructure.BracketResolution.Pair>,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): List<ScriptLanguageRun> {
        if (snapshot.scalars.isEmpty()) return emptyList()
        val languageScript = likelyScript(language)
        val scriptProperties = snapshot.scalars.mapIndexed { index, scalar ->
            observeCancellation(index, profile, cancellationToken)
            scriptProperties(scalar)
        }
        val pairedScripts = pairedPunctuationScripts(bracketPairs, scriptProperties, profile, cancellationToken)
        val resolvedScripts = ArrayList<String>(snapshot.scalars.size)
        var previousScript: String? = null
        snapshot.scalars.indices.forEach { scalarIndex ->
            observeCancellation(scalarIndex, profile, cancellationToken)
            val properties = scriptProperties[scalarIndex]
            val resolved = when {
                properties.script == UNKNOWN_SCRIPT -> UNKNOWN_SCRIPT
                pairedScripts[scalarIndex] != null -> pairedScripts.getValue(scalarIndex)
                properties.candidates.isEmpty() ->
                    previousScript ?: nextContextScript(scriptProperties, scalarIndex + 1, profile, cancellationToken)
                        ?: properties.script
                else -> resolveCandidateScript(
                    properties = properties,
                    previousScript = previousScript,
                    nextScript = nextContextScript(scriptProperties, scalarIndex + 1, profile, cancellationToken),
                    languageScript = languageScript,
                )
            }
            resolvedScripts += resolved
            previousScript = resolved.takeIf(::isExplicitScript)
        }

        val runs = mutableListOf<ScriptLanguageRun>()
        var runStart = 0
        var script = resolvedScripts.first()
        for (scalarIndex in 1 until resolvedScripts.size) {
            observeCancellation(scalarIndex, profile, cancellationToken)
            if (resolvedScripts[scalarIndex] != script) {
                runs += scriptRun(snapshot, runStart, scalarIndex, script, language.canonicalTag)
                runStart = scalarIndex
                script = resolvedScripts[scalarIndex]
            }
        }
        runs += scriptRun(snapshot, runStart, resolvedScripts.size, script, language.canonicalTag)
        return runs
    }

    private data class ScriptProperties(
        val script: String,
        val candidates: Set<String>,
    )

    private fun scriptProperties(scalar: Int): ScriptProperties = ScriptProperties(
        script = UnicodeScript.codeOf(scalar),
        candidates = candidateScripts(scalar),
    )

    private fun candidateScripts(scalar: Int): Set<String> {
        val declared = UnicodeScriptExtensions.extensionsOf(scalar)
        val declaredOrScript = if (declared.isEmpty()) listOf(UnicodeScript.codeOf(scalar)) else declared
        return declaredOrScript
            .filterNot { it == COMMON_SCRIPT || it == INHERITED_SCRIPT || it == UNKNOWN_SCRIPT }
            .toSet()
    }

    private fun resolveCandidateScript(
        properties: ScriptProperties,
        previousScript: String?,
        nextScript: String?,
        languageScript: String?,
    ): String = when {
        previousScript != null && previousScript in properties.candidates -> previousScript
        nextScript != null && nextScript in properties.candidates -> nextScript
        languageScript != null && languageScript in properties.candidates -> languageScript
        properties.script in properties.candidates -> properties.script
        else -> properties.candidates.minOrNull() ?: properties.script
    }

    private fun nextContextScript(
        scriptProperties: List<ScriptProperties>,
        start: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): String? {
        for (scalarIndex in start until scriptProperties.size) {
            observeCancellation(scalarIndex, profile, cancellationToken)
            val properties = scriptProperties[scalarIndex]
            when {
                properties.script == UNKNOWN_SCRIPT -> return null
                isExplicitScript(properties.script) -> return properties.script
                properties.candidates.size == 1 -> return properties.candidates.single()
            }
        }
        return null
    }

    private fun pairedPunctuationScripts(
        bracketPairs: List<UnicodeBidiStructure.BracketResolution.Pair>,
        scriptProperties: List<ScriptProperties>,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): Map<Int, String> {
        val resolvedScripts = mutableMapOf<Int, String>()
        bracketPairs.forEachIndexed { pairIndex, pair ->
            observeCancellation(pairIndex, profile, cancellationToken)
            enclosingScript(scriptProperties, pair.opening, pair.closing, profile, cancellationToken)?.let { script ->
                resolvedScripts[pair.opening] = script
                resolvedScripts[pair.closing] = script
            }
        }
        return resolvedScripts
    }

    private fun enclosingScript(
        scriptProperties: List<ScriptProperties>,
        openingIndex: Int,
        closingIndex: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): String? {
        val before = previousContextScript(scriptProperties, openingIndex - 1, profile, cancellationToken)
        val after = nextContextScript(scriptProperties, closingIndex + 1, profile, cancellationToken)
        return before?.takeIf { it == after }
    }

    private fun previousContextScript(
        scriptProperties: List<ScriptProperties>,
        start: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): String? {
        for (scalarIndex in start downTo 0) {
            observeCancellation(scalarIndex, profile, cancellationToken)
            val properties = scriptProperties[scalarIndex]
            when {
                properties.script == UNKNOWN_SCRIPT -> return null
                isExplicitScript(properties.script) -> return properties.script
                properties.candidates.size == 1 -> return properties.candidates.single()
            }
        }
        return null
    }

    /**
     * The script the analysis language contributes to ambiguous `Script_Extensions` candidates.
     *
     * A tag that names a script means that script; otherwise the likely-script source answers for
     * the bare language subtag, and a language the source carries no entry for contributes nothing.
     */
    private fun likelyScript(language: ParsedLanguage): String? =
        language.explicitScript ?: UnicodeLikelyScript.scriptOf(language.primarySubtag)

    private fun isExplicitScript(script: String): Boolean =
        script != COMMON_SCRIPT && script != INHERITED_SCRIPT && script != UNKNOWN_SCRIPT

    private fun scriptRun(
        snapshot: TextSnapshot,
        start: Int,
        endExclusive: Int,
        script: String,
        language: String,
    ): ScriptLanguageRun = ScriptLanguageRun(
        range = scalarRange(snapshot, start, endExclusive),
        script = script,
        language = language,
    )

    private const val COMMON_SCRIPT: String = "Zyyy"
    private const val INHERITED_SCRIPT: String = "Zinh"
    private const val UNKNOWN_SCRIPT: String = "Zzzz"
}
