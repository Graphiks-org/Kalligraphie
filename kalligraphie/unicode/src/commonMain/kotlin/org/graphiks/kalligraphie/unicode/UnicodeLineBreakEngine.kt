package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.UnicodeAnalysisProfile

/**
 * The portable UAX #14: one line-break decision between every pair of adjacent scalars, resolved
 * from this module's generated tables instead of the JVM's `BreakIterator`.
 *
 * The rules are the ones UAX #14 publishes for Unicode 16.0, evaluated in their published order so
 * the first match decides. LB1 resolves the indeterminate classes — `AI`, `SG`, `XX` to `AL`; `SA`
 * to `CM` for a combining mark and `AL` otherwise; `CJ` to `NS`. LB9 and LB10 fold a `CM` or `ZWJ`
 * into its preceding base unless that base is one of the excluded classes, with an orphan becoming
 * `AL`; the folding groups matter twice over, because a boundary inside one group can never break
 * and the rules that need the group's base — the quotation contexts of LB15a and LB15b, the
 * Brahmic syllables of LB28a — re-read it. LB2 through LB31 decide each boundary. The East Asian
 * exemptions of LB19a, LB21a, LB25, and LB30, and the potential-emoji base of LB30b, read the
 * tables shipped for exactly that purpose; `$EastAsian` is the East_Asian_Width F, W, and H set.
 *
 * Two shape notes from the rule ordering. A rule written `B × A` before LB18 is a prohibited
 * break: it holds even across spaces, which is why LB14 through LB17 read the last non-space
 * class, while LB18 (`SP ÷`) has already allowed a break by the time LB19 and later rules run, so
 * those only ever see adjacent pairs. And the numeric run of LB25 — `NU (SY | IS)*` — is tracked
 * as its own state, because several of its pairs look at more than one preceding character.
 */
internal object UnicodeLineBreakEngine {

    /** One boundary's verdict: `NO_BREAK` (×), `BREAK` (÷), or the mandatory breaks of LB4 and LB5. */
    internal enum class Decision {
        NO_BREAK,
        BREAK,
        MANDATORY,
    }

