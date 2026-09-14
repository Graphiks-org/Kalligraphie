@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontCacheBudget
import org.graphiks.kalligraphie.api.FontCacheScope
import org.graphiks.kalligraphie.api.FontCacheScopeBackend
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import kotlin.concurrent.atomics.AtomicInt

/**
 * Conservative charge of one retained entry, including reservation and index metadata.
 * @suppress
 */
@KalligraphieInternalApi
public data class FontCacheCharge(
    /** Estimated managed bytes retained by this entry. */ public val retainedBytes: Long,
    /** Decoded pixels retained by this entry. */ public val decodedPixels: Long,
    /** Known or estimated cache-owned native bytes. */ public val nativeBytes: Long,
    /** Conservative units of cache-owned native resources. */ public val nativeAllocations: Long,
) {
    init {
        require(retainedBytes > 0)
        require(decodedPixels >= 0 && nativeBytes >= 0 && nativeAllocations >= 0)
    }
}

/**
 * Independent cache reference; never retains its participant, scope, source or resolver.
 * @suppress
 */
@KalligraphieInternalApi
public interface FontCachePayload {
    /** Relinquishes this reference once; incomplete or uncertain cleanup returns a failure. */
    public fun release(): FontOperationResult<Unit>
}

/**
 * Stable capture identity and local bounds, without captured source or consumer owners.
 * @suppress
 */
@KalligraphieInternalApi
public class FontCacheParticipant internal constructor(internal val identity: Any, internal val policy: FontMaterializationCachePolicy) {
    internal var record: CacheParticipantRecord? = null
    internal var epoch: Long = 0
    internal var firstFault: FontOperationResult.Failure? = null
}

/**
 * Already-accounted admission; acquire the cache child only after receiving this object.
 * @suppress
 */
@KalligraphieInternalApi
public class FontCacheReservation internal constructor(private val coordinator: FontCacheCoordinator, internal val entry: CacheNode) {
    /** Publishes an independently owned payload; on false the caller must [abandon] it. */
    public fun publish(payload: FontCachePayload): Boolean = coordinator.publish(entry, payload)

    /** Relinquishes an unpublished cache child, preserving its charge until confirmed cleanup. */
    public fun abandon(payload: FontCachePayload? = null): Unit = coordinator.abandon(entry, payload)
}

/**
 * Cross-module coordinator for simultaneous face, capture and shared retention bounds.
 *
 * Lookups and admissions try coordination once per decision and may skip caching on contention.
 * Safety acknowledgements and explicit closure finish their short structural sections eventually.
 * Resource calls run outside those sections. Charges survive index removal until confirmed release;
 * incomplete releases retain only conservative accounting and a preallocated first-fault summary.
 * @suppress
 */
@KalligraphieInternalApi
public class FontCacheCoordinator(private val budget: FontCacheBudget) : FontCacheScopeBackend {
    private val identity = Any()
    private val gate = AtomicInt(0)
    private var closed = false
    private val ledger = CacheLedger()
    private val victims = CacheQueue(0)
    private var participants: CacheParticipantRecord? = null
    private var firstFault: FontOperationResult.Failure? = null
    // Force fallback initialization before any reservation can own a charge or reference.
    private val incompleteRelease = INCOMPLETE_RELEASE
    private val releaseException = RELEASE_EXCEPTION
    private val releaseAllocationFailure = RELEASE_ALLOCATION_FAILURE
    private val completed = RELEASED
    private var measurement: FontCacheMeasurement? = null

    /**
     * Attaches a preallocated isolated-run recorder before any retention.
     * @suppress
     */
    public fun measure(recorder: FontCacheMeasurement) {
        lock()
        try {
            check(measurement == null && ledger.count == 0L && participants == null)
            ledger.measurementSlot = recorder.register(0, budget)
            measurement = recorder
        } finally { gate.store(0) }
    }

    /** Creates a lightweight capture identity without registering an empty record. */
    public fun participant(policy: FontMaterializationCachePolicy): FontCacheParticipant = FontCacheParticipant(identity, policy)

