package org.graphiks.kalligraphie.glyph.color

import org.graphiks.kalligraphie.font.TypefaceID
import org.graphiks.kalligraphie.glyph.GlyphRouteDiagnostic
import org.graphiks.kalligraphie.glyph.GlyphStrikeKey
import org.graphiks.kalligraphie.glyph.OutlineGlyphRepresentation
import org.graphiks.kalligraphie.text.shaping.EmojiSequenceFact
import org.graphiks.kalligraphie.text.shaping.EmojiSequenceKind
import org.graphiks.kalligraphie.text.shaping.ShapedGlyphRun
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Plans color glyph routes for emoji, COLR/CPAL, bitmap strikes, PNG glyphs, and SVG glyphs.
 */
interface ColorGlyphPlanner {
    /**
     * Plans color glyph representations for a shaped glyph run.
     *
     * @param run shaped glyph run to inspect.
     * @param strikeKey strike inputs used for color glyph selection.
     * @return color glyph planning result.
     */
    fun plan(run: ShapedGlyphRun, strikeKey: GlyphStrikeKey): ColorGlyphPlanningResult
}

/**
 * Deterministic color glyph planner that dispatches each glyph to the first available route.
 *
 * The planner is intentionally metadata-only. It does not decode PNG payloads, render SVG
 * documents, build COLR paint graphs, rasterize outlines, or allocate GPU resources. It connects
 * caller-supplied route availability facts to per-glyph [GlyphRepresentation] decisions in the
 * same route order as [SimpleEmojiGlyphDispatcher]: COLR, bitmap, PNG, SVG, then outline.
 *
 * @property availability route availability facts for this planning pass.
 * @property outlineRepresentations optional outline fallback representations keyed by glyph ID.
 */
class SimpleColorGlyphPlanner(
    availability: EmojiGlyphRouteAvailability,
    private val outlineRepresentations: Map<Int, OutlineGlyphRepresentation> = emptyMap(),
) : ColorGlyphPlanner {
    private val dispatcher = SimpleEmojiGlyphDispatcher(availability)

    /**
     * Plans one representation per glyph with stable run-order output and route diagnostics.
     */
    override fun plan(run: ShapedGlyphRun, strikeKey: GlyphStrikeKey): ColorGlyphPlanningResult {
        val routes = ArrayList<ColorGlyphRoute>(run.glyphIds.size)
        val diagnostics = ArrayList<ColorGlyphDiagnostic>()

        run.glyphIds.forEach { glyphId ->
            val dispatch = dispatcher.dispatch(glyphId = glyphId, strikeKey = strikeKey)
            diagnostics += dispatch.diagnostics
            routeFor(dispatch)?.let { route -> routes += route }
        }

        return ColorGlyphPlanningResult(
            routes = routes.toList(),
            diagnostics = diagnostics.toList(),
        )
    }

    private fun routeFor(dispatch: EmojiGlyphDispatch): ColorGlyphRoute? =
        when (dispatch.route) {
            "colr", "bitmap", "png", "svg" -> ColorGlyphRoute(
                glyphId = dispatch.glyphId,
                route = dispatch.route,
            )
            "outline" -> ColorGlyphRoute(
                glyphId = dispatch.glyphId,
                route = dispatch.route,
                outline = outlineRepresentations[dispatch.glyphId]
                    ?: OutlineGlyphRepresentation(glyphId = dispatch.glyphId),
            )
            else -> null
        }
}

/**
 * Plans COLR glyph paint graphs and palette usage.
 */
interface COLRGlyphPlanner {
    /**
     * Builds a COLR paint graph for one glyph.
     *
     * @param glyphId glyph identifier to plan.
     * @param palette palette selected for the glyph.
     * @return paint graph for the glyph.
     */
    fun plan(glyphId: Int, palette: CPALPalette): COLRPaintGraph
}

/**
 * Describes a renderer-neutral COLR paint graph.
 *
 * @property root root paint node for the graph.
 * @property nodes flattened node list for validation and traversal.
 */
data class COLRPaintGraph(
    val root: COLRPaintNode,
    val nodes: List<COLRPaintNode>,
)

/**
 * Represents one node in a COLR paint graph.
 *
 * @property id stable node identifier within a paint graph.
 * @property kind paint operation kind.
 * @property children child node identifiers.
 * @property paletteIndex optional CPAL palette index consumed by this node.
 * @property glyphId optional glyph identifier referenced by this node. Root nodes use this for the
 * base glyph, and layer nodes use it for the glyph painted by the layer.
 */
data class COLRPaintNode(
    val id: Int,
    val kind: String,
    val children: List<Int> = emptyList(),
    val paletteIndex: Int? = null,
    val glyphId: Int? = null,
)

/**
 * Stores a CPAL palette in pure Kotlin form.
 *
 * @property index palette index selected from the font.
 * @property colors packed ARGB colors in palette order.
 * @property labels optional human-readable palette labels.
 */
data class CPALPalette(
    val index: Int,
    val colors: List<Int>,
    val labels: List<String> = emptyList(),
)

/**
 * Describes one caller-supplied CPAL palette entry override.
 *
 * Overrides are renderer-neutral color substitutions applied after a palette has been selected
 * from a [CPALTable]. They are keyed by palette entry index, not by glyph ID or paint node ID.
 * The [color] value is a packed ARGB integer in the same representation used by [CPALPalette].
 *
 * @property index zero-based palette entry index to replace in the selected palette.
 * @property color packed ARGB color that replaces the selected palette entry when [index] is in
 * range for that palette.
 */
data class CPALPaletteOverride(
    val index: Int,
    val color: Int,
)

/**
 * Selects a CPAL palette and applies palette entry overrides without renderer dependencies.
 *
 * The selection mirrors only the pure CPAL concepts from font argument data while deliberately
 * avoiding graphics, color-management, platform image, and platform-specific execution
 * dependencies. [index] chooses the palette by table order. [overrides] are then applied in list
 * order to the selected palette; duplicate override indexes therefore use the last supplied color.
 * Overrides whose indexes are outside the selected palette are ignored so callers can share
 * override lists across fonts with different palette sizes.
 *
 * @property index zero-based palette index requested from a CPAL table.
 * @property overrides renderer-neutral color substitutions keyed by selected palette entry index.
 */
data class CPALPaletteSelection(
    val index: Int = 0,
    val overrides: List<CPALPaletteOverride> = emptyList(),
) {
    /**
     * Resolves this selection against a parsed CPAL table.
     *
     * @param table CPAL table whose palette list supplies the base palette.
     * @return the selected palette with in-range overrides applied, or null when [index] does not
     * name a palette in [table].
     */
    fun select(table: CPALTable): CPALPalette? {
        val palette = table.palettes.getOrNull(index) ?: return null
        if (overrides.isEmpty()) return palette

        val colors = palette.colors.toMutableList()
        overrides.forEach { override ->
            if (override.index in colors.indices) {
                colors[override.index] = override.color
            }
        }
        return palette.copy(colors = colors.toList())
    }

    companion object {
        /**
         * Default CPAL selection: palette zero with no color overrides.
         */
        val Default: CPALPaletteSelection = CPALPaletteSelection()
    }
}

/**
 * Palette index value used by OpenType COLR/COLRv1 records to request the current foreground text
 * color instead of an entry from the selected CPAL palette.
 */
const val COLR_FOREGROUND_PALETTE_INDEX: Int = 0xFFFF

/**
 * Stores the parsed contents of a CPAL version 0 table.
 *
 * The table keeps palettes in font order and preserves colors as packed ARGB integers. OpenType
 * stores CPAL v0 color records as BGRA bytes; [CPALV0Parser] converts that byte order once during
 * parsing so downstream color glyph planning does not need to know the table encoding.
 *
 * @property numPaletteEntries number of color entries expected in every parsed palette.
 * @property numColorRecords number of raw color records advertised by the CPAL table.
 * @property palettes parsed palettes in table order.
 */
data class CPALTable(
    val numPaletteEntries: Int,
    val numColorRecords: Int,
    val palettes: List<CPALPalette>,
)

/**
 * Parses OpenType CPAL version 0 table bytes into pure Kotlin palette models.
 *
 * The parser accepts raw CPAL table bytes whose first byte is the CPAL table header. It performs
 * all reads through checked big-endian helpers, rejects unsupported versions, and caps advertised
 * counts before expanding palette records so malformed fonts cannot force unbounded allocation.
 */
object CPALV0Parser {
    /**
     * Parses a CPAL version 0 table.
     *
     * @param bytes raw CPAL table bytes starting at offset zero.
     * @return parsed CPAL table, or null when the bytes are unsupported, truncated, out of range,
     * or exceed the defensive color-font caps used by the pure Kotlin parser.
     */
    fun parse(bytes: ByteArray): CPALTable? {
        val reader = ColorTableReader(bytes)
        if (!reader.fits(0, CPAL_V0_HEADER_SIZE.toLong())) return null

        val version = reader.u16(0) ?: return null
        if (version != 0) return null

        val numPaletteEntries = reader.u16(2) ?: return null
        val numPalettes = reader.u16(4) ?: return null
        val numColorRecords = reader.u16(6) ?: return null
        val colorRecordsArrayOffset = reader.u32(8)?.toIntOrNull() ?: return null

        if (numPaletteEntries > MAX_COLOR_PALETTE_ENTRIES) return null
        if (numPalettes > MAX_COLOR_PALETTES) return null
        if (numColorRecords > MAX_COLOR_RECORDS) return null
        if (numPalettes.toLong() * numPaletteEntries.toLong() > MAX_EXPANDED_COLOR_RECORDS) {
            return null
        }
        if (!reader.fits(CPAL_V0_HEADER_SIZE, numPalettes.toLong() * U16_SIZE_BYTES.toLong())) return null
        if (!reader.fits(colorRecordsArrayOffset, numColorRecords.toLong() * CPAL_COLOR_RECORD_SIZE.toLong())) {
            return null
        }

        val palettes = ArrayList<CPALPalette>(numPalettes)
        repeat(numPalettes) { paletteIndex ->
            val firstColorRecordIndex = reader.u16(CPAL_V0_HEADER_SIZE + paletteIndex * U16_SIZE_BYTES)
                ?: return null
            if (firstColorRecordIndex + numPaletteEntries > numColorRecords) return null

            val colors = ArrayList<Int>(numPaletteEntries)
            repeat(numPaletteEntries) { entryIndex ->
                val colorOffset = colorRecordsArrayOffset +
                    (firstColorRecordIndex + entryIndex) * CPAL_COLOR_RECORD_SIZE
                val blue = reader.u8(colorOffset) ?: return null
                val green = reader.u8(colorOffset + 1) ?: return null
                val red = reader.u8(colorOffset + 2) ?: return null
                val alpha = reader.u8(colorOffset + 3) ?: return null
                colors += packArgb(alpha = alpha, red = red, green = green, blue = blue)
            }

            palettes += CPALPalette(
                index = paletteIndex,
                colors = colors.toList(),
            )
        }

        return CPALTable(
            numPaletteEntries = numPaletteEntries,
            numColorRecords = numColorRecords,
            palettes = palettes.toList(),
        )
    }
}

/**
 * Describes one COLR version 0 base glyph record.
 *
 * Base records map a rendered glyph identifier to a contiguous slice of COLR layer records. The
 * parser validates that [firstLayerIndex] and [numLayers] stay inside the parsed layer record
 * array before exposing the record.
 *
 * @property glyphId glyph identifier whose color representation is described by this record.
 * @property firstLayerIndex index of the first layer record for this glyph.
 * @property numLayers number of layer records consumed by this glyph.
 */
data class COLRBaseGlyphRecord(
    val glyphId: Int,
    val firstLayerIndex: Int,
    val numLayers: Int,
)

/**
 * Describes one COLR version 0 layer record.
 *
 * @property glyphId glyph identifier painted for this layer.
 * @property paletteIndex CPAL palette entry index used by this layer, or
 * [COLR_FOREGROUND_PALETTE_INDEX] when the layer should use the current foreground color.
 */
data class COLRLayerRecord(
    val glyphId: Int,
    val paletteIndex: Int,
)

/**
 * Stores the parsed contents of a COLR version 0 table.
 *
 * The table keeps base glyph records and layer records in source order. Callers can use
 * [layersForGlyph] to resolve the validated layer slice for a specific base glyph without knowing
 * the raw table offsets.
 *
 * @property baseGlyphRecords parsed base glyph records in table order.
 * @property layerRecords parsed layer records in table order.
 */
data class COLRV0Table(
    val baseGlyphRecords: List<COLRBaseGlyphRecord>,
    val layerRecords: List<COLRLayerRecord>,
) {
    /**
     * Resolves the layer records for one base glyph.
     *
     * @param glyphId base glyph identifier to look up.
     * @return immutable layer records for [glyphId], or an empty list when the glyph has no COLR
     * version 0 base record.
     */
    fun layersForGlyph(glyphId: Int): List<COLRLayerRecord> {
        val baseRecord = baseGlyphRecords.firstOrNull { record -> record.glyphId == glyphId }
            ?: return emptyList()
        return layerRecords.subList(
            fromIndex = baseRecord.firstLayerIndex,
            toIndex = baseRecord.firstLayerIndex + baseRecord.numLayers,
        ).toList()
    }
}

/**
 * Parses OpenType COLR version 0 table bytes into pure Kotlin base and layer records.
 *
 * The parser accepts raw COLR table bytes whose first byte is the COLR table header. It supports
 * only COLR version 0, validates every advertised offset and slice before reading records, and
 * rejects count expansions that exceed the module-local color glyph caps.
 */
object COLRV0Parser {
    /**
     * Parses a COLR version 0 table.
     *
     * @param bytes raw COLR table bytes starting at offset zero.
     * @return parsed COLR version 0 table, or null when the bytes are unsupported, truncated,
     * out of range, or exceed defensive color-font caps.
     */
    fun parse(bytes: ByteArray): COLRV0Table? =
        parseRecords(bytes = bytes, allowVersionOne = false)

    /**
     * Parses the version 0 base/layer record prefix retained by a COLR version 1 table.
     *
     * COLR version 1 deliberately preserves the first fourteen bytes and the
     * version 0 record arrays. This entry point shares every bounds/count check
     * with [parse] while admitting the version 1 marker.
     */
    fun parseRetainedV0Records(bytes: ByteArray): COLRV0Table? =
        parseRecords(bytes = bytes, allowVersionOne = true)

    private fun parseRecords(
        bytes: ByteArray,
        allowVersionOne: Boolean,
    ): COLRV0Table? {
        val reader = ColorTableReader(bytes)
        if (!reader.fits(0, COLR_V0_HEADER_SIZE.toLong())) return null

        val version = reader.u16(0) ?: return null
        if (version != 0 && !(allowVersionOne && version == 1)) return null

        val numBaseGlyphRecords = reader.u16(2) ?: return null
        val baseGlyphRecordsOffset = reader.u32(4)?.toIntOrNull() ?: return null
        val layerRecordsOffset = reader.u32(8)?.toIntOrNull() ?: return null
        val numLayerRecords = reader.u16(12) ?: return null

        if (numBaseGlyphRecords > MAX_COLOR_BASE_GLYPHS) return null
        if (numLayerRecords > MAX_COLOR_LAYERS) return null
        if (!reader.fits(baseGlyphRecordsOffset, numBaseGlyphRecords.toLong() * COLR_BASE_RECORD_SIZE.toLong())) {
            return null
        }
        if (!reader.fits(layerRecordsOffset, numLayerRecords.toLong() * COLR_LAYER_RECORD_SIZE.toLong())) {
            return null
        }

        val layerRecords = ArrayList<COLRLayerRecord>(numLayerRecords)
        repeat(numLayerRecords) { layerIndex ->
            val layerOffset = layerRecordsOffset + layerIndex * COLR_LAYER_RECORD_SIZE
            layerRecords += COLRLayerRecord(
                glyphId = reader.u16(layerOffset) ?: return null,
                paletteIndex = reader.u16(layerOffset + 2) ?: return null,
            )
        }

        val baseGlyphRecords = ArrayList<COLRBaseGlyphRecord>(numBaseGlyphRecords)
        var expandedLayerCount = 0L
        repeat(numBaseGlyphRecords) { baseIndex ->
            val baseOffset = baseGlyphRecordsOffset + baseIndex * COLR_BASE_RECORD_SIZE
            val glyphId = reader.u16(baseOffset) ?: return null
            val firstLayerIndex = reader.u16(baseOffset + 2) ?: return null
            val numLayers = reader.u16(baseOffset + 4) ?: return null

            if (numLayers > MAX_LAYERS_PER_COLOR_GLYPH) return null
            expandedLayerCount += numLayers.toLong()
            if (expandedLayerCount > MAX_EXPANDED_COLOR_LAYERS) return null
            if (firstLayerIndex + numLayers > numLayerRecords) return null

            baseGlyphRecords += COLRBaseGlyphRecord(
                glyphId = glyphId,
                firstLayerIndex = firstLayerIndex,
                numLayers = numLayers,
            )
        }

        return COLRV0Table(
            baseGlyphRecords = baseGlyphRecords.toList(),
            layerRecords = layerRecords.toList(),
        )
    }
}

/**
 * Builds renderer-neutral COLR paint graphs from parsed COLR version 0 layer records.
 *
 * The planner preserves CPAL palette indexes on layer nodes instead of resolving renderer state.
 * It validates palette indexes against the selected [CPALPalette] where possible so downstream
 * diagnostics can distinguish a paintable layer from a layer that references a missing palette
 * entry.
 *
 * @property colr parsed COLR version 0 table used for glyph-to-layer lookup.
 */
class SimpleCOLRGlyphPlanner(
    private val colr: COLRV0Table,
) : COLRGlyphPlanner {
    /**
     * Builds a paint graph for one COLR version 0 glyph.
     *
     * @param glyphId base glyph identifier to plan.
     * @param palette selected CPAL palette used to validate layer palette indexes.
     * @return paint graph whose root represents the base glyph and whose children represent COLR
     * version 0 layers in paint order.
     */
    override fun plan(glyphId: Int, palette: CPALPalette): COLRPaintGraph {
        val layers = colr.layersForGlyph(glyphId)
        val layerNodes = layers.mapIndexed { layerIndex, layer ->
            val nodeId = layerIndex + 1
            COLRPaintNode(
                id = nodeId,
                kind = layerKind(layer.paletteIndex, palette),
                paletteIndex = layer.paletteIndex,
                glyphId = layer.glyphId,
            )
        }
        val root = COLRPaintNode(
            id = 0,
            kind = "colr-v0-glyph",
            children = layerNodes.map { node -> node.id },
            glyphId = glyphId,
        )
        return COLRPaintGraph(
            root = root,
            nodes = listOf(root) + layerNodes,
        )
    }

    /**
     * Classifies a layer node using the selected CPAL palette.
     */
    private fun layerKind(paletteIndex: Int, palette: CPALPalette): String =
        if (paletteIndex == COLR_FOREGROUND_PALETTE_INDEX || palette.colors.getOrNull(paletteIndex) != null) {
            "colr-v0-layer"
        } else {
            "colr-v0-missing-palette-layer"
        }
}

/**
 * Immutable bounds for one color glyph layer or aggregate color glyph plan.
 *
 * Bounds are carried as font-design-space extrema so later CPU/GPU handoff work can reason about
 * geometry without re-reading COLR/CPAL tables. The bounds are value-object evidence only; this
 * ticket does not claim rasterization or GPU composite support.
 */
data class ColorGlyphBounds(
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
) {
    init {
        require(xMin < xMax) { "Color glyph bounds must have xMin < xMax." }
        require(yMin < yMax) { "Color glyph bounds must have yMin < yMax." }
    }

    fun union(other: ColorGlyphBounds): ColorGlyphBounds = ColorGlyphBounds(
        xMin = minOf(xMin, other.xMin),
        yMin = minOf(yMin, other.yMin),
        xMax = maxOf(xMax, other.xMax),
        yMax = maxOf(yMax, other.yMax),
    )

    fun intersectConservative(other: ColorGlyphBounds): ColorGlyphBounds {
        val clippedXMin = maxOf(xMin, other.xMin)
        val clippedYMin = maxOf(yMin, other.yMin)
        val clippedXMax = minOf(xMax, other.xMax)
        val clippedYMax = minOf(yMax, other.yMax)
        return if (clippedXMin < clippedXMax && clippedYMin < clippedYMax) {
            ColorGlyphBounds(
                xMin = clippedXMin,
                yMin = clippedYMin,
                xMax = clippedXMax,
                yMax = clippedYMax,
            )
        } else {
            other
        }
    }

    fun transformedBy(
        xx: Float,
        yx: Float,
        xy: Float,
        yy: Float,
        dx: Float,
        dy: Float,
    ): ColorGlyphBounds {
        val corners = listOf(
            xMin.toFloat() to yMin.toFloat(),
            xMax.toFloat() to yMin.toFloat(),
            xMin.toFloat() to yMax.toFloat(),
            xMax.toFloat() to yMax.toFloat(),
        )
        val transformedCorners = corners.map { (x, y) ->
            ((xx * x) + (xy * y) + dx) to ((yx * x) + (yy * y) + dy)
        }
        val minX = floor(transformedCorners.minOf { (x, _) -> x }.toDouble()).toInt()
        val minY = floor(transformedCorners.minOf { (_, y) -> y }.toDouble()).toInt()
        val maxX = ceil(transformedCorners.maxOf { (x, _) -> x }.toDouble()).toInt()
        val maxY = ceil(transformedCorners.maxOf { (_, y) -> y }.toDouble()).toInt()
        return ColorGlyphBounds(xMin = minX, yMin = minY, xMax = maxX, yMax = maxY)
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("xMin")).append(": ").append(xMin).append(", ")
        append(colorGlyphJsonString("yMin")).append(": ").append(yMin).append(", ")
        append(colorGlyphJsonString("xMax")).append(": ").append(xMax).append(", ")
        append(colorGlyphJsonString("yMax")).append(": ").append(yMax)
        append("}")
    }
}

/**
 * Route-scoped artifact-key evidence derived from the M9 [GlyphStrikeKey] preimage rules.
 *
 * The hash is intentionally carried as a value object instead of a future renderer handle. M11 can
 * register typed artifacts from these facts later without reopening COLR/CPAL table state.
 */
data class ColorGlyphArtifactKey(
    val glyphId: Int,
    val route: String,
    val strikeKeySha256: String,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(", ")
        append(colorGlyphJsonString("route")).append(": ").append(colorGlyphJsonString(route)).append(", ")
        append(colorGlyphJsonString("strikeKeySha256")).append(": ")
        append(colorGlyphJsonString(strikeKeySha256))
        append("}")
    }
}

/**
 * Stable palette selection facts recorded alongside a resolved COLRv0 color glyph plan.
 */
data class ColorGlyphPalette(
    val identity: String,
    val selectionIndex: Int,
    val resolvedIndex: Int,
    val overrideCount: Int,
    val colorCount: Int,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("identity")).append(": ").append(colorGlyphJsonString(identity)).append(", ")
        append(colorGlyphJsonString("selectionIndex")).append(": ").append(selectionIndex).append(", ")
        append(colorGlyphJsonString("resolvedIndex")).append(": ").append(resolvedIndex).append(", ")
        append(colorGlyphJsonString("overrideCount")).append(": ").append(overrideCount).append(", ")
        append(colorGlyphJsonString("colorCount")).append(": ").append(colorCount)
        append("}")
    }
}

/**
 * Typed COLRv0 layer plan consumed later by artifact registration work.
 */
