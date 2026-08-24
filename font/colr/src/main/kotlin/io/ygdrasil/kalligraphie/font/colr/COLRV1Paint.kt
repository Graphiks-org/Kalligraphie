package io.ygdrasil.kalligraphie.font.colr

/**
 * COLRv1 ColorLine extend mode.
 *
 * @property tag stable lowercase label used for deterministic diagnostics and graph summaries.
 */
enum class COLRV1ColorLineExtend(val tag: String) {
    /** Clamp the gradient to the edge stop colors outside the stop range. */
    PAD("pad"),

    /** Repeat the gradient stop range outside the stop range. */
    REPEAT("repeat"),

    /** Mirror the gradient stop range outside the stop range. */
    REFLECT("reflect"),
}

/**
 * Describes one COLRv1 ColorStop or VarColorStop.
 *
 * @property offset normalized F2DOT14 stop offset preserved from the font.
 * @property paletteIndex CPAL palette entry index, or [COLR_FOREGROUND_PALETTE_INDEX].
 * @property alpha alpha in the range 0.0 to 1.0 after F2DOT14 decoding and clamping.
 * @property varIndexBase variation index base for VarColorStop, or null for ColorStop.
 */
data class COLRV1ColorStop(
    val offset: Float,
    val paletteIndex: Int,
    val alpha: Float,
    val varIndexBase: Long? = null,
)

/**
 * Describes a COLRv1 ColorLine shared by linear, radial, and sweep gradient paints.
 *
 * The model keeps OpenType gradient data as metadata only. It does not resolve CPAL colors,
 * construct shader stops, apply color spaces, or normalize tile behavior for a renderer.
 *
 * @property extend extend mode applied outside the first and last color stops.
 * @property stops parsed color stops in font order.
 */
data class COLRV1ColorLine(
    val extend: COLRV1ColorLineExtend,
    val stops: List<COLRV1ColorStop>,
)

/**
 * COLRv1 PaintComposite mode independent of renderer-specific blend enums.
 *
 * @property fontValue integer value stored by the OpenType COLR table.
 * @property graphSuffix stable lowercase label used by flattened paint graph nodes.
 */
enum class COLRV1CompositeMode(val fontValue: Int, val graphSuffix: String) {
    CLEAR(0, "clear"),
    SRC(1, "src"),
    DST(2, "dst"),
    SRC_OVER(3, "src-over"),
    DST_OVER(4, "dst-over"),
    SRC_IN(5, "src-in"),
    DST_IN(6, "dst-in"),
    SRC_OUT(7, "src-out"),
    DST_OUT(8, "dst-out"),
    SRC_ATOP(9, "src-atop"),
    DST_ATOP(10, "dst-atop"),
    XOR(11, "xor"),
    PLUS(12, "plus"),
    SCREEN(13, "screen"),
    OVERLAY(14, "overlay"),
    DARKEN(15, "darken"),
    LIGHTEN(16, "lighten"),
    COLOR_DODGE(17, "color-dodge"),
    COLOR_BURN(18, "color-burn"),
    HARD_LIGHT(19, "hard-light"),
    SOFT_LIGHT(20, "soft-light"),
    DIFFERENCE(21, "difference"),
    EXCLUSION(22, "exclusion"),
    MULTIPLY(23, "multiply"),
    HUE(24, "hue"),
    SATURATION(25, "saturation"),
    COLOR(26, "color"),
    LUMINOSITY(27, "luminosity");

    companion object {
        /**
         * Resolves an OpenType composite mode byte.
         *
         * @param value unsigned composite mode value from a PaintComposite record.
         * @return matching mode, or null when [value] is outside the COLRv1 mode range.
         */
        fun fromFontValue(value: Int): COLRV1CompositeMode? =
            entries.firstOrNull { mode -> mode.fontValue == value }
    }
}

/**
 * Renderer-neutral subset of COLR version 1 paint operations parsed by the pure Kotlin font stack.
 *
 * The supported subset includes: solid paints, glyph paints, layer groups,
 * gradients, PaintColrGlyph references, composites, translations, affine
 * transforms, scale, rotate, and skew. Var* variants for variable fonts are
 * parsed but rejected at the planner level with a diagnostic.
 */