    /**
     * Returns one [Decision] per boundary: index `0` is the start of text (always `NO_BREAK`),
     * index `i > 0` is the boundary between scalars `i - 1` and `i`, and index `size` is the end
     * of text, which LB3 breaks by default and which the analyzer filters back out unless the
     * source text mandates a termination there.
     */
    internal fun decisions(
        scalars: List<Int>,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ): Array<Decision> {
        val size = scalars.size
        val result = Array(size + 1) { Decision.NO_BREAK }
        if (size == 0) return result

        val resolved = Array(size) { index ->
            observeCancellation(index, profile, cancellationToken)
            resolveClass(scalars[index])
        }

        // LB9 and LB10: a `CM` or `ZWJ` attaches to the preceding base unless that base is one of
        // the excluded classes; an orphan becomes `AL`. Every scalar belongs to a group whose
        // first scalar is its base, so a boundary inside a group can never break and the rules
        // that need the base can find it.
        val effective = arrayOfNulls<LineBreakClass>(size)
        val groupStart = IntArray(size)
        var openGroupStart = -1
        for (index in 0 until size) {
            val resolvedClass = resolved[index]
            val foldsIntoPrevious = (resolvedClass == LineBreakClass.CM ||
                resolvedClass == LineBreakClass.ZWJ) && openGroupStart >= 0
            if (foldsIntoPrevious) {
                groupStart[index] = openGroupStart
                effective[index] = effective[openGroupStart]
                continue
            }
            groupStart[index] = index
            effective[index] = if (resolvedClass == LineBreakClass.CM ||
                resolvedClass == LineBreakClass.ZWJ
            ) {
                LineBreakClass.AL
            } else {
                resolvedClass
            }
            openGroupStart = if (isFoldingBase(effective[index]!!)) index else -1
        }

        // The last non-space state the prohibited rules read: its folded class, whether its real
        // scalar is the zero width joiner LB8a prohibits breaking after, and where it sits.
        var lastNonSpaceClass: LineBreakClass? = null
        var lastNonSpaceScalarIsZwj = false
        var lastNonSpaceScalarIndex = -1
        var regionalIndicatorGroups = 0
        var numericRunState = 0

        for (position in 1..size) {
            observeCancellation(position, profile, cancellationToken)
            val previousIndex = position - 1
            val previous = effective[previousIndex]!!
            val previousScalar = scalars[previousIndex]
            val previousResolved = resolved[previousIndex]
            val current = if (position == size) null else effective[position]
            val currentResolved = if (position == size) null else resolved[position]

            // The count of Regional Indicator *groups* ending at the previous scalar: a mark
            // folded into an indicator does not count twice.
            regionalIndicatorGroups = when {
                previous != LineBreakClass.RI -> 0
                previousIndex >= 1 && groupStart[previousIndex] == groupStart[previousIndex - 1] ->
                    regionalIndicatorGroups
                else -> regionalIndicatorGroups + 1
            }

            // LB25's numeric run `NU (SY | IS)*`, closed by one `CL` or `CP` inside it: the
            // state describes the run ending at the previous scalar
            numericRunState = when {
                previous == LineBreakClass.NU -> 1
                numericRunState == 1 && (previous == LineBreakClass.SY ||
                    previous == LineBreakClass.IS) -> 1
                numericRunState == 1 && (previous == LineBreakClass.CL ||
                    previous == LineBreakClass.CP) -> 2
                else -> 0
            }

            // The last non-space state is folded in before the verdict, so it covers every
            // scalar up to and including the previous one
            if (previous != LineBreakClass.SP) {
                lastNonSpaceClass = previous
                // LB8a reaches across spaces only for a ZWJ folded into a base: an orphan one
                // (LB10) breaks like anything else once a space follows it
                lastNonSpaceScalarIsZwj = previousScalar == ZERO_WIDTH_JOINER &&
                    groupStart[previousIndex] != previousIndex
                lastNonSpaceScalarIndex = previousIndex
            }

            result[position] = decide(
                position,
                scalars,
                resolved,
                effective,
                groupStart,
                previous,
                previousResolved,
                previousScalar,
                current,
                currentResolved,
                lastNonSpaceClass,
                lastNonSpaceScalarIsZwj,
                lastNonSpaceScalarIndex,
                regionalIndicatorGroups,
                numericRunState,
            )
        }
        return result
    }

