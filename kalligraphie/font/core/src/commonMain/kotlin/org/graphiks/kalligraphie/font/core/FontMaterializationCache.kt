@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontCacheScope
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontOperationResult.Success
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationKey
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
private class PortableFontCachePayload(result: Success<GlyphRepresentation>) : FontCachePayload {
    val result = AtomicReference<Success<GlyphRepresentation>?>(result)
    override fun release(): FontOperationResult<Unit> {
        result.store(null)
        return PORTABLE_RELEASED
    }
}

private val PORTABLE_RELEASED = Success(Unit)

/** Retains only immutable successes; payloads never reference source or consumer owners. */
@OptIn(ExperimentalAtomicApi::class)
internal class FontMaterializationCache(policy: FontMaterializationCachePolicy, scope: FontCacheScope? = null) {
    private val coordinator = FontCacheCoordinator.resolve(scope, policy)
    private val participant = coordinator.participant(policy)

    fun get(faceId: FontFaceId, key: GlyphRepresentationKey): Success<GlyphRepresentation>? =
        (coordinator.get(participant, faceId, key) as? PortableFontCachePayload)?.result?.load()

    fun put(faceId: FontFaceId, key: GlyphRepresentationKey, result: Success<GlyphRepresentation>, charge: FontCacheCharge) {
        val reservation = coordinator.reserve(participant, faceId, key, charge) ?: return
        var payload: PortableFontCachePayload? = null
        try {
            payload = PortableFontCachePayload(result)
            if (reservation.publish(payload)) return
        } catch (_: FontCacheAllocationError) {
            // Optional retention cannot turn a complete typographic result into a failure.
        }
        reservation.abandon(payload)
    }

    fun clearFace(faceId: FontFaceId) = coordinator.clearFace(participant, faceId)
}
