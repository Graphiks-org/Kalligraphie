@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphAffineTransform
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
 * `glyph<N>`. Supported documents contain `svg`, `g`, `defs`, `linearGradient`, concentric
 * `radialGradient`, bounded single-child `clipPath`, self-closing `stop`, `path`, and `rect`
 * elements. Groups may contain
 * `translate`, `scale`, `rotate(angle)`, `rotate(angle cx cy)`, `skewX(angle)`, `skewY(angle)`, and
 * six-coefficient SVG `matrix` transforms. Rotation and skew angles use SVG degrees.
 * Object-bounding-box and absolute user-space gradients may additionally
 * declare an invertible `gradientTransform` list containing those same operations. A bounded
 * user-space clip definition and its single `path` or sharp-cornered `rect` child may declare the same transform list. At reference
 * time, object-bounding-box paint composes as `T * B * G`, while absolute user-space paint composes
 * as `T * G`; both affect only gradient geometry, never the shape clip. Every non-singular
 * composition must retain the exact determinant
 * orientation of its decoded `Double` factors in the stored result; numeric rank loss or
 * orientation inversion is rejected as invalid data. Paths may use `M`, `L`, `H`, `V`, `C`, `S`,
 * and `Z` commands (and their relative forms).
 * Shapes accept solid `#RRGGBB` or `fill="none"`. Their optional `fill-opacity` is a finite
 * unitless number or percentage, clamped to `0.0..1.0`. A translucent solid normalizes to an
 * unbounded solid under the shape's path clip, while a gradient multiplies each immutable,
 * per-reference color-stop opacity by the shape opacity. A painted shape may reference one
 * preceding local `userSpaceOnUse` clip definition, which wraps the normalized shape paint subtree
 * in an outer path clip under `T * C * P`, where `T` is the shape's effective transform, `C` is the
 * clip definition's transform, and `P` is the clip child transform. The painted shape remains under
 * `T`, and gradient geometry retains its
 * existing `T * G` or `T * B * G` space. Rectangles additionally accept preceding local
 * object-bounding-box or unitless absolute user-space gradients, while paths accept only the
 * absolute user-space form. Supported linear and concentric radial gradients normalize to
 * schema-3 portable paints; a radial coordinate mapping is represented by an explicit transform
 * node.
 * Scripts, network or external references, entities, animation, XML declarations, strokes,
 * masks, other SVG clipping forms, and every unlisted element or attribute are rejected before
 * any [SvgOpenTypeData] is returned.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object SvgOpenTypeReader {
    /**
     * Decodes and validates every document in an OpenType `SVG ` table.
     *
     * [profile] bounds source bytes, document records, authored transform operations, graph nodes,
     * paths, depth, and path geometry before publication. Every complete authored group, gradient,
     * or clip-path definition function call shares the same source-operation budget regardless of
     * operand count and independently of generated paint-graph transform nodes. The operation
     * is all-or-nothing:
     * malformed or unsupported content returns a typed failure and no partial data. The returned
     * value contains no source XML and is safe to retain after the caller releases the source
     * buffer.
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
     * markup, and exact caller limits are certified by [read] during fully normalized SVG asset
     * acquisition, or by [readGlyph] when a covered glyph in a mixed SVG/COLR asset is requested.
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
    private val gradients = linkedMapOf<String, SvgGradient>()
    private val clipPaths = linkedMapOf<String, SvgClipPath>()
    private val elementIds = mutableSetOf<String>()
    private var parsedGradientCount: Int = 0
    private var parsedColorStopCount: Int = 0
    private var pendingGradient: SvgGradientBuilder? = null
    private var pendingClipPath: SvgClipPathBuilder? = null

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
                if (name in GRADIENT_ELEMENTS) {
                    val definition = pendingGradient?.build()
                        ?: return invalid("font.svg.invalid-gradient", "SVG gradient state is invalid.")
                    gradients[definition.id] = definition
                    pendingGradient = null
                }
                if (name == "clipPath") {
                    val definition = pendingClipPath?.build()
                        ?: return unsupported("SVG clipPath must contain exactly one supported path or rect child.")
                    clipPaths[definition.id] = definition
                    pendingClipPath = null
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
                        when (val parsed = parseTransform(attributes.getValue("transform"), "SVG group")) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    } else {
                        AffineTransform.identity
                    }
                    val transform = if (targetGlyphId != null) {
                        local
                    } else {
                        try {
                            parent.transform.then(local)
                        } catch (_: IllegalArgumentException) {
                            return invalid(
                                "font.svg.invalid-transform",
                                "Nested SVG group transform exceeds the portable coordinate domain.",
                            )
                        }
                    }
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

                "clipPath" -> {
                    if (
                        stack.lastOrNull()?.name != "defs" ||
                        selfClosing ||
                        attributes.keys.any { key -> key !in CLIP_PATH_ATTRIBUTES }
                    ) {
                        return unsupported("Only non-empty user-space clipPath definitions directly inside defs are supported.")
                    }
                    if (profile.schemaVersion != 3) {
                        return unsupported("SVG clip paths require paint schema 3.")
                    }
                    val id = attributes["id"]?.takeIf(String::isSvgDefinitionId)
                        ?: return invalid("font.svg.invalid-clip-id", "SVG clipPath requires a valid local id.")
                    if (!elementIds.add(id)) {
                        return invalid("font.svg.duplicate-id", "SVG element ids must be globally unique within a document.")
                    }
                    when (attributes["clipPathUnits"] ?: "userSpaceOnUse") {
                        "userSpaceOnUse" -> Unit
                        else -> return unsupported("Only userSpaceOnUse SVG clip paths are supported.")
                    }
                    val transform = when (
                        val parsed = parseTransform(attributes.getOrElse("transform") { "" }, "SVG clipPath")
                    ) {
                        is FontOperationResult.Success -> parsed.value
                        is FontOperationResult.Failure -> return parsed
                        is FontOperationResult.Cancelled -> return parsed
                    }
                    if (stack.size + 1 > profile.limits.maxDepth) return limit("SVG nesting-depth limit exceeded.")
                    pendingClipPath = SvgClipPathBuilder(id, transform)
                    stack.addLast(SvgElement("clipPath", AffineTransform.identity, null))
                }

                "linearGradient" -> {
                    if (stack.lastOrNull()?.name != "defs") {
                        return unsupported("SVG linearGradient must be a child of defs.")
                    }
                    if (profile.schemaVersion != 3) {
                        return unsupported("SVG linear gradients require paint schema 3.")
                    }
                    if (attributes.keys.any { key -> key !in LINEAR_GRADIENT_ATTRIBUTES }) {
                        return unsupported("SVG linear-gradient attributes outside the static bounded subset are not supported.")
                    }
                    val id = attributes["id"]?.takeIf(String::isSvgDefinitionId)
                        ?: return invalid("font.svg.invalid-gradient-id", "SVG linearGradient requires a valid local id.")
                    if (!elementIds.add(id)) {
                        return invalid("font.svg.duplicate-id", "SVG element ids must be globally unique within a document.")
                    }
                    if (parsedGradientCount >= profile.limits.maxGradients) {
                        return limit("SVG gradient-definition limit exceeded.")
                    }
                    val coordinateSpace = when (attributes.getOrElse("gradientUnits") { "objectBoundingBox" }) {
                        "objectBoundingBox" -> SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX
                        "userSpaceOnUse" -> SvgGradientCoordinateSpace.USER_SPACE_ON_USE
                        else -> return unsupported("SVG linear-gradient coordinate space is not supported.")
                    }
                    val gradientTransform = when (val parsed = parseGradientTransform(attributes, "SVG linear-gradient")) {
                        is FontOperationResult.Success -> parsed.value
                        is FontOperationResult.Failure -> return parsed
                        is FontOperationResult.Cancelled -> return parsed
                    }
                    val x1 = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("x1") { "0%" })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient x1 is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> when (
                            val parsed = parseUserSpaceCoordinate(attributes["x1"], "SVG linear-gradient x1")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    }
                    val y1 = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("y1") { "0%" })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient y1 is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> when (
                            val parsed = parseUserSpaceCoordinate(attributes["y1"], "SVG linear-gradient y1")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    }
                    val x2 = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("x2") { "100%" })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient x2 is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> when (
                            val parsed = parseUserSpaceCoordinate(attributes["x2"], "SVG linear-gradient x2")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    }
                    val y2 = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("y2") { "0%" })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG linear-gradient y2 is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> when (
                            val parsed = parseUserSpaceCoordinate(attributes["y2"], "SVG linear-gradient y2")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    }
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
                        coordinateSpace = coordinateSpace,
                        x1 = x1,
                        y1 = y1,
                        x2 = x2,
                        y2 = y2,
                        transform = gradientTransform,
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

                "radialGradient" -> {
                    if (stack.lastOrNull()?.name != "defs") {
                        return unsupported("SVG radialGradient must be a child of defs.")
                    }
                    if (profile.schemaVersion != 3) {
                        return unsupported("SVG radial gradients require paint schema 3.")
                    }
                    if (attributes.keys.any { key -> key !in RADIAL_GRADIENT_ATTRIBUTES }) {
                        return unsupported("SVG radial-gradient attributes outside the static bounded subset are not supported.")
                    }
                    val id = attributes["id"]?.takeIf(String::isSvgDefinitionId)
                        ?: return invalid("font.svg.invalid-gradient-id", "SVG radialGradient requires a valid local id.")
                    if (!elementIds.add(id)) {
                        return invalid("font.svg.duplicate-id", "SVG element ids must be globally unique within a document.")
                    }
                    if (parsedGradientCount >= profile.limits.maxGradients) {
                        return limit("SVG gradient-definition limit exceeded.")
                    }
                    val coordinateSpace = when (attributes.getOrElse("gradientUnits") { "objectBoundingBox" }) {
                        "objectBoundingBox" -> SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX
                        "userSpaceOnUse" -> SvgGradientCoordinateSpace.USER_SPACE_ON_USE
                        else -> return unsupported("SVG radial-gradient coordinate space is not supported.")
                    }
                    val gradientTransform = when (val parsed = parseGradientTransform(attributes, "SVG radial-gradient")) {
                        is FontOperationResult.Success -> parsed.value
                        is FontOperationResult.Failure -> return parsed
                        is FontOperationResult.Cancelled -> return parsed
                    }
                    val cx = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("cx") { "50%" })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG radial-gradient cx is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> when (
                            val parsed = parseUserSpaceCoordinate(attributes["cx"], "SVG radial-gradient cx")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    }
                    val cy = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("cy") { "50%" })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG radial-gradient cy is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> when (
                            val parsed = parseUserSpaceCoordinate(attributes["cy"], "SVG radial-gradient cy")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    }
                    val parsedRadius = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseSvgFractionWithLexicalSignificance(attributes.getOrElse("r") { "50%" })
                                ?: return invalid("font.svg.invalid-gradient-radius", "SVG radial-gradient radius is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> when (
                            val parsed = parseUserSpaceRadius(attributes["r"])
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                    }
                    if (parsedRadius.hasNegativeNonZeroMantissa) {
                        return invalid("font.svg.invalid-gradient-radius", "SVG radial-gradient radius must be non-negative.")
                    }
                    val radius = parsedRadius.value
                    val fx = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("fx") { attributes.getOrElse("cx") { "50%" } })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG radial-gradient fx is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> attributes["fx"]?.let { value ->
                            when (val parsed = parseUserSpaceCoordinate(value, "SVG radial-gradient fx")) {
                                is FontOperationResult.Success -> parsed.value
                                is FontOperationResult.Failure -> return parsed
                                is FontOperationResult.Cancelled -> return parsed
                            }
                        } ?: cx
                    }
                    val fy = when (coordinateSpace) {
                        SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                            parseObjectBoundingBoxCoordinate(attributes.getOrElse("fy") { attributes.getOrElse("cy") { "50%" } })
                                ?: return invalid("font.svg.invalid-gradient-coordinate", "SVG radial-gradient fy is invalid.")
                        SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> attributes["fy"]?.let { value ->
                            when (val parsed = parseUserSpaceCoordinate(value, "SVG radial-gradient fy")) {
                                is FontOperationResult.Success -> parsed.value
                                is FontOperationResult.Failure -> return parsed
                                is FontOperationResult.Cancelled -> return parsed
                            }
                        } ?: cy
                    }
                    if (radius > 0.0 && (fx != cx || fy != cy)) {
                        return unsupported("Only concentric SVG radial gradients are supported.")
                    }
                    val extendMode = when (attributes.getOrElse("spreadMethod") { "pad" }) {
                        "pad" -> GlyphPaintExtendMode.PAD
                        "repeat" -> GlyphPaintExtendMode.REPEAT
                        "reflect" -> GlyphPaintExtendMode.REFLECT
                        else -> return unsupported("SVG radial-gradient spread method is not supported.")
                    }
                    val interpolationSpace = when (attributes.getOrElse("color-interpolation") { "sRGB" }) {
                        "sRGB" -> GlyphPaintInterpolationSpace.SRGB
                        "linearRGB" -> GlyphPaintInterpolationSpace.LINEAR_SRGB
                        else -> return unsupported("SVG radial-gradient color interpolation is not supported.")
                    }
                    parsedGradientCount += 1
                    val builder = SvgRadialGradientBuilder(
                        id = id,
                        coordinateSpace = coordinateSpace,
                        centerX = cx,
                        centerY = cy,
                        radius = radius,
                        focusX = fx,
                        focusY = fy,
                        transform = gradientTransform,
                        extendMode = extendMode,
                        interpolationSpace = interpolationSpace,
                    )
                    if (selfClosing) {
                        gradients[id] = builder.build()
                    } else {
                        if (stack.size + 1 > profile.limits.maxDepth) return limit("SVG nesting-depth limit exceeded.")
                        pendingGradient = builder
                        stack.addLast(SvgElement("radialGradient", AffineTransform.identity, null))
                    }
                }

                "stop" -> {
                    if (
                        stack.lastOrNull()?.name !in GRADIENT_ELEMENTS ||
                        !selfClosing ||
                        attributes.keys.any { key -> key !in STOP_ATTRIBUTES }
                    ) {
                        return unsupported("Only self-closing stops within a supported gradient are supported.")
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
                        ?: return invalid("font.svg.invalid-gradient", "SVG stop has no active gradient.")
                    parsedColorStopCount += 1
                }

                "path" -> {
                    if (stack.lastOrNull()?.name == "clipPath") {
                        if (
                            !selfClosing ||
                            "d" !in attributes ||
                            attributes.keys.any { key -> key !in CLIP_PATH_CHILD_PATH_ATTRIBUTES }
                        ) {
                            return unsupported("SVG clipPath path child must be self-closing with d and optional transform.")
                        }
                        val pathData = attributes.getValue("d")
                        val commands = when (val parsed = parseRawPath(pathData)) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                        when (val validated = validateRawClipPath(commands, profile)) {
                            is FontOperationResult.Success -> Unit
                            is FontOperationResult.Failure -> return validated
                            is FontOperationResult.Cancelled -> return validated
                        }
                        val childTransform = when (
                            val parsed = parseTransform(attributes.getOrElse("transform") { "" }, "SVG clip path")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                        if (pendingClipPath?.addPath(commands, childTransform) != true) {
                            return unsupported("SVG clipPath must contain exactly one supported path or rect child.")
                        }
                        continue
                    }
                    if (
                        stack.lastOrNull()?.name !in PAINT_CONTAINER_ELEMENTS ||
                        !selfClosing ||
                        attributes.keys.any { key -> key !in PATH_ATTRIBUTES }
                    ) {
                        return unsupported("Only self-closing paths with d and fill attributes are supported.")
                    }
                    val pathData = attributes["d"] ?: return invalid("font.svg.missing-path-data", "SVG path is missing d data.")
                    val fillOpacity = parseSvgFraction(attributes.getOrElse("fill-opacity") { "1" })
                        ?.coerceIn(0.0, 1.0)
                        ?: return invalid("font.svg.invalid-fill-opacity", "SVG fill opacity is invalid.")
                    val clipPath = when (val reference = attributes["clip-path"]) {
                        null -> null
                        else -> {
                            val id = parseLocalPaintReference(reference)
                                ?: return unsupported("SVG clip-path must be a local url(#id) reference.")
                            clipPaths[id]
                                ?: return unsupported("SVG clip-path references must resolve to a preceding local clipPath definition.")
                        }
                    }
                    val fill = attributes["fill"] ?: "#000000"
                    if (fill == "none") continue
                    val solid = parseColor(fill)
                    val gradient = if (solid == null) {
                        val reference = parseLocalPaintReference(fill)
                            ?: return unsupported("Only #RRGGBB or preceding local gradient references are supported for SVG paths.")
                        gradients[reference]
                            ?: return unsupported("SVG path gradient references must resolve to a preceding local definition.")
                    } else {
                        null
                    }
                    val path = when (val parsed = parsePath(pathData, stack.last().transform)) {
                        is FontOperationResult.Success -> parsed.value
                        is FontOperationResult.Failure -> return parsed
                        is FontOperationResult.Cancelled -> return parsed
                    }
                    val clip = when (val materialized = clipPath?.materialize(stack.last().transform)) {
                        null -> null
                        is FontOperationResult.Success -> materialized.value
                        is FontOperationResult.Failure -> return materialized
                        is FontOperationResult.Cancelled -> return materialized
                    }
                    val target = stack.last().glyphTargetId?.let(glyphs::get) ?: unassignedPaint
                    if (solid != null) {
                        if (fillOpacity != 0.0 && clip == null && !stack.last().transform.preservesArea) continue
                        target.appendSolidPath(
                            path = path,
                            color = solid,
                            opacity = fillOpacity,
                            clipPath = clip?.path,
                            omitPaint = !stack.last().transform.preservesArea || clip?.preservesArea == false,
                            profile = profile,
                        )?.let { failure -> return failure }
                        continue
                    }
                    target.appendGradientPath(
                        path = path,
                        clipPath = clip?.path,
                        clipPathPreservesArea = clip?.preservesArea != false,
                        definition = checkNotNull(gradient).withFillOpacity(fillOpacity),
                        rectangle = null,
                        transform = stack.last().transform,
                        omitPaint = fillOpacity == 0.0,
                        profile = profile,
                    )?.let { failure -> return failure }
                }

                "rect" -> {
                    if (stack.lastOrNull()?.name == "clipPath") {
                        if (
                            !selfClosing ||
                            attributes.keys.any { key -> key !in CLIP_PATH_CHILD_RECT_ATTRIBUTES }
                        ) {
                            return unsupported("SVG clipPath rect child must be a self-closing sharp-cornered rectangle with optional transform.")
                        }
                        val x = parseSvgNumber(attributes.getOrElse("x") { "0" })
                            ?: return invalid("font.svg.invalid-rect", "SVG clip rectangle x is invalid.")
                        val y = parseSvgNumber(attributes.getOrElse("y") { "0" })
                            ?: return invalid("font.svg.invalid-rect", "SVG clip rectangle y is invalid.")
                        val parsedWidth = attributes["width"]?.let(::parseSvgNumberWithLexicalSignificance)
                            ?: return invalid("font.svg.invalid-rect", "SVG clip rectangle width is required and must be finite.")
                        val parsedHeight = attributes["height"]?.let(::parseSvgNumberWithLexicalSignificance)
                            ?: return invalid("font.svg.invalid-rect", "SVG clip rectangle height is required and must be finite.")
                        if (
                            parsedWidth.value < 0.0 ||
                            parsedHeight.value < 0.0 ||
                            parsedWidth.hasNegativeNonZeroMantissa ||
                            parsedHeight.hasNegativeNonZeroMantissa
                        ) {
                            return invalid("font.svg.invalid-rect", "SVG clip rectangle dimensions must be non-negative.")
                        }
                        val width = parsedWidth.value
                        val height = parsedHeight.value
                        val commands = rectangleRawPath(x, y, width, height)
                        when (val validated = validateRawClipPath(commands, profile)) {
                            is FontOperationResult.Success -> Unit
                            is FontOperationResult.Failure -> return validated
                            is FontOperationResult.Cancelled -> return validated
                        }
                        val childTransform = when (
                            val parsed = parseTransform(attributes.getOrElse("transform") { "" }, "SVG clip rectangle")
                        ) {
                            is FontOperationResult.Success -> parsed.value
                            is FontOperationResult.Failure -> return parsed
                            is FontOperationResult.Cancelled -> return parsed
                        }
                        if (
                            pendingClipPath?.addPath(
                                commands,
                                childTransform,
                                hasArea = width > 0.0 && height > 0.0,
                            ) != true
                        ) {
                            return unsupported("SVG clipPath must contain exactly one supported path or rect child.")
                        }
                        continue
                    }
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
                    val fillOpacity = parseSvgFraction(attributes.getOrElse("fill-opacity") { "1" })
                        ?.coerceIn(0.0, 1.0)
                        ?: return invalid("font.svg.invalid-fill-opacity", "SVG fill opacity is invalid.")
                    val fill = attributes.getOrElse("fill") { "#000000" }
                    val clipPath = when (val reference = attributes["clip-path"]) {
                        null -> null
                        else -> {
                            val id = parseLocalPaintReference(reference)
                                ?: return unsupported("SVG clip-path must be a local url(#id) reference.")
                            clipPaths[id]
                                ?: return unsupported("SVG clip-path references must resolve to a preceding local clipPath definition.")
                        }
                    }
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
                    if (fillOpacity != 0.0 && clipPath == null && !stack.last().transform.preservesArea) continue
                    val path = when (val result = rectanglePath(x, y, width, height, stack.last().transform)) {
                        is FontOperationResult.Success -> result.value
                        is FontOperationResult.Failure -> return result
                        is FontOperationResult.Cancelled -> return result
                    }
                    val clip = when (val materialized = clipPath?.materialize(stack.last().transform)) {
                        null -> null
                        is FontOperationResult.Success -> materialized.value
                        is FontOperationResult.Failure -> return materialized
                        is FontOperationResult.Cancelled -> return materialized
                    }
                    val target = stack.last().glyphTargetId?.let(glyphs::get) ?: unassignedPaint
                    if (solid != null) {
                        target.appendSolidPath(
                            path = path,
                            color = solid,
                            opacity = fillOpacity,
                            clipPath = clip?.path,
                            omitPaint = !stack.last().transform.preservesArea || clip?.preservesArea == false,
                            profile = profile,
                        )?.let { failure -> return failure }
                        continue
                    }
                    target.appendGradientPath(
                        path = path,
                        clipPath = clip?.path,
                        clipPathPreservesArea = clip?.preservesArea != false,
                        definition = checkNotNull(gradient).withFillOpacity(fillOpacity),
                        rectangle = SvgRectangle(x, y, width, height),
                        transform = stack.last().transform,
                        omitPaint = fillOpacity == 0.0,
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

    private fun parseGradientTransform(
        attributes: Map<String, String>,
        context: String,
    ): FontOperationResult<AffineTransform> {
        val parsed = parseTransform(
            value = attributes.getOrElse("gradientTransform") { "" },
            context = context,
        )
        if (parsed is FontOperationResult.Success && !parsed.value.preservesArea) {
            return unsupported("Singular SVG gradient transforms are not supported.")
        }
        return parsed
    }

    private fun parseTransform(
        value: String,
        context: String,
    ): FontOperationResult<AffineTransform> {
        var cursor = value.skipWhitespace()
        var parsedOperation = false
        val operations = mutableListOf<ParsedSvgTransformOperation>()
        while (cursor < value.length) {
            if (parsedOperation) {
                val separatorStart = cursor
                var containsComma = false
                while (cursor < value.length) {
                    cursor = value.skipWhitespace(cursor)
                    if (value.getOrNull(cursor) != ',') break
                    containsComma = true
                    cursor += 1
                }
                if (cursor == separatorStart) {
                    return invalid("font.svg.invalid-transform", "$context transform list is malformed.")
                }
                if (cursor == value.length) {
                    if (containsComma) return invalid("font.svg.invalid-transform", "$context transform list is malformed.")
                    break
                }
            }
            val nameStart = cursor
            while (cursor < value.length && value[cursor].isSvgAsciiLetter()) cursor += 1
            val name = value.substring(nameStart, cursor)
            cursor = value.skipWhitespace(cursor)
            if (cursor >= value.length || value[cursor] != '(') {
                return invalid("font.svg.invalid-transform", "$context transform is malformed.")
            }
            val close = value.indexOf(')', cursor + 1)
            if (close < 0) return invalid("font.svg.invalid-transform", "$context transform is malformed.")
            val numbers = parseSvgTransformOperands(value.substring(cursor + 1, close))
                ?: return invalid("font.svg.invalid-transform", "$context transform is malformed.")
            var hasUnderflowedNonZeroScale = false
            var hasUnderflowedNonZeroMatrixCoefficient = false
            var hasUnderflowedNonZeroRotationAngle = false
            var hasUnderflowedNonZeroSkewAngle = false
            var hasInvalidSkewAngle = false
            val next = when (name) {
                "translate" -> if (numbers.size in 1..2) {
                    AffineTransform.translate(numbers[0].value, numbers.getOrNull(1)?.value ?: 0.0)
                } else {
                    return invalid("font.svg.invalid-transform", "$context translate transform has invalid operands.")
                }

                "scale" -> if (numbers.size in 1..2) {
                    hasUnderflowedNonZeroScale = numbers.any(ParsedSvgTransformNumber::isUnderflowedNonZero)
                    AffineTransform.scale(numbers[0].value, numbers.getOrElse(1) { numbers[0] }.value)
                } else {
                    return invalid("font.svg.invalid-transform", "$context scale transform has invalid operands.")
                }

                "matrix" -> if (numbers.size == 6) {
                    hasUnderflowedNonZeroMatrixCoefficient =
                        numbers.any(ParsedSvgTransformNumber::isUnderflowedNonZero)
                    AffineTransform.matrix(
                        a = numbers[0].value,
                        b = numbers[1].value,
                        c = numbers[2].value,
                        d = numbers[3].value,
                        e = numbers[4].value,
                        f = numbers[5].value,
                    )
                } else {
                    return invalid("font.svg.invalid-transform", "$context matrix transform has invalid operands.")
                }

                "rotate" -> if (numbers.size == 1 || numbers.size == 3) {
                    hasUnderflowedNonZeroRotationAngle = numbers[0].isUnderflowedNonZero
                    AffineTransform.rotate(
                        degrees = numbers[0].value,
                        centerX = numbers.getOrNull(1)?.value ?: 0.0,
                        centerY = numbers.getOrNull(2)?.value ?: 0.0,
                    )
                } else {
                    return invalid("font.svg.invalid-transform", "$context rotate transform has invalid operands.")
                }

                "skewX", "skewY" -> if (numbers.size == 1) {
                    hasUnderflowedNonZeroSkewAngle = numbers[0].isUnderflowedNonZero
                    val skew = when (name) {
                        "skewX" -> AffineTransform.skewX(numbers[0].value)
                        else -> AffineTransform.skewY(numbers[0].value)
                    }
                    skew ?: run {
                        hasInvalidSkewAngle = true
                        AffineTransform.identity
                    }
                } else {
                    return invalid("font.svg.invalid-transform", "$context $name transform has invalid operands.")
                }

                else -> return invalid("font.svg.invalid-transform", "$context transform function is invalid.")
            }
            if (!transformBudget.tryConsume()) return limit("SVG transform-operation limit exceeded.")
            operations += ParsedSvgTransformOperation(
                transform = next,
                hasUnderflowedNonZeroScale = hasUnderflowedNonZeroScale,
                hasUnderflowedNonZeroMatrixCoefficient = hasUnderflowedNonZeroMatrixCoefficient,
                hasUnderflowedNonZeroRotationAngle = hasUnderflowedNonZeroRotationAngle,
                hasUnderflowedNonZeroSkewAngle = hasUnderflowedNonZeroSkewAngle,
                hasInvalidSkewAngle = hasInvalidSkewAngle,
            )
            cursor = close + 1
            parsedOperation = true
        }
        var transform = AffineTransform.identity
        for (operation in operations) {
            if (operation.hasUnderflowedNonZeroMatrixCoefficient) {
                return invalid(
                    "font.svg.invalid-transform",
                    "$context matrix transform exceeds the portable coordinate domain.",
                )
            }
            if (operation.hasUnderflowedNonZeroScale) {
                return invalid("font.svg.invalid-transform", "$context scale transform exceeds the portable coordinate domain.")
            }
            if (operation.hasUnderflowedNonZeroRotationAngle) {
                return invalid("font.svg.invalid-transform", "$context rotate transform exceeds the portable coordinate domain.")
            }
            if (operation.hasUnderflowedNonZeroSkewAngle || operation.hasInvalidSkewAngle) {
                return invalid("font.svg.invalid-transform", "$context skew transform exceeds the portable coordinate domain.")
            }
            transform = try {
                transform.then(operation.transform)
            } catch (_: IllegalArgumentException) {
                return invalid("font.svg.invalid-transform", "$context transform exceeds the portable coordinate domain.")
            }
        }
        return FontOperationResult.Success(transform)
    }

    private fun parsePath(data: String, transform: AffineTransform): FontOperationResult<GlyphPaintPath> {
        val commands = when (val parsed = parseRawPath(data)) {
            is FontOperationResult.Success -> parsed.value
            is FontOperationResult.Failure -> return parsed
            is FontOperationResult.Cancelled -> return parsed
        }
        return materializePath(commands, transform)
    }

    private fun parseRawPath(data: String): FontOperationResult<List<RawPathCommand>> {
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
        return FontOperationResult.Success(commands.toList())
    }
}

private data class SvgElement(
    val name: String,
    val transform: AffineTransform,
    val glyphTargetId: Int?,
)

private data class SvgClipPath(
    val id: String,
    val commands: List<RawPathCommand>,
    val transform: AffineTransform,
    val childTransform: AffineTransform,
    val hasArea: Boolean,
) {
    fun materialize(referenceTransform: AffineTransform): FontOperationResult<MaterializedSvgClipPath> {
        val effectiveTransform = try {
            referenceTransform.then(transform).then(childTransform)
        } catch (_: IllegalArgumentException) {
            return invalid(
                "font.svg.invalid-transform",
                "SVG clipPath transform composition exceeds the portable coordinate domain.",
            )
        }
        return when (val materialized = materializePath(commands, effectiveTransform)) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                MaterializedSvgClipPath(materialized.value, hasArea && effectiveTransform.preservesArea),
            )
            is FontOperationResult.Failure -> materialized
            is FontOperationResult.Cancelled -> materialized
        }
    }
}

private class SvgClipPathBuilder(
    private val id: String,
    private val transform: AffineTransform,
) {
    private var commands: List<RawPathCommand>? = null
    private var childTransform: AffineTransform? = null
    private var hasArea: Boolean? = null

    fun addPath(
        pathCommands: List<RawPathCommand>,
        pathTransform: AffineTransform,
        hasArea: Boolean = true,
    ): Boolean {
        if (commands != null) return false
        commands = pathCommands.toList()
        childTransform = pathTransform
        this.hasArea = hasArea
        return true
    }

    fun build(): SvgClipPath? = commands?.let { pathCommands ->
        SvgClipPath(id, pathCommands.toList(), transform, checkNotNull(childTransform), checkNotNull(hasArea))
    }
}

private data class MaterializedSvgClipPath(
    val path: GlyphPaintPath,
    val preservesArea: Boolean,
)

private class SvgGlyphPaintBuilder {
    private val nodes: MutableList<GlyphPaintNode> = mutableListOf()
    private val roots: MutableList<Int> = mutableListOf()
    private var internalReferences: Int = 0
    private var pathCount: Int = 0
    private var gradientCount: Int = 0
    private var colorStopCount: Int = 0
    private var transformCount: Int = 0
    private var clipCount: Int = 0
    private var deepestRoot: Int = 0

    fun appendSolidPath(
        path: GlyphPaintPath,
        color: GlyphColor,
        opacity: Double = 1.0,
        clipPath: GlyphPaintPath? = null,
        omitPaint: Boolean = false,
        profile: PaintGraphProfile,
    ): FontOperationResult.Failure? {
        val usesUnboundedSolid = opacity < 1.0
        val requiredPaintKind = if (usesUnboundedSolid) GlyphPaintNodeKind.SOLID else GlyphPaintNodeKind.PATH
        if (requiredPaintKind !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept the normalized SVG solid paint.")
        }
        if ((usesUnboundedSolid || clipPath != null) && GlyphPaintNodeKind.PATH_CLIP !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept SVG path clips.")
        }
        pathLimitFailure(path, profile)?.let { return it }
        if (!path.fits(profile) || clipPath?.fits(profile) == false) {
            return limit("SVG paint graph exceeds the selected outline resource limits.")
        }
        val clipIncrement = if (clipPath == null) 0 else 1
        val shapeClipIncrement = if (usesUnboundedSolid) 1 else 0
        projectedLimitFailure(
            additionalNodes = 1 + shapeClipIncrement + clipIncrement,
            additionalReferences = shapeClipIncrement + clipIncrement,
            additionalPaths = 1 + clipIncrement,
            additionalGradients = 0,
            additionalColorStops = 0,
            additionalTransforms = 0,
            additionalClips = shapeClipIncrement + clipIncrement,
            rootDepth = 1 + shapeClipIncrement + clipIncrement,
            profile = profile,
        )?.let { return it }
        if (omitPaint || opacity == 0.0) return null
        nodes += if (usesUnboundedSolid) GlyphPaintNode.Solid(color, opacity) else GlyphPaintNode.Path(path, color)
        val shapeIndex = nodes.lastIndex
        if (usesUnboundedSolid) {
            nodes += GlyphPaintNode.PathClip(path, shapeIndex)
        }
        if (clipPath != null) {
            nodes += GlyphPaintNode.PathClip(clipPath, nodes.lastIndex)
        }
        roots += nodes.lastIndex
        internalReferences += shapeClipIncrement + clipIncrement
        pathCount += 1 + clipIncrement
        clipCount += shapeClipIncrement + clipIncrement
        deepestRoot = maxOf(deepestRoot, 1 + shapeClipIncrement + clipIncrement)
        return null
    }

    fun appendGradientPath(
        path: GlyphPaintPath,
        clipPath: GlyphPaintPath? = null,
        clipPathPreservesArea: Boolean = true,
        definition: SvgGradient,
        rectangle: SvgRectangle?,
        transform: AffineTransform,
        omitPaint: Boolean = false,
        profile: PaintGraphProfile,
    ): FontOperationResult.Failure? {
        if (rectangle == null && definition.coordinateSpace != SvgGradientCoordinateSpace.USER_SPACE_ON_USE) {
            return unsupported("SVG object-bounding-box gradients are not supported for paths.")
        }
        if (definition.colorStops.isEmpty()) return null
        pathLimitFailure(path, profile)?.let { return it }
        if (!path.fits(profile) || clipPath?.fits(profile) == false) {
            return limit("SVG paint graph exceeds the selected outline resource limits.")
        }
        return when (definition) {
            is SvgLinearGradient ->
                appendLinearGradientPath(path, clipPath, clipPathPreservesArea, definition, rectangle, transform, omitPaint, profile)
            is SvgRadialGradient ->
                appendRadialGradientPath(path, clipPath, clipPathPreservesArea, definition, rectangle, transform, omitPaint, profile)
        }
    }

    private fun appendLinearGradientPath(
        path: GlyphPaintPath,
        clipPath: GlyphPaintPath?,
        clipPathPreservesArea: Boolean,
        definition: SvgLinearGradient,
        rectangle: SvgRectangle?,
        transform: AffineTransform,
        omitPaint: Boolean,
        profile: PaintGraphProfile,
    ): FontOperationResult.Failure? {
        if (definition.colorStops.isEmpty()) return null
        if (GlyphPaintNodeKind.PATH_CLIP !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept SVG path clips.")
        }
        val intrinsicallySolid = definition.colorStops.size == 1 || definition.hasDegenerateVector
        val shapeTransformOmitsPaint = !transform.preservesArea
        val points = if (intrinsicallySolid || shapeTransformOmitsPaint) {
            null
        } else {
            definition.points(rectangle, transform)
                ?: return invalid("font.svg.invalid-gradient", "SVG linear-gradient coordinates exceed the portable domain.")
        }
        val useSolid = intrinsicallySolid ||
            (!shapeTransformOmitsPaint && checkNotNull(points).let { resolved -> resolved.p0 == resolved.p1 })
        if (!shapeTransformOmitsPaint && !useSolid && !checkNotNull(points).formsPlane) {
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
        pathLimitFailure(path, profile)?.let { return it }
        val clipIncrement = if (clipPath == null) 0 else 1
        projectedLimitFailure(
            additionalNodes = 2 + clipIncrement,
            additionalReferences = 1 + clipIncrement,
            additionalPaths = 1 + clipIncrement,
            additionalGradients = if (useSolid) 0 else 1,
            additionalColorStops = if (useSolid) 0 else definition.colorStops.size,
            additionalTransforms = 0,
            additionalClips = 1 + clipIncrement,
            rootDepth = 2 + clipIncrement,
            profile = profile,
        )?.let { return it }
        if (omitPaint || shapeTransformOmitsPaint || !clipPathPreservesArea) return null
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
        val shapeClipIndex = nodes.lastIndex
        if (clipPath != null) {
            nodes += GlyphPaintNode.PathClip(clipPath, shapeClipIndex)
        }
        roots += nodes.lastIndex
        internalReferences += 1 + clipIncrement
        pathCount += 1 + clipIncrement
        if (!useSolid) {
            gradientCount += 1
            colorStopCount += definition.colorStops.size
        }
        clipCount += 1 + clipIncrement
        deepestRoot = maxOf(deepestRoot, 2 + clipIncrement)
        return null
    }

    private fun appendRadialGradientPath(
        path: GlyphPaintPath,
        clipPath: GlyphPaintPath?,
        clipPathPreservesArea: Boolean,
        definition: SvgRadialGradient,
        rectangle: SvgRectangle?,
        transform: AffineTransform,
        omitPaint: Boolean,
        profile: PaintGraphProfile,
    ): FontOperationResult.Failure? {
        if (definition.colorStops.isEmpty()) return null
        if (GlyphPaintNodeKind.PATH_CLIP !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept SVG path clips.")
        }
        val useSolid = definition.colorStops.size == 1 || definition.radius == 0.0
        val requiredPaintKind = if (useSolid) GlyphPaintNodeKind.SOLID else GlyphPaintNodeKind.RADIAL_GRADIENT
        if (requiredPaintKind !in profile.acceptedNodeKinds) {
            return unsupported("The selected paint profile does not accept the normalized SVG gradient paint.")
        }
        if (!useSolid) {
            if (GlyphPaintNodeKind.TRANSFORM !in profile.acceptedNodeKinds) {
                return unsupported("The selected paint profile does not accept the normalized SVG radial-gradient transform.")
            }
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
        val shapeTransformOmitsPaint = !transform.preservesArea
        val paintTransform = if (useSolid || shapeTransformOmitsPaint) {
            null
        } else {
            try {
                when (definition.coordinateSpace) {
                    SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                        transform.then(AffineTransform.objectBoundingBox(checkNotNull(rectangle))).then(definition.transform)
                    SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> transform.then(definition.transform)
                }
            } catch (_: IllegalArgumentException) {
                return invalid("font.svg.invalid-gradient", "SVG radial-gradient transform exceeds the portable domain.")
            }
        }
        pathLimitFailure(path, profile)?.let { return it }
        val clipIncrement = if (clipPath == null) 0 else 1
        projectedLimitFailure(
            additionalNodes = (if (useSolid) 2 else 3) + clipIncrement,
            additionalReferences = (if (useSolid) 1 else 2) + clipIncrement,
            additionalPaths = 1 + clipIncrement,
            additionalGradients = if (useSolid) 0 else 1,
            additionalColorStops = if (useSolid) 0 else definition.colorStops.size,
            additionalTransforms = if (useSolid) 0 else 1,
            additionalClips = 1 + clipIncrement,
            rootDepth = (if (useSolid) 2 else 3) + clipIncrement,
            profile = profile,
        )?.let { return it }
        if (omitPaint || shapeTransformOmitsPaint || !clipPathPreservesArea) return null
        val paintIndex = nodes.size
        nodes += if (useSolid) {
            val finalStop = definition.colorStops.last()
            GlyphPaintNode.Solid(finalStop.color, finalStop.opacity)
        } else {
            GlyphPaintNode.RadialGradient(
                colorLine = GlyphPaintColorLine(
                    extendMode = definition.extendMode,
                    colorStops = definition.colorStops,
                    interpolationSpace = definition.interpolationSpace,
                    alphaInterpolationMode = GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED,
                ),
                c0 = GlyphPaintPoint(definition.focusX, definition.focusY),
                radius0 = 0.0,
                c1 = GlyphPaintPoint(definition.centerX, definition.centerY),
                radius1 = definition.radius,
            )
        }
        val clippedPaintIndex = if (useSolid) {
            paintIndex
        } else {
            nodes += GlyphPaintNode.Transform(
                paint = paintIndex,
                matrix = checkNotNull(paintTransform).toGlyphAffineTransform(),
            )
            nodes.lastIndex
        }
        nodes += GlyphPaintNode.PathClip(path, clippedPaintIndex)
        val shapeClipIndex = nodes.lastIndex
        if (clipPath != null) {
            nodes += GlyphPaintNode.PathClip(clipPath, shapeClipIndex)
        }
        roots += nodes.lastIndex
        internalReferences += (if (useSolid) 1 else 2) + clipIncrement
        pathCount += 1 + clipIncrement
        if (!useSolid) {
            gradientCount += 1
            colorStopCount += definition.colorStops.size
            transformCount += 1
        }
        clipCount += 1 + clipIncrement
        deepestRoot = maxOf(deepestRoot, (if (useSolid) 2 else 3) + clipIncrement)
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

    private fun pathLimitFailure(path: GlyphPaintPath, profile: PaintGraphProfile): FontOperationResult.Failure? =
        if (
            path.contourCount > profile.outlineProfile.maxContours ||
            path.pointCount > profile.outlineProfile.maxPoints ||
            path.estimatedByteSize > profile.outlineProfile.maxBytes
        ) {
            limit("SVG portable path exceeds the selected outline resource limits.")
        } else {
            null
        }

    private fun projectedLimitFailure(
        additionalNodes: Int,
        additionalReferences: Int,
        additionalPaths: Int,
        additionalGradients: Int,
        additionalColorStops: Int,
        additionalTransforms: Int,
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
            transformCount.toLong() + additionalTransforms > profile.limits.maxTransforms ||
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

private sealed interface SvgGradient {
    val id: String
    val coordinateSpace: SvgGradientCoordinateSpace
    val transform: AffineTransform
    val extendMode: GlyphPaintExtendMode
    val interpolationSpace: GlyphPaintInterpolationSpace
    val colorStops: List<GlyphPaintColorStop>
}

private enum class SvgGradientCoordinateSpace {
    OBJECT_BOUNDING_BOX,
    USER_SPACE_ON_USE,
}

private data class SvgLinearGradient(
    override val id: String,
    override val coordinateSpace: SvgGradientCoordinateSpace,
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    override val transform: AffineTransform,
    override val extendMode: GlyphPaintExtendMode,
    override val interpolationSpace: GlyphPaintInterpolationSpace,
    override val colorStops: List<GlyphPaintColorStop>,
) : SvgGradient {
    val hasDegenerateVector: Boolean
        get() = x1 == x2 && y1 == y2

    fun points(rectangle: SvgRectangle?, shapeTransform: AffineTransform): SvgGradientPoints? = try {
        val dx = x2 - x1
        val dy = y2 - y1
        val paintTransform = when (coordinateSpace) {
            SvgGradientCoordinateSpace.OBJECT_BOUNDING_BOX ->
                shapeTransform.then(AffineTransform.objectBoundingBox(checkNotNull(rectangle))).then(transform)
            SvgGradientCoordinateSpace.USER_SPACE_ON_USE -> shapeTransform.then(transform)
        }
        val p0 = paintTransform.apply(Point(x1, y1))
        val p1 = paintTransform.apply(Point(x2, y2))
        val p2 = paintTransform.apply(Point(x1 - dy, y1 + dx))
        SvgGradientPoints(
            GlyphPaintPoint(p0.x, p0.y),
            GlyphPaintPoint(p1.x, p1.y),
            GlyphPaintPoint(p2.x, p2.y),
        )
    } catch (_: IllegalArgumentException) {
        null
    }
}

private data class SvgRadialGradient(
    override val id: String,
    override val coordinateSpace: SvgGradientCoordinateSpace,
    val centerX: Double,
    val centerY: Double,
    val radius: Double,
    val focusX: Double,
    val focusY: Double,
    override val transform: AffineTransform,
    override val extendMode: GlyphPaintExtendMode,
    override val interpolationSpace: GlyphPaintInterpolationSpace,
    override val colorStops: List<GlyphPaintColorStop>,
) : SvgGradient

private fun SvgGradient.withFillOpacity(fillOpacity: Double): SvgGradient {
    val effectiveColorStops = colorStops.map { stop ->
        stop.copy(opacity = stop.opacity * fillOpacity)
    }
    return when (this) {
        is SvgLinearGradient -> copy(colorStops = effectiveColorStops)
        is SvgRadialGradient -> copy(colorStops = effectiveColorStops)
    }
}

private sealed interface SvgGradientBuilder {
    val id: String

    fun addStop(offset: Double, color: GlyphColor, opacity: Double)

    fun build(): SvgGradient
}

private class SvgLinearGradientBuilder(
    override val id: String,
    private val coordinateSpace: SvgGradientCoordinateSpace,
    private val x1: Double,
    private val y1: Double,
    private val x2: Double,
    private val y2: Double,
    private val transform: AffineTransform,
    private val extendMode: GlyphPaintExtendMode,
    private val interpolationSpace: GlyphPaintInterpolationSpace,
) : SvgGradientBuilder {
    private val colorLine = SvgColorLineBuilder(extendMode)

    override fun addStop(offset: Double, color: GlyphColor, opacity: Double): Unit = colorLine.addStop(offset, color, opacity)

    override fun build(): SvgLinearGradient = SvgLinearGradient(
        id = id,
        coordinateSpace = coordinateSpace,
        x1 = x1,
        y1 = y1,
        x2 = x2,
        y2 = y2,
        transform = transform,
        extendMode = extendMode,
        interpolationSpace = interpolationSpace,
        colorStops = colorLine.build(),
    )
}

private class SvgRadialGradientBuilder(
    override val id: String,
    private val coordinateSpace: SvgGradientCoordinateSpace,
    private val centerX: Double,
    private val centerY: Double,
    private val radius: Double,
    private val focusX: Double,
    private val focusY: Double,
    private val transform: AffineTransform,
    private val extendMode: GlyphPaintExtendMode,
    private val interpolationSpace: GlyphPaintInterpolationSpace,
) : SvgGradientBuilder {
    private val colorLine = SvgColorLineBuilder(extendMode)

    override fun addStop(offset: Double, color: GlyphColor, opacity: Double): Unit = colorLine.addStop(offset, color, opacity)

    override fun build(): SvgRadialGradient = SvgRadialGradient(
        id = id,
        coordinateSpace = coordinateSpace,
        centerX = centerX,
        centerY = centerY,
        radius = radius,
        focusX = focusX,
        focusY = focusY,
        transform = transform,
        extendMode = extendMode,
        interpolationSpace = interpolationSpace,
        colorStops = colorLine.build(),
    )
}

private class SvgColorLineBuilder(
    private val extendMode: GlyphPaintExtendMode,
) {
    private val colorStops = mutableListOf<GlyphPaintColorStop>()

    fun addStop(offset: Double, color: GlyphColor, opacity: Double) {
        val normalizedOffset = maxOf(colorStops.lastOrNull()?.offset ?: 0.0, offset.coerceIn(0.0, 1.0))
        colorStops += GlyphPaintColorStop(normalizedOffset, color, opacity.coerceIn(0.0, 1.0))
    }

    fun build(): List<GlyphPaintColorStop> = if (colorStops.size > 1 && extendMode != GlyphPaintExtendMode.PAD) {
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
}

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

private fun rectangleRawPath(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
): List<RawPathCommand> = listOf(
    RawPathCommand.MoveTo(Point(x, y)),
    RawPathCommand.LineTo(Point(x + width, y)),
    RawPathCommand.LineTo(Point(x + width, y + height)),
    RawPathCommand.LineTo(Point(x, y + height)),
    RawPathCommand.Close,
)

private fun validateRawClipPath(
    commands: List<RawPathCommand>,
    profile: PaintGraphProfile,
): FontOperationResult<Unit> {
    var contourOpen = false
    var pointCount = 0L
    var contourCount = 0L
    var byteWeight = 32

    fun addWeight(weight: Int) {
        byteWeight = if (byteWeight > Int.MAX_VALUE - weight) Int.MAX_VALUE else byteWeight + weight
    }

    fun invalidPath(): FontOperationResult.Failure =
        invalid("font.svg.invalid-path", "SVG path coordinates exceed the portable path domain.")

    fun Point.isFiniteCoordinate(): Boolean = x.isFinite() && y.isFinite()

    for (command in commands) {
        when (command) {
            is RawPathCommand.MoveTo -> {
                if (contourOpen || !command.point.isFiniteCoordinate()) return invalidPath()
                contourOpen = true
                pointCount += 1
                addWeight(16)
            }

            is RawPathCommand.LineTo -> {
                if (!contourOpen || !command.point.isFiniteCoordinate()) return invalidPath()
                pointCount += 1
                addWeight(16)
            }

            is RawPathCommand.CubicTo -> {
                if (
                    !contourOpen ||
                    !command.control1.isFiniteCoordinate() ||
                    !command.control2.isFiniteCoordinate() ||
                    !command.endpoint.isFiniteCoordinate()
                ) {
                    return invalidPath()
                }
                pointCount += 3
                addWeight(48)
            }

            RawPathCommand.Close -> {
                if (!contourOpen) return invalidPath()
                contourOpen = false
                contourCount += 1
                addWeight(1)
            }
        }
    }
    if (commands.isEmpty() || contourOpen) return invalidPath()
    if (
        pointCount > profile.outlineProfile.maxPoints ||
        contourCount > profile.outlineProfile.maxContours ||
        byteWeight > profile.outlineProfile.maxBytes
    ) {
        return limit("SVG clip path exceeds the selected outline resource limits.")
    }
    return FontOperationResult.Success(Unit)
}

private fun materializePath(
    commands: List<RawPathCommand>,
    transform: AffineTransform,
): FontOperationResult<GlyphPaintPath> = try {
    FontOperationResult.Success(GlyphPaintPath(commands.map { command -> command.materialize(transform) }))
} catch (_: IllegalArgumentException) {
    invalid("font.svg.invalid-path", "SVG path coordinates exceed the portable path domain.")
}

private fun GlyphPaintPath.fits(profile: PaintGraphProfile): Boolean =
    pointCount <= profile.outlineProfile.maxPoints &&
        contourCount <= profile.outlineProfile.maxContours &&
        estimatedByteSize <= profile.outlineProfile.maxBytes

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

    fun singleNumber(): Double? = singleNumberWithLexicalSignificance()?.value

    fun singleNumberWithLexicalSignificance(): ParsedSvgNumber? {
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
        val literal = source.substring(start, end)
        val mantissa = literal.removePrefix("-").removePrefix("+").substringBefore('e').substringBefore('E')
        val hasNegativeNonZeroMantissa = literal.startsWith('-') && mantissa.any { digit -> digit in '1'..'9' }
        val value = literal.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        return ParsedSvgNumber(value, hasNegativeNonZeroMantissa)
    }
}

private data class ParsedSvgNumber(
    val value: Double,
    val hasNegativeNonZeroMantissa: Boolean,
)

private data class ParsedSvgTransformNumber(
    val value: Double,
    val nextIndex: Int,
    val hasNonZeroMantissa: Boolean,
) {
    val isUnderflowedNonZero: Boolean
        get() = value == 0.0 && hasNonZeroMantissa
}

private data class ParsedSvgTransformOperation(
    val transform: AffineTransform,
    val hasUnderflowedNonZeroScale: Boolean,
    val hasUnderflowedNonZeroMatrixCoefficient: Boolean,
    val hasUnderflowedNonZeroRotationAngle: Boolean,
    val hasUnderflowedNonZeroSkewAngle: Boolean,
    val hasInvalidSkewAngle: Boolean,
)

private fun parseSvgTransformOperands(source: String): List<ParsedSvgTransformNumber>? {
    val values = mutableListOf<ParsedSvgTransformNumber>()
    var index = source.skipWhitespace()
    while (index < source.length) {
        val parsed = parseSvgTransformNumber(source, index) ?: return null
        values += parsed
        index = parsed.nextIndex
        val separatorStart = index
        index = source.skipWhitespace(index)
        val hadWhitespace = index > separatorStart
        if (index == source.length) break
        if (source[index] == ',') {
            index = source.skipWhitespace(index + 1)
            if (index == source.length || source.getOrNull(index) == ',') return null
        } else if (!hadWhitespace) {
            return null
        }
    }
    return values
}

private fun parseSvgTransformNumber(source: String, start: Int): ParsedSvgTransformNumber? {
    var index = start
    if (source.getOrNull(index) in setOf('+', '-')) index += 1
    val integerStart = index
    while (source.getOrNull(index)?.isSvgAsciiDigit() == true) index += 1
    val hasIntegerDigits = index > integerStart
    if (source.getOrNull(index) == '.') {
        index += 1
        val fractionStart = index
        while (source.getOrNull(index)?.isSvgAsciiDigit() == true) index += 1
        if (!hasIntegerDigits && index == fractionStart) return null
    } else if (!hasIntegerDigits) {
        return null
    }
    if (source.getOrNull(index) in setOf('e', 'E')) {
        index += 1
        if (source.getOrNull(index) in setOf('+', '-')) index += 1
        val exponentStart = index
        while (source.getOrNull(index)?.isSvgAsciiDigit() == true) index += 1
        if (index == exponentStart) return null
    }
    val literal = source.substring(start, index)
    val value = literal.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    val mantissa = literal.removePrefix("-").removePrefix("+").substringBefore('e').substringBefore('E')
    return ParsedSvgTransformNumber(value, index, mantissa.any { digit -> digit in '1'..'9' })
}

private fun Char.isSvgAsciiDigit(): Boolean = this in '0'..'9'

private fun Char.isSvgAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun Char.isSvgWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

private data class AffineTransform(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
    private val determinantOrientation: DeterminantOrientation,
) {
    fun then(next: AffineTransform): AffineTransform {
        val composedA = a * next.a + c * next.b
        val composedB = b * next.a + d * next.b
        val composedC = a * next.c + c * next.d
        val composedD = b * next.c + d * next.d
        val composedE = a * next.e + c * next.f + e
        val composedF = b * next.e + d * next.f + f
        require(
            composedA.isFinite() && composedB.isFinite() && composedC.isFinite() &&
                composedD.isFinite() && composedE.isFinite() && composedF.isFinite(),
        ) { "SVG transform is not finite." }
        val expectedOrientation = determinantOrientation * next.determinantOrientation
        val storedOrientation = exactDeterminantOrientation(composedA, composedB, composedC, composedD)
        require(expectedOrientation == DeterminantOrientation.SINGULAR || storedOrientation == expectedOrientation) {
            "SVG transform composition lost area or reversed orientation in the portable coordinate domain."
        }
        return AffineTransform(
            a = composedA,
            b = composedB,
            c = composedC,
            d = composedD,
            e = composedE,
            f = composedF,
            determinantOrientation = expectedOrientation,
        )
    }

    fun apply(point: Point): Point = Point(a * point.x + c * point.y + e, b * point.x + d * point.y + f)

    val preservesArea: Boolean
        get() = determinantOrientation != DeterminantOrientation.SINGULAR

    fun toGlyphAffineTransform(): GlyphAffineTransform = GlyphAffineTransform(a, b, c, d, e, f)

    companion object {
        val identity: AffineTransform = matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        fun translate(x: Double, y: Double): AffineTransform = matrix(1.0, 0.0, 0.0, 1.0, x, y)
        fun scale(x: Double, y: Double): AffineTransform = matrix(x, 0.0, 0.0, y, 0.0, 0.0)
        fun rotate(degrees: Double, centerX: Double, centerY: Double): AffineTransform {
            val normalizedDegrees = degrees % 360.0
            val (cosine, sine) = when (normalizedDegrees) {
                0.0 -> 1.0 to 0.0
                90.0, -270.0 -> 0.0 to 1.0
                180.0, -180.0 -> -1.0 to 0.0
                270.0, -90.0 -> 0.0 to -1.0
                else -> {
                    val radians = normalizedDegrees * kotlin.math.PI / 180.0
                    kotlin.math.cos(radians) to kotlin.math.sin(radians)
                }
            }
            return matrix(
                a = cosine,
                b = sine,
                c = -sine,
                d = cosine,
                e = centerX - cosine * centerX + sine * centerY,
                f = centerY - sine * centerX - cosine * centerY,
            )
        }

        fun skewX(degrees: Double): AffineTransform? =
            skewTangent(degrees)?.let { tangent -> matrix(1.0, 0.0, tangent, 1.0, 0.0, 0.0) }

        fun skewY(degrees: Double): AffineTransform? =
            skewTangent(degrees)?.let { tangent -> matrix(1.0, tangent, 0.0, 1.0, 0.0, 0.0) }

        private fun skewTangent(degrees: Double): Double? {
            val normalizedDegrees = degrees % 180.0
            val tangent = when (normalizedDegrees) {
                0.0 -> 0.0
                45.0, -135.0 -> 1.0
                -45.0, 135.0 -> -1.0
                90.0, -90.0 -> return null
                else -> kotlin.math.tan(normalizedDegrees * kotlin.math.PI / 180.0)
            }
            return tangent.takeIf(Double::isFinite)
        }

        fun matrix(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double): AffineTransform =
            AffineTransform(a, b, c, d, e, f, exactDeterminantOrientation(a, b, c, d))

        fun objectBoundingBox(rectangle: SvgRectangle): AffineTransform =
            matrix(rectangle.width, 0.0, 0.0, rectangle.height, rectangle.x, rectangle.y)
    }
}

private fun formsNonDegenerateBasis(
    firstX: Double,
    firstY: Double,
    secondX: Double,
    secondY: Double,
): Boolean {
    return exactDeterminantOrientation(firstX, firstY, secondX, secondY) != DeterminantOrientation.SINGULAR
}

private enum class DeterminantOrientation {
    POSITIVE,
    NEGATIVE,
    SINGULAR,
    ;

    operator fun times(other: DeterminantOrientation): DeterminantOrientation = when {
        this == SINGULAR || other == SINGULAR -> SINGULAR
        this == other -> POSITIVE
        else -> NEGATIVE
    }
}

private fun exactDeterminantOrientation(a: Double, b: Double, c: Double, d: Double): DeterminantOrientation {
    if (!a.isFinite() || !b.isFinite() || !c.isFinite() || !d.isFinite()) {
        return DeterminantOrientation.SINGULAR
    }
    val comparison = compareExactBinaryProducts(exactBinaryProduct(a, d), exactBinaryProduct(b, c))
    return when {
        comparison == 0 -> DeterminantOrientation.SINGULAR
        comparison > 0 -> DeterminantOrientation.POSITIVE
        else -> DeterminantOrientation.NEGATIVE
    }
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

private fun compareExactBinaryProducts(first: ExactBinaryProduct, second: ExactBinaryProduct): Int {
    if (first == second) return 0
    val firstIsZero = first.significand.isZero
    val secondIsZero = second.significand.isZero
    if (firstIsZero) return if (second.negative) 1 else -1
    if (secondIsZero) return if (first.negative) -1 else 1
    if (first.negative != second.negative) return if (first.negative) -1 else 1
    val magnitudeComparison = compareExactBinaryMagnitudes(first, second)
    return if (first.negative) -magnitudeComparison else magnitudeComparison
}

private fun compareExactBinaryMagnitudes(first: ExactBinaryProduct, second: ExactBinaryProduct): Int {
    val firstTopExponent = first.exponent + first.significand.bitLength
    val secondTopExponent = second.exponent + second.significand.bitLength
    if (firstTopExponent != secondTopExponent) return firstTopExponent.compareTo(secondTopExponent)
    val commonExponent = minOf(first.exponent, second.exponent)
    val alignedFirst = first.significand.shiftLeft(first.exponent - commonExponent)
    val alignedSecond = second.significand.shiftLeft(second.exponent - commonExponent)
    return alignedFirst.compareTo(alignedSecond)
}

private val Unsigned128.isZero: Boolean
    get() = high == 0UL && low == 0UL

private val Unsigned128.bitLength: Int
    get() = if (high != 0UL) {
        ULong.SIZE_BITS + (ULong.SIZE_BITS - high.countLeadingZeroBits())
    } else {
        ULong.SIZE_BITS - low.countLeadingZeroBits()
    }

private fun Unsigned128.shiftLeft(distance: Int): Unsigned128 {
    require(distance >= 0 && bitLength + distance <= ULong.SIZE_BITS * 2)
    if (distance == 0 || isZero) return this
    return if (distance < ULong.SIZE_BITS) {
        Unsigned128(
            high = (high shl distance) or (low shr (ULong.SIZE_BITS - distance)),
            low = low shl distance,
        )
    } else {
        Unsigned128(high = low shl (distance - ULong.SIZE_BITS), low = 0UL)
    }
}

private operator fun Unsigned128.compareTo(other: Unsigned128): Int = when {
    high != other.high -> high.compareTo(other.high)
    else -> low.compareTo(other.low)
}

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
    return parseSvgNumberWithLexicalSignificance(value)?.value
}

private fun parseSvgNumberWithLexicalSignificance(value: String): ParsedSvgNumber? {
    val text = value.trimSvgWhitespace()
    if (text.isEmpty() || text.endsWith('%')) return null
    return SvgNumberCursor(text).singleNumberWithLexicalSignificance()
}

private fun parseSvgFraction(value: String): Double? {
    return parseSvgFractionWithLexicalSignificance(value)?.value
}

private fun parseSvgFractionWithLexicalSignificance(value: String): ParsedSvgNumber? {
    val text = value.trimSvgWhitespace()
    if (text.isEmpty()) return null
    val percentage = text.endsWith('%')
    if (percentage && text.getOrNull(text.lastIndex - 1)?.isSvgWhitespace() == true) return null
    val numberText = if (percentage) text.dropLast(1) else text
    val parsed = SvgNumberCursor(numberText).singleNumberWithLexicalSignificance() ?: return null
    return parsed.copy(value = if (percentage) parsed.value / 100.0 else parsed.value)
}

private fun parseObjectBoundingBoxCoordinate(value: String): Double? = parseSvgFraction(value)

private fun parseUserSpaceCoordinate(value: String?, name: String): FontOperationResult<Double> {
    if (value == null) {
        return unsupported("$name uses a viewport-dependent default outside the supported subset.")
    }
    val text = value.trimSvgWhitespace()
    if (text.endsWith('%')) {
        return if (parseSvgFractionWithLexicalSignificance(value) != null) {
            unsupported("$name percentages are outside the supported subset.")
        } else {
            invalid("font.svg.invalid-gradient-coordinate", "$name is invalid.")
        }
    }
    val coordinate = parseSvgNumber(value)
        ?: return invalid("font.svg.invalid-gradient-coordinate", "$name is invalid.")
    return FontOperationResult.Success(coordinate)
}

private fun parseUserSpaceRadius(value: String?): FontOperationResult<ParsedSvgNumber> {
    if (value == null) {
        return unsupported("SVG radial-gradient radius uses a viewport-dependent default outside the supported subset.")
    }
    val text = value.trimSvgWhitespace()
    if (text.endsWith('%')) {
        val radius = parseSvgFractionWithLexicalSignificance(value)
            ?: return invalid("font.svg.invalid-gradient-radius", "SVG radial-gradient radius is invalid.")
        if (radius.hasNegativeNonZeroMantissa) {
            return invalid("font.svg.invalid-gradient-radius", "SVG radial-gradient radius must be non-negative.")
        }
        return unsupported("SVG radial-gradient radius percentages are outside the supported subset.")
    }
    val radius = SvgNumberCursor(text).singleNumberWithLexicalSignificance()
        ?: return invalid("font.svg.invalid-gradient-radius", "SVG radial-gradient radius is invalid.")
    return FontOperationResult.Success(radius)
}

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
private val PATH_ATTRIBUTES: Set<String> = setOf("d", "fill", "fill-opacity", "clip-path")
private val RECT_ATTRIBUTES: Set<String> = setOf("x", "y", "width", "height", "fill", "fill-opacity", "clip-path")
private val CLIP_PATH_ATTRIBUTES: Set<String> = setOf("id", "clipPathUnits", "transform")
private val CLIP_PATH_CHILD_PATH_ATTRIBUTES: Set<String> = setOf("d", "transform")
private val CLIP_PATH_CHILD_RECT_ATTRIBUTES: Set<String> = setOf("x", "y", "width", "height", "transform")
private val LINEAR_GRADIENT_ATTRIBUTES: Set<String> = setOf(
    "id",
    "x1",
    "y1",
    "x2",
    "y2",
    "gradientUnits",
    "spreadMethod",
    "color-interpolation",
    "gradientTransform",
)
private val RADIAL_GRADIENT_ATTRIBUTES: Set<String> = setOf(
    "id",
    "cx",
    "cy",
    "r",
    "fx",
    "fy",
    "gradientUnits",
    "spreadMethod",
    "color-interpolation",
    "gradientTransform",
)
private val STOP_ATTRIBUTES: Set<String> = setOf("offset", "stop-color", "stop-opacity")
private val GRADIENT_ELEMENTS: Set<String> = setOf("linearGradient", "radialGradient")
private val CONTAINER_ELEMENTS: Set<String> = setOf("svg", "g", "defs", "clipPath") + GRADIENT_ELEMENTS
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