    /**
     * Returns an indexed payload, or a cache miss on contention or closure. Native adapters must
     * acquire a child immediately on this payload before reading any native context from it.
     */
    public fun get(participant: FontCacheParticipant, faceId: FontFaceId, completeKey: Any): FontCachePayload? {
        if (participant.identity !== identity) return null
        if (!gate.compareAndSet(0, 1)) { measurement?.contended(); return null }
        try {
            if (closed) return null
            val capture = participant.record ?: return null
            val face = capture.faces.get(faceId)
            measurement?.indexed(capture.faces.lastVisits)
            if (face == null) return null
            val entry = face.entries.get(completeKey)
            measurement?.indexed(face.entries.lastVisits)
            if (entry == null) return null
            if (entry.state != ACTIVE || entry.captureEpoch != participant.epoch || entry.faceEpoch != entry.face.epoch) return null
            promote(entry)
            return entry.payload
        } catch (_: FontCacheAllocationError) {
            return null
        } finally { gate.store(0) }
    }

    /**
     * Reserves all three budgets before the caller obtains a cache-owned child. Admission performs
     * at most two decisions and retires at most 32 victims; an unavailable optimization returns null.
     * All metadata needed to publish is allocated before the charged reservation is installed.
     */
    public fun reserve(participant: FontCacheParticipant, faceId: FontFaceId, completeKey: Any, charge: FontCacheCharge): FontCacheReservation? {
        var retained = false
        var attempts = 0
        var count = 0
        try {
            if (participant.identity !== identity || !charge.fits(participant.policy.perFace) ||
                !charge.fits(participant.policy.perCatalog) || !charge.fits(budget)) return null
            val entry = CacheNode(completeKey, charge)
            val reservation = FontCacheReservation(this, entry)
            val retired = arrayOfNulls<CacheNode>(MAX_VICTIMS)
            attempts++
            if (!gate.compareAndSet(0, 1)) { measurement?.contended(); return null }
            try {
                if (closed) return null
                val record = participant.record
                val face = indexed(record?.faces, faceId)
                if (indexed(face?.entries, completeKey) != null) return null
                if (fits(participant, face, charge)) {
                    val installed = install(participant, faceId, entry, reservation)
                    retained = installed != null
                    return installed
                }
                // Project successful acknowledgements only for selecting relevant victims.
                // Actual admission still uses unchanged total charges until cleanup finishes.
                val scopeAvailable = ledger.total.copy()
                val captureAvailable = record?.ledger?.total?.copy() ?: CacheAmounts()
                val faceAvailable = face?.ledger?.total?.copy() ?: CacheAmounts()
                while (count < MAX_VICTIMS) {
                    val victim = when {
                        !faceAvailable.fits(participant.policy.perFace, charge) -> face?.victims?.head
                        !captureAvailable.fits(participant.policy.perCatalog, charge) -> record?.victims?.head
                        !scopeAvailable.fits(budget, charge) -> victims.head
                        else -> null
                    } ?: break
                    retire(victim)
                    retired[count++] = victim
                    scopeAvailable.subtract(victim.charge)
                    if (victim.capture.participant === participant) {
                        captureAvailable.subtract(victim.charge)
                        if (victim.face.faceId == faceId) faceAvailable.subtract(victim.charge)
                    }
                }
            } finally { gate.store(0) }
            for (index in 0 until count) release(retired[index]!!)
            if (count == 0) return null
            attempts++
            if (!gate.compareAndSet(0, 1)) { measurement?.contended(); return null }
            try {
                if (closed) return null
                val face = indexed(participant.record?.faces, faceId)
                if (indexed(face?.entries, completeKey) != null || !fits(participant, face, charge)) return null
                val installed = install(participant, faceId, entry, reservation)
                retained = installed != null
                return installed
            } finally { gate.store(0) }
        } catch (_: FontCacheAllocationError) {
            // All allocations precede charged mutations and retirement; release never allocates.
            return null
        } finally { measurement?.admission(retained, attempts, count) }
    }

    private fun fits(participant: FontCacheParticipant, face: CacheFaceRecord?, charge: FontCacheCharge): Boolean =
        ledger.total.fits(budget, charge) &&
            (participant.record?.ledger?.total?.fits(participant.policy.perCatalog, charge) ?: true) &&
            (face?.ledger?.total?.fits(participant.policy.perFace, charge) ?: true)