    private fun decide(
        position: Int,
        scalars: List<Int>,
        resolved: Array<LineBreakClass>,
        effective: Array<LineBreakClass?>,
        groupStart: IntArray,
        previous: LineBreakClass,
        previousResolved: LineBreakClass,
        previousScalar: Int,
        current: LineBreakClass?,
        currentResolved: LineBreakClass?,
        lastNonSpaceClass: LineBreakClass?,
        lastNonSpaceScalarIsZwj: Boolean,
        lastNonSpaceScalarIndex: Int,
        regionalIndicatorGroups: Int,
        numericRunState: Int,
    ): Decision {
        val endOfText = current == null
        val previousGroupBase = groupStart[position - 1]
        val previousGroupBaseClass = effective[previousGroupBase]!!

        // A boundary inside one LB9 group — between a base and its folded marks — never breaks.
        if (!endOfText && groupStart[position] != position &&
            groupStart[position - 1] == groupStart[position]
        ) {
            return Decision.NO_BREAK
        }

        // LB3: ÷ eot — the end of text always breaks, and the mandatory terminations of LB4 and
        // LB5 are still mandatory there
        if (endOfText) {
            return when (previous) {
                LineBreakClass.BK, LineBreakClass.CR, LineBreakClass.LF, LineBreakClass.NL ->
                    Decision.MANDATORY
                else -> Decision.BREAK
            }
        }

        // LB4: BK !; LB5: CR × LF, CR !, LF !, NL !
        if (previous == LineBreakClass.BK) return Decision.MANDATORY
        if (previous == LineBreakClass.CR) {
            return if (current == LineBreakClass.LF) Decision.NO_BREAK else Decision.MANDATORY
        }
        if (previous == LineBreakClass.LF || previous == LineBreakClass.NL) return Decision.MANDATORY

        // LB6: × (BK | CR | LF | NL)
        if (current == LineBreakClass.BK || current == LineBreakClass.CR ||
            current == LineBreakClass.LF || current == LineBreakClass.NL
        ) {
            return Decision.NO_BREAK
        }

        // LB7: × SP; × ZW
        if (current == LineBreakClass.SP || current == LineBreakClass.ZW) return Decision.NO_BREAK

        // LB8: ZW SP* ÷ — the break lands right after the zero width space or after the spaces
        // that follow it; once any other character sits between, LB8 has already spent itself
        if (previous == LineBreakClass.ZW || (previous == LineBreakClass.SP &&
                lastNonSpaceClass == LineBreakClass.ZW)
        ) {
            return Decision.BREAK
        }

        // LB8a: ZWJ ×, adjacent for every zero width joiner, across spaces for a folded one
        if (previousResolved == LineBreakClass.ZWJ) return Decision.NO_BREAK
        if (previous == LineBreakClass.SP && lastNonSpaceScalarIsZwj) return Decision.NO_BREAK

        // LB11: × WJ; WJ ×
        if (current == LineBreakClass.WJ || previous == LineBreakClass.WJ) return Decision.NO_BREAK

        // LB12: GL ×
        if (previous == LineBreakClass.GL) return Decision.NO_BREAK

        // LB12a: [^SP BA HY] × GL
        if (current == LineBreakClass.GL && previous != LineBreakClass.SP &&
            previous != LineBreakClass.BA && previous != LineBreakClass.HY
        ) {
            return Decision.NO_BREAK
        }

        // LB13: × CL; × CP; × EX; × SY — the `IS` half of the old rule lives in LB15c and LB15d
        if (current == LineBreakClass.CL || current == LineBreakClass.CP ||
            current == LineBreakClass.EX || current == LineBreakClass.SY
        ) {
            return Decision.NO_BREAK
        }

        // LB14: OP SP* ×
        if (lastNonSpaceClass == LineBreakClass.OP || previous == LineBreakClass.OP) {
            return Decision.NO_BREAK
        }

        // LB15a: (OP | [\p{Pi}&QU]) SP* × [\p{Pi}&QU] — the break before an initial quotation
        // whose context, across spaces, is an opening or an initial quotation, is prohibited
        fun isInitialQuotationBase(scalarIndex: Int): Boolean {
            val base = groupStart[scalarIndex]
            return effective[base] == LineBreakClass.QU &&
                UnicodeQuotation.isInitialQuotation(scalars[base])
        }
        if (current == LineBreakClass.QU && position < scalars.size &&
            UnicodeQuotation.isInitialQuotation(scalars[position])
        ) {
            val contextClass = if (previous == LineBreakClass.SP) lastNonSpaceClass else previous
            val contextIsOpening = contextClass == LineBreakClass.OP ||
                (contextClass == LineBreakClass.QU && lastNonSpaceScalarIndex >= 0 &&
                    previous == LineBreakClass.SP &&
                    isInitialQuotationBase(lastNonSpaceScalarIndex)) ||
                (contextClass == LineBreakClass.QU && previous != LineBreakClass.SP &&
                    isInitialQuotationBase(position - 1))
            if (contextIsOpening) return Decision.NO_BREAK
        }

        // LB15a continued: an initial quotation that an opening, another quotation, or the start
        // of the line introduced keeps everything that follows it, across spaces too. A plain
        // quotation mark or one that plain text introduced breaks after its trailing spaces (LB18)
        fun piTrailsStickyContext(piIndex: Int): Boolean {
            val base = groupStart[piIndex]
            if (effective[base] != LineBreakClass.QU ||
                !UnicodeQuotation.isInitialQuotation(scalars[base])
            ) {
                return false
            }
            val contextIndex = base - 1
            if (contextIndex < 0) return true
            return effective[contextIndex] in STICKY_TRAILING_CONTEXTS
        }
        if ((previous == LineBreakClass.QU && piTrailsStickyContext(position - 1)) ||
            (previous == LineBreakClass.SP && lastNonSpaceScalarIndex >= 0 &&
                lastNonSpaceClass == LineBreakClass.QU &&
                piTrailsStickyContext(lastNonSpaceScalarIndex))
        ) {
            return Decision.NO_BREAK
        }

        // LB15b: × [\p{Pf}&QU] (SP | GL | WJ | CL | QU | CP | EX | IS | SY | BK | CR | LF | NL | ZW | eot)
        if (current == LineBreakClass.QU && position < scalars.size &&
            UnicodeQuotation.isFinalQuotation(scalars[position]) &&
            (position + 1 == scalars.size || effective[position + 1] in FINAL_QUOTATION_STOPPERS)
        ) {
            return Decision.NO_BREAK
        }

        // LB15c: SP ÷ IS NU — break before a decimal mark that follows a space
        if (previous == LineBreakClass.SP && current == LineBreakClass.IS &&
            position + 1 < scalars.size && effective[position + 1] == LineBreakClass.NU
        ) {
            return Decision.BREAK
        }

        // LB15d: × IS, even after spaces
        if (current == LineBreakClass.IS) return Decision.NO_BREAK

        // LB16: (CL | CP) SP* × NS
        if (current == LineBreakClass.NS && (lastNonSpaceClass == LineBreakClass.CL ||
                lastNonSpaceClass == LineBreakClass.CP || previous == LineBreakClass.CL ||
                previous == LineBreakClass.CP)
        ) {
            return Decision.NO_BREAK
        }

        // LB17: B2 SP* × B2
        if (current == LineBreakClass.B2 && (lastNonSpaceClass == LineBreakClass.B2 ||
                previous == LineBreakClass.B2)
        ) {
            return Decision.NO_BREAK
        }

        // LB18: SP ÷
        if (previous == LineBreakClass.SP) return Decision.BREAK

        // LB19: × (QU that is not Pi); (QU that is not Pf) ×
        if (current == LineBreakClass.QU && position < scalars.size &&
            !UnicodeQuotation.isInitialQuotation(scalars[position])
        ) {
            return Decision.NO_BREAK
        }
        if (previous == LineBreakClass.QU &&
            !UnicodeQuotation.isFinalQuotation(scalars[previousGroupBase])
        ) {
            return Decision.NO_BREAK
        }

        // LB19a: unless surrounded by East Asian characters — East_Asian_Width F, W or H —
        // do not break either side of any unresolved quotation mark
        if (current == LineBreakClass.QU) {
            if (!UnicodeEastAsianWidth.isFullwidthWideOrHalfwidth(scalars[position - 1])) {
                return Decision.NO_BREAK
            }
            if (position + 1 == scalars.size ||
                !UnicodeEastAsianWidth.isFullwidthWideOrHalfwidth(scalars[position + 1])
            ) {
                return Decision.NO_BREAK
            }
        }
        if (previous == LineBreakClass.QU) {
            if (current != null && !UnicodeEastAsianWidth.isFullwidthWideOrHalfwidth(scalars[position])) {
                return Decision.NO_BREAK
            }
            val beforeQuotation = previousGroupBase - 1
            if (beforeQuotation < 0 ||
                !UnicodeEastAsianWidth.isFullwidthWideOrHalfwidth(scalars[beforeQuotation])
            ) {
                return Decision.NO_BREAK
            }
        }

        // LB20: ÷ CB; CB ÷
        if (current == LineBreakClass.CB || previous == LineBreakClass.CB) return Decision.BREAK

        // LB20a: (sot | BK | CR | LF | NL | SP | ZW | CB | GL) (HY | U+2010) × AL — a
        // word-initial hyphen stays with the letters it joins
        if (current == LineBreakClass.AL && (previousGroupBaseClass == LineBreakClass.HY ||
                scalars[previousGroupBase] == WORD_INITIAL_HYPHEN)
        ) {
            val contextBeforeHyphen = previousGroupBase == 0 || effective[previousGroupBase - 1] in
                WORD_INITIAL_HYPHEN_CONTEXTS
            if (contextBeforeHyphen) return Decision.NO_BREAK
        }

        // LB21: × BA; × HY; × NS; BB ×
        if (current == LineBreakClass.BA || current == LineBreakClass.HY ||
            current == LineBreakClass.NS
        ) {
            return Decision.NO_BREAK
        }
        if (previous == LineBreakClass.BB) return Decision.NO_BREAK

        // LB21a: HL (HY | [BA - $EastAsian]) × [^HL] — the Hebrew hyphen breaks only before
        // another Hebrew letter
        if (position >= 2 && effective[position - 2] == LineBreakClass.HL &&
            (previous == LineBreakClass.HY || (previous == LineBreakClass.BA &&
                !UnicodeEastAsianWidth.isFullwidthWideOrHalfwidth(scalars[previousGroupBase]))) &&
            current != LineBreakClass.HL
        ) {
            return Decision.NO_BREAK
        }

        // LB21b: SY × HL
        if (previous == LineBreakClass.SY && current == LineBreakClass.HL) return Decision.NO_BREAK

        // LB22: × IN
        if (current == LineBreakClass.IN) return Decision.NO_BREAK

        // LB23: (AL | HL) × NU; NU × (AL | HL)
        if ((previous == LineBreakClass.AL || previous == LineBreakClass.HL) &&
            current == LineBreakClass.NU
        ) {
            return Decision.NO_BREAK
        }
        if (previous == LineBreakClass.NU && (current == LineBreakClass.AL || current == LineBreakClass.HL)) {
            return Decision.NO_BREAK
        }

        // LB23a: PR × (ID | EB | EM); (ID | EB | EM) × PO
        if (previous == LineBreakClass.PR && (current == LineBreakClass.ID ||
                current == LineBreakClass.EB || current == LineBreakClass.EM)
        ) {
            return Decision.NO_BREAK
        }
        if ((previous == LineBreakClass.ID || previous == LineBreakClass.EB ||
                previous == LineBreakClass.EM) && current == LineBreakClass.PO
        ) {
            return Decision.NO_BREAK
        }

        // LB24: (PR | PO) × (AL | HL); (AL | HL) × (PR | PO)
        if ((previous == LineBreakClass.PR || previous == LineBreakClass.PO) &&
            (current == LineBreakClass.AL || current == LineBreakClass.HL)
        ) {
            return Decision.NO_BREAK
        }
        if ((previous == LineBreakClass.AL || previous == LineBreakClass.HL) &&
            (current == LineBreakClass.PR || current == LineBreakClass.PO)
        ) {
            return Decision.NO_BREAK
        }

        val previousInOpenRun = numericRunState == 1 && (previous == LineBreakClass.NU ||
            previous == LineBreakClass.SY || previous == LineBreakClass.IS)
        val previousClosesRun = numericRunState == 2 && (previous == LineBreakClass.CL ||
            previous == LineBreakClass.CP)
        if ((previousClosesRun && (current == LineBreakClass.PO || current == LineBreakClass.PR)) ||
            (previousInOpenRun && (current == LineBreakClass.PO || current == LineBreakClass.PR ||
                current == LineBreakClass.NU)) ||
            ((previous == LineBreakClass.PO || previous == LineBreakClass.PR) &&
                current == LineBreakClass.NU) ||
            (previous == LineBreakClass.HY && current == LineBreakClass.NU) ||
            (previous == LineBreakClass.IS && current == LineBreakClass.NU)
        ) {
            return Decision.NO_BREAK
        }

        // LB25 continued: a currency sign keeps an opening that introduces the number it
        // prefixes — `$(12` — through the optional decimal mark of that number
        if ((previous == LineBreakClass.PO || previous == LineBreakClass.PR) &&
            current == LineBreakClass.OP && position + 1 < scalars.size
        ) {
            val afterOpening = effective[position + 1]
            val numberFollowsOpening = afterOpening == LineBreakClass.NU ||
                (afterOpening == LineBreakClass.IS && position + 2 < scalars.size &&
                    effective[position + 2] == LineBreakClass.NU)
            if (numberFollowsOpening) return Decision.NO_BREAK
        }

        // LB26: JL × (JL | JV | H2 | H3); (JV | H2) × (JV | JT); (JT | H3) × JT
        if (previous == LineBreakClass.JL && (current == LineBreakClass.JL ||
                current == LineBreakClass.JV || current == LineBreakClass.H2 ||
                current == LineBreakClass.H3)
        ) {
            return Decision.NO_BREAK
        }
        if ((previous == LineBreakClass.JV || previous == LineBreakClass.H2) &&
            (current == LineBreakClass.JV || current == LineBreakClass.JT)
        ) {
            return Decision.NO_BREAK
        }
        if ((previous == LineBreakClass.JT || previous == LineBreakClass.H3) &&
            current == LineBreakClass.JT
        ) {
            return Decision.NO_BREAK
        }

        // LB27: (JL | JV | JT | H2 | H3) × PO; PR × (JL | JV | JT | H2 | H3)
        if ((previous == LineBreakClass.JL || previous == LineBreakClass.JV ||
                previous == LineBreakClass.JT || previous == LineBreakClass.H2 ||
                previous == LineBreakClass.H3) && current == LineBreakClass.PO
        ) {
            return Decision.NO_BREAK
        }
        if (previous == LineBreakClass.PR && (current == LineBreakClass.JL ||
                current == LineBreakClass.JV || current == LineBreakClass.JT ||
                current == LineBreakClass.H2 || current == LineBreakClass.H3)
        ) {
            return Decision.NO_BREAK
        }

        // LB28: (AL | HL) × (AL | HL)
        if ((previous == LineBreakClass.AL || previous == LineBreakClass.HL) &&
            (current == LineBreakClass.AL || current == LineBreakClass.HL)
        ) {
            return Decision.NO_BREAK
        }

        fun precedingGroupClass(index: Int): LineBreakClass? {
            val groupBase = groupStart[index]
            if (groupBase == 0) return null
            return effective[groupStart[groupBase - 1]]
        }

        // LB28a: the Brahmic orthographic syllables, read on each group's folded base class —
        // a combining mark folded into an aksara counts as that aksara — while the dotted circle
        // of the rule is U+25CC specifically, including an unattached one
        val previousGroupBaseIsSyllabic = previousGroupBaseClass == LineBreakClass.AK ||
            previousGroupBaseClass == LineBreakClass.AS || scalars[previousGroupBase] == DOTTED_CIRCLE
        val currentIsDottedCircle = scalars[position] == DOTTED_CIRCLE
        if (previousGroupBaseClass == LineBreakClass.AP && (current == LineBreakClass.AK ||
                current == LineBreakClass.AS || currentIsDottedCircle)
        ) {
            return Decision.NO_BREAK
        }
        if (previousGroupBaseIsSyllabic && (current == LineBreakClass.VF || current == LineBreakClass.VI)) {
            return Decision.NO_BREAK
        }
        if (position >= 2) {
            val beforePreviousGroupBaseClass = effective[groupStart[position - 2]]!!
            val beforePreviousIsSyllabic = beforePreviousGroupBaseClass == LineBreakClass.AK ||
                beforePreviousGroupBaseClass == LineBreakClass.AS ||
                scalars[position - 2] == DOTTED_CIRCLE
            if (previous == LineBreakClass.VI && beforePreviousIsSyllabic &&
                (current == LineBreakClass.AK || current == LineBreakClass.AS || currentIsDottedCircle)
            ) {
                return Decision.NO_BREAK
            }
            // The same syllable spelled with a folded mark: the aksara sits in the group before
            // the virama's group
            val beforeVirama = precedingGroupClass(position - 1)
            if (previous == LineBreakClass.VI && beforeVirama != null &&
                (beforeVirama == LineBreakClass.AK || beforeVirama == LineBreakClass.AS ||
                    scalars[groupStart[groupStart[position - 1] - 1]] == DOTTED_CIRCLE) &&
                (current == LineBreakClass.AK || current == LineBreakClass.AS || currentIsDottedCircle)
            ) {
                return Decision.NO_BREAK
            }
            if (beforePreviousIsSyllabic && previousGroupBaseIsSyllabic &&
                position + 1 < scalars.size && effective[position + 1] == LineBreakClass.VF
            ) {
                return Decision.NO_BREAK
            }
            val beforeThePair = precedingGroupClass(position - 1)
            if (beforeThePair != null &&
                (beforeThePair == LineBreakClass.AK || beforeThePair == LineBreakClass.AS ||
                    scalars[groupStart[groupStart[position - 1] - 1]] == DOTTED_CIRCLE) &&
                previousGroupBaseIsSyllabic && current == LineBreakClass.VF
            ) {
                return Decision.NO_BREAK
            }
        }

        // LB29: IS × (AL | HL)
        if (previous == LineBreakClass.IS && (current == LineBreakClass.AL || current == LineBreakClass.HL)) {
            return Decision.NO_BREAK
        }

        // LB30: (AL | HL | NU) × OP; CP × (AL | HL | NU), exempting East Asian width F, W, H
        if ((previous == LineBreakClass.AL || previous == LineBreakClass.HL ||
                previous == LineBreakClass.NU) && current == LineBreakClass.OP &&
            !UnicodeEastAsianWidth.isFullwidthWideOrHalfwidth(scalars[position])
        ) {
            return Decision.NO_BREAK
        }
        if (previous == LineBreakClass.CP && (current == LineBreakClass.AL ||
                current == LineBreakClass.HL || current == LineBreakClass.NU) &&
            !UnicodeEastAsianWidth.isFullwidthWideOrHalfwidth(scalars[previousGroupBase])
        ) {
            return Decision.NO_BREAK
        }

        // LB30a: sot (RI RI)* RI × RI; [^RI] (RI RI)* RI × RI — no break inside a pair, which
        // is when an odd number of indicator groups precedes the boundary
        if (previous == LineBreakClass.RI && current == LineBreakClass.RI &&
            regionalIndicatorGroups.rem(2) == 1
        ) {
            return Decision.NO_BREAK
        }

        // LB30b: EB × EM; [\p{Extended_Pictographic}&\p{Cn}] × EM
        if (current == LineBreakClass.EM &&
            (previous == LineBreakClass.EB ||
                UnicodePotentialEmoji.isPotentialEmojiBase(scalars[previousGroupBase]))
        ) {
            return Decision.NO_BREAK
        }

        // LB31: ÷ ALL
        return Decision.BREAK
    }

