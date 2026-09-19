@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackendIdentity
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingDistributionProvenance
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceLimit
import org.graphiks.kalligraphie.api.ShapingSafetyFlags
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kffi.harfbuzz.HarfBuzz
import org.graphiks.kffi.harfbuzz.HarfBuzzBindingException
import org.graphiks.kffi.harfbuzz.HarfBuzzBindingFailure
import org.graphiks.kffi.harfbuzz.HarfBuzzBlob
import org.graphiks.kffi.harfbuzz.HarfBuzzBuffer
import org.graphiks.kffi.harfbuzz.HarfBuzzBufferFlags
import org.graphiks.kffi.harfbuzz.HarfBuzzClusterLevel
import org.graphiks.kffi.harfbuzz.HarfBuzzDirection
import org.graphiks.kffi.harfbuzz.HarfBuzzFace
import org.graphiks.kffi.harfbuzz.HarfBuzzFeature
import org.graphiks.kffi.harfbuzz.HarfBuzzFont
import org.graphiks.kffi.harfbuzz.HarfBuzzTag

/** Thin adapter over the published kffi HarfBuzz binding. */
internal class HarfBuzzBindings private constructor(private val hb: HarfBuzz) {

    /** The semantic identity is unchanged; only the provenance artifactId names the kffi publication. */
    val identity: ShapingBackendIdentity = ShapingBackendIdentity(
        semantic = HARFBUZZ_SEMANTIC_IDENTITY,
        provenance = ShapingDistributionProvenance(
            operatingSystem = hb.bindingIdentity.operatingSystem,
            architecture = hb.bindingIdentity.architecture,
            artifactId = hb.bindingIdentity.artifactId,
            artifactSha256 = hb.bindingIdentity.artifactSha256,
            sourceProject = "harfbuzz",
            sourceRevision = hb.bindingIdentity.upstreamSourceRevision,
            buildChainIdentity = hb.bindingIdentity.buildChainIdentity,
        ),
    )

    fun prepare(fontBytes: ByteArray, faceIndex: Int, layoutSize: Float): PreparedHarfBuzzFont {
        val blob = hb.createBlob(fontBytes)
        var face: HarfBuzzFace? = null
        var font: HarfBuzzFont? = null
        try {
            face = blob.createFace(faceIndex)
            val designToLayout = DesignToLayoutScale.create(layoutSize, face.unitsPerEm())
            font = face.createFont()
            font.useOpenTypeFunctions()
            font.setScale(designToLayout.unitsPerEm, designToLayout.unitsPerEm)
            face.makeImmutable()
            font.makeImmutable()
            return PreparedHarfBuzzFont(this, blob, face, font, designToLayout)
        } catch (error: Throwable) {
            font?.close()
            face?.close()
            blob.close()
            throw error
        }
    }

    fun shape(request: ShapingRequest, preparedFont: PreparedHarfBuzzFont): ShapedGlyphRun {
        val buffer = hb.createBuffer()
        try {
            configureBuffer(buffer, request)
            val scalarTable = mutableListOf<ContextScalar>()
            val itemScalarRanges = mutableListOf<TextRange>()
            val textLength = request.snapshot.scalarCount(request.contextRange)
            val text = IntArray(textLength)
            var itemOffset = 0
            var token = 0
            request.snapshot.forEachScalar(request.contextRange) { scalar, scalarRange ->
                val contextToken = token
                token += 1
                observeCancellation(request, contextToken)
                text[contextToken] = scalar
                val belongsToItem = scalarRange.start >= request.itemRange.start &&
                    scalarRange.endExclusive <= request.itemRange.endExclusive
                if (scalarRange.endExclusive <= request.itemRange.start) itemOffset += 1
                val itemToken = if (belongsToItem) {
                    val t = ShaperClusterToken(itemScalarRanges.size)
                    itemScalarRanges += scalarRange
                    t
                } else null
                scalarTable += ContextScalar(sourceRange = scalarRange, itemToken = itemToken)
            }
            buffer.addUtf32(text, itemOffset, itemScalarRanges.size)
            observeCancellation(request)
            val features = request.features.map { HarfBuzzFeature(HarfBuzzTag.of(it.tag), it.value) }
            observeCancellation(request)
            val accepted = buffer.shape(preparedFont.font, features)
            observeCancellation(request)
            check(accepted) { "HarfBuzz did not accept the explicit OpenType shaper configuration." }
            return shapedRun(request, preparedFont, buffer, scalarTable, itemScalarRanges)
        } finally {
            buffer.close()
        }
    }

