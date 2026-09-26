package org.graphiks.kalligraphie.bench

import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

/**
 * Android actual: `conformance` declares `END_TO_END_LAYOUT` absent on Android, so no paragraph
 * scenario is served. When the capability lands, this actual returns the paragraph scenarios.
 */
public actual fun paragraphScenarios(corpus: FixtureCorpus): List<MeasurementScenario> = emptyList()
