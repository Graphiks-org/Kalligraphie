package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Exact-source immutable catalogue preserving portable faces and interpretation. */
public interface CoreTextFontCatalogSnapshot : FontCatalogSnapshot {
    /** Explicit bridge profile available only on eligible static monochrome TrueType faces. */
    public val platformProfile: PlatformHandleProfile
    /**
     * Opens a private portable resolver. Its first close reports immediately available cleanup;
     * closure never waits for admitted acquire/reopen operations. The last completing operation
     * reports deferred cleanup refusal as terminal failure (or retains primary cancellation),
     * closing any untransferred asset. Already transferred owners remain independent.
     * Native cache cleanup faults are reported separately on first and repeated resolver close,
     * including faults learned after admitted operations drain; they never replace an acquisition
     * success. The last drained resolver releases this capture's evictable native contexts.
     */
    public override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle>
}

/** Opt-in factory; loading the portable library alone never loads Apple native libraries. */
public object CoreTextFontCatalog {
    /**
     * Captures exact immutable sources after a complete allocation-free bounded preflight.
     * Requires macOS 15+ x64/arm64 JVM. Unknown estimates, size/source violations, limits and
     * platform operational failures are typed; unsupported platform subsets retain portable routes.
     * Safe to call concurrently; cancellation transfers no snapshot and abandons temporary data.
     * Native calls are not interruptible; cancellation checks resume after each call.
     * [cachePolicy] bounds independently owned native contexts for this capture. [cacheScope]
     * adds aggregate bounds across explicitly attached captures; closing it disables retention
     * while consumer owners and new acquisitions remain usable. Without a scope, enabled retention
     * uses this capture's local budget. The portable catalogue's own cache is not reconfigured.
     * No additional native retention occurs with the default disabled policy.
     * Pass the same scope to the portable capture to include its representations. Each retained
     * context charges the known N-byte CFData source copy and four owned native resource units,
     * not total OS memory or malloc calls. Reservations, retirement and residual uncertainty stay
     * charged at all levels until cache reference release is confirmed. Resolver close reports
     * known deferred cache cleanup faults on first and repeated calls without retrying releases.
     * Drain resolvers/scopes outside the rendering critical path. Trailing parameters require
     * JVM consumer recompilation; custom providers' independent caches do not participate.
     */
    public fun capture(portable: FontCatalogSnapshot, policy: CoreTextFontAccessPolicy,
        cancellationToken: CancellationToken = CancellationToken.none,
        cachePolicy: FontMaterializationCachePolicy = FontMaterializationCachePolicy.disabled,
        cacheScope: FontCacheScope? = null): FontOperationResult<CoreTextFontCatalogSnapshot> =
        nativeResult {
            checkCancellation(cancellationToken)
            val platform = CoreTextRuntimeIdentity.checkPlatform()
            val admission = CoreTextByteAdmission(policy.maxTransientOwnedBytes)
            val sources = CoreTextSourceCapture.capture(portable, policy, admission, cancellationToken)
            checkCancellation(cancellationToken)
            val bindings = CoreTextBindings()
            checkCancellation(cancellationToken)
            val runtime = CoreTextRuntimeIdentity.capture(platform, bindings)
            checkCancellation(cancellationToken)
            val cache = CoreTextContextCache(cachePolicy, cacheScope)
            checkCancellation(cancellationToken)
            CapturedCoreTextCatalog(portable, sources, runtime, bindings, admission, cache)
        }
}

internal class CapturedCoreTextCatalog(private val portable: FontCatalogSnapshot,
    private val sources: Map<FontFaceId, CoreTextCapturedSource>, private val runtime: CoreTextRuntimeIdentity,
    private val bindings: CoreTextBindings, private val admission: CoreTextByteAdmission,
    private val cache: CoreTextContextCache) : CoreTextFontCatalogSnapshot {
    override val generation = FontCatalogGeneration(FontProviderId("org.graphiks.kalligraphie.coretext"), java.util.UUID.randomUUID().toString())
    override val platformProfile: PlatformHandleProfile = runtime.profile
    override val faces: List<FontFaceRecord> = java.util.Collections.unmodifiableList(portable.faces.map { record ->
        record.copy(capabilities = record.capabilities.copy(platformHandle = sources.getValue(record.id).platformEligible))
    })
    override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> =
        adaptCoreTextOwnedResult(portable.openAssetResolver(), { it.close() }) { delegate ->
            cache.resolverOpened()
            var transferred = false
            try {
                val resolver = CoreTextAssetResolver(generation, delegate, sources, runtime, bindings, admission, cache)
                transferred = true
                resolver
            } finally { if (!transferred) cache.resolverDrained() }
    }
    override fun resolveFace(faceId: FontFaceId, requirements: FontAccessRequirementsSnapshot): FontOperationResult<FontFace> = coreTextResult {
        val platformAccepted = sources[faceId]?.platformEligible == true && runtime.profile in requirements.acceptedProfiles
        val underlyingRequirements = if (requirements.mode == FontAccessRequirementsSnapshot.Mode.LAYOUT_ONLY || platformAccepted) {
            FontAccessRequirementsSnapshot.layoutOnly()
        } else {
            val profiles = requirements.acceptedProfiles.filterNot { it is PlatformHandleProfile }
            if (profiles.isEmpty()) fail(FontError.UnsupportedRepresentationProfile("This face cannot provide an accepted CoreText platform profile."))
            FontAccessRequirementsSnapshot.renderable(profiles, requirements.portableDataRequired)
        }
        adaptCoreTextResult(portable.resolveFace(faceId, underlyingRequirements)) { face ->
            CoreTextFontFace(face, generation, sources[faceId], runtime)
        }
    }
}
