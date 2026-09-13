package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontCacheBudget
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult.Success
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationKey
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal data class FontCacheCharge(
    val retainedBytes: Long,
    val decodedPixels: Long,
    val nativeBytes: Long,
    val nativeAllocations: Long,
) {
    init {
        require(retainedBytes > 0L)
        require(decodedPixels >= 0L && nativeBytes >= 0L && nativeAllocations >= 0L)
    }
}

private data class CacheEntry(
    val faceId: FontFaceId,
    val key: GlyphRepresentationKey,
    val result: Success<GlyphRepresentation>,
    val charge: FontCacheCharge,
)

private data class CacheState(val entries: List<CacheEntry>)

private fun Iterable<CacheEntry>.fits(budget: FontCacheBudget): Boolean {
    var retainedBytes = 0L
    var decodedPixels = 0L
    var nativeBytes = 0L
    var nativeAllocations = 0L
    for (entry in this) {
        val charge = entry.charge
        if (charge.retainedBytes > budget.retainedBytes - retainedBytes ||
            charge.decodedPixels > budget.decodedPixels - decodedPixels ||
            charge.nativeBytes > budget.nativeBytes - nativeBytes ||
            charge.nativeAllocations > budget.nativeAllocations - nativeAllocations
        ) return false
        retainedBytes += charge.retainedBytes
        decodedPixels += charge.decodedPixels
        nativeBytes += charge.nativeBytes
        nativeAllocations += charge.nativeAllocations
    }
    return true
}

/** Atomic catalog-wide retention of complete immutable portable successes. */
@OptIn(ExperimentalAtomicApi::class)
internal class FontMaterializationCache(
    private val policy: FontMaterializationCachePolicy,
) {
    private val state = AtomicReference(CacheState(emptyList()))

    fun get(faceId: FontFaceId, key: GlyphRepresentationKey): Success<GlyphRepresentation>? {
        while (true) {
            val current = state.load()
            val index = current.entries.indexOfFirst { it.faceId == faceId && it.key == key }
            if (index < 0) return null
            val entry = current.entries[index]
            if (index == current.entries.lastIndex) return entry.result
            val promoted = current.entries.toMutableList()
            promoted.removeAt(index)
            promoted += entry
            if (state.compareAndSet(current, CacheState(promoted.toList()))) return entry.result
        }
    }

    fun put(
        faceId: FontFaceId,
        key: GlyphRepresentationKey,
        result: Success<GlyphRepresentation>,
        charge: FontCacheCharge,
    ) {
        val candidate = CacheEntry(faceId, key, result, charge)
        val admissible = listOf(candidate).fits(policy.perFace) &&
            listOf(candidate).fits(policy.perCatalog)
        while (true) {
            val current = state.load()
            val retained = current.entries
                .filterNot { it.faceId == faceId && it.key == key }
                .toMutableList()
            if (admissible) {
                retained += candidate
                while (!retained.filter { it.faceId == faceId }.fits(policy.perFace)) {
                    retained.removeAt(retained.indexOfFirst { it.faceId == faceId })
                }
                while (!retained.fits(policy.perCatalog)) retained.removeAt(0)
            } else if (retained.size == current.entries.size) {
                return
            }
            if (state.compareAndSet(current, CacheState(retained.toList()))) return
        }
    }

    fun clearFace(faceId: FontFaceId) {
        while (true) {
            val current = state.load()
            val retained = current.entries.filterNot { it.faceId == faceId }
            if (retained.size == current.entries.size) return
            if (state.compareAndSet(current, CacheState(retained))) return
        }
    }
}
