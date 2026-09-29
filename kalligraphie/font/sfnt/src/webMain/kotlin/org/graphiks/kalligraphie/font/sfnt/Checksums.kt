package org.graphiks.kalligraphie.font.sfnt

internal object Adler32 {
    fun of(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
        var a = 1
        var b = 0
        for (index in start until end) {
            a = (a + (bytes[index].toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }
}

internal object Crc32 {
    private val table = IntArray(256).also { table ->
        for (index in 0 until 256) {
            var value = index
            repeat(8) { value = if (value and 1 != 0) 0xEDB88320.toInt() xor (value ushr 1) else value ushr 1 }
            table[index] = value
        }
    }

    fun of(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
        var crc = -1
        for (index in start until end) {
            crc = table[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}
