@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import kotlin.io.encoding.Base64
import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CbdtCblcReaderTest {
    @Test
    fun decodesStraightRgbaPixelsFromAFormatSeventeenRecord() {
        val tables = tables(strike())

        val glyph = decoded(
            value(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())).decode(GlyphId(0)),
        )

        assertContentEquals(rgba2x2Pixels(), glyph.copyDecodedPixels())
        assertEquals(2, glyph.width)
        assertEquals(2, glyph.height)
        assertEquals(1, glyph.originX)
        assertEquals(2, glyph.originY)
        assertEquals(3, glyph.metrics.advanceX)
        assertEquals(0, glyph.metrics.advanceY)
        assertEquals(BitmapPixelFormat.RGBA_8888, glyph.pixelFormat)
        assertEquals(BitmapStrike(16, 16, 32), glyph.strike)
    }

    @Test
    fun decodesAFormatEighteenRecordWithBigMetrics() {
        val tables = tables(strike(imageFormat = 18))

        val glyph = decoded(
            value(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())).decode(GlyphId(0)),
        )

        assertContentEquals(rgba2x2Pixels(), glyph.copyDecodedPixels())
        assertEquals(1, glyph.originX)
        assertEquals(2, glyph.originY)
        assertEquals(3, glyph.metrics.advanceX)
        assertEquals(4, glyph.metrics.advanceY)
    }

    @Test
    fun selectsOnlyTheExactStrike() {
        val tables = tables(
            strike(ppemX = 16, ppemY = 16),
            strike(ppemX = 32, ppemY = 32),
        )

        val sixteen = decoded(
            value(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())).decode(GlyphId(0)),
        )
        val thirtyTwo = decoded(
            value(
                CbdtCblcReader.read(
                    tables.first,
                    tables.second,
                    glyphCount = 1,
                    profile = profile(strike = BitmapStrike(32, 32, 32)),
                ),
            ).decode(GlyphId(0)),
        )

        assertEquals(BitmapStrike(16, 16, 32), sixteen.strike)
        assertEquals(BitmapStrike(32, 32, 32), thirtyTwo.strike)
        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(
                CbdtCblcReader.read(
                    tables.first,
                    tables.second,
                    glyphCount = 1,
                    profile = profile(strike = BitmapStrike(24, 24, 32)),
                ),
            ),
        )
    }

    @Test
    fun rejectsAVersionOneProfile() {
        val tables = tables(strike())

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(
                CbdtCblcReader.read(
                    tables.first,
                    tables.second,
                    glyphCount = 1,
                    profile = profile(schemaVersion = 1),
                ),
            ),
        )
    }

    @Test
    fun rejectsAStrikeWhoseBitDepthIsNotThirtyTwo() {
        val tables = tables(strike(bitDepth = 1))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(
                CbdtCblcReader.read(
                    tables.first,
                    tables.second,
                    glyphCount = 1,
                    profile = profile(strike = BitmapStrike(16, 16, 1)),
                ),
            ),
        )
    }

    @Test
    fun reportsReservedStrikeFlagsAsInvalidData() {
        val tables = tables(strike(flags = 0x04))

        assertEquals(
            "font.cblc.invalid-flags",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsAVerticalStrikeAsUnavailable() {
        val tables = tables(strike(flags = 0x02))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
        )
    }

    @Test
    fun rejectsImageFormatNineteenBeforePublishing() {
        val tables = tables(strike(imageFormat = 19))

        assertIs<FontError.UnsupportedRepresentationProfile>(
            error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
        )
    }

    @Test
    fun rejectsAPngWhoseDimensionsDisagreeWithTheMetrics() {
        val tables = tables(strike(imageWidth = 1, imageHeight = 1))

        assertEquals(
            "font.cbdt.dimension-mismatch",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsTypedBoundsForCumulativeDecodedBytes() {
        val tables = tables(strike(glyphs = 2))

        val failure = assertIs<FontOperationResult.Failure>(
            CbdtCblcReader.read(
                tables.first,
                tables.second,
                glyphCount = 2,
                profile = profile(maxTotalDecodedBytes = 31),
            ),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)
        assertEquals(BitmapResourceLimit.TOTAL_DECODED_BYTES, error.limit)
        assertEquals(32, error.observed)
        assertEquals(31, error.maximum)
    }

    @Test
    fun acceptsAStructurallyValidColourStrike() {
        val tables = tables(strike())

        assertTrue(CbdtCblcReader.hasStructurallyValidTables(tables.first, tables.second, glyphCount = 1))
    }

    @Test
    fun rejectsAStructurallyInvalidColourStrike() {
        val tables = tables(strike(bitDepth = 8))

        assertFalse(CbdtCblcReader.hasStructurallyValidTables(tables.first, tables.second, glyphCount = 1))
    }

    @Test
    fun reportsTruncatedTableHeadersAsInvalidData() {
        assertEquals(
            "font.cblc.truncated",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(ByteArray(7), cbdtHeader(), glyphCount = 1, profile = profile())),
            ).code,
        )
        assertEquals(
            "font.cbdt.truncated",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(cblcHeader(strikeCount = 0), ByteArray(3), glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsANonMonotonicOffsetArrayAsInvalidData() {
        val tables = tables(strike()).also { (cblc, _) -> cblc.writeUInt32(cblc.size - 8, 100u) }

        assertEquals(
            "font.cblc.invalid-offset-order",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsADuplicateSelectedStrikeAsInvalidData() {
        val tables = tables(strike(), strike())

        assertEquals(
            "font.cblc.duplicate-strike",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsOverlappingGlyphRecordsAcrossSubtablesAsInvalidData() {
        val tables = tables(strike(subtableRanges = listOf(0..0, 0..0)))

        assertEquals(
            "font.cblc.duplicate-glyph",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsASubtableGlyphRangeOutsideItsStrikeAsInvalidData() {
        val tables = tables(strike(subtableRanges = listOf(0..1)))

        assertEquals(
            "font.cblc.invalid-strike-glyph-range",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun reportsAZeroPpemStrikeAsInvalidData() {
        val tables = tables(strike(ppemX = 0))

        assertEquals(
            "font.cblc.invalid-strike",
            assertIs<FontError.FontDataFailure>(
                error(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())),
            ).code,
        )
    }

    @Test
    fun decodesNegativeBearingsAsSignedOrigins() {
        val tables = tables(strike(bearingX = -1, bearingY = -2))

        val glyph = decoded(
            value(CbdtCblcReader.read(tables.first, tables.second, glyphCount = 1, profile = profile())).decode(GlyphId(0)),
        )

        assertEquals(-1, glyph.originX)
        assertEquals(-2, glyph.originY)
    }

    private fun profile(
        strike: BitmapStrike = BitmapStrike(16, 16, 32),
        schemaVersion: Int = 2,
        maxTotalDecodedBytes: Int = 1_024,
    ): BitmapProfile = BitmapProfile(
        strike = strike,
        acceptedPixelFormats = listOf(BitmapPixelFormat.RGBA_8888),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = 8,
            maxIndexSubtables = 8,
            maxRecordCount = 8,
            maxIndexTableBytes = 4_096,
            maxSourceTableBytes = 4_096,
            maxWidth = 16,
            maxHeight = 16,
            maxPixels = 256,
            maxCompressedBytes = 4_096,
            maxTotalCompressedBytes = 4_096,
            maxDecodedBytes = 1_024,
            maxTotalDecodedBytes = maxTotalDecodedBytes,
        ),
        schemaVersion = schemaVersion,
    )

    private fun strike(
        ppemX: Int = 16,
        ppemY: Int = 16,
        bitDepth: Int = 32,
        flags: Int = 0x01,
        imageFormat: Int = 17,
        glyphs: Int = 1,
        subtableRanges: List<IntRange>? = null,
        imageWidth: Int = 2,
        imageHeight: Int = 2,
        bearingX: Int = 1,
        bearingY: Int = 2,
        advance: Int = 3,
        horiAdvance: Int = 3,
        vertAdvance: Int = 4,
    ): StrikeSpec = StrikeSpec(
        ppemX = ppemX,
        ppemY = ppemY,
        bitDepth = bitDepth,
        flags = flags,
        imageFormat = imageFormat,
        glyphs = glyphs,
        subtableRanges = subtableRanges,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        bearingX = bearingX,
        bearingY = bearingY,
        advance = advance,
        horiAdvance = horiAdvance,
        vertAdvance = vertAdvance,
    )

    private fun tables(vararg specs: StrikeSpec): Pair<ByteArray, ByteArray> {
        val recordLengths = specs.map { spec ->
            val metricsLength = if (spec.imageFormat == 18) BIG_GLYPH_METRICS_LENGTH else SMALL_GLYPH_METRICS_LENGTH
            metricsLength + DATA_LENGTH_FIELD_LENGTH + RGBA_2X2_BYTES.size
        }
        val ranges = specs.map { spec -> spec.subtableRanges ?: listOf(0 until spec.glyphs) }
        val assetLengths = ranges.map { strikeRanges ->
            strikeRanges.size * 8 + strikeRanges.sumOf { 8 + (it.count() + 1) * 4 }
        }
        val cblc = ByteArray(8 + specs.size * 48 + assetLengths.sum())
        val cbdt = ByteArray(4 + specs.indices.sumOf { recordLengths[it] * specs[it].glyphs })
        cblc.writeUInt32(0, VERSION_TWO)
        cblc.writeUInt32(4, specs.size.toUInt())
        cbdt.writeUInt32(0, VERSION_TWO)
        var indexTablesOffset = 8 + specs.size * 48
        var imageDataOffset = 4
        specs.forEachIndexed { index, spec ->
            val sizeTableOffset = 8 + index * 48
            val recordLength = recordLengths[index]
            cblc.writeUInt32(sizeTableOffset, indexTablesOffset.toUInt())
            cblc.writeUInt32(sizeTableOffset + 4, assetLengths[index].toUInt())
            cblc.writeUInt32(sizeTableOffset + 8, ranges[index].size.toUInt())
            cblc.writeUInt32(sizeTableOffset + 12, 0u)
            cblc.writeUInt16(sizeTableOffset + 40, 0)
            cblc.writeUInt16(sizeTableOffset + 42, spec.glyphs - 1)
            cblc[sizeTableOffset + 44] = spec.ppemX.toByte()
            cblc[sizeTableOffset + 45] = spec.ppemY.toByte()
            cblc[sizeTableOffset + 46] = spec.bitDepth.toByte()
            cblc[sizeTableOffset + 47] = spec.flags.toByte()
            var subtableOffsetWithinAsset = ranges[index].size * 8
            ranges[index].forEachIndexed { subtableIndex, range ->
                val entryOffset = indexTablesOffset + subtableIndex * 8
                cblc.writeUInt16(entryOffset, range.first)
                cblc.writeUInt16(entryOffset + 2, range.last)
                cblc.writeUInt32(entryOffset + 4, subtableOffsetWithinAsset.toUInt())
                val subtableOffset = indexTablesOffset + subtableOffsetWithinAsset
                cblc.writeUInt16(subtableOffset, 1)
                cblc.writeUInt16(subtableOffset + 2, spec.imageFormat)
                cblc.writeUInt32(subtableOffset + 4, imageDataOffset.toUInt())
                repeat(range.count() + 1) { glyphIndex ->
                    cblc.writeUInt32(subtableOffset + 8 + glyphIndex * 4, ((range.first + glyphIndex) * recordLength).toUInt())
                }
                subtableOffsetWithinAsset += 8 + (range.count() + 1) * 4
            }
            repeat(spec.glyphs) { glyph ->
                val recordOffset = imageDataOffset + glyph * recordLength
                cbdt[recordOffset] = spec.imageHeight.toByte()
                cbdt[recordOffset + 1] = spec.imageWidth.toByte()
                cbdt[recordOffset + 2] = spec.bearingX.toByte()
                cbdt[recordOffset + 3] = spec.bearingY.toByte()
                if (spec.imageFormat == 18) {
                    cbdt[recordOffset + 4] = spec.horiAdvance.toByte()
                    cbdt[recordOffset + 7] = spec.vertAdvance.toByte()
                    cbdt.writeUInt32(recordOffset + 8, RGBA_2X2_BYTES.size.toUInt())
                    RGBA_2X2_BYTES.copyInto(cbdt, recordOffset + 12)
                } else {
                    cbdt[recordOffset + 4] = spec.advance.toByte()
                    cbdt.writeUInt32(recordOffset + 5, RGBA_2X2_BYTES.size.toUInt())
                    RGBA_2X2_BYTES.copyInto(cbdt, recordOffset + 9)
                }
            }
            indexTablesOffset += assetLengths[index]
            imageDataOffset += recordLength * spec.glyphs
        }
        return cblc to cbdt
    }

    private fun value(result: FontOperationResult<CbdtCblcData>): CbdtCblcData =
        assertIs<FontOperationResult.Success<CbdtCblcData>>(result).value

    private fun decoded(result: FontOperationResult<BitmapGlyphIR>): BitmapGlyphIR =
        assertIs<FontOperationResult.Success<BitmapGlyphIR>>(result).value

    private fun error(result: FontOperationResult<CbdtCblcData>): FontError =
        assertIs<FontOperationResult.Failure>(result).error
}

private class StrikeSpec(
    val ppemX: Int,
    val ppemY: Int,
    val bitDepth: Int,
    val flags: Int,
    val imageFormat: Int,
    val glyphs: Int,
    val subtableRanges: List<IntRange>?,
    val imageWidth: Int,
    val imageHeight: Int,
    val bearingX: Int,
    val bearingY: Int,
    val advance: Int,
    val horiAdvance: Int,
    val vertAdvance: Int,
)

private fun rgba2x2Pixels(): ByteArray = byteArrayOf(
    255.toByte(), 0, 0, 255.toByte(),
    0, 255.toByte(), 0, 128.toByte(),
    0, 0, 255.toByte(), 64,
    255.toByte(), 255.toByte(), 255.toByte(), 0,
)

private const val RGBA_2X2 =
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAF0lEQVR42mP4z8DwHwgbGIC0w////xkAQBgHul5CkSMAAAAASUVORK5CYII="

private val RGBA_2X2_BYTES: ByteArray = Base64.decode(RGBA_2X2)

private fun cblcHeader(strikeCount: Int): ByteArray = ByteArray(8).also { bytes ->
    bytes.writeUInt32(0, VERSION_TWO)
    bytes.writeUInt32(4, strikeCount.toUInt())
}

private fun cbdtHeader(): ByteArray = ByteArray(4).also { bytes -> bytes.writeUInt32(0, VERSION_TWO) }

private const val VERSION_TWO: UInt = 0x00020000u
private const val SMALL_GLYPH_METRICS_LENGTH = 5
private const val BIG_GLYPH_METRICS_LENGTH = 8
private const val DATA_LENGTH_FIELD_LENGTH = 4

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
