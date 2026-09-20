@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.immutableListSnapshot

/** One decoded `fvar` axis record in design coordinates. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class FvarAxis(
    public val tag: String,
    public val minValue: Float,
    public val defaultValue: Float,
    public val maxValue: Float,
    public val hidden: Boolean,
    public val nameId: Int,
)

/** One decoded `fvar` named instance in design coordinates. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class FvarInstance(
    public val subfamilyNameId: Int,
    public val coordinates: List<FontVariationCoordinate>,
    public val postScriptNameId: Int?,
)

/** Decoded `fvar` data. */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class FvarData(axes: List<FvarAxis>, instances: List<FvarInstance>) {
    public val axes: List<FvarAxis> = axes.immutableListSnapshot()
    public val instances: List<FvarInstance> = instances.immutableListSnapshot()
    public fun axis(tag: String): FvarAxis? = axes.firstOrNull { it.tag == tag }
}

/**
 * Decodes the OpenType `fvar` table. Only version 1.0 is accepted. Every offset is bounds-checked
 * against the table length and the operation is all-or-nothing.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object FvarReader {
    private const val HEADER_SIZE = 16
    private const val AXIS_RECORD_SIZE = 20

    public fun read(
        table: ByteArray,
        limits: VariationLimits = VariationLimits(),
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<FvarData> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (table.size > limits.maxSourceBytes) {
            return variationLimitFailure("fvar table exceeds the source-byte limit.", "fvar")
        }
        if (table.size < HEADER_SIZE) {
            return variationFailure("font.variation.invalid-fvar", "fvar header is truncated.", "fvar")
        }
        val major = readUInt16(table, 0)?.toInt() ?: return invalid()
        val minor = readUInt16(table, 2)?.toInt() ?: return invalid()
        if (major != 1 || minor != 0) {
            return variationFailure("font.variation.unsupported-fvar-version", "Unsupported fvar version $major.$minor.", "fvar")
        }
        val axesArrayOffset = readUInt16(table, 4)?.toInt() ?: return invalid()
        val reserved = readUInt16(table, 6)?.toInt() ?: return invalid()
        if (reserved != 2) {
            return variationFailure("font.variation.invalid-fvar", "fvar reserved field must be 2.", "fvar")
        }
        val axisCount = readUInt16(table, 8)?.toInt() ?: return invalid()
        val axisSize = readUInt16(table, 10)?.toInt() ?: return invalid()
        val instanceCount = readUInt16(table, 12)?.toInt() ?: return invalid()
        val instanceSize = readUInt16(table, 14)?.toInt() ?: return invalid()
        if (axisCount > limits.maxAxes) {
            return variationLimitFailure("fvar axis count $axisCount exceeds the limit.", "fvar")
        }
        if (instanceCount > limits.maxInstances) {
            return variationLimitFailure("fvar instance count $instanceCount exceeds the limit.", "fvar")
        }
        if (axisSize < AXIS_RECORD_SIZE) {
            return variationFailure("font.variation.invalid-fvar", "fvar axisSize must be at least 20.", "fvar")
        }
        if (instanceCount > 0 && instanceSize < axisCount * 4 + 4) {
            return variationFailure("font.variation.invalid-fvar", "fvar instanceSize is too small.", "fvar")
        }
        val axesEnd = axesArrayOffset.toLong() + axisCount.toLong() * axisSize.toLong()
        if (axesEnd > table.size.toLong()) return invalid()
        val instanceStart = axesEnd
        if (instanceStart + instanceCount.toLong() * instanceSize.toLong() > table.size.toLong()) return invalid()

        val axes = ArrayList<FvarAxis>(axisCount)
        for (index in 0 until axisCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val base = axesArrayOffset + index * axisSize
            val tag = table.decodeAsciiTag(base)
            val minValue = readFixed(table, base + 4) ?: return invalid()
            val defaultValue = readFixed(table, base + 8) ?: return invalid()
            val maxValue = readFixed(table, base + 12) ?: return invalid()
            if (!(minValue <= defaultValue && defaultValue <= maxValue)) {
                return variationFailure("font.variation.invalid-fvar", "Axis $tag has invalid min/default/max bounds.", "fvar")
            }
            val flags = readUInt16(table, base + 16)?.toInt() ?: return invalid()
            val nameId = readUInt16(table, base + 18)?.toInt() ?: return invalid()
            axes += FvarAxis(tag, minValue, defaultValue, maxValue, hidden = (flags and 0x0001) != 0, nameId = nameId)
        }

        val hasPostScriptNameId = instanceCount > 0 && instanceSize >= axisCount * 4 + 6
        val instances = ArrayList<FvarInstance>(instanceCount)
        for (index in 0 until instanceCount) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val base = (instanceStart + index.toLong() * instanceSize).toInt()
            val subfamilyNameId = readUInt16(table, base)?.toInt() ?: return invalid()
            val coordinates = ArrayList<FontVariationCoordinate>(axisCount)
            for (axisIndex in 0 until axisCount) {
                val value = readFixed(table, base + 4 + axisIndex * 4) ?: return invalid()
                coordinates += FontVariationCoordinate(axes[axisIndex].tag, value)
            }
            val postScriptNameId = if (hasPostScriptNameId) {
                readUInt16(table, base + 4 + axisCount * 4)?.toInt() ?: return invalid()
            } else {
                null
            }
            instances += FvarInstance(subfamilyNameId, coordinates, postScriptNameId)
        }
        return FontOperationResult.Success(FvarData(axes, instances))
    }

    private fun invalid(): FontOperationResult.Failure =
        variationFailure("font.variation.invalid-fvar", "fvar table is truncated.", "fvar")

    private fun readFixed(bytes: ByteArray, offset: Int): Float? {
        val raw = readUInt32(bytes, offset) ?: return null
        return raw.toInt().toFloat() / 65_536f
    }
}
