package io.ygdrasil.kalligraphie.font

import kotlin.test.Test
import kotlin.test.assertEquals

class FontNamespaceTest {
    @Test
    fun exposesTheKalligraphieFontNamespace() {
        assertEquals(
            "io.ygdrasil.kalligraphie.font.FontSource",
            FontSource::class.qualifiedName,
        )
    }
}
