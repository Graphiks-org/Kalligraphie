package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Describes the name declared by the system or package that supplied a font. */
public data class FontSourceProvenance(
    /** The source name used for diagnostics and provenance tracking. */
    public val declaredName: String,
)

@JvmInline
/** Stable content-derived identifier for a font source. */
public value class FontSourceId(
    /** Stable identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "FontSourceId value must not be blank." }
    }
}

@JvmInline
/** Stable identifier for a face within a font source. */
public value class FontFaceId(
    /** Stable identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "FontFaceId value must not be blank." }
    }
}

@JvmInline
/** Stable identifier for a concrete font instance. */
public value class FontInstanceKey(
    /** Stable identifier value. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "FontInstanceKey value must not be blank." }
    }
}

/** Immutable byte-backed font source used by the loading pipeline. */
public class FontSource(
    sourceBytes: ByteArray,
    /** Provenance supplied by the caller for diagnostics and inspection. */
    public val provenance: FontSourceProvenance,
) {
    private val capturedBytes: ByteArray = sourceBytes.copyOf()

    /** Identifier derived from the captured bytes. */
    public val id: FontSourceId = FontSourceId(capturedBytes.sha256Hex())

    /** Number of bytes captured from the source. */
    public val sizeInBytes: Int
        get() = capturedBytes.size

    /** Returns a defensive copy of the captured font bytes. */
    public fun copyBytes(): ByteArray = capturedBytes.copyOf()
}

internal fun ByteArray.sha256Hex(): String {
    val digest = Sha256.digest(this)
    val chars = CharArray(digest.size * 2)
    val hexDigits = "0123456789abcdef"
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xFF
        chars[index * 2] = hexDigits[value ushr 4]
        chars[index * 2 + 1] = hexDigits[value and 0x0F]
    }
    return chars.concatToString()
}

private object Sha256 {
    private val initialHash = intArrayOf(
        0x6A09E667,
        0xBB67AE85.toInt(),
        0x3C6EF372,
        0xA54FF53A.toInt(),
        0x510E527F,
        0x9B05688C.toInt(),
        0x1F83D9AB,
        0x5BE0CD19,
    )

    private val roundConstants = intArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF.toInt(), 0xE9B5DBA5.toInt(),
        0x3956C25B, 0x59F111F1, 0x923F82A4.toInt(), 0xAB1C5ED5.toInt(),
        0xD807AA98.toInt(), 0x12835B01, 0x243185BE, 0x550C7DC3,
        0x72BE5D74, 0x80DEB1FE.toInt(), 0x9BDC06A7.toInt(), 0xC19BF174.toInt(),
        0xE49B69C1.toInt(), 0xEFBE4786.toInt(), 0x0FC19DC6, 0x240CA1CC,
        0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152.toInt(), 0xA831C66D.toInt(), 0xB00327C8.toInt(), 0xBF597FC7.toInt(),
        0xC6E00BF3.toInt(), 0xD5A79147.toInt(), 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13,
        0x650A7354, 0x766A0ABB, 0x81C2C92E.toInt(), 0x92722C85.toInt(),
        0xA2BFE8A1.toInt(), 0xA81A664B.toInt(), 0xC24B8B70.toInt(), 0xC76C51A3.toInt(),
        0xD192E819.toInt(), 0xD6990624.toInt(), 0xF40E3585.toInt(), 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5,
        0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814.toInt(), 0x8CC70208.toInt(),
        0x90BEFFFA.toInt(), 0xA4506CEB.toInt(), 0xBEF9A3F7.toInt(), 0xC67178F2.toInt(),
    )

    fun digest(bytes: ByteArray): ByteArray {
        val padded = pad(bytes)
        val hash = initialHash.copyOf()
        val schedule = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                schedule[index] = ((padded[base].toInt() and 0xFF) shl 24) or
                    ((padded[base + 1].toInt() and 0xFF) shl 16) or
                    ((padded[base + 2].toInt() and 0xFF) shl 8) or
                    (padded[base + 3].toInt() and 0xFF)
            }
            for (index in 16 until 64) {
                schedule[index] = schedule[index - 16] + smallSigma0(schedule[index - 15]) +
                    schedule[index - 7] + smallSigma1(schedule[index - 2])
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]

            for (index in 0 until 64) {
                val temp1 = h + bigSigma1(e) + choose(e, f, g) + roundConstants[index] + schedule[index]
                val temp2 = bigSigma0(a) + majority(a, b, c)
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
            offset += 64
        }

        return ByteArray(32).also { result ->
            for (index in hash.indices) {
                val value = hash[index]
                val base = index * 4
                result[base] = (value ushr 24).toByte()
                result[base + 1] = (value ushr 16).toByte()
                result[base + 2] = (value ushr 8).toByte()
                result[base + 3] = value.toByte()
            }
        }
    }

    private fun pad(bytes: ByteArray): ByteArray {
        val bitLength = bytes.size.toLong() * 8L
        val paddingLength = ((56 - ((bytes.size + 1) % 64)) + 64) % 64
        val output = ByteArray(bytes.size + 1 + paddingLength + 8)
        bytes.copyInto(output, endIndex = bytes.size)
        output[bytes.size] = 0x80.toByte()
        for (index in 0 until 8) {
            output[output.size - 1 - index] = (bitLength ushr (index * 8)).toByte()
        }
        return output
    }

    private fun choose(x: Int, y: Int, z: Int): Int = (x and y) xor (x.inv() and z)

    private fun majority(x: Int, y: Int, z: Int): Int = (x and y) xor (x and z) xor (y and z)

    private fun bigSigma0(value: Int): Int = value.rotateRight(2) xor value.rotateRight(13) xor value.rotateRight(22)

    private fun bigSigma1(value: Int): Int = value.rotateRight(6) xor value.rotateRight(11) xor value.rotateRight(25)

    private fun smallSigma0(value: Int): Int = value.rotateRight(7) xor value.rotateRight(18) xor (value ushr 3)

    private fun smallSigma1(value: Int): Int = value.rotateRight(17) xor value.rotateRight(19) xor (value ushr 10)
}
