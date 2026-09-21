@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand

/**
 * Decoded Type 2 charstring: cubic contours plus the resolved advance width.
 *
 * [seac] is present only for the deprecated `endchar` accent composition; the
 * caller composes base and accent glyphs. [pointCount] follows the outline point
 * accounting of the TrueType path (move/line = 1, cubic = 3).
 */
internal class Type2Outline(
    val contours: List<GlyphContour>,
    val pointCount: Int,
    val bounds: DesignBounds,
    val width: Int,
    val seac: Type2Seac?,
)

/** Deprecated `endchar` standard-encoding accent composition request. */
internal data class Type2Seac(
    val accentOffsetX: Int,
    val accentOffsetY: Int,
    val baseCharacter: Int,
    val accentCharacter: Int,
)

/**
 * Interpreter for CFF1 Type 2 charstrings.
 *
 * Implements number decoding, the operand stack, width parsing, stem hints and
 * hint masks, every path operator (move/line/curve families), subroutines with
 * biased indices, transient storage, arithmetic/logic operators, the flex family
 * and the deprecated `endchar` accent form.
 *
 * A malformed program, an unsupported operator, an underflowing stack or an
 * exceeded point/contour/call budget returns a typed
 * [FontError.InvalidFontData] failure instead of a wrong outline. CFF2 `blend`
 * and `vsindex` are handled by the CFF2 entry point, not here.
 */
internal object Type2CharstringInterpreter {
    private const val MAX_CALL_DEPTH = 10
    private const val MAX_OPERATIONS = 1_000_000
    private val LOCATION: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF ")

    /**
     * Interprets one Type 2 charstring.
     *
     * @param charString the charstring program.
     * @param globalSubrs global subroutines indexed from 0.
     * @param localSubrs local subroutines from the active Private DICT, from 0.
     * @param nominalWidthX / [defaultWidthX] width operands from the Private DICT.
     * @param maxPoints / [maxContours] outline budgets; a breach is a typed failure.
     */
    fun interpret(
        charString: ByteArray,
        globalSubrs: List<ByteArray>,
        localSubrs: List<ByteArray>,
        nominalWidthX: Int,
        defaultWidthX: Int,
        maxPoints: Int,
        maxContours: Int,
        hasWidth: Boolean = true,
        variationSource: CffVariationSource? = null,
        initialVsIndex: Int = 0,
    ): FontOperationResult<Type2Outline> = try {
        val interpreter = Interpreter(
            globalSubrs, localSubrs, nominalWidthX, defaultWidthX, maxPoints, maxContours, hasWidth, variationSource,
            initialVsIndex,
        )
        interpreter.execute(charString, 0)
        FontOperationResult.Success(interpreter.finish())
    } catch (failure: CharstringFailure) {
        FontOperationResult.Failure(FontError.InvalidFontData(failure.message ?: "CFF charstring failed.", LOCATION))
    }

    private class CharstringFailure(message: String) : RuntimeException(message)