sealed interface COLRV1Paint {
    /**
     * PaintSolid or PaintVarSolid.
     *
     * @property paletteIndex CPAL palette index, or [COLR_FOREGROUND_PALETTE_INDEX].
     * @property alpha alpha in the range 0.0 to 1.0 after F2DOT14 decoding and clamping.
     * @property varIndexBase variation index base for PaintVarSolid, or null for PaintSolid.
     */
    data class Solid(
        val paletteIndex: Int,
        val alpha: Float,
        val varIndexBase: Long? = null,
    ) : COLRV1Paint

    /**
     * PaintGlyph.
     *
     * @property glyphId glyph identifier whose outline is filled by [paint].
     * @property paint child paint applied to [glyphId].
     */
    data class Glyph(
        val glyphId: Int,
        val paint: COLRV1Paint,
    ) : COLRV1Paint

    /**
     * PaintColrLayers.
     *
     * @property paints layer paints resolved from the COLRv1 LayerList in paint order.
     */
    data class Layers(
        val paints: List<COLRV1Paint>,
    ) : COLRV1Paint

    /**
     * PaintLinearGradient or PaintVarLinearGradient.
     *
     * @property colorLine renderer-neutral gradient extend mode and color stops.
     * @property x0 x coordinate of the first gradient control point in font design units.
     * @property y0 y coordinate of the first gradient control point in font design units.
     * @property x1 x coordinate of the second gradient control point in font design units.
     * @property y1 y coordinate of the second gradient control point in font design units.
     * @property x2 x coordinate of the third gradient control point in font design units.
     * @property y2 y coordinate of the third gradient control point in font design units.
     * @property varIndexBase variation index base for PaintVarLinearGradient, or null for
     * PaintLinearGradient.
     */
    data class LinearGradient(
        val colorLine: COLRV1ColorLine,
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val varIndexBase: Long? = null,
    ) : COLRV1Paint

    /**
     * PaintRadialGradient or PaintVarRadialGradient.
     *
     * @property colorLine renderer-neutral gradient extend mode and color stops.
     * @property x0 x coordinate of the start circle center in font design units.
     * @property y0 y coordinate of the start circle center in font design units.
     * @property radius0 radius of the start circle in font design units.
     * @property x1 x coordinate of the end circle center in font design units.
     * @property y1 y coordinate of the end circle center in font design units.
     * @property radius1 radius of the end circle in font design units.
     * @property varIndexBase variation index base for PaintVarRadialGradient, or null for
     * PaintRadialGradient.
     */
    data class RadialGradient(
        val colorLine: COLRV1ColorLine,
        val x0: Int,
        val y0: Int,
        val radius0: Int,
        val x1: Int,
        val y1: Int,
        val radius1: Int,
        val varIndexBase: Long? = null,
    ) : COLRV1Paint

    /**
     * PaintSweepGradient or PaintVarSweepGradient.
     *
     * @property colorLine renderer-neutral gradient extend mode and color stops.
     * @property centerX x coordinate of the sweep center in font design units.
     * @property centerY y coordinate of the sweep center in font design units.
     * @property startAngle normalized F2DOT14 start angle value preserved from the font.
     * @property endAngle normalized F2DOT14 end angle value preserved from the font.
     * @property varIndexBase variation index base for PaintVarSweepGradient, or null for
     * PaintSweepGradient.
     */
    data class SweepGradient(
        val colorLine: COLRV1ColorLine,
        val centerX: Int,
        val centerY: Int,
        val startAngle: Float,
        val endAngle: Float,
        val varIndexBase: Long? = null,
    ) : COLRV1Paint

    /**
     * PaintComposite.
     *
     * @property source source paint that is composited over [backdrop] using [mode].
     * @property mode OpenType composite mode preserved without binding to a renderer blend enum.
     * @property backdrop backdrop paint used as the destination input for [mode].
     */
    data class Composite(
        val source: COLRV1Paint,
        val mode: COLRV1CompositeMode,
        val backdrop: COLRV1Paint,
    ) : COLRV1Paint

    /**
     * PaintColrGlyph.
     *
     * @property glyphId base glyph identifier whose COLRv1 paint graph is referenced.
     */
    data class ColrGlyph(
        val glyphId: Int,
    ) : COLRV1Paint

