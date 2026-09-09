@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.unicode

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.VersionInfo
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditorOperationContext
import org.graphiks.kalligraphie.api.EditorOperationProfile
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import org.graphiks.kalligraphie.api.LineBreakAnalysis
import org.graphiks.kalligraphie.api.LineBreakAnalysisOutcome
import org.graphiks.kalligraphie.api.LineBreakKind
import org.graphiks.kalligraphie.api.LineBreakOpportunity
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.UnicodeAnalysis
import org.graphiks.kalligraphie.api.UnicodeDataIdentity

/** Factory for the pinned JVM-reference UAX #14 line-break analyzer. */
public object JvmLineBreakAnalyzer {
    /** Creates an analyzer backed internally by ICU4J 77.1 and Unicode 16.0 data. */
    public fun create(): LineBreakAnalyzer = IcuLineBreakAnalyzer()

    /** Creates the additive bounded analyzer backed by the same pinned ICU4J data. */
    public fun createBounded(): BoundedLineBreakAnalyzer = IcuLineBreakAnalyzer()
}

@OptIn(KalligraphieInternalApi::class)
internal class IcuLineBreakAnalyzer : BoundedLineBreakAnalyzer {
    init {
        verifyPinnedLineBreakData()
    }

    override fun analyze(snapshot: TextSnapshot, unicodeAnalysis: UnicodeAnalysis): LineBreakAnalysis {
        val outcome = analyze(
            snapshot,
            unicodeAnalysis,
            EditorOperationProfile.unbounded,
            CancellationToken.none,
        )
        return when (outcome) {
            is LineBreakAnalysisOutcome.Success -> outcome.value
            is LineBreakAnalysisOutcome.LimitExceeded -> error("The unbounded line-break analyzer exceeded ${outcome.limit.kind}.")
            LineBreakAnalysisOutcome.Cancelled -> error("The non-cancellable line-break analyzer was cancelled.")
        }
    }

    override fun analyze(
        snapshot: TextSnapshot,
        unicodeAnalysis: UnicodeAnalysis,
        profile: EditorOperationProfile,
        cancellationToken: CancellationToken,
    ): LineBreakAnalysisOutcome = analyze(
        snapshot,
        unicodeAnalysis,
        EditorOperationContext.create(profile, cancellationToken),
    )

    override fun analyze(
        snapshot: TextSnapshot,
        unicodeAnalysis: UnicodeAnalysis,
        context: EditorOperationContext,
    ): LineBreakAnalysisOutcome {
        require(unicodeAnalysis.range == snapshot.range) {
            "Unicode analysis must cover the complete supplied snapshot."
        }
        require(unicodeAnalysis.unicodeData == LINE_BREAK_UNICODE_DATA) {
            "Unicode analysis must use the pinned ICU4J 77.1 Unicode 16.0 data."
        }
        context.sourceLimit(snapshot)?.let { return LineBreakAnalysisOutcome.LimitExceeded(it) }
        context.scalarLimit(snapshot)?.let { return LineBreakAnalysisOutcome.LimitExceeded(it) }
        if (context.isCancellationRequested()) return LineBreakAnalysisOutcome.Cancelled
        context.chargeLineBreakWork(snapshot.scalars.size.toLong())?.let {
            return LineBreakAnalysisOutcome.LimitExceeded(it)
        }

        val canonicalText = LineBreakUtf16Text(snapshot, context)
            ?: return LineBreakAnalysisOutcome.Cancelled
        val graphemeEnds = unicodeAnalysis.graphemeClusters
            .map { cluster -> cluster.endExclusive }
            .toSet()
        val opportunities = when (val result = lineBreakOpportunities(snapshot, canonicalText, graphemeEnds, context)) {
            is LineBreakWork.Success -> result.opportunities
            is LineBreakWork.LimitExceeded -> return LineBreakAnalysisOutcome.LimitExceeded(result.limit)
            LineBreakWork.Cancelled -> return LineBreakAnalysisOutcome.Cancelled
        }
        if (context.isCancellationRequested()) return LineBreakAnalysisOutcome.Cancelled
        return LineBreakAnalysisOutcome.Success(
            LineBreakAnalysis(
                range = unicodeAnalysis.range,
                unicodeData = LINE_BREAK_UNICODE_DATA,
                graphemeClusters = unicodeAnalysis.graphemeClusters,
                opportunities = opportunities,
            ),
        )
    }
}

