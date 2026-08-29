package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.glyph.OutlineMaterializer
import org.graphiks.kalligraphie.font.scaler.CmapReader
import org.graphiks.kalligraphie.font.scaler.GlyfReader
import org.graphiks.kalligraphie.font.scaler.MetricsReader
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont

internal class TrueTypeFace(
    private val sourceId: FontSourceId,
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
) : FontFace {
    override val metadata: FontFaceMetadata = parsedFont.metadata
    override val id: FontFaceId = FontFaceId("${sourceId.value}#0")

    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
        FontOperationResult.Success(
            TrueTypeFontInstance(
                key = FontInstanceKey("${id.value}@${descriptor.layoutSize.value}"),
                descriptor = descriptor,
                sourceBytes = sourceBytes,
                parsedFont = parsedFont,
                faceId = id,
                sourceId = sourceId,
            ),
        )
}

private data class TrueTypeFontInstance(
    override val key: FontInstanceKey,
    private val descriptor: FontInstanceDescriptor,
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
    private val faceId: FontFaceId,
    private val sourceId: FontSourceId,
) : FontInstance {
    override fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> {
        val cmapTable = sourceBytes.sliceFor(parsedFont, "cmap") ?: return missingTable("cmap")
        return when (val result = CmapReader.resolveGlyphId(cmapTable, codePoint)) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                GlyphResolution(codePoint = codePoint, glyphId = result.value.glyphId),
                result.diagnostics,
            )
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

    override fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        MetricsReader.readGlyphMetrics(sourceBytes, parsedFont, glyphId, descriptor.layoutSize.value)

    override fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> {
        val outlineProfile = requirements.outlineProfile
        if (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE || outlineProfile == null) {
            return failure(FontError.UnsupportedRepresentationProfile("A renderable outline profile is required.", FontDiagnosticLocation.Face(0)))
        }
        if (resolver.sourceId != sourceId) {
            return failure(FontError.InvalidFontData("Resolver source does not match this face.", FontDiagnosticLocation.Face(0)))
        }
        if (resolver is EmbeddedFontAssetResolver && resolver.isClosed) {
            return failure(FontError.ResourceClosed("Asset resolver is closed."))
        }
        return FontOperationResult.Success(
            TrueTypeRenderAssetHandle(
                faceId = faceId,
                sourceBytes = sourceBytes.copyOf(),
                parsedFont = parsedFont,
                profile = outlineProfile,
            ),
        )
    }

    private fun missingTable(tag: String): FontOperationResult.Failure =
        FontOperationResult.Failure(org.graphiks.kalligraphie.api.FontError.MissingRequiredTable(tag))

    private fun ByteArray.sliceFor(parsedFont: ParsedTrueTypeFont, tag: String): ByteArray? =
        parsedFont.tableRecords[tag]?.let { record ->
            org.graphiks.kalligraphie.font.sfnt.slice(this, record)
        }
}

private class TrueTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
    private val profile: org.graphiks.kalligraphie.api.OutlineProfile,
) : FontRenderAssetHandle {
    private var closed: Boolean = false

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> {
        if (closed) {
            return failure(FontError.ResourceClosed("Render asset is closed."))
        }
        val outline = when (val result = GlyfReader.readGlyphOutline(sourceBytes, parsedFont, GlyphId(request.glyphId), profile)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return OutlineMaterializer.materialize(outline, profile)
    }

    override fun close(): FontOperationResult<Unit> {
        closed = true
        return FontOperationResult.Success(Unit)
    }
}

private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
    FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
