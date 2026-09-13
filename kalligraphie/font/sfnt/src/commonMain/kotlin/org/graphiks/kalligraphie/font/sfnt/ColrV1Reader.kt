@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Bounded global COLR v1 indexes; individual paint graphs are decoded only on demand. */
@KalligraphieInternalApi
public object ColrV1Reader {
    /** Checks global indexes and CPAL structure without walking any glyph paint graph. */
    public fun hasStructurallyValidTables(colrTable: ByteArray, cpalTable: ByteArray, glyphCount: Int): Boolean {
        if (CpalPaletteReader.validateStructure(cpalTable) == null) return false
        return colrResult { readIndexes(colrTable, glyphCount, null) } is FontOperationResult.Success
    }

    /** Captures bounded source bytes, global indexes and the selected CPAL 0/1 palette for paint schema 2 or 3. */
    public fun read(
        colrTable: ByteArray,
        cpalTable: ByteArray,
        glyphCount: Int,
        profile: PaintGraphProfile,
        paletteIndex: Int,
        foregroundColor: GlyphColor,
    ): FontOperationResult<ColrV1Data> = colrResult {
        if (profile.schemaVersion !in 2..3) {
            colrUnsupported("COLR version 1 requires paint-graph schema version 2 or 3.")
        }
        val limits = profile.limits
        colrLimit(colrTable.size.toLong() + cpalTable.size, limits.maxSourceBytes, "source bytes")
        val palettes = CpalPaletteReader.read(cpalTable, ColrCpalV0Limits(
            maxPalettes = limits.maxPalettes,
            maxPaletteEntries = limits.maxPaletteEntries,
            maxColorRecords = limits.maxColorRecords,
            maxDecodedPaletteBytes = limits.maxDecodedPaletteBytes,
            maxBaseGlyphRecords = limits.maxBaseGlyphRecords,
            maxLayerRecords = limits.maxLayerRecords,
        )).colrValue()
        val palette = palettes.getOrNull(paletteIndex) ?: colrUnsupported("The selected CPAL palette is unavailable.")
        val indexes = readIndexes(colrTable, glyphCount, limits)
        ColrV1Data(colrTable.copyOf(), indexes, glyphCount, palette.toList(), foregroundColor)
    }
}

