package org.graphiks.kalligraphie.platform.browser

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

/**
 * Proves the one route that actually yields font bytes: a granted query whose `FontData.blob()`
 * resolves to a real `Blob`, which must be read as an `ArrayBuffer` before it can become Kotlin
 * bytes. Node has no `window`, so that projection returns without asserting — the browser run is the
 * one this test exists for.
 */
class BrowserFontDiscoveryStubTest {
    @Test
    fun readsTheFontBytesThroughTheBlob() = runTest {
        // Node has no `window`; the browser run is the one this test exists for.
        if (hasWindow()) {
            installStubQueryLocalFonts()

            val outcome = assertIs<BrowserFontDiscovery.Success>(discoverLocalFonts())
            val font = outcome.fonts.single()
            assertContentEquals(byteArrayOf(0x41, 0x42, 0x43, 0x44), font.bytes)
        }
    }
}

/** Whether this projection runs inside a browser, where `window.queryLocalFonts` can exist. */
private fun hasWindow(): Boolean = js("typeof window !== 'undefined'")

/**
 * Installs a stub `window.queryLocalFonts` returning one `FontData` whose blob carries `ABCD`.
 *
 * The stub is the only way to exercise the success path in a headless run: a real query without a
 * user gesture is refused, so the failure would otherwise mask a broken byte extraction. It is one
 * JavaScript expression because the Wasm target inlines `js(...)` into an expression position.
 */
@Suppress("UnsafeCastFromDynamic")
private fun installStubQueryLocalFonts(): Unit = js("(window.queryLocalFonts = function () { return Promise.resolve([{ family: 'Stub Family', fullName: 'Stub Family Regular', postscriptName: 'StubFamily-Regular', style: 'Regular', blob: function () { return Promise.resolve(new Blob([new Uint8Array([65, 66, 67, 68])])); } }]); }, undefined)")
