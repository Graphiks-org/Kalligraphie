package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.catalog.KALNIA_GLAZE
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment
import kotlin.test.Test
import kotlin.test.assertTrue

class KalniaGlazeAlphabetSceneTest {
    @Test
    fun theAlphabetSheetRendersEveryCapitalAtTwoWeights() {
        val image = VariablePaintSheetScene.alphabetSheet(
            corpus = E2eTestEnvironment.corpus,
            fontPath = KALNIA_GLAZE,
            codepoints = (0x41..0x5A).toList(),
            weights = listOf(100f, 700f),
            pixelsPerEm = 48.0,
        )
        assertTrue(image.width > 0 && image.height > 0)
    }
}