data class COLRV0LayerPlan(
    val layerIndex: Int,
    val glyphId: Int,
    val paletteIndex: Int,
    val resolvedColor: Int?,
    val usesForegroundColor: Boolean,
    val outlineArtifactKey: ColorGlyphArtifactKey,
    val bounds: ColorGlyphBounds,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("layerIndex")).append(": ").append(layerIndex).append(", ")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(", ")
        append(colorGlyphJsonString("paletteIndex")).append(": ").append(paletteIndex).append(", ")
        append(colorGlyphJsonString("resolvedColorArgb")).append(": ")
        append(colorGlyphNullableString(resolvedColor?.let(::colorGlyphArgbHex)))
        append(", ")
        append(colorGlyphJsonString("usesForegroundColor")).append(": ").append(usesForegroundColor).append(", ")
        append(colorGlyphJsonString("outlineArtifactKey")).append(": ").append(outlineArtifactKey.toCanonicalJson()).append(", ")
        append(colorGlyphJsonString("bounds")).append(": ").append(bounds.toCanonicalJson())
        append("}")
    }
}

/**
 * Deterministic COLRv0 plan proof produced from already-parsed color table facts.
 */
data class ColorGlyphPlan(
    val glyphId: Int,
    val typefaceId: TypefaceID,
    val routeKind: String = "colrv0",
    val artifactKey: ColorGlyphArtifactKey,
    val palette: ColorGlyphPalette,
    val layers: List<COLRV0LayerPlan>,
    val paintGraph: COLRV1PaintGraphEvidence? = null,
    val bounds: ColorGlyphBounds,
    val fallbackPolicy: String,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", ColorGlyphPlanSchema, comma = true)
        appendColorGlyphJsonField("glyphId", glyphId, comma = true)
        appendColorGlyphJsonField("typefaceId", typefaceId.value.toString(), comma = true)
        appendColorGlyphJsonField("routeKind", routeKind, comma = true)
        append("  ").append(colorGlyphJsonString("artifactKey")).append(": ").append(artifactKey.toCanonicalJson()).append(",\n")
        append("  ").append(colorGlyphJsonString("palette")).append(": ").append(palette.toCanonicalJson()).append(",\n")
        append("  ").append(colorGlyphJsonString("layers")).append(": ")
        appendColorGlyphLayerPlansJson(layers, indent = "  ")
        append(",\n")
        append("  ").append(colorGlyphJsonString("paintGraph")).append(": ")
        append(paintGraph?.toCanonicalJson(indent = "  ") ?: "null")
        append(",\n")
        append("  ").append(colorGlyphJsonString("bounds")).append(": ").append(bounds.toCanonicalJson()).append(",\n")
        appendColorGlyphJsonField("fallbackPolicy", fallbackPolicy, comma = true)
        append("  ").append(colorGlyphJsonString("diagnostics")).append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }

    companion object {
        const val ColorGlyphPlanSchema: String = "org.graphiks.kanvas.glyph.color.ColorGlyphPlan.v1"
    }
}

/**
 * Result of attempting to convert COLRv0 metadata into a typed color glyph plan.
 */
data class COLRV0ColorGlyphPlanDecision(
    val plan: ColorGlyphPlan?,
    val selectedRoute: ColorGlyphRoute?,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
)

data class COLRV1ColorGlyphPlanDecision(
    val plan: ColorGlyphPlan?,
    val selectedRoute: ColorGlyphRoute?,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
)

data class COLRV1GradientStopEvidence(
    val stopIndex: Int,
    val offset: Float,
    val paletteIndex: Int,
    val resolvedColorArgb: String,
    val alpha: Float,
    val varIndexBase: Long? = null,
    val appliedAlphaDelta: Float? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("stopIndex")).append(": ").append(stopIndex).append(", ")
        append(colorGlyphJsonString("offset")).append(": ").append(colorGlyphFloatToken(offset)).append(", ")
        append(colorGlyphJsonString("paletteIndex")).append(": ").append(paletteIndex).append(", ")
        append(colorGlyphJsonString("resolvedColorArgb")).append(": ").append(colorGlyphJsonString(resolvedColorArgb)).append(", ")
        append(colorGlyphJsonString("alpha")).append(": ").append(colorGlyphFloatToken(alpha)).append(", ")
        append(colorGlyphJsonString("varIndexBase")).append(": ").append(varIndexBase ?: "null").append(", ")
        append(colorGlyphJsonString("appliedAlphaDelta")).append(": ")
        append(appliedAlphaDelta?.let(::colorGlyphFloatToken) ?: "null")
        append("}")
    }
}

data class COLRV1LinearGradientGeometry(
    val x0: Int,
    val y0: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("x0")).append(": ").append(x0).append(", ")
        append(colorGlyphJsonString("y0")).append(": ").append(y0).append(", ")
        append(colorGlyphJsonString("x1")).append(": ").append(x1).append(", ")
        append(colorGlyphJsonString("y1")).append(": ").append(y1).append(", ")
        append(colorGlyphJsonString("x2")).append(": ").append(x2).append(", ")
        append(colorGlyphJsonString("y2")).append(": ").append(y2)
        append("}")
    }
}

data class COLRV1RadialGradientGeometry(
    val x0: Int,
    val y0: Int,
    val radius0: Int,
    val x1: Int,
    val y1: Int,
    val radius1: Int,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("x0")).append(": ").append(x0).append(", ")
        append(colorGlyphJsonString("y0")).append(": ").append(y0).append(", ")
        append(colorGlyphJsonString("radius0")).append(": ").append(radius0).append(", ")
        append(colorGlyphJsonString("x1")).append(": ").append(x1).append(", ")
        append(colorGlyphJsonString("y1")).append(": ").append(y1).append(", ")
        append(colorGlyphJsonString("radius1")).append(": ").append(radius1)
        append("}")
    }
}

data class COLRV1SweepGradientGeometry(
    val centerX: Int,
    val centerY: Int,
    val startAngle: Float,
    val endAngle: Float,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("centerX")).append(": ").append(centerX).append(", ")
        append(colorGlyphJsonString("centerY")).append(": ").append(centerY).append(", ")
        append(colorGlyphJsonString("startAngle")).append(": ").append(colorGlyphFloatToken(startAngle)).append(", ")
        append(colorGlyphJsonString("endAngle")).append(": ").append(colorGlyphFloatToken(endAngle))
        append("}")
    }
}

data class COLRV1GradientEvidence(
    val extendMode: String,
    val stops: List<COLRV1GradientStopEvidence>,
    val variationCoordinates: Map<String, Float> = emptyMap(),
    val linearGeometry: COLRV1LinearGradientGeometry? = null,
    val radialGeometry: COLRV1RadialGradientGeometry? = null,
    val sweepGeometry: COLRV1SweepGradientGeometry? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("extendMode")).append(": ").append(colorGlyphJsonString(extendMode)).append(", ")
        append(colorGlyphJsonString("stops")).append(": ")
        append(stops.joinToString(prefix = "[", postfix = "]") { stop -> stop.toCanonicalJson() })
        append(", ")
        append(colorGlyphJsonString("variationCoordinates")).append(": ")
        append(colorGlyphFloatMapJson(variationCoordinates))
        append(", ")
        append(colorGlyphJsonString("linearGeometry")).append(": ")
        append(linearGeometry?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("radialGeometry")).append(": ")
        append(radialGeometry?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("sweepGeometry")).append(": ")
        append(sweepGeometry?.toCanonicalJson() ?: "null")
        append("}")
    }
}

data class COLRV1TransformEvidence(
    val transformKind: String,
    val xx: Float,
    val yx: Float,
    val xy: Float,
    val yy: Float,
    val dx: Float,
    val dy: Float,
    val determinant: Float,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("transformKind")).append(": ").append(colorGlyphJsonString(transformKind)).append(", ")
        append(colorGlyphJsonString("xx")).append(": ").append(colorGlyphFloatToken(xx)).append(", ")
        append(colorGlyphJsonString("yx")).append(": ").append(colorGlyphFloatToken(yx)).append(", ")
        append(colorGlyphJsonString("xy")).append(": ").append(colorGlyphFloatToken(xy)).append(", ")
        append(colorGlyphJsonString("yy")).append(": ").append(colorGlyphFloatToken(yy)).append(", ")
        append(colorGlyphJsonString("dx")).append(": ").append(colorGlyphFloatToken(dx)).append(", ")
        append(colorGlyphJsonString("dy")).append(": ").append(colorGlyphFloatToken(dy)).append(", ")
        append(colorGlyphJsonString("determinant")).append(": ").append(colorGlyphFloatToken(determinant))
        append("}")
    }
}

data class COLRV1CompositeEvidence(
    val mode: String,
    val sourceNodeId: Int,
    val backdropNodeId: Int,
    val destinationReadClass: String,
    val requiresLayerIsolation: Boolean,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("mode")).append(": ").append(colorGlyphJsonString(mode)).append(", ")
        append(colorGlyphJsonString("sourceNodeId")).append(": ").append(sourceNodeId).append(", ")
        append(colorGlyphJsonString("backdropNodeId")).append(": ").append(backdropNodeId).append(", ")
        append(colorGlyphJsonString("destinationReadClass")).append(": ")
        append(colorGlyphJsonString(destinationReadClass)).append(", ")
        append(colorGlyphJsonString("requiresLayerIsolation")).append(": ").append(requiresLayerIsolation)
        append("}")
    }
}

data class COLRV1ClipEvidence(
    val clipBounds: ColorGlyphBounds,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("clipBounds")).append(": ").append(clipBounds.toCanonicalJson())
        append("}")
    }
}

data class COLRV1PaintGraphNode(
    val nodeId: Int,
    val kind: String,
    val childNodeIds: List<Int> = emptyList(),
    val glyphId: Int? = null,
    val referencedColrGlyphId: Int? = null,
    val paletteIndex: Int? = null,
    val resolvedColorArgb: String? = null,
    val alpha: Float? = null,
    val varIndexBase: Long? = null,
    val outlineArtifactKey: ColorGlyphArtifactKey? = null,
    val bounds: ColorGlyphBounds? = null,
    val gradient: COLRV1GradientEvidence? = null,
    val transform: COLRV1TransformEvidence? = null,
    val composite: COLRV1CompositeEvidence? = null,
    val clip: COLRV1ClipEvidence? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("nodeId")).append(": ").append(nodeId).append(", ")
        append(colorGlyphJsonString("kind")).append(": ").append(colorGlyphJsonString(kind)).append(", ")
        append(colorGlyphJsonString("childNodeIds")).append(": ")
        append(childNodeIds.joinToString(prefix = "[", postfix = "]"))
        append(", ")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId ?: "null").append(", ")
        append(colorGlyphJsonString("referencedColrGlyphId")).append(": ").append(referencedColrGlyphId ?: "null").append(", ")
        append(colorGlyphJsonString("paletteIndex")).append(": ").append(paletteIndex ?: "null").append(", ")
        append(colorGlyphJsonString("resolvedColorArgb")).append(": ").append(colorGlyphNullableString(resolvedColorArgb)).append(", ")
        append(colorGlyphJsonString("alpha")).append(": ").append(alpha?.let(::colorGlyphFloatToken) ?: "null").append(", ")
        append(colorGlyphJsonString("varIndexBase")).append(": ").append(varIndexBase ?: "null").append(", ")
        append(colorGlyphJsonString("outlineArtifactKey")).append(": ")
        append(outlineArtifactKey?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("bounds")).append(": ").append(bounds?.toCanonicalJson() ?: "null")
        append(", ")
        append(colorGlyphJsonString("gradient")).append(": ").append(gradient?.toCanonicalJson() ?: "null")
        if (transform != null) {
            append(", ")
            append(colorGlyphJsonString("transform")).append(": ").append(transform.toCanonicalJson())
        }
        if (composite != null) {
            append(", ")
            append(colorGlyphJsonString("composite")).append(": ").append(composite.toCanonicalJson())
        }
        if (clip != null) {
            append(", ")
            append(colorGlyphJsonString("clip")).append(": ").append(clip.toCanonicalJson())
        }
        append("}")
    }
}

data class COLRV1PaintGraphEvidence(
    val glyphId: Int,
    val typefaceId: TypefaceID,
    val paletteIdentity: String,
    val rootNodeId: Int,
    val supportedOperationGroup: String,
    val nodes: List<COLRV1PaintGraphNode>,
    val bounds: ColorGlyphBounds,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false, indent = "").toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true, indent = "")

    internal fun toCanonicalJson(indent: String): String = canonicalJson(includeDumpSha256 = true, indent = indent)

    private fun canonicalJson(includeDumpSha256: Boolean, indent: String): String = buildString {
        val fieldIndent = "$indent  "
        append("{\n")
        append(fieldIndent).append(colorGlyphJsonString("schema")).append(": ")
        append(colorGlyphJsonString(COLRV1PaintGraphSchema)).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("typefaceId")).append(": ")
        append(colorGlyphJsonString(typefaceId.value.toString())).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("paletteIdentity")).append(": ")
        append(colorGlyphJsonString(paletteIdentity)).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("rootNodeId")).append(": ").append(rootNodeId).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("supportedOperationGroup")).append(": ")
        append(colorGlyphJsonString(supportedOperationGroup)).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("nodes")).append(": ")
        appendColorGlyphGraphNodesJson(nodes, indent = fieldIndent)
        append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("bounds")).append(": ").append(bounds.toCanonicalJson()).append(",\n")
        append(fieldIndent).append(colorGlyphJsonString("diagnostics")).append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = fieldIndent)
        if (includeDumpSha256) {
            append(",\n")
            append(fieldIndent).append(colorGlyphJsonString("dumpSha256")).append(": ")
            append(colorGlyphJsonString(dumpSha256)).append("\n")
        } else {
            append("\n")
        }
        append(indent).append("}")
    }

    fun toCompositePlan(): ColorGlyphCompositePlan? {
        val operations = nodes
            .filter { node -> node.transform != null || node.composite != null || node.clip != null }
            .map { node ->
                ColorGlyphCompositeOperation(
                    nodeId = node.nodeId,
                    kind = node.kind,
                    childNodeIds = node.childNodeIds,
                    bounds = node.bounds,
                    transform = node.transform,
                    composite = node.composite,
                    clip = node.clip,
                )
            }
        if (operations.isEmpty()) return null
        return ColorGlyphCompositePlan(
            glyphId = glyphId,
            typefaceId = typefaceId,
            paletteIdentity = paletteIdentity,
            supportedOperationGroup = supportedOperationGroup,
            operations = operations,
            diagnostics = diagnostics,
        )
    }

    companion object {
        const val COLRV1PaintGraphSchema: String = "org.graphiks.kanvas.glyph.color.COLRV1PaintGraph.v1"
    }
}

data class ColorGlyphCompositeOperation(
    val nodeId: Int,
    val kind: String,
    val childNodeIds: List<Int>,
    val bounds: ColorGlyphBounds?,
    val transform: COLRV1TransformEvidence? = null,
    val composite: COLRV1CompositeEvidence? = null,
    val clip: COLRV1ClipEvidence? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("nodeId")).append(": ").append(nodeId).append(", ")
        append(colorGlyphJsonString("kind")).append(": ").append(colorGlyphJsonString(kind)).append(", ")
        append(colorGlyphJsonString("childNodeIds")).append(": ")
        append(childNodeIds.joinToString(prefix = "[", postfix = "]"))
        append(", ")
        append(colorGlyphJsonString("bounds")).append(": ").append(bounds?.toCanonicalJson() ?: "null")
        if (transform != null) {
            append(", ")
            append(colorGlyphJsonString("transform")).append(": ").append(transform.toCanonicalJson())
        }
        if (composite != null) {
            append(", ")
            append(colorGlyphJsonString("composite")).append(": ").append(composite.toCanonicalJson())
        }
        if (clip != null) {
            append(", ")
            append(colorGlyphJsonString("clip")).append(": ").append(clip.toCanonicalJson())
        }
        append("}")
    }
}

data class ColorGlyphCompositePlan(
    val glyphId: Int,
    val typefaceId: TypefaceID,
    val paletteIdentity: String,
    val supportedOperationGroup: String,
    val operations: List<ColorGlyphCompositeOperation>,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        append("  ").append(colorGlyphJsonString("schema")).append(": ")
        append(colorGlyphJsonString(ColorGlyphCompositePlanSchema)).append(",\n")
        append("  ").append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(",\n")
        append("  ").append(colorGlyphJsonString("typefaceId")).append(": ")
        append(colorGlyphJsonString(typefaceId.value.toString())).append(",\n")
        append("  ").append(colorGlyphJsonString("paletteIdentity")).append(": ")
        append(colorGlyphJsonString(paletteIdentity)).append(",\n")
        append("  ").append(colorGlyphJsonString("supportedOperationGroup")).append(": ")
        append(colorGlyphJsonString(supportedOperationGroup)).append(",\n")
        append("  ").append(colorGlyphJsonString("operations")).append(": ")
        append(
            operations.joinToString(
                prefix = "[\n",
                postfix = "\n  ]",
                separator = ",\n",
            ) { operation -> "    ${operation.toCanonicalJson()}" },
        )
        append(",\n")
        append("  ").append(colorGlyphJsonString("diagnostics")).append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            append("  ").append(colorGlyphJsonString("dumpSha256")).append(": ")
            append(colorGlyphJsonString(dumpSha256)).append("\n")
        } else {
            append("\n")
        }
        append("}")
    }

    companion object {
        const val ColorGlyphCompositePlanSchema: String = "org.graphiks.kanvas.glyph.color.ColorGlyphCompositePlan.v1"
    }
}

/**
 * Promotes parsed COLRv0 and CPAL facts into a deterministic `ColorGlyphPlan`.
 *
 * This planner is intentionally CPU-only coordination evidence. It does not decode bitmaps,
 * rasterize SVG, composite color layers, allocate GPU resources, or claim GPU support.
 */
