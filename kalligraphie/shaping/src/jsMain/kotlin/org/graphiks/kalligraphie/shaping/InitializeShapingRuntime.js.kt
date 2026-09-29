package org.graphiks.kalligraphie.shaping

import kotlinx.coroutines.await
import org.graphiks.kffi.harfbuzz.initializeHarfBuzz

/** Awaits the one-time instantiation of the bundled WebAssembly HarfBuzz module. */
public actual suspend fun initializeShapingRuntime() {
    initializeHarfBuzz().await()
}
