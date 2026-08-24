package io.ygdrasil.kalligraphie.glyph

import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GlyphMaskBlurTest {
    @Test
    fun `all four blur styles use exact gaussian padding and mask algebra`() {
        val source = centerMask()
        val normal = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.NORMAL, 1f, 1f, 1f),
        )
        val solid = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.SOLID, 1f, 1f, 1f),
        )
        val outer = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.OUTER, 1f, 1f, 1f),
        )
        val inner = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.INNER, 1f, 1f, 1f),
        )

        assertEquals(3, normal.paddingPx)
        assertEquals(3, solid.paddingPx)
        assertEquals(3, outer.paddingPx)
        assertEquals(3, inner.paddingPx)
        assertEquals(9, normal.mask.width)
        assertEquals(9, normal.mask.height)
        val center = 4 * normal.mask.rowBytes + 4
        assertTrue(solid.mask.pixels[center] >= normal.mask.pixels[center])
        assertEquals(0, outer.mask.pixels[center])
        assertTrue(inner.mask.pixels[center] > 0)
        assertTrue(outer.mask.pixels.any { it > 0 })
    }

    @Test
    fun `padding uses sigma times the largest absolute raster scale`() {
        val cases = listOf(
            Triple(0f, 4f to 5f, 0),
            Triple(0.1f, 2f to 3f, 1),
            Triple(1.25f, 2f to 0.5f, ceil(3f * 1.25f * 2f).toInt()),
        )

        cases.forEach { (sigma, scales, expected) ->
            val result = blurGlyphMask(
                centerMask(),
                GlyphMaskBlurKey(
                    style = GlyphMaskBlurStyle.NORMAL,
                    sigma = sigma,
                    rasterScaleX = scales.first,
                    rasterScaleY = scales.second,
                ),
            )
            assertEquals(expected, result.paddingPx)
        }
    }

    @Test
    fun `separable convolution rounds once after the vertical pass and is deterministic`() {
        val source = A8GlyphMask(
            glyphId = 7,
            width = 3,
            height = 2,
            rowBytes = 4,
            pixels = listOf(
                0, 64, 255, 99,
                255, 128, 0, 88,
            ),
            sourceOutlineSha256 = "1".repeat(64),
        )
        val key = GlyphMaskBlurKey(GlyphMaskBlurStyle.NORMAL, 0.75f, 1.5f, 1f)

        val first = blurGlyphMask(source, key)
        val second = blurGlyphMask(source, key)

        assertEquals(first, second)
        assertEquals(directTwoDimensionalGaussian(source, key), first.mask.pixels)
    }

    @Test
    fun `blur output does not retain source pixels and published pixels are immutable`() {
        val pixels = mutableListOf(
            0, 0, 0,
            0, 255, 0,
            0, 0, 0,
        )
        val source = A8GlyphMask(3, 3, 3, pixels = pixels)
        val result = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.NORMAL, 1f, 1f, 1f),
        )
        val before = result.mask.pixels.toList()

        pixels.fill(255)
        assertEquals(before, result.mask.pixels)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (result.mask.pixels as MutableList<Int>)[0] = 255
        }
    }

    @Test
    fun `blur style remains part of exact output identity`() {
        val source = centerMask()
        val normal = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.NORMAL, 1f, 1f, 1f),
        )
        val solid = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.SOLID, 1f, 1f, 1f),
        )

        assertNotEquals(normal.mask.pixels, solid.mask.pixels)
    }

    @Test
    fun `partial source alpha uses exact integer style algebra`() {
        val source = A8GlyphMask(
            glyphId = 9,
            width = 1,
            height = 1,
            pixels = listOf(73),
            sourceOutlineSha256 = "9".repeat(64),
        )
        val normal = blurGlyphMask(
            source,
            GlyphMaskBlurKey(GlyphMaskBlurStyle.NORMAL, 0.5f, 1f, 1f),
        )
        val center = normal.paddingPx * normal.mask.rowBytes + normal.paddingPx
        val blurredAlpha = normal.mask.pixels[center]
        val sourceAlpha = 73

        fun expected(style: GlyphMaskBlurStyle): Int = when (style) {
            GlyphMaskBlurStyle.NORMAL -> blurredAlpha
            GlyphMaskBlurStyle.SOLID ->
                sourceAlpha + ((255 - sourceAlpha) * blurredAlpha + 127) / 255
            GlyphMaskBlurStyle.OUTER ->
                (blurredAlpha * (255 - sourceAlpha) + 127) / 255
            GlyphMaskBlurStyle.INNER ->
                (blurredAlpha * sourceAlpha + 127) / 255
        }

        GlyphMaskBlurStyle.entries.forEach { style ->
            val actual = blurGlyphMask(
                source,
                GlyphMaskBlurKey(style, 0.5f, 1f, 1f),
            ).mask.pixels[center]
            assertEquals(expected(style), actual, style.name)
        }
    }

    @Test
    fun `zero sigma is an exact no-op for every blur style`() {
        val source = A8GlyphMask(
            glyphId = 10,
            width = 2,
            height = 1,
            left = 3,
            top = 4,
            pixels = listOf(73, 211),
            sourceOutlineSha256 = "a".repeat(64),
        )

        GlyphMaskBlurStyle.entries.forEach { style ->
            val result = blurGlyphMask(
                source,
                GlyphMaskBlurKey(style, 0f, 2f, 3f),
            )
            assertEquals(0, result.paddingPx)
            assertEquals(source, result.mask, style.name)
        }
    }

    @Test
    fun `empty mask with unrepresentable blur radius is rejected before bearing arithmetic`() {
        val empty = A8GlyphMask(
            glyphId = 11,
            width = 0,
            height = 0,
            left = Int.MIN_VALUE,
            top = Int.MAX_VALUE,
            pixels = emptyList(),
            sourceOutlineSha256 = "b".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            blurGlyphMask(
                empty,
                GlyphMaskBlurKey(
                    GlyphMaskBlurStyle.NORMAL,
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                    Float.MAX_VALUE,
                ),
            )
        }
    }

    @Test
    fun `blur rejects right and bottom overflow before publishing Int bounds`() {
        val nearRight = A8GlyphMask(
            glyphId = 12,
            width = 1,
            height = 1,
            left = Int.MAX_VALUE - 1,
            top = 0,
            pixels = listOf(255),
            sourceOutlineSha256 = "c".repeat(64),
        )
        val nearBottom = nearRight.copy(
            left = 0,
            top = Int.MAX_VALUE - 1,
        )
        val key = GlyphMaskBlurKey(
            GlyphMaskBlurStyle.NORMAL,
            1f,
            1f,
            1f,
        )

        listOf(nearRight, nearBottom).forEach { source ->
            assertFailsWith<IllegalArgumentException> {
                blurGlyphMask(source, key)
            }
        }
    }

    private fun centerMask(): A8GlyphMask = A8GlyphMask(
        glyphId = 5,
        width = 3,
        height = 3,
        pixels = listOf(
            0, 0, 0,
            0, 255, 0,
            0, 0, 0,
        ),
        sourceOutlineSha256 = "0".repeat(64),
    )

    /**
     * Independent 2D reference: unlike production it does not use separable intermediate rows, so
     * equality proves that no horizontal quantization was introduced before the vertical pass.
     */
    private fun directTwoDimensionalGaussian(
        source: A8GlyphMask,
        key: GlyphMaskBlurKey,
    ): List<Int> {
        val sigma = key.sigma.toDouble() *
            max(abs(key.rasterScaleX.toDouble()), abs(key.rasterScaleY.toDouble()))
        val padding = ceil(3.0 * sigma).toInt()
        if (padding == 0) {
            return List(source.width * source.height) { index ->
                val y = index / source.width
                val x = index % source.width
                source.pixels[y * source.rowBytes + x]
            }
        }
        val weights = (-padding..padding).map { offset ->
            exp(-(offset * offset).toDouble() / (2.0 * sigma * sigma))
        }
        val normalized = weights.map { weight -> weight / weights.sum() }
        val outputWidth = source.width + padding * 2
        val outputHeight = source.height + padding * 2
        return List(outputWidth * outputHeight) { index ->
            val outputX = index % outputWidth
            val outputY = index / outputWidth
            var alpha = 0.0
            for (sourceY in 0 until source.height) {
                val weightYIndex = outputY - (sourceY + padding) + padding
                if (weightYIndex !in normalized.indices) continue
                for (sourceX in 0 until source.width) {
                    val weightXIndex = outputX - (sourceX + padding) + padding
                    if (weightXIndex !in normalized.indices) continue
                    alpha += source.pixels[sourceY * source.rowBytes + sourceX] *
                        normalized[weightXIndex] *
                        normalized[weightYIndex]
                }
            }
            alpha.roundToInt().coerceIn(0, 255)
        }
    }
}
