package org.graphiks.kalligraphie.platform.browser

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise
import kotlin.js.unsafeCast
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray

internal actual fun localFontAccess(): LocalFontAccess = WasmLocalFontAccess

private object WasmLocalFontAccess : LocalFontAccess {
    override fun isSupported(): Boolean = wasmQueryLocalFontsSupported()

    override suspend fun query(): List<BrowserFontData> {
        val data = wasmQueryLocalFonts().await<JsArray<WasmFontData>>()
        return buildList {
            for (index in 0 until data.length) {
                val font = data[index] ?: continue
                add(WasmBrowserFontData(font))
            }
        }
    }
}

private fun wasmQueryLocalFontsSupported(): Boolean =
    js("typeof window !== 'undefined' && typeof window.queryLocalFonts === 'function'")

private fun wasmQueryLocalFonts(): Promise<JsArray<WasmFontData>> = js("window.queryLocalFonts()")

/** One `FontData` the browser returned. */
private external interface WasmFontData : JsAny {
    val family: String
    val fullName: String
    val postscriptName: String
    val style: String
    fun blob(): Promise<WasmBlob>
}

/** The `Blob` `FontData.blob()` resolves to; its bytes are read through `arrayBuffer()`. */
private external interface WasmBlob : JsAny {
    fun arrayBuffer(): Promise<JsAny>
}

private class WasmBrowserFontData(private val font: WasmFontData) : BrowserFontData {
    override val family: String get() = font.family
    override val fullName: String get() = font.fullName
    override val postscriptName: String get() = font.postscriptName
    override val style: String get() = font.style

    override suspend fun bytes(): ByteArray =
        font.blob().await<WasmBlob>().arrayBuffer().await<JsAny>().toByteArray()
}

/** Copies the `ArrayBuffer` a `Blob` resolves to into Kotlin-managed bytes. */
private fun JsAny.toByteArray(): ByteArray = Int8Array(unsafeCast<ArrayBuffer>()).toByteArray()