    fun release(prepared: PreparedHarfBuzzFont) {
        val failures = buildList {
            runCatching { prepared.font.close() }.exceptionOrNull()?.let(::add)
            runCatching { prepared.face.close() }.exceptionOrNull()?.let(::add)
            runCatching { prepared.blob.close() }.exceptionOrNull()?.let(::add)
        }
        aggregateFailures(failures)?.let { throw it }
    }

    private fun configureBuffer(buffer: HarfBuzzBuffer, request: ShapingRequest) {
        buffer.setDirection(request.direction.toKffiDirection())
        buffer.setScript(hb.parseScript(request.script.value))
        buffer.setLanguage(hb.parseLanguage(request.language))
        buffer.setClusterLevel(HarfBuzzClusterLevel.MONOTONE_CHARACTERS)
        buffer.setFlags(
            HarfBuzzBufferFlags(
                beginningOfText = request.bot,
                endOfText = request.eot,
                produceUnsafeToConcat = true,
            ),
        )
    }

    private fun shapedRun(
        request: ShapingRequest,
        prepared: PreparedHarfBuzzFont,
        buffer: HarfBuzzBuffer,
        scalarTable: List<ContextScalar>,
        itemScalarRanges: List<TextRange>,
    ): ShapedGlyphRun {
        val glyphCount = buffer.glyphCount()
        if (glyphCount > request.resourceProfile.maxGlyphs) {
            throw ShapingLimitExceeded(ShapingResourceLimit.GLYPHS, glyphCount)
        }
        observeCancellation(request)
        val infos = buffer.glyphInfos()
        val positions = buffer.glyphPositions()
        val glyphRecords = List(glyphCount) { glyphIndex ->
            observeCancellation(request, glyphIndex)
            val info = infos[glyphIndex]
            val position = positions[glyphIndex]
            require(info.cluster in scalarTable.indices) {
                "HarfBuzz returned a cluster token outside this shaping context."
            }
            val itemToken = requireNotNull(scalarTable[info.cluster].itemToken) {
                "A published glyph must be wholly attributable to ShapingRequest.itemRange."
            }
            NativeGlyphRecord(
                glyphId = info.glyphId,
                tokenValue = itemToken.value,
                safetyMask = (if (info.flags.unsafeToBreak) HB_GLYPH_FLAG_UNSAFE_TO_BREAK else 0) or
                    (if (info.flags.unsafeToConcat) HB_GLYPH_FLAG_UNSAFE_TO_CONCAT else 0),
                xAdvance = position.xAdvance,
                yAdvance = position.yAdvance,
                xOffset = position.xOffset,
                yOffset = position.yOffset,
            )
        }
        val clusters = buildClusters(request, itemScalarRanges, glyphRecords)
        val clustersByToken = clusters.associateBy { cluster -> cluster.token }
        val glyphs = glyphRecords.mapIndexed { glyphIndex, record ->
            observeCancellation(request, glyphIndex)
            val flags = ShapingSafetyFlags(
                unsafeToBreak = record.safetyMask and HB_GLYPH_FLAG_UNSAFE_TO_BREAK != 0,
                unsafeToConcat = record.safetyMask and HB_GLYPH_FLAG_UNSAFE_TO_CONCAT != 0,
            )
            ShapedGlyph(
                glyphId = GlyphId(record.glyphId),
                xAdvance = prepared.designToLayout.convert(record.xAdvance),
                yAdvance = record.yAdvance.toPhysicalVerticalCoordinate(request.direction, prepared.designToLayout),
                xOffset = prepared.designToLayout.convert(record.xOffset),
                yOffset = record.yOffset.toPhysicalVerticalCoordinate(request.direction, prepared.designToLayout),
                safetyFlags = flags,
                clusterTokens = listOf(ShaperClusterToken(record.tokenValue)),
            )
        }
        val caretFacts = glyphRecords.mapIndexedNotNull { glyphIndex, record ->
            observeCancellation(request, glyphIndex)
            val cluster = clustersByToken.getValue(ShaperClusterToken(record.tokenValue))
            if (cluster.internalAdmissibleGraphemeBoundaries().isEmpty()) {
                null
            } else {
                ligatureCaretFact(
                    prepared.font,
                    request.direction,
                    record.glyphId,
                    glyphIndex,
                    cluster,
                    finalAdvanceMatchesUnshapedAdvance = advancesMatch(
                        shapedAdvance = record.xAdvance,
                        unshapedAdvance = prepared.font.glyphHorizontalAdvance(record.glyphId),
                    ),
                    prepared.designToLayout,
                )
            }
        }
        return ShapedGlyphRun(
            range = request.itemRange,
            fontInstanceKey = request.font.key,
            backendIdentity = identity,
            direction = request.direction,
            script = request.script,
            language = request.language,
            bidiLevel = request.bidiLevel,
            bot = request.bot,
            eot = request.eot,
            featurePolicy = request.featurePolicy,
            features = request.features,
            graphemeClusters = request.graphemeClusters,
            glyphs = glyphs,
            clusters = clusters,
            ligatureCaretFacts = caretFacts,
        )
    }

