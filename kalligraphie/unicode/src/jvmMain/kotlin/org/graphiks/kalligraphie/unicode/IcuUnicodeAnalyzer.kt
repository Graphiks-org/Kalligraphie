package org.graphiks.kalligraphie.unicode

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.ibm.icu.lang.UScript
import com.ibm.icu.text.Bidi
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.IllformedLocaleException
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.VersionInfo
import java.util.BitSet
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.BidiRun
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.UnicodeDataIdentity

/** Factory for the pinned JVM-reference Unicode analyzer. */
public object JvmUnicodeAnalyzer {
    /** Creates an analyzer backed internally by ICU4J 77.1 and Unicode 16.0 data. */
    public fun create(): UnicodeAnalyzer = IcuUnicodeAnalyzer()
}

internal class IcuUnicodeAnalyzer : UnicodeAnalyzer {
    init {
        verifyPinnedUnicodeData()
    }

    override fun analyze(snapshot: TextSnapshot, request: UnicodeAnalysisRequest): UnicodeAnalysis {
        val locale = parseLanguage(request.language)
        val canonicalLanguage = locale.toLanguageTag()
        val canonicalText = CanonicalUtf16Text(snapshot)
        val logicalBidiRuns = logicalBidiRuns(snapshot, canonicalText, request.baseDirection)
        return UnicodeAnalysis(
            range = snapshot.range,
            unicodeData = UNICODE_DATA,
            graphemeClusters = graphemeClusters(snapshot, canonicalText),
            scriptLanguageRuns = scriptLanguageRuns(snapshot, locale, canonicalLanguage),
            logicalBidiRuns = logicalBidiRuns,
            visualBidiRuns = visualBidiRuns(snapshot, canonicalText, request.baseDirection),
        )
    }
}

private fun parseLanguage(language: String): ULocale = try {
    ULocale.Builder().setLanguageTag(language).build()
} catch (_: IllformedLocaleException) {
    throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
}

private fun graphemeClusters(snapshot: TextSnapshot, text: CanonicalUtf16Text): List<TextRange> {
    if (snapshot.scalars.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT)
    iterator.setText(text.value)
    val ranges = mutableListOf<TextRange>()
    var startUtf16 = iterator.first()
    var endUtf16 = iterator.next()
    while (endUtf16 != BreakIterator.DONE) {
        ranges += text.range(snapshot, startUtf16, endUtf16)
        startUtf16 = endUtf16
        endUtf16 = iterator.next()
    }
    return ranges
}

private fun scriptLanguageRuns(
    snapshot: TextSnapshot,
    locale: ULocale,
    language: String,
): List<ScriptLanguageRun> {
    if (snapshot.scalars.isEmpty()) return emptyList()
    val languageScript = likelyScript(locale)
    val scriptProperties = snapshot.scalars.map(::scriptProperties)
    val pairedScripts = pairedPunctuationScripts(snapshot.scalars, scriptProperties)
    val resolvedScripts = IntArray(snapshot.scalars.size)
    var previousScript: Int? = null
    snapshot.scalars.indices.forEach { scalarIndex ->
        val properties = scriptProperties[scalarIndex]
        val resolved = when {
            properties.script == UScript.UNKNOWN -> UScript.UNKNOWN
            pairedScripts[scalarIndex] != null -> pairedScripts.getValue(scalarIndex)
            properties.candidates.isEmpty() ->
                previousScript ?: nextContextScript(scriptProperties, scalarIndex + 1) ?: properties.script
            else -> resolveCandidateScript(
                properties = properties,
                previousScript = previousScript,
                nextScript = nextContextScript(scriptProperties, scalarIndex + 1),
                languageScript = languageScript,
            )
        }
        resolvedScripts[scalarIndex] = resolved
        previousScript = resolved.takeIf(::isExplicitScript)
    }

    val runs = mutableListOf<ScriptLanguageRun>()
    var runStart = 0
    var script = resolvedScripts.first()
    for (scalarIndex in 1 until resolvedScripts.size) {
        if (resolvedScripts[scalarIndex] != script) {
            runs += scriptRun(snapshot, runStart, scalarIndex, script, language)
            runStart = scalarIndex
            script = resolvedScripts[scalarIndex]
        }
    }
    runs += scriptRun(snapshot, runStart, resolvedScripts.size, script, language)
    return runs
}

