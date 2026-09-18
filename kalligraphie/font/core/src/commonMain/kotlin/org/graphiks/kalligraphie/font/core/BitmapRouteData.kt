@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.font.sfnt.CbdtCblcData
import org.graphiks.kalligraphie.font.sfnt.EbdtFormatOneData
import org.graphiks.kalligraphie.font.sfnt.SbixData

private const val EBDT_FORMAT_ONE_ROUTE_PARAMETERS: String = "eblc-v2;ebdt-v2;index-format-1;image-format-1"
private const val CBDT_CBLC_ROUTE_PARAMETERS: String = "cblc;cbdt;index-format-1;image-format-17-18"
private const val SBIX_ROUTE_PARAMETERS: String = "sbix-v1;png;bitmap-v1"

/**
 * One validated bitmap route held by a render asset, with the route parameters that participate
 * in its representation key.
 *
 * Implementations are immutable and safe to share across detached handles; [routeParameters] must
 * change whenever decode semantics change.
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

/** The CBLC index-format 1 / CBDT image-format 17 and 18 colour route. */
internal class CbdtCblcBitmapRoute(
    private val data: CbdtCblcData,
) : BitmapRouteData {
    override val routeParameters: String = CBDT_CBLC_ROUTE_PARAMETERS

    override fun decode(glyphId: GlyphId, cancellationToken: CancellationToken): FontOperationResult<BitmapGlyphIR> =
        data.decode(glyphId, cancellationToken)
}

/** The sbix version 1 `'png '` bitmap route. */
internal class SbixBitmapRoute(
    private val data: SbixData,
) : BitmapRouteData {
    override val routeParameters: String = SBIX_ROUTE_PARAMETERS

    override fun decode(glyphId: GlyphId, cancellationToken: CancellationToken): FontOperationResult<BitmapGlyphIR> =
        data.decode(glyphId, cancellationToken)
}
