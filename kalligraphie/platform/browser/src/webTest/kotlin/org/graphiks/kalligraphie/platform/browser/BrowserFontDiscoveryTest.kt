package org.graphiks.kalligraphie.platform.browser

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs

/**
 * On a runtime without `window.queryLocalFonts` — the Node test runtime, and any browser that does
 * not implement the Local Font Access API — discovery is a typed [BrowserFontDiscovery.Unsupported],
 * never an exception and never an empty list that would look like a machine with no fonts.
 */
class BrowserFontDiscoveryTest {
    @Test
    fun reportsUnsupportedWhenTheApiIsAbsent() = runTest {
        assertIs<BrowserFontDiscovery.Unsupported>(discoverLocalFonts())
    }
}
