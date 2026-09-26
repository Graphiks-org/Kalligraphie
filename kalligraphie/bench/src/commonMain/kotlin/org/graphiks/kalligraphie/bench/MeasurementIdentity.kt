package org.graphiks.kalligraphie.bench

/**
 * Honest description of where a measurement ran.
 *
 * Every field states what this platform can actually observe: a runtime that reports no version
 * says so rather than inheriting another platform's.
 */
public data class MeasurementIdentity(
    /** Commit the measurement ran on. */
    public val commit: String,
    /** Host machine, as this platform names it. */
    public val machine: String,
    /** Operating system with version and architecture. */
    public val operatingSystem: String,
    /** Runtime: JVM name and version, ART, or Kotlin/Native. */
    public val runtime: String,
    /** Platform family, one of `jvm`, `android`, `ios`. */
    public val platformId: String,
    /** SHA-256 of every corpus file actually read, keyed by resource path. */
    public val fontHashes: Map<String, String>,
    /** Cache and garbage-collection policy applied between profiles. */
    public val gcPolicy: String,
) {
    init {
        require(commit.isNotBlank()) { "A measured commit is required." }
        require(machine.isNotBlank() && operatingSystem.isNotBlank() && runtime.isNotBlank()) {
            "A measurement identity needs a machine, an operating system and a runtime."
        }
        require(platformId.isNotBlank()) { "A measurement identity needs a platform identifier." }
        require(fontHashes.isNotEmpty()) { "At least one corpus hash is required." }
        require(gcPolicy.isNotBlank()) { "A measurement identity states its cache and GC policy." }
    }
}
