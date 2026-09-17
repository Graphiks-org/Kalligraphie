@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.font.sfnt.EbdtFormatOneData

/**
 * One validated bitmap route held by a render asset, with the route parameters that participate
 * in its representation key.
 */
internal sealed interface BitmapRouteData {
    /** Canonical route fingerprint recorded in the representation key. */
    val routeParameters: String

    /** Decodes one validated glyph, or fails with the route's typed error. */
    fun decode(glyphId: GlyphId, cancellationToken: CancellationToken): FontOperationResult<BitmapGlyphIR>
}

/** The EBLC index-format 1 / EBDT image-format 1 monochrome route. */
internal class EbdtMonoBitmapRoute(
    private val data: EbdtFormatOneData,
) : BitmapRouteData {
    override val routeParameters: String = EBDT_FORMAT_ONE_ROUTE_PARAMETERS

    override fun decode(glyphId: GlyphId, cancellationToken: CancellationToken): FontOperationResult<BitmapGlyphIR> =
        data.decode(glyphId, cancellationToken)
}
