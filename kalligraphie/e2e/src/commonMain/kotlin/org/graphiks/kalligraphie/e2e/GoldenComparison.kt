package org.graphiks.kalligraphie.e2e

/** Verdict of comparing one scene's rendered image against the manifest. */
public sealed interface GoldenComparison {
    /** Scene id common to every verdict. */
    public val sceneId: String

    /** The rendered digest equals the recorded digest. */
    public data class Matched(
        override val sceneId: String,
    ) : GoldenComparison

    /** The rendered digest differs from the recorded digest. */
    public data class Mismatch(
        override val sceneId: String,
        /** Digest recorded in the manifest. */
        public val expectedSha256: String,
        /** Digest of the rendered image. */
        public val actualSha256: String,
        /** Expected width: the scene frame declared by the catalog. */
        public val expectedWidth: Int,
        /** Expected height: the scene frame declared by the catalog. */
        public val expectedHeight: Int,
        /** Rendered width. */
        public val actualWidth: Int,
        /** Rendered height. */
        public val actualHeight: Int,
    ) : GoldenComparison

    /** A catalogued scene has no manifest entry. */
    public data class MissingInManifest(
        override val sceneId: String,
    ) : GoldenComparison

    /** A manifest entry has no catalogued scene. */
    public data class StaleManifestEntry(
        override val sceneId: String,
    ) : GoldenComparison
}

/** Outcome of rendering one scene. */
public sealed interface GoldenRenderOutcome {
    /** The scene rendered one canonical image. */
    public data class Rendered(
        /** Canonical image produced by the renderer. */
        public val image: GoldenImage,
    ) : GoldenRenderOutcome

    /** The scene refused to render. */
    public data class Refused(
        /** Stable diagnostic code. */
        public val code: GoldenDiagnosticCode,
        /** Human-readable reason without sensitive data. */
        public val detail: String,
    ) : GoldenRenderOutcome
}
