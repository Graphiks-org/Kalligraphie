package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals

class DumpImageTest {
    @Test
    fun a8CanvasEncodesAnEmptyPgm() {
        val canvas = A8Canvas(width = 2, height = 2)
        assertEquals(
            "P5\n2 2\n255\n" + "\u0000\u0000\u0000\u0000",
            canvas.toPgm().toString(Charsets.ISO_8859_1),
        )
    }

    @Test
    fun drawCoverageFlipsVerticallyAndScalesInk() {
        val image = A8Image(width = 1, height = 2, left = 0, top = -1, pixels = byteArrayOf(1, 2))
        val canvas = A8Canvas(width = 1, height = 5)
        canvas.drawCoverage(image, penX = 0, baselineY = 4, ink = 255)
        assertEquals(0, canvas.sample(0, 0))
        assertEquals(0, canvas.sample(0, 1))
        assertEquals(0, canvas.sample(0, 2))
        assertEquals(2, canvas.sample(0, 3))
        assertEquals(1, canvas.sample(0, 4))

        val half = A8Canvas(width = 1, height = 2)
        half.drawCoverage(A8Image(1, 1, 0, 0, byteArrayOf(-128)), penX = 0, baselineY = 1, ink = 255)
        assertEquals(128, half.sample(0, 0))
    }

    @Test
    fun drawCoverageSkipsPixelsOutsideTheCanvas() {
        val image = A8Image(width = 1, height = 1, left = -1, top = 0, pixels = byteArrayOf(-1))
        val canvas = A8Canvas(width = 1, height = 1)
        canvas.drawCoverage(image, penX = 0, baselineY = 1)
        assertEquals(0, canvas.sample(0, 0))
    }

    @Test
    fun rgbaCanvasStartsWhiteAndCompositesOverIt() {
        val canvas = RgbaCanvas(width = 1, height = 1)
        assertEquals(0xFFFFFFFF.toInt(), canvas.pixel(0, 0))
        canvas.drawColor(Rgba8Image(1, 1, 0, 0, byteArrayOf(10, 20, 30, -128)), penX = 0, baselineY = 1)
        assertEquals(0xFF84898E.toInt(), canvas.pixel(0, 0))
    }

    @Test
    fun rgbaCanvasDrawsABitmapWithoutFlipping() {
        val image = Rgba8Image(1, 2, 0, 0, byteArrayOf(1, 2, 3, -1, 4, 5, 6, -1))
        val canvas = RgbaCanvas(width = 1, height = 2)
        canvas.drawBitmap(image, x = 0, y = 0)
        assertEquals(0xFF010203.toInt(), canvas.pixel(0, 0))
        assertEquals(0xFF040506.toInt(), canvas.pixel(0, 1))
    }

    @Test
    fun rgbCanvasEncodesAPpm() {
        val canvas = RgbaCanvas(width = 1, height = 1)
        assertEquals("P6\n1 1\n255\n", canvas.toPpm().toString(Charsets.ISO_8859_1).substring(0, 11))
    }

    @Test
    fun standaloneEncodersKeepTheRawImages() {
        val a8 = A8Image(1, 1, 0, 0, byteArrayOf(7))
        assertEquals("P5\n1 1\n255\n\u0007", pgm(a8).toString(Charsets.ISO_8859_1))
        val rgba = Rgba8Image(1, 1, 0, 0, byteArrayOf(10, 20, 30, -1))
        assertEquals("P6\n1 1\n255\n\u000A\u0014\u001E", ppm(rgba).toString(Charsets.ISO_8859_1))
    }
}
