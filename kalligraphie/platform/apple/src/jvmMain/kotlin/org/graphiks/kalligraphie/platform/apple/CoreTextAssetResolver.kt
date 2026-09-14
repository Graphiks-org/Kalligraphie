package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Owns its private portable resolver and translates exact selections into issued platform keys. */
internal class CoreTextAssetResolver(override val generation: FontCatalogGeneration,
    private val delegate: FontAssetResolverHandle, private val sources: Map<FontFaceId, CoreTextCapturedSource>,
    private val runtime: CoreTextRuntimeIdentity, private val bindings: CoreTextBindings,
    private val admission: CoreTextByteAdmission, private val cache: CoreTextContextCache) : FontAssetResolverHandle {
    private val owner = CoreTextResourceOwner {
        try { delegate.close() } finally { cache.resolverDrained() }
    }
    fun acquire(instance: FontInstance, variant: FontRenderVariantSnapshot, requirements: FontAccessRequirementsSnapshot,
        token: CancellationToken): FontOperationResult<FontRenderAssetHandle> = admittedOperation(token) {
        coreTextResult {
            checkCancellation(token)
            if (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE) fail(FontError.UnsupportedRepresentationProfile("Renderable access is required."))
            var refusal: FontOperationResult.Failure? = null
            for (profile in requirements.acceptedProfiles) {
                checkCancellation(token)
                val result = if (profile is PlatformHandleProfile) nativeResult {
                    if (profile != runtime.profile) fail(FontError.UnsupportedRepresentationProfile("This platform bridge profile does not match CoreText."))
                    val source = sources[instance.key.face]
                        ?: fail(FontError.AssetUnavailable("The instance source is absent from this captured generation."))
                    if (!source.platformEligible) fail(FontError.UnsupportedRepresentationProfile("This source subset has no CoreText platform route."))
                    var key = FontRenderAssetKey(instance.key, variant.key, profile, generation, variant.takeUnless { it == FontRenderVariantSnapshot.default })
                    key = key.copy(platformContext = runtime.context(key))
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
            if (key.representationProfile is PlatformHandleProfile) {
                if (key.representationProfile != runtime.profile || key.platformContext != runtime.context(key)) fail(FontError.AssetUnavailable("Platform key has a different bridge/runtime or invalid reopening token."))
                nativeResult { nativeAsset(source, key, cancellationToken) }
            } else {
                if (key.platformContext != null) fail(FontError.AssetUnavailable("Portable asset key cannot carry platform context."))
                val variant = key.variantSnapshot ?: if (key.variant == FontRenderVariantKey.default) FontRenderVariantSnapshot.default
                    else fail(FontError.AssetUnavailable("Non-default portable variant requires its complete snapshot."))
                wrapPortable(delegate.reopen(key.copy(generation = delegate.generation), cancellationToken), key.fontInstanceKey, variant,
                    key.representationProfile, cancellationToken)
            }
        }
    }
    private fun nativeAsset(source: CoreTextCapturedSource, key: FontRenderAssetKey, token: CancellationToken): FontRenderAssetHandle {
        checkCancellation(token)
        CoreTextFontContext.validate(source, key)
        cache.get(key, token)?.let { return it }
        val context = CoreTextFontContext.create(source, key, bindings, admission, token)
        var resourceOwner: CoreTextResourceOwner? = null
        var transferred = false
        try {
            resourceOwner = CoreTextResourceOwner { context.release(); FontOperationResult.Success(Unit) }
            val asset = CoreTextPlatformAsset(key, context, resourceOwner)
            checkCancellation(token)
            cache.retain(key, context, resourceOwner, source.bytes.size.toLong(), token)
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
            val completed = result
            var transferable = false
            var cancelled = completed is FontOperationResult.Cancelled
            try {
                // Ownership is protected before release, including its callback/result allocation.
                val drainage = cleanupResult { operation.closeResult() }
                if (completed != null) {
                    var primary = completed
                    if (completed is FontOperationResult.Success && token.isCancellationRequested()) {
                        cancelled = true
                        primary = FontOperationResult.Cancelled(completed.diagnostics)
                        result = primary
                    }
                    result = completeCoreTextCleanup(primary, drainage)
                    transferable = result is FontOperationResult.Success
                }
            } catch (_: OutOfMemoryError) {
                result = allocationOutcome(result, cancelled)
            } finally {
                if (!transferable && completed is FontOperationResult.Success) {
                    val closed = cleanupResult { completed.value.close() }
                    val primary = result ?: ALLOCATION_FAILURE
                    result = try { completeCoreTextCleanup(primary, closed) }
                    catch (_: OutOfMemoryError) { allocationOutcome(primary, cancelled) }
                }
            }
        }
        checkNotNull(result)
    }
    private inline fun cleanupResult(block: () -> FontOperationResult<Unit>): FontOperationResult<Unit> =
        try { block() } catch (_: OutOfMemoryError) { ALLOCATION_FAILURE }

    private fun allocationOutcome(primary: FontOperationResult<*>?, cancelled: Boolean): FontOperationResult<Nothing> =
        if (primary is FontOperationResult.Cancelled) primary else if (cancelled) CANCELLED else ALLOCATION_FAILURE

    override fun close(): FontOperationResult<Unit> = cleanupResult {
        val portable = cleanupResult { owner.closeResult() }
        val nativeCache = cache.fault()
        when {
            nativeCache is FontOperationResult.Success -> portable
            portable is FontOperationResult.Success -> nativeCache
            else -> portable.withCoreTextDiagnostics(portable.coreTextDiagnostics() + nativeCache.coreTextDiagnostics() +
                (nativeCache as FontOperationResult.Failure).error.toDiagnostic())
        }
    }

    private companion object {
        // Initialized before any resolver instance/admitted asset, so exhaustion fallback allocates nothing.
        val ALLOCATION_FAILURE = FontOperationResult.Failure(FontError.FontDataFailure("font.native-allocation-failed",
            "Platform resolver cleanup could not allocate its result.", FontDiagnosticLocation.Source))
        val CANCELLED = FontOperationResult.Cancelled()
    }
}
