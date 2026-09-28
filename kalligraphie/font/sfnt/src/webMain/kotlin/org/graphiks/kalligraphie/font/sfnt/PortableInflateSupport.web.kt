package org.graphiks.kalligraphie.font.sfnt

internal actual fun platformInflateSupport(): InflateSupport = PortableInflateSupport

private object PortableInflateSupport : InflateSupport {
    override fun inflateZlib(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome {
        if (compressed.size < 6) return InflateOutcome.Malformed("zlib header is truncated")
        val cmf = compressed[0].toInt() and 0xFF
        val flg = compressed[1].toInt() and 0xFF
        if (cmf and 0x0F != 8) return InflateOutcome.Malformed("zlib compression method is not deflate")
        if (cmf ushr 4 > 7) return InflateOutcome.Malformed("zlib window size is invalid")
        if ((cmf shl 8 or flg) % 31 != 0) return InflateOutcome.Malformed("zlib header check is invalid")
        if (flg and 0x20 != 0) return InflateOutcome.Malformed("zlib preset dictionary is unsupported")
        val bodyEnd = compressed.size - 4
        val raw = when (val result = RawInflate(compressed, 2, bodyEnd, maxOutputBytes).inflate()) {
            is RawInflate.Result.Success -> result.bytes
            is RawInflate.Result.Malformed -> return InflateOutcome.Malformed(result.detail)
            is RawInflate.Result.TooLarge -> return InflateOutcome.LimitExceeded(result.observed, result.maximum)
        }
        val expected = readBigEndianInt(compressed, bodyEnd)
        if (Adler32.of(raw) != expected) return InflateOutcome.Malformed("zlib checksum is invalid")
        return InflateOutcome.Success(raw)
    }

    override fun gunzip(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome {
        if (compressed.size < 18) return InflateOutcome.Malformed("gzip member is truncated")
        if (compressed[0] != 0x1F.toByte() || compressed[1] != 0x8B.toByte()) {
            return InflateOutcome.Malformed("gzip magic is missing")
        }
        if (compressed[2] != 8.toByte()) return InflateOutcome.Malformed("gzip method is not deflate")
        var offset = 10
        val flags = compressed[3].toInt() and 0xFF
        if (flags and 0xE0 != 0) return InflateOutcome.Malformed("gzip reserved flags are set")
        if (flags and 0x04 != 0) { // FEXTRA
            if (offset + 2 > compressed.size) return InflateOutcome.Malformed("gzip extra field is truncated")
            val len = (compressed[offset].toInt() and 0xFF) or ((compressed[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2 + len
        }
        if (flags and 0x08 != 0) offset = skipZeroTerminated(compressed, offset) ?: return InflateOutcome.Malformed("gzip name is truncated")
        if (flags and 0x10 != 0) offset = skipZeroTerminated(compressed, offset) ?: return InflateOutcome.Malformed("gzip comment is truncated")
        if (flags and 0x02 != 0) offset += 2 // FHCRC
        if (offset > compressed.size - 8) return InflateOutcome.Malformed("gzip header is truncated")
        val bodyEnd = compressed.size - 8
        val raw = when (val result = RawInflate(compressed, offset, bodyEnd, maxOutputBytes).inflate()) {
            is RawInflate.Result.Success -> result.bytes
            is RawInflate.Result.Malformed -> return InflateOutcome.Malformed(result.detail)
            is RawInflate.Result.TooLarge -> return InflateOutcome.LimitExceeded(result.observed, result.maximum)
        }
        // Single-member acceptance: RawInflate consumed exactly bodyEnd; Okio rejects trailing input.
        if (Crc32.of(raw) != readLittleEndianInt(compressed, bodyEnd)) {
            return InflateOutcome.Malformed("gzip checksum is invalid")
        }
        if ((raw.size.toLong() and 0xFFFFFFFFL) != (readLittleEndianInt(compressed, bodyEnd + 4).toLong() and 0xFFFFFFFFL)) {
            return InflateOutcome.Malformed("gzip length is invalid")
        }
        return InflateOutcome.Success(raw)
    }
}

private fun skipZeroTerminated(input: ByteArray, start: Int): Int? {
    var index = start
    while (index < input.size) {
        if (input[index] == 0.toByte()) return index + 1
        index++
    }
    return null
}

private fun readBigEndianInt(input: ByteArray, offset: Int): Int =
    ((input[offset].toInt() and 0xFF) shl 24) or ((input[offset + 1].toInt() and 0xFF) shl 16) or
        ((input[offset + 2].toInt() and 0xFF) shl 8) or (input[offset + 3].toInt() and 0xFF)

private fun readLittleEndianInt(input: ByteArray, offset: Int): Int =
    (input[offset].toInt() and 0xFF) or ((input[offset + 1].toInt() and 0xFF) shl 8) or
        ((input[offset + 2].toInt() and 0xFF) shl 16) or ((input[offset + 3].toInt() and 0xFF) shl 24)