    /**
     * PaintTranslate or PaintVarTranslate.
     *
     * @property paint translated child paint.
     * @property dx x translation in font design units.
     * @property dy y translation in font design units.
     * @property varIndexBase variation index base for PaintVarTranslate, or null for
     * PaintTranslate.
     */
    data class Translate(
        val paint: COLRV1Paint,
        val dx: Int,
        val dy: Int,
        val varIndexBase: Long? = null,
    ) : COLRV1Paint

    /**
     * PaintTransform or PaintVarTransform.
     *
     * @property paint transformed child paint.
     * @property xx affine transform xx component.
     * @property yx affine transform yx component.
     * @property xy affine transform xy component.
     * @property yy affine transform yy component.
     * @property dx affine transform x translation.
     * @property dy affine transform y translation.
     * @property varIndexBase variation index base for PaintVarTransform, or null for
     * PaintTransform.
     */
    data class Transform(
        val paint: COLRV1Paint,
        val xx: Float,
        val yx: Float,
        val xy: Float,
        val yy: Float,
        val dx: Float,
        val dy: Float,
        val varIndexBase: Long? = null,
    ) : COLRV1Paint

    /**
     * PaintScale.
     *
     * @property paint scaled child paint.
     * @property scaleX scale factor in x direction as F2DOT14.
     * @property scaleY scale factor in y direction as F2DOT14.
     */
    data class Scale(
        val paint: COLRV1Paint,
        val scaleX: Float,
        val scaleY: Float,
    ) : COLRV1Paint

    /**
     * PaintScaleAroundCenter.
     *
     * @property paint scaled child paint.
     * @property scaleX scale factor in x direction as F2DOT14.
     * @property scaleY scale factor in y direction as F2DOT14.
     * @property centerX x coordinate of the scale center in font design units.
     * @property centerY y coordinate of the scale center in font design units.
     */
    data class ScaleAroundCenter(
        val paint: COLRV1Paint,
        val scaleX: Float,
        val scaleY: Float,
        val centerX: Int,
        val centerY: Int,
    ) : COLRV1Paint

    /**
     * PaintScaleUniform.
     *
     * @property paint scaled child paint.
     * @property scale uniform scale factor as F2DOT14.
     */
    data class ScaleUniform(
        val paint: COLRV1Paint,
        val scale: Float,
    ) : COLRV1Paint

    /**
     * PaintScaleUniformAroundCenter.
     *
     * @property paint scaled child paint.
     * @property scale uniform scale factor as F2DOT14.
     * @property centerX x coordinate of the scale center in font design units.
     * @property centerY y coordinate of the scale center in font design units.
     */
    data class ScaleUniformAroundCenter(
        val paint: COLRV1Paint,
        val scale: Float,
        val centerX: Int,
        val centerY: Int,
    ) : COLRV1Paint

    /**
     * PaintRotate.
     *
     * @property paint rotated child paint.
     * @property angle rotation angle as F2DOT14.
     */
    data class Rotate(
        val paint: COLRV1Paint,
        val angle: Float,
    ) : COLRV1Paint

    /**
     * PaintRotateAroundCenter.
     *
     * @property paint rotated child paint.
     * @property angle rotation angle as F2DOT14.
     * @property centerX x coordinate of the rotation center in font design units.
     * @property centerY y coordinate of the rotation center in font design units.
     */
    data class RotateAroundCenter(
        val paint: COLRV1Paint,
        val angle: Float,
        val centerX: Int,
        val centerY: Int,
    ) : COLRV1Paint

    /**
     * PaintSkew.
     *
     * @property paint skewed child paint.
     * @property xSkew skew angle in x direction as F2DOT14.
     * @property ySkew skew angle in y direction as F2DOT14.
     */
    data class Skew(
        val paint: COLRV1Paint,
        val xSkew: Float,
        val ySkew: Float,
    ) : COLRV1Paint

    /**
     * PaintSkewAroundCenter.
     *
     * @property paint skewed child paint.
     * @property xSkew skew angle in x direction as F2DOT14.
     * @property ySkew skew angle in y direction as F2DOT14.
     * @property centerX x coordinate of the skew center in font design units.
     * @property centerY y coordinate of the skew center in font design units.
     */
    data class SkewAroundCenter(
        val paint: COLRV1Paint,
        val xSkew: Float,
        val ySkew: Float,
        val centerX: Int,
        val centerY: Int,
    ) : COLRV1Paint
}
