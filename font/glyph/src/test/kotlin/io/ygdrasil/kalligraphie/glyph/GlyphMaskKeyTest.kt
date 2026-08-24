package io.ygdrasil.kalligraphie.glyph

import io.ygdrasil.kalligraphie.font.TypefaceID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class GlyphMaskKeyTest {

    private fun typefaceId(uuid: String): TypefaceID =
        TypefaceID(Uuid.parse(uuid))

    private fun strikeKey(
        sizePx: Float = 16f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        subpixelX: Float = 0f,
        subpixelY: Float = 0f,
        variation: Map<String, Float> = emptyMap(),
        palette: String? = null,
    ): GlyphStrikeKey =
        GlyphStrikeKey(
            typefaceId = typefaceId("550e8400-e29b-41d4-a716-446655441001"),
            sizePx = sizePx,
            scaleX = scaleX,
            scaleY = scaleY,
            subpixelX = subpixelX,
            subpixelY = subpixelY,
            variationCoordinates = variation,
            paletteIdentity = palette,
        )

    private fun maskKey(
        faceIndex: Int = 0,
        outlineSha256: String = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        rasterizerVersion: String = "a8-nonzero-4x4-v1",
        blur: GlyphMaskBlurKey? = null,
        strikeKey: GlyphStrikeKey = strikeKey(),
    ): GlyphMaskKey =
        GlyphMaskKey(
            strikeKey = strikeKey,
            faceIndex = faceIndex,
            sourceOutlineSha256 = outlineSha256,
            rasterizerVersion = rasterizerVersion,
            blur = blur,
        )

    @Test
    fun `mask key separates face subpixel variation palette and blur`() {
        val base = maskKey(
            faceIndex = 0,
            strikeKey = strikeKey(subpixelX = 0f, variation = emptyMap(), palette = null),
            blur = null,
        )

        assertNotEquals(base.sha256(), base.copy(faceIndex = 1).sha256())
        assertNotEquals(base.sha256(), maskKey(strikeKey = strikeKey(subpixelX = 0.25f)).sha256())
        assertNotEquals(
            base.sha256(),
            maskKey(strikeKey = strikeKey(variation = mapOf("wght" to 700f))).sha256(),
        )
        assertNotEquals(
            base.sha256(),
            maskKey(strikeKey = strikeKey(palette = "palette-1")).sha256(),
        )
        assertNotEquals(
            base.sha256(),
            maskKey(blur = GlyphMaskBlurKey(
                style = GlyphMaskBlurStyle.NORMAL,
                sigma = 2f,
                rasterScaleX = 1f,
                rasterScaleY = 1f,
            )).sha256(),
        )
    }

    @Test
    fun `mask key is immutable and deterministic`() {
        val key = maskKey()
        val first = key.sha256()
        val second = key.sha256()
        assertEquals(first, second)

        val copy = key.copy()
        assertEquals(first, copy.sha256())
    }

    @Test
    fun `mask key snapshots mutable strike variation coordinates`() {
        val variation = linkedMapOf("wght" to 400f)
        val key = maskKey(strikeKey = strikeKey(variation = variation))
        val originalPreimage = key.canonicalPreimage()
        val originalHash = key.sha256()

        variation["wght"] = 700f

        assertEquals(mapOf("wght" to 400f), key.strikeKey.variationCoordinates)
        assertEquals(originalPreimage, key.canonicalPreimage())
        assertEquals(originalHash, key.sha256())
    }

    @Test
    fun `mask key hash code and hash map lookup survive caller mutation`() {
        val variation = linkedMapOf("wght" to 400f)
        val key = maskKey(strikeKey = strikeKey(variation = variation))
        val originalHashCode = key.hashCode()
        val cache = hashMapOf(key to "stored-mask")

        variation["wght"] = 700f

        assertEquals(originalHashCode, key.hashCode())
        assertEquals("stored-mask", cache[key])
    }

    @Test
    fun `mask key copy remains equal and canonically identical after caller mutation`() {
        val variation = linkedMapOf("wght" to 400f)
        val key = maskKey(strikeKey = strikeKey(variation = variation))

        variation["wght"] = 700f
        val copied = key.copy()

        assertEquals(key, copied)
        assertEquals(key.hashCode(), copied.hashCode())
        assertEquals(key.canonicalPreimage(), copied.canonicalPreimage())
        assertEquals(key.sha256(), copied.sha256())
    }

    @Test
    fun `mask key canonical preimage includes all fields`() {
        val blurKey = GlyphMaskBlurKey(
            style = GlyphMaskBlurStyle.OUTER,
            sigma = 3.5f,
            rasterScaleX = 1.5f,
            rasterScaleY = 1.5f,
        )
        val key = maskKey(
            faceIndex = 2,
            blur = blurKey,
            strikeKey = strikeKey(
                subpixelX = 0.125f,
                variation = mapOf("wght" to 500f, "wdth" to 75f),
                palette = "cpal.palette.2",
            ),
        )
        val preimage = key.canonicalPreimage()
        assertTrue(preimage.contains("\"faceIndex\": 2"))
        assertTrue(preimage.contains("\"strikeKey\""))
        assertTrue(preimage.contains("\"sigma\": "))
        assertTrue(preimage.contains("OUTER"))
        assertTrue(preimage.contains("\"sourceOutlineSha256\""))
        assertTrue(preimage.contains("\"rasterizerVersion\""))
        assertTrue(preimage.contains("\"blur\""))
    }

    @Test
    fun `mask key serializes null blur as JSON null`() {
        val key = maskKey(blur = null)
        val preimage = key.canonicalPreimage()
        assertTrue(preimage.contains("\"blur\": null"))
        assertTrue(!preimage.contains("\"sigma\""))
    }

    @Test
    fun `mask key requires valid face index`() {
        maskKey(faceIndex = 0)
        maskKey(faceIndex = 999)

        try {
            maskKey(faceIndex = -1)
            assertTrue(false, "Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `mask key requires finite positive scale on blur`() {
        GlyphMaskBlurKey(
            style = GlyphMaskBlurStyle.NORMAL,
            sigma = 0f,
            rasterScaleX = 1f,
            rasterScaleY = 1f,
        )

        try {
            GlyphMaskBlurKey(
                style = GlyphMaskBlurStyle.NORMAL,
                sigma = -1f,
                rasterScaleX = 1f,
                rasterScaleY = 1f,
            )
            assertTrue(false, "Expected IllegalArgumentException for negative sigma")
        } catch (_: IllegalArgumentException) {
        }

        try {
            GlyphMaskBlurKey(
                style = GlyphMaskBlurStyle.NORMAL,
                sigma = 1f,
                rasterScaleX = Float.NaN,
                rasterScaleY = 1f,
            )
            assertTrue(false, "Expected IllegalArgumentException for NaN scale")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `mask key requires lowercase hexadecimal source outline hash`() {
        maskKey(outlineSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

        try {
            maskKey(outlineSha256 = "")
            assertTrue(false, "Expected IllegalArgumentException for empty hash")
        } catch (_: IllegalArgumentException) {
        }

        try {
            maskKey(outlineSha256 = "not-a-valid-sha256-hash-value-here!!!")
            assertTrue(false, "Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `blur key compares correctly`() {
        val a = GlyphMaskBlurKey(GlyphMaskBlurStyle.NORMAL, 2f, 1f, 1f)
        val b = GlyphMaskBlurKey(GlyphMaskBlurStyle.NORMAL, 2f, 1f, 1f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())

        val diff = a.copy(style = GlyphMaskBlurStyle.SOLID)
        assertNotEquals(a, diff)
    }

    @Test
    fun `mask key without blur produces consistent sha256`() {
        val key = maskKey(blur = null)
        val sha = key.sha256()
        assertEquals(64, sha.length)
        assertTrue(sha.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(sha, key.sha256())
    }

    @Test
    fun `two masks with distinct outlines produce distinct sha256 on same strike key`() {
        val strike = strikeKey()
        val keyA = maskKey(
            outlineSha256 = "1111111111111111111111111111111111111111111111111111111111111111",
            strikeKey = strike,
        )
        val keyB = keyA.copy(
            sourceOutlineSha256 = "2222222222222222222222222222222222222222222222222222222222222222",
        )
        assertNotEquals(keyA.sha256(), keyB.sha256())
    }
}
