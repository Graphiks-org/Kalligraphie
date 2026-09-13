@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintAlphaInterpolationMode
import org.graphiks.kalligraphie.api.GlyphPaintColorLine
import org.graphiks.kalligraphie.api.GlyphPaintColorStop
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintInterpolationSpace
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphPaintPoint
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.PaintGraphProfile
import kotlin.text.CharacterCodingException

/**
 * One fully normalized SVG-in-OpenType paint result.
 *
 * A result contains only portable paint data. It never exposes the SVG document, an XML object,
 * a URI, or a renderer resource.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public sealed interface SvgGlyphPaint {
    /** The SVG document is present but has no paintable ink in the supported subset. */
    public data object Empty : SvgGlyphPaint

    /** A complete profile-compatible portable paint graph. */
    public data class Paint(
        /** Normalized graph ready for a portable consumer. */
        public val paint: org.graphiks.kalligraphie.api.GlyphPaintIR,
    ) : SvgGlyphPaint
}

/**
 * Immutable, profile-certified SVG-in-OpenType data for one TrueType face.
 *
 * The value owns no source bytes. It can safely outlive parsing and be shared by detached render
 * assets. [glyphPaint] returns `null` when no SVG document targets the requested glyph and an
 * explicit [SvgGlyphPaint.Empty] when that document has no ink.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class SvgOpenTypeData internal constructor(
    records: List<SvgOpenTypeDocumentRecord>,
) {
    private val records: List<SvgOpenTypeDocumentRecord> = records.toList()

    /**
     * Returns the normalized result for [glyphId], or `null` when no SVG document targets it.
     *
     * This operation is read-only, deterministic, and safe for concurrent calls.
     */
    public fun glyphPaint(glyphId: GlyphId): SvgGlyphPaint? =
        records.firstNotNullOfOrNull { record -> record.glyphPaints[glyphId.value] }
}

