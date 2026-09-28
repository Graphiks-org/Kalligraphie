package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebShapingBackendTest {
    @Test
    fun reportsTheUnsupportedPlatformFailureUntilTheBackendLands() {
        val opened = HarfBuzzShapingBackend.open()
        val failure = assertIs<FontOperationResult.Failure>(opened)
        assertEquals("font.shaping-native-platform-unsupported", failure.error.code)
    }
}
