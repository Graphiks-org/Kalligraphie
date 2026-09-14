package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Owns its private portable resolver and translates exact selections into issued native keys. */
internal class CoreTextAssetResolver(override val generation: FontCatalogGeneration,
    private val delegate: FontAssetResolverHandle, private val sources: Map<FontFaceId, CoreTextCapturedSource>,
    private val runtime: CoreTextRuntimeIdentity, private val bindings: CoreTextBindings,
    private val admission: CoreTextByteAdmission) : FontAssetResolverHandle {
    private val owner = CoreTextResourceOwner { delegate.close() }
    fun acquire(instance: FontInstance, variant: FontRenderVariantSnapshot, requirements: FontAccessRequirementsSnapshot,
        token: CancellationToken): FontOperationResult<FontRenderAssetHandle> = admittedOperation(token) {
        coreTextResult {
            checkCancellation(token)
            if (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE) fail(FontError.UnsupportedRepresentationProfile("Renderable access is required."))
            var refusal: FontOperationResult.Failure? = null
            for (profile in requirements.acceptedProfiles) {
                checkCancellation(token)
                val result = if (profile is NativeHandleProfile) nativeResult {
                    if (profile != runtime.profile) fail(FontError.UnsupportedRepresentationProfile("This native bridge profile does not match CoreText."))
                    val source = sources[instance.key.face]
                        ?: fail(FontError.AssetUnavailable("The instance source is absent from this captured generation."))
                    if (!source.nativeEligible) fail(FontError.UnsupportedRepresentationProfile("This source subset has no CoreText native route."))
                    var key = FontRenderAssetKey(instance.key, variant.key, profile, generation, variant.takeUnless { it == FontRenderVariantSnapshot.default })
                    key = key.copy(nativeContext = runtime.context(key))
                    nativeAsset(source, key, token)
                } else {
                    val portableRequirements = FontAccessRequirementsSnapshot.renderable(listOf(profile), requirements.portableDataRequired)
                    wrapPortable(instance.acquireRenderAsset(delegate, variant, portableRequirements, token), instance.key, variant, profile, token)
                }
                when (result) {
                    is FontOperationResult.Success -> return@coreTextResult result
                    is FontOperationResult.Cancelled -> throw CoreTextAbort(result)
                    is FontOperationResult.Failure -> {
                        if (result.error !is FontError.UnsupportedRepresentationProfile &&
                            result.error !is FontError.GlyphRepresentationUnavailable && result.error !is FontError.AssetUnavailable) throw CoreTextAbort(result)
                        if (refusal == null) refusal = result
                    }
                }
            }
            throw CoreTextAbort(refusal ?: FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile("No accepted asset route is available.")))
        }
    }
    override fun reopen(key: FontRenderAssetKey): FontOperationResult<FontRenderAssetHandle> = reopen(key, CancellationToken.none)
    override fun reopen(key: FontRenderAssetKey, cancellationToken: CancellationToken): FontOperationResult<FontRenderAssetHandle> = admittedOperation(cancellationToken) {
        coreTextResult {
            checkCancellation(cancellationToken)
            if (key.generation != generation) fail(FontError.IncompatibleCatalogGeneration("Asset key belongs to another adapted provider generation."))
            val source = sources[key.fontInstanceKey.face] ?: fail(FontError.AssetUnavailable("Asset face is absent from the captured generation."))
            if (key.representationProfile is NativeHandleProfile) {
                if (key.representationProfile != runtime.profile || key.nativeContext != runtime.context(key)) fail(FontError.AssetUnavailable("Native key has a different bridge/runtime or invalid reopening token."))
                nativeResult { nativeAsset(source, key, cancellationToken) }
            } else {
                if (key.nativeContext != null) fail(FontError.AssetUnavailable("Portable asset key cannot carry native context."))
                val variant = key.variantSnapshot ?: if (key.variant == FontRenderVariantKey.default) FontRenderVariantSnapshot.default
                    else fail(FontError.AssetUnavailable("Non-default portable variant requires its complete snapshot."))
                wrapPortable(delegate.reopen(key.copy(generation = delegate.generation), cancellationToken), key.fontInstanceKey, variant,
                    key.representationProfile, cancellationToken)
            }
        }
    }
    private fun nativeAsset(source: CoreTextCapturedSource, key: FontRenderAssetKey, token: CancellationToken): FontRenderAssetHandle {
        val context = CoreTextFontContext.create(source, key, bindings, admission, token)
        var resourceOwner: CoreTextResourceOwner? = null
        var transferred = false
        try {
            resourceOwner = CoreTextResourceOwner { context.release(); FontOperationResult.Success(Unit) }
            val asset = CoreTextNativeAsset(key, context, resourceOwner)
            checkCancellation(token)
            transferred = true
            return asset
        } finally { if (!transferred) { if (resourceOwner == null) context.release() else resourceOwner.close() } }
    }
    private fun wrapPortable(result: FontOperationResult<FontRenderAssetHandle>, instance: FontInstanceKey,
        variant: FontRenderVariantSnapshot, profile: GlyphRepresentationProfile, token: CancellationToken): FontOperationResult<FontRenderAssetHandle> = adaptCoreTextAsset(result) { asset ->
            checkCancellation(token)
            if (asset.key.fontInstanceKey != instance || asset.key.variant != variant.key || asset.key.representationProfile != profile ||
                asset.key.generation != delegate.generation || asset.key.variantSnapshot != variant.takeUnless { it == FontRenderVariantSnapshot.default }) {
                fail(FontError.AssetUnavailable("Portable provider issued an asset for a different exact selection."))
            }
            CoreTextPortableAsset(asset, asset.key.copy(generation = generation))
    }
    private inline fun admittedOperation(token: CancellationToken,
        block: () -> FontOperationResult<FontRenderAssetHandle>): FontOperationResult<FontRenderAssetHandle> = coreTextResult {
        val operation = owner.acquireChild() ?: fail(FontError.ResourceClosed("CoreText asset resolver is closed."))
        var result: FontOperationResult<FontRenderAssetHandle>? = null
        try {
            result = coreTextResult { block() }
        } finally {
            val drainage = operation.closeResult()
            val completed = result
            if (completed != null) {
                if (completed is FontOperationResult.Success) {
                    var transferable = false
                    try {
                        val primary = if (token.isCancellationRequested()) FontOperationResult.Cancelled(completed.diagnostics) else completed
                        result = completeCoreTextCleanup(primary, drainage)
                        transferable = result is FontOperationResult.Success
                    } finally {
                        if (!transferable) result = completeCoreTextCleanup(checkNotNull(result), completed.value.close())
                    }
                } else result = completeCoreTextCleanup(completed, drainage)
            }
        }
        checkNotNull(result)
    }
    override fun close(): FontOperationResult<Unit> = owner.closeResult()
}
