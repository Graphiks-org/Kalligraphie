package org.graphiks.kalligraphie.bench.scenarios

import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

class ContainerScenariosTest {
    @Test
    fun exposesTheThreeContainerProfiles() {
        assertEquals(
            listOf("WoffColdCapture", "Woff2ColdCapture", "Woff2ColdGlyph"),
            containerScenarios(TestCorpus).map { it.name },
        )
    }
}

/**
 * The in-memory corpus for this test: `containerScenarios` reads bytes only to build its fixtures,
 * so the profiles' names are asserted without a real font and without touching the class path.
 */
private object TestCorpus : FixtureCorpus {
    override fun bytes(path: String): ByteArray = ByteArray(0)

    override fun sha256Hex(path: String): String = ""
}
