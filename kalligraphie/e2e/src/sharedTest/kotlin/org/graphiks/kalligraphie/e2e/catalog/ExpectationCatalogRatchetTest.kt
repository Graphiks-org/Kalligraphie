// ExpectationCatalogRatchetTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.conformance.PortableCapability
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment
import org.graphiks.kalligraphie.e2e.GoldenRenderOutcome

class ExpectationCatalogRatchetTest {
    @Test
    fun theRegistryIsExactlyWhatTheDeclaredCapabilitiesImply() {
        val implied = supportedEntriesFor(E2eTestEnvironment.capabilities)
        val registered = E2eTestEnvironment.renderers.keys
        val missing = implied - registered
        val extra = registered - implied
        assertTrue(missing.isEmpty(), "entries this platform must verify but does not register: $missing")
        assertTrue(extra.isEmpty(), "renderers registered on a platform whose declaration cannot serve them: $extra")
    }

    @Test
    fun aPlatformWithoutEndToEndLayoutIsNotRequiredToVerifyTheParagraphScenes() {
        // The mechanism the ratchet above rests on, pinned without a second platform: dropping
        // END_TO_END_LAYOUT excuses exactly the scenes that compose text, and nothing else. If the
        // capability ever lands here, the registry must grow the same entries or the ratchet fails.
        val glyphOnly = supportedEntriesFor(setOf(PortableCapability.GLYPH_REPRESENTATION_VARIANTS))
        val everyCapability = PortableCapability.entries.toSet()
        assertTrue("outline.glyf-simple-composite" in glyphOnly, "the portable outline scene stays required")
        assertTrue("script.latin.outline-sheet" in glyphOnly, "a portable sheet stays required")
        assertEquals(
            setOf(
                "script.latin.composed-line",
                "script.greek.composed-line",
                "script.cyrillic.composed-line",
                "script.arabic.composed-line",
                "script.devanagari.composed-line",
                "script.mixed.composed-line",
                "variation.wght-ladder",
                "composition.every-route-mosaic",
            ),
            supportedEntriesFor(everyCapability) - glyphOnly,
            "the paragraph route must excuse exactly the scenes that compose text",
        )
    }

    @Test
    fun everyRendererFontPathBelongsToItsEntryCorpusKey() {
        val mismatches = ExpectationCatalog.entries.mapNotNull { entry ->
            val renderer = E2eTestEnvironment.renderers[entry.id] ?: return@mapNotNull null
            CatalogSceneMaterializer.fontPathMismatch(entry, renderer)
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun everyRendererRouteMatchesItsEntryRoute() {
        val mismatches = ExpectationCatalog.entries.mapNotNull { entry ->
            val renderer = E2eTestEnvironment.renderers[entry.id] ?: return@mapNotNull null
            CatalogSceneMaterializer.routeMismatch(entry, renderer)
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun everyRendererManifestKeyMatchesItsEntryKey() {
        val mismatches = ExpectationCatalog.entries.mapNotNull { entry ->
            val renderer = E2eTestEnvironment.renderers[entry.id] ?: return@mapNotNull null
            CatalogSceneMaterializer.sceneIdMismatch(entry, renderer)
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    @Test
    fun everySupportedEntryMaterializesOrRefusesTyped() {
        for (entry in ExpectationCatalog.entries.filter { candidate -> candidate.id in E2eTestEnvironment.renderers }) {
            val materialized = CatalogSceneMaterializer.materialize(
                entry,
                E2eTestEnvironment.renderers.getValue(entry.id),
                E2eTestEnvironment.corpus,
            )
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

    @Test
    fun everyProbeableEntryHasAProbeAndEveryProbeHasAProbeableEntry() {
        val probeable = ExpectationCatalog.entries
            .filter { entry ->
                entry.status is CatalogStatus.ExpectedRejection ||
                    (entry.status is CatalogStatus.NotYet && entry.status.currentBehavior != null)
            }
            .map { entry -> entry.id }
            .toSet()
        assertEquals(probeable, CatalogProbes.byId.keys, "probeable entries and probes must be the same set")
    }

    @Test
    fun everyProbeAgreesWithItsDeclaredStatus() {
        for (entry in ExpectationCatalog.entries) {
            val probe = CatalogProbes.byId[entry.id] ?: continue
            val observed = probe.observe(E2eTestEnvironment.corpus)
            when (val status = entry.status) {
                is CatalogStatus.ExpectedRejection -> {
                    val rejected = assertIs<ProbeObservation.Rejected>(observed, "${entry.id} must be rejected")
                    assertEquals(status.stage, rejected.stage, "${entry.id} stage changed")
                    assertEquals(status.code, rejected.diagnostic, "${entry.id} diagnostic changed")
                }

                is CatalogStatus.NotYet -> when (val pinned = status.currentBehavior) {
                    is PinnedBehavior.RejectedAt -> {
                        val rejected = assertIs<ProbeObservation.Rejected>(observed, "${entry.id} must be rejected")
                        assertEquals(pinned.stage, rejected.stage, "${entry.id} stage changed")
                        assertEquals(pinned.diagnostic, rejected.diagnostic, "${entry.id} diagnostic changed")
                    }

                    is PinnedBehavior.SucceededWith -> {
                        val succeeded = assertIs<ProbeObservation.Succeeded>(observed, "${entry.id} must succeed")
                        assertEquals(pinned.stage, succeeded.stage, "${entry.id} stage changed")
                        assertEquals(pinned.observation, succeeded.observation, "${entry.id} observation changed")
                    }

                    null -> error("${entry.id} is probeable but declares no pinned behaviour")
                }

                else -> error("${entry.id} has a probe but is not probeable")
            }
        }
    }

    @Test
    fun everyProbeFontPathBelongsToItsEntryCorpusKey() {
        val mismatches = ExpectationCatalog.entries.mapNotNull { entry ->
            val probe = CatalogProbes.byId[entry.id] ?: return@mapNotNull null
            CatalogProbes.fontPathMismatch(entry, probe)
        }
        assertTrue(mismatches.isEmpty(), mismatches.joinToString("\n"))
    }

    private class Exemption(val frame: String, val reason: String)

    private fun readExemptions(): Map<String, Exemption> {
        val text = E2eTestEnvironment.corpus.text("/catalog/auto-sizing-exemptions.tsv")
        val lines = text.split('\n').map { line -> line.removeSuffix("\r") }.filter { line -> line.isNotBlank() }
        assertEquals("# kalligraphie.e2e-exemptions/v1", lines.first(), "unrecognised exemptions header")
        return lines.drop(1).associate { line ->
            val fields = line.split('\t')
            assertEquals(3, fields.size, "expected id, frame and reason: $line")
            fields[0] to Exemption(frame = fields[1], reason = fields[2])
        }
    }
}
