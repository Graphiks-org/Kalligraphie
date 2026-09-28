package org.graphiks.kalligraphie.smoke

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.initialize
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend

/**
 * Exercises the published `org.graphiks:kalligraphie` artifact the way an application does: resolve
 * the coordinate, await the portable initializer, then open the public shaping facade.
 *
 * On web this proves the end-to-end consumer wiring — the published klib, the extracted WebAssembly
 * runtime and the asynchronous initializer together — which no project-to-project test can see.
 * The runtime files themselves travel in the kffi klib, so a defect in *their* packaging is caught
 * by the extraction failing, not by a Kalligraphie artifact being wrong.
 */
class ConsumerShapingSmokeTest {
    @Test
    fun opensThePublishedShapingBackendAfterThePortableInitializer() = runTest(timeout = 5.minutes) {
        initialize()
        assertIs<FontOperationResult.Success<*>>(HarfBuzzShapingBackend.open())
    }
}
