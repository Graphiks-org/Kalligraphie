// CatalogAuditor.kt
package org.graphiks.kalligraphie.e2e.catalog

/** One rule violation found in a catalog. */
public data class CatalogViolation(
    /** Id of the offending entry, or null for a catalog-wide violation. */
    public val entryId: String?,
    /** Stable rule name. */
    public val rule: String,
    /** Human-readable detail. */
    public val detail: String,
)

/**
 * Structural audit of an [ExpectationCatalog]: the rules a reviewer would otherwise have to hold
 * in their head across eight axis files. Returns every violation instead of failing on the first,
 * so one run reports the whole state.
 */
public object CatalogAuditor {
    /** Audits [entries], returning violations in entry order followed by catalog-wide rules. */
    public fun audit(entries: List<CatalogEntry>): List<CatalogViolation> {
        val violations = ArrayList<CatalogViolation>()
        val seen = LinkedHashMap<String, CatalogEntry>()
        for (entry in entries) {
            val previous = seen.put(entry.id, entry)
            if (previous != null) {
                violations.add(
                    CatalogViolation(
                        entry.id,
                        "duplicate-id",
                        "${entry.id} is declared twice (axes ${previous.axis} and ${entry.axis})",
                    ),
                )
                continue
            }
            violations.addAll(auditEntry(entry))
        }
        for (axis in CatalogAxis.entries) {
            if (entries.none { entry -> entry.axis == axis }) {
                violations.add(CatalogViolation(null, "axis-uncovered", "axis $axis has no catalog entry"))
            }
        }
        return violations
    }

    private fun auditEntry(entry: CatalogEntry): List<CatalogViolation> {
        val violations = ArrayList<CatalogViolation>()
        when (entry.status) {
            is CatalogStatus.Supported -> {
                if (entry.font == null) violations.add(entry.violation("supported-missing-font", "a supported entry must name its corpus font"))
                if (entry.family == null) violations.add(entry.violation("supported-missing-family", "a supported entry must name its scene family"))
                if (entry.frame == null) violations.add(entry.violation("supported-missing-frame", "a supported entry must name its frame policy"))
                if (entry.route == null) {
                    violations.add(entry.violation("supported-missing-route", "a supported entry must name the platform route its scene needs"))
                }
                if (entry.tables.isEmpty()) violations.add(entry.violation("supported-without-tables", "a supported entry must claim the tables it exercises"))
            }

            is CatalogStatus.ExpectedRejection, is CatalogStatus.OutOfScope -> {
                if (entry.family != null || entry.frame != null || entry.route != null) {
                    violations.add(entry.violation("non-scene-carries-scene-fields", "${entry.id} declares scene fields but produces no scene"))
                }
            }

            is CatalogStatus.NotYet -> {
                if (entry.family != null || entry.frame != null || entry.route != null) {
                    violations.add(entry.violation("non-scene-carries-scene-fields", "${entry.id} declares scene fields but produces no scene"))
                }
                if (entry.status.currentBehavior != null && entry.font == null) {
                    violations.add(entry.violation("pinned-behaviour-missing-font", "${entry.id} pins an observable behaviour without a font to observe it on"))
                }
            }
        }
        return violations
    }

    private fun CatalogEntry.violation(rule: String, detail: String) = CatalogViolation(id, rule, detail)
}
