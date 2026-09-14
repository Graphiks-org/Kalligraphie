package org.graphiks.kalligraphie.platform.apple

import java.lang.foreign.*

internal object CoreTextConsumerProbe {
    private val arena = Arena.ofShared()
    private val symbols = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreText.framework/CoreText", arena)
    private fun call(name: String, result: MemoryLayout, vararg args: MemoryLayout) =
        Linker.nativeLinker().downcallHandle(symbols.find(name).orElseThrow(), FunctionDescriptor.of(result, *args))
    fun size(font: MemorySegment): Double = call("CTFontGetSize", ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS).invokeWithArguments(font) as Double
    fun unitsPerEm(font: MemorySegment): Int = call("CTFontGetUnitsPerEm", ValueLayout.JAVA_INT, ValueLayout.ADDRESS).invokeWithArguments(font) as Int
    fun glyphCount(font: MemorySegment): Long = call("CTFontGetGlyphCount", ValueLayout.JAVA_LONG, ValueLayout.ADDRESS).invokeWithArguments(font) as Long
    fun horizontalAdvance(font: MemorySegment, glyph: Int): Double = Arena.ofConfined().use { temp ->
        val input = temp.allocate(ValueLayout.JAVA_SHORT)
        input.set(ValueLayout.JAVA_SHORT, 0, glyph.toShort())
        val output = temp.allocate(16, 8)
        call("CTFontGetAdvancesForGlyphs", ValueLayout.JAVA_DOUBLE, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG)
            .invokeWithArguments(font, 1, input, output, 1L)
        output.get(ValueLayout.JAVA_DOUBLE, 0)
    }
}