/** Immutable source capture for lazy, cancellable static COLR v1 glyph materialization. */
@KalligraphieInternalApi
public class ColrV1Data internal constructor(
    private val table: ByteArray,
    private val indexes: ColrV1Indexes,
    private val glyphCount: Int,
    private val palette: List<GlyphColor>,
    private val foregroundColor: GlyphColor,
) {
    /** Whether this glyph has a version-one base paint record. */
    public fun containsGlyph(glyphId: GlyphId): Boolean = indexes.glyphs.asList().binarySearch(glyphId.value) >= 0

    /** Whether this glyph has legacy layers in the same COLR table. */
    public fun containsLegacyGlyph(glyphId: GlyphId): Boolean = indexes.legacyGlyphs.asList().binarySearch(glyphId.value) >= 0

    /** Resolves one complete schema-2 or schema-3 graph, obtaining glyph clips through the caller's outline materializer. */
    public fun resolveGlyph(
        glyphId: GlyphId,
        profile: PaintGraphProfile,
        cancellationToken: CancellationToken,
        materializeOutline: (GlyphId) -> FontOperationResult<GlyphOutlineIR>,
    ): FontOperationResult<GlyphRepresentation> = colrResult {
        val location = FontDiagnosticLocation.Glyph(glyphId.value)
        if (profile.schemaVersion !in 2..3) {
            colrUnsupported("COLR version 1 requires paint-graph schema version 2 or 3.", location)
        }
        if (glyphId.value !in 0 until glyphCount) colrInvalid("COLR glyph is outside the face.", location)
        val record = indexes.glyphs.asList().binarySearch(glyphId.value)
        if (record < 0) return@colrResult resolveLegacyOrOutline(glyphId, profile, cancellationToken, materializeOutline)
        val reader = ColrBytes(table)
        val clipIndex = indexes.clipStarts.asList().binarySearch(glyphId.value).let { found -> if (found >= 0) found else -found - 2 }
        val clip = if (clipIndex >= 0 && glyphId.value <= indexes.clipEnds[clipIndex]) {
            val offset = indexes.clipOffsets[clipIndex]
            if (reader.u8(offset) != 1) colrUnsupported("Variable or unknown COLR clip boxes are unsupported.", location)
            reader.bounds(offset)
        } else null
        val limits = profile.limits
        val nodes = ArrayList<GlyphPaintNode>()
        val completed = HashMap<Int, Int>()
        val active = HashSet<Int>()
        val frames = ArrayDeque<PaintFrame>()
        var visits = 0L
        var references = 0L
        var gradients = 0L
        var clips = 0L
        var stops = 0L
        var transforms = 0L
        var composites = 0L
        var admittedNodes = 0L
        fun checkCancelled() {
            if (cancellationToken.isCancellationRequested()) throw ColrAbort(FontOperationResult.Cancelled())
        }
        fun limit(value: Long, maximum: Int, dimension: String) = colrLimit(value, maximum, dimension, location)
        fun color(index: Int): GlyphColor = if (index == 0xFFFF) foregroundColor
        else palette.getOrNull(index) ?: colrInvalid("COLR references an unavailable CPAL entry.", location)
        fun colorLine(offset: Int): GlyphPaintColorLine {
            reader.range(offset, 3)
            val mode = when (reader.u8(offset)) {
                1 -> GlyphPaintExtendMode.REPEAT
                2 -> GlyphPaintExtendMode.REFLECT
                else -> GlyphPaintExtendMode.PAD
            }
            if (mode !in profile.acceptedGradientExtendModes) colrUnsupported("The consumer does not accept the COLR gradient extend mode.", location)
            val count = reader.u16(offset + 1)
            stops += count
            limit(stops, limits.maxColorStops, "color stops")
            reader.range(offset + 3, count.toLong() * 6)
            val colors = ArrayList<GlyphPaintColorStop>(count)
            repeat(count) { index ->
                checkCancelled()
                val stop = offset + 3 + index * 6
                colors += GlyphPaintColorStop(reader.f2(stop), color(reader.u16(stop + 2)), reader.opacity(stop + 4))
            }
            return GlyphPaintColorLine(
                mode,
                colors.sortedBy { it.offset },
                GlyphPaintInterpolationSpace.LINEAR_SRGB,
            )
        }
        fun enter(offset: Int, depth: Int) {
            checkCancelled()
            visits += 1
            limit(visits, limits.maxPaintVisits, "source paint visits")
            limit(depth.toLong(), limits.maxDepth, "paint depth")
            if (offset in active) colrInvalid("COLR paint graph contains a cycle.", location)
            if (offset in completed) return
            val format = reader.u8(offset)
            val kind = when (format) {
                1 -> GlyphPaintNodeKind.GROUP
                2 -> GlyphPaintNodeKind.SOLID
                4 -> GlyphPaintNodeKind.LINEAR_GRADIENT
                6 -> GlyphPaintNodeKind.RADIAL_GRADIENT
                8 -> GlyphPaintNodeKind.SWEEP_GRADIENT
                10 -> GlyphPaintNodeKind.GLYPH_CLIP
                11 -> null // A source reference disappears into its autonomous child DAG.
                12, 14, 16, 18, 20, 22, 24, 26, 28, 30 -> GlyphPaintNodeKind.TRANSFORM
                32 -> GlyphPaintNodeKind.COMPOSITE
                else -> colrUnsupported("Unsupported COLR paint format $format.", location)
            }
            if (kind != null) {
                limit(++admittedNodes, limits.maxNodes, "paint nodes")
                if (kind !in profile.acceptedNodeKinds) colrUnsupported("The consumer does not accept the COLR paint node.", location)
            }
            if (format == 1 && GlyphPaintCompositionMode.SOURCE_OVER !in profile.acceptedCompositionModes) {
                colrUnsupported("The consumer does not accept COLR layer composition.", location)
            }
            val children = when (format) {
                1 -> {
                    reader.range(offset, 6)
                    val count = reader.u8(offset + 1)
                    val first = reader.u32(offset + 2)
                    if (first > indexes.layers.size || count.toLong() > indexes.layers.size.toLong() - first) colrInvalid("COLR layer range is invalid.", location)
                    limit(references + count, limits.maxReferences, "paint references")
                    IntArray(count) { indexes.layers[first.toInt() + it] }
                }
                10 -> {
                    reader.range(offset, 6)
                    clips += 1
                    limit(clips, limits.maxClips, "glyph clips")
                    if (reader.u16(offset + 4) !in 0 until glyphCount) colrInvalid("COLR clip references an invalid glyph.", location)
                    intArrayOf(reader.relative(offset, reader.u24(offset + 1)))
                }
                11 -> {
                    reader.range(offset, 3)
                    val referenced = reader.u16(offset + 1)
                    val target = indexes.glyphs.asList().binarySearch(referenced)
                    if (target < 0) colrInvalid("COLR references a missing base paint glyph.", location)
                    intArrayOf(indexes.paints[target])
                }
                12, 14, 16, 18, 20, 22, 24, 26, 28, 30 -> {
                    limit(++transforms, limits.maxTransforms, "transforms")
                    intArrayOf(reader.relative(offset, reader.u24(offset + 1)))
                }
                32 -> {
                    reader.range(offset, 8)
                    limit(++composites, limits.maxComposites, "composites")
                    if (compositeMode(reader.u8(offset + 4)) !in profile.acceptedCompositionModes) {
                        colrUnsupported("The consumer does not accept the COLR composite mode.", location)
                    }
                    // Traverse in paint order: backdrop, then source.
                    intArrayOf(reader.relative(offset, reader.u24(offset + 5)), reader.relative(offset, reader.u24(offset + 1)))
                }
                else -> IntArray(0)
            }
            references += children.size
            limit(references, limits.maxReferences, "paint references")
            if (format == 4 || format == 6 || format == 8) {
                gradients += 1
                limit(gradients, limits.maxGradients, "gradients")
            }
            active += offset
            frames.addLast(PaintFrame(offset, format, depth, children))
        }
        enter(indexes.paints[record], 1)
        while (frames.isNotEmpty()) {
            checkCancelled()
            val frame = frames.last()
            if (frame.nextChild < frame.children.size) {
                enter(frame.children[frame.nextChild++], frame.depth + 1)
                continue
            }
            val offset = frame.offset
            if (frame.format == 11) {
                completed[offset] = completed.getValue(frame.children.single())
                active.remove(offset)
                frames.removeLast()
                continue
            }
            val node: GlyphPaintNode = when (frame.format) {
                1 -> GlyphPaintNode.Group(frame.children.map { completed.getValue(it) })
                2 -> {
                    reader.range(offset, 5)
                    GlyphPaintNode.Solid(color(reader.u16(offset + 1)), reader.opacity(offset + 3))
                }
                4 -> {
                    reader.range(offset, 16)
                    val p0 = reader.point(offset + 4)
                    val p1 = reader.point(offset + 8)
                    val p2 = reader.point(offset + 12)
                    val cross = (p1.x - p0.x) * (p2.y - p0.y) - (p1.y - p0.y) * (p2.x - p0.x)
                    if (cross == 0.0) colrInvalid("COLR linear gradient geometry is degenerate.", location)
                    GlyphPaintNode.LinearGradient(colorLine(reader.relative(offset, reader.u24(offset + 1))), p0, p1, p2)
                }
                6 -> {
                    reader.range(offset, 16)
                    GlyphPaintNode.RadialGradient(colorLine(reader.relative(offset, reader.u24(offset + 1))), reader.point(offset + 4), reader.u16(offset + 8).toDouble(), reader.point(offset + 10), reader.u16(offset + 14).toDouble())
                }
                8 -> {
                    reader.range(offset, 12)
                    val start = (reader.f2(offset + 8) + 1.0) * 180.0
                    val end = (reader.f2(offset + 10) + 1.0) * 180.0
                    GlyphPaintNode.SweepGradient(colorLine(reader.relative(offset, reader.u24(offset + 1))), reader.point(offset + 4), start, end)
                }
                10 -> {
                    checkCancelled()
                    val outline = materializeOutline(GlyphId(reader.u16(offset + 4))).colrValue()
                    checkCancelled()
                    GlyphPaintNode.GlyphClip(outline, completed.getValue(frame.children.single()))
                }
                32 -> GlyphPaintNode.Composite(
                    source = completed.getValue(frame.children[1]),
                    backdrop = completed.getValue(frame.children[0]),
                    mode = compositeMode(reader.u8(offset + 4)),
                )
                else -> GlyphPaintNode.Transform(completed.getValue(frame.children.single()), reader.transform(offset, frame.format))
            }
            completed[offset] = nodes.size
            nodes += node
            active.remove(offset)
            frames.removeLast()
        }
        val paint = GlyphPaintIR(
            schemaVersion = profile.schemaVersion,
            rootNode = completed.getValue(indexes.paints[record]),
            nodes = nodes,
            clipBounds = clip,
        )
        // Account for expanded paths through shared nodes, independently of memoized source decoding.
        val pending = ArrayDeque<Pair<Int, Int>>()
        pending.addLast(paint.rootNode to 1)
        var expandedVisits = 0L
        var expandedClips = 0L
        var expandedGradients = 0L
        var expandedStops = 0L
        var expandedTransforms = 0L
        var expandedComposites = 0L
        while (pending.isNotEmpty()) {
            checkCancelled()
            val (index, depth) = pending.removeLast()
            limit(++expandedVisits, limits.maxPaintVisits, "paint visits")
            limit(depth.toLong(), limits.maxDepth, "paint depth")
            val node = nodes[index]
            val line = when (node) {
                is GlyphPaintNode.LinearGradient -> node.colorLine
                is GlyphPaintNode.RadialGradient -> node.colorLine
                is GlyphPaintNode.SweepGradient -> node.colorLine
                else -> null
            }
            if (line != null) {
                limit(++expandedGradients, limits.maxGradients, "gradients")
                expandedStops += line.colorStops.size
                limit(expandedStops, limits.maxColorStops, "color stops")
            }
            if (node is GlyphPaintNode.GlyphClip) limit(++expandedClips, limits.maxClips, "glyph clips")
            if (node is GlyphPaintNode.Transform) limit(++expandedTransforms, limits.maxTransforms, "transforms")
            if (node is GlyphPaintNode.Composite) limit(++expandedComposites, limits.maxComposites, "composites")
            limit(expandedVisits + pending.size + node.children.size, limits.maxPaintVisits, "paint visits")
            node.children.forEach { pending.addLast(it to depth + 1) }
        }
        checkCancelled()
        if (!profile.accepts(paint)) colrUnsupported("The complete COLR graph is not bounded or accepted by the consumer.", location)
        GlyphRepresentation.Paint(paint)
    }

    private fun resolveLegacyOrOutline(
        glyphId: GlyphId,
        profile: PaintGraphProfile,
        cancellationToken: CancellationToken,
        materializeOutline: (GlyphId) -> FontOperationResult<GlyphOutlineIR>,
    ): GlyphRepresentation {
        val location = FontDiagnosticLocation.Glyph(glyphId.value)
        val reader = ColrBytes(table)
        val record = indexes.legacyGlyphs.asList().binarySearch(glyphId.value)
        val count = if (record >= 0) indexes.legacyCounts[record] else 1
        val limits = profile.limits
        colrLimit(count.toLong() + if (count > 1) 1 else 0, limits.maxNodes, "paint nodes", location)
        colrLimit(count.toLong(), limits.maxPaths, "outline paths", location)
        colrLimit(if (count > 1) count.toLong() else 0, limits.maxReferences, "paint references", location)
        colrLimit(if (count > 1) 2 else 1, limits.maxDepth, "paint depth", location)
        colrLimit(count.toLong() + if (count > 1) 1 else 0, limits.maxPaintVisits, "paint visits", location)
        if (GlyphPaintNodeKind.SOLID_OUTLINE !in profile.acceptedNodeKinds ||
            (count > 1 && (GlyphPaintNodeKind.GROUP !in profile.acceptedNodeKinds ||
                GlyphPaintCompositionMode.SOURCE_OVER !in profile.acceptedCompositionModes))) {
            colrUnsupported("The consumer does not accept the COLR legacy or outline fallback.", location)
        }
        val nodes = ArrayList<GlyphPaintNode>()
        repeat(count) { index ->
            if (cancellationToken.isCancellationRequested()) throw ColrAbort(FontOperationResult.Cancelled())
            val layer = if (record >= 0) indexes.legacyLayerOffset + (indexes.legacyFirsts[record] + index) * 4 else 0
            val target = if (record >= 0) reader.u16(layer) else glyphId.value
            if (target !in 0 until glyphCount) colrInvalid("COLR legacy layer references an invalid glyph.", location)
            val paletteIndex = if (record >= 0) reader.u16(layer + 2) else 0xFFFF
            val color = if (record < 0) GlyphColor(0, 0, 0)
                else if (paletteIndex == 0xFFFF) foregroundColor else palette.getOrNull(paletteIndex)
                ?: colrInvalid("COLR legacy layer references an unavailable CPAL entry.", location)
            val outline = materializeOutline(GlyphId(target)).colrValue()
            if (cancellationToken.isCancellationRequested()) throw ColrAbort(FontOperationResult.Cancelled())
            if (outline.pointCount > 0) nodes += GlyphPaintNode.SolidOutline(outline, color)
        }
        if (nodes.isEmpty()) return GlyphRepresentation.Empty
        if (nodes.size > 1) nodes += GlyphPaintNode.Group(nodes.indices.toList())
        val paint = GlyphPaintIR(schemaVersion = profile.schemaVersion, rootNode = nodes.lastIndex, nodes = nodes)
        if (!profile.accepts(paint)) colrUnsupported("The complete COLR fallback is not accepted by the consumer.", location)
        return GlyphRepresentation.Paint(paint)
    }
}

