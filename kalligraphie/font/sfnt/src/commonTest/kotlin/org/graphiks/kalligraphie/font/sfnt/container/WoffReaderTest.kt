@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag

class WoffReaderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun roundTripsAnUncompressedDirectory() {
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt()), limits),
        ).value
        assertEquals("cmap", decoded.decodeAsciiTag(12))
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(decoded))
    }

    @Test
    fun roundTripsADeflatedTable() {
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapDeflated(WoffTestFonts.singleTableSfnt()), limits),
        ).value
        assertEquals("cmap", decoded.decodeAsciiTag(12))
    }

    @Test
    fun byteDistinctContainersDecodeToIdenticalBytes() {
        val font = WoffTestFonts.singleTableSfnt()
        val fromRaw = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapUncompressed(font), limits),
        ).value
        val fromDeflated = assertIs<FontOperationResult.Success<ByteArray>>(
            WoffReader.decode(WoffTestFonts.wrapDeflated(font), limits),
        ).value
        assertContentEquals(fromRaw, fromDeflated)
    }

    @Test
    fun refusesATruncatedHeader() {
        assertEquals(
            "font.woff.invalid-header",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(ByteArray(10), limits)).error.code,
        )
    }

    @Test
    fun refusesACollectionFlavor() {
        val woff = WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt(), flavor = 0x74746366u)
        assertIs<FontError.UnsupportedContainer>(
            assertIs<FontOperationResult.Failure>(WoffReader.decode(woff, limits)).error,
        )
    }

    @Test
    fun refusesAHeaderTotalSfntSizeMismatch() {
        val woff = WoffTestFonts.withWrongTotalSfntSize()
        assertEquals(
            "font.woff.invalid-header",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(woff, limits)).error.code,
        )
    }

    @Test
    fun refusesADuplicateTag() {
        assertEquals(
            "font.woff.invalid-table-directory",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(WoffTestFonts.wrapWithDuplicateTag(), limits)).error.code,
        )
    }

    @Test
    fun refusesANonZeroReservedField() {
        assertEquals(
            "font.woff.invalid-header",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(WoffTestFonts.withNonZeroReserved(), limits)).error.code,
        )
    }

    @Test
    fun refusesOverlappingTableExtents() {
        assertEquals(
            "font.woff.invalid-table-directory",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(WoffTestFonts.withOverlappingExtents(), limits)).error.code,
        )
    }

    @Test
    fun refusesACompressedLengthLargerThanOriginal() {
        assertEquals(
            "font.woff.invalid-table-directory",
            assertIs<FontOperationResult.Failure>(
                WoffReader.decode(WoffTestFonts.withCompressedLargerThanOriginal(), limits),
            ).error.code,
        )
    }

    @Test
    fun refusesAMalformedZlibStream() {
        assertEquals(
            "font.woff.invalid-deflate",
            assertIs<FontOperationResult.Failure>(WoffReader.decode(WoffTestFonts.withMalformedDeflate(), limits)).error.code,
        )
    }

    @Test
    fun refusesATableOverTheDecodingLimit() {
        val limited = WoffDecodeLimits(maxDecodedFontBytes = 1, maxWorkingBytes = 64L * 1024 * 1024)
        assertIs<FontError.ResourceLimitExceeded>(
            assertIs<FontOperationResult.Failure>(WoffReader.decode(WoffTestFonts.wrapUncompressed(), limited)).error,
        )
    }
}
