package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile

/**
 * The portable half of UAX #9: the resolved embedding level of every scalar, from this module's own
 * tables.
 *
 * The structure around the algorithm — the FSI directions, the explicit embedding structure, the
 * isolating run sequences, the bracket pairs, and the normative repair of the X9-removed characters
 * — is shared with the JVM path and lives in [UnicodeBidiStructure]; this object is what the JVM
 * outsources to ICU's `Bidi`: X1–X10 feed the weak rules W1–W7, the neutrals N0–N2, and the implicit
 * levels I1–I2, all speaking this module's [BidiClass] values.
 *
 * The entry point is [resolveLevels]. Two facts about its output matter to its callers: a level is
 * produced for *every* scalar, and the X9-removed ones are only meaningful after
 * [UnicodeBidiStructure.repairLevels] has run, which [resolveLevels] applies itself.
 */
internal object UnicodeBidiEngine {

    /** One resolved analysis: the embedding levels and the BD16 bracket pairs the resolution used. */
    internal class ResolvedLevels(
        val levels: IntArray,
        val bracketPairs: UnicodeBidiStructure.BracketResolution,
    )

    /**
     * Resolves the embedding level of every scalar of [scalars], per UAX #9, at [paragraphLevel],
     * and keeps the BD16 bracket pairs the resolution found — the same pairs the script resolution
     * attributes paired punctuation from, so one analysis computes them once.
     *
     * The X9-removed characters carry the normative level the shared repair assigns them, so the
     * result is directly partitionable into runs and reordable into visual order.
     */
    internal fun resolve(
        scalars: List<Int>,
        paragraphLevel: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): ResolvedLevels {
        val classOf = TABLES
        val fsi = UnicodeBidiStructure.fsiDirections(scalars, paragraphLevel, classOf, profile, cancellationToken)
        val explicit = UnicodeBidiStructure.explicitStructure(
            scalars,
            paragraphLevel,
            fsi,
            classOf,
            profile,
            cancellationToken,
        )
        val brackets = UnicodeBidiStructure.resolveBracketPairs(
            scalars,
            explicit,
            BRACKETS,
            profile,
            cancellationToken,
        )
        val types = implicitTypes(scalars, explicit, brackets, paragraphLevel, profile, cancellationToken)
        val levels = implicitLevels(scalars, explicit, types, profile, cancellationToken)
        UnicodeBidiStructure.repairLevels(
            scalars,
            levels,
            paragraphLevel,
            fsi,
            classOf,
            profile,
            cancellationToken,
        )
        return ResolvedLevels(levels, brackets)
    }

    internal fun resolveLevels(
        scalars: List<Int>,
        paragraphLevel: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): IntArray = resolve(scalars, paragraphLevel, profile, cancellationToken).levels

    private val TABLES: UnicodeBidiStructure.ClassOf = UnicodeBidiStructure.ClassOf { scalar ->
        UnicodeBidiClass.of(scalar)
    }

    private val BRACKETS: UnicodeBidiStructure.BracketsOf = object : UnicodeBidiStructure.BracketsOf {
        override fun typeOf(scalar: Int): BidiBracketType = UnicodeBidiBrackets.typeOf(scalar)
        override fun pairedOf(scalar: Int): Int = UnicodeBidiBrackets.pairedOf(scalar)

        // The canonical equivalents the UCD names for the two angle brackets: each points at the
        // other's canonical spelling, which is what makes a 2329/3009 pair match.
        override fun canonicalOf(scalar: Int): Int = when (scalar) {
            0x2329 -> 0x3008
            0x232A -> 0x3009
            else -> scalar
        }
    }

    // ------------------------------------------------------------------------------------------------
    // W1–W7, N0–N2: the implicit types
    // ------------------------------------------------------------------------------------------------