class COLRV0ColorGlyphPlanner(
    private val colr: COLRV0Table?,
    private val cpal: CPALTable?,
    private val layerBounds: Map<Int, ColorGlyphBounds> = emptyMap(),
) {
    fun plan(
        glyphId: Int,
        typefaceId: TypefaceID,
        strikeKey: GlyphStrikeKey,
        paletteSelection: CPALPaletteSelection = CPALPaletteSelection.Default,
        allowMonochromeFallback: Boolean = false,
        outlineFallback: OutlineGlyphRepresentation? = null,
    ): COLRV0ColorGlyphPlanDecision {
        require(glyphId >= 0) { "COLRv0 color glyph planner requires a non-negative glyphId." }

        val table = colr ?: return refusal(
            diagnostic = colrMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;tableFamily=COLR;version=0;reason=table-unavailable",
                message = "COLRv0 table facts are unavailable for glyph $glyphId.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )
        val paletteTable = cpal ?: return refusal(
            diagnostic = cpalMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;tableFamily=CPAL;version=0;reason=table-unavailable",
                message = "COLRv0 palette selection is unavailable for glyph $glyphId: CPAL table facts are missing.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )
        val palette = paletteSelection.select(paletteTable) ?: return refusal(
            diagnostic = cpalMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;tableFamily=CPAL;version=0;requestedPaletteIndex=${paletteSelection.index};" +
                    "availablePaletteCount=${paletteTable.palettes.size}",
                message = "COLRv0 palette selection is unavailable for glyph $glyphId: requested palette " +
                    "${paletteSelection.index} is outside the CPAL range.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )

        val layers = table.layersForGlyph(glyphId)
        if (layers.isEmpty()) {
            return refusal(
                diagnostic = colrMalformedDiagnostic(
                    glyphId = glyphId,
                    detail = "glyphId=$glyphId;tableFamily=COLR;version=0;reason=missing-base-glyph-record",
                    message = "COLRv0 base glyph record is unavailable for glyph $glyphId.",
                ),
                glyphId = glyphId,
                allowMonochromeFallback = allowMonochromeFallback,
                outlineFallback = outlineFallback,
            )
        }

        val layerPlans = ArrayList<COLRV0LayerPlan>(layers.size)
        var aggregateBounds: ColorGlyphBounds? = null
        layers.forEachIndexed { layerIndex, layer ->
            val usesForeground = layer.paletteIndex == COLR_FOREGROUND_PALETTE_INDEX
            val resolvedColor = when {
                usesForeground -> null
                else -> palette.colors.getOrNull(layer.paletteIndex) ?: return refusal(
                    diagnostic = cpalMalformedDiagnostic(
                        glyphId = glyphId,
                        detail = "glyphId=$glyphId;tableFamily=CPAL;version=0;requestedPaletteIndex=${palette.index};" +
                            "layerIndex=$layerIndex;layerGlyphId=${layer.glyphId};paletteEntry=${layer.paletteIndex};" +
                            "availableColorCount=${palette.colors.size}",
                        message = "COLRv0 palette selection is unavailable for glyph $glyphId: layer $layerIndex " +
                            "references missing CPAL entry ${layer.paletteIndex}.",
                    ),
                    glyphId = glyphId,
                    allowMonochromeFallback = allowMonochromeFallback,
                    outlineFallback = outlineFallback,
                )
            }

            val bounds = layerBounds[layer.glyphId] ?: return refusal(
                diagnostic = colrMalformedDiagnostic(
                    glyphId = glyphId,
                    detail = "glyphId=$glyphId;tableFamily=COLR;version=0;layerIndex=$layerIndex;" +
                        "layerGlyphId=${layer.glyphId};reason=layer-bounds-missing",
                    message = "COLRv0 layer bounds are unavailable for glyph $glyphId layer $layerIndex.",
                ),
                glyphId = glyphId,
                allowMonochromeFallback = allowMonochromeFallback,
                outlineFallback = outlineFallback,
            )

            val outlineArtifactKey = strikeKey.artifactKeyForGlyph(
                glyphId = layer.glyphId,
                route = OutlineArtifactRoute,
            )
            val layerPlan = COLRV0LayerPlan(
                layerIndex = layerIndex,
                glyphId = layer.glyphId,
                paletteIndex = layer.paletteIndex,
                resolvedColor = resolvedColor,
                usesForegroundColor = usesForeground,
                outlineArtifactKey = outlineArtifactKey,
                bounds = bounds,
            )
            layerPlans += layerPlan
            aggregateBounds = aggregateBounds?.union(bounds) ?: bounds
        }

        val plan = ColorGlyphPlan(
            glyphId = glyphId,
            typefaceId = typefaceId,
            routeKind = "colrv0",
            artifactKey = strikeKey.artifactKeyForGlyph(
                glyphId = glyphId,
                route = ColorArtifactRoute,
            ),
            palette = ColorGlyphPalette(
                identity = strikeKey.paletteIdentity ?: "cpal:${palette.index}",
                selectionIndex = paletteSelection.index,
                resolvedIndex = palette.index,
                overrideCount = paletteSelection.overrides
                    .map { override -> override.index }
                    .distinct()
                    .count { index -> index in palette.colors.indices },
                colorCount = palette.colors.size,
            ),
            layers = layerPlans.toList(),
            paintGraph = null,
            bounds = aggregateBounds ?: error("COLRv0 layer plans must produce aggregate bounds."),
            fallbackPolicy = "allow-monochrome-outline-fallback",
            diagnostics = emptyList(),
        )
        return COLRV0ColorGlyphPlanDecision(
            plan = plan,
            selectedRoute = ColorGlyphRoute(glyphId = glyphId, route = "colr"),
            diagnostics = emptyList(),
        )
    }

    private fun refusal(
        diagnostic: ColorGlyphDiagnostic,
        glyphId: Int,
        allowMonochromeFallback: Boolean,
        outlineFallback: OutlineGlyphRepresentation?,
    ): COLRV0ColorGlyphPlanDecision {
        val selectedRoute = if (allowMonochromeFallback && outlineFallback?.glyphId == glyphId) {
            ColorGlyphRoute(glyphId = glyphId, route = "outline", outline = outlineFallback)
        } else {
            null
        }
        return COLRV0ColorGlyphPlanDecision(
            plan = null,
            selectedRoute = selectedRoute,
            diagnostics = listOf(diagnostic),
        )
    }
}

class COLRV1ColorGlyphPlanner(
    private val colr: COLRV1Table?,
    private val cpal: CPALTable?,
    private val glyphBounds: Map<Int, ColorGlyphBounds> = emptyMap(),
    private val variationAlphaDeltas: Map<Long, Float> = emptyMap(),
    private val maxRecursionDepth: Int = 8,
    private val maxExpandedNodeCount: Int = 64,
    private val maxGradientStopCount: Int = 64,
    private val maxClipBoxCount: Int = 1,
) {
    fun plan(
        glyphId: Int,
        typefaceId: TypefaceID,
        strikeKey: GlyphStrikeKey,
        paletteSelection: CPALPaletteSelection = CPALPaletteSelection.Default,
        allowMonochromeFallback: Boolean = false,
        outlineFallback: OutlineGlyphRepresentation? = null,
    ): COLRV1ColorGlyphPlanDecision {
        val table = colr ?: return refusal(
            diagnostic = colrMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;nodeId=0;tableFamily=COLR;version=1;reason=table-unavailable",
                message = "COLRv1 table facts are unavailable for glyph $glyphId.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )
        val paletteTable = cpal ?: return refusal(
            diagnostic = cpalMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;nodeId=0;tableFamily=CPAL;version=0;reason=table-unavailable",
                message = "COLRv1 palette selection is unavailable for glyph $glyphId: CPAL table facts are missing.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )
        val palette = paletteSelection.select(paletteTable) ?: return refusal(
            diagnostic = cpalMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;nodeId=0;tableFamily=CPAL;version=0;requestedPaletteIndex=${paletteSelection.index};availablePaletteCount=${paletteTable.palettes.size}",
                message = "COLRv1 palette selection is unavailable for glyph $glyphId: requested palette ${paletteSelection.index} is outside the CPAL range.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )
        val rootPaint = table.paintForGlyph(glyphId) ?: return refusal(
            diagnostic = colrMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;nodeId=0;tableFamily=COLR;version=1;reason=missing-base-glyph-record",
                message = "COLRv1 base glyph record is unavailable for glyph $glyphId.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )

        val nodes = ArrayList<COLRV1PaintGraphNode>()
        var nextNodeId = 1
        var expandedNodeCount = 0

        data class WalkResult(
            val nodeId: Int,
            val nodeIndex: Int,
            val bounds: ColorGlyphBounds,
        )

        fun budgetExceeded(limitName: String, limit: Int, observed: Int): COLRV1ColorGlyphPlanDecision =
            refusal(
                diagnostic = COLRV1Parser.budgetExceededDiagnostic(
                    glyphId = glyphId,
                    limitName = limitName,
                    limit = limit,
                    observed = observed,
                ),
                glyphId = glyphId,
                allowMonochromeFallback = allowMonochromeFallback,
                outlineFallback = outlineFallback,
            )

        fun unsupportedPaint(nodeId: Int, paintKind: String, detail: String): COLRV1ColorGlyphPlanDecision =
            refusal(
                diagnostic = ColorGlyphDiagnostic(
                    glyphId = glyphId,
                    route = "colr",
                    code = ColorGlyphDiagnosticCodes.COLRV1PaintUnsupported,
                    severity = "warning",
                    detail = "glyphId=$glyphId;nodeId=$nodeId;paintKind=$paintKind;$detail",
                    message = "COLRv1 paint $paintKind is unsupported for glyph $glyphId node $nodeId.",
                ),
                glyphId = glyphId,
                allowMonochromeFallback = allowMonochromeFallback,
                outlineFallback = outlineFallback,
            )

        fun malformed(nodeId: Int, detail: String, message: String): COLRV1ColorGlyphPlanDecision =
            refusal(
                diagnostic = colrMalformedDiagnostic(
                    glyphId = glyphId,
                    detail = "glyphId=$glyphId;nodeId=$nodeId;tableFamily=COLR;version=1;$detail",
                    message = message,
                ),
                glyphId = glyphId,
                allowMonochromeFallback = allowMonochromeFallback,
                outlineFallback = outlineFallback,
            )

        fun reserveNode(kind: String): Pair<Int, Int> {
            val nodeId = nextNodeId++
            val index = nodes.size
            nodes += COLRV1PaintGraphNode(nodeId = nodeId, kind = kind)
            return nodeId to index
        }

        fun setNode(index: Int, node: COLRV1PaintGraphNode) {
            nodes[index] = node
        }

        fun malformedGradientCoordinates(
            nodeId: Int,
            paintKind: String,
            detail: String,
        ): COLRV1ColorGlyphPlanDecision = malformed(
            nodeId = nodeId,
            detail = "reason=malformed-gradient-coordinates;paintKind=$paintKind;$detail",
            message = "COLRv1 gradient coordinates are malformed for glyph $glyphId node $nodeId.",
        )

        fun gradientStopEvidence(
            nodeId: Int,
            paintKind: String,
            colorLine: COLRV1ColorLine,
        ): List<COLRV1GradientStopEvidence> {
            if (colorLine.stops.size > maxGradientStopCount) {
                throw COLRV1PlannerRefusal(
                    budgetExceeded(
                        limitName = "gradientStops",
                        limit = maxGradientStopCount,
                        observed = colorLine.stops.size,
                    ),
                )
            }
            return colorLine.stops.mapIndexed { stopIndex, stop ->
                val appliedAlphaDelta = stop.varIndexBase?.let { varIndexBase ->
                    variationAlphaDeltas[varIndexBase]
                        ?: throw COLRV1PlannerRefusal(
                            unsupportedPaint(
                                nodeId = nodeId,
                                paintKind = paintKind,
                                detail = "reason=variable-color-data-unsupported;stopIndex=$stopIndex;varIndexBase=$varIndexBase",
                            ),
                        )
                }
                val resolvedAlpha = (stop.alpha + (appliedAlphaDelta ?: 0f)).coerceIn(0f, 1f)
                COLRV1GradientStopEvidence(
                    stopIndex = stopIndex,
                    offset = stop.offset,
                    paletteIndex = stop.paletteIndex,
                    resolvedColorArgb = resolvePaletteColorArgb(
                        palette = palette,
                        paletteIndex = stop.paletteIndex,
                        alpha = resolvedAlpha,
                    ),
                    alpha = resolvedAlpha,
                    varIndexBase = stop.varIndexBase,
                    appliedAlphaDelta = appliedAlphaDelta,
                )
            }
        }

        fun wrapWithClip(clipGlyphId: Int, childWalk: () -> WalkResult?): WalkResult? {
            val clipBox = table.clipBoxForGlyph(clipGlyphId) ?: return childWalk()
            if (1 > maxClipBoxCount) {
                throw COLRV1PlannerRefusal(
                    budgetExceeded(
                        limitName = "clipBoxes",
                        limit = maxClipBoxCount,
                        observed = 1,
                    ),
                )
            }
            val nodeIdAndIndex = reserveNode("colrv1-paint-clip-box")
            val child = childWalk() ?: return null
            val clipBounds = clipBox.toBounds()
            val clippedBounds = child.bounds.intersectConservative(clipBounds)
            setNode(
                nodeIdAndIndex.second,
                COLRV1PaintGraphNode(
                    nodeId = nodeIdAndIndex.first,
                    kind = "colrv1-paint-clip-box",
                    childNodeIds = listOf(child.nodeId),
                    bounds = clippedBounds,
                    clip = COLRV1ClipEvidence(clipBounds = clipBounds),
                ),
            )
            return WalkResult(
                nodeId = nodeIdAndIndex.first,
                nodeIndex = nodeIdAndIndex.second,
                bounds = clippedBounds,
            )
        }

        fun walk(paint: COLRV1Paint, depth: Int): WalkResult? {
            if (depth > maxRecursionDepth) {
                val decision = budgetExceeded(
                    limitName = "recursionDepth",
                    limit = maxRecursionDepth,
                    observed = depth,
                )
                throw COLRV1PlannerRefusal(decision)
            }
            expandedNodeCount += 1
            if (expandedNodeCount > maxExpandedNodeCount) {
                val decision = budgetExceeded(
                    limitName = "expandedPaintCount",
                    limit = maxExpandedNodeCount,
                    observed = expandedNodeCount,
                )
                throw COLRV1PlannerRefusal(decision)
            }

            return when (paint) {
                is COLRV1Paint.Solid -> {
                    val nodeIdAndIndex = reserveNode(
                        if (paint.varIndexBase == null) "colrv1-paint-solid" else "colrv1-paint-var-solid",
                    )
                    val resolvedAlpha = if (paint.varIndexBase == null) {
                        paint.alpha
                    } else {
                        val delta = variationAlphaDeltas[paint.varIndexBase]
                            ?: throw COLRV1PlannerRefusal(
                                unsupportedPaint(
                                    nodeId = nodeIdAndIndex.first,
                                    paintKind = "colrv1-paint-var-solid",
                                    detail = "reason=variable-color-data-unsupported;varIndexBase=${paint.varIndexBase}",
                                ),
                            )
                        (paint.alpha + delta).coerceIn(0f, 1f)
                    }
                    val resolvedColor = resolvePaletteColorArgb(
                        palette = palette,
                        paletteIndex = paint.paletteIndex,
                        alpha = resolvedAlpha,
                    )
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = if (paint.varIndexBase == null) "colrv1-paint-solid" else "colrv1-paint-var-solid",
                            paletteIndex = paint.paletteIndex,
                            resolvedColorArgb = resolvedColor,
                            alpha = resolvedAlpha,
                            varIndexBase = paint.varIndexBase,
                        ),
                    )
                    WalkResult(
                        nodeId = nodeIdAndIndex.first,
                        nodeIndex = nodeIdAndIndex.second,
                        bounds = ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 1, yMax = 1),
                    )
                }
                is COLRV1Paint.Glyph -> {
                    val nodeIdAndIndex = reserveNode("colrv1-paint-glyph")
                    val child = walk(paint.paint, depth + 1) ?: return null
                    val bounds = glyphBounds[paint.glyphId]
                        ?: throw COLRV1PlannerRefusal(
                            malformed(
                                nodeId = nodeIdAndIndex.first,
                                detail = "reason=missing-glyph-bounds;referencedGlyphId=${paint.glyphId}",
                                message = "COLRv1 glyph bounds are unavailable for glyph $glyphId node ${nodeIdAndIndex.first}.",
                            ),
                        )
                    val childNode = nodes[child.nodeIndex]
                    if (childNode.gradient != null && childNode.bounds == null) {
                        setNode(
                            child.nodeIndex,
                            childNode.copy(bounds = bounds),
                        )
                    }
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = "colrv1-paint-glyph",
                            childNodeIds = listOf(child.nodeId),
                            glyphId = paint.glyphId,
                            outlineArtifactKey = strikeKey.artifactKeyForGlyph(
                                glyphId = paint.glyphId,
                                route = OutlineArtifactRoute,
                            ),
                            bounds = bounds,
                        ),
                    )
                    WalkResult(nodeId = nodeIdAndIndex.first, nodeIndex = nodeIdAndIndex.second, bounds = bounds)
                }
                is COLRV1Paint.ColrGlyph -> {
                    val nodeIdAndIndex = reserveNode("colrv1-paint-colr-glyph")
                    val referencedPaint = table.paintForGlyph(paint.glyphId)
                        ?: throw COLRV1PlannerRefusal(
                            malformed(
                                nodeId = nodeIdAndIndex.first,
                                detail = "reason=missing-colr-glyph-reference;referencedGlyphId=${paint.glyphId}",
                                message = "COLRv1 PaintColrGlyph reference is unavailable for glyph $glyphId node ${nodeIdAndIndex.first}.",
                            ),
                        )
                    val child = wrapWithClip(clipGlyphId = paint.glyphId) {
                        walk(referencedPaint, depth + 1)
                    } ?: return null
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = "colrv1-paint-colr-glyph",
                            childNodeIds = listOf(child.nodeId),
                            referencedColrGlyphId = paint.glyphId,
                            bounds = child.bounds,
                        ),
                    )
                    WalkResult(nodeId = nodeIdAndIndex.first, nodeIndex = nodeIdAndIndex.second, bounds = child.bounds)
                }
                is COLRV1Paint.Translate -> {
                    val nodeIdAndIndex = reserveNode("colrv1-paint-translate")
                    if (paint.varIndexBase != null) {
                        throw COLRV1PlannerRefusal(
                            unsupportedPaint(
                                nodeId = nodeIdAndIndex.first,
                                paintKind = "colrv1-paint-translate",
                                detail = "reason=variable-transform-unsupported;varIndexBase=${paint.varIndexBase}",
                            ),
                        )
                    }
                    val child = walk(paint.paint, depth + 1) ?: return null
                    val bounds = child.bounds.transformedBy(
                        xx = 1f,
                        yx = 0f,
                        xy = 0f,
                        yy = 1f,
                        dx = paint.dx.toFloat(),
                        dy = paint.dy.toFloat(),
                    )
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = "colrv1-paint-translate",
                            childNodeIds = listOf(child.nodeId),
                            bounds = bounds,
                            transform = COLRV1TransformEvidence(
                                transformKind = "translate",
                                xx = 1f,
                                yx = 0f,
                                xy = 0f,
                                yy = 1f,
                                dx = paint.dx.toFloat(),
                                dy = paint.dy.toFloat(),
                                determinant = 1f,
                            ),
                            varIndexBase = paint.varIndexBase,
                        ),
                    )
                    WalkResult(nodeId = nodeIdAndIndex.first, nodeIndex = nodeIdAndIndex.second, bounds = bounds)
                }
                is COLRV1Paint.Transform -> {
                    val classification = classifyCOLRV1Transform(
                        xx = paint.xx,
                        yx = paint.yx,
                        xy = paint.xy,
                        yy = paint.yy,
                    )
                    val nodeIdAndIndex = reserveNode(classification.nodeKind)
                    if (paint.varIndexBase != null) {
                        throw COLRV1PlannerRefusal(
                            unsupportedPaint(
                                nodeId = nodeIdAndIndex.first,
                                paintKind = classification.nodeKind,
                                detail = "reason=variable-transform-unsupported;varIndexBase=${paint.varIndexBase}",
                            ),
                        )
                    }
                    if (!paint.xx.isFinite() || !paint.yx.isFinite() || !paint.xy.isFinite() || !paint.yy.isFinite() ||
                        !paint.dx.isFinite() || !paint.dy.isFinite()
                    ) {
                        throw COLRV1PlannerRefusal(
                            malformed(
                                nodeId = nodeIdAndIndex.first,
                                detail = "reason=malformed-transform-payload;paintKind=${classification.nodeKind}",
                                message = "COLRv1 transform payload is malformed for glyph $glyphId node ${nodeIdAndIndex.first}.",
                            ),
                        )
                    }
                    val determinant = (paint.xx * paint.yy) - (paint.xy * paint.yx)
                    if (abs(determinant) <= COLRV1TransformDeterminantEpsilon) {
                        throw COLRV1PlannerRefusal(
                            malformed(
                                nodeId = nodeIdAndIndex.first,
                                detail = "reason=singular-transform;paintKind=${classification.nodeKind};determinant=${colorGlyphFloatToken(determinant)}",
                                message = "COLRv1 transform is singular for glyph $glyphId node ${nodeIdAndIndex.first}.",
                            ),
                        )
                    }
                    val child = walk(paint.paint, depth + 1) ?: return null
                    val bounds = child.bounds.transformedBy(
                        xx = paint.xx,
                        yx = paint.yx,
                        xy = paint.xy,
                        yy = paint.yy,
                        dx = paint.dx,
                        dy = paint.dy,
                    )
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = classification.nodeKind,
                            childNodeIds = listOf(child.nodeId),
                            bounds = bounds,
                            transform = COLRV1TransformEvidence(
                                transformKind = classification.transformKind,
                                xx = paint.xx,
                                yx = paint.yx,
                                xy = paint.xy,
                                yy = paint.yy,
                                dx = paint.dx,
                                dy = paint.dy,
                                determinant = determinant,
                            ),
                            varIndexBase = paint.varIndexBase,
                        ),
                    )
                    WalkResult(nodeId = nodeIdAndIndex.first, nodeIndex = nodeIdAndIndex.second, bounds = bounds)
                }
                is COLRV1Paint.Composite -> {
                    val nodeKind = "colrv1-paint-composite-${paint.mode.graphSuffix}"
                    val nodeIdAndIndex = reserveNode(nodeKind)
                    val compositePlan = compositePlanForMode(paint.mode)
                        ?: throw COLRV1PlannerRefusal(
                            unsupportedPaint(
                                nodeId = nodeIdAndIndex.first,
                                paintKind = nodeKind,
                                detail = "reason=composite-mode-unsupported;mode=${paint.mode.graphSuffix}",
                            ),
                        )
                    val source = walk(paint.source, depth + 1) ?: return null
                    val backdrop = walk(paint.backdrop, depth + 1) ?: return null
                    val bounds = source.bounds.union(backdrop.bounds)
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = nodeKind,
                            childNodeIds = listOf(source.nodeId, backdrop.nodeId),
                            bounds = bounds,
                            composite = COLRV1CompositeEvidence(
                                mode = paint.mode.graphSuffix,
                                sourceNodeId = source.nodeId,
                                backdropNodeId = backdrop.nodeId,
                                destinationReadClass = compositePlan.destinationReadClass,
                                requiresLayerIsolation = compositePlan.requiresLayerIsolation,
                            ),
                        ),
                    )
                    WalkResult(nodeId = nodeIdAndIndex.first, nodeIndex = nodeIdAndIndex.second, bounds = bounds)
                }
                is COLRV1Paint.LinearGradient -> {
                    val nodeIdAndIndex = reserveNode("colrv1-paint-linear-gradient")
                    if (paint.varIndexBase != null) {
                        throw COLRV1PlannerRefusal(
                            unsupportedPaint(
                                nodeId = nodeIdAndIndex.first,
                                paintKind = "colrv1-paint-linear-gradient",
                                detail = "reason=variable-gradient-geometry-unsupported;varIndexBase=${paint.varIndexBase}",
                            ),
                        )
                    }
                    if (paint.x0 == paint.x1 && paint.y0 == paint.y1) {
                        throw COLRV1PlannerRefusal(
                            malformedGradientCoordinates(
                                nodeId = nodeIdAndIndex.first,
                                paintKind = "colrv1-paint-linear-gradient",
                                detail = "x0=${paint.x0};y0=${paint.y0};x1=${paint.x1};y1=${paint.y1};x2=${paint.x2};y2=${paint.y2}",
                            ),
                        )
                    }
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = "colrv1-paint-linear-gradient",
                            gradient = COLRV1GradientEvidence(
                                extendMode = paint.colorLine.extend.tag,
                                stops = gradientStopEvidence(
                                    nodeId = nodeIdAndIndex.first,
                                    paintKind = "colrv1-paint-linear-gradient",
                                    colorLine = paint.colorLine,
                                ),
                                variationCoordinates = if (paint.colorLine.stops.any { stop -> stop.varIndexBase != null }) {
                                    strikeKey.variationCoordinates
                                } else {
                                    emptyMap()
                                },
                                linearGeometry = COLRV1LinearGradientGeometry(
                                    x0 = paint.x0,
                                    y0 = paint.y0,
                                    x1 = paint.x1,
                                    y1 = paint.y1,
                                    x2 = paint.x2,
                                    y2 = paint.y2,
                                ),
                            ),
                            varIndexBase = paint.varIndexBase,
                        ),
                    )
                    WalkResult(
                        nodeId = nodeIdAndIndex.first,
                        nodeIndex = nodeIdAndIndex.second,
                        bounds = ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 1, yMax = 1),
                    )
                }
                is COLRV1Paint.RadialGradient -> {
                    val nodeIdAndIndex = reserveNode("colrv1-paint-radial-gradient")
                    if (paint.varIndexBase != null) {
                        throw COLRV1PlannerRefusal(
                            unsupportedPaint(
                                nodeId = nodeIdAndIndex.first,
                                paintKind = "colrv1-paint-radial-gradient",
                                detail = "reason=variable-gradient-geometry-unsupported;varIndexBase=${paint.varIndexBase}",
                            ),
                        )
                    }
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = "colrv1-paint-radial-gradient",
                            gradient = COLRV1GradientEvidence(
                                extendMode = paint.colorLine.extend.tag,
                                stops = gradientStopEvidence(
                                    nodeId = nodeIdAndIndex.first,
                                    paintKind = "colrv1-paint-radial-gradient",
                                    colorLine = paint.colorLine,
                                ),
                                radialGeometry = COLRV1RadialGradientGeometry(
                                    x0 = paint.x0,
                                    y0 = paint.y0,
                                    radius0 = paint.radius0,
                                    x1 = paint.x1,
                                    y1 = paint.y1,
                                    radius1 = paint.radius1,
                                ),
                            ),
                            varIndexBase = paint.varIndexBase,
                        ),
                    )
                    WalkResult(
                        nodeId = nodeIdAndIndex.first,
                        nodeIndex = nodeIdAndIndex.second,
                        bounds = ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 1, yMax = 1),
                    )
                }
                is COLRV1Paint.SweepGradient -> {
                    val nodeIdAndIndex = reserveNode("colrv1-paint-sweep-gradient")
                    if (paint.varIndexBase != null) {
                        throw COLRV1PlannerRefusal(
                            unsupportedPaint(
                                nodeId = nodeIdAndIndex.first,
                                paintKind = "colrv1-paint-sweep-gradient",
                                detail = "reason=variable-gradient-geometry-unsupported;varIndexBase=${paint.varIndexBase}",
                            ),
                        )
                    }
                    setNode(
                        nodeIdAndIndex.second,
                        COLRV1PaintGraphNode(
                            nodeId = nodeIdAndIndex.first,
                            kind = "colrv1-paint-sweep-gradient",
                            gradient = COLRV1GradientEvidence(
                                extendMode = paint.colorLine.extend.tag,
                                stops = gradientStopEvidence(
                                    nodeId = nodeIdAndIndex.first,
                                    paintKind = "colrv1-paint-sweep-gradient",
                                    colorLine = paint.colorLine,
                                ),
                                sweepGeometry = COLRV1SweepGradientGeometry(
                                    centerX = paint.centerX,
                                    centerY = paint.centerY,
                                    startAngle = paint.startAngle,
                                    endAngle = paint.endAngle,
                                ),
                            ),
                            varIndexBase = paint.varIndexBase,
                        ),
                    )
                    WalkResult(
                        nodeId = nodeIdAndIndex.first,
                        nodeIndex = nodeIdAndIndex.second,
                        bounds = ColorGlyphBounds(xMin = 0, yMin = 0, xMax = 1, yMax = 1),
                    )
                }
                is COLRV1Paint.Layers -> throw COLRV1PlannerRefusal(
                    unsupportedPaint(
                        nodeId = nextNodeId,
                        paintKind = paint.colrv1PlannerKind(),
                        detail = "reason=operation-group-unsupported",
                    ),
                )
            }
        }

        val root = try {
            wrapWithClip(clipGlyphId = glyphId) {
                walk(rootPaint, depth = 1)
            }
        } catch (refusal: COLRV1PlannerRefusal) {
            return refusal.decision
        } ?: return refusal(
            diagnostic = colrMalformedDiagnostic(
                glyphId = glyphId,
                detail = "glyphId=$glyphId;nodeId=0;tableFamily=COLR;version=1;reason=graph-walk-failed",
                message = "COLRv1 graph walk failed for glyph $glyphId.",
            ),
            glyphId = glyphId,
            allowMonochromeFallback = allowMonochromeFallback,
            outlineFallback = outlineFallback,
        )

        val graph = COLRV1PaintGraphEvidence(
            glyphId = glyphId,
            typefaceId = typefaceId,
            paletteIdentity = strikeKey.paletteIdentity ?: "cpal:${palette.index}",
            rootNodeId = 0,
            supportedOperationGroup = when {
                nodes.any { node -> node.transform != null || node.composite != null || node.clip != null } -> "transform-composite-clip"
                nodes.any { node -> node.gradient != null } -> "gradient-glyph"
                else -> "solid-glyph-colr-glyph"
            },
            nodes = nodes.toList(),
            bounds = root.bounds,
            diagnostics = emptyList(),
        )
        val plan = ColorGlyphPlan(
            glyphId = glyphId,
            typefaceId = typefaceId,
            routeKind = "colrv1",
            artifactKey = strikeKey.artifactKeyForGlyph(
                glyphId = glyphId,
                route = ColorArtifactRoute,
            ),
            palette = ColorGlyphPalette(
                identity = strikeKey.paletteIdentity ?: "cpal:${palette.index}",
                selectionIndex = paletteSelection.index,
                resolvedIndex = palette.index,
                overrideCount = paletteSelection.overrides
                    .map { override -> override.index }
                    .distinct()
                    .count { index -> index in palette.colors.indices },
                colorCount = palette.colors.size,
            ),
            layers = emptyList(),
            paintGraph = graph,
            bounds = root.bounds,
            fallbackPolicy = "allow-monochrome-outline-fallback",
            diagnostics = emptyList(),
        )
        return COLRV1ColorGlyphPlanDecision(
            plan = plan,
            selectedRoute = ColorGlyphRoute(glyphId = glyphId, route = "colr"),
            diagnostics = emptyList(),
        )
    }

    private fun refusal(
        diagnostic: ColorGlyphDiagnostic,
        glyphId: Int,
        allowMonochromeFallback: Boolean,
        outlineFallback: OutlineGlyphRepresentation?,
    ): COLRV1ColorGlyphPlanDecision {
        val selectedRoute = if (allowMonochromeFallback && outlineFallback?.glyphId == glyphId) {
            ColorGlyphRoute(glyphId = glyphId, route = "outline", outline = outlineFallback)
        } else {
            null
        }
        return COLRV1ColorGlyphPlanDecision(
            plan = null,
            selectedRoute = selectedRoute,
            diagnostics = listOf(diagnostic),
        )
    }
}

