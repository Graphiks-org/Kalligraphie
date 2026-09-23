// CatalogMatrixRunnerTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.graphiks.kalligraphie.e2e.golden.repositoryRoot

class CatalogMatrixRunnerTest {
    @Test
    fun writesTheMatrixOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_MATRIX") != "true") {
            return
        }
        for (language in CatalogMatrixLanguage.entries) {
            val target = matrixPath(language)
            Files.createDirectories(target.parent)
            Files.writeString(target, CatalogMatrixRenderer.render(ExpectationCatalog.entries, language))
        }
    }

    @Test
    fun theCommittedMatrixMatchesTheCatalog() {
        for (language in CatalogMatrixLanguage.entries) {
            val path = matrixPath(language)
            check(Files.exists(path)) {
                "$path is missing; run ./gradlew :kalligraphie:e2e:updateE2eGolden"
            }
            assertEquals(
                CatalogMatrixRenderer.render(ExpectationCatalog.entries, language),
                Files.readString(path),
                "$path is stale; run ./gradlew :kalligraphie:e2e:updateE2eGolden",
            )
        }
    }

    private fun matrixPath(language: CatalogMatrixLanguage): Path =
        repositoryRoot().resolve("docs/docs/generated/${CatalogMatrixRenderer.fileName(language)}")
}
