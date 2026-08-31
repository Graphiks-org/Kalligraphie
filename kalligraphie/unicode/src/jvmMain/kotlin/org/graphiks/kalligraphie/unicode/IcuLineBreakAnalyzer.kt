package org.graphiks.kalligraphie.unicode

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.VersionInfo
import org.graphiks.kalligraphie.api.LineBreakAnalysis
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
}

internal class IcuLineBreakAnalyzer : LineBreakAnalyzer {
    init {
        verifyPinnedLineBreakData()
    }

    override fun analyze(snapshot: TextSnapshot, unicodeAnalysis: UnicodeAnalysis): LineBreakAnalysis {
        require(unicodeAnalysis.range == snapshot.range) {
            "Unicode analysis must cover the complete supplied snapshot."
        }
        require(unicodeAnalysis.unicodeData == LINE_BREAK_UNICODE_DATA) {
            "Unicode analysis must use the pinned ICU4J 77.1 Unicode 16.0 data."
        }

        val canonicalText = LineBreakUtf16Text(snapshot)
        val graphemeEnds = unicodeAnalysis.graphemeClusters
            .map { cluster -> cluster.endExclusive }
            .toSet()
        val opportunities = lineBreakOpportunities(snapshot, canonicalText, graphemeEnds)
        return LineBreakAnalysis(
            range = unicodeAnalysis.range,
            unicodeData = LINE_BREAK_UNICODE_DATA,
            graphemeClusters = unicodeAnalysis.graphemeClusters,
            opportunities = opportunities,
        )
    }
}

private fun lineBreakOpportunities(
    snapshot: TextSnapshot,
    text: LineBreakUtf16Text,
    graphemeEnds: Set<TextIndex>,
): List<LineBreakOpportunity> {
    if (snapshot.scalars.isEmpty()) return emptyList()
    val iterator = BreakIterator.getLineInstance(ULocale.ROOT)
    iterator.setText(text.value)
    val opportunities = mutableListOf<LineBreakOpportunity>()
    iterator.first()
    var utf16Boundary = iterator.next()
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
        utf16Boundary = iterator.next()
    }
    return opportunities
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

private class LineBreakUtf16Text(snapshot: TextSnapshot) {
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

    fun scalarBoundary(utf16Boundary: Int): Int {
        val scalarBoundary = scalarBoundaryToUtf16.binarySearch(utf16Boundary)
        check(scalarBoundary >= 0) { "ICU returned a boundary inside a Unicode scalar." }
        return scalarBoundary
    }
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
