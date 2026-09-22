@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader

/**
 * Platform-agnostic companion to the fixture-based `VariableFontInkBoundsTest`.
 *
 * The fixture oracle lives in `jvmTest` because Kotlin/Native `commonTest` cannot read the
 * `test-fixtures` resources (only `jvmTest` wires `resources.srcDir(rootProject.file("test-fixtures"))`).
 * This class pins the same behaviour with synthetic TrueType faces so it compiles and runs on every
 * target, including iOS.
 */
class SyntheticVariableFontInkBoundsTest {
    /**
     * A one-point contour at `(10, 20)` whose `gvar` deltas move the point to `(60, 50)` at normalized
     * `wght = 1.0`. The static `glyf` header records `(10, 20, 10, 20)`; the instanced ink bounds are
     * `(60, 50, 60, 50)`.
     */
    @Test
    fun trueTypeInkBoundsAreTheVariedOutlineBoundsAtANonDefaultLocation() {
        val prepared = syntheticVariableFont()
        assertEquals(DesignBounds(10, 20, 10, 20), metricsFor(prepared, wght = null).bounds)
        assertEquals(DesignBounds(60, 50, 60, 50), metricsFor(prepared, wght = 1f).bounds)
    }

    @Test
    fun theDefaultInstanceStillUsesTheStaticHeaderBounds() {
        val prepared = syntheticVariableFont()
        assertEquals(DesignBounds(10, 20, 10, 20), metricsFor(prepared, wght = null).bounds)
        assertEquals(DesignBounds(10, 20, 10, 20), metricsFor(prepared, wght = 0f).bounds)
    }

    /**
     * The memo is the mechanism that keeps the repeated reads of the paragraph projection path from
     * re-materializing the outline. `MetricsReader` passes the supplied bounds through to
     * `GlyphMetrics.bounds` unchanged, so an identity match across two calls is a black-box proof that
     * the second read was served from the per-`(glyphId, location)` cache rather than re-decoded (the
     * static header path constructs a fresh `DesignBounds` each call).
     */
    @Test
    fun theInstancedBoundsAreMemoizedPerGlyphAndLocation() {
        val prepared = syntheticVariableFont()
        assertSame(metricsFor(prepared, wght = 1f).bounds, metricsFor(prepared, wght = 1f).bounds)
    }

    /**
     * The memo is capped at 512 `(glyphId, ordered location)` pairs per face and evicts the eldest
     * insertion once the cap is exceeded. At exactly the cap the eldest pair survives, and the entry
     * past it evicts that pair, so a re-read returns value-equal but not identity-equal bounds — the
     * black-box signature of the cap, since an unbounded cache would keep the original instance.
     */
    @Test
    fun theInstancedBoundsCacheEvictsItsEldestPairBeyondTheCap() {
        val prepared = syntheticVariableFont()
        val locations = (1..513).map { it.toFloat() / 1024f }
        val firstBounds = metricsFor(prepared, wght = locations.first()).bounds
        locations.subList(1, 512).forEach { metricsFor(prepared, wght = it) }
        assertSame(firstBounds, metricsFor(prepared, wght = locations.first()).bounds)
        metricsFor(prepared, wght = locations[512])
        val recomputed = metricsFor(prepared, wght = locations.first()).bounds
        assertEquals(firstBounds, recomputed)
        assertNotSame(firstBounds, recomputed)
    }

    /**
     * The new failure surface of feeding the instanced bounds into the metric route: with `HVAR`
     * present, the non-default metric path used to read only the `HVAR` deltas and never decoded the
     * outline (`PreparedTrueTypeFont.trueTypeVariationDeltas` short-circuited on a non-null variation
     * table). It now materializes the outline under `metricsOutlineProfile()` to obtain the ink
     * bounds, so a glyph whose `glyf` header is readable but whose outline is malformed now fails at a
     * non-default location, while the default instance still succeeds from the header.
     */
    @Test
    fun aNonDefaultMetricFailsWhenTheInstancedOutlineCannotBeRead() {
        val prepared = syntheticVariableFont(malformedMetricsGlyph = true)
        val default = assertIs<FontOperationResult.Success<GlyphMetrics>>(
            prepared.readGlyphMetrics(GlyphId(1), LAYOUT_SIZE),
        ).value
        assertEquals(DesignBounds(5, 6, 7, 8), default.bounds)

        val varied = prepared.readGlyphMetrics(GlyphId(1), LAYOUT_SIZE, listOf(FontAxisCoordinate("wght", 1f)))
        assertIs<FontOperationResult.Failure>(varied)
    }

    /**
     * Glyph 1 is a valid one-point contour at `(10, 20)` with a `gvar` delta moving it to `(60, 50)`.
     * When [malformedMetricsGlyph] is set, glyph 1 is instead a ten-byte header (`numberOfContours = 1`,
     * bbox `(5, 6, 7, 8)`) whose contour data is missing, and the face also carries an `HVAR` table so
     * the pre-fix metric path had a non-outline route to succeed through.
     */
    private fun syntheticVariableFont(malformedMetricsGlyph: Boolean = false): PreparedTrueTypeFont {
        val header = singlePointGlyph(0, 0)
        val body = if (malformedMetricsGlyph) {
            byteArrayOf(0, 1, 0, 5, 0, 6, 0, 7, 0, 8)
        } else {
            singlePointGlyph(10, 20)
        }
        val glyf = header + body
        val loca = locaFormat0(0, header.size, glyf.size)
        val extraTables = buildMap<String, ByteArray> {
            put("fvar", singleAxisFvarTable())
            if (malformedMetricsGlyph) {
                put("HVAR", inkBoundsHvar(intArrayOf(0, 86)))
            } else {
                put(
                    "gvar",
                    gvarTable(
                        axisCount = 1,
                        glyphRecords = listOf(
                            ByteArray(0),
                            gvarGlyphRecord(
                                peak = listOf(1.0),
                                xDeltas = intArrayOf(50, 0, 0, 0, 0),
                                yDeltas = intArrayOf(30, 0, 0, 0, 0),
                            ),
                        ),
                    ),
                )
            }
        }
        val bytes = minimalTrueTypeFont(
            glyphCount = 2,
            tables = mapOf("loca" to loca, "glyf" to glyf),
            extraTables = extraTables,
        )
        val parsed = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
            SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("synthetic-ink-bounds"))),
        ).value
        return PreparedTrueTypeFont(FontSource(bytes, FontSourceProvenance("synthetic-ink-bounds")), parsed)
    }

    private fun metricsFor(prepared: PreparedTrueTypeFont, wght: Float?): GlyphMetrics {
        val axes = wght?.let { listOf(FontAxisCoordinate("wght", it)) } ?: emptyList()
        return assertIs<FontOperationResult.Success<GlyphMetrics>>(
            prepared.readGlyphMetrics(GlyphId(1), LAYOUT_SIZE, axes),
        ).value
    }

    private companion object {
        /** `minimalTrueTypeFont`'s `head.unitsPerEm`; design-unit assertions do not depend on it. */
        const val LAYOUT_SIZE = 2048f
    }
}

/**
 * Builds an `HVAR` 1.0 table with one implicit advance-width delta row per glyph and one axis region
 * `(0, 1, 1)`. Duplicated from `VariationMetricsTest`'s file-private builder so this file can depend
 * on it without widening that test's visibility.
 */
private fun inkBoundsHvar(advanceDeltas: IntArray): ByteArray {
    val store = inkBoundsItemVariationStore(advanceDeltas.map { intArrayOf(it) })
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

private fun inkBoundsItemVariationStore(itemDeltas: List<IntArray>): ByteArray {
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
