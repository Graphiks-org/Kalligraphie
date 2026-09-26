package org.graphiks.kalligraphie.bench

import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus
import org.graphiks.kalligraphie.bench.scenarios.glyphMaterializationScenarios
import org.graphiks.kalligraphie.conformance.PortableCapabilityIdentity

/**
 * The paragraph scenarios a target serves, declared per platform.
 *
 * The bodies need `END_TO_END_LAYOUT` (Unicode analysis and the editable-line sessions), so the JVM
 * actual provides them and the Android and iOS actuals provide none while `conformance` declares the
 * capability absent there. When a platform flips the capability, its actual starts returning
 * scenarios and the registry serves them with no other change — the same derived rule the e2e
 * harness follows.
 */
public expect fun paragraphScenarios(corpus: FixtureCorpus): List<MeasurementScenario>

/**
 * Chooses the scenarios a platform must run, from the platform's own capability declaration.
 *
 * The selection is derived, never hand-written: a platform that stops serving a capability cannot
 * look complete by silently publishing fewer profiles, because [deferred] names what it left out.
 */
public object ScenarioRegistry {
    /** Every scenario the module knows, in canonical report order. */
    public fun all(corpus: FixtureCorpus): List<MeasurementScenario> =
        glyphMaterializationScenarios(corpus) + paragraphScenarios(corpus)

    /** The scenarios [identity] serves: those whose capability is declared present, in [all] order. */
    public fun select(corpus: FixtureCorpus, identity: PortableCapabilityIdentity): List<MeasurementScenario> =
        all(corpus).filter { scenario -> scenario.requiredCapability?.let(identity::presenceOf) ?: true }

    /** Scenarios the module knows but [identity] does not serve, with the capability keeping them out. */
    public fun deferred(corpus: FixtureCorpus, identity: PortableCapabilityIdentity): List<MeasurementScenario> =
        all(corpus).filter { scenario ->
            val capability = scenario.requiredCapability ?: return@filter false
            !identity.presenceOf(capability)
        }
}
