@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.io.encoding.Base64

/**
 * One WOFF2 `hmtx` transform vector plus the reconstruction this decoder must produce.
 *
 * [transformed] is the transformed `hmtx` table exactly as it appears in the WOFF2 font-data
 * stream; [expected] is the normal interleaved `hmtx` table this decoder pins.
 */
internal class Woff2HmtxVector(
    val transformed: ByteArray,
    val expected: ByteArray,
    val numberOfHMetrics: Int,
    val numGlyphs: Int,
    val xMinByGlyph: IntArray,
)

/**
 * Test-only WOFF2 `hmtx` transform fixtures.
 *
 * The five **hand-constructed** vectors were built byte-by-byte from W3C WOFF2 §5.4 and pin the
 * exact reconstruction for every valid `flags` value (`1`, `2`, `3`) and both count shapes
 * (`numberOfHMetrics < numGlyphs` and equal). Their layout was cross-checked against
 * `fontTools 4.65.0`'s `WOFF2HmtxTable.transform`/`reconstruct`.
 *
 * The three **real fontTools-derived** fixtures come from one small TrueType font whose glyph
 * order is `[".notdef", "A", "B", "C", "D"]` with `numberOfHMetrics == 3`, so glyphs `C` and `D`
 * are the trailing (monospaced) bearings. `.notdef` and `D` are empty (`xMin == 0`). In the
 * `FLAGS_TWO` font `A.lsb (1) != A.xMin (0)`, which forces the explicit `lsb[]` array and yields
 * `flags == 2`; in the `FLAGS_THREE` fonts every `lsb` equals its `xMin`, yielding `flags == 3`.
 *
 * Commands used to produce the fontTools-derived fixtures (Python 3.12):
 *
 * ```
 * uv run --with fonttools==4.65.0 --with brotli python3 gen_hmtx.py
 * ```
 *
 * `REAL_WOFF2_FLAGS_TWO` transforms both `glyf`/`loca` and `hmtx`;
 * `REAL_WOFF2_NULL_GLYF_FLAGS_THREE` transforms only `hmtx`, so its `glyf`/`loca` use the null
 * transform (version 3) and the reader must read each `xMin` from the passthrough `glyf` records.
 */
internal object Woff2HmtxVectors {
    /** `flags = 1`, equal counts: the proportional `lsb[]` is derived, the (empty) trailing is read. */
    val FLAGS_ONE: Woff2HmtxVector = vector(
        transformed = "AQH0Arw=",
        expected = "AfQAAwK8//w=",
        numberOfHMetrics = 2,
        numGlyphs = 2,
        xMins = intArrayOf(3, -4),
    )

    /**
     * `flags = 2`, `numberOfHMetrics < numGlyphs`: the proportional `lsb[]` is explicit and the
     * trailing bearings are derived from `xMin` (the last glyph is empty, so its bearing is 0).
     */
    val FLAGS_TWO: Woff2HmtxVector = vector(
        transformed = "AgJY//U=",
        expected = "Alj/9QAFAAA=",
        numberOfHMetrics = 1,
        numGlyphs = 3,
        xMins = intArrayOf(0, 5, 0),
    )

    /** `flags = 3`, equal counts: both arrays are derived, so the trailing list is empty. */
    val FLAGS_THREE_FLAT: Woff2HmtxVector = vector(
        transformed = "AwH0Arw=",
        expected = "AfQAAwK8//w=",
        numberOfHMetrics = 2,
        numGlyphs = 2,
        xMins = intArrayOf(3, -4),
    )

    /** `flags = 2`, equal counts: the proportional `lsb[]` is explicit and the trailing list is empty. */
    val FLAGS_TWO_EQUAL: Woff2HmtxVector = vector(
        transformed = "AgH0ArwAA//8",
        expected = "AfQAAwK8//w=",
        numberOfHMetrics = 2,
        numGlyphs = 2,
        xMins = intArrayOf(3, -4),
    )

    /** `flags = 3`, `numberOfHMetrics < numGlyphs`: both arrays are derived. */
    val FLAGS_THREE_UNEQUAL: Woff2HmtxVector = vector(
        transformed = "AwH0Arw=",
        expected = "AfQAAAK8AAcAAP/9",
        numberOfHMetrics = 2,
        numGlyphs = 4,
        xMins = intArrayOf(0, 7, 0, -3),
    )

    /** `flags = 1`, `numberOfHMetrics < numGlyphs`: the trailing `leftSideBearing[]` is explicit. */
    val FLAGS_ONE_UNEQUAL: Woff2HmtxVector = vector(
        transformed = "AQJYAAkAAQ==",
        expected = "AlgAAAAJAAE=",
        numberOfHMetrics = 1,
        numGlyphs = 3,
        xMins = intArrayOf(0, 5, 0),
    )

    /** A real fontTools `hmtx` transform with `flags == 2` (`glyf`/`loca` also transformed). */
    val REAL_FLAGS_TWO: Woff2HmtxVector = vector(
        transformed = "AgH0AlgCvAAAAAEAFA==",
        expected = "AfQAAAJYAAECvAAUAAoAAA==",
        numberOfHMetrics = 3,
        numGlyphs = 5,
        xMins = intArrayOf(0, 0, 20, 10, 0),
    )

    /** A real fontTools `hmtx` transform with `flags == 3`. */
    val REAL_FLAGS_THREE: Woff2HmtxVector = vector(
        transformed = "AwH0AlgCvA==",
        expected = "AfQAAAJYAAACvAAUAAoAAA==",
        numberOfHMetrics = 3,
        numGlyphs = 5,
        xMins = intArrayOf(0, 0, 20, 10, 0),
    )

