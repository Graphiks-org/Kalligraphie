@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Immutable operand/operator view of one CFF DICT structure.
 *
 * A DICT is a sequence of operands followed by an operator. This reader validates
 * every operand encoding (`32..246`, `247..250`, `251..254`, `28`, `29`, `30`),
 * clears the operand stack on each operator, and rejects reserved single-byte
 * operators, truncated operands and a trailing operand sequence without an
 * operator.
 *
 * Operator keys are `0..21` for single-byte operators and [escaped] `(0x0C00 | b1)`
 * for two-byte `12 x` operators, so the two spaces cannot collide.
 *
 * @property entries operator key to its complete operand list, in first-seen order.
 */
internal class CffDict private constructor(
    private val entries: Map<Int, List<Double>>,
) {
    /** Operator keys present in this DICT. */
    val operators: Set<Int>
        get() = entries.keys

    /** Complete operand list recorded for [operator], or `null` when absent. */
    fun operands(operator: Int): List<Double>? = entries[operator]

    /** Operand [index] for [operator], or `null` when the operator or operand is absent. */
    fun operand(operator: Int, index: Int = 0): Double? = entries[operator]?.getOrNull(index)

    /**
     * Integral operand [index] for [operator], or `null` when absent or non-integral.
     *
     * @throws IllegalArgumentException never; a non-finite or fractional operand
     * yields `null` so callers can raise a typed failure instead of truncating.
     */
    fun integer(operator: Int, index: Int = 0): Int? {
        val value = operand(operator, index) ?: return null
        if (!value.isFinite()) return null
        val rounded = kotlin.math.round(value)
        if (rounded != value) return null
        if (rounded < Int.MIN_VALUE.toDouble() || rounded > Int.MAX_VALUE.toDouble()) return null
        return rounded.toInt()
    }

    companion object {
        /** Base added to the second byte of a two-byte `12 x` operator. */
        const val ESCAPE_BASE: Int = 0x0C00

        /** Maximum operands held before an operator, per the CFF DICT stack rule. */
        const val MAX_OPERANDS: Int = 48

        private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF ")

        /** Operator key for a two-byte `12 x` operator whose second byte is [second]. */
        fun escaped(second: Int): Int = ESCAPE_BASE or (second and 0xFF)

        /**
         * Reads a DICT covering `[start, end)` in [bytes].
         *
         * @return the parsed DICT, or a typed [FontError.InvalidFontData] failure
         * for a reserved byte, an out-of-range operand, or trailing operands.
         */
        fun read(bytes: ByteArray, start: Int, end: Int): FontOperationResult<CffDict> {
            if (start < 0 || end > bytes.size || start > end) {
                return failure("CFF DICT range is outside the source.")
            }
            val entries = LinkedHashMap<Int, List<Double>>()
            val stack = ArrayList<Double>()
            var index = start
            while (index < end) {
                val b0 = bytes[index].toInt() and 0xFF
                when {
                    b0 <= 31 && b0 != 28 && b0 != 29 && b0 != 30 -> {
                        val operator: Int
                        if (b0 == 12) {
                            if (index + 1 >= end) return failure("CFF DICT escape operator is truncated.")
                            val second = bytes[index + 1].toInt() and 0xFF
                            operator = escaped(second)
                            index += 2
                        } else {
                            operator = b0
                            index += 1
                        }
                        entries[operator] = stack.toList()
                        stack.clear()
                    }

                    b0 == 28 -> {
                        if (index + 2 >= end) return failure("CFF DICT shortint operand is truncated.")
                        val value = ((bytes[index + 1].toInt() and 0xFF) shl 8) or (bytes[index + 2].toInt() and 0xFF)
                        stack.add(value.toShort().toDouble())
                        index += 3
                    }

                    b0 == 29 -> {
                        if (index + 4 >= end) return failure("CFF DICT longint operand is truncated.")
                        val value = ((bytes[index + 1].toInt() and 0xFF) shl 24) or
                            ((bytes[index + 2].toInt() and 0xFF) shl 16) or
                            ((bytes[index + 3].toInt() and 0xFF) shl 8) or
                            (bytes[index + 4].toInt() and 0xFF)
                        stack.add(value.toDouble())
                        index += 5
                    }

                    b0 == 30 -> {
                        val parsed = readReal(bytes, index + 1, end)
                            ?: return failure("CFF DICT real operand is malformed or truncated.")
                        stack.add(parsed.value)
                        index = parsed.next
                    }

                    b0 in 32..246 -> {
                        stack.add((b0 - 139).toDouble())
                        index += 1
                    }

                    b0 in 247..250 -> {
                        if (index + 1 >= end) return failure("CFF DICT positive operand is truncated.")
                        val b1 = bytes[index + 1].toInt() and 0xFF
                        stack.add(((b0 - 247) * 256 + b1 + 108).toDouble())
                        index += 2
                    }

                    b0 in 251..254 -> {
                        if (index + 1 >= end) return failure("CFF DICT negative operand is truncated.")
                        val b1 = bytes[index + 1].toInt() and 0xFF
                        stack.add((-(b0 - 251) * 256 - b1 - 108).toDouble())
                        index += 2
                    }

                    else -> return failure("CFF DICT byte is reserved: $b0.")
                }
                if (stack.size > MAX_OPERANDS) return failure("CFF DICT operand stack exceeds $MAX_OPERANDS.")
            }
            if (stack.isNotEmpty()) return failure("CFF DICT ends with operands but no operator.")
            return FontOperationResult.Success(CffDict(entries))
        }

        private data class RealResult(val value: Double, val next: Int)

        private fun readReal(bytes: ByteArray, start: Int, end: Int): RealResult? {
            val text = StringBuilder()
            var index = start
            while (index < end) {
                val byte = bytes[index].toInt() and 0xFF
                for (nibbleIndex in 0..1) {
                    val nibble = if (nibbleIndex == 0) byte ushr 4 else byte and 0x0F
                    when (nibble) {
                        0xF -> {
                            val value = text.toString().toDoubleOrNull() ?: return null
                            return RealResult(value, index + 1)
                        }

                        in 0..9 -> text.append(nibble)
                        0xA -> text.append('.')
                        0xB -> text.append('E')
                        0xC -> text.append("E-")
                        0xE -> text.append('-')
                        else -> return null
                    }
                }
                index += 1
            }
            return null
        }

        private fun failure(message: String): FontOperationResult.Failure =
            FontOperationResult.Failure(FontError.InvalidFontData(message, location))
    }
}
