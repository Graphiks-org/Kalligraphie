@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class GlyfReaderTest {
    @Test
    fun computesSimpleGlyphBoundsFromDecodedPointsNotHeaderBounds() {
        val glyph = simpleGlyphWithFalseHeaderBounds()
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph.size),
                    "glyf" to glyph,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val success = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(result)
        assertEquals(DesignBounds(10, 15, 50, 40), success.value.bounds)
        assertEquals(3, success.value.pointCount)
    }

    @Test
    fun readsMaxComponentDepthFromFieldAfterMaxComponentElements() {
        val glyph0 = compositeGlyph(componentGlyphIds = listOf(1))
        val glyph1 = compositeGlyph(componentGlyphIds = listOf(2))
        val glyph2 = simpleGlyphWithFalseHeaderBounds()
        val glyf = glyph0 + glyph1 + glyph2
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 3,
                maxComponentElements = 1,
                maxComponentDepth = 4,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph0.size, glyph0.size + glyph1.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val success = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(result)
        assertEquals(DesignBounds(10, 15, 50, 40), success.value.bounds)
    }

    @Test
    fun rejectsCompositeGlyphWhenMaxComponentElementsBudgetIsExceeded() {
        val glyph0 = compositeGlyph(componentGlyphIds = listOf(1, 2))
        val glyph1 = simpleGlyphWithFalseHeaderBounds()
        val glyph2 = simpleGlyphWithFalseHeaderBounds()
        val glyf = glyph0 + glyph1 + glyph2
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 3,
                maxComponentElements = 1,
                maxComponentDepth = 4,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph0.size, glyph0.size + glyph1.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
    }

    @Test
    fun rejectsLocaOffsetPastGlyfLength() {
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, 8),
                    "glyf" to ByteArray(4),
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.loca.out-of-range", failure.error.code)
    }

    @Test
    fun rejectsTruncatedGlyphHeaderInsideValidLocaRange() {
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, 8),
                    "glyf" to ByteArray(8),
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.truncated", failure.error.code)
    }

    @Test
    fun rejectsCompositeThatReentersTheActiveGlyphPath() {
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, 18),
                    "glyf" to compositeGlyphSelfCycle(),
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.composite-cycle", failure.error.code)
    }

    @Test
    fun rejectsSimpleGlyphWhenMaxpMaxPointsIsZero() {
        val glyph = simpleGlyphWithFalseHeaderBounds()
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                maxPoints = 0,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph.size),
                    "glyf" to glyph,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals(3L, failure.diagnostics.single().data.observedValue)
        assertEquals(0L, failure.diagnostics.single().data.limit)
    }

    @Test
    fun rejectsGlyphBytesBeforeParsingWhenProfileByteBudgetIsExceeded() {
        val glyph = simpleGlyphWithFalseHeaderBounds()
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph.size),
                    "glyf" to glyph,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(
            parsed.bytes,
            parsed.font,
            GlyphId(0),
            outlineProfile(maxBytes = 16),
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals(glyph.size.toLong(), failure.diagnostics.single().data.observedValue)
        assertEquals(16L, failure.diagnostics.single().data.limit)
    }

    @Test
    fun rejectsCompositeWhenMaxpComponentElementsIsZero() {
        val parent = compositeGlyph(componentGlyphIds = listOf(1))
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                maxComponentElements = 0,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals(1L, failure.diagnostics.single().data.observedValue)
        assertEquals(0L, failure.diagnostics.single().data.limit)
    }

    @Test
    fun rejectsCompositeWhenMaxpComponentDepthIsZero() {
        val parent = compositeGlyph(componentGlyphIds = listOf(1))
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                maxComponentDepth = 0,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals(1L, failure.diagnostics.single().data.observedValue)
        assertEquals(0L, failure.diagnostics.single().data.limit)
    }

    @Test
    fun locaOffsetsAndScalerOutlineListsRejectMutableCasts() {
        val glyph = simpleGlyphWithFalseHeaderBounds()
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph.size),
                    "glyf" to glyph,
                ),
            ),
        )
        val loca = assertIs<FontOperationResult.Success<LocaTable>>(
            LocaReader.readLoca(parsed.bytes, parsed.font, glyph.size),
        ).value
        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile()),
        ).value

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (loca.offsets as MutableList<Int>)[0] = 2
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (outline.contours as MutableList<org.graphiks.kalligraphie.api.GlyphContour>)[0] = outline.contours[0]
        }
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (outline.components as MutableList<org.graphiks.kalligraphie.api.GlyphComponentReference>).add(
                org.graphiks.kalligraphie.api.GlyphComponentReference(
                    0,
                    org.graphiks.kalligraphie.api.GlyphComponentTransform(0, 0),
                ),
            )
        }
    }

    @Test
    fun appliesVersionedUniformScaleToCompositeContours() {
        val parent = compositeGlyphWithUniformScale(componentGlyphId = 1, scale = 8_192)
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(result).value
        assertEquals(DesignBounds(5, 7, 25, 20), outline.bounds)
        assertEquals(GlyphOutlineCommand.LineTo(25.0, 7.5), outline.contours.single().commands[2])
    }

    @Test
    fun appliesVersionedTwoByTwoTransformToCompositeContours() {
        val parent = compositeGlyphWithTwoByTwo(
            componentGlyphId = 1,
            xx = 0,
            yx = 16_384,
            xy = -16_384,
            yy = 0,
        )
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(result).value
        assertEquals(DesignBounds(-40, 10, -15, 50), outline.bounds)
    }

    @Test
    fun rejectsFirstCompositeComponentUsingPointNumbers() {
        val parent = compositeGlyphWithFirstPointAlignment(componentGlyphId = 1)
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.invalid-component-placement", failure.error.code)
    }

    @Test
    fun alignsLaterCompositeComponentUsingParentAndChildPointNumbers() {
        val parent = compositeGlyphWithPointAlignment(
            componentGlyphId = 1,
            parentPoint = 1,
            childPoint = 0,
        )
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(result).value
        assertEquals(DesignBounds(10, 15, 70, 60), outline.bounds)
        assertEquals(6, outline.pointCount)
    }

    @Test
    fun alignsLaterCompositeComponentPointsAfterTransformingTheChild() {
        val parent = compositeGlyphWithPointAlignedSecondComponent()
        val firstChild = simpleGlyphWithFalseHeaderBounds()
        val nestedSecondChild = compositeGlyph(componentGlyphIds = listOf(3))
        val secondChildLeaf = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + firstChild + nestedSecondChild + secondChildLeaf
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 4,
                tables = mapOf(
                    "loca" to locaFormat0(
                        0,
                        parent.size,
                        parent.size + firstChild.size,
                        parent.size + firstChild.size + nestedSecondChild.size,
                        glyf.size,
                    ),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(result).value
        assertEquals(DesignBounds(110, 215, 150, 253), outline.bounds)
        assertEquals(
            listOf(
                GlyphOutlineCommand.MoveTo(110, 220),
                GlyphOutlineCommand.LineTo(130, 240),
                GlyphOutlineCommand.LineTo(150, 215),
                GlyphOutlineCommand.Close,
            ),
            outline.contours[0].commands,
        )
        assertEquals(
            listOf(
                GlyphOutlineCommand.MoveTo(110.0, 242.5),
                GlyphOutlineCommand.LineTo(120.0, 252.5),
                GlyphOutlineCommand.LineTo(130, 240),
                GlyphOutlineCommand.Close,
            ),
            outline.contours[1].commands,
        )
    }

    @Test
    fun rejectsMutuallyExclusiveScaledAndUnscaledOffsetFlags() {
        val parent = compositeGlyphWithOffsetFlags(
            componentGlyphId = 1,
            offsetFlags = 0x0800 or 0x1000,
        )
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile())

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertEquals("font.glyf.invalid-component-flags", failure.error.code)
    }

    @Test
    fun compositeTransformOverflowReturnsGeometryOverflowInsteadOfSaturating() {
        val parent = compositeGlyphWithUniformScale(componentGlyphId = 1, scale = 32_767)
        val child = simpleGlyphWithRepeatedXDelta(pointCount = 32_771, delta = 32_767)
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                maxPoints = 40_000,
                maxCompositePoints = 40_000,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val result = GlyfReader.readGlyphOutline(
            parsed.bytes,
            parsed.font,
            GlyphId(0),
            outlineProfile(maxBytes = 200_000, maxPoints = 40_000),
        )

        val failure = assertIs<FontOperationResult.Failure>(result)
        assertIs<FontError.GeometryOverflow>(failure.error)
        assertEquals("font.geometry-overflow", failure.diagnostics.single().code)
    }

    @Test
    fun appliesGvarSimpleGlyphDeltasAtNonDefaultInstance() {
        val glyph = singlePointGlyph(x = 100, y = 200)
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph.size),
                    "glyf" to glyph,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    "gvar" to singleGlyphGvar(singlePointGvarRecord(xDelta = 10)),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value

        val varied = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 1f)),
            ),
        ).value
        val variedMove = assertIs<GlyphOutlineCommand.MoveTo>(varied.contours.single().commands.first())
        assertEquals(110.0, variedMove.x)
        assertEquals(200.0, variedMove.y)

        val unvaried = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(prepared, GlyphId(0), outlineProfile(), CancellationToken.none),
        ).value
        val unvariedMove = assertIs<GlyphOutlineCommand.MoveTo>(unvaried.contours.single().commands.first())
        assertEquals(100.0, unvariedMove.x)
    }

    @Test
    fun appliesGvarCompositeComponentOffsetDeltasAtNonDefaultInstance() {
        val composite = compositeGlyph(componentGlyphIds = listOf(1, 2))
        val child1 = singlePointGlyph(x = 0, y = 0)
        val child2 = singlePointGlyph(x = 0, y = 0)
        val glyf = composite + child1 + child2
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 3,
                tables = mapOf(
                    "loca" to locaFormat0(0, composite.size, composite.size + child1.size, glyf.size),
                    "glyf" to glyf,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    "gvar" to gvarTable(
                        axisCount = 1,
                        glyphRecords = listOf(
                            gvarGlyphRecord(
                                peak = listOf(1.0),
                                xDeltas = intArrayOf(100, -200, 0, 7, 0, 0),
                                yDeltas = intArrayOf(300, 50, 0, 0, 9, 0),
                            ),
                            ByteArray(0),
                            ByteArray(0),
                        ),
                    ),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value

        // Default instance: both components keep their raw (0, 0) offsets.
        val unvaried = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(prepared, GlyphId(0), outlineProfile(), CancellationToken.none),
        ).value
        assertEquals(DesignBounds(0, 0, 0, 0), unvaried.bounds)

        // Varied: component 0 -> (100, 300), component 1 -> (-200, 50).
        val varied = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 1f)),
            ),
        ).value
        assertEquals(DesignBounds(-200, 50, 100, 300), varied.bounds)
    }

    @Test
    fun appliesGvarComponentDeltaBeforeScalingScaledComponentOffset() {
        val composite = compositeGlyphWithScaledOffsetAndUniformScale(
            componentGlyphId = 1,
            offsetX = 100,
            offsetY = 200,
            scale = 8_192,
        )
        val child = singlePointGlyph(x = 0, y = 0)
        val glyf = composite + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, composite.size, glyf.size),
                    "glyf" to glyf,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    "gvar" to gvarTable(
                        axisCount = 1,
                        glyphRecords = listOf(
                            gvarGlyphRecord(
                                peak = listOf(1.0),
                                xDeltas = intArrayOf(10, 0, 0, 0, 0),
                                yDeltas = intArrayOf(20, 0, 0, 0, 0),
                            ),
                            ByteArray(0),
                        ),
                    ),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value

        val varied = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 1f)),
            ),
        ).value
        // scale * (rawOffset + delta) = 0.5 * (110, 220) = (55, 110).
        val move = assertIs<GlyphOutlineCommand.MoveTo>(varied.contours.single().commands.first())
        assertEquals(55.0, move.x)
        assertEquals(110.0, move.y)
    }

    @Test
    fun ignoresGvarDeltasForPointMatchedComponents() {
        val parent = compositeGlyphWithPointAlignment(componentGlyphId = 1, parentPoint = 1, childPoint = 0)
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    "gvar" to gvarTable(
                        axisCount = 1,
                        glyphRecords = listOf(
                            // Component 1 is point-matched: its 999 delta must have no effect.
                            gvarGlyphRecord(
                                peak = listOf(1.0),
                                xDeltas = intArrayOf(0, 999, 0, 0, 0, 0),
                                yDeltas = intArrayOf(0, 0, 0, 0, 0, 0),
                            ),
                            ByteArray(0),
                        ),
                    ),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value

        // Default instance: no tuple deltas are decoded, so this run is the all-zero-delta reference.
        val unvaried = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(prepared, GlyphId(0), outlineProfile(), CancellationToken.none),
        ).value

        val varied = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 1f)),
            ),
        ).value

        // Point alignment cancels the added translation in the assembled contours and bounds, so the
        // bounds assertions alone cannot detect a delta wrongly applied to the point-matched component.
        // The published component references retain that translation, so compare them to the zero-delta
        // run: a mutant that applied the 999 delta to component 1 would shift its transform translation
        // by -999 and fail this equality.
        assertEquals(DesignBounds(10, 15, 70, 60), unvaried.bounds)
        assertEquals(DesignBounds(10, 15, 70, 60), varied.bounds)
        assertEquals(unvaried.components, varied.components)
        assertEquals(unvaried.components[1].transform, varied.components[1].transform)
    }

    @Test
    fun reportsPointMatchedCycleBeforeOutOfRangeParentUnderCollectFirstOrdering() {
        // The point-matched second component is both out of range (parent point 99) and a self-cycle
        // (its component glyph 0 is already in the active path). Phase 1 validates every component
        // record before resolving children, so it reports the cycle; the old interleaved reader
        // checked the parent point first and reported font.glyf.component-point-out-of-range. This
        // pins that accepted diagnostic-precedence shift as intentional.
        val parent = compositeGlyphWithPointAlignedSelfCycle(parentPoint = 99, childPoint = 0)
        val child = simpleGlyphWithFalseHeaderBounds()
        val glyf = parent + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, parent.size, glyf.size),
                    "glyf" to glyf,
                ),
            ),
        )

        val failure = assertIs<FontOperationResult.Failure>(
            GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), outlineProfile()),
        )
        assertEquals("font.glyf.composite-cycle", failure.error.code)
    }

    @Test
    fun propagatesMalformedCompositeGvarFailure() {
        val composite = compositeGlyph(componentGlyphIds = listOf(1))
        val child = singlePointGlyph(x = 0, y = 0)
        val glyf = composite + child
        val truncated = ByteArray(6).also { it.writeUInt16(0, 1); it.writeUInt16(2, 6) }
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, composite.size, glyf.size),
                    "glyf" to glyf,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    "gvar" to gvarTable(axisCount = 1, glyphRecords = listOf(truncated, ByteArray(0))),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value

        val failure = assertIs<FontOperationResult.Failure>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 1f)),
            ),
        )
        assertEquals("font.variation.invalid-gvar", failure.error.code)
    }

    @Test
    fun reportsNestedCompositeComponentBudgetAtTheBreachingChild() {
        // Root glyph 0 has components [1, 2]; glyph 1 is itself a composite with one component [3].
        // With a budget of two components, phase 1 counts the root's two components successfully and
        // the nested composite is the call that crosses the budget, so the failure is located at
        // glyph 1 rather than at the root. The old interleaved traversal would count the root's
        // second component after resolving glyph 1 and report the breach at glyph 0; this test pins
        // the new collect-then-resolve ordering.
        val root = compositeGlyph(componentGlyphIds = listOf(1, 2))
        val nested = compositeGlyph(componentGlyphIds = listOf(3))
        val simple = singlePointGlyph(x = 0, y = 0)
        val leaf = singlePointGlyph(x = 0, y = 0)
        val glyf = root + nested + simple + leaf
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 4,
                tables = mapOf(
                    "loca" to locaFormat0(
                        0,
                        root.size,
                        root.size + nested.size,
                        root.size + nested.size + simple.size,
                        glyf.size,
                    ),
                    "glyf" to glyf,
                ),
            ),
        )
        val profile = OutlineProfile(
            maxBytes = 4_096,
            maxContours = 32,
            maxPoints = 256,
            maxCompositeDepth = 4,
            maxCompositeComponents = 2,
        )

        val failure = assertIs<FontOperationResult.Failure>(
            GlyfReader.readGlyphOutline(parsed.bytes, parsed.font, GlyphId(0), profile),
        )
        val error = assertIs<FontError.ResourceLimitExceeded>(failure.error)
        assertEquals(3L, failure.diagnostics.single().data.observedValue)
        assertEquals(2L, failure.diagnostics.single().data.limit)
        assertEquals(FontDiagnosticLocation.Glyph(1), error.location)
    }

    @Test
    fun exposesSimpleGlyphPhantomDeltasAtNonDefaultInstance() {
        val glyph = singlePointGlyph(x = 100, y = 200)
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph.size),
                    "glyf" to glyph,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    // 0 outline, 1 left, 2 right, 3 top, 4 bottom.
                    "gvar" to gvarTable(
                        axisCount = 1,
                        glyphRecords = listOf(
                            gvarGlyphRecord(
                                peak = listOf(1.0),
                                xDeltas = intArrayOf(0, 0, 42, 0, 0),
                                yDeltas = intArrayOf(0, 0, 0, 99, 0),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value
        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 1f)),
            ),
        ).value
        val phantoms = outline.variationPhantoms!!
        assertEquals(0.0, phantoms.leftX)
        assertEquals(42.0, phantoms.rightX)
        assertEquals(42.0, phantoms.horizontalAdvanceDelta)
        assertEquals(99.0, phantoms.topY)
        assertEquals(0.0, phantoms.bottomY)
        assertEquals(99.0, phantoms.verticalAdvanceDelta)
    }

    @Test
    fun exposesCompositePhantomDeltasAtNonDefaultInstance() {
        val composite = compositeGlyph(componentGlyphIds = listOf(1))
        val child = singlePointGlyph(x = 0, y = 0)
        val glyf = composite + child
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 2,
                tables = mapOf(
                    "loca" to locaFormat0(0, composite.size, glyf.size),
                    "glyf" to glyf,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    // 1 component + 4 phantom: 0 component, 1 left, 2 right, 3 top, 4 bottom.
                    "gvar" to gvarTable(
                        axisCount = 1,
                        glyphRecords = listOf(
                            gvarGlyphRecord(
                                peak = listOf(1.0),
                                xDeltas = intArrayOf(0, 0, 7, 0, 0),
                                yDeltas = intArrayOf(0, 0, 0, 9, 0),
                            ),
                            ByteArray(0),
                        ),
                    ),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value
        val outline = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 1f)),
            ),
        ).value
        assertEquals(7.0, outline.variationPhantoms!!.rightX)
        assertEquals(9.0, outline.variationPhantoms!!.topY)
    }

    @Test
    fun defaultInstanceCarriesNoVariationPhantoms() {
        val glyph = singlePointGlyph(x = 100, y = 200)
        val parsed = parseFont(
            minimalTrueTypeFont(
                glyphCount = 1,
                tables = mapOf(
                    "loca" to locaFormat0(0, glyph.size),
                    "glyf" to glyph,
                ),
                extraTables = mapOf(
                    "fvar" to singleAxisFvarTable(),
                    "gvar" to gvarTable(
                        axisCount = 1,
                        glyphRecords = listOf(
                            gvarGlyphRecord(
                                peak = listOf(1.0),
                                xDeltas = intArrayOf(0, 0, 42, 0, 0),
                                yDeltas = intArrayOf(0, 0, 0, 99, 0),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val prepared = assertIs<FontOperationResult.Success<PreparedGlyphData>>(
            GlyfReader.prepare(parsed.bytes, parsed.font),
        ).value
        val noAxes = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(prepared, GlyphId(0), outlineProfile(), CancellationToken.none),
        ).value
        assertNull(noAxes.variationPhantoms)
        val zeroAxes = assertIs<FontOperationResult.Success<ScalerGlyphOutline>>(
            GlyfReader.readGlyphOutline(
                prepared,
                GlyphId(0),
                outlineProfile(),
                CancellationToken.none,
                listOf(FontAxisCoordinate("wght", 0f)),
            ),
        ).value
        assertNull(zeroAxes.variationPhantoms)
    }

    @Test
    fun scalerOutlineCopyAndEqualityIncludeVariationPhantoms() {
        val base = ScalerGlyphOutline(
            glyphId = 1,
            unitsPerEm = 2_048,
            bounds = DesignBounds(0, 0, 0, 0),
            contours = emptyList(),
            pointCount = 0,
            components = emptyList(),
        )
        val withPhantoms = base.copy(variationPhantoms = GlyphVariationPhantoms(0.0, 42.0, 99.0, 0.0))
        assertEquals(0.0, withPhantoms.variationPhantoms!!.leftX)
        assertEquals(42.0, withPhantoms.variationPhantoms!!.rightX)
        assertNull(base.variationPhantoms)
        assertNotEquals(base, withPhantoms)
    }

    private fun parseFont(bytes: ByteArray): ParsedFont =
        ParsedFont(
            bytes = bytes,
            font = assertIs<FontOperationResult.Success<ParsedTrueTypeFont>>(
                SfntReader.readMetadata(FontSource(bytes, FontSourceProvenance("glyf-test.ttf"))),
            ).value,
        )

    private fun outlineProfile(
        maxBytes: Int = 4_096,
        maxPoints: Int = 256,
    ): OutlineProfile =
        OutlineProfile(
            maxBytes = maxBytes,
            maxContours = 32,
            maxPoints = maxPoints,
            maxCompositeDepth = 4,
            maxCompositeComponents = 16,
        )
}

private data class ParsedFont(
    val bytes: ByteArray,
    val font: ParsedTrueTypeFont,
)

private fun minimalTrueTypeFont(
    glyphCount: Int,
    indexToLocFormat: Int = 0,
    maxPoints: Int = 128,
    maxContours: Int = 16,
    maxCompositePoints: Int = 128,
    maxCompositeContours: Int = 16,
    maxComponentElements: Int = 8,
    maxComponentDepth: Int = 8,
    tables: Map<String, ByteArray>,
    extraTables: Map<String, ByteArray> = emptyMap(),
): ByteArray {
    val requiredTables = linkedMapOf(
        "head" to headTable(unitsPerEm = 2048, indexToLocFormat = indexToLocFormat),
        "maxp" to maxpTable(
            glyphCount = glyphCount,
            maxPoints = maxPoints,
            maxContours = maxContours,
            maxCompositePoints = maxCompositePoints,
            maxCompositeContours = maxCompositeContours,
            maxComponentElements = maxComponentElements,
            maxComponentDepth = maxComponentDepth,
        ),
        "name" to nameTable(),
        "cmap" to byteArrayOf(0, 0, 0, 0),
        "hhea" to ByteArray(36).also { it.writeUInt16(34, maxOf(glyphCount, 1)) },
        "hmtx" to ByteArray(maxOf(glyphCount, 1) * 4),
        "loca" to tables.getValue("loca"),
        "glyf" to tables.getValue("glyf"),
    )
    requiredTables.putAll(extraTables)
    val tableTags = requiredTables.keys.toList()
    val directorySize = 12 + tableTags.size * 16
    var nextOffset = directorySize
    val offsets = LinkedHashMap<String, Int>()
    for (tag in tableTags) {
        offsets[tag] = nextOffset
        nextOffset += requiredTables.getValue(tag).size
    }
    val fontBytes = ByteArray(nextOffset)
    fontBytes.writeUInt32(0, 0x00010000)
    fontBytes.writeUInt16(4, tableTags.size)
    var directoryOffset = 12
    for (tag in tableTags) {
        val table = requiredTables.getValue(tag)
        val tableOffset = offsets.getValue(tag)
        fontBytes.writeTag(directoryOffset, tag)
        fontBytes.writeUInt32(directoryOffset + 8, tableOffset)
        fontBytes.writeUInt32(directoryOffset + 12, table.size)
        table.copyInto(fontBytes, destinationOffset = tableOffset)
        directoryOffset += 16
    }
    return fontBytes
}

private fun locaFormat0(vararg offsets: Int): ByteArray =
    ByteArray(offsets.size * 2).also { bytes ->
        offsets.forEachIndexed { index, offset -> bytes.writeUInt16(index * 2, offset / 2) }
    }

private fun compositeGlyphSelfCycle(): ByteArray =
    ByteArray(18).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0003)
        bytes.writeUInt16(12, 0)
    }

private fun compositeGlyph(componentGlyphIds: List<Int>): ByteArray {
    val bytes = ByteArray(10 + componentGlyphIds.size * 8)
    bytes.writeInt16(0, -1)
    var offset = 10
    componentGlyphIds.forEachIndexed { index, glyphId ->
        val flags = if (index == componentGlyphIds.lastIndex) 0x0003 else 0x0023
        bytes.writeUInt16(offset, flags)
        bytes.writeUInt16(offset + 2, glyphId)
        bytes.writeInt16(offset + 4, 0)
        bytes.writeInt16(offset + 6, 0)
        offset += 8
    }
    return bytes
}

private fun compositeGlyphWithUniformScale(componentGlyphId: Int, scale: Int): ByteArray =
    ByteArray(20).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0003 or 0x0008)
        bytes.writeUInt16(12, componentGlyphId)
        bytes.writeInt16(14, 0)
        bytes.writeInt16(16, 0)
        bytes.writeInt16(18, scale)
    }