internal class ColrV1Indexes(
    val glyphs: IntArray,
    val paints: IntArray,
    val layers: IntArray,
    val clipStarts: IntArray,
    val clipEnds: IntArray,
    val clipOffsets: IntArray,
    val legacyGlyphs: IntArray,
    val legacyFirsts: IntArray,
    val legacyCounts: IntArray,
    val legacyLayerOffset: Int,
)

private fun readIndexes(table: ByteArray, glyphCount: Int, limits: PaintGraphLimits?): ColrV1Indexes {
    val reader = ColrBytes(table)
    reader.range(0, 34)
    if (reader.u16(0) != 1) colrUnsupported("Only COLR version 1 is supported by this reader.")
    if (glyphCount <= 0) colrInvalid("COLR glyph count must be positive.")
    // The legacy header remains structurally bounded even when no legacy glyph is requested.
    reader.range(reader.u32(4), reader.u16(2).toLong() * 6)
    reader.range(reader.u32(8), reader.u16(12).toLong() * 4)
    val legacyCount = reader.u16(2)
    val legacyLayerCount = reader.u16(12)
    colrLimit(legacyCount.toLong(), limits?.maxBaseGlyphRecords ?: 65_536, "legacy base glyph records")
    colrLimit(legacyLayerCount.toLong(), limits?.maxLayerRecords ?: 65_536, "legacy layer records")
    val legacyGlyphs = IntArray(legacyCount)
    val legacyFirsts = IntArray(legacyCount)
    val legacyCounts = IntArray(legacyCount)
    repeat(legacyCount) { index ->
        val offset = reader.u32(4).toInt() + index * 6
        val glyph = reader.u16(offset)
        val first = reader.u16(offset + 2)
        val count = reader.u16(offset + 4)
        if (glyph >= glyphCount || (index > 0 && glyph <= legacyGlyphs[index - 1]) ||
            count == 0 || first > legacyLayerCount || count > legacyLayerCount - first) {
            colrInvalid("COLR legacy base glyph record is invalid.")
        }
        legacyGlyphs[index] = glyph
        legacyFirsts[index] = first
        legacyCounts[index] = count
    }
    for (field in listOf(26, 30)) {
        val offset = reader.u32(field)
        if (offset != 0L) reader.range(offset, 1)
    }
    val base = reader.relative(0, reader.u32(14))
    val count = reader.u32(base)
    colrLimit(count, limits?.maxBaseGlyphRecords ?: 65_536, "base glyph records")
    reader.range(base.toLong() + 4, count * 6)
    val glyphs = IntArray(count.toInt())
    val paints = IntArray(count.toInt())
    repeat(count.toInt()) { index ->
        val record = base + 4 + index * 6
        val glyph = reader.u16(record)
        if (glyph !in 0 until glyphCount || (index > 0 && glyph <= glyphs[index - 1])) colrInvalid("COLR base glyph records must be valid and strictly sorted.")
        glyphs[index] = glyph
        paints[index] = reader.relative(base, reader.u32(record + 2))
    }
    val layerOffset = reader.u32(18)
    val layers = if (layerOffset == 0L) IntArray(0) else {
        val baseLayer = reader.relative(0, layerOffset)
        val layerCount = reader.u32(baseLayer)
        colrLimit(layerCount, limits?.maxLayerRecords ?: 65_536, "layer records")
        reader.range(baseLayer.toLong() + 4, layerCount * 4)
        IntArray(layerCount.toInt()) { reader.relative(baseLayer, reader.u32(baseLayer + 4 + it * 4)) }
    }
    val clipOffset = reader.u32(22)
    if (clipOffset == 0L) return ColrV1Indexes(glyphs, paints, layers, IntArray(0), IntArray(0), IntArray(0), legacyGlyphs, legacyFirsts, legacyCounts, reader.u32(8).toInt())
    val clipBase = reader.relative(0, clipOffset)
    if (reader.u8(clipBase) != 1) colrInvalid("COLR ClipList format is invalid.")
    val clipCount = reader.u32(clipBase + 1)
    colrLimit(clipCount, limits?.maxClipRecords ?: 65_536, "clip records")
    reader.range(clipBase.toLong() + 5, clipCount * 7)
    val starts = IntArray(clipCount.toInt())
    val ends = IntArray(clipCount.toInt())
    val offsets = IntArray(clipCount.toInt())
    repeat(clipCount.toInt()) { index ->
        val record = clipBase + 5 + index * 7
        val start = reader.u16(record)
        val end = reader.u16(record + 2)
        if (start > end || end >= glyphCount || (index > 0 && start <= ends[index - 1])) colrInvalid("COLR clip ranges must be valid, sorted and disjoint.")
        val offset = reader.relative(clipBase, reader.u24(record + 4))
        when (reader.u8(offset)) {
            1 -> reader.bounds(offset)
            2 -> reader.range(offset, 13)
            else -> colrInvalid("COLR ClipBox format is invalid.")
        }
        starts[index] = start
        ends[index] = end
        offsets[index] = offset
    }
    return ColrV1Indexes(glyphs, paints, layers, starts, ends, offsets, legacyGlyphs, legacyFirsts, legacyCounts, reader.u32(8).toInt())
}

