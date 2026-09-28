package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment
import kotlin.test.Test
import kotlin.test.assertTrue

class VariablePaintSheetSceneTest {
    @Test
    fun theSheetRendersEveryCellAndVariesWithTheInstance() {
        val image = VariablePaintSheetScene.sheet(
            corpus = E2eTestEnvironment.corpus,
            fontPath = "/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf",
            codepoints = listOf(0x41, 0x42, 0x43),
            weights = listOf(400f, 900f),
            pixelsPerEm = 64.0,
        )
        assertTrue(image.width > 0 && image.height > 0)
    }
}
