package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile

/**
 * The UAX #9 machinery both bidirectional implementations run.
 *
 * The JVM resolves the levels with ICU's `Bidi`, and the portable resolver of [UnicodeBidiEngine]
 * implements the algorithm over this module's tables — but the *structure* around that core is one
 * piece of work: the FSI directions (P2, P3), the explicit embedding structure (X1–X8), the
 * isolating run sequences with their sos/eos types (X10), the bracket pairs (BD16), and the
 * normative repair of the X9-removed characters, which ICU does not implement. This object owns all
 * of it, parameterised by where the properties come from, so the two implementations cannot drift:
 * the ICU path passes ICU lookups, the portable path passes the generated tables, and the same code
 * decides when a sequence overflowed its bracket stack or which isolates need restoring.
 *
 * The implicit resolution (W1–W7, N0–N2, I1–I2) is *not* here: ICU has it inside `Bidi`, and the
 * portable resolver implements it beside this machinery in [UnicodeBidiEngine].
 */
internal object UnicodeBidiStructure {

    /** Where one scalar's BiDi class comes from. */
    internal fun interface ClassOf {
        /** The BiDi class of [scalar], per the Unicode Character Database. */
        operator fun get(scalar: Int): BidiClass
    }

    /** Where one scalar's bracket facts come from. */
    internal interface BracketsOf {
        /** The `Bidi_Paired_Bracket_Type` of [scalar]. */
        fun typeOf(scalar: Int): BidiBracketType

        /** The scalar [scalar] is paired with, or [scalar] itself when it is not a bracket. */
        fun pairedOf(scalar: Int): Int

        /**
         * The canonical spelling of [scalar] for pairing purposes: BD16 matches the canonical
         * equivalents, so the two angle brackets — whose own pairs are each other's canonical
         * spellings — pair with the CJK brackets they are canonically equivalent to.
         */
        fun canonicalOf(scalar: Int): Int
    }

    // ------------------------------------------------------------------------------------------------
    // P2, P3: the first strong type of each FSI
    // ------------------------------------------------------------------------------------------------

    /**
     * P2: the first strong type of each FSI, ignoring strong types inside nested isolates.
     * P3 falls back to the left-to-right direction when an FSI finds no strong type — whatever
     * the paragraph's direction is, which is what the corpus demands of a weak-content FSI. The
     * array answers, per FSI position, whether its content is right-to-left.
     */
    internal fun fsiDirections(
        scalars: List<Int>,
        paragraphLevel: Int,
        classOf: ClassOf,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): BooleanArray {
        val directions = BooleanArray(scalars.size)
        val stack = mutableListOf(IsolateFrame())
        var closedFrames = 0

        fun closeNestedFrames() {
            while (stack.size > 1) {
                observeCancellation(closedFrames++, profile, cancellationToken)
                val frame = stack.removeAt(stack.lastIndex)
                frame.fsiIndex?.let { index -> directions[index] = frame.firstStrongRtl ?: P3_FALLBACK_RTL }
            }
        }

        scalars.forEachIndexed { index, scalar ->
            observeCancellation(index, profile, cancellationToken)
            when (classOf[scalar]) {
                BidiClass.L -> if (stack.last().firstStrongRtl == null) stack.last().firstStrongRtl = false
                BidiClass.R, BidiClass.AL ->
                    if (stack.last().firstStrongRtl == null) stack.last().firstStrongRtl = true
                BidiClass.LRI, BidiClass.RLI -> stack.add(IsolateFrame())
                BidiClass.FSI -> stack.add(IsolateFrame(fsiIndex = index))
                BidiClass.PDI -> if (stack.size > 1) {
                    val frame = stack.removeAt(stack.lastIndex)
                    frame.fsiIndex?.let { index -> directions[index] = frame.firstStrongRtl ?: P3_FALLBACK_RTL }
                }
                BidiClass.B -> {
                    closeNestedFrames()
                    stack.single().firstStrongRtl = null
                }
                else -> Unit
            }
        }
        closeNestedFrames()
        return directions
    }

    /** P3's answer when an FSI finds no strong type inside: left-to-right, always. */
    private const val P3_FALLBACK_RTL: Boolean = false

