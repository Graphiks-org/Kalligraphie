package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WebPortableConformanceTest {
    @Test
    fun decodesUtf8OnTheWebRuntime() {
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A\u00E9\uD83D\uDE00".encodeToByteArray())),
        ).snapshot
        assertEquals(listOf(0x41, 0xE9, 0x1F600), snapshot.scalars)
    }

    @Test
    fun declaresShapingAbsenceWithADiagnostic() {
        val identity = currentPortableCapabilityIdentity()
        assertEquals("web", identity.platformId)
        assertFalse(identity.presenceOf(PortableCapability.SHAPING))
        val diagnostic = identity.absenceDiagnostic(PortableCapability.SHAPING)
        assertNotNull(diagnostic)
        assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic.code)
    }
}
