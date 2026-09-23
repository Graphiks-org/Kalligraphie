package org.graphiks.kalligraphie.e2e.catalog

/**
 * One human sentence carried in both documentation languages.
 *
 * [english] is the canonical wording: it is what the Kotlin source, the identifiers and the
 * technology vocabulary are written in, and it is the text the English matrix renders. [french] is
 * its translation, and the French matrix renders that one — a generated page whose prose is English
 * is a bilingual page only in its headings.
 *
 * Both wordings are required. A default French wording would let a new entry land as an English
 * sentence on the French page, which is exactly the defect this type exists to prevent.
 */
public class CatalogText(
    /** Canonical English wording. */
    public val english: String,
    /** French wording of the same sentence. */
    public val french: String,
) {
    init {
        require(english.isNotBlank()) { "A catalog text must carry its English wording." }
        require(french.isNotBlank()) { "A catalog text must carry its French wording." }
    }

    /** Returns the wording of [language]. */
    public fun text(language: CatalogMatrixLanguage): String = when (language) {
        CatalogMatrixLanguage.EN -> english
        CatalogMatrixLanguage.FR -> french
    }

    override fun equals(other: Any?): Boolean =
        other is CatalogText && english == other.english && french == other.french

    override fun hashCode(): Int = 31 * english.hashCode() + french.hashCode()

    override fun toString(): String = "CatalogText(english=$english, french=$french)"
}
