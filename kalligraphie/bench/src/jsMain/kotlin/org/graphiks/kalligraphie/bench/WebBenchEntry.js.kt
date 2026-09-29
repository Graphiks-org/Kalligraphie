package org.graphiks.kalligraphie.bench

/**
 * Kotlin/JS entry point of the web benchmark.
 *
 * A suspending `main` lets the asynchronous HarfBuzz initialization complete before the first
 * profile runs; the Kotlin Gradle plugin awaits it before the Node process exits.
 */
public suspend fun main() {
    runWebBenchmark()
}