    /** LB1: resolve `AI`, `SG`, `XX`, `SA` and `CJ` into the classes the rest of the rules read. */
    private fun resolveClass(scalar: Int): LineBreakClass = when (val raw = UnicodeLineBreak.of(scalar)) {
        LineBreakClass.AI, LineBreakClass.SG, LineBreakClass.XX -> LineBreakClass.AL
        LineBreakClass.SA -> if (UnicodeCombiningMark.isCombiningMark(scalar)) LineBreakClass.CM else LineBreakClass.AL
        LineBreakClass.CJ -> LineBreakClass.NS
        else -> raw
    }

    /** The classes LB9 folds a `CM` or `ZWJ` into; the others end the fold and close the group. */
    private fun isFoldingBase(effectiveClass: LineBreakClass): Boolean = when (effectiveClass) {
        LineBreakClass.BK, LineBreakClass.CR, LineBreakClass.LF, LineBreakClass.NL,
        LineBreakClass.SP, LineBreakClass.ZW,
        -> false
        else -> true
    }

    private val FINAL_QUOTATION_STOPPERS: Set<LineBreakClass> = setOf(
        LineBreakClass.SP, LineBreakClass.GL, LineBreakClass.WJ, LineBreakClass.CL,
        LineBreakClass.QU, LineBreakClass.CP, LineBreakClass.EX, LineBreakClass.IS,
        LineBreakClass.SY, LineBreakClass.BK, LineBreakClass.CR, LineBreakClass.LF,
        LineBreakClass.NL, LineBreakClass.ZW,
    )

