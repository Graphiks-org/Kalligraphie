@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.OutlineProfile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Cff2Test {
    @Test
    fun decodesADefaultInstanceGlyph() {
        val bytes = buildCff2WithGlyph(byteArrayOf(139.toByte(), 139.toByte(), 21, 149.toByte(), 139.toByte(), 5))
        val table = success(Cff2Table.read(bytes))

        val outline = success(Cff2Reader.readGlyphOutline(bytes, table, 0, 1000, profile()))

        assertEquals(1, table.glyphCount)
        assertEquals(GlyphOutlineCommand.MoveTo(0.0, 0.0), outline.contours.single().commands[0])
        assertEquals(GlyphOutlineCommand.LineTo(10.0, 0.0), outline.contours.single().commands[1])
    }

    @Test
    fun rejectsANonCff2MajorVersion() {
        val bytes = buildCff2WithGlyph(byteArrayOf(14)).also { it[0] = 1 }

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(Cff2Table.read(bytes)).error)
    }

    @Test
    fun computesDefaultInstanceRegionScalars() {
        val store = success(CffVarStore.read(variationStoreBytes(), 0))

        assertEquals(1, store.regionCount(0))
        assertContentEquals(doubleArrayOf(0.5), store.scalars(0))
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

    private fun variationStoreBytes(): ByteArray {
        val store = ArrayList<Byte>()
        fun u16(value: Int) { store += ((value shr 8) and 0xFF).toByte(); store += (value and 0xFF).toByte() }
        fun u32(value: Int) { store += ((value shr 24) and 0xFF).toByte(); store += ((value shr 16) and 0xFF).toByte(); store += ((value shr 8) and 0xFF).toByte(); store += (value and 0xFF).toByte() }
        u16(1)              // format
        u32(12)             // variationRegionListOffset
        u16(1)              // itemVariationDataCount
        u32(22)             // itemVariationDataOffsets[0]
        // region list at 12
        u16(1)              // axisCount
        u16(1)              // regionCount
        u16(0xC000)         // start = -1.0
        u16(0x4000)         // peak = 1.0
        u16(0x4000)         // end = 1.0
        // item variation data at 22
        u16(1)              // itemCount
        u16(0)              // wordDeltaCount
        u16(1)              // regionIndexCount
        u16(0)              // regionIndexes[0]
        val out = ArrayList<Byte>()
        out += ((store.size shr 8) and 0xFF).toByte()
        out += (store.size and 0xFF).toByte()
        out.addAll(store)
        return out.toByteArray()
    }
}

/** Builds a minimal CFF2 with one glyph, no variation store, and one Font DICT. */
internal fun buildCff2WithGlyph(charString: ByteArray): ByteArray {
    val header = byteArrayOf(2, 0, 5, 0, 0)
    val globalSubr = byteArrayOf(0, 0, 0, 0)
    val fdSelect = byteArrayOf(0, 0)
    val charStringsIndex = testCff2Index(listOf(charString))
    fun fontDict(privateOffset: Int): ByteArray = testDictInt(0) + testDictInt(privateOffset) + byteArrayOf(18)
    fun topDict(fdArrayOffset: Int, fdSelectOffset: Int, charStringsOffset: Int): ByteArray =
        testDictInt(charStringsOffset) + byteArrayOf(17) +
            testDictInt(fdArrayOffset) + byteArrayOf(12, 36) +
            testDictInt(fdSelectOffset) + byteArrayOf(12, 37)

    val probeTop = topDict(0, 0, 0)
    header[3] = ((probeTop.size shr 8) and 0xFF).toByte()
    header[4] = (probeTop.size and 0xFF).toByte()
    val globalSubrOffset = header.size + probeTop.size
    val fdSelectOffset = globalSubrOffset + globalSubr.size
    val charStringsOffset = fdSelectOffset + fdSelect.size
    val fdArrayOffset = charStringsOffset + charStringsIndex.size
    val fdArrayLength = testCff2Index(listOf(fontDict(0))).size
    val privateOffset = fdArrayOffset + fdArrayLength
    val topDictBytes = topDict(fdArrayOffset, fdSelectOffset, charStringsOffset)
    val fdArray = testCff2Index(listOf(fontDict(privateOffset)))
    return header + topDictBytes + globalSubr + fdSelect + charStringsIndex + fdArray
}