private data class ScriptProperties(
    val script: Int,
    val candidates: Set<Int>,
)

private fun scriptProperties(scalar: Int): ScriptProperties = ScriptProperties(
    script = UScript.getScript(scalar),
    candidates = candidateScripts(scalar),
)

private fun candidateScripts(scalar: Int): Set<Int> {
    val scriptExtensions = BitSet()
    UScript.getScriptExtensions(scalar, scriptExtensions)
    scriptExtensions.clear(UScript.COMMON)
    scriptExtensions.clear(UScript.INHERITED)
    scriptExtensions.clear(UScript.UNKNOWN)
    return buildSet {
        var script = scriptExtensions.nextSetBit(0)
        while (script >= 0) {
            add(script)
            script = scriptExtensions.nextSetBit(script + 1)
        }
    }
}

private fun resolveCandidateScript(
    properties: ScriptProperties,
    previousScript: Int?,
    nextScript: Int?,
    languageScript: Int?,
): Int = when {
    previousScript != null && previousScript in properties.candidates -> previousScript
    nextScript != null && nextScript in properties.candidates -> nextScript
    languageScript != null && languageScript in properties.candidates -> languageScript
    properties.script in properties.candidates -> properties.script
    else -> properties.candidates.minByOrNull(UScript::getShortName) ?: properties.script
}

private fun nextContextScript(scriptProperties: List<ScriptProperties>, start: Int): Int? {
    for (scalarIndex in start until scriptProperties.size) {
        val properties = scriptProperties[scalarIndex]
        when {
            properties.script == UScript.UNKNOWN -> return null
            isExplicitScript(properties.script) -> return properties.script
            properties.candidates.size == 1 -> return properties.candidates.single()
        }
    }
    return null
}

private fun pairedPunctuationScripts(
    scalars: List<Int>,
    scriptProperties: List<ScriptProperties>,
): Map<Int, Int> {
    val openingIndexes = mutableListOf<Int>()
    val resolvedScripts = mutableMapOf<Int, Int>()
    scalars.forEachIndexed { scalarIndex, scalar ->
        when (UCharacter.getIntPropertyValue(scalar, UProperty.BIDI_PAIRED_BRACKET_TYPE)) {
            UCharacter.BidiPairedBracketType.OPEN -> openingIndexes += scalarIndex
            UCharacter.BidiPairedBracketType.CLOSE -> {
                val openingIndex = openingIndexes.lastOrNull() ?: return@forEachIndexed
                if (UCharacter.getBidiPairedBracket(scalars[openingIndex]) == scalar) {
                    openingIndexes.removeAt(openingIndexes.lastIndex)
                    enclosingScript(scriptProperties, openingIndex, scalarIndex)?.let { script ->
                        resolvedScripts[openingIndex] = script
                        resolvedScripts[scalarIndex] = script
                    }
                }
            }
        }
    }
    return resolvedScripts
}

private fun enclosingScript(
    scriptProperties: List<ScriptProperties>,
    openingIndex: Int,
    closingIndex: Int,
): Int? {
    val before = previousContextScript(scriptProperties, openingIndex - 1)
    val after = nextContextScript(scriptProperties, closingIndex + 1)
    return before?.takeIf { it == after }
}

private fun previousContextScript(scriptProperties: List<ScriptProperties>, start: Int): Int? {
    for (scalarIndex in start downTo 0) {
        val properties = scriptProperties[scalarIndex]
        when {
            properties.script == UScript.UNKNOWN -> return null
            isExplicitScript(properties.script) -> return properties.script
            properties.candidates.size == 1 -> return properties.candidates.single()
        }
    }
    return null
}

private fun likelyScript(locale: ULocale): Int? {
    val script = ULocale.addLikelySubtags(locale).script
    if (script.isEmpty()) return null
    return UScript.getCodeFromName(script).takeUnless { it == UScript.INVALID_CODE }
}

