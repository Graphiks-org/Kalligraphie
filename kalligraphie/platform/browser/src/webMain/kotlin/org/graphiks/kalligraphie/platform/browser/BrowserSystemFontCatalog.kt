package org.graphiks.kalligraphie.platform.browser

import kotlinx.coroutines.await
import kotlin.js.JsAny
import kotlin.js.unsafeCast
import kotlin.js.JsArray
import kotlin.js.Promise
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.toByteArray

/**
 * One system font the browser exposed, with the bytes the library parses itself.
 *
 * The library never asks the browser to shape: it takes the bytes and runs its own SFNT/HarfBuzz
 * pipeline, exactly as it does for an application-supplied font.
 */
public class BrowserFont(
    /** The font family, as the operating system names it. */
    public val family: String,
    /** The full human-readable name. */
    public val fullName: String,
    /** The PostScript name. */
    public val postscriptName: String,
    /** The style description, such as `Regular` or `Bold Italic`. */
    public val style: String,
    /** The font bytes, copied out of the browser's `Blob`. */
    public val bytes: ByteArray,
)

/**
 * Outcome of a browser system-font discovery.
 *
 * The system-font route is optional and platform-dependent: an unsupported browser or a denied
 * permission is a typed outcome the application handles, never an exception, and never a silent
 * empty list that would look like a machine with no fonts.
 */
public sealed interface BrowserFontDiscovery {
    /** The query succeeded; [fonts] may legitimately be empty. */
    public class Success(public val fonts: List<BrowserFont>) : BrowserFontDiscovery

    /** The browser does not implement the Local Font Access API. */
    public data object Unsupported : BrowserFontDiscovery

    /** The application-supplied bytes remain the only route: the user denied the permission. */
    public class PermissionDenied(public val message: String) : BrowserFontDiscovery

    /** The query failed for any other reason. */
    public class Failed(public val message: String) : BrowserFontDiscovery
}

/**
 * Reads the operating system's font catalog through the Local Font Access API.
 *
 * This is a separate, explicitly user-triggered operation, never part of a library initializer:
 * the API requires a secure context and a user gesture for its permission prompt, and waiting on a
 * slow resource before requesting it can exhaust the transient activation. The application calls it
 * from its own click handler.
 *
 * Application-supplied bytes remain the primary route on every platform; this is the optional
 * system-font route, and its [BrowserFontDiscovery.Unsupported] outcome is the honest answer on a
 * browser that does not implement it.
 */
public suspend fun discoverLocalFonts(): BrowserFontDiscovery {
    val access = localFontAccess()
    if (!access.isSupported()) return BrowserFontDiscovery.Unsupported
    return try {
        val data = access.query().await<JsArray<BrowserFontData>>()
        val fonts = buildList {
            for (index in 0 until data.length) {
                val font = data[index] ?: continue
                add(
                    BrowserFont(
                        family = font.family,
                        fullName = font.fullName,
                        postscriptName = font.postscriptName,
                        style = font.style,
                        bytes = font.blob().await<JsAny>().toByteArray(),
                    ),
                )
            }
        }
        BrowserFontDiscovery.Success(fonts)
    } catch (error: Throwable) {
        val message = error.message ?: "The browser font query failed."
        if (message.contains("permission", ignoreCase = true) || message.contains("SecurityError")) {
            BrowserFontDiscovery.PermissionDenied(message)
        } else {
            BrowserFontDiscovery.Failed(message)
        }
    }
}

/** One `FontData` the browser returned. */
internal external interface BrowserFontData : JsAny {
    val family: String
    val fullName: String
    val postscriptName: String
    val style: String
    fun blob(): Promise<JsAny>
}

/** The per-target access to `window.queryLocalFonts`, which is not on the portable `Window` type. */
internal interface LocalFontAccess {
    /** Whether this browser implements the Local Font Access API. */
    fun isSupported(): Boolean

    /** Queries the system fonts; only valid when [isSupported] is true. */
    fun query(): Promise<JsArray<BrowserFontData>>
}

/** The target-specific access; declared here so the shared discovery compiles on both targets. */
internal expect fun localFontAccess(): LocalFontAccess

/** Copies the `ArrayBuffer` a `Blob` resolves to into Kotlin-managed bytes. */
private suspend fun JsAny.toByteArray(): ByteArray {
    val buffer = this.unsafeCast<ArrayBuffer>()
    return Int8Array(buffer).toByteArray()
}