private class COLRV1PlannerRefusal(
    val decision: COLRV1ColorGlyphPlanDecision,
) : RuntimeException()

/**
 * Stores the parsed COLR version 1 paint data supported by the pure Kotlin font stack.
 *
 * This is a renderer-neutral metadata model. It proves that a bounded COLRv1 paint graph can be
 * parsed deterministically, but it does not claim complete COLRv1 rendering support. Unsupported
 * paint formats, malformed offsets, excessive nesting, and count expansions are rejected by
 * [COLRV1Parser.parse] with `null`.
 *
 * @property baseGlyphPaintRecords COLRv1 base glyph paint records in source order.
 * @property layerPaints parsed LayerList paints in source order.
 * @property clipRanges parsed ClipList ranges in source order.
 */
data class COLRV1Table(
    val baseGlyphPaintRecords: List<COLRV1BaseGlyphPaintRecord>,
    val layerPaints: List<COLRV1Paint> = emptyList(),
    val clipRanges: List<COLRV1ClipRange> = emptyList(),
) {
    /**
     * Resolves the COLRv1 root paint for [glyphId].
     *
     * @param glyphId base glyph identifier to look up.
     * @return parsed root paint, or null when [glyphId] has no COLRv1 paint record.
     */
    fun paintForGlyph(glyphId: Int): COLRV1Paint? =
        baseGlyphPaintRecords.firstOrNull { record -> record.glyphId == glyphId }?.paint

    /**
     * Resolves the first ClipList box whose range contains [glyphId].
     *
     * @param glyphId glyph identifier to look up.
     * @return parsed clip box, or null when no parsed range covers [glyphId].
     */
    fun clipBoxForGlyph(glyphId: Int): COLRV1ClipBox? =
        clipRanges.firstOrNull { range -> glyphId in range.startGlyphId..range.endGlyphId }?.box

    /**
     * Builds a deterministic generic paint graph for [glyphId].
     *
     * The returned graph flattens the parsed COLRv1 paint tree into stable pre-order node IDs. It
     * preserves palette and glyph references needed by route diagnostics, while transform values
     * remain available on the typed [COLRV1Paint] model returned by [paintForGlyph].
     *
     * @param glyphId base glyph identifier to convert.
     * @return flattened graph, or null when [glyphId] has no COLRv1 paint record.
     */
    fun paintGraphForGlyph(glyphId: Int): COLRPaintGraph? {
        val paint = paintForGlyph(glyphId) ?: return null
        val nodes = ArrayList<COLRPaintNode>()
        val root = COLRPaintNode(
            id = 0,
            kind = "colr-v1-glyph",
            glyphId = glyphId,
        )
        nodes += root
        val childId = appendCOLRV1PaintNode(paint = paint, nodes = nodes)
        nodes[0] = root.copy(children = listOf(childId))
        return COLRPaintGraph(
            root = nodes[0],
            nodes = nodes.toList(),
        )
    }

    /**
     * Detects a PaintColrGlyph cycle reachable from [glyphId] and reports it as stable evidence.
     *
     * This traversal follows only already-parsed COLRv1 paint data. It does not expand a renderer
     * graph, compute bounds, resolve palettes, or claim complete COLRv1 rendering support.
     *
     * @param glyphId base glyph identifier to inspect for recursive PaintColrGlyph references.
     * @return cycle diagnostic, or null when no cycle is found through parsed PaintColrGlyph links.
     */
    fun paintColrGlyphCycleDiagnostic(glyphId: Int): ColorGlyphDiagnostic? {
        val glyphPath = ArrayList<Int>()

        fun cycleDiagnostic(cyclePath: List<Int>): ColorGlyphDiagnostic {
            val pathText = cyclePath.joinToString(">")
            val cycleLength = cyclePath.size - 1
            return ColorGlyphDiagnostic(
                glyphId = glyphId,
                route = "colr",
                code = ColorGlyphDiagnosticCodes.COLRV1CycleDetected,
                severity = "warning",
                detail = "glyphId=$glyphId;tableFamily=COLR;version=1;" +
                    "cyclePath=$pathText;cycleLength=$cycleLength",
                message = "COLRv1 PaintColrGlyph cycle detected for glyph $glyphId: $pathText.",
            )
        }

        fun visitPaint(paint: COLRV1Paint): ColorGlyphDiagnostic? {
            when (paint) {
                is COLRV1Paint.Solid,
                is COLRV1Paint.LinearGradient,
                is COLRV1Paint.RadialGradient,
                is COLRV1Paint.SweepGradient -> return null
                is COLRV1Paint.Glyph -> return visitPaint(paint.paint)
                is COLRV1Paint.Layers -> {
                    paint.paints.forEach { child ->
                        visitPaint(child)?.let { diagnostic -> return diagnostic }
                    }
                    return null
                }
                is COLRV1Paint.Composite -> {
                    visitPaint(paint.source)?.let { diagnostic -> return diagnostic }
                    return visitPaint(paint.backdrop)
                }
                is COLRV1Paint.ColrGlyph -> {
                    val cycleStart = glyphPath.indexOf(paint.glyphId)
                    if (cycleStart >= 0) {
                        return cycleDiagnostic(glyphPath.drop(cycleStart) + paint.glyphId)
                    }

                    val referencedPaint = paintForGlyph(paint.glyphId) ?: return null
                    glyphPath += paint.glyphId
                    val diagnostic = visitPaint(referencedPaint)
                    glyphPath.removeAt(glyphPath.lastIndex)
                    return diagnostic
                }
                is COLRV1Paint.Translate -> return visitPaint(paint.paint)
                is COLRV1Paint.Transform -> return visitPaint(paint.paint)
            }
        }

        val paint = paintForGlyph(glyphId) ?: return null
        glyphPath += glyphId
        val diagnostic = visitPaint(paint)
        glyphPath.removeAt(glyphPath.lastIndex)
        return diagnostic
    }
}

/**
 * Describes one COLR version 1 base glyph paint record.
 *
 * @property glyphId base glyph identifier whose color representation is described by [paint].
 * @property paint parsed root paint for [glyphId].
 */
data class COLRV1BaseGlyphPaintRecord(
    val glyphId: Int,
    val paint: COLRV1Paint,
)

/**
 * Describes one parsed COLR version 1 ClipList range.
 *
 * @property startGlyphId first glyph identifier covered by [box].
 * @property endGlyphId last glyph identifier covered by [box].
 * @property box parsed clip box for this inclusive glyph range.
 */
data class COLRV1ClipRange(
    val startGlyphId: Int,
    val endGlyphId: Int,
    val box: COLRV1ClipBox,
)

/**
 * Describes a COLR version 1 clip box in font design units.
 *
 * @property xMin minimum x coordinate.
 * @property yMin minimum y coordinate.
 * @property xMax maximum x coordinate.
 * @property yMax maximum y coordinate.
 */
data class COLRV1ClipBox(
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
)

/**
 * Renderer-neutral subset of COLR version 1 paint operations parsed by the pure Kotlin font stack.
 *
 * The supported subset is intentionally bounded: solid paints, glyph paints, layer groups,
 * gradients, PaintColrGlyph references, composites, translations, and affine transforms. Scale,
 * rotate, skew, and other COLRv1 paint formats currently make [COLRV1Parser.parse] return `null`
 * instead of producing a partial or misleading graph.
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
}

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
 * COLRv1 PaintComposite mode independent of renderer-specific blend enums.
 *
 * @property fontValue integer value stored by the OpenType COLR table.
 * @property graphSuffix stable lowercase label used by flattened paint graph nodes.
 */
enum class COLRV1CompositeMode(val fontValue: Int, val graphSuffix: String) {
    /** Clear both source and backdrop contributions. */
    CLEAR(0, "clear"),

    /** Use the source contribution. */
    SRC(1, "src"),

    /** Use the backdrop contribution. */
    DST(2, "dst"),

    /** Composite source over backdrop. */
    SRC_OVER(3, "src-over"),

    /** Composite backdrop over source. */
    DST_OVER(4, "dst-over"),

    /** Keep source only where backdrop exists. */
    SRC_IN(5, "src-in"),

    /** Keep backdrop only where source exists. */
    DST_IN(6, "dst-in"),

    /** Keep source only outside backdrop. */
    SRC_OUT(7, "src-out"),

    /** Keep backdrop only outside source. */
    DST_OUT(8, "dst-out"),

    /** Place source atop backdrop. */
    SRC_ATOP(9, "src-atop"),

    /** Place backdrop atop source. */
    DST_ATOP(10, "dst-atop"),

    /** Exclusive-or blend between source and backdrop. */
    XOR(11, "xor"),

    /** Add source and backdrop contributions. */
    PLUS(12, "plus"),

    /** Screen blend between source and backdrop. */
    SCREEN(13, "screen"),

    /** Overlay blend between source and backdrop. */
    OVERLAY(14, "overlay"),

    /** Darken blend between source and backdrop. */
    DARKEN(15, "darken"),

    /** Lighten blend between source and backdrop. */
    LIGHTEN(16, "lighten"),

    /** Color-dodge blend between source and backdrop. */
    COLOR_DODGE(17, "color-dodge"),

    /** Color-burn blend between source and backdrop. */
    COLOR_BURN(18, "color-burn"),

    /** Hard-light blend between source and backdrop. */
    HARD_LIGHT(19, "hard-light"),

    /** Soft-light blend between source and backdrop. */
    SOFT_LIGHT(20, "soft-light"),

    /** Difference blend between source and backdrop. */
    DIFFERENCE(21, "difference"),

    /** Exclusion blend between source and backdrop. */
    EXCLUSION(22, "exclusion"),

    /** Multiply blend between source and backdrop. */
    MULTIPLY(23, "multiply"),

    /** Hue component blend between source and backdrop. */
    HUE(24, "hue"),

    /** Saturation component blend between source and backdrop. */
    SATURATION(25, "saturation"),

    /** Color component blend between source and backdrop. */
    COLOR(26, "color"),

