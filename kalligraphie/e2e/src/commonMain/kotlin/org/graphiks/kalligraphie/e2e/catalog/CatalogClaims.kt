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
     * Tables an entry is allowed to leave unclaimed, with the reason, keyed by corpus key.
     *
     * The wildcard key `"*"` excuses the tables every SFNT carries without carrying a technology:
     * they deserve no claim, and a per-family copy of one reason would drown the export. It is an
     * excuse for the families where no entry claims such a table, not a statement that none ever
     * does — `cmap`, `hmtx`, `OS/2` and the rest are claimed by the entries of the families whose
     * scenes really read them. The lint merges these reasons with the ones of the family before it
     * checks, so a claimed table stays claimed and the merged reason is simply not needed.
     *
     * Another key lands here only after the lint of task 12 named the table, never in advance.
     *
     * Declared after the structural tables it reads, because an object initialises its properties
     * in declaration order.
     */
    public val unclaimedAllowlist: Map<String, Map<String, String>> =
        mapOf(STRUCTURAL_TABLES_KEY to STRUCTURAL_TABLES.associateWith { STRUCTURAL_REASON })

    /**
     * Groups the claimed tables of [entries] by corpus key. The sets carry no guaranteed order: the
     * canonical order is the one [render] produces with `sorted()`, not one of these sets.
     */
    public fun claimsOf(entries: List<CatalogEntry>): Map<String, Set<String>> = entries
        .filter { entry -> entry.font != null }
        .groupBy { entry -> entry.font!!.value }
        .mapValues { (_, axisEntries) -> axisEntries.flatMapTo(linkedSetOf()) { entry -> entry.tables } }

    /**
     * Returns the canonical JSON export of [entries], with [allowlist] written under
     * `allowUnclaimed`. [allowlist] defaults to [unclaimedAllowlist] and is a parameter so a test
     * can prove that a hand-written reason survives the escaping.
     */
    public fun render(
        entries: List<CatalogEntry>,
        allowlist: Map<String, Map<String, String>> = unclaimedAllowlist,
    ): String = buildString {
        appendLine("{")
        appendLine("  \"schema\": \"kalligraphie.e2e-claims/v1\",")
        appendLine("  \"fonts\": {")
        val claims = claimsOf(entries).entries.sortedBy { (key, _) -> key }
        claims.forEachIndexed { index, (key, tables) ->
            append("    ").append(jsonString(key)).append(": [")
            append(tables.sorted().joinToString(", ") { table -> jsonString(table) })
            append("]")
            appendLine(if (index == claims.size - 1) "" else ",")
        }
        appendLine("  },")
        appendLine("  \"allowUnclaimed\": {")
        val allowlistEntries = allowlist.entries.sortedBy { (key, _) -> key }
        allowlistEntries.forEachIndexed { index, (key, tables) ->
            append("    ").append(jsonString(key)).append(": {")
            append(
                tables.entries.sortedBy { (table, _) -> table }
                    .joinToString(", ") { (table, reason) -> "${jsonString(table)}: ${jsonString(reason)}" },
            )
            append("}")
            appendLine(if (index == allowlistEntries.size - 1) "" else ",")
        }
        appendLine("  }")
        appendLine("}")
    }

    /**
     * Escapes [raw] as a JSON string literal, quotation marks, backslash and control characters
     * included: a hand-written allowlist reason must not be able to break the document the lint
     * parses. The escapes are the ones `json.loads` accepts.
     */
    private fun jsonString(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (char in raw) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (char.code < 0x20) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
        append('"')
    }
}