private fun compositeGlyphWithTwoByTwo(
    componentGlyphId: Int,
    xx: Int,
    yx: Int,
    xy: Int,
    yy: Int,
): ByteArray =
    ByteArray(26).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0003 or 0x0080)
        bytes.writeUInt16(12, componentGlyphId)
        bytes.writeInt16(14, 0)
        bytes.writeInt16(16, 0)
        bytes.writeInt16(18, xx)
        bytes.writeInt16(20, yx)
        bytes.writeInt16(22, xy)
        bytes.writeInt16(24, yy)
    }

private fun compositeGlyphWithPointAlignedSecondComponent(): ByteArray =
    ByteArray(28).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0001 or 0x0002 or 0x0020)
        bytes.writeUInt16(12, 1)
        bytes.writeInt16(14, 100)
        bytes.writeInt16(16, 200)
        bytes.writeUInt16(18, 0x0001 or 0x0008)
        bytes.writeUInt16(20, 2)
        bytes.writeUInt16(22, 1)
        bytes.writeUInt16(24, 2)
        bytes.writeInt16(26, 8_192)
    }

private fun compositeGlyphWithOffsetFlags(componentGlyphId: Int, offsetFlags: Int): ByteArray =
    ByteArray(18).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0003 or offsetFlags)
        bytes.writeUInt16(12, componentGlyphId)
        bytes.writeInt16(14, 0)
        bytes.writeInt16(16, 0)
    }

