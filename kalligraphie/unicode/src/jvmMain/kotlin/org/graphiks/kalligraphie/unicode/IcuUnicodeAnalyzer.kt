package org.graphiks.kalligraphie.unicode

import com.ibm.icu.lang.UCharacter
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
    /** Creates an analyzer backed internally by ICU4J 76.1 and Unicode 16.0 data. */
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
    val candidateScripts = snapshot.scalars.map(::candidateScripts)
    val resolvedScripts = IntArray(snapshot.scalars.size)
    var previousScript: Int? = null
    snapshot.scalars.indices.forEach { scalarIndex ->
        val candidates = candidateScripts[scalarIndex]
        val primary = UScript.getScript(snapshot.scalars[scalarIndex]).takeUnless(::isNeutralScript)
        val nextScript = nextContextScript(candidateScripts, snapshot.scalars, scalarIndex + 1, languageScript)
        val resolved = when {
            candidates.isEmpty() -> previousScript ?: nextScript ?: languageScript ?: UScript.COMMON
            previousScript != null && previousScript in candidates -> previousScript
            languageScript != null && languageScript in candidates -> languageScript
            primary != null && primary in candidates -> primary
            nextScript != null && nextScript in candidates -> nextScript
            else -> candidates.minOrNull() ?: UScript.COMMON
        }
        resolvedScripts[scalarIndex] = resolved
        previousScript = resolved
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

private fun nextContextScript(
    candidateScripts: List<Set<Int>>,
    scalars: List<Int>,
    start: Int,
    languageScript: Int?,
): Int? {
    for (scalarIndex in start until candidateScripts.size) {
        val candidates = candidateScripts[scalarIndex]
        if (candidates.isEmpty()) continue
        val primary = UScript.getScript(scalars[scalarIndex]).takeUnless(::isNeutralScript)
        return when {
            languageScript != null && languageScript in candidates -> languageScript
            primary != null && primary in candidates -> primary
            else -> candidates.minOrNull()
        }
    }
    return null
}

private fun likelyScript(locale: ULocale): Int? {
    val script = ULocale.addLikelySubtags(locale).script
    if (script.isEmpty()) return null
    return UScript.getCodeFromName(script).takeUnless { it == UScript.INVALID_CODE }
}

private fun isNeutralScript(script: Int): Boolean = script == UScript.COMMON || script == UScript.INHERITED

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
    check(VersionInfo.ICU_VERSION.major == 76 && VersionInfo.ICU_VERSION.minor == 1) {
        "ICU4J 76.1 is required."
    }
}

private val UNICODE_DATA: UnicodeDataIdentity = UnicodeDataIdentity(
    unicodeVersion = "16.0",
    implementation = "ICU4J",
    implementationVersion = "76.1",
)
private const val INVALID_LANGUAGE_MESSAGE: String = "Language must be a well-formed BCP 47 tag."
