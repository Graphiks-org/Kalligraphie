@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColorSpace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EbdtFormatOneReaderTest {
    @Test
    fun rejectsUnsupportedIndexAndImageFormatsBeforeReturningRouteData() {
        val unsupportedIndex = formatOneTables(indexFormat = 2, imageFormat = 1)
        val unsupportedImage = formatOneTables(indexFormat = 1, imageFormat = 2)

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(EbdtFormatOneReader.read(unsupportedIndex.first, unsupportedIndex.second, glyphCount = 1, profile = profile())),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(EbdtFormatOneReader.read(unsupportedImage.first, unsupportedImage.second, glyphCount = 1, profile = profile())),
        )
    }

    @Test
    fun rejectsBitmapProfilesThatDoNotUseSchemaVersionTwo() {
        val tables = formatOneTables()

        val unsupported = assertIs<FontError.UnsupportedRepresentationProfile>(
            error(
                EbdtFormatOneReader.read(
                    eblcTable = tables.first,
                    ebdtTable = tables.second,
                    glyphCount = 1,
                    profile = profile(schemaVersion = 1),
                ),
            ),
        )
        assertTrue(unsupported.message.contains("schema version 2"), unsupported.message)
    }

    @Test
    fun rejectsAProfileWhoseStrikeBitDepthDoesNotMatchTheFixture() {
        val tables = formatOneTables()

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(
                EbdtFormatOneReader.read(
                    eblcTable = tables.first,
                    ebdtTable = tables.second,
                    glyphCount = 1,
                    profile = profile(bitDepth = 8),
                ),
            ),
        )
    }

    @Test
    fun rejectsAProfileWhoseStrikePixelsPerEmDoNotMatchTheFixture() {
        val tables = formatOneTables()

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(
                EbdtFormatOneReader.read(
                    eblcTable = tables.first,
                    ebdtTable = tables.second,
                    glyphCount = 1,
                    profile = profile(pixelsPerEmX = 8),
                ),
            ),
        )
    }

    @Test
    fun rejectsAggregateCompressedBytesBeforeReturningAnyBitmapRouteData() {
        val tables = formatOneTables(recordCount = 2)

        val failure = assertIs<FontOperationResult.Failure>(
            EbdtFormatOneReader.read(
                eblcTable = tables.first,
                ebdtTable = tables.second,
                glyphCount = 2,
                profile = profile(maxRecordCount = 2, maxTotalCompressedBytes = 11),
            ),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)
        assertEquals(BitmapResourceLimit.TOTAL_COMPRESSED_BYTES, error.limit)
        assertEquals(12, error.observed)
        assertEquals(11, error.maximum)
    }

    @Test
    fun reportsTheExactSourceTableBoundThatRejectedTheRoute() {
        val tables = formatOneTables()

        val failure = assertIs<FontOperationResult.Failure>(
            EbdtFormatOneReader.read(
                eblcTable = tables.first,
                ebdtTable = tables.second,
                glyphCount = 1,
                profile = profile(maxSourceTableBytes = 4),
            ),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)
        assertEquals(BitmapResourceLimit.SOURCE_TABLE_BYTES, error.limit)
        assertEquals(10, error.observed)
        assertEquals(4, error.maximum)
    }

    @Test
    fun reportsWidthBeforeHeightWhenBothExceedTheirBounds() {
        val tables = formatOneTables(imageWidth = 2, imageHeight = 2)

        val failure = assertIs<FontOperationResult.Failure>(
            EbdtFormatOneReader.read(
                eblcTable = tables.first,
                ebdtTable = tables.second,
                glyphCount = 1,
                profile = profile(maxWidth = 1, maxHeight = 1),
            ),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)
        assertEquals(BitmapResourceLimit.WIDTH, error.limit)
        assertEquals(2, error.observed)
        assertEquals(1, error.maximum)
    }

    @Test
    fun rejectsASubtableWhoseGlyphRangeEscapesTheSelectedStrike() {
        val tables = formatOneTables(
            recordCount = 1,
            strikeEndGlyph = 0,
            subtableFirstGlyph = 1,
            subtableLastGlyph = 1,
        )

        assertEquals(
            "font.eblc.invalid-strike-glyph-range",
            assertIs<FontError.FontDataFailure>(
                error(EbdtFormatOneReader.read(tables.first, tables.second, glyphCount = 2, profile = profile())),
            ).code,
        )
    }

    @Test
    fun rejectsASubtableOutsideTheDeclaredIndexTablesRegion() {
        val tables = formatOneTables().also { (eblc, _) ->
            eblc.writeUInt32(12, 8u)
        }

        assertEquals(
            "font.eblc.invalid-index-tables-range",
            assertIs<FontError.FontDataFailure>(
                error(EbdtFormatOneReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsTruncatedEbdtHeaderAsInvalidFontData() {
        val result = EbdtFormatOneReader.read(
            eblcTable = eblcHeader(strikeCount = 0),
            ebdtTable = ByteArray(3),
            glyphCount = 1,
            profile = profile(),
        )

        assertEquals("font.ebdt.truncated", failure(result).error.code)
    }

    @Test
    fun reportsZeroPpemStrikeAsInvalidFontDataInsteadOfThrowing() {
        val eblc = ByteArray(56).also { bytes ->
            bytes.writeUInt32(0, VERSION_TWO)
            bytes.writeUInt32(4, 1u)
            bytes.writeUInt16(8 + 40, 0)
            bytes.writeUInt16(8 + 42, 0)
            bytes[8 + 44] = 0
            bytes[8 + 45] = 0
            bytes[8 + 46] = 1
        }

        val result = EbdtFormatOneReader.read(
            eblcTable = eblc,
            ebdtTable = ebdtHeader(),
            glyphCount = 1,
            profile = profile(),
        )

        assertEquals("font.eblc.invalid-strike", failure(result).error.code)
    }

    @Test
    fun reportsAnOutOfRangeStrikeBitDepthAsInvalidFontDataInsteadOfThrowing() {
        val eblc = ByteArray(56).also { bytes ->
            bytes.writeUInt32(0, VERSION_TWO)
            bytes.writeUInt32(4, 1u)
            bytes.writeUInt16(8 + 40, 0)
            bytes.writeUInt16(8 + 42, 0)
            bytes[8 + 44] = 16
            bytes[8 + 45] = 16
            bytes[8 + 46] = 33
        }

        val result = EbdtFormatOneReader.read(
            eblcTable = eblc,
            ebdtTable = ebdtHeader(),
            glyphCount = 1,
            profile = profile(),
        )

        assertEquals("font.eblc.invalid-strike", failure(result).error.code)
    }

    @Test
    fun capabilityPredicateAcceptsAValidFormatOneTable() {
        val tables = formatOneTables()

        assertTrue(EbdtFormatOneReader.hasStructurallyValidFormatOneTables(tables.first, tables.second, glyphCount = 1))
    }

    @Test
    fun capabilityPredicateRejectsAStructureOnlyViolation() {
        val tables = formatOneTables(indexFormat = 2)

        assertFalse(EbdtFormatOneReader.hasStructurallyValidFormatOneTables(tables.first, tables.second, glyphCount = 1))
    }

    @Test
    fun capabilityPredicateRejectsDuplicateStrikes() {
        val tables = formatOneBudgetTables(1, 1).also { (eblc, _) ->
            eblc[8 + 48 + 44] = 16
            eblc[8 + 48 + 45] = 16
        }

        assertFalse(EbdtFormatOneReader.hasStructurallyValidFormatOneTables(tables.first, tables.second, glyphCount = 1))
    }

    @Test
    fun capabilityPredicateRejectsCumulativeDecodedBytesAcrossStrikes() {
        val recordsPerStrike = 520
        val tables = formatOneBudgetTables(recordsPerStrike, recordsPerStrike)

        assertFalse(
            EbdtFormatOneReader.hasStructurallyValidFormatOneTables(
                tables.first,
                tables.second,
                glyphCount = recordsPerStrike,
            ),
        )
    }

    private fun profile(
        maxRecordCount: Int = 1,
        maxTotalCompressedBytes: Int = 64,
        schemaVersion: Int = 2,
        maxSourceTableBytes: Int = 1_024,
        maxWidth: Int = 16,
        maxHeight: Int = 16,
        maxPixels: Int = 256,
        pixelsPerEmX: Int = 16,
        pixelsPerEmY: Int = 16,
        bitDepth: Int = 1,
    ): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(pixelsPerEmX, pixelsPerEmY, bitDepth),
        acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 1,
            maxIndexSubtables = 1,
            maxRecordCount = maxRecordCount,
            maxIndexTableBytes = 1_024,
            maxSourceTableBytes = maxSourceTableBytes,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxPixels = maxPixels,
            maxCompressedBytes = 64,
            maxTotalCompressedBytes = maxTotalCompressedBytes,
            maxDecodedBytes = 256,
            maxTotalDecodedBytes = 256,
        ),
        schemaVersion = schemaVersion,
    )

    private fun eblcHeader(strikeCount: Int): ByteArray = ByteArray(8).also { bytes ->
        bytes.writeUInt32(0, VERSION_TWO)
        bytes.writeUInt32(4, strikeCount.toUInt())
    }

    private fun ebdtHeader(): ByteArray = ByteArray(4).also { bytes -> bytes.writeUInt32(0, VERSION_TWO) }

    private fun formatOneTables(
        indexFormat: Int = 1,
        imageFormat: Int = 1,
        recordCount: Int = 1,
        strikeEndGlyph: Int = recordCount - 1,
        subtableFirstGlyph: Int = 0,
        subtableLastGlyph: Int = recordCount - 1,
        imageWidth: Int = 1,
        imageHeight: Int = 1,
    ): Pair<ByteArray, ByteArray> {
        val recordLength = 6
        val eblc = ByteArray(72 + (recordCount + 1) * 4).also { bytes ->
            bytes.writeUInt32(0, VERSION_TWO)
            bytes.writeUInt32(4, 1u)
            bytes.writeUInt32(8, 56u)
            bytes.writeUInt32(12, (bytes.size - 56).toUInt())
            bytes.writeUInt32(16, 1u)
            bytes.writeUInt16(48, 0)
            bytes.writeUInt16(50, strikeEndGlyph)
            bytes[52] = 16
            bytes[53] = 16
            bytes[54] = 1
            bytes.writeUInt16(56, subtableFirstGlyph)
            bytes.writeUInt16(58, subtableLastGlyph)
            bytes.writeUInt32(60, 8u)
            bytes.writeUInt16(64, indexFormat)
            bytes.writeUInt16(66, imageFormat)
            bytes.writeUInt32(68, 4u)
            repeat(recordCount + 1) { offset ->
                bytes.writeUInt32(72 + offset * 4, (offset * recordLength).toUInt())
            }
        }
        val ebdt = ByteArray(4 + recordCount * recordLength).also { bytes ->
            bytes.writeUInt32(0, VERSION_TWO)
            repeat(recordCount) { record ->
                val offset = 4 + record * recordLength
                bytes[offset] = imageHeight.toByte()
                bytes[offset + 1] = imageWidth.toByte()
                bytes[offset + 4] = 1
                bytes[offset + 5] = 0x80.toByte()
            }
        }
        return eblc to ebdt
    }

    private fun formatOneBudgetTables(vararg recordsPerStrike: Int): Pair<ByteArray, ByteArray> {
        val recordLength = 5 + ((BUDGET_DIMENSION + 7) / 8) * BUDGET_DIMENSION
        val assetLengths = recordsPerStrike.map { records -> 8 + 8 + (records + 1) * 4 }
        val indexTablesOffset = 8 + recordsPerStrike.size * 48
        val eblc = ByteArray(indexTablesOffset + assetLengths.sum())
        eblc.writeUInt32(0, VERSION_TWO)
        eblc.writeUInt32(4, recordsPerStrike.size.toUInt())
        var assetOffset = indexTablesOffset
        recordsPerStrike.forEachIndexed { strike, records ->
            val sizeTableOffset = 8 + strike * 48
            eblc.writeUInt32(sizeTableOffset, assetOffset.toUInt())
            eblc.writeUInt32(sizeTableOffset + 4, assetLengths[strike].toUInt())
            eblc.writeUInt32(sizeTableOffset + 8, 1u)
            eblc.writeUInt16(sizeTableOffset + 40, 0)
            eblc.writeUInt16(sizeTableOffset + 42, records - 1)
            eblc[sizeTableOffset + 44] = (16 + strike).toByte()
            eblc[sizeTableOffset + 45] = (16 + strike).toByte()
            eblc[sizeTableOffset + 46] = 1
            eblc.writeUInt16(assetOffset, 0)
            eblc.writeUInt16(assetOffset + 2, records - 1)
            eblc.writeUInt32(assetOffset + 4, 8u)
            val subtableOffset = assetOffset + 8
            eblc.writeUInt16(subtableOffset, 1)
            eblc.writeUInt16(subtableOffset + 2, 1)
            eblc.writeUInt32(subtableOffset + 4, 4u)
            repeat(records + 1) { index ->
                eblc.writeUInt32(subtableOffset + 8 + index * 4, (index * recordLength).toUInt())
            }
            assetOffset += assetLengths[strike]
        }
        val ebdt = ByteArray(4 + recordsPerStrike.max() * recordLength)
        ebdt.writeUInt32(0, VERSION_TWO)
        repeat(recordsPerStrike.max()) { record ->
            val offset = 4 + record * recordLength
            ebdt[offset] = BUDGET_DIMENSION.toByte()
            ebdt[offset + 1] = BUDGET_DIMENSION.toByte()
            ebdt[offset + 4] = 1
        }
        return eblc to ebdt
    }

    private fun failure(result: FontOperationResult<EbdtFormatOneData>): FontOperationResult.Failure =
        assertIs<FontOperationResult.Failure>(result).also { failure ->
            assertIs<FontError.FontDataFailure>(failure.error)
        }

    private fun error(result: FontOperationResult<EbdtFormatOneData>): FontError =
        assertIs<FontOperationResult.Failure>(result).error
}

private const val VERSION_TWO: UInt = 0x00020000u
private const val BUDGET_DIMENSION = 255

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeUInt32(offset: Int, value: UInt) {
    this[offset] = (value shr 24).toByte()
    this[offset + 1] = (value shr 16).toByte()
    this[offset + 2] = (value shr 8).toByte()
    this[offset + 3] = value.toByte()
}
