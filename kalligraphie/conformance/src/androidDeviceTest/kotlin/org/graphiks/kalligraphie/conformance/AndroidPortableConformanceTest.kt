package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class AndroidPortableConformanceTest {
    @Test
    fun decodesUtf8OnTheAndroidRuntime() {
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A\u00E9\uD83D\uDE00".encodeToByteArray())),
        ).snapshot
        assertEquals(listOf(0x41, 0xE9, 0x1F600), snapshot.scalars)
        assertEquals(listOf(1, 2, 4), snapshot.sourceRanges.map { it.endExclusive.value - it.start.value })
    }

    @Test
    fun cancelsOnTheAndroidRuntime() {
        val outcome = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("abc".encodeToByteArray())),
            TextDecodingProfile.unbounded,
            CancellationToken.cancelled,
        )
        assertIs<TextDecodingOutcome.Cancelled>(outcome)
    }

    @Test
    fun declaresAndroidCapabilitiesAndReportsTheAbsenceDiagnostic() {
        val identity = currentPortableCapabilityIdentity()
        assertEquals("android", identity.platformId)
        assertFalse(identity.presenceOf(PortableCapability.SHAPING))
        assertEquals(
            CAPABILITY_ABSENCE_DIAGNOSTIC_CODE,
            identity.absenceDiagnostic(PortableCapability.SHAPING)?.code,
        )
    }
}