private fun compositeGlyphWithPointAlignment(
    componentGlyphId: Int,
    parentPoint: Int,
    childPoint: Int,
): ByteArray =
    ByteArray(26).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0023)
        bytes.writeUInt16(12, componentGlyphId)
        bytes.writeInt16(14, 0)
        bytes.writeInt16(16, 0)
        bytes.writeUInt16(18, 0x0001)
        bytes.writeUInt16(20, componentGlyphId)
        bytes.writeUInt16(22, parentPoint)
        bytes.writeUInt16(24, childPoint)
    }

private fun compositeGlyphWithFirstPointAlignment(componentGlyphId: Int): ByteArray =
    ByteArray(18).also { bytes ->
        bytes.writeInt16(0, -1)
        bytes.writeUInt16(10, 0x0001)
        bytes.writeUInt16(12, componentGlyphId)
        bytes.writeUInt16(14, 0)
        bytes.writeUInt16(16, 0)
    }

private fun simpleGlyphWithFalseHeaderBounds(): ByteArray =
    ByteArray(30).also { bytes ->
        bytes.writeInt16(0, 1)
        bytes.writeInt16(2, 0)
        bytes.writeInt16(4, 0)
        bytes.writeInt16(6, 100)
        bytes.writeInt16(8, 100)
        bytes.writeUInt16(10, 2)
        bytes.writeUInt16(12, 0)
        bytes[14] = 0x01
        bytes[15] = 0x01
        bytes[16] = 0x01
        bytes.writeInt16(17, 10)
        bytes.writeInt16(19, 20)
        bytes.writeInt16(21, 20)
        bytes.writeInt16(23, 20)
        bytes.writeInt16(25, 20)
        bytes.writeInt16(27, -25)
    }

