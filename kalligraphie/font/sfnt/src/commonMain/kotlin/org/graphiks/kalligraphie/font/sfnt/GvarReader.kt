@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Resolved `gvar` deltas for one glyph's outline (or component) points and its four phantom points.
 *
 * The outline or component point count is [pointCount]; the four phantom slots follow it at indices
 * [pointCount] through `pointCount + 3`.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class GvarGlyphDeltas internal constructor(
    /** Number of outline or component points covered by the deltas. */
    public val pointCount: Int,
    internal val xDeltas: DoubleArray,
    internal val yDeltas: DoubleArray,
) {
    init {
        require(xDeltas.size == yDeltas.size) { "gvar x and y delta counts must match." }
        require(xDeltas.size == pointCount + GVAR_PHANTOM_POINT_COUNT) {
            "gvar delta count must cover the outline points plus the phantom points."
        }
    }

    /**
     * Deltas for the four phantom points that follow the outline or component points.
     *
     * For a composite glyph whose component sets `USE_MY_METRICS`, the OpenType specification takes
     * the composite's phantom positions from that component, so a metrics consumer must prefer the
     * metrics-source glyph's phantom deltas.
     */
    public val phantomDeltas: GvarPhantomDeltas = GvarPhantomDeltas(
        leftX = xDeltas[pointCount + GVAR_PHANTOM_LEFT_INDEX],
        rightX = xDeltas[pointCount + GVAR_PHANTOM_RIGHT_INDEX],
        topY = yDeltas[pointCount + GVAR_PHANTOM_TOP_INDEX],
        bottomY = yDeltas[pointCount + GVAR_PHANTOM_BOTTOM_INDEX],
    )

    /** Horizontal delta for outline [pointIndex], or `0.0` when out of range; phantoms are excluded. */
    public fun xDelta(pointIndex: Int): Double = if (pointIndex in 0 until pointCount) xDeltas[pointIndex] else 0.0

    /** Vertical delta for outline [pointIndex], or `0.0` when out of range; phantoms are excluded. */
    public fun yDelta(pointIndex: Int): Double = if (pointIndex in 0 until pointCount) yDeltas[pointIndex] else 0.0
}