    private class IsolateFrame(
        var fsiIndex: Int? = null,
        var firstStrongRtl: Boolean? = null,
    )

    // ------------------------------------------------------------------------------------------------
    // X1–X8, X10: the explicit embedding structure and the isolating run sequences
    // ------------------------------------------------------------------------------------------------

    /** The explicit structure X1–X10 produce, everything the implicit rules need. */
    internal class ExplicitStructure(
        /** The embedding level X1–X8 assign to every scalar, removed characters included. */
        val levels: IntArray,
        /**
         * The level every scalar presents to a neighbouring sequence's sos or eos computation:
         * an opener stands for the embedding it opened, a closer for the one it closed, a
         * separator for the paragraph. X10's comparison reads this, never [levels].
         */
        val presentedLevels: IntArray,
        /** The direction every scalar is overridden to, or [NO_OVERRIDE]. */
        val overrides: IntArray,
        /** For each isolate initiator, the index of its matching PDI, or [NO_INDEX]. */
        val matchingPdi: IntArray,
        /** X10's isolating run sequence id per scalar, or [NO_INDEX] for a removed character. */
        val sequenceAt: IntArray,
        /** One entry per isolating run sequence, in id order. */
        val sequences: List<Sequence>,
    ) {
        /** One isolating run sequence: the scalars it covers and its sos/eos directions. */
        internal class Sequence(
            /** The scalar positions of the sequence, in logical order. */
            val positions: IntArray,
            /** Whether sos, the type before the sequence, is right-to-left. */
            val sosRtl: Boolean,
            /** Whether eos, the type after the sequence, is right-to-left. */
            val eosRtl: Boolean,
        )
    }

