package org.graphiks.kalligraphie.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FontIdentityContractTest {
    @Test
    fun capturedSourceIdentityIsStableAndDistinguishesOrigin() {
        val sameFactsA = preimage(originPath = "fixtures/specimen.ttf", tableTags = listOf("name", "cmap"))
        val sameFactsB = preimage(originPath = "fixtures/specimen.ttf", tableTags = listOf("cmap", "name", "name"))
        val differentOrigin = preimage(originPath = "fixtures/renamed-specimen.ttf", tableTags = listOf("name", "cmap"))

        assertEquals(sameFactsA.deriveFontSourceID(), sameFactsB.deriveFontSourceID())
        assertNotEquals(sameFactsA.deriveFontSourceID(), differentOrigin.deriveFontSourceID())
    }

    private fun preimage(originPath: String, tableTags: List<String>): FontSourceIdentityPreimage =
        FontSourceIdentityPreimage.fromCapturedBytes(
            kind = FontSourceKind.BUNDLED_FIXTURE,
            declaredName = "specimen.ttf",
            licenseId = "OFL-1.1",
            bytes = byteArrayOf(0x00, 0x01, 0x00, 0x00),
            faceCount = 1,
            tableTags = tableTags,
            parserGeneration = 1,
            originPath = originPath,
        )
}
