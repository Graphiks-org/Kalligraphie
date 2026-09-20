package org.graphiks.kalligraphie.e2e

/**
 * Declarative identity of one golden scene.
 *
 * [width] and [height] are the dimensions of the canonical image the scene is
 * expected to produce, for every family. The verifier asserts them against the
 * rendered image, so a scene frame is a checked contract, not documentation.
 */
public data class GoldenScene(
    /** Stable identifier; also the manifest key. */
    public val id: String,
    /** Route the scene exercises. */
    public val family: GoldenSceneFamily,
    /** Expected canonical image width in pixels; must be positive. */
    public val width: Int,
    /** Expected canonical image height in pixels; must be positive. */
    public val height: Int,
    /** Free-form labels for filtering and reporting. */
    public val tags: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "A golden scene id must not be blank." }
        require(width > 0) { "A golden scene width must be positive." }
        require(height > 0) { "A golden scene height must be positive." }
    }
}
