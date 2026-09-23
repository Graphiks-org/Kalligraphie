// ExpectationCatalogRatchetTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class ExpectationCatalogRatchetTest {
    @Test
    fun everySupportedEntryHasARendererAndEveryRendererHasASupportedEntry() {
        val supported = ExpectationCatalog.entries
            .filter { entry -> entry.status is CatalogStatus.Supported }
            .map { entry -> entry.id }
            .toSet()
        assertEquals(supported, SceneRenderers.byId.keys, "supported entries and renderers must be the same set")
    }

    @Test
    fun everyRendererFontPathBelongsToItsEntryCorpusKey() {
        val mismatches = ExpectationCatalog.entries.mapNotNull { entry ->
            val renderer = SceneRenderers.byId[entry.id] ?: return@mapNotNull null
            CatalogSceneMaterializer.fontPathMismatch(entry, renderer)
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun everySupportedEntryMaterializesOrRefusesTyped() {
        for (entry in ExpectationCatalog.entries.filter { entry -> entry.status is CatalogStatus.Supported }) {
            val materialized = CatalogSceneMaterializer.materialize(entry, SceneRenderers.byId.getValue(entry.id))
            val outcome = materialized.render()
            if (outcome is GoldenRenderOutcome.Refused) {
                assertTrue(
                    outcome.code.code.startsWith("e2e."),
                    "${entry.id} refused with a non-e2e code: ${outcome.code.code}",
                )
            }
        }
    }

    @Test
    fun everyPinnedFrameIsExemptedWithItsExactDimensions() {
        val exemptions = readExemptions()
        val pinned = ExpectationCatalog.entries.mapNotNull { entry ->
            val frame = entry.frame as? SceneFramePolicy.Pinned ?: return@mapNotNull null
            entry.id to "${frame.width}x${frame.height}"
        }.toMap()
        assertEquals(
            pinned,
            exemptions.mapValues { (_, record) -> record.frame },
            "pinned frames must be exempted with their exact dimensions, and exemptions must not be stale",
        )
    }

    @Test
    fun noNewEntryMayPinItsFrame() {
        val exemptions = readExemptions()
        assertTrue(
            exemptions.values.all { record -> record.reason.startsWith("migrated verbatim") },
            "a new pinned frame is an escape hatch: every exemption must be a migration record",
        )
    }

    private class Exemption(val frame: String, val reason: String)

    private fun readExemptions(): Map<String, Exemption> {
        val text = checkNotNull(object {}.javaClass.getResourceAsStream("/catalog/auto-sizing-exemptions.tsv")) {
            "the auto-sizing exemptions resource is missing"
        }.use { input -> input.readBytes().decodeToString() }
        val lines = text.split('\n').map { line -> line.removeSuffix("\r") }.filter { line -> line.isNotBlank() }
        assertEquals("# kalligraphie.e2e-exemptions/v1", lines.first(), "unrecognised exemptions header")
        return lines.drop(1).associate { line ->
            val fields = line.split('\t')
            assertEquals(3, fields.size, "expected id, frame and reason: $line")
            fields[0] to Exemption(frame = fields[1], reason = fields[2])
        }
    }
}
