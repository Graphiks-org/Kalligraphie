package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterLimits
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.raster.Rgba8Image
import org.graphiks.kalligraphie.raster.sha256
import kotlin.math.roundToInt

/** One rendered variant: the padded, transparent-background image. */
internal class LogoRender(
    val image: Rgba8Image,
)

/**
 * Renders the Kalligraphie badge and wordmark artefacts with the deterministic
 * CPU rasterizer.
 *
 * The rasterizer preserves the source axes, so its raw output is vertically
 * mirrored; every rendered variant is flipped once into image orientation before
 * padding.
 */
internal object KalligraphieLogo {
    const val Wordmark: String = "Kalligraphie"

    /** Ink of the light variant: pure black. */
    val Ink: GlyphColor = GlyphColor(0, 0, 0)

    /** Ink of the dark variant: pure white. */
    val Paper: GlyphColor = GlyphColor(255, 255, 255)

    /** Square canvas edge of the badge image, so the mark drops into avatars and favicons uncropped. */
    private const val BadgeCanvasPx = 512

    /** Badge margin as a share of the canvas edge. */
    private const val BadgeMarginRatio = 0.05

    /** Ink width target of the wordmark image, before its margin. */
    private const val WordmarkTargetWidthPx = 1_152

    /** Uniform transparent margin around the wordmark. */
    private const val WordmarkMarginPx = 24

    /** Share of the badge square occupied by the letter's ink height. */
    private const val BadgeHeightRatio = 0.52

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

    /** Renders the badge with [ink] as the square and its complement as the knockout. */
    fun renderBadge(fonts: KalligraphieLogoFonts, ink: GlyphColor): LogoRender {
        val badgeGlyph = fonts.badgeGlyph()
        val badgeUpem = badgeGlyph.unitsPerEm
        val badgeInkHeight = (badgeGlyph.bounds.maxY - badgeGlyph.bounds.minY).toDouble()
        val side = badgeInkHeight / BadgeHeightRatio
        val centreX = (badgeGlyph.bounds.minX + badgeGlyph.bounds.maxX) / 2.0
        val centreY = (badgeGlyph.bounds.minY + badgeGlyph.bounds.maxY) / 2.0
        val left = centreX - side / 2.0
        val bottom = centreY - side / 2.0

        val margin = (BadgeCanvasPx * BadgeMarginRatio).roundToInt()
        val inkTargetPx = BadgeCanvasPx - margin * 2
        val pixelsPerEm = inkTargetPx * badgeUpem / side

        val nodes = mutableListOf<GlyphPaintNode>()
        nodes += GlyphPaintNode.Path(roundedSquarePath(left, bottom, side), ink)
        nodes += GlyphPaintNode.SolidOutline(badgeGlyph, complement(ink))
        val rasterized = rasterize(groupedGraph(nodes), pixelsPerEm, badgeUpem)
        return LogoRender(image = padToSize(flipVertically(rasterized), BadgeCanvasPx, BadgeCanvasPx))
    }

    /**
     * Renders the wordmark with [ink].
     *
     * Every placed outline already carries its pen position, so the glyphs are
     * painted without any further translation.
     */
    fun renderWordmark(fonts: KalligraphieLogoFonts, ink: GlyphColor): LogoRender {
        val wordmark = fonts.wordmark(Wordmark)
        val wordmarkUpem = wordmark.unitsPerEm
        val inkBounds = inkBoundsOf(wordmark.glyphs)
        val inkWidth = (inkBounds.maxX - inkBounds.minX).toDouble()
        val pixelsPerEm = WordmarkTargetWidthPx * wordmarkUpem / inkWidth

        val nodes = mutableListOf<GlyphPaintNode>()
        wordmark.glyphs.forEach { glyph ->
            nodes += GlyphPaintNode.SolidOutline(glyph.outline, ink)
        }
        val rasterized = rasterize(groupedGraph(nodes), pixelsPerEm, wordmarkUpem)
        return LogoRender(image = pad(flipVertically(rasterized), WordmarkMarginPx))
    }

    /** Returns the colour opposite [ink] on every channel. */
    private fun complement(ink: GlyphColor): GlyphColor = GlyphColor(
        red = 255 - ink.red,
        green = 255 - ink.green,
        blue = 255 - ink.blue,
    )

    /** Wraps [nodes] in a trailing root group composited `SOURCE_OVER`. */
    private fun groupedGraph(nodes: List<GlyphPaintNode>): GlyphPaintIR {
        val root = nodes.size
        return GlyphPaintIR(
            schemaVersion = 1,
            rootNode = root,
            nodes = nodes + GlyphPaintNode.Group(children = (0 until root).toList()),
        )
    }

    /** Rasterizes [graph], refusing a typed failure or an empty image. */
    private fun rasterize(graph: GlyphPaintIR, pixelsPerEm: Double, unitsPerEm: Int): Rgba8Image {
        val request = PaintRasterRequest(
            pixelsPerEm = pixelsPerEm,
            unitsPerEm = unitsPerEm,
            limits = Limits,
        )
        val rasterized = when (val result = GlyphRasterizer.rasterizePaint(graph, request)) {
            is RasterResult.Success -> result.value
            is RasterResult.Failure -> error("logo rasterization failed: ${result.diagnostics}")
        }
        require(rasterized.width > 0 && rasterized.height > 0) { "the logo rendered no ink" }
        return rasterized
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

    /**
     * Returns [image] mirrored about its horizontal axis.
     *
     * The rasterizer preserves the source axes, so y-up design space maps to
     * image rows without negation and its raw output reads upside down. The
     * upstream demonstration sheets flip for the same reason; this is the single
     * flip that puts the composed artefacts into image orientation. The reflected
     * vertical bearing is `-(top + height)`.
     */
    private fun flipVertically(image: Rgba8Image): Rgba8Image {
        val source = image.copyPixels()
        val target = ByteArray(source.size)
        val stride = image.width * 4
        for (y in 0 until image.height) {
            source.copyInto(
                target,
                destinationOffset = (image.height - 1 - y) * stride,
                startIndex = y * stride,
                endIndex = (y + 1) * stride,
            )
        }
        return Rgba8Image(image.width, image.height, image.left, -(image.top + image.height), target)
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
        return Rgba8Image(width, height, image.left - margin, image.top - margin, target)
    }

    /** Returns a [width]×[height] transparent canvas with [image] centred. */
    private fun padToSize(image: Rgba8Image, width: Int, height: Int): Rgba8Image {
        require(width >= image.width && height >= image.height) {
            "the ${width}x$height canvas must fit the ${image.width}x${image.height} image"
        }
        val left = (width - image.width) / 2
        val top = (height - image.height) / 2
        val source = image.copyPixels()
        val target = ByteArray(width * height * 4)
        for (y in 0 until image.height) {
            source.copyInto(
                target,
                destinationOffset = ((y + top) * width + left) * 4,
                startIndex = y * image.width * 4,
                endIndex = (y + 1) * image.width * 4,
            )
        }
        return Rgba8Image(width, height, image.left - left, image.top - top, target)
    }
}

/** Encodes [image] as PNG bytes. */
internal fun LogoRender.toPng(): ByteArray =
    PngEncoder.encodeRgba8(image.width, image.height, image.copyPixels())

/** Returns the SHA-256 digest of [image]'s pixel buffer. */
internal fun LogoRender.pixelDigest(): String =
    sha256(image.copyPixels())
