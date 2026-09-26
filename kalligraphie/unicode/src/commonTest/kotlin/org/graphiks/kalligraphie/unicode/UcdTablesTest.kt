package org.graphiks.kalligraphie.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spot checks of the generated Unicode tables against the values the Unicode Character Database
 * publishes.
 *
 * These are not the conformance authority — the segmenter and the analyzer are checked against the
 * committed Unicode conformance corpora — but every table is pinned here on the three shapes a
 * generator can get wrong: an explicit range, a scalar the source does not list and the `@missing`
 * defaults decide, and a scalar the table omits entirely. A table that silently lost its ranges,
 * its defaults or its value mapping fails here long before a conformance run.
 *
 * The expectations are read off the UCD, not off the tables: `DerivedBidiClass.txt` lists `L` for
 * U+0041 and its `@missing` lines answer `R` for the Hebrew block and `L` everywhere else, and the
 * scalars that take a default are the ones no source file mentions.
 */
class UcdTablesTest {
    @Test
    fun bidiClassMatchesTheDatabase() {
        assertEquals(BidiClass.L, UnicodeBidiClass.of(0x0041))
        assertEquals(BidiClass.R, UnicodeBidiClass.of(0x05D0))
        assertEquals(BidiClass.AL, UnicodeBidiClass.of(0x0627))
        assertEquals(BidiClass.EN, UnicodeBidiClass.of(0x0030))
        assertEquals(BidiClass.AN, UnicodeBidiClass.of(0x0660))
        assertEquals(BidiClass.B, UnicodeBidiClass.of(0x000A))
        assertEquals(BidiClass.WS, UnicodeBidiClass.of(0x0020))
        assertEquals(BidiClass.NSM, UnicodeBidiClass.of(0x0300))
        // Unassigned scalars: the Hebrew block's @missing override, then the general @missing default.
        assertEquals(BidiClass.R, UnicodeBidiClass.of(0x0590))
        assertEquals(BidiClass.L, UnicodeBidiClass.of(0x0378))
    }

    @Test
    fun graphemeClusterBreakMatchesTheDatabase() {
        assertEquals(GraphemeClusterBreak.CR, UnicodeGraphemeBreak.of(0x000D))
        assertEquals(GraphemeClusterBreak.LF, UnicodeGraphemeBreak.of(0x000A))
        assertEquals(GraphemeClusterBreak.EXTEND, UnicodeGraphemeBreak.of(0x0300))
        assertEquals(GraphemeClusterBreak.ZWJ, UnicodeGraphemeBreak.of(0x200D))
        assertEquals(GraphemeClusterBreak.REGIONAL_INDICATOR, UnicodeGraphemeBreak.of(0x1F1E6))
        assertEquals(GraphemeClusterBreak.L, UnicodeGraphemeBreak.of(0x1100))
        assertEquals(GraphemeClusterBreak.V, UnicodeGraphemeBreak.of(0x1160))
        assertEquals(GraphemeClusterBreak.T, UnicodeGraphemeBreak.of(0x11A8))
        assertEquals(GraphemeClusterBreak.LV, UnicodeGraphemeBreak.of(0xAC00))
        assertEquals(GraphemeClusterBreak.LVT, UnicodeGraphemeBreak.of(0xAC01))
        assertEquals(GraphemeClusterBreak.OTHER, UnicodeGraphemeBreak.of(0x0041))
        assertEquals(GraphemeClusterBreak.OTHER, UnicodeGraphemeBreak.of(0x0378))
    }

    @Test
    fun indicConjunctBreakMatchesTheDatabase() {
        assertEquals(IndicConjunctBreak.LINKER, UnicodeIndicConjunctBreak.of(0x094D))
        assertEquals(IndicConjunctBreak.CONSONANT, UnicodeIndicConjunctBreak.of(0x0915))
        assertEquals(IndicConjunctBreak.EXTEND, UnicodeIndicConjunctBreak.of(0x0300))
        assertEquals(IndicConjunctBreak.EXTEND, UnicodeIndicConjunctBreak.of(0x200D))
        // The source lists no InCB value for a Latin letter, nor for an unassigned scalar.
        assertEquals(IndicConjunctBreak.NONE, UnicodeIndicConjunctBreak.of(0x0041))
        assertEquals(IndicConjunctBreak.NONE, UnicodeIndicConjunctBreak.of(0x0378))
    }

    @Test
    fun lineBreakMatchesTheDatabase() {
        assertEquals(LineBreakClass.LF, UnicodeLineBreak.of(0x000A))
        assertEquals(LineBreakClass.CR, UnicodeLineBreak.of(0x000D))
        assertEquals(LineBreakClass.SP, UnicodeLineBreak.of(0x0020))
        assertEquals(LineBreakClass.AL, UnicodeLineBreak.of(0x0041))
        assertEquals(LineBreakClass.ID, UnicodeLineBreak.of(0x4E00))
        assertEquals(LineBreakClass.GL, UnicodeLineBreak.of(0x00A0))
        assertEquals(LineBreakClass.ID, UnicodeLineBreak.of(0x1F600))
        assertEquals(LineBreakClass.XX, UnicodeLineBreak.of(0x0378))
    }