private fun isExplicitScript(script: Int): Boolean =
    script != UScript.COMMON && script != UScript.INHERITED && script != UScript.UNKNOWN

private fun scriptRun(
    snapshot: TextSnapshot,
    start: Int,
    endExclusive: Int,
    script: Int,
    language: String,
): ScriptLanguageRun = ScriptLanguageRun(
    range = scalarRange(snapshot, start, endExclusive),
    script = UScript.getShortName(script),
    language = language,
)

private fun logicalBidiRuns(
    snapshot: TextSnapshot,
    text: CanonicalUtf16Text,
    baseDirection: BaseDirection,
): List<BidiRun> {
    if (snapshot.scalars.isEmpty()) return emptyList()
    val bidi = bidi(text.value, baseDirection)
    val runs = mutableListOf<BidiRun>()
    var utf16Start = 0
    while (utf16Start < text.value.length) {
        val run = bidi.getLogicalRun(utf16Start)
        runs += BidiRun(text.range(snapshot, run.start, run.limit), run.embeddingLevel.toInt())
        utf16Start = run.limit
    }
    return runs
}

private fun visualBidiRuns(
    snapshot: TextSnapshot,
    text: CanonicalUtf16Text,
    baseDirection: BaseDirection,
): List<BidiRun> {
    if (snapshot.scalars.isEmpty()) return emptyList()
    val bidi = bidi(text.value, baseDirection)
    return List(bidi.countRuns()) { visualIndex ->
        val run = bidi.getVisualRun(visualIndex)
        BidiRun(text.range(snapshot, run.start, run.limit), run.embeddingLevel.toInt())
    }
}

private fun bidi(text: String, baseDirection: BaseDirection): Bidi = Bidi().apply {
    val paragraphLevel = when (baseDirection) {
        BaseDirection.LEFT_TO_RIGHT -> Bidi.LTR
        BaseDirection.RIGHT_TO_LEFT -> Bidi.RTL
    }
    setPara(text, paragraphLevel, null)
}

private class CanonicalUtf16Text(snapshot: TextSnapshot) {
    val value: String
    private val scalarBoundaryToUtf16: IntArray = IntArray(snapshot.scalars.size + 1)

    init {
        val builder = StringBuilder()
        snapshot.scalars.forEachIndexed { scalarIndex, scalar ->
            scalarBoundaryToUtf16[scalarIndex] = builder.length
            builder.appendCodePoint(scalar)
        }
        scalarBoundaryToUtf16[snapshot.scalars.size] = builder.length
        value = builder.toString()
    }

    fun range(snapshot: TextSnapshot, utf16Start: Int, utf16EndExclusive: Int): TextRange =
        scalarRange(
            snapshot,
            scalarBoundary(utf16Start),
            scalarBoundary(utf16EndExclusive),
        )

    private fun scalarBoundary(utf16Boundary: Int): Int {
        val scalarBoundary = scalarBoundaryToUtf16.binarySearch(utf16Boundary)
        check(scalarBoundary >= 0) { "ICU returned a boundary inside a Unicode scalar." }
        return scalarBoundary
    }
}

private fun scalarRange(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
    TextRange(
        snapshot.textIndexAtScalarBoundary(start),
        snapshot.textIndexAtScalarBoundary(endExclusive),
    )

private fun verifyPinnedUnicodeData() {
    check(UCharacter.getUnicodeVersion() == VersionInfo.UNICODE_16_0) {
        "ICU4J must provide Unicode 16.0 data."
    }
    check(VersionInfo.ICU_VERSION.major == 77 && VersionInfo.ICU_VERSION.minor == 1) {
        "ICU4J 77.1 is required."
    }
}

private val UNICODE_DATA: UnicodeDataIdentity = UnicodeDataIdentity(
    unicodeVersion = loadedVersion(UCharacter.getUnicodeVersion()),
    implementation = "ICU4J",
    implementationVersion = loadedVersion(VersionInfo.ICU_VERSION),
)

private fun loadedVersion(version: VersionInfo): String = "${version.major}.${version.minor}"

private const val INVALID_LANGUAGE_MESSAGE: String = "Language must be a well-formed BCP 47 tag."
