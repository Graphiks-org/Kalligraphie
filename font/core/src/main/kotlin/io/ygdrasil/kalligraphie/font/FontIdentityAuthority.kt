package io.ygdrasil.kalligraphie.font

/**
 * Canonical authority for identities derived from caller-owned font bytes.
 */
object FontIdentityAuthority {
    /**
     * Captures the stable source facts for an in-memory font.
     */
    fun memorySource(
        bytes: ByteArray,
        declaredName: String,
        parserGeneration: Int = 1,
    ): FontSourceIdentityPreimage {
        val snapshot = bytes.copyOf()
        return FontSourceIdentityPreimage.fromCapturedBytes(
            kind = FontSourceKind.MEMORY,
            declaredName = declaredName,
            licenseId = null,
            bytes = snapshot,
            faceCount = snapshot.collectionFaceCountOrNull() ?: 1,
            tableTags = emptyList(),
            parserGeneration = parserGeneration,
            hostDependent = false,
        )
    }
}

private fun ByteArray.collectionFaceCountOrNull(): Int? {
    if (size < TTC_HEADER_SIZE || !startsWithTtcTag()) return null
    val faceCount = readUInt32BigEndian(offset = TTC_FACE_COUNT_OFFSET)
    return faceCount.takeIf { it <= Int.MAX_VALUE.toLong() }?.toInt()
}

private fun ByteArray.startsWithTtcTag(): Boolean =
    this[0] == 't'.code.toByte() &&
        this[1] == 't'.code.toByte() &&
        this[2] == 'c'.code.toByte() &&
        this[3] == 'f'.code.toByte()

private fun ByteArray.readUInt32BigEndian(offset: Int): Long =
    ((this[offset].toLong() and 0xffL) shl 24) or
        ((this[offset + 1].toLong() and 0xffL) shl 16) or
        ((this[offset + 2].toLong() and 0xffL) shl 8) or
        (this[offset + 3].toLong() and 0xffL)

private const val TTC_HEADER_SIZE = 12
private const val TTC_FACE_COUNT_OFFSET = 8
