package org.graphiks.kalligraphie.e2e

/** Stable diagnostic codes reported by the e2e harness. */
public enum class GoldenDiagnosticCode(
    /** Wire code, prefixed `e2e.`. */
    public val code: String,
) {
    /** The scene failed to render. */
    RENDER_FAILED("e2e.render-failed"),

    /** The rendered digest differs from the recorded digest. */
    MISMATCH("e2e.mismatch"),

    /** A scene's declared frame and its rendered bounds disagree. */
    SCENE_BOUNDS_INVALID("e2e.scene-bounds-invalid"),

    /** An auto-sized scene measured no ink at all. */
    BLANK_SCENE("e2e.blank-scene"),

    /** A catalogued scene has no manifest entry. */
    MANIFEST_MISSING_ENTRY("e2e.manifest-missing-entry"),

    /** A manifest entry has no catalogued scene. */
    MANIFEST_STALE_ENTRY("e2e.manifest-stale-entry"),

    /** A scene id appears more than once. */
    MANIFEST_DUPLICATE_ID("e2e.manifest-duplicate-id"),

    /** The manifest was written under a different canonicalization version. */
    CANONICALIZATION_VERSION_MISMATCH("e2e.canonicalization-version-mismatch"),

    /** The manifest text is not well formed. */
    MANIFEST_MALFORMED("e2e.manifest-malformed"),
}
