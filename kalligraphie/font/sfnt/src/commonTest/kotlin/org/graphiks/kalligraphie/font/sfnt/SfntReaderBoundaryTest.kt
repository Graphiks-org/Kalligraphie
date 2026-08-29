package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import kotlin.test.Test
import kotlin.test.assertIs

class SfntReaderBoundaryTest {
    @Test
    fun rejectsUnsupportedContainersWithTypedFailure() {
        val bytes = byteArrayOf(
            't'.code.toByte(),
            't'.code.toByte(),
            'c'.code.toByte(),
            'f'.code.toByte(),
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("unsupported.ttcf")))

        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.UnsupportedContainer>(result.error)
    }

    @Test
    fun rejectsOutOfBoundsRequiredTablesWithTypedFailure() {
        val bytes = byteArrayOf(
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            'h'.code.toByte(),
            'e'.code.toByte(),
            'a'.code.toByte(),
            'd'.code.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x40,
            0x00,
            0x00,
            0x00,
            0x36,
        )

        val result = SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("out-of-bounds.ttf")))

        assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.MissingRequiredTable>(result.error)
    }
}
