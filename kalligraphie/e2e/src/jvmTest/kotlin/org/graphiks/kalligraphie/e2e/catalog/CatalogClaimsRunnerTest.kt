// CatalogClaimsRunnerTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.golden.repositoryRoot

class CatalogClaimsRunnerTest {
    @Test
    fun writesTheClaimsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_E2E_CLAIMS") != "true") {
            return
        }
        val target = claimsPath()
        Files.createDirectories(target.parent)
        Files.writeString(target, CatalogClaims.render(ExpectationCatalog.entries))
    }

    @Test
    fun theCommittedClaimsMatchTheCatalog() {
        val path = claimsPath()
        check(Files.exists(path)) { "$path is missing; run ./gradlew :kalligraphie:e2e:updateE2eGolden" }
        assertEquals(
            CatalogClaims.render(ExpectationCatalog.entries),
            Files.readString(path),
            "$path is stale; run ./gradlew :kalligraphie:e2e:updateE2eGolden",
        )
    }

    @Test
    fun everyAllowlistedTableCarriesAReason() {
        for ((key, tables) in CatalogClaims.unclaimedAllowlist) {
            assertTrue(key.isNotBlank())
            for ((table, reason) in tables) {
                assertTrue(reason.isNotBlank(), "$key/$table is allowlisted without a reason")
            }
        }
    }

    @Test
    fun everyClaimedTableIsDeclaredByAnEntryThatNamesItsFont() {
        val claims = CatalogClaims.claimsOf(ExpectationCatalog.entries)
        for ((key, tables) in claims) {
            assertTrue(key.isNotBlank())
            assertTrue(tables.isNotEmpty(), "$key is claimed by entries that declare no table")
        }
    }

    /**
     * Task 12 writes its allowlist reasons by hand, so a quotation mark or a backslash in one of
     * them must not be able to produce a document `json.loads` refuses. The freshness test cannot
     * see that: it compares [CatalogClaims.render] with itself.
     */
    @Test
    fun aHostileAllowlistReasonStillRendersJsonThatParsesBackVerbatim() {
        val reason = "a \"quoted\" reason with a backslash \\ and a tab\tand a control \u0001"
        val rendered = CatalogClaims.render(
            ExpectationCatalog.entries,
            mapOf("weird-family" to mapOf("COLR" to reason)),
        )
        val script = """
            import json, sys
            document = json.load(sys.stdin)
            sys.stdout.write(document["allowUnclaimed"]["weird-family"]["COLR"])
        """.trimIndent()
        val process = ProcessBuilder(pythonInterpreter(), "-c", script).redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { writer -> writer.write(rendered) }
        val parsed = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "the Python proof refused the rendered claims:\n$parsed")
        assertEquals(reason, parsed)
    }

    /**
     * Resolves the Python 3 interpreter the JSON round-trip runs on.
     *
     * A Windows runner installs Python as `python`, not `python3`, so the name is discovered rather
     * than assumed. The proof this test carries is only worth having if it really runs: an
     * interpreter that cannot be found fails the test, and never skips the round-trip.
     */
    private fun pythonInterpreter(): String =
        listOf("python3", "python", "py").firstOrNull(::canRunPython)
            ?: error("Python 3 is required to prove the claims export parses as JSON.")

    private fun canRunPython(candidate: String): Boolean = try {
        ProcessBuilder(candidate, "-c", "import json, sys; sys.exit(0)")
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    } catch (missing: IOException) {
        false
    }

    private fun claimsPath(): Path = repositoryRoot().resolve("kalligraphie/e2e/src/harnessResources/catalog/claimed-tables.json")
}
