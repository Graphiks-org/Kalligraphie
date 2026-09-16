package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterLimits
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.raster.Rgba8Image

/** One rendered variant: the padded image and the layout that produced it. */
internal class LogoRender(
    val image: Rgba8Image,
    val pixelsPerEm: Double,
    val badgeUnitsPerEm: Int,
)

/** Composes and renders the Kalligraphie lockup with the deterministic CPU rasterizer. */
internal object KalligraphieLogo {
    const val Wordmark: String = "Kalligraphie"

    /** Ink colour of the light variant. */
    val Ink: GlyphColor = GlyphColor(0, 0, 0)

    /** Paper colour used to knock the badge letter out of the filled square. */
    val Paper: GlyphColor = GlyphColor(255, 255, 255)

    /** Ink width target before the margin is added, so the final width is close to 1200 px. */
    private const val InkTargetWidthPx = 1_152

    /** Uniform transparent margin added around the tight ink canvas. */
    private const val MarginPx = 24

    /** Share of the badge square occupied by the letter's ink height. */
    private const val BadgeHeightRatio = 0.52

    /** Gap after the badge, as a share of the badge side. */
    private const val BadgeGapRatio = 0.5

    /** Corner radius, as a share of the badge side. */
    private const val CornerRadiusRatio = 0.22

    /** Cubic circle-arc constant. */
    private const val Kappa = 0.5522847498307936

    private val Limits = RasterLimits(
        maxWidthPx = 2_048,
        maxHeightPx = 1_024,
        maxPixelsPerImage = 2_097_152,
        maxContours = 4_096,
        maxTotalPoints = 262_144,
        maxPaintNodes = 64,
        maxPaintDepth = 8,
    )

    /** Renders the lockup with [ink] as the dark colour and its opposite as the knockout. */
    fun render(fonts: KalligraphieLogoFonts, ink: GlyphColor): LogoRender {
        val paper = GlyphColor(
            red = 255 - ink.red,
            green = 255 - ink.green,
            blue = 255 - ink.blue,
        )
        val layout = layout(fonts, ink, paper)
        val request = PaintRasterRequest(
            pixelsPerEm = layout.pixelsPerEm,
            unitsPerEm = layout.badgeUnitsPerEm,
            limits = Limits,
        )
        val rasterized = when (val result = GlyphRasterizer.rasterizePaint(layout.graph, request)) {
            is RasterResult.Success -> result.value
            is RasterResult.Failure -> error("logo rasterization failed: ${result.diagnostics}")
        }
        require(rasterized.width > 0 && rasterized.height > 0) { "the logo rendered no ink" }
        return LogoRender(
            image = pad(rasterized, MarginPx),
            pixelsPerEm = layout.pixelsPerEm,
            badgeUnitsPerEm = layout.badgeUnitsPerEm,
        )
    }

