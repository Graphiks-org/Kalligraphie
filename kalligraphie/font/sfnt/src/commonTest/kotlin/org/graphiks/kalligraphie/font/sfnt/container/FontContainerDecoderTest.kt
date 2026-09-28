@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.container

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult

class FontContainerDecoderTest {
    private val limits = WoffDecodeLimits.EMBEDDED

    @Test
    fun passesThroughANonContainerSource() {
        val sfnt = WoffTestFonts.singleTableSfnt()
        val result = assertIs<FontOperationResult.Success<DecodedFont?>>(
            FontContainerDecoder.decode(
                org.graphiks.kalligraphie.api.FontSource(sfnt, org.graphiks.kalligraphie.api.FontSourceProvenance("plain")),
                limits,
            ),
        )
        assertEquals(null, result.value)
    }

    @Test
    fun decodesAWoffSourceToItsKind() {
        val woff = WoffTestFonts.wrapUncompressed(WoffTestFonts.singleTableSfnt())
        val result = assertIs<FontOperationResult.Success<DecodedFont?>>(
            FontContainerDecoder.decode(
                org.graphiks.kalligraphie.api.FontSource(woff, org.graphiks.kalligraphie.api.FontSourceProvenance("wrapped")),
                limits,
            ),
        )
        assertEquals(ContainerKind.WOFF, result.value?.kind)
    }

    @Test
    fun decodesAWoff2SourceToItsKind() {
        val woff2 = Woff2TestFonts.singleTableUntransformed()
        val result = assertIs<FontOperationResult.Success<DecodedFont?>>(
            FontContainerDecoder.decode(
                org.graphiks.kalligraphie.api.FontSource(woff2, org.graphiks.kalligraphie.api.FontSourceProvenance("wrapped2")),
                limits,
            ),
        )
        assertEquals(ContainerKind.WOFF2, result.value?.kind)
    }

    @Test
    fun aRecognizedContainerNeverDecodesToNull() {
        val woff2 = Woff2TestFonts.withCollectionFlavor()
        val failure = assertIs<FontOperationResult.Failure>(
            FontContainerDecoder.decode(
                org.graphiks.kalligraphie.api.FontSource(woff2, org.graphiks.kalligraphie.api.FontSourceProvenance("collection")),
                limits,
            ),
        )
        assertIs<org.graphiks.kalligraphie.api.FontError.UnsupportedContainer>(failure.error)
    }
}