private fun simpleGlyphWithRepeatedXDelta(pointCount: Int, delta: Int): ByteArray {
    require(pointCount in 1..65_536)
    val unpaddedSize = 14 + pointCount + pointCount * 2
    return ByteArray(if (unpaddedSize % 2 == 0) unpaddedSize else unpaddedSize + 1).also { bytes ->
        bytes.writeInt16(0, 1)
        bytes.writeUInt16(10, pointCount - 1)
        bytes.writeUInt16(12, 0)
        repeat(pointCount) { index -> bytes[14 + index] = 0x21 }
        var coordinateOffset = 14 + pointCount
        repeat(pointCount) {
            bytes.writeInt16(coordinateOffset, delta)
            coordinateOffset += 2
        }
    }
}

private fun headTable(unitsPerEm: Int, indexToLocFormat: Int): ByteArray =
    ByteArray(54).also { bytes ->
        bytes.writeUInt16(18, unitsPerEm)
        bytes.writeInt16(50, indexToLocFormat)
    }

private fun maxpTable(
    glyphCount: Int,
    maxPoints: Int,
    maxContours: Int,
    maxCompositePoints: Int,
    maxCompositeContours: Int,
    maxComponentElements: Int,
    maxComponentDepth: Int,
): ByteArray =
    ByteArray(32).also { bytes ->
        bytes.writeUInt32(0, 0x00010000)
        bytes.writeUInt16(4, glyphCount)
        bytes.writeUInt16(6, maxPoints)
        bytes.writeUInt16(8, maxContours)
        bytes.writeUInt16(10, maxCompositePoints)
        bytes.writeUInt16(12, maxCompositeContours)
        bytes.writeUInt16(14, 2)
        bytes.writeUInt16(28, maxComponentElements)
        bytes.writeUInt16(30, maxComponentDepth)
    }

