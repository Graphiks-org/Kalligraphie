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

/**
 * Common adapter over the platform HarfBuzz binding.
 *
 * The typographic adaptation (buffer configuration, cluster construction, GDEF ligature-caret
 * interpretation and design-to-layout conversion) is shared; the native operations stay behind
 * [HarfBuzzPlatformBinding]. The adapter owns prepared fonts and releases them through the
 * platform binding on [PreparedHarfBuzzFont.close].
 */
internal class HarfBuzzBindings private constructor(private val binding: HarfBuzzPlatformBinding) {

    /** The semantic identity is unchanged; only the provenance artifactId names the native publication. */
    val identity: ShapingBackendIdentity = ShapingBackendIdentity(
        semantic = HARFBUZZ_SEMANTIC_IDENTITY,
        provenance = ShapingDistributionProvenance(
            operatingSystem = binding.identity.operatingSystem,
            architecture = binding.identity.architecture,
            artifactId = binding.identity.artifactId,
            artifactSha256 = binding.identity.artifactSha256,
            sourceProject = "harfbuzz",
            sourceRevision = binding.identity.upstreamSourceRevision,
            buildChainIdentity = binding.identity.buildChainIdentity,
        ),
    )

    fun prepare(fontBytes: ByteArray, faceIndex: Int, layoutSize: Float): PreparedHarfBuzzFont {
        val prepared = binding.prepare(fontBytes, faceIndex, layoutSize)
        val designToLayout = try {
            DesignToLayoutScale.create(layoutSize, prepared.unitsPerEm)
        } catch (error: Throwable) {
            binding.release(prepared)
            throw error
        }
        return PreparedHarfBuzzFont(this, prepared, designToLayout)
    }

    fun shape(request: ShapingRequest, preparedFont: PreparedHarfBuzzFont): ShapedGlyphRun {
        val buffer = binding.createBuffer()
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
            observeCancellation(request)
            val accepted = buffer.shape(preparedFont.font, request.features)
            observeCancellation(request)
            check(accepted) { "HarfBuzz did not accept the explicit OpenType shaper configuration." }
            return shapedRun(request, preparedFont, buffer, scalarTable, itemScalarRanges)
        } finally {
            buffer.close()
        }
    }

    fun release(prepared: PreparedHarfBuzzFont) {
        binding.release(prepared.font)
    }

    private fun configureBuffer(buffer: PlatformHarfBuzzBuffer, request: ShapingRequest) {
        buffer.setDirection(request.direction)
        buffer.setScript(request.script.value)
        buffer.setLanguage(request.language)
        buffer.setClusterLevelMonotoneCharacters()
        buffer.setFlags(
            beginningOfText = request.bot,
            endOfText = request.eot,
            produceUnsafeToConcat = true,
        )
    }

    private fun shapedRun(
        request: ShapingRequest,
        prepared: PreparedHarfBuzzFont,
        buffer: PlatformHarfBuzzBuffer,
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
                safetyMask = (if (info.unsafeToBreak) HB_GLYPH_FLAG_UNSAFE_TO_BREAK else 0) or
                    (if (info.unsafeToConcat) HB_GLYPH_FLAG_UNSAFE_TO_CONCAT else 0),
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
                        unshapedAdvance = prepared.font.horizontalAdvance(record.glyphId),
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
        font: PlatformPreparedFont,
        direction: ShapingDirection,
        glyphId: Int,
        glyphIndex: Int,
        cluster: ShaperCluster,
        finalAdvanceMatchesUnshapedAdvance: Boolean,
        designToLayout: DesignToLayoutScale,
    ): GdefLigatureCaretFact {
        val expectedCaretCount = cluster.internalAdmissibleGraphemeBoundaries().size
        val carets = font.ligatureCarets(direction, glyphId, 0, expectedCaretCount)
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
            FontOperationResult.Success(HarfBuzzBindings(openHarfBuzzPlatformBinding()))
        } catch (failure: HarfBuzzBindingException) {
            bindingFailureError(failure)
        } catch (error: Throwable) {
            shapingFailure(
                code = "font.shaping-native-load-failed",
                message = "The bundled HarfBuzz binding could not be loaded: " +
                    (error.message ?: error::class.simpleName) + ".",
            )
        }
    }
}

/** An open prepared font: owns the platform HarfBuzz objects and the design-to-layout scale. */
internal class PreparedHarfBuzzFont(
    private val bindings: HarfBuzzBindings,
    internal val font: PlatformPreparedFont,
    internal val designToLayout: DesignToLayoutScale,
) {
    fun close() {
        bindings.release(this)
    }
}

/**
 * Portable classification of a native binding failure.
 *
 * Mirrors the kffi failure taxonomy so the adapter maps to the same typed diagnostic codes without
 * depending on any native type.
 */
internal enum class HarfBuzzBindingFailure {
    UNSUPPORTED_PLATFORM,
    RESOURCE_MISSING,
    RESOURCE_CORRUPT,
    LIBRARY_LOAD,
    SYMBOL_RESOLUTION,
    VERSION_MISMATCH,
    NATIVE_OPERATION,
}

/** Portable binding exception raised by [openHarfBuzzPlatformBinding] and mapped by [bindingFailureError]. */
internal class HarfBuzzBindingException(
    val failure: HarfBuzzBindingFailure,
    message: String? = null,
) : RuntimeException(message)

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
