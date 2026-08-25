package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.font.FontSource
import org.graphiks.kalligraphie.font.FontSourceID
import org.graphiks.kalligraphie.font.FontSourceKind
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class SFNTReaderBoundaryContractTest {
    private val reader = DefaultSFNTReader()

    @Test
    fun rejectsATruncatedTableDirectory() {
        assertFailsWith<IllegalArgumentException> {
            reader.readDirectory(source(singleTableSfnt(tableLength = 4).copyOf(20)))
        }
    }

    @Test
    fun rejectsATableRangePastTheAvailableSourceBytes() {
        val source = source(singleTableSfnt(tableLength = 5))
        val record = reader.readDirectory(source).tables.single()

        assertFailsWith<IllegalArgumentException> { reader.readTable(source, record) }
    }

    private fun source(bytes: ByteArray) = FontSource(
        id = FontSourceID(Uuid.NIL),
        kind = FontSourceKind.GENERATED_FIXTURE,
        displayName = "minimal.sfnt",
        bytes = bytes,
    )

    private fun singleTableSfnt(tableLength: Int): ByteArray = ByteArray(32).apply {
        this[1] = 1
        this[5] = 1
        this[7] = 16
        this[12] = 'n'.code.toByte()
        this[13] = 'a'.code.toByte()
        this[14] = 'm'.code.toByte()
        this[15] = 'e'.code.toByte()
        this[23] = 28
        this[27] = tableLength.toByte()
    }
}
