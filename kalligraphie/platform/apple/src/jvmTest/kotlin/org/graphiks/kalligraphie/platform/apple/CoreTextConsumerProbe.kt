package org.graphiks.kalligraphie.platform.apple

import java.lang.foreign.*

internal object CoreTextConsumerProbe {
    private val arena = Arena.ofShared()
    private val symbols = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreText.framework/CoreText", arena)
    private val graphics = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", arena)
    private val foundation = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", arena)
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
    /** Consumer observation only: certification itself never requests a path. */
    fun pathBounds(font: MemorySegment, glyph: Int): List<Double> = Arena.ofConfined().use { temp ->
        val path = call("CTFontCreatePathForGlyph", ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS)
            .invokeWithArguments(font, glyph.toShort(), MemorySegment.NULL) as MemorySegment
        check(path != MemorySegment.NULL) { "Audited ink glyph has no native path." }
        try {
            val rect = MemoryLayout.structLayout(ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE)
            val bounds = Linker.nativeLinker().downcallHandle(graphics.find("CGPathGetBoundingBox").orElseThrow(), FunctionDescriptor.of(rect, ValueLayout.ADDRESS))
                .invokeWithArguments(temp, path) as MemorySegment
            val x = bounds.get(ValueLayout.JAVA_DOUBLE, 0)
            val y = bounds.get(ValueLayout.JAVA_DOUBLE, 8)
            listOf(x, y, x + bounds.get(ValueLayout.JAVA_DOUBLE, 16), y + bounds.get(ValueLayout.JAVA_DOUBLE, 24))
        } finally {
            Linker.nativeLinker().downcallHandle(foundation.find("CFRelease").orElseThrow(), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)).invokeWithArguments(path)
        }
    }
}
