package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kffi.MemoryAllocator
import org.graphiks.kffi.engine.JvmDowncallEngine
import org.graphiks.kffi.engine.JvmDowncallEngine.AbiType
import org.graphiks.kffi.engine.JvmDowncallEngine.FunctionShape
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/** Minimal bindings, initialized only by the opted-in factory on a supported platform. */
internal class CoreTextBindings {
    private val engine = JvmDowncallEngine
    init {
        for (framework in listOf("CoreFoundation", "CoreGraphics", "CoreText")) {
            try { System.load("/System/Library/Frameworks/$framework.framework/$framework") }
            catch (failure: UnsatisfiedLinkError) { nativeFailure("font.native-library-load-failed", "Cannot load $framework: ${failure.message}") }
            catch (failure: SecurityException) { nativeFailure("font.native-library-load-failed", "Cannot load $framework: ${failure.message}") }
        }
        engine.registerStructLayout(matrixName, 48L, 8L, listOf("a", "b", "c", "d", "tx", "ty").mapIndexed { index, name ->
            JvmDowncallEngine.StructField(name, JvmDowncallEngine.FieldKind.FLOAT64, index * 8L)
        })
    }
    private fun symbol(name: String): Long = try { engine.resolveSymbol(name) } catch (failure: Exception) {
        nativeFailure("font.native-symbol-resolution-failed", "Cannot resolve $name: ${failure.message}")
    }
    private val dataCreate = symbol("CFDataCreate")
    private val providerCreate = symbol("CGDataProviderCreateWithCFData")
    private val graphicsCreate = symbol("CGFontCreateWithDataProvider")
    private val fontCreate = symbol("CTFontCreateWithGraphicsFont")
    private val cfRelease = symbol("CFRelease")
    private val providerRelease = symbol("CGDataProviderRelease")
    private val graphicsRelease = symbol("CGFontRelease")
    private val getSize = symbol("CTFontGetSize")
    private val getUpem = symbol("CTFontGetUnitsPerEm")
    private val getGlyphCount = symbol("CTFontGetGlyphCount")
    private val getMatrix = symbol("CTFontGetMatrix")
    private val sysctl = symbol("sysctlbyname")
    fun data(bytes: Long, size: Long): Long = pointer(engine.callGeneric(dataCreate, FunctionShape(AbiType.Pointer, listOf(AbiType.Pointer, AbiType.Pointer, AbiType.I64)), 0L, bytes, size))
    fun provider(data: Long): Long = engine.callP1P(providerCreate, data)
    fun graphics(provider: Long): Long = engine.callP1P(graphicsCreate, provider)
    fun font(graphics: Long, size: Double): Long = pointer(engine.callGeneric(fontCreate,
        FunctionShape(AbiType.Pointer, listOf(AbiType.Pointer, AbiType.F64, AbiType.Pointer, AbiType.Pointer)), graphics, size, 0L, 0L))
    fun releaseFont(font: Long) { if (font != 0L) engine.callV1P(cfRelease, font) }
    fun releaseGraphics(graphics: Long) { if (graphics != 0L) engine.callV1P(graphicsRelease, graphics) }
    fun releaseProvider(provider: Long) { if (provider != 0L) engine.callV1P(providerRelease, provider) }
    fun releaseData(data: Long) { if (data != 0L) engine.callV1P(cfRelease, data) }
    fun size(font: Long): Double = engine.callD1P(getSize, font)
    fun upem(font: Long): Long = engine.callI1P(getUpem, font) and 0xffffffffL
    fun glyphCount(font: Long): Long = engine.callL1P(getGlyphCount, font)
    fun identityMatrix(font: Long, allocator: MemoryAllocator): Boolean {
        val result = engine.invokeStructReturnAfterPointer(getMatrix, allocator, matrixName, font)
        val segment = MemorySegment.ofAddress(result.rawValue).reinterpret(48L)
        return listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0).withIndex().all { (index, expected) -> segment.get(ValueLayout.JAVA_DOUBLE, index * 8L) == expected }
    }
    fun kernelBuild(): String = MemoryAllocator().use { temp ->
        val name = temp.allocateFrom("kern.osversion")
        val length = temp.bufferOf(0L)
        val shape = FunctionShape(AbiType.I32, listOf(AbiType.Pointer, AbiType.Pointer, AbiType.Pointer, AbiType.Pointer, AbiType.I64))
        fun query(output: Long): Int = engine.callGeneric(sysctl, shape, name.handler.rawValue, output, length.handler.rawValue, 0L, 0L) as Int
        if (query(0L) != 0) nativeFailure("font.native-runtime-identity-unavailable", "Cannot query the kernel OS build length.")
        val size = length.readLong()
        if (size <= 1 || size > 1024) nativeFailure("font.native-runtime-identity-unavailable", "Kernel OS build has an invalid bounded length.")
        val bytes = temp.allocateBuffer(size.toULong())
        if (query(bytes.handler.rawValue) != 0 || length.readLong() !in 2..size) nativeFailure("font.native-runtime-identity-unavailable", "Cannot query the complete kernel OS build.")
        val leaf = ByteArray(length.readLong().toInt())
        bytes.readBytes(leaf)
        if (leaf.last() != 0.toByte()) nativeFailure("font.native-runtime-identity-unavailable", "Kernel OS build is not terminated.")
        String(leaf, 0, leaf.size - 1, Charsets.US_ASCII)
    }
    private fun pointer(result: Any?): Long = (result as MemorySegment).address()
    private companion object { const val matrixName = "org.graphiks.kalligraphie.coretext.CGAffineTransform" }
}