    /** Luminosity component blend between source and backdrop. */
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
     * Builds a stable refusal diagnostic when a COLRv1 paint graph exceeds a parser budget.
     *
     * @param glyphId base glyph identifier whose paint graph exceeded the budget, or null when the
     * glyph cannot be identified.
     * @param limitName stable budget name, such as `expandedPaintCount` or `paintDepth`.
     * @param limit configured budget limit.
     * @param observed observed value that exceeded [limit].
     * @return stable COLRv1 budget refusal diagnostic.
     */
    fun budgetExceededDiagnostic(
        glyphId: Int?,
        limitName: String,
        limit: Int,
        observed: Int,
    ): ColorGlyphDiagnostic {
        require(limitName.isNotBlank()) {
            "COLRv1 budget diagnostic limit name must be non-blank."
        }
        require(limit >= 0 && observed >= 0) {
            "COLRv1 budget diagnostic values must be non-negative."
        }

        val glyphLabel = glyphId?.toString() ?: "unknown"
        val normalizedLimitName = limitName.trim()
        return ColorGlyphDiagnostic(
            glyphId = glyphId,
            route = "colr",
            code = ColorGlyphDiagnosticCodes.COLRV1BudgetExceeded,
            severity = "warning",
            detail = "glyphId=$glyphLabel;tableFamily=COLR;version=1;" +
                "limitName=$normalizedLimitName;limit=$limit;observed=$observed",
            message = "COLRv1 paint graph budget $normalizedLimitName exceeded for glyph $glyphLabel: " +
                "observed $observed, limit $limit.",
        )
    }

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
            32 -> parseCompositePaint(
                reader = reader,
                layerPaintOffsets = layerPaintOffsets,
                paintOffset = paintOffset,
                depth = depth,
                state = state,
            )
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

/**
 * Selects embedded bitmap strikes for color glyph alternate routes.
 */
interface BitmapStrikeSelector {
    /**
     * Selects the best bitmap strike for a glyph and requested size.
     *
     * @param glyphId glyph identifier to resolve.
     * @param requestedSizePx requested size in pixels.
     * @return selected bitmap strike or null when no suitable strike exists.
     */
    fun select(glyphId: Int, requestedSizePx: Float): BitmapStrikeSelection?
}

/**
 * Selects bitmap strikes from a caller-supplied immutable snapshot.
 *
 * The selector copies [entries] at construction time so later mutations to the source collection do
 * not affect route decisions. Selection is intentionally metadata-only: it does not load bitmap
 * payload bytes, decode PNG images, or consult platform font APIs. For a matching [glyphId], the
 * selector chooses the strike whose [BitmapStrikeSelection.ppem] is closest to [requestedSizePx].
 * Equal-distance ties are resolved by lower ppem, then lower width, lower height, and finally the
 * lexicographic format label so the same inputs always produce the same result.
 *
 * Invalid entries with non-positive dimensions, non-positive ppem, or blank format labels are
 * ignored when the snapshot is built. Invalid requested sizes return null rather than selecting an
 * arbitrary fallback.
 *
 * @property entries embedded bitmap strike metadata entries to snapshot for deterministic
 * selection.
 */
class StaticBitmapStrikeSelector(
    entries: Iterable<BitmapStrikeSelection>,
) : BitmapStrikeSelector {
    private val selections: List<BitmapStrikeSelection> = entries
        .filter { entry -> entry.isUsable() }
        .map { entry -> entry.copy(format = entry.format.trim()) }
        .sortedWith(BITMAP_STRIKE_STABLE_ORDER)
        .toList()

    /**
     * Selects the nearest bitmap strike for [glyphId] and [requestedSizePx].
     *
     * @param glyphId glyph identifier to resolve.
     * @param requestedSizePx requested size in pixels; non-finite and non-positive sizes are
     * rejected.
     * @return the nearest immutable bitmap strike metadata entry, or null when no usable entry
     * exists for the glyph.
     */
    override fun select(glyphId: Int, requestedSizePx: Float): BitmapStrikeSelection? {
        if (requestedSizePx <= 0f || requestedSizePx.isNaN() || requestedSizePx.isInfinite()) {
            return null
        }

        return selections
            .asSequence()
            .filter { selection -> selection.glyphId == glyphId }
            .minWithOrNull(bitmapStrikeDistanceOrder(requestedSizePx))
    }
}

/**
 * Describes a selected embedded bitmap strike.
 *
 * @property glyphId glyph identifier represented by the bitmap.
 * @property width bitmap width in pixels.
 * @property height bitmap height in pixels.
 * @property format source bitmap format label.
 * @property ppem pixels-per-em size advertised by the embedded bitmap strike. When callers only
 * know the bitmap dimensions, the default uses the larger dimension as a conservative square-strike
 * approximation.
 */
data class BitmapStrikeSelection(
    val glyphId: Int,
    val width: Int,
    val height: Int,
    val format: String,
    val ppem: Int = maxOf(width, height),
)

/**
 * Renderer-neutral plan for one PNG-backed embedded bitmap glyph.
 *
 * The plan records only deterministic font-owned facts. It does not allocate GPU textures, create
 * renderer resources, call platform codecs, or claim that the glyph can be sampled by a GPU route.
 *
 * @property glyphId glyph identifier represented by the bitmap plan.
 * @property tableFamily embedded bitmap table family that supplied the strike.
 * @property requestedSizePx requested glyph size in pixels.
 * @property selectedStrikePpem pixels-per-em advertised by the selected strike.
 * @property sourceFormat normalized source payload format.
 * @property left horizontal bitmap origin in glyph strike space.
 * @property top vertical bitmap origin in glyph strike space.
 * @property width decoded bitmap width in pixels.
 * @property height decoded bitmap height in pixels.
 * @property originX horizontal origin included in the future GPU handoff plan.
 * @property originY vertical origin included in the future GPU handoff plan.
 * @property scalingPolicy stable policy label explaining exact vs scaled strike use.
 * @property alphaPolicy stable alpha/premul policy label for decoded pixels.
 * @property sourcePayloadSha256 SHA-256 digest of the original PNG payload bytes.
 * @property decodedPixelSha256 SHA-256 digest of decoded ARGB pixels in row-major order.
 * @property diagnostics stable bitmap glyph diagnostics attached to this plan.
 */
data class BitmapGlyphPlan(
    val glyphId: Int,
    val tableFamily: String,
    val requestedSizePx: Float,
    val selectedStrikePpem: Int,
    val sourceFormat: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val originX: Int,
    val originY: Int,
    val scalingPolicy: String,
    val alphaPolicy: String,
    val sourcePayloadSha256: String,
    val decodedPixelSha256: String,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    /**
     * SHA-256 digest of [toCanonicalJson] content with the `dumpSha256` field omitted.
     */
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    /**
     * Serializes this bitmap plan as deterministic JSON evidence.
     *
     * @return canonical JSON dump ending with a newline.
     */
    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    /**
     * Shared constructors for bitmap glyph plans.
     */
    companion object {
        /**
         * Builds a plan from a selected PNG strike and already-decoded PNG image.
         *
         * @param strike selected embedded bitmap strike metadata.
         * @param requestedSizePx requested glyph size in pixels.
         * @param tableFamily source table family label, such as `CBDT/CBLC` or `sbix`.
         * @param sourcePayload original PNG payload bytes.
         * @param image decoded PNG glyph image.
         * @return deterministic bitmap glyph plan.
         */
        fun fromPNG(
            strike: BitmapStrikeSelection,
            requestedSizePx: Float,
            tableFamily: String,
            sourcePayload: ByteArray,
            image: PNGGlyphImage,
            originX: Int = 0,
            originY: Int = 0,
        ): BitmapGlyphPlan {
            require(requestedSizePx.isFinite() && requestedSizePx > 0f) {
                "Bitmap glyph requested size must be finite and positive."
            }
            require(strike.glyphId == image.glyphId) {
                "Bitmap strike glyph ${strike.glyphId} does not match decoded glyph ${image.glyphId}."
            }
            require(strike.width == image.width && strike.height == image.height) {
                "Bitmap strike dimensions must match decoded PNG dimensions for glyph ${strike.glyphId}."
            }
            require(strike.format.trim().lowercase() == "png") {
                "Bitmap glyph plan requires a PNG strike for glyph ${strike.glyphId}."
            }
            require(tableFamily.isNotBlank()) {
                "Bitmap glyph table family must be non-blank."
            }

            return BitmapGlyphPlan(
                glyphId = strike.glyphId,
                tableFamily = tableFamily.trim(),
                requestedSizePx = requestedSizePx,
                selectedStrikePpem = strike.ppem,
                sourceFormat = "png",
                left = 0,
                top = 0,
                width = image.width,
                height = image.height,
                originX = originX,
                originY = originY,
                scalingPolicy = if (requestedSizePx == strike.ppem.toFloat()) {
                    "exact-strike"
                } else {
                    "scale-to-requested-size"
                },
                alphaPolicy = "premultiplied-argb",
                sourcePayloadSha256 = colorGlyphSha256(sourcePayload.copyOf()),
                decodedPixelSha256 = colorGlyphSha256(image.pixels.toARGBByteArray()),
                diagnostics = emptyList(),
            )
        }

        /**
         * Builds a stable refusal diagnostic for non-PNG embedded bitmap payloads.
         *
         * @param glyphId glyph identifier whose bitmap payload was inspected.
         * @param tableFamily source table family label, such as `CBDT/CBLC` or `sbix`.
         * @param sourceFormat unsupported source payload format label.
         * @param sourcePayload original payload bytes used only for a deterministic hash.
         * @return stable non-PNG bitmap payload refusal diagnostic.
         */
        fun unsupportedPayloadDiagnostic(
            glyphId: Int,
            tableFamily: String,
            sourceFormat: String,
            sourcePayload: ByteArray,
        ): ColorGlyphDiagnostic {
            val normalizedFamily = tableFamily.trim().ifEmpty { "unknown" }
            val normalizedFormat = sourceFormat.trim().lowercase().ifEmpty { "unknown" }
            val payloadHash = colorGlyphSha256(sourcePayload.copyOf())

            return ColorGlyphDiagnostic(
                glyphId = glyphId,
                route = "bitmap",
                code = ColorGlyphDiagnosticCodes.BitmapPayloadFormatUnsupported,
                severity = "warning",
                detail = "glyphId=$glyphId;tableFamily=$normalizedFamily;sourceFormat=$normalizedFormat;" +
                    "sourcePayloadSha256=$payloadHash",
                message = "Bitmap glyph payload format $normalizedFormat is unsupported for glyph $glyphId " +
                    "from $normalizedFamily.",
            )
        }

        /**
         * Builds a stable refusal diagnostic when no embedded bitmap strike can satisfy the
         * requested size.
         *
         * @param glyphId glyph identifier whose embedded bitmap strikes were considered.
         * @param requestedSizePx requested glyph size in pixels.
         * @param availableStrikes available embedded bitmap strikes considered for this glyph.
         * @return stable strike-unavailable diagnostic.
         */
        fun strikeUnavailableDiagnostic(
            glyphId: Int,
            requestedSizePx: Float,
            availableStrikes: List<BitmapStrikeSelection>,
        ): ColorGlyphDiagnostic {
            require(requestedSizePx.isFinite() && requestedSizePx > 0f) {
                "Bitmap strike-unavailable requested size must be finite and positive."
            }
            val availablePpems = availableStrikes
                .sortedWith(BITMAP_STRIKE_STABLE_ORDER)
                .filter { selection -> selection.glyphId == glyphId || availableStrikes.none { it.glyphId == glyphId } }
                .map { selection -> selection.ppem }
            val availableLabel = if (availablePpems.isEmpty()) {
                "none"
            } else {
                availablePpems.joinToString(separator = ",")
            }
            return ColorGlyphDiagnostic(
                glyphId = glyphId,
                route = "bitmap",
                code = ColorGlyphDiagnosticCodes.BitmapStrikeUnavailable,
                severity = "warning",
                detail = "glyphId=$glyphId;requestedSizePx=${colorGlyphFloatToken(requestedSizePx)};" +
                    "availableStrikes=$availableLabel",
                message = "Bitmap glyph strike is unavailable for glyph $glyphId at " +
                    "${colorGlyphFloatToken(requestedSizePx)}px: available strikes $availableLabel.",
            )
        }

        /**
         * Builds a stable refusal diagnostic for malformed PNG bitmap glyph payloads.
         *
         * @param glyphId glyph identifier whose PNG payload failed to decode.
         * @param tableFamily source table family label, such as `CBDT/CBLC` or `sbix`.
         * @param sourcePayload original payload bytes used only for deterministic evidence.
         * @param failure decode failure emitted by the pure Kotlin PNG decoder.
         * @return stable malformed PNG bitmap payload refusal diagnostic.
         */
        fun pngDecodeFailedDiagnostic(
            glyphId: Int,
            tableFamily: String,
            sourcePayload: ByteArray,
            failure: Throwable,
        ): ColorGlyphDiagnostic {
            val normalizedFamily = tableFamily.trim().ifEmpty { "unknown" }
            val payloadHash = colorGlyphSha256(sourcePayload.copyOf())
            val failureClass = failure.javaClass.simpleName.ifEmpty { "Throwable" }
            val failureMessage = failure.message?.trim().orEmpty().ifEmpty { "unknown" }

            return ColorGlyphDiagnostic(
                glyphId = glyphId,
                route = "bitmap",
                code = ColorGlyphDiagnosticCodes.PNGDecodeFailed,
                severity = "warning",
                detail = "glyphId=$glyphId;tableFamily=$normalizedFamily;sourceFormat=png;" +
                    "sourcePayloadSha256=$payloadHash;failureClass=$failureClass;" +
                    "failureMessage=$failureMessage",
                message = "Bitmap glyph PNG decode failed for glyph $glyphId from $normalizedFamily: " +
                    failureMessage,
            )
        }
    }

    /**
     * Serializes this plan with optional dump hash inclusion.
     */
    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", BitmapGlyphPlanSchema, comma = true)
        appendColorGlyphJsonField("glyphId", glyphId, comma = true)
        appendColorGlyphJsonField("tableFamily", tableFamily, comma = true)
        append("  ").append(colorGlyphJsonString("requestedSizePx")).append(": ")
        append(colorGlyphFloatToken(requestedSizePx)).append(",\n")
        appendColorGlyphJsonField("selectedStrikePpem", selectedStrikePpem, comma = true)
        appendColorGlyphJsonField("sourceFormat", sourceFormat, comma = true)
        append("  \"bounds\": {")
        append(colorGlyphJsonString("left")).append(": ").append(left).append(", ")
        append(colorGlyphJsonString("top")).append(": ").append(top).append(", ")
        append(colorGlyphJsonString("width")).append(": ").append(width).append(", ")
        append(colorGlyphJsonString("height")).append(": ").append(height)
        append("},\n")
        append("  \"origin\": {")
        append(colorGlyphJsonString("x")).append(": ").append(originX).append(", ")
        append(colorGlyphJsonString("y")).append(": ").append(originY)
        append("},\n")
        appendColorGlyphJsonField("scalingPolicy", scalingPolicy, comma = true)
        appendColorGlyphJsonField("alphaPolicy", alphaPolicy, comma = true)
        appendColorGlyphJsonField("sourcePayloadSha256", sourcePayloadSha256, comma = true)
        appendColorGlyphJsonField("decodedPixelSha256", decodedPixelSha256, comma = true)
        append("  \"diagnostics\": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Decodes PNG-backed glyph images into pure Kotlin byte buffers.
 */
interface PNGGlyphDecoder {
    /**
     * Decodes a PNG glyph payload.
     *
     * @param glyphId glyph identifier associated with the PNG payload.
     * @param bytes encoded PNG bytes.
     * @return decoded PNG glyph image.
     */
    fun decode(glyphId: Int, bytes: ByteArray): PNGGlyphImage
}

/**
 * Decodes minimal PNG glyph payloads into packed ARGB pixels.
 *
 * The decoder accepts bytes whose first byte is the PNG signature, validates that the first chunk
 * is an IHDR chunk with the required 13-byte payload, and reads the IHDR width and height as
 * unsigned big-endian integers before decoding non-interlaced 8-bit RGB and RGBA payloads. It does
 * not use AWT, ImageIO, JNI, native code, GPU, or renderer APIs.
 */
object BasicPNGGlyphDecoder : PNGGlyphDecoder {
    /**
     * Reads PNG signature and IHDR metadata for one glyph payload.
     *
     * @param glyphId glyph identifier associated with the PNG payload.
     * @param bytes encoded PNG bytes.
     * @return PNG glyph metadata with decoded packed ARGB pixels.
     * @throws IllegalArgumentException when the payload is too large, truncated, not a PNG, missing
     * IHDR as the first chunk, advertising invalid image dimensions, or failing PNG decode.
     */
    override fun decode(glyphId: Int, bytes: ByteArray): PNGGlyphImage {
        require(bytes.size <= MAX_PNG_GLYPH_PAYLOAD_BYTES) {
            "PNG glyph payload exceeds $MAX_PNG_GLYPH_PAYLOAD_BYTES bytes."
        }
        require(hasPngSignature(bytes)) {
            "PNG glyph payload must start with the PNG signature."
        }
        require(bytes.size >= PNG_MIN_HEADER_BYTES) {
            "PNG glyph payload is truncated before the IHDR header."
        }

        val reader = ColorTableReader(bytes)
        val ihdrLength = reader.u32(PNG_IHDR_LENGTH_OFFSET)
            ?: throw IllegalArgumentException("PNG glyph payload is truncated before the IHDR length.")
        require(ihdrLength == PNG_IHDR_DATA_SIZE.toLong()) {
            "PNG glyph IHDR chunk must be $PNG_IHDR_DATA_SIZE bytes, found $ihdrLength."
        }
        require(matchesAscii(bytes = bytes, offset = PNG_IHDR_TYPE_OFFSET, text = "IHDR")) {
            "PNG glyph payload must contain IHDR as the first chunk."
        }

        val width = reader.u32(PNG_IHDR_WIDTH_OFFSET)?.toIntOrNull()
            ?: throw IllegalArgumentException("PNG glyph IHDR width is out of range.")
        val height = reader.u32(PNG_IHDR_HEIGHT_OFFSET)?.toIntOrNull()
            ?: throw IllegalArgumentException("PNG glyph IHDR height is out of range.")
        require(width in 1..MAX_PNG_GLYPH_DIMENSION) {
            "PNG glyph IHDR width must be between 1 and $MAX_PNG_GLYPH_DIMENSION pixels."
        }
        require(height in 1..MAX_PNG_GLYPH_DIMENSION) {
            "PNG glyph IHDR height must be between 1 and $MAX_PNG_GLYPH_DIMENSION pixels."
        }

        val pixels = decodeMinimalPngGlyphPixels(bytes = bytes, width = width, height = height)

        return PNGGlyphImage(
            glyphId = glyphId,
            width = width,
            height = height,
            pixels = pixels,
        )
    }
}

/**
 * Stores parsed PNG glyph image metadata and optional decoded pixels.
 *
 * @property glyphId glyph identifier represented by the image.
 * @property width image width in pixels.
 * @property height image height in pixels.
 * @property pixels immutable decoded pixels in row-major order as packed ARGB integers.
 */
data class PNGGlyphImage(
    val glyphId: Int,
    val width: Int,
    val height: Int,
    val pixels: List<Int>,
)

/**
 * Renders parsed SVG glyph documents into pure Kotlin image buffers or vector plans.
 */
interface SVGGlyphRenderer {
    /**
     * Renders a parsed SVG glyph document at a requested size.
     *
     * @param document parsed SVG glyph document.
     * @param sizePx requested render size in pixels.
     * @return rendered SVG glyph image.
     */
    fun render(document: SVGGlyphDocument, sizePx: Float): SVGGlyphImage
}

/**
 * Parses SVG glyph payloads into renderer-neutral documents.
 */
interface SVGGlyphParser {
    /**
     * Parses an SVG glyph payload.
     *
     * @param glyphId glyph identifier associated with the SVG payload.
     * @param text UTF-8 decoded SVG text.
     * @return parsed SVG glyph document.
     */
    fun parse(glyphId: Int, text: String): SVGGlyphDocument
}

/**
 * Parses a bounded subset of SVG glyph text into renderer-neutral metadata.
 *
 * This parser is intentionally small and dependency-free. It treats [text] as already UTF-8
 * decoded SVG source, rejects payloads beyond [MAX_SVG_GLYPH_TEXT_CHARS], reads the root
 * `viewBox` attribute, and summarizes common paintable element start tags. It does not execute
 * scripts, expand entities, resolve external references, apply CSS, or build a full XML DOM. The
 * output is suitable for route diagnostics and later handoff to a real SVG renderer, not for
 * claiming complete SVG rendering support.
 *
 * Element summaries use the stable form `name{key=value,...}`. Attribute values are trimmed and
 * internal whitespace is collapsed; attributes are canonicalized to lowercase and sorted by name so
 * equivalent input ordering produces deterministic metadata.
 */
object BasicSVGGlyphParser : SVGGlyphParser {
    /**
     * Builds a stable refusal diagnostic for an SVG glyph external resource reference.
     *
     * @param glyphId glyph identifier whose SVG payload referenced an external resource.
     * @param elementName SVG element carrying the refused reference.
     * @param attributeName SVG attribute carrying the refused reference.
     * @param reference raw reference text, used only for deterministic hashing.
     * @return stable SVG external resource refusal diagnostic.
     */
    fun externalResourceRefusedDiagnostic(
        glyphId: Int,
        elementName: String,
        attributeName: String,
        reference: String,
    ): ColorGlyphDiagnostic {
        val normalizedElement = elementName.trim().ifEmpty { "unknown" }
        val normalizedAttribute = attributeName.trim().ifEmpty { "unknown" }
        val referenceHash = colorGlyphSha256(reference.toByteArray(Charsets.UTF_8))

        return ColorGlyphDiagnostic(
            glyphId = glyphId,
            route = "svg",
            code = ColorGlyphDiagnosticCodes.SVGExternalResourceRefused,
            severity = "warning",
            detail = "glyphId=$glyphId;elementName=$normalizedElement;attributeName=$normalizedAttribute;" +
                "referenceSha256=$referenceHash",
            message = "SVG glyph external resource refused for glyph $glyphId: " +
                "$normalizedElement $normalizedAttribute.",
        )
    }

    /**
     * Builds a stable refusal diagnostic for an unsupported SVG glyph feature.
     *
     * @param glyphId glyph identifier whose SVG payload used the unsupported feature.
     * @param elementName SVG element associated with the unsupported feature.
     * @param featureName stable feature label describing the unsupported behavior.
     * @return stable SVG unsupported feature diagnostic.
     */
    fun unsupportedFeatureDiagnostic(
        glyphId: Int,
        elementName: String,
        featureName: String,
    ): ColorGlyphDiagnostic {
        val normalizedElement = elementName.trim().ifEmpty { "unknown" }
        val normalizedFeature = featureName.trim().ifEmpty { "unknown" }

        return ColorGlyphDiagnostic(
            glyphId = glyphId,
            route = "svg",
            code = ColorGlyphDiagnosticCodes.SVGFeatureUnsupported,
            severity = "warning",
            detail = "glyphId=$glyphId;elementName=$normalizedElement;featureName=$normalizedFeature",
            message = "SVG glyph feature $normalizedFeature is unsupported for glyph $glyphId " +
                "in $normalizedElement.",
        )
    }

    /**
     * Builds a stable refusal diagnostic for SVG `<use>` recursion exceeding a bounded depth.
     *
     * @param glyphId glyph identifier whose SVG payload exceeded the recursion budget.
     * @param referenceId stable referenced symbol or element identifier.
     * @param depth observed recursion depth.
     * @param maxDepth configured maximum recursion depth.
     * @return stable SVG recursion budget refusal diagnostic.
     */
    fun useRecursionRefusedDiagnostic(
        glyphId: Int,
        referenceId: String,
        depth: Int,
        maxDepth: Int,
    ): ColorGlyphDiagnostic {
        require(depth >= 0 && maxDepth >= 0) {
            "SVG use recursion diagnostic depths must be non-negative."
        }

        val normalizedReferenceId = referenceId.trim().ifEmpty { "unknown" }
        return ColorGlyphDiagnostic(
            glyphId = glyphId,
            route = "svg",
            code = ColorGlyphDiagnosticCodes.SVGBudgetExceeded,
            severity = "warning",
            detail = "glyphId=$glyphId;referenceId=$normalizedReferenceId;depth=$depth;maxDepth=$maxDepth",
            message = "SVG glyph use recursion exceeded for glyph $glyphId at $normalizedReferenceId: " +
                "depth $depth, max $maxDepth.",
        )
    }

    /**
     * Builds a stable refusal diagnostic for a malformed SVG glyph document.
     *
     * @param glyphId glyph identifier whose SVG payload failed to parse.
     * @param sourceText original SVG source used only for deterministic hashing.
     * @param failure parser failure emitted by the bounded SVG parser.
     * @return stable SVG malformed-document diagnostic.
     */
    fun documentMalformedDiagnostic(
        glyphId: Int,
        sourceText: String,
        failure: Throwable,
    ): ColorGlyphDiagnostic {
        val sourceHash = colorGlyphSha256(sourceText.toByteArray(Charsets.UTF_8))
        val failureClass = failure.javaClass.simpleName.ifEmpty { "Throwable" }
        val failureMessage = failure.message?.trim().orEmpty().ifEmpty { "unknown" }
        val normalizedMessage = if (failureMessage.endsWith('.')) failureMessage else "$failureMessage."

        return ColorGlyphDiagnostic(
            glyphId = glyphId,
            route = "svg",
            code = ColorGlyphDiagnosticCodes.SVGDocumentMalformed,
            severity = "warning",
            detail = "glyphId=$glyphId;sourceDocumentSha256=$sourceHash;failureClass=$failureClass;" +
                "failureMessage=$failureMessage",
            message = "SVG glyph document is malformed for glyph $glyphId: $normalizedMessage",
        )
    }

    /**
     * Builds a stable refusal diagnostic for an SVG glyph resource or complexity budget overflow.
     *
     * @param glyphId glyph identifier whose SVG payload exceeded a limit.
     * @param budgetName stable budget label such as `pathCommands` or `gradientStops`.
     * @param observed observed count.
     * @param max configured maximum count.
     * @return stable SVG budget-exceeded diagnostic.
     */
    fun budgetExceededDiagnostic(
        glyphId: Int,
        budgetName: String,
        observed: Int,
        max: Int,
    ): ColorGlyphDiagnostic {
        require(observed >= 0 && max >= 0) {
            "SVG budget diagnostic counts must be non-negative."
        }

        val normalizedBudget = budgetName.trim().ifEmpty { "unknown" }
        return ColorGlyphDiagnostic(
            glyphId = glyphId,
            route = "svg",
            code = ColorGlyphDiagnosticCodes.SVGBudgetExceeded,
            severity = "warning",
            detail = "glyphId=$glyphId;budgetName=$normalizedBudget;observed=$observed;max=$max",
            message = "SVG glyph budget $normalizedBudget exceeded for glyph $glyphId: observed $observed, max $max.",
        )
    }

    /**
     * Parses bounded UTF-8 SVG text for one glyph.
     *
     * @param glyphId glyph identifier associated with the SVG payload.
     * @param text UTF-8 decoded SVG text.
     * @return parsed SVG glyph document containing root viewBox values and simple element
     * summaries.
     * @throws IllegalArgumentException when the payload is oversized, lacks a root SVG start tag,
     * lacks a valid four-number viewBox, has malformed attributes, or exceeds the simple element
     * summary cap.
     */
    override fun parse(glyphId: Int, text: String): SVGGlyphDocument {
        require(text.length <= MAX_SVG_GLYPH_TEXT_CHARS) {
            "SVG glyph payload exceeds $MAX_SVG_GLYPH_TEXT_CHARS UTF-16 code units."
        }

        val source = text.removePrefix("\uFEFF")
        val rootTag = findSvgStartTag(source, "svg")
            ?: throw IllegalArgumentException("SVG glyph payload must contain an <svg> root tag.")
        val rootAttributes = parseSvgTagAttributes(rootTag.source)
        val viewBoxText = attributeValue(rootAttributes, "viewBox")
            ?: throw IllegalArgumentException("SVG glyph payload must contain a root viewBox attribute.")

        return SVGGlyphDocument(
            glyphId = glyphId,
            viewBox = parseSvgViewBox(viewBoxText),
            elements = extractSvgElementSummaries(source),
        )
    }
}

/**
 * Stores a parsed SVG glyph document.
 *
 * @property glyphId glyph identifier represented by the document.
 * @property viewBox SVG viewBox values encoded as minX, minY, width, and height.
 * @property elements immutable renderer-neutral element summaries in `name{key=value,...}` form.
 * The basic parser emits one summary per supported SVG start tag and leaves unsupported content
 * out of this diagnostic list.
 */
data class SVGGlyphDocument(
    val glyphId: Int,
    val viewBox: List<Float> = emptyList(),
    val elements: List<String> = emptyList(),
)

/**
 * Renderer-neutral primitive entry inside an [SVGGlyphPlan].
 *
 * @property kind stable primitive label such as `path`, `rect`, `linearGradient`, or `use`.
 * @property summary canonical parser summary for deterministic fixture evidence.
 */
data class SVGGlyphPrimitive(
    val kind: String,
    val summary: String,
)

/**
 * Stable SVG resource-limit facts captured alongside an [SVGGlyphPlan].
 *
 * @property documentChars UTF-16 source length for the SVG payload.
 * @property pathCommandCount observed total path command count.
 * @property gradientStopCount observed gradient stop count.
 * @property primitiveCount observed primitive count in the renderer-neutral summary.
 * @property maxDocumentChars configured maximum SVG payload length.
 * @property maxPathCommands configured maximum path command count.
 * @property maxGradientStops configured maximum gradient stop count.
 * @property maxPrimitiveCount configured maximum primitive count.
 * @property maxUseRecursionDepth configured maximum supported `<use>` recursion depth.
 */
data class SVGGlyphResourceLimits(
    val documentChars: Int,
    val pathCommandCount: Int,
    val gradientStopCount: Int,
    val primitiveCount: Int,
    val maxDocumentChars: Int,
    val maxPathCommands: Int,
    val maxGradientStops: Int,
    val maxPrimitiveCount: Int,
    val maxUseRecursionDepth: Int,
)

/**
 * Renderer-neutral plan for one bounded SVG glyph document.
 *
 * The plan records only deterministic pure Kotlin facts: source hash, viewBox, bounds, primitive
 * summaries, resource limits, and stable diagnostics. It does not allocate GPU resources, invoke a
 * native SVG engine, or claim complete SVG rendering support.
 *
 * @property glyphId glyph identifier represented by this SVG plan.
 * @property sourceDocumentSha256 SHA-256 digest of the UTF-8 SVG source bytes.
 * @property viewBoxMinX viewBox minimum x coordinate.
 * @property viewBoxMinY viewBox minimum y coordinate.
 * @property viewBoxWidth viewBox width.
 * @property viewBoxHeight viewBox height.
 * @property boundsLeft glyph bounds left coordinate.
 * @property boundsTop glyph bounds top coordinate.
 * @property boundsWidth glyph bounds width.
 * @property boundsHeight glyph bounds height.
 * @property primitives immutable renderer-neutral primitive summaries.
 * @property resourceLimits stable resource-limit facts for this plan.
 * @property diagnostics stable SVG diagnostics attached to this plan.
 */
data class SVGGlyphPlan(
    val glyphId: Int,
    val sourceDocumentSha256: String,
    val viewBoxMinX: Float,
    val viewBoxMinY: Float,
    val viewBoxWidth: Float,
    val viewBoxHeight: Float,
    val boundsLeft: Float,
    val boundsTop: Float,
    val boundsWidth: Float,
    val boundsHeight: Float,
    val primitives: List<SVGGlyphPrimitive>,
    val resourceLimits: SVGGlyphResourceLimits,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    /**
     * SHA-256 digest of [toCanonicalJson] content with the `dumpSha256` field omitted.
     */
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    /**
     * Serializes this SVG plan as deterministic JSON evidence.
     *
     * @return canonical JSON dump ending with a newline.
     */
    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    companion object {
        /**
         * Builds a deterministic SVG glyph plan from parser output and the original source text.
         *
         * @param sourceText UTF-8 decoded SVG source text.
         * @param document parsed SVG glyph document for the same payload.
         * @param diagnostics stable route diagnostics to attach to the plan.
         * @return deterministic SVG glyph plan.
         */
        fun fromDocument(
            sourceText: String,
            document: SVGGlyphDocument,
            diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
        ): SVGGlyphPlan {
            require(document.viewBox.size == 4) {
                "SVG glyph document must contain a four-number viewBox."
            }

            val viewBox = document.viewBox
            val primitives = document.elements.map { summary ->
                SVGGlyphPrimitive(
                    kind = svgPrimitiveKind(summary),
                    summary = summary,
                )
            }
            val pathCommandCount = svgPathCommandCount(sourceText)
            val gradientStopCount = svgGradientStopCount(sourceText)
            val resourceLimits = SVGGlyphResourceLimits(
                documentChars = sourceText.length,
                pathCommandCount = pathCommandCount,
                gradientStopCount = gradientStopCount,
                primitiveCount = primitives.size,
                maxDocumentChars = MAX_SVG_GLYPH_TEXT_CHARS,
                maxPathCommands = MAX_SVG_PATH_COMMANDS,
                maxGradientStops = MAX_SVG_GRADIENT_STOPS,
                maxPrimitiveCount = MAX_SVG_GLYPH_ELEMENTS,
                maxUseRecursionDepth = MAX_SVG_USE_RECURSION_DEPTH,
            )

            val allDiagnostics = ArrayList<ColorGlyphDiagnostic>()
            if (pathCommandCount > MAX_SVG_PATH_COMMANDS) {
                allDiagnostics += BasicSVGGlyphParser.budgetExceededDiagnostic(
                    glyphId = document.glyphId,
                    budgetName = "pathCommands",
                    observed = pathCommandCount,
                    max = MAX_SVG_PATH_COMMANDS,
                )
            }
            if (gradientStopCount > MAX_SVG_GRADIENT_STOPS) {
                allDiagnostics += BasicSVGGlyphParser.budgetExceededDiagnostic(
                    glyphId = document.glyphId,
                    budgetName = "gradientStops",
                    observed = gradientStopCount,
                    max = MAX_SVG_GRADIENT_STOPS,
                )
            }
            if (primitives.size > MAX_SVG_GLYPH_ELEMENTS) {
                allDiagnostics += BasicSVGGlyphParser.budgetExceededDiagnostic(
                    glyphId = document.glyphId,
                    budgetName = "primitiveCount",
                    observed = primitives.size,
                    max = MAX_SVG_GLYPH_ELEMENTS,
                )
            }
            allDiagnostics += diagnostics

            return SVGGlyphPlan(
                glyphId = document.glyphId,
                sourceDocumentSha256 = colorGlyphSha256(sourceText.toByteArray(Charsets.UTF_8)),
                viewBoxMinX = viewBox[0],
                viewBoxMinY = viewBox[1],
                viewBoxWidth = viewBox[2],
                viewBoxHeight = viewBox[3],
                boundsLeft = viewBox[0],
                boundsTop = viewBox[1],
                boundsWidth = viewBox[2],
                boundsHeight = viewBox[3],
                primitives = primitives,
                resourceLimits = resourceLimits,
                diagnostics = allDiagnostics.toList(),
            )
        }
    }

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", SVGGlyphPlanSchema, comma = true)
        appendColorGlyphJsonField("glyphId", glyphId, comma = true)
        appendColorGlyphJsonField("sourceDocumentSha256", sourceDocumentSha256, comma = true)
        append("  \"viewBox\": {")
        append(colorGlyphJsonString("minX")).append(": ").append(colorGlyphFloatToken(viewBoxMinX)).append(", ")
        append(colorGlyphJsonString("minY")).append(": ").append(colorGlyphFloatToken(viewBoxMinY)).append(", ")
        append(colorGlyphJsonString("width")).append(": ").append(colorGlyphFloatToken(viewBoxWidth)).append(", ")
        append(colorGlyphJsonString("height")).append(": ").append(colorGlyphFloatToken(viewBoxHeight))
        append("},\n")
        append("  \"bounds\": {")
        append(colorGlyphJsonString("left")).append(": ").append(colorGlyphFloatToken(boundsLeft)).append(", ")
        append(colorGlyphJsonString("top")).append(": ").append(colorGlyphFloatToken(boundsTop)).append(", ")
        append(colorGlyphJsonString("width")).append(": ").append(colorGlyphFloatToken(boundsWidth)).append(", ")
        append(colorGlyphJsonString("height")).append(": ").append(colorGlyphFloatToken(boundsHeight))
        append("},\n")
        appendColorGlyphJsonField("primitiveCount", primitives.size, comma = true)
        append("  \"primitives\": [")
        if (primitives.isNotEmpty()) {
            append("\n")
            append(
                primitives.joinToString(",\n") { primitive ->
                    "    {\"kind\": ${colorGlyphJsonString(primitive.kind)}, " +
                        "\"summary\": ${colorGlyphJsonString(primitive.summary)}}"
                },
            )
            append("\n")
            append("  ")
        }
        append("],\n")
        append("  \"resourceLimits\": {")
        append(colorGlyphJsonString("documentChars")).append(": ").append(resourceLimits.documentChars).append(", ")
        append(colorGlyphJsonString("pathCommandCount")).append(": ").append(resourceLimits.pathCommandCount).append(", ")
        append(colorGlyphJsonString("gradientStopCount")).append(": ").append(resourceLimits.gradientStopCount).append(", ")
        append(colorGlyphJsonString("primitiveCount")).append(": ").append(resourceLimits.primitiveCount).append(", ")
        append(colorGlyphJsonString("maxDocumentChars")).append(": ").append(resourceLimits.maxDocumentChars).append(", ")
        append(colorGlyphJsonString("maxPathCommands")).append(": ").append(resourceLimits.maxPathCommands).append(", ")
        append(colorGlyphJsonString("maxGradientStops")).append(": ").append(resourceLimits.maxGradientStops).append(", ")
        append(colorGlyphJsonString("maxPrimitiveCount")).append(": ").append(resourceLimits.maxPrimitiveCount).append(", ")
        append(colorGlyphJsonString("maxUseRecursionDepth")).append(": ").append(resourceLimits.maxUseRecursionDepth)
        append("},\n")
        append("  \"diagnostics\": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }
}

/**
 * Stores a rendered SVG glyph image.
 *
 * @property glyphId glyph identifier represented by the image.
 * @property width image width in pixels.
 * @property height image height in pixels.
 * @property pixels Immutable rendered pixels in row-major order as packed ARGB
 * integers.
 */
data class SVGGlyphImage(
    val glyphId: Int,
    val width: Int,
    val height: Int,
    val pixels: List<Int>,
)

/**
 * Describes route availability for a pure Kotlin emoji glyph dispatch pass.
 *
 * The sets are intentionally plain glyph identifiers. They let a caller feed table-level knowledge
 * from COLR/CPAL parsing, bitmap strike discovery, PNG payload indexing, SVG document discovery,
 * and outline fallback availability without coupling the dispatcher to any parser, scaler, codec,
 * cache, or renderer.
 *
 * @property colrGlyphs glyph identifiers with parsed COLR color layer records.
 * @property bitmapGlyphs glyph identifiers with embedded bitmap strike data.
 * @property pngGlyphs glyph identifiers with PNG-backed glyph image payloads.
 * @property svgGlyphs glyph identifiers with SVG glyph documents.
 * @property outlineGlyphs glyph identifiers with outline fallback data.
 */
data class EmojiGlyphRouteAvailability(
    val colrGlyphs: Set<Int> = emptySet(),
    val bitmapGlyphs: Set<Int> = emptySet(),
    val pngGlyphs: Set<Int> = emptySet(),
    val svgGlyphs: Set<Int> = emptySet(),
    val outlineGlyphs: Set<Int> = emptySet(),
)

/** Canonical representation priority shared by emoji and prepared-text routing. */
enum class EmojiGlyphRepresentationRoute(val stableName: String) {
    COLR("colr"),
    BITMAP("bitmap"),
    PNG("png"),
    SVG("svg"),
    OUTLINE("outline"),
}

/**
 * Sole authority for choosing between simultaneously available glyph representations.
 *
 * The order is intentionally identical to Skia-like color-glyph dispatch:
 * COLR, embedded bitmap, decoded PNG, SVG, then monochrome outline.
 */
object EmojiGlyphRepresentationPriority {
    fun select(
        colrAvailable: Boolean,
        bitmapAvailable: Boolean,
        pngAvailable: Boolean,
        svgAvailable: Boolean,
        outlineAvailable: Boolean,
    ): EmojiGlyphRepresentationRoute? = orderedCandidates(
        colrAvailable = colrAvailable,
        bitmapAvailable = bitmapAvailable,
        pngAvailable = pngAvailable,
        svgAvailable = svgAvailable,
        outlineAvailable = outlineAvailable,
    ).firstOrNull { (_, available) -> available }?.first

    internal fun orderedCandidates(
        colrAvailable: Boolean,
        bitmapAvailable: Boolean,
        pngAvailable: Boolean,
        svgAvailable: Boolean,
        outlineAvailable: Boolean,
    ): List<Pair<EmojiGlyphRepresentationRoute, Boolean>> = listOf(
        EmojiGlyphRepresentationRoute.COLR to colrAvailable,
        EmojiGlyphRepresentationRoute.BITMAP to bitmapAvailable,
        EmojiGlyphRepresentationRoute.PNG to pngAvailable,
        EmojiGlyphRepresentationRoute.SVG to svgAvailable,
        EmojiGlyphRepresentationRoute.OUTLINE to outlineAvailable,
    )
}

/**
 * Dispatches emoji glyphs to the best available color glyph route.
 */
interface EmojiGlyphDispatcher {
    /**
     * Selects a color glyph route for an emoji glyph.
     *
     * @param glyphId glyph identifier to dispatch.
     * @param strikeKey strike inputs used for route selection.
     * @return dispatch result for the emoji glyph.
     */
    fun dispatch(glyphId: Int, strikeKey: GlyphStrikeKey): EmojiGlyphDispatch
}

/**
 * Deterministic emoji glyph dispatcher with COLR-first route preference.
 *
 * Routes are considered in the order COLR, bitmap, PNG, SVG, then outline. The dispatcher emits
 * diagnostics for unavailable higher-priority routes, the selected route, and any lower-priority
 * alternatives that were present but skipped. It does not decode or render any glyph content; it
 * only chooses among availability facts supplied by [availability].
 *
 * @property availability route availability facts for the dispatch pass.
 */
class SimpleEmojiGlyphDispatcher(
    private val availability: EmojiGlyphRouteAvailability,
) : EmojiGlyphDispatcher {
    /**
     * Selects the preferred available route for one emoji glyph.
     *
     * @param glyphId glyph identifier to dispatch.
     * @param strikeKey strike inputs included in diagnostics to make route evidence size-aware.
     * @return dispatch result containing the selected route name and non-fatal diagnostics.
     */
    override fun dispatch(glyphId: Int, strikeKey: GlyphStrikeKey): EmojiGlyphDispatch {
        val candidates = routeCandidates(glyphId)
        val selectedIndex = candidates.indexOfFirst { candidate -> candidate.available }

        if (selectedIndex < 0) {
            return EmojiGlyphDispatch(
                glyphId = glyphId,
                route = "missing",
                diagnostics = candidates.map { candidate ->
                    unavailableDiagnostic(glyphId = glyphId, route = candidate.route, strikeKey = strikeKey)
                } + ColorGlyphDiagnostic(
                    glyphId = glyphId,
                    route = "missing",
                    message = "No emoji glyph route is available for glyph $glyphId at ${strikeKey.sizePx}px.",
                    severity = "warning",
                    code = ColorGlyphDiagnosticCodes.EmojiFallbackUnavailable,
                    detail = "glyphId=$glyphId;sizePx=${strikeKey.sizePx};availableRoutes=none",
                ),
            )
        }

        val selected = candidates[selectedIndex]
        val diagnostics = ArrayList<ColorGlyphDiagnostic>()
        candidates.forEachIndexed { index, candidate ->
            when {
                index < selectedIndex -> {
                    diagnostics += unavailableDiagnostic(
                        glyphId = glyphId,
                        route = candidate.route,
                        strikeKey = strikeKey,
                    )
                }
                index == selectedIndex -> {
                    diagnostics += ColorGlyphDiagnostic(
                        glyphId = glyphId,
                        route = candidate.route,
                        message = "Selected ${candidate.route} route for glyph $glyphId at ${strikeKey.sizePx}px.",
                        code = ColorGlyphDiagnosticCodes.EmojiRouteSelected,
                        detail = "selected=${candidate.route};glyphId=$glyphId;sizePx=${strikeKey.sizePx}",
                    )
                }
                candidate.available -> {
                    diagnostics += ColorGlyphDiagnostic(
                        glyphId = glyphId,
                        route = candidate.route,
                        message = "Route ${candidate.route} skipped for glyph $glyphId because it has lower preference than ${selected.route}.",
                        code = ColorGlyphDiagnosticCodes.EmojiRouteLowerPreferenceSkipped,
                        detail = "candidate=${candidate.route};selected=${selected.route};glyphId=$glyphId;sizePx=${strikeKey.sizePx}",
                    )
                }
            }
        }

        return EmojiGlyphDispatch(
            glyphId = glyphId,
            route = selected.route,
            diagnostics = diagnostics.toList(),
        )
    }

    /**
     * Builds route candidates in the public emoji dispatch preference order.
     */
    private fun routeCandidates(glyphId: Int): List<EmojiRouteCandidate> =
        EmojiGlyphRepresentationPriority.orderedCandidates(
            colrAvailable = glyphId in availability.colrGlyphs,
            bitmapAvailable = glyphId in availability.bitmapGlyphs,
            pngAvailable = glyphId in availability.pngGlyphs,
            svgAvailable = glyphId in availability.svgGlyphs,
            outlineAvailable = glyphId in availability.outlineGlyphs,
        ).map { (route, available) ->
            EmojiRouteCandidate(route = route.stableName, available = available)
        }

    /**
     * Builds an unavailable-route diagnostic for one candidate.
     */
    private fun unavailableDiagnostic(glyphId: Int, route: String, strikeKey: GlyphStrikeKey): ColorGlyphDiagnostic =
        ColorGlyphDiagnostic(
            glyphId = glyphId,
            route = route,
            message = "Route $route is unavailable for glyph $glyphId at ${strikeKey.sizePx}px.",
            code = unavailableDiagnosticCode(route),
            detail = "candidate=$route;glyphId=$glyphId;sizePx=${strikeKey.sizePx}",
        )

    private fun unavailableDiagnosticCode(route: String): String =
        when (route) {
            "bitmap" -> ColorGlyphDiagnosticCodes.BitmapStrikeUnavailable
            "outline" -> ColorGlyphDiagnosticCodes.EmojiFallbackUnavailable
            else -> ColorGlyphDiagnosticCodes.ColorGlyphUnavailable
        }
}

/**
 * Records one emoji dispatch decision.
 *
 * @property glyphId glyph identifier being dispatched.
 * @property route selected color glyph route.
 * @property diagnostics diagnostics emitted while selecting the route.
 */
data class EmojiGlyphDispatch(
    val glyphId: Int,
    val route: String,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    /**
     * SHA-256 digest of this dispatch dump without the `dumpSha256` field.
     */
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    /**
     * Serializes the dispatch decision as deterministic JSON evidence.
     *
     * @return canonical JSON dump ending with a newline.
     */
    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", EmojiDispatchSchema, comma = true)
        appendColorGlyphJsonField("glyphId", glyphId, comma = true)
        append("  ")
        append(colorGlyphJsonString("routeOrder"))
        append(": ")
        appendColorGlyphRouteOrderJson()
        append(",\n")
        appendColorGlyphJsonField("selectedRoute", route, comma = true)
        append("  ")
        append(colorGlyphJsonString("diagnostics"))
        append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }

    companion object {
        /**
         * Stable schema label for emoji glyph dispatch evidence.
         */
        const val EmojiDispatchSchema: String = "org.graphiks.kanvas.glyph.color.EmojiGlyphDispatch.v1"
    }
}

data class EmojiFallbackAttempt(
    val familyName: String,
    val typefaceId: TypefaceID?,
    val covered: Boolean,
    val reason: String,
    val selected: Boolean = false,
    val diagnosticCode: String? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("familyName")).append(": ").append(colorGlyphJsonString(familyName)).append(", ")
        append(colorGlyphJsonString("typefaceId")).append(": ")
        append(typefaceId?.let { colorGlyphJsonString(it.value.toString()) } ?: "null").append(", ")
        append(colorGlyphJsonString("covered")).append(": ").append(covered).append(", ")
        append(colorGlyphJsonString("reason")).append(": ").append(colorGlyphJsonString(reason)).append(", ")
        append(colorGlyphJsonString("selected")).append(": ").append(selected).append(", ")
        append(colorGlyphJsonString("diagnosticCode")).append(": ")
        append(diagnosticCode?.let(::colorGlyphJsonString) ?: "null")
        append("}")
    }
}

data class EmojiRoutePlanReference(
    val dumpId: String,
    val caseId: String,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("dumpId")).append(": ").append(colorGlyphJsonString(dumpId)).append(", ")
        append(colorGlyphJsonString("caseId")).append(": ").append(colorGlyphJsonString(caseId))
        append("}")
    }
}

