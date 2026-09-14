@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontCacheBudget
import org.graphiks.kalligraphie.api.KalligraphieInternalApi
import kotlin.concurrent.atomics.AtomicLong

/**
 * Bounded, opt-in primitive recorder for isolated retention measurements. Attach before use.
 * Cells and events are allocated by construction; recording invokes no external callback and
 * retains no keys, sources, participants or native resources. Read only after workload drainage.
 * Ledger cells survive pruning. Saturation invalidates completeness of the recorded evidence.
 * @suppress
 */
@KalligraphieInternalApi
public class FontCacheMeasurement(public val ledgerCapacity: Int = 512, public val eventCapacity: Int = 20_000) {
    init { require(ledgerCapacity in 1..4096 && eventCapacity in 1..100_000) }
    private val cells = LongArray(ledgerCapacity * 45)
    private val events = LongArray(eventCapacity * 22)
    public var ledgerCount: Int = 0
        private set
    public var eventCount: Int = 0
        private set
    public var saturated: Boolean = false
        private set
    private val requests = AtomicLong(0)
    private val fallbacks = AtomicLong(0)
    private val contention = AtomicLong(0)
    private val decisions = AtomicLong(0)
    private val removals = AtomicLong(0)
    private val visits = AtomicLong(0)
    private val preparationVisits = AtomicLong(0)
    private val removalVisits = AtomicLong(0)
    private val maximumDecisions = AtomicLong(0)
    private val maximumVictims = AtomicLong(0)
    private val counterSaturation = AtomicLong(0)

    internal fun register(level: Int, budget: FontCacheBudget): Int {
        if (ledgerCount == ledgerCapacity) { saturated = true; return -1 }
        val slot = ledgerCount++
        val base = slot * 45
        cells[base] = level.toLong()
        cells[base + 1] = budget.retainedBytes
        cells[base + 2] = budget.decodedPixels
        cells[base + 3] = budget.nativeBytes
        cells[base + 4] = budget.nativeAllocations
        return slot
    }

    internal fun record(ledger: CacheLedger, state: Int) {
        val slot = ledger.measurementSlot
        if (slot < 0) return
        val base = slot * 45
        var dimension = 0
        while (dimension < 20) {
            val amount = if (dimension < 4) ledger.total else ledger.categories[(dimension - 4) / 4]
            val value = when (dimension % 4) {
                0 -> amount.bytes
                1 -> amount.pixels
                2 -> amount.nativeBytes
                else -> amount.allocations
            }
            cells[base + 5 + dimension] = value
            if (value > cells[base + 25 + dimension]) cells[base + 25 + dimension] = value
            dimension++
        }
        if (eventCount == eventCapacity) { saturated = true; return }
        val event = eventCount++ * 22
        events[event] = slot.toLong()
        events[event + 1] = state.toLong()
        dimension = 0
        while (dimension < 20) {
            events[event + 2 + dimension] = cells[base + 5 + dimension]
            dimension++
        }
    }

    internal fun contended() { increment(contention, 1) }
    internal fun indexed(count: Int) { increment(visits, count.toLong()) }
    internal fun prepared(count: Int) { increment(preparationVisits, count.toLong()) }
    internal fun removed(count: Int) { increment(removalVisits, count.toLong()) }
    internal fun unpublished() { increment(fallbacks, 1) }
    internal fun admission(retained: Boolean, attempts: Int, victims: Int) {
        increment(requests, 1)
        if (!retained) increment(fallbacks, 1)
        increment(decisions, attempts.toLong())
        increment(removals, victims.toLong())
        maximum(maximumDecisions, attempts.toLong())
        maximum(maximumVictims, victims.toLong())
    }
    private fun increment(cell: AtomicLong, value: Long) {
        var old = cell.load()
        while (true) {
            if (value > 1_000_000_000L - old) { counterSaturation.store(1); return }
            if (cell.compareAndSet(old, old + value)) return
            old = cell.load()
        }
    }
    private fun maximum(cell: AtomicLong, value: Long) {
        var old = cell.load()
        while (value > old && !cell.compareAndSet(old, value)) old = cell.load()
    }

    /** Copies numeric evidence outside coordination, after all workload operations complete. */
    public fun report(): String = buildString {
        appendLine("saturated=${saturated || counterSaturation.load() != 0L} ledgers=$ledgerCount events=$eventCount")
        appendLine("requests=${requests.load()} uncached=${fallbacks.load()} contention=${contention.load()} decisions=${decisions.load()} victims=${removals.load()} lookupIndexVisits=${visits.load()} preparationIndexVisits=${preparationVisits.load()} removalIndexVisits=${removalVisits.load()} maxDecisions=${maximumDecisions.load()} maxVictims=${maximumVictims.load()}")
        appendLine("Dimensions: managedBytes, decodedPixels, nativeBytes, nativeUnits. Groups: total, active, reserved, retiring, residual. Levels: 0 scope, 1 capture, 2 face.")
        repeat(ledgerCount) { slot ->
            val base = slot * 45
            appendLine("ledger=$slot level=${cells[base]} budget=${(1..4).map { cells[base + it] }} current=${(5..24).map { cells[base + it] }} maxima=${(25..44).map { cells[base + it] }}")
        }
        appendLine("Event-time snapshots (slot,state,20 dimensions); state 4 is confirmed acknowledgement before pruning:")
        repeat(eventCount) { event -> appendLine((0..21).joinToString(",") { events[event * 22 + it].toString() }) }
    }

    /** Checks recorded evidence after drainage, without adding functional cache assertions. */
    public fun verifyDrainedBounds() {
        check(!saturated && counterSaturation.load() == 0L) { "Recorder saturated; evidence is incomplete." }
        repeat(ledgerCount) { slot ->
            val base = slot * 45
            repeat(4) { dimension -> check(cells[base + 25 + dimension] <= cells[base + 1 + dimension]) { "Observed charged maximum exceeded its budget." } }
            repeat(20) { dimension -> check(cells[base + 5 + dimension] == 0L) { "Charge remains active, reserved, retiring or residual; drainage is not confirmed." } }
        }
    }
}
