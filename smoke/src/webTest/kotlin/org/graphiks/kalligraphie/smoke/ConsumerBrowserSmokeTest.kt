package org.graphiks.kalligraphie.smoke

import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.initialize
import org.graphiks.kalligraphie.platform.browser.BrowserFontDiscovery
import org.graphiks.kalligraphie.platform.browser.discoverLocalFonts

/**
 * Exercises the published `org.graphiks:kalligraphie-platform-browser` artifact: the optional system
 * font route must be a typed outcome, never an exception and never a silent empty list.
 *
 * The Node test projection has no `window`, so the honest answer is [BrowserFontDiscovery.Unsupported];
 * a browser that implements the API would answer with its own outcome, which is why both are accepted.
 */
class ConsumerBrowserSmokeTest {
    @Test
    fun readsThePublishedBrowserAdapterAsATypedOutcome() = runTest(timeout = 5.minutes) {
        initialize()
        val outcome = discoverLocalFonts()
        assertTrue(
            outcome is BrowserFontDiscovery.Unsupported || outcome is BrowserFontDiscovery.Success,
            "unexpected browser discovery outcome: $outcome",
        )
    }
}
