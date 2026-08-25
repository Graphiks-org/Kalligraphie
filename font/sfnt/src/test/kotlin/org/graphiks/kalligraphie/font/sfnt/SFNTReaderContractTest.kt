package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.font.FontSource
import org.graphiks.kalligraphie.font.FontSourceID
import org.graphiks.kalligraphie.font.FontSourceKind
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SFNTReaderContractTest {
    @Test
    fun readsKnownTablesFromLicensedTrueTypeInput() {
        val source = FontSource(
            id = FontSourceID(Uuid.NIL),
            kind = FontSourceKind.BUNDLED_FIXTURE,
            displayName = "LiberationSans-Regular.ttf",
            bytes = checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")).use { it.readBytes() },
        )
        val reader = DefaultSFNTReader()

        val directory = reader.readDirectory(source)
        val tags = directory.tables.map { it.tag.value }.toSet()

        assertTrue(setOf("cmap", "head", "name").all(tags::contains))
        assertTrue(reader.readTable(source, directory.tables.first { it.tag.value == "name" }).isNotEmpty())
    }
}
