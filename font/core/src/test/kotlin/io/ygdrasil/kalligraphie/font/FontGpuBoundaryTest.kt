package io.ygdrasil.kalligraphie.font

import kotlin.test.Test
import kotlin.test.assertFailsWith

class FontGpuBoundaryTest {
    @Test
    fun coreDoesNotExposeGpuTelemetryContracts() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("io.ygdrasil.kalligraphie.font.FontTelemetryDomain")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("io.ygdrasil.kalligraphie.font.FontTelemetryEvidenceWriter")
        }
    }
}
