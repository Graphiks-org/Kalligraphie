@file:Suppress("UnsafeCastFromDynamic")

package org.graphiks.kalligraphie.platform.browser

import kotlin.js.JsArray
import kotlin.js.Promise

internal actual fun localFontAccess(): LocalFontAccess = JsLocalFontAccess

private object JsLocalFontAccess : LocalFontAccess {
    override fun isSupported(): Boolean =
        js("typeof window !== 'undefined' && typeof window.queryLocalFonts === 'function'")

    override fun query(): Promise<JsArray<BrowserFontData>> = js("window.queryLocalFonts()")
}
