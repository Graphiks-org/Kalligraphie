package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics

class VariableFontMetricsTest {
    /** Shared OFL variable TrueType fixture (wght 100/100/900, HVAR present, no VVAR, no MVAR). */
    private val bytes: ByteArray = requireNotNull(
        javaClass.getResourceAsStream("/fonts/noto-sans-jp/NotoSansJP-VerticalFixture.ttf"),
    ).readBytes()

    @Test
    fun variedAdvanceWidthUsesHvarThroughTheFacade() {
        val varied = success(instance(900f).metrics(GlyphId(1)))
        assertEquals(660, varied.advanceWidthDesignUnits)
        assertEquals(11, varied.leftSideBearingDesignUnits)
    }

    @Test
    fun defaultAdvanceWidthIsTheHmtxValue() {
        val default = success(instance(null).metrics(GlyphId(1)))
        assertEquals(574, default.advanceWidthDesignUnits)
        assertEquals(11, default.leftSideBearingDesignUnits)
    }

    @Test
    fun fontMetricsReturnTheOs2Defaults() {
        val font = success(instance(900f).fontMetrics())
        assertEquals(880f, font.ascender)
        assertEquals(-120f, font.descender)
        assertEquals(0f, font.lineGap)
        assertEquals(-125f, font.underlinePosition)
        assertEquals(50f, font.underlineThickness)
        assertEquals(543f, font.xHeight)
        assertEquals(733f, font.capHeight)
    }

    private fun instance(wght: Float?): FontInstance {
        val descriptor = FontInstanceDescriptor(
            variation = wght?.let { FontVariationCoordinates(listOf(FontVariationCoordinate("wght", it))) },
        )
        return success(openFace().instantiate(descriptor))
    }

    private fun openFace(): FontFace {
        val catalog = success(Kalligraphie.embedded(bytes, FontSourceProvenance("NotoSansJP-VerticalFixture")))
        val faceId = catalog.faces.single().id
        return success(catalog.resolveFace(faceId, FontAccessRequirementsSnapshot.layoutOnly()))
    }

    private fun <T> success(result: FontOperationResult<T>): T = when (result) {
        is FontOperationResult.Success -> result.value
        is FontOperationResult.Failure -> error("Unexpected failure: ${result.error}")
        is FontOperationResult.Cancelled -> error("Unexpected cancellation")
    }
}
