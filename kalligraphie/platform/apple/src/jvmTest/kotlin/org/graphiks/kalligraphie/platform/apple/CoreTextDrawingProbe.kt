package org.graphiks.kalligraphie.platform.apple

import java.lang.foreign.*
import org.graphiks.kalligraphie.api.PositionedGlyph

/** Application-side drawing, independent of provider certification and portable outlines. */
internal object CoreTextDrawingProbe {
    data class Observation(val crossbarAlpha: Int, val counterAlpha: Int, val outsideAlpha: Int,
        val textMatrix: List<Double>, val ctm: List<Double>)
    private val linker = Linker.nativeLinker()
    private val arena = Arena.ofShared()
    private val graphics = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics", arena)
    private val text = SymbolLookup.libraryLookup("/System/Library/Frameworks/CoreText.framework/CoreText", arena)
    private val address = ValueLayout.ADDRESS
    private val double = ValueLayout.JAVA_DOUBLE
    private val long = ValueLayout.JAVA_LONG
    private val affine = MemoryLayout.structLayout(*Array(6) { double })
    private fun invoke(name: String, result: MemoryLayout?, layouts: List<MemoryLayout>, vararg values: Any): Any? {
        val descriptor = if (result == null) FunctionDescriptor.ofVoid(*layouts.toTypedArray())
            else FunctionDescriptor.of(result, *layouts.toTypedArray())
        return linker.downcallHandle((if (name.startsWith("CTFont")) text else graphics).find(name).orElseThrow(), descriptor)
            .invokeWithArguments(*values)
    }
    private fun matrix(temp: Arena, values: List<Double>): MemorySegment = temp.allocate(affine).also { segment ->
        values.forEachIndexed { index, value -> segment.set(double, index * 8L, value) }
    }
    private fun readMatrix(temp: Arena, context: MemorySegment, name: String): List<Double> {
        val result = invoke(name, affine, listOf(address), temp, context) as MemorySegment
        return List(6) { result.get(double, it * 8L) }
    }
    fun draw(font: MemorySegment, glyph: PositionedGlyph): Observation = Arena.ofConfined().use { temp ->
        val pixels = temp.allocate(256L * 256L * 4L).fill(0)
        val colorSpace = invoke("CGColorSpaceCreateDeviceRGB", address, emptyList()) as MemorySegment
        check(colorSpace != MemorySegment.NULL)
        try {
            val context = invoke("CGBitmapContextCreate", address,
                listOf(address, long, long, long, long, address, ValueLayout.JAVA_INT),
                pixels, 256L, 256L, 8L, 1024L, colorSpace, 1) as MemorySegment
            check(context != MemorySegment.NULL)
            try {
                invoke("CGContextTranslateCTM", null, listOf(address, double, double), context, 20.0, 0.0)
                invoke("CGContextScaleCTM", null, listOf(address, double, double), context, 0.1, 0.1)
                invoke("CGContextSetTextMatrix", null, listOf(address, affine), context,
                    matrix(temp, listOf(1.2, 0.1, 0.2, 0.9, 7.0, 11.0)))
                val previousTextMatrix = readMatrix(temp, context, "CGContextGetTextMatrix")
                invoke("CGContextSaveGState", null, listOf(address), context)
                try {
                    invoke("CGContextTranslateCTM", null, listOf(address, double, double), context,
                        glyph.origin.x.value.toDouble(), glyph.origin.y.value.toDouble())
                    val t = glyph.transform
                    invoke("CGContextConcatCTM", null, listOf(address, affine), context,
                        matrix(temp, listOf(t.a.toDouble(), t.b.toDouble(), t.c.toDouble(), t.d.toDouble(), 0.0, 0.0)))
                    invoke("CGContextScaleCTM", null, listOf(address, double, double), context, 1.0, -1.0)
                    invoke("CGContextSetTextMatrix", null, listOf(address, affine), context,
                        matrix(temp, listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)))
                    val glyphIds = temp.allocate(ValueLayout.JAVA_SHORT)
                    glyphIds.set(ValueLayout.JAVA_SHORT, 0, glyph.shapedGlyph.glyphId.value.toShort())
                    val positions = temp.allocate(16, 8).fill(0)
                    invoke("CTFontDrawGlyphs", null, listOf(address, address, address, long, address),
                        font, glyphIds, positions, 1L, context)
                } finally {
                    invoke("CGContextRestoreGState", null, listOf(address), context)
                    invoke("CGContextSetTextMatrix", null, listOf(address, affine), context,
                        matrix(temp, previousTextMatrix))
                }
                // Bitmap rows run top-to-bottom; device-space y runs bottom-to-top.
                fun alpha(x: Int, y: Int) = pixels.get(ValueLayout.JAVA_BYTE,
                    ((255 - y) * 1024 + x * 4 + 3).toLong()).toInt() and 255
                Observation(alpha(98, 47), alpha(98, 15), alpha(14, 47),
                    readMatrix(temp, context, "CGContextGetTextMatrix"), readMatrix(temp, context, "CGContextGetCTM"))
            } finally { invoke("CGContextRelease", null, listOf(address), context) }
        } finally { invoke("CGColorSpaceRelease", null, listOf(address), colorSpace) }
    }
}
