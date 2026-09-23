// CatalogClaims.kt
package org.graphiks.kalligraphie.e2e.catalog

/**
 * Renders the catalog's table claims for the corpus lint.
 *
 * The Kotlin model is the only authority on what is claimed; the Python lint reads this export and
 * checks it against the tables the fonts really carry. A table that is really present and claimed
 * nowhere fails the lint, which is how an unclaimed technology is caught.
 */
public object CatalogClaims {
    /**
     * Key of the allowlist entry that applies to every corpus family, so a structural table is not
     * excused once per family.
     */
    private const val STRUCTURAL_TABLES_KEY = "*"

    /** The one reason the structural tables are excused, stated once. */
    private const val STRUCTURAL_REASON =
        "present in every SFNT and not technology-bearing; the catalog claims technology tables only"

    /** Tables every SFNT carries and no technology owns; listed in the conventional sfnt order. */
    private val STRUCTURAL_TABLES: List<String> =
        listOf("cmap", "head", "hhea", "hmtx", "maxp", "name", "post", "OS/2")

    /**
     * Tables no entry claims on purpose, with the reason, keyed by corpus key. The wildcard key
     * `"*"` holds the tables that live in every SFNT without carrying a technology: no entry can
     * claim them, and a per-family copy of the same reason would drown the export. Another key
     * lands here only after the lint of task 12 named the table, never in advance.
     *
     * Declared after the structural tables it reads, because an object initialises its properties
     * in declaration order.
     */
    public val unclaimedAllowlist: Map<String, Map<String, String>> =
        mapOf(STRUCTURAL_TABLES_KEY to STRUCTURAL_TABLES.associateWith { STRUCTURAL_REASON })

    /** Groups the claimed tables of [entries] by corpus key. */
    public fun claimsOf(entries: List<CatalogEntry>): Map<String, Set<String>> = entries
        .filter { entry -> entry.font != null }
        .groupBy { entry -> entry.font!!.value }
        .mapValues { (_, axisEntries) -> axisEntries.flatMapTo(linkedSetOf()) { entry -> entry.tables } }

    /** Returns the canonical JSON export of [entries]. */
    public fun render(entries: List<CatalogEntry>): String = buildString {
        appendLine("{")
        appendLine("  \"schema\": \"kalligraphie.e2e-claims/v1\",")
        appendLine("  \"fonts\": {")
        val claims = claimsOf(entries).entries.sortedBy { (key, _) -> key }
        claims.forEachIndexed { index, (key, tables) ->
            append("    \"$key\": [")
            append(tables.sorted().joinToString(", ") { table -> "\"$table\"" })
            append("]")
            appendLine(if (index == claims.size - 1) "" else ",")
        }
        appendLine("  },")
        appendLine("  \"allowUnclaimed\": {")
        val allowlist = unclaimedAllowlist.entries.sortedBy { (key, _) -> key }
        allowlist.forEachIndexed { index, (key, tables) ->
            append("    \"$key\": {")
            append(tables.entries.sortedBy { (table, _) -> table }.joinToString(", ") { (table, reason) -> "\"$table\": \"$reason\"" })
            append("}")
            appendLine(if (index == allowlist.size - 1) "" else ",")
        }
        appendLine("  }")
        appendLine("}")
    }
}