/**
 * Decodes the deliberately small, safe SVG-in-OpenType subset implemented by Kalligraphie.
 *
 * Only SVG table version 0 with raw UTF-8 or gzip-encoded UTF-8 documents is accepted. Every glyph in a document record
 * is targeted exactly once by either the root `svg` or a descendant `g` whose `id` is exactly
 * `glyph<N>`. Supported documents contain `svg`, `g`, `defs`, `linearGradient`, self-closing
 * `stop`, `path`, and `rect` elements. Groups may contain `translate` and `scale` transforms;
 * paths may use `M`, `L`, `H`, `V`, `C`, `S`, and `Z` commands (and their relative forms).
 * Shapes accept solid `#RRGGBB`, `fill="none"`, or a preceding local linear-gradient reference
 * for rectangles. Object-bounding-box linear gradients normalize to schema-3 portable paints.
 * Scripts, network or external references, entities, animation, XML declarations, strokes,
 * masks, and every unlisted element or attribute are rejected before any [SvgOpenTypeData] is
 * returned.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object SvgOpenTypeReader {
    /**
     * Decodes and validates every document in an OpenType `SVG ` table.
     *
     * [profile] bounds source bytes, document records, transform operations, graph nodes, paths,
     * depth, and path geometry before publication. The operation is all-or-nothing: malformed or
     * unsupported content returns a typed failure and no partial data. The returned value contains
     * no source XML and is safe to retain after the caller releases the source buffer.
     *
     * @param svgTable exact bytes of the OpenType `SVG ` table.
     * @param glyphCount glyph count from the owning TrueType face.
     * @param profile complete consumer capability and resource limits.
     * @return fully normalized portable data, or a typed invalid-data, unsupported, or limit error.
     */
    public fun read(
        svgTable: ByteArray,
        glyphCount: Int,
        profile: PaintGraphProfile,
    ): FontOperationResult<SvgOpenTypeData> = readSelected(svgTable, glyphCount, profile, null)

    /**
     * Validates the complete bounded SVG source/index without normalizing glyph payloads.
     *
     * Mixed color routes defer payload capabilities and graph limits until a covered glyph is
     * requested. [svgTable], [glyphCount] and [profile] have the same meaning as in [read].
     */
    public fun validateIndex(
        svgTable: ByteArray,
        glyphCount: Int,
        profile: PaintGraphProfile,
    ): FontOperationResult<Unit> = when (val result = readSelected(svgTable, glyphCount, profile, -1)) {
        is FontOperationResult.Success -> FontOperationResult.Success(Unit, result.diagnostics)
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    /**
     * Normalizes only the document covering [glyphId], preserving whole-document validation.
     *
     * All source bytes/index records remain bounded and validated; unrelated documents cannot
     * reject this glyph's payload capability. [svgTable], [glyphCount] and [profile] are as in
     * [read]. A successful null result means no SVG record covers the requested glyph.
     */
    public fun readGlyph(
        svgTable: ByteArray,
        glyphCount: Int,
        profile: PaintGraphProfile,
        glyphId: GlyphId,
    ): FontOperationResult<SvgGlyphPaint?> = when (val result = readSelected(svgTable, glyphCount, profile, glyphId.value)) {
        is FontOperationResult.Success -> FontOperationResult.Success(result.value.glyphPaint(glyphId), result.diagnostics)
        is FontOperationResult.Failure -> result
        is FontOperationResult.Cancelled -> result
    }

    private fun readSelected(
        svgTable: ByteArray,
        glyphCount: Int,
        profile: PaintGraphProfile,
        selectedGlyphId: Int?,
    ): FontOperationResult<SvgOpenTypeData> {
        if (glyphCount <= 0) return invalid("font.svg.invalid-glyph-count", "SVG glyph count must be positive.")
        if (svgTable.size > profile.limits.maxSourceBytes) {
            return limit("SVG source-byte limit exceeded.")
        }
        if (svgTable.size < SVG_HEADER_LENGTH) return invalid("font.svg.truncated", "SVG table header is truncated.")
        if (readUInt16(svgTable, 0)?.toInt() != SVG_TABLE_VERSION) {
            return invalid("font.svg.unsupported-version", "Only SVG table version 0 is supported.")
        }
        val documentListOffset = readUInt32(svgTable, 2)?.toLong()
            ?: return invalid("font.svg.truncated", "SVG document-list offset is truncated.")
        if (documentListOffset < SVG_HEADER_LENGTH || documentListOffset > Int.MAX_VALUE ||
            checkedRangeEnd(documentListOffset, 2L, svgTable.size) == null
        ) {
            return invalid("font.svg.invalid-document-list-offset", "SVG document-list offset is outside the table body.")
        }
        if (readUInt32(svgTable, 6) != 0u) {
            return invalid("font.svg.invalid-reserved", "SVG table reserved field must be zero.")
        }
        val documentListStart = documentListOffset.toInt()
        val documentCount = readUInt16(svgTable, documentListStart)?.toInt()
            ?: return invalid("font.svg.truncated", "SVG document-list count is truncated.")
        if (documentCount == 0) return invalid("font.svg.empty-document-list", "SVG document-list must not be empty.")
        if (documentCount > profile.limits.maxSvgDocuments) return limit("SVG document-record limit exceeded.")
        val recordsStart = documentListStart + DOCUMENT_LIST_HEADER_LENGTH
        if (checkedRangeEnd(recordsStart, documentCount * DOCUMENT_RECORD_LENGTH, svgTable.size) == null) {
            return invalid("font.svg.truncated", "SVG document records are truncated.")
        }

        val records = ArrayList<SvgOpenTypeDocumentRecord>(documentCount)
        var previousLastGlyphId = -1
        var cumulativeEncodedDocumentBytes = 0L
        var cumulativeDecodedDocumentBytes = 0L
        val transformBudget = SvgTransformBudget(profile.limits.maxSvgTransformOperations)
        repeat(documentCount) { recordIndex ->
            val offset = recordsStart + recordIndex * DOCUMENT_RECORD_LENGTH
            val firstGlyphId = readUInt16(svgTable, offset)?.toInt()
                ?: return invalid("font.svg.truncated", "SVG first glyph ID is truncated.")
            val lastGlyphId = readUInt16(svgTable, offset + 2)?.toInt()
                ?: return invalid("font.svg.truncated", "SVG last glyph ID is truncated.")
            val documentOffset = readUInt32(svgTable, offset + 4)?.toLong()
                ?: return invalid("font.svg.truncated", "SVG document offset is truncated.")
            val documentLength = readUInt32(svgTable, offset + 8)?.toLong()
                ?: return invalid("font.svg.truncated", "SVG document length is truncated.")
            if (firstGlyphId > lastGlyphId || lastGlyphId >= glyphCount || firstGlyphId <= previousLastGlyphId) {
                return invalid("font.svg.invalid-glyph-range", "SVG document glyph ranges must be sorted, disjoint, and owned by the face.")
            }
            val sourceOffset = documentListOffset + documentOffset
            val sourceEnd = checkedRangeEnd(sourceOffset, documentLength, svgTable.size)
                ?: return invalid("font.svg.invalid-document-range", "SVG document bytes exceed the table.")
            if (documentLength == 0L) return invalid("font.svg.empty-document", "SVG document bytes must not be empty.")
            if (documentLength > profile.limits.maxSourceBytes.toLong() - cumulativeEncodedDocumentBytes) {
                return limit("SVG cumulative document-byte limit exceeded.")
            }
            cumulativeEncodedDocumentBytes += documentLength
            previousLastGlyphId = lastGlyphId
            if (selectedGlyphId != null && selectedGlyphId !in firstGlyphId..lastGlyphId) return@repeat
            val encodedDocument = svgTable.copyOfRange(sourceOffset.toInt(), sourceEnd)
            val document = when (
                val result = decodeSvgDocument(
                    encoded = encodedDocument,
                    limits = profile.limits,
                    remainingTotalDecodedBytes = profile.limits.maxSvgTotalDecodedBytes.toLong() - cumulativeDecodedDocumentBytes,
                )
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            cumulativeDecodedDocumentBytes += document.size.toLong()
            val xml = try {
                document.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                return invalid("font.svg.invalid-utf8", "SVG document is not valid UTF-8.")
            }
            val parsed = when (val result = SvgDocumentParser(profile, transformBudget).parse(xml)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            records += SvgOpenTypeDocumentRecord(firstGlyphId, lastGlyphId, parsed.glyphPaints)
            previousLastGlyphId = lastGlyphId
        }
        if (records.any { record ->
                val expectedGlyphCount = record.lastGlyphId - record.firstGlyphId + 1
                record.glyphPaints.size != expectedGlyphCount ||
                    record.glyphPaints.keys.any { glyphId -> glyphId !in record.firstGlyphId..record.lastGlyphId }
            }
        ) {
            return invalid(
                "font.svg.incomplete-glyph-targets",
                "Each SVG document record must declare exactly one compatible glyph<N> target for every covered glyph.",
            )
        }
        return FontOperationResult.Success(SvgOpenTypeData(records))
    }

    /**
     * Returns whether the table has a structurally valid version-0 envelope.
     *
     * Catalogues use this check before advertising an SVG paint route. It validates record ranges
     * and glyph ownership without inspecting document contents. Transport, integrity, UTF-8,
     * markup, and exact caller limits are certified by [read] while acquiring the render asset.
     */
    public fun hasStructurallyValidVersionZeroTable(svgTable: ByteArray, glyphCount: Int): Boolean {
        if (glyphCount <= 0 || svgTable.size < SVG_HEADER_LENGTH) return false
        if (readUInt16(svgTable, 0)?.toInt() != SVG_TABLE_VERSION || readUInt32(svgTable, 6) != 0u) return false
        val documentListOffset = readUInt32(svgTable, 2)?.toLong() ?: return false
        if (
            documentListOffset < SVG_HEADER_LENGTH ||
            documentListOffset > Int.MAX_VALUE ||
            checkedRangeEnd(documentListOffset, DOCUMENT_LIST_HEADER_LENGTH.toLong(), svgTable.size) == null
        ) {
            return false
        }
        val documentListStart = documentListOffset.toInt()
        val documentCount = readUInt16(svgTable, documentListStart)?.toInt() ?: return false
        if (documentCount == 0) return false
        val recordsStart = documentListStart + DOCUMENT_LIST_HEADER_LENGTH
        if (checkedRangeEnd(recordsStart, documentCount * DOCUMENT_RECORD_LENGTH, svgTable.size) == null) return false

        var previousLastGlyphId = -1
        repeat(documentCount) { recordIndex ->
            val offset = recordsStart + recordIndex * DOCUMENT_RECORD_LENGTH
            val firstGlyphId = readUInt16(svgTable, offset)?.toInt() ?: return false
            val lastGlyphId = readUInt16(svgTable, offset + 2)?.toInt() ?: return false
            val documentOffset = readUInt32(svgTable, offset + 4)?.toLong() ?: return false
            val documentLength = readUInt32(svgTable, offset + 8)?.toLong() ?: return false
            if (
                firstGlyphId > lastGlyphId ||
                lastGlyphId >= glyphCount ||
                firstGlyphId <= previousLastGlyphId ||
                documentLength == 0L
            ) {
                return false
            }
            val sourceOffset = documentListOffset + documentOffset
            if (checkedRangeEnd(sourceOffset, documentLength, svgTable.size) == null) return false
            previousLastGlyphId = lastGlyphId
        }
        return true
    }
}

internal data class SvgOpenTypeDocumentRecord(
    val firstGlyphId: Int,
    val lastGlyphId: Int,
    val glyphPaints: Map<Int, SvgGlyphPaint>,
)

private data class ParsedSvgDocument(
    val glyphPaints: Map<Int, SvgGlyphPaint>,
)

private class SvgDocumentParser(
    private val profile: PaintGraphProfile,
    private val transformBudget: SvgTransformBudget,
) {
    private val glyphs = linkedMapOf<Int, SvgGlyphPaintBuilder>()
    private val unassignedPaint = SvgGlyphPaintBuilder()
    private val gradients = linkedMapOf<String, SvgLinearGradient>()
    private val elementIds = mutableSetOf<String>()
    private var parsedGradientCount: Int = 0
    private var parsedColorStopCount: Int = 0
    private var pendingGradient: SvgLinearGradientBuilder? = null

    fun parse(xml: String): FontOperationResult<ParsedSvgDocument> {
        if (xml.contains("<!") || xml.contains("<?") || xml.contains('&')) {
            return unsupported("SVG declarations, entities, and processing instructions are not supported.")
        }
        val stack = ArrayDeque<SvgElement>()
        var rootSeen = false
        var cursor = 0
        while (cursor < xml.length) {
            val nextTag = xml.indexOf('<', cursor)
            if (nextTag < 0) {
                if (xml.substring(cursor).isNotBlank()) return invalid("font.svg.text-content", "SVG text content is not supported.")
                break
            }
            if (xml.substring(cursor, nextTag).isNotBlank()) return invalid("font.svg.text-content", "SVG text content is not supported.")
            val endTag = xml.findTagEnd(nextTag + 1) ?: return invalid("font.svg.truncated-xml", "SVG markup is truncated.")
            val token = xml.substring(nextTag + 1, endTag).trim()
            cursor = endTag + 1
            if (token.isEmpty()) return invalid("font.svg.invalid-element", "SVG contains an empty element.")
            if (token.startsWith('/')) {
                val name = token.drop(1).trim()
                if (name !in CONTAINER_ELEMENTS || stack.lastOrNull()?.name != name) {
                    return invalid("font.svg.invalid-close", "SVG element nesting is invalid.")
                }
                if (name == "linearGradient") {
                    val definition = pendingGradient?.build()
                        ?: return invalid("font.svg.invalid-gradient", "SVG linear-gradient state is invalid.")
                    gradients[definition.id] = definition
                    pendingGradient = null
                }
                stack.removeLast()
                continue
            }
            val selfClosing = token.endsWith('/')
            val content = if (selfClosing) token.dropLast(1).trimEnd() else token
            val nameEnd = content.indexOfFirst { character -> character.isWhitespace() }.let { index -> if (index < 0) content.length else index }
            val name = content.substring(0, nameEnd)
            val attributes = parseAttributes(content.substring(nameEnd))
                ?: return invalid("font.svg.invalid-attribute", "SVG attributes must be quoted and unique.")
            when (name) {
                "svg" -> {
                    if (rootSeen || stack.isNotEmpty() || selfClosing || attributes.keys.any { key -> key !in SVG_ATTRIBUTES }) {
                        return unsupported("Only one non-empty root svg element with declared attributes is supported.")
                    }
                    if (attributes["xmlns"] != SVG_NAMESPACE) return unsupported("SVG root must declare the SVG namespace.")
                    val id = attributes["id"]
                    val targetGlyphId = id?.let(::parseGlyphTargetId)
                    if (id != null && targetGlyphId == null) {
                        return invalid("font.svg.invalid-glyph-target", "SVG root id must have the form glyph<N>.")
                    }
                    if (id != null && !elementIds.add(id)) {
                        return invalid("font.svg.duplicate-id", "SVG element ids must be globally unique within a document.")
                    }
                    if (targetGlyphId != null && !registerGlyphTarget(targetGlyphId)) {
                        return invalid("font.svg.duplicate-glyph-target", "SVG document declares a glyph target more than once.")
                    }
                    rootSeen = true
                    stack.addLast(SvgElement("svg", AffineTransform.identity, targetGlyphId))
                }

                "g" -> {
                    if (
                        stack.lastOrNull()?.name !in PAINT_CONTAINER_ELEMENTS ||
                        selfClosing ||
                        attributes.keys.any { key -> key !in GROUP_ATTRIBUTES }
                    ) {
                        return unsupported("SVG group attributes other than transform and glyph target id are not supported.")
                    }
                    if (stack.size + 1 > profile.limits.maxDepth) return limit("SVG nesting-depth limit exceeded.")
                    val id = attributes["id"]
                    val targetGlyphId = id?.let(::parseGlyphTargetId)
                    if (id != null && targetGlyphId == null) {
                        return invalid("font.svg.invalid-glyph-target", "SVG group id must have the form glyph<N>.")
                    }
                    if (id != null && !elementIds.add(id)) {
                        return invalid("font.svg.duplicate-id", "SVG element ids must be globally unique within a document.")
                    }
                    val parent = stack.last()
                    if (targetGlyphId != null && parent.glyphTargetId != null) {
                        return unsupported("Nested SVG glyph targets are not supported.")
                    }
                    if (targetGlyphId != null && !registerGlyphTarget(targetGlyphId)) {
                        return invalid("font.svg.duplicate-glyph-target", "SVG document declares a glyph target more than once.")
                    }
                    val local = if ("transform" in attributes) {
                        when (val parsed = parseTransform(attributes.getValue("transform"))) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    } else {
                        AffineTransform.identity
                    }
                    val transform = if (targetGlyphId != null) local else parent.transform.then(local)
                    stack.addLast(
                        SvgElement(
                            "g",
                            transform,
                            targetGlyphId ?: parent.glyphTargetId,
                        ),
                    )
                }

                "defs" -> {
                    if (
                        stack.lastOrNull()?.name !in PAINT_CONTAINER_ELEMENTS ||
                        attributes.isNotEmpty()
                    ) {
                        return unsupported("SVG defs must be an attribute-free child of svg or g.")
                    }
                    if (!selfClosing) {
                        if (stack.size + 1 > profile.limits.maxDepth) return limit("SVG nesting-depth limit exceeded.")
                        stack.addLast(SvgElement("defs", AffineTransform.identity, null))
                    }
                }

                "linearGradient" -> {
                    if (stack.lastOrNull()?.name != "defs") {
                        return unsupported("SVG linearGradient must be a child of defs.")
                    }
                    if (profile.schemaVersion != 3) {
                        return unsupported("SVG linear gradients require paint schema 3.")
                    }
                    if (attributes.keys.any { key -> key !in LINEAR_GRADIENT_ATTRIBUTES }) {
                        return unsupported("SVG linear-gradient attributes outside the static object-bounding-box subset are not supported.")
                    }
                    val id = attributes["id"]?.takeIf(String::isSvgDefinitionId)
                        ?: return invalid("font.svg.invalid-gradient-id", "SVG linearGradient requires a valid local id.")
                    if (!elementIds.add(id)) {
                        return invalid("font.svg.duplicate-id", "SVG element ids must be globally unique within a document.")
                    }
                    if (parsedGradientCount >= profile.limits.maxGradients) {
                        return limit("SVG gradient-definition limit exceeded.")
                    }
                    if (attributes.getOrElse("gradientUnits") { "objectBoundingBox" } != "objectBoundingBox") {
                        return unsupported("Only objectBoundingBox SVG linear gradients are supported.")
                    }
                    val x1 = parseObjectBoundingBoxCoordinate(attributes.getOrElse("x1") { "0%" })
                        ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient x1 is invalid.")
                    val y1 = parseObjectBoundingBoxCoordinate(attributes.getOrElse("y1") { "0%" })
                        ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient y1 is invalid.")
                    val x2 = parseObjectBoundingBoxCoordinate(attributes.getOrElse("x2") { "100%" })
                        ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient x2 is invalid.")
                    val y2 = parseObjectBoundingBoxCoordinate(attributes.getOrElse("y2") { "0%" })
                        ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient y2 is invalid.")
                    val extendMode = when (attributes.getOrElse("spreadMethod") { "pad" }) {
                        "pad" -> GlyphPaintExtendMode.PAD
                        "repeat" -> GlyphPaintExtendMode.REPEAT
                        "reflect" -> GlyphPaintExtendMode.REFLECT
                        else -> return unsupported("SVG linear-gradient spread method is not supported.")
                    }
                    val interpolationSpace = when (attributes.getOrElse("color-interpolation") { "sRGB" }) {
                        "sRGB" -> GlyphPaintInterpolationSpace.SRGB
                        "linearRGB" -> GlyphPaintInterpolationSpace.LINEAR_SRGB
                        else -> return unsupported("SVG linear-gradient color interpolation is not supported.")
                    }
                    parsedGradientCount += 1
                    val builder = SvgLinearGradientBuilder(
                        id = id,
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2,
                        extendMode = extendMode,
                        interpolationSpace = interpolationSpace,
                    )
                    if (selfClosing) {
                        gradients[id] = builder.build()
                    } else {
                        if (stack.size + 1 > profile.limits.maxDepth) return limit("SVG nesting-depth limit exceeded.")
                        pendingGradient = builder
                        stack.addLast(SvgElement("linearGradient", AffineTransform.identity, null))
                    }
                }

                "stop" -> {
                    if (
                        stack.lastOrNull()?.name != "linearGradient" ||
                        !selfClosing ||
                        attributes.keys.any { key -> key !in STOP_ATTRIBUTES }
                    ) {
                        return unsupported("Only self-closing stops within linearGradient are supported.")
                    }
                    if (parsedColorStopCount >= profile.limits.maxColorStops) {
                        return limit("SVG color-stop limit exceeded.")
                    }
                    val rawOffset = parseSvgFraction(attributes.getOrElse("offset") { "0" })
                        ?: return invalid("font.svg.invalid-stop-offset", "SVG stop offset is invalid.")
                    val color = parseColor(attributes.getOrElse("stop-color") { "#000000" })
                        ?: return unsupported("Only #RRGGBB SVG stop colors are supported.")
                    val rawOpacity = parseSvgFraction(attributes.getOrElse("stop-opacity") { "1" })
                        ?: return invalid("font.svg.invalid-stop-opacity", "SVG stop opacity is invalid.")
                    pendingGradient?.addStop(rawOffset, color, rawOpacity)
                        ?: return invalid("font.svg.invalid-gradient", "SVG stop has no active linear gradient.")
                    parsedColorStopCount += 1
                }

                "path" -> {
                    if (
                        stack.lastOrNull()?.name !in PAINT_CONTAINER_ELEMENTS ||
                        !selfClosing ||
                        attributes.keys.any { key -> key !in PATH_ATTRIBUTES }
                    ) {
                        return unsupported("Only self-closing paths with d and fill attributes are supported.")
                    }
                    val pathData = attributes["d"] ?: return invalid("font.svg.missing-path-data", "SVG path is missing d data.")
                    val fill = attributes["fill"] ?: "#000000"
                    if (fill == "none") continue
                    val color = parseColor(fill) ?: return unsupported("Only #RRGGBB SVG fills are supported.")
                    val path = when (val parsed = parsePath(pathData, stack.last().transform)) {
                        is FontOperationResult.Success -> parsed.value
                        is FontOperationResult.Failure -> return parsed
                        is FontOperationResult.Cancelled -> return parsed
                    }
                    val target = stack.last().glyphTargetId?.let(glyphs::get) ?: unassignedPaint
                    target.appendSolidPath(path, color, profile)?.let { failure -> return failure }
                }

                "rect" -> {
                    if (
                        stack.lastOrNull()?.name !in PAINT_CONTAINER_ELEMENTS ||
                        !selfClosing ||
                        attributes.keys.any { key -> key !in RECT_ATTRIBUTES }
                    ) {
                        return unsupported("Only self-closing rectangles in the static fill subset are supported.")
                    }
                    val x = parseSvgNumber(attributes.getOrElse("x") { "0" })
                        ?: return invalid("font.svg.invalid-rect", "SVG rectangle x is invalid.")
                    val y = parseSvgNumber(attributes.getOrElse("y") { "0" })
                        ?: return invalid("font.svg.invalid-rect", "SVG rectangle y is invalid.")
                    val width = attributes["width"]?.let(::parseSvgNumber)
                        ?: return invalid("font.svg.invalid-rect", "SVG rectangle width is required and must be finite.")
                    val height = attributes["height"]?.let(::parseSvgNumber)
                        ?: return invalid("font.svg.invalid-rect", "SVG rectangle height is required and must be finite.")
                    if (width < 0.0 || height < 0.0) {
                        return invalid("font.svg.invalid-rect", "SVG rectangle dimensions must be non-negative.")
                    }
                    val fill = attributes.getOrElse("fill") { "#000000" }
                    if (fill == "none") continue
                    val solid = parseColor(fill)
                    val gradient = if (solid == null) {
                        val reference = parseLocalPaintReference(fill)
                            ?: return unsupported("Only #RRGGBB or preceding local gradient references are supported for SVG rectangles.")
                        gradients[reference]
                            ?: return unsupported("SVG rectangle gradient references must resolve to a preceding local definition.")
                    } else {
                        null
                    }
                    if (width == 0.0 || height == 0.0) continue
                    if (!stack.last().transform.preservesArea) continue
                    val path = when (val result = rectanglePath(x, y, width, height, stack.last().transform)) {
                        is FontOperationResult.Success -> result.value
                        is FontOperationResult.Failure -> return result
                        is FontOperationResult.Cancelled -> return result
                    }
                    val target = stack.last().glyphTargetId?.let(glyphs::get) ?: unassignedPaint
                    if (solid != null) {
                        target.appendSolidPath(path, solid, profile)?.let { failure -> return failure }
                        continue
                    }
                    target.appendGradientRect(
                        path = path,
                        definition = checkNotNull(gradient),
                        rectangle = SvgRectangle(x, y, width, height),
                        transform = stack.last().transform,
                        profile = profile,
                    )?.let { failure -> return failure }
                }

                else -> return unsupported("SVG element $name is not supported.")
            }
        }
        if (!rootSeen || stack.isNotEmpty()) return invalid("font.svg.unclosed-element", "SVG root or group element is not closed.")
        val glyphPaints = linkedMapOf<Int, SvgGlyphPaint>()
        glyphs.forEach { (glyphId, target) ->
            val paint = target.paint(profile) ?: return unsupported("SVG paint graph exceeds the selected profile.")
            glyphPaints[glyphId] = paint
        }
        return FontOperationResult.Success(ParsedSvgDocument(glyphPaints))
    }

    private fun registerGlyphTarget(glyphId: Int): Boolean {
        if (glyphId in glyphs) return false
        glyphs[glyphId] = SvgGlyphPaintBuilder()
        return true
    }

    private fun parseTransform(value: String): FontOperationResult<AffineTransform> {
        var cursor = 0
        var transform = AffineTransform.identity
        while (cursor < value.length) {
            cursor = value.skipWhitespace(cursor)
            if (cursor == value.length) break
            val nameStart = cursor
            while (cursor < value.length && value[cursor].isLetter()) cursor += 1
            val name = value.substring(nameStart, cursor)
            cursor = value.skipWhitespace(cursor)
            if (cursor >= value.length || value[cursor] != '(') {
                return invalid("font.svg.invalid-transform", "SVG group transform is malformed.")
            }
            val close = value.indexOf(')', cursor + 1)
            if (close < 0) return invalid("font.svg.invalid-transform", "SVG group transform is malformed.")
            val numbers = SvgNumberCursor(value.substring(cursor + 1, close)).allNumbers()
                ?: return invalid("font.svg.invalid-transform", "SVG group transform is malformed.")
            val next = when (name) {
                "translate" -> if (numbers.size in 1..2) AffineTransform.translate(numbers[0], numbers.getOrElse(1) { 0.0 }) else {
                    return invalid("font.svg.invalid-transform", "SVG translate transform has invalid operands.")
                }

                "scale" -> if (numbers.size in 1..2) AffineTransform.scale(numbers[0], numbers.getOrElse(1) { numbers[0] }) else {
                    return invalid("font.svg.invalid-transform", "SVG scale transform has invalid operands.")
                }

                else -> return unsupported("SVG transform $name is not supported.")
            }
            if (!transformBudget.tryConsume()) return limit("SVG transform-operation limit exceeded.")
            transform = try {
                transform.then(next)
            } catch (_: IllegalArgumentException) {
                return invalid("font.svg.invalid-transform", "SVG group transform exceeds the portable coordinate domain.")
            }
            cursor = close + 1
        }
        return FontOperationResult.Success(transform)
    }

    private fun parsePath(data: String, transform: AffineTransform): FontOperationResult<GlyphPaintPath> {
        val cursor = SvgNumberCursor(data)
        val commands = mutableListOf<RawPathCommand>()
        var activeCommand: Char? = null
        var current = Point(0.0, 0.0)
        var contourStart = Point(0.0, 0.0)
        var contourOpen = false
        var previousCubicControl2: Point? = null
        while (cursor.hasRemaining()) {
            val next = cursor.peek()
            if (next != null && next.isLetter()) {
                activeCommand = next
                cursor.advance()
            }
            val command = activeCommand ?: return invalid("font.svg.path-command", "SVG path data is missing a command.")
            val relative = command.isLowerCase()
            when (command.lowercaseChar()) {
                'm' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        val point = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG move command is invalid.")
                        if (contourOpen) commands += RawPathCommand.Close
                        if (count == 0) {
                            commands += RawPathCommand.MoveTo(point)
                            contourStart = point
                            contourOpen = true
                        } else {
                            commands += RawPathCommand.LineTo(point)
                        }
                        current = point
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG move command requires coordinates.")
                    activeCommand = if (relative) 'l' else 'L'
                }

                'l' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG line command requires a move command.")
                        val point = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG line command is invalid.")
                        commands += RawPathCommand.LineTo(point)
                        current = point
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG line command requires coordinates.")
                }

                'h' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG horizontal line requires a move command.")
                        val value = cursor.number() ?: return invalid("font.svg.path-number", "SVG horizontal line is invalid.")
                        current = Point(if (relative) current.x + value else value, current.y)
                        commands += RawPathCommand.LineTo(current)
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG horizontal line requires coordinates.")
                }

                'v' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG vertical line requires a move command.")
                        val value = cursor.number() ?: return invalid("font.svg.path-number", "SVG vertical line is invalid.")
                        current = Point(current.x, if (relative) current.y + value else value)
                        commands += RawPathCommand.LineTo(current)
                        previousCubicControl2 = null
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG vertical line requires coordinates.")
                }

                'c' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG cubic curve requires a move command.")
                        val control1 = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG cubic curve is invalid.")
                        val control2 = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG cubic curve is invalid.")
                        val endpoint = cursor.point(relative, current) ?: return invalid("font.svg.path-number", "SVG cubic curve is invalid.")
                        commands += RawPathCommand.CubicTo(control1, control2, endpoint)
                        current = endpoint
                        previousCubicControl2 = control2
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG cubic curve requires coordinates.")
                }

                's' -> {
                    var count = 0
                    while (cursor.hasNumber()) {
                        requireOpenContour(contourOpen) ?: return invalid("font.svg.path-order", "SVG smooth cubic curve requires a move command.")
                        val control1 = previousCubicControl2?.let { previous ->
                            Point(2.0 * current.x - previous.x, 2.0 * current.y - previous.y)
                        } ?: current
                        val control2 = cursor.point(relative, current)
                            ?: return invalid("font.svg.path-number", "SVG smooth cubic curve is invalid.")
                        val endpoint = cursor.point(relative, current)
                            ?: return invalid("font.svg.path-number", "SVG smooth cubic curve is invalid.")
                        commands += RawPathCommand.CubicTo(control1, control2, endpoint)
                        current = endpoint
                        previousCubicControl2 = control2
                        count += 1
                    }
                    if (count == 0) return invalid("font.svg.path-number", "SVG smooth cubic curve requires coordinates.")
                }

                'z' -> {
                    if (!contourOpen) return invalid("font.svg.path-order", "SVG close command requires a move command.")
                    commands += RawPathCommand.Close
                    current = contourStart
                    contourOpen = false
                    previousCubicControl2 = null
                    activeCommand = null
                }

                else -> return unsupported("SVG path command $command is not supported.")
            }
            if (commands.size > profile.outlineProfile.maxPoints * 2 + profile.outlineProfile.maxContours) {
                return limit("SVG path-command limit exceeded.")
            }
        }
        if (contourOpen) commands += RawPathCommand.Close
        if (commands.isEmpty()) return invalid("font.svg.empty-path", "SVG path data must contain commands.")
        return try {
            FontOperationResult.Success(GlyphPaintPath(commands.map { command -> command.materialize(transform) }))
        } catch (_: IllegalArgumentException) {
            invalid("font.svg.invalid-path", "SVG path coordinates exceed the portable path domain.")
        }
    }
}