    private fun install(participant: FontCacheParticipant, faceId: FontFaceId, entry: CacheNode, reservation: FontCacheReservation): FontCacheReservation? {
        val capture = participant.record ?: CacheParticipantRecord(participant)
        val face = indexed(capture.faces, faceId) ?: CacheFaceRecord(faceId, capture)
        val faceInsertion = if (face.ledger.count == 0L) prepared(capture.faces, faceId, face) ?: return null else null
        val insertion = prepared(face.entries, entry.key!!, entry) ?: return null
        measurement?.let {
            if (capture.ledger.measurementSlot < 0) capture.ledger.measurementSlot = it.register(1, participant.policy.perCatalog)
            if (face.ledger.measurementSlot < 0) face.ledger.measurementSlot = it.register(2, participant.policy.perFace)
        }
        // No allocation, platform work, user callback or checked addition follows this boundary.
        if (participant.record == null) {
            capture.next = participants
            participants?.previous = capture
            participants = capture
            participant.record = capture
        }
        faceInsertion?.commit()
        if (faceInsertion != null) face.indexItem = faceInsertion.item
        insertion.commit()
        entry.indexItem = insertion.item
        entry.capture = capture
        entry.face = face
        entry.captureEpoch = participant.epoch
        entry.faceEpoch = face.epoch
        charge(entry, RESERVED, true)
        entry.state = RESERVED
        observe(entry)
        return reservation
    }

    internal fun publish(entry: CacheNode, payload: FontCachePayload): Boolean {
        if (!gate.compareAndSet(0, 1)) { measurement?.contended(); measurement?.unpublished(); return false }
        try {
            if (entry.state != RESERVED || closed || entry.captureEpoch != entry.capture.participant.epoch || entry.faceEpoch != entry.face.epoch) { measurement?.unpublished(); return false }
            entry.payload = payload
            transition(entry, ACTIVE)
            promote(entry)
            return true
        } finally { gate.store(0) }
    }

    internal fun abandon(entry: CacheNode, payload: FontCachePayload?) {
        lock()
        try {
            if (entry.state != RESERVED) return
            removed(entry.face.entries, entry.indexItem!!)
            entry.indexItem = null
            entry.key = null
            if (payload == null) {
                acknowledge(entry)
                return
            }
            entry.payload = payload
            transition(entry, RETIRING)
        } finally { gate.store(0) }
        release(entry)
    }

    /** Removes this capture's face entries; outstanding reservations and cleanup remain charged. */
    public fun clearFace(participant: FontCacheParticipant, faceId: FontFaceId) {
        if (participant.identity !== identity) return
        lock()
        val face: CacheFaceRecord?
        val epoch: Long
        try {
            face = participant.record?.faces?.get(faceId)
            if (face != null) face.epoch += 1L
            epoch = face?.epoch ?: 0L
        } finally { gate.store(0) }
        if (face != null) drain(face.victims, faceEpoch = epoch)
    }

    /** Clears one capture without resetting outstanding or residual accounting on reopening. */
    public fun clearParticipant(participant: FontCacheParticipant) {
        if (participant.identity !== identity) return
        lock()
        val capture: CacheParticipantRecord?
        val epoch: Long
        try {
            participant.epoch += 1L
            epoch = participant.epoch
            capture = participant.record
        } finally { gate.store(0) }
        if (capture != null) drain(capture.victims, captureEpoch = epoch)
    }

    /** Returns only this capture's bounded known cleanup fault, also after repeated closure. */
    public fun fault(participant: FontCacheParticipant): FontOperationResult<Unit> {
        if (participant.identity !== identity) return completed
        lock()
        try { return participant.firstFault ?: completed } finally { gate.store(0) }
    }

    /** Disables caching atomically and drains references outside coordination without retrying them. */
    override fun close(): FontOperationResult<Unit> {
        lock()
        try { closed = true } finally { gate.store(0) }
        drain(victims)
        lock()
        try { return firstFault ?: completed } finally { gate.store(0) }
    }

    private fun drain(queue: CacheQueue, captureEpoch: Long? = null, faceEpoch: Long? = null) {
        // A single pre-existing intrusive node is the chunk: even OOM cannot interrupt drainage.
        while (true) {
            lock()
            val entry: CacheNode
            try {
                entry = queue.head ?: return
                // New entries append after inaccessible old entries and belong to the reopened
                // capture/face. Old entries cannot be promoted after their epoch changes.
                if (captureEpoch != null && entry.captureEpoch >= captureEpoch) return
                if (faceEpoch != null && entry.faceEpoch >= faceEpoch) return
                retire(entry)
            } finally { gate.store(0) }
            release(entry)
        }
    }

