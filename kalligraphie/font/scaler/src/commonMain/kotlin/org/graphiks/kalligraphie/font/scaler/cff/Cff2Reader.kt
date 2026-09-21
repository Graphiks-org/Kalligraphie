@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.scaler.ScalerGlyphOutline
import org.graphiks.kalligraphie.font.sfnt.variation.VariationStoreLimits

/**
 * Adapts a parsed CFF2 table into the scaler's [ScalerGlyphOutline].
 *
 * CFF2 charstrings carry no width and no `endchar`; variation deltas are applied through the
 * ItemVariationStore block. [axisTags] is the face's `fvar` axis order and [normalizedAxes] the
 * instance's tag-keyed normalized selection; together they select the charstring's `blend`
 * scalars. An empty [normalizedAxes] materializes the default instance and leaves the default
 * outline unchanged.
 */
internal object Cff2Reader {
    fun readGlyphOutline(
        bytes: ByteArray,
        table: Cff2Table,
        glyphId: Int,
        unitsPerEm: Int,
        profile: OutlineProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
        axisTags: List<String> = emptyList(),
        normalizedAxes: List<FontAxisCoordinate> = emptyList(),
    ): FontOperationResult<ScalerGlyphOutline> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId < 0 || glyphId >= table.glyphCount) return FontOperationResult.Failure(FontError.GlyphOutOfRange(glyphId))
        val fdIndex = table.fdIndexOf(glyphId)
        val localSubrs = table.fontDicts?.getOrNull(fdIndex)?.localSubrs
        val variationSource: CffVariationSource? = if (table.variationStoreOffset == null) {
            null
        } else {
            val orderedAxes = orderAxes(axisTags, normalizedAxes)
            when (
                val result = CffVarStore.read(
                    bytes = bytes,
                    offset = table.variationStoreOffset,
                    normalizedAxes = orderedAxes,
                    limits = VariationStoreLimits(),
                    cancellationToken = cancellationToken,
                )
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
        }
        val charString = table.charStringsIndex.item(glyphId)
        val result = Type2CharstringInterpreter.interpret(
            charString = charString,
            globalSubrs = table.globalSubrIndex.toItems(),
            localSubrs = localSubrs.toItems(),
            nominalWidthX = 0,
            defaultWidthX = 0,
            maxPoints = profile.maxPoints,
            maxContours = profile.maxContours,
            hasWidth = false,
            variationSource = variationSource,
            initialVsIndex = table.defaultVsIndex(glyphId),
        )
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        return when (result) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                ScalerGlyphOutline(
                    glyphId = glyphId,
                    unitsPerEm = unitsPerEm,
                    bounds = result.value.bounds,
                    contours = result.value.contours,
                    pointCount = result.value.pointCount,
                    components = emptyList(),
                ),
            )

            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

    /** Maps tag-keyed [normalizedAxes] into [axisTags] (`fvar`) order, defaulting absent axes to zero. */
    internal fun orderAxes(axisTags: List<String>, normalizedAxes: List<FontAxisCoordinate>): List<Double> =
        List(axisTags.size) { index ->
            val tag = axisTags[index]
            normalizedAxes.firstOrNull { it.tag == tag }?.value?.toDouble() ?: 0.0
        }

    private fun CffIndex?.toItems(): List<ByteArray> =
        if (this == null) emptyList() else (0 until itemCount).map { item(it) }
}
