package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontDataInterpretationVersion
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
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont

internal class TrueTypeFace(
    private val sourceId: FontSourceId,
    private val parsedFont: ParsedTrueTypeFont,
    private val resource: PreparedFontResource,
) : FontFace {
    override val metadata: FontFaceMetadata = parsedFont.metadata
    override val id: FontFaceId = FontFaceId(source = sourceId, faceIndex = 0)

    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> {
        if (descriptor.layoutSize.value <= 0f) {
            return failure(
                FontError.InvalidInstanceDescriptor(
                    message = "Font instance layout size must be finite and positive.",
                    location = FontDiagnosticLocation.Face(0),
                ),
            )
        }
        if (
            descriptor.geometry.normalizedAxes.isNotEmpty() ||
            descriptor.geometry.syntheticBold ||
            descriptor.geometry.syntheticItalic
        ) {
            return failure(
                FontError.InvalidInstanceDescriptor(
                    message = "Variation axes and synthetic geometry are not supported by this TrueType face.",
                    location = FontDiagnosticLocation.Face(0),
                ),
            )
        }
        return FontOperationResult.Success(
            TrueTypeFontInstance(
                key = instanceKey(descriptor),
                descriptor = descriptor,
                resource = resource,
                faceId = id,
                sourceId = sourceId,
            ),
        )
    }

    private fun instanceKey(descriptor: FontInstanceDescriptor): FontInstanceKey =
        FontInstanceKey(
            face = id,
            interpretation = FontDataInterpretationVersion(
                pipelineId = "org.graphiks.kalligraphie.true-type",
                version = "1",
            ),
            layoutSize = descriptor.layoutSize,
            geometry = descriptor.geometry,
        )
}

private data class TrueTypeFontInstance(
    override val key: FontInstanceKey,
    private val descriptor: FontInstanceDescriptor,
    private val resource: PreparedFontResource,
    private val faceId: FontFaceId,
    private val sourceId: FontSourceId,
) : FontInstance {
    override fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> {
        return resource.preparedFont.resolveGlyph(codePoint)
    }

    override fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        resource.preparedFont.readGlyphMetrics(glyphId, descriptor.layoutSize.value)

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
                    "Variant render assets are not supported by this embedded TrueType face.",
                    FontDiagnosticLocation.Face(0),
                ),
            )
        }
        if (resolver.sourceId != sourceId) {
            return failure(FontError.InvalidFontData("Resolver source does not match this face.", FontDiagnosticLocation.Face(0)))
        }
        if (resolver !is EmbeddedFontAssetResolver) {
            return failure(FontError.InvalidFontData("Resolver was not opened by the embedded TrueType catalog.", FontDiagnosticLocation.Face(0)))
        }
        val lease = resolver.acquireAssetLease()
            ?: return failure(FontError.ResourceClosed("Asset resolver is closed."))
        return try {
            val handle = TrueTypeRenderAssetHandle(
                faceId = faceId,
                resourceLease = lease,
                profile = outlineProfile,
            )
            FontOperationResult.Success(handle)
        } catch (throwable: Throwable) {
            lease.release()
            throw throwable
        }
    }

}

private class TrueTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    private val profile: org.graphiks.kalligraphie.api.OutlineProfile,
) : FontRenderAssetHandle {
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                TrueTypeRenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
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
            val preparedFont = resourceLease?.preparedFont
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val outline = when (val result = preparedFont.readGlyphOutline(GlyphId(request.glyphId), profile, cancellationToken)) {
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

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
    FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