data class EmojiRouteRequest(
    val sequenceFact: EmojiSequenceFact,
    val unicodeVersion: String,
    val glyphId: Int?,
    val strikeKey: GlyphStrikeKey,
    val fallbackAttempts: List<EmojiFallbackAttempt> = emptyList(),
    val availability: EmojiGlyphRouteAvailability = EmojiGlyphRouteAvailability(),
    val evidenceRef: EmojiRoutePlanReference? = null,
    val allowMonochromeFallback: Boolean = true,
)

data class EmojiRouteTrace(
    val textRange: IntRange,
    val sequenceKind: EmojiSequenceKind,
    val codePoints: List<Int>,
    val unicodeVersion: String,
    val glyphId: Int?,
    val fallbackAttempts: List<EmojiFallbackAttempt> = emptyList(),
    val selectedTypefaceId: TypefaceID? = null,
    val selectedFamilyName: String? = null,
    val selectedRoute: String,
    val monochromeFallback: Boolean,
    val evidenceRef: EmojiRoutePlanReference? = null,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", EmojiRouteTraceSchema, comma = true)
        append("  ").append(colorGlyphJsonString("textRange")).append(": ")
            .append(colorGlyphJsonString("${textRange.first}..${textRange.last}")).append(",\n")
        appendColorGlyphJsonField("sequenceKind", sequenceKind.name, comma = true)
        append("  ").append(colorGlyphJsonString("codePoints")).append(": ")
            .append(codePoints.joinToString(prefix = "[", postfix = "]") { codePoint ->
                colorGlyphJsonString("U+${codePoint.toString(16).uppercase().padStart(4, '0')}")
            }).append(",\n")
        appendColorGlyphJsonField("unicodeVersion", unicodeVersion, comma = true)
        append("  ").append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId ?: "null").append(",\n")
        append("  ").append(colorGlyphJsonString("selectedTypefaceId")).append(": ")
            .append(selectedTypefaceId?.let { colorGlyphJsonString(it.value.toString()) } ?: "null").append(",\n")
        append("  ").append(colorGlyphJsonString("selectedFamilyName")).append(": ")
            .append(selectedFamilyName?.let(::colorGlyphJsonString) ?: "null").append(",\n")
        appendColorGlyphJsonField("selectedRoute", selectedRoute, comma = true)
        append("  ").append(colorGlyphJsonString("monochromeFallback")).append(": ").append(monochromeFallback).append(",\n")
        append("  ").append(colorGlyphJsonString("fallbackAttempts")).append(": ")
            .append(fallbackAttempts.joinToString(prefix = "[", postfix = "]") { it.toCanonicalJson() }).append(",\n")
        append("  ").append(colorGlyphJsonString("evidenceRef")).append(": ")
            .append(evidenceRef?.toCanonicalJson() ?: "null").append(",\n")
        append("  ").append(colorGlyphJsonString("diagnostics")).append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }

    companion object {
        const val EmojiRouteTraceSchema: String = "org.graphiks.kanvas.glyph.color.EmojiRouteTrace.v1"
    }
}

class SimpleEmojiSequencePlanner {
    fun plan(request: EmojiRouteRequest): EmojiRouteTrace {
        if (request.sequenceFact.kind == EmojiSequenceKind.Unsupported) {
            return EmojiRouteTrace(
                textRange = request.sequenceFact.textRange,
                sequenceKind = request.sequenceFact.kind,
                codePoints = request.sequenceFact.codePoints,
                unicodeVersion = request.unicodeVersion,
                glyphId = request.glyphId,
                selectedRoute = "missing",
                monochromeFallback = false,
                diagnostics = listOf(
                    ColorGlyphDiagnostic(
                        glyphId = request.glyphId,
                        route = "missing",
                        message = "Emoji sequence is unsupported for the entire cluster.",
                        severity = "warning",
                        code = ColorGlyphDiagnosticCodes.EmojiSequenceUnsupported,
                        detail = unsupportedSequenceDetail(request.sequenceFact),
                    ),
                ),
            )
        }

        val selectedAttempt = request.fallbackAttempts.firstOrNull { it.selected }
        if (selectedAttempt == null) {
            return EmojiRouteTrace(
                textRange = request.sequenceFact.textRange,
                sequenceKind = request.sequenceFact.kind,
                codePoints = request.sequenceFact.codePoints,
                unicodeVersion = request.unicodeVersion,
                glyphId = request.glyphId,
                fallbackAttempts = request.fallbackAttempts,
                selectedRoute = "missing",
                monochromeFallback = false,
                diagnostics = listOf(
                    ColorGlyphDiagnostic(
                        glyphId = request.glyphId,
                        route = "missing",
                        message = "No emoji-capable fallback family is available for the sequence cluster.",
                        severity = "warning",
                        code = ColorGlyphDiagnosticCodes.EmojiFallbackUnavailable,
                        detail = "textRange=${request.sequenceFact.textRange.first}..${request.sequenceFact.textRange.last};kind=${request.sequenceFact.kind.name};availableFallbacks=none",
                    ),
                ),
            )
        }

        val effectiveAvailability = if (request.allowMonochromeFallback) {
            request.availability
        } else {
            request.availability.copy(outlineGlyphs = emptySet())
        }
        val routeDiagnostics = colorRouteDiagnostics(
            glyphId = request.glyphId,
            strikeKey = request.strikeKey,
            availability = effectiveAvailability,
        )
        val selectedRoute = selectedRouteFor(glyphId = request.glyphId, availability = effectiveAvailability)
        return EmojiRouteTrace(
            textRange = request.sequenceFact.textRange,
            sequenceKind = request.sequenceFact.kind,
            codePoints = request.sequenceFact.codePoints,
            unicodeVersion = request.unicodeVersion,
            glyphId = request.glyphId,
            fallbackAttempts = request.fallbackAttempts,
            selectedTypefaceId = selectedAttempt.typefaceId,
            selectedFamilyName = selectedAttempt.familyName,
            selectedRoute = selectedRoute,
            monochromeFallback = selectedRoute == "outline",
            evidenceRef = request.evidenceRef,
            diagnostics = routeDiagnostics,
        )
    }

    private fun colorRouteDiagnostics(
        glyphId: Int?,
        strikeKey: GlyphStrikeKey,
        availability: EmojiGlyphRouteAvailability,
    ): List<ColorGlyphDiagnostic> {
        val resolvedGlyphId = glyphId ?: return emptyList()
        val dispatcher = SimpleEmojiGlyphDispatcher(availability)
        val dispatch = dispatcher.dispatch(glyphId = resolvedGlyphId, strikeKey = strikeKey)
        return if (dispatch.route != "missing") {
            dispatch.diagnostics
        } else {
            dispatch.diagnostics.dropLast(1) + ColorGlyphDiagnostic(
                glyphId = resolvedGlyphId,
                route = "missing",
                message = "No supported color glyph route is available for the selected emoji typeface.",
                severity = "warning",
                code = ColorGlyphDiagnosticCodes.ColorGlyphUnavailable,
                detail = "glyphId=$resolvedGlyphId;sizePx=${strikeKey.sizePx};supportedRoutes=none",
            )
        }
    }

    private fun selectedRouteFor(glyphId: Int?, availability: EmojiGlyphRouteAvailability): String {
        val resolvedGlyphId = glyphId ?: return "missing"
        return when {
            resolvedGlyphId in availability.colrGlyphs -> "colr"
            resolvedGlyphId in availability.bitmapGlyphs -> "bitmap"
            resolvedGlyphId in availability.pngGlyphs -> "png"
            resolvedGlyphId in availability.svgGlyphs -> "svg"
            resolvedGlyphId in availability.outlineGlyphs -> "outline"
            else -> "missing"
        }
    }

    private fun unsupportedSequenceDetail(sequenceFact: EmojiSequenceFact): String =
        "textRange=${sequenceFact.textRange.first}..${sequenceFact.textRange.last};codePoints=" +
            sequenceFact.codePoints.joinToString(",") { codePoint ->
                "U+${codePoint.toString(16).uppercase().padStart(4, '0')}"
            }
}

/**
 * Describes a color glyph planning result without duplicating public GPU API plan classes.
 *
 * @property routes glyph routes selected by color planning.
 * @property diagnostics color-specific diagnostics.
 */
data class ColorGlyphPlanningResult(
    val routes: List<ColorGlyphRoute>,
    val diagnostics: List<ColorGlyphDiagnostic> = emptyList(),
) {
    /**
     * SHA-256 digest of this planning dump without the `dumpSha256` field.
     */
    val dumpSha256: String
        get() = colorGlyphSha256(canonicalJson(includeDumpSha256 = false).toByteArray(Charsets.UTF_8))

    /**
     * Serializes selected routes and diagnostics as deterministic JSON evidence.
     *
     * @return canonical JSON dump ending with a newline.
     */
    fun toCanonicalJson(): String = canonicalJson(includeDumpSha256 = true)

    private fun canonicalJson(includeDumpSha256: Boolean): String = buildString {
        append("{\n")
        appendColorGlyphJsonField("schema", PlanningResultSchema, comma = true)
        append("  ")
        append(colorGlyphJsonString("routeOrder"))
        append(": ")
        appendColorGlyphRouteOrderJson()
        append(",\n")
        append("  ")
        append(colorGlyphJsonString("selectedRoutes"))
        append(": ")
        appendColorGlyphRoutesJson(routes, indent = "  ")
        append(",\n")
        append("  ")
        append(colorGlyphJsonString("diagnostics"))
        append(": ")
        appendColorGlyphDiagnosticsJson(diagnostics, indent = "  ")
        if (includeDumpSha256) {
            append(",\n")
            appendColorGlyphJsonField("dumpSha256", dumpSha256, comma = false)
        } else {
            append("\n")
        }
        append("}\n")
    }

    companion object {
        /**
         * Stable schema label for color glyph planning evidence.
         */
        const val PlanningResultSchema: String = "org.graphiks.kanvas.glyph.color.ColorGlyphPlanningResult.v1"
    }
}