    private fun promote(entry: CacheNode) {
        victims.promote(entry)
        entry.capture.victims.promote(entry)
        entry.face.victims.promote(entry)
    }

    private fun retire(entry: CacheNode) {
        victims.remove(entry)
        entry.capture.victims.remove(entry)
        entry.face.victims.remove(entry)
        removed(entry.face.entries, entry.indexItem!!)
        entry.indexItem = null
        entry.key = null
        transition(entry, RETIRING)
    }

    private fun release(entry: CacheNode) {
        var fault: FontOperationResult.Failure? = null
        try {
            if (entry.payload!!.release() !is FontOperationResult.Success) fault = incompleteRelease
        } catch (_: FontCacheAllocationError) { fault = releaseAllocationFailure
        } catch (_: Throwable) { fault = releaseException }
        lock()
        try {
            // Null before acknowledgement: relinquishing a reference creates room, index removal does not.
            entry.payload = null
            if (fault == null) acknowledge(entry) else {
                transition(entry, RESIDUAL)
                if (entry.capture.participant.firstFault == null) entry.capture.participant.firstFault = fault
                if (firstFault == null) firstFault = fault
            }
        } finally { gate.store(0) }
    }

    private fun transition(entry: CacheNode, state: Int) {
        charge(entry, entry.state, false)
        charge(entry, state, true)
        entry.state = state
        observe(entry)
    }

    private fun observe(entry: CacheNode) {
        val recorder = measurement ?: return
        recorder.record(ledger, entry.state)
        recorder.record(entry.capture.ledger, entry.state)
        recorder.record(entry.face.ledger, entry.state)
    }

    private fun charge(entry: CacheNode, state: Int, add: Boolean) {
        ledger.update(state, entry.charge, add)
        entry.capture.ledger.update(state, entry.charge, add)
        entry.face.ledger.update(state, entry.charge, add)
    }

    private fun acknowledge(entry: CacheNode) {
        charge(entry, entry.state, false)
        entry.state = RELEASED_STATE
        observe(entry)
        val face = entry.face
        val capture = entry.capture
        if (face.ledger.count == 0L) {
            removed(capture.faces, face.indexItem!!)
            face.indexItem = null
        }
        if (capture.ledger.count == 0L) {
            if (capture.previous == null) participants = capture.next else capture.previous!!.next = capture.next
            capture.next?.previous = capture.previous
            capture.previous = null
            capture.next = null
            capture.participant.record = null
        }
    }

    private fun lock() { while (!gate.compareAndSet(0, 1)) { /* Mandatory accounting acknowledgement. */ } }

    private fun <T> indexed(index: CacheIndex<T>?, key: Any): T? {
        if (index == null) return null
        val value = index.get(key)
        measurement?.indexed(index.lastVisits)
        return value
    }

    private fun <T> prepared(index: CacheIndex<T>, key: Any, value: T): CacheIndex.Insertion<T>? {
        val insertion = index.prepare(key, value)
        measurement?.prepared(index.lastVisits)
        return insertion
    }

    private fun <T> removed(index: CacheIndex<T>, item: CacheIndex.Item<T>) {
        index.remove(item)
        measurement?.removed(index.lastVisits)
    }

    /** Concrete facade assembly, rejecting alternate backends rather than creating a replacement. */
    public companion object {
        /** Uses the explicit domain, including a closed one, or the historical private catalog bound. */
        public fun resolve(scope: FontCacheScope?, policy: FontMaterializationCachePolicy): FontCacheCoordinator =
            if (scope == null) FontCacheCoordinator(policy.perCatalog)
            else scope.backend as? FontCacheCoordinator ?: error("Font cache scopes must be assembled by Kalligraphie.")
    }
}

