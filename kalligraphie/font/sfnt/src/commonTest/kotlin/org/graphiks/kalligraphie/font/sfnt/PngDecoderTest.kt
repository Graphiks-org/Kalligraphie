package org.graphiks.kalligraphie.font.sfnt

import kotlin.io.encoding.Base64
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PngDecoderTest {
    @Test
    fun decodesStraightRgbaPixelsFromATruecolorImage() {
        val decoded = value(PngDecoder.decode(fixture(RGBA_2X2), limits(), "CBDT"))

        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
        assertContentEquals(
            byteArrayOf(
                255.toByte(), 0, 0, 255.toByte(),
                0, 255.toByte(), 0, 128.toByte(),
                0, 0, 255.toByte(), 64,
                255.toByte(), 255.toByte(), 255.toByte(), 0,
            ),
            decoded.copyPixels(),
        )
    }

    @Test
    fun expandsTruecolorPixelsToOpaqueAlpha() {
        val decoded = value(PngDecoder.decode(fixture(RGB_1X1), limits(), "sbix"))

        assertEquals(1, decoded.width)
        assertContentEquals(byteArrayOf(10, 20, 30, 255.toByte()), decoded.copyPixels())
    }

    @Test
    fun inspectsTheDeclaredHeaderWithoutInflatingPixels() {
        val header = header(PngDecoder.inspectHeader(fixture(RGBA_2X2), limits(), "CBDT"))

        assertEquals(2, header.width)
        assertEquals(2, header.height)
        assertEquals(BitmapPixelFormat.RGBA_8888, header.pixelFormat)
    }

    @Test
    fun inspectsAHeaderWithoutRequiringOrReadingLaterChunks() {
        val headerOnly = fixture(RGBA_2X2).copyOfRange(0, IHDR_CHUNK_END)

        val header = header(PngDecoder.inspectHeader(headerOnly, limits(), "CBDT"))
        assertEquals(2, header.width)
        assertEquals(2, header.height)

        assertEquals(
            "font.png.truncated",
            code(assertIs<FontOperationResult.Failure>(PngDecoder.decode(headerOnly, limits(), "CBDT"))),
        )
    }

    @Test
    fun refusesAHostileDeclaredHeaderWhenInspecting() {
        val failure = assertIs<FontOperationResult.Failure>(
            PngDecoder.inspectHeader(fixture(HOSTILE_DIMENSIONS), limits(), "CBDT"),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)

        assertEquals(BitmapResourceLimit.WIDTH, error.limit)
        assertEquals(5_000, error.observed)
        assertEquals(16, error.maximum)
    }

    @Test
    fun toleratesASuggestedPlteChunkInATruecolorImage() {
        val decoded = value(PngDecoder.decode(fixture(RGBA_WITH_PLTE), limits(), "CBDT"))

        assertEquals(1, decoded.width)
        assertContentEquals(byteArrayOf(10, 20, 30, 255.toByte()), decoded.copyPixels())
    }

    @Test
    fun refusesASuggestedPaletteThatPrecedesTheHeader() {
        assertEquals(
            "font.png.invalid-chunk-order",
            code(assertIs<FontOperationResult.Failure>(PngDecoder.decode(fixture(PLTE_FIRST), limits(), "CBDT"))),
        )
    }

    @Test
    fun rejectsADecompressionBombBeforePublishingPixels() {
        val failure = assertIs<FontOperationResult.Failure>(PngDecoder.decode(fixture(BOMB), limits(), "CBDT"))
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)

        assertEquals(BitmapResourceLimit.DECODED_BYTES, error.limit)
        assertEquals(5, error.maximum)
    }

    @Test
    fun rejectsHostileDeclaredDimensionsBeforeInflating() {
        val failure = assertIs<FontOperationResult.Failure>(
            PngDecoder.decode(fixture(HOSTILE_DIMENSIONS), limits(), "CBDT"),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)

        assertEquals(BitmapResourceLimit.WIDTH, error.limit)
        assertEquals(5_000, error.observed)
        assertEquals(16, error.maximum)
    }

    @Test
    fun rejectsImagesWhosePixelsAreNotEightBitTruecolor() {
        assertEquals(
            "font.png.unsupported-format",
            code(assertIs<FontOperationResult.Failure>(PngDecoder.decode(fixture(PALETTE_INDEXED), limits(), "CBDT"))),
        )
        assertEquals(
            "font.png.unsupported-format",
            code(assertIs<FontOperationResult.Failure>(PngDecoder.decode(fixture(INTERLACED), limits(), "CBDT"))),
        )
    }

    @Test
    fun reportsTruncatedImagesAndCorruptedCrcsAsInvalidData() {
        val truncated = fixture(RGBA_2X2).copyOfRange(0, fixture(RGBA_2X2).size - 12)
        assertEquals(
            "font.png.truncated",
            code(assertIs<FontOperationResult.Failure>(PngDecoder.decode(truncated, limits(), "CBDT"))),
        )

        val corrupted = fixture(RGBA_2X2).also { bytes -> bytes[20] = (bytes[20] + 1).toByte() }
        assertEquals(
            "font.png.invalid-crc",
            code(assertIs<FontOperationResult.Failure>(PngDecoder.decode(corrupted, limits(), "CBDT"))),
        )
    }

    @Test
    fun refusesCompressedDataThatExceedsItsBound() {
        val failure = assertIs<FontOperationResult.Failure>(
            PngDecoder.decode(fixture(RGBA_2X2), limits(maxCompressedBytes = 1), "CBDT"),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)

        assertEquals(BitmapResourceLimit.COMPRESSED_BYTES, error.limit)
        assertEquals(1, error.maximum)
    }

    @Test
    fun reportsTheExactDimensionWhenPixelsExceedTheirBound() {
        val failure = assertIs<FontOperationResult.Failure>(
            PngDecoder.decode(
                fixture(HOSTILE_DIMENSIONS),
                limits(maxWidth = 10_000, maxHeight = 10_000, maxPixels = 256),
                "CBDT",
            ),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)
        assertEquals(BitmapResourceLimit.PIXELS, error.limit)
        assertEquals(25_000_000, error.observed)
        assertEquals(256, error.maximum)
    }

    @Test
    fun reportsHeightBeforePixelsWhenOnlyHeightExceedsItsBound() {
        val failure = assertIs<FontOperationResult.Failure>(
            PngDecoder.decode(
                fixture(HOSTILE_DIMENSIONS),
                limits(maxWidth = 10_000, maxHeight = 4_999, maxPixels = 25_000_000),
                "CBDT",
            ),
        )
        val error = assertIs<FontError.BitmapResourceLimitExceeded>(failure.error)
        assertEquals(BitmapResourceLimit.HEIGHT, error.limit)
        assertEquals(5_000, error.observed)
        assertEquals(4_999, error.maximum)
    }

    @Test
    fun refusesAnUnsupportedRowFilterInsteadOfThrowing() {
        assertEquals(
            "font.png.unsupported-filter",
            code(assertIs<FontOperationResult.Failure>(PngDecoder.decode(fixture(UNSUPPORTED_FILTER), limits(), "CBDT"))),
        )
    }

    @Test
    fun appliesSubAndUpRowFilters() {
        val decoded = value(PngDecoder.decode(fixture(FILTERS_SUB_UP), limits(), "CBDT"))

        assertContentEquals(
            byteArrayOf(
                10, 20, 30, 255.toByte(), 40, 50, 60, 255.toByte(),
                70, 80, 90, 255.toByte(), 100, 110, 120, 255.toByte(),
            ),
            decoded.copyPixels(),
        )
    }

    @Test
    fun appliesAverageAndPaethRowFilters() {
        val decoded = value(PngDecoder.decode(fixture(FILTERS_AVG_PAETH), limits(), "CBDT"))

        assertContentEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
            decoded.copyPixels(),
        )
    }

    private fun limits(
        maxWidth: Int = 16,
        maxHeight: Int = 16,
        maxPixels: Int = 256,
        maxCompressedBytes: Int = 4_096,
    ): BitmapLimits = BitmapLimits(
        maxStrikes = 1,
        maxIndexSubtables = 1,
        maxRecordCount = 1,
        maxIndexTableBytes = 1_024,
        maxSourceTableBytes = 1_024,
        maxWidth = maxWidth,
        maxHeight = maxHeight,
        maxPixels = maxPixels,
        maxCompressedBytes = maxCompressedBytes,
        maxTotalCompressedBytes = 4_096,
        maxDecodedBytes = 1_024,
        maxTotalDecodedBytes = 1_024,
    )

    private fun fixture(encoded: String): ByteArray = Base64.decode(encoded)

    private fun value(result: FontOperationResult<DecodedPng>): DecodedPng =
        assertIs<FontOperationResult.Success<DecodedPng>>(result).value

    private fun header(result: FontOperationResult<PngHeader>): PngHeader =
        assertIs<FontOperationResult.Success<PngHeader>>(result).value

    private fun code(result: FontOperationResult.Failure): String =
        assertIs<FontError.FontDataFailure>(result.error).code
}

