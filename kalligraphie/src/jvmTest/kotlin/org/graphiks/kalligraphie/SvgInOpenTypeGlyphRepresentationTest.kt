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
import org.graphiks.kalligraphie.api.GlyphAffineTransform
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
    fun translucentSolidPathUsesExactOpacityInsideTheShapeAndExternalClips() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path d="M1 1 L5 1 L1 6 Z"/></clipPath></defs>
              <path d="M0 0 L4 0 L0 4 Z" fill="#123456" fill-opacity="0.5" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(
                    gradientProfile(
                        maxNodes = 3,
                        maxReferences = 2,
                        maxPaths = 2,
                        maxGradients = 0,
                        maxColorStops = 0,
                        maxClips = 2,
                    ),
                ),
            ).representation,
        ).paint

        assertEquals(GlyphPaintNode.Solid(GlyphColor(0x12, 0x34, 0x56), 0.5), paint.nodes[0])
        val shapeClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, shapeClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                GlyphPaintPathCommand.LineTo(4.0, 0.0),
                GlyphPaintPathCommand.LineTo(0.0, 4.0),
                GlyphPaintPathCommand.Close,
            ),
            shapeClip.path.commands,
        )
        val outerClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[2])
        assertEquals(1, outerClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(1.0, 1.0),
                GlyphPaintPathCommand.LineTo(5.0, 1.0),
                GlyphPaintPathCommand.LineTo(1.0, 6.0),
                GlyphPaintPathCommand.Close,
            ),
            outerClip.path.commands,
        )
    }

    @Test
    fun gradientFillOpacityDerivesStopsPerUseWithoutMutatingTheSharedDefinition() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="gradient">
                  <stop offset="0" stop-color="#123456" stop-opacity="40%"/>
                  <stop offset="1" stop-color="#ABCDEF" stop-opacity="80%"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="4" height="5" fill="url(#gradient)" fill-opacity="25%"/>
              <rect x="10" y="0" width="4" height="5" fill="url(#gradient)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(
                    gradientProfile(
                        maxNodes = 5,
                        maxReferences = 4,
                        maxPaths = 2,
                        maxGradients = 2,
                        maxColorStops = 4,
                        maxClips = 2,
                    ),
                ),
            ).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(0x12, 0x34, 0x56), 0.1),
                GlyphPaintColorStop(1.0, GlyphColor(0xAB, 0xCD, 0xEF), 0.2),
            ),
            assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0]).colorLine.colorStops,
        )
        assertEquals(
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(0x12, 0x34, 0x56), 0.4),
                GlyphPaintColorStop(1.0, GlyphColor(0xAB, 0xCD, 0xEF), 0.8),
            ),
            assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[2]).colorLine.colorStops,
        )
        assertEquals(GlyphPaintNode.Group(listOf(1, 3)), paint.nodes[4])
    }

    @Test
    fun fillOpacityDefaultsToOpaqueAcceptsPercentagesAndClampsFiniteValues() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <path d="M0 0 L2 0 L0 2 Z" fill="#010203"/>
              <path d="M3 0 L5 0 L3 2 Z" fill="#112233" fill-opacity="50%"/>
              <rect x="6" y="0" width="2" height="2" fill="#445566" fill-opacity="-0.25"/>
              <rect x="9" y="0" width="2" height="2" fill="#778899" fill-opacity="1.5"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(
                    gradientProfile(
                        maxNodes = 8,
                        maxReferences = 8,
                        maxPaths = 6,
                        maxGradients = 0,
                        maxColorStops = 0,
                        maxClips = 3,
                    ),
                ),
            ).representation,
        ).paint

        assertEquals(GlyphColor(0x01, 0x02, 0x03), assertIs<GlyphPaintNode.Path>(paint.nodes[0]).color)
        assertEquals(GlyphPaintNode.Solid(GlyphColor(0x11, 0x22, 0x33), 0.5), paint.nodes[1])
        assertEquals(1, assertIs<GlyphPaintNode.PathClip>(paint.nodes[2]).paint)
        assertEquals(GlyphColor(0x77, 0x88, 0x99), assertIs<GlyphPaintNode.Path>(paint.nodes[3]).color)
        assertEquals(GlyphPaintNode.Group(listOf(0, 2, 3)), paint.nodes[4])
    }

    @Test
    fun malformedFillOpacityReturnsTypedInvalidDataEvenWhenTheFillIsNone() {
        val shapes = listOf(
            """<path d="M0 0 L1 0 L0 1 Z" fill="#123456" fill-opacity="bad"/>""",
            """<path d="M0 0 L1 0 L0 1 Z" fill="none" fill-opacity="50 %"/>""",
            """<rect width="1" height="1" fill="#123456" fill-opacity="NaN"/>""",
            """<rect width="1" height="1" fill="none" fill-opacity="1e999"/>""",
        )

        for (shape in shapes) {
            val error = assertIs<FontError.FontDataFailure>(
                acquireSvgFailure(
                    """
                        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                          $shape
                        </svg>
                    """.trimIndent(),
                    gradientProfile(),
                ),
            )

            assertEquals("font.svg.invalid-fill-opacity", error.code)
        }
    }

    @Test
    fun clipChildrenDoNotAcceptFillOpacity() {
        val children = listOf(
            """<path d="M0 0 L1 0 L0 1 Z" fill-opacity="0.5"/>""",
            """<rect width="1" height="1" fill-opacity="0.5"/>""",
        )

        for (child in children) {
            assertIs<FontError.UnsupportedRepresentationProfile>(
                acquireSvgFailure(
                    """
                        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                          <defs><clipPath id="cut">$child</clipPath></defs>
                        </svg>
                    """.trimIndent(),
                    gradientProfile(),
                ),
            )
        }
    }

    @Test
    fun zeroSolidFillOpacityValidatesItsShapeAndRequiredNodesBeforeOmission() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <path d="M0 0 L4 0 L0 4 Z" fill="#123456" fill-opacity="0"/>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(resolveSvgDocument(document, listOf(gradientProfile())).representation)
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(
                document,
                gradientProfile(
                    nodeKinds = listOf(GlyphPaintNodeKind.PATH_CLIP, GlyphPaintNodeKind.GROUP),
                ),
            ),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(
                document,
                gradientProfile(
                    nodeKinds = listOf(GlyphPaintNodeKind.SOLID, GlyphPaintNodeKind.GROUP),
                ),
            ),
        )
        val invalidShape = document.replace("M0 0 L4 0 L0 4 Z", "M0 0 Lbad")
        assertEquals(
            "font.svg.path-number",
            assertIs<FontError.FontDataFailure>(acquireSvgFailure(invalidShape, gradientProfile())).code,
        )
    }

    @Test
    fun zeroGradientFillOpacityValidatesTheReferencedPaintBeforeOmission() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="gradient" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0" stop-color="#123456"/>
                  <stop offset="1" stop-color="#ABCDEF"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="4" height="5" fill="url(#gradient)" fill-opacity="0"/>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(resolveSvgDocument(document, listOf(gradientProfile())).representation)
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(
                document,
                gradientProfile(
                    nodeKinds = listOf(
                        GlyphPaintNodeKind.SOLID,
                        GlyphPaintNodeKind.GROUP,
                        GlyphPaintNodeKind.PATH_CLIP,
                    ),
                ),
            ),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(document.replace("url(#gradient)", "url(#missing)"), gradientProfile()),
        )
        val invalidGradient = document
            .replace("x1=\"0\"", "x1=\"1e308\"")
            .replace("x2=\"1\"", "x2=\"-1e308\"")
        assertEquals(
            "font.svg.invalid-gradient",
            assertIs<FontError.FontDataFailure>(acquireSvgFailure(invalidGradient, gradientProfile())).code,
        )
    }

    @Test
    fun zeroTranslucentSolidAccountsForShapeAndExternalClipNodesBeforeOmission() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path d="M1 1 L5 1 L1 6 Z"/></clipPath></defs>
              <rect x="0" y="0" width="4" height="5" fill="#123456" fill-opacity="0" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()
        fun profile(
            maxNodes: Int = 3,
            maxReferences: Int = 2,
            maxDepth: Int = 3,
            maxPaintVisits: Int = 3,
            maxPaths: Int = 2,
            maxClips: Int = 2,
        ): PaintGraphProfile = gradientProfile(
            maxNodes = maxNodes,
            maxReferences = maxReferences,
            maxDepth = maxDepth,
            maxPaintVisits = maxPaintVisits,
            maxPaths = maxPaths,
            maxGradients = 0,
            maxColorStops = 0,
            maxClips = maxClips,
        )

        assertIs<GlyphRepresentation.Empty>(resolveSvgDocument(document, listOf(profile())).representation)
        val limitedProfiles = listOf(
            profile(maxNodes = 2),
            profile(maxReferences = 1),
            profile(maxDepth = 2),
            profile(maxPaintVisits = 2),
            profile(maxPaths = 1),
            profile(maxClips = 1),
        )
        for (limitedProfile in limitedProfiles) {
            assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, limitedProfile))
        }
    }

    @Test
    fun translucentSolidDoesNotRequireTheOpaquePathNodeKind() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <rect x="0" y="0" width="4" height="5" fill="#123456" fill-opacity="0.5"/>
            </svg>
        """.trimIndent()
        val profile = gradientProfile(
            nodeKinds = listOf(
                GlyphPaintNodeKind.SOLID,
                GlyphPaintNodeKind.PATH_CLIP,
                GlyphPaintNodeKind.GROUP,
            ),
            maxNodes = 2,
            maxReferences = 1,
            maxPaths = 1,
            maxGradients = 0,
            maxColorStops = 0,
            maxClips = 1,
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(profile)).representation,
        ).paint

        assertEquals(GlyphPaintNode.Solid(GlyphColor(0x12, 0x34, 0x56), 0.5), paint.nodes[0])
        assertEquals(0, assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).paint)
    }

    @Test
    fun rectangularClipChildComposesItsGeometryForEveryReference() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <clipPath id="cut" transform="translate(10 20)">
                  <rect x="1" y="2" width="3" height="4" transform="scale(2 3)"/>
                </clipPath>
              </defs>
              <g transform="translate(100 200)">
                <path d="M0 0 L1 0 L0 1 Z" fill="#102030" clip-path="url(#cut)"/>
              </g>
              <g transform="translate(-10 30) scale(3 2)">
                <path d="M0 0 L1 0 L0 1 Z" fill="#405060" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(
                    clipProfile(
                        maxNodes = 5,
                        maxReferences = 4,
                        maxPaintVisits = 5,
                        maxPaths = 4,
                        maxClips = 2,
                    ),
                ),
            ).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(112.0, 226.0),
                GlyphPaintPathCommand.LineTo(118.0, 226.0),
                GlyphPaintPathCommand.LineTo(118.0, 238.0),
                GlyphPaintPathCommand.LineTo(112.0, 238.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(26.0, 82.0),
                GlyphPaintPathCommand.LineTo(44.0, 82.0),
                GlyphPaintPathCommand.LineTo(44.0, 106.0),
                GlyphPaintPathCommand.LineTo(26.0, 106.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[3]).path.commands,
        )
    }

    @Test
    fun rectangularClipChildDefaultsItsOmittedOriginToZero() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><rect width="3" height="4"/></clipPath></defs>
              <path d="M0 0 L1 0 L0 1 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(clipProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                GlyphPaintPathCommand.LineTo(3.0, 0.0),
                GlyphPaintPathCommand.LineTo(3.0, 4.0),
                GlyphPaintPathCommand.LineTo(0.0, 4.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
    }

    @Test
    fun rectangularClipChildRejectsInvalidDimensions() {
        val documents = listOf(
            "<rect x=\"0\" y=\"0\" width=\"-1\" height=\"1\"/>",
            "<rect x=\"0\" y=\"0\" width=\"1\" height=\"-1\"/>",
            "<rect x=\"0\" y=\"0\" width=\"-1e-9999\" height=\"1\"/>",
            "<rect x=\"0\" y=\"0\" width=\"1\" height=\"-1e-9999\"/>",
            "<rect x=\"0\" y=\"0\" height=\"1\"/>",
            "<rect x=\"0\" y=\"0\" width=\"1\"/>",
            "<rect x=\"0\" y=\"0\" width=\"bad\" height=\"1\"/>",
            "<rect x=\"0\" y=\"0\" width=\"1\" height=\"NaN\"/>",
        )

        for (rectangle in documents) {
            val error = assertIs<FontError.FontDataFailure>(
                acquireSvgFailure(
                    """
                        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                          <defs><clipPath id="cut">$rectangle</clipPath></defs>
                        </svg>
                    """.trimIndent(),
                    clipProfile(),
                ),
            )

            assertEquals("font.svg.invalid-rect", error.code)
        }
    }

    @Test
    fun zeroSizedRectangularClipProducesEmptyOnlyAfterClipValidation() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><rect width="0" height="4"/></clipPath></defs>
              <path d="M0 0 L1 0 L0 1 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(resolveSvgDocument(document, listOf(clipProfile())).representation)
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(
                document,
                clipProfile(nodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP)),
            ),
        )
    }

    @Test
    fun rectangularClipChildRejectsRoundedCornersAndMultipleChildren() {
        val documents = listOf(
            "<rect width=\"3\" height=\"4\" rx=\"1\"/>",
            "<rect width=\"3\" height=\"4\" ry=\"1\"/>",
            "<rect width=\"3\" height=\"4\"/><rect width=\"3\" height=\"4\"/>",
            "<path d=\"M0 0 L1 0 L0 1 Z\"/><rect width=\"3\" height=\"4\"/>",
        )

        for (children in documents) {
            assertIs<FontError.UnsupportedRepresentationProfile>(
                acquireSvgFailure(
                    """
                        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                          <defs><clipPath id="cut">$children</clipPath></defs>
                        </svg>
                    """.trimIndent(),
                    clipProfile(),
                ),
            )
        }
    }

    @Test
    fun unusedRectangularClipStillHonorsTheExactOutlinePointLimit() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="unused"><rect width="4" height="4"/></clipPath></defs>
            </svg>
        """.trimIndent()

        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(document, clipProfile(maxOutlinePoints = 3)),
        )
    }

    @Test
    fun unusedRectangularClipTransformConsumesTheAuthoredTransformBudget() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><rect width="3" height="4" transform="translate(4 5) scale(2)"/></clipPath></defs>
            </svg>
        """.trimIndent()

        assertIs<FontError.ResourceLimitExceeded>(
            acquireSvgFailure(document, clipProfile(maxSvgTransformOperations = 1)),
        )
    }

    @Test
    fun clipChildTransformComposesPerReferenceWithoutChangingThePaintedShapes() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <clipPath id="cut" transform="scale(2 3)">
                  <path transform="translate(4 5)" d="M1 2 L3 2 L1 4 Z"/>
                </clipPath>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <path d="M1 2 L3 2 L1 4 Z" fill="#102030" clip-path="url(#cut)"/>
              </g>
              <g transform="translate(-10 30) scale(3 2)">
                <path d="M1 2 L3 2 L1 4 Z" fill="#405060" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(
                    clipProfile(
                        maxNodes = 5,
                        maxReferences = 4,
                        maxPaths = 4,
                        maxClips = 2,
                        maxPaintVisits = 5,
                    ),
                ),
            ).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 26.0),
                GlyphPaintPathCommand.LineTo(16.0, 26.0),
                GlyphPaintPathCommand.LineTo(12.0, 32.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes[0]).path.commands,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(30.0, 83.0),
                GlyphPaintPathCommand.LineTo(38.0, 83.0),
                GlyphPaintPathCommand.LineTo(30.0, 101.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(-7.0, 34.0),
                GlyphPaintPathCommand.LineTo(-1.0, 34.0),
                GlyphPaintPathCommand.LineTo(-7.0, 38.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes[2]).path.commands,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(20.0, 72.0),
                GlyphPaintPathCommand.LineTo(32.0, 72.0),
                GlyphPaintPathCommand.LineTo(20.0, 84.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[3]).path.commands,
        )
    }

    @Test
    fun malformedClipChildTransformIsInvalidFontData() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path transform="scale(" d="M0 0 L1 0 L0 1 Z"/></clipPath></defs>
              <path d="M0 0 L1 0 L0 1 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        val error = assertIs<FontError.FontDataFailure>(acquireSvgFailure(document, clipProfile()))

        assertEquals("font.svg.invalid-transform", error.code)
    }

    @Test
    fun unusedClipChildTransformStillConsumesTheAuthoredTransformBudget() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path transform="translate(4 5) scale(2)" d="M0 0 L1 0 L0 1 Z"/></clipPath></defs>
            </svg>
        """.trimIndent()

        assertIs<FontError.ResourceLimitExceeded>(
            acquireSvgFailure(document, clipProfile(maxSvgTransformOperations = 1)),
        )
    }

    @Test
    fun unusedClipPathStillHonorsTheExactOutlinePointLimit() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="unused"><path d="M0 0 L4 0 L4 4 L0 4 Z"/></clipPath></defs>
            </svg>
        """.trimIndent()

        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(document, clipProfile(maxOutlinePoints = 3)),
        )
    }

    @Test
    fun unusedClipPathRejectsOverflowFromRelativeCoordinates() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="unused"><path d="M1e308 0 l1e308 0 L0 1 Z"/></clipPath></defs>
            </svg>
        """.trimIndent()

        val error = assertIs<FontError.FontDataFailure>(acquireSvgFailure(document, clipProfile()))

        assertEquals("font.svg.invalid-path", error.code)
    }

    @Test
    fun singularClipChildTransformOmitsPaintAfterValidatingTheReferenceAndProfile() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path transform="scale(0 1)" d="M0 0 L1 0 L0 1 Z"/></clipPath></defs>
              <path d="M0 0 L1 0 L0 1 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(
            resolveSvgDocument(document, listOf(clipProfile())).representation,
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(
                document,
                clipProfile(nodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP)),
            ),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(document.replace("url(#cut)", "url(#missing)"), clipProfile()),
        )
    }

    @Test
    fun unlistedClipChildAttributesRemainUnsupported() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path data-extra="no" d="M0 0 L1 0 L0 1 Z"/></clipPath></defs>
              <path d="M0 0 L1 0 L0 1 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, clipProfile()))
    }

    @Test
    fun clipTransformComposesInsideTheReferencingTransformWithoutMovingTheSolidShape() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <clipPath id="cut" transform="scale(2 3)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath>
              </defs>
              <g transform="translate(10 20)">
                <path d="M1 2 L4 2 L2 9 Z" fill="#102030" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(clipProfile())).representation,
        ).paint

        assertEquals(2, paint.nodes.size)
        assertEquals(1, paint.rootNode)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(11.0, 22.0),
                GlyphPaintPathCommand.LineTo(14.0, 22.0),
                GlyphPaintPathCommand.LineTo(12.0, 29.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes[0]).path.commands,
        )
        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, clip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 26.0),
                GlyphPaintPathCommand.LineTo(18.0, 26.0),
                GlyphPaintPathCommand.LineTo(14.0, 38.0),
                GlyphPaintPathCommand.Close,
            ),
            clip.path.commands,
        )
    }

    @Test
    fun clipTransformSupportsEveryAuthoredTransformFormInListOrder() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <clipPath id="cut" transform="translate(10 20) scale(2 3) rotate(90) skewX(45) skewY(-45) matrix(1 0 0 1 4 5)">
                  <path d="M0 0 L1 0 L0 1 Z"/>
                </clipPath>
              </defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(clipProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(8.0, 35.0),
                GlyphPaintPathCommand.LineTo(10.0, 35.0),
                GlyphPaintPathCommand.LineTo(6.0, 38.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
    }

    @Test
    fun clipTransformChangesOnlyTheOuterClipAroundLinearGradientPaint() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="paint" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
                <clipPath id="cut" transform="scale(2 3)"><path d="M2 3 L7 3 L6 8 Z"/></clipPath>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <rect x="1" y="2" width="4" height="6" fill="url(#paint)" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile(maxNodes = 3, maxReferences = 2))).representation,
        ).paint

        assertEquals(2, paint.rootNode)
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        assertEquals(GlyphPaintPoint(10.0, 20.0), gradient.p0)
        assertEquals(GlyphPaintPoint(30.0, 20.0), gradient.p1)
        assertEquals(GlyphPaintPoint(10.0, 50.0), gradient.p2)
        val shapeClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, shapeClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 26.0),
                GlyphPaintPathCommand.LineTo(20.0, 26.0),
                GlyphPaintPathCommand.LineTo(20.0, 44.0),
                GlyphPaintPathCommand.LineTo(12.0, 44.0),
                GlyphPaintPathCommand.Close,
            ),
            shapeClip.path.commands,
        )
        val outerClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[2])
        assertEquals(1, outerClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(18.0, 47.0),
                GlyphPaintPathCommand.LineTo(38.0, 47.0),
                GlyphPaintPathCommand.LineTo(34.0, 92.0),
                GlyphPaintPathCommand.Close,
            ),
            outerClip.path.commands,
        )
    }

    @Test
    fun solidPathClipKeepsPaintInsideTheLiteralTransformedClipGeometry() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <clipPath id="cut"><path d="M0 1 L5 1 L3 6 Z"/></clipPath>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <path d="M1 2 L4 2 L2 9 Z" fill="#102030" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint

        assertEquals(2, paint.nodes.size)
        assertEquals(1, paint.rootNode)
        val shape = assertIs<GlyphPaintNode.Path>(paint.nodes[0])
        assertEquals(GlyphColor(16, 32, 48), shape.color)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 26.0),
                GlyphPaintPathCommand.LineTo(18.0, 26.0),
                GlyphPaintPathCommand.LineTo(14.0, 47.0),
                GlyphPaintPathCommand.Close,
            ),
            shape.path.commands,
        )
        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, clip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(10.0, 23.0),
                GlyphPaintPathCommand.LineTo(20.0, 23.0),
                GlyphPaintPathCommand.LineTo(16.0, 38.0),
                GlyphPaintPathCommand.Close,
            ),
            clip.path.commands,
        )
    }

    @Test
    fun linearGradientClipKeepsTheShapeClipInsideTheLiteralOuterClip() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="paint" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
                <clipPath id="cut"><path d="M2 3 L7 3 L6 8 Z"/></clipPath>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <rect x="1" y="2" width="4" height="6" fill="url(#paint)" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile(maxNodes = 3, maxReferences = 2))).representation,
        ).paint

        assertEquals(2, paint.rootNode)
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        assertEquals(GlyphPaintPoint(10.0, 20.0), gradient.p0)
        assertEquals(GlyphPaintPoint(30.0, 20.0), gradient.p1)
        assertEquals(GlyphPaintPoint(10.0, 50.0), gradient.p2)
        val shapeClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, shapeClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 26.0),
                GlyphPaintPathCommand.LineTo(20.0, 26.0),
                GlyphPaintPathCommand.LineTo(20.0, 44.0),
                GlyphPaintPathCommand.LineTo(12.0, 44.0),
                GlyphPaintPathCommand.Close,
            ),
            shapeClip.path.commands,
        )
        val outerClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[2])
        assertEquals(1, outerClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(14.0, 29.0),
                GlyphPaintPathCommand.LineTo(24.0, 29.0),
                GlyphPaintPathCommand.LineTo(22.0, 44.0),
                GlyphPaintPathCommand.Close,
            ),
            outerClip.path.commands,
        )
    }

    @Test
    fun radialGradientClipKeepsItsTransformBelowBothLiteralPathClips() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <radialGradient id="paint" gradientUnits="userSpaceOnUse" cx="10" cy="20" r="5">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </radialGradient>
                <clipPath id="cut"><path d="M2 3 L7 3 L6 8 Z"/></clipPath>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <path d="M1 2 L4 2 L2 9 Z" fill="url(#paint)" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()
        val profile = radialGradientProfile(
            maxNodes = 4,
            maxReferences = 3,
            maxDepth = 4,
            maxPaintVisits = 4,
            maxPaths = 2,
            maxClips = 2,
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(profile)).representation,
        ).paint

        assertEquals(3, paint.rootNode)
        val gradient = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])
        assertEquals(GlyphPaintPoint(10.0, 20.0), gradient.c0)
        assertEquals(GlyphPaintPoint(10.0, 20.0), gradient.c1)
        assertEquals(5.0, gradient.radius1)
        val transform = assertIs<GlyphPaintNode.Transform>(paint.nodes[1])
        assertEquals(0, transform.paint)
        assertEquals(GlyphAffineTransform(2.0, 0.0, 0.0, 3.0, 10.0, 20.0), transform.matrix)
        val shapeClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[2])
        assertEquals(1, shapeClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 26.0),
                GlyphPaintPathCommand.LineTo(18.0, 26.0),
                GlyphPaintPathCommand.LineTo(14.0, 47.0),
                GlyphPaintPathCommand.Close,
            ),
            shapeClip.path.commands,
        )
        val outerClip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[3])
        assertEquals(2, outerClip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(14.0, 29.0),
                GlyphPaintPathCommand.LineTo(24.0, 29.0),
                GlyphPaintPathCommand.LineTo(22.0, 44.0),
                GlyphPaintPathCommand.Close,
            ),
            outerClip.path.commands,
        )
        assertIs<FontError.ResourceLimitExceeded>(
            acquireSvgFailure(
                document,
                radialGradientProfile(
                    maxNodes = 4,
                    maxReferences = 3,
                    maxDepth = 3,
                    maxPaintVisits = 4,
                    maxPaths = 2,
                    maxClips = 2,
                ),
            ),
        )
    }

    @Test
    fun reusedClipDefinitionMaterializesLiteralGeometryForEachReferenceTransform() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut" transform="translate(5 7)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>
              <g transform="translate(10 0)">
                <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
              </g>
              <g transform="scale(2 3)">
                <path d="M0 0 L3 0 L0 4 Z" fill="#405060" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()
        val profile = clipProfile(
            maxNodes = 5,
            maxReferences = 4,
            maxDepth = 3,
            maxPaintVisits = 5,
            maxPaths = 4,
            maxClips = 2,
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(profile)).representation,
        ).paint

        assertEquals(4, paint.rootNode)
        assertEquals(listOf(1, 3), assertIs<GlyphPaintNode.Group>(paint.nodes[4]).children)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(16.0, 9.0),
                GlyphPaintPathCommand.LineTo(19.0, 9.0),
                GlyphPaintPathCommand.LineTo(17.0, 13.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 27.0),
                GlyphPaintPathCommand.LineTo(18.0, 27.0),
                GlyphPaintPathCommand.LineTo(14.0, 39.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[3]).path.commands,
        )
    }

    @Test
    fun clipTransformOperationsAreChargedOnceAtDefinitionIncludingUnusedDefinitions() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <clipPath id="cut" transform="translate(5 7)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath>
                <clipPath id="unused" transform="scale(2 3)"><path d="M0 0 L1 0 L0 1 Z"/></clipPath>
              </defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
              <path d="M5 0 L8 0 L5 4 Z" fill="#405060" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()
        val exact = clipProfile(
            maxNodes = 5,
            maxReferences = 4,
            maxPaintVisits = 5,
            maxPaths = 4,
            maxClips = 2,
            maxSvgTransformOperations = 2,
        )

        assertIs<GlyphRepresentation.Paint>(resolveSvgDocument(document, listOf(exact)).representation)
        val failure = acquireSvgFailure(document, clipProfile(
            maxNodes = 5,
            maxReferences = 4,
            maxPaintVisits = 5,
            maxPaths = 4,
            maxClips = 2,
            maxSvgTransformOperations = 1,
        ))
        assertIs<FontError.ResourceLimitExceeded>(failure)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.location).tag)
    }

    @Test
    fun invalidClipTransformsFailWithTypedSvgTableDataErrors() {
        val transforms = listOf(
            "unknown(1)",
            "translate(1),",
            "matrix(1 0)",
            "translate(1e309)",
            "scale(1e-999 1)",
            "skewX(90)",
            "scale(1e308) scale(1e308)",
            "matrix(1e-200 0 0 1 0 0) matrix(1e-200 0 0 1 0 0)",
        )

        for (transform in transforms) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs><clipPath id="cut" transform="$transform"><path d="M0 0 L1 0 L0 1 Z"/></clipPath></defs>
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030"/>
                </svg>
            """.trimIndent()

            val failure = acquireSvgFailure(document, clipProfile())
            assertIs<FontError.FontDataFailure>(failure, transform)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.location).tag, transform)
        }
    }

    @Test
    fun invalidReferenceAndClipTransformCompositionsFailWithTypedSvgTableErrors() {
        val cases = listOf(
            "matrix(1e308 0 0 1 0 0)" to "matrix(1e308 0 0 1 0 0)",
            "matrix(1e-200 0 0 1 0 0)" to "matrix(1e-200 0 0 1 0 0)",
        )

        for ((referenceTransform, clipTransform) in cases) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs><clipPath id="cut" transform="$clipTransform"><path d="M0 0 L1 0 L0 1 Z"/></clipPath></defs>
                  <g transform="$referenceTransform">
                    <path d="M0 0 L1 0 L0 1 Z" fill="#102030" clip-path="url(#cut)"/>
                  </g>
                </svg>
            """.trimIndent()

            val failure = acquireSvgFailure(document, clipProfile())
            assertIs<FontError.FontDataFailure>(failure, document)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.location).tag, document)
        }
    }

    @Test
    fun omittedAndExplicitUserSpaceClipUnitsEachProduceTheLiteralClippedPaint() {
        fun document(units: String): String = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"$units><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        for (units in listOf("", " clipPathUnits=\"userSpaceOnUse\"")) {
            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document(units), listOf(clipProfile())).representation,
                units,
            ).paint

            assertEquals(1, paint.rootNode, units)
            assertEquals(2, paint.nodes.size, units)
            val shape = assertIs<GlyphPaintNode.Path>(paint.nodes[0], units)
            assertEquals(GlyphColor(16, 32, 48), shape.color, units)
            assertEquals(
                listOf(
                    GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                    GlyphPaintPathCommand.LineTo(3.0, 0.0),
                    GlyphPaintPathCommand.LineTo(0.0, 4.0),
                    GlyphPaintPathCommand.Close,
                ),
                shape.path.commands,
                units,
            )
            val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1], units)
            assertEquals(0, clip.paint, units)
            assertEquals(
                listOf(
                    GlyphPaintPathCommand.MoveTo(1.0, 2.0),
                    GlyphPaintPathCommand.LineTo(4.0, 2.0),
                    GlyphPaintPathCommand.LineTo(2.0, 6.0),
                    GlyphPaintPathCommand.Close,
                ),
                clip.path.commands,
                units,
            )
        }
    }

    @Test
    fun rejectsClipDefinitionsOutsideTheSinglePathUserSpaceSubset() {
        val definitions = listOf(
            """<clipPath id="cut" clipPathUnits="objectBoundingBox" transform="translate(1 2)"><path d="M0 0 L1 0 L0 1 Z"/></clipPath>""",
            """<clipPath id="cut" clipPathUnits="viewport"><path d="M0 0 L1 0 L0 1 Z"/></clipPath>""",
            """<clipPath id="cut"></clipPath>""",
            """<clipPath id="cut"><path d="M0 0 L1 0 L0 1 Z"/><path d="M0 0 L2 0 L0 2 Z"/></clipPath>""",
            """<clipPath id="cut"/>""",
            """<clipPath id="cut"><path d="M0 0 L1 0 L0 1 Z" fill="#000000"/></clipPath>""",
            """<clipPath id="cut"><path d="M0 0 L1 0 L0 1 Z" clip-rule="evenodd"/></clipPath>""",
            """<clipPath id="cut"><path id="inside" d="M0 0 L1 0 L0 1 Z"/></clipPath>""",
            """<clipPath id="cut"><path d="M0 0 L1 0 L0 1 Z"></path></clipPath>""",
            """<clipPath id="cut" transform="translate(1 2)"><g><path d="M0 0 L1 0 L0 1 Z"/></g></clipPath>""",
        )
        val documents = definitions.map { definition ->
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>$definition</defs>
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030"/>
                </svg>
            """.trimIndent()
        } + """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <clipPath id="cut"><path d="M0 0 L1 0 L0 1 Z"/></clipPath>
              <path d="M0 0 L2 0 L0 2 Z" fill="#102030"/>
            </svg>
        """.trimIndent()

        for (document in documents) {
            assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, clipProfile()), document)
        }
    }

    @Test
    fun rejectsMalformedDuplicateAndInvalidClipDefinitionDataWithTypedFontFailures() {
        val definitions = listOf(
            """<clipPath id="cut"><path d="M0 nope"/></clipPath>""",
            """<clipPath id="1cut"><path d="M0 0 L1 0 L0 1 Z"/></clipPath>""",
            """<clipPath id="cut"><path d="M0 0 L1 0 L0 1 Z"/></clipPath><clipPath id="cut"><path d="M0 0 L2 0 L0 2 Z"/></clipPath>""",
        )

        for (definition in definitions) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>$definition</defs>
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030"/>
                </svg>
            """.trimIndent()

            assertIs<FontError.FontDataFailure>(acquireSvgFailure(document, clipProfile()), definition)
        }
    }

    @Test
    fun rejectsMalformedOrNonLocalClipReferencesBeforePublishingEarlierPaint() {
        val definitions = """<defs><clipPath id="cut"><path d="M0 0 L1 0 L0 1 Z"/></clipPath></defs>"""
        val documents = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  $definitions
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030" clip-path="cut"/>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  $definitions
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030" clip-path="url(https://example.com/c.svg#cut)"/>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030" clip-path="url(#cut)"/>
                  $definitions
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  $definitions
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030"/>
                  <path d="M3 3 L5 3 L3 5 Z" fill="#405060" clip-path="url(#missing)"/>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <path d="M0 0 L2 0 L0 2 Z" fill="#102030" clip-path="url(#glyph1)"/>
                </svg>
            """.trimIndent(),
        )

        for (document in documents) {
            assertIs<FontError.UnsupportedRepresentationProfile>(
                acquireSvgFailure(
                    document,
                    clipProfile(maxNodes = 5, maxReferences = 4, maxDepth = 3, maxPaintVisits = 5, maxPaths = 4),
                ),
                document,
            )
        }
    }

    @Test
    fun exactClipProfileLimitsPassWhileEveryGeneratedClipBudgetIsEnforced() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()
        val exact = clipProfile(maxOutlineBytes = 81, maxOutlineContours = 1, maxOutlinePoints = 3)

        val resolved = resolveSvgDocument(document, listOf(exact))

        assertEquals(exact, resolved.profile)
        assertIs<GlyphRepresentation.Paint>(resolved.representation)
        val graphLimitProfiles = listOf(
            clipProfile(maxNodes = 1),
            clipProfile(maxReferences = 0),
            clipProfile(maxDepth = 1),
            clipProfile(maxPaintVisits = 1),
            clipProfile(maxPaths = 1),
            clipProfile(maxClips = 0),
        )
        for (profile in graphLimitProfiles) {
            assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, profile))
        }
        val outlineLimitProfiles = listOf(
            clipProfile(maxOutlineBytes = 80),
            clipProfile(maxOutlinePoints = 2),
        )
        for (profile in outlineLimitProfiles) {
            assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, profile))
        }
    }

    @Test
    fun clipAndPaintedPathsBothHonorExactLiteralOutlineLimits() {
        val documents = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs><clipPath id="cut"><path d="M0 0 L2 0 L0 2 Z M3 3 L5 3 L3 5 Z"/></clipPath></defs>
                  <path d="M0 0 L4 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs><clipPath id="cut"><path d="M0 0 L4 0 L0 4 Z"/></clipPath></defs>
                  <path d="M0 0 L2 0 L0 2 Z M3 3 L5 3 L3 5 Z" fill="#102030" clip-path="url(#cut)"/>
                </svg>
            """.trimIndent(),
        )
        val exact = clipProfile(maxOutlineBytes = 130, maxOutlineContours = 2, maxOutlinePoints = 6)

        for (document in documents) {
            assertIs<GlyphRepresentation.Paint>(resolveSvgDocument(document, listOf(exact)).representation)
            assertIs<FontError.UnsupportedRepresentationProfile>(
                acquireSvgFailure(document, clipProfile(maxOutlineBytes = 129, maxOutlineContours = 2, maxOutlinePoints = 6)),
            )
            assertIs<FontError.UnsupportedRepresentationProfile>(
                acquireSvgFailure(document, clipProfile(maxOutlineBytes = 130, maxOutlineContours = 1, maxOutlinePoints = 6)),
            )
            assertIs<FontError.UnsupportedRepresentationProfile>(
                acquireSvgFailure(document, clipProfile(maxOutlineBytes = 130, maxOutlineContours = 2, maxOutlinePoints = 5)),
            )
        }
    }

    @Test
    fun clipsRequireExactSchemaThreeAndPathClipButOrderedFallbackCanSelectIt() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()
        val schemaTwo = clipProfile(schemaVersion = 2, nodeKinds = listOf(GlyphPaintNodeKind.PATH))
        val withoutPathClip = clipProfile(nodeKinds = listOf(GlyphPaintNodeKind.PATH))
        val compatible = clipProfile()

        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, schemaTwo))
        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, withoutPathClip))
        assertEquals(compatible, resolveSvgDocument(document, listOf(schemaTwo, compatible)).profile)
    }

    @Test
    fun singularClippedPaintStaysEmptyOnlyAfterClipRequirementsAndLimitsValidate() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>
              <g transform="scale(0 1)">
                <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        assertEquals(GlyphRepresentation.Empty, resolveSvgDocument(document, listOf(clipProfile())).representation)
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(document, clipProfile(nodeKinds = listOf(GlyphPaintNodeKind.PATH))),
        )
        assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, clipProfile(maxNodes = 1)))
    }

    @Test
    fun singularClipTransformOmitsSolidAndGradientPaintOnlyAfterReachedValidation() {
        val solid = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut" transform="scale(0 1)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="#102030" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()
        val gradient = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="paint" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
                <clipPath id="cut" transform="scale(0 1)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath>
              </defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="url(#paint)" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()
        val radialGradient = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <radialGradient id="paint" gradientUnits="userSpaceOnUse" cx="10" cy="20" r="5">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </radialGradient>
                <clipPath id="cut" transform="scale(0 1)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath>
              </defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="url(#paint)" clip-path="url(#cut)"/>
            </svg>
        """.trimIndent()

        assertEquals(GlyphRepresentation.Empty, resolveSvgDocument(solid, listOf(clipProfile())).representation)
        assertEquals(GlyphRepresentation.Empty, resolveSvgDocument(gradient, listOf(gradientProfile())).representation)
        assertEquals(
            GlyphRepresentation.Empty,
            resolveSvgDocument(
                radialGradient,
                listOf(radialGradientProfile(
                    maxNodes = 4,
                    maxReferences = 3,
                    maxDepth = 4,
                    maxPaintVisits = 4,
                    maxPaths = 2,
                    maxClips = 2,
                )),
            ).representation,
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(solid, clipProfile(nodeKinds = listOf(GlyphPaintNodeKind.PATH))),
        )
        assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(solid, clipProfile(maxNodes = 1)))
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(solid, clipProfile(maxOutlinePoints = 2)),
        )

        val invalidSource = solid.replace("M1 2 L4 2 L2 6 Z", "M0 nope")
        val sourceFailure = acquireSvgFailure(invalidSource, clipProfile())
        assertIs<FontError.FontDataFailure>(sourceFailure)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(sourceFailure.location).tag)

        val invalidReference = solid.replace("url(#cut)", "url(#missing)")
        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(invalidReference, clipProfile()))
    }

    @Test
    fun singularClipDoesNotHideOverflowingLinearGradientMapping() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="paint" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0" gradientTransform="translate(1e308 0)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
                <clipPath id="cut" transform="scale(0 1)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath>
              </defs>
              <g transform="scale(2 1)">
                <path d="M0 0 L3 0 L0 4 Z" fill="url(#paint)" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val failure = assertIs<FontError.FontDataFailure>(acquireSvgFailure(document, gradientProfile()))
        assertEquals("font.svg.invalid-gradient", failure.code)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.location).tag)
    }

    @Test
    fun singularClipDoesNotHideOverflowingRadialGradientMapping() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <radialGradient id="paint" gradientUnits="userSpaceOnUse" cx="10" cy="20" r="5" gradientTransform="translate(1e308 0)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </radialGradient>
                <clipPath id="cut" transform="scale(0 1)"><path d="M1 2 L4 2 L2 6 Z"/></clipPath>
              </defs>
              <g transform="scale(2 1)">
                <path d="M0 0 L3 0 L0 4 Z" fill="url(#paint)" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()

        val failure = assertIs<FontError.FontDataFailure>(
            acquireSvgFailure(
                document,
                radialGradientProfile(
                    maxNodes = 4,
                    maxReferences = 3,
                    maxDepth = 4,
                    maxPaintVisits = 4,
                    maxPaths = 2,
                    maxClips = 2,
                ),
            ),
        )
        assertEquals("font.svg.invalid-gradient", failure.code)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.location).tag)
    }

    @Test
    fun singularClippedPaintValidatesProjectedGroupingBeforeLeavingEarlierPaintUntouched() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs><clipPath id="cut"><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>
              <path d="M0 0 L3 0 L0 4 Z" fill="#102030"/>
              <g transform="scale(0 1)">
                <path d="M5 5 L8 5 L5 9 Z" fill="#405060" clip-path="url(#cut)"/>
              </g>
            </svg>
        """.trimIndent()
        val exact = clipProfile(
            maxNodes = 4,
            maxReferences = 3,
            maxPaintVisits = 4,
            maxPaths = 3,
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(exact)).representation,
        ).paint
        assertEquals(0, paint.rootNode)
        assertEquals(1, paint.nodes.size)
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(document, clipProfile(
                compositionModes = emptyList(),
                maxNodes = 4,
                maxReferences = 3,
                maxPaintVisits = 4,
                maxPaths = 3,
            )),
        )
    }

    @Test
    fun noInkShapesStillValidateLocalClipReferencesWithoutGeneratingNodes() {
        val definitions = """<defs><clipPath id="cut"><path d="M1 2 L4 2 L2 6 Z"/></clipPath></defs>"""
        val emptyDocuments = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  $definitions
                  <path d="Mnot parsed" fill="none" clip-path="url(#cut)"/>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  $definitions
                  <rect x="0" y="0" width="0" height="4" fill="#102030" clip-path="url(#cut)"/>
                </svg>
            """.trimIndent(),
        )

        for (document in emptyDocuments) {
            assertEquals(GlyphRepresentation.Empty, resolveSvgDocument(document, listOf(clipProfile())).representation)
        }
        val unresolvedZeroRectangle = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              $definitions
              <rect x="0" y="0" width="0" height="4" fill="#102030" clip-path="url(#missing)"/>
            </svg>
        """.trimIndent()
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(unresolvedZeroRectangle, clipProfile()),
        )
        val unresolvedNoFillPath = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              $definitions
              <path d="Mnot parsed" fill="none" clip-path="url(#missing)"/>
            </svg>
        """.trimIndent()
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(unresolvedNoFillPath, clipProfile()),
        )
    }

    @Test
    fun groupSkewXFortyFiveProducesExactPathGeometry() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="skewX(45)">
                <path d="M 2 3 L 7 3 L 6 8 Z" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(5.0, 3.0),
                GlyphPaintPathCommand.LineTo(10.0, 3.0),
                GlyphPaintPathCommand.LineTo(14.0, 8.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands,
        )
    }

    @Test
    fun groupSkewYFortyFiveProducesExactPathGeometry() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="skewY(45)">
                <path d="M 2 3 L 7 -3 L -6 8 Z" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(2.0, 5.0),
                GlyphPaintPathCommand.LineTo(7.0, 4.0),
                GlyphPaintPathCommand.LineTo(-6.0, 2.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands,
        )
    }

    @Test
    fun skewAndTranslateKeepAuthoredTransformListOrder() {
        val cases = listOf(
            "skewX(45) translate(10 20)" to GlyphPaintPathCommand.MoveTo(35.0, 23.0),
            "translate(10 20) skewX(45)" to GlyphPaintPathCommand.MoveTo(15.0, 23.0),
        )

        for ((transform, expectedMove) in cases) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$transform">
                    <path d="M 2 3 L 7 -3 L -6 8 Z" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent()

            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document, listOf(paintProfile())).representation,
            ).paint

            assertEquals(expectedMove, assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands.first())
        }
    }

    @Test
    fun skewAnglesReduceByTangentPeriodAndCanonicalizeCommonCases() {
        val cases = listOf(
            "skewX(-0.0)" to GlyphPaintPathCommand.MoveTo(2.0, 3.0),
            "skewY(180)" to GlyphPaintPathCommand.MoveTo(2.0, 3.0),
            "skewX(225)" to GlyphPaintPathCommand.MoveTo(5.0, 3.0),
            "skewY(-135)" to GlyphPaintPathCommand.MoveTo(2.0, 5.0),
            "skewX(135)" to GlyphPaintPathCommand.MoveTo(-1.0, 3.0),
            "skewY(315)" to GlyphPaintPathCommand.MoveTo(2.0, 1.0),
        )

        for ((transform, expectedMove) in cases) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$transform">
                    <path d="M 2 3 L 7 -3 L -6 8 Z" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent()

            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document, listOf(paintProfile())).representation,
            ).paint

            assertEquals(expectedMove, assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands.first())
        }
    }

    @Test
    fun largeExactSkewAngleReducesToLiteralFortyFiveDegreeGeometry() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="skewX(9000000000000045)">
                <path d="M 2 3 L 7 -3 L -6 8 Z" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(5.0, 3.0),
                GlyphPaintPathCommand.LineTo(4.0, -3.0),
                GlyphPaintPathCommand.LineTo(2.0, 8.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands,
        )
    }

    @Test
    fun groupRotateNinetyAroundOriginProducesExactPathGeometry() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="rotate(90)">
                <path d="M 2 3 L 7 3 L 6 8 Z" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(-3.0, 2.0),
                GlyphPaintPathCommand.LineTo(-3.0, 7.0),
                GlyphPaintPathCommand.LineTo(-8.0, 6.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands,
        )
    }

    @Test
    fun groupRotateAroundAuthoredCenterProducesExactPathGeometry() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="rotate(90 10 20)">
                <path d="M 12 23 L 17 23 L 16 28 Z" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(7.0, 22.0),
                GlyphPaintPathCommand.LineTo(7.0, 27.0),
                GlyphPaintPathCommand.LineTo(2.0, 26.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands,
        )
    }

    @Test
    fun rotateAndTranslateKeepAuthoredTransformListOrder() {
        val cases = listOf(
            "rotate(90) translate(10 20)" to listOf(
                GlyphPaintPathCommand.MoveTo(-23.0, 12.0),
                GlyphPaintPathCommand.LineTo(-23.0, 17.0),
                GlyphPaintPathCommand.LineTo(-28.0, 16.0),
                GlyphPaintPathCommand.Close,
            ),
            "translate(10 20) rotate(90)" to listOf(
                GlyphPaintPathCommand.MoveTo(7.0, 22.0),
                GlyphPaintPathCommand.LineTo(7.0, 27.0),
                GlyphPaintPathCommand.LineTo(2.0, 26.0),
                GlyphPaintPathCommand.Close,
            ),
        )

        for ((transform, expectedCommands) in cases) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$transform">
                    <path d="M 2 3 L 7 3 L 6 8 Z" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent()

            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document, listOf(paintProfile())).representation,
            ).paint

            assertEquals(expectedCommands, assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands)
        }
    }

    @Test
    fun quadrantRotationsCanonicalizeToExactPathGeometry() {
        val cases = listOf(
            "-0.0" to GlyphPaintPathCommand.MoveTo(2.0, 3.0),
            "180" to GlyphPaintPathCommand.MoveTo(-2.0, -3.0),
            "270" to GlyphPaintPathCommand.MoveTo(3.0, -2.0),
            "450" to GlyphPaintPathCommand.MoveTo(-3.0, 2.0),
        )

        for ((angle, expectedMove) in cases) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="rotate($angle)">
                    <path d="M 2 3 L 7 3 L 6 8 Z" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent()

            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document, listOf(paintProfile())).representation,
            ).paint

            assertEquals(expectedMove, assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands.first())
        }
    }

    @Test
    fun finiteNegativeNonQuadrantRotationUsesSvgDegrees() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="rotate(-30)">
                <path d="M 2 0 L 4 0 L 2 2 Z" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint
        val commands = assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands
        val move = assertIs<GlyphPaintPathCommand.MoveTo>(commands[0])
        val firstLine = assertIs<GlyphPaintPathCommand.LineTo>(commands[1])
        val secondLine = assertIs<GlyphPaintPathCommand.LineTo>(commands[2])

        assertEquals(kotlin.math.sqrt(3.0), move.x, 1e-12)
        assertEquals(-1.0, move.y, 1e-12)
        assertEquals(2.0 * kotlin.math.sqrt(3.0), firstLine.x, 1e-12)
        assertEquals(-2.0, firstLine.y, 1e-12)
        assertEquals(kotlin.math.sqrt(3.0) + 1.0, secondLine.x, 1e-12)
        assertEquals(kotlin.math.sqrt(3.0) - 1.0, secondLine.y, 1e-12)
    }

    @Test
    fun groupMatricesUseSvgCoefficientsAndSourceOrder() {
        val cases = listOf(
            "translate(10 20) matrix(2.,1e0 .5,3 4,5)" to listOf(
                GlyphPaintPathCommand.MoveTo(14.0, 25.0),
                GlyphPaintPathCommand.LineTo(18.0, 27.0),
                GlyphPaintPathCommand.LineTo(18.5, 30.0),
                GlyphPaintPathCommand.LineTo(14.5, 28.0),
                GlyphPaintPathCommand.Close,
            ),
            "matrix(2 1 .5 3 4 5) translate(10 20)" to listOf(
                GlyphPaintPathCommand.MoveTo(34.0, 75.0),
                GlyphPaintPathCommand.LineTo(38.0, 77.0),
                GlyphPaintPathCommand.LineTo(38.5, 80.0),
                GlyphPaintPathCommand.LineTo(34.5, 78.0),
                GlyphPaintPathCommand.Close,
            ),
        )

        for ((transform, expectedCommands) in cases) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$transform">
                    <rect x="0" y="0" width="2" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent()

            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document, listOf(paintProfile())).representation,
            ).paint

            assertEquals(expectedCommands, assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands)
        }
    }

    @Test
    fun linearGradientTransformUsesTBgOrderWithoutTransformingTheClip() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="moved" gradientTransform="translate(.25 .5) scale(.5 .25)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <rect x="100" y="200" width="400" height="600" fill="url(#moved)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(410.0, 1_520.0), gradient.p0)
        assertEquals(GlyphPaintPoint(810.0, 1_520.0), gradient.p1)
        assertEquals(GlyphPaintPoint(410.0, 1_970.0), gradient.p2)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(210.0, 620.0),
                GlyphPaintPathCommand.LineTo(1_010.0, 620.0),
                GlyphPaintPathCommand.LineTo(1_010.0, 2_420.0),
                GlyphPaintPathCommand.LineTo(210.0, 2_420.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
    }

    @Test
    fun userSpaceLinearGradientUsesTgOrderWithoutBoundingBoxScalingOrTransformingTheClip() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="absolute" gradientUnits="userSpaceOnUse" x1="1" y1="2" x2="11" y2="2" gradientTransform="translate(5 7) scale(2 4)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <rect x="100" y="200" width="400" height="600" fill="url(#absolute)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(24.0, 65.0), gradient.p0)
        assertEquals(GlyphPaintPoint(64.0, 65.0), gradient.p1)
        assertEquals(GlyphPaintPoint(24.0, 185.0), gradient.p2)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(210.0, 620.0),
                GlyphPaintPathCommand.LineTo(1_010.0, 620.0),
                GlyphPaintPathCommand.LineTo(1_010.0, 2_420.0),
                GlyphPaintPathCommand.LineTo(210.0, 2_420.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
    }

    @Test
    fun pathUsesUserSpaceLinearGradientInItsActiveCoordinateSystem() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="absolute" gradientUnits="userSpaceOnUse" x1="1" y1="2" x2="11" y2="2" gradientTransform="translate(5 7) scale(2 4)">
                  <stop offset="0" stop-color="#102030" stop-opacity="25%"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10 20) scale(2 3)">
                <path d="M1 2 L4 2 L2 9 Z" fill="url(#absolute)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(24.0, 65.0), gradient.p0)
        assertEquals(GlyphPaintPoint(64.0, 65.0), gradient.p1)
        assertEquals(GlyphPaintPoint(24.0, 185.0), gradient.p2)
        assertEquals(
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(16, 32, 48), 0.25),
                GlyphPaintColorStop(1.0, GlyphColor(144, 160, 176), 1.0),
            ),
            gradient.colorLine.colorStops,
        )
        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[1])
        assertEquals(0, clip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(12.0, 26.0),
                GlyphPaintPathCommand.LineTo(18.0, 26.0),
                GlyphPaintPathCommand.LineTo(14.0, 47.0),
                GlyphPaintPathCommand.Close,
            ),
            clip.path.commands,
        )
    }

    @Test
    fun pathUsesUserSpaceRadialGradientWithOneActiveTransformNode() {
        val document = radialSvgDocument(
            attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"10\" cy=\"20\" r=\"5\" gradientTransform=\"matrix(2 1 0 3 5 7)\"",
            rectangle = """<g transform="translate(10 20) scale(4 5)"><path d="M1 2 L4 2 L2 9 Z" fill="url(#radial)"/></g>""",
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(radialGradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(10.0, 20.0), gradient.c0)
        assertEquals(0.0, gradient.radius0)
        assertEquals(GlyphPaintPoint(10.0, 20.0), gradient.c1)
        assertEquals(5.0, gradient.radius1)
        assertEquals(
            GlyphAffineTransform(8.0, 5.0, 0.0, 15.0, 30.0, 55.0),
            assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).matrix,
        )
        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[2])
        assertEquals(1, clip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(14.0, 30.0),
                GlyphPaintPathCommand.LineTo(26.0, 30.0),
                GlyphPaintPathCommand.LineTo(18.0, 65.0),
                GlyphPaintPathCommand.Close,
            ),
            clip.path.commands,
        )
    }

    @Test
    fun pathGradientDefinitionIsResolvedIndependentlyAtEachReference() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="shared" gradientUnits="userSpaceOnUse" x1="10" y1="20" x2="30" y2="20">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(100 200)">
                <path d="M0 0 L10 0 L0 20 Z" fill="url(#shared)"/>
              </g>
              <g transform="translate(-50 75) scale(2 3)">
                <path d="M1000 2000 L5000 2000 L1000 8000 Z" fill="url(#shared)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(gradientProfile(maxGradients = 2, maxColorStops = 4, maxSvgTransformOperations = 3)),
            ).representation,
        ).paint
        val first = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        val second = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[2])

        assertEquals(GlyphPaintPoint(110.0, 220.0), first.p0)
        assertEquals(GlyphPaintPoint(130.0, 220.0), first.p1)
        assertEquals(GlyphPaintPoint(110.0, 240.0), first.p2)
        assertEquals(GlyphPaintPoint(-30.0, 135.0), second.p0)
        assertEquals(GlyphPaintPoint(10.0, 135.0), second.p1)
        assertEquals(GlyphPaintPoint(-30.0, 195.0), second.p2)
        assertEquals(
            GlyphPaintPathCommand.MoveTo(100.0, 200.0),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands.first(),
        )
        assertEquals(
            GlyphPaintPathCommand.MoveTo(1_950.0, 6_075.0),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[3]).path.commands.first(),
        )
    }

    @Test
    fun pathGradientsNormalizeEmptyAndIntrinsicSolidPaints() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="empty" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0"/>
                <linearGradient id="one" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
                  <stop offset="40%" stop-color="#AABBCC" stop-opacity="40%"/>
                </linearGradient>
                <linearGradient id="flat" gradientUnits="userSpaceOnUse" x1="5" y1="7" x2="5" y2="7">
                  <stop offset="0" stop-color="#112233" stop-opacity="20%"/>
                  <stop offset="1" stop-color="#445566" stop-opacity="70%"/>
                </linearGradient>
                <radialGradient id="zero" gradientUnits="userSpaceOnUse" cx="8" cy="9" r="0">
                  <stop offset="0" stop-color="#010203" stop-opacity="10%"/>
                  <stop offset="1" stop-color="#ABCDEF" stop-opacity="90%"/>
                </radialGradient>
              </defs>
              <path d="M0 0 L1 0 L0 1 Z" fill="url(#empty)"/>
              <path d="M1 1 L3 1 L1 4 Z" fill="url(#one)"/>
              <path d="M10 20 L14 20 L10 25 Z" fill="url(#flat)"/>
              <path d="M30 40 L36 40 L30 47 Z" fill="url(#zero)"/>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(
                    gradientProfile(
                        maxNodes = 7,
                        maxReferences = 6,
                        maxPaths = 3,
                        maxGradients = 4,
                        maxColorStops = 5,
                        maxClips = 3,
                    ),
                ),
            ).representation,
        ).paint

        assertEquals(6, paint.rootNode)
        assertEquals(7, paint.nodes.size)
        assertEquals(GlyphPaintNode.Solid(GlyphColor(170, 187, 204), 0.4), paint.nodes[0])
        assertEquals(GlyphPaintNode.Solid(GlyphColor(68, 85, 102), 0.7), paint.nodes[2])
        assertEquals(GlyphPaintNode.Solid(GlyphColor(171, 205, 239), 0.9), paint.nodes[4])
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(1.0, 1.0),
                GlyphPaintPathCommand.LineTo(3.0, 1.0),
                GlyphPaintPathCommand.LineTo(1.0, 4.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(10.0, 20.0),
                GlyphPaintPathCommand.LineTo(14.0, 20.0),
                GlyphPaintPathCommand.LineTo(10.0, 25.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[3]).path.commands,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(30.0, 40.0),
                GlyphPaintPathCommand.LineTo(36.0, 40.0),
                GlyphPaintPathCommand.LineTo(30.0, 47.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[5]).path.commands,
        )
        assertEquals(GlyphPaintNode.Group(listOf(1, 3, 5)), paint.nodes[6])
    }

    @Test
    fun objectBoundingBoxPathGradientsStayUnsupportedBeforeReductionOrSingularOmission() {
        val definitions = listOf(
            """<linearGradient id="box"/>""",
            """<linearGradient id="box"><stop offset="1" stop-color="#AABBCC"/></linearGradient>""",
            """<radialGradient id="box" r="0"><stop offset="1" stop-color="#AABBCC"/></radialGradient>""",
        )

        for (definition in definitions) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>$definition</defs>
                  <g transform="scale(0 1)">
                    <path d="M0 0 L10 0 L0 10 Z" fill="url(#box)"/>
                  </g>
                </svg>
            """.trimIndent()

            val error = acquireSvgFailure(document, gradientProfile())

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun unsafePathGradientReferencesFailAtomicallyWithTypedPublicErrors() {
        val invalidPaints = listOf(
            "url(#paint",
            "url(https://example.test/paint)",
            "url(#missing)",
            "url(#glyph1)",
        )
        for (fill in invalidPaints) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <path d="M0 0 L1 0 L0 1 Z" fill="#010203"/>
                  <path d="M2 2 L3 2 L2 3 Z" fill="$fill"/>
                </svg>
            """.trimIndent()

            val error = acquireSvgFailure(document, gradientProfile())

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }

        val forwardReference = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <path d="M0 0 L1 0 L0 1 Z" fill="#010203"/>
              <path d="M2 2 L3 2 L2 3 Z" fill="url(#later)"/>
              <defs>
                <linearGradient id="later" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1" y2="0">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
            </svg>
        """.trimIndent()

        val error = acquireSvgFailure(forwardReference, gradientProfile())
        assertIs<FontError.UnsupportedRepresentationProfile>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun singularPathTransformsOmitValidGradientPaintOnlyAfterReferenceAndProfileValidation() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="absolute" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="scale(0 1)">
                <path d="M0 0 L10 0 L0 10 Z" fill="url(#absolute)"/>
              </g>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        )

        val missingCapability = gradientProfile(
            nodeKinds = listOf(
                GlyphPaintNodeKind.PATH,
                GlyphPaintNodeKind.GROUP,
                GlyphPaintNodeKind.SOLID,
                GlyphPaintNodeKind.PATH_CLIP,
            ),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, missingCapability))

        val unresolved = document.replace("url(#absolute)", "url(#missing)")
        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(unresolved, gradientProfile()))
    }

    @Test
    fun pathGradientOutlineExhaustionIsAResourceLimitBeforeSingularOmission() {
        val triangle = "M0 0 L10 0 L0 10 Z"
        val twoContours = "M0 0 L10 0 L0 10 Z M20 20 L30 20 L20 30 Z"
        val linearDefinition = """
            <linearGradient id="paint" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
              <stop offset="0" stop-color="#102030"/>
              <stop offset="1" stop-color="#90A0B0"/>
            </linearGradient>
        """.trimIndent()
        val radialDefinition = """
            <radialGradient id="paint" gradientUnits="userSpaceOnUse" cx="0" cy="0" r="10">
              <stop offset="0" stop-color="#102030"/>
              <stop offset="1" stop-color="#90A0B0"/>
            </radialGradient>
        """.trimIndent()
        val solidDefinition = """
            <linearGradient id="paint" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
              <stop offset="1" stop-color="#AABBCC"/>
            </linearGradient>
        """.trimIndent()
        data class Case(
            val name: String,
            val document: String,
            val profile: PaintGraphProfile,
        )
        val rejected = listOf(
            Case(
                "linear point limit",
                singularPathGradientDocument(linearDefinition, triangle),
                gradientProfile(maxOutlinePoints = 2),
            ),
            Case(
                "radial contour limit",
                singularPathGradientDocument(radialDefinition, twoContours),
                radialGradientProfile(maxOutlineContours = 1),
            ),
            Case(
                "solid-reduction byte limit",
                singularPathGradientDocument(solidDefinition, triangle),
                gradientProfile(maxOutlineBytes = 80),
            ),
        )

        for (case in rejected) {
            for (transform in listOf("scale(1 1)", "scale(0 1)")) {
                val failure = assertIs<FontError.ResourceLimitExceeded>(
                    acquireSvgFailure(case.document.replace("scale(0 1)", transform), case.profile),
                    "${case.name}: $transform",
                )
                assertEquals(FontDiagnosticLocation.Table("SVG "), failure.location)
            }
        }

        val admitted = gradientProfile(maxOutlineBytes = 81, maxOutlineContours = 1, maxOutlinePoints = 3)
        assertIs<GlyphRepresentation.Empty>(
            resolveSvgDocument(
                singularPathGradientDocument(solidDefinition, triangle),
                listOf(admitted),
            ).representation,
        )
        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                singularPathGradientDocument(solidDefinition, triangle).replace("scale(0 1)", "scale(1 1)"),
                listOf(admitted),
            ).representation,
        ).paint
        assertEquals(GlyphPaintNode.Solid(GlyphColor(170, 187, 204), 1.0), paint.nodes[0])
        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[paint.rootNode])
        assertEquals(0, clip.paint)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                GlyphPaintPathCommand.LineTo(10.0, 0.0),
                GlyphPaintPathCommand.LineTo(0.0, 10.0),
                GlyphPaintPathCommand.Close,
            ),
            clip.path.commands,
        )
    }

    @Test
    fun pathGradientCapabilitiesRemainAuthoritative() {
        val linear = userSpaceLinearPathSvgDocument()
        val incompatibleLinearProfiles = listOf(
            gradientProfile(
                nodeKinds = listOf(
                    GlyphPaintNodeKind.PATH,
                    GlyphPaintNodeKind.GROUP,
                    GlyphPaintNodeKind.SOLID,
                    GlyphPaintNodeKind.LINEAR_GRADIENT,
                ),
            ),
            gradientProfile(
                nodeKinds = listOf(
                    GlyphPaintNodeKind.PATH,
                    GlyphPaintNodeKind.GROUP,
                    GlyphPaintNodeKind.SOLID,
                    GlyphPaintNodeKind.PATH_CLIP,
                ),
            ),
            gradientProfile(extendModes = listOf(GlyphPaintExtendMode.REPEAT)),
            gradientProfile(interpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB)),
            gradientProfile(alphaInterpolationModes = emptyList()),
        )
        for (profile in incompatibleLinearProfiles) {
            assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(linear, profile))
        }

        val radial = radialSvgDocument(
            attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\" r=\"10\"",
            rectangle = """<path d="M0 0 L10 0 L0 10 Z" fill="url(#radial)"/>""",
        )
        val missingTransform = radialGradientProfile(
            nodeKinds = listOf(
                GlyphPaintNodeKind.SOLID,
                GlyphPaintNodeKind.GROUP,
                GlyphPaintNodeKind.RADIAL_GRADIENT,
                GlyphPaintNodeKind.PATH_CLIP,
            ),
        )
        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(radial, missingTransform))
    }

    @Test
    fun pathGradientGraphAndPaintBudgetsRemainAuthoritative() {
        val linear = userSpaceLinearPathSvgDocument()
        val limitedLinearProfiles = listOf(
            gradientProfile(maxNodes = 1),
            gradientProfile(maxReferences = 0),
            gradientProfile(maxPaths = 0),
            gradientProfile(maxGradients = 0),
            gradientProfile(maxColorStops = 1),
            gradientProfile(maxClips = 0),
        )
        for (profile in limitedLinearProfiles) {
            assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(linear, profile))
        }

        val radial = radialSvgDocument(
            attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\" r=\"10\"",
            rectangle = """<path d="M0 0 L10 0 L0 10 Z" fill="url(#radial)"/>""",
        )
        assertIs<FontError.ResourceLimitExceeded>(
            acquireSvgFailure(radial, radialGradientProfile(maxTransforms = 0)),
        )
    }

    @Test
    fun reusedRadialPathGradientDepthAndProducedCountsStayBounded() {
        val document = radialSvgDocument(
            attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\" r=\"10\"",
            rectangle = """
                <path d="M0 0 L10 0 L0 10 Z" fill="url(#radial)"/>
                <path d="M20 20 L30 20 L20 30 Z" fill="url(#radial)"/>
            """.trimIndent(),
        )
        fun profile(
            maxDepth: Int = 4,
            maxGradients: Int = 2,
            maxColorStops: Int = 4,
        ): PaintGraphProfile = radialGradientProfile(
            maxNodes = 7,
            maxReferences = 6,
            maxDepth = maxDepth,
            maxPaths = 2,
            maxGradients = maxGradients,
            maxColorStops = maxColorStops,
            maxTransforms = 2,
            maxClips = 2,
        )

        assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, profile(maxDepth = 3)))
        assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, profile(maxGradients = 1)))
        assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, profile(maxColorStops = 2)))

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(profile())).representation,
        ).paint
        assertEquals(6, paint.rootNode)
        assertEquals(7, paint.nodes.size)
        assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])
        assertEquals(0, assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).paint)
        assertEquals(1, assertIs<GlyphPaintNode.PathClip>(paint.nodes[2]).paint)
        assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[3])
        assertEquals(3, assertIs<GlyphPaintNode.Transform>(paint.nodes[4]).paint)
        assertEquals(4, assertIs<GlyphPaintNode.PathClip>(paint.nodes[5]).paint)
        assertEquals(GlyphPaintNode.Group(listOf(2, 5)), paint.nodes[6])
    }

    @Test
    fun userSpaceGradientIsResolvedAtEachReferenceWithoutDependingOnRectangleBounds() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="shared" gradientUnits="userSpaceOnUse" x1="10" y1="20" x2="30" y2="20">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(100 200)">
                <rect x="0" y="0" width="10" height="20" fill="url(#shared)"/>
              </g>
              <g transform="translate(-50 75) scale(2 3)">
                <rect x="1000" y="2000" width="4000" height="6000" fill="url(#shared)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(
                document,
                listOf(gradientProfile(maxGradients = 2, maxColorStops = 4, maxSvgTransformOperations = 3)),
            ).representation,
        ).paint
        val first = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        val second = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[2])

        assertEquals(GlyphPaintPoint(110.0, 220.0), first.p0)
        assertEquals(GlyphPaintPoint(130.0, 220.0), first.p1)
        assertEquals(GlyphPaintPoint(110.0, 240.0), first.p2)
        assertEquals(GlyphPaintPoint(-30.0, 135.0), second.p0)
        assertEquals(GlyphPaintPoint(10.0, 135.0), second.p1)
        assertEquals(GlyphPaintPoint(-30.0, 195.0), second.p2)
    }

    @Test
    fun linearGradientRotationUsesTBgOrderWithoutTransformingTheClip() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="rotated" gradientTransform="rotate(90)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10 20)">
                <rect x="30" y="40" width="100" height="200" fill="url(#rotated)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(40.0, 60.0), gradient.p0)
        assertEquals(GlyphPaintPoint(40.0, 260.0), gradient.p1)
        assertEquals(GlyphPaintPoint(-60.0, 60.0), gradient.p2)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(40.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 260.0),
                GlyphPaintPathCommand.LineTo(40.0, 260.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
    }

    @Test
    fun linearGradientSkewUsesTBgOrderWithoutTransformingTheClip() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="skewed" gradientTransform="skewX(45)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10 20)">
                <rect x="30" y="40" width="100" height="200" fill="url(#skewed)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(40.0, 60.0), gradient.p0)
        assertEquals(GlyphPaintPoint(140.0, 60.0), gradient.p1)
        assertEquals(GlyphPaintPoint(140.0, 260.0), gradient.p2)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(40.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 260.0),
                GlyphPaintPathCommand.LineTo(40.0, 260.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
    }

    @Test
    fun linearGradientMatrixUsesTBgOrderWithoutTransformingTheClip() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="sheared" gradientTransform="matrix(-2 1 0 3 .5 .25)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10 20)">
                <rect x="30" y="40" width="100" height="200" fill="url(#sheared)"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(gradientProfile())).representation,
        ).paint
        val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(90.0, 110.0), gradient.p0)
        assertEquals(GlyphPaintPoint(-110.0, 310.0), gradient.p1)
        assertEquals(GlyphPaintPoint(90.0, 710.0), gradient.p2)
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(40.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 260.0),
                GlyphPaintPathCommand.LineTo(40.0, 260.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).path.commands,
        )
    }

    @Test
    fun gradientTransformListOrderChangesLinearPaintGeometry() {
        val cases = listOf(
            "translate(1.,2.), scale(2 3)" to listOf(
                GlyphPaintPoint(1.0, 2.0),
                GlyphPaintPoint(3.0, 2.0),
                GlyphPaintPoint(1.0, 5.0),
            ),
            "scale(2,3) translate(1.e0 .2e1)" to listOf(
                GlyphPaintPoint(2.0, 6.0),
                GlyphPaintPoint(4.0, 6.0),
                GlyphPaintPoint(2.0, 9.0),
            ),
            "translate(1),,scale(2)" to listOf(
                GlyphPaintPoint(1.0, 0.0),
                GlyphPaintPoint(3.0, 0.0),
                GlyphPaintPoint(1.0, 2.0),
            ),
        )

        for ((gradientTransform, expectedPoints) in cases) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>
                    <linearGradient id="ordered" gradientTransform="$gradientTransform">
                      <stop offset="0" stop-color="#102030"/>
                      <stop offset="1" stop-color="#90A0B0"/>
                    </linearGradient>
                  </defs>
                  <rect x="0" y="0" width="1" height="1" fill="url(#ordered)"/>
                </svg>
            """.trimIndent()

            val paint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(document, listOf(gradientProfile())).representation,
            ).paint
            val gradient = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])

            assertEquals(expectedPoints[0], gradient.p0)
            assertEquals(expectedPoints[1], gradient.p1)
            assertEquals(expectedPoints[2], gradient.p2)
        }
    }

    @Test
    fun radialGradientTransformUsesOneExactTBgTransform() {
        val document = radialSvgDocument(
            attributes = "gradientTransform=\"matrix(-2 1 0 3 .5 .25)\"",
            rectangle = """<g transform="translate(10 20)"><rect x="30" y="40" width="100" height="200" fill="url(#radial)"/></g>""",
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(radialGradientProfile(maxTransforms = 1))).representation,
        ).paint
        val radial = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(0.5, 0.5), radial.c0)
        assertEquals(GlyphPaintPoint(0.5, 0.5), radial.c1)
        assertEquals(0.5, radial.radius1)
        assertEquals(
            GlyphAffineTransform(-200.0, 200.0, 0.0, 600.0, 90.0, 110.0),
            assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).matrix,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(40.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 260.0),
                GlyphPaintPathCommand.LineTo(40.0, 260.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[2]).path.commands,
        )
    }

    @Test
    fun userSpaceRadialGradientRetainsAbsoluteCircleAndUsesOneExactTgTransform() {
        val document = radialSvgDocument(
            attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"30\" cy=\"40\" r=\"25\" gradientTransform=\"matrix(-2 1 0 3 5 7)\"",
            rectangle = """<g transform="translate(10 20) scale(2 3)"><rect x="100" y="200" width="400" height="600" fill="url(#radial)"/></g>""",
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(radialGradientProfile(maxTransforms = 1))).representation,
        ).paint
        val radial = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(30.0, 40.0), radial.c0)
        assertEquals(0.0, radial.radius0)
        assertEquals(GlyphPaintPoint(30.0, 40.0), radial.c1)
        assertEquals(25.0, radial.radius1)
        assertEquals(
            GlyphAffineTransform(-4.0, 3.0, 0.0, 9.0, 20.0, 41.0),
            assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).matrix,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(210.0, 620.0),
                GlyphPaintPathCommand.LineTo(1_010.0, 620.0),
                GlyphPaintPathCommand.LineTo(1_010.0, 2_420.0),
                GlyphPaintPathCommand.LineTo(210.0, 2_420.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[2]).path.commands,
        )
    }

    @Test
    fun userSpaceRadialGradientRequiresTheExistingTransformCapabilityAndBudget() {
        val document = radialSvgDocument(
            attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"30\" cy=\"40\" r=\"25\"",
        )
        val withoutTransformKind = radialGradientProfile(
            nodeKinds = listOf(
                GlyphPaintNodeKind.SOLID,
                GlyphPaintNodeKind.RADIAL_GRADIENT,
                GlyphPaintNodeKind.PATH_CLIP,
            ),
        )

        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, withoutTransformKind))
        assertIs<FontError.ResourceLimitExceeded>(
            acquireSvgFailure(document, radialGradientProfile(maxTransforms = 0)),
        )
    }

    @Test
    fun radialGradientRotationIsRetainedInTheExactTBgTransformNode() {
        val document = radialSvgDocument(
            attributes = "gradientTransform=\"rotate(90)\"",
            rectangle = """<g transform="translate(10 20)"><rect x="30" y="40" width="100" height="200" fill="url(#radial)"/></g>""",
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(radialGradientProfile(maxTransforms = 1))).representation,
        ).paint

        assertEquals(
            GlyphAffineTransform(0.0, 200.0, -100.0, 0.0, 40.0, 60.0),
            assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).matrix,
        )
        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(40.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 60.0),
                GlyphPaintPathCommand.LineTo(140.0, 260.0),
                GlyphPaintPathCommand.LineTo(40.0, 260.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.PathClip>(paint.nodes[2]).path.commands,
        )
    }

    @Test
    fun negativeScaleReflectsLinearPaintWhileEmptyListsRemainIdentity() {
        val reflected = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="reflected" gradientTransform="translate(.5) scale(-2)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="100" height="50" fill="url(#reflected)"/>
            </svg>
        """.trimIndent()
        val reflectedPaint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(reflected, listOf(gradientProfile())).representation,
        ).paint
        val reflectedGradient = assertIs<GlyphPaintNode.LinearGradient>(reflectedPaint.nodes[0])
        assertEquals(GlyphPaintPoint(50.0, 0.0), reflectedGradient.p0)
        assertEquals(GlyphPaintPoint(-150.0, 0.0), reflectedGradient.p1)
        assertEquals(GlyphPaintPoint(50.0, -100.0), reflectedGradient.p2)

        for (emptyTransform in listOf("", "  \t  \n")) {
            val identity = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>
                    <linearGradient id="identity" gradientTransform="$emptyTransform">
                      <stop offset="0" stop-color="#102030"/>
                      <stop offset="1" stop-color="#90A0B0"/>
                    </linearGradient>
                  </defs>
                  <rect x="10" y="20" width="30" height="40" fill="url(#identity)"/>
                </svg>
            """.trimIndent()
            val identityPaint = assertIs<GlyphRepresentation.Paint>(
                resolveSvgDocument(identity, listOf(gradientProfile())).representation,
            ).paint
            val identityGradient = assertIs<GlyphPaintNode.LinearGradient>(identityPaint.nodes[0])
            assertEquals(GlyphPaintPoint(10.0, 20.0), identityGradient.p0)
            assertEquals(GlyphPaintPoint(40.0, 20.0), identityGradient.p1)
            assertEquals(GlyphPaintPoint(10.0, 60.0), identityGradient.p2)
        }
    }

    @Test
    fun malformedAndSingularGradientTransformsReturnTypedPublicFailures() {
        val malformed = listOf(
            "translate()",
            "translate(1)scale(2)",
            "scale(1,,2)",
            "scale(1,)",
            "matrix(1 0 0 1 0)",
            "matrix(1,,0,0,1,0,0)",
            "matrix(1 0 0 1 0 10%)",
            "translate(10%)",
            "rotate()",
            "rotate(10 20)",
            "rotate(10 20 30 40)",
            "rotate(10,,20,30)",
            "rotate(10%)",
            "skewX()",
            "skewY(10 20)",
            "skewX(10,)",
            "skewY(,10)",
            "skewX(10deg)",
            "skewY(10%)",
            "skewZ(10)",
            "skewX(45) skewY(",
            "skewX(1e999)",
            "scale(1) trailing",
            "rotate(10) trailing",
            "scale(1e308) scale(1e308)",
            "matrix(1 0 0 1 0 1e999)",
        )
        for (gradientTransform in malformed) {
            val error = acquireSvgFailure(
                radialSvgDocument(attributes = "gradientTransform=\"$gradientTransform\""),
                radialGradientProfile(),
            )

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }

        val singular = listOf(
            "scale(0 1)",
            "scale(0)",
            "scale(0.0)",
            "scale(-0)",
        )
        for (gradientTransform in singular) {
            val error = acquireSvgFailure(
                radialSvgDocument(attributes = "gradientTransform=\"$gradientTransform\""),
                radialGradientProfile(),
            )

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun nonZeroGradientScaleUnderflowIsInvalidFontData() {
        val transforms = listOf(
            "scale(1e-200 1) scale(1e-200 1)",
            "scale(1e-999 1)",
        )

        for (gradientTransform in transforms) {
            val error = acquireSvgFailure(
                radialSvgDocument(attributes = "gradientTransform=\"$gradientTransform\""),
                radialGradientProfile(),
            )

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun lexicallyNonZeroRotationAngleUnderflowIsInvalidFontData() {
        val documents = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="rotate(1e-999)">
                    <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent(),
            radialSvgDocument(attributes = "gradientTransform=\"rotate(-1e-999 10 20)\""),
        )

        for ((index, document) in documents.withIndex()) {
            val profile = if (index == 0) paintProfile() else radialGradientProfile()
            val error = acquireSvgFailure(document, profile)

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun exactSkewAsymptotesAreInvalidFontData() {
        val cases = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="skewX(90)">
                    <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent() to paintProfile(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="skewY(-270)">
                    <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent() to paintProfile(),
            radialSvgDocument(attributes = "gradientTransform=\"skewX(270)\"") to radialGradientProfile(),
            radialSvgDocument(attributes = "gradientTransform=\"skewY(-90)\"") to radialGradientProfile(),
        )

        for ((document, profile) in cases) {
            val error = acquireSvgFailure(document, profile)

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun lexicallyNonZeroSkewAngleUnderflowIsInvalidFontData() {
        val cases = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="skewX(1e-999)">
                    <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent() to paintProfile(),
            radialSvgDocument(attributes = "gradientTransform=\"skewY(-1e-999)\"") to radialGradientProfile(),
        )

        for ((document, profile) in cases) {
            val error = acquireSvgFailure(document, profile)

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun nonZeroMatrixCoefficientUnderflowIsInvalidFontData() {
        val cases = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="matrix(1 0 0 1 1e-999 0)">
                    <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent() to paintProfile(),
            radialSvgDocument(attributes = "gradientTransform=\"matrix(1e-999 0 0 1 0 0)\"") to
                radialGradientProfile(),
        )

        for ((document, profile) in cases) {
            val error = acquireSvgFailure(document, profile)

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun fullRankMatrixCompositionThatLosesRankIsInvalidFontData() {
        val localList = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="matrix(1e-200 0 0 1 0 0) matrix(1e-200 0 0 1 0 0)">
                <rect x="0" y="0" width="1" height="1" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()
        val nestedGroups = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="matrix(1e-200 0 0 1 0 0)">
                <g transform="matrix(1e-200 0 0 1 0 0)">
                  <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                </g>
              </g>
            </svg>
        """.trimIndent()

        for (document in listOf(localList, nestedGroups)) {
            val error = acquireSvgFailure(document, paintProfile(maxDepth = 3))

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun fullRankMatrixCompositionThatReversesOrientationIsInvalidFontData() {
        val first = "matrix(0.9999999999999991 0.9999999999999991 0.9999999999999991 0.9999999999999993 0 0)"
        val second = "matrix(1 0.9999999999999991 0.9999999999999993 1 0 0)"
        val documents = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$first $second">
                    <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$first">
                    <g transform="$second">
                      <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                    </g>
                  </g>
                </svg>
            """.trimIndent(),
        )

        for (document in documents) {
            val error = acquireSvgFailure(document, paintProfile(maxDepth = 3))

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun twoMatrixReflectionsProduceLiteralPaintGeometry() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="matrix(-1 0 0 1 0 0) matrix(1 0 0 -1 0 0)">
                <rect x="0" y="0" width="2" height="1" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                GlyphPaintPathCommand.LineTo(-2.0, 0.0),
                GlyphPaintPathCommand.LineTo(-2.0, -1.0),
                GlyphPaintPathCommand.LineTo(0.0, -1.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands,
        )
    }

    @Test
    fun nonZeroGroupScaleUnderflowIsInvalidFontData() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="scale(1e-999 1)">
                <rect x="0" y="0" width="1" height="1" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val error = acquireSvgFailure(document, paintProfile())

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun singularMatrixFactorCannotRegainRankAcrossListsOrGroups() {
        val listTransform = "matrix(1 3 1 3 0 0) matrix(.1 .1 .1 .2 0 0)"
        val nestedTransform = """
            <g transform="matrix(1 3 1 3 0 0)">
              <g transform="matrix(.1 .1 .1 .2 0 0)">
                <rect x="0" y="0" width="1" height="1" fill="#102030"/>
              </g>
            </g>
        """.trimIndent()
        val groupDocuments = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$listTransform">
                    <rect x="0" y="0" width="1" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  $nestedTransform
                </svg>
            """.trimIndent(),
        )

        for (document in groupDocuments) {
            assertIs<GlyphRepresentation.Empty>(
                resolveSvgDocument(document, listOf(paintProfile(maxDepth = 3))).representation,
            )
        }

        val gradient = radialSvgDocument(attributes = "gradientTransform=\"$listTransform\"")
        val error = acquireSvgFailure(gradient, radialGradientProfile())
        assertIs<FontError.UnsupportedRepresentationProfile>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun smallestNonZeroMatrixScaleRetainsFinitePaint() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="matrix(5e-324 0 0 1 0 0)">
                <rect x="0" y="0" width="1" height="1" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile())).representation,
        ).paint

        assertEquals(
            listOf(
                GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                GlyphPaintPathCommand.LineTo(5e-324, 0.0),
                GlyphPaintPathCommand.LineTo(5e-324, 1.0),
                GlyphPaintPathCommand.LineTo(0.0, 1.0),
                GlyphPaintPathCommand.Close,
            ),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands,
        )
    }

    @Test
    fun supportedSkewDoesNotMaskInvalidNumericComposition() {
        val transforms = listOf(
            "scale(1e-200 1) skewX(0) scale(1e-200 1)",
            "scale(1e308 1) skewX(0) scale(1e308 1)",
        )

        for (gradientTransform in transforms) {
            val error = acquireSvgFailure(
                radialSvgDocument(attributes = "gradientTransform=\"$gradientTransform\""),
                radialGradientProfile(maxSvgTransformOperations = 3),
            )

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun skewFunctionStillConsumesTheSourceBudgetBeforeNumericValidation() {
        val document = radialSvgDocument(
            attributes = "gradientTransform=\"scale(1e-200 1) skewX(0) scale(1e-200 1)\"",
        )

        val error = acquireSvgFailure(document, radialGradientProfile(maxSvgTransformOperations = 2))

        assertIs<FontError.ResourceLimitExceeded>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun malformedSuffixTakesPrecedenceOverEarlierValidTransforms() {
        val document = radialSvgDocument(
            attributes = "gradientTransform=\"scale(1) rotate(0) scale(\"",
        )

        val error = acquireSvgFailure(document, radialGradientProfile(maxSvgTransformOperations = 3))

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun threeOperandRotateConsumesOneAuthoredTransformOperation() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <g transform="rotate(90 10 20) translate(0)">
                <rect x="10" y="20" width="2" height="1" fill="#102030"/>
              </g>
            </svg>
        """.trimIndent()

        val failure = acquireSvgFailure(document, paintProfile(maxSvgTransformOperations = 1))
        assertIs<FontError.ResourceLimitExceeded>(failure)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.location).tag)

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(paintProfile(maxSvgTransformOperations = 2))).representation,
        ).paint
        assertEquals(
            GlyphPaintPathCommand.MoveTo(10.0, 20.0),
            assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands.first(),
        )
    }

    @Test
    fun oneOperandSkewsEachConsumeOneAuthoredTransformOperation() {
        for (skew in listOf("skewX(0)", "skewY(0)")) {
            val document = """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <g transform="$skew translate(0)">
                    <rect x="0" y="0" width="2" height="1" fill="#102030"/>
                  </g>
                </svg>
            """.trimIndent()
            val insufficient = paintProfile(maxSvgTransformOperations = 1)
            val compatible = paintProfile(maxSvgTransformOperations = 2)

            val failure = acquireSvgFailure(document, insufficient)
            assertIs<FontError.ResourceLimitExceeded>(failure)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(failure.location).tag)

            val resolved = resolveSvgDocument(document, listOf(insufficient, compatible))
            assertEquals(compatible, resolved.profile)
            val paint = assertIs<GlyphRepresentation.Paint>(resolved.representation).paint
            assertEquals(
                GlyphPaintPathCommand.MoveTo(0.0, 0.0),
                assertIs<GlyphPaintNode.Path>(paint.nodes.single()).path.commands.first(),
            )
        }
    }

    @Test
    fun radialObjectBoundingBoxCompositionUnderflowIsInvalidFontData() {
        val document = radialSvgDocument(
            attributes = "gradientTransform=\"scale(1e-200 1)\"",
            rectangle = """<rect x="0" y="0" width="1e-200" height="1" fill="url(#radial)"/>""",
        )

        val error = acquireSvgFailure(document, radialGradientProfile())

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun linearObjectBoundingBoxCompositionUnderflowIsInvalidFontData() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="linear" gradientTransform="scale(1e-200 1)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <rect x="0" y="0" width="1e-200" height="1" fill="url(#linear)"/>
            </svg>
        """.trimIndent()

        val error = acquireSvgFailure(document, gradientProfile())

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun singularGradientTransformsAreRejectedBeforeEmptyOrSolidReduction() {
        val documents = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs><linearGradient id="empty" gradientTransform="scale(0 1)"/></defs>
                  <rect x="0" y="0" width="10" height="10" fill="url(#empty)"/>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>
                    <linearGradient id="one" gradientTransform="scale(1 0)">
                      <stop offset="0" stop-color="#102030"/>
                    </linearGradient>
                  </defs>
                  <rect x="0" y="0" width="10" height="10" fill="url(#one)"/>
                </svg>
            """.trimIndent(),
            radialSvgDocument(attributes = "r=\"0\" gradientTransform=\"scale(0)\""),
            radialSvgDocument(
                attributes = "gradientTransform=\"matrix(1 0 0 0 0 0)\"",
                stops = """<stop offset="40%" stop-color="#AABBCC"/>""",
            ),
        )

        for (document in documents) {
            val error = acquireSvgFailure(document, radialGradientProfile())

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun validGradientTransformsDoNotAddCapabilitiesToEmptyAndSolidReductions() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="empty" gradientTransform="matrix(1 0 0 1 1 0)"/>
                <radialGradient id="one" gradientTransform="matrix(2 0 0 2 0 0)">
                  <stop offset="40%" stop-color="#AABBCC" stop-opacity="40%"/>
                </radialGradient>
              </defs>
              <rect x="0" y="0" width="10" height="10" fill="url(#empty)"/>
              <rect x="20" y="30" width="40" height="50" fill="url(#one)"/>
            </svg>
        """.trimIndent()
        val profile = gradientProfile(
            nodeKinds = listOf(GlyphPaintNodeKind.SOLID, GlyphPaintNodeKind.PATH_CLIP),
            extendModes = emptyList(),
            interpolationSpaces = emptyList(),
            alphaInterpolationModes = emptyList(),
            maxNodes = 2,
            maxReferences = 1,
            maxPaths = 1,
            maxGradients = 2,
            maxColorStops = 1,
            maxTransforms = 0,
            maxClips = 1,
            maxSvgTransformOperations = 2,
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(profile)).representation,
        ).paint

        assertEquals(GlyphPaintNode.Solid(GlyphColor(170, 187, 204), 0.4), paint.nodes[0])
        assertEquals(0, assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).paint)
    }

    @Test
    fun sourceTransformBudgetChargesUnusedIdentityOnceWithoutRepayingGradientReuse() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="unused" gradientTransform="matrix(1 0 0 1 0 0)"/>
                <linearGradient id="shared" gradientTransform="translate(.25 0) scale(.5 1)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <g transform="translate(10)">
                <rect x="0" y="0" width="100" height="50" fill="url(#shared)"/>
                <rect x="200" y="100" width="200" height="80" fill="url(#shared)"/>
              </g>
            </svg>
        """.trimIndent()
        val insufficient = gradientProfile(
            maxNodes = 5,
            maxReferences = 4,
            maxPaths = 2,
            maxGradients = 2,
            maxColorStops = 4,
            maxClips = 2,
            maxSvgTransformOperations = 3,
        )
        val compatible = gradientProfile(
            maxNodes = 5,
            maxReferences = 4,
            maxPaths = 2,
            maxGradients = 2,
            maxColorStops = 4,
            maxClips = 2,
            maxSvgTransformOperations = 4,
        )

        assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, insufficient))
        val resolved = resolveSvgDocument(document, listOf(insufficient, compatible))
        val paint = assertIs<GlyphRepresentation.Paint>(resolved.representation).paint

        assertEquals(compatible, resolved.profile)
        val first = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[0])
        assertEquals(GlyphPaintPoint(35.0, 0.0), first.p0)
        assertEquals(GlyphPaintPoint(85.0, 0.0), first.p1)
        assertEquals(GlyphPaintPoint(35.0, 50.0), first.p2)
        val second = assertIs<GlyphPaintNode.LinearGradient>(paint.nodes[2])
        assertEquals(GlyphPaintPoint(260.0, 100.0), second.p0)
        assertEquals(GlyphPaintPoint(360.0, 100.0), second.p1)
        assertEquals(GlyphPaintPoint(260.0, 180.0), second.p2)
    }

    @Test
    fun overflowingGradientTransformRejectsTheWholeAssetAfterEarlierPaint() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <rect x="0" y="0" width="10" height="10" fill="#102030"/>
              <defs>
                <linearGradient id="overflow" gradientTransform="scale(1e308) scale(1e308)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <rect x="20" y="0" width="10" height="10" fill="url(#overflow)"/>
            </svg>
        """.trimIndent()

        val error = acquireSvgFailure(document, gradientProfile(maxSvgTransformOperations = 2))

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun overflowingSkewCompositionRejectsTheWholeAssetAfterEarlierPaint() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <rect x="0" y="0" width="10" height="10" fill="#102030"/>
              <defs>
                <linearGradient id="overflow" gradientTransform="scale(1e308 1) skewX(45) skewY(45)">
                  <stop offset="0" stop-color="#102030"/>
                  <stop offset="1" stop-color="#90A0B0"/>
                </linearGradient>
              </defs>
              <rect x="20" y="0" width="10" height="10" fill="url(#overflow)"/>
            </svg>
        """.trimIndent()

        val error = acquireSvgFailure(document, gradientProfile(maxSvgTransformOperations = 3))

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun overflowingNestedGroupMatricesRejectTheWholeAssetWithTypedFailure() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <rect x="0" y="0" width="10" height="10" fill="#102030"/>
              <g transform="matrix(1e308 0 0 1 0 0)">
                <g transform="matrix(1e308 0 0 1 0 0)">
                  <rect x="20" y="0" width="10" height="10" fill="#90A0B0"/>
                </g>
              </g>
            </svg>
        """.trimIndent()

        val error = acquireSvgFailure(document, paintProfile(maxDepth = 3))

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun normalizesDefaultRadialGradientThroughTheFirstCompatibleProfile() {
        val document = radialSvgDocument(
            rectangle = """<g transform="translate(10 20)"><rect x="100" y="200" width="400" height="600" fill="url(#radial)"/></g>""",
        )
        val compatible = radialGradientProfile()
        val profiles = listOf(
            schema2GradientProfile(),
            radialGradientProfile(
                nodeKinds = listOf(GlyphPaintNodeKind.SOLID, GlyphPaintNodeKind.TRANSFORM, GlyphPaintNodeKind.PATH_CLIP),
            ),
            radialGradientProfile(
                nodeKinds = listOf(GlyphPaintNodeKind.SOLID, GlyphPaintNodeKind.RADIAL_GRADIENT, GlyphPaintNodeKind.PATH_CLIP),
            ),
            radialGradientProfile(interpolationSpaces = listOf(GlyphPaintInterpolationSpace.LINEAR_SRGB)),
            radialGradientProfile(alphaInterpolationModes = listOf(GlyphPaintAlphaInterpolationMode.PREMULTIPLIED)),
            radialGradientProfile(extendModes = listOf(GlyphPaintExtendMode.REPEAT)),
            compatible,
        )

        val resolved = resolveSvgDocument(document, profiles)
        val paint = assertIs<GlyphRepresentation.Paint>(resolved.representation).paint

        assertEquals(compatible, resolved.profile)
        assertEquals(2, paint.rootNode)
        assertEquals(3, paint.nodes.size)
        val radial = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])
        assertEquals(GlyphPaintPoint(0.5, 0.5), radial.c0)
        assertEquals(0.0, radial.radius0)
        assertEquals(GlyphPaintPoint(0.5, 0.5), radial.c1)
        assertEquals(0.5, radial.radius1)
        assertEquals(GlyphPaintExtendMode.PAD, radial.colorLine.extendMode)
        assertEquals(GlyphPaintInterpolationSpace.SRGB, radial.colorLine.interpolationSpace)
        assertEquals(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED, radial.colorLine.alphaInterpolationMode)
        assertEquals(
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(16, 32, 48), 0.25),
                GlyphPaintColorStop(1.0, GlyphColor(144, 160, 176), 1.0),
            ),
            radial.colorLine.colorStops,
        )
        val transform = assertIs<GlyphPaintNode.Transform>(paint.nodes[1])
        assertEquals(0, transform.paint)
        assertEquals(GlyphAffineTransform(400.0, 0.0, 0.0, 600.0, 110.0, 220.0), transform.matrix)
        val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[2])
        assertEquals(1, clip.paint)
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
    }

    @Test
    fun mapsCustomConcentricRadialGeometryAndColorLineInObjectBoundingBoxSpace() {
        val document = radialSvgDocument(
            attributes = "cx=\"25%\" cy=\"75%\" r=\"40%\" fx=\"0.25\" fy=\"0.75\" spreadMethod=\"repeat\" color-interpolation=\"linearRGB\"",
            stops = """
                <stop offset="25%" stop-color="#102030" stop-opacity="40%"/>
                <stop offset="75%" stop-color="#90A0B0" stop-opacity="80%"/>
            """.trimIndent(),
            rectangle = """<rect x="10" y="20" width="200" height="100" fill="url(#radial)"/>""",
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(document, listOf(radialGradientProfile(maxColorStops = 4))).representation,
        ).paint
        val radial = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])

        assertEquals(GlyphPaintPoint(0.25, 0.75), radial.c0)
        assertEquals(GlyphPaintPoint(0.25, 0.75), radial.c1)
        assertEquals(0.0, radial.radius0)
        assertEquals(0.4, radial.radius1)
        assertEquals(GlyphPaintExtendMode.REPEAT, radial.colorLine.extendMode)
        assertEquals(GlyphPaintInterpolationSpace.LINEAR_SRGB, radial.colorLine.interpolationSpace)
        assertEquals(
            listOf(
                GlyphPaintColorStop(0.0, GlyphColor(16, 32, 48), 0.4),
                GlyphPaintColorStop(0.25, GlyphColor(16, 32, 48), 0.4),
                GlyphPaintColorStop(0.75, GlyphColor(144, 160, 176), 0.8),
                GlyphPaintColorStop(1.0, GlyphColor(144, 160, 176), 0.8),
            ),
            radial.colorLine.colorStops,
        )
        assertEquals(
            GlyphAffineTransform(200.0, 0.0, 0.0, 100.0, 10.0, 20.0),
            assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).matrix,
        )
    }

    @Test
    fun noStopRadialGradientPaintsNothingWithoutReachedPaintCapabilities() {
        val profile = radialGradientProfile(
            nodeKinds = listOf(GlyphPaintNodeKind.SOLID),
            extendModes = emptyList(),
            interpolationSpaces = emptyList(),
            alphaInterpolationModes = emptyList(),
            maxColorStops = 0,
            maxTransforms = 0,
        )

        assertIs<GlyphRepresentation.Empty>(
            resolveSvgDocument(radialSvgDocument(stops = ""), listOf(profile)).representation,
        )
    }

    @Test
    fun oneStopAndZeroRadiusRadialsUseTheFinalStopWithOnlyReachedCapabilities() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <radialGradient id="one" cx="1e308" fx="1e308">
                  <stop offset="40%" stop-color="#AABBCC" stop-opacity="40%"/>
                </radialGradient>
                <radialGradient id="zero" r="0" fx="125%" fy="-25%">
                  <stop offset="0%" stop-color="#112233" stop-opacity="20%"/>
                  <stop offset="100%" stop-color="#445566" stop-opacity="70%"/>
                </radialGradient>
              </defs>
              <rect x="10" y="20" width="30" height="40" fill="url(#one)"/>
              <rect x="50" y="60" width="70" height="80" fill="url(#zero)"/>
            </svg>
        """.trimIndent()
        val profile = radialGradientProfile(
            nodeKinds = listOf(GlyphPaintNodeKind.SOLID, GlyphPaintNodeKind.PATH_CLIP, GlyphPaintNodeKind.GROUP),
            extendModes = emptyList(),
            interpolationSpaces = emptyList(),
            alphaInterpolationModes = emptyList(),
            maxNodes = 5,
            maxReferences = 4,
            maxGradients = 2,
            maxColorStops = 3,
            maxTransforms = 0,
            maxPaths = 2,
            maxClips = 2,
        )

        val paint = assertIs<GlyphRepresentation.Paint>(resolveSvgDocument(document, listOf(profile)).representation).paint

        assertEquals(GlyphPaintNode.Solid(GlyphColor(170, 187, 204), 0.4), paint.nodes[0])
        assertEquals(0, assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).paint)
        assertEquals(GlyphPaintNode.Solid(GlyphColor(68, 85, 102), 0.7), paint.nodes[2])
        assertEquals(2, assertIs<GlyphPaintNode.PathClip>(paint.nodes[3]).paint)
        assertEquals(GlyphPaintNode.Group(listOf(1, 3)), paint.nodes[4])
    }

    @Test
    fun radialPaintMustFitEveryReachedGraphBudget() {
        val document = radialSvgDocument()
        val limitedProfiles = listOf(
            radialGradientProfile(maxNodes = 2),
            radialGradientProfile(maxReferences = 1),
            radialGradientProfile(maxDepth = 2),
            radialGradientProfile(maxPaintVisits = 2),
            radialGradientProfile(maxPaths = 0),
            radialGradientProfile(maxGradients = 0),
            radialGradientProfile(maxColorStops = 1),
            radialGradientProfile(maxTransforms = 0),
            radialGradientProfile(maxClips = 0),
        )

        for (profile in limitedProfiles) {
            val error = acquireSvgFailure(document, profile)

            assertIs<FontError.ResourceLimitExceeded>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun malformedRadialGeometryIsInvalidFontData() {
        val documents = listOf(
            radialSvgDocument(attributes = "r=\"-1\""),
            radialSvgDocument(attributes = "r=\"-1e-323%\""),
            radialSvgDocument(attributes = "r=\"-1e-999\""),
            radialSvgDocument(attributes = "r=\"1.\""),
            radialSvgDocument(attributes = "cx=\"1.e2\" fx=\"1.e2\""),
            radialSvgDocument(attributes = "fy=\"50 %\""),
        )

        for (document in documents) {
            val error = acquireSvgFailure(document, radialGradientProfile())

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun explicitNegativeZeroRadialRadiusKeepsZeroRadiusSemantics() {
        val profile = radialGradientProfile(
            nodeKinds = listOf(GlyphPaintNodeKind.SOLID, GlyphPaintNodeKind.PATH_CLIP),
            extendModes = emptyList(),
            interpolationSpaces = emptyList(),
            alphaInterpolationModes = emptyList(),
            maxNodes = 2,
            maxReferences = 1,
            maxDepth = 3,
            maxPaintVisits = 2,
            maxTransforms = 0,
        )

        val paint = assertIs<GlyphRepresentation.Paint>(
            resolveSvgDocument(radialSvgDocument(attributes = "r=\"-0.0%\""), listOf(profile)).representation,
        ).paint

        assertEquals(GlyphPaintNode.Solid(GlyphColor(144, 160, 176), 1.0), paint.nodes[0])
        assertEquals(0, assertIs<GlyphPaintNode.PathClip>(paint.nodes[1]).paint)
    }

    @Test
    fun nonConcentricRadialFocusIsOutsideTheSupportedSubsetWithoutClamping() {
        val documents = listOf(
            radialSvgDocument(attributes = "fx=\"60%\""),
            radialSvgDocument(attributes = "fx=\"100%\""),
            radialSvgDocument(attributes = "fx=\"125%\""),
        )

        for (document in documents) {
            val error = acquireSvgFailure(document, radialGradientProfile())

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun unsupportedRadialFeaturesAreRejectedBeforePublication() {
        val documents = listOf(
            radialSvgDocument(attributes = "fr=\"0\""),
            radialSvgDocument(attributes = "href=\"#base\""),
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\""),
        )

        for (document in documents) {
            val error = acquireSvgFailure(document, radialGradientProfile())

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun radialReferencesRemainBackwardOnlyAndIdsRemainGloballyUniqueAcrossGradientKinds() {
        val forwardReference = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <rect x="0" y="0" width="10" height="10" fill="url(#later)"/>
              <defs><radialGradient id="later"><stop offset="0" stop-color="#000000"/></radialGradient></defs>
            </svg>
        """.trimIndent()
        assertIs<FontError.UnsupportedRepresentationProfile>(
            acquireSvgFailure(forwardReference, radialGradientProfile()),
        )
        val collisions = listOf(
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs>
                    <linearGradient id="same"><stop offset="0" stop-color="#000000"/></linearGradient>
                    <radialGradient id="same"><stop offset="1" stop-color="#FFFFFF"/></radialGradient>
                  </defs>
                </svg>
            """.trimIndent(),
            """
                <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
                  <defs><radialGradient id="glyph1"><stop offset="0" stop-color="#000000"/></radialGradient></defs>
                </svg>
            """.trimIndent(),
        )
        for (document in collisions) {
            val error = acquireSvgFailure(document, radialGradientProfile(maxGradients = 2))

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun reportsRadialRectangleOutlinePointExhaustionAsAResourceLimit() {
        val twoStops = """
            <stop offset="0" stop-color="#102030"/>
            <stop offset="1" stop-color="#90A0B0"/>
        """.trimIndent()
        val cases = listOf(
            Triple("", twoStops, null),
            Triple("", """<stop offset="1" stop-color="#90A0B0"/>""", GlyphPaintNode.Solid(GlyphColor(144, 160, 176), 1.0)),
            Triple("r=\"0\"", twoStops, GlyphPaintNode.Solid(GlyphColor(144, 160, 176), 1.0)),
        )
        for ((attributes, stops, expectedSolid) in cases) {
            val document = radialSvgDocument(
                attributes = attributes,
                stops = stops,
                rectangle = """<rect x="10" y="20" width="30" height="40" fill="url(#radial)"/>""",
            )
            val tooSmall = radialGradientProfile(outlineProfile = svgOutlineProfile().copy(maxPoints = 3))

            val failure = assertIs<FontError.ResourceLimitExceeded>(acquireSvgFailure(document, tooSmall))
            assertEquals(FontDiagnosticLocation.Table("SVG "), failure.location)

            val admitted = radialGradientProfile(outlineProfile = svgOutlineProfile().copy(maxPoints = 4))
            val paint = assertIs<GlyphRepresentation.Paint>(resolveSvgDocument(document, listOf(admitted)).representation).paint
            if (expectedSolid == null) {
                val radial = assertIs<GlyphPaintNode.RadialGradient>(paint.nodes[0])
                assertEquals(GlyphPaintPoint(0.5, 0.5), radial.c0)
                assertEquals(0.0, radial.radius0)
                assertEquals(GlyphPaintPoint(0.5, 0.5), radial.c1)
                assertEquals(0.5, radial.radius1)
                assertEquals(
                    listOf(
                        GlyphPaintColorStop(0.0, GlyphColor(16, 32, 48), 1.0),
                        GlyphPaintColorStop(1.0, GlyphColor(144, 160, 176), 1.0),
                    ),
                    radial.colorLine.colorStops,
                )
                assertEquals(
                    GlyphAffineTransform(30.0, 0.0, 0.0, 40.0, 10.0, 20.0),
                    assertIs<GlyphPaintNode.Transform>(paint.nodes[1]).matrix,
                )
            } else {
                assertEquals(expectedSolid, paint.nodes[0])
            }
            val clip = assertIs<GlyphPaintNode.PathClip>(paint.nodes[paint.rootNode])
            assertEquals(
                listOf(
                    GlyphPaintPathCommand.MoveTo(10.0, 20.0),
                    GlyphPaintPathCommand.LineTo(40.0, 20.0),
                    GlyphPaintPathCommand.LineTo(40.0, 60.0),
                    GlyphPaintPathCommand.LineTo(10.0, 60.0),
                    GlyphPaintPathCommand.Close,
                ),
                clip.path.commands,
            )
        }
    }

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
    fun noStopGradientEmitsNoPathWhenPaintedOutlinesExceedTheProfile() {
        val document = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              <defs>
                <linearGradient id="empty" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="1" y2="0"/>
              </defs>
              <path d="M0 0 L10 0 L0 10 Z" fill="url(#empty)"/>
              <rect x="20" y="30" width="40" height="50" fill="url(#empty)"/>
            </svg>
        """.trimIndent()

        assertIs<GlyphRepresentation.Empty>(
            resolveSvgDocument(
                document,
                listOf(gradientProfile(maxOutlineContours = 1, maxOutlinePoints = 2)),
            ).representation,
        )
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
    fun unclippedSingularGradientRectangleStaysEmptyWithUnusedCapabilityAndGraphLimits() {
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
        val profiles = listOf(
            gradientProfile(nodeKinds = listOf(GlyphPaintNodeKind.PATH_CLIP)),
            gradientProfile(maxNodes = 1),
        )

        for (profile in profiles) {
            assertEquals(GlyphRepresentation.Empty, resolveSvgDocument(document, listOf(profile)).representation)
        }
    }

    @Test
    fun singularGroupMatrixValidatesThenOmitsPathPaint() {
        val rankRevivingGroups = """
            <g transform="matrix(1 3 1 3 0 0)">
              <g transform="matrix(.1 .1 .1 .2 0 0)">
                %s
              </g>
            </g>
        """.trimIndent()
        val valid = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              ${rankRevivingGroups.format("""<path d="M0 0 L1 0 L1 1 Z" fill="#102030"/>""")}
            </svg>
        """.trimIndent()
        val malformedPath = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              ${rankRevivingGroups.format("""<path d="M0" fill="#102030"/>""")}
            </svg>
        """.trimIndent()
        val unsupportedFill = """
            <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
              ${rankRevivingGroups.format("""<path d="M0 0 L1 0 L1 1 Z" fill="url(https://example.test/paint)"/>""")}
            </svg>
        """.trimIndent()

        val profile = paintProfile(maxDepth = 3)
        assertIs<GlyphRepresentation.Empty>(resolveSvgDocument(valid, listOf(profile)).representation)
        assertIs<FontError.FontDataFailure>(acquireSvgFailure(malformedPath, profile))
        assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(unsupportedFill, profile))
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
            "<svg xmlns=\"http://www.w3.org/2000/svg\" id=\"glyph1\"><defs><linearGradient id=\"derived\" href=\"#base\"><stop offset=\"0\" stop-color=\"#000000\"/></linearGradient></defs><rect x=\"0\" y=\"0\" width=\"10\" height=\"10\" fill=\"url(#derived)\"/></svg>",
        )

        for (document in unsupportedDocuments) {
            val error = acquireSvgFailure(document, gradientProfile())
            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun userSpacePercentagesAndViewportDependentDefaultsAreTypedUnsupportedBeforePublication() {
        val linearDocuments = listOf(
            userSpaceLinearSvgDocument(attributes = "x1=\"0\" y1=\"0\" y2=\"0\""),
            userSpaceLinearSvgDocument(attributes = "x1=\"0\" y1=\"0\" x2=\"100%\" y2=\"0\""),
        )
        for (document in linearDocuments) {
            val error = acquireSvgFailure(document, listOf(schema2GradientProfile(), gradientProfile()))

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }

        val radialDocuments = listOf(
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\" cy=\"0\" r=\"10\""),
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"50%\" cy=\"0\" r=\"10\""),
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\""),
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\" r=\"50%\""),
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\" r=\"10\" fx=\"50%\""),
        )
        for (document in radialDocuments) {
            val error = acquireSvgFailure(document, radialGradientProfile())

            assertIs<FontError.UnsupportedRepresentationProfile>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }
    }

    @Test
    fun negativeUserSpacePercentageRadiusIsInvalidBeforeViewportDependentExclusion() {
        val document = radialSvgDocument(
            attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\" r=\"-1%\"",
        )

        val error = acquireSvgFailure(document, radialGradientProfile())

        assertIs<FontError.FontDataFailure>(error)
        assertEquals("font.svg.invalid-gradient-radius", error.code)
        assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
    }

    @Test
    fun userSpaceCoordinatesAreValidatedBeforeEmptyOrSolidReduction() {
        val viewportDependentDocuments = listOf(
            userSpaceLinearSvgDocument(
                attributes = "x1=\"0\" y1=\"0\" x2=\"100%\" y2=\"0\"",
                stops = """<stop offset="1" stop-color="#90A0B0"/>""",
            ) to gradientProfile(),
            radialSvgDocument(
                attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\"",
                stops = "",
            ) to radialGradientProfile(),
        )

        for ((document, profile) in viewportDependentDocuments) {
            assertIs<FontError.UnsupportedRepresentationProfile>(acquireSvgFailure(document, profile))
        }
    }

    @Test
    fun malformedOrUnsupportedUserSpaceGeometryKeepsItsTypedErrorClassification() {
        val malformedDocuments = listOf(
            userSpaceLinearSvgDocument(attributes = "x1=\"1.\" y1=\"0\" x2=\"10\" y2=\"0\"") to gradientProfile(),
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"1.e2\" cy=\"0\" r=\"10\"") to
                radialGradientProfile(),
            radialSvgDocument(attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"0\" cy=\"0\" r=\"-1\"") to
                radialGradientProfile(),
        )
        for ((document, profile) in malformedDocuments) {
            val error = acquireSvgFailure(document, profile)

            assertIs<FontError.FontDataFailure>(error)
            assertEquals("SVG ", assertIs<FontDiagnosticLocation.Table>(error.location).tag)
        }

        val unsupportedDocuments = listOf(
            userSpaceLinearSvgDocument(
                units = "viewport",
                attributes = "x1=\"0\" y1=\"0\" x2=\"10\" y2=\"0\"",
            ) to gradientProfile(),
            radialSvgDocument(
                attributes = "gradientUnits=\"viewport\" cx=\"0\" cy=\"0\" r=\"10\"",
            ) to radialGradientProfile(),
            radialSvgDocument(
                attributes = "gradientUnits=\"userSpaceOnUse\" cx=\"10\" cy=\"20\" r=\"5\" fx=\"11\" fy=\"20\"",
            ) to radialGradientProfile(),
        )
        for ((document, profile) in unsupportedDocuments) {
            val error = acquireSvgFailure(document, profile)

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
        maxDepth: Int = 2,
        maxSvgCompressedDocumentBytes: Int = maxSourceBytes,
        maxSvgDecodedDocumentBytes: Int = maxSourceBytes,
        maxSvgTotalDecodedBytes: Int = maxSourceBytes,
        maxSvgTransformOperations: Int = 4_096,
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP),
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = PaintGraphLimits(
            maxNodes = 4,
            maxReferences = 4,
            maxDepth = maxDepth,
            maxSourceBytes = maxSourceBytes,
            maxPaths = 2,
            maxPaintVisits = maxPaintVisits,
            maxSvgCompressedDocumentBytes = maxSvgCompressedDocumentBytes,
            maxSvgDecodedDocumentBytes = maxSvgDecodedDocumentBytes,
            maxSvgTotalDecodedBytes = maxSvgTotalDecodedBytes,
            maxSvgTransformOperations = maxSvgTransformOperations,
        ),
        outlineProfile = OutlineProfile(
            maxBytes = 16 * 1024,
            maxContours = 8,
            maxPoints = 64,
            maxCompositeDepth = 1,
            maxCompositeComponents = 1,
        ),
    )

    private fun clipProfile(
        schemaVersion: Int = 3,
        nodeKinds: List<GlyphPaintNodeKind> = listOf(
            GlyphPaintNodeKind.PATH,
            GlyphPaintNodeKind.PATH_CLIP,
            GlyphPaintNodeKind.GROUP,
        ),
        compositionModes: List<org.graphiks.kalligraphie.api.GlyphPaintCompositionMode> =
            listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        maxNodes: Int = 2,
        maxReferences: Int = 1,
        maxDepth: Int = 3,
        maxPaintVisits: Int = 2,
        maxPaths: Int = 2,
        maxClips: Int = 1,
        maxOutlineBytes: Int = 16 * 1024,
        maxOutlineContours: Int = 8,
        maxOutlinePoints: Int = 64,
        maxSvgTransformOperations: Int = 4_096,
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = nodeKinds,
        acceptedCompositionModes = compositionModes,
        limits = PaintGraphLimits(
            maxNodes = maxNodes,
            maxReferences = maxReferences,
            maxDepth = maxDepth,
            maxSourceBytes = 16 * 1024,
            maxPaths = maxPaths,
            maxClips = maxClips,
            maxPaintVisits = maxPaintVisits,
            maxSvgTransformOperations = maxSvgTransformOperations,
        ),
        outlineProfile = svgOutlineProfile(maxOutlineBytes, maxOutlineContours, maxOutlinePoints),
        schemaVersion = schemaVersion,
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
        nodeKinds: List<GlyphPaintNodeKind> = listOf(
            GlyphPaintNodeKind.PATH,
            GlyphPaintNodeKind.GROUP,
            GlyphPaintNodeKind.SOLID,
            GlyphPaintNodeKind.LINEAR_GRADIENT,
            GlyphPaintNodeKind.PATH_CLIP,
        ),
        extendModes: List<GlyphPaintExtendMode> = GlyphPaintExtendMode.entries.toList(),
        interpolationSpaces: List<GlyphPaintInterpolationSpace> = GlyphPaintInterpolationSpace.entries.toList(),
        alphaInterpolationModes: List<GlyphPaintAlphaInterpolationMode> =
            listOf(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED),
        maxNodes: Int = 5,
        maxReferences: Int = 4,
        maxDepth: Int = 8,
        maxPaintVisits: Int = maxNodes,
        maxPaths: Int = 2,
        maxGradients: Int = 1,
        maxColorStops: Int = 4,
        maxTransforms: Int = 0,
        maxClips: Int = 2,
        maxSvgTransformOperations: Int = 4_096,
        maxOutlineBytes: Int = 16 * 1024,
        maxOutlineContours: Int = 8,
        maxOutlinePoints: Int = 64,
        outlineProfile: OutlineProfile = svgOutlineProfile(maxOutlineBytes, maxOutlineContours, maxOutlinePoints),
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = nodeKinds,
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = gradientLimits(
            maxNodes = maxNodes,
            maxReferences = maxReferences,
            maxDepth = maxDepth,
            maxPaintVisits = maxPaintVisits,
            maxPaths = maxPaths,
            maxGradients = maxGradients,
            maxColorStops = maxColorStops,
            maxTransforms = maxTransforms,
            maxClips = maxClips,
            maxSvgTransformOperations = maxSvgTransformOperations,
        ),
        outlineProfile = outlineProfile,
        schemaVersion = 3,
        acceptedGradientExtendModes = extendModes,
        acceptedGradientInterpolationSpaces = interpolationSpaces,
        acceptedGradientAlphaInterpolationModes = alphaInterpolationModes,
    )

    private fun radialGradientProfile(
        nodeKinds: List<GlyphPaintNodeKind> = listOf(
            GlyphPaintNodeKind.SOLID,
            GlyphPaintNodeKind.GROUP,
            GlyphPaintNodeKind.RADIAL_GRADIENT,
            GlyphPaintNodeKind.TRANSFORM,
            GlyphPaintNodeKind.PATH_CLIP,
        ),
        extendModes: List<GlyphPaintExtendMode> = GlyphPaintExtendMode.entries.toList(),
        interpolationSpaces: List<GlyphPaintInterpolationSpace> = GlyphPaintInterpolationSpace.entries.toList(),
        alphaInterpolationModes: List<GlyphPaintAlphaInterpolationMode> =
            listOf(GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED),
        maxNodes: Int = 3,
        maxReferences: Int = 2,
        maxDepth: Int = 3,
        maxPaintVisits: Int = maxNodes,
        maxPaths: Int = 1,
        maxGradients: Int = 1,
        maxColorStops: Int = 2,
        maxTransforms: Int = 1,
        maxClips: Int = 1,
        maxSvgTransformOperations: Int = 4_096,
        maxOutlineBytes: Int = 16 * 1024,
        maxOutlineContours: Int = 8,
        maxOutlinePoints: Int = 64,
        outlineProfile: OutlineProfile = svgOutlineProfile(maxOutlineBytes, maxOutlineContours, maxOutlinePoints),
    ): PaintGraphProfile = PaintGraphProfile(
        acceptedNodeKinds = nodeKinds,
        acceptedCompositionModes = listOf(org.graphiks.kalligraphie.api.GlyphPaintCompositionMode.SOURCE_OVER),
        limits = gradientLimits(
            maxNodes = maxNodes,
            maxReferences = maxReferences,
            maxDepth = maxDepth,
            maxPaintVisits = maxPaintVisits,
            maxPaths = maxPaths,
            maxGradients = maxGradients,
            maxColorStops = maxColorStops,
            maxTransforms = maxTransforms,
            maxClips = maxClips,
            maxSvgTransformOperations = maxSvgTransformOperations,
        ),
        outlineProfile = outlineProfile,
        schemaVersion = 3,
        acceptedGradientExtendModes = extendModes,
        acceptedGradientInterpolationSpaces = interpolationSpaces,
        acceptedGradientAlphaInterpolationModes = alphaInterpolationModes,
    )

    private fun gradientLimits(
        maxNodes: Int = 5,
        maxReferences: Int = 4,
        maxDepth: Int = 8,
        maxPaintVisits: Int = maxNodes,
        maxPaths: Int = 2,
        maxGradients: Int = 2,
        maxColorStops: Int = 6,
        maxTransforms: Int = 0,
        maxClips: Int = 2,
        maxSvgTransformOperations: Int = 4_096,
    ): PaintGraphLimits = PaintGraphLimits(
        maxNodes = maxNodes,
        maxReferences = maxReferences,
        maxDepth = maxDepth,
        maxSourceBytes = 16 * 1024,
        maxPaths = maxPaths,
        maxGradients = maxGradients,
        maxColorStops = maxColorStops,
        maxTransforms = maxTransforms,
        maxClips = maxClips,
        maxPaintVisits = maxPaintVisits,
        maxSvgTransformOperations = maxSvgTransformOperations,
    )

    private fun svgOutlineProfile(
        maxBytes: Int = 16 * 1024,
        maxContours: Int = 8,
        maxPoints: Int = 64,
    ): OutlineProfile = OutlineProfile(
        maxBytes = maxBytes,
        maxContours = maxContours,
        maxPoints = maxPoints,
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

    private fun acquireSvgFailure(document: String, profile: PaintGraphProfile): FontError =
        acquireSvgFailure(document, listOf(profile))

    private fun acquireSvgFailure(document: String, profiles: List<PaintGraphProfile>): FontError {
        val catalog = success(
            Kalligraphie.embedded(
                fixtureWithSvgDocumentPayload(document.encodeToByteArray()),
                FontSourceProvenance("Twemoji fixture with rejected Kalligraphie-authored SVG gradient document"),
            ),
        )
        val requirements = FontAccessRequirementsSnapshot.renderable(profiles)
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

    private fun radialSvgDocument(
        attributes: String = "",
        stops: String = """
            <stop offset="0%" stop-color="#102030" stop-opacity="25%"/>
            <stop offset="100%" stop-color="#90A0B0"/>
        """.trimIndent(),
        rectangle: String = """<rect x="0" y="0" width="100" height="100" fill="url(#radial)"/>""",
    ): String = """
        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
          <defs>
            <radialGradient id="radial" $attributes>
              $stops
            </radialGradient>
          </defs>
          $rectangle
        </svg>
    """.trimIndent()

    private fun userSpaceLinearSvgDocument(
        units: String = "userSpaceOnUse",
        attributes: String,
        stops: String = """
            <stop offset="0" stop-color="#102030"/>
            <stop offset="1" stop-color="#90A0B0"/>
        """.trimIndent(),
    ): String = """
        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
          <rect x="0" y="0" width="5" height="5" fill="#010203"/>
          <defs>
            <linearGradient id="absolute" gradientUnits="$units" $attributes>
              $stops
            </linearGradient>
          </defs>
          <rect x="10" y="20" width="30" height="40" fill="url(#absolute)"/>
        </svg>
    """.trimIndent()

    private fun userSpaceLinearPathSvgDocument(): String = """
        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
          <defs>
            <linearGradient id="absolute" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="10" y2="0">
              <stop offset="0" stop-color="#102030"/>
              <stop offset="1" stop-color="#90A0B0"/>
            </linearGradient>
          </defs>
          <path d="M0 0 L10 0 L0 10 Z" fill="url(#absolute)"/>
        </svg>
    """.trimIndent()

    private fun singularPathGradientDocument(
        definition: String,
        pathData: String,
    ): String = """
        <svg xmlns="http://www.w3.org/2000/svg" id="glyph1">
          <defs>$definition</defs>
          <g transform="scale(0 1)">
            <path d="$pathData" fill="url(#paint)"/>
          </g>
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
        assertIs<FontOperationResult.Success<T>>(result, result.toString()).value
}