    internal fun explicitStructure(
        scalars: List<Int>,
        paragraphLevel: Int,
        fsiDirections: BooleanArray,
        classOf: ClassOf,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): ExplicitStructure {
        val levels = IntArray(scalars.size)
        val presentedLevels = IntArray(scalars.size)
        val overrides = IntArray(scalars.size) { NO_OVERRIDE }
        val matchingPdi = IntArray(scalars.size) { NO_INDEX }
        val pdiMatched = BooleanArray(scalars.size)
        val stack = mutableListOf(Embedding(paragraphLevel, isolate = false, override = false))
        val isolateIndexes = mutableListOf<Int>()
        var overflowIsolates = 0
        var overflowEmbeddings = 0
        var validIsolates = 0

        scalars.forEachIndexed { index, scalar ->
            observeCancellation(index, profile, cancellationToken)
            val direction = classOf[scalar]
            val enclosingLevel = stack.last().level
            if (direction == BidiClass.PDI && overflowIsolates == 0 && validIsolates > 0) {
                overflowEmbeddings = 0
                while (!stack.removeAt(stack.lastIndex).isolate) {
                    // Pop embeddings nested within the matching isolate.
                }
                matchingPdi[isolateIndexes.removeAt(isolateIndexes.lastIndex)] = index
                pdiMatched[index] = true
                validIsolates -= 1
            }
            overrides[index] = when {
                stack.last().override && stack.last().level % 2 == 0 -> BidiClass.L.ordinal
                stack.last().override -> BidiClass.R.ordinal
                else -> NO_OVERRIDE
            }
            when (direction) {
                BidiClass.LRE, BidiClass.LRO, BidiClass.RLE, BidiClass.RLO ->
                    if (overflowIsolates == 0) {
                        val rtl = direction == BidiClass.RLE || direction == BidiClass.RLO
                        val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                        if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowEmbeddings == 0) {
                            val override = direction == BidiClass.LRO || direction == BidiClass.RLO
                            stack.add(Embedding(newLevel, isolate = false, override = override))
                        } else {
                            overflowEmbeddings += 1
                        }
                    }
                BidiClass.PDF -> when {
                    overflowIsolates != 0 -> Unit
                    overflowEmbeddings != 0 -> overflowEmbeddings -= 1
                    !stack.last().isolate && stack.size > 1 -> stack.removeAt(stack.lastIndex)
                }
                BidiClass.LRI, BidiClass.RLI, BidiClass.FSI -> {
                    val rtl = when (direction) {
                        BidiClass.LRI -> false
                        BidiClass.RLI -> true
                        else -> fsiDirections[index]
                    }
                    val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                    if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        stack.add(Embedding(newLevel, isolate = true, override = false))
                        isolateIndexes.add(index)
                        validIsolates += 1
                    } else {
                        overflowIsolates += 1
                    }
                }
                BidiClass.PDI -> if (overflowIsolates != 0) overflowIsolates -= 1
                BidiClass.B -> {
                    stack.clear()
                    stack.add(Embedding(paragraphLevel, isolate = false, override = false))
                    isolateIndexes.clear()
                    overflowIsolates = 0
                    overflowEmbeddings = 0
                    validIsolates = 0
                }
                else -> Unit
            }
            // The level a scalar carries: a separator takes the paragraph level whatever it
            // terminated (X8); an isolate initiator belongs to the embedding that contains it,
            // not the one it opens; every other scalar ends in the embedding it sits in after
            // its own push or pop. What it presents to a neighbouring sequence differs: an
            // opener stands for the embedding it opened, a closer for the one it closed, and a
            // separator for the paragraph.
            levels[index] = when {
                direction == BidiClass.B || direction == BidiClass.S -> paragraphLevel
                direction.isIsolateInitiator() -> enclosingLevel
                else -> stack.last().level
            }
            // What each character presents to the sos and eos comparisons and to the run
            // structure: the embedding it sits in per the stack. A paragraph separator sits in
            // the paragraph — its own X8 reset already put it there; a segment separator sits in
            // the embedding around it, whatever X8 assigns it for its own resolution; an isolate
            // initiator sits in the embedding that contains it, not the one it opens.
            presentedLevels[index] = when {
                direction.isIsolateInitiator() -> enclosingLevel
                else -> stack.last().level
            }
        }

        val (sequenceAt, sequences) = isolatingRunSequences(
            scalars,
            levels,
            presentedLevels,
            matchingPdi,
            pdiMatched,
            paragraphLevel,
            classOf,
            profile,
            cancellationToken,
        )
        return ExplicitStructure(levels, presentedLevels, overrides, matchingPdi, sequenceAt, sequences)
    }

    private class Embedding(
        val level: Int,
        val isolate: Boolean,
        val override: Boolean,
    )

    /** X10: the runs, their linking through matching isolates, and every sequence's sos/eos. */
    private fun isolatingRunSequences(
        scalars: List<Int>,
        levels: IntArray,
        presentedLevels: IntArray,
        matchingPdi: IntArray,
        pdiMatched: BooleanArray,
        paragraphLevel: Int,
        classOf: ClassOf,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): Pair<IntArray, List<ExplicitStructure.Sequence>> {
        val runs = mutableListOf<MutableList<Int>>()
        scalars.indices.forEach { index ->
            observeCancellation(index, profile, cancellationToken)
            val direction = classOf[scalars[index]]
            if (direction.removedByX9()) return@forEach
            val previous = runs.lastOrNull()?.lastOrNull()
            // X10 breaks a run after an isolate initiator, at a paragraph separator, and before
            // a PDI whose isolate initiator matched — an unmatched PDI has no isolate to close
            // and behaves as a neutral, staying inside the run.
            val previousIsInitiator = previous != null && classOf[scalars[previous]].isIsolateInitiator()
            if (previous == null || previousIsInitiator || presentedLevels[previous] != presentedLevels[index] ||
                classOf[scalars[previous]] == BidiClass.B ||
                (direction == BidiClass.PDI && pdiMatched[index])
            ) {
                runs.add(mutableListOf())
            }
            runs.last().add(index)
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
            val pdi = matchingPdi[positions.last()]
            if (pdi != NO_INDEX) {
                successors[runIndex] = runAt[pdi]
                hasPredecessor[runAt[pdi]] = true
            }
        }
        val sequenceAt = IntArray(scalars.size) { NO_INDEX }
        val sequences = mutableListOf<ExplicitStructure.Sequence>()
        var linkedRuns = 0
        var assignedPositions = 0
        runs.indices.forEach { startRun ->
            observeCancellation(startRun, profile, cancellationToken)
            if (hasPredecessor[startRun]) return@forEach
            val positions = mutableListOf<Int>()
            var run = startRun
            while (run != NO_INDEX) {
                observeCancellation(linkedRuns++, profile, cancellationToken)
                runs[run].forEach {
                    observeCancellation(assignedPositions++, profile, cancellationToken)
                    sequenceAt[it] = sequences.size
                    positions.add(it)
                }
                run = successors[run]
            }
            // X10's sos/eos: the higher of the sequence's own boundary level and the level the
            // nearest character outside it presents — skipping the characters X9 removes, and
            // using the paragraph embedding level where there is none — decides, odd meaning
            // right-to-left. The sequence's own boundary is its first or last character's level,
            // except that a separator opening or closing a sequence presents the embedding it
            // sits in rather than the paragraph level X8 assigns it. A sequence ending in an
            // unterminated isolate initiator compares against the paragraph level, since its
            // content lies beyond it.
            val firstPosition = positions.first()
            val sosRtl = maxOf(
                presentedLevels[firstPosition],
                outsidePresentedLevel(scalars, presentedLevels, classOf, firstPosition, step = -1, paragraphLevel),
            ).rem(2) == 1
            val lastPosition = positions.last()
            val eosRtl = if (classOf[scalars[lastPosition]].isIsolateInitiator() && matchingPdi[lastPosition] == NO_INDEX) {
                maxOf(presentedLevels[lastPosition], paragraphLevel).rem(2) == 1
            } else {
                maxOf(
                    presentedLevels[lastPosition],
                    outsidePresentedLevel(scalars, presentedLevels, classOf, lastPosition, step = +1, paragraphLevel),
                ).rem(2) == 1
            }
            sequences.add(ExplicitStructure.Sequence(positions.toIntArray(), sosRtl, eosRtl))
        }
        return Pair(sequenceAt, sequences)
    }

    /**
     * The level the nearest non-removed character outside the sequence presents, or the
     * paragraph's when the sequence reaches the edge of the text.
     */
    private fun outsidePresentedLevel(
        scalars: List<Int>,
        presentedLevels: IntArray,
        classOf: ClassOf,
        from: Int,
        step: Int,
        paragraphLevel: Int,
    ): Int {
        var index = from + step
        while (index in presentedLevels.indices) {
            if (!classOf[scalars[index]].removedByX9()) return presentedLevels[index]
            index += step
        }
        return paragraphLevel
    }

    // ------------------------------------------------------------------------------------------------
    // BD16: the bracket pairs of one isolating run sequence
    // ------------------------------------------------------------------------------------------------

    /** The bracket pairs BD16 found, and the sequences whose bracket stack overflowed. */
    internal class BracketResolution(
        /** One entry per pair, ordered by the logical position of the opening bracket. */
        val pairs: List<Pair>,
        /** The sequence ids whose bracket stack overflowed the fixed depth. */
        val overflowedSequences: Set<Int>,
    ) {
        /** One bracket pair, by scalar position. */
        internal class Pair(val opening: Int, val closing: Int)
    }

    internal fun resolveBracketPairs(
        scalars: List<Int>,
        explicit: ExplicitStructure,
        bracketsOf: BracketsOf,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): BracketResolution {
        // BD16 owns one fixed 63-entry bracket stack per isolating run sequence.
        val stacks = HashMap<Int, MutableList<StackEntry>>()
        val pairs = mutableListOf<BracketResolution.Pair>()
        val overflowed = mutableSetOf<Int>()
        var stackWork = 0
        scalars.forEachIndexed { index, scalar ->
            observeCancellation(index, profile, cancellationToken)
            val sequence = explicit.sequenceAt[index]
            if (sequence == NO_INDEX || sequence in overflowed || explicit.overrides[index] != NO_OVERRIDE) {
                return@forEachIndexed
            }
            val stack = stacks.getOrPut(sequence) { mutableListOf() }
            when (bracketsOf.typeOf(scalar)) {
                BidiBracketType.OPEN -> if (stack.size == MAX_PAIRED_BRACKET_DEPTH) {
                    overflowed.add(sequence)
                    stack.clear()
                } else {
                    stack.add(StackEntry(bracketsOf.canonicalOf(bracketsOf.pairedOf(scalar)), index))
                }
                BidiBracketType.CLOSE -> {
                    val closing = bracketsOf.canonicalOf(scalar)
                    var match = stack.lastIndex
                    while (match >= 0) {
                        observeCancellation(stackWork++, profile, cancellationToken)
                        if (stack[match].expectedClosing == closing) break
                        match -= 1
                    }
                    if (match >= 0) {
                        pairs.add(BracketResolution.Pair(stack[match].position, index))
                        while (stack.lastIndex >= match) {
                            observeCancellation(stackWork++, profile, cancellationToken)
                            stack.removeAt(stack.lastIndex)
                        }
                    }
                }
                BidiBracketType.NONE -> Unit
            }
        }
        val validPairs = mutableListOf<BracketResolution.Pair>()
        pairs.forEachIndexed { pairIndex, pair ->
            observeCancellation(pairIndex, profile, cancellationToken)
            if (explicit.sequenceAt[pair.opening] !in overflowed) validPairs.add(pair)
        }
        return BracketResolution(validPairs, overflowed)
    }

    private class StackEntry(val expectedClosing: Int, val position: Int)

    // ------------------------------------------------------------------------------------------------
    // The normative repair of the X9-removed characters
    // ------------------------------------------------------------------------------------------------

    /**
     * Restores the levels the removed characters must carry and resets the trailing whitespace
     * before each separator, per UAX #9's own treatment of what it removes.
     *
     * ICU does not implement this: it answers levels for the removed characters from its own
     * internals, so the JVM path repairs ICU's output, and the portable path repairs levels whose
     * removed positions still carry their raw embedding level. Either way this is the same function.
     */
    internal fun repairLevels(
        scalars: List<Int>,
        levels: IntArray,
        paragraphLevel: Int,
        fsiDirections: BooleanArray,
        classOf: ClassOf,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ) {
        restoreNormativeIsolateLevels(scalars, levels, paragraphLevel, fsiDirections, classOf, profile, cancellationToken)
        attachX9Controls(scalars, levels, classOf)
        resetTrailingIsolateControls(scalars, levels, paragraphLevel, classOf)
    }

    private fun restoreNormativeIsolateLevels(
        scalars: List<Int>,
        levels: IntArray,
        baseParagraphLevel: Int,
        fsiDirections: BooleanArray,
        classOf: ClassOf,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ) {
        val stack = mutableListOf(Embedding(baseParagraphLevel, isolate = false, override = false))
        val embeddingLevelAt = IntArray(scalars.size)
        var overflowIsolates = 0
        var overflowEmbeddings = 0
        var validIsolates = 0
        scalars.forEachIndexed { index, scalar ->
            observeCancellation(index, profile, cancellationToken)
            embeddingLevelAt[index] = stack.last().level
            val direction = classOf[scalar]
            if (direction == BidiClass.NSM && validIsolates == 0) {
                var previous = index - 1
                while (previous >= 0 && classOf[scalars[previous]].removedByX9()) previous -= 1
                if (previous >= 0 && !classOf[scalars[previous]].isolateControl()) {
                    levels[index] = if (stack.last().level != embeddingLevelAt[previous]) {
                        val sequenceDirection = maxOf(stack.last().level, embeddingLevelAt[previous]) % 2
                        if (stack.last().level % 2 == sequenceDirection) {
                            stack.last().level
                        } else {
                            stack.last().level + 1
                        }
                    } else if (classOf[scalars[previous]] in BIDI_WHITESPACE_CLASSES) {
                        levels[index]
                    } else {
                        maxOf(levels[previous], embeddingLevelAt[previous])
                    }
                }
            }
            when (direction) {
                BidiClass.LRE, BidiClass.LRO, BidiClass.RLE, BidiClass.RLO -> {
                    if (overflowIsolates == 0) {
                        val rtl = direction == BidiClass.RLE || direction == BidiClass.RLO
                        val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                        if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowEmbeddings == 0) {
                            val override = direction == BidiClass.LRO || direction == BidiClass.RLO
                            stack.add(Embedding(newLevel, isolate = false, override = override))
                        } else {
                            overflowEmbeddings += 1
                        }
                    }
                }
                BidiClass.PDF -> when {
                    overflowIsolates != 0 -> Unit
                    overflowEmbeddings != 0 -> overflowEmbeddings -= 1
                    !stack.last().isolate && stack.size > 1 -> stack.removeAt(stack.lastIndex)
                }
                BidiClass.LRI, BidiClass.RLI, BidiClass.FSI -> {
                    if (stack.last().override && validIsolates == 0) levels[index] = stack.last().level
                    val rtl = when (direction) {
                        BidiClass.LRI -> false
                        BidiClass.RLI -> true
                        else -> fsiDirections[index]
                    }
                    val newLevel = nextEmbeddingLevel(stack.last().level, rtl)
                    if (newLevel <= MAX_EXPLICIT_EMBEDDING_LEVEL && overflowIsolates == 0 && overflowEmbeddings == 0) {
                        stack.add(Embedding(newLevel, isolate = true, override = false))
                        validIsolates += 1
                    } else {
                        overflowIsolates += 1
                    }
                }
                BidiClass.PDI -> {
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
                else -> Unit
            }
        }
        resetTrailingIsolateControls(scalars, levels, baseParagraphLevel, classOf)
    }

    private fun attachX9Controls(scalars: List<Int>, levels: IntArray, classOf: ClassOf) {
        var index = 0
        while (index < scalars.size) {
            if (!classOf[scalars[index]].removedByX9()) {
                index += 1
                continue
            }
            val start = index
            while (index < scalars.size && classOf[scalars[index]].removedByX9()) index += 1
            val retainedLevel = when {
                index < levels.size -> levels[index]
                start > 0 -> levels[start - 1]
                else -> levels[start]
            }
            for (removedIndex in start until index) levels[removedIndex] = retainedLevel
        }
    }

    private fun resetTrailingIsolateControls(
        scalars: List<Int>,
        levels: IntArray,
        paragraphLevel: Int,
        classOf: ClassOf,
    ) {
        // L1: the whitespace and the isolate formatting before each separator, and at the end of
        // the text, all take the paragraph embedding level. The removed characters are walked over
        // — they were already given their retained level — but only the whitespace and the isolate
        // controls are reset, which is what the rule names.
        fun resetBefore(endExclusive: Int) {
            var index = endExclusive - 1
            while (index >= 0 && classOf[scalars[index]] in L1_RESETTABLE_CLASSES) {
                val direction = classOf[scalars[index]]
                if (direction.isolateControl() || direction == BidiClass.WS) levels[index] = paragraphLevel
                index -= 1
            }
        }

        scalars.forEachIndexed { index, scalar ->
            if (classOf[scalar] == BidiClass.B || classOf[scalar] == BidiClass.S) {
                resetBefore(index)
            }
        }
        resetBefore(scalars.size)
    }

    private fun BidiClass.isolateControl(): Boolean = this == BidiClass.LRI ||
        this == BidiClass.RLI ||
        this == BidiClass.FSI ||
        this == BidiClass.PDI

    private fun BidiClass.isIsolateInitiator(): Boolean = this == BidiClass.LRI ||
        this == BidiClass.RLI ||
        this == BidiClass.FSI

    private fun BidiClass.removedByX9(): Boolean = when (this) {
        BidiClass.BN, BidiClass.LRE, BidiClass.LRO, BidiClass.RLE, BidiClass.RLO, BidiClass.PDF -> true
        else -> false
    }

    internal const val MAX_EXPLICIT_EMBEDDING_LEVEL: Int = 125
    internal const val MAX_PAIRED_BRACKET_DEPTH: Int = 63
    internal const val NO_INDEX: Int = -1
    internal const val NO_OVERRIDE: Int = -1

    private val L1_RESETTABLE_CLASSES: Set<BidiClass> = setOf(
        BidiClass.WS,
        BidiClass.BN,
        BidiClass.LRE,
        BidiClass.LRO,
        BidiClass.RLE,
        BidiClass.RLO,
        BidiClass.PDF,
        BidiClass.LRI,
        BidiClass.RLI,
        BidiClass.FSI,
        BidiClass.PDI,
    )

    private val BIDI_WHITESPACE_CLASSES: Set<BidiClass> = setOf(
        BidiClass.WS,
        BidiClass.B,
        BidiClass.S,
    )
}
