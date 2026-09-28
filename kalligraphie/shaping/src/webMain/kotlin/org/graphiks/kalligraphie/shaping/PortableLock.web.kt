package org.graphiks.kalligraphie.shaping

/**
 * Web actual for [PortableLock].
 *
 * Kotlin/JS and single-threaded Kotlin/Wasm run one thread, so there is no contention to arbitrate
 * and the lock is a straight pass-through. Reentrancy is therefore trivially satisfied.
 */
internal actual class PortableLock actual constructor() {
    internal actual fun <T> withLock(block: () -> T): T = block()
}
