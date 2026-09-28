@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt.brotli

import org.graphiks.kalligraphie.api.KalligraphieInternalApi

/**
 * The 121 word transformations of the Brotli static dictionary (RFC 7932 §8, Appendix B).
 *
 * A transformed dictionary string is `prefix + transform(base_word) + suffix`. The elementary
 * transforms are Identity, FermentFirst and FermentAll (the RFC's "ferment" is the simplified
 * uppercasing of the reference decoder) and OmitFirst1..OmitFirst9 / OmitLast1..OmitLast9. An
 * omission can shorten the base word, including to the empty string, so the output length is not
 * fixed by the input length.
 *
 * The table is hand-written from the RFC's Appendix B, with the upstream C reference
 * (`c/common/transform.c` at the pinned commit) as the second reading. [TRANSFORMS] is in
 * `transform_id` order; its Appendix B byte encoding (each row as the prefix bytes, a zero byte,
 * the elementary transform's [TransformType.code], the suffix bytes and a zero byte) is 648 bytes
 * whose CRC-32 is `0x3d965f81`, the check value the RFC publishes, and which the test asserts.
 */
@KalligraphieInternalApi
internal object BrotliDictionaryTransforms {
    /**
     * Applies transformation [transformId] to a base dictionary [word] (RFC 7932 §8).
     *
     * @throws IllegalArgumentException if [transformId] is not in `0..120`.
     */
    fun apply(transformId: Int, word: ByteArray): ByteArray {
        require(transformId in TRANSFORMS.indices) {
            "Brotli dictionary transform $transformId is out of range."
        }
        val transform = TRANSFORMS[transformId]
        val base = transform.type.transform(word)
        val result = ByteArray(transform.prefix.size + base.size + transform.suffix.size)
        var position = 0
        transform.prefix.copyInto(result, position)
        position += transform.prefix.size
        base.copyInto(result, position)
        position += base.size
        transform.suffix.copyInto(result, position)
        return result
    }

