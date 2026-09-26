package org.graphiks.kalligraphie.bench.scenarios

import org.graphiks.kalligraphie.bench.MeasurementScenario
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

/**
 * The portable glyph-materialization scenarios, shared by every target. Filled in by the port of
 * `GlyphMaterializationBenchmark`; the signature is what the registry needs.
 */
public fun glyphMaterializationScenarios(corpus: FixtureCorpus): List<MeasurementScenario> = emptyList()
