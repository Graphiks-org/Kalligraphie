package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapGlyphMetrics
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GlyphRasterizerTest {
    @Test
    fun outlineRouteScalesDesignUnitsToPixels() {
        val outline = squareOutline(unitsPerEm = 1_000, size = 500)
        val result = assertIs<RasterResult.Success<A8Image>>(
            GlyphRasterizer.rasterizeOutline(outline, OutlineRasterRequest(pixelsPerEm = 100.0)),
        )
        val image = result.value
        assertEquals(50, image.width)
        assertEquals(50, image.height)
        assertEquals(255, image[25, 25])
    }

    @Test
    fun outlineRouteAppliesIntegerOrigin() {
        val outline = squareOutline(unitsPerEm = 1_000, size = 500)
        val result = assertIs<RasterResult.Success<A8Image>>(
            GlyphRasterizer.rasterizeOutline(
                outline,
                OutlineRasterRequest(pixelsPerEm = 100.0, originX = 3, originY = -2),
            ),
        )
        assertEquals(3, result.value.left)
        assertEquals(-2, result.value.top)
    }

    @Test
    fun refusesOriginThatOverflowsTheIntegerDomain() {
        val failure = assertIs<RasterResult.Failure>(
            GlyphRasterizer.rasterizeOutline(
                squareOutline(unitsPerEm = 1_000, size = 500),
                OutlineRasterRequest(pixelsPerEm = 100.0, originX = Int.MAX_VALUE),
            ),
        )
        assertEquals("originX", assertIs<RasterDiagnostic.InvalidRequest>(failure.diagnostics.single()).field)
    }

    @Test
    fun acceptsExtentEndingExactlyAtTheIntegerBoundary() {
        val result = assertIs<RasterResult.Success<A8Image>>(
            GlyphRasterizer.rasterizeOutline(
                squareOutline(unitsPerEm = 1_000, size = 500),
                OutlineRasterRequest(pixelsPerEm = 100.0, originX = Int.MAX_VALUE - 49),
            ),
        )
        assertEquals(Int.MAX_VALUE - 49, result.value.left)
        assertEquals(50, result.value.width)
        assertEquals(255, result.value[25, 25])
    }

    @Test
    fun invalidPixelsPerEmIsRefusedWithTheFieldName() {
        listOf(0.0, -1.0, Double.POSITIVE_INFINITY, Double.NaN).forEach { value ->
            val failure = assertIs<RasterResult.Failure>(
                GlyphRasterizer.rasterizeOutline(squareOutline(1_000, 500), OutlineRasterRequest(value)),
            )
            assertEquals("pixelsPerEm", assertIs<RasterDiagnostic.InvalidRequest>(failure.diagnostics.single()).field)
        }
    }

    @Test
    fun pixelBudgetRefusesOversizedOutput() {
        val limits = RasterLimits.Default.copy(maxPixelsPerImage = 100)
        val failure = assertIs<RasterResult.Failure>(
            GlyphRasterizer.rasterizeOutline(
                squareOutline(unitsPerEm = 1_000, size = 500),
                OutlineRasterRequest(pixelsPerEm = 100.0, limits = limits),
            ),
        )
        val diagnostic = assertIs<RasterDiagnostic.LimitExceeded>(failure.diagnostics.single())
        assertEquals("maxPixelsPerImage", diagnostic.field)
        assertEquals(2_500L, diagnostic.observed)
        assertEquals(100L, diagnostic.limit)
    }

    @Test
    fun bitmapRouteChecksTheCanvasBudget() {
        val bitmap = BitmapGlyphIR(
            glyphId = GlyphId(3),
            strike = BitmapStrike(16, 16, 1),
            width = 4,
            height = 4,
            originX = 0,
            originY = 0,
            metrics = BitmapGlyphMetrics(16, 0),
            pixelFormat = BitmapPixelFormat.ALPHA_8,
            colorSpace = GlyphColorSpace.SRGB,
            decodedPixels = ByteArray(16) { -1 },
        )
        val limits = RasterLimits.Default.copy(maxPixelsPerImage = 15)
        val failure = assertIs<RasterResult.Failure>(
            GlyphRasterizer.rasterizeBitmap(bitmap, BitmapRasterRequest(GlyphColor(0, 0, 0), limits)),
        )
        val diagnostic = assertIs<RasterDiagnostic.LimitExceeded>(failure.diagnostics.single())
        assertEquals("maxPixelsPerImage", diagnostic.field)
        assertEquals(16L, diagnostic.observed)
        assertEquals(15L, diagnostic.limit)
    }

    @Test
    fun paintRouteMapsRejectedPositionsToInvalidRequest() {
        val path = GlyphPaintPath(
            listOf(
                GlyphPaintPathCommand.MoveTo(2_100_000_000.0, 0.0),
                GlyphPaintPathCommand.LineTo(2_100_000_004.0, 0.0),
                GlyphPaintPathCommand.LineTo(2_100_000_004.0, 4.0),
                GlyphPaintPathCommand.LineTo(2_100_000_000.0, 4.0),
                GlyphPaintPathCommand.Close,
            ),
        )
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 0,
            nodes = listOf(GlyphPaintNode.Path(path, GlyphColor(0, 0, 0))),
        )
        val failure = assertIs<RasterResult.Failure>(
            GlyphRasterizer.rasterizePaint(
                paint,
                PaintRasterRequest(pixelsPerEm = 1.0, unitsPerEm = 1, originX = 1_100_000_000),
            ),
        )
        assertEquals("originX", assertIs<RasterDiagnostic.InvalidRequest>(failure.diagnostics.single()).field)
    }

    @Test
    fun paintRouteRefusesNonPositiveUnitsPerEm() {
        val paint = GlyphPaintIR(
            schemaVersion = 1,
            rootNode = 0,
            nodes = listOf(
                GlyphPaintNode.Path(
                    GlyphPaintPath(
                        listOf(
                            GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                            GlyphPaintPathCommand.LineTo(1.0, 0.0),
                            GlyphPaintPathCommand.LineTo(1.0, 1.0),
                            GlyphPaintPathCommand.Close,
                        ),
                    ),
                    GlyphColor(0, 0, 0),
                ),
            ),
        )
        val failure = assertIs<RasterResult.Failure>(
            GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(pixelsPerEm = 1.0, unitsPerEm = 0)),
        )
        assertEquals("unitsPerEm", assertIs<RasterDiagnostic.InvalidRequest>(failure.diagnostics.single()).field)
    }

    private fun squareOutline(unitsPerEm: Int, size: Int): GlyphOutlineIR =
        GlyphOutlineIR(
            glyphId = 1,
            unitsPerEm = unitsPerEm,
            bounds = DesignBounds(0, 0, size, size),
            commands = listOf(
                GlyphOutlineIR.Command.MoveTo(0, 0),
                GlyphOutlineIR.Command.LineTo(size, 0),
                GlyphOutlineIR.Command.LineTo(size, size),
                GlyphOutlineIR.Command.LineTo(0, size),
                GlyphOutlineIR.Command.Close,
            ),
        )
}
