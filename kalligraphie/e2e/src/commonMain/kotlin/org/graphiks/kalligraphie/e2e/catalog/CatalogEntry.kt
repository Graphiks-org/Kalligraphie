// CatalogEntry.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** One declared expectation about one font technology. */
public data class CatalogEntry(
    /** Stable identifier, unique across every axis, lower-case dotted form. */
    public val id: String,
    /** Dimension this entry belongs to. */
    public val axis: CatalogAxis,
    /** Short human description of the technology, e.g. "COLR v0 + CPAL v0", in both languages. */
    public val technology: CatalogText,
    /** Corpus family carrying the technology, or null when none applies yet. */
    public val font: CorpusKey?,
    /** Expected behaviour. */
    public val status: CatalogStatus,
    /** Free-form labels for filtering and reporting. */
    public val tags: Set<String> = emptySet(),
    /** SFNT tables this entry's technology consumes, e.g. `setOf("COLR", "CPAL")`. */
    public val tables: Set<String> = emptySet(),
    /** Manifest route of the generated scene; set exactly for [CatalogStatus.Supported]. */
    public val family: GoldenSceneFamily? = null,
    /**
     * Platform route the scene needs; set exactly for [CatalogStatus.Supported].
     *
     * The entry's half of a two-way check: its renderer declares the same route and the harness
     * refuses a disagreement, so an entry cannot claim a route its scene does not use. The declared
     * route is what decides whether a platform verifies this scene or justifies skipping it against
     * its own capability matrix.
     */
    public val route: CatalogRoute? = null,
    /** Frame policy of the generated scene; set exactly for [CatalogStatus.Supported]. */
    public val frame: SceneFramePolicy? = null,
    /**
     * Corpus families the one scene of this entry composes beside [font], in declaration order.
     *
     * Empty for the ordinary entry, whose scene rests on a single family. A composition entry — the
     * mosaic that mixes representations, faces and styles in one image — declares every family it
     * draws, and its renderer must load exactly the declared set: the ratchet checks both
     * directions, so a composed font cannot appear undeclared and a declared one cannot be missing.
     * [font] stays the primary family, the one this entry's [tables] are claimed against.
     */
    public val composedOf: List<CorpusKey> = emptyList(),
    /**
     * SFNT tables the scene of this entry exercises in each family of [composedOf].
     *
     * A composed scene reads different tables in different families — the mosaic draws outlines in
     * the Latin families, a colour graph in the emoji one and a strike in the bitmap one — so its
     * claims are per family, never a union that would credit a family with a table it never
     * contributes. Every composed family must appear, and [tables] stays the claim of the primary
     * family [font]. The lint reads both through the same per-family export.
     */
    public val composedTables: Map<CorpusKey, Set<String>> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "A catalog entry id must not be blank." }
        require(id.matches(ID_PATTERN)) { "A catalog entry id must be lower-case dotted form: $id" }
        require(composedOf.none { key -> key == font }) { "A composed entry must not repeat its primary family: $id" }
        require(composedOf.distinct().size == composedOf.size) { "A composed entry must not repeat a family: $id" }
        require(composedTables.keys.toSet() == composedOf.toSet()) {
            "a composed entry must claim the tables of every family it composes, and of no other: $id"
        }
        require(composedTables.values.none { claims -> claims.isEmpty() }) {
            "a composed entry must claim at least one table per family it composes: $id"
        }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")
    }
}