    /** The transformation table in `transform_id` order (RFC 7932 Appendix B). */
    internal val TRANSFORMS: Array<Transform> = arrayOf(
        Transform(bytes(""), TransformType.IDENTITY, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" ")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes(" ")),
        Transform(bytes(""), TransformType.OMIT_FIRST_1, bytes("")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes(" ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" the ")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes("")),
        Transform(bytes("s "), TransformType.IDENTITY, bytes(" ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" of ")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" and ")),
        Transform(bytes(""), TransformType.OMIT_FIRST_2, bytes("")),
        Transform(bytes(""), TransformType.OMIT_LAST_1, bytes("")),
        Transform(bytes(", "), TransformType.IDENTITY, bytes(" ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(", ")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes(" ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" in ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" to ")),
        Transform(bytes("e "), TransformType.IDENTITY, bytes(" ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("\"")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(".")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("\">")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("\n")),
        Transform(bytes(""), TransformType.OMIT_LAST_3, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("]")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" for ")),
        Transform(bytes(""), TransformType.OMIT_FIRST_3, bytes("")),
        Transform(bytes(""), TransformType.OMIT_LAST_2, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" a ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" that ")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(". ")),
        Transform(bytes("."), TransformType.IDENTITY, bytes("")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes(", ")),
        Transform(bytes(""), TransformType.OMIT_FIRST_4, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" with ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("'")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" from ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" by ")),
        Transform(bytes(""), TransformType.OMIT_FIRST_5, bytes("")),
        Transform(bytes(""), TransformType.OMIT_FIRST_6, bytes("")),
        Transform(bytes(" the "), TransformType.IDENTITY, bytes("")),
        Transform(bytes(""), TransformType.OMIT_LAST_4, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(". The ")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" on ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" as ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" is ")),
        Transform(bytes(""), TransformType.OMIT_LAST_7, bytes("")),
        Transform(bytes(""), TransformType.OMIT_LAST_1, bytes("ing ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("\n\t")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(":")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes(". ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("ed ")),
        Transform(bytes(""), TransformType.OMIT_FIRST_9, bytes("")),
        Transform(bytes(""), TransformType.OMIT_FIRST_7, bytes("")),
        Transform(bytes(""), TransformType.OMIT_LAST_6, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("(")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes(", ")),
        Transform(bytes(""), TransformType.OMIT_LAST_8, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" at ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("ly ")),
        Transform(bytes(" the "), TransformType.IDENTITY, bytes(" of ")),
        Transform(bytes(""), TransformType.OMIT_LAST_5, bytes("")),
        Transform(bytes(""), TransformType.OMIT_LAST_9, bytes("")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes(", ")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes("\"")),
        Transform(bytes("."), TransformType.IDENTITY, bytes("(")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes(" ")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes("\">")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("=\"")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes(".")),
        Transform(bytes(".com/"), TransformType.IDENTITY, bytes("")),
        Transform(bytes(" the "), TransformType.IDENTITY, bytes(" of the ")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes("'")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(". This ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(",")),
        Transform(bytes("."), TransformType.IDENTITY, bytes(" ")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes("(")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes(".")),
        Transform(bytes(""), TransformType.IDENTITY, bytes(" not ")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes("=\"")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("er ")),
        Transform(bytes(" "), TransformType.FERMENT_ALL, bytes(" ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("al ")),
        Transform(bytes(" "), TransformType.FERMENT_ALL, bytes("")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("='")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes("\"")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes(". ")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes("(")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("ful ")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes(". ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("ive ")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("less ")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes("'")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("est ")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes(".")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes("\">")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes("='")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes(",")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("ize ")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes(".")),
        Transform(bytes("\u00c2\u00a0"), TransformType.IDENTITY, bytes("")),
        Transform(bytes(" "), TransformType.IDENTITY, bytes(",")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes("=\"")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes("=\"")),
        Transform(bytes(""), TransformType.IDENTITY, bytes("ous ")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes(", ")),
        Transform(bytes(""), TransformType.FERMENT_FIRST, bytes("='")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes(",")),
        Transform(bytes(" "), TransformType.FERMENT_ALL, bytes("=\"")),
        Transform(bytes(" "), TransformType.FERMENT_ALL, bytes(", ")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes(",")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes("(")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes(". ")),
        Transform(bytes(" "), TransformType.FERMENT_ALL, bytes(".")),
        Transform(bytes(""), TransformType.FERMENT_ALL, bytes("='")),
        Transform(bytes(" "), TransformType.FERMENT_ALL, bytes(". ")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes("=\"")),
        Transform(bytes(" "), TransformType.FERMENT_ALL, bytes("='")),
        Transform(bytes(" "), TransformType.FERMENT_FIRST, bytes("='")),
    )

    /** An elementary transform T of RFC 7932 §8; [code] is its Appendix B byte value. */
    internal enum class TransformType(val code: Int) {
        IDENTITY(0),
        FERMENT_FIRST(1),
        FERMENT_ALL(2),
        OMIT_FIRST_1(3),
        OMIT_FIRST_2(4),
        OMIT_FIRST_3(5),
        OMIT_FIRST_4(6),
        OMIT_FIRST_5(7),
        OMIT_FIRST_6(8),
        OMIT_FIRST_7(9),
        OMIT_FIRST_8(10),
        OMIT_FIRST_9(11),
        OMIT_LAST_1(12),
        OMIT_LAST_2(13),
        OMIT_LAST_3(14),
        OMIT_LAST_4(15),
        OMIT_LAST_5(16),
        OMIT_LAST_6(17),
        OMIT_LAST_7(18),
        OMIT_LAST_8(19),
        OMIT_LAST_9(20),
        ;

        /** The elementary transform applied to the (copied) base word. */
        fun transform(word: ByteArray): ByteArray = when (this) {
            IDENTITY -> word.copyOf()
            FERMENT_FIRST -> word.copyOf().also { if (it.isNotEmpty()) ferment(it, 0) }
            FERMENT_ALL -> word.copyOf().also {
                var position = 0
                while (position < it.size) position += ferment(it, position)
            }
            else -> {
                val count = if (code <= OMIT_FIRST_9.code) code - OMIT_FIRST_1.code + 1 else code - OMIT_LAST_1.code + 1
                when {
                    word.size <= count -> ByteArray(0)
                    code <= OMIT_FIRST_9.code -> word.copyOfRange(count, word.size)
                    else -> word.copyOfRange(0, word.size - count)
                }
            }
        }
    }

    /** One RFC 7932 Appendix B row: a Latin-1 prefix, an elementary transform and a suffix. */
    internal class Transform(
        val prefix: ByteArray,
        val type: TransformType,
        val suffix: ByteArray,
    )
}

/**
 * Interprets [latin1] as a sequence of bytes, one per character.
 *
 * The table above writes every ASCII prefix and suffix verbatim and the one non-ASCII prefix
 * (transform 102, a non-breaking space) as its `\u00c2\u00a0` characters, so truncating each
 * character to its low byte recovers the RFC's bytes exactly.
 */
private fun bytes(latin1: String): ByteArray = ByteArray(latin1.length) { latin1[it].code.toByte() }

/**
 * The Ferment function of RFC 7932 §8, applied at [position]; returns the number of bytes of the
 * rune it handled (`1`, `2` or `3`).
 */
private fun ferment(word: ByteArray, position: Int): Int {
    val first = word[position].toInt() and 0xFF
    return when {
        first < 0xC0 -> {
            if (first in 'a'.code..'z'.code) word[position] = (first xor 0x20).toByte()
            1
        }
        first < 0xE0 -> {
            if (position + 1 < word.size) {
                word[position + 1] = ((word[position + 1].toInt() and 0xFF) xor 0x20).toByte()
            }
            2
        }
        else -> {
            if (position + 2 < word.size) {
                word[position + 2] = ((word[position + 2].toInt() and 0xFF) xor 0x05).toByte()
            }
            3
        }
    }
}
