package org.graphiks.kalligraphie.bench

import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus
import org.graphiks.kalligraphie.bench.scenarios.paragraphScenariosJvm

/**
 * JVM actual: the incremental-layout, editable-line, consumer, session, handoff and concurrent
 * scenarios — all of which need `END_TO_END_LAYOUT`, which the JVM platform declares present.
 */
public actual fun paragraphScenarios(corpus: FixtureCorpus): List<MeasurementScenario> =
    paragraphScenariosJvm(corpus)
