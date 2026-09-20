package org.graphiks.kalligraphie.e2e

/** Location of the first byte where two canonical images differ. */
public data class GoldenImageDifference(
    /** Zero-based offset of the first differing byte. */
    public val byteOffset: Int,
    /** Pixel column of the differing byte. */
    public val x: Int,
    /** Pixel row of the differing byte. */
    public val y: Int,
)

/** Locates the first differing byte between two canonical images. */
public object GoldenImageDiff {
    /**
     * Returns the first differing byte location, or `null` when the canonical bytes
     * are identical.
     *
     * @throws IllegalArgumentException when the images have different dimensions or formats.
     */
    public fun firstDifference(expected: GoldenImage, actual: GoldenImage): GoldenImageDifference? {
        require(expected.width == actual.width && expected.height == actual.height && expected.format == actual.format) {
            "Images must share dimensions and format to be compared byte by byte."
        }
        val expectedBytes = expected.copyCanonicalBytes()
        val actualBytes = actual.copyCanonicalBytes()
        val bytesPerPixel = expected.format.bytesPerPixel
        for (offset in expectedBytes.indices) {
            if (expectedBytes[offset] != actualBytes[offset]) {
                val pixelIndex = offset / bytesPerPixel
                return GoldenImageDifference(
                    byteOffset = offset,
                    x = if (expected.width == 0) 0 else pixelIndex % expected.width,
                    y = if (expected.width == 0) 0 else pixelIndex / expected.width,
                )
            }
        }
        return null
    }
}
