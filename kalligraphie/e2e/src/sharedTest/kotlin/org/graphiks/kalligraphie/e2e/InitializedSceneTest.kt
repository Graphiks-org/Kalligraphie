package org.graphiks.kalligraphie.e2e

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.initialize

/**
 * Runs a scene test once the portable runtime is ready.
 *
 * On JVM, Android and iOS `initialize()` is a no-op and this is just the body. On web the WebAssembly
 * HarfBuzz module is instantiated asynchronously, so the paragraph scenes can only render after it is
 * awaited — something a plain synchronous `@Test` cannot do. `runTest` drives the body on a scope that
 * waits for the real promise, which is why the tests that render the whole catalog go through it.
 *
 * The timeout is generous because the golden verification renders every catalogued scene, and the
 * slowest target is a Kotlin/Wasm test process on a shared runner.
 */
internal fun initializedSceneTest(block: suspend () -> Unit): TestResult =
    runTest(timeout = 10.minutes) {
        initialize()
        block()
    }