    private val WORD_INITIAL_HYPHEN_CONTEXTS: Set<LineBreakClass> = setOf(
        LineBreakClass.BK, LineBreakClass.CR, LineBreakClass.LF, LineBreakClass.NL,
        LineBreakClass.SP, LineBreakClass.ZW, LineBreakClass.CB, LineBreakClass.GL,
    )

    private val STICKY_TRAILING_CONTEXTS: Set<LineBreakClass> = setOf(
        LineBreakClass.BK, LineBreakClass.CR, LineBreakClass.LF, LineBreakClass.NL,
        LineBreakClass.OP, LineBreakClass.QU, LineBreakClass.GL, LineBreakClass.SP,
        LineBreakClass.ZW,
    )

    private const val ZERO_WIDTH_JOINER: Int = 0x200D
    private const val DOTTED_CIRCLE: Int = 0x25CC
    private const val WORD_INITIAL_HYPHEN: Int = 0x2010

    private fun observeCancellation(
        scalarIndex: Int,
        profile: UnicodeAnalysisProfile,
        cancellationToken: CancellationToken,
    ) {
        if (scalarIndex % profile.cancellationCheckInterval == 0) observeCancellation(cancellationToken)
    }

    private fun observeCancellation(cancellationToken: CancellationToken) {
        if (cancellationToken.isCancellationRequested()) throw UnicodeAnalysisCancelled
    }
}
