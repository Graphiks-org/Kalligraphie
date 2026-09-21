package org.graphiks.kalligraphie.shaping

/**
 * JVM actual for [PortableLock], backed by the object monitor.
 *
 * `synchronized` on a dedicated monitor is reentrant and matches the semantics
 * of the `@Synchronized`/`synchronized(lock)` sections it replaces.
 */
internal actual class PortableLock actual constructor() {
    private val monitor: Any = Any()

    internal actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
