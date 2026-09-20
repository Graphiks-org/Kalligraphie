package org.graphiks.kalligraphie.platform.windows

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Optional Windows smoke test over the **default** DirectWrite registry.
 *
 * Unlike the controlled journey, this exercises the real integration path:
 * `KffiDirectWriteFontRegistry` → the DirectWrite system font collection →
 * capture. It is a smoke check on whatever is installed, never the oracle; the
 * functional proof stays on the controlled boundary. Skipped off Windows.
 */
class DirectWriteNativeSmokeTest {
    @Test
    fun discoversAndCapturesTheInstalledDirectWriteCollection() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Windows"))

        val result = DirectWriteSystemFontCatalog.open()

        val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(result, result.toString()).value
        assertTrue(catalog.faces.isNotEmpty(), "the DirectWrite system collection must report at least one face")
        assertTrue(
            catalog.faces.all { it.metadata.familyName.isNotBlank() },
            "every captured face must carry a family name parsed from the captured bytes",
        )
    }
}
