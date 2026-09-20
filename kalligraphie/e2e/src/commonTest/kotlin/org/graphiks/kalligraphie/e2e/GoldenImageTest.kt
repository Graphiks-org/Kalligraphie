package org.graphiks.kalligraphie.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoldenImageTest {
    @Test
    fun alpha8CopiesAndExposesRowMajorBytes() {
        val pixels = byteArrayOf(1, 2, 3, 4, 5, 6)
        val image = GoldenImage.alpha8(width = 2, height = 3, pixels = pixels)
        pixels[0] = 99
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), image.copyCanonicalBytes().toList())
        assertEquals(2, image.width)
        assertEquals(3, image.height)
        assertEquals(PixelFormat.ALPHA_8, image.format)
    }

    @Test
    fun rgba8RequiresFourBytesPerPixel() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImage.rgba8(width = 1, height = 1, pixels = byteArrayOf(1, 2, 3))
        }
        val image = GoldenImage.rgba8(width = 1, height = 1, pixels = byteArrayOf(1, 2, 3, 4))
        assertEquals(4, image.copyCanonicalBytes().size)
    }

    @Test
    fun alpha8RejectsMismatchedPixelCount() {
        assertFailsWith<IllegalArgumentException> {
            GoldenImage.alpha8(width = 2, height = 2, pixels = byteArrayOf(1, 2, 3))
        }
    }

    @Test
    fun sceneRejectsBlankIdAndNegativeDimensions() {
        assertFailsWith<IllegalArgumentException> {
            GoldenScene(id = " ", family = GoldenSceneFamily.GLYPH_OUTLINE, width = 1, height = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            GoldenScene(id = "scene", family = GoldenSceneFamily.GLYPH_OUTLINE, width = -1, height = 1)
        }
    }
}
