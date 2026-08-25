package org.graphiks.kalligraphie.font

import org.graphiks.kalligraphie.font.sfnt.DefaultSFNTReader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class FontPublicJvmContractTest {
    @Test
    fun readsAnInMemorySfntThroughThePublicFontModule() {
        val bytes = ByteArray(32).apply {
            this[1] = 1
            this[5] = 1
            this[12] = 'n'.code.toByte()
            this[13] = 'a'.code.toByte()
            this[14] = 'm'.code.toByte()
            this[15] = 'e'.code.toByte()
            this[23] = 28
            this[27] = 4
            this[28] = 'K'.code.toByte()
            this[29] = 'A'.code.toByte()
            this[30] = 'L'.code.toByte()
            this[31] = 'L'.code.toByte()
        }
        val source = FontSource(
            id = FontSourceID(Uuid.NIL),
            kind = FontSourceKind.MEMORY,
            displayName = "memory.sfnt",
            bytes = bytes,
        )
        val reader = DefaultSFNTReader()
        val record = reader.readDirectory(source).tables.single()

        assertEquals("name", record.tag.value)
        assertContentEquals("KALL".encodeToByteArray(), reader.readTable(source, record))
    }
}