/**
 * Resolved `gvar` deltas for the four phantom points of one glyph.
 *
 * Phantom points follow the outline or component points in `gvar` point order: the left and right
 * side-bearing points carry horizontal deltas and the top and bottom side-bearing points carry
 * vertical deltas. Values are additive adjustments in design units at the requested instance.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public data class GvarPhantomDeltas internal constructor(
    /** Horizontal delta of the left side-bearing phantom point. */
    public val leftX: Double,
    /** Horizontal delta of the right side-bearing phantom point. */
    public val rightX: Double,
    /** Vertical delta of the top side-bearing phantom point. */
    public val topY: Double,
    /** Vertical delta of the bottom side-bearing phantom point. */
    public val bottomY: Double,
) {
    /** Advance-width delta: the right side-bearing delta minus the left side-bearing delta. */
    public val horizontalAdvanceDelta: Double get() = rightX - leftX

    /** Advance-height delta: the top side-bearing delta minus the bottom side-bearing delta. */
    public val verticalAdvanceDelta: Double get() = topY - bottomY
}

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
) {
    /**
     * Decodes and applies the `gvar` tuple deltas of one simple glyph at [normalizedAxes].
     *
     * [baseX]/[baseY] are the unvaried outline coordinates (phantom slots excluded);
     * [contourEndPoints] is the inclusive last-point index of each contour. Returns `null` when the
     * glyph has no variation record. Malformed records fail closed; cancellation returns
     * [FontOperationResult.Cancelled] with no partial output.
     *
     * @param glyphId numeric glyph identifier.
     * @param contourEndPoints inclusive end index of each contour, in order.
     * @param baseX horizontal coordinates of the decoded outline points.
     * @param baseY vertical coordinates of the decoded outline points.
     * @param normalizedAxes normalized coordinates in `fvar` axis order, missing axes treated as 0.
     * @param cancellationToken cooperative cancellation checked before each tuple.
     */
    public fun glyphDeltas(
        glyphId: Int,
        contourEndPoints: List<Int>,
        baseX: List<Double>,
        baseY: List<Double>,
        normalizedAxes: List<Double>,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<GvarGlyphDeltas?> {
        if (baseX.size != baseY.size) return invalid("gvar base coordinates are inconsistent.")
        return decodeDeltas(
            glyphId = glyphId,
            pointCount = baseX.size,
            contourEndPoints = contourEndPoints,
            baseX = baseX,
            baseY = baseY,
            normalizedAxes = normalizedAxes,
            cancellationToken = cancellationToken,
        )
    }

    /**
     * Decodes and applies the `gvar` tuple deltas of one composite glyph at [normalizedAxes].
     *
     * For a composite glyph the glyph's points are its components in glyph-entry order followed by
     * the four phantom points: point numbers refer to component indices and no interpolation is
     * performed for un-referenced components. The caller applies each component delta to that
     * component's placement offset only when the component selects `ARGS_ARE_XY_VALUES`. Returns
     * `null` when the glyph has no variation record. Malformed records fail closed; cancellation
     * returns [FontOperationResult.Cancelled] with no partial output.
     *
     * @param glyphId numeric glyph identifier.
     * @param componentCount number of components in the composite glyph.
     * @param normalizedAxes normalized coordinates in `fvar` axis order, missing axes treated as 0.
     * @param cancellationToken cooperative cancellation checked before each tuple.
     */
    public fun compositeGlyphDeltas(
        glyphId: Int,
        componentCount: Int,
        normalizedAxes: List<Double>,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<GvarGlyphDeltas?> {
        if (componentCount < 0) return invalid("gvar component count must not be negative.")
        if (componentCount == 0) return FontOperationResult.Success(null)
        return decodeDeltas(
            glyphId = glyphId,
            pointCount = componentCount,
            contourEndPoints = emptyList(),
            baseX = emptyList(),
            baseY = emptyList(),
            normalizedAxes = normalizedAxes,
            cancellationToken = cancellationToken,
        )
    }

    private fun decodeDeltas(
        glyphId: Int,
        pointCount: Int,
        contourEndPoints: List<Int>,
        baseX: List<Double>,
        baseY: List<Double>,
        normalizedAxes: List<Double>,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GvarGlyphDeltas?> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId !in 0 until glyphCount) return FontOperationResult.Success(null)
        val start = glyphDataStart + glyphOffsets[glyphId]
        val end = glyphDataStart + glyphOffsets[glyphId + 1]
        if (start == end) return FontOperationResult.Success(null)
        if (start < 0 || end > table.size || end - start < 4) return invalid("gvar glyph record is truncated.")
        val tupleVariationCountField = readUInt16(table, start)?.toInt() ?: return invalid("gvar glyph record is truncated.")
        val tupleVariationCount = tupleVariationCountField and GVAR_TUPLE_COUNT_MASK
        if (tupleVariationCount <= 0) return invalid("gvar glyph record declares no tuple variations.")
        if (tupleVariationCount > maxTupleVariations) {
            return variationLimitFailure("gvar tuple variation count $tupleVariationCount exceeds the limit.", "gvar")
        }
        val offsetToData = readUInt16(table, start + 2)?.toInt() ?: return invalid("gvar glyph record is truncated.")
        val tupleDataStart = start + offsetToData
        if (tupleDataStart < start || tupleDataStart > end) return invalid("gvar tuple data offset is out of range.")
        val maxPointCountValue = pointCount.toLong() + GVAR_PHANTOM_POINT_COUNT.toLong()
        if (maxPointCountValue > maxPointsPerVariation.toLong()) {
            return variationLimitFailure("gvar point count $maxPointCountValue exceeds the limit.", "gvar")
        }
        val maxPointCount = maxPointCountValue.toInt()
        val headers = ArrayList<GvarTupleHeader>(tupleVariationCount)
        var headerOffset = start + 4
        repeat(tupleVariationCount) {
            val variationDataSize = readUInt16Within(table, headerOffset, end) ?: return invalid("gvar tuple header is truncated.")
            val tupleIndex = readUInt16Within(table, headerOffset + 2, end) ?: return invalid("gvar tuple header is truncated.")
            headerOffset += 4
            val peak = if (tupleIndex and GVAR_EMBEDDED_PEAK_TUPLE != 0) {
                val values = DoubleArray(axisCount)
                for (axis in 0 until axisCount) {
                    values[axis] = readF2Dot14Within(table, headerOffset + axis * 2, end)
                        ?: return invalid("gvar embedded peak tuple is truncated.")
                }
                headerOffset += axisCount * 2
                values
            } else {
                sharedTuples.getOrNull(tupleIndex and GVAR_TUPLE_INDEX_MASK)
                    ?: return invalid("gvar tuple references an unknown shared tuple.")
            }
            val startTuple: DoubleArray?
            val endTuple: DoubleArray?
            if (tupleIndex and GVAR_INTERMEDIATE_REGION != 0) {
                startTuple = DoubleArray(axisCount)
                for (axis in 0 until axisCount) {
                    startTuple[axis] = readF2Dot14Within(table, headerOffset + axis * 2, end)
                        ?: return invalid("gvar intermediate start tuple is truncated.")
                }
                headerOffset += axisCount * 2
                endTuple = DoubleArray(axisCount)
                for (axis in 0 until axisCount) {
                    endTuple[axis] = readF2Dot14Within(table, headerOffset + axis * 2, end)
                        ?: return invalid("gvar intermediate end tuple is truncated.")
                }
                headerOffset += axisCount * 2
            } else {
                startTuple = null
                endTuple = null
            }
            headers += GvarTupleHeader(variationDataSize, tupleIndex, peak, startTuple, endTuple)
        }
        if (tupleDataStart < headerOffset) return invalid("gvar tuple data overlaps the tuple headers.")

        var dataOffset = tupleDataStart
        val sharedPoints = if (tupleVariationCountField and GVAR_SHARED_POINT_NUMBERS != 0) {
            val packed = readPackedPoints(table, dataOffset, maxPointCount, end)
                ?: return invalid("gvar shared point numbers are malformed.")
            dataOffset = packed.nextOffset
            packed.values
        } else {
            null
        }

        val xDeltas = DoubleArray(maxPointCount)
        val yDeltas = DoubleArray(maxPointCount)
        for (header in headers) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val tupleEnd = dataOffset.toLong() + header.variationDataSize
            if (tupleEnd > end.toLong()) return invalid("gvar tuple data is truncated.")
            var tupleOffset = dataOffset
            val privatePoints = if (header.tupleIndex and GVAR_PRIVATE_POINT_NUMBERS != 0) {
                val packed = readPackedPoints(table, tupleOffset, maxPointCount, tupleEnd.toInt())
                    ?: return invalid("gvar private point numbers are malformed.")
                tupleOffset = packed.nextOffset
                packed.values
            } else {
                null
            }
            val targetPoints = privatePoints ?: sharedPoints ?: IntArray(maxPointCount) { it }
            val tupleX = readPackedDeltas(table, tupleOffset, targetPoints.size, tupleEnd.toInt())
                ?: return invalid("gvar x deltas are malformed.")
            tupleOffset = tupleX.nextOffset
            val tupleY = readPackedDeltas(table, tupleOffset, targetPoints.size, tupleEnd.toInt())
                ?: return invalid("gvar y deltas are malformed.")
            if (tupleY.nextOffset > tupleEnd.toInt()) return invalid("gvar tuple data is truncated.")
            val scalar = TupleVariationScalars.scalar(normalizedAxes, header.peak, header.startTuple, header.endTuple)
            if (scalar != 0.0) {
                val resolved = GvarIup.resolvePointDeltas(
                    pointCount = pointCount,
                    contourEndPoints = contourEndPoints,
                    baseX = baseX,
                    baseY = baseY,
                    targetPoints = targetPoints,
                    tupleXDeltas = tupleX.values,
                    tupleYDeltas = tupleY.values,
                )
                for (index in 0 until maxPointCount) {
                    xDeltas[index] += resolved.xDeltas[index] * scalar
                    yDeltas[index] += resolved.yDeltas[index] * scalar
                }
            }
            dataOffset = tupleEnd.toInt()
        }
        return FontOperationResult.Success(GvarGlyphDeltas(pointCount, xDeltas, yDeltas))
    }

    private fun invalid(message: String): FontOperationResult.Failure =
        variationFailure("font.variation.invalid-gvar", message, "gvar")
}

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