    /** Composes the paint graph and the scale that maps its ink to the target width. */
    internal fun layout(fonts: KalligraphieLogoFonts, ink: GlyphColor, paper: GlyphColor): LogoLayout {
        val badgeGlyph = fonts.badgeGlyph()
        val badgeUpem = badgeGlyph.unitsPerEm
        val wordmark = fonts.wordmark(Wordmark)

        val badgeInkHeight = (badgeGlyph.bounds.maxY - badgeGlyph.bounds.minY).toDouble()
        val side = badgeInkHeight / BadgeHeightRatio
        val centreX = (badgeGlyph.bounds.minX + badgeGlyph.bounds.maxX) / 2.0
        val centreY = (badgeGlyph.bounds.minY + badgeGlyph.bounds.maxY) / 2.0
        val left = centreX - side / 2.0
        val bottom = centreY - side / 2.0
        val right = left + side

        val wordInk = inkBoundsOf(wordmark.glyphs)
        val badgePerWordUnit = badgeUpem.toDouble() / wordmark.unitsPerEm.toDouble()
        val wordUnitPerBadge = 1.0 / badgePerWordUnit
        val wordLeft = right + side * BadgeGapRatio
        val wordCentreY = (wordInk.minY + wordInk.maxY) / 2.0
        val wordDx = wordLeft * wordUnitPerBadge - wordInk.minX
        val wordDy = centreY * wordUnitPerBadge - wordCentreY

        val placedWordLeft = (wordInk.minX + wordDx) * badgePerWordUnit
        val placedWordRight = (wordInk.maxX + wordDx) * badgePerWordUnit
        val unionWidth = maxOf(right, placedWordRight) - minOf(left, placedWordLeft)
        val pixelsPerEm = InkTargetWidthPx.toDouble() * badgeUpem / unionWidth

        val nodes = mutableListOf<GlyphPaintNode>()
        nodes += GlyphPaintNode.Path(roundedSquarePath(left, bottom, side), ink)
        nodes += GlyphPaintNode.SolidOutline(badgeGlyph, paper)
        wordmark.glyphs.forEach { glyph ->
            nodes += GlyphPaintNode.SolidOutline(
                glyph.outline.translated(wordDx, wordDy),
                ink,
            )
        }
        val root = nodes.size
        nodes += GlyphPaintNode.Group(children = (0 until root).toList())
        return LogoLayout(
            pixelsPerEm = pixelsPerEm,
            badgeUnitsPerEm = badgeUpem,
            graph = GlyphPaintIR(schemaVersion = 1, rootNode = root, nodes = nodes),
        )
    }

    private fun roundedSquarePath(left: Double, bottom: Double, side: Double): GlyphPaintPath {
        val right = left + side
        val top = bottom + side
        val radius = side * CornerRadiusRatio
        val handle = radius * Kappa
        return GlyphPaintPath(
            listOf(
                GlyphPaintPathCommand.MoveTo(left + radius, top),
                GlyphPaintPathCommand.LineTo(right - radius, top),
                GlyphPaintPathCommand.CubicTo(right - radius + handle, top, right, top - radius + handle, right, top - radius),
                GlyphPaintPathCommand.LineTo(right, bottom + radius),
                GlyphPaintPathCommand.CubicTo(right, bottom + radius - handle, right - radius + handle, bottom, right - radius, bottom),
                GlyphPaintPathCommand.LineTo(left + radius, bottom),
                GlyphPaintPathCommand.CubicTo(left + radius - handle, bottom, left, bottom + radius - handle, left, bottom + radius),
                GlyphPaintPathCommand.LineTo(left, top - radius),
                GlyphPaintPathCommand.CubicTo(left, top - radius + handle, left + radius - handle, top, left + radius, top),
                GlyphPaintPathCommand.Close,
            ),
        )
    }

    /** Returns a new image with [margin] transparent pixels on every side. */
    private fun pad(image: Rgba8Image, margin: Int): Rgba8Image {
        require(margin >= 0) { "margin must not be negative." }
        val width = image.width + margin * 2
        val height = image.height + margin * 2
        val source = image.copyPixels()
        val target = ByteArray(width * height * 4)
        for (y in 0 until image.height) {
            source.copyInto(
                target,
                destinationOffset = ((y + margin) * width + margin) * 4,
                startIndex = y * image.width * 4,
                endIndex = (y + 1) * image.width * 4,
            )
        }
        return Rgba8Image(width, height, 0, 0, target)
    }
}

/** One composed logo: the paint graph and the scale that maps its ink to pixels. */
internal class LogoLayout(
    val pixelsPerEm: Double,
    val badgeUnitsPerEm: Int,
    val graph: GlyphPaintIR,
)

/** Encodes [image] as PNG bytes. */
internal fun LogoRender.toPng(): ByteArray =
    PngEncoder.encodeRgba8(image.width, image.height, image.copyPixels())

/** Returns the SHA-256 digest of [image]'s pixel buffer. */
internal fun LogoRender.pixelDigest(): String =
    org.graphiks.kalligraphie.raster.sha256(image.copyPixels())