    /**
     * The per-scalar direction the implicit level rules see: `L`, `R`, a number (`EN`, `AN`), or
     * `null` for a removed character, which takes no part in any rule.
     *
     * W runs over each isolating run sequence in order, because every one of its backward searches
     * stops at the sequence's sos. N0 then resolves the bracket pairs in the logical order of their
     * opening brackets — its decisions may be informed by earlier pairs — and N1/N2 close.
     */
    private fun implicitTypes(
        scalars: List<Int>,
        explicit: UnicodeBidiStructure.ExplicitStructure,
        brackets: UnicodeBidiStructure.BracketResolution,
        paragraphLevel: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): Array<BidiClass?> {
        val types = arrayOfNulls<BidiClass>(scalars.size)
        scalars.forEachIndexed { index, scalar ->
            observeCancellation(index, profile, cancellationToken)
            if (explicit.sequenceAt[index] == UnicodeBidiStructure.NO_INDEX) return@forEachIndexed
            // X4 and X5: an override imposes its direction on everything inside it, which is what
            // the weak and neutral rules must see — except on the separators, which X8 assigns
            // the paragraph level and keeps out of every rule that follows.
            val direction = UnicodeBidiClass.of(scalar)
            types[index] = when {
                direction == BidiClass.B || direction == BidiClass.S -> direction
                explicit.overrides[index] == BidiClass.L.ordinal -> BidiClass.L
                explicit.overrides[index] == BidiClass.R.ordinal -> BidiClass.R
                else -> direction
            }
        }

        explicit.sequences.forEach { sequence ->
            weakRules(sequence, types, explicit, paragraphLevel)
        }
        neutralRules(scalars, explicit, brackets, types, paragraphLevel, profile, cancellationToken)
        return types
    }

    /** W1–W7 over one isolating run sequence. */
    private fun weakRules(
        sequence: UnicodeBidiStructure.ExplicitStructure.Sequence,
        types: Array<BidiClass?>,
        explicit: UnicodeBidiStructure.ExplicitStructure,
        paragraphLevel: Int,
    ) {
        val positions = sequence.positions
        val sos = if (sequence.sosRtl) BidiClass.R else BidiClass.L

        // W1: a nonspacing mark takes the type of the previous character in the sequence — ON when
        // that character is an isolate initiator or a PDI — or sos at the start of the sequence.
        var previous = sos
        positions.forEach { position ->
            val type = types[position]
            if (type == BidiClass.NSM) {
                types[position] = if (previous == BidiClass.LRI || previous == BidiClass.RLI ||
                    previous == BidiClass.FSI || previous == BidiClass.PDI
                ) {
                    BidiClass.ON
                } else {
                    previous
                }
            }
            previous = types[position] ?: previous
        }

        // W2: a European number becomes an Arabic number when the first strong type behind it,
        // searching to sos, is an Arabic letter.
        positions.forEachIndexed { positionIndex, position ->
            if (types[position] != BidiClass.EN) return@forEachIndexed
            var search = positionIndex - 1
            var found = sos
            while (search >= 0) {
                val candidate = types[positions[search]]
                if (candidate == BidiClass.R || candidate == BidiClass.L || candidate == BidiClass.AL) {
                    found = candidate
                    break
                }
                search -= 1
            }
            if (found == BidiClass.AL) types[position] = BidiClass.AN
        }

        // W3: an Arabic letter is right-to-left.
        positions.forEach { position ->
            if (types[position] == BidiClass.AL) types[position] = BidiClass.R
        }

        // W4: a single European separator between two European numbers is one; a common separator
        // between two numbers of the same type is that type.
        positions.forEachIndexed { positionIndex, position ->
            val before = if (positionIndex > 0) types[positions[positionIndex - 1]] else null
            val after = if (positionIndex + 1 < positions.size) types[positions[positionIndex + 1]] else null
            when (types[position]) {
                BidiClass.ES -> if (before == BidiClass.EN && after == BidiClass.EN) types[position] = BidiClass.EN
                BidiClass.CS -> when {
                    before == BidiClass.EN && after == BidiClass.EN -> types[position] = BidiClass.EN
                    before == BidiClass.AN && after == BidiClass.AN -> types[position] = BidiClass.AN
                    else -> Unit
                }
                else -> Unit
            }
        }

        // W5: a run of European terminators adjacent to a European number, on either side, is one.
        val european = HashSet<Int>()
        var chain = false
        positions.forEach { position ->
            when (types[position]) {
                BidiClass.EN -> chain = true
                BidiClass.ET -> if (chain) european.add(position)
                else -> chain = false
            }
        }
        chain = false
        for (index in positions.indices.reversed()) {
            val position = positions[index]
            when (types[position]) {
                BidiClass.EN -> chain = true
                BidiClass.ET -> if (chain) european.add(position) else chain = false
                else -> chain = false
            }
        }
        european.forEach { position -> types[position] = BidiClass.EN }

        // W6: every remaining separator and terminator is other-neutral.
        positions.forEach { position ->
            if (types[position] == BidiClass.ET || types[position] == BidiClass.ES ||
                types[position] == BidiClass.CS
            ) {
                types[position] = BidiClass.ON
            }
        }

        // W7: a European number becomes Latin when the first strong type behind it, searching to
        // sos, is Latin. No Arabic letter remains: W3 has already turned them right-to-left.
        positions.forEachIndexed { positionIndex, position ->
            if (types[position] != BidiClass.EN) return@forEachIndexed
            var search = positionIndex - 1
            var found = sos
            while (search >= 0) {
                val candidate = types[positions[search]]
                if (candidate == BidiClass.R || candidate == BidiClass.L) {
                    found = candidate
                    break
                }
                search -= 1
            }
            if (found == BidiClass.L) types[position] = BidiClass.L
        }
    }