private data class GvarTupleHeader(
    val variationDataSize: Int,
    val tupleIndex: Int,
    val peak: DoubleArray,
    val startTuple: DoubleArray?,
    val endTuple: DoubleArray?,
)

private data class PackedGvarInts(
    val values: IntArray,
    val nextOffset: Int,
)

private fun readPackedPoints(
    bytes: ByteArray,
    offset: Int,
    maxPointCount: Int,
    limit: Int,
): PackedGvarInts? {
    var current = offset
    val first = readUInt8Within(bytes, current, limit) ?: return null
    current += 1
    if (first == 0) return PackedGvarInts(IntArray(maxPointCount) { it }, current)
    val pointCount = if (first and 0x80 != 0) {
        val second = readUInt8Within(bytes, current, limit) ?: return null
        current += 1
        ((first and 0x7F) shl 8) or second
    } else {
        first
    }
    if (pointCount > maxPointCount) return null
    val points = IntArray(pointCount)
    var index = 0
    var point = 0
    while (index < pointCount) {
        val control = readUInt8Within(bytes, current, limit) ?: return null
        current += 1
        val wordDeltas = control and 0x80 != 0
        val runCount = (control and 0x7F) + 1
        if (index + runCount > pointCount) return null
        repeat(runCount) {
            val delta = if (wordDeltas) {
                val value = readUInt16Within(bytes, current, limit) ?: return null
                current += 2
                value
            } else {
                val value = readUInt8Within(bytes, current, limit) ?: return null
                current += 1
                value
            }
            point += delta
            if (point >= maxPointCount) return null
            points[index] = point
            index += 1
        }
    }
    return PackedGvarInts(points, current)
}

