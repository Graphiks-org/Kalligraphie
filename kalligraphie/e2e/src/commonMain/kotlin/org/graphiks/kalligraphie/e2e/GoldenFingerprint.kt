package org.graphiks.kalligraphie.e2e

/**
 * One manifest record: the identity and content digest of a scene's canonical image.
 */
public data class GoldenFingerprint(
    /** Scene identifier this record describes. */
    public val sceneId: String,
    /** Family of the scene. */
    public val family: GoldenSceneFamily,
    /** Recorded image width in pixels. */
    public val width: Int,
    /** Recorded image height in pixels. */
    public val height: Int,
    /** Recorded pixel layout. */
    public val format: PixelFormat,
    /** Lowercase hexadecimal SHA-256 of the canonical bytes. */
    public val sha256: String,
) {
    init {
        require(sceneId.isNotBlank()) { "A golden fingerprint scene id must not be blank." }
        require(width >= 0) { "A golden fingerprint width must be non-negative." }
        require(height >= 0) { "A golden fingerprint height must be non-negative." }
        require(sha256.length == 64 && sha256.all { char -> char in "0123456789abcdef" }) {
            "A golden fingerprint digest must be a 64-character lowercase hexadecimal string."
        }
    }

    public companion object {
        /** Derives the fingerprint of [image] under the identity of [scene]. */
        public fun of(scene: GoldenScene, image: GoldenImage): GoldenFingerprint = GoldenFingerprint(
            sceneId = scene.id,
            family = scene.family,
            width = image.width,
            height = image.height,
            format = image.format,
            sha256 = sha256Hex(image.copyCanonicalBytes()),
        )
    }
}