    private fun ligatureCaretFact(
        font: HarfBuzzFont,
        direction: ShapingDirection,
        glyphId: Int,
        glyphIndex: Int,
        cluster: ShaperCluster,
        finalAdvanceMatchesUnshapedAdvance: Boolean,
        designToLayout: DesignToLayoutScale,
    ): GdefLigatureCaretFact {
        val expectedCaretCount = cluster.internalAdmissibleGraphemeBoundaries().size
        val carets = font.ligatureCarets(direction.toKffiDirection(), glyphId, 0, expectedCaretCount)
        val safelyReadableCount = carets.copiedCount.coerceIn(0, expectedCaretCount)
        return LigatureCaretFactInterpreter.fromNativeResponse(
            glyphIndex = glyphIndex,
            direction = direction,
            cluster = cluster,
            response = NativeLigatureCaretResponse(
                totalCount = carets.totalCount,
                copiedCount = carets.copiedCount,
                finalAdvanceMatchesUnshapedAdvance = finalAdvanceMatchesUnshapedAdvance,
                positions = List(safelyReadableCount) { index ->
                    designToLayout.convert(carets.positions[index])
                },
            ),
        )
    }

    companion object {
        fun open(): FontOperationResult<HarfBuzzBindings> = try {
            FontOperationResult.Success(HarfBuzzBindings(HarfBuzz.open()))
        } catch (failure: HarfBuzzBindingException) {
            bindingFailureError(failure)
        }
    }
}

/** An open prepared font: owns the kffi blob/face/font and the design-to-layout scale. */
internal class PreparedHarfBuzzFont(
    private val bindings: HarfBuzzBindings,
    internal val blob: HarfBuzzBlob,
    internal val face: HarfBuzzFace,
    internal val font: HarfBuzzFont,
    internal val designToLayout: DesignToLayoutScale,
) {
    fun close() {
        bindings.release(this)
    }
}

internal fun bindingFailureError(failure: HarfBuzzBindingException): FontOperationResult.Failure {
    val code = when (failure.failure) {
        HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM -> "font.shaping-native-platform-unsupported"
        HarfBuzzBindingFailure.RESOURCE_MISSING -> "font.shaping-native-resource-missing"
        HarfBuzzBindingFailure.RESOURCE_CORRUPT -> "font.shaping-native-resource-corrupt"
        HarfBuzzBindingFailure.LIBRARY_LOAD,
        HarfBuzzBindingFailure.SYMBOL_RESOLUTION,
        -> "font.shaping-native-load-failed"
        HarfBuzzBindingFailure.VERSION_MISMATCH -> "font.shaping-native-version-mismatch"
        HarfBuzzBindingFailure.NATIVE_OPERATION -> "font.shaping-native-failure"
    }
    return shapingFailure(code, failure.message ?: "The bundled HarfBuzz binding failed.")
}

private fun ShapingDirection.toKffiDirection(): HarfBuzzDirection = when (this) {
    ShapingDirection.LEFT_TO_RIGHT -> HarfBuzzDirection.LEFT_TO_RIGHT
    ShapingDirection.RIGHT_TO_LEFT -> HarfBuzzDirection.RIGHT_TO_LEFT
    ShapingDirection.TOP_TO_BOTTOM -> HarfBuzzDirection.TOP_TO_BOTTOM
}
