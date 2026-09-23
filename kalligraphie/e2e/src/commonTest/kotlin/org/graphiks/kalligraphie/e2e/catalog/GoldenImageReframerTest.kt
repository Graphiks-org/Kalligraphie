package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.GoldenOrientation

class GoldenImageReframerTest {
    @Test
    fun theSourceImageLandsAtTheOffset() {
        val source = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4))
        val framed = GoldenImageReframer.reframe(source, width = 4, height = 4, offsetX = 1, offsetY = 2)
        assertEquals(4, framed.width)
        assertEquals(4, framed.height)
        assertContentEquals(
            byteArrayOf(
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 1, 2, 0,
                0, 3, 4, 0,
            ),
            framed.copyCanonicalBytes(),
        )
    }

    @Test
    fun clippingTheSourceIsRefused() {
        val source = GoldenImage.alpha8(3, 3, ByteArray(9))
        assertFailsWith<IllegalArgumentException> {
            GoldenImageReframer.reframe(source, width = 2, height = 2, offsetX = 0, offsetY = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            GoldenImageReframer.reframe(source, width = 3, height = 3, offsetX = 1, offsetY = 0)
        }
    }

    @Test
    fun aZeroSizedFrameIsRefused() {
        // An empty source makes the containment guard trivially true, so only positivity can throw.
        val source = GoldenImage.alpha8(0, 0, ByteArray(0))
        assertFailsWith<IllegalArgumentException> {
            GoldenImageReframer.reframe(source, width = 0, height = 1, offsetX = 0, offsetY = 0)
        }
    }

    @Test
    fun theFrameKeepsTheSourceOrientation() {
        val source = GoldenImage.alpha8(2, 2, byteArrayOf(1, 2, 3, 4), GoldenOrientation.DESIGN)
        val framed = GoldenImageReframer.reframe(source, width = 3, height = 3, offsetX = 1, offsetY = 1)
        assertEquals(GoldenOrientation.DESIGN, framed.orientation)
        assertContentEquals(
            byteArrayOf(
                0, 0, 0,
                0, 1, 2,
                0, 3, 4,
            ),
            framed.copyCanonicalBytes(),
        )
    }
}
