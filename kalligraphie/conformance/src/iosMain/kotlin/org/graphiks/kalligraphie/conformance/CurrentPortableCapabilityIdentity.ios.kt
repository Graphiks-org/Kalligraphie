package org.graphiks.kalligraphie.conformance

/**
 * iOS declares shaping through the bundled HarfBuzz backend shipped in the native artifact.
 * Unicode analysis and end-to-end layout stay absent until the portable analysis and layout
 * backends land; the portable glyph representation route remains available.
 */
public actual fun currentPortableCapabilityIdentity(): PortableCapabilityIdentity =
    PortableCapabilityIdentity(
        platformId = "ios",
        declarations = listOf(
            CapabilityDeclaration(PortableCapability.UNICODE_ANALYSIS, available = false, profileId = "absent"),
            CapabilityDeclaration(PortableCapability.SHAPING, available = true, profileId = "bundled-harfbuzz"),
            CapabilityDeclaration(PortableCapability.END_TO_END_LAYOUT, available = false, profileId = "absent"),
            CapabilityDeclaration(
                PortableCapability.GLYPH_REPRESENTATION_VARIANTS,
                available = true,
                profileId = "portable-glyph",
            ),
        ),
    )
