package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*

/** Exact-source immutable catalogue preserving portable faces and interpretation. */
public interface CoreTextFontCatalogSnapshot : FontCatalogSnapshot {
    /** Explicit bridge profile available only on eligible static monochrome TrueType faces. */
    public val nativeProfile: NativeHandleProfile
}

/** Opt-in factory; loading the portable library alone never loads Apple native libraries. */
public object CoreTextFontCatalog {
    /**
     * Captures exact immutable sources after a complete allocation-free bounded preflight.
     * Requires macOS 15+ x64/arm64 JVM. Unknown estimates, size/source violations, limits and
     * native operational failures are typed; unsupported native subsets retain portable routes.
     * Safe to call concurrently; cancellation transfers no snapshot and abandons temporary data.
     * Native calls are not interruptible; cancellation checks resume after each call.
     */
    public fun capture(portable: FontCatalogSnapshot, policy: CoreTextFontAccessPolicy,
        cancellationToken: CancellationToken = CancellationToken.none): FontOperationResult<CoreTextFontCatalogSnapshot> =
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
            CapturedCoreTextCatalog(portable, sources, runtime, bindings, admission)
        }
}

internal class CapturedCoreTextCatalog(private val portable: FontCatalogSnapshot,
    private val sources: Map<FontFaceId, CoreTextCapturedSource>, private val runtime: CoreTextRuntimeIdentity,
    private val bindings: CoreTextBindings, private val admission: CoreTextByteAdmission) : CoreTextFontCatalogSnapshot {
    override val generation = FontCatalogGeneration(FontProviderId("org.graphiks.kalligraphie.coretext"), java.util.UUID.randomUUID().toString())
    override val nativeProfile: NativeHandleProfile = runtime.profile
    override val faces: List<FontFaceRecord> = java.util.Collections.unmodifiableList(portable.faces.map { record ->
        record.copy(capabilities = record.capabilities.copy(nativeHandle = sources.getValue(record.id).nativeEligible))
    })
    override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> = nativeResult {
        val delegate = portable.openAssetResolver().valueOrAbort()
        var transferred = false
        try { CoreTextAssetResolver(generation, delegate, sources, runtime, bindings, admission).also { transferred = true } }
        finally { if (!transferred) delegate.close() }
    }
    override fun resolveFace(faceId: FontFaceId, requirements: FontAccessRequirementsSnapshot): FontOperationResult<FontFace> = nativeResult {
        val nativeAccepted = sources[faceId]?.nativeEligible == true && runtime.profile in requirements.acceptedProfiles
        val underlyingRequirements = if (requirements.mode == FontAccessRequirementsSnapshot.Mode.LAYOUT_ONLY || nativeAccepted) {
            FontAccessRequirementsSnapshot.layoutOnly()
        } else {
            val profiles = requirements.acceptedProfiles.filterNot { it is NativeHandleProfile }
            if (profiles.isEmpty()) fail(FontError.UnsupportedRepresentationProfile("This face cannot provide an accepted CoreText native profile."))
            FontAccessRequirementsSnapshot.renderable(profiles, requirements.portableDataRequired)
        }
        val face = portable.resolveFace(faceId, underlyingRequirements).valueOrAbort()
        CoreTextFontFace(face, generation, sources[faceId], runtime)
    }
}
