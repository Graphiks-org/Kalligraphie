@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.VerticalGlyphMetrics
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader

class VariationMetricsTest {
    @Test
    fun appliesHvarAdvanceDeltaAtANonDefaultLocation() {
        val prepared = preparedFont(
            glyphCount = 2,
            hmtx = hmtx(listOf(500 to 10, 600 to 20)),
            hhea = hhea(numberOfHMetrics = 2),
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "HVAR" to hvarTable(advanceDeltas = intArrayOf(0, 86)),
            ),
        )

        val default = metricsFor(prepared, glyphId = 1, wght = null)
        assertEquals(600, default.advanceWidthDesignUnits)
        assertEquals(20, default.leftSideBearingDesignUnits)

        val varied = metricsFor(prepared, glyphId = 1, wght = 1f)
        assertEquals(686, varied.advanceWidthDesignUnits)
        assertEquals(20, varied.leftSideBearingDesignUnits)
    }

    @Test
    fun appliesVvarAdvanceHeightDeltaAtANonDefaultLocation() {
        val prepared = preparedFont(
            glyphCount = 2,
            vmtx = vmtx(listOf(1000 to 100, 1000 to 200)),
            vhea = vhea(numberOfLongVerMetrics = 2),
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "VVAR" to vvarTable(advanceHeightDeltas = intArrayOf(0, -40)),
            ),
        )

        assertEquals(1000f, verticalMetricsFor(prepared, glyphId = 1, wght = null).advanceHeight.value)
        assertEquals(960f, verticalMetricsFor(prepared, glyphId = 1, wght = 1f).advanceHeight.value)
    }

    @Test
    fun leavesMetricsUnchangedWithoutAVariationTable() {
        val prepared = preparedFont(
            glyphCount = 2,
            hmtx = hmtx(listOf(500 to 10, 600 to 20)),
            hhea = hhea(numberOfHMetrics = 2),
            extraTables = mapOf("fvar" to singleAxisFvarTable()),
        )

        val varied = metricsFor(prepared, glyphId = 1, wght = 1f)
        assertEquals(600, varied.advanceWidthDesignUnits)
        assertEquals(20, varied.leftSideBearingDesignUnits)
    }

    @Test
    fun usesThePhantomAdvanceDeltaWithoutHvar() {
        val prepared = preparedFont(
            glyphCount = 2,
            hmtx = hmtx(listOf(500 to 10, 600 to 20)),
            hhea = hhea(numberOfHMetrics = 2),
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "gvar" to gvarTable(
                    axisCount = 1,
                    glyphRecords = listOf(
                        ByteArray(0),
                        gvarGlyphRecord(
                            peak = listOf(1.0),
                            xDeltas = intArrayOf(0, 0, 40, 0, 0),
                            yDeltas = intArrayOf(0, 0, 0, 0, 0),
                        ),
                    ),
                ),
            ),
        )

        val varied = metricsFor(prepared, glyphId = 1, wght = 1f)
        assertEquals(640, varied.advanceWidthDesignUnits)
        assertEquals(20, varied.leftSideBearingDesignUnits)
    }

    @Test
    fun usesTheVerticalPhantomAdvanceDeltaWithoutVvar() {
        val prepared = preparedFont(
            glyphCount = 2,
            vmtx = vmtx(listOf(1000 to 100, 1000 to 200)),
            vhea = vhea(numberOfLongVerMetrics = 2),
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "gvar" to gvarTable(
                    axisCount = 1,
                    glyphRecords = listOf(
                        ByteArray(0),
                        gvarGlyphRecord(
                            peak = listOf(1.0),
                            xDeltas = intArrayOf(0, 0, 0, 0, 0),
                            yDeltas = intArrayOf(0, 0, 0, 30, -50),
                        ),
                    ),
                ),
            ),
        )

        val varied = verticalMetricsFor(prepared, glyphId = 1, wght = 1f)
        assertEquals(1080f, varied.advanceHeight.value)
        assertEquals(200f, varied.topSideBearing.value)
    }

    @Test
    fun fallsBackToBaseMetricsWithoutAGvarGlyphRecord() {
        val prepared = preparedFont(
            glyphCount = 2,
            hmtx = hmtx(listOf(500 to 10, 600 to 20)),
            hhea = hhea(numberOfHMetrics = 2),
            extraTables = mapOf(
                "fvar" to singleAxisFvarTable(),
                "gvar" to gvarTable(axisCount = 1, glyphRecords = listOf(ByteArray(0), ByteArray(0))),
            ),
        )

        val varied = metricsFor(prepared, glyphId = 1, wght = 1f)
        assertEquals(600, varied.advanceWidthDesignUnits)
        assertEquals(20, varied.leftSideBearingDesignUnits)
    }

    private fun metricsFor(prepared: PreparedTrueTypeFont, glyphId: Int, wght: Float?): GlyphMetrics =
        assertIs<FontOperationResult.Success<GlyphMetrics>>(
            prepared.readGlyphMetrics(GlyphId(glyphId), LAYOUT_SIZE, axes(wght)),
        ).value

    private fun verticalMetricsFor(
        prepared: PreparedTrueTypeFont,
        glyphId: Int,
        wght: Float?,
    ): VerticalGlyphMetrics =
        assertIs<FontOperationResult.Success<VerticalGlyphMetrics>>(
            prepared.readVerticalGlyphMetrics(GlyphId(glyphId), LAYOUT_SIZE, axes(wght)),
        ).value

    private fun axes(wght: Float?): List<FontAxisCoordinate> =
        wght?.let { listOf(FontAxisCoordinate("wght", it)) } ?: emptyList()

    private fun preparedFont(
        glyphCount: Int,
        hmtx: ByteArray = hmtx(List(glyphCount) { 500 to 0 }),
        hhea: ByteArray = hhea(numberOfHMetrics = glyphCount),
        vmtx: ByteArray? = null,
        vhea: ByteArray? = null,
        extraTables: Map<String, ByteArray> = emptyMap(),
    ): PreparedTrueTypeFont {
        val glyph = singlePointGlyph(0, 0)
        val glyf = ByteArray(glyph.size * glyphCount)
        repeat(glyphCount) { index -> glyph.copyInto(glyf, index * glyph.size) }
        val loca = locaFormat0(*IntArray(glyphCount + 1) { it * glyph.size })
        val bytes = minimalTrueTypeFont(
            glyphCount = glyphCount,
            tables = mapOf("loca" to loca, "glyf" to glyf),
            extraTables = buildMap {
                put("hhea", hhea)
                put("hmtx", hmtx)
                if (vmtx != null && vhea != null) {
                    put("vmtx", vmtx)
                    put("vhea", vhea)
                }
                putAll(extraTables)
            },
        )
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("variation-metrics.ttf"))),
        ).value
        return PreparedTrueTypeFont(FontSource(bytes, FontSourceProvenance("variation-metrics.ttf")), parsed)
    }

    private companion object {
        const val LAYOUT_SIZE = 2048f
    }
}

