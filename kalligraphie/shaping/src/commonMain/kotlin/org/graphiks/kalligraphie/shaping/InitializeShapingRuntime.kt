package org.graphiks.kalligraphie.shaping

/**
 * Initializes the platform shaping runtime, once, before any facade is opened.
 *
 * On JVM, Android and iOS the bundled HarfBuzz library loads synchronously and this is a no-op.
 * On web the WebAssembly module is instantiated asynchronously, so a consumer must `await` this
 * before opening a shaping backend. It is idempotent.
 */
public expect suspend fun initializeShapingRuntime()
