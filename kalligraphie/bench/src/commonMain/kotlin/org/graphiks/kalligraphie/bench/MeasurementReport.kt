package org.graphiks.kalligraphie.bench

/** The publication contract: one reproducible report per platform family. */
public data class MeasurementReport(
    /** Where the measurement ran. */
    public val identity: MeasurementIdentity,
    /** Corpus identifier. */
    public val corpusId: String,
    /** What the corpus contains. */
    public val corpusDescription: String,
    /** Profiles in canonical order. */
    public val profiles: List<MeasurementProfile>,
) {
    init {
        require(corpusId.isNotBlank() && corpusDescription.isNotBlank()) {
            "A report needs an identified corpus."
        }
        require(profiles.isNotEmpty()) { "A report needs at least one profile." }
        val names = profiles.map(MeasurementProfile::name)
        require(names.toSet().size == names.size) { "Profile names must be unique within a report." }
    }

    /** Renders the report as the Markdown a human reads and a reviewer archives. */
    public fun toMarkdown(): String = buildString {
        appendLine("# ${identity.platformId} measurement")
        appendLine()
        appendLine("- Commit: `${identity.commit}`")
        appendLine("- Platform: ${identity.platformId}")
        appendLine("- Machine: ${identity.machine}")
        appendLine("- OS: ${identity.operatingSystem}")
        appendLine("- Runtime: ${identity.runtime}")
        appendLine("- Cache policy: ${identity.gcPolicy}")
        appendLine("- Corpus: `${corpusId}` — $corpusDescription")
        appendLine("- Corpus SHA-256:")
        identity.fontHashes.entries.sortedBy { it.key }.forEach { (name, hash) ->
            appendLine("  - `$name`: `$hash`")
        }
        profiles.forEach { profile ->
            appendLine()
            appendLine("## ${profile.name}")
            appendLine()
            appendLine("- Route: ${profile.route}")
            appendLine("- Timed boundary: ${profile.timedBoundary}")
            appendLine("- Cache state: ${profile.cacheState}")
            appendLine("- Warmup iterations: ${profile.warmupIterations}")
            appendLine("- Measured iterations: ${profile.iterations}")
            appendLine("- Latency p50: ${profile.latency.p50Nanos} ns")
            appendLine("- Latency p95: ${profile.latency.p95Nanos} ns")
            appendLine("- Latency p99: ${profile.latency.p99Nanos} ns")
            profile.p95ObjectiveNanos?.let { target ->
                val verdict = if (profile.latency.p95Nanos <= target) "within" else "above"
                appendLine("- Observational objective: p95 <= $target ns — $verdict (not a CI gate)")
            }
            profile.consumed.entries.sortedBy { it.key }.forEach { (name, count) ->
                appendLine("- Consumed `$name`: $count")
            }
            profile.figures.entries.sortedBy { it.key }.forEach { (label, figure) ->
                appendLine("- $label: ${figure.state.name.lowercase()} ${figure.value ?: "—"} (${figure.detail})")
            }
        }
    }
}
