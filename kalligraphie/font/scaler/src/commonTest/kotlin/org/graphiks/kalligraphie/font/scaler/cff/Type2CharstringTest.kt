package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class Type2CharstringTest {
    @Test
    fun emitsALineContour() {
        val outline = success(interpret(byteArrayOf(149.toByte(), 159.toByte(), 21, 169.toByte(), 179.toByte(), 5, 14)))

        val contour = outline.contours.single()
        assertEquals(GlyphOutlineCommand.MoveTo(10.0, 20.0), contour.commands[0])
        assertEquals(GlyphOutlineCommand.LineTo(40.0, 60.0), contour.commands[1])
        assertEquals(GlyphOutlineCommand.Close, contour.commands[2])
        assertEquals(2, outline.pointCount)
    }

    @Test
    fun emitsACubicContour() {
        val outline = success(
            interpret(
                byteArrayOf(
                    149.toByte(), 159.toByte(), 21,
                    149.toByte(), 149.toByte(), 159.toByte(), 159.toByte(), 169.toByte(), 169.toByte(), 8,
                    14,
                ),
            ),
        )

        assertEquals(
            GlyphOutlineCommand.CubicTo(20.0, 30.0, 40.0, 50.0, 70.0, 80.0),
            outline.contours.single().commands[1],
        )
    }

    @Test
    fun parsesALeadingWidthFromAnOddStemOperandCount() {
        val outline = success(
            interpret(byteArrayOf(142.toByte(), 143.toByte(), 144.toByte(), 1, 149.toByte(), 159.toByte(), 21, 14), nominalWidthX = 100),
        )

        assertEquals(103, outline.width)
        assertEquals(GlyphOutlineCommand.MoveTo(10.0, 20.0), outline.contours.single().commands[0])
    }

    @Test
    fun usesTheDefaultWidthWhenNoWidthOperandIsPresent() {
        val outline = success(interpret(byteArrayOf(149.toByte(), 159.toByte(), 21, 14), defaultWidthX = 500))

        assertEquals(500, outline.width)
    }

    @Test
    fun callsALocalSubroutineWithBias() {
        val subroutine = byteArrayOf(149.toByte(), 159.toByte(), 21, 144.toByte(), 144.toByte(), 5, 11)
        val outline = success(interpret(byteArrayOf(32, 10, 14), localSubrs = listOf(subroutine)))

        val contour = outline.contours.single()
        assertEquals(GlyphOutlineCommand.MoveTo(10.0, 20.0), contour.commands[0])
        assertEquals(GlyphOutlineCommand.LineTo(15.0, 25.0), contour.commands[1])
    }

    @Test
    fun decodesAFixedPointOperand() {
        val outline = success(interpret(byteArrayOf(255.toByte(), 0, 1, 0x80.toByte(), 0, 22, 14)))

        assertEquals(GlyphOutlineCommand.MoveTo(1.5, 0.0), outline.contours.single().commands[0])
    }

    @Test
    fun appliesArithmeticOperators() {
        val outline = success(interpret(byteArrayOf(141.toByte(), 142.toByte(), 12, 10, 22, 14)))

        assertEquals(GlyphOutlineCommand.MoveTo(5.0, 0.0), outline.contours.single().commands[0])
    }

    @Test
    fun emitsAnHvCurvetoGroup() {
        val outline = success(
            interpret(
                byteArrayOf(
                    139.toByte(), 139.toByte(), 21,
                    149.toByte(), 159.toByte(), 169.toByte(), 179.toByte(), 31,
                    14,
                ),
            ),
        )

        assertEquals(
            GlyphOutlineCommand.CubicTo(10.0, 0.0, 30.0, 30.0, 30.0, 70.0),
            outline.contours.single().commands[1],
        )
    }

    @Test
    fun rejectsStackUnderflow() {
        val result = interpret(byteArrayOf(5))

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsAnUnsupportedOperator() {
        val result = interpret(byteArrayOf(2))

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsASubroutineIndexOutOfRange() {
        val result = interpret(byteArrayOf(10), localSubrs = emptyList())

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun callsAGlobalSubroutineWithBias() {
        val subroutine = byteArrayOf(139.toByte(), 139.toByte(), 21, 144.toByte(), 144.toByte(), 5, 11)
        val outline = success(interpret(byteArrayOf(32, 29, 14), globalSubrs = listOf(subroutine)))

        assertEquals(GlyphOutlineCommand.LineTo(5.0, 5.0), outline.contours.single().commands[1])
    }

    @Test
    fun rejectsAExceededCallDepth() {
        val recursive = byteArrayOf(32, 10, 11)
        val result = interpret(byteArrayOf(32, 10, 14), localSubrs = listOf(recursive))

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun appliesBlendAtTheDefaultInstance() {
        val source = object : CffVariationSource {
            override fun regionCount(vsIndex: Int): Int = 1
            override fun scalars(vsIndex: Int): DoubleArray = doubleArrayOf(0.5)
        }
        val outline = success(
            interpret(
                bytes = byteArrayOf(149.toByte(), 159.toByte(), 140.toByte(), 16, 22),
                variationSource = source,
            ),
        )

        assertEquals(GlyphOutlineCommand.MoveTo(20.0, 0.0), outline.contours.single().commands[0])
    }

    @Test
    fun seedsTheVsIndexFromTheInitialIndex() {
        val outline = success(
            interpret(
                bytes = byteArrayOf(149.toByte(), 159.toByte(), 140.toByte(), 16, 22),
                variationSource = singleRegionOnlyAtVsIndexOne(),
                initialVsIndex = 1,
            ),
        )

        assertEquals(GlyphOutlineCommand.MoveTo(30.0, 0.0), outline.contours.single().commands[0])
    }

    @Test
    fun appliesAnExplicitVsIndexOverride() {
        val outline = success(
            interpret(
                bytes = byteArrayOf(140.toByte(), 15, 149.toByte(), 159.toByte(), 140.toByte(), 16, 22),
                variationSource = singleRegionOnlyAtVsIndexOne(),
                initialVsIndex = 0,
            ),
        )

        assertEquals(GlyphOutlineCommand.MoveTo(30.0, 0.0), outline.contours.single().commands[0])
    }

    @Test
    fun rejectsABlendWhenTheVsIndexHasNoRegions() {
        val source = object : CffVariationSource {
            override fun regionCount(vsIndex: Int): Int = 0
            override fun scalars(vsIndex: Int): DoubleArray = DoubleArray(0)
        }

        val result = interpret(
            bytes = byteArrayOf(149.toByte(), 159.toByte(), 140.toByte(), 16, 22),
            variationSource = source,
            initialVsIndex = 0,
        )

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    @Test
    fun rejectsANegativeInitialVsIndex() {
        val result = interpret(byteArrayOf(149.toByte(), 159.toByte(), 21, 14), initialVsIndex = -1)

        assertIs<FontError.InvalidFontData>(assertIs<FontOperationResult.Failure>(result).error)
    }

    private fun singleRegionOnlyAtVsIndexOne(): CffVariationSource = object : CffVariationSource {
        override fun regionCount(vsIndex: Int): Int = if (vsIndex == 1) 1 else 0
        override fun scalars(vsIndex: Int): DoubleArray = if (vsIndex == 1) doubleArrayOf(1.0) else DoubleArray(0)
    }

    private fun interpret(
        bytes: ByteArray,
        localSubrs: List<ByteArray> = emptyList(),
        globalSubrs: List<ByteArray> = emptyList(),
        nominalWidthX: Int = 0,
        defaultWidthX: Int = 0,
        variationSource: CffVariationSource? = null,
        initialVsIndex: Int = 0,
    ): FontOperationResult<Type2Outline> = Type2CharstringInterpreter.interpret(
        charString = bytes,
        globalSubrs = globalSubrs,
        localSubrs = localSubrs,
        nominalWidthX = nominalWidthX,
        defaultWidthX = defaultWidthX,
        maxPoints = 100_000,
        maxContours = 1_000,
        variationSource = variationSource,
        initialVsIndex = initialVsIndex,
    )

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
