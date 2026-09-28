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
 * On web this is also the only check that the published klib still carries the WebAssembly HarfBuzz
 * runtime the library extracts — a consumer that cannot open the backend from the artifact proves a
 * packaging defect no project-to-project test can see.
 */
class ConsumerShapingSmokeTest {
    @Test
    fun opensThePublishedShapingBackendAfterThePortableInitializer() = runTest(timeout = 5.minutes) {
        initialize()
        assertIs<FontOperationResult.Success<*>>(HarfBuzzShapingBackend.open())
    }
}
