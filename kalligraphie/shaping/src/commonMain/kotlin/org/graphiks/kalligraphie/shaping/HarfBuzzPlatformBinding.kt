package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ShapingDirection
import kotlin.math.roundToInt

/**
 * Portable, platform-neutral surface of the native HarfBuzz operations used by [HarfBuzzBindings].
 *
 * `commonMain` must never reference a native binding type directly: the kffi HarfBuzz surface stays
 * behind an `expect`/`actual` boundary so the adapter compiles for every target of this module.
 * Each platform supplies exactly one binding; the JVM and Android source sets own real bindings
 * (`jvmMain`, `androidMain`), while iOS still reports the typed graceful-degradation failure
 * (`font.shaping-native-platform-unsupported`) because the kffi HarfBuzz binding publishes no
 * Kotlin/Native target yet.
 *
 * No native handle, library type or platform dependency crosses this interface. Implementations
 * live in the platform source sets (`jvmMain`, `androidMain`, `iosMain`).
 */
internal interface HarfBuzzPlatformBinding {
    /** Distribution provenance reported by the loaded native library. */
    val identity: PlatformBindingIdentity

    /**
     * Whether this binding can apply a non-default normalized variation location.
     *
     * `false` means a location with any coordinate `!= 0f` must be refused with the typed
     * `font.shaping-variation-unsupported` failure rather than shaping without it. An empty
     * location and an explicitly design-default (`[0.0]`) location are admitted either way.
     */
    val supportsVariationLocation: Boolean

    /** Allocates a shaping buffer owned by the caller; every buffer must be [PlatformHarfBuzzBuffer.close]d. */
    fun createBuffer(): PlatformHarfBuzzBuffer

    /**
     * Opens a blob/face/font over [fontBytes] at [faceIndex].
     *
     * [variationLocation] is the instance's normalized location in the face's `fvar` axis order
     * (empty means "no variation"); a non-default value (any coordinate `!= 0f`) may be applied
     * only when [supportsVariationLocation] is `true`, while an all-zero location is a no-op.
     * The array is a fresh, caller-owned buffer that an implementation only reads during this call
     * and never retains or aliases, so its mutable element type carries no cross-call hazard.
     *
     * The platform font must be scaled by its face [PlatformPreparedFont.unitsPerEm] in design units
     * only: the caller applies the layout size through [DesignToLayoutScale]. Do not pre-apply the
     * layout size here — doing so would double-scale every shaped metric.
     */
    fun prepare(fontBytes: ByteArray, faceIndex: Int, variationLocation: FloatArray): PlatformPreparedFont

    /** Releases the native objects retained by [prepared]. */
    fun release(prepared: PlatformPreparedFont)
}

/** Immutable provenance of the loaded native distribution, mirroring the kffi binding identity. */
internal class PlatformBindingIdentity(
    val operatingSystem: String,
    val architecture: String,
    val artifactId: String,
    val artifactSha256: String,
    val upstreamSourceRevision: String,
    val buildChainIdentity: String,
)

/** An open native blob/face/font triple. */
internal interface PlatformPreparedFont {
    /** Face units-per-em, used to build the design-to-layout scale. */
    val unitsPerEm: Int

    /** Unshaped horizontal advance of [glyphId], used to audit ligature-carets against kerning. */
    fun horizontalAdvance(glyphId: Int): Int

    /**
     * Native `hb_font_get_glyph_extents` of [glyphId] at the prepared font's variation location,
     * in design units.
     *
     * The fields are `hb_glyph_extents_t` verbatim: `xBearing`/`yBearing` are the top-left corner
     * of the ink rectangle and the HarfBuzz y axis points up, `width` is `xMax - xMin` and
     * `height` is `yMin - yMax` (negative). The result is the varied outline envelope in the
     * font's design units and excludes `gvar` phantom points, matching the engine's ink bounds.
     * Consumed by the ink-bounds cross-check in `:kalligraphie:shaping:jvmTest`, which is the
     * only caller: no shaping path reads ink extents.
     */
    fun extents(glyphId: Int): PlatformGlyphExtents

    /** Reads the GDEF ligature carets of [glyphId] for [direction]. */
    fun ligatureCarets(
        direction: ShapingDirection,
        glyphId: Int,
        offset: Int,
        count: Int,
    ): PlatformLigatureCarets
}

/** One glyph descriptor read back from a shaped buffer. */
internal class PlatformGlyphInfo(
    val glyphId: Int,
    val cluster: Int,
    val unsafeToBreak: Boolean,
    val unsafeToConcat: Boolean,
)

/** One glyph position read back from a shaped buffer. */
internal class PlatformGlyphPosition(
    val xAdvance: Int,
    val yAdvance: Int,
    val xOffset: Int,
    val yOffset: Int,
)

/** Direct result of the native `hb_ot_layout_get_ligature_carets` call at the platform boundary. */
internal class PlatformLigatureCarets(
    val totalCount: Int,
    val copiedCount: Int,
    val positions: List<Int>,
)

/** Direct result of the native `hb_font_get_glyph_extents` call at the platform boundary. */
internal class PlatformGlyphExtents(
    val xBearing: Int,
    val yBearing: Int,
    val width: Int,
    val height: Int,
)

/** A native shaping buffer. */
internal interface PlatformHarfBuzzBuffer {
    fun setDirection(direction: ShapingDirection)
    fun setScript(scriptValue: String)
    fun setLanguage(language: String)
    fun setClusterLevelMonotoneCharacters()
    fun setFlags(beginningOfText: Boolean, endOfText: Boolean, produceUnsafeToConcat: Boolean)
    fun addUtf32(text: IntArray, offset: Int, length: Int)

    /** Shapes [prepared] with the explicit [features]; returns HarfBuzz's acceptance of the shaper. */
    fun shape(prepared: PlatformPreparedFont, features: List<OpenTypeFeature>): Boolean

    fun glyphCount(): Int
    fun glyphInfos(): List<PlatformGlyphInfo>
    fun glyphPositions(): List<PlatformGlyphPosition>
    fun close()
}

/**
 * Opens the native binding for this platform.
 *
 * Throws [HarfBuzzBindingException] when the target has no native library; [HarfBuzzBindings.open]
 * converts that into the typed `font.shaping-native-platform-unsupported` failure.
 */
internal expect fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding

/**
 * Converts a normalized variation location into the `hb_font_set_var_coords_normalized` encoding.
 *
 * HarfBuzz expects each normalized coordinate in `[-1, 1]` as a `1 shl 14` (`16384`) fixed-point
 * integer; the default [scale] is that factor. An empty array converts to an empty array, and the
 * conversion is shared by every platform that applies a variation location.
 */
internal fun FloatArray.toNormalizedVarCoords(scale: Int = 1 shl 14): IntArray =
    IntArray(size) { index -> (this[index] * scale).roundToInt() }