private data class SvgElement(
    val name: String,
    val transform: AffineTransform,
    val glyphTargetId: Int?,
)

private class SvgGlyphPaintBuilder {
    private val nodes: MutableList<GlyphPaintNode> = mutableListOf()
    private val roots: MutableList<Int> = mutableListOf()
    private var internalReferences: Int = 0
    private var pathCount: Int = 0
    private var gradientCount: Int = 0
    private var colorStopCount: Int = 0
    private var clipCount: Int = 0
    private var deepestRoot: Int = 0

    fun appendSolidPath(
        path: GlyphPaintPath,
        color: GlyphColor,
        profile: PaintGraphProfile,
    ): FontOperationResult.Failure? {
        if (GlyphPaintNodeKind.PATH !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept SVG solid paths.")
        }
        projectedLimitFailure(
            additionalNodes = 1,
            additionalReferences = 0,
            additionalPaths = 1,
            additionalGradients = 0,
            additionalColorStops = 0,
            additionalClips = 0,
            rootDepth = 1,
            profile = profile,
        )?.let { return it }
        nodes += GlyphPaintNode.Path(path, color)
        roots += nodes.lastIndex
        pathCount += 1
        deepestRoot = maxOf(deepestRoot, 1)
        return null
    }

    fun appendGradientRect(
        path: GlyphPaintPath,
        definition: SvgLinearGradient,
        rectangle: SvgRectangle,
        transform: AffineTransform,
        profile: PaintGraphProfile,
    ): FontOperationResult.Failure? {
        if (definition.colorStops.isEmpty()) return null
        if (GlyphPaintNodeKind.PATH_CLIP !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept SVG path clips.")
        }
        val intrinsicallySolid = definition.colorStops.size == 1 || definition.hasDegenerateVector
        val points = if (intrinsicallySolid) {
            null
        } else {
            definition.points(rectangle, transform)
                ?: return invalid("font.svg.invalid-gradient", "SVG linear-gradient coordinates exceed the portable domain.")
        }
        val useSolid = intrinsicallySolid || checkNotNull(points).let { resolved -> resolved.p0 == resolved.p1 }
        if (!useSolid && !checkNotNull(points).formsPlane) {
            return invalid("font.svg.invalid-gradient", "SVG linear-gradient points are collinear after normalization.")
        }
        val requiredPaintKind = if (useSolid) GlyphPaintNodeKind.SOLID else GlyphPaintNodeKind.LINEAR_GRADIENT
        if (requiredPaintKind !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept the normalized SVG gradient paint.")
        }
        if (!useSolid) {
            if (definition.extendMode !in profile.acceptedGradientExtendModes) {
                return unsupported("The selected paint profile does not accept the SVG gradient spread method.")
            }
            if (definition.interpolationSpace !in profile.acceptedGradientInterpolationSpaces) {
                return unsupported("The selected paint profile does not accept the SVG gradient interpolation space.")
            }
            if (GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED !in profile.acceptedGradientAlphaInterpolationModes) {
                return unsupported("The selected paint profile does not accept SVG alpha interpolation.")
            }
        }
        projectedLimitFailure(
            additionalNodes = 2,
            additionalReferences = 1,
            additionalPaths = 1,
            additionalGradients = if (useSolid) 0 else 1,
            additionalColorStops = if (useSolid) 0 else definition.colorStops.size,
            additionalClips = 1,
            rootDepth = 2,
            profile = profile,
        )?.let { return it }
        val paintIndex = nodes.size
        nodes += if (useSolid) {
            val finalStop = definition.colorStops.last()
            GlyphPaintNode.Solid(finalStop.color, finalStop.opacity)
        } else {
            val gradientPoints = checkNotNull(points)
            GlyphPaintNode.LinearGradient(
                colorLine = GlyphPaintColorLine(
                    extendMode = definition.extendMode,
                    colorStops = definition.colorStops,
                    interpolationSpace = definition.interpolationSpace,
                    alphaInterpolationMode = GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED,
                ),
                p0 = gradientPoints.p0,
                p1 = gradientPoints.p1,
                p2 = gradientPoints.p2,
            )
        }
        nodes += GlyphPaintNode.PathClip(path, paintIndex)
        roots += nodes.lastIndex
        internalReferences += 1
        pathCount += 1
        if (!useSolid) {
            gradientCount += 1
            colorStopCount += definition.colorStops.size
        }
        clipCount += 1
        deepestRoot = maxOf(deepestRoot, 2)
        return null
    }

