package org.graphiks.kalligraphie.shaping

import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * Proves the bundled WebAssembly HarfBuzz backend opens on the web runtimes.
 *
 * The shaping output itself is pinned by the frozen-oracle probe of the published kffi binding;
 * this test proves the Kalligraphie-level route — asynchronous initialization, then opening the
 * platform binding over the extracted runtime — is wired.
 */
class WebShapingBackendProbe {
    @Test
    fun opensTheBundledBackendOnWeb() = runTest {
        initializeShapingRuntime()
        val opened = HarfBuzzShapingBackend.open()
        assertIs<FontOperationResult.Success<*>>(opened)
    }
}
