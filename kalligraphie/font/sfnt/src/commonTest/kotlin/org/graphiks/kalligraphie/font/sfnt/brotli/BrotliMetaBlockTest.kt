@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BrotliMetaBlockTest {
    @Test
    fun aLeadingZeroBitMeansWbits16() {
        assertEquals(16, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000010))))
    }

    @Test
    fun oneThenThreeZeroBitsMeansWbits17() {
        assertEquals(17, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00000001))))
    }

    @Test
    fun oneThenWbits24() {
        assertEquals(24, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00001111))))
    }

    @Test
    fun theReservedPatternIsRejected() {
        // "0010001" (bit0=1, then 000, then 100) names no window and must not decode.
        assertEquals(null, BrotliMetaBlock.readWbits(BrotliBits(byteArrayOf(0b00010001))))
    }

    @Test
    fun lastEmptyEndsTheStream() {
        val header = assertIs<BrotliLastEmptyHeader>(BrotliMetaBlock.read(BrotliBits(byteArrayOf(0b00000011))))
        assertEquals(true, header.isLast)
    }

    @Test
    fun readsAnUncompressedMetaBlockHeader() {
        // ISLAST=0, MNIBBLES=4, MLEN-1=1, ISUNCOMPRESSED=1, then two literal bytes at byte 3.
        val bits = BrotliBits(
            byteArrayOf(0b00001000, 0, 0b00001000, 'A'.code.toByte(), 'B'.code.toByte()),
        )
        val header = assertIs<BrotliUncompressedHeader>(BrotliMetaBlock.read(bits))
        assertEquals(false, header.isLast)
        assertEquals(2, header.length)
        assertEquals('A'.code, bits.readBits(8))
        assertEquals('B'.code, bits.readBits(8))
    }

    @Test
    fun readsAMetadataMetaBlockHeader() {
        // ISLAST=0, MNIBBLES code 11 => metadata, reserved=0, MSKIPBYTES=0.
        val header = assertIs<BrotliMetadataHeader>(BrotliMetaBlock.read(BrotliBits(byteArrayOf(0b00000110))))
        assertEquals(false, header.isLast)
        assertEquals(0, header.length)
    }

    @Test
    fun readsANonEmptyMetadataLength() {
        // ISLAST=0, MNIBBLES code 11 => metadata, reserved=0, MSKIPBYTES=1, MSKIPLEN-1=0, one byte.
        val bits = BrotliBits(byteArrayOf(0b00010110, 0, 0x7F))
        val header = assertIs<BrotliMetadataHeader>(BrotliMetaBlock.read(bits))
        assertEquals(1, header.length)
        assertEquals(0x7F, bits.readBits(8))
    }

    @Test
    fun readsACompressedHeaderWithSingleTrees() {
        val writer = BrotliTestBitWriter()
        writer.writeBit(0)            // ISLAST
        writer.writeBits(0, 2)        // MNIBBLES code 00 => 4
        writer.writeBits(0, 16)       // MLEN - 1 = 0 => MLEN = 1
        writer.writeBit(0)            // ISUNCOMPRESSED
        writer.writeBit(0)            // NBLTYPESL = 1
        writer.writeBit(0)            // NBLTYPESI = 1
        writer.writeBit(0)            // NBLTYPESD = 1
        writer.writeBits(0, 2)        // NPOSTFIX = 0
        writer.writeBits(0, 4)        // NDIRECT high nibble = 0
        writer.writeBits(0, 2)        // literal context mode 0
        writer.writeBit(0)            // NTREESL = 1
        writer.writeBit(0)            // NTREESD = 1
        writer.writeSimpleCode(65, symbolBits = 8)  // literal
        writer.writeSimpleCode(0, symbolBits = 10)  // insert-and-copy
        writer.writeSimpleCode(0, symbolBits = 6)   // distance, alphabet 64

        val bits = BrotliBits(writer.toByteArray())
        val header = assertIs<BrotliCompressedHeader>(BrotliMetaBlock.read(bits))
        assertEquals(false, bits.overran)
        assertEquals(false, header.isLast)
        assertEquals(1, header.length)
        assertEquals(0, header.npostfix)
        assertEquals(0, header.ndirect)
        assertEquals(1, header.literalBlockCategory.blockTypes)
        assertEquals(null, header.literalBlockCategory.typeCode)
        assertEquals(0, header.literalBlockCategory.firstCount)
        assertEquals(1, header.numLiteralTrees)
        assertEquals(1, header.numDistanceTrees)
        assertContentEquals(intArrayOf(0), header.literalContextModes)
        assertContentEquals(IntArray(64), header.literalContextMap)
        assertContentEquals(IntArray(4), header.distanceContextMap)
        assertEquals(65, header.literalCodes[0].readCode(BrotliBits(byteArrayOf())))
        assertEquals(0, header.insertCopyCodes[0].readCode(BrotliBits(byteArrayOf())))
        assertEquals(0, header.distanceCodes[0].readCode(BrotliBits(byteArrayOf())))
    }

    @Test
    fun readsLiteralBlockSwitchingTables() {
        val writer = BrotliTestBitWriter()
        writer.writeBit(0)            // ISLAST
        writer.writeBits(0, 2)        // MNIBBLES code 00 => 4
        writer.writeBits(0, 16)       // MLEN - 1 = 0 => MLEN = 1
        writer.writeBit(0)            // ISUNCOMPRESSED
        writer.writeBit(1)            // NBLTYPESL is encoded as 2
        writer.writeBits(0, 3)
        writer.writeSimpleCode(0, symbolBits = 2)   // block type code, alphabet 4
        writer.writeSimpleCode(0, symbolBits = 5)   // block count code, alphabet 26
        writer.writeBits(0, 2)        // first block count extra bits => 1
        writer.writeBit(0)            // NBLTYPESI = 1
        writer.writeBit(0)            // NBLTYPESD = 1
        writer.writeBits(0, 2)        // NPOSTFIX = 0
        writer.writeBits(0, 4)        // NDIRECT high nibble = 0
        writer.writeBits(0, 2)        // context mode for block type 0
        writer.writeBits(0, 2)        // context mode for block type 1
        writer.writeBit(0)            // NTREESL = 1
        writer.writeBit(0)            // NTREESD = 1
        writer.writeSimpleCode(65, symbolBits = 8)
        writer.writeSimpleCode(0, symbolBits = 10)
        writer.writeSimpleCode(0, symbolBits = 6)

        val bits = BrotliBits(writer.toByteArray())
        val header = assertIs<BrotliCompressedHeader>(BrotliMetaBlock.read(bits))
        assertEquals(false, bits.overran)
        assertEquals(2, header.literalBlockCategory.blockTypes)
        assertEquals(1, header.literalBlockCategory.firstCount)
        assertEquals(2, header.literalContextModes.size)
        assertContentEquals(IntArray(128), header.literalContextMap)
    }

    /** A one-symbol simple prefix code; the canonical length is zero, so it emits no bits. */
    private fun BrotliTestBitWriter.writeSimpleCode(symbol: Int, symbolBits: Int) {
        writeBits(1, 2)     // simple
        writeBits(0, 2)     // NSYM - 1 = 0
        writeBits(symbol, symbolBits)
    }
}
