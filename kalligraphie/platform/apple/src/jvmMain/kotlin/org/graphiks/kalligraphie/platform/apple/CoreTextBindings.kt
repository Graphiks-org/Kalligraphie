package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kffi.MemoryAllocator
import org.graphiks.kffi.NativeAddress
import org.graphiks.kffi.apple.AppleBindingException
import org.graphiks.kffi.apple.AppleBindingFailure
import org.graphiks.kffi.coretext.CoreText
import org.graphiks.kffi.darwin.DarwinSystemInformation

/** Font-policy adapter, initialized only by the opted-in factory on a supported platform. */
internal class CoreTextBindings {
    private val api = bindingCall { CoreText() }

    private inline fun <T> bindingCall(block: () -> T): T = try { block() }
    catch (failure: AppleBindingException) {
        val code = when (failure.failure) {
            AppleBindingFailure.LIBRARY_LOAD -> "font.native-library-load-failed"
            AppleBindingFailure.SYMBOL_RESOLUTION -> "font.native-symbol-resolution-failed"
            AppleBindingFailure.SYSTEM_INFORMATION -> "font.platform-runtime-identity-unavailable"
            AppleBindingFailure.UNSUPPORTED_PLATFORM -> "font.platform-unsupported"
        }
        nativeFailure(code, failure.message ?: "Apple platform binding failed.")
    }

    fun data(bytes: Long, size: Long): Long = bindingCall { api.createData(NativeAddress(bytes), size).rawValue }
    fun provider(data: Long): Long = bindingCall { api.createProvider(NativeAddress(data)).rawValue }
    fun graphics(provider: Long): Long = bindingCall { api.createGraphicsFont(NativeAddress(provider)).rawValue }
    fun font(graphics: Long, size: Double): Long = bindingCall { api.createFont(NativeAddress(graphics), size).rawValue }

    fun releaseFont(font: Long) { release(font, 3) { api.releaseCF(it) } }
    fun releaseGraphics(graphics: Long) { release(graphics, 2) { api.releaseGraphicsFont(it) } }
    fun releaseProvider(provider: Long) { release(provider, 1) { api.releaseProvider(it) } }
    fun releaseData(data: Long, bytes: Long) { release(data, 0, bytes) { api.releaseCF(it) } }
    private inline fun release(resource: Long, kind: Int, bytes: Long = 0, block: (NativeAddress) -> Unit) {
        if (resource == 0L) return
        try { bindingCall { block(NativeAddress(resource)) } }
        catch (failure: Throwable) { CoreTextResourceMeasurement.uncertain(kind); throw failure }
        CoreTextResourceMeasurement.released(kind, bytes)
    }

    fun size(font: Long): Double = bindingCall { api.fontSize(NativeAddress(font)) }
    fun upem(font: Long): Long = bindingCall { api.unitsPerEm(NativeAddress(font)).toLong() }
    fun glyphCount(font: Long): Long = bindingCall { api.glyphCount(NativeAddress(font)) }
    fun identityMatrix(font: Long, allocator: MemoryAllocator): Boolean = bindingCall {
        val value = api.fontMatrix(NativeAddress(font), allocator)
        value.a == 1.0 && value.b == 0.0 && value.c == 0.0 && value.d == 1.0 && value.tx == 0.0 && value.ty == 0.0
    }

    fun kernelBuild(): String {
        val bytes = bindingCall { DarwinSystemInformation.readSysctlBytes("kern.osversion", 1024L) }
        if (bytes.size !in 2..1024 || bytes.last() != 0.toByte())
            nativeFailure("font.platform-runtime-identity-unavailable", "Kernel OS build is incomplete or unterminated.")
        return String(bytes, 0, bytes.size - 1, Charsets.US_ASCII)
    }
}
