package org.graphiks.kalligraphie.platform.browser

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Local Font Access route is optional and platform-dependent, so its contract is the typing more
 * than any fixed result: a runtime that does not implement the API answers
 * [BrowserFontDiscovery.Unsupported], and a runtime that does implement it can only answer with a
 * real outcome — never the sentinel, and never an exception.
 *
 * The same suite runs under Node (no `window`, so unsupported) and under Chrome headless (the API
 * exists and the query needs a user gesture), which is exactly the pair this invariant distinguishes.
 */
class BrowserFontDiscoveryTest {
    @Test
    fun reportsATypedOutcomeThatMatchesWhetherTheApiIsPresent() = runTest {
        val supported = localFontAccess().isSupported()
        when (val outcome = discoverLocalFonts()) {
            is BrowserFontDiscovery.Success ->
                assertTrue(supported, "a font list can only come from a runtime that implements the API")

            is BrowserFontDiscovery.Unsupported ->
                assertFalse(supported, "an implementing runtime must not report Unsupported")

            is BrowserFontDiscovery.PermissionDenied ->
                assertTrue(supported, "a permission outcome can only come from an implementing runtime")

            is BrowserFontDiscovery.Failed ->
                assertTrue(supported, "a failure can only come from an implementing runtime")
        }
    }
}