    /** N0 over every bracket pair, then N1 and N2 over the neutral runs of each sequence. */
    private fun neutralRules(
        scalars: List<Int>,
        explicit: UnicodeBidiStructure.ExplicitStructure,
        brackets: UnicodeBidiStructure.BracketResolution,
        types: Array<BidiClass?>,
        paragraphLevel: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ) {
        val embeddingRtl = BooleanArray(explicit.sequences.size)
        explicit.sequences.forEachIndexed { sequenceIndex, sequence ->
            embeddingRtl[sequenceIndex] = explicit.presentedLevels[sequence.positions.first()].rem(2) == 1
        }

        // N0: the pairs, in the logical order of their opening brackets — an earlier pair's
        // decision is what a later pair reads. Types EN and AN count as R wherever N0 looks; a
        // sequence whose bracket stack overflowed keeps its brackets neutral.
        brackets.pairs.sortedBy { it.opening }.forEach { pair ->
            observeCancellation(pair.opening, profile, cancellationToken)
            val sequenceIndex = explicit.sequenceAt[pair.opening]
            if (sequenceIndex in brackets.overflowedSequences) return@forEach
            val embeddingRtlOfSequence = embeddingRtl[sequenceIndex]
            // N0 inspects the enclosed strong types as two existence tests, not a first-found
            // search: any strong type matching the embedding direction decides before any
            // opposite one is even considered.
            var enclosedL = false
            var enclosedR = false
            strongInside(scalars, types, pair.opening, pair.closing, explicit) { rtl ->
                if (rtl) enclosedR = true else enclosedL = true
            }
            val enclosedMatchingE = if (embeddingRtlOfSequence) enclosedR else enclosedL
            val enclosedOpposite = if (embeddingRtlOfSequence) enclosedL else enclosedR
            val resolvedRtl: Boolean = when {
                // N0.b: the enclosed strong types match the embedding direction.
                enclosedMatchingE -> embeddingRtlOfSequence
                // N0.c: only opposite types inside; the context before the pair decides — when
                // the context carries the same opposite direction the pair takes it, otherwise
                // both brackets take the embedding direction.
                enclosedOpposite -> {
                    val oppositeRtl = !embeddingRtlOfSequence
                    val contextRtl = strongBefore(scalars, types, explicit, sequenceIndex, pair.opening)
                    if (contextRtl == oppositeRtl) oppositeRtl else embeddingRtlOfSequence
                }
                // N0.d: no strong type inside; the pair stays neutral for N1 and N2.
                else -> return@forEach
            }
            val resolved = if (resolvedRtl) BidiClass.R else BidiClass.L
            types[pair.opening] = resolved
            types[pair.closing] = resolved
            nsmAfter(scalars, explicit, types, pair.opening, resolved)
            nsmAfter(scalars, explicit, types, pair.closing, resolved)
        }

        // N1 and N2: every maximal run of neutrals and isolates takes the surrounding strong
        // direction when both sides agree — numbers counting as right-to-left — and otherwise the
        // embedding direction.
        explicit.sequences.forEach { sequence ->
            val sos = if (sequence.sosRtl) BidiClass.R else BidiClass.L
            val eos = if (sequence.eosRtl) BidiClass.R else BidiClass.L
            var index = 0
            while (index < sequence.positions.size) {
                val position = sequence.positions[index]
                observeCancellation(position, profile, cancellationToken)
                // X8 keeps the separators themselves out of the resolution — but an NSM that W1
                // typed with a separator's type is a normal character and takes part.
                val original = UnicodeBidiClass.of(scalars[position])
                if (original == BidiClass.B || original == BidiClass.S) {
                    index += 1
                    continue
                }
                if (types[position].isNeutralOrIsolate()) {
                    val start = index
                    while (index < sequence.positions.size && types[sequence.positions[index]].isNeutralOrIsolate() &&
                        UnicodeBidiClass.of(scalars[sequence.positions[index]]).let { it != BidiClass.B && it != BidiClass.S }
                    ) {
                        index += 1
                    }
                    val before = strongDirection(scalars, types, sequence, start, backward = true, sos = sos)
                    val after = strongDirection(scalars, types, sequence, index, backward = false, sos = eos)
                    val resolved = if (before != null && before == after) before else {
                        if (embeddingRtl[explicit.sequenceAt[position]]) BidiClass.R else BidiClass.L
                    }
                    for (neutralIndex in start until index) types[sequence.positions[neutralIndex]] = resolved
                } else {
                    index += 1
                }
            }
        }
    }

