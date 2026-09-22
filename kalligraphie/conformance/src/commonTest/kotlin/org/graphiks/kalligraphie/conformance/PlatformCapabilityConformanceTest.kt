package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformCapabilityConformanceTest {
    private fun expectedAvailability(platformId: String): Map<PortableCapability, Boolean> = when (platformId) {
        "jvm" -> PortableCapability.entries.associateWith { true }
        // iOS ships the bundled HarfBuzz shaping backend; analysis and layout stay absent.
        "ios" -> mapOf(
            PortableCapability.UNICODE_ANALYSIS to false,
            PortableCapability.SHAPING to true,
            PortableCapability.END_TO_END_LAYOUT to false,
            PortableCapability.GLYPH_REPRESENTATION_VARIANTS to true,
        )
        // Android ships the bundled HarfBuzz shaping backend (API 28+); analysis and layout stay absent.
        "android" -> mapOf(
            PortableCapability.UNICODE_ANALYSIS to false,
            PortableCapability.SHAPING to true,
            PortableCapability.END_TO_END_LAYOUT to false,
            PortableCapability.GLYPH_REPRESENTATION_VARIANTS to true,
        )
        else -> error("Unexpected platform identity: $platformId")
    }

    @Test
    fun declaresTheExpectedCapabilityMatrix() {
        val identity = currentPortableCapabilityIdentity()
        val expected = expectedAvailability(identity.platformId)
        PortableCapability.entries.forEach { capability ->
            assertEquals(expected.getValue(capability), identity.presenceOf(capability), capability.name)
        }
    }

    @Test
    fun reportsAnAbsenceDiagnosticExactlyWhenACapabilityIsUnavailable() {
        val identity = currentPortableCapabilityIdentity()
        val expected = expectedAvailability(identity.platformId)
        PortableCapability.entries.forEach { capability ->
            val diagnostic = identity.absenceDiagnostic(capability)
            assertEquals(expected.getValue(capability), diagnostic == null, capability.name)
            if (diagnostic != null) assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic.code)
        }
    }

    @Test
    fun decodingRunsRegardlessOfCapabilities() {
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A".encodeToByteArray())),
        ).snapshot
        assertEquals(listOf(0x41), snapshot.scalars)
    }
}
