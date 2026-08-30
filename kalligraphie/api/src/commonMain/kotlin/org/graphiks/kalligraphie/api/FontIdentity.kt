package org.graphiks.kalligraphie.api

import kotlin.jvm.JvmInline

/** Describes the name declared by the system or package that supplied a font. */
public data class FontSourceProvenance(
    /** The source name used for diagnostics and provenance tracking. */
    public val declaredName: String,
)

/** A lowercase SHA-256 digest identifying portable font content. */
@JvmInline
public value class FontContentDigest private constructor(
    /** The 64-character lowercase hexadecimal digest. */
    public val value: String,
) {
    public companion object {
        /** Creates a digest after validating its canonical hexadecimal form. */
        public operator fun invoke(value: String): FontContentDigest {
            require(value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                "FontContentDigest must contain exactly 64 hexadecimal characters."
            }
            return FontContentDigest(value.lowercase())
        }
    }
}

/** Structured identity of either portable content or an opaque provider asset. */
public sealed interface FontSourceId {
    /** Identity derived solely from the exact portable font bytes. */
    public data class Portable(
        /** SHA-256 digest of the portable font bytes. */
        public val contentDigest: FontContentDigest,
    ) : FontSourceId

    /** Identity assigned by a provider whose token is meaningful only in its generation. */
    public data class Opaque(
        /** Stable identity of the provider or backing store. */
        public val providerId: String,
        /** Provider generation in which [sourceToken] is valid. */
        public val catalogGeneration: String,
        /** Provider-owned token for the source. */
        public val sourceToken: String,
    ) : FontSourceId {
        init {
            require(providerId.isNotBlank()) { "providerId must not be blank." }
            require(catalogGeneration.isNotBlank()) { "catalogGeneration must not be blank." }
            require(sourceToken.isNotBlank()) { "sourceToken must not be blank." }
        }
    }
}

/** Identity of one face, distinguished from sibling faces in the same source. */
public data class FontFaceId(
    /** Source containing the face. */
    public val source: FontSourceId,
    /** Zero-based face index within [source]. */
    public val faceIndex: Int,
) {
    init {
        require(faceIndex >= 0) { "faceIndex must be non-negative." }
    }
}

/** Versioned identity of the interpretation pipeline used to decode a font. */
public data class FontDataInterpretationVersion(
    /** Stable pipeline identity, independent of a particular font source. */
    public val pipelineId: String,
    /** Version of the interpretation rules. */
    public val version: String,
) {
    init {
        require(pipelineId.isNotBlank()) { "pipelineId must not be blank." }
        require(version.isNotBlank()) { "version must not be blank." }
    }
}

/**
 * One normalized variation-axis coordinate included in an instance identity.
 *
 * The tag must be an OpenType four-character tag and the value must be finite.
 * Instances are immutable and therefore safe to share between concurrent callers.
 *
 * @param tag four-character OpenType axis tag.
 * @param value normalized finite axis value; negative zero is canonicalized.
 */
public class FontAxisCoordinate(
    /** Four-character OpenType axis tag. */
    public val tag: String,
    /** Normalized finite axis value; negative zero is canonicalized. */
    value: Float,
) {
    /** Canonical finite axis value; negative zero is represented as positive zero. */
    public val value: Float = if (value == 0f) 0f else value

    init {
        require(tag.length == 4) { "Font axis tags must contain exactly four characters." }
        require(value.isFinite()) { "Font axis values must be finite." }
    }

    /** Copies this coordinate while revalidating and canonicalizing its value. */
    public fun copy(tag: String = this.tag, value: Float = this.value): FontAxisCoordinate =
        FontAxisCoordinate(tag, value)

    override fun equals(other: Any?): Boolean = other is FontAxisCoordinate && tag == other.tag && value == other.value

    override fun hashCode(): Int = 31 * tag.hashCode() + value.hashCode()

    override fun toString(): String = "FontAxisCoordinate(tag=$tag, value=$value)"
}

/**
 * Geometric parameters that affect the rendered shape of an instance.
 *
 * Axis coordinates are copied into an immutable, tag-sorted snapshot. Two
 * parameter values are equal only when their axes and synthetic geometry
 * settings are equal, which makes them suitable for instance-key equality.
 *
 * @param normalizedAxes variation coordinates to include in the identity.
 * @param syntheticBold whether synthetic bold geometry is applied.
 * @param syntheticItalic whether synthetic italic geometry is applied.
 */
public class FontGeometryParameters(
    /** Variation coordinates to include in the instance identity. */
    normalizedAxes: List<FontAxisCoordinate> = emptyList(),
    /** Whether synthetic bold geometry is applied. */
    public val syntheticBold: Boolean = false,
    /** Whether synthetic italic geometry is applied. */
    public val syntheticItalic: Boolean = false,
) {
    /** Normalized variation axes participating in the instance identity. */
    public val normalizedAxes: List<FontAxisCoordinate> = normalizedAxes
        .sortedBy { it.tag }
        .immutableListSnapshot()

    init {
        require(this.normalizedAxes.zipWithNext().all { (left, right) -> left.tag < right.tag }) {
            "normalizedAxes must be sorted by unique axis tag."
        }
    }

    /** Copies these parameters while retaining canonical axis ordering. */
    public fun copy(
        normalizedAxes: List<FontAxisCoordinate> = this.normalizedAxes,
        syntheticBold: Boolean = this.syntheticBold,
        syntheticItalic: Boolean = this.syntheticItalic,
    ): FontGeometryParameters = FontGeometryParameters(normalizedAxes, syntheticBold, syntheticItalic)

    override fun equals(other: Any?): Boolean = other is FontGeometryParameters &&
        normalizedAxes == other.normalizedAxes &&
        syntheticBold == other.syntheticBold &&
        syntheticItalic == other.syntheticItalic

    override fun hashCode(): Int {
        var result = normalizedAxes.hashCode()
        result = 31 * result + syntheticBold.hashCode()
        result = 31 * result + syntheticItalic.hashCode()
        return result
    }

    override fun toString(): String =
        "FontGeometryParameters(normalizedAxes=$normalizedAxes, syntheticBold=$syntheticBold, " +
            "syntheticItalic=$syntheticItalic)"
}

/** Complete identity of a concrete face interpretation and geometric instance. */
public data class FontInstanceKey(
    /** Face from which the instance is derived. */
    public val face: FontFaceId,
    /** Interpretation rules used to decode the face. */
    public val interpretation: FontDataInterpretationVersion,
    /** Layout size used by the instance. */
    public val layoutSize: LayoutUnit,
    /** Variation and synthetic geometry parameters. */
    public val geometry: FontGeometryParameters = FontGeometryParameters(),
)

/** Immutable byte-backed font source used by the loading pipeline. */
public class FontSource(
    sourceBytes: ByteArray,
    /** Provenance supplied by the caller for diagnostics and inspection. */
    public val provenance: FontSourceProvenance,
) {
    private val capturedBytes: ByteArray = sourceBytes.copyOf()

    /** Identifier derived from the captured bytes. */
    public val id: FontSourceId = FontSourceId.Portable(FontContentDigest(capturedBytes.sha256Hex()))

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
