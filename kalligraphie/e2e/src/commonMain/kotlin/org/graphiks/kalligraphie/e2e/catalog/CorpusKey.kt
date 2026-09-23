// CorpusKey.kt
package org.graphiks.kalligraphie.e2e.catalog

/**
 * Stable identifier of one font family of `test-fixtures/fonts/`, shared with
 * `scripts/fonts/corpus.json`.
 */
public data class CorpusKey(
    /** Directory name under `test-fixtures/fonts/`. */
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A corpus key must not be blank." }
        require(value.none { char -> char.isWhitespace() }) { "A corpus key must not contain whitespace." }
    }
}
