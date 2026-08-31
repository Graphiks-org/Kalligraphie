package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CmapReaderTest {
    @Test
    fun prefersPlatform310Format12OverAllOtherSupportedUnicodeSubtables() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(
                format4Subtable(delta = 100, platformId = 0, encodingId = 3),
                format4Subtable(delta = 50, platformId = 3, encodingId = 1),
                format12Subtable(startCharCode = 0x41, endCharCode = 0x41, startGlyphId = 300, platformId = 0, encodingId = 4),
                format12Subtable(startCharCode = 0x41, endCharCode = 0x41, startGlyphId = 400, platformId = 3, encodingId = 10),
            ),
            codePoint = 0x41,
        )

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(400, success.value.glyphId.value)
    }

    @Test
    fun prefersPlatform0Format12OverFormat4WhenPlatform310IsAbsent() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(
                format4Subtable(delta = 100, platformId = 0, encodingId = 3),
                format4Subtable(delta = 50, platformId = 3, encodingId = 1),
                format12Subtable(startCharCode = 0x41, endCharCode = 0x41, startGlyphId = 300, platformId = 0, encodingId = 4),
            ),
            codePoint = 0x41,
        )

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(300, success.value.glyphId.value)
    }

    @Test
    fun prefersPlatform31Format4OverPlatform0Format4WhenNoFormat12Exists() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(
                format4Subtable(delta = 100, platformId = 0, encodingId = 3),
                format4Subtable(delta = 50, platformId = 3, encodingId = 1),
            ),
            codePoint = 0x41,
        )

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(115, success.value.glyphId.value)
    }

    @Test
    fun prefersLowerOffsetWhenTwoSubtablesHaveTheSamePriority() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(
                format12Subtable(startCharCode = 0x41, endCharCode = 0x41, startGlyphId = 300, platformId = 0, encodingId = 4),
                format12Subtable(startCharCode = 0x41, endCharCode = 0x41, startGlyphId = 301, platformId = 0, encodingId = 6),
            ),
            codePoint = 0x41,
        )

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(300, success.value.glyphId.value)
    }

    @Test
    fun resolvesFormat4GlyphUsingDeltaSegment() {
        val result = CmapReader.resolveGlyphId(cmapTable = cmapTable(format4Subtable(delta = -29)), codePoint = 0x41)

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(36, success.value.glyphId.value)
        assertTrue(success.diagnostics.isEmpty())
    }

    @Test
    fun resolvesFormat4GlyphUsingRangeOffsetSegment() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(format4SubtableWithRangeOffset(startCode = 0x00C4, endCode = 0x00C4, glyphId = 134)),
            codePoint = 0x00C4,
        )

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(134, success.value.glyphId.value)
    }

    @Test
    fun resolvesFormat12GlyphUsingUnicodeGroup() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(format12Subtable(startCharCode = 0x1F600, endCharCode = 0x1F602, startGlyphId = 900)),
            codePoint = 0x1F601,
        )

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(901, success.value.glyphId.value)
    }

    @Test
    fun rejectsMalformedFormat4SegmentOrdering() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(format4SubtableWithUnsortedSegments()),
            codePoint = 0x41,
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
        assertEquals(FontDiagnosticLocation.Table("cmap"), failure.error.location)
    }

    @Test
    fun rejectsCodePointsOutsideUnicodeScalarRange() {
        val result = CmapReader.resolveGlyphId(cmapTable = cmapTable(format12Subtable(0x41, 0x41, 36)), codePoint = 0x110000)

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
    }

    @Test
    fun hostileSubtableOffsetReturnsTypedFailureWithoutIndexException() {
        val cmap = ByteArray(12).also { bytes ->
            bytes.writeUInt16(0, 0)
            bytes.writeUInt16(2, 1)
            bytes.writeUInt16(4, 3)
            bytes.writeUInt16(6, 10)
            bytes.writeUInt32(8, Int.MAX_VALUE)
        }

        val result = CmapReader.resolveGlyphId(cmap, 0x41)

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
        assertEquals(Int.MAX_VALUE.toLong(), failure.diagnostics.single().data.offset)
    }

    @Test
    fun validatesTrailingFormat4SegmentsBeforePublishingQueriedMapping() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(format4SubtableWithMalformedTrailingSegment()),
            codePoint = 0x41,
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
    }

    @Test
    fun format4DeltaUsesUnsignedSixteenBitModuloArithmetic() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(format4Subtable(delta = -66)),
            codePoint = 0x41,
        )

        val success = assertIs<FontOperationResult.Success<GlyphLookupResult>>(result)
        assertEquals(0xFFFF, success.value.glyphId.value)
    }

    @Test
    fun rejectsGlyphIdAtOrBeyondMaxpGlyphCount() {
        val result = CmapReader.resolveGlyphId(
            cmapTable = cmapTable(format12Subtable(0x41, 0x41, 5)),
            codePoint = 0x41,
            numGlyphs = 5,
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.InvalidFontData>(failure.error)
        assertEquals(5L, failure.diagnostics.single().data.observedValue)
        assertEquals(5L, failure.diagnostics.single().data.limit)
    }

    @Test
    fun preparedUnicodeLookupReturnsStableResultsAcrossRepeatedQueries() {
        val lookup = assertIs<FontOperationResult.Success<UnicodeCmapLookup>>(
            CmapReader.readUnicodeCmap(
                cmapTable = cmapTable(format4Subtable(delta = -29)),
                numGlyphs = 256,
            ),
        ).value

        repeat(3) {
            assertEquals(36, assertIs<FontOperationResult.Success<GlyphLookupResult>>(lookup.resolveGlyphId(0x41)).value.glyphId.value)
            val missing = assertIs<FontOperationResult.Success<GlyphLookupResult>>(lookup.resolveGlyphId(0x42))
            assertEquals(0, missing.value.glyphId.value)
            assertEquals("font.cmap.glyph-not-found", missing.diagnostics.single().code)
        }
    }

    @Test
    fun resolvesAnExplicitUnicodeVariationSequenceWithoutAcceptingTheBaseMappingAlone() {
        val lookup = assertIs<FontOperationResult.Success<UnicodeCmapLookup>>(
            CmapReader.readUnicodeCmap(
                cmapTable(
                    format4SubtableWithRangeOffset(startCode = 0x2764, endCode = 0x2764, glyphId = 5),
                    format14Subtable(variationSelector = 0xFE0F, baseCodePoint = 0x2764, glyphId = 6),
                ),
                numGlyphs = 7,
            ),
        ).value

        assertEquals(5, assertIs<FontOperationResult.Success<GlyphLookupResult>>(lookup.resolveGlyphId(0x2764)).value.glyphId.value)
        assertEquals(6, assertIs<FontOperationResult.Success<GlyphLookupResult>>(lookup.resolveGlyphId(0x2764, 0xFE0F)).value.glyphId.value)
        assertEquals(5, assertIs<FontOperationResult.Success<GlyphLookupResult>>(lookup.resolveGlyphId(0x2764, 0xFE0E)).value.glyphId.value)
        assertEquals(0, assertIs<FontOperationResult.Success<GlyphLookupResult>>(lookup.resolveGlyphId(0x2764, 0xFE01)).value.glyphId.value)
    }
}