private const val RGBA_2X2 = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAF0lEQVR42mP4z8DwHwgbGIC0w////xkAQBgHul5CkSMAAAAASUVORK5CYII="
private const val RGB_1X1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR42mPgEpEDAABoAD1q9XBbAAAAAElFTkSuQmCC"
private const val RGBA_WITH_PLTE = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACVBMVEX/AAAA/wAAAP8tSs2KAAAADUlEQVR42mPgEpH7DwABpAE8TNUcpwAAAABJRU5ErkJggg=="
private const val PLTE_FIRST = "iVBORw0KGgoAAAADUExURf8AABniCTcAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mPgEpH7DwABpAE8TNUcpwAAAABJRU5ErkJggg=="
private const val IHDR_CHUNK_END = 33
private const val BOMB = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAHElEQVR42u3BAQ0AAADCoGzvX8oeDigAAADg3QD/rRA9bckhIQAAAABJRU5ErkJggg=="
private const val HOSTILE_DIMENSIONS = "iVBORw0KGgoAAAANSUhEUgAAE4gAABOICAYAAABdmIfLAAAAC0lEQVR42mNggAIAAAkAAWj2z04AAAAASUVORK5CYII="
private const val PALETTE_INDEXED = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAMAAAAoyzS7AAAACklEQVR42mNgAAAAAgAB5Sfe/AAAAABJRU5ErkJggg=="
private const val INTERLACED = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAFoEvQfAAAADUlEQVR42mNgYGD4DwABBAEAgLvRWwAAAABJRU5ErkJggg=="
private const val UNSUPPORTED_FILTER = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNlZGIGAAAiAAw7fcc7AAAAAElFTkSuQmCC"
private const val FILTERS_SUB_UP = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR42mPkEpEDAiYbMAAACvwCAh9ycW4AAAAASUVORK5CYII="
private const val FILTERS_AVG_PAETH = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAYAAABytg0kAAAAF0lEQVR42mNmZGJmYWVlY2PhAAIWIAAAAvUAWK2eakcAAAAASUVORK5CYII="