    @Test
    fun extendedPictographicMatchesTheDatabase() {
        assertTrue(UnicodeExtendedPictographic.isExtendedPictographic(0x00A9))
        assertTrue(UnicodeExtendedPictographic.isExtendedPictographic(0x1F600))
        assertFalse(UnicodeExtendedPictographic.isExtendedPictographic(0x0041))
        assertFalse(UnicodeExtendedPictographic.isExtendedPictographic(0x0378))
    }

    @Test
    fun scriptMatchesTheDatabase() {
        assertEquals("Latn", UnicodeScript.codeOf(0x0041))
        assertEquals("Hebr", UnicodeScript.codeOf(0x05D0))
        assertEquals("Arab", UnicodeScript.codeOf(0x0627))
        assertEquals("Hani", UnicodeScript.codeOf(0x4E00))
        assertEquals("Zinh", UnicodeScript.codeOf(0x0300))
        assertEquals("Zyyy", UnicodeScript.codeOf(0x0020))
        assertEquals("Zzzz", UnicodeScript.codeOf(0x0378))
    }

    @Test
    fun scriptExtensionsMatchTheDatabase() {
        assertTrue(UnicodeScriptExtensions.extensionsOf(0x00B7).contains("Latn"))
        assertTrue(UnicodeScriptExtensions.extensionsOf(0x0964).containsAll(listOf("Deva", "Beng")))
        // A scalar the source does not list has no extensions; it falls back to its Script.
        assertTrue(UnicodeScriptExtensions.extensionsOf(0x0041).isEmpty())
        assertFalse(UnicodeScriptExtensions.hasExtensions(0x0041))
        assertFalse(UnicodeScriptExtensions.hasExtensions(0x0378))
        assertTrue(UnicodeScriptExtensions.hasExtensions(0x00B7))
    }

    @Test
    fun bidiBracketsMatchTheDatabase() {
        assertEquals(BidiBracketType.OPEN, UnicodeBidiBrackets.typeOf(0x0028))
        assertEquals(0x0029, UnicodeBidiBrackets.pairedOf(0x0028))
        assertEquals(BidiBracketType.CLOSE, UnicodeBidiBrackets.typeOf(0x0029))
        assertEquals(0x0028, UnicodeBidiBrackets.pairedOf(0x0029))
        assertEquals(BidiBracketType.OPEN, UnicodeBidiBrackets.typeOf(0x005B))
        assertEquals(0x2046, UnicodeBidiBrackets.pairedOf(0x2045))
        // An unpaired scalar answers itself, which is what the UCD's own API documents.
        assertEquals(BidiBracketType.NONE, UnicodeBidiBrackets.typeOf(0x0041))
        assertEquals(0x0041, UnicodeBidiBrackets.pairedOf(0x0041))
    }

    @Test
    fun likelyScriptMatchesTheDatabase() {
        assertEquals("Latn", UnicodeLikelyScript.scriptOf("en"))
        assertEquals("Arab", UnicodeLikelyScript.scriptOf("ar"))
        assertEquals("Hebr", UnicodeLikelyScript.scriptOf("he"))
        assertEquals("Jpan", UnicodeLikelyScript.scriptOf("ja"))
        assertEquals("Cyrl", UnicodeLikelyScript.scriptOf("ru"))
        // A two-letter tag is looked up as its whole self, and an unknown tag answers nothing.
        assertEquals("Latn", UnicodeLikelyScript.scriptOf("aa"))
        assertEquals(null, UnicodeLikelyScript.scriptOf("zzz"))
    }

    @Test
    fun everyTableDeclaresThePinnedUnicodeVersion() {
        val version = "16.0"
        assertEquals(version, UnicodeVerticalOrientation.unicodeVersion)
        assertEquals(version, UnicodeBidiClass.unicodeVersion)
        assertEquals(version, UnicodeGraphemeBreak.unicodeVersion)
        assertEquals(version, UnicodeIndicConjunctBreak.unicodeVersion)
        assertEquals(version, UnicodeLineBreak.unicodeVersion)
        assertEquals(version, UnicodeExtendedPictographic.unicodeVersion)
        assertEquals(version, UnicodeScript.unicodeVersion)
        assertEquals(version, UnicodeScriptExtensions.unicodeVersion)
        assertEquals(version, UnicodeBidiBrackets.unicodeVersion)
        assertEquals(version, UnicodeLikelyScript.unicodeVersion)
    }
}