private fun cmapTable(vararg subtables: CmapSubtable): ByteArray {
    val headerSize = 4 + subtables.size * 8
    val totalSize = headerSize + subtables.sumOf { it.bytes.size }
    val bytes = ByteArray(totalSize)
    bytes.writeUInt16(0, 0)
    bytes.writeUInt16(2, subtables.size)
    var directoryOffset = 4
    var subtableOffset = headerSize
    for (subtable in subtables) {
        bytes.writeUInt16(directoryOffset, subtable.platformId)
        bytes.writeUInt16(directoryOffset + 2, subtable.encodingId)
        bytes.writeUInt32(directoryOffset + 4, subtableOffset)
        subtable.bytes.copyInto(bytes, destinationOffset = subtableOffset)
        directoryOffset += 8
        subtableOffset += subtable.bytes.size
    }
    return bytes
}

private fun format4Subtable(delta: Int, platformId: Int = 3, encodingId: Int = 1): CmapSubtable {
    val segCount = 2
    val length = 16 + segCount * 8
    val bytes = ByteArray(length)
    bytes.writeUInt16(0, 4)
    bytes.writeUInt16(2, length)
    bytes.writeUInt16(4, 0)
    bytes.writeUInt16(6, segCount * 2)
    bytes.writeUInt16(8, 0)
    bytes.writeUInt16(10, 0)
    bytes.writeUInt16(12, 0)
    bytes.writeUInt16(14, 0x0041)
    bytes.writeUInt16(16, 0xFFFF)
    bytes.writeUInt16(18, 0)
    bytes.writeUInt16(20, 0x0041)
    bytes.writeUInt16(22, 0xFFFF)
    bytes.writeInt16(24, delta)
    bytes.writeInt16(26, 1)
    bytes.writeUInt16(28, 0)
    bytes.writeUInt16(30, 0)
    return CmapSubtable(platformId = platformId, encodingId = encodingId, bytes = bytes)
}

private fun format4SubtableWithRangeOffset(startCode: Int, endCode: Int, glyphId: Int): CmapSubtable {
    val segCount = 2
    val length = 16 + segCount * 8 + 2
    val bytes = ByteArray(length)
    bytes.writeUInt16(0, 4)
    bytes.writeUInt16(2, length)
    bytes.writeUInt16(4, 0)
    bytes.writeUInt16(6, segCount * 2)
    bytes.writeUInt16(8, 0)
    bytes.writeUInt16(10, 0)
    bytes.writeUInt16(12, 0)
    bytes.writeUInt16(14, endCode)
    bytes.writeUInt16(16, 0xFFFF)
    bytes.writeUInt16(18, 0)
    bytes.writeUInt16(20, startCode)
    bytes.writeUInt16(22, 0xFFFF)
    bytes.writeInt16(24, 0)
    bytes.writeInt16(26, 1)
    bytes.writeUInt16(28, 4)
    bytes.writeUInt16(30, 0)
    bytes.writeUInt16(32, glyphId)
    return CmapSubtable(platformId = 3, encodingId = 1, bytes = bytes)
}