    /** N0's fix for a nonspacing mark written immediately after a bracket whose type N0 set. */
    private fun nsmAfter(
        scalars: List<Int>,
        explicit: UnicodeBidiStructure.ExplicitStructure,
        types: Array<BidiClass?>,
        bracketPosition: Int,
        resolved: BidiClass,
    ) {
        val sequenceIndex = explicit.sequenceAt[bracketPosition]
        val sequence = explicit.sequences[sequenceIndex]
        val order = sequence.positions.indexOf(bracketPosition)
        val next = sequence.positions.getOrNull(order + 1) ?: return
        if (UnicodeBidiClass.of(scalars[next]) == BidiClass.NSM) types[next] = resolved
    }

    /**
     * Reports every strong type strictly inside the pair to [onStrong], numbers counting as R.
     * N0 reads them as existence tests per direction, which is why this reports all of them.
     * The scan is scoped to the pair's own isolating run sequence: the content of an isolate
     * inside the pair belongs to another sequence and takes no part in the pair's resolution.
     */
    private inline fun strongInside(
        scalars: List<Int>,
        types: Array<BidiClass?>,
        opening: Int,
        closing: Int,
        explicit: UnicodeBidiStructure.ExplicitStructure,
        onStrong: (Boolean) -> Unit,
    ) {
        val sequence = explicit.sequenceAt[opening]
        var position = opening + 1
        while (position < closing) {
            if (explicit.sequenceAt[position] == sequence) {
                when (types[position]) {
                    BidiClass.L -> onStrong(false)
                    BidiClass.R, BidiClass.EN, BidiClass.AN -> onStrong(true)
                    else -> Unit
                }
            }
            position += 1
        }
    }

