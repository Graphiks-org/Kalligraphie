package org.graphiks.kalligraphie.shaping

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

/**
 * Kotlin/Native (iOS) actual for [PortableLock].
 *
 * Kotlin/Native has no object monitor and no portable reentrant lock in its
 * standard library. `kotlinx.atomicfu.locks.reentrantLock` supplies one and is
 * reentrant, matching the JVM/ART object-monitor semantics the other actuals
 * rely on. Only the `atomicfu` runtime artifact is needed for the `locks` API;
 * the atomicfu compiler plugin is not required.
 */
internal actual class PortableLock actual constructor() {
    private val lock = reentrantLock()

    internal actual fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