private fun format4SubtableWithUnsortedSegments(): CmapSubtable {
    val segCount = 3
    val length = 16 + segCount * 8
    val bytes = ByteArray(length)
    bytes.writeUInt16(0, 4)
    bytes.writeUInt16(2, length)
    bytes.writeUInt16(4, 0)
    bytes.writeUInt16(6, segCount * 2)
    bytes.writeUInt16(8, 0)
    bytes.writeUInt16(10, 0)
    bytes.writeUInt16(12, 0)
    bytes.writeUInt16(14, 0x0060)
    bytes.writeUInt16(16, 0x0041)
    bytes.writeUInt16(18, 0xFFFF)
    bytes.writeUInt16(20, 0)
    bytes.writeUInt16(22, 0x0050)
    bytes.writeUInt16(24, 0x0041)
    bytes.writeUInt16(26, 0xFFFF)
    bytes.writeInt16(28, 0)
    bytes.writeInt16(30, 0)
    bytes.writeInt16(32, 1)
    bytes.writeUInt16(34, 0)
    bytes.writeUInt16(36, 0)
    bytes.writeUInt16(38, 0)
    return CmapSubtable(platformId = 3, encodingId = 1, bytes = bytes)
}

private fun format4SubtableWithMalformedTrailingSegment(): CmapSubtable {
    val segCount = 3
    val length = 16 + segCount * 8
    val bytes = ByteArray(length)
    bytes.writeUInt16(0, 4)
    bytes.writeUInt16(2, length)
    bytes.writeUInt16(4, 0)
    bytes.writeUInt16(6, segCount * 2)
    bytes.writeUInt16(8, 0)
    bytes.writeUInt16(10, 0)
    bytes.writeUInt16(12, 0)
    bytes.writeUInt16(14, 0x0041)
    bytes.writeUInt16(16, 0x0030)
    bytes.writeUInt16(18, 0xFFFF)
    bytes.writeUInt16(20, 0)
    bytes.writeUInt16(22, 0x0041)
    bytes.writeUInt16(24, 0x0030)
    bytes.writeUInt16(26, 0xFFFF)
    bytes.writeInt16(28, -29)
    bytes.writeInt16(30, 0)
    bytes.writeInt16(32, 1)
    bytes.writeUInt16(34, 0)
    bytes.writeUInt16(36, 0)
    bytes.writeUInt16(38, 0)
    return CmapSubtable(platformId = 3, encodingId = 1, bytes = bytes)
}

private fun format12Subtable(
    startCharCode: Int,
    endCharCode: Int,
    startGlyphId: Int,
    platformId: Int = 3,
    encodingId: Int = 10,
): CmapSubtable {
    val length = 28
    val bytes = ByteArray(length)
    bytes.writeUInt16(0, 12)
    bytes.writeUInt16(2, 0)
    bytes.writeUInt32(4, length)
    bytes.writeUInt32(8, 0)
    bytes.writeUInt32(12, 1)
    bytes.writeUInt32(16, startCharCode)
    bytes.writeUInt32(20, endCharCode)
    bytes.writeUInt32(24, startGlyphId)
    return CmapSubtable(platformId = platformId, encodingId = encodingId, bytes = bytes)
}

private fun format14Subtable(
    variationSelector: Int,
    baseCodePoint: Int,
    glyphId: Int,
): CmapSubtable {
    val recordOffset = 10
    val selectorRecordsSize = 22
    val defaultOffset = recordOffset + selectorRecordsSize
    val mappingOffset = defaultOffset + 4 + 4
    val length = mappingOffset + 4 + 5
    val bytes = ByteArray(length)
    bytes.writeUInt16(0, 14)
    bytes.writeUInt32(2, length)
    bytes.writeUInt32(6, 2)
    bytes.writeUInt24(recordOffset, 0xFE0E)
    bytes.writeUInt32(recordOffset + 3, defaultOffset)
    bytes.writeUInt32(recordOffset + 7, 0)
    bytes.writeUInt24(recordOffset + 11, variationSelector)
    bytes.writeUInt32(recordOffset + 14, 0)
    bytes.writeUInt32(recordOffset + 18, mappingOffset)
    bytes.writeUInt32(defaultOffset, 1)
    bytes.writeUInt24(defaultOffset + 4, baseCodePoint)
    bytes[defaultOffset + 7] = 0
    bytes.writeUInt32(mappingOffset, 1)
    bytes.writeUInt24(mappingOffset + 4, baseCodePoint)
    bytes.writeUInt16(mappingOffset + 7, glyphId)
    return CmapSubtable(platformId = 0, encodingId = 5, bytes = bytes)
}

private data class CmapSubtable(
    val platformId: Int,
    val encodingId: Int,
    val bytes: ByteArray,
)

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeInt16(offset: Int, value: Int) {
    writeUInt16(offset, value and 0xFFFF)
}

private fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

private fun ByteArray.writeUInt24(offset: Int, value: Int) {
    this[offset] = (value ushr 16).toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = value.toByte()
}
