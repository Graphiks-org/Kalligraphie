@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CffReaderTest {
    @Test
    fun decodesAPlainGlyph() {
        val bytes = minimalCff1WithGlyph(byteArrayOf(149.toByte(), 159.toByte(), 21, 169.toByte(), 179.toByte(), 5, 14))
        val table = success(CffTable.read(bytes))

        val outline = success(CffReader.readGlyphOutline(bytes, table, 0, 2048, profile()))

        assertEquals(2048, outline.unitsPerEm)
        assertEquals(2, outline.pointCount)
        assertEquals(GlyphOutlineCommand.MoveTo(10.0, 20.0), outline.contours.single().commands[0])
        assertEquals(GlyphOutlineCommand.LineTo(40.0, 60.0), outline.contours.single().commands[1])
    }

    @Test
    fun refusesAnOutOfRangeGlyph() {
        val bytes = minimalCff1WithGlyph(byteArrayOf(14))
        val table = success(CffTable.read(bytes))

        val result = CffReader.readGlyphOutline(bytes, table, 7, 1000, profile())

        assertIs<FontError.GlyphOutOfRange>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun decodesACidKeyedGlyphThroughItsFontDict() {
        val charString = byteArrayOf(139.toByte(), 139.toByte(), 21, 149.toByte(), 139.toByte(), 5, 14)
        val bytes = buildCidCff1(charString)
        val table = success(CffTable.read(bytes))

        val outline = success(CffReader.readGlyphOutline(bytes, table, 0, 1000, profile()))

        assertEquals(GlyphOutlineCommand.MoveTo(0.0, 0.0), outline.contours.single().commands[0])
        assertEquals(GlyphOutlineCommand.LineTo(10.0, 0.0), outline.contours.single().commands[1])
    }

    @Test
    fun refusesACidKeyedFaceWithoutAFontDict() {
        val ros = testDictInt(0) + testDictInt(0) + byteArrayOf(12, 30)
        val bytes = minimalCff1WithGlyph(byteArrayOf(14), topDictPrefix = ros)
        val table = success(CffTable.read(bytes))

        val error = assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(CffReader.readGlyphOutline(bytes, table, 0, 1000, profile())).error,
        )
        assertEquals("font.cff.cid-missing-font-dict", error.code)
    }

    @Test
    fun refusesASeacWhoseAccentCannotBeResolved() {
        val bytes = minimalCff1WithGlyph(byteArrayOf(149.toByte(), 149.toByte(), 149.toByte(), 149.toByte(), 14))
        val table = success(CffTable.read(bytes))

        val error = assertIs<FontError.FontDataFailure>(
            assertIs<FontOperationResult.Failure>(CffReader.readGlyphOutline(bytes, table, 0, 1000, profile())).error,
        )
        assertEquals("font.cff.seac-unresolved", error.code)
    }

    @Test
    fun observesCancellationBeforeDecoding() {
        val bytes = minimalCff1WithGlyph(byteArrayOf(14))
        val table = success(CffTable.read(bytes))

        assertIs<FontOperationResult.Cancelled>(
            CffReader.readGlyphOutline(bytes, table, 0, 1000, profile(), CancellationToken.cancelled),
        )
    }

    @Test
    fun composesASeacFromItsBaseAndAccentGlyphs() {
        val notdef = byteArrayOf(14)
        val baseA = byteArrayOf(139.toByte(), 139.toByte(), 21, 149.toByte(), 139.toByte(), 5, 14)
        val accentB = byteArrayOf(139.toByte(), 139.toByte(), 21, 139.toByte(), 149.toByte(), 5, 14)
        // endchar adx=5 ady=7 bchar='A' achar='B' -> codes 65/66 -> SIDs 34/35
        val seacGlyph = byteArrayOf(144.toByte(), 146.toByte(), 204.toByte(), 205.toByte(), 14)
        val bytes = buildCff1WithGlyphs(listOf(notdef, baseA, accentB, seacGlyph), listOf(34, 35, 0))
        val table = success(CffTable.read(bytes))

        val outline = success(CffReader.readGlyphOutline(bytes, table, 3, 1000, profile()))

        assertEquals(2, outline.contours.size)
        assertEquals(GlyphOutlineCommand.MoveTo(0.0, 0.0), outline.contours[0].commands[0])
        assertEquals(GlyphOutlineCommand.LineTo(10.0, 0.0), outline.contours[0].commands[1])
        assertEquals(GlyphOutlineCommand.MoveTo(5.0, 7.0), outline.contours[1].commands[0])
        assertEquals(GlyphOutlineCommand.LineTo(5.0, 17.0), outline.contours[1].commands[1])
    }

    private fun profile(): OutlineProfile = OutlineProfile(
        maxBytes = 1_000_000,
        maxContours = 1_000,
        maxPoints = 100_000,
        maxCompositeDepth = 32,
        maxCompositeComponents = 1_024,
    )

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
