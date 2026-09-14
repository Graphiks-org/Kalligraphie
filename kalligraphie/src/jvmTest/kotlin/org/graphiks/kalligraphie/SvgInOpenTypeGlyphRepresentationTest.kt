package org.graphiks.kalligraphie

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintAlphaInterpolationMode
import org.graphiks.kalligraphie.api.GlyphPaintColorStop
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintInterpolationSpace
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphPaintPoint
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SvgInOpenTypeGlyphRepresentationTest {
    @Test
    fun reportsRectangleOutlinePointExhaustionAsAResourceLimit() {
        for (fill in listOf("#102030", "url(#sky)")) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>
                    <linearGradient id="sky">
                      <stop offset="0" stop-color="#102030"/>
                      <stop offset="1" stop-color="#90A0B0"/>
                    </linearGradient>
                  </defs>
                  <rect x="10" y="20" width="30" height="40" fill="$fill"/>
                </svg>
            """.trimIndent()
            val tooSmall = gradientProfile(outlineProfile = svgOutlineProfile().copy(maxPoints = 3))

            val failure = assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, tooSmall))
            assertEquals(FontDiagnosticLocation.Table("SVG "), failure.location)

            val admitted = gradientProfile(outlineProfile = svgOutlineProfile().copy(maxPoints = 4))
            val paint = assertIs<GlyphRepresentation.Paint>(resolveSvgDocument(document, listOf(admitted)).representation).paint
            val path = if (fill == "#102030") {
                val solid = assertIs<GlyphPaintNode.Path>(paint.nodes.single())
                assertEquals(GlyphColor(16, 32, 48), solid.color)
                solid.path
            } else {
                val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
                assertEquals(
                    listOf(
                        GlyphPaintColorStop(0.0, GlyphColor(16, 32, 48), 1.0),
                        GlyphPaintColorStop(1.0, GlyphColor(144, 160, 176), 1.0),
                    ),
                    gradient.colorLine.colorStops,
                )
                assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path
            }
            assertEquals(
                listOf(
                    GlyphPaintPathCommand.MoveTo(10.0, 20.0),
                    GlyphPaintPathCommand.LineTo(40.0, 20.0),
                    GlyphPaintPathCommand.LineTo(40.0, 60.0),
                    GlyphPaintPathCommand.LineTo(10.0, 60.0),
                    GlyphPaintPathCommand.Close,
                ),
                path.commands,
            )
        }
    }

    @Test
    fun normalizesDefaultSrgbGradientAndSolidRectangleWithOrderedProfileFallback() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="sky">
                  <stop offset="0%" stop-color="#102030" stop-opacity="25%"/>
                  <stop offset="100%" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10 20)">
                <rect x="100" y="200" width="400" height="600" fill="url(#sky)"/>
                <rect x="600" y="200" width="50" height="75" fill="#C0D0E0"/>
              </g>
            </svg>
        """.trimIndent()
        val schema2 = schema2GradientProfile()
        val linearOnly = gradientProfile(
            interpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB),
        )
        val srgb = gradientProfile(interpolationSpaces = listOf(GlyphPaintInterpolationSpace.SRGB))

        val resolved = resolveSvgDocument(document, listOf(schema2, linearOnly, srgb))
        val paint = assertIs<GlyphRepresentation.Paint>(resolved.representation).paint

        assertEquals(srgb, resolved.profile)
        assertEquals(3, paint.schemaVersion)
        assertEquals(3, paint.rootNode)
        assertEquals(4, paint.nodes.size)
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        assertEquals(GlyphPaintExtendMode.PAD, gradient.colorLine.extendMode)
        assertEquals(GlyphPaintInterpolationSpace.SRGB, gradient.colorLine.interpolationSpace)
        assertEquals(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED, gradient.colorLine.alphaInterpolationMode)
        assertEquals(
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(16, 32, 48), 0.25),
                GlyphPaintColorStop(1.0, GlyphColor(144, 160, 176), 1.0),
            ),
            gradient.colorLine.colorStops,
        )
        assertEquals(GlyphPaintPoint(110.0, 220.0), gradient.p0)
        assertEquals(GlyphPaintPoint(510.0, 220.0), gradient.p1)
        assertEquals(GlyphPaintPoint(110.0, 820.0), gradient.p2)

        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, clip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(110.0, 220.0),
                GlyphPaintPathCommand.LineTo(510.0, 220.0),
                GlyphPaintPathCommand.LineTo(510.0, 820.0),
                GlyphPaintPathCommand.LineTo(110.0, 820.0),
                GlyphPaintPathCommand.Close,
            ),
            clip.path.commands,
        )
        val solid = assertIs<GlyphPaintNode.Path>(paint.nodes[2])
        assertEquals(GlyphColor(192, 208, 224), solid.color)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(610.0, 220.0),
                GlyphPaintPathCommand.LineTo(660.0, 220.0),
                GlyphPaintPathCommand.LineTo(660.0, 295.0),
                GlyphPaintPathCommand.LineTo(610.0, 295.0),
                GlyphPaintPathCommand.Close,
            ),
            solid.path.commands,
        )
        assertEquals(GlyphPaintNode.Group(listOf(1, 2)), paint.nodes[3])
    }

    @Test
    fun mapsInterpolationSpreadAndNormalizedStopsToLiteralPortableGradients() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="linear" x1="25%" y1="20%" x2="75%" y2="80%" spreadMethod="repeat" color-interpolation="linearRGB">
                  <stop offset="-25%" stop-color="#010203" stop-opacity="-10%"/>
                  <stop offset="60%" stop-color="#112233" stop-opacity="25%"/>
                  <stop offset="40%" stop-color="#445566" stop-opacity="150%"/>
                  <stop offset="125%" stop-color="#778899"/>
                </linearGradient>
                <linearGradient id="encoded" spreadMethod="reflect" color-interpolation="sRGB">
                  <stop offset="0" stop-color="#AABBCC"/>
                  <stop offset="1" stop-color="#DDEEFF"/>
                </linearGradient>
              </defs>
              <rect x="100" y="200" width="400" height="600" fill="url(#linear)"/>
              <rect x="600" y="100" width="100" height="200" fill="url(#encoded)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile(maxGradients = 2, maxColorStops = 6))).representation,
        ).paint
        val linear = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        val encoded = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[2])

        assertEquals(GlyphPaintExtendMode.REPEAT, linear.colorLine.extendMode)
        assertEquals(GlyphPaintInterpolationSpace.LINEAR_SRGB, linear.colorLine.interpolationSpace)
        assertEquals(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED, linear.colorLine.alphaInterpolationMode)
        assertEquals(
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(1, 2, 3), 0.0),
                GlyphPaintColorStop(0.6, GlyphColor(17, 34, 51), 0.25),
                GlyphPaintColorStop(0.6, GlyphColor(68, 85, 102), 1.0),
                GlyphPaintColorStop(1.0, GlyphColor(119, 136, 153), 1.0),
            ),
            linear.colorLine.colorStops,
        )
        assertEquals(GlyphPaintPoint(200.0, 320.0), linear.p0)
        assertEquals(GlyphPaintPoint(400.0, 680.0), linear.p1)
        assertEquals(-40.0, linear.p2.x, 1e-9)
        assertEquals(620.0, linear.p2.y, 1e-9)
        assertEquals(GlyphPaintExtendMode.REFLECT, encoded.colorLine.extendMode)
        assertEquals(GlyphPaintInterpolationSpace.SRGB, encoded.colorLine.interpolationSpace)
    }

    @Test
    fun repeatAndReflectGradientsExposeCompletePeriodsToPortableConsumers() {
        for (spreadMethod in listOf("repeat", "reflect")) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>
                    <linearGradient id="period" spreadMethod="$spreadMethod">
                      <stop offset="25%" stop-color="#102030" stop-opacity="40%"/>
                      <stop offset="75%" stop-color="#90A0B0" stop-opacity="80%"/>
                    </linearGradient>
                  </defs>
                  <rect x="0" y="0" width="100" height="100" fill="url(#period)"/>
                </svg>
            """.trimIndent()

            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document, listOf(gradientProfile(maxColorStops = 4))).representation,
            ).paint
            val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

            assertEquals(
                listOf(
                    GlyphPaintColorStop(0.0, GlyphColor(16, 32, 48), 0.4),
                    GlyphPaintColorStop(0.25, GlyphColor(16, 32, 48), 0.4),
                    GlyphPaintColorStop(0.75, GlyphColor(144, 160, 176), 0.8),
                    GlyphPaintColorStop(1.0, GlyphColor(144, 160, 176), 0.8),
                ),
                gradient.colorLine.colorStops,
                spreadMethod,
            )
        }
    }

    @Test
    fun repeatEndpointCopiesPreserveEqualOffsetDiscontinuities() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="step" spreadMethod="repeat">
                  <stop offset="25%" stop-color="#102030"/>
                  <stop offset="25%" stop-color="#405060"/>
                  <stop offset="75%" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="100" height="100" fill="url(#step)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile(maxColorStops = 5))).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(listOf(0.0, 0.25, 0.25, 0.75, 1.0), gradient.colorLine.colorStops.map { it.offset })
        assertEquals(
            listOf(
                GlyphColor(16, 32, 48),
                GlyphColor(16, 32, 48),
                GlyphColor(64, 80, 96),
                GlyphColor(144, 160, 176),
                GlyphColor(144, 160, 176),
            ),
            gradient.colorLine.colorStops.map { it.color },
        )
    }

    @Test
    fun syntheticRepeatEndpointsConsumeTheReachedColorStopBudget() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="period" spreadMethod="repeat">
                  <stop offset="25%" stop-color="#102030"/>
                  <stop offset="75%" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="100" height="100" fill="url(#period)"/>
            </svg>
        """.trimIndent()

        val error = acquireSvgFailure(document, gradientProfile(maxColorStops = 3))

        assertIs<FontError.ResourceLimitExceeded>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun schemaOneSvgKeepsItsHistoricalNodeAndDepthBudgetsWithoutAVisitBudget() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <path d="M 0 0 L 10 0 L 10 10 Z" fill="#102030"/>
              <path d="M 20 20 L 30 20 L 30 30 Z" fill="#405060"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile(maxPaintVisits = 1))).representation,
        ).paint

        assertEquals(3, paint.nodes.size)
        assertEquals(GlyphPaintNode.Group(listOf(0, 1)), paint.nodes[paint.rootNode])
        assertEquals(
            listOf(GlyphColor(16, 32, 48), GlyphColor(64, 80, 96)),
            paint.nodes.filterIsInstance<GlyphPaintNode.Path>().map { it.color },
        )
    }

    @Test
    fun rejectsJavaOnlyNumericFormsBeforePublishingAnSvgAsset() {
        val invalidDocuments = listOf(
            numericSvgDocument(rectX = "0x1.0p0"),
            numericSvgDocument(rectWidth = "10f"),
            numericSvgDocument(gradientX1 = "0x1.0p-1"),
            numericSvgDocument(gradientX2 = "1d"),
            numericSvgDocument(stopOffset = "0.5f"),
            numericSvgDocument(stopOpacity = "0x1.0p-1"),
        )
        for (document in invalidDocuments) {
            val error = acquireSvgFailure(document, gradientProfile())

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun rejectsAttributeNumbersOutsideTheSvgNumberGrammar() {
        val invalidDocuments = listOf(
            numericSvgDocument(rectWidth = "1."),
            numericSvgDocument(rectWidth = "\u00a01"),
            numericSvgDocument(gradientX1 = "1.e2"),
            numericSvgDocument(stopOffset = "50 %"),
        )
        for (document in invalidDocuments) {
            val error = acquireSvgFailure(document, gradientProfile())

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun acceptsSvgExponentNotationAcrossGeometryGradientAndStops() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="numbers" x1="0e0" y1="0E0" x2="1e0" y2="0E0">
                  <stop offset="0e0" stop-opacity="5e-1" stop-color="#102030"/>
                  <stop offset="1E0" stop-opacity="1e0" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <rect x="1e1" y="2E1" width="3e1" height="4E1" fill="url(#numbers)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(listOf(0.0, 1.0), gradient.colorLine.colorStops.map { it.offset })
        assertEquals(listOf(0.5, 1.0), gradient.colorLine.colorStops.map { it.opacity })
        assertEquals(GlyphPaintPoint(10.0, 20.0), gradient.p0)
        assertEquals(GlyphPaintPoint(40.0, 20.0), gradient.p1)
        assertEquals(GlyphPaintPoint(10.0, 60.0), gradient.p2)
    }

    @Test
    fun noStopGradientPaintsNothing() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><linearGradient id="empty"/></defs>
              <rect x="10" y="20" width="30" height="40" fill="url(#empty)"/>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(resolveSvgDocument(document, listOf(gradientProfile())).representation)
    }

    @Test
    fun transformedRectangleWithoutAreaPaintsNothing() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="vertical" x1="0%" y1="0%" x2="0%" y2="100%">
                  <stop offset="0%" stop-color="#102030"/>
                  <stop offset="100%" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="scale(0 1)">
                <rect x="10" y="20" width="30" height="40" fill="url(#vertical)"/>
              </g>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(resolveSvgDocument(document, listOf(gradientProfile())).representation)
    }

    @Test
    fun extremeAnisotropicScaleWithFinitePaintGeometryRemainsRenderable() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="balanced">
                  <stop offset="0%" stop-color="#102030"/>
                  <stop offset="100%" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="scale(1e-308 1e308)">
                <rect x="0" y="0" width="1e308" height="1e-308" fill="url(#balanced)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        assertEquals(GlyphPaintPoint(0.0, 0.0), gradient.p0)
        assertEquals(1.0, gradient.p1.x, 1e-12)
        assertEquals(0.0, gradient.p1.y)
        assertEquals(0.0, gradient.p2.x)
        assertEquals(1.0, gradient.p2.y, 1e-12)
        assertEquals(1, paint.rootNode)
        assertEquals(0, assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).paint)
    }

    @Test
    fun diagonalGradientSurvivesExtremeAnisotropyWithFinitePoints() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="diagonal" x1="0" y1="0" x2="1" y2="1">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="scale(5e-324 1e9)">
                <rect x="0" y="0" width="1" height="1" fill="url(#diagonal)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        assertEquals(GlyphPaintPoint(0.0, 0.0), gradient.p0)
        assertEquals(GlyphPaintPoint(5e-324, 1e9), gradient.p1)
        assertEquals(GlyphPaintPoint(-5e-324, 1e9), gradient.p2)
        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, clip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                GlyphPaintPathCommand.LineTo(5e-324, 0.0),
                GlyphPaintPathCommand.LineTo(5e-324, 1e9),
                GlyphPaintPathCommand.LineTo(0.0, 1e9),
                GlyphPaintPathCommand.Close,
            ),
            clip.path.commands,
        )
    }

    @Test
    fun oneStopAndDegenerateGradientsUseTheirFinalStopAsBoundedSolidPaint() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="one" x1="1e308">
                  <stop offset="40%" stop-color="#AABBCC" stop-opacity="40%"/>
                </linearGradient>
                <linearGradient id="flat" x1="10%" y1="20%" x2="10%" y2="20%">
                  <stop offset="0%" stop-color="#112233" stop-opacity="20%"/>
                  <stop offset="100%" stop-color="#445566" stop-opacity="70%"/>
                </linearGradient>
              </defs>
              <rect x="10" y="20" width="30" height="40" fill="url(#one)"/>
              <rect x="50" y="60" width="70" height="80" fill="url(#flat)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile(maxGradients = 2, maxColorStops = 3))).representation,
        ).paint

        assertEquals(4, paint.rootNode)
        assertEquals(5, paint.nodes.size)
        assertEquals(GlyphPaintNode.Solid(GlyphColor(170, 187, 204), 0.4), paint.nodes[0])
        val oneStopClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, oneStopClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(10.0, 20.0),
                GlyphPaintPathCommand.LineTo(40.0, 20.0),
                GlyphPaintPathCommand.LineTo(40.0, 60.0),
                GlyphPaintPathCommand.LineTo(10.0, 60.0),
                GlyphPaintPathCommand.Close,
            ),
            oneStopClip.path.commands,
        )
        assertEquals(GlyphPaintNode.Solid(GlyphColor(68, 85, 102), 0.7), paint.nodes[2])
        val degenerateClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[3])
        assertEquals(2, degenerateClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(50.0, 60.0),
                GlyphPaintPathCommand.LineTo(120.0, 60.0),
                GlyphPaintPathCommand.LineTo(120.0, 140.0),
                GlyphPaintPathCommand.LineTo(50.0, 140.0),
                GlyphPaintPathCommand.Close,
            ),
            degenerateClip.path.commands,
        )
        assertEquals(GlyphPaintNode.Group(listOf(1, 3)), paint.nodes[4])
    }

    @Test
    fun rejectsUnsafeOrUnsupportedGradientReferencesBeforePublishingAnAsset() {
        val unsupportedDocuments = listOf(
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(#missing)\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(https://example.test/paint)\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><rect x=\"0\" y=\"0\" width=\"0\" height=\"10\" fill=\"url(https://example.test/paint)\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><g transform=\"scale(0 1)\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(https://example.test/paint)\"/></g></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(#later)\"/><defs><linearGradient id=\"later\"><stop offset=\"0\" stop-color=\"#000000\"/></linearGradient></defs></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><defs><linearGradient id=\"units\" gradientUnits=\"userSpaceOnUse\"><stop offset=\"0\" stop-color=\"#000000\"/></linearGradient></defs><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(#units)\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><defs><linearGradient id=\"moved\" gradientTransform=\"scale(2)\"><stop offset=\"0\" stop-color=\"#000000\"/></linearGradient></defs><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(#moved)\"/></svg>",
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><defs><linearGradient id=\"derived\" href=\"#base\"><stop offset=\"0\" stop-color=\"#000000\"/></linearGradient></defs><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(#derived)\"/></svg>",
        )

        for (document in unsupportedDocuments) {
            val error = acquireSvgFailure(document, gradientProfile())
            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun rejectsDuplicateGradientIdsAndResourceExhaustionBeforePublishingAnAsset() {
        val duplicate = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="same"><stop offset="0" stop-color="#000000"/></linearGradient>
                <linearGradient id="same"><stop offset="1" stop-color="#FFFFFF"/></linearGradient>
              </defs>
              <rect x="0" y="0" width="10" height="10" fill="url(#same)"/>
            </svg>
        """.trimIndent()
        assertIs<FontError.FontDataFailure>(acquireSvgFailure(duplicate, gradientProfile(maxGradients = 2)))

        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="bounded">
                  <stop offset="0" stop-color="#000000"/>
                  <stop offset="1" stop-color="#FFFFFF"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="10" height="10" fill="url(#bounded)"/>
            </svg>
        """.trimIndent()
        val limitedProfiles = listOf(
            gradientProfile(maxGradients = 0),
            gradientProfile(maxColorStops = 1),
            gradientProfile(maxPaths = 0),
            gradientProfile(maxClips = 0),
            gradientProfile(maxNodes = 1),
            gradientProfile(maxReferences = 0),
        )
        for (profile in limitedProfiles) {
            val error = acquireSvgFailure(document, profile)
            assertIs<FontError.ResourceLimitExceeded>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun rejectsGlobalIdCollisionsAcrossGlyphTargetsAndGradientDefinitions() {
        val collidingDocuments = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>
                    <linearGradient id="glyph1">
                      <stop offset="0" stop-color="#000000"/>
                      <stop offset="1" stop-color="#FFFFFF"/>
                    </linearGradient>
                  </defs>
                  <rect x="0" y="0" width="10" height="10" fill="url(#glyph1)"/>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg">
                  <defs>
                    <linearGradient id="glyph1">
                      <stop offset="0" stop-color="#000000"/>
                      <stop offset="1" stop-color="#FFFFFF"/>
                    </linearGradient>
                  </defs>
                  <g id="glyph1">
                    <rect x="0" y="0" width="10" height="10" fill="url(#glyph1)"/>
                  </g>
                </svg>
            """.trimIndent(),
        )

        for (document in collidingDocuments) {
            val error = acquireSvgFailure(document, gradientProfile())
            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun normalizesTheVersionedSvgInOpenTypeGlyphIntoPortableCubicPaths() {
        materializationCachePolicies().forEach { cachePolicy ->
            val catalog = success(
                Kalligraphie.embedded(
                    fixtureBytes(),
                    FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
                    cachePolicy,
                ),
            )
            val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
            val resolver = success(catalog.openAssetResolver())
            try {
                val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
                try {
                    val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))).paint
                    val warmPaint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))).paint
                    repeat(5) { index ->
                        val pressureRequirements = FontAccessRequirementsSnapshot.renderable(
                            listOf(paintProfile(maxSourceBytes = 16 * 1024 + index + 1)),
                        )
                        val pressureAsset = success(
                            instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, pressureRequirements),
                        )
                        try {
                            assertIs<GlyphRepresentation.Paint>(success(pressureAsset.resolveGlyph(FontGlyphRequest(GlyphId(1)))))
                        } finally {
                            pressureAsset.close()
                        }
                    }
                    val afterPressurePaint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1))))).paint

                    assertTrue(catalog.faces.single().capabilities.paintGraph)
                    assertEquals(paint, warmPaint)
                    assertEquals(paint, afterPressurePaint)
                    for (resolved in listOf(paint, warmPaint, afterPressurePaint)) {
                        assertEquals(1, resolved.schemaVersion)
                        assertEquals(2, resolved.rootNode)
                        assertEquals(3, resolved.nodes.size)
                        assertEquals(GlyphPaintNode.Group(listOf(0, 1)), resolved.nodes[2])
                        val path = assertIs<GlyphPaintNode.Path>(resolved.nodes[0])
                        val secondPath = assertIs<GlyphPaintNode.Path>(resolved.nodes[1])
                        assertEquals(GlyphColor(49, 55, 61), path.color)
                        assertEquals(GlyphColor(49, 55, 61), secondPath.color)
                        val move = assertIs<GlyphPaintPathCommand.MoveTo>(path.path.commands.first())
                        assertEquals(18.0 * 56.888888888888886, move.x, absoluteTolerance = 0.000_000_1)
                        assertEquals(-6.75 - 1638.4, move.y, absoluteTolerance = 0.000_000_1)
                        assertIs<GlyphPaintPathCommand.CubicTo>(path.path.commands[1])
                    }
                } finally {
                    asset.close()
                }
            } finally {
                resolver.close()
            }
        }
    }

    @Test
    fun materializesTheAuditedSvgGlyphWhenItsDocumentUsesGzipTransport() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithGzipSvgDocument(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf with gzip SVG transport"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(
                    success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1)))),
                ).paint

                assertEquals(1, paint.schemaVersion)
                assertEquals(2, paint.rootNode)
                assertEquals(3, paint.nodes.size)
                assertEquals(GlyphPaintNode.Group(listOf(0, 1)), paint.nodes[2])
                val firstPath = assertIs<GlyphPaintNode.Path>(paint.nodes[0])
                assertEquals(GlyphColor(49, 55, 61), firstPath.color)
                val move = assertIs<GlyphPaintPathCommand.MoveTo>(firstPath.path.commands.first())
                assertEquals(18.0 * 56.888888888888886, move.x, absoluteTolerance = 0.000_000_1)
                assertEquals(-6.75 - 1638.4, move.y, absoluteTolerance = 0.000_000_1)
                assertIs<GlyphPaintPathCommand.CubicTo>(firstPath.path.commands[1])
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsAGzipSvgDocumentWithACorruptTrailerBeforePublishingAnAsset() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithGzipSvgDocument(corruptTrailer = true),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf with corrupt gzip SVG trailer"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            val error = assertIs<FontError.FontDataFailure>(failure.error)
            assertEquals("font.svg.invalid-gzip", error.code)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsAGzipSvgDocumentWithAReservedFlagBeforePublishingAnAsset() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithGzipSvgDocument(setReservedFlag = true),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf with reserved gzip flag"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            val error = assertIs<FontError.FontDataFailure>(failure.error)
            assertEquals("font.svg.invalid-gzip", error.code)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsMalformedGzipWithoutLeakingADecompressorException() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithSvgDocumentPayload(
                    byteArrayOf(
                        0x1F,
                        0x8B.toByte(),
                        0x08,
                        0x06,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                        0x00,
                    ),
                ),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf with malformed gzip SVG"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            val error = assertIs<FontError.FontDataFailure>(failure.error)
            assertEquals("font.svg.invalid-gzip", error.code)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsAGzipSvgDocumentWithANonDeflateMethodAtAcquisition() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithGzipSvgDocument(compressionMethod = 0x07),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf with non-DEFLATE gzip method"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            val error = assertIs<FontError.FontDataFailure>(failure.error)
            assertEquals("font.svg.invalid-gzip", error.code)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsARawSvgDocumentThatIsNotStrictUtf8AtAcquisition() {
        val invalidUtf8Document = auditedSvgDocumentBytes().apply { this[0] = 0xFF.toByte() }
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithSvgDocumentPayload(invalidUtf8Document),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf with invalid UTF-8 SVG"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            val error = assertIs<FontError.FontDataFailure>(failure.error)
            assertEquals("font.svg.invalid-utf8", error.code)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsAGzipSvgDocumentWhoseDecodedArtworkExceedsTheConsumerLimit() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithGzipSvgDocument(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf with gzip SVG transport"),
            ),
        )
        val permissiveRequirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val restrictiveRequirements = FontAccessRequirementsSnapshot.renderable(
            listOf(
                paintProfile(
                    maxSourceBytes = 16 * 1024,
                    maxSvgCompressedDocumentBytes = 16 * 1024,
                    maxSvgDecodedDocumentBytes = 687,
                    maxSvgTotalDecodedBytes = 16 * 1024,
                ),
            ),
        )
        val face = success(catalog.resolveFace(catalog.faces.single().id, permissiveRequirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val permissiveAsset = success(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, permissiveRequirements),
            )
            try {
                assertIs<GlyphRepresentation.Paint>(
                    success(permissiveAsset.resolveGlyph(FontGlyphRequest(GlyphId(1)))),
                )
            } finally {
                permissiveAsset.close()
            }
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, restrictiveRequirements),
            )

            val error = assertIs<FontError.ResourceLimitExceeded>(failure.error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun materializesAnInkBearingGlyphOutsideTheSvgRangeFromItsTrueTypeOutline() {
        val catalog = success(
            Kalligraphie.embedded(
                liberationSansWithAnAuditedSvgTable(),
                FontSourceProvenance("LiberationSans-Regular.ttf with audited SVG-in-OpenType table"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfileWithOutlineFallback()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(36))))).paint

                assertEquals(1, paint.nodes.size)
                val outline = assertIs<GlyphPaintNode.SolidOutline>(paint.nodes.single())
                assertEquals(GlyphColor(0, 0, 0), outline.color)
                assertEquals(36, outline.outline.glyphId)
                assertEquals(2, outline.outline.contours.size)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun keepsTheColrColorGlyphForAGlyphOutsideTheSvgCoverage() {
        val catalog = success(
            Kalligraphie.embedded(
                bungeeColorWithAnAuditedSvgTable(),
                FontSourceProvenance("Bungee Color Regular with audited SVG-in-OpenType table"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfileWithColorFallback()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(1_000f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(43))))).paint

                assertEquals(
                    listOf(292, 293),
                    paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().map { it.outline.glyphId },
                )
                assertEquals(
                    listOf(GlyphColor(201, 9, 0), GlyphColor(255, 149, 128)),
                    paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>().map { it.color },
                )
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun refusesAnOutOfRangeSvgGlyphWhenTheProfileCannotRepresentItsOutlineFallback() {
        val catalog = success(
            Kalligraphie.embedded(
                liberationSansWithAnAuditedSvgTable(),
                FontSourceProvenance("LiberationSans-Regular.ttf with audited SVG-in-OpenType table"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(36))))

                assertIs<FontError.UnsupportedRepresentationProfile>(failure.error)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun selectsTheFirstProfileThatCanCertifyTheCompleteSvgRoute() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
            ),
        )
        val incompatible = PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE),
            acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(maxNodes = 4, maxReferences = 4, maxDepth = 2, maxSourceBytes = 16 * 1024),
            outlineProfile = OutlineProfile(
                maxBytes = 16 * 1024,
                maxContours = 8,
                maxPoints = 64,
                maxCompositeDepth = 1,
                maxCompositeComponents = 1,
            ),
        )
        val compatible = paintProfile()
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(incompatible, compatible))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                assertEquals(compatible, asset.key.representationProfile)
                assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1)))))
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    @Test
    fun reportsTheExactSvgTableTagWhenTheProfileSourceLimitRejectsTheRoute() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile(maxSourceBytes = 64)))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val failure = assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            )

            assertIs<FontError.ResourceLimitExceeded>(failure.error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.error.location).tag)
        } finally {
            resolver.close()
        }
    }

    @Test
    fun rejectsAnOutOfRangeGlyphInsteadOfReportingNoInk() {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureBytes(),
                FontSourceProvenance("TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(paintProfile()))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                val failure = assertIs<FontOperationResult.Failure>(asset.resolveGlyph(FontGlyphRequest(GlyphId(2))))

                assertEquals(2, assertIs<FontError.GlyphOutOfRange>(failure.error).glyphId)
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun paintProfile(
        maxSourceBytes: Int = 16 * 1024,
        maxPaintVisits: Int = 4,
        maxSvgCompressedDocumentBytes: Int = maxSourceBytes,
        maxSvgDecodedDocumentBytes: Int = maxSourceBytes,
        maxSvgTotalDecodedBytes: Int = maxSourceBytes,
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 4,
            maxReferences = 4,
            maxDepth = 2,
            maxSourceBytes = maxSourceBytes,
            maxPaths = 2,
            maxPaintVisits = maxPaintVisits,
            maxSvgCompressedDocumentBytes = maxSvgCompressedDocumentBytes,
            maxSvgDecodedDocumentBytes = maxSvgDecodedDocumentBytes,
            maxSvgTotalDecodedBytes = maxSvgTotalDecodedBytes,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 16 * 1024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 1,
            maxCompositeComponents = 1,
        ),
    )

    private fun schema2GradientProfile(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(
            GlyphPaintNodeKind.PATH,
            GlyphPaintNodeKind.GROUP,
            GlyphPaintNodeKind.SOLID,
            GlyphPaintNodeKind.LINEAR_GRADIENT,
        ),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = gradientLimits(),
        outlineProfile = svgOutlineProfile(),
        schemaVersion = 2,
        acceptedGradientExtendModes = GlyphPaintExtendMode.entries.toList(),
        acceptedGradientInterpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB),
    )

    private fun gradientProfile(
        interpolationSpaces: List<GlyphPaintInterpolationSpace> = GlyphPaintInterpolationSpace.entries.toList(),
        alphaInterpolationModes: List<GlyphPaintAlphaInterpolationMode> =
            listOf(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED),
        maxNodes: Int = 5,
        maxReferences: Int = 4,
        maxPaths: Int = 2,
        maxGradients: Int = 1,
        maxColorStops: Int = 4,
        maxClips: Int = 2,
        outlineProfile: OutlineProfile = svgOutlineProfile(),
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(
            GlyphPaintNodeKind.PATH,
            GlyphPaintNodeKind.GROUP,
            GlyphPaintNodeKind.SOLID,
            GlyphPaintNodeKind.LINEAR_GRADIENT,
            GlyphPaintNodeKind.PATH_CLIP,
        ),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = gradientLimits(
            maxNodes = maxNodes,
            maxReferences = maxReferences,
            maxPaths = maxPaths,
            maxGradients = maxGradients,
            maxColorStops = maxColorStops,
            maxClips = maxClips,
        ),
        outlineProfile = outlineProfile,
        schemaVersion = 3,
        acceptedGradientExtendModes = GlyphPaintExtendMode.entries.toList(),
        acceptedGradientInterpolationSpaces = interpolationSpaces,
        acceptedGradientAlphaInterpolationModes = alphaInterpolationModes,
    )

    private fun gradientLimits(
        maxNodes: Int = 5,
        maxReferences: Int = 4,
        maxPaths: Int = 2,
        maxGradients: Int = 2,
        maxColorStops: Int = 6,
        maxClips: Int = 2,
    ): PaintGraphLimits = PaintGraphLimits(
        maxNodes = maxNodes,
        maxReferences = maxReferences,
        maxDepth = 8,
        maxSourceBytes = 16 * 1024,
        maxPaths = maxPaths,
        maxGradients = maxGradients,
        maxColorStops = maxColorStops,
        maxClips = maxClips,
        maxPaintVisits = maxNodes,
    )

    private fun svgOutlineProfile(): OutlineProfile = OutlineProfile(
        maxBytes = 16 * 1024,
        maxContours = 8,
        maxPoints = 64,
        maxCompositeDepth = 1,
        maxCompositeComponents = 1,
    )

    private fun resolveSvgDocument(
        document: String,
        profiles: List<PaintGraphProfile>,
    ): ResolvedSvgDocument {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithSvgDocumentPayload(document.encodeToByteArray()),
                FontSourceProvenance("Twemoji fixture with Kalligraphie-authored SVG gradient document"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(profiles)
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
            try {
                return ResolvedSvgDocument(
                    profile = assertIs<PaintGraphProfile>(asset.key.representationProfile),
                    representation = success(asset.resolveGlyph(FontGlyphRequest(GlyphId(1)))),
                )
            } finally {
                asset.close()
            }
        } finally {
            resolver.close()
        }
    }

    private fun acquireSvgFailure(document: String, profile: PaintGraphProfile): FontError {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithSvgDocumentPayload(document.encodeToByteArray()),
                FontSourceProvenance("Twemoji fixture with rejected Kalligraphie-authored SVG gradient document"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(profile))
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val resolver = success(catalog.openAssetResolver())
        return try {
            assertIs<FontOperationResult.Failure>(
                instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements),
            ).error
        } finally {
            resolver.close()
        }
    }

    private fun numericSvgDocument(
        rectX: String = "0",
        rectWidth: String = "10",
        gradientX1: String = "0",
        gradientX2: String = "1",
        stopOffset: String = "0",
        stopOpacity: String = "1",
    ): String = """
        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
          <defs>
            <linearGradient id="numbers" x1="$gradientX1" x2="$gradientX2">
              <stop offset="$stopOffset" stop-opacity="$stopOpacity" stop-color="#102030"/>
            </linearGradient>
          </defs>
          <rect x="$rectX" y="0" width="$rectWidth" height="10" fill="url(#numbers)"/>
        </svg>
    """.trimIndent()

    private data class ResolvedSvgDocument(
        val profile: PaintGraphProfile,
        val representation: GlyphRepresentation,
    )

    private fun paintProfileWithOutlineFallback(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(
            GlyphPaintNodeKind.SOLID_OUTLINE,
            GlyphPaintNodeKind.PATH,
            GlyphPaintNodeKind.GROUP,
        ),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 4,
            maxReferences = 4,
            maxDepth = 2,
            maxSourceBytes = 16 * 1024,
            maxPaths = 2,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 16 * 1024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 1,
            maxCompositeComponents = 1,
        ),
    )

    private fun paintProfileWithColorFallback(): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(
            GlyphPaintNodeKind.SOLID_OUTLINE,
            GlyphPaintNodeKind.PATH,
            GlyphPaintNodeKind.GROUP,
        ),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 4,
            maxReferences = 4,
            maxDepth = 2,
            maxSourceBytes = 100_000,
            maxPaths = 2,
            maxPalettes = 9,
            maxPaletteEntries = 2,
            maxColorRecords = 16,
            maxDecodedPaletteBytes = 72,
            maxBaseGlyphRecords = 288,
            maxLayerRecords = 576,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 1_024,
            maxPoints = 65_536,
            maxCompositeDepth = 16,
            maxCompositeComponents = 256,
        ),
    )

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64")
            ?.bufferedReader()
            ?.use { reader -> Base64.getMimeDecoder().decode(reader.readText()) }
            ?: error("Missing SVG-in-OpenType fixture resource.")

    /**
     * Builds a valid test-only SFNT from two audited fixtures. The SVG table is byte-for-byte from
     * the SVG-in-OpenType specimen and covers only glyph 1; Liberation Sans glyph 36 (`A`) is
     * therefore deliberately outside the SVG range. Its two contours are independently audited in
     * the Liberation Sans provenance record.
     */
    private fun liberationSansWithAnAuditedSvgTable(): ByteArray =
        sfntWithAdditionalTable(
            source = liberationSansFixtureBytes(),
            tag = "SVG ",
            bytes = svgTableBytes(),
        )

    /**
     * Builds a valid test-only hybrid from audited Bungee Color and SVG-in-OpenType fixtures.
     * SVG covers glyph 1 only; Bungee glyph 43 (`A`) remains outside that coverage and has the
     * COLR v0 layers 292 and 293 specified in the Bungee Color provenance record.
     */
    private fun bungeeColorWithAnAuditedSvgTable(): ByteArray =
        sfntWithAdditionalTable(
            source = bungeeColorFixtureBytes(),
            tag = "SVG ",
            bytes = svgTableBytes(),
        )

    private fun liberationSansFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "Missing Liberation Sans fixture resource."
        }.use { stream -> stream.readBytes() }

    private fun bungeeColorFixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/bungee-color/BungeeColor-Regular.ttf")) {
            "Missing Bungee Color fixture resource."
        }.use { stream -> stream.readBytes() }

    private fun svgTableBytes(): ByteArray {
        val source = fixtureBytes()
        val tableCount = readUInt16(source, 4)
        repeat(tableCount) { index ->
            val recordOffset = 12 + index * SFNT_TABLE_RECORD_BYTES
            if (readTag(source, recordOffset) == "SVG ") {
                val offset = readUInt32(source, recordOffset + 8)
                val length = readUInt32(source, recordOffset + 12)
                return source.copyOfRange(offset, offset + length)
            }
        }
        error("The SVG-in-OpenType fixture has no SVG table.")
    }

    private fun fixtureWithGzipSvgDocument(
        corruptTrailer: Boolean = false,
        setReservedFlag: Boolean = false,
        compressionMethod: Byte? = null,
    ): ByteArray {
        val gzipDocument = deterministicGzip(auditedSvgDocumentBytes())
        if (compressionMethod != null) {
            gzipDocument[GZIP_COMPRESSION_METHOD_OFFSET] = compressionMethod
        }
        if (setReservedFlag) {
            gzipDocument[GZIP_FLAGS_OFFSET] =
                (gzipDocument[GZIP_FLAGS_OFFSET].toInt() or GZIP_RESERVED_FLAG).toByte()
        }
        if (corruptTrailer) {
            val crcOffset = gzipDocument.size - GZIP_TRAILER_BYTES
            gzipDocument[crcOffset] = (gzipDocument[crcOffset].toInt() xor 1).toByte()
        }
        return fixtureWithSvgDocumentPayload(gzipDocument)
    }

    private fun auditedSvgDocumentBytes(): ByteArray {
        val table = svgTableBytes()
        val (documentStart, documentEnd) = auditedSvgDocumentRange(table)
        return table.copyOfRange(documentStart, documentEnd)
    }

    private fun fixtureWithSvgDocumentPayload(document: ByteArray): ByteArray {
        val source = fixtureBytes()
        val table = svgTableBytes()
        val (documentStart, documentEnd) = auditedSvgDocumentRange(table)
        val documentLength = documentEnd - documentStart
        val documentListOffset = readUInt32(table, 2)
        val recordOffset = documentListOffset + 2
        val replacementTable = ByteArray(table.size - documentLength + document.size)
        table.copyInto(replacementTable, endIndex = documentStart)
        document.copyInto(replacementTable, destinationOffset = documentStart)
        table.copyInto(
            replacementTable,
            destinationOffset = documentStart + document.size,
            startIndex = documentEnd,
        )
        writeUInt32(replacementTable, recordOffset + 8, document.size.toUInt())
        return sfntWithReplacedTable(source, "SVG ", replacementTable)
    }

    private fun auditedSvgDocumentRange(table: ByteArray): Pair<Int, Int> {
        val documentListOffset = readUInt32(table, 2)
        check(readUInt16(table, documentListOffset) == 1) { "The audited SVG fixture must contain one document record." }
        val recordOffset = documentListOffset + 2
        val documentOffset = readUInt32(table, recordOffset + 4)
        val documentLength = readUInt32(table, recordOffset + 8)
        val documentStart = documentListOffset + documentOffset
        val documentEnd = documentStart + documentLength
        check(documentStart >= 0 && documentEnd >= documentStart && documentEnd <= table.size) {
            "The audited SVG document range must be valid."
        }
        return documentStart to documentEnd
    }

    private fun deterministicGzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { gzip -> gzip.write(bytes) }
        output.toByteArray()
    }

    private fun sfntWithAdditionalTable(source: ByteArray, tag: String, bytes: ByteArray): ByteArray {
        val sourceTables = readTables(source)
        check(sourceTables.none { it.tag == tag }) { "Source SFNT already has a $tag table." }
        return rebuildSfnt(source, sourceTables + SfntTable(tag, bytes, openTypeChecksum(bytes)))
    }

    private fun sfntWithReplacedTable(source: ByteArray, tag: String, bytes: ByteArray): ByteArray {
        val sourceTables = readTables(source)
        check(sourceTables.count { it.tag == tag } == 1) { "Source SFNT must contain exactly one $tag table." }
        return rebuildSfnt(
            source,
            sourceTables.map { table ->
                if (table.tag == tag) SfntTable(tag, bytes, openTypeChecksum(bytes)) else table
            },
        )
    }

    private fun rebuildSfnt(source: ByteArray, sourceTables: List<SfntTable>): ByteArray {
        val tables = sourceTables.sortedBy { it.tag }
        val headerSize = SFNT_HEADER_BYTES + tables.size * SFNT_TABLE_RECORD_BYTES
        val offsets = ArrayList<Int>(tables.size)
        var totalSize = headerSize
        tables.forEach { table ->
            totalSize = alignToWord(totalSize)
            offsets += totalSize
            totalSize += table.bytes.size
        }
        totalSize = alignToWord(totalSize)

        val result = ByteArray(totalSize)
        source.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = 4)
        writeUInt16(result, 4, tables.size)
        val largestPowerOfTwo = Integer.highestOneBit(tables.size)
        writeUInt16(result, 6, largestPowerOfTwo * SFNT_TABLE_RECORD_BYTES)
        writeUInt16(result, 8, Integer.numberOfTrailingZeros(largestPowerOfTwo))
        writeUInt16(result, 10, tables.size * SFNT_TABLE_RECORD_BYTES - largestPowerOfTwo * SFNT_TABLE_RECORD_BYTES)

        tables.forEachIndexed { index, table ->
            val recordOffset = SFNT_HEADER_BYTES + index * SFNT_TABLE_RECORD_BYTES
            writeTag(result, recordOffset, table.tag)
            writeUInt32(result, recordOffset + 4, table.checksum)
            writeUInt32(result, recordOffset + 8, offsets[index].toUInt())
            writeUInt32(result, recordOffset + 12, table.bytes.size.toUInt())
            table.bytes.copyInto(result, destinationOffset = offsets[index])
        }

        val headOffset = offsets[tables.indexOfFirst { it.tag == "head" }]
        writeUInt32(result, headOffset + HEAD_CHECKSUM_ADJUSTMENT_OFFSET, 0u)
        writeUInt32(result, headOffset + HEAD_CHECKSUM_ADJUSTMENT_OFFSET, OPEN_TYPE_CHECKSUM_MAGIC - openTypeChecksum(result))
        return result
    }

    private fun readTables(source: ByteArray): List<SfntTable> {
        val tableCount = readUInt16(source, 4)
        return List(tableCount) { index ->
            val recordOffset = SFNT_HEADER_BYTES + index * SFNT_TABLE_RECORD_BYTES
            val offset = readUInt32(source, recordOffset + 8)
            val length = readUInt32(source, recordOffset + 12)
            require(offset >= 0 && length >= 0 && offset <= source.size - length) { "Invalid SFNT table range in fixture." }
            SfntTable(
                tag = readTag(source, recordOffset),
                bytes = source.copyOfRange(offset, offset + length),
                checksum = readUInt32(source, recordOffset + 4).toUInt(),
            )
        }
    }

    private fun readTag(bytes: ByteArray, offset: Int): String =
        CharArray(4) { index -> bytes[offset + index].toInt().toChar() }.concatToString()

    private fun writeTag(bytes: ByteArray, offset: Int, tag: String) {
        require(tag.length == 4) { "An SFNT tag must have exactly four characters." }
        tag.forEachIndexed { index, character -> bytes[offset + index] = character.code.toByte() }
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: UInt) {
        bytes[offset] = (value shr 24).toByte()
        bytes[offset + 1] = (value shr 16).toByte()
        bytes[offset + 2] = (value shr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun openTypeChecksum(bytes: ByteArray): UInt {
        var checksum = 0u
        bytes.indices.step(4).forEach { offset ->
            val word = (bytes[offset].toUInt() and 0xffu) shl 24 or
                ((bytes.getOrElse(offset + 1) { 0 }.toUInt() and 0xffu) shl 16) or
                ((bytes.getOrElse(offset + 2) { 0 }.toUInt() and 0xffu) shl 8) or
                (bytes.getOrElse(offset + 3) { 0 }.toUInt() and 0xffu)
            checksum += word
        }
        return checksum
    }

    private fun alignToWord(value: Int): Int = (value + 3) and 3.inv()

    private data class SfntTable(
        val tag: String,
        val bytes: ByteArray,
        val checksum: UInt,
    )

    private companion object {
        const val SFNT_HEADER_BYTES: Int = 12
        const val SFNT_TABLE_RECORD_BYTES: Int = 16
        const val HEAD_CHECKSUM_ADJUSTMENT_OFFSET: Int = 8
        const val GZIP_TRAILER_BYTES: Int = 8
        const val GZIP_COMPRESSION_METHOD_OFFSET: Int = 2
        const val GZIP_FLAGS_OFFSET: Int = 3
        const val GZIP_RESERVED_FLAG: Int = 0x20
        val OPEN_TYPE_CHECKSUM_MAGIC: UInt = 0xB1B0AFBAu
    }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
