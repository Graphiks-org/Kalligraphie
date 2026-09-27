package org.graphiks.kalligraphie.conformance

/**
 * iOS declares every portable capability present.
 *
 * Shaping comes from the bundled HarfBuzz backend shipped in the native artifact, the glyph
 * representation route is the portable one, and the remaining two are served by this repository's
 * own portable code: Unicode analysis and line breaking resolve from the module's generated
 * Unicode 16.0 tables, and the paragraph facade composes text through them without a platform
 * Unicode engine, so a device's ICU version cannot change the result.
 *
 * The profile identifiers name those routes, not a device: `portable-unicode-16.0` is the pinned
 * table release the analysis resolves from, and `portable-paragraph` is the portable paragraph
 * composition over it.
 */
public actual fun currentPortableCapabilityIdentity(): PortableCapabilityIdentity =
    PortableCapabilityIdentity(
        platformId = "ios",
        declarations = listOf(
            CapabilityDeclaration(
                PortableCapability.UNICODE_ANALYSIS,
                available = true,
                profileId = "portable-unicode-16.0",
            ),
            CapabilityDeclaration(PortableCapability.SHAPING, available = true, profileId = "bundled-harfbuzz"),
            CapabilityDeclaration(
                PortableCapability.END_TO_END_LAYOUT,
                available = true,
                profileId = "portable-paragraph",
            ),
            CapabilityDeclaration(
                PortableCapability.GLYPH_REPRESENTATION_VARIANTS,
                available = true,
                profileId = "portable-glyph",
            ),
        ),
    )
