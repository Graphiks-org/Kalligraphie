package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Preserves the entire portable face/instance interpretation while adapting asset acquisition. */
internal class CoreTextFontFace(private val delegate: FontFace, private val generation: FontCatalogGeneration,
    private val source: CoreTextCapturedSource?, private val runtime: CoreTextRuntimeIdentity) : FontFace {
    override val id: FontFaceId get() = delegate.id
    override val metadata: FontFaceMetadata get() = delegate.metadata
    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> = adaptCoreTextResult(delegate.instantiate(descriptor)) { instance ->
        CoreTextFontInstance(instance, generation, source, runtime)
    }
}

internal class CoreTextFontInstance(private val delegate: FontInstance, private val generation: FontCatalogGeneration,
    private val source: CoreTextCapturedSource?, private val runtime: CoreTextRuntimeIdentity) : FontInstance by delegate {
    override fun estimateRenderAssetBytes(renderVariant: FontRenderVariantSnapshot, profile: GlyphRepresentationProfile): FontOperationResult<Long> =
        if (profile == runtime.profile && source?.platformEligible == true && renderVariant == FontRenderVariantSnapshot.default && key.geometry == FontGeometryParameters()) {
            // One controlled CFData source copy retained live; private OS/font allocations excluded.
            FontOperationResult.Success(source.bytes.size.toLong())
        } else if (profile is PlatformHandleProfile) {
            FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile("CoreText cannot estimate this platform profile, geometry or variant."))
        } else delegate.estimateRenderAssetBytes(renderVariant, profile)

    override fun acquireRenderAsset(resolver: FontAssetResolverHandle, variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot): FontOperationResult<FontRenderAssetHandle> =
        acquireRenderAsset(resolver, variant, requirements, CancellationToken.none)
    override fun acquireRenderAsset(resolver: FontAssetResolverHandle, variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> =
        if (cancellationToken.isCancellationRequested()) FontOperationResult.Cancelled()
        else if (variant == FontRenderVariantKey.default) acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements, cancellationToken)
        else FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile("CoreText requires a complete snapshot to reopen a non-default render variant."))
    override fun acquireRenderAsset(resolver: FontAssetResolverHandle, renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot): FontOperationResult<FontRenderAssetHandle> =
        acquireRenderAsset(resolver, renderVariant, requirements, CancellationToken.none)
    override fun acquireRenderAsset(resolver: FontAssetResolverHandle, renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> = coreTextResult {
        checkCancellation(cancellationToken)
        if (resolver.generation != generation) fail(FontError.IncompatibleCatalogGeneration("Resolver does not belong to the adapted instance generation."))
        if (resolver !is CoreTextAssetResolver) fail(FontError.AssetUnavailable("Resolver cannot acquire this adapted CoreText instance."))
        resolver.acquire(delegate, renderVariant, requirements, cancellationToken)
    }
}
