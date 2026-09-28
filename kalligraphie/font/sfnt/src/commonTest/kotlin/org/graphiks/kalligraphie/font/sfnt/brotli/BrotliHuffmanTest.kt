@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BrotliHuffmanTest {
    @Test
    fun aSingleSymbolCodeConsumesNoBits() {
        assertEquals(2, BrotliHuffman.fromSingleSymbol(2).readCode(BrotliBits(byteArrayOf())))
    }

    @Test
    fun aTwoSymbolCanonicalTreeDecodesBothSymbols() {
        val code = BrotliHuffman.fromCodeLengths(intArrayOf(1, 1), maxBits = 1)
        assertEquals(0, code.readCode(BrotliBits(byteArrayOf(0b00000000))))
        assertEquals(1, code.readCode(BrotliBits(byteArrayOf(0b00000001))))
    }

    @Test
    fun readsASimpleOneSymbolCodeDescription() {
        // LSB-first: 2 bits "simple" = 1 (bit0=1), 2 bits NSYM-1 = 0, 1 symbol bit = 1 => 0x11.
        val code = BrotliHuffmanReader.read(BrotliBits(byteArrayOf(0x11)), alphabetSize = 2)
        assertEquals(1, code.readCode(BrotliBits(byteArrayOf())))
    }

    @Test
    fun readsAComplexCodeDescription() {
        // HSKIP 0; code-length-code lengths (1, 2, 2) for symbols 1, 2, 3; main lengths (1, 2, 3, 3)
        // for symbols 0..3. Bits, in stream order: 00 1110 110 110 0 10 11 11 => 0xDC 0xA6 0x07.
        val code = BrotliHuffmanReader.read(
            BrotliBits(byteArrayOf(0xDC.toByte(), 0xA6.toByte(), 0x07)),
            alphabetSize = 4,
        )
        assertEquals(0, code.readCode(BrotliBits(byteArrayOf(0))))
        assertEquals(1, code.readCode(BrotliBits(byteArrayOf(1))))
        assertEquals(2, code.readCode(BrotliBits(byteArrayOf(3))))
        assertEquals(3, code.readCode(BrotliBits(byteArrayOf(7))))
    }

    @Test
    fun rejectsASimpleCodeThatRepeatsASymbol() {
        // Simple, NSYM-1 = 1, then the symbol 0 twice => 0x05.
        assertFailsWith<IllegalArgumentException> {
            BrotliHuffmanReader.read(BrotliBits(byteArrayOf(0x05)), alphabetSize = 4)
        }
    }
}