private class PaintFrame(val offset: Int, val format: Int, val depth: Int, val children: IntArray, var nextChild: Int = 0)

private class ColrBytes(private val bytes: ByteArray) {
    fun range(offset: Long, length: Long) {
        if (offset < 0 || length < 0 || offset > bytes.size.toLong() || length > bytes.size.toLong() - offset) colrInvalid("COLR table range is truncated or out of bounds.")
    }
    fun range(offset: Int, length: Long) = range(offset.toLong(), length)
    fun u8(offset: Int): Int { range(offset, 1); return bytes[offset].toInt() and 255 }
    fun u16(offset: Int): Int { range(offset, 2); return (u8(offset) shl 8) or u8(offset + 1) }
    fun s16(offset: Int): Int = u16(offset).toShort().toInt()
    fun u24(offset: Int): Long { range(offset, 3); return (u8(offset).toLong() shl 16) or u16(offset + 1).toLong() }
    fun u32(offset: Int): Long { range(offset, 4); return (u16(offset).toLong() shl 16) or u16(offset + 2).toLong() }
    fun relative(base: Int, delta: Long): Int {
        if (delta == 0L) colrInvalid("COLR required offset is null.")
        val offset = base.toLong() + delta
        range(offset, 1)
        return offset.toInt()
    }
    fun f2(offset: Int): Double = s16(offset) / 16384.0
    fun fixed(offset: Int): Double = u32(offset).toInt() / 65536.0
    fun transform(offset: Int, format: Int): GlyphAffineTransform {
        var xx = 1.0
        var yx = 0.0
        var xy = 0.0
        var yy = 1.0
        var dx = 0.0
        var dy = 0.0
        var centerOffset: Int? = null
        when (format) {
            12 -> {
                val matrix = relative(offset, u24(offset + 4))
                range(matrix, 24)
                xx = fixed(matrix); yx = fixed(matrix + 4); xy = fixed(matrix + 8)
                yy = fixed(matrix + 12); dx = fixed(matrix + 16); dy = fixed(matrix + 20)
            }
            14 -> { dx = s16(offset + 4).toDouble(); dy = s16(offset + 6).toDouble() }
            16, 18 -> {
                xx = f2(offset + 4); yy = f2(offset + 6)
                if (format == 18) centerOffset = offset + 8
            }
            20, 22 -> {
                xx = f2(offset + 4); yy = xx
                if (format == 22) centerOffset = offset + 6
            }
            24, 26 -> {
                val angle = f2(offset + 4)
                // Exact quarter-turns must not acquire floating-point residual translations.
                val quarterTurns = angle * 2.0
                if (quarterTurns == quarterTurns.toInt().toDouble()) {
                    val quarter = ((quarterTurns.toInt() % 4) + 4) % 4
                    xx = when (quarter) { 0 -> 1.0; 2 -> -1.0; else -> 0.0 }
                    yx = when (quarter) { 1 -> 1.0; 3 -> -1.0; else -> 0.0 }
                } else { xx = cos(angle * PI); yx = sin(angle * PI) }
                xy = -yx; yy = xx
                if (format == 26) centerOffset = offset + 6
            }
            28, 30 -> {
                val xAngle = f2(offset + 4)
                val yAngle = f2(offset + 6)
                if (xAngle % 1.0 == 0.5 || xAngle % 1.0 == -0.5 || yAngle % 1.0 == 0.5 || yAngle % 1.0 == -0.5) {
                    colrInvalid("COLR skew has a non-finite tangent.")
                }
                xy = -tan(xAngle * PI); yx = tan(yAngle * PI)
                if (format == 30) centerOffset = offset + 8
            }
        }
        centerOffset?.let {
            val cx = s16(it).toDouble()
            val cy = s16(it + 2).toDouble()
            dx = cx - xx * cx - xy * cy
            dy = cy - yx * cx - yy * cy
        }
        fun finite(value: Double): Double {
            if (!value.isFinite()) colrInvalid("COLR transform coefficient is non-finite.")
            return if (value == 0.0) 0.0 else value
        }
        return GlyphAffineTransform(finite(xx), finite(yx), finite(xy), finite(yy), finite(dx), finite(dy))
    }
    fun opacity(offset: Int): Double = f2(offset).coerceIn(0.0, 1.0)
    fun point(offset: Int): GlyphPaintPoint = GlyphPaintPoint(s16(offset).toDouble(), s16(offset + 2).toDouble())
    fun bounds(offset: Int): DesignBounds {
        range(offset, 9)
        val result = DesignBounds(s16(offset + 1), s16(offset + 3), s16(offset + 5), s16(offset + 7))
        if (result.minX > result.maxX || result.minY > result.maxY) colrInvalid("COLR clip bounds are reversed.")
        return result
    }
}

