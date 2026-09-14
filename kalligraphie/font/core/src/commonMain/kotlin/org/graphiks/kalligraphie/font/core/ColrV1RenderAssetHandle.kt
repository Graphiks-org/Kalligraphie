@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.font.glyph.OutlineMaterializer
import org.graphiks.kalligraphie.font.sfnt.ColrV1Data
import org.graphiks.kalligraphie.font.sfnt.SvgOpenTypeReader
import org.graphiks.kalligraphie.font.sfnt.SvgGlyphPaint

/** Owns a prepared-font lease and immutable lazy COLR v1 source data. */
internal class ColrV1RenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val profile: PaintGraphProfile,
    private val colorData: ColrV1Data,
    private val svgSource: ColrV1SvgSource? = null,
) : FontRenderAssetHandle {
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease() ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detached = resourceLease?.resource?.acquireLease() ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(ColrV1RenderAssetHandle(faceId, detached, key, profile, colorData, svgSource))
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(request: FontGlyphRequest, cancellationToken: CancellationToken): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease() ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val resource = resourceLease?.resource ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val preparedFont = resource.preparedFont
            val glyphId = GlyphId(request.glyphId)
            val svgCovered = svgSource?.covers(glyphId) == true
            val representationKey = GlyphRepresentationKey(
                assetKey = key,
                glyphId = glyphId,
                variant = key.variant,
                profile = GlyphRepresentationProfileKey.paintGraph(profile),
                routeParameters = when {
                    svgCovered -> "svg-opentype-v0"
                    colorData.containsGlyph(glyphId) -> "colr-v1-static;cpal-0-or-1"
                    colorData.containsLegacyGlyph(glyphId) -> "colr-v1-to-v0;cpal-0-or-1"
                    else -> "colr-v1-to-glyf-outline"
                },
            )
            resource.cachedRepresentation(representationKey)?.let { return it }
            val svgPaint = if (svgCovered) {
                when (val result = checkNotNull(svgSource).readGlyph(glyphId, profile)) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
            } else null
            val result = when (svgPaint) {
                SvgGlyphPaint.Empty -> FontOperationResult.Success(GlyphRepresentation.Empty)
                is SvgGlyphPaint.Paint -> FontOperationResult.Success(GlyphRepresentation.Paint(svgPaint.paint))
                null -> colorData.resolveGlyph(glyphId, profile, cancellationToken) outline@{ outlineGlyph ->
                    if (cancellationToken.isCancellationRequested()) return@outline FontOperationResult.Cancelled()
                    when (val outline = preparedFont.readGlyphOutline(outlineGlyph, profile.outlineProfile, cancellationToken)) {
                        is FontOperationResult.Failure -> outline
                        is FontOperationResult.Cancelled -> outline
                        is FontOperationResult.Success -> when (val materialized = OutlineMaterializer.materialize(outline.value, profile.outlineProfile, cancellationToken)) {
                            is FontOperationResult.Failure -> materialized
                            is FontOperationResult.Cancelled -> materialized
                            is FontOperationResult.Success -> when (val value = materialized.value) {
                                is GlyphRepresentation.Outline -> FontOperationResult.Success(value.outline, materialized.diagnostics)
                                GlyphRepresentation.Empty -> FontOperationResult.Success(GlyphOutlineIR(
                                    glyphId = outlineGlyph.value,
                                    unitsPerEm = outline.value.unitsPerEm,
                                    bounds = DesignBounds.empty,
                                    contours = emptyList(),
                                    pointCount = 0,
                                    limits = profile.outlineProfile.toGlyphOutlineLimits(),
                                ))
                                else -> failure(FontError.InvalidFontData("COLR glyph clip did not materialize an outline.", FontDiagnosticLocation.Glyph(outlineGlyph.value)))
                            }
                        }
                    }
                }
            }
            when (result) {
                is FontOperationResult.Success -> if (cancellationToken.isCancellationRequested()) FontOperationResult.Cancelled()
                else result.also { resource.cacheRepresentation(representationKey, it) }
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

/** Immutable bounded SVG bytes with coverage taken from an already validated complete index. */
internal class ColrV1SvgSource(private val bytes: ByteArray, private val glyphCount: Int) {
    private val glyphRanges: List<IntRange>

    init {
        fun unsignedShort(offset: Int): Int =
            ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)
        val listOffset = (2..5).fold(0) { value, offset -> (value shl 8) or (bytes[offset].toInt() and 255) }
        glyphRanges = List(unsignedShort(listOffset)) { index ->
            val offset = listOffset + 2 + index * 12
            unsignedShort(offset)..unsignedShort(offset + 2)
        }
    }

    fun covers(glyphId: GlyphId): Boolean = glyphRanges.any { glyphId.value in it }

    fun readGlyph(glyphId: GlyphId, profile: PaintGraphProfile): FontOperationResult<SvgGlyphPaint?> =
        SvgOpenTypeReader.readGlyph(bytes, glyphCount, profile, glyphId)
}
