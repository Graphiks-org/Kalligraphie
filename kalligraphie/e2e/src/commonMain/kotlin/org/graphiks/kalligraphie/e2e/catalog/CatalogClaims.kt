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
     * The wildcard key, read in two positions. As the top-level key of `allowUnclaimed` it names the
     * entry that applies to every corpus family, so a structural table is not excused once per
     * family; inside one family's entry it excuses that family whole, and says why the family stays
     * outside the claimed perimeter.
     */
    private const val WILDCARD_KEY = "*"

    /** The one reason the structural tables are excused, stated once. */
    private const val STRUCTURAL_REASON =
        "present in every SFNT and not technology-bearing; the catalog claims technology tables only"

    /** Tables every SFNT carries and no technology owns; listed in the conventional sfnt order. */
    private val STRUCTURAL_TABLES: List<String> =
        listOf("cmap", "head", "hhea", "hmtx", "maxp", "name", "post", "OS/2")

    /** The reason the outline tables of the sbix family are excused, shared by `glyf` and `loca`. */
    private const val SBIX_OUTLINES_REASON =
        "the catalogue's sbix scene draws the strike and resolves advances through hhea/hmtx; the " +
            "sbix route does not require glyf, so this family's outlines are carried but never read"

    /** The reason the outline tables of the COLR v1 fixture are excused, shared by `glyf` and `loca`. */
    private const val REJECTED_FIXTURE_OUTLINES_REASON =
        "the colour axis pins this fixture as rejected at face resolution, the CPU compositor " +
            "refusing GlyphClip, so no catalogued scene ever decodes its outlines"

    /**
     * The reason the vertical-metric tables of the VVAR fixture are excused, shared by `VVAR`,
     * `vhea` and `vmtx`.
     */
    private const val VVAR_VERTICAL_REASON =
        "carried by the fixture; the catalogued scene rasterises the outline and observes no " +
            "vertical metric, which the shaping suite covers"

    /** The reason the VVAR fixture's axis declaration is excused. */
    private const val VVAR_AXIS_REASON =
        "carried by the fixture; the catalogued scene instantiates the default location, so it " +
            "applies no axis value and reads no axis declaration"

    /**
     * Tables a family an entry does reference really carries while no catalogued scene reads them,
     * keyed by corpus key. Each reason names the catalogued scene that stops short of the table, so
     * the excuse reads as a decision rather than as an omission.
     *
     * Declared before [unclaimedAllowlist], which reads it while the object initialises.
     */
    private val UNREAD_TABLES: Map<String, Map<String, String>> = mapOf(
        "bungee-color" to mapOf(
            "GPOS" to "no catalogued scene shapes text with this family: the colour sheet resolves " +
                "code points through cmap and paints the COLR graph, never consulting pair positioning",
            "GSUB" to "no catalogued scene shapes text with this family: the colour sheet never " +
                "selects a substitution, so its locl and stylistic-set lookups carry no expectation",
        ),
        "emoji-two-colr-v0" to mapOf(
            "GSUB" to "no catalogued scene shapes text with this family: the emoji sheets resolve " +
                "code points through cmap and paint the COLR graph, never applying ccmp",
        ),
        "kalligraphie-var-colr" to mapOf(
            "glyf" to REJECTED_FIXTURE_OUTLINES_REASON,
            "loca" to REJECTED_FIXTURE_OUTLINES_REASON,
        ),
        "kalligraphie-var-vvar" to mapOf(
            "VVAR" to VVAR_VERTICAL_REASON,
            "vhea" to VVAR_VERTICAL_REASON,
            "vmtx" to VVAR_VERTICAL_REASON,
            "fvar" to VVAR_AXIS_REASON,
        ),
        "liberation" to mapOf(
            "kern" to "legacy pair-kerning records, every one of the 908 reproduced by the GPOS " +
                "kerning the composed lines apply; no catalogued scene reads the redundant table",
        ),
        "skia-sbix" to mapOf(
            "glyf" to SBIX_OUTLINES_REASON,
            "loca" to SBIX_OUTLINES_REASON,
        ),
        "worksans" to mapOf(
            "STAT" to "the ladder selects its instances by design `wght` coordinate through " +
                "`fvar` and `avar` and reads no style attribute; `variation.stat` records that " +
                "portable STAT reading is not implemented yet",
            "gasp" to "the ladder rasterises the varied outlines with the portable CPU rasterizer, " +
                "which applies no grid-fitting hint profile, so the table's flags and ranges are " +
                "carried and never consulted",
        ),
    )

    /**
     * Families no catalog entry references at all, each with the reason it stands outside the
     * claimed perimeter. The lint reads such an entry as one excuse for the whole family, so no
     * table of these families is left unaccounted for.
     *
     * Declared before [unclaimedAllowlist], which reads it while the object initialises.
     */
    private val UNREFERENCED_FAMILIES: Map<String, String> = mapOf(
        "cff2-variable" to "synthetic variable CFF 2 fixture of the CFF journey and the scaler's " +
            "variation tests; the CFF 2 outline expectation is pinned on cff2-liberation, and no " +
            "catalogued scene references this family",
        "dejavu" to "real DejaVu Sans, the default wide-coverage face of the layout module's " +
            "paragraph fixtures; no catalogued scene references this family",
        "gdef-kern" to "minimal GDEF/GPOS caret fixture of the layout and shaping suites; no " +
            "catalogued scene references this family",
        "liberation-amiri-collection" to "Liberation + Amiri TrueType collection, the multi-face " +
            "container fixture of the platform registry suites; no catalogued scene references this family",
        "noto-sans-jp" to "subset variable JP fixture whose vertical advances the shaping module " +
            "cross-checks; no catalogued scene references this family",
        "skia-colr-v1" to "static COLR v1 fixture with transforms and gradients, read by the colour " +
            "representation tests; the color axis pins COLR v1 on the synthetic variable fixture, " +
            "and no catalogued scene uses the Skia file",
        "twemoji-svginot-glyph5" to "SVG-in-OpenType subset read by the glyph-representation suite; " +
            "no catalogued scene renders an SVG glyph, so the family stays outside the claimed perimeter",
    )

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
     * [UNREAD_TABLES] holds the tables a referenced family carries and no catalogued scene reads;
     * [UNREFERENCED_FAMILIES] holds the families no entry references, each excused whole by a
     * wildcard reason of its own.
     *
     * Declared after the structural tables and the family excuses it reads, because an object
     * initialises its properties in declaration order.
     */
    public val unclaimedAllowlist: Map<String, Map<String, String>> =
        mapOf(WILDCARD_KEY to STRUCTURAL_TABLES.associateWith { STRUCTURAL_REASON }) +
            UNREAD_TABLES +
            UNREFERENCED_FAMILIES.mapValues { (_, reason) -> mapOf(WILDCARD_KEY to reason) }

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
