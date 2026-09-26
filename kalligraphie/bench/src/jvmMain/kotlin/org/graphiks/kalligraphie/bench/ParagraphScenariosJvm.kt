package org.graphiks.kalligraphie.bench

import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

/**
 * JVM actual: the paragraph scenarios are provided once the portable contract is wired — filled in
 * by the port of `IncrementalLayoutBenchmark`, `EditableLineMeasurement` and the materialization
 * consumer/session and handoff profiles, all of which need `END_TO_END_LAYOUT`.
 */
public actual fun paragraphScenarios(corpus: FixtureCorpus): List<MeasurementScenario> = emptyList()
