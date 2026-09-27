package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformCapabilityConformanceTest {
    /**
     * Every platform now declares the complete portable surface, and the matrix is written out per
     * platform rather than derived from the entries, so a declaration that silently gained or lost
     * a capability fails here instead of agreeing with itself.
     */
    private fun expectedAvailability(platformId: String): Map<PortableCapability, Boolean> = when (platformId) {
        "jvm", "ios", "android" -> mapOf(
            PortableCapability.UNICODE_ANALYSIS to true,
            PortableCapability.SHAPING to true,
            PortableCapability.END_TO_END_LAYOUT to true,
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
