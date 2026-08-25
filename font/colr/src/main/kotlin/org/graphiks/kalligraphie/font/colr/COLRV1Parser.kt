package org.graphiks.kalligraphie.font.colr

internal const val COLR_V1_HEADER_SIZE = 34
internal const val COLR_V1_BASE_GLYPH_LIST_OFFSET = 14
internal const val COLR_V1_LAYER_LIST_OFFSET = 18
internal const val COLR_V1_CLIP_LIST_OFFSET = 22
internal const val COLR_V1_BASE_GLYPH_PAINT_RECORD_SIZE = 6
internal const val COLR_V1_CLIP_LIST_HEADER_SIZE = 5
internal const val COLR_V1_CLIP_RECORD_SIZE = 7
internal const val COLR_V1_CLIP_BOX_FORMAT1_SIZE = 9
internal const val COLR_V1_CLIP_BOX_FORMAT2_SIZE = 13
internal const val COLR_V1_PAINT_COLR_LAYERS_SIZE = 6
internal const val COLR_V1_PAINT_SOLID_SIZE = 5
internal const val COLR_V1_PAINT_VAR_SOLID_SIZE = 9
internal const val COLR_V1_PAINT_LINEAR_GRADIENT_SIZE = 16
internal const val COLR_V1_PAINT_VAR_LINEAR_GRADIENT_SIZE = 20
internal const val COLR_V1_PAINT_RADIAL_GRADIENT_SIZE = 16
internal const val COLR_V1_PAINT_VAR_RADIAL_GRADIENT_SIZE = 20
internal const val COLR_V1_PAINT_SWEEP_GRADIENT_SIZE = 12
internal const val COLR_V1_PAINT_VAR_SWEEP_GRADIENT_SIZE = 16
internal const val COLR_V1_COLOR_LINE_HEADER_SIZE = 3
internal const val COLR_V1_COLOR_STOP_SIZE = 6
internal const val COLR_V1_VAR_COLOR_STOP_SIZE = 10
internal const val COLR_V1_PAINT_GLYPH_SIZE = 6
internal const val COLR_V1_PAINT_COLR_GLYPH_SIZE = 3
internal const val COLR_V1_PAINT_TRANSFORM_SIZE = 7
internal const val COLR_V1_TRANSFORM_SIZE = 24
internal const val COLR_V1_VAR_TRANSFORM_SIZE = 28
internal const val COLR_V1_PAINT_TRANSLATE_SIZE = 8
internal const val COLR_V1_PAINT_VAR_TRANSLATE_SIZE = 12
internal const val COLR_V1_PAINT_COMPOSITE_SIZE = 8
internal const val COLR_V1_PAINT_SCALE_SIZE = 8
internal const val COLR_V1_PAINT_SCALE_AROUND_CENTER_SIZE = 12
internal const val COLR_V1_PAINT_SCALE_UNIFORM_SIZE = 6
internal const val COLR_V1_PAINT_SCALE_UNIFORM_AROUND_CENTER_SIZE = 10
internal const val COLR_V1_PAINT_ROTATE_SIZE = 6
internal const val COLR_V1_PAINT_ROTATE_AROUND_CENTER_SIZE = 10
internal const val COLR_V1_PAINT_SKEW_SIZE = 8
internal const val COLR_V1_PAINT_SKEW_AROUND_CENTER_SIZE = 12

/**
 * Tracks total COLRv1 paint expansion during one parse call.
 */
internal data class COLRV1PaintParseState(
    var expandedPaintCount: Int = 0,
)

/**
 * Parses a bounded, renderer-neutral COLR version 1 paint graph subset.
 *
 * The parser accepts raw COLR table bytes whose first byte is the COLR table header. It supports
 * COLRv1 BaseGlyphList, LayerList, ClipList format 1, PaintSolid, PaintVarSolid,
 * PaintLinearGradient, PaintVarLinearGradient, PaintRadialGradient, PaintVarRadialGradient,
 * PaintSweepGradient, PaintVarSweepGradient, PaintGlyph, PaintColrGlyph, PaintColrLayers,
 * PaintTranslate, PaintVarTranslate, PaintTransform, PaintVarTransform, and PaintComposite. It
 * rejects unsupported paint formats and malformed data with `null` and performs all reads through
 * checked big-endian helpers.
 */
