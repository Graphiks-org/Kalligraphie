package io.ygdrasil.kalligraphie.text

import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import kotlin.test.Test
import kotlin.test.assertEquals

class TextNamespaceTest {
    @Test
    fun exposesTheKalligraphieTextNamespace() {
        assertEquals(
            "io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun",
            ShapedGlyphRun::class.qualifiedName,
        )
    }
}
