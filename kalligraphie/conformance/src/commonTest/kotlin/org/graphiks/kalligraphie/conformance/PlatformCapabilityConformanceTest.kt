package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformCapabilityConformanceTest {
    /**
     * Every platform declares the complete portable surface — web included, since its shaping and
     * end-to-end layout landed — and the matrix is written out per platform rather than derived from
     * the entries, so a declaration that silently gained or lost a capability fails here instead of
     * agreeing with itself. One map serves them all: a platform that grows its own surface is the
     * only reason to name it separately.
     */
    private fun expectedAvailability(platformId: String): Map<PortableCapability, Boolean> = when (platformId) {
        "jvm", "ios", "android", "web" -> mapOf(
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