/**
 * Builds a one-axis format-1 ItemVariationStore with region `(0, 1, 1)` and one item variation
 * data subtable. Each row is one int16 delta per region.
 */
private fun itemVariationStore(itemDeltas: List<IntArray>): ByteArray {
    val regionListOffset = 12
    val regionListSize = 4 + 6
    val itemDataOffset = regionListOffset + regionListSize
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1)
    u32(regionListOffset)
    u16(1)
    u32(itemDataOffset)
    u16(1)
    u16(1)
    u16(0x0000); u16(0x4000); u16(0x4000)
    u16(itemDeltas.size)
    u16(itemDeltas.firstOrNull()?.size ?: 0)
    u16(1)
    u16(0)
    itemDeltas.forEach { row -> row.forEach { u16(it and 0xFFFF) } }
    return out.toByteArray()
}

/** Builds an HVAR 1.0 table with one implicit advance-width delta row per glyph. */
private fun hvarTable(advanceDeltas: IntArray): ByteArray {
    val store = itemVariationStore(itemDeltas = advanceDeltas.map { intArrayOf(it) })
    val storeOffset = 20
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1); u16(0)
    u32(storeOffset)
    u32(0)
    u32(0)
    u32(0)
    store.forEach { u8(it.toInt() and 0xFF) }
    return out.toByteArray()
}

/** Builds a VVAR 1.0 table with one implicit advance-height delta row per glyph. */
private fun vvarTable(advanceHeightDeltas: IntArray): ByteArray {
    val store = itemVariationStore(itemDeltas = advanceHeightDeltas.map { intArrayOf(it) })
    val storeOffset = 24
    val out = ArrayList<Byte>()
    fun u8(value: Int) { out += (value and 0xFF).toByte() }
    fun u16(value: Int) { u8(value shr 8); u8(value) }
    fun u32(value: Int) { u8(value shr 24); u8(value shr 16); u8(value shr 8); u8(value) }
    u16(1); u16(0)
    u32(storeOffset)
    u32(0)
    u32(0)
    u32(0)
    u32(0)
    store.forEach { u8(it.toInt() and 0xFF) }
    return out.toByteArray()
}