    /** The original `hmtx` table of the `flags == 2` real fixture. */
    val REAL_REFERENCE_HMTX_FLAGS_TWO: ByteArray = Base64.decode("AfQAAAJYAAECvAAUAAoAAA==")

    /** The original `hmtx` table shared by the two `flags == 3` real fixtures. */
    val REAL_REFERENCE_HMTX_FLAGS_THREE: ByteArray = Base64.decode("AfQAAAJYAAACvAAUAAoAAA==")

    /** A full real WOFF2 whose `glyf`/`loca` and `hmtx` all use non-null transforms. */
    val REAL_WOFF2_FLAGS_TWO: ByteArray = Base64.decode(
        "d09GMgABAAAAAAHAAAoAAAAABCgAAAF1AAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAABmAANApETwE2AiRDEA0LDAAEIAWDXQcsG3IDAB6HsRt6EiKqcbH5rCUe/tuv3TczH1Fti2S1UrQSxUqmcQiJ0DQSEqmR9/5PvcuYuvRAvliep92WMSZWtAC2sC0cyKmb3D3haGAUaOcZreWJbLCxNZSN2QaMIIHAM3d1NjseaqUu8fPijXJ6RJWi8QOx2YttTp6qY3D8AmCKnZDRZwWZxRRKb2CvXsd2veAfwSMVaEFGKzoBBRBCkIuA+rW1FTW798LVyiUYV24Mz1UAXwSCm7/O5HWu/Tch/4Ln+dcf2+BeDGQJBIe7mz5E/OwQAJAT/tNdAgl9sFJER2JrbV4ECMPMcdJoGGeTpnAxbRNX2p1ZDbqlLIoKYdU1cNLhDWcb3nGx44srI8HMBninQV0Xn5GsJHTUzDsdFPu8jMkjVgmN6Io7DPmWcKgq9113pbacfp9TgVqtkBkFerlCghxpzHbkQGE3W4wSR4nMohYYoURatDCUvYM2IvMqMZqmOi2yomY5C08YFeNz5i1YBAAAAA==",
    )

    /** A full real WOFF2 whose `glyf`/`loca` use the null transform (version 3) but `hmtx` is transformed. */
    val REAL_WOFF2_NULL_GLYF_FLAGS_THREE: ByteArray = Base64.decode(
        "d09GMgABAAAAAAHIAAoAAAAABCgAAAGCAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAABmAANMpCATYCJEMQB8sMBCAFg10HLBtrAwCeB7Yx7NG0TBpFyg6j+GsPHhF8jf323X3xpJg3MQ9ZrNK1ZBpDSJTO35/7QaOBPhs1D3ceaRGNA8PSEcTO+yrCQEowoQbCgxQ2/kbAwYM0dZO7iH+EbdDOM1rLE9lgY4tGsvEtCiyCBALP3NW12T1EV7LEz4s3yuWKdQWiDNTBIo5oFVKazD9Aj7MTMtp+kFkwjsJS4DQTRxDlFfDAixRhac29KC8Ia+ONjrKy1qbaL6mOsJqaXbsyGzNRTZn9GwLB7cN9Baz6Q+kNX+Pn2gVvUkiWQHCeu+klPsEAgFzxX/wUImo6JkwIFOsMiZAavgUIA4xBUjYAsjHjoGDCAShqevBLeWdZNAaFZa8AScsvyPb9gYJjHygaDN4rId6nbatfSAgZXHbrCefVuW5z9npVKUdz00MezZYIoZgX3WhpflF+nxuRVqtSmEVGpUoGXOqsTuBc5bTazDKmTGHTiswwoyomeHS9gykg05WZLXuDHlgyb1HVJ9wii9NmzJoD",
    )

    /** [REAL_WOFF2_NULL_GLYF_FLAGS_THREE] whose `hmtx` `origLength` declares 17 rather than 16 bytes. */
    val REAL_WOFF2_BAD_HMTX_LENGTH: ByteArray = Base64.decode(
        "d09GMgABAAAAAAHEAAoAAAAABCgAAAF6AAEAAAAAAAAAAAAAAAAAAAAAAAAAAAAABmAANApETwE2AiRDEQcLDAAEIAWDXQcsG2wDAB4HuRnrksJNWwq33CclHr7G2vu7i6inOySrhULUShQrmUpIhKaR6aQI+Q6uefeRutADWYEnrvUjEiea7qVLnDDI0VNuSnyxVNDGa9TuXJ/IBztf6fFsIiOYwMAHorN2UbPX8FBbahM/L54op2usTtHkADZWsWWZJ2sYgh8ATvIJGb1hkC0mUVYDu7UatmqCvgW31IFmZLSgA1AAIQS5cKheWlpQGfdVuFq2YHC1s+G2AuiNQHDz25G0jtX/xsy+4Xnu9a0X3LOBLIHgcH+W3ok/9xAAkDP+02l6F6hTBEgAAGj1IkAYQoCkwZAA2YRJAYopGwLUaXMGQb0Jk7IoMoQV13OUtHtF2bo3VGz7oTrDUQnq69KT4jj4huTEoW1S97QO+ropneerajXabIsn6LZETG7fdJNFdSl9PicMsVjAUzLkfAEHOZSo9ci+QK/WKDkOHJ5GzFDCjqZYmGDoHSwR2QtHqTrKpMiyqqWsnvDrWJ01Z94CAAA=",
    )

    private fun vector(
        transformed: String,
        expected: String,
        numberOfHMetrics: Int,
        numGlyphs: Int,
        xMins: IntArray,
    ): Woff2HmtxVector = Woff2HmtxVector(
        transformed = Base64.decode(transformed),
        expected = Base64.decode(expected),
        numberOfHMetrics = numberOfHMetrics,
        numGlyphs = numGlyphs,
        xMinByGlyph = xMins,
    )
}
