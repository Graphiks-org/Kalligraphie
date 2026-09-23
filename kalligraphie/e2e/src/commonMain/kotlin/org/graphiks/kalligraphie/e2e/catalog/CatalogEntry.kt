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
    /** Frame policy of the generated scene; set exactly for [CatalogStatus.Supported]. */
    public val frame: SceneFramePolicy? = null,
) {
    init {
        require(id.isNotBlank()) { "A catalog entry id must not be blank." }
        require(id.matches(ID_PATTERN)) { "A catalog entry id must be lower-case dotted form: $id" }
    }

    private companion object {
        val ID_PATTERN = Regex("[a-z0-9]+(?:[.-][a-z0-9]+)*")
    }
}