    /**
     * N0.c's context: the first strong type before the opening bracket, within the sequence,
     * numbers counting as R, sos when the sequence offers none.
     */
    private fun strongBefore(
        scalars: List<Int>,
        types: Array<BidiClass?>,
        explicit: UnicodeBidiStructure.ExplicitStructure,
        sequenceIndex: Int,
        opening: Int,
    ): Boolean {
        val positions = explicit.sequences[sequenceIndex].positions
        val sosRtl = explicit.sequences[sequenceIndex].sosRtl
        val order = positions.indexOf(opening)
        var index = order - 1
        while (index >= 0) {
            when (val type = types[positions[index]]) {
                BidiClass.L -> return false
                BidiClass.R, BidiClass.EN, BidiClass.AN -> return true
                BidiClass.S, BidiClass.B -> Unit
                else -> if (type != null && !type.isNeutralOrIsolate()) return sosRtl
            }
            index -= 1
        }
        return sosRtl
    }

    /**
     * The first strong direction before or after a neutral run, or the boundary type. The walk
     * crosses the separators — X8 keeps them out of every rule, so they are as transparent here
     * as the characters X9 removes.
     */
    private fun strongDirection(
        scalars: List<Int>,
        types: Array<BidiClass?>,
        sequence: UnicodeBidiStructure.ExplicitStructure.Sequence,
        fromIndex: Int,
        backward: Boolean,
        sos: BidiClass,
    ): BidiClass? {
        val positions = sequence.positions
        var index = if (backward) fromIndex - 1 else fromIndex
        while (index in positions.indices) {
            when (val type = types[positions[index]]) {
                BidiClass.L -> return BidiClass.L
                BidiClass.R, BidiClass.EN, BidiClass.AN -> return BidiClass.R
                BidiClass.S, BidiClass.B -> Unit
                else -> if (type != null && !type.isNeutralOrIsolate()) return sos
            }
            index = if (backward) index - 1 else index + 1
        }
        return sos
    }

    // The types N1 and N2 resolve. A separator's own type is among them only because an NSM can
    // inherit it through W1 — the separators themselves are excluded from the scan by their
    // original class before this is consulted.
    private fun BidiClass?.isNeutralOrIsolate(): Boolean = when (this) {
        BidiClass.ON, BidiClass.WS, BidiClass.S, BidiClass.B,
        BidiClass.LRI, BidiClass.RLI, BidiClass.FSI, BidiClass.PDI,
        -> true
        else -> false
    }

    // ------------------------------------------------------------------------------------------------
    // I1, I2: the implicit levels
    // ------------------------------------------------------------------------------------------------

    /**
     * I1 and I2: at an even level a right-to-left character goes up one and a number up two; at an
     * odd level a left-to-right character or a number goes up one. The removed characters keep
     * their embedding level here and take their normative level in the repair.
     */
    private fun implicitLevels(
        scalars: List<Int>,
        explicit: UnicodeBidiStructure.ExplicitStructure,
        types: Array<BidiClass?>,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): IntArray {
        val levels = IntArray(scalars.size)
        scalars.forEachIndexed { index, _ ->
            observeCancellation(index, profile, cancellationToken)
            val level = explicit.levels[index]
            levels[index] = when (types[index]) {
                BidiClass.L -> if (level.rem(2) == 0) level else level + 1
                BidiClass.R -> if (level.rem(2) == 0) level + 1 else level
                BidiClass.EN, BidiClass.AN -> if (level.rem(2) == 0) level + 2 else level + 1
                else -> level
            }
        }
        return levels
    }
}
