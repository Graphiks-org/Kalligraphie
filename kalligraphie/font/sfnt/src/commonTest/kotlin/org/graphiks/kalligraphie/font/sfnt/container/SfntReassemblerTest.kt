@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.decodeAsciiTag
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32

class SfntReassemblerTest {
    @Test
    fun writesASortedPaddedDirectory() {
        val sfnt = assertIs<FontOperationResult.Success<ByteArray>>(
            SfntReassembler.assemble(0x00010000u, listOf(SfntTable("head", ByteArray(54)), SfntTable("cmap", ByteArray(5))), 4_096),
        ).value
        assertEquals(0x00010000u, readUInt32(sfnt, 0))
        assertEquals(2, readUInt16(sfnt, 4)!!.toInt())
        assertEquals("cmap", sfnt.decodeAsciiTag(12))
        assertEquals("head", sfnt.decodeAsciiTag(28))
        assertEquals(0, sfnt.size % 4)
    }

    @Test
    fun theHeadDirectoryChecksumIsComputedWithTheAdjustmentZeroed() {
        val head = ByteArray(54).also { it[8] = 0x12; it[9] = 0x34; it[10] = 0x56; it[11] = 0x78 }
        val sfnt = assertIs<FontOperationResult.Success<ByteArray>>(
            SfntReassembler.assemble(0x00010000u, listOf(SfntTable("head", head)), 4_096),
        ).value
        assertEquals(SfntReassembler.tableChecksum(ByteArray(54)), readUInt32(sfnt, 16))
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(sfnt))
    }

    @Test
    fun refusesACombinedSizeBeyondTheLimitEvenWhenEachTableFits() {
        // Each table is below 100, but the assembled font (12 + 32 + 56 + 8 = 108) is not.
        val failure = assertIs<FontOperationResult.Failure>(
            SfntReassembler.assemble(
                0x00010000u,
                listOf(SfntTable("head", ByteArray(54)), SfntTable("cmap", ByteArray(5))),
                maxAssembledBytes = 100,
            ),
        )
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }
}
