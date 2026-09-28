@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

class Woff2ReaderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun decodesAnUntransformedWoff2AndRecomputesChecksums() {
        val decoded = assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2TestFonts.singleTableUntransformed(), limits),
        ).value
        assertEquals(0xB1B0AFBAu, SfntReassembler.wholeFontChecksum(decoded))
    }

    @Test
    fun acceptsAReconstructedSizeThatDiffersFromTotalSfntSize() {
        assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2TestFonts.withWrongTotalSfntSize(), limits),
        )
    }

    @Test
    fun acceptsANonZeroReservedField() {
        assertIs<FontOperationResult.Success<ByteArray>>(
            Woff2Reader.decode(Woff2TestFonts.withNonZeroReserved(), limits),
        )
    }

    @Test
    fun refusesACollectionFlavor() {
        assertIs<FontError.UnsupportedContainer>(
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withCollectionFlavor(), limits)).error,
        )
    }

    @Test
    fun refusesAnUnknownTransformVersion() {
        assertEquals(
            "font.woff2.unknown-transform",
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withUnknownTransform(), limits)).error.code,
        )
    }

    @Test
    fun refusesABadUIntBase128() {
        assertEquals(
            "font.woff2.invalid-table-directory",
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withBadUIntBase128(), limits)).error.code,
        )
    }

    @Test
    fun aValidStreamWithTheWrongDirectoryLengthIsNotABrotliFailure() {
        assertEquals(
            "font.woff2.invalid-font-data-size",
            assertIs<FontOperationResult.Failure>(Woff2Reader.decode(Woff2TestFonts.withWrongDirectoryLength(), limits)).error.code,
        )
    }
}
