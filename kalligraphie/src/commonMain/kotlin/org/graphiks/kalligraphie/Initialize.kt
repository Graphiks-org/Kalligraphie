package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.shaping.initializeShapingRuntime

/**
 * Initializes the portable runtime once, before any facade is opened.
 *
 * On JVM, Android and iOS every bundled library loads synchronously and this is a no-op. On web the
 * WebAssembly HarfBuzz module is instantiated asynchronously, so a consumer must `await` this before
 * opening a facade. It is idempotent and may be called from any thread.
 */
public suspend fun initialize() {
    initializeShapingRuntime()
}