/**
 * Metadata-only color glyph route selected for one glyph.
 *
 * @property glyphId glyph identifier represented by the selected route.
 * @property route selected color route label.
 * @property outline outline fallback representation when [route] is `outline`.
 */
data class ColorGlyphRoute(
    val glyphId: Int,
    val route: String,
    val outline: OutlineGlyphRepresentation? = null,
) {
    init {
        require(route in COLOR_GLYPH_ROUTES) {
            "Unsupported color glyph route: $route."
        }
        require((route == "outline") == (outline != null)) {
            "Outline color glyph routes must carry an outline representation, and non-outline routes must not."
        }
    }

    /**
     * Serializes this selected route as deterministic JSON evidence.
     *
     * @return canonical JSON object without a trailing newline.
     */
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId).append(", ")
        append(colorGlyphJsonString("route")).append(": ").append(colorGlyphJsonString(route)).append(", ")
        append(colorGlyphJsonString("outlineFallback")).append(": ").append(route == "outline").append(", ")
        append(colorGlyphJsonString("outlineFacts")).append(": ")
        if (outline == null) {
            append("null")
        } else {
            append("{")
            append(colorGlyphJsonString("pathCommandCount")).append(": ").append(outline.pathCommands.size).append(", ")
            append(colorGlyphJsonString("windingRule")).append(": ").append(colorGlyphJsonString(outline.windingRule))
            append("}")
        }
        append("}")
    }
}

/**
 * Stable diagnostic code families used by color glyph planning and emoji dispatch evidence.
 */
object ColorGlyphDiagnosticCodes {
    const val CPALMalformed: String = "text.color.CPAL-malformed"
    const val COLRMalformed: String = "text.color.COLR-malformed"
    const val COLRV1PaintUnsupported: String = "text.color.COLRv1-paint-unsupported"
    const val COLRV1CycleDetected: String = "text.color.COLRv1-cycle-detected"
    const val COLRV1BudgetExceeded: String = "text.color.COLRv1-budget-exceeded"
    const val PNGDecodeFailed: String = "text.bitmap.PNG-decode-failed"
    const val BitmapStrikeUnavailable: String = "text.bitmap.strike-unavailable"
    const val BitmapPayloadFormatUnsupported: String = "text.bitmap.payload-format-unsupported"
    const val SVGDocumentMalformed: String = "text.SVG.document-malformed"
    const val SVGFeatureUnsupported: String = "text.SVG.feature-unsupported"
    const val SVGExternalResourceRefused: String = "text.SVG.external-resource-refused"
    const val SVGBudgetExceeded: String = "text.SVG.budget-exceeded"
    const val EmojiSequenceUnsupported: String = "text.emoji.sequence-unsupported"
    const val EmojiFallbackUnavailable: String = "text.emoji.fallback-unavailable"
    const val ColorGlyphUnavailable: String = "text.emoji.color-glyph-unavailable"
    const val EmojiRouteSelected: String = "text.emoji.route-selected"
    const val EmojiRouteLowerPreferenceSkipped: String = "text.emoji.route-lower-preference-skipped"
}

/**
 * Describes a color glyph routing decision, alternate route, or unsupported source condition.
 *
 * @property glyphId glyph identifier associated with the diagnostic when available.
 * @property route selected or attempted color route.
 * @property message human-readable diagnostic message.
 * @property severity severity label for logs and PM evidence.
 * @property code stable diagnostic code suitable for route dumps and PM evidence.
 * @property detail stable machine-readable detail string without host-specific facts.
 *
 * The primary constructor keeps JVM overloads for the original four-field constructor. The
 * Kotlin data-class `copy(...)` signature follows the current property set.
 */
data class ColorGlyphDiagnostic @JvmOverloads constructor(
    val glyphId: Int?,
    val route: String,
    val message: String,
    val severity: String = "info",
    val code: String = ColorGlyphDiagnosticCodes.ColorGlyphUnavailable,
    val detail: String = message,
) {
    /**
     * Converts this color diagnostic into a generic glyph route diagnostic.
     *
     * @return generic glyph route diagnostic with the same core fields.
     */
    fun toGlyphRouteDiagnostic(): GlyphRouteDiagnostic =
        GlyphRouteDiagnostic(
            glyphId = glyphId,
            route = route,
            message = message,
            severity = severity,
        )

    /**
     * Serializes this color diagnostic with stable field order and JSON escaping.
     *
     * @return canonical JSON object without a trailing newline.
     */
    fun toCanonicalJson(): String = buildString {
        append("{")
        append(colorGlyphJsonString("glyphId")).append(": ").append(glyphId ?: "null").append(", ")
        append(colorGlyphJsonString("route")).append(": ").append(colorGlyphJsonString(route)).append(", ")
        append(colorGlyphJsonString("code")).append(": ").append(colorGlyphJsonString(code)).append(", ")
        append(colorGlyphJsonString("detail")).append(": ").append(colorGlyphJsonString(detail)).append(", ")
        append(colorGlyphJsonString("severity")).append(": ").append(colorGlyphJsonString(severity)).append(", ")
        append(colorGlyphJsonString("message")).append(": ").append(colorGlyphJsonString(message))
        append("}")
    }
}

/**
 * One route candidate considered by [SimpleEmojiGlyphDispatcher].
 */
private data class EmojiRouteCandidate(
    val route: String,
    val available: Boolean,
)

private val COLOR_GLYPH_ROUTE_ORDER = listOf("colr", "bitmap", "png", "svg", "outline")
private val COLOR_GLYPH_ROUTES = COLOR_GLYPH_ROUTE_ORDER.toSet()
private const val BitmapGlyphPlanSchema = "org.graphiks.kanvas.glyph.color.BitmapGlyphPlan.v1"
private const val ColorArtifactRoute = "text.glyph.color.COLR"
private const val OutlineArtifactRoute = "text.glyph.outline"
private const val SVGGlyphPlanSchema = "org.graphiks.kanvas.glyph.color.SVGGlyphPlan.v1"

/**
 * Appends a canonical JSON string field using the module's two-space object indentation.
 */
private fun StringBuilder.appendColorGlyphJsonField(name: String, value: String, comma: Boolean) {
    append("  ")
    append(colorGlyphJsonString(name))
    append(": ")
    append(colorGlyphJsonString(value))
    if (comma) append(",")
    append("\n")
}

/**
 * Appends a canonical JSON integer field using the module's two-space object indentation.
 */
private fun StringBuilder.appendColorGlyphJsonField(name: String, value: Int, comma: Boolean) {
    append("  ")
    append(colorGlyphJsonString(name))
    append(": ")
    append(value)
    if (comma) append(",")
    append("\n")
}

/**
 * Appends the stable route preference order used by emoji color glyph dispatch.
 */
private fun StringBuilder.appendColorGlyphRouteOrderJson() {
    append(COLOR_GLYPH_ROUTE_ORDER.joinToString(prefix = "[", postfix = "]") { route ->
        colorGlyphJsonString(route)
    })
}

/**
 * Derives a stable primitive kind label from a canonical SVG element summary.
 */
private fun svgPrimitiveKind(summary: String): String =
    when (val raw = summary.substringBefore('{').trim()) {
        "clippath" -> "clipPath"
        "lineargradient" -> "linearGradient"
        "radialgradient" -> "radialGradient"
        else -> raw
    }

/**
 * Counts SVG path commands in raw source text using literal `d="..."` path attributes.
 */
private fun svgPathCommandCount(sourceText: String): Int =
    SVG_PATH_ATTRIBUTE_PATTERN.findAll(sourceText).sumOf { match ->
        SVG_PATH_COMMAND_PATTERN.findAll(match.groupValues[1]).count()
    }

/**
 * Counts SVG gradient stop elements in raw source text.
 */
private fun svgGradientStopCount(sourceText: String): Int =
    SVG_STOP_ELEMENT_PATTERN.findAll(sourceText).count()

/**
 * Appends selected routes as a canonical JSON array while preserving planning order.
 */
