package org.graphiks.kalligraphie

/**
 * Web actual for [PortableLock].
 *
 * One thread means no contention, so the lock is a pass-through and reentrancy is trivially safe.
 */
internal actual class PortableLock actual constructor() {
    internal actual fun <T> withLock(block: () -> T): T = block()
}