    private class Interpreter(
        private val globalSubrs: List<ByteArray>,
        private val localSubrs: List<ByteArray>,
        private val nominalWidthX: Int,
        private val defaultWidthX: Int,
        private val maxPoints: Int,
        private val maxContours: Int,
        private val hasWidth: Boolean,
        private val variationSource: CffVariationSource?,
        initialVsIndex: Int,
    ) {
        private val stack = ArrayList<Double>(48)
        private val transient = DoubleArray(32)
        private val contours = ArrayList<GlyphContour>()
        private var current: ArrayList<GlyphOutlineCommand>? = null
        private var x = 0.0
        private var y = 0.0
        private var pendingWidth = 0
        private var widthParsed = false
        private var hintCount = 0
        private var vsIndex = initialVsIndex
        private var pointCount = 0
        private var operations = 0
        private var ended = false
        private var seac: Type2Seac? = null
        private var minX = 0.0
        private var minY = 0.0
        private var maxX = 0.0
        private var maxY = 0.0
        private var hasBounds = false

        fun execute(code: ByteArray, depth: Int) {
            if (depth > MAX_CALL_DEPTH) fail("CFF charstring call depth exceeds $MAX_CALL_DEPTH.")
            var index = 0
            while (index < code.size && !ended) {
                if (++operations > MAX_OPERATIONS) fail("CFF charstring operation budget exceeded.")
                val b0 = code[index].toInt() and 0xFF
                when {
                    b0 == 28 -> {
                        if (index + 2 >= code.size) fail("CFF charstring shortint is truncated.")
                        push(((((code[index + 1].toInt() and 0xFF) shl 8) or (code[index + 2].toInt() and 0xFF))).toShort().toDouble())
                        index += 3
                    }

                    b0 == 255 -> {
                        if (index + 4 >= code.size) fail("CFF charstring fixed operand is truncated.")
                        val raw = ((code[index + 1].toInt() and 0xFF) shl 24) or
                            ((code[index + 2].toInt() and 0xFF) shl 16) or
                            ((code[index + 3].toInt() and 0xFF) shl 8) or
                            (code[index + 4].toInt() and 0xFF)
                        push(raw.toDouble() / 65536.0)
                        index += 5
                    }

                    b0 in 32..246 -> { push((b0 - 139).toDouble()); index += 1 }
                    b0 in 247..250 -> {
                        if (index + 1 >= code.size) fail("CFF charstring operand is truncated.")
                        push(((b0 - 247) * 256 + (code[index + 1].toInt() and 0xFF) + 108).toDouble())
                        index += 2
                    }

                    b0 in 251..254 -> {
                        if (index + 1 >= code.size) fail("CFF charstring operand is truncated.")
                        push((-(b0 - 251) * 256 - (code[index + 1].toInt() and 0xFF) - 108).toDouble())
                        index += 2
                    }

                    b0 == 12 -> {
                        if (index + 1 >= code.size) fail("CFF charstring escape operator is truncated.")
                        escaped(code[index + 1].toInt() and 0xFF)
                        index += 2
                    }

                    b0 == 11 -> return

                    else -> {
                        val consumedMask = operator(b0, code, index, depth)
                        index += consumedMask
                    }
                }
            }
        }

        /** Applies one operator; returns the total byte length it consumed. */
        private fun operator(b0: Int, code: ByteArray, index: Int, depth: Int): Int = when (b0) {
            1, 3, 18, 23 -> { stems(); 1 }
            19, 20 -> hintMask(code, index)
            21 -> { rmoveto(); 1 }
            22 -> { hmoveto(); 1 }
            4 -> { vmoveto(); 1 }
            5 -> { rlineto(); 1 }
            6 -> { hlineto(); 1 }
            7 -> { vlineto(); 1 }
            8 -> { rrcurveto(); 1 }
            24 -> { rcurveline(); 1 }
            25 -> { rlinecurve(); 1 }
            26 -> { vvcurveto(); 1 }
            27 -> { hhcurveto(); 1 }
            30 -> { alternatingCurves(horizontalFirst = false); 1 }
            31 -> { alternatingCurves(horizontalFirst = true); 1 }
            15 -> { vsindex(); 1 }
            16 -> { blend(); 1 }
            10 -> { callSubr(localSubrs, depth); 1 }
            29 -> { callSubr(globalSubrs, depth); 1 }
            14 -> { endchar(); 1 }
            else -> fail("CFF charstring operator $b0 is not supported.")
        }

        private fun escaped(b1: Int) {
            when (b1) {
                3 -> push(if (pop() != 0.0 && pop() != 0.0) 1.0 else 0.0)
                4 -> push(if (pop() != 0.0 || pop() != 0.0) 1.0 else 0.0)
                5 -> push(if (pop() == 0.0) 1.0 else 0.0)
                9 -> push(abs(pop()))
                10 -> { val b = pop(); push(pop() + b) }
                11 -> { val b = pop(); push(pop() - b) }
                12 -> { val b = pop(); push(if (b == 0.0) 0.0 else pop() / b) }
                14 -> push(-pop())
                15 -> { val b = pop(); push(if (pop() == b) 1.0 else 0.0) }
                18 -> pop()
                20 -> { val index = pop().toInt() and 31; transient[index] = pop() }
                21 -> push(transient[pop().toInt() and 31])
                22 -> {
                    val s2 = pop(); val s1 = pop(); val v2 = pop(); val v1 = pop()
                    push(if (v1 <= v2) s1 else s2)
                }

                23 -> push(0.5)
                24 -> { val b = pop(); push(pop() * b) }
                26 -> { val value = pop(); push(if (value < 0.0) 0.0 else sqrt(value)) }
                27 -> { val value = pop(); push(value); push(value) }
                28 -> { val b = pop(); val a = pop(); push(b); push(a) }
                29 -> index()
                30 -> roll()
                34 -> hflex()
                35 -> flex()
                36 -> hflex1()
                37 -> flex1()
                else -> fail("CFF charstring escaped operator 12 $b1 is not supported.")
            }
        }

        private fun stems() {
            parseWidthForStems()
            stack.clear()
        }

        private fun hintMask(code: ByteArray, index: Int): Int {
            parseWidthForStems()
            stack.clear()
            val maskLength = (hintCount + 7) / 8
            if (index + 1 + maskLength > code.size) fail("CFF hint mask is truncated.")
            return 1 + maskLength
        }

        private fun parseWidthForStems() {
            parseWidth(stack.size % 2 == 1)
            hintCount += stack.size / 2
        }

        private fun parseWidth(present: Boolean) {
            if (widthParsed) return
            widthParsed = true
            pendingWidth = if (hasWidth && present && stack.isNotEmpty()) {
                nominalWidthX + stack.removeAt(0).toInt()
            } else {
                defaultWidthX
            }
        }

        private fun vsindex() {
            val index = pop().toInt()
            if (index < 0) fail("CFF2 vsindex must be non-negative.")
            vsIndex = index
        }

        private fun blend() {
            val source = variationSource ?: fail("CFF blend requires a variation source.")
            val regions = source.regionCount(vsIndex)
            if (regions <= 0) fail("CFF blend has no regions for vsindex $vsIndex.")
            val count = pop().toInt()
            if (count < 0) fail("CFF blend count must be non-negative.")
            val operandCount = count * (regions + 1)
            if (stack.size < operandCount) fail("CFF blend operands are truncated.")
            val start = stack.size - operandCount
            val operands = ArrayList<Double>(operandCount)
            for (position in start until stack.size) operands.add(stack[position])
            repeat(operandCount) { stack.removeAt(stack.lastIndex) }
            val scalars = source.scalars(vsIndex)
            for (value in 0 until count) {
                var blended = operands[value]
                for (region in 0 until regions) blended += operands[count + value * regions + region] * (scalars.getOrNull(region) ?: 0.0)
                push(blended)
            }
        }

        private fun rmoveto() {
            parseWidth(stack.size == 3)
            if (stack.size != 2) fail("CFF rmoveto expects two operands.")
            moveBy(stack[0], stack[1]); stack.clear()
        }

        private fun hmoveto() {
            parseWidth(stack.size == 2)
            if (stack.size != 1) fail("CFF hmoveto expects one operand.")
            moveBy(stack[0], 0.0); stack.clear()
        }

        private fun vmoveto() {
            parseWidth(stack.size == 2)
            if (stack.size != 1) fail("CFF vmoveto expects one operand.")
            moveBy(0.0, stack[0]); stack.clear()
        }

        private fun rlineto() {
            if (stack.isEmpty() || stack.size % 2 != 0) fail("CFF rlineto expects operand pairs.")
            var index = 0
            while (index < stack.size) { lineBy(stack[index], stack[index + 1]); index += 2 }
            stack.clear()
        }

        private fun hlineto() = alternatingLines(horizontalFirst = true)
        private fun vlineto() = alternatingLines(horizontalFirst = false)

        private fun alternatingLines(horizontalFirst: Boolean) {
            var horizontal = horizontalFirst
            for (value in stack) {
                if (horizontal) lineBy(value, 0.0) else lineBy(0.0, value)
                horizontal = !horizontal
            }
            stack.clear()
        }

        private fun rrcurveto() {
            if (stack.isEmpty() || stack.size % 6 != 0) fail("CFF rrcurveto expects operand sextets.")
            var index = 0
            while (index < stack.size) {
                curveBy(stack[index], stack[index + 1], stack[index + 2], stack[index + 3], stack[index + 4], stack[index + 5])
                index += 6
            }
            stack.clear()
        }

        private fun rcurveline() {
            if (stack.size < 8 || (stack.size - 2) % 6 != 0) fail("CFF rcurveline operands are malformed.")
            var index = 0
            while (index < stack.size - 2) {
                curveBy(stack[index], stack[index + 1], stack[index + 2], stack[index + 3], stack[index + 4], stack[index + 5])
                index += 6
            }
            lineBy(stack[index], stack[index + 1]); stack.clear()
        }

        private fun rlinecurve() {
            if (stack.size < 8 || (stack.size - 6) % 2 != 0) fail("CFF rlinecurve operands are malformed.")
            var index = 0
            while (index < stack.size - 6) { lineBy(stack[index], stack[index + 1]); index += 2 }
            curveBy(stack[index], stack[index + 1], stack[index + 2], stack[index + 3], stack[index + 4], stack[index + 5])
            stack.clear()
        }

        private fun vvcurveto() {
            val leading = if (stack.size % 4 == 1) stack[0] else 0.0
            var index = if (stack.size % 4 == 1) 1 else 0
            var first = true
            while (index < stack.size) {
                curveBy(if (first) leading else 0.0, stack[index], stack[index + 1], stack[index + 2], 0.0, stack[index + 3])
                first = false; index += 4
            }
            stack.clear()
        }

        private fun hhcurveto() {
            val leading = if (stack.size % 4 == 1) stack[0] else 0.0
            var index = if (stack.size % 4 == 1) 1 else 0
            var first = true
            while (index < stack.size) {
                curveBy(stack[index], if (first) leading else 0.0, stack[index + 1], stack[index + 2], stack[index + 3], 0.0)
                first = false; index += 4
            }
            stack.clear()
        }

        private fun alternatingCurves(horizontalFirst: Boolean) {
            var index = 0
            var horizontal = horizontalFirst
            while (index + 4 <= stack.size) {
                var dx1 = 0.0
                var dy1 = 0.0
                val dx2: Double
                val dy2: Double
                var dx3 = 0.0
                var dy3 = 0.0
                if (horizontal) {
                    dx1 = stack[index]; dx2 = stack[index + 1]; dy2 = stack[index + 2]; dy3 = stack[index + 3]
                } else {
                    dy1 = stack[index]; dx2 = stack[index + 1]; dy2 = stack[index + 2]; dx3 = stack[index + 3]
                }
                index += 4
                if (index == stack.size - 1) {
                    if (horizontal) dx3 = stack[index] else dy3 = stack[index]
                    index += 1
                }
                curveBy(dx1, dy1, dx2, dy2, dx3, dy3)
                horizontal = !horizontal
            }
            if (index != stack.size) fail("CFF alternating curve operands are malformed.")
            stack.clear()
        }

        private fun hflex() {
            if (stack.size != 7) fail("CFF hflex expects seven operands.")
            val dy2 = stack[2]
            curveBy(stack[0], 0.0, stack[1], dy2, stack[3], 0.0)
            curveBy(stack[4], 0.0, stack[5], -dy2, stack[6], 0.0)
            stack.clear()
        }

        private fun flex() {
            if (stack.size != 13) fail("CFF flex expects thirteen operands.")
            curveBy(stack[0], stack[1], stack[2], stack[3], stack[4], stack[5])
            curveBy(stack[6], stack[7], stack[8], stack[9], stack[10], stack[11])
            stack.clear()
        }

        private fun hflex1() {
            if (stack.size != 9) fail("CFF hflex1 expects nine operands.")
            val dy1 = stack[1]
            val dy2 = stack[3]
            val dy5 = stack[7]
            curveBy(stack[0], dy1, stack[2], dy2, stack[4], 0.0)
            curveBy(stack[5], 0.0, stack[6], dy5, stack[8], -(dy1 + dy2 + dy5))
            stack.clear()
        }

        private fun flex1() {
            if (stack.size != 11) fail("CFF flex1 expects eleven operands.")
            val dx = stack[0] + stack[2] + stack[4] + stack[6] + stack[8]
            val dy = stack[1] + stack[3] + stack[5] + stack[7] + stack[9]
            curveBy(stack[0], stack[1], stack[2], stack[3], stack[4], stack[5])
            if (abs(dx) > abs(dy)) {
                curveBy(stack[6], stack[7], stack[8], stack[9], -dx, stack[10])
            } else {
                curveBy(stack[6], stack[7], stack[8], stack[9], stack[10], -dy)
            }
            stack.clear()
        }

        private fun index() {
            val count = pop().toInt()
            if (count < 0 || count >= stack.size) fail("CFF index operand is out of range.")
            push(stack[stack.size - 1 - count])
        }

        private fun roll() {
            val shift = pop().toInt()
            val count = pop().toInt()
            if (count < 0 || count > stack.size) fail("CFF roll count is out of range.")
            if (count == 0) return
            val start = stack.size - count
            val slice = stack.subList(start, stack.size).toMutableList()
            val normalized = ((shift % count) + count) % count
            val rotated = slice.takeLast(normalized) + slice.dropLast(normalized)
            for (position in 0 until count) stack[start + position] = rotated[position]
        }

        private fun callSubr(subrs: List<ByteArray>, depth: Int) {
            val raw = pop().toInt()
            val biased = raw + bias(subrs.size)
            if (biased < 0 || biased >= subrs.size) fail("CFF subroutine index $raw is out of range.")
            execute(subrs[biased], depth + 1)
        }

        private fun endchar() {
            parseWidth(stack.size == 1 || stack.size == 5)
            if (stack.size == 4) {
                seac = Type2Seac(stack[0].toInt(), stack[1].toInt(), stack[2].toInt(), stack[3].toInt())
            } else if (stack.isNotEmpty()) {
                fail("CFF endchar operands are malformed.")
            }
            stack.clear()
            ended = true
        }

        fun finish(): Type2Outline {
            closeContour()
            val bounds = if (hasBounds) DesignBounds(
                minX = floor(minX).toInt(),
                minY = floor(minY).toInt(),
                maxX = ceil(maxX).toInt(),
                maxY = ceil(maxY).toInt(),
            ) else DesignBounds.empty
            return Type2Outline(contours.toList(), pointCount, bounds, if (widthParsed) pendingWidth else defaultWidthX, seac)
        }

        private fun moveBy(dx: Double, dy: Double) {
            closeContour()
            x += dx; y += dy
            current = arrayListOf(GlyphOutlineCommand.MoveTo(x, y))
            track(x, y); pointCount += 1
        }

        private fun lineBy(dx: Double, dy: Double) {
            val contour = current ?: failReturn("CFF line operator before a move.")
            x += dx; y += dy
            contour.add(GlyphOutlineCommand.LineTo(x, y))
            track(x, y); pointCount += 1; checkPoints()
        }

        private fun curveBy(dx1: Double, dy1: Double, dx2: Double, dy2: Double, dx3: Double, dy3: Double) {
            val contour = current ?: failReturn("CFF curve operator before a move.")
            val c1x = x + dx1; val c1y = y + dy1
            val c2x = c1x + dx2; val c2y = c1y + dy2
            val ex = c2x + dx3; val ey = c2y + dy3
            contour.add(GlyphOutlineCommand.CubicTo(c1x, c1y, c2x, c2y, ex, ey))
            track(c1x, c1y); track(c2x, c2y); track(ex, ey)
            pointCount += 3; x = ex; y = ey; checkPoints()
        }

        private fun closeContour() {
            val contour = current ?: return
            contour.add(GlyphOutlineCommand.Close)
            contours.add(GlyphContour(contour))
            current = null
            if (contours.size > maxContours) fail("CFF contour budget exceeded.")
        }

        private fun checkPoints() {
            if (pointCount > maxPoints) fail("CFF point budget exceeded.")
        }

        private fun track(px: Double, py: Double) {
            if (!hasBounds) {
                minX = px; minY = py; maxX = px; maxY = py; hasBounds = true
            } else {
                if (px < minX) minX = px
                if (py < minY) minY = py
                if (px > maxX) maxX = px
                if (py > maxY) maxY = py
            }
        }

        private fun push(value: Double) {
            stack.add(value)
            if (stack.size > 48) fail("CFF charstring operand stack exceeds 48.")
        }

        private fun pop(): Double = if (stack.isEmpty()) failReturn("CFF charstring stack underflow.") else stack.removeAt(stack.lastIndex)

        private fun bias(count: Int): Int = when {
            count < 1240 -> 107
            count < 33900 -> 1131
            else -> 32768
        }

        private fun fail(message: String): Nothing = throw CharstringFailure(message)

        private fun failReturn(message: String): Nothing = throw CharstringFailure(message)
    }
}