object COLRV1Parser {
    /**
     * Parses a COLR version 1 table.
     *
     * @param bytes raw COLR table bytes starting at offset zero.
     * @return parsed COLR version 1 table, or null when the bytes are unsupported, truncated,
     * malformed, or exceed the defensive color-font caps.
     */
    fun parse(bytes: ByteArray): COLRV1Table? {
        val reader = ColorTableReader(bytes)
        if (!reader.fits(0, COLR_V1_HEADER_SIZE.toLong())) return null

        val version = reader.u16(0) ?: return null
        if (version != 1) return null

        val layerPaintOffsets = parseLayerPaintOffsets(reader) ?: return null
        val state = COLRV1PaintParseState()
        val layerPaints = ArrayList<COLRV1Paint>(layerPaintOffsets.size)
        layerPaintOffsets.forEach { paintOffset ->
            layerPaints += parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = 0,
                state = state,
            ) ?: return null
        }

        val baseGlyphPaintRecords = parseBaseGlyphPaintRecords(
            reader = reader,
            layerPaintOffsets = layerPaintOffsets,
            state = state,
        ) ?: return null
        val clipRanges = parseClipRanges(reader) ?: return null

        return COLRV1Table(
            baseGlyphPaintRecords = baseGlyphPaintRecords.toList(),
            layerPaints = layerPaints.toList(),
            clipRanges = clipRanges.toList(),
        )
    }

    private fun parseBaseGlyphPaintRecords(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        state: COLRV1PaintParseState,
    ): List<COLRV1BaseGlyphPaintRecord>? {
        val baseGlyphListOffset = reader.u32(COLR_V1_BASE_GLYPH_LIST_OFFSET)?.toIntOrNull()
            ?: return null
        if (baseGlyphListOffset == 0) return emptyList()
        if (!reader.fits(baseGlyphListOffset, U32_SIZE_BYTES.toLong())) return null

        val baseGlyphPaintCount = reader.u32(baseGlyphListOffset)?.toIntOrNull()
            ?: return null
        if (baseGlyphPaintCount > MAX_COLOR_BASE_GLYPHS) return null
        if (!reader.fits(
                baseGlyphListOffset + U32_SIZE_BYTES,
                baseGlyphPaintCount.toLong() * COLR_V1_BASE_GLYPH_PAINT_RECORD_SIZE.toLong(),
            )
        ) {
            return null
        }

        val records = ArrayList<COLRV1BaseGlyphPaintRecord>(baseGlyphPaintCount)
        repeat(baseGlyphPaintCount) { recordIndex ->
            val recordOffset = baseGlyphListOffset +
                U32_SIZE_BYTES +
                recordIndex * COLR_V1_BASE_GLYPH_PAINT_RECORD_SIZE
            val glyphId = reader.u16(recordOffset) ?: return null
            val paintOffset = reader.u32(recordOffset + U16_SIZE_BYTES)?.toIntOrNull()
                ?: return null
            val absolutePaintOffset = absoluteTableOffset(
                baseOffset = baseGlyphListOffset,
                relativeOffset = paintOffset,
                tableSize = reader.size,
            ) ?: return null
            val paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = absolutePaintOffset,
                depth = 0,
                state = state,
            ) ?: return null
            records += COLRV1BaseGlyphPaintRecord(glyphId = glyphId, paint = paint)
        }
        return records.toList()
    }

    private fun parseLayerPaintOffsets(reader: ColorTableReader): List<Int>? {
        val layerListOffset = reader.u32(COLR_V1_LAYER_LIST_OFFSET)?.toIntOrNull()
            ?: return null
        if (layerListOffset == 0) return emptyList()
        if (!reader.fits(layerListOffset, U32_SIZE_BYTES.toLong())) return null

        val layerCount = reader.u32(layerListOffset)?.toIntOrNull() ?: return null
        if (layerCount > MAX_COLOR_LAYERS) return null
        if (!reader.fits(
                layerListOffset + U32_SIZE_BYTES,
                layerCount.toLong() * U32_SIZE_BYTES.toLong(),
            )
        ) {
            return null
        }

        return List(layerCount) { index ->
            val offset = reader.u32(layerListOffset + U32_SIZE_BYTES + index * U32_SIZE_BYTES)
                ?.toIntOrNull() ?: return null
            absoluteTableOffset(
                baseOffset = layerListOffset,
                relativeOffset = offset,
                tableSize = reader.size,
            ) ?: return null
        }
    }

    private fun parseClipRanges(reader: ColorTableReader): List<COLRV1ClipRange>? {
        val clipListOffset = reader.u32(COLR_V1_CLIP_LIST_OFFSET)?.toIntOrNull()
            ?: return null
        if (clipListOffset == 0) return emptyList()
        if (!reader.fits(clipListOffset, COLR_V1_CLIP_LIST_HEADER_SIZE.toLong())) return null
        if (reader.u8(clipListOffset) != 1) return null

        val clipCount = reader.u32(clipListOffset + 1)?.toIntOrNull() ?: return null
        if (clipCount > MAX_COLOR_BASE_GLYPHS) return null
        if (!reader.fits(
                clipListOffset + COLR_V1_CLIP_LIST_HEADER_SIZE,
                clipCount.toLong() * COLR_V1_CLIP_RECORD_SIZE.toLong(),
            )
        ) {
            return null
        }

        val ranges = ArrayList<COLRV1ClipRange>(clipCount)
        var previousEnd = -1
        repeat(clipCount) { index ->
            val recordOffset = clipListOffset + COLR_V1_CLIP_LIST_HEADER_SIZE + index * COLR_V1_CLIP_RECORD_SIZE
            val startGlyphId = reader.u16(recordOffset) ?: return null
            val endGlyphId = reader.u16(recordOffset + 2) ?: return null
            if (startGlyphId > endGlyphId || startGlyphId <= previousEnd) return null
            previousEnd = endGlyphId

            val clipBoxOffset = reader.u24(recordOffset + 4) ?: return null
            if (clipBoxOffset == 0) return null
            val clipBoxStart = absoluteTableOffset(
                baseOffset = clipListOffset,
                relativeOffset = clipBoxOffset,
                tableSize = reader.size,
            ) ?: return null
            val clipBox = parseClipBox(reader = reader, clipBoxStart = clipBoxStart) ?: return null
            ranges += COLRV1ClipRange(
                startGlyphId = startGlyphId,
                endGlyphId = endGlyphId,
                box = clipBox,
            )
        }
        return ranges.toList()
    }

    private fun parseClipBox(reader: ColorTableReader, clipBoxStart: Int): COLRV1ClipBox? {
        val format = reader.u8(clipBoxStart) ?: return null
        val minSize = when (format) {
            1 -> COLR_V1_CLIP_BOX_FORMAT1_SIZE
            2 -> COLR_V1_CLIP_BOX_FORMAT2_SIZE
            else -> return null
        }
        if (!reader.fits(clipBoxStart, minSize.toLong())) return null

        val xMin = reader.i16(clipBoxStart + 1) ?: return null
        val yMin = reader.i16(clipBoxStart + 3) ?: return null
        val xMax = reader.i16(clipBoxStart + 5) ?: return null
        val yMax = reader.i16(clipBoxStart + 7) ?: return null
        if (xMin >= xMax || yMin >= yMax) return null
        return COLRV1ClipBox(xMin = xMin, yMin = yMin, xMax = xMax, yMax = yMax)
    }

    private fun parsePaint(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint? {
        if (depth > MAX_COLOR_PAINT_DEPTH) return null
        state.expandedPaintCount += 1
        if (state.expandedPaintCount > MAX_COLR_V1_EXPANDED_PAINTS) return null
        if (!reader.fits(paintOffset, 1L)) return null

        return when (val format = reader.u8(paintOffset) ?: return null) {
            1 -> parseLayersPaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            2, 3 -> parseSolidPaint(reader = reader, paintOffset = paintOffset, variable = format == 3)
            4, 5 -> parseLinearGradientPaint(
                reader = reader,
                paintOffset = paintOffset,
                variable = format == 5,
            )
            6, 7 -> parseRadialGradientPaint(
                reader = reader,
                paintOffset = paintOffset,
                variable = format == 7,
            )
            8, 9 -> parseSweepGradientPaint(
                reader = reader,
                paintOffset = paintOffset,
                variable = format == 9,
            )
            10 -> parseGlyphPaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            11 -> parseColrGlyphPaint(reader = reader, paintOffset = paintOffset)
            12, 13 -> parseTransformPaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
                variable = format == 13,
            )
            14, 15 -> parseTranslatePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
                variable = format == 15,
            )
            16 -> parsePaintScale(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            17 -> null
            20 -> parsePaintScaleAroundCenter(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            21 -> null
            22 -> parsePaintScaleUniform(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            23 -> null
            24 -> parsePaintScaleUniformAroundCenter(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            25 -> null
            28 -> parsePaintRotate(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            29 -> null
            30 -> parsePaintRotateAroundCenter(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            31 -> null
            32 -> parseCompositePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            33 -> null
            34 -> parsePaintSkew(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
            35 -> null
            else -> null
        }
    }

    private fun parseLayersPaint(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.Layers? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_COLR_LAYERS_SIZE.toLong())) return null
        val layerCount = reader.u8(paintOffset + 1) ?: return null
        val firstLayerIndex = reader.u32(paintOffset + 2)?.toIntOrNull() ?: return null
        if (layerCount == 0 || layerCount > MAX_LAYERS_PER_COLOR_GLYPH) return null
        if (firstLayerIndex.toLong() + layerCount.toLong() > layerPaintOffsets.size.toLong()) return null

        val paints = ArrayList<COLRV1Paint>(layerCount)
        repeat(layerCount) { index ->
            paints += parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = layerPaintOffsets[firstLayerIndex + index],
                depth = depth + 1,
                state = state,
            ) ?: return null
        }
        return COLRV1Paint.Layers(paints = paints.toList())
    }

    private fun parseSolidPaint(
        reader: ColorTableReader,
        paintOffset: Int,
        variable: Boolean,
    ): COLRV1Paint.Solid? {
        val paintSize = if (variable) COLR_V1_PAINT_VAR_SOLID_SIZE else COLR_V1_PAINT_SOLID_SIZE
        if (!reader.fits(paintOffset, paintSize.toLong())) return null
        return COLRV1Paint.Solid(
            paletteIndex = reader.u16(paintOffset + 1) ?: return null,
            alpha = reader.f2Dot14(paintOffset + 3)?.coerceIn(0f, 1f) ?: return null,
            varIndexBase = if (variable) reader.u32(paintOffset + 5) ?: return null else null,
        )
    }

    private fun parseLinearGradientPaint(
        reader: ColorTableReader,
        paintOffset: Int,
        variable: Boolean,
    ): COLRV1Paint.LinearGradient? {
        val paintSize = if (variable) COLR_V1_PAINT_VAR_LINEAR_GRADIENT_SIZE else COLR_V1_PAINT_LINEAR_GRADIENT_SIZE
        if (!reader.fits(paintOffset, paintSize.toLong())) return null
        val colorLineOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.LinearGradient(
            colorLine = parseColorLine(reader = reader, colorLineOffset = colorLineOffset, variableStops = variable)
                ?: return null,
            x0 = reader.i16(paintOffset + 4) ?: return null,
            y0 = reader.i16(paintOffset + 6) ?: return null,
            x1 = reader.i16(paintOffset + 8) ?: return null,
            y1 = reader.i16(paintOffset + 10) ?: return null,
            x2 = reader.i16(paintOffset + 12) ?: return null,
            y2 = reader.i16(paintOffset + 14) ?: return null,
            varIndexBase = if (variable) reader.u32(paintOffset + 16) ?: return null else null,
        )
    }

    private fun parseRadialGradientPaint(
        reader: ColorTableReader,
        paintOffset: Int,
        variable: Boolean,
    ): COLRV1Paint.RadialGradient? {
        val paintSize = if (variable) COLR_V1_PAINT_VAR_RADIAL_GRADIENT_SIZE else COLR_V1_PAINT_RADIAL_GRADIENT_SIZE
        if (!reader.fits(paintOffset, paintSize.toLong())) return null
        val colorLineOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.RadialGradient(
            colorLine = parseColorLine(reader = reader, colorLineOffset = colorLineOffset, variableStops = variable)
                ?: return null,
            x0 = reader.i16(paintOffset + 4) ?: return null,
            y0 = reader.i16(paintOffset + 6) ?: return null,
            radius0 = reader.u16(paintOffset + 8) ?: return null,
            x1 = reader.i16(paintOffset + 10) ?: return null,
            y1 = reader.i16(paintOffset + 12) ?: return null,
            radius1 = reader.u16(paintOffset + 14) ?: return null,
            varIndexBase = if (variable) reader.u32(paintOffset + 16) ?: return null else null,
        )
    }

    private fun parseSweepGradientPaint(
        reader: ColorTableReader,
        paintOffset: Int,
        variable: Boolean,
    ): COLRV1Paint.SweepGradient? {
        val paintSize = if (variable) COLR_V1_PAINT_VAR_SWEEP_GRADIENT_SIZE else COLR_V1_PAINT_SWEEP_GRADIENT_SIZE
        if (!reader.fits(paintOffset, paintSize.toLong())) return null
        val colorLineOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.SweepGradient(
            colorLine = parseColorLine(reader = reader, colorLineOffset = colorLineOffset, variableStops = variable)
                ?: return null,
            centerX = reader.i16(paintOffset + 4) ?: return null,
            centerY = reader.i16(paintOffset + 6) ?: return null,
            startAngle = reader.f2Dot14(paintOffset + 8) ?: return null,
            endAngle = reader.f2Dot14(paintOffset + 10) ?: return null,
            varIndexBase = if (variable) reader.u32(paintOffset + 12) ?: return null else null,
        )
    }

    private fun parseColorLine(
        reader: ColorTableReader,
        colorLineOffset: Int,
        variableStops: Boolean,
    ): COLRV1ColorLine? {
        if (!reader.fits(colorLineOffset, COLR_V1_COLOR_LINE_HEADER_SIZE.toLong())) return null
        val extend = when (reader.u8(colorLineOffset) ?: return null) {
            0 -> COLRV1ColorLineExtend.PAD
            1 -> COLRV1ColorLineExtend.REPEAT
            2 -> COLRV1ColorLineExtend.REFLECT
            else -> return null
        }
        val stopCount = reader.u16(colorLineOffset + 1) ?: return null
        if (stopCount == 0 || stopCount > MAX_COLOR_STOPS) return null
        val stopSize = if (variableStops) COLR_V1_VAR_COLOR_STOP_SIZE else COLR_V1_COLOR_STOP_SIZE
        if (!reader.fits(
                colorLineOffset + COLR_V1_COLOR_LINE_HEADER_SIZE,
                stopCount.toLong() * stopSize.toLong(),
            )
        ) {
            return null
        }

        val stops = ArrayList<COLRV1ColorStop>(stopCount)
        repeat(stopCount) { index ->
            val stopOffset = colorLineOffset + COLR_V1_COLOR_LINE_HEADER_SIZE + index * stopSize
            stops += COLRV1ColorStop(
                offset = reader.f2Dot14(stopOffset) ?: return null,
                paletteIndex = reader.u16(stopOffset + 2) ?: return null,
                alpha = reader.f2Dot14(stopOffset + 4)?.coerceIn(0f, 1f) ?: return null,
                varIndexBase = if (variableStops) reader.u32(stopOffset + 6) ?: return null else null,
            )
        }
        return COLRV1ColorLine(
            extend = extend,
            stops = stops.toList(),
        )
    }

    private fun parseGlyphPaint(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.Glyph? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_GLYPH_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.Glyph(
            glyphId = reader.u16(paintOffset + 4) ?: return null,
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
        )
    }

    private fun parseColrGlyphPaint(
        reader: ColorTableReader,
        paintOffset: Int,
    ): COLRV1Paint.ColrGlyph? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_COLR_GLYPH_SIZE.toLong())) return null
        return COLRV1Paint.ColrGlyph(
            glyphId = reader.u16(paintOffset + 1) ?: return null,
        )
    }

    private fun parseTranslatePaint(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
        variable: Boolean,
    ): COLRV1Paint.Translate? {
        val paintSize = if (variable) COLR_V1_PAINT_VAR_TRANSLATE_SIZE else COLR_V1_PAINT_TRANSLATE_SIZE
        if (!reader.fits(paintOffset, paintSize.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.Translate(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            dx = reader.i16(paintOffset + 4) ?: return null,
            dy = reader.i16(paintOffset + 6) ?: return null,
            varIndexBase = if (variable) reader.u32(paintOffset + 8) ?: return null else null,
        )
    }

    private fun parseTransformPaint(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
        variable: Boolean,
    ): COLRV1Paint.Transform? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_TRANSFORM_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        val transformOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 4)
            ?: return null
        val transformSize = if (variable) COLR_V1_VAR_TRANSFORM_SIZE else COLR_V1_TRANSFORM_SIZE
        if (!reader.fits(transformOffset, transformSize.toLong())) return null

        return COLRV1Paint.Transform(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            xx = reader.fixed16Dot16(transformOffset) ?: return null,
            yx = reader.fixed16Dot16(transformOffset + 4) ?: return null,
            xy = reader.fixed16Dot16(transformOffset + 8) ?: return null,
            yy = reader.fixed16Dot16(transformOffset + 12) ?: return null,
            dx = reader.fixed16Dot16(transformOffset + 16) ?: return null,
            dy = reader.fixed16Dot16(transformOffset + 20) ?: return null,
            varIndexBase = if (variable) reader.u32(transformOffset + 24) ?: return null else null,
        )
    }

    private fun parseCompositePaint(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.Composite? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_COMPOSITE_SIZE.toLong())) return null
        val sourceOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        val mode = COLRV1CompositeMode.fromFontValue(reader.u8(paintOffset + 4) ?: return null)
            ?: return null
        val backdropOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 5)
            ?: return null

        return COLRV1Paint.Composite(
            source = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = sourceOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            mode = mode,
            backdrop = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = backdropOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
        )
    }

    private fun parsePaintScale(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.Scale? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_SCALE_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.Scale(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            scaleX = reader.f2Dot14(paintOffset + 4) ?: return null,
            scaleY = reader.f2Dot14(paintOffset + 6) ?: return null,
        )
    }

    private fun parsePaintScaleAroundCenter(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.ScaleAroundCenter? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_SCALE_AROUND_CENTER_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.ScaleAroundCenter(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            scaleX = reader.f2Dot14(paintOffset + 4) ?: return null,
            scaleY = reader.f2Dot14(paintOffset + 6) ?: return null,
            centerX = reader.i16(paintOffset + 8) ?: return null,
            centerY = reader.i16(paintOffset + 10) ?: return null,
        )
    }

    private fun parsePaintScaleUniform(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.ScaleUniform? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_SCALE_UNIFORM_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.ScaleUniform(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            scale = reader.f2Dot14(paintOffset + 4) ?: return null,
        )
    }

    private fun parsePaintScaleUniformAroundCenter(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.ScaleUniformAroundCenter? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_SCALE_UNIFORM_AROUND_CENTER_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.ScaleUniformAroundCenter(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            scale = reader.f2Dot14(paintOffset + 4) ?: return null,
            centerX = reader.i16(paintOffset + 6) ?: return null,
            centerY = reader.i16(paintOffset + 8) ?: return null,
        )
    }

    private fun parsePaintRotate(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.Rotate? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_ROTATE_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.Rotate(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            angle = reader.f2Dot14(paintOffset + 4) ?: return null,
        )
    }

    private fun parsePaintRotateAroundCenter(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.RotateAroundCenter? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_ROTATE_AROUND_CENTER_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.RotateAroundCenter(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            angle = reader.f2Dot14(paintOffset + 4) ?: return null,
            centerX = reader.i16(paintOffset + 6) ?: return null,
            centerY = reader.i16(paintOffset + 8) ?: return null,
        )
    }

    private fun parsePaintSkew(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.Skew? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_SKEW_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.Skew(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            xSkew = reader.f2Dot14(paintOffset + 4) ?: return null,
            ySkew = reader.f2Dot14(paintOffset + 6) ?: return null,
        )
    }

    private fun parsePaintSkewAroundCenter(
        reader: ColorTableReader,
        layerPaintOffsets: List<Int>,
        paintOffset: Int,
        depth: Int,
        state: COLRV1PaintParseState,
    ): COLRV1Paint.SkewAroundCenter? {
        if (!reader.fits(paintOffset, COLR_V1_PAINT_SKEW_AROUND_CENTER_SIZE.toLong())) return null
        val childOffset = childPaintOffset(reader = reader, parentOffset = paintOffset, fieldOffset = 1)
            ?: return null
        return COLRV1Paint.SkewAroundCenter(
            paint = parsePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = childOffset,
                depth = depth + 1,
                state = state,
            ) ?: return null,
            xSkew = reader.f2Dot14(paintOffset + 4) ?: return null,
            ySkew = reader.f2Dot14(paintOffset + 6) ?: return null,
            centerX = reader.i16(paintOffset + 8) ?: return null,
            centerY = reader.i16(paintOffset + 10) ?: return null,
        )
    }

    private fun childPaintOffset(reader: ColorTableReader, parentOffset: Int, fieldOffset: Int): Int? {
        val relativeOffset = reader.u24(parentOffset + fieldOffset) ?: return null
        if (relativeOffset == 0) return null
        return absoluteTableOffset(
            baseOffset = parentOffset,
            relativeOffset = relativeOffset,
            tableSize = reader.size,
        )
    }
}
