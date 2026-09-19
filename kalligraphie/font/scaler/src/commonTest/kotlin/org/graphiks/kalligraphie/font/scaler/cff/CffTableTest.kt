package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CffTableTest {
    @Test
    fun readsHeaderIndexesAndTopDictOffsets() {
        val bytes = buildCff1 { offset -> dictInt(offset) + byteArrayOf(17) }

        val table = success(CffTable.read(bytes))

        assertEquals(1, table.nameIndex.itemCount)
        assertEquals(0, table.stringIndex.itemCount)
        assertEquals(0, table.globalSubrIndex.itemCount)
        assertEquals(2, table.charStringType)
        assertEquals(1, table.glyphCount)
        assertEquals(1, table.charStringsIndex.itemCount)
        assertEquals(CffCharset.Predefined(CffCharsetReader.ISO_ADOBE), table.charset)
        assertEquals(bytes.size, table.charStringsIndex.endOffset)
    }

    @Test
    fun rejectsANonCff1MajorVersion() {
        val bytes = buildCff1 { offset -> dictInt(offset) + byteArrayOf(17) }.also { it[0] = 2 }

        val error = assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(CffTable.read(bytes)).error)
        assertEquals("font.cff.unsupported-major-version", error.code)
    }

    @Test
    fun rejectsAnUnsupportedCharstringType() {
        val bytes = buildCff1 { offset -> dictInt(3) + byteArrayOf(12, 6) + dictInt(offset) + byteArrayOf(17) }

        val error = assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(CffTable.read(bytes)).error)
        assertEquals("font.cff.unsupported-charstring-type", error.code)
    }

    @Test
    fun rejectsAMissingCharStringsOperator() {
        val bytes = buildCff1 { _offset: Int -> dictInt(2) + byteArrayOf(12, 6) }

        val error = assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(CffTable.read(bytes)).error)
        assertEquals("font.cff.missing-charstrings", error.code)
    }

    @Test
    fun rejectsATruncatedHeader() {
        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(CffTable.read(byteArrayOf(1, 0, 4))).error)
    }

    @Test
    fun leavesCharsetAndPrivateAbsentWhenNotDeclared() {
        val bytes = buildCff1 { offset -> dictInt(offset) + byteArrayOf(17) }

        val table = success(CffTable.read(bytes))

        assertNull(table.charsetOffset)
        assertNull(table.privateDict)
        assertNull(table.privateDictData)
        assertNull(table.localSubrs)
        assertEquals(0, table.nominalWidthX)
        assertEquals(0, table.defaultWidthX)
    }

    @Test
    fun readsPrivateDictAndLocalSubrs() {
        val table = success(CffTable.read(buildCff1WithLocalSubrs()))

        assertEquals(CffTable.PrivateDictRef(size = 6, offset = table.privateDict!!.offset), table.privateDict)
        assertEquals(1, table.localSubrs?.itemCount)
        assertEquals(1, table.glyphCount)
    }

    @Test
    fun refusesAPrivateDictOutsideTheSource() {
        val bytes = buildCff1 { offset ->
            dictInt(10) + dictInt(99999) + byteArrayOf(18) + dictInt(offset) + byteArrayOf(17)
        }

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(CffTable.read(bytes)).error)
    }

    @Test
    fun reportsCidKeyedWhenRosIsDeclared() {
        val bytes = buildCff1 { offset ->
            dictInt(0) + dictInt(0) + byteArrayOf(12, 30) + dictInt(offset) + byteArrayOf(17)
        }

        assertTrue(success(CffTable.read(bytes)).isCidKeyed)
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}

private fun dictInt(value: Int): ByteArray = byteArrayOf(
    29,
    (value shr 24).toByte(),
    (value shr 16).toByte(),
    (value shr 8).toByte(),
    value.toByte(),
)

private fun cffIndex(items: List<ByteArray>): ByteArray {
    require(items.isNotEmpty()) { "cffIndex helper only builds non-empty INDEXes; use [0,0] for empty." }
    val out = ArrayList<Byte>()
    out += 0
    out += items.size.toByte()
    out += 2
    var offset = 1
    for (item in items) {
        out += ((offset shr 8) and 0xFF).toByte()
        out += (offset and 0xFF).toByte()
        offset += item.size
    }
    out += ((offset shr 8) and 0xFF).toByte()
    out += (offset and 0xFF).toByte()
    for (item in items) out.addAll(item.toList())
    return out.toByteArray()
}

private val oneGlyphCharStrings: ByteArray = cffIndex(listOf(byteArrayOf(14)))

private fun buildCff1(
    charStrings: ByteArray = oneGlyphCharStrings,
    buildTopDict: (charStringsOffset: Int) -> ByteArray,
): ByteArray {
    val header = byteArrayOf(1, 0, 4, 4)
    val nameIndex = cffIndex(listOf("Test".encodeToByteArray()))
    val probe = cffIndex(listOf(buildTopDict(0)))
    val base = header.size + nameIndex.size + probe.size + 2 + 2
    val topIndex = cffIndex(listOf(buildTopDict(base)))
    return header + nameIndex + topIndex + byteArrayOf(0, 0) + byteArrayOf(0, 0) + charStrings
}

private fun buildCff1WithLocalSubrs(): ByteArray {
    val header = byteArrayOf(1, 0, 4, 4)
    val nameIndex = cffIndex(listOf("Test".encodeToByteArray()))
    val charStringsIndex = oneGlyphCharStrings
    val stringIndex = byteArrayOf(0, 0)
    val globalSubr = byteArrayOf(0, 0)
    val localSubrs = cffIndex(listOf(byteArrayOf(11)))
    val privateDictData = dictInt(6) + byteArrayOf(19)

    fun topDict(charStringsOffset: Int, privateOffset: Int): ByteArray =
        dictInt(charStringsOffset) + byteArrayOf(17) +
            dictInt(privateDictData.size) + dictInt(privateOffset) + byteArrayOf(18)

    val probe = cffIndex(listOf(topDict(0, 0))).size
    val charStringsOffset = header.size + nameIndex.size + probe + stringIndex.size + globalSubr.size
    val privateOffset = charStringsOffset + charStringsIndex.size
    val topIndex = cffIndex(listOf(topDict(charStringsOffset, privateOffset)))
    return header + nameIndex + topIndex + stringIndex + globalSubr + charStringsIndex + privateDictData + localSubrs
}
