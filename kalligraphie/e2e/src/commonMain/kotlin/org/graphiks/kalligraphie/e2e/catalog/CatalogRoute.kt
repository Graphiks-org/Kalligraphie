// CatalogRoute.kt
package org.graphiks.kalligraphie.e2e.catalog

/**
 * The platform route a scene needs, and therefore the ground on which it can be verified.
 *
 * A scene is rendered through the public facade and the CPU rasterizer, and that whole chain is
 * portable for every font that resolves to a glyph representation. Composing *text* is the one
 * thing that is not: the paragraph facade that turns a text range into positioned glyphs exists
 * only where the host supplies Unicode analysis and shaping, so a scene that lays out a line
 * cannot run on a host that does not.
 *
 * Declaring the route is what lets the catalog be verified on every platform instead of only where
 * the harness happens to compile: each platform verifies the entries whose route it can serve, and
 * an entry it cannot serve is *justified* by the platform's own declared capabilities rather than
 * silently skipped. The mapping from a route to those capabilities lives with the harness, not
 * here, so this module keeps depending on `:kalligraphie:api` alone.
 */
public enum class CatalogRoute {
    /**
     * Resolves font bytes into glyph representations — outlines, paint graphs, bitmaps, variable
     * instances — and rasterizes them. Needs the portable glyph-representation route only.
     */
    PORTABLE_GLYPH,

    /**
     * Lays out text through the paragraph facade before rasterizing it.
     *
     * The editable-paragraph route rests on the glyph-representation one: it selects faces, shapes
     * them and hands positions to the same rasterizer.
     */
    PARAGRAPH_LAYOUT,
}
