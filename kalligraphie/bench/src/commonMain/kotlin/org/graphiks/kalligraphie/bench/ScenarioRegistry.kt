package org.graphiks.kalligraphie.bench

import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus
import org.graphiks.kalligraphie.bench.scenarios.containerScenarios
import org.graphiks.kalligraphie.bench.scenarios.glyphMaterializationScenarios
import org.graphiks.kalligraphie.bench.scenarios.paragraphScenarios
import org.graphiks.kalligraphie.conformance.PortableCapabilityIdentity

/** One profile this platform does not run, and what keeps it out. */
public class DeferredScenario(
    /** The profile name the report would have published. */
    public val name: String,
    /** Why this platform cannot run it. */
    public val reason: String,
)

/**
 * Chooses the scenarios a platform must run, from the platform's own capability declaration and the
 * instruments its harness provides.
 *
 * The selection is derived, never hand-written: a platform that stops serving a capability, or never
 * had an instrument, cannot look complete by silently publishing fewer profiles, because
 * [deferred] and [deferredInstruments] name what it left out and why. The paragraph profiles are
 * common code since the text facades and the portable Unicode analysis became `commonMain`, so every
 * platform that declares `END_TO_END_LAYOUT` serves them.
 *
 * [platformScenarios] carries the scenarios that live beside a harness instrument instead of in
 * common code — today the Java family's worker-pool profile. A platform whose harness has no such
 * instrument has no such source set, passes nothing, and states the deferral through
 * [deferredInstruments] instead.
 */
public object ScenarioRegistry {
    /** Every scenario the module knows, in canonical report order. */
    public fun all(
        corpus: FixtureCorpus,
        platformScenarios: List<MeasurementScenario> = emptyList(),
    ): List<MeasurementScenario> =
        glyphMaterializationScenarios(corpus) + containerScenarios(corpus) + paragraphScenarios(corpus) + platformScenarios

    /** The scenarios this platform serves, in [all] order. */
    public fun select(
        corpus: FixtureCorpus,
        identity: PortableCapabilityIdentity,
        instruments: Set<MeasurementInstrument> = measurementInstruments,
        platformScenarios: List<MeasurementScenario> = emptyList(),
    ): List<MeasurementScenario> =
        all(corpus, platformScenarios).filter { scenario -> isServed(scenario, identity, instruments) }

    /** The profiles this platform does not run, each with the capability or instrument keeping it out. */
    public fun deferred(
        corpus: FixtureCorpus,
        identity: PortableCapabilityIdentity,
        instruments: Set<MeasurementInstrument> = measurementInstruments,
        platformScenarios: List<MeasurementScenario> = emptyList(),
    ): List<DeferredScenario> = all(corpus, platformScenarios).mapNotNull { scenario ->
        val capability = scenario.requiredCapability
        val instrument = scenario.requiredInstrument
        when {
            capability != null && !identity.presenceOf(capability) ->
                DeferredScenario(scenario.name, "$capability is not declared available on this platform")

            instrument != null && instrument !in instruments ->
                DeferredScenario(scenario.name, "the harness has no $instrument instrument: ${instrument.description}")

            else -> null
        }
    }

    /** The profiles an instrument supplies elsewhere and this harness cannot run, each with its reason. */
    public fun deferredInstruments(
        instruments: Set<MeasurementInstrument> = measurementInstruments,
    ): List<DeferredScenario> = MeasurementInstrument.entries
        .filterNot(instruments::contains)
        .map { instrument ->
            DeferredScenario(instrument.profileName, "the harness has no such instrument: ${instrument.description}")
        }

    private fun isServed(
        scenario: MeasurementScenario,
        identity: PortableCapabilityIdentity,
        instruments: Set<MeasurementInstrument>,
    ): Boolean {
        val capability = scenario.requiredCapability
        val instrument = scenario.requiredInstrument
        return (capability == null || identity.presenceOf(capability)) && (instrument == null || instrument in instruments)
    }
}