private fun nameTable(): ByteArray {
    val family = "Test".encodeUtf16Be()
    val style = "Regular".encodeUtf16Be()
    val stringOffset = 30
    return ByteArray(stringOffset + family.size + style.size).also { bytes ->
        bytes.writeUInt16(2, 2)
        bytes.writeUInt16(4, stringOffset)
        bytes.writeNameRecord(6, 1, family.size, 0)
        bytes.writeNameRecord(18, 2, style.size, family.size)
        family.copyInto(bytes, destinationOffset = stringOffset)
        style.copyInto(bytes, destinationOffset = stringOffset + family.size)
    }
}

private fun ByteArray.writeNameRecord(recordOffset: Int, nameId: Int, length: Int, textOffset: Int) {
    writeUInt16(recordOffset, 3)
    writeUInt16(recordOffset + 2, 1)
    writeUInt16(recordOffset + 4, 0x0409)
    writeUInt16(recordOffset + 6, nameId)
    writeUInt16(recordOffset + 8, length)
    writeUInt16(recordOffset + 10, textOffset)
}

private fun String.encodeUtf16Be(): ByteArray =
    ByteArray(length * 2).also { bytes ->
        forEachIndexed { index, char ->
            bytes[index * 2] = (char.code ushr 8).toByte()
            bytes[index * 2 + 1] = char.code.toByte()
        }
    }