private fun StringBuilder.appendColorGlyphRoutesJson(routes: List<ColorGlyphRoute>, indent: String) {
    append("[")
    if (routes.isNotEmpty()) {
        append("\n")
        append(routes.joinToString(",\n") { route -> "$indent  ${route.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

/**
 * Appends typed COLRv0 layer plans as a canonical JSON array.
 */
private fun StringBuilder.appendColorGlyphLayerPlansJson(
    layers: List<COLRV0LayerPlan>,
    indent: String,
) {
    append("[")
    if (layers.isNotEmpty()) {
        append("\n")
        append(layers.joinToString(",\n") { layer -> "$indent  ${layer.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

private fun StringBuilder.appendColorGlyphGraphNodesJson(
    nodes: List<COLRV1PaintGraphNode>,
    indent: String,
) {
    append("[")
    if (nodes.isNotEmpty()) {
        append("\n")
        append(nodes.joinToString(",\n") { node -> "$indent  ${node.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

/**
 * Appends diagnostics as a canonical JSON array while preserving route evidence order.
 */
private fun StringBuilder.appendColorGlyphDiagnosticsJson(diagnostics: List<ColorGlyphDiagnostic>, indent: String) {
    append("[")
    if (diagnostics.isNotEmpty()) {
        append("\n")
        append(diagnostics.joinToString(",\n") { diagnostic -> "$indent  ${diagnostic.toCanonicalJson()}" })
        append("\n")
        append(indent)
    }
    append("]")
}

/**
 * Formats a finite float using a stable JSON token.
 */
private fun colorGlyphFloatToken(value: Float): String {
    require(value.isFinite()) { "Color glyph float values must be finite." }
    val token = value.toString()
    return if (token.endsWith(".0") && 'E' !in token && 'e' !in token) {
        token.dropLast(2)
    } else {
        token
    }
}

private const val ColorGlyphFloatEpsilon: Float = 0.0001f
private const val COLRV1TransformDeterminantEpsilon: Float = 0.0001f

private fun approxColorGlyphFloat(left: Float, right: Float): Boolean =
    abs(left - right) <= ColorGlyphFloatEpsilon

private fun colorGlyphFloatMapJson(values: Map<String, Float>): String = buildString {
    append("{")
    append(
        values.entries
            .sortedBy { entry -> entry.key }
            .joinToString(", ") { entry ->
                "${colorGlyphJsonString(entry.key)}: ${colorGlyphFloatToken(entry.value)}"
            },
    )
    append("}")
}

private fun colorGlyphNullableString(value: String?): String =
    value?.let(::colorGlyphJsonString) ?: "null"

private fun GlyphStrikeKey.artifactKeyForGlyph(glyphId: Int, route: String): ColorGlyphArtifactKey =
    ColorGlyphArtifactKey(
        glyphId = glyphId,
        route = route,
        strikeKeySha256 = copy(
            glyphId = glyphId,
            representationRoute = route,
            maskFormat = GlyphStrikeKey.NoMaskFormat,
        ).preimageSha256(glyphId),
    )

private fun cpalMalformedDiagnostic(
    glyphId: Int,
    detail: String,
    message: String,
): ColorGlyphDiagnostic = ColorGlyphDiagnostic(
    glyphId = glyphId,
    route = "colr",
    code = ColorGlyphDiagnosticCodes.CPALMalformed,
    severity = "warning",
    detail = detail,
    message = message,
)

private fun colrMalformedDiagnostic(
    glyphId: Int,
    detail: String,
    message: String,
): ColorGlyphDiagnostic = ColorGlyphDiagnostic(
    glyphId = glyphId,
    route = "colr",
    code = ColorGlyphDiagnosticCodes.COLRMalformed,
    severity = "warning",
    detail = detail,
    message = message,
)

private fun colorGlyphArgbHex(color: Int): String {
    val unsigned = color.toLong() and 0xFFFF_FFFFL
    return "#%08X".format(unsigned)
}

private fun resolvePaletteColorArgb(
    palette: CPALPalette,
    paletteIndex: Int,
    alpha: Float,
): String {
    if (paletteIndex == COLR_FOREGROUND_PALETTE_INDEX) {
        return "#%02X000000".format((255f * alpha).toInt().coerceIn(0, 255))
    }
    val baseColor = palette.colors.getOrNull(paletteIndex)
        ?: error("Palette index $paletteIndex is unavailable for resolved color output.")
    val baseAlpha = (baseColor ushr 24) and 0xFF
    val resolvedAlpha = (baseAlpha.toFloat() * alpha).toInt().coerceIn(0, 255)
    val rgb = baseColor and 0x00FF_FFFF
    return colorGlyphArgbHex((resolvedAlpha shl 24) or rgb)
}

private fun COLRV1Paint.colrv1PlannerKind(): String =
    when (this) {
        is COLRV1Paint.Solid -> if (varIndexBase == null) "colrv1-paint-solid" else "colrv1-paint-var-solid"
        is COLRV1Paint.Glyph -> "colrv1-paint-glyph"
        is COLRV1Paint.Layers -> "colrv1-paint-layers"
        is COLRV1Paint.LinearGradient -> "colrv1-paint-linear-gradient"
        is COLRV1Paint.RadialGradient -> "colrv1-paint-radial-gradient"
        is COLRV1Paint.SweepGradient -> "colrv1-paint-sweep-gradient"
        is COLRV1Paint.Composite -> "colrv1-paint-composite"
        is COLRV1Paint.ColrGlyph -> "colrv1-paint-colr-glyph"
        is COLRV1Paint.Translate -> "colrv1-paint-translate"
        is COLRV1Paint.Transform -> "colrv1-paint-transform"
    }

private data class COLRV1TransformClassification(
    val nodeKind: String,
    val transformKind: String,
)

private data class COLRV1CompositePlanFacts(
    val destinationReadClass: String,
    val requiresLayerIsolation: Boolean,
)

private fun COLRV1ClipBox.toBounds(): ColorGlyphBounds =
    ColorGlyphBounds(
        xMin = xMin,
        yMin = yMin,
        xMax = xMax,
        yMax = yMax,
    )

private fun classifyCOLRV1Transform(
    xx: Float,
    yx: Float,
    xy: Float,
    yy: Float,
): COLRV1TransformClassification =
    when {
        approxColorGlyphFloat(yx, 0f) && approxColorGlyphFloat(xy, 0f) &&
            (!approxColorGlyphFloat(xx, 1f) || !approxColorGlyphFloat(yy, 1f)) ->
            COLRV1TransformClassification(nodeKind = "colrv1-paint-scale", transformKind = "scale")
        approxColorGlyphFloat(xx, yy) && approxColorGlyphFloat(xy, -yx) &&
            (!approxColorGlyphFloat(yx, 0f) || !approxColorGlyphFloat(xy, 0f)) ->
            COLRV1TransformClassification(nodeKind = "colrv1-paint-rotate", transformKind = "rotate")
        approxColorGlyphFloat(xx, 1f) && approxColorGlyphFloat(yy, 1f) &&
            (!approxColorGlyphFloat(xy, 0f) || !approxColorGlyphFloat(yx, 0f)) ->
            COLRV1TransformClassification(nodeKind = "colrv1-paint-skew", transformKind = "skew")
        else -> COLRV1TransformClassification(nodeKind = "colrv1-paint-transform", transformKind = "transform")
    }

private fun compositePlanForMode(mode: COLRV1CompositeMode): COLRV1CompositePlanFacts? =
    when (mode) {
        COLRV1CompositeMode.SRC_OVER,
        COLRV1CompositeMode.PLUS -> COLRV1CompositePlanFacts(
            destinationReadClass = "none",
            requiresLayerIsolation = false,
        )
        COLRV1CompositeMode.MULTIPLY -> COLRV1CompositePlanFacts(
            destinationReadClass = "shader-destination-read",
            requiresLayerIsolation = true,
        )
        else -> null
    }

/**
 * Escapes a string for canonical JSON evidence.
 */
private fun colorGlyphJsonString(value: String): String = buildString {
    append('"')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

/**
 * Computes a lowercase SHA-256 digest for deterministic color glyph evidence.
 */
private fun colorGlyphSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }

/**
 * Encodes packed ARGB pixels into deterministic big-endian bytes for hashing.
 */
private fun List<Int>.toARGBByteArray(): ByteArray {
    val bytes = ByteArray(size * 4)
    forEachIndexed { index, pixel ->
        val offset = index * 4
        bytes[offset] = (pixel ushr 24).toByte()
        bytes[offset + 1] = (pixel ushr 16).toByte()
        bytes[offset + 2] = (pixel ushr 8).toByte()
        bytes[offset + 3] = pixel.toByte()
    }
    return bytes
}

/**
 * Bounds-checked big-endian reader for raw OpenType color table bytes.
 */
private class ColorTableReader(
    private val bytes: ByteArray,
) {
    /**
     * Size of the bounded byte source.
     */
    val size: Int
        get() = bytes.size

    /**
     * Returns true when [offset] and [length] describe a range inside [bytes].
     */
    fun fits(offset: Int, length: Long): Boolean {
        if (offset < 0 || length < 0L) return false
        val start = offset.toLong()
        val end = start + length
        return start <= bytes.size.toLong() && end >= start && end <= bytes.size.toLong()
    }

    /**
     * Reads an unsigned 8-bit value.
     */
    fun u8(offset: Int): Int? =
        if (fits(offset, 1L)) bytes[offset].toInt() and 0xFF else null

    /**
     * Reads an unsigned 16-bit big-endian value.
     */
    fun u16(offset: Int): Int? {
        if (!fits(offset, U16_SIZE_BYTES.toLong())) return null
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    /**
     * Reads a signed 16-bit big-endian value.
     */
    fun i16(offset: Int): Int? =
        u16(offset)?.toShort()?.toInt()

    /**
     * Reads an unsigned 24-bit big-endian value.
     */
    fun u24(offset: Int): Int? {
        if (!fits(offset, U24_SIZE_BYTES.toLong())) return null
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    /**
     * Reads an unsigned 32-bit big-endian value.
     */
    fun u32(offset: Int): Long? {
        if (!fits(offset, U32_SIZE_BYTES.toLong())) return null
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    /**
     * Reads a signed OpenType F2DOT14 value.
     */
    fun f2Dot14(offset: Int): Float? =
        i16(offset)?.let { value -> value / 16384f }

    /**
     * Reads a signed OpenType Fixed 16.16 value.
     */
    fun fixed16Dot16(offset: Int): Float? {
        if (!fits(offset, U32_SIZE_BYTES.toLong())) return null
        val raw = ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
        return raw / 65536f
    }
}

/**
 * Converts a nullable unsigned 32-bit table offset to a Kotlin Int.
 */
private fun Long.toIntOrNull(): Int? =
    if (this <= Int.MAX_VALUE.toLong()) toInt() else null

/**
 * Resolves a relative table offset without overflowing the Kotlin Int offset space.
 */
private fun absoluteTableOffset(baseOffset: Int, relativeOffset: Int, tableSize: Int): Int? {
    val absolute = baseOffset.toLong() + relativeOffset.toLong()
    if (absolute < 0L || absolute >= tableSize.toLong() || absolute > Int.MAX_VALUE.toLong()) return null
    return absolute.toInt()
}

/**
 * Appends one COLRv1 typed paint into a generic flattened paint graph.
 */
private fun appendCOLRV1PaintNode(paint: COLRV1Paint, nodes: MutableList<COLRPaintNode>): Int {
    val id = nodes.size
    val baseNode = when (paint) {
        is COLRV1Paint.Solid -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-solid",
            paletteIndex = paint.paletteIndex,
        )
        is COLRV1Paint.Glyph -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-glyph",
            glyphId = paint.glyphId,
        )
        is COLRV1Paint.Layers -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-layers",
        )
        is COLRV1Paint.LinearGradient -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-linear-gradient",
        )
        is COLRV1Paint.RadialGradient -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-radial-gradient",
        )
        is COLRV1Paint.SweepGradient -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-sweep-gradient",
        )
        is COLRV1Paint.Composite -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-composite-${paint.mode.graphSuffix}",
        )
        is COLRV1Paint.ColrGlyph -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-colr-glyph",
            glyphId = paint.glyphId,
        )
        is COLRV1Paint.Translate -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-translate",
        )
        is COLRV1Paint.Transform -> COLRPaintNode(
            id = id,
            kind = "colr-v1-paint-transform",
        )
    }
    nodes += baseNode

    val childIds = when (paint) {
        is COLRV1Paint.Solid -> emptyList()
        is COLRV1Paint.Glyph -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.Layers -> paint.paints.map { child -> appendCOLRV1PaintNode(paint = child, nodes = nodes) }
        is COLRV1Paint.LinearGradient -> emptyList()
        is COLRV1Paint.RadialGradient -> emptyList()
        is COLRV1Paint.SweepGradient -> emptyList()
        is COLRV1Paint.Composite -> listOf(
            appendCOLRV1PaintNode(paint = paint.source, nodes = nodes),
            appendCOLRV1PaintNode(paint = paint.backdrop, nodes = nodes),
        )
        is COLRV1Paint.ColrGlyph -> emptyList()
        is COLRV1Paint.Translate -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
        is COLRV1Paint.Transform -> listOf(appendCOLRV1PaintNode(paint = paint.paint, nodes = nodes))
    }
    nodes[id] = baseNode.copy(children = childIds)
    return id
}

/**
 * Packs color channels into the module's ARGB integer representation.
 */
private fun packArgb(alpha: Int, red: Int, green: Int, blue: Int): Int =
    ((alpha and 0xFF) shl 24) or
        ((red and 0xFF) shl 16) or
        ((green and 0xFF) shl 8) or
        (blue and 0xFF)

/**
 * Returns true when a bitmap strike entry contains usable metadata.
 */
private fun BitmapStrikeSelection.isUsable(): Boolean =
    glyphId >= 0 &&
        width > 0 &&
        height > 0 &&
        ppem > 0 &&
        format.isNotBlank()

/**
 * Builds the nearest-strike comparator for one requested size.
 */
private fun bitmapStrikeDistanceOrder(requestedSizePx: Float): Comparator<BitmapStrikeSelection> =
    compareBy<BitmapStrikeSelection> { selection ->
        abs(selection.ppem.toFloat() - requestedSizePx)
    }.then(BITMAP_STRIKE_STABLE_ORDER)

/**
 * Returns true when [bytes] starts with the eight-byte PNG signature.
 */
private fun hasPngSignature(bytes: ByteArray): Boolean {
    if (bytes.size < PNG_SIGNATURE.size) return false
    return PNG_SIGNATURE.indices.all { index -> bytes[index] == PNG_SIGNATURE[index] }
}

/**
 * Returns true when [bytes] contains [text] encoded as ASCII at [offset].
 */
private fun matchesAscii(bytes: ByteArray, offset: Int, text: String): Boolean {
    if (offset < 0 || offset + text.length > bytes.size) return false
    return text.indices.all { index -> bytes[offset + index].toInt() == text[index].code }
}

/**
 * Decodes a small, font-glyph-oriented PNG subset: non-interlaced 8-bit RGB/RGBA.
 */
private fun decodeMinimalPngGlyphPixels(bytes: ByteArray, width: Int, height: Int): List<Int> {
    val reader = ColorTableReader(bytes)
    val idat = ByteArrayOutputStream()
    var offset = PNG_SIGNATURE.size
    var sawIhdr = false
    var sawIdat = false
    var sawIend = false
    var bitDepth = -1
    var colorType = -1
    var compression = -1
    var filter = -1
    var interlace = -1

    while (offset < bytes.size) {
        if (!reader.fits(offset, PNG_CHUNK_OVERHEAD_BYTES.toLong())) {
            throw IllegalArgumentException("Could not decode PNG glyph payload: truncated chunk.")
        }
        val length = reader.u32(offset)?.toIntOrNull()
            ?: throw IllegalArgumentException("Could not decode PNG glyph payload: chunk length is out of range.")
        val typeOffset = offset + U32_SIZE_BYTES
        val dataOffset = typeOffset + PNG_CHUNK_TYPE_BYTES
        val crcOffset = dataOffset + length
        if (!reader.fits(dataOffset, length.toLong() + U32_SIZE_BYTES.toLong())) {
            throw IllegalArgumentException("Could not decode PNG glyph payload: truncated chunk data.")
        }
        requirePngChunkCrc(bytes = bytes, typeOffset = typeOffset, crcOffset = crcOffset)

        when (pngChunkType(bytes, typeOffset)) {
            PNG_CHUNK_IHDR -> {
                if (sawIhdr || offset != PNG_SIGNATURE.size || length != PNG_IHDR_DATA_SIZE) {
                    throw IllegalArgumentException("Could not decode PNG glyph payload: invalid IHDR chunk.")
                }
                bitDepth = reader.u8(dataOffset + 8)
                    ?: throw IllegalArgumentException("Could not decode PNG glyph payload: truncated IHDR.")
                colorType = reader.u8(dataOffset + 9)
                    ?: throw IllegalArgumentException("Could not decode PNG glyph payload: truncated IHDR.")
                compression = reader.u8(dataOffset + 10)
                    ?: throw IllegalArgumentException("Could not decode PNG glyph payload: truncated IHDR.")
                filter = reader.u8(dataOffset + 11)
                    ?: throw IllegalArgumentException("Could not decode PNG glyph payload: truncated IHDR.")
                interlace = reader.u8(dataOffset + 12)
                    ?: throw IllegalArgumentException("Could not decode PNG glyph payload: truncated IHDR.")
                sawIhdr = true
            }
            PNG_CHUNK_IDAT -> {
                if (!sawIhdr || sawIend) {
                    throw IllegalArgumentException("Could not decode PNG glyph payload: IDAT is out of order.")
                }
                idat.write(bytes, dataOffset, length)
                sawIdat = true
            }
            PNG_CHUNK_IEND -> {
                if (!sawIhdr || !sawIdat || length != 0) {
                    throw IllegalArgumentException("Could not decode PNG glyph payload: invalid IEND chunk.")
                }
                sawIend = true
                offset = bytes.size
                continue
            }
            else -> {
                if (isCriticalPngChunk(bytes[typeOffset])) {
                    throw IllegalArgumentException("Could not decode PNG glyph payload: unsupported critical chunk.")
                }
            }
        }

        offset = crcOffset + U32_SIZE_BYTES
    }

    if (!sawIhdr || !sawIdat || !sawIend) {
        throw IllegalArgumentException("Could not decode PNG glyph payload: missing required chunk.")
    }
    if (bitDepth != 8 || colorType !in setOf(PNG_COLOR_TYPE_RGB, PNG_COLOR_TYPE_RGBA)) {
        throw IllegalArgumentException("Could not decode PNG glyph payload: unsupported PNG color type.")
    }
    if (compression != 0 || filter != 0 || interlace != 0) {
        throw IllegalArgumentException("Could not decode PNG glyph payload: unsupported PNG encoding.")
    }

    val bytesPerPixel = if (colorType == PNG_COLOR_TYPE_RGBA) 4 else 3
    val rowBytes = width * bytesPerPixel
    val expectedInflatedSize = height * (rowBytes + 1)
    val inflated = inflatePngIdat(idat.toByteArray(), expectedInflatedSize)
    if (inflated.size != expectedInflatedSize) {
        throw IllegalArgumentException("Could not decode PNG glyph payload: truncated IDAT stream.")
    }

    val pixels = ArrayList<Int>(width * height)
    val previous = ByteArray(rowBytes)
    val current = ByteArray(rowBytes)
    var source = 0
    repeat(height) {
        val filterType = inflated[source++].toInt() and 0xFF
        inflated.copyInto(current, destinationOffset = 0, startIndex = source, endIndex = source + rowBytes)
        source += rowBytes
        if (!unfilterPngRow(filterType, current, previous, bytesPerPixel)) {
            throw IllegalArgumentException("Could not decode PNG glyph payload: invalid row filter.")
        }
        var pixelOffset = 0
        repeat(width) {
            val red = current[pixelOffset++].toInt() and 0xFF
            val green = current[pixelOffset++].toInt() and 0xFF
            val blue = current[pixelOffset++].toInt() and 0xFF
            val alpha = if (bytesPerPixel == 4) current[pixelOffset++].toInt() and 0xFF else 0xFF
            pixels += packArgb(alpha = alpha, red = red, green = green, blue = blue)
        }
        current.copyInto(previous)
    }
    return pixels.toList()
}

private fun requirePngChunkCrc(bytes: ByteArray, typeOffset: Int, crcOffset: Int) {
    val expected = ColorTableReader(bytes).u32(crcOffset)
        ?: throw IllegalArgumentException("Could not decode PNG glyph payload: missing chunk CRC.")
    val crc = CRC32()
    crc.update(bytes, typeOffset, crcOffset - typeOffset)
    if (crc.value != expected) {
        throw IllegalArgumentException("Could not decode PNG glyph payload: invalid chunk CRC.")
    }
}

private fun pngChunkType(bytes: ByteArray, offset: Int): String =
    String(
        charArrayOf(
            (bytes[offset].toInt() and 0xFF).toChar(),
            (bytes[offset + 1].toInt() and 0xFF).toChar(),
            (bytes[offset + 2].toInt() and 0xFF).toChar(),
            (bytes[offset + 3].toInt() and 0xFF).toChar(),
        ),
    )

private fun isCriticalPngChunk(firstTypeByte: Byte): Boolean =
    firstTypeByte.toInt() and 0x20 == 0

private fun inflatePngIdat(idat: ByteArray, expectedSize: Int): ByteArray {
    val inflater = Inflater()
    return try {
        inflater.setInput(idat)
        val output = ByteArrayOutputStream(expectedSize)
        val buffer = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw IllegalArgumentException("Could not decode PNG glyph payload: truncated IDAT stream.")
                }
            } else {
                output.write(buffer, 0, count)
                if (output.size() > expectedSize) {
                    throw IllegalArgumentException("Could not decode PNG glyph payload: oversized IDAT stream.")
                }
            }
        }
        output.toByteArray()
    } catch (_: DataFormatException) {
        throw IllegalArgumentException("Could not decode PNG glyph payload: invalid IDAT stream.")
    } finally {
        inflater.end()
    }
}

private fun unfilterPngRow(filterType: Int, current: ByteArray, previous: ByteArray, bytesPerPixel: Int): Boolean {
    for (index in current.indices) {
        val left = if (index >= bytesPerPixel) current[index - bytesPerPixel].toInt() and 0xFF else 0
        val up = previous[index].toInt() and 0xFF
        val upLeft = if (index >= bytesPerPixel) previous[index - bytesPerPixel].toInt() and 0xFF else 0
        val raw = current[index].toInt() and 0xFF
        val reconstructed = when (filterType) {
            0 -> raw
            1 -> raw + left
            2 -> raw + up
            3 -> raw + ((left + up) / 2)
            4 -> raw + paethPredictor(left, up, upLeft)
            else -> return false
        }
        current[index] = reconstructed.toByte()
    }
    return true
}

private fun paethPredictor(left: Int, up: Int, upLeft: Int): Int {
    val estimate = left + up - upLeft
    val leftDistance = abs(estimate - left)
    val upDistance = abs(estimate - up)
    val upLeftDistance = abs(estimate - upLeft)
    return when {
        leftDistance <= upDistance && leftDistance <= upLeftDistance -> left
        upDistance <= upLeftDistance -> up
        else -> upLeft
    }
}

/**
 * Finds the first start tag named [tagName] in [text].
 */
private fun findSvgStartTag(text: String, tagName: String): SVGStartTag? {
    var searchIndex = 0
    while (searchIndex < text.length) {
        val openIndex = text.indexOf('<', startIndex = searchIndex)
        if (openIndex < 0) return null

        val ignoredMarkupEnd = findIgnoredSvgMarkupEnd(text, openIndex)
        if (ignoredMarkupEnd != null) {
            searchIndex = ignoredMarkupEnd
            continue
        }

        val nameStart = skipSvgWhitespace(text, openIndex + 1)
        if (nameStart >= text.length || text[nameStart] == '/' || text[nameStart] == '!' || text[nameStart] == '?') {
            searchIndex = openIndex + 1
            continue
        }

        val nameEnd = readSvgNameEnd(text, nameStart)
        if (nameEnd == nameStart) {
            searchIndex = openIndex + 1
            continue
        }

        val closeIndex = findSvgTagEnd(text, nameEnd)
            ?: throw IllegalArgumentException("SVG glyph payload contains an unterminated start tag.")
        val name = text.substring(nameStart, nameEnd)
        if (name.equals(tagName, ignoreCase = true)) {
            return SVGStartTag(
                name = name.lowercase(),
                source = text.substring(nameStart, closeIndex),
            )
        }
        searchIndex = closeIndex + 1
    }
    return null
}

/**
 * Extracts deterministic summaries for supported SVG element start tags.
 */
private fun extractSvgElementSummaries(text: String): List<String> {
    val summaries = ArrayList<String>()
    var searchIndex = 0
    while (searchIndex < text.length) {
        val openIndex = text.indexOf('<', startIndex = searchIndex)
        if (openIndex < 0) break

        val ignoredMarkupEnd = findIgnoredSvgMarkupEnd(text, openIndex)
        if (ignoredMarkupEnd != null) {
            searchIndex = ignoredMarkupEnd
            continue
        }

        val nameStart = skipSvgWhitespace(text, openIndex + 1)
        if (nameStart >= text.length || text[nameStart] == '/' || text[nameStart] == '!' || text[nameStart] == '?') {
            searchIndex = openIndex + 1
            continue
        }

        val nameEnd = readSvgNameEnd(text, nameStart)
        if (nameEnd == nameStart) {
            searchIndex = openIndex + 1
            continue
        }

        val closeIndex = findSvgTagEnd(text, nameEnd)
            ?: throw IllegalArgumentException("SVG glyph payload contains an unterminated start tag.")
        val name = text.substring(nameStart, nameEnd).lowercase()
        if (name != "svg" && name in SVG_SUMMARY_ELEMENT_NAMES) {
            if (summaries.size == MAX_SVG_GLYPH_ELEMENTS) {
                throw IllegalArgumentException("SVG glyph element summary count exceeds $MAX_SVG_GLYPH_ELEMENTS.")
            }
            val attributes = parseSvgTagAttributes(text.substring(nameStart, closeIndex))
            summaries += formatSvgElementSummary(name = name, attributes = attributes)
        }

        searchIndex = closeIndex + 1
    }
    return summaries.toList()
}

/**
 * Returns the first offset after an ignored XML/SVG markup block at [openIndex].
 */
private fun findIgnoredSvgMarkupEnd(text: String, openIndex: Int): Int? =
    when {
        text.startsWith("<!--", startIndex = openIndex) ->
            findRequiredSvgMarkupTerminator(text, openIndex, "-->", "comment")

        text.startsWith("<![CDATA[", startIndex = openIndex) ->
            findRequiredSvgMarkupTerminator(text, openIndex, "]]>", "CDATA")

        text.startsWith("<?", startIndex = openIndex) ->
            findRequiredSvgMarkupTerminator(text, openIndex, "?>", "processing instruction")

        text.startsWith("<!", startIndex = openIndex) ->
            (findSvgTagEnd(text, openIndex + 2)
                ?: throw IllegalArgumentException("SVG glyph payload contains an unterminated declaration.")) + 1

        else -> null
    }

/**
 * Finds the end of an ignored XML/SVG block and reports a bounded parse error when missing.
 */
private fun findRequiredSvgMarkupTerminator(
    text: String,
    startIndex: Int,
    terminator: String,
    blockName: String,
): Int {
    val endIndex = text.indexOf(terminator, startIndex = startIndex + terminator.length)
    require(endIndex >= 0) {
        "SVG glyph payload contains an unterminated $blockName block."
    }
    return endIndex + terminator.length
}

/**
 * Parses quoted XML-style attributes from a start tag source without the opening `<` or closing
 * `>`.
 */
private fun parseSvgTagAttributes(tagSource: String): Map<String, String> {
    val attributes = LinkedHashMap<String, String>()
    var index = readSvgNameEnd(tagSource, skipSvgWhitespace(tagSource, 0))

    while (index < tagSource.length) {
        index = skipSvgWhitespace(tagSource, index)
        if (index >= tagSource.length || tagSource[index] == '/') break

        val nameStart = index
        val nameEnd = readSvgNameEnd(tagSource, nameStart)
        require(nameEnd > nameStart) {
            "SVG glyph payload contains a malformed attribute name."
        }
        val name = tagSource.substring(nameStart, nameEnd)

        index = skipSvgWhitespace(tagSource, nameEnd)
        require(index < tagSource.length && tagSource[index] == '=') {
            "SVG glyph attribute $name must use a quoted value."
        }
        index = skipSvgWhitespace(tagSource, index + 1)
        require(index < tagSource.length && (tagSource[index] == '"' || tagSource[index] == '\'')) {
            "SVG glyph attribute $name must use a quoted value."
        }

        val quote = tagSource[index]
        val valueStart = index + 1
        val valueEnd = tagSource.indexOf(quote, startIndex = valueStart)
        require(valueEnd >= 0) {
            "SVG glyph attribute $name has an unterminated quoted value."
        }
        require(valueEnd - valueStart <= MAX_SVG_ATTRIBUTE_VALUE_CHARS) {
            "SVG glyph attribute $name exceeds $MAX_SVG_ATTRIBUTE_VALUE_CHARS characters."
        }

        attributes[name] = normalizeSvgAttributeValue(tagSource.substring(valueStart, valueEnd))
        index = valueEnd + 1
    }

    return attributes.toMap()
}

/**
 * Reads and validates the four-number SVG viewBox attribute.
 */
private fun parseSvgViewBox(value: String): List<Float> {
    val parts = value.split(SVG_NUMBER_SEPARATOR)
        .filter { part -> part.isNotEmpty() }
    require(parts.size == 4) {
        "SVG glyph viewBox must contain exactly four numbers."
    }

    val viewBox = parts.map { part ->
        val number = part.toFloatOrNull()
            ?: throw IllegalArgumentException("SVG glyph viewBox contains a non-numeric value.")
        require(!number.isNaN() && !number.isInfinite()) {
            "SVG glyph viewBox values must be finite."
        }
        number
    }
    require(viewBox[2] > 0f && viewBox[3] > 0f) {
        "SVG glyph viewBox width and height must be positive."
    }
    return viewBox.toList()
}

/**
 * Returns an attribute value using case-insensitive SVG attribute-name matching.
 */
private fun attributeValue(attributes: Map<String, String>, name: String): String? =
    attributes.entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value

/**
 * Formats one simple SVG element summary in deterministic key order.
 */
private fun formatSvgElementSummary(name: String, attributes: Map<String, String>): String {
    val summaryAttributes = LinkedHashMap<String, String>()
    attributes.forEach { (key, value) ->
        val canonicalKey = canonicalSvgAttributeName(key)
        if (canonicalKey in SVG_SUMMARY_ATTRIBUTE_NAMES) {
            summaryAttributes.putIfAbsent(canonicalKey, value)
        }
    }

    val body = summaryAttributes.entries
        .sortedBy { (key, _) -> key }
        .joinToString(separator = ",") { (key, value) -> "$key=$value" }
    return "$name{$body}"
}

/**
 * Converts equivalent SVG attribute spellings into summary keys.
 */
private fun canonicalSvgAttributeName(name: String): String =
    when (val lowerName = name.lowercase()) {
        "xlink:href" -> "href"
        else -> lowerName
    }

/**
 * Collapses insignificant whitespace in simple SVG attribute summaries.
 */
private fun normalizeSvgAttributeValue(value: String): String =
    value.trim().replace(SVG_WHITESPACE, " ")

/**
 * Skips whitespace in [text] starting at [index].
 */
private fun skipSvgWhitespace(text: String, index: Int): Int {
    var cursor = index
    while (cursor < text.length && text[cursor].isWhitespace()) {
        cursor += 1
    }
    return cursor
}

/**
 * Reads an XML-style name end offset in [text] starting at [index].
 */
private fun readSvgNameEnd(text: String, index: Int): Int {
    var cursor = index
    while (cursor < text.length && isSvgNameCharacter(text[cursor])) {
        cursor += 1
    }
    return cursor
}

/**
 * Returns true when [character] is accepted by the basic SVG attribute and element scanner.
 */
private fun isSvgNameCharacter(character: Char): Boolean =
    character.isLetterOrDigit() ||
        character == '_' ||
        character == '-' ||
        character == ':' ||
        character == '.'

/**
 * Finds the closing `>` for a start tag while respecting quoted attribute values.
 */
private fun findSvgTagEnd(text: String, index: Int): Int? {
    var quote: Char? = null
    var cursor = index
    while (cursor < text.length) {
        val character = text[cursor]
        when {
            quote != null && character == quote -> quote = null
            quote == null && (character == '"' || character == '\'') -> quote = character
            quote == null && character == '>' -> return cursor
        }
        cursor += 1
    }
    return null
}

/**
 * One parsed SVG start tag used by the basic SVG scanner.
 */
private data class SVGStartTag(
    val name: String,
    val source: String,
)

/**
 * Tracks total COLRv1 paint expansion during one parse call.
 */
private data class COLRV1PaintParseState(
    var expandedPaintCount: Int = 0,
)

private const val U16_SIZE_BYTES = 2
private const val U24_SIZE_BYTES = 3
private const val U32_SIZE_BYTES = 4
private const val CPAL_V0_HEADER_SIZE = 12
private const val CPAL_COLOR_RECORD_SIZE = 4
private const val COLR_V0_HEADER_SIZE = 14
private const val COLR_BASE_RECORD_SIZE = 6
private const val COLR_LAYER_RECORD_SIZE = 4
private const val COLR_V1_HEADER_SIZE = 34
private const val COLR_V1_BASE_GLYPH_LIST_OFFSET = 14
private const val COLR_V1_LAYER_LIST_OFFSET = 18
private const val COLR_V1_CLIP_LIST_OFFSET = 22
private const val COLR_V1_BASE_GLYPH_PAINT_RECORD_SIZE = 6
private const val COLR_V1_CLIP_LIST_HEADER_SIZE = 5
private const val COLR_V1_CLIP_RECORD_SIZE = 7
private const val COLR_V1_CLIP_BOX_FORMAT1_SIZE = 9
private const val COLR_V1_CLIP_BOX_FORMAT2_SIZE = 13
private const val COLR_V1_PAINT_COLR_LAYERS_SIZE = 6
private const val COLR_V1_PAINT_SOLID_SIZE = 5
private const val COLR_V1_PAINT_VAR_SOLID_SIZE = 9
private const val COLR_V1_PAINT_LINEAR_GRADIENT_SIZE = 16
private const val COLR_V1_PAINT_VAR_LINEAR_GRADIENT_SIZE = 20
private const val COLR_V1_PAINT_RADIAL_GRADIENT_SIZE = 16
private const val COLR_V1_PAINT_VAR_RADIAL_GRADIENT_SIZE = 20
private const val COLR_V1_PAINT_SWEEP_GRADIENT_SIZE = 12
private const val COLR_V1_PAINT_VAR_SWEEP_GRADIENT_SIZE = 16
private const val COLR_V1_COLOR_LINE_HEADER_SIZE = 3
private const val COLR_V1_COLOR_STOP_SIZE = 6
private const val COLR_V1_VAR_COLOR_STOP_SIZE = 10
private const val COLR_V1_PAINT_GLYPH_SIZE = 6
private const val COLR_V1_PAINT_COLR_GLYPH_SIZE = 3
private const val COLR_V1_PAINT_TRANSFORM_SIZE = 7
private const val COLR_V1_TRANSFORM_SIZE = 24
private const val COLR_V1_VAR_TRANSFORM_SIZE = 28
private const val COLR_V1_PAINT_TRANSLATE_SIZE = 8
private const val COLR_V1_PAINT_VAR_TRANSLATE_SIZE = 12
private const val COLR_V1_PAINT_COMPOSITE_SIZE = 8
private const val MAX_COLOR_PALETTES = 256
private const val MAX_COLOR_PALETTE_ENTRIES = 4096
private const val MAX_COLOR_RECORDS = 4096
private const val MAX_EXPANDED_COLOR_RECORDS = 65536L
private const val MAX_COLOR_BASE_GLYPHS = 8192
private const val MAX_COLOR_LAYERS = 16384
private const val MAX_LAYERS_PER_COLOR_GLYPH = 256
private const val MAX_COLOR_STOPS = 4096
private const val MAX_EXPANDED_COLOR_LAYERS = 65536L
private const val MAX_COLOR_PAINT_DEPTH = 32
private const val MAX_COLR_V1_EXPANDED_PAINTS = 65536
private const val MAX_PNG_GLYPH_PAYLOAD_BYTES = 16 * 1024 * 1024
private const val MAX_PNG_GLYPH_DIMENSION = 8192
private const val PNG_MIN_HEADER_BYTES = 33
private const val PNG_IHDR_LENGTH_OFFSET = 8
private const val PNG_IHDR_TYPE_OFFSET = 12
private const val PNG_IHDR_WIDTH_OFFSET = 16
private const val PNG_IHDR_HEIGHT_OFFSET = 20
private const val PNG_IHDR_DATA_SIZE = 13
private const val PNG_CHUNK_OVERHEAD_BYTES = 12
private const val PNG_CHUNK_TYPE_BYTES = 4
private const val PNG_CHUNK_IHDR = "IHDR"
private const val PNG_CHUNK_IDAT = "IDAT"
private const val PNG_CHUNK_IEND = "IEND"
private const val PNG_COLOR_TYPE_RGB = 2
private const val PNG_COLOR_TYPE_RGBA = 6
private const val MAX_SVG_GLYPH_TEXT_CHARS = 64 * 1024
private const val MAX_SVG_GLYPH_ELEMENTS = 256
private const val MAX_SVG_ATTRIBUTE_VALUE_CHARS = 4096
private const val MAX_SVG_PATH_COMMANDS = 256
private const val MAX_SVG_GRADIENT_STOPS = 64
private const val MAX_SVG_USE_RECURSION_DEPTH = 8

private val BITMAP_STRIKE_STABLE_ORDER: Comparator<BitmapStrikeSelection> =
    compareBy<BitmapStrikeSelection> { selection -> selection.ppem }
        .thenBy { selection -> selection.width }
        .thenBy { selection -> selection.height }
        .thenBy { selection -> selection.format }

private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4E,
    0x47,
    0x0D,
    0x0A,
    0x1A,
    0x0A,
)

private val SVG_NUMBER_SEPARATOR = Regex("[,\\s]+")
private val SVG_WHITESPACE = Regex("\\s+")
private val SVG_PATH_ATTRIBUTE_PATTERN = Regex("""(?i)<path\b[^>]*\bd\s*=\s*"([^"]*)"""")
private val SVG_PATH_COMMAND_PATTERN = Regex("[AaCcHhLlMmQqSsTtVvZz]")
private val SVG_STOP_ELEMENT_PATTERN = Regex("""(?i)<stop\b""")
private val SVG_SUMMARY_ELEMENT_NAMES = setOf(
    "defs",
    "g",
    "clippath",
    "lineargradient",
    "radialgradient",
    "path",
    "rect",
    "circle",
    "ellipse",
    "line",
    "polyline",
    "polygon",
    "stop",
    "symbol",
    "use",
)
private val SVG_SUMMARY_ATTRIBUTE_NAMES = setOf(
    "clip-path",
    "cx",
    "cy",
    "d",
    "fill",
    "gradientunits",
    "height",
    "href",
    "id",
    "opacity",
    "offset",
    "points",
    "r",
    "rx",
    "ry",
    "stroke",
    "stop-color",
    "stroke-width",
    "transform",
    "viewbox",
    "width",
    "x",
    "x1",
    "x2",
    "y",
    "y1",
    "y2",
)
