package io.ygdrasil.kalligraphie.font.scaler

import io.ygdrasil.kalligraphie.font.colr.COLRV1Parser
import io.ygdrasil.kalligraphie.font.colr.COLRV1Table
import io.ygdrasil.kalligraphie.font.colr.CPALV0Parser
import io.ygdrasil.kalligraphie.font.colr.CPALTable
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

val GlyphBounds.width: Double get() = right - left
val GlyphBounds.height: Double get() = bottom - top

data class ScaledGlyph(
    val sourceCodepoint: Int,
    val glyphId: Int,
    val size: Float,
    val advanceWidth: Float,
    val bounds: GlyphBounds,
    val commands: List<OutlineCommand> = emptyList(),
    val representation: GlyphRepresentation? = null,
) {
    fun checksum(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(sourceCodepoint.toString().encodeToByteArray())
        md.update(glyphId.toString().encodeToByteArray())
        md.update(size.toBits().toString().encodeToByteArray())
        md.update(advanceWidth.toBits().toString().encodeToByteArray())
        md.update(bounds.left.toBits().toString().encodeToByteArray())
        md.update(bounds.top.toBits().toString().encodeToByteArray())
        md.update(bounds.right.toBits().toString().encodeToByteArray())
        md.update(bounds.bottom.toBits().toString().encodeToByteArray())
        for (cmd in commands) {
            md.update(cmd.toString().encodeToByteArray())
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

sealed class GlyphScaleResult {
    data class Success(val glyph: ScaledGlyph) : GlyphScaleResult()
    data class Unsupported(val code: String, val reason: String) : GlyphScaleResult()
}

class GlyphScaler private constructor(
    private val fontBytes: ByteArray,
) {
    private data class TableRecord(val offset: Int, val length: Int)

    private val tables: Map<String, TableRecord>
    private val isCFF: Boolean
    private val numGlyphs: Int
    private val unitsPerEm: Int
    private val indexToLocFormat: Int
    private val numHMetrics: Int
    private val hheaMetricsRaw: HheaMetrics
    private val cmap: CmapSubtable
    private val advanceWidths: IntArray
    private val glyphOffsets: IntArray
    /** Legacy CPAL parse — palette 0 only, used by [parseColrV0]. */
    private val cpalPalette: IntArray?

    /**
     * Resolves a CPAL palette entry to a packed ARGB color.
     *
     * Checks the multi-palette [cpalMultiTable] (palette 0) first, then falls
     * back to the legacy single-palette [cpalPalette]. Returns null when the
     * index is out of range or no palette is available.
     */
    fun resolveCpalColor(paletteIndex: Int): Int? {
        val multi = cpalMultiTable
        if (multi != null) {
            val colors = multi.palettes.getOrNull(0) ?: return null
            return colors.getOrNull(paletteIndex)
        }
        val legacy = cpalPalette
        if (legacy != null && paletteIndex in legacy.indices) {
            return legacy[paletteIndex]
        }
        return null
    }
    private val colrV0BaseGlyphs: Map<Int, List<ColorLayerEntry>>?
    private val sbixGlyphs: Map<Int, GlyphRepresentation.Bitmap>?
    private val cblcStrikes: List<CblcStrike>?
    internal val colrV1Table: COLRV1Table?
    internal val cpalMultiTable: CPALTable?

    /** True when any color glyph table is present — avoids O(n) glyph scan in [hasColorGlyphs]. */
    val hasAnyColorTable: Boolean by lazy {
        colrV0BaseGlyphs != null || colrV1Table != null || cblcStrikes != null || sbixGlyphs != null
    }

    init {
        tables = parseTableDirectory()
        cpalPalette = parseCpal()
        colrV0BaseGlyphs = parseColrV0()
        sbixGlyphs = parseSbix()
        cblcStrikes = parseCblc()
        colrV1Table = parseColrV1()
        cpalMultiTable = parseCpalMulti()
        val sfntTag = String(fontBytes, 0, 4, Charsets.ISO_8859_1)
        isCFF = sfntTag == "OTTO" || sfntTag == "typ1"
        numGlyphs = parseMaxp()
        unitsPerEm = parseHeadUnitsPerEm()
        indexToLocFormat = parseHeadLocFormat()
        numHMetrics = parseHheaNumHMetrics()
        hheaMetricsRaw = parseHheaMetrics()
        cmap = parseCmap()
        advanceWidths = parseHmtx()
        glyphOffsets = parseLoca()
    }

    fun getGlyphRepresentation(glyphId: Int, fontSize: Float): GlyphRepresentation? {
        return try {
            scaleGlyph(glyphId, fontSize).representation
        } catch (_: Exception) {
            null
        }
    }

    fun glyphIdForCodepoint(codepoint: Int): Int? {
        val id = cmap.glyphId(codepoint)
        return if (id in 0 until numGlyphs) id else null
    }

    /**
     * Scales a glyph outline for the given glyph ID and size.
     *
     * Throws on CFF/CFF2 fonts — callers that may encounter unknown font types
     * should use [scaleGlyphOrDiagnostic] instead, which catches the exception
     * and returns an [GlyphScaleResult.Unsupported] diagnostic.
     */
    fun scaleGlyph(glyphId: Int, size: Float, sourceCodepoint: Int = 0): ScaledGlyph {
        if (glyphId < 0 || glyphId >= numGlyphs) {
            throw IllegalArgumentException("Glyph ID $glyphId out of range [0, $numGlyphs)")
        }
        val scale = size / unitsPerEm.toFloat()
        val advance = advanceWidths[min(glyphId, advanceWidths.lastIndex)] * scale

        val colorLayers = colrV0BaseGlyphs?.get(glyphId)
        if (colorLayers != null) {
            return ScaledGlyph(
                sourceCodepoint = sourceCodepoint,
                glyphId = glyphId,
                size = size,
                advanceWidth = advance,
                bounds = computeBounds(emptyList()),
                commands = emptyList(),
                representation = GlyphRepresentation.ColorLayers(colorLayers),
            )
        }

        val colrV1 = colrV1Table
        if (colrV1 != null) {
            val paint = colrV1.paintForGlyph(glyphId)
            if (paint != null) {
                val paintGraph = colrV1.paintGraphForGlyph(glyphId)
                if (paintGraph != null) {
                    return ScaledGlyph(
                        sourceCodepoint = sourceCodepoint,
                        glyphId = glyphId,
                        size = size,
                        advanceWidth = advance,
                        bounds = GlyphBounds(0.0, 0.0, size.toDouble(), size.toDouble()),
                        commands = emptyList(),
                        representation = GlyphRepresentation.ColorLayersV1(paintGraph),
                    )
                }
            }
        }

        val cblcBitmap = resolveCbdtBitmap(glyphId, size)
        if (cblcBitmap != null) {
            return ScaledGlyph(
                sourceCodepoint = sourceCodepoint,
                glyphId = glyphId,
                size = size,
                advanceWidth = advance,
                bounds = GlyphBounds(0.0, 0.0, cblcBitmap.pixelWidth.toDouble(), cblcBitmap.pixelHeight.toDouble()),
                commands = emptyList(),
                representation = cblcBitmap,
            )
        }

        val sbixBmp = sbixGlyphs?.get(glyphId)
        if (sbixBmp != null) {
            return ScaledGlyph(
                sourceCodepoint = sourceCodepoint,
                glyphId = glyphId,
                size = size,
                advanceWidth = advance,
                bounds = GlyphBounds(0.0, 0.0, sbixBmp.pixelWidth.toDouble(), sbixBmp.pixelHeight.toDouble()),
                commands = emptyList(),
                representation = sbixBmp,
            )
        }

        val outline = parseGlyphOutline(glyphId)
        val commands = outlineToCommands(outline)
        val scaledCommands = commands.map { scaleCommand(it, scale) }
        val bounds = computeBounds(scaledCommands)
        return ScaledGlyph(
            sourceCodepoint = sourceCodepoint,
            glyphId = glyphId,
            size = size,
            advanceWidth = advance,
            bounds = bounds,
            commands = scaledCommands,
            representation = GlyphRepresentation.Outline(scaledCommands),
        )
    }

    val hheaAscent: Int get() = hheaMetricsRaw.ascent
    val hheaDescent: Int get() = hheaMetricsRaw.descent
    val hheaLineGap: Int get() = hheaMetricsRaw.lineGap
    val unitsPerEmInt: Int get() = unitsPerEm

    fun scaleGlyphOrDiagnostic(glyphId: Int, size: Float): GlyphScaleResult {
        if (glyphId < 0 || glyphId >= numGlyphs) {
            return GlyphScaleResult.Unsupported(
                "font.scaler.glyph_id_out_of_range",
                "Glyph ID $glyphId out of range [0, $numGlyphs)",
            )
        }
        return try {
            GlyphScaleResult.Success(scaleGlyph(glyphId, size))
        } catch (e: Exception) {
            val code: String
            val reason: String
            val msg = e.message ?: ""
            when {
                "CFF/CFF2" in msg -> {
                    code = "font.scaler.cff_not_yet_supported"
                    reason = "CFF/CFF2 charstring parsing is deferred here; use CFFScaler for pure Kotlin CFF path output"
                }
                "point-matching" in msg -> {
                    code = "font.scaler.composite_point_matching_unsupported"
                    reason = "Composite glyph component uses point-matching (not xy values)"
                }
                else -> {
                    code = "font.scaler.outline_unavailable"
                    reason = msg.ifEmpty { "Unknown error" }
                }
            }
            GlyphScaleResult.Unsupported(code, reason)
        }
    }

    private fun parseTableDirectory(): Map<String, TableRecord> {
        val bytes = fontBytes
        require(bytes.size >= 12)
        val sfnt = String(bytes, 0, 4, Charsets.ISO_8859_1)
        require(sfnt == "\u0000\u0001\u0000\u0000" || sfnt == "true" || sfnt == "OTTO" || sfnt == "typ1") {
            "Unsupported SFNT scaler type: ${bytes[0].toInt() and 0xFF}${bytes[1].toInt() and 0xFF}${bytes[2].toInt() and 0xFF}${bytes[3].toInt() and 0xFF}"
        }
        val numTables = u16(bytes, 4)
        require(bytes.size >= 12 + numTables * 16)
        val tables = HashMap<String, TableRecord>()
        var off = 12
        repeat(numTables) {
            val tag = String(bytes, off, 4, Charsets.ISO_8859_1)
            val tableOff = u32(bytes, off + 8).toInt()
            val tableLen = u32(bytes, off + 12).toInt()
            if (tableOff >= 0 && tableLen >= 0 && tableOff.toLong() + tableLen <= bytes.size.toLong()) {
                tables[tag] = TableRecord(tableOff, tableLen)
            }
            off += 16
        }
        return tables
    }

    private fun parseMaxp(): Int {
        val maxp = tables["maxp"] ?: error("Missing maxp table")
        return u16(fontBytes, maxp.offset + 4)
    }

    private fun parseHeadUnitsPerEm(): Int {
        val head = tables["head"] ?: error("Missing head table")
        return u16(fontBytes, head.offset + 18)
    }

    private fun parseHeadLocFormat(): Int {
        if (isCFF) return 0
        val head = tables["head"] ?: error("Missing head table")
        return i16(fontBytes, head.offset + 50).toInt()
    }

    private fun parseHheaNumHMetrics(): Int {
        val hhea = tables["hhea"] ?: error("Missing hhea table")
        return u16(fontBytes, hhea.offset + 34)
    }

    private fun parseHmtx(): IntArray {
        val hmtx = tables["hmtx"] ?: error("Missing hmtx table")
        if (hmtx.length < numHMetrics * 4) error("hmtx too small")
        val advances = IntArray(numGlyphs)
        var h = hmtx.offset
        for (i in 0 until numHMetrics) {
            advances[i] = u16(fontBytes, h); h += 2
            h += 2
        }
        for (i in numHMetrics until numGlyphs) {
            advances[i] = advances[numHMetrics - 1]
        }
        return advances
    }

    private fun parseLoca(): IntArray {
        if (isCFF) return intArrayOf()
        val loca = tables["loca"] ?: error("Missing loca table")
        val glyf = tables["glyf"] ?: error("Missing glyf table")
        val offsets = IntArray(numGlyphs + 1)
        if (indexToLocFormat == 0) {
            require(loca.length >= (numGlyphs + 1) * 2)
            for (i in 0..numGlyphs) offsets[i] = u16(fontBytes, loca.offset + i * 2) * 2
        } else {
            require(loca.length >= (numGlyphs + 1) * 4)
            for (i in 0..numGlyphs) offsets[i] = u32(fontBytes, loca.offset + i * 4).toInt()
        }
        if (offsets.any { it < 0 || it > glyf.length }) error("loca offsets out of range")
        for (i in 0 until offsets.lastIndex) {
            if (offsets[i] > offsets[i + 1]) error("loca offsets not monotonic")
        }
        return offsets
    }

    private fun parseCpal(): IntArray? {
        val cpalTable = tables["CPAL"] ?: return null
        val bytes = fontBytes
        val off = cpalTable.offset
        if (cpalTable.length < 12 || off + 12 > bytes.size) return null
        val numPaletteEntries = u16(bytes, off + 2)
        val numPalettes = u16(bytes, off + 4)
        val numColorRecords = u16(bytes, off + 6)
        if (numPalettes == 0 || numPaletteEntries == 0) return null
        if (numPaletteEntries > numColorRecords) return null
        val colorRecordIndicesLength = numPalettes.toLong() * 2L
        if (12L + colorRecordIndicesLength > cpalTable.length.toLong()) return null
        val colorRecordsOffset = u32(bytes, off + 8)
        if (colorRecordsOffset + numColorRecords.toLong() * 4L > cpalTable.length.toLong()) return null
        val firstColorRecordIndex = u16(bytes, off + 12)
        if (firstColorRecordIndex + numPaletteEntries > numColorRecords) return null
        val colors = IntArray(numPaletteEntries)
        for (i in 0 until numPaletteEntries) {
            val entryOff = cpalTable.offset + colorRecordsOffset.toInt() +
                (firstColorRecordIndex + i) * 4
            if (entryOff + 4 > cpalTable.offset + cpalTable.length) return null
            val b = u8(bytes, entryOff)
            val g = u8(bytes, entryOff + 1)
            val r = u8(bytes, entryOff + 2)
            val a = u8(bytes, entryOff + 3)
            colors[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return colors
    }

    private fun parseColrV0(): Map<Int, List<ColorLayerEntry>>? {
        val colrTable = tables["COLR"] ?: return null
        val palette = cpalPalette ?: return null
        val bytes = fontBytes
        val off = colrTable.offset
        val tableEnd = off + colrTable.length
        val version = u16(bytes, off)
        if (version != 0) return null
        val numBaseGlyphRecords = u16(bytes, off + 2)
        val baseGlyphRecordsOffset = u32(bytes, off + 4).toInt()
        val layerRecordsOffset = u32(bytes, off + 8).toInt()
        val numLayerRecords = u16(bytes, off + 12)
        // Bounds: record offsets must lie within the COLR table
        if (baseGlyphRecordsOffset < 0 || baseGlyphRecordsOffset + numBaseGlyphRecords * 6 > colrTable.length) return null
        if (layerRecordsOffset < 0 || layerRecordsOffset + numLayerRecords * 4 > colrTable.length) return null
        val result = mutableMapOf<Int, List<ColorLayerEntry>>()
        for (i in 0 until numBaseGlyphRecords) {
            val baseOff = off + baseGlyphRecordsOffset + i * 6
            if (baseOff + 6 > tableEnd) break
            val glyphId = u16(bytes, baseOff)
            val firstLayerIndex = u16(bytes, baseOff + 2)
            val numLayers = u16(bytes, baseOff + 4)
            val layers = mutableListOf<ColorLayerEntry>()
            for (j in 0 until numLayers) {
                if (firstLayerIndex + j >= numLayerRecords) break
                val layerOff = off + layerRecordsOffset + (firstLayerIndex + j) * 4
                if (layerOff + 4 > tableEnd) break
                val layerGlyphId = u16(bytes, layerOff)
                val paletteIndex = u16(bytes, layerOff + 2)
                val argb = if (paletteIndex < palette.size) palette[paletteIndex] else 0xFF000000.toInt()
                layers.add(ColorLayerEntry(layerGlyphId, argb))
            }
            result[glyphId] = layers
        }
        return if (result.isEmpty()) null else result
    }

    private fun parseColrV1(): COLRV1Table? {
        val colrTable = tables["COLR"] ?: return null
        val bytes = fontBytes
        val off = colrTable.offset
        if (off + 4 > bytes.size) return null
        val version = u16(bytes, off)
        if (version < 1) return null
        return try {
            COLRV1Parser.parse(bytes.copyOfRange(colrTable.offset, colrTable.offset + colrTable.length))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCpalMulti(): CPALTable? {
        val cpalTable = tables["CPAL"] ?: return null
        return try {
            CPALV0Parser.parse(fontBytes.copyOfRange(cpalTable.offset, cpalTable.offset + cpalTable.length))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSbix(): Map<Int, GlyphRepresentation.Bitmap>? {
        val sbixTable = tables["sbix"] ?: return null
        val bytes = fontBytes
        val off = sbixTable.offset
        val numStrikes = u32(bytes, off + 4).toInt()
        if (numStrikes == 0) return null
        val strikeOffset = u32(bytes, off + 8).toInt()
        val strikeOff = sbixTable.offset + strikeOffset
        if (strikeOff + 8 > bytes.size) return null
        val ppem = u16(bytes, strikeOff)
        val numGlyphs = u32(bytes, strikeOff + 4).toInt()
        val result = mutableMapOf<Int, GlyphRepresentation.Bitmap>()
        var glyphOff = strikeOff + 8
        for (i in 0 until numGlyphs) {
            if (glyphOff + 12 > bytes.size) break
            val originOffsetX = i16(bytes, glyphOff).toInt()
            val originOffsetY = i16(bytes, glyphOff + 2).toInt()
            val graphicType = String(bytes, glyphOff + 4, 4, Charsets.ISO_8859_1)
            val dataLen = u32(bytes, glyphOff + 8).toInt()
            if (graphicType == "png " && dataLen > 0) {
                val pngStart = glyphOff + 12
                if (pngStart + dataLen <= bytes.size) {
                    val pngData = bytes.copyOfRange(pngStart, pngStart + dataLen)
                    result[i] = GlyphRepresentation.Bitmap(
                        pngData = pngData,
                        originX = originOffsetX.toFloat(),
                        originY = originOffsetY.toFloat(),
                        pixelWidth = ppem,
                        pixelHeight = ppem,
                    )
                }
            }
            glyphOff += 12 + dataLen
        }
        return result.ifEmpty { null }
    }

    private fun parseCblc(): List<CblcStrike>? {
        val cblcTable = tables["CBLC"] ?: return null
        val cbdtTable = tables["CBDT"] ?: return null
        val bytes = fontBytes
        val offset = cblcTable.offset
        if (offset + 8 > bytes.size) return null
        val numSizes = u32(bytes, offset + 4).toInt()
        if (numSizes == 0) return null
        val strikes = mutableListOf<CblcStrike>()
        var sizeOff = offset + 8
        for (si in 0 until numSizes) {
            if (sizeOff + 48 > bytes.size) break
            val indexSubTableArrayOffset = u32(bytes, sizeOff).toInt()
            val numTables = u32(bytes, sizeOff + 8).toInt()
            val ppemX = u8(bytes, sizeOff + 12).toInt()
            val ppemY = u8(bytes, sizeOff + 13).toInt()
            val glyphBitmaps = mutableMapOf<Int, GlyphRepresentation.Bitmap>()
            for (ti in 0 until numTables) {
                val subOff = cblcTable.offset + indexSubTableArrayOffset + ti * 8
                if (subOff + 8 > bytes.size) break
                val firstGlyph = u16(bytes, subOff).toInt()
                val lastGlyph = u16(bytes, subOff + 2).toInt()
                val additionalOffset = u32(bytes, subOff + 4).toInt()
                for (gid in firstGlyph..lastGlyph) {
                    if (gid > 0xFFFF) break
                    val entryOff = cblcTable.offset + additionalOffset + (gid - firstGlyph) * 4
                    if (entryOff + 4 > bytes.size) continue
                    val glyphOffset = u32(bytes, entryOff).toInt()
                    if (glyphOffset == 0) continue
                    val cbdtOff = cbdtTable.offset + glyphOffset
                    if (cbdtOff + 6 > bytes.size) continue
                    val format = u16(bytes, cbdtOff + 4)
                    if (format != 17 && format != 18 && format != 19) continue
                    val totalLen = u32(bytes, cbdtOff)
                    if (totalLen < 8L) continue // avoid underflow
                    val pngLen = totalLen.toInt() - 8
                    if (cbdtOff + 8 + pngLen > bytes.size || pngLen <= 0) continue
                    val pngData = bytes.copyOfRange(cbdtOff + 8, cbdtOff + 8 + pngLen)
                    glyphBitmaps[gid] = GlyphRepresentation.Bitmap(
                        pngData = pngData,
                        originX = 0f,
                        originY = 0f,
                        pixelWidth = ppemX,
                        pixelHeight = ppemY,
                    )
                }
            }
            strikes.add(CblcStrike(ppemX, ppemY, glyphBitmaps))
            sizeOff += 48
        }
        return strikes.ifEmpty { null }
    }

    private fun parseCmap(): CmapSubtable {
        val cmapTable = tables["cmap"] ?: error("Missing cmap table")
        val bytes = fontBytes
        val limit = cmapTable.offset + cmapTable.length
        val numTables = u16(bytes, cmapTable.offset + 2)
        var bestScore = -1
        var bestOffset = -1
        var off = cmapTable.offset + 4
        repeat(numTables) {
            val platform = u16(bytes, off)
            val encoding = u16(bytes, off + 2)
            val subOffset = u32(bytes, off + 4).toInt()
            off += 8
            if (subOffset < 0 || cmapTable.offset + subOffset >= limit) return@repeat
            val abs = cmapTable.offset + subOffset
            if (abs + 2 > limit) return@repeat
            val format = u16(bytes, abs)
            val score = when {
                format == 12 && platform == 3 && encoding == 10 -> 100
                format == 4 && platform == 3 && encoding == 1 -> 90
                format == 4 && platform == 0 -> 80
                format == 4 -> 50
                format == 6 && platform == 1 && encoding == 0 -> 20
                format == 0 && platform == 1 && encoding == 0 -> 10
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                bestOffset = abs
            }
        }
        require(bestOffset >= 0) { "No supported cmap subtable found" }
        val format = u16(fontBytes, bestOffset)
        return when (format) {
            4 -> parseCmapFormat4(bestOffset, limit)
            12 -> parseCmapFormat12(bestOffset, limit)
            else -> error("Unsupported cmap format $format")
        }
    }

    private fun parseCmapFormat4(off: Int, limit: Int): CmapSubtable {
        val bytes = fontBytes
        val segCount = u16(bytes, off + 6) / 2
        var p = off + 14
        val endCode = IntArray(segCount) { u16(bytes, p + it * 2) }
        p += segCount * 2 + 2
        val startCode = IntArray(segCount) { u16(bytes, p + it * 2) }
        p += segCount * 2
        val idDelta = IntArray(segCount) { i16(bytes, p + it * 2).toInt() }
        p += segCount * 2
        val idRangeOffset = IntArray(segCount) { u16(bytes, p + it * 2) }
        p += segCount * 2
        val remaining = max(0, (limit - p))
        val glyphIdArray = IntArray(remaining / 2) {
            if (p + it * 2 + 1 < limit) u16(bytes, p + it * 2) else 0
        }
        return CmapFormat4(endCode, startCode, idDelta, idRangeOffset, glyphIdArray)
    }

    private fun parseCmapFormat12(off: Int, limit: Int): CmapSubtable {
        val bytes = fontBytes
        val numGroups = u32(bytes, off + 12).toInt()
        val groups = ArrayList<CmapFormat12Group>(numGroups)
        var p = off + 16
        repeat(numGroups) {
            groups.add(
                CmapFormat12Group(
                    startChar = u32(bytes, p),
                    endChar = u32(bytes, p + 4),
                    startGlyph = u32(bytes, p + 8),
                )
            )
            p += 12
        }
        return CmapFormat12(groups)
    }

    private fun parseGlyphOutline(glyphId: Int, depth: Int = 0): GlyphData {
        if (isCFF) error("CFF/CFF2 charstring parsing is deferred")
        val glyf = tables["glyf"] ?: error("Missing glyf table")
        val start = glyphOffsets[glyphId]
        val end = glyphOffsets.getOrElse(glyphId + 1) { start }
        if (start == end) return GlyphData.Empty
        val p = glyf.offset + start
        val numberOfContours = i16(fontBytes, p).toInt()
        if (numberOfContours >= 0) return parseSimpleGlyph(p, numberOfContours)
        return parseCompositeGlyph(p, depth)
    }

    private fun parseSimpleGlyph(p: Int, numberOfContours: Int): GlyphData {
        if (numberOfContours == 0) return GlyphData.Empty
        val bytes = fontBytes
        var off = p + 10
        val endPts = IntArray(numberOfContours) { u16(bytes, off + it * 2) }
        off += numberOfContours * 2
        val instructionLength = u16(bytes, off)
        off += 2 + instructionLength
        val pointCount = endPts.last() + 1
        if (pointCount < 0) error("Invalid point count")
        val flags = IntArray(pointCount)
        var i = 0
        while (i < pointCount) {
            val flag = u8(bytes, off++)
            flags[i++] = flag
            if ((flag and 0x08) != 0) {
                val repeat = u8(bytes, off++)
                repeat(repeat) { if (i < pointCount) flags[i++] = flag }
            }
        }
        val xs = IntArray(pointCount)
        var x = 0
        for (j in 0 until pointCount) {
            val flag = flags[j]
            val dx = if ((flag and 0x02) != 0) {
                val v = u8(bytes, off++)
                if ((flag and 0x10) != 0) v else -v
            } else {
                if ((flag and 0x10) != 0) 0 else {
                    val v = i16(bytes, off).toInt(); off += 2; v
                }
            }
            x += dx; xs[j] = x
        }
        val ys = IntArray(pointCount)
        var y = 0
        for (j in 0 until pointCount) {
            val flag = flags[j]
            val dy = if ((flag and 0x04) != 0) {
                val v = u8(bytes, off++)
                if ((flag and 0x20) != 0) v else -v
            } else {
                if ((flag and 0x20) != 0) 0 else {
                    val v = i16(bytes, off).toInt(); off += 2; v
                }
            }
            y += dy; ys[j] = y
        }
        val contours = ArrayList<List<GlyphPoint>>(numberOfContours)
        var startIdx = 0
        for (endIdx in endPts) {
            val pts = ArrayList<GlyphPoint>(endIdx - startIdx + 1)
            for (idx in startIdx..endIdx) {
                pts.add(GlyphPoint(xs[idx].toFloat(), ys[idx].toFloat(), (flags[idx] and 0x01) != 0))
            }
            contours.add(pts)
            startIdx = endIdx + 1
        }
        return GlyphData.Simple(contours)
    }

    private fun parseCompositeGlyph(p: Int, depth: Int = 0): GlyphData {
        if (depth > 8) return GlyphData.Empty
        val bytes = fontBytes
        var off = p + 10
        val allContours = ArrayList<List<GlyphPoint>>()
        do {
            val flags = u16(bytes, off); off += 2
            val componentGlyph = u16(bytes, off); off += 2
            val arg1: Int
            val arg2: Int
            if ((flags and 0x0001) != 0) {
                arg1 = i16(bytes, off).toInt(); arg2 = i16(bytes, off + 2).toInt(); off += 4
            } else {
                arg1 = i8(bytes, off).toInt(); arg2 = i8(bytes, off + 1).toInt(); off += 2
            }
            val hasWords = (flags and 0x0001) != 0
            val xyValues = (flags and 0x0002) != 0
            if (!xyValues) {
                error("Composite glyph component uses point-matching (not xy values)")
            }
            val dx = if (xyValues) arg1 else 0
            val dy = if (xyValues) arg2 else 0
            var a = 1.0f; var b = 0.0f; var c = 0.0f; var d = 1.0f
            when {
                (flags and 0x0008) != 0 -> {
                    a = f2dot14(bytes, off); d = a; off += 2
                }
                (flags and 0x0040) != 0 -> {
                    a = f2dot14(bytes, off); d = f2dot14(bytes, off + 2); off += 4
                }
                (flags and 0x0080) != 0 -> {
                    a = f2dot14(bytes, off); b = f2dot14(bytes, off + 2)
                    c = f2dot14(bytes, off + 4); d = f2dot14(bytes, off + 6); off += 8
                }
            }
            val childData = parseGlyphOutline(componentGlyph, depth + 1)
            if (childData is GlyphData.Simple) {
                for (contour in childData.contours) {
                    allContours.add(contour.map { pt ->
                        val nx = a * pt.x + c * pt.y + dx
                        val ny = b * pt.x + d * pt.y + dy
                        GlyphPoint(nx, ny, pt.onCurve)
                    })
                }
            }
        } while ((flags and 0x0020) != 0)
        return if (allContours.isEmpty()) GlyphData.Empty else GlyphData.Simple(allContours)
    }

    private fun outlineToCommands(glyph: GlyphData): List<OutlineCommand> {
        if (glyph !is GlyphData.Simple || glyph.contours.isEmpty()) return emptyList()
        val commands = ArrayList<OutlineCommand>()
        for (contour in glyph.contours) {
            if (contour.isEmpty()) continue
            emitContourCommands(commands, contour)
        }
        return commands
    }

    private fun emitContourCommands(commands: ArrayList<OutlineCommand>, contour: List<GlyphPoint>) {
        val n = contour.size
        val first = contour.first()
        val last = contour.last()
        val start = when {
            first.onCurve -> first
            last.onCurve -> last
            else -> GlyphPoint((last.x + first.x) / 2f, (last.y + first.y) / 2f, true)
        }
        commands.add(moveTo(start.x.toDouble(), start.y.toDouble()))
        var prev = start
        var i = if (first.onCurve) 1 else 0
        var emitted = 0
        while (emitted < n) {
            val curr = contour[i % n]
            if (curr === start && emitted > 0) break
            if (curr.onCurve) {
                commands.add(lineTo(curr.x.toDouble(), curr.y.toDouble()))
                prev = curr; i++; emitted++
            } else {
                val next = contour[(i + 1) % n]
                val end = if (next.onCurve) next else
                    GlyphPoint((curr.x + next.x) / 2f, (curr.y + next.y) / 2f, true)
                commands.add(quadraticTo(
                    curr.x.toDouble(), curr.y.toDouble(),
                    end.x.toDouble(), end.y.toDouble(),
                ))
                prev = end
                i += if (next.onCurve) 2 else 1
                emitted += if (next.onCurve) 2 else 1
            }
        }
        if (prev != start) commands.add(close())
        else commands.add(close())
    }

    private fun scaleCommand(cmd: OutlineCommand, scale: Float): OutlineCommand {
        val s = scale.toDouble()
        return when (cmd) {
            is OutlineCommand.MoveTo -> moveTo(cmd.x * s, cmd.y * s)
            is OutlineCommand.LineTo -> lineTo(cmd.x * s, cmd.y * s)
            is OutlineCommand.QuadraticTo -> quadraticTo(
                cmd.controlX * s, cmd.controlY * s,
                cmd.x * s, cmd.y * s,
            )
            is OutlineCommand.CubicTo -> cubicTo(
                cmd.controlX1 * s, cmd.controlY1 * s,
                cmd.controlX2 * s, cmd.controlY2 * s,
                cmd.x * s, cmd.y * s,
            )
            OutlineCommand.Close -> close()
        }
    }

    private fun computeBounds(commands: List<OutlineCommand>): GlyphBounds {
        var l = Double.POSITIVE_INFINITY
        var t = Double.POSITIVE_INFINITY
        var r = Double.NEGATIVE_INFINITY
        var b = Double.NEGATIVE_INFINITY
        var hasPoint = false
        fun include(x: Double, y: Double) {
            l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y); hasPoint = true
        }
        for (cmd in commands) {
            when (cmd) {
                is OutlineCommand.MoveTo -> include(cmd.x, cmd.y)
                is OutlineCommand.LineTo -> include(cmd.x, cmd.y)
                is OutlineCommand.QuadraticTo -> { include(cmd.controlX, cmd.controlY); include(cmd.x, cmd.y) }
                is OutlineCommand.CubicTo -> {
                    include(cmd.controlX1, cmd.controlY1)
                    include(cmd.controlX2, cmd.controlY2)
                    include(cmd.x, cmd.y)
                }
                OutlineCommand.Close -> {}
            }
        }
        return if (hasPoint) GlyphBounds(l, t, r, b) else GlyphBounds(0.0, 0.0, 0.0, 0.0)
    }

    private sealed class CmapSubtable {
        abstract fun glyphId(cp: Int): Int
    }

    private class CmapFormat4(
        private val endCodes: IntArray,
        private val startCodes: IntArray,
        private val idDeltas: IntArray,
        private val idRangeOffsets: IntArray,
        private val glyphIdArray: IntArray,
    ) : CmapSubtable() {
        override fun glyphId(cp: Int): Int {
            if (cp !in 0..0xFFFF) return 0
            for (i in endCodes.indices) {
                if (cp < startCodes[i]) continue
                if (cp > endCodes[i]) continue
                if (idRangeOffsets[i] == 0) return (cp + idDeltas[i]) and 0xFFFF
                val index = idRangeOffsets[i] / 2 + (cp - startCodes[i]) - (endCodes.size - i)
                if (index !in glyphIdArray.indices) return 0
                val glyph = glyphIdArray[index]
                return if (glyph == 0) 0 else (glyph + idDeltas[i]) and 0xFFFF
            }
            return 0
        }
    }

    private class CmapFormat12(private val groups: List<CmapFormat12Group>) : CmapSubtable() {
        override fun glyphId(cp: Int): Int {
            for (g in groups) {
                if (cp in g.startChar..g.endChar) return (g.startGlyph + (cp.toLong() - g.startChar)).toInt()
            }
            return 0
        }
    }

    private data class CmapFormat12Group(val startChar: Long, val endChar: Long, val startGlyph: Long)

    private data class GlyphPoint(val x: Float, val y: Float, val onCurve: Boolean)

    private class HheaMetrics(
        val ascent: Int,
        val descent: Int,
        val lineGap: Int,
    )

    private data class CblcStrike(
        val ppemX: Int,
        val ppemY: Int,
        val glyphBitmaps: Map<Int, GlyphRepresentation.Bitmap>,
    )

    private fun parseHheaMetrics(): HheaMetrics {
        val hhea = tables["hhea"] ?: error("Missing hhea table")
        val bytes = fontBytes
        return HheaMetrics(
            ascent = i16(bytes, hhea.offset + 4).toInt(),
            descent = i16(bytes, hhea.offset + 6).toInt(),
            lineGap = i16(bytes, hhea.offset + 8).toInt(),
        )
    }

    private fun resolveCbdtBitmap(glyphId: Int, fontSize: Float): GlyphRepresentation.Bitmap? {
        val strikes = cblcStrikes ?: return null
        if (strikes.isEmpty()) return null
        var bestStrike = strikes.first()
        var bestDiff = kotlin.math.abs(bestStrike.ppemX - fontSize.toInt())
        for (strike in strikes) {
            val diff = kotlin.math.abs(strike.ppemX - fontSize.toInt())
            if (diff < bestDiff) {
                bestDiff = diff
                bestStrike = strike
            }
        }
        return bestStrike.glyphBitmaps[glyphId]
    }

    private sealed class GlyphData {
        data object Empty : GlyphData()
        data class Simple(val contours: List<List<GlyphPoint>>) : GlyphData()
    }

    companion object {
        fun fromBytes(bytes: ByteArray): GlyphScaler = GlyphScaler(bytes)

        private fun u8(bytes: ByteArray, off: Int): Int = bytes[off].toInt() and 0xFF
        private fun i8(bytes: ByteArray, off: Int): Byte = bytes[off]
        private fun u16(bytes: ByteArray, off: Int): Int =
            ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)
        private fun i16(bytes: ByteArray, off: Int): Short = u16(bytes, off).toShort()
        private fun u32(bytes: ByteArray, off: Int): Long =
            ((bytes[off].toLong() and 0xFF) shl 24) or
                ((bytes[off + 1].toLong() and 0xFF) shl 16) or
                ((bytes[off + 2].toLong() and 0xFF) shl 8) or
                (bytes[off + 3].toLong() and 0xFF)
        private fun f2dot14(bytes: ByteArray, off: Int): Float = i16(bytes, off).toInt() / 16384f
    }
}