private fun readPackedDeltas(
    bytes: ByteArray,
    offset: Int,
    count: Int,
    limit: Int,
): PackedGvarInts? {
    val values = IntArray(count)
    var current = offset
    var index = 0
    while (index < count) {
        val control = readUInt8Within(bytes, current, limit) ?: return null
        current += 1
        val runCount = (control and 0x3F) + 1
        if (index + runCount > count) return null
        when {
            control and 0x80 != 0 -> repeat(runCount) {
                values[index] = 0
                index += 1
            }
            control and 0x40 != 0 -> repeat(runCount) {
                values[index] = readInt16Within(bytes, current, limit) ?: return null
                current += 2
                index += 1
            }
            else -> repeat(runCount) {
                values[index] = readInt8Within(bytes, current, limit) ?: return null
                current += 1
                index += 1
            }
        }
    }
    return PackedGvarInts(values, current)
}

private fun readUInt8Within(bytes: ByteArray, offset: Int, limit: Int): Int? =
    if (offset >= 0 && offset < limit && offset < bytes.size) bytes[offset].toInt() and 0xFF else null

private fun readInt8Within(bytes: ByteArray, offset: Int, limit: Int): Int? =
    readUInt8Within(bytes, offset, limit)?.let { if (it and 0x80 != 0) it - 0x100 else it }

private fun readUInt16Within(bytes: ByteArray, offset: Int, limit: Int): Int? =
    if (offset >= 0 && offset + 2 <= limit && offset + 2 <= bytes.size) readUInt16(bytes, offset)?.toInt() else null

private fun readInt16Within(bytes: ByteArray, offset: Int, limit: Int): Int? =
    if (offset >= 0 && offset + 2 <= limit && offset + 2 <= bytes.size) readInt16(bytes, offset) else null

private fun readF2Dot14Within(bytes: ByteArray, offset: Int, limit: Int): Double? =
    readInt16Within(bytes, offset, limit)?.toDouble()?.div(16_384.0)