private const val MAX_VICTIMS = 32
private const val ACTIVE = 0
private const val RESERVED = 1
private const val RETIRING = 2
private const val RESIDUAL = 3
private const val RELEASED_STATE = 4
private val RELEASED = FontOperationResult.Success(Unit)
private val INCOMPLETE_RELEASE = cleanupFault("font.cache-release-incomplete", "A cache reference could not confirm complete cleanup; its conservative charge remains reserved.")
private val RELEASE_EXCEPTION = cleanupFault("font.cache-release-failed", "A cache reference threw during cleanup; its conservative charge remains reserved.")
private val RELEASE_ALLOCATION_FAILURE = cleanupFault("font.cache-release-allocation-failed", "Cache cleanup could not allocate its result; its conservative charge remains reserved.")
private fun cleanupFault(code: String, message: String) = FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source))

private fun FontCacheCharge.fits(budget: FontCacheBudget): Boolean = retainedBytes <= budget.retainedBytes &&
    decodedPixels <= budget.decodedPixels && nativeBytes <= budget.nativeBytes && nativeAllocations <= budget.nativeAllocations

internal class CacheAmounts(var bytes: Long = 0, var pixels: Long = 0, var nativeBytes: Long = 0, var allocations: Long = 0) {
    fun copy() = CacheAmounts(bytes, pixels, nativeBytes, allocations)
    fun fits(budget: FontCacheBudget, charge: FontCacheCharge): Boolean = charge.retainedBytes <= budget.retainedBytes - bytes &&
        charge.decodedPixels <= budget.decodedPixels - pixels && charge.nativeBytes <= budget.nativeBytes - nativeBytes &&
        charge.nativeAllocations <= budget.nativeAllocations - allocations
    fun add(charge: FontCacheCharge) { bytes += charge.retainedBytes; pixels += charge.decodedPixels; nativeBytes += charge.nativeBytes; allocations += charge.nativeAllocations }
    fun subtract(charge: FontCacheCharge) { bytes -= charge.retainedBytes; pixels -= charge.decodedPixels; nativeBytes -= charge.nativeBytes; allocations -= charge.nativeAllocations }
}

internal class CacheLedger {
    var measurementSlot = -1
    val total = CacheAmounts()
    val categories = Array(4) { CacheAmounts() }
    var count = 0L
    fun update(state: Int, charge: FontCacheCharge, add: Boolean) {
        if (add) { total.add(charge); categories[state].add(charge); count++ }
        else { total.subtract(charge); categories[state].subtract(charge); count-- }
    }
}

internal class CacheParticipantRecord(val participant: FontCacheParticipant) {
    val ledger = CacheLedger()
    val faces = CacheIndex<CacheFaceRecord>()
    val victims = CacheQueue(1)
    var previous: CacheParticipantRecord? = null
    var next: CacheParticipantRecord? = null
}

internal class CacheFaceRecord(val faceId: FontFaceId, val capture: CacheParticipantRecord) {
    var indexItem: CacheIndex.Item<CacheFaceRecord>? = null
    val ledger = CacheLedger()
    val entries = CacheIndex<CacheNode>()
    val victims = CacheQueue(2)
    var epoch = 0L
}

internal class CacheNode(var key: Any?, val charge: FontCacheCharge) {
    var indexItem: CacheIndex.Item<CacheNode>? = null
    lateinit var capture: CacheParticipantRecord
    lateinit var face: CacheFaceRecord
    var captureEpoch = 0L
    var faceEpoch = 0L
    var state = RELEASED_STATE
    var payload: FontCachePayload? = null
    val previous = arrayOfNulls<CacheNode>(3)
    val next = arrayOfNulls<CacheNode>(3)
    val linked = BooleanArray(3)
}

internal class CacheQueue(private val level: Int) {
    var head: CacheNode? = null
    private var tail: CacheNode? = null
    fun promote(entry: CacheNode) {
        if (tail === entry) return
        remove(entry)
        entry.previous[level] = tail
        tail?.next?.set(level, entry)
        if (head == null) head = entry
        tail = entry
        entry.linked[level] = true
    }
    fun remove(entry: CacheNode) {
        if (!entry.linked[level]) return
        val previous = entry.previous[level]
        val next = entry.next[level]
        if (previous == null) head = next else previous.next[level] = next
        if (next == null) tail = previous else next.previous[level] = previous
        entry.previous[level] = null
        entry.next[level] = null
        entry.linked[level] = false
    }
}
