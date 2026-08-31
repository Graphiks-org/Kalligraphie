package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncrementalLayoutBenchmarkTest {
    @Test
    fun measurementReportNamesEveryRequiredReproducibilityField() {
        val report = IncrementalLayoutBenchmark.reportFor(
            environment = MeasurementEnvironment(
                commit = "0123456789abcdef",
                machine = "fixed-machine",
                operatingSystem = "FixedOS 1.0",
                jvm = "FixedVM 21",
                unicodeVersion = "16.0",
                harfBuzzVersion = "14.3.0",
                fontHashes = mapOf("Fixture.ttf" to "abc123"),
                gcPolicy = "between profiles",
            ),
            corpus = MeasurementCorpus(
                id = "fixed-corpus",
                description = "fixed multilingual paragraph",
                scalarCount = 24,
                paragraphCount = 2,
            ),
            profiles = listOf(
                fixedProfile("InteractiveEdit"),
                fixedProfile("ViewportLayout"),
                fixedProfile("Cancellation"),
            ),
        )

        assertEquals(
            listOf("InteractiveEdit", "ViewportLayout", "Cancellation"),
            report.profiles.map(MeasurementProfileReport::name),
        )
        assertTrue(report.metadata.commit.isNotBlank())
        assertTrue(report.metadata.machine.isNotBlank())
        assertTrue(report.metadata.operatingSystem.isNotBlank())
        assertTrue(report.metadata.jvm.isNotBlank())
        assertTrue(report.metadata.fontHashes.isNotEmpty())
        assertTrue(report.metadata.unicodeVersion.isNotBlank())
        assertTrue(report.metadata.harfBuzzVersion.isNotBlank())
        assertTrue(report.metadata.gcPolicy.isNotBlank())
        assertTrue(report.corpus.id.isNotBlank())
        assertTrue(report.corpus.description.isNotBlank())
        assertTrue(report.corpus.scalarCount > 0)
        assertTrue(report.corpus.paragraphCount > 0)
        assertTrue(report.profiles.all { profile ->
            profile.iterations > 0 &&
                profile.warmupIterations > 0 &&
                profile.coverage.isNotBlank() &&
                profile.cacheState.isNotBlank() &&
                profile.latency.p50Nanos > 0 &&
                profile.latency.p95Nanos > 0 &&
                profile.latency.p99Nanos > 0 &&
                profile.allocations.state.isNotBlank() &&
                profile.retainedJvmMemory.state.isNotBlank() &&
                profile.retainedNativeMemory.state.isNotBlank() &&
                profile.cancellationDelay.state.isNotBlank() &&
                profile.rematerializedText.state.isNotBlank() &&
                profile.rematerializedLines.state.isNotBlank() &&
                profile.rematerializedParagraphs.state.isNotBlank()
        })
    }

    private fun fixedProfile(name: String): MeasurementProfileReport = MeasurementProfileReport(
        name = name,
        warmupIterations = 2,
        iterations = 5,
        coverage = "scalars 0..8; overscan 1 line",
        cacheState = "warm after profile-local warmup",
        latency = Percentiles(100, 200, 300),
        allocations = MeasurementValue.available(1_024, "bytes per iteration"),
        retainedJvmMemory = MeasurementValue.unavailable("not sampled in completeness fixture"),
        retainedNativeMemory = MeasurementValue.unavailable("not exposed by the backend"),
        cancellationDelay = MeasurementValue.unavailable("not applicable to this fixture"),
        rematerializedText = MeasurementValue.available(8, "scalars per iteration"),
        rematerializedLines = MeasurementValue.available(2, "lines per iteration"),
        rematerializedParagraphs = MeasurementValue.available(1, "paragraphs per iteration"),
    )
}