    fun paint(profile: PaintGraphProfile): SvgGlyphPaint? {
        if (roots.isEmpty()) return SvgGlyphPaint.Empty
        val completeNodes = ArrayList<GlyphPaintNode>(nodes.size + 1)
        completeNodes += nodes
        val rootNode = if (roots.size == 1) {
            roots.single()
        } else {
            completeNodes += GlyphPaintNode.Group(roots, GlyphPaintCompositionMode.SOURCE_OVER)
            completeNodes.lastIndex
        }
        val paint = GlyphPaintIR(profile.schemaVersion, rootNode, completeNodes)
        return if (profile.accepts(paint)) SvgGlyphPaint.Paint(paint) else null
    }

    private fun projectedLimitFailure(
        additionalNodes: Int,
        additionalReferences: Int,
        additionalPaths: Int,
        additionalGradients: Int,
        additionalColorStops: Int,
        additionalClips: Int,
        rootDepth: Int,
        profile: PaintGraphProfile,
    ): FontOperationResult.Failure? {
        val rootsAfterAppend = roots.size + 1
        val groupNodes = if (rootsAfterAppend > 1) 1 else 0
        val groupReferences = if (rootsAfterAppend > 1) rootsAfterAppend else 0
        val nodesAfterAppend = nodes.size.toLong() + additionalNodes + groupNodes
        val referencesAfterAppend = internalReferences.toLong() + additionalReferences + groupReferences
        val depthAfterAppend = maxOf(deepestRoot, rootDepth) + if (rootsAfterAppend > 1) 1 else 0
        if (
            nodesAfterAppend > profile.limits.maxNodes ||
            referencesAfterAppend > profile.limits.maxReferences ||
            depthAfterAppend > profile.limits.maxDepth ||
            (profile.schemaVersion >= 2 && nodesAfterAppend > profile.limits.maxPaintVisits) ||
            pathCount.toLong() + additionalPaths > profile.limits.maxPaths ||
            gradientCount.toLong() + additionalGradients > profile.limits.maxGradients ||
            colorStopCount.toLong() + additionalColorStops > profile.limits.maxColorStops ||
            clipCount.toLong() + additionalClips > profile.limits.maxClips
        ) {
            return limit("SVG generated paint-graph limit exceeded.")
        }
        if (
            rootsAfterAppend > 1 &&
            (
                GlyphPaintNodeKind.GROUP !in profile.acceptedNodeKinds ||
                    GlyphPaintCompositionMode.SOURCE_OVER !in profile.acceptedCompositionModes
            )
        ) {
            return unsupported("The selected paint profile cannot group the complete SVG paint.")
        }
        return null
    }
}

