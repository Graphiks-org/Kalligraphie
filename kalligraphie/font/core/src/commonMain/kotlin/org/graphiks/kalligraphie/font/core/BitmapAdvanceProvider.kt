@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.slice

/**
 * Builds the `hmtx`-only horizontal advance resolver shared by the sbix capability prefilter and
 * the sbix route reader.
 *
 * OpenType's sbix contract defines horizontal advances through `hmtx`: every strike record relies
 * on the face's `hmtx` entry, and `glyf` is not required to resolve them. Reading
 * `hhea.numberOfHMetrics` and the `hmtx` longHorMetric records directly therefore keeps the sbix
 * route independent of outline data. The general TrueType metrics path resolves composite-glyph
 * advances through `glyf` (the `USE_MY_METRICS` rule), which an sbix-only face is not required to
 * provide.
 *
 * Each `hmtx` longHorMetric is four bytes (`advanceWidth` u16, `leftSideBearing` i16); the advance
 * for a glyph is the `advanceWidth` of record `min(glyphId, numberOfHMetrics - 1)`.
 *
 * The resolver is lazy: it retains only the validated `hmtx` bytes and reads one four-byte metric
 * record per call rather than materializing an advance array. It never throws. A missing or
 * unusable `hhea`/`hmtx` table, an `hmtx` record that does not fit the table, an out-of-domain
 * `numberOfHMetrics`, or a glyph outside the face resolves to `null`.
 *
 * @param resource captured embedded source that owns the raw `hmtx` bytes.
 * @param parsedFont parsed table directory for the same face.
 * @return the lazy resolver, or `null` when this face has no usable `hmtx` advance table at all.
 */
internal fun hmtxAdvanceDesignUnitsProvider(
    resource: PreparedFontResource,
    parsedFont: ParsedTrueTypeFont,
): ((Int) -> Int?)? {
    val glyphCount = parsedFont.metadata.glyphCount
    if (glyphCount <= 0) return null
    val hheaRecord = parsedFont.tableRecords["hhea"] ?: return null
    val hmtxRecord = parsedFont.tableRecords["hmtx"] ?: return null
    val sourceBytes = resource.preparedFont.copySourceBytes()
    val hhea = slice(sourceBytes, hheaRecord) ?: return null
    val numberOfHMetrics = readUInt16(hhea, 34)?.toInt() ?: return null
    if (numberOfHMetrics !in 1..glyphCount) return null
    val hmtx = slice(sourceBytes, hmtxRecord) ?: return null
    return { glyphId ->
        if (glyphId !in 0 until glyphCount) {
            null
        } else {
            val offset = minOf(glyphId, numberOfHMetrics - 1).toLong() * 4L
            if (offset > hmtx.size.toLong() - 4L) null
            else readUInt16(hmtx, offset.toInt())?.toInt()
        }
    }
}
