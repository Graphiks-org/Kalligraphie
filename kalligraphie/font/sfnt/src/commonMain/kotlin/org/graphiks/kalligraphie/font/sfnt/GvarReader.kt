@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Decoded OpenType `gvar` table: shared tuples, glyph variation-data offsets, and the serialized
 * per-glyph data region. Immutable and safe to share.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class GvarData internal constructor(
    private val table: ByteArray,
    /** Number of normalized axes tuple coordinates are expressed in. */
    public val axisCount: Int,
    /** Number of glyphs with a variation-data slot. */
    public val glyphCount: Int,
    private val sharedTuples: List<DoubleArray>,
    private val glyphOffsets: IntArray,
    private val glyphDataStart: Int,
    private val maxTupleVariations: Int,
    private val maxPointsPerVariation: Int,
)

/**
 * Decodes the OpenType `gvar` table version 1.0.
 *
 * Every offset is bounds-checked against [table]; the operation is all-or-nothing and never
 * publishes partial decoded state. `axisCount` and `glyphCount` must match the values derived from
 * `fvar` and `maxp`.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object GvarReader {
    private const val HEADER_SIZE = 20

    /**
     * Parses one `gvar` table.
     *
     * @param table exact bytes of the OpenType `gvar` table.
     * @param expectedAxisCount axis count that must equal the header axis count, from `fvar`.
     * @param expectedGlyphCount glyph count that must equal the header glyph count, from `maxp`.
     * @param limits resource bounds enforced before allocating decoded records.
     * @param cancellationToken cooperative cancellation checked before each shared tuple.
     * @return decoded table or a typed version, malformed-data, or limit failure.
     */
    public fun read(
        table: ByteArray,
        expectedAxisCount: Int,
        expectedGlyphCount: Int,
        limits: GvarLimits = GvarLimits(),
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<GvarData> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (table.size > limits.maxSourceBytes) {
            return variationLimitFailure("gvar table exceeds the source-byte limit.", "gvar")
        }
        if (table.size < HEADER_SIZE) return invalidGvar("gvar header is truncated.")
        val major = readUInt16(table, 0)?.toInt() ?: return invalidGvar("gvar header is truncated.")
        val minor = readUInt16(table, 2)?.toInt() ?: return invalidGvar("gvar header is truncated.")
        if (major != 1 || minor != 0) {
            return variationFailure("font.variation.unsupported-gvar-version", "Unsupported gvar version $major.$minor.", "gvar")
        }
        val axisCount = readUInt16(table, 4)?.toInt() ?: return invalidGvar("gvar header is truncated.")
        if (axisCount != expectedAxisCount) {
            return invalidGvar("gvar axis count $axisCount does not match fvar $expectedAxisCount.")
        }
        val sharedTupleCount = readUInt16(table, 6)?.toInt() ?: return invalidGvar("gvar header is truncated.")
        if (sharedTupleCount > limits.maxSharedTuples) {
            return variationLimitFailure("gvar shared tuple count $sharedTupleCount exceeds the limit.", "gvar")
        }
        val sharedTuplesOffset = readUInt32AsInt(table, 8) ?: return invalidGvar("gvar header is truncated.")
        val glyphCount = readUInt16(table, 12)?.toInt() ?: return invalidGvar("gvar header is truncated.")
        if (glyphCount != expectedGlyphCount) {
            return invalidGvar("gvar glyph count $glyphCount does not match maxp $expectedGlyphCount.")
        }
        val flags = readUInt16(table, 14)?.toInt() ?: return invalidGvar("gvar header is truncated.")
        val glyphDataOffset = readUInt32AsInt(table, 16) ?: return invalidGvar("gvar header is truncated.")

        val sharedTuples = ArrayList<DoubleArray>(sharedTupleCount)
        if (sharedTupleCount > 0) {
            if (sharedTuplesOffset < HEADER_SIZE) return invalidGvar("gvar shared tuples offset is inside the header.")
            val byteCount = sharedTupleCount.toLong() * axisCount * 2L
            if (byteCount > (table.size - sharedTuplesOffset).toLong()) return invalidGvar("gvar shared tuples are truncated.")
            repeat(sharedTupleCount) { tupleIndex ->
                if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
                val base = sharedTuplesOffset + tupleIndex * axisCount * 2
                sharedTuples += DoubleArray(axisCount) { axis ->
                    readF2Dot14(table, base + axis * 2) ?: return invalidGvar("gvar shared tuple is truncated.")
                }
            }
        }

        val longOffsets = flags and GVAR_LONG_OFFSETS != 0
        val entrySize = if (longOffsets) 4 else 2
        val offsetsByteCount = (glyphCount.toLong() + 1L) * entrySize.toLong()
        if (HEADER_SIZE.toLong() + offsetsByteCount > table.size.toLong()) {
            return invalidGvar("gvar glyph variation offsets are truncated.")
        }
        val offsets = IntArray(glyphCount + 1)
        for (index in 0..glyphCount) {
            val at = HEADER_SIZE + index * entrySize
            offsets[index] = if (longOffsets) {
                readUInt32AsInt(table, at) ?: return invalidGvar("gvar glyph variation offset is truncated.")
            } else {
                (readUInt16(table, at)?.toInt() ?: return invalidGvar("gvar glyph variation offset is truncated.")) * 2
            }
        }
        for (index in 0 until glyphCount) {
            if (offsets[index] > offsets[index + 1]) {
                return invalidGvar("gvar glyph variation offsets must be monotonic.")
            }
        }
        if (glyphDataOffset < 0 || offsets[glyphCount] > table.size - glyphDataOffset) {
            return invalidGvar("gvar glyph variation data is out of range.")
        }
        return FontOperationResult.Success(
            GvarData(
                table = table,
                axisCount = axisCount,
                glyphCount = glyphCount,
                sharedTuples = sharedTuples,
                glyphOffsets = offsets,
                glyphDataStart = glyphDataOffset,
                maxTupleVariations = limits.maxTupleVariations,
                maxPointsPerVariation = limits.maxPointsPerVariation,
            ),
        )
    }

    private fun invalidGvar(message: String): FontOperationResult.Failure =
        variationFailure("font.variation.invalid-gvar", message, "gvar")

    private fun readUInt32AsInt(bytes: ByteArray, offset: Int): Int? {
        val value = readUInt32(bytes, offset)?.toLong() ?: return null
        return if (value > Int.MAX_VALUE.toLong()) null else value.toInt()
    }

    private fun readF2Dot14(bytes: ByteArray, offset: Int): Double? {
        val raw = readInt16(bytes, offset) ?: return null
        return raw.toDouble() / 16_384.0
    }
}