private fun ByteArray.writeTag(offset: Int, tag: String) {
    tag.forEachIndexed { index, char -> this[offset + index] = char.code.toByte() }
}

private fun ByteArray.writeUInt16(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

private fun ByteArray.writeInt16(offset: Int, value: Int) {
    writeUInt16(offset, value and 0xFFFF)
}

private fun ByteArray.writeUInt32(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

private fun singlePointGlyph(x: Int, y: Int): ByteArray =
    ByteArray(20).also { bytes ->
        bytes.writeInt16(0, 1)
        bytes.writeInt16(2, x)
        bytes.writeInt16(4, y)
        bytes.writeInt16(6, x)
        bytes.writeInt16(8, y)
        bytes.writeUInt16(10, 0)
        bytes.writeUInt16(12, 0)
        bytes[14] = 0x01
        bytes.writeInt16(15, x)
        bytes.writeInt16(17, y)
    }

private fun singleAxisFvarTable(): ByteArray =
    ByteArray(36).also { bytes ->
        bytes.writeUInt16(0, 1)
        bytes.writeUInt16(2, 0)
        bytes.writeUInt16(4, 16)
        bytes.writeUInt16(6, 2)
        bytes.writeUInt16(8, 1)
        bytes.writeUInt16(10, 20)
        bytes.writeUInt16(12, 0)
        bytes.writeUInt16(14, 0)
        bytes.writeTag(16, "wght")
        bytes.writeInt32Fixed(20, 100f)
        bytes.writeInt32Fixed(24, 100f)
        bytes.writeInt32Fixed(28, 900f)
        bytes.writeUInt16(32, 0)
        bytes.writeUInt16(34, 1)
    }

private fun singlePointGvarRecord(xDelta: Int): ByteArray =
    // 17 bytes are used (10 header bytes + 7 serialized bytes); the 18th byte is zero padding so
    // the short (uint16 / 2) gvar offset stays integral.
    ByteArray(18).also { record ->
        record.writeUInt16(0, 1)
        record.writeUInt16(2, 10)
        record.writeUInt16(4, 7)
        record.writeUInt16(6, 0x8000)
        record.writeInt16(8, 0x4000)
        record[10] = 0x00
        record[11] = xDelta.toByte()
        record[12] = 0x80.toByte()
        record[13] = 0x80.toByte()
        record[14] = 0x80.toByte()
        record[15] = 0x80.toByte()
        record[16] = 0x84.toByte()
    }

private fun singleGlyphGvar(glyphRecord: ByteArray): ByteArray {
    val glyphDataOffset = 24
    return ByteArray(glyphDataOffset + glyphRecord.size).also { bytes ->
        bytes.writeUInt16(0, 1)
        bytes.writeUInt16(2, 0)
        bytes.writeUInt16(4, 1)
        bytes.writeUInt16(6, 0)
        bytes.writeUInt32(8, 0)
        bytes.writeUInt16(12, 1)
        bytes.writeUInt16(14, 0)
        bytes.writeUInt32(16, glyphDataOffset)
        bytes.writeUInt16(20, 0)
        bytes.writeUInt16(22, glyphRecord.size / 2)
        glyphRecord.copyInto(bytes, glyphDataOffset)
    }
}

private fun ByteArray.writeInt32Fixed(offset: Int, value: Float) {
    writeUInt32(offset, (value * 65_536f).toInt())
}

private fun compositeGlyphWithScaledOffsetAndUniformScale(
    componentGlyphId: Int,
    offsetX: Int,
    offsetY: Int,
    scale: Int,
): ByteArray =
    ByteArray(20).also { bytes ->
        bytes.writeInt16(0, -1)
        // ARG_1_AND_2_ARE_WORDS | ARGS_ARE_XY_VALUES | WE_HAVE_A_SCALE | SCALED_COMPONENT_OFFSET.
        bytes.writeUInt16(10, 0x0003 or 0x0008 or 0x0800)
        bytes.writeUInt16(12, componentGlyphId)
        bytes.writeInt16(14, offsetX)
        bytes.writeInt16(16, offsetY)
        bytes.writeInt16(18, scale)
    }

private fun gvarTable(axisCount: Int, glyphRecords: List<ByteArray>): ByteArray {
    val headerSize = 20
    val offsetsSize = (glyphRecords.size + 1) * 2
    val glyphDataOffset = headerSize + offsetsSize
    fun evenSize(byteCount: Int): Int = (byteCount + 1) / 2 * 2
    val glyphDataSize = glyphRecords.sumOf { evenSize(it.size) }
    val bytes = ByteArray(glyphDataOffset + glyphDataSize)
    bytes.writeUInt16(0, 1)
    bytes.writeUInt16(2, 0)
    bytes.writeUInt16(4, axisCount)
    bytes.writeUInt16(6, 0)
    bytes.writeUInt32(8, 0)
    bytes.writeUInt16(12, glyphRecords.size)
    bytes.writeUInt16(14, 0)
    bytes.writeUInt32(16, glyphDataOffset)
    var cursor = 0
    glyphRecords.forEachIndexed { index, record ->
        bytes.writeUInt16(headerSize + index * 2, cursor / 2)
        record.copyInto(bytes, glyphDataOffset + cursor)
        cursor += evenSize(record.size)
    }
    bytes.writeUInt16(headerSize + glyphRecords.size * 2, cursor / 2)
    return bytes
}

private fun gvarGlyphRecord(peak: List<Double>, xDeltas: IntArray, yDeltas: IntArray): ByteArray {
    require(peak.isNotEmpty()) { "gvar peak must declare at least one axis." }
    require(xDeltas.size == yDeltas.size) { "gvar x and y delta counts must match." }
    val axisCount = peak.size
    val headerSize = 4 + 4 + axisCount * 2
    val data = packedDeltas(xDeltas) + packedDeltas(yDeltas)
    val record = ByteArray((headerSize + data.size + 1) / 2 * 2)
    record.writeUInt16(0, 1)
    record.writeUInt16(2, headerSize)
    record.writeUInt16(4, data.size)
    record.writeUInt16(6, 0x8000)
    peak.forEachIndexed { axis, value ->
        record.writeInt16(8 + axis * 2, (value * 16_384.0).toInt())
    }
    data.copyInto(record, headerSize)
    return record
}

private fun packedDeltas(values: IntArray): ByteArray {
    val body = ArrayList<Byte>(values.size * 3)
    for (value in values) {
        when {
            value == 0 -> body += 0x80.toByte()
            value in -128..127 -> {
                body += 0x00
                body += value.toByte()
            }
            else -> {
                body += 0x40.toByte()
                body += (value ushr 8).toByte()
                body += value.toByte()
            }
        }
    }
    return body.toByteArray()
}

private fun compositeGlyphWithPointAlignedSelfCycle(parentPoint: Int, childPoint: Int): ByteArray =
    ByteArray(26).also { bytes ->
        bytes.writeInt16(0, -1)
        // First component: words | XY offsets | more components, glyph 1 at offset (0, 0).
        bytes.writeUInt16(10, 0x0023)
        bytes.writeUInt16(12, 1)
        bytes.writeInt16(14, 0)
        bytes.writeInt16(16, 0)
        // Second component: words-only point match whose component glyph 0 re-enters the active path.
        bytes.writeUInt16(18, 0x0001)
        bytes.writeUInt16(20, 0)
        bytes.writeUInt16(22, parentPoint)
        bytes.writeUInt16(24, childPoint)
    }