private data class SvgRectangle(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

private data class SvgGradientPoints(
    val p0: GlyphPaintPoint,
    val p1: GlyphPaintPoint,
    val p2: GlyphPaintPoint,
) {
    val formsPlane: Boolean
        get() = formsNonDegenerateBasis(
            firstX = p1.x - p0.x,
            firstY = p1.y - p0.y,
            secondX = p2.x - p0.x,
            secondY = p2.y - p0.y,
        )
}

private data class SvgLinearGradient(
    val id: String,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val extendMode: GlyphPaintExtendMode,
    val interpolationSpace: GlyphPaintInterpolationSpace,
    val colorStops: List<GlyphPaintColorStop>,
) {
    val hasDegenerateVector: Boolean
        get() = x1 == x2 && y1 == y2

    fun points(rectangle: SvgRectangle, transform: AffineTransform): SvgGradientPoints? = try {
        val dx = x2 - x1
        val dy = y2 - y1
        val p0 = transform.apply(rectangle.map(x1, y1))
        val p1 = transform.apply(rectangle.map(x2, y2))
        val p2 = transform.apply(rectangle.map(x1 - dy, y1 + dx))
        SvgGradientPoints(
            GlyphPaintPoint(p0.x, p0.y),
            GlyphPaintPoint(p1.x, p1.y),
            GlyphPaintPoint(p2.x, p2.y),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private class SvgLinearGradientBuilder(
    val id: String,
    private val x1: Double,
    private val y1: Double,
    private val x2: Double,
    private val y2: Double,
    private val extendMode: GlyphPaintExtendMode,
    private val interpolationSpace: GlyphPaintInterpolationSpace,
) {
    private val colorStops = mutableListOf<GlyphPaintColorStop>()

    fun addStop(offset: Double, color: GlyphColor, opacity: Double) {
        val normalizedOffset = maxOf(colorStops.lastOrNull()?.offset ?: 0.0, offset.coerceIn(0.0, 1.0))
        colorStops += GlyphPaintColorStop(normalizedOffset, color, opacity.coerceIn(0.0, 1.0))
    }

    fun build(): SvgLinearGradient {
        val normalizedStops = if (colorStops.size > 1 && extendMode != GlyphPaintExtendMode.PAD) {
            buildList(colorStops.size + 2) {
                colorStops.first().takeIf { stop -> stop.offset > 0.0 }?.let { stop ->
                    add(stop.copy(offset = 0.0))
                }
                addAll(colorStops)
                colorStops.last().takeIf { stop -> stop.offset < 1.0 }?.let { stop ->
                    add(stop.copy(offset = 1.0))
                }
            }
        } else {
            colorStops.toList()
        }
        return SvgLinearGradient(
            id = id,
            x1 = x1,
            y1 = y1,
            x2 = x2,
            y2 = y2,
            extendMode = extendMode,
            interpolationSpace = interpolationSpace,
            colorStops = normalizedStops,
        )
    }
}

private fun SvgRectangle.map(normalizedX: Double, normalizedY: Double): Point =
    Point(x + normalizedX * width, y + normalizedY * height)

private class SvgTransformBudget(
    private val maximum: Int,
) {
    private var consumed: Int = 0

    fun tryConsume(): Boolean {
        if (consumed >= maximum) return false
        consumed += 1
        return true
    }
}

private data class Point(val x: Double, val y: Double)

private sealed interface RawPathCommand {
    data class MoveTo(val point: Point) : RawPathCommand
    data class LineTo(val point: Point) : RawPathCommand
    data class CubicTo(val control1: Point, val control2: Point, val endpoint: Point) : RawPathCommand
    data object Close : RawPathCommand
}

private fun RawPathCommand.materialize(transform: AffineTransform): GlyphPaintPathCommand = when (this) {
    is RawPathCommand.MoveTo -> transform.apply(point).let { point -> GlyphPaintPathCommand.MoveTo(point.x, point.y) }
    is RawPathCommand.LineTo -> transform.apply(point).let { point -> GlyphPaintPathCommand.LineTo(point.x, point.y) }
    is RawPathCommand.CubicTo -> {
        val control1 = transform.apply(control1)
        val control2 = transform.apply(control2)
        val endpoint = transform.apply(endpoint)
        GlyphPaintPathCommand.CubicTo(control1.x, control1.y, control2.x, control2.y, endpoint.x, endpoint.y)
    }
    RawPathCommand.Close -> GlyphPaintPathCommand.Close
}

private class SvgNumberCursor(
    private val source: String,
) {
    private var index = 0

    fun hasRemaining(): Boolean {
        index = source.skipSeparators(index)
        return index < source.length
    }

    fun peek(): Char? {
        index = source.skipSeparators(index)
        return source.getOrNull(index)
    }

    fun advance() {
        index += 1
    }

    fun hasNumber(): Boolean {
        index = source.skipSeparators(index)
        return source.getOrNull(index)?.let { character ->
            character.isSvgAsciiDigit() || character == '+' || character == '-' || character == '.'
        } == true
    }

    fun number(): Double? {
        index = source.skipSeparators(index)
        val start = index
        if (source.getOrNull(index) in setOf('+', '-')) index += 1
        var digits = 0
        while (source.getOrNull(index)?.isSvgAsciiDigit() == true) {
            index += 1
            digits += 1
        }
        if (source.getOrNull(index) == '.') {
            index += 1
            while (source.getOrNull(index)?.isSvgAsciiDigit() == true) {
                index += 1
                digits += 1
            }
        }
        if (digits == 0) return null
        if (source.getOrNull(index) in setOf('e', 'E')) {
            val exponent = index
            index += 1
            if (source.getOrNull(index) in setOf('+', '-')) index += 1
            val exponentDigits = index
            while (source.getOrNull(index)?.isSvgAsciiDigit() == true) index += 1
            if (exponentDigits == index) {
                index = exponent
            }
        }
        return source.substring(start, index).toDoubleOrNull()?.takeIf(Double::isFinite)
    }

    fun point(relative: Boolean, origin: Point): Point? {
        val x = number() ?: return null
        val y = number() ?: return null
        return if (relative) Point(origin.x + x, origin.y + y) else Point(x, y)
    }

    fun allNumbers(): List<Double>? {
        val values = mutableListOf<Double>()
        while (hasRemaining()) values += number() ?: return null
        return values
    }

    fun singleNumber(): Double? {
        index = source.skipWhitespace(0)
        if (index == source.length || source[index] == ',') return null
        val start = index
        if (source.getOrNull(index) in setOf('+', '-')) index += 1
        var integerDigits = 0
        while (source.getOrNull(index)?.isSvgAsciiDigit() == true) {
            index += 1
            integerDigits += 1
        }
        if (source.getOrNull(index) == '.') {
            index += 1
            val fractionStart = index
            while (source.getOrNull(index)?.isSvgAsciiDigit() == true) index += 1
            if (fractionStart == index) return null
        } else if (integerDigits == 0) {
            return null
        }
        if (source.getOrNull(index) in setOf('e', 'E')) {
            index += 1
            if (source.getOrNull(index) in setOf('+', '-')) index += 1
            val exponentStart = index
            while (source.getOrNull(index)?.isSvgAsciiDigit() == true) index += 1
            if (exponentStart == index) return null
        }
        val end = index
        index = source.skipWhitespace(index)
        if (index != source.length) return null
        return source.substring(start, end).toDoubleOrNull()?.takeIf(Double::isFinite)
    }
}

private fun Char.isSvgAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isSvgWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

private data class AffineTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    fun then(next: AffineTransform): AffineTransform = AffineTransform(
        a = a * next.a + c * next.b,
        b = b * next.a + d * next.b,
        c = a * next.c + c * next.d,
        d = b * next.c + d * next.d,
        e = a * next.e + c * next.f + e,
        f = b * next.e + d * next.f + f,
    ).also { transform -> require(transform.values.all(Double::isFinite)) { "SVG transform is not finite." } }

    fun apply(point: Point): Point = Point(a * point.x + c * point.y + e, b * point.x + d * point.y + f)

    val preservesArea: Boolean
        get() = formsNonDegenerateBasis(a, b, c, d)

    val values: List<Double>
        get() = listOf(a, b, c, d, e, f)

    companion object {
        val identity: AffineTransform = AffineTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(x: Double, y: Double): AffineTransform = AffineTransform(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(x: Double, y: Double): AffineTransform = AffineTransform(x, 0.0, 0.0, y, 0.0, 0.0)
    }
}

private fun formsNonDegenerateBasis(
    firstX: Double,
    firstY: Double,
    secondX: Double,
    secondY: Double,
): Boolean {
    if (!firstX.isFinite() || !firstY.isFinite() || !secondX.isFinite() || !secondY.isFinite()) return false
    return exactBinaryProduct(firstX, secondY) != exactBinaryProduct(firstY, secondX)
}

private data class ExactBinaryComponent(
    val negative: Boolean,
    val significand: ULong,
    val exponent: Int,
)

private data class Unsigned128(
    val high: ULong,
    val low: ULong,
)

private data class ExactBinaryProduct(
    val negative: Boolean,
    val significand: Unsigned128,
    val exponent: Int,
)

private fun exactBinaryProduct(first: Double, second: Double): ExactBinaryProduct {
    val left = first.exactBinaryComponent()
    val right = second.exactBinaryComponent()
    if (left.significand == 0UL || right.significand == 0UL) return ZERO_EXACT_BINARY_PRODUCT
    return ExactBinaryProduct(
        negative = left.negative != right.negative,
        significand = multiplySignificands(left.significand, right.significand),
        exponent = left.exponent + right.exponent,
    )
}

private fun Double.exactBinaryComponent(): ExactBinaryComponent {
    val bits = toBits().toULong()
    val encodedExponent = ((bits shr DOUBLE_SIGNIFICAND_BITS) and DOUBLE_EXPONENT_MASK).toInt()
    var significand = bits and DOUBLE_FRACTION_MASK
    var exponent = if (encodedExponent == 0) {
        DOUBLE_SUBNORMAL_EXPONENT
    } else {
        significand = significand or DOUBLE_IMPLICIT_BIT
        encodedExponent - DOUBLE_EXPONENT_BIAS - DOUBLE_SIGNIFICAND_BITS
    }
    if (significand == 0UL) return ExactBinaryComponent(false, 0UL, 0)
    while ((significand and 1UL) == 0UL) {
        significand = significand shr 1
        exponent += 1
    }
    return ExactBinaryComponent(
        negative = (bits and DOUBLE_SIGN_MASK) != 0UL,
        significand = significand,
        exponent = exponent,
    )
}

private fun multiplySignificands(first: ULong, second: ULong): Unsigned128 {
    val firstLow = first and UNSIGNED_INT_MASK
    val firstHigh = first shr UNSIGNED_INT_BITS
    val secondLow = second and UNSIGNED_INT_MASK
    val secondHigh = second shr UNSIGNED_INT_BITS
    val lowProduct = firstLow * secondLow
    val highMiddle = firstHigh * secondLow + (lowProduct shr UNSIGNED_INT_BITS)
    val combinedMiddle = (highMiddle and UNSIGNED_INT_MASK) + firstLow * secondHigh
    return Unsigned128(
        high = firstHigh * secondHigh + (highMiddle shr UNSIGNED_INT_BITS) +
            (combinedMiddle shr UNSIGNED_INT_BITS),
        low = (combinedMiddle shl UNSIGNED_INT_BITS) or (lowProduct and UNSIGNED_INT_MASK),
    )
}

private fun parseAttributes(source: String): Map<String, String>? {
    val attributes = linkedMapOf<String, String>()
    var index = 0
    while (index < source.length) {
        index = source.skipWhitespace(index)
        if (index == source.length) break
        val nameStart = index
        while (source.getOrNull(index)?.let { character -> character.isLetterOrDigit() || character in setOf(':', '_', '-') } == true) index += 1
        if (nameStart == index) return null
        val name = source.substring(nameStart, index)
        index = source.skipWhitespace(index)
        if (source.getOrNull(index) != '=') return null
        index = source.skipWhitespace(index + 1)
        val quote = source.getOrNull(index)?.takeIf { character -> character == '\'' || character == '"' } ?: return null
        index += 1
        val valueStart = index
        while (source.getOrNull(index) != quote) {
            val character = source.getOrNull(index) ?: return null
            if (character == '<' || character == '&') return null
            index += 1
        }
        val value = source.substring(valueStart, index)
        index += 1
        if (attributes.put(name, value) != null) return null
    }
    return attributes
}

private fun String.findTagEnd(start: Int): Int? {
    var quote: Char? = null
    for (index in start until length) {
        val character = this[index]
        if (quote != null) {
            if (character == quote) quote = null
        } else if (character == '\'' || character == '"') {
            quote = character
        } else if (character == '>') {
            return index
        }
    }
    return null
}

private fun String.skipWhitespace(start: Int = 0): Int {
    var index = start
    while (getOrNull(index)?.isSvgWhitespace() == true) index += 1
    return index
}

private fun String.skipSeparators(start: Int): Int {
    var index = start
    while (getOrNull(index)?.let { character -> character.isSvgWhitespace() || character == ',' } == true) index += 1
    return index
}

private fun String.trimSvgWhitespace(): String {
    val start = skipWhitespace()
    var end = length
    while (end > start && this[end - 1].isSvgWhitespace()) end -= 1
    return substring(start, end)
}

private fun rectanglePath(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    transform: AffineTransform,
): FontOperationResult<GlyphPaintPath> = try {
    val topLeft = transform.apply(Point(x, y))
    val topRight = transform.apply(Point(x + width, y))
    val bottomRight = transform.apply(Point(x + width, y + height))
    val bottomLeft = transform.apply(Point(x, y + height))
    FontOperationResult.Success(
        GlyphPaintPath(
            listOf(
                GlyphPaintPathCommand.MoveTo(topLeft.x, topLeft.y),
                GlyphPaintPathCommand.LineTo(topRight.x, topRight.y),
                GlyphPaintPathCommand.LineTo(bottomRight.x, bottomRight.y),
                GlyphPaintPathCommand.LineTo(bottomLeft.x, bottomLeft.y),
                GlyphPaintPathCommand.Close,
            ),
        ),
    )
} catch (_: IllegalArgumentException) {
    invalid("font.svg.invalid-rect", "SVG rectangle coordinates exceed the portable path domain.")
}

private fun parseSvgNumber(value: String): Double? {
    val text = value.trimSvgWhitespace()
    if (text.isEmpty() || text.endsWith('%')) return null
    return SvgNumberCursor(text).singleNumber()
}

private fun parseSvgFraction(value: String): Double? {
    val text = value.trimSvgWhitespace()
    if (text.isEmpty()) return null
    val percentage = text.endsWith('%')
    if (percentage && text.getOrNull(text.lastIndex - 1)?.isSvgWhitespace() == true) return null
    val numberText = if (percentage) text.dropLast(1) else text
    val number = parseSvgNumber(numberText) ?: return null
    return if (percentage) number / 100.0 else number
}

private fun parseObjectBoundingBoxCoordinate(value: String): Double? = parseSvgFraction(value)

private fun parseLocalPaintReference(value: String): String? {
    val text = value.trim()
    if (!text.startsWith("url(#") || !text.endsWith(')')) return null
    val id = text.substring(5, text.length - 1)
    return id.takeIf(String::isSvgDefinitionId)
}

private fun String.isSvgDefinitionId(): Boolean =
    isNotEmpty() &&
        (first().isLetter() || first() == '_') &&
        drop(1).all { character -> character.isLetterOrDigit() || character in setOf('_', '-', '.') }

private fun parseColor(value: String): GlyphColor? {
    if (value.length != 7 || value.firstOrNull() != '#') return null
    val red = value.substring(1, 3).toIntOrNull(16) ?: return null
    val green = value.substring(3, 5).toIntOrNull(16) ?: return null
    val blue = value.substring(5, 7).toIntOrNull(16) ?: return null
    return GlyphColor(red, green, blue)
}

private fun parseGlyphTargetId(value: String): Int? {
    val suffix = value.removePrefix("glyph")
    if (suffix.length == value.length || suffix.isEmpty() || suffix.any { character -> !character.isDigit() }) return null
    return suffix.toIntOrNull()
}

private fun requireOpenContour(open: Boolean): Unit? = if (open) Unit else null

private fun invalid(code: String, message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table("SVG ")))

private fun unsupported(message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile(message, FontDiagnosticLocation.Table("SVG ")))

private fun limit(message: String): FontOperationResult.Failure =
    FontOperationResult.Failure(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Table("SVG ")))

private const val SVG_TABLE_VERSION: Int = 0
private const val SVG_HEADER_LENGTH: Int = 10
private const val DOCUMENT_LIST_HEADER_LENGTH: Int = 2
private const val DOCUMENT_RECORD_LENGTH: Int = 12
private const val SVG_NAMESPACE: String = "http://www.w3.org/2000/svg"
private val SVG_ATTRIBUTES: Set<String> = setOf("xmlns", "id")
private val GROUP_ATTRIBUTES: Set<String> = setOf("transform", "id")
private val PATH_ATTRIBUTES: Set<String> = setOf("d", "fill")
private val RECT_ATTRIBUTES: Set<String> = setOf("x", "y", "width", "height", "fill")
private val LINEAR_GRADIENT_ATTRIBUTES: Set<String> = setOf(
    "id",
    "x1",
    "y1",
    "x2",
    "y2",
    "gradientUnits",
    "spreadMethod",
    "color-interpolation",
)
private val STOP_ATTRIBUTES: Set<String> = setOf("offset", "stop-color", "stop-opacity")
private val CONTAINER_ELEMENTS: Set<String> = setOf("svg", "g", "defs", "linearGradient")
private val PAINT_CONTAINER_ELEMENTS: Set<String> = setOf("svg", "g")
private const val DOUBLE_SIGNIFICAND_BITS: Int = 52
private const val DOUBLE_EXPONENT_BIAS: Int = 1023
private const val DOUBLE_SUBNORMAL_EXPONENT: Int = -1074
private const val DOUBLE_SIGN_MASK: ULong = 0x8000_0000_0000_0000UL
private const val DOUBLE_EXPONENT_MASK: ULong = 0x7FFUL
private const val DOUBLE_FRACTION_MASK: ULong = 0x000F_FFFF_FFFF_FFFFUL
private const val DOUBLE_IMPLICIT_BIT: ULong = 0x0010_0000_0000_0000UL
private const val UNSIGNED_INT_BITS: Int = 32
private const val UNSIGNED_INT_MASK: ULong = 0xFFFF_FFFFUL
private val ZERO_EXACT_BINARY_PRODUCT: ExactBinaryProduct = ExactBinaryProduct(
    negative = false,
    significand = Unsigned128(0UL, 0UL),
    exponent = 0,
)