private fun compositeMode(value: Int): GlyphPaintCompositionMode = when (value) {
    0 -> GlyphPaintCompositionMode.CLEAR
    1 -> GlyphPaintCompositionMode.SOURCE
    2 -> GlyphPaintCompositionMode.DESTINATION
    3 -> GlyphPaintCompositionMode.SOURCE_OVER
    4 -> GlyphPaintCompositionMode.DESTINATION_OVER
    5 -> GlyphPaintCompositionMode.SOURCE_IN
    6 -> GlyphPaintCompositionMode.DESTINATION_IN
    7 -> GlyphPaintCompositionMode.SOURCE_OUT
    8 -> GlyphPaintCompositionMode.DESTINATION_OUT
    9 -> GlyphPaintCompositionMode.SOURCE_ATOP
    10 -> GlyphPaintCompositionMode.DESTINATION_ATOP
    11 -> GlyphPaintCompositionMode.XOR
    12 -> GlyphPaintCompositionMode.PLUS
    13 -> GlyphPaintCompositionMode.SCREEN
    14 -> GlyphPaintCompositionMode.OVERLAY
    15 -> GlyphPaintCompositionMode.DARKEN
    16 -> GlyphPaintCompositionMode.LIGHTEN
    17 -> GlyphPaintCompositionMode.COLOR_DODGE
    18 -> GlyphPaintCompositionMode.COLOR_BURN
    19 -> GlyphPaintCompositionMode.HARD_LIGHT
    20 -> GlyphPaintCompositionMode.SOFT_LIGHT
    21 -> GlyphPaintCompositionMode.DIFFERENCE
    22 -> GlyphPaintCompositionMode.EXCLUSION
    23 -> GlyphPaintCompositionMode.MULTIPLY
    24 -> GlyphPaintCompositionMode.HSL_HUE
    25 -> GlyphPaintCompositionMode.HSL_SATURATION
    26 -> GlyphPaintCompositionMode.HSL_COLOR
    27 -> GlyphPaintCompositionMode.HSL_LUMINOSITY
    else -> GlyphPaintCompositionMode.CLEAR
}

