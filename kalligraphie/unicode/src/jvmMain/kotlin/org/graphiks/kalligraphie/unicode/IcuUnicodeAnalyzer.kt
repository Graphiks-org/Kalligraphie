package org.graphiks.kalligraphie.unicode

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UCharacterEnums
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
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.ScriptLanguageRun
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeAnalysisLimit
import org.graphiks.kalligraphie.api.UnicodeAnalysisOutcome
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile
import org.graphiks.kalligraphie.api.UnicodeAnalysisRequest
import org.graphiks.kalligraphie.api.UnicodeDataIdentity

/** Factory for the pinned JVM-reference Unicode analyzer. */
public object JvmUnicodeAnalyzer {
    /** Creates an analyzer backed internally by ICU4J 77.1 and Unicode 16.0 data. */
    public fun create(): BoundedUnicodeAnalyzer = IcuUnicodeAnalyzer()

    /**
     * Validates [language] and returns the canonical BCP 47 form used by this JVM analyzer.
     *
     * This stateless operation uses the same pinned ICU4J parser as [create], retains no ICU
     * resource, and is safe for concurrent calls.
     *
     * @throws IllegalArgumentException when [language] is not a well-formed BCP 47 tag.
     */
    public fun canonicalizeLanguageTag(language: String): String = parseLanguage(language).toLanguageTag()
}

internal class IcuUnicodeAnalyzer : BoundedUnicodeAnalyzer {
    init {
        verifyPinnedUnicodeData()
    }

    override fun analyze(snapshot: TextSnapshot, request: UnicodeAnalysisRequest): UnicodeAnalysis =
        requireComplete(analyze(snapshot, request, UnicodeAnalysisProfile.unbounded, CancellationToken.none))

    override fun analyze(
        snapshot: TextSnapshot,
        request: UnicodeAnalysisRequest,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): UnicodeAnalysisOutcome {
        if (snapshot.scalars.size > profile.maxScalars) {
            return UnicodeAnalysisOutcome.LimitExceeded(UnicodeAnalysisLimit.SCALARS, snapshot.scalars.size)
        }
        return try {
            observeCancellation(cancellationToken)
            val locale = parseLanguage(request.language)
            val canonicalLanguage = locale.toLanguageTag()
            val canonicalText = CanonicalUtf16Text(snapshot, profile, cancellationToken)
            val bidiPreparation = prepareBidi(snapshot.scalars, request.baseDirection, profile, cancellationToken)
            val bidi = bidi(bidiPreparation.text, request.baseDirection)
            val resolvedBidiLevels = resolvedBidiLevels(
                snapshot,
                bidi,
                request.baseDirection,
                bidiPreparation.fsiDirections,
                profile,
                cancellationToken,
            )
            val logicalBidiRuns = bidiRuns(snapshot, resolvedBidiLevels, profile, cancellationToken)
            val graphemes = graphemeClusters(snapshot, canonicalText, cancellationToken)
            val scripts = scriptLanguageRuns(snapshot, locale, canonicalLanguage, profile, cancellationToken)
            val visualBidiRuns = reorderBidiRuns(logicalBidiRuns, profile, cancellationToken)
            UnicodeAnalysisOutcome.Success(
                UnicodeAnalysis(
                    range = snapshot.range,
                    unicodeData = UNICODE_DATA,
                    graphemeClusters = graphemes,
                    scriptLanguageRuns = scripts,
                    logicalBidiRuns = logicalBidiRuns,
                    visualBidiRuns = visualBidiRuns,
                ),
            )
        } catch (_: UnicodeAnalysisCancelled) {
            UnicodeAnalysisOutcome.Cancelled
        }
    }
}

private fun requireComplete(outcome: UnicodeAnalysisOutcome): UnicodeAnalysis = when (outcome) {
    is UnicodeAnalysisOutcome.Success -> outcome.value
    is UnicodeAnalysisOutcome.LimitExceeded -> error("The unbounded Unicode analyzer exceeded ${outcome.limit}.")
    UnicodeAnalysisOutcome.Cancelled -> error("The non-cancellable Unicode analyzer was cancelled.")
}

