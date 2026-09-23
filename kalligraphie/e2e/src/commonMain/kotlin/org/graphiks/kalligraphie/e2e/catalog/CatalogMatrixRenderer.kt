// CatalogMatrixRenderer.kt
package org.graphiks.kalligraphie.e2e.catalog

/** Language of the generated catalog matrix. */
public enum class CatalogMatrixLanguage {
    /** English. */
    EN,
    /** French. */
    FR,
}

/**
 * Renders the catalog as Markdown: a header, a per-axis count table, then one table per axis.
 *
 * The matrix is the only place where the catalog's numbers appear. Narrative pages point at it
 * instead of restating counts, so a status flip cannot leave the documentation lying.
 */
public object CatalogMatrixRenderer {
    /** Returns the Markdown document for [entries] in [language]. */
    public fun render(entries: List<CatalogEntry>, language: CatalogMatrixLanguage): String = buildString {
        appendLine(if (language == CatalogMatrixLanguage.EN) "# End-to-end expectation catalog" else "# Catalogue d'attentes end-to-end")
        appendLine()
        appendLine(
            if (language == CatalogMatrixLanguage.EN) {
                "Generated from `ExpectationCatalog` by `./gradlew :kalligraphie:e2e:updateE2eGolden`; do not edit by hand."
            } else {
                "Généré depuis `ExpectationCatalog` par `./gradlew :kalligraphie:e2e:updateE2eGolden` ; ne pas modifier à la main."
            },
        )
        appendLine()
        appendLine(if (language == CatalogMatrixLanguage.EN) "## Totals" else "## Totaux")
        appendLine()
        appendLine(
            if (language == CatalogMatrixLanguage.EN) "| Status | Entries |" else "| Statut | Entrées |",
        )
        appendLine("| --- | --- |")
        for (status in STATUS_LABELS.keys) {
            val count = entries.count { entry -> entry.status::class == status }
            appendLine("| ${STATUS_LABELS.getValue(status).getValue(language)} | $count |")
        }
        appendLine()
        for (axis in CatalogAxis.entries) {
            val axisEntries = entries.filter { entry -> entry.axis == axis }
            if (axisEntries.isEmpty()) continue
            appendLine("## ${axis}")
            appendLine()
            appendLine(
                if (language == CatalogMatrixLanguage.EN) {
                    "| Entry | Technology | Font | Status |"
                } else {
                    "| Entrée | Technologie | Police | Statut |"
                },
            )
            appendLine("| --- | --- | --- | --- |")
            for (entry in axisEntries) {
                appendLine(
                    "| `${entry.id}` | ${entry.technology.text(language)} | ${entry.font?.value ?: "—"} | " +
                        "${describe(entry.status, language)} |",
                )
            }
            appendLine()
        }
    }

    /** Returns the committed file name of the matrix in [language]. */
    public fun fileName(language: CatalogMatrixLanguage): String = when (language) {
        CatalogMatrixLanguage.EN -> "e2e-catalog-matrix.md"
        CatalogMatrixLanguage.FR -> "e2e-catalog-matrix.fr.md"
    }

    private fun describe(status: CatalogStatus, language: CatalogMatrixLanguage): String = when (status) {
        is CatalogStatus.Supported -> if (language == CatalogMatrixLanguage.EN) {
            "Supported since ${status.sinceCommit}"
        } else {
            "Supporté depuis ${status.sinceCommit}"
        }

        is CatalogStatus.ExpectedRejection -> if (language == CatalogMatrixLanguage.EN) {
            "Expected rejection `${status.code}` at ${status.stage}"
        } else {
            "Rejet attendu `${status.code}` à ${status.stage}"
        }

        is CatalogStatus.NotYet -> when {
            status.currentBehavior != null -> if (language == CatalogMatrixLanguage.EN) {
                "Not yet; today pinned (`${status.trackingIssue}`)"
            } else {
                "Pas encore ; comportement actuel épinglé (`${status.trackingIssue}`)"
            }

            status.unpinnedReason == UnpinnedReason.NO_REAL_FONT_KNOWN -> if (language == CatalogMatrixLanguage.EN) {
                "Not yet; no real font known"
            } else {
                "Pas encore ; aucune police réelle connue"
            }

            status.unpinnedReason == UnpinnedReason.CORPUS_NOT_ACQUIRED -> if (language == CatalogMatrixLanguage.EN) {
                "Not yet; corpus not acquired"
            } else {
                "Pas encore ; corpus non acquis"
            }

            status.unpinnedReason == UnpinnedReason.READER_NOT_IMPLEMENTED -> if (language == CatalogMatrixLanguage.EN) {
                "Not yet; the corpus carries it, the reader does not"
            } else {
                "Pas encore ; le corpus le porte, le lecteur non"
            }

            status.unpinnedReason == UnpinnedReason.NO_API_SURFACE -> if (language == CatalogMatrixLanguage.EN) {
                "Not yet; no public entry point carries the request"
            } else {
                "Pas encore ; aucune entrée publique ne porte la demande"
            }

            else -> error("${status.trackingIssue} declares no reason and pins no behaviour")
        }

        is CatalogStatus.OutOfScope -> if (language == CatalogMatrixLanguage.EN) {
            "Out of scope: ${status.rationale.english}"
        } else {
            "Hors périmètre : ${status.rationale.french}"
        }
    }

    private val STATUS_LABELS: Map<kotlin.reflect.KClass<out CatalogStatus>, Map<CatalogMatrixLanguage, String>> = mapOf(
        CatalogStatus.Supported::class to mapOf(
            CatalogMatrixLanguage.EN to "Supported",
            CatalogMatrixLanguage.FR to "Supporté",
        ),
        CatalogStatus.ExpectedRejection::class to mapOf(
            CatalogMatrixLanguage.EN to "Expected rejection",
            CatalogMatrixLanguage.FR to "Rejet attendu",
        ),
        CatalogStatus.NotYet::class to mapOf(
            CatalogMatrixLanguage.EN to "Not yet",
            CatalogMatrixLanguage.FR to "Pas encore",
        ),
        CatalogStatus.OutOfScope::class to mapOf(
            CatalogMatrixLanguage.EN to "Out of scope",
            CatalogMatrixLanguage.FR to "Hors périmètre",
        ),
    )
}
