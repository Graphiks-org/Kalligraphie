@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStore
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStoreEvaluator
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStoreLimits

/**
 * Variation data required by a CFF2 charstring's `blend` and `vsindex` operators.
 *
 * Implementations expose the region count and the region scalars for one variation-store index.
 * Scalars are evaluated at the location the source was created for; the portable route passes the
 * instance's normalized axes in `fvar` axis order, and an empty list is the default instance (all
 * axis coordinates zero).
 */
internal interface CffVariationSource {
    /** Number of regions in the item variation data at [vsIndex]. */
    fun regionCount(vsIndex: Int): Int

    /** Region scalars at the source's location for [vsIndex]. */
    fun scalars(vsIndex: Int): DoubleArray
}

/**
 * Parsed CFF2 VariationStore block, delegating region evaluation to the shared
 * [VariationStoreEvaluator].
 *
 * The CFF2 block is a 16-bit byte length followed by an OpenType ItemVariationStore whose offsets
 * are relative to the ItemVariationStore start. Scalars are evaluated at [normalizedAxes], in
 * `fvar` axis order; an empty list evaluates the default instance.
 */
internal class CffVarStore private constructor(
    private val store: VariationStore,
    private val normalizedAxes: List<Double>,
) : CffVariationSource {
    override fun regionCount(vsIndex: Int): Int = store.regionCountAt(vsIndex)

    override fun scalars(vsIndex: Int): DoubleArray =
        VariationStoreEvaluator.scalars(store, vsIndex, normalizedAxes)

    companion object {
        private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF2")

        /** Reads a CFF2 VariationStore block beginning at [offset] in [bytes]. */
        fun read(
            bytes: ByteArray,
            offset: Int,
            normalizedAxes: List<Double> = emptyList(),
            limits: VariationStoreLimits = VariationStoreLimits(),
            cancellationToken: CancellationToken = CancellationToken.none,
        ): FontOperationResult<CffVarStore> {
            if (offset < 0 || offset + 2 > bytes.size) {
                return failure("font.variation.truncated-store", "CFF2 variation store is truncated.")
            }
            val base = offset + 2
            return when (
                val result = VariationStoreEvaluator.read(bytes, base, "CFF2", limits, cancellationToken)
            ) {
                is FontOperationResult.Success ->
                    FontOperationResult.Success(CffVarStore(result.value, normalizedAxes))
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        }

        private fun failure(code: String, message: String): FontOperationResult.Failure {
            val error = FontError.FontDataFailure(code = code, message = message, location = location)
            return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
        }
    }
}