private fun lineBreakOpportunities(
    snapshot: TextSnapshot,
    text: LineBreakUtf16Text,
    graphemeEnds: Set<TextIndex>,
    context: EditorOperationContext,
): LineBreakWork {
    if (snapshot.scalars.isEmpty()) return LineBreakWork.Success(emptyList())
    val iterator = BreakIterator.getLineInstance(ULocale.ROOT)
    if (context.isCancellationRequested()) return LineBreakWork.Cancelled
    iterator.setText(text.value)
    if (context.isCancellationRequested()) return LineBreakWork.Cancelled
    val opportunities = mutableListOf<LineBreakOpportunity>()
    if (context.isCancellationRequested()) return LineBreakWork.Cancelled
    iterator.first()
    if (context.isCancellationRequested()) return LineBreakWork.Cancelled
    context.chargeLineBreakWork(1L)?.let { return LineBreakWork.LimitExceeded(it) }
    if (context.isCancellationRequested()) return LineBreakWork.Cancelled
    var utf16Boundary = iterator.next()
    if (context.isCancellationRequested()) return LineBreakWork.Cancelled
    while (utf16Boundary != BreakIterator.DONE) {
        val scalarBoundary = text.scalarBoundary(utf16Boundary)
        val boundary = snapshot.textIndexAtScalarBoundary(scalarBoundary)
        if (boundary in graphemeEnds) {
            val kind = if (snapshot.requiresLineTerminationBefore(scalarBoundary)) {
                LineBreakKind.MANDATORY
            } else {
                LineBreakKind.ALLOWED
            }
            if (boundary != snapshot.range.endExclusive || kind == LineBreakKind.MANDATORY) {
                opportunities += LineBreakOpportunity(boundary, kind)
            }
        }
        context.chargeLineBreakWork(1L)?.let { return LineBreakWork.LimitExceeded(it) }
        if (context.isCancellationRequested()) return LineBreakWork.Cancelled
        utf16Boundary = iterator.next()
        if (context.isCancellationRequested()) return LineBreakWork.Cancelled
    }
    return LineBreakWork.Success(opportunities)
}

private fun TextSnapshot.requiresLineTerminationBefore(scalarBoundary: Int): Boolean {
    if (scalarBoundary == 0) return false
    return when (scalars[scalarBoundary - 1]) {
        CARRIAGE_RETURN,
        LINE_FEED,
        VERTICAL_TAB,
        FORM_FEED,
        NEXT_LINE,
        LINE_SEPARATOR,
        PARAGRAPH_SEPARATOR,
        -> true

        else -> false
    }
}

private class LineBreakUtf16Text private constructor(
    val value: String,
    private val scalarBoundaryToUtf16: IntArray,
) {
    fun scalarBoundary(utf16Boundary: Int): Int {
        val scalarBoundary = scalarBoundaryToUtf16.binarySearch(utf16Boundary)
        check(scalarBoundary >= 0) { "ICU returned a boundary inside a Unicode scalar." }
        return scalarBoundary
    }

    companion object {
        operator fun invoke(snapshot: TextSnapshot, context: EditorOperationContext): LineBreakUtf16Text? {
            val boundaries = IntArray(snapshot.scalars.size + 1)
            val builder = StringBuilder()
            snapshot.scalars.forEachIndexed { scalarIndex, scalar ->
                if (scalarIndex % context.profile.cancellationCheckInterval == 0 && context.isCancellationRequested()) {
                    return null
                }
                boundaries[scalarIndex] = builder.length
                builder.appendCodePoint(scalar)
            }
            boundaries[snapshot.scalars.size] = builder.length
            if (context.isCancellationRequested()) return null
            return LineBreakUtf16Text(builder.toString(), boundaries)
        }
    }
}

private sealed interface LineBreakWork {
    data class Success(val opportunities: List<LineBreakOpportunity>) : LineBreakWork
    data class LimitExceeded(val limit: org.graphiks.kalligraphie.api.EditorOperationLimitExceeded) : LineBreakWork
    data object Cancelled : LineBreakWork
}

private fun verifyPinnedLineBreakData() {
    check(UCharacter.getUnicodeVersion() == VersionInfo.UNICODE_16_0) {
        "ICU4J must provide Unicode 16.0 data."
    }
    check(VersionInfo.ICU_VERSION.major == 77 && VersionInfo.ICU_VERSION.minor == 1) {
        "ICU4J 77.1 is required."
    }
}

private val LINE_BREAK_UNICODE_DATA: UnicodeDataIdentity = UnicodeDataIdentity(
    unicodeVersion = "${UCharacter.getUnicodeVersion().major}.${UCharacter.getUnicodeVersion().minor}",
    implementation = "ICU4J",
    implementationVersion = "${VersionInfo.ICU_VERSION.major}.${VersionInfo.ICU_VERSION.minor}",
)

private const val CARRIAGE_RETURN: Int = 0x000D
private const val LINE_FEED: Int = 0x000A
private const val VERTICAL_TAB: Int = 0x000B
private const val FORM_FEED: Int = 0x000C
private const val NEXT_LINE: Int = 0x0085
private const val LINE_SEPARATOR: Int = 0x2028
private const val PARAGRAPH_SEPARATOR: Int = 0x2029
