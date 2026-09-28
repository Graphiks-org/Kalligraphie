package org.graphiks.kalligraphie.platform.browser

import kotlin.js.JsArray
import kotlin.js.Promise

internal actual fun localFontAccess(): LocalFontAccess = WasmLocalFontAccess

private object WasmLocalFontAccess : LocalFontAccess {
    override fun isSupported(): Boolean = wasmQueryLocalFontsSupported()

    override fun query(): Promise<JsArray<BrowserFontData>> = wasmQueryLocalFonts()
}

private fun wasmQueryLocalFontsSupported(): Boolean =
    js("typeof window !== 'undefined' && typeof window.queryLocalFonts === 'function'")

private fun wasmQueryLocalFonts(): Promise<JsArray<BrowserFontData>> =
    js("window.queryLocalFonts()")