private fun parseLanguage(language: String): ULocale = try {
    ULocale.Builder().setLanguageTag(language).build()
} catch (_: IllformedLocaleException) {
    throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
}

private fun graphemeClusters(
    snapshot: TextSnapshot,
    text: CanonicalUtf16Text,
    cancellationToken: CancellationToken,
): List<TextRange> {
    if (snapshot.scalars.isEmpty()) return emptyList()
    val iterator = BreakIterator.getCharacterInstance(ULocale.ROOT)
    iterator.setText(text.value)
    val ranges = mutableListOf<TextRange>()
    var startUtf16 = iterator.first()
    var endUtf16 = iterator.next()
    while (endUtf16 != BreakIterator.DONE) {
        observeCancellation(cancellationToken)
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
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): List<ScriptLanguageRun> {
    if (snapshot.scalars.isEmpty()) return emptyList()
    val languageScript = likelyScript(locale)
    val scriptProperties = snapshot.scalars.mapIndexed { index, scalar ->
        observeCancellation(index, profile, cancellationToken)
        scriptProperties(scalar)
    }
    val pairedScripts = pairedPunctuationScripts(snapshot.scalars, scriptProperties, profile, cancellationToken)
    val resolvedScripts = IntArray(snapshot.scalars.size)
    var previousScript: Int? = null
    snapshot.scalars.indices.forEach { scalarIndex ->
        observeCancellation(scalarIndex, profile, cancellationToken)
        val properties = scriptProperties[scalarIndex]
        val resolved = when {
            properties.script == UScript.UNKNOWN -> UScript.UNKNOWN
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
        resolvedScripts[scalarIndex] = resolved
        previousScript = resolved.takeIf(::isExplicitScript)
    }

    val runs = mutableListOf<ScriptLanguageRun>()
    var runStart = 0
    var script = resolvedScripts.first()
    for (scalarIndex in 1 until resolvedScripts.size) {
        observeCancellation(scalarIndex, profile, cancellationToken)
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

private fun nextContextScript(
    scriptProperties: List<ScriptProperties>,
    start: Int,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): Int? {
    for (scalarIndex in start until scriptProperties.size) {
        observeCancellation(scalarIndex, profile, cancellationToken)
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
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): Map<Int, Int> {
    val openingIndexes = mutableListOf<Int>()
    val resolvedScripts = mutableMapOf<Int, Int>()
    scalars.forEachIndexed { scalarIndex, scalar ->
        observeCancellation(scalarIndex, profile, cancellationToken)
        when (UCharacter.getIntPropertyValue(scalar, UProperty.BIDI_PAIRED_BRACKET_TYPE)) {
            UCharacter.BidiPairedBracketType.OPEN -> openingIndexes += scalarIndex
            UCharacter.BidiPairedBracketType.CLOSE -> {
                val matchingStackIndex = openingIndexes.indexOfLast { openingIndex ->
                    canonicalBracket(UCharacter.getBidiPairedBracket(scalars[openingIndex])) == canonicalBracket(scalar)
                }
                if (matchingStackIndex >= 0) {
                    val openingIndex = openingIndexes[matchingStackIndex]
                    openingIndexes.subList(matchingStackIndex, openingIndexes.size).clear()
                    enclosingScript(scriptProperties, openingIndex, scalarIndex, profile, cancellationToken)?.let { script ->
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
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): Int? {
    val before = previousContextScript(scriptProperties, openingIndex - 1, profile, cancellationToken)
    val after = nextContextScript(scriptProperties, closingIndex + 1, profile, cancellationToken)
    return before?.takeIf { it == after }
}

private fun previousContextScript(
    scriptProperties: List<ScriptProperties>,
    start: Int,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): Int? {
    for (scalarIndex in start downTo 0) {
        observeCancellation(scalarIndex, profile, cancellationToken)
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

private fun resolvedBidiLevels(
    snapshot: TextSnapshot,
    bidi: Bidi,
    baseDirection: BaseDirection,
    fsiDirections: BooleanArray,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): IntArray {
    val levels = IntArray(snapshot.scalars.size)
    var utf16Offset = 0
    snapshot.scalars.forEachIndexed { scalarIndex, scalar ->
        observeCancellation(scalarIndex, profile, cancellationToken)
        val level = bidi.getLevelAt(utf16Offset).toInt()
        levels[scalarIndex] = level
        utf16Offset += Character.charCount(scalar)
    }
    restoreNormativeIsolateLevels(snapshot.scalars, levels, baseDirection, fsiDirections, profile, cancellationToken)
    attachX9Controls(snapshot.scalars, levels)
    return levels
}

private fun isIsolateInitiator(scalar: Int): Boolean = when (UCharacter.getIntPropertyValue(scalar, UProperty.BIDI_CLASS)) {
    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt(),
    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt(),
    UCharacterEnums.ECharacterDirection.FIRST_STRONG_ISOLATE.toInt(),
    -> true

    else -> false
}

private fun restoreNormativeIsolateLevels(
    scalars: List<Int>,
    levels: IntArray,
    baseDirection: BaseDirection,
    fsiDirections: BooleanArray,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
) {
    val stack = mutableListOf(EmbeddingStatus(baseDirection.paragraphLevel, isolate = false, override = false))
    val embeddingLevelAt = IntArray(scalars.size)
    var overflowIsolates = 0
    var overflowEmbeddings = 0
    var validIsolates = 0
    scalars.forEachIndexed { index, scalar ->
        observeCancellation(index, profile, cancellationToken)
        embeddingLevelAt[index] = stack.last().level
        val direction = bidiClass(scalar)
        if (direction == UCharacterEnums.ECharacterDirection.DIR_NON_SPACING_MARK && validIsolates == 0) {
            var previous = index - 1
            while (previous >= 0 && bidiClass(scalars[previous]) in X9_REMOVED_DIRECTIONS) previous -= 1
            if (previous >= 0 && !isIsolateControl(bidiClass(scalars[previous]))) {
                levels[index] = if (stack.last().level != embeddingLevelAt[previous]) {
                    val sequenceDirection = maxOf(stack.last().level, embeddingLevelAt[previous]) % 2
                    if (stack.last().level % 2 == sequenceDirection) {
                        stack.last().level
                    } else {
                        stack.last().level + 1
                    }
                } else if (bidiClass(scalars[previous]) in BIDI_WHITESPACE_DIRECTIONS) {
                    levels[index]
                } else {
                    maxOf(levels[previous], embeddingLevelAt[previous])
                }
            }
        }
        when (direction) {
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_EMBEDDING,
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_OVERRIDE,
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_EMBEDDING,
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE,
            -> {
                if (overflowIsolates == 0) {
                    val rtl = bidiClass(scalar) == UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_EMBEDDING ||
                        bidiClass(scalar) == UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE
                    val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                    if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowEmbeddings == 0) {
                        val override = direction == UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_OVERRIDE ||
                            direction == UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE
                        stack += EmbeddingStatus(newLevel, isolate = false, override = override)
                    } else {
                        overflowEmbeddings += 1
                    }
                }
            }
            UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_FORMAT -> when {
                overflowIsolates != 0 -> Unit
                overflowEmbeddings != 0 -> overflowEmbeddings -= 1
                !stack.last().isolate && stack.size > 1 -> {
                    stack.removeAt(stack.lastIndex)
                }
            }
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt(),
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt(),
            UCharacterEnums.ECharacterDirection.FIRST_STRONG_ISOLATE.toInt(),
            -> {
                if (stack.last().override && validIsolates == 0) levels[index] = stack.last().level
                val rtl = when (bidiClass(scalar)) {
                    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt() -> false
                    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt() -> true
                    else -> fsiDirections[index]
                }
                val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowIsolates == 0 && overflowEmbeddings == 0) {
                    stack += EmbeddingStatus(newLevel, isolate = true, override = false)
                    validIsolates += 1
                } else {
                    overflowIsolates += 1
                }
            }
            UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_ISOLATE.toInt() -> {
                if (overflowIsolates != 0) {
                    overflowIsolates -= 1
                } else if (validIsolates != 0) {
                    overflowEmbeddings = 0
                    while (!stack.removeAt(stack.lastIndex).isolate) {
                        // Pop embeddings nested within the matching isolate.
                    }
                    validIsolates -= 1
                }
                if (stack.last().override && validIsolates == 0) levels[index] = stack.last().level
            }
        }
    }
    resetTrailingIsolateControls(scalars, levels, baseDirection.paragraphLevel)
}

private fun attachX9Controls(scalars: List<Int>, levels: IntArray) {
    var index = 0
    while (index < scalars.size) {
        if (bidiClass(scalars[index]) !in X9_REMOVED_DIRECTIONS) {
            index += 1
            continue
        }
        val start = index
        while (index < scalars.size && bidiClass(scalars[index]) in X9_REMOVED_DIRECTIONS) index += 1
        val retainedLevel = when {
            index < levels.size -> levels[index]
            start > 0 -> levels[start - 1]
            else -> levels[start]
        }
        for (removedIndex in start until index) levels[removedIndex] = retainedLevel
    }
}

private fun resetTrailingIsolateControls(scalars: List<Int>, levels: IntArray, paragraphLevel: Int) {
    fun resetBefore(endExclusive: Int) {
        var index = endExclusive - 1
        while (index >= 0 && isL1Resettable(bidiClass(scalars[index]))) {
            if (isIsolateControl(bidiClass(scalars[index]))) levels[index] = paragraphLevel
            index -= 1
        }
    }

    scalars.forEachIndexed { index, scalar ->
        if (bidiClass(scalar) == UCharacterEnums.ECharacterDirection.BLOCK_SEPARATOR ||
            bidiClass(scalar) == UCharacterEnums.ECharacterDirection.SEGMENT_SEPARATOR
        ) {
            resetBefore(index)
        }
    }
    resetBefore(scalars.size)
}

private fun isL1Resettable(direction: Int): Boolean = direction in L1_RESETTABLE_DIRECTIONS

private fun isIsolateControl(direction: Int): Boolean = direction ==
    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt() ||
    direction == UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt() ||
    direction == UCharacterEnums.ECharacterDirection.FIRST_STRONG_ISOLATE.toInt() ||
    direction == UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_ISOLATE.toInt()

private fun fsiDirections(
    scalars: List<Int>,
    baseDirection: BaseDirection,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): BooleanArray {
    // P2 ignores strong types inside nested isolates, so each frame records only direct content.
    val fallback = baseDirection == BaseDirection.RIGHT_TO_LEFT
    val directions = BooleanArray(scalars.size) { fallback }
    val stack = mutableListOf(IsolateDirectionFrame())
    var closedFrames = 0

    fun closeNestedFrames() {
        while (stack.size > 1) {
            observeCancellation(closedFrames++, profile, cancellationToken)
            val frame = stack.removeAt(stack.lastIndex)
            if (frame.fsiIndex != null) directions[frame.fsiIndex] = frame.firstStrongRtl ?: fallback
        }
    }

    scalars.forEachIndexed { index, scalar ->
        observeCancellation(index, profile, cancellationToken)
        when (bidiClass(scalar)) {
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT ->
                if (stack.last().firstStrongRtl == null) stack.last().firstStrongRtl = false
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT,
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ARABIC,
            -> if (stack.last().firstStrongRtl == null) stack.last().firstStrongRtl = true
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt(),
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt(),
            -> stack += IsolateDirectionFrame()
            UCharacterEnums.ECharacterDirection.FIRST_STRONG_ISOLATE.toInt() ->
                stack += IsolateDirectionFrame(fsiIndex = index)
            UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_ISOLATE.toInt() -> if (stack.size > 1) {
                val frame = stack.removeAt(stack.lastIndex)
                if (frame.fsiIndex != null) directions[frame.fsiIndex] = frame.firstStrongRtl ?: fallback
            }
            UCharacterEnums.ECharacterDirection.BLOCK_SEPARATOR -> {
                closeNestedFrames()
                stack.single().firstStrongRtl = null
            }
        }
    }
    closeNestedFrames()
    return directions
}

private data class IsolateDirectionFrame(
    val fsiIndex: Int? = null,
    var firstStrongRtl: Boolean? = null,
)

private fun bidiClass(scalar: Int): Int = UCharacter.getIntPropertyValue(scalar, UProperty.BIDI_CLASS)

private fun prepareBidi(
    scalars: List<Int>,
    baseDirection: BaseDirection,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): BidiPreparation {
    val fsiDirections = fsiDirections(scalars, baseDirection, profile, cancellationToken)
    val explicit = explicitBidiStructure(scalars, baseDirection, fsiDirections, profile, cancellationToken)
    val overflowedSequences = bracketOverflowedSequences(scalars, explicit, profile, cancellationToken)
    val text = buildString {
        scalars.forEachIndexed { index, scalar ->
            observeCancellation(index, profile, cancellationToken)
            val bracketType = UCharacter.getIntPropertyValue(scalar, UProperty.BIDI_PAIRED_BRACKET_TYPE)
            appendCodePoint(
                when {
                    explicit.sequenceAt[index] in overflowedSequences && bracketType != UCharacter.BidiPairedBracketType.NONE ->
                        NON_BRACKET_OTHER_NEUTRAL
                    scalar == CANONICAL_LEFT_ANGLE_BRACKET -> CANONICAL_LEFT_ANGLE_BRACKET_REPRESENTATIVE
                    scalar == CANONICAL_RIGHT_ANGLE_BRACKET -> CANONICAL_RIGHT_ANGLE_BRACKET_REPRESENTATIVE
                    else -> scalar
                },
            )
        }
    }
    return BidiPreparation(text, fsiDirections)
}

private fun explicitBidiStructure(
    scalars: List<Int>,
    baseDirection: BaseDirection,
    fsiDirections: BooleanArray,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): ExplicitBidiStructure {
    val levels = IntArray(scalars.size)
    val overrides = IntArray(scalars.size) { NO_OVERRIDE }
    val matchingPdi = IntArray(scalars.size) { NO_INDEX }
    val stack = mutableListOf(EmbeddingStatus(baseDirection.paragraphLevel, isolate = false, override = false))
    val isolateIndexes = mutableListOf<Int>()
    var overflowIsolates = 0
    var overflowEmbeddings = 0
    var validIsolates = 0

    scalars.forEachIndexed { index, scalar ->
        observeCancellation(index, profile, cancellationToken)
        val direction = bidiClass(scalar)
        if (direction == UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_ISOLATE.toInt() && overflowIsolates == 0 && validIsolates > 0) {
            overflowEmbeddings = 0
            while (!stack.removeAt(stack.lastIndex).isolate) {
                // Pop embeddings nested within the matching isolate.
            }
            matchingPdi[isolateIndexes.removeAt(isolateIndexes.lastIndex)] = index
            validIsolates -= 1
        }
        levels[index] = stack.last().level
        overrides[index] = when {
            stack.last().override && stack.last().level % 2 == 0 -> UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT
            stack.last().override -> UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT
            else -> NO_OVERRIDE
        }
        when (direction) {
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_EMBEDDING,
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_OVERRIDE,
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_EMBEDDING,
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE,
            -> if (overflowIsolates == 0) {
                val rtl = direction == UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_EMBEDDING ||
                    direction == UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE
                val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowEmbeddings == 0) {
                    val override = direction == UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_OVERRIDE ||
                        direction == UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE
                    stack += EmbeddingStatus(newLevel, isolate = false, override = override)
                } else {
                    overflowEmbeddings += 1
                }
            }
            UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_FORMAT -> when {
                overflowIsolates != 0 -> Unit
                overflowEmbeddings != 0 -> overflowEmbeddings -= 1
                !stack.last().isolate && stack.size > 1 -> stack.removeAt(stack.lastIndex)
            }
            UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt(),
            UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt(),
            UCharacterEnums.ECharacterDirection.FIRST_STRONG_ISOLATE.toInt(),
            -> {
                val rtl = when (direction) {
                    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt() -> false
                    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt() -> true
                    else -> fsiDirections[index]
                }
                val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowIsolates == 0 && overflowEmbeddings == 0) {
                    stack += EmbeddingStatus(newLevel, isolate = true, override = false)
                    isolateIndexes += index
                    validIsolates += 1
                } else {
                    overflowIsolates += 1
                }
            }
            UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_ISOLATE.toInt() -> {
                if (overflowIsolates != 0) overflowIsolates -= 1
            }
            UCharacterEnums.ECharacterDirection.BLOCK_SEPARATOR -> {
                stack.clear()
                stack += EmbeddingStatus(baseDirection.paragraphLevel, isolate = false, override = false)
                isolateIndexes.clear()
                overflowIsolates = 0
                overflowEmbeddings = 0
                validIsolates = 0
            }
        }
    }

    return ExplicitBidiStructure(
        levels,
        overrides,
        isolatingRunSequences(scalars, levels, matchingPdi, profile, cancellationToken),
    )
}

private fun isolatingRunSequences(
    scalars: List<Int>,
    levels: IntArray,
    matchingPdi: IntArray,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): IntArray {
    // X10 links the run ending in an isolate initiator to the run beginning at its matching PDI.
    val runs = mutableListOf<MutableList<Int>>()
    scalars.indices.forEach { index ->
        observeCancellation(index, profile, cancellationToken)
        if (bidiClass(scalars[index]) in X9_REMOVED_DIRECTIONS) return@forEach
        val previous = runs.lastOrNull()?.lastOrNull()
        if (previous == null || levels[previous] != levels[index] ||
            bidiClass(scalars[previous]) == UCharacterEnums.ECharacterDirection.BLOCK_SEPARATOR
        ) {
            runs.add(mutableListOf())
        }
        runs.last() += index
    }
    val runAt = IntArray(scalars.size) { NO_INDEX }
    var mappedPositions = 0
    runs.forEachIndexed { runIndex, positions ->
        positions.forEach {
            observeCancellation(mappedPositions++, profile, cancellationToken)
            runAt[it] = runIndex
        }
    }
    val successors = IntArray(runs.size) { NO_INDEX }
    val hasPredecessor = BooleanArray(runs.size)
    runs.forEachIndexed { runIndex, positions ->
        observeCancellation(runIndex, profile, cancellationToken)
        val last = positions.last()
        val pdi = matchingPdi[last]
        if (pdi != NO_INDEX) {
            successors[runIndex] = runAt[pdi]
            hasPredecessor[runAt[pdi]] = true
        }
    }
    val sequenceAt = IntArray(scalars.size) { NO_INDEX }
    var sequence = 0
    var linkedRuns = 0
    var assignedPositions = 0
    runs.indices.forEach { startRun ->
        observeCancellation(startRun, profile, cancellationToken)
        if (hasPredecessor[startRun]) return@forEach
        var run = startRun
        while (run != NO_INDEX) {
            observeCancellation(linkedRuns++, profile, cancellationToken)
            runs[run].forEach {
                observeCancellation(assignedPositions++, profile, cancellationToken)
                sequenceAt[it] = sequence
            }
            run = successors[run]
        }
        sequence += 1
    }
    return sequenceAt
}

private fun bracketOverflowedSequences(
    scalars: List<Int>,
    explicit: ExplicitBidiStructure,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): Set<Int> {
    // BD16 owns one fixed 63-entry bracket stack per isolating run sequence.
    val stacks = mutableMapOf<Int, MutableList<Int>>()
    val overflowed = mutableSetOf<Int>()
    scalars.forEachIndexed { index, scalar ->
        observeCancellation(index, profile, cancellationToken)
        val sequence = explicit.sequenceAt[index]
        if (sequence == NO_INDEX || sequence in overflowed || explicit.overrides[index] != NO_OVERRIDE) return@forEachIndexed
        val stack = stacks.getOrPut(sequence, ::mutableListOf)
        when (UCharacter.getIntPropertyValue(scalar, UProperty.BIDI_PAIRED_BRACKET_TYPE)) {
            UCharacter.BidiPairedBracketType.OPEN -> if (stack.size == MAX_PAIRED_BRACKET_DEPTH) {
                overflowed += sequence
                stack.clear()
            } else {
                stack += canonicalBracket(UCharacter.getBidiPairedBracket(scalar))
            }
            UCharacter.BidiPairedBracketType.CLOSE -> {
                val match = stack.indexOfLast { it == canonicalBracket(scalar) }
                if (match >= 0) stack.subList(match, stack.size).clear()
            }
        }
    }
    return overflowed
}

private fun canonicalBracket(scalar: Int): Int = when (scalar) {
    CANONICAL_LEFT_ANGLE_BRACKET -> CANONICAL_LEFT_ANGLE_BRACKET_REPRESENTATIVE
    CANONICAL_RIGHT_ANGLE_BRACKET -> CANONICAL_RIGHT_ANGLE_BRACKET_REPRESENTATIVE
    else -> scalar
}

private data class BidiPreparation(val text: String, val fsiDirections: BooleanArray)
private data class ExplicitBidiStructure(
    val levels: IntArray,
    val overrides: IntArray,
    val sequenceAt: IntArray,
)

private fun nextEmbeddingLevel(current: Int, rtl: Boolean): Int = if (rtl) {
    if (current % 2 == 0) current + 1 else current + 2
} else {
    if (current % 2 == 0) current + 2 else current + 1
}

private val BaseDirection.paragraphLevel: Int
    get() = if (this == BaseDirection.LEFT_TO_RIGHT) 0 else 1

private data class EmbeddingStatus(
    val level: Int,
    val isolate: Boolean,
    val override: Boolean,
)

private fun bidiRuns(
    snapshot: TextSnapshot,
    levels: IntArray,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): List<BidiRun> {
    if (levels.isEmpty()) return emptyList()
    val runs = mutableListOf<BidiRun>()
    var start = 0
    var level = levels.first()
    for (scalarIndex in 1 until levels.size) {
        observeCancellation(scalarIndex, profile, cancellationToken)
        if (levels[scalarIndex] != level) {
            runs += BidiRun(scalarRange(snapshot, start, scalarIndex), level)
            start = scalarIndex
            level = levels[scalarIndex]
        }
    }
    runs += BidiRun(scalarRange(snapshot, start, levels.size), level)
    return runs
}

private fun reorderBidiRuns(
    logicalRuns: List<BidiRun>,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
): List<BidiRun> {
    var minimumOddLevel: Int? = null
    var maximumLevel = 0
    val visualRuns = ArrayList<BidiRun>(logicalRuns.size)
    logicalRuns.forEachIndexed { runIndex, run ->
        observeCancellation(runIndex, profile, cancellationToken)
        if (run.level.rem(2) == 1) {
            minimumOddLevel = minOf(minimumOddLevel ?: run.level, run.level)
        }
        maximumLevel = maxOf(maximumLevel, run.level)
        visualRuns += run
    }
    val firstReorderingLevel = minimumOddLevel ?: return logicalRuns
    for (level in maximumLevel downTo firstReorderingLevel) {
        var sequenceStart: Int? = null
        for (runIndex in 0..visualRuns.size) {
            observeCancellation(runIndex, profile, cancellationToken)
            if (runIndex < visualRuns.size && visualRuns[runIndex].level >= level) {
                if (sequenceStart == null) sequenceStart = runIndex
            } else if (sequenceStart != null) {
                reverseBidiRunSequence(visualRuns, sequenceStart, runIndex, profile, cancellationToken)
                sequenceStart = null
            }
        }
    }
    return visualRuns
}

private fun reverseBidiRunSequence(
    runs: MutableList<BidiRun>,
    start: Int,
    endExclusive: Int,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
) {
    var left = start
    var right = endExclusive - 1
    var swapIndex = 0
    while (left < right) {
        observeCancellation(swapIndex, profile, cancellationToken)
        val run = runs[left]
        runs[left] = runs[right]
        runs[right] = run
        left += 1
        right -= 1
        swapIndex += 1
    }
}

private fun bidi(text: String, baseDirection: BaseDirection): Bidi = Bidi().apply {
    val paragraphLevel = when (baseDirection) {
        BaseDirection.LEFT_TO_RIGHT -> Bidi.LTR
        BaseDirection.RIGHT_TO_LEFT -> Bidi.RTL
    }
    val oppositeDirectionSentinel = if (baseDirection == BaseDirection.LEFT_TO_RIGHT) '\u05D0' else 'a'
    setPara(text + '\u2029' + oppositeDirectionSentinel, paragraphLevel, null)
}

private class CanonicalUtf16Text(
    snapshot: TextSnapshot,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
) {
    val value: String
    private val scalarBoundaryToUtf16: IntArray = IntArray(snapshot.scalars.size + 1)

    init {
        val builder = StringBuilder()
        snapshot.scalars.forEachIndexed { scalarIndex, scalar ->
            observeCancellation(scalarIndex, profile, cancellationToken)
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

private fun observeCancellation(cancellationToken: CancellationToken) {
    if (cancellationToken.isCancellationRequested()) throw UnicodeAnalysisCancelled
}

private fun observeCancellation(
    scalarIndex: Int,
    profile: UnicodeAnalysisProfile,
    cancellationToken: CancellationToken,
) {
    if (scalarIndex % profile.cancellationCheckInterval == 0) observeCancellation(cancellationToken)
}

private data object UnicodeAnalysisCancelled : RuntimeException()

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
private const val MAX_EXPLICIT_EMBEDDING_LEVEL: Int = 125
private const val MAX_PAIRED_BRACKET_DEPTH: Int = 63
private const val NON_BRACKET_OTHER_NEUTRAL: Int = 0x0022
private const val CANONICAL_LEFT_ANGLE_BRACKET: Int = 0x2329
private const val CANONICAL_LEFT_ANGLE_BRACKET_REPRESENTATIVE: Int = 0x3008
private const val CANONICAL_RIGHT_ANGLE_BRACKET: Int = 0x232A
private const val CANONICAL_RIGHT_ANGLE_BRACKET_REPRESENTATIVE: Int = 0x3009
private const val NO_INDEX: Int = -1
private const val NO_OVERRIDE: Int = -1

private val L1_RESETTABLE_DIRECTIONS: Set<Int> = setOf(
    UCharacterEnums.ECharacterDirection.WHITE_SPACE_NEUTRAL,
    UCharacterEnums.ECharacterDirection.BOUNDARY_NEUTRAL,
    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_EMBEDDING,
    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_OVERRIDE,
    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_EMBEDDING,
    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE,
    UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_FORMAT,
    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_ISOLATE.toInt(),
    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_ISOLATE.toInt(),
    UCharacterEnums.ECharacterDirection.FIRST_STRONG_ISOLATE.toInt(),
    UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_ISOLATE.toInt(),
)

private val X9_REMOVED_DIRECTIONS: Set<Int> = setOf(
    UCharacterEnums.ECharacterDirection.BOUNDARY_NEUTRAL,
    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_EMBEDDING,
    UCharacterEnums.ECharacterDirection.LEFT_TO_RIGHT_OVERRIDE,
    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_EMBEDDING,
    UCharacterEnums.ECharacterDirection.RIGHT_TO_LEFT_OVERRIDE,
    UCharacterEnums.ECharacterDirection.POP_DIRECTIONAL_FORMAT,
)

private val BIDI_WHITESPACE_DIRECTIONS: Set<Int> = setOf(
    UCharacterEnums.ECharacterDirection.WHITE_SPACE_NEUTRAL,
    UCharacterEnums.ECharacterDirection.BLOCK_SEPARATOR,
    UCharacterEnums.ECharacterDirection.SEGMENT_SEPARATOR,
)
