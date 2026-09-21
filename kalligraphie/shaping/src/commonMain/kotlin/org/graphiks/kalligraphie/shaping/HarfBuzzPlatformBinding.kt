package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ShapingDirection

/**
 * Portable, platform-neutral surface of the native HarfBuzz operations used by [HarfBuzzBindings].
 *
 * `commonMain` must never reference a native binding type directly: `kffi-harfbuzz` currently
 * publishes only a JVM target, so the adapter has to compile for every target of this module while
 * the native surface stays behind an `expect`/`actual` boundary. Each platform supplies exactly one
 * binding; only the JVM target owns a real implementation, while Android (until the binding lands)
 * and iOS report the typed graceful-degradation failure.
 *
 * No native handle, library type or platform dependency crosses this interface. Implementations
 * live in the platform source sets (`jvmMain`, `androidMain`, `iosMain`).
 */
internal interface HarfBuzzPlatformBinding {
    /** Distribution provenance reported by the loaded native library. */
    val identity: PlatformBindingIdentity

    /** Allocates a shaping buffer owned by the caller; every buffer must be [PlatformHarfBuzzBuffer.close]d. */
    fun createBuffer(): PlatformHarfBuzzBuffer

    /**
     * Opens a blob/face/font over [fontBytes] at [faceIndex].
     *
     * The platform font must be scaled by its face [PlatformPreparedFont.unitsPerEm] in design units
     * only: the caller applies the layout size through [DesignToLayoutScale]. Do not pre-apply the
     * layout size here — doing so would double-scale every shaped metric.
     */
    fun prepare(fontBytes: ByteArray, faceIndex: Int): PlatformPreparedFont

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
