package org.graphiks.kalligraphie.e2e

/**
 * Declarative identity of one golden scene.
 *
 * [width] and [height] are the declared canvas dimensions for composed families;
 * for tight-output families (the glyph routes) they document the expected output
 * size and are asserted by the scene's own renderer test.
 */
public data class GoldenScene(
    /** Stable identifier; also the manifest key. */
    public val id: String,
    /** Route the scene exercises. */
    public val family: GoldenSceneFamily,
    /** Declared canvas width, or expected output width for tight-output families. */
    public val width: Int,
    /** Declared canvas height, or expected output height for tight-output families. */
    public val height: Int,
    /** Free-form labels for filtering and reporting. */
    public val tags: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank()) { "A golden scene id must not be blank." }
        require(width >= 0) { "A golden scene width must be non-negative." }
        require(height >= 0) { "A golden scene height must be non-negative." }
    }
}
