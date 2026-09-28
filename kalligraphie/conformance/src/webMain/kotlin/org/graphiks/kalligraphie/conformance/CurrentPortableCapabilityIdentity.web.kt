package org.graphiks.kalligraphie.conformance

/**
 * Web declares its capability surface explicitly.
 *
 * Unicode analysis and line breaking resolve from the repository's own portable Unicode 16.0
 * tables, and the glyph representation route is the portable one, so both are present without a
 * platform engine. `SHAPING` is absent until the kffi HarfBuzz web artifact lands (Phase 2), and
 * `END_TO_END_LAYOUT` is consequently absent too: the paragraph facade cannot compose without a
 * shaper. The absence diagnostic is emitted for both.
 */
public actual fun currentPortableCapabilityIdentity(): PortableCapabilityIdentity =
    PortableCapabilityIdentity(
        platformId = "web",
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