private class ColrAbort(val result: FontOperationResult<Nothing>) : RuntimeException()
private inline fun <T> colrResult(block: () -> T): FontOperationResult<T> = try {
    FontOperationResult.Success(block())
} catch (abort: ColrAbort) {
    abort.result
}
private fun <T> FontOperationResult<T>.colrValue(): T = when (this) {
    is FontOperationResult.Success -> value
    is FontOperationResult.Failure -> throw ColrAbort(this)
    is FontOperationResult.Cancelled -> throw ColrAbort(this)
}
private fun colrInvalid(message: String, location: FontDiagnosticLocation = FontDiagnosticLocation.Table("COLR")): Nothing =
    throw ColrAbort(FontOperationResult.Failure(FontError.InvalidFontData(message, location)))
private fun colrUnsupported(message: String, location: FontDiagnosticLocation = FontDiagnosticLocation.Table("COLR")): Nothing =
    throw ColrAbort(FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile(message, location)))
private fun colrLimit(value: Long, maximum: Int, dimension: String, location: FontDiagnosticLocation = FontDiagnosticLocation.Table("COLR")) {
    if (value > maximum) throw ColrAbort(FontOperationResult.Failure(FontError.ResourceLimitExceeded("COLR $dimension limit exceeded.", location)))
}
