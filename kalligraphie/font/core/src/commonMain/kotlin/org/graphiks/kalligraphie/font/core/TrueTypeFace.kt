package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
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
import org.graphiks.kalligraphie.font.sfnt.slice

internal class TrueTypeFace(
    private val sourceId: FontSourceId,
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
) : FontFace {
    override val metadata: FontFaceMetadata = parsedFont.metadata
    override val id: FontFaceId = FontFaceId("${sourceId.value}#0")

    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> {
        if (descriptor.layoutSize.value <= 0f) {
            return failure(
                FontError.InvalidInstanceDescriptor(
                    message = "Font instance layout size must be finite and positive.",
                    location = FontDiagnosticLocation.Face(0),
                ),
            )
        }
        return FontOperationResult.Success(
            TrueTypeFontInstance(
                key = instanceKey(descriptor),
                descriptor = descriptor,
                sourceBytes = sourceBytes.copyOf(),
                parsedFont = parsedFont.detachedCopy(),
                faceId = id,
                sourceId = sourceId,
            ),
        )
    }

    private fun instanceKey(descriptor: FontInstanceDescriptor): FontInstanceKey =
        FontInstanceKey(
            "ttf-j1|face=${id.value}|interpretation=static-true-type-outline-v1|" +
                "layout-size-bits=${descriptor.layoutSize.value.toRawBits()}|variations=none|synthetic=none",
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
        return when (
            val result = CmapReader.resolveGlyphId(
                cmapTable = cmapTable,
                codePoint = codePoint,
                numGlyphs = parsedFont.metadata.glyphCount,
            )
        ) {
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
        if (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE || outlineProfile == null || outlineProfile.schemaVersion != 1) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    "A schemaVersion=1 renderable outline profile is required.",
                    FontDiagnosticLocation.Face(0),
                ),
            )
        }
        if (variant != FontRenderVariantKey.default) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    "J1 does not support variant render assets.",
                    FontDiagnosticLocation.Face(0),
                ),
            )
        }
        if (resolver.sourceId != sourceId) {
            return failure(FontError.InvalidFontData("Resolver source does not match this face.", FontDiagnosticLocation.Face(0)))
        }
        if (resolver !is EmbeddedFontAssetResolver) {
            return failure(FontError.InvalidFontData("Resolver was not opened by the embedded J1 catalog.", FontDiagnosticLocation.Face(0)))
        }
        val lease = resolver.acquireLease()
            ?: return failure(FontError.ResourceClosed("Asset resolver is closed."))
        return try {
            FontOperationResult.Success(
                TrueTypeRenderAssetHandle(
                    faceId = faceId,
                    sourceBytes = sourceBytes.copyOf(),
                    parsedFont = parsedFont.detachedCopy(),
                    profile = outlineProfile,
                ),
            )
        } finally {
            lease.release()
        }
    }

    private fun missingTable(tag: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.MissingRequiredTable(tag))

    private fun ByteArray.sliceFor(parsedFont: ParsedTrueTypeFont, tag: String): ByteArray? =
        parsedFont.tableRecords[tag]?.let { record -> slice(this, record) }
}

private class TrueTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
    private val profile: org.graphiks.kalligraphie.api.OutlineProfile,
) : FontRenderAssetHandle {
    private val lifecycle = FontHandleLifecycle()

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            FontOperationResult.Success(
                TrueTypeRenderAssetHandle(
                    faceId = faceId,
                    sourceBytes = sourceBytes.copyOf(),
                    parsedFont = parsedFont.detachedCopy(),
                    profile = profile.copy(),
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled()
            }
            val outline = when (
                val result = GlyfReader.readGlyphOutline(
                    sourceBytes,
                    parsedFont,
                    GlyphId(request.glyphId),
                    profile,
                    cancellationToken,
                )
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled()
            }
            OutlineMaterializer.materialize(outline, profile, cancellationToken)
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }
}

private fun ParsedTrueTypeFont.detachedCopy(): ParsedTrueTypeFont =
    copy(tableRecords = tableRecords.entries.associate { (tag, record) -> tag to record.copy() })

private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
    FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
