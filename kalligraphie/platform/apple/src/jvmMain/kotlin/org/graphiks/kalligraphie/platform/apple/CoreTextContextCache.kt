@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.font.core.FontCacheCharge
import org.graphiks.kalligraphie.font.core.FontCacheCoordinator
import org.graphiks.kalligraphie.font.core.FontCachePayload

/** Capture-stable participation; contains no captured source, catalogue or resolver. */
internal class CoreTextContextCache(policy: FontMaterializationCachePolicy, scope: FontCacheScope?) {
    private val coordinator = FontCacheCoordinator.resolve(scope, policy)
    private val participant = coordinator.participant(policy)
    // A resolver remains counted until its admission parent and every admitted operation drain.
    private var resolvers = 0L

    fun resolverOpened() = synchronized(this) { resolvers = Math.addExact(resolvers, 1L) }
    fun resolverDrained() {
        val last = synchronized(this) { --resolvers == 0L }
        // Reopening can race this reusable epoch drainage. Only optimization entries are removed;
        // pending/residual charges and the stable participant identity remain with the coordinator.
        if (last) coordinator.clearParticipant(participant)
    }
    fun fault(): FontOperationResult<Unit> = coordinator.fault(participant)

    fun get(key: FontRenderAssetKey, token: CancellationToken): CoreTextPlatformAsset? {
        val payload = coordinator.get(participant, key.fontInstanceKey.face, key) as? CoreTextContextPayload ?: return null
        // Never read the context until an independent owner defeats concurrent cache retirement.
        val owner = payload.acquireChild() ?: return null
        var transferred = false
        try {
            val asset = CoreTextPlatformAsset(key, payload.context, owner)
            checkCancellation(token)
            transferred = true
            return asset
        } finally { if (!transferred) owner.close() }
    }

    fun retain(key: FontRenderAssetKey, context: CoreTextFontContext, consumer: CoreTextResourceOwner,
        sourceBytes: Long, token: CancellationToken) {
        val charge = try { charge(key, sourceBytes) } catch (_: OutOfMemoryError) { return }
            catch (_: ArithmeticException) { return }
        val reservation = coordinator.reserve(participant, key.fontInstanceKey.face, key, charge) ?: return
        var payload: CoreTextContextPayload? = null
        var published = false
        try {
            // Allocate the release envelope before acquiring a cache-owned child, so even an
            // exhausted heap cannot strand an acquired child outside the charged reservation.
            val candidate = CoreTextContextPayload(context)
            payload = candidate
            val child = consumer.acquireChild() ?: return
            candidate.install(child)
            checkCancellation(token)
            published = reservation.publish(candidate)
        } catch (_: OutOfMemoryError) {
            // Retention is optional; the already proven consumer context remains usable.
        } finally { if (!published) reservation.abandon(payload) }
    }

    private fun charge(key: FontRenderAssetKey, sourceBytes: Long): FontCacheCharge {
        // 4096 covers coordinator/index/reservation/queue/ledger metadata (two fixed-depth trie
        // paths). 1024 additionally covers native payload, shared owner, context and key objects.
        // Every variable-length field of the complete eligible key adds conservative UTF-16 bytes.
        var bytes = 4096L + 1024L
        fun text(value: String) { bytes = Math.addExact(bytes, Math.multiplyExact(value.length.toLong(), 2L)) }
        when (val source = key.fontInstanceKey.face.source) {
            is FontSourceId.Portable -> text(source.contentDigest.value)
            is FontSourceId.Opaque -> { text(source.providerId); text(source.catalogGeneration); text(source.sourceToken) }
        }
        text(key.fontInstanceKey.interpretation.pipelineId); text(key.fontInstanceKey.interpretation.version)
        text(key.generation.provider.value); text(key.generation.value); text(key.variant.value)
        val profile = key.representationProfile as PlatformHandleProfile
        text(profile.bridgeKind); text(profile.bridgeId); text(profile.bridgeVersion)
        val proof = checkNotNull(key.platformContext)
        text(proof.reopenToken)
        text(proof.routeIdentity.bridgeKind); text(proof.routeIdentity.bridgeId)
        text(proof.routeIdentity.bridgeVersion); text(proof.routeIdentity.runtimeInterpretationId)
        // Geometry is empty and variant canonical by pre-lookup validation. CFData retains N
        // known native bytes; CFData, CGDataProvider, CGFont and CTFont own four resource units.
        return FontCacheCharge(bytes, 0L, sourceBytes, 4L)
    }
}

/** Cache payload owns only the minimal native context and one independent resource reference. */
private class CoreTextContextPayload(val context: CoreTextFontContext) : FontCachePayload {
    private var owner: CoreTextResourceOwner? = null
    fun install(child: CoreTextResourceOwner) = synchronized(this) { owner = child }
    fun acquireChild(): CoreTextResourceOwner? = synchronized(this) { owner?.acquireChild() }
    override fun release(): FontOperationResult<Unit> {
        val releasing = synchronized(this) { owner.also { owner = null } }
        // Relinquishing this reference is success even when caller-exclusive children remain.
        // A final native cleanup refusal/throw is accounted conservatively by the coordinator.
        return releasing?.closeResult() ?: RELEASED
    }
    private companion object { val RELEASED = FontOperationResult.Success(Unit) }
}
