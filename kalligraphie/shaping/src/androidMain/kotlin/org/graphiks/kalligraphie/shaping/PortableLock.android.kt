package org.graphiks.kalligraphie.shaping

/**
 * Android actual for [PortableLock], backed by the ART object monitor.
 *
 * `synchronized` on a dedicated monitor is reentrant and matches the semantics
 * of the `@Synchronized`/`synchronized(lock)` sections it replaces.
 */
public actual class PortableLock actual constructor() {
    private val monitor: Any = Any()

    public actual fun <T> withLock(block: () -> T): T = synchronized(monitor) { block() }
}
