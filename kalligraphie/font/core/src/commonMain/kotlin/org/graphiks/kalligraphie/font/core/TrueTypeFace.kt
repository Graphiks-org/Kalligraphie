@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontAxisCoordinate
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontDataInterpretationVersion
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontGeometryParameters
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontMetrics
import org.graphiks.kalligraphie.api.FontNamedInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontVariationAxis
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFontData
import org.graphiks.kalligraphie.api.OpenTypeDataCopyEstimate
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderAssetKey
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentationProfile
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphRepresentationKey
import org.graphiks.kalligraphie.api.GlyphRepresentationProfileKey
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.glyph.OutlineMaterializer
import org.graphiks.kalligraphie.font.scaler.PreparedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.AvarData
import org.graphiks.kalligraphie.font.sfnt.AvarReader
import org.graphiks.kalligraphie.font.sfnt.ColrV1Reader
import org.graphiks.kalligraphie.font.sfnt.ColrV1Data
import org.graphiks.kalligraphie.font.sfnt.ColrCpalReader
import org.graphiks.kalligraphie.font.sfnt.ColrCpalV0Data
import org.graphiks.kalligraphie.font.sfnt.ColrCpalV0Limits
import org.graphiks.kalligraphie.font.sfnt.ColrV0Layer
import org.graphiks.kalligraphie.font.sfnt.CbdtCblcData
import org.graphiks.kalligraphie.font.sfnt.CbdtCblcReader
import org.graphiks.kalligraphie.font.sfnt.EbdtFormatOneData
import org.graphiks.kalligraphie.font.sfnt.EbdtFormatOneReader
import org.graphiks.kalligraphie.font.sfnt.FvarData
import org.graphiks.kalligraphie.font.sfnt.FvarReader
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.SbixData
import org.graphiks.kalligraphie.font.sfnt.SbixReader
import org.graphiks.kalligraphie.font.sfnt.SvgGlyphPaint
import org.graphiks.kalligraphie.font.sfnt.SvgOpenTypeData
import org.graphiks.kalligraphie.font.sfnt.SvgOpenTypeReader
import org.graphiks.kalligraphie.font.sfnt.VariationNormalizer
import org.graphiks.kalligraphie.font.sfnt.slice

private const val GLYF_OUTLINE_ROUTE_PARAMETERS: String = "glyf-outline-v1"
private const val COLR_CPAL_V0_ROUTE_PARAMETERS: String = "colr-v0;cpal-v0"
private const val SVG_OPEN_TYPE_V0_ROUTE_PARAMETERS: String = "svg-opentype-v0"
private const val SVG_COLR_CPAL_V0_FALLBACK_ROUTE_PARAMETERS: String = "svg-opentype-v0;colr-v0;cpal-v0-fallback"
private const val SVG_GLYF_OUTLINE_FALLBACK_ROUTE_PARAMETERS: String = "svg-opentype-v0;glyf-outline-fallback-v1"

internal class TrueTypeFace(
    private val faceId: FontFaceId,
    private val generation: FontCatalogGeneration,
    private val parsedFont: ParsedTrueTypeFont,
    private val resource: PreparedFontResource,
    private val outlineRouteSupported: Boolean,
    private val paintGraphSupported: Boolean,
    private val colrV1Supported: Boolean = false,
    private val svgRouteSupported: Boolean,
    private val bitmapRouteSupported: Boolean,
    private val cbdtCblcRouteSupported: Boolean,
    private val sbixRouteSupported: Boolean,
) : FontFace {
    override val metadata: FontFaceMetadata = parsedFont.metadata
    override val id: FontFaceId = faceId

    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> {
        if (descriptor.layoutSize.value <= 0f) {
            return failure(
                FontError.InvalidInstanceDescriptor(
                    message = "Font instance layout size must be finite and positive.",
                    location = FontDiagnosticLocation.FaceId(id),
                ),
            )
        }
        val variation = descriptor.variation
        val effectiveGeometry: FontGeometryParameters
        val successDiagnostics: List<FontDiagnostic>
        if (variation != null && variation.coordinates.isNotEmpty()) {
            if (descriptor.geometry.normalizedAxes.isNotEmpty()) {
                return failure(
                    FontError.FontDataFailure(
                        code = "font.variation.ambiguous-request",
                        message = "A design variation and normalized axes cannot be combined.",
                        location = FontDiagnosticLocation.FaceId(id),
                    ),
                )
            }
            when (val normalized = normalize(variation)) {
                is FontOperationResult.Success -> {
                    effectiveGeometry = FontGeometryParameters(
                        normalizedAxes = normalized.value,
                        syntheticBold = descriptor.geometry.syntheticBold,
                        syntheticItalic = descriptor.geometry.syntheticItalic,
                    )
                    successDiagnostics = normalized.diagnostics
                }
                is FontOperationResult.Failure -> return normalized
                is FontOperationResult.Cancelled -> return normalized
            }
        } else {
            effectiveGeometry = descriptor.geometry
            successDiagnostics = emptyList()
        }
        if (effectiveGeometry.syntheticBold || effectiveGeometry.syntheticItalic) {
            return failure(
                FontError.InvalidInstanceDescriptor(
                    message = "Synthetic geometry is not supported by this TrueType face.",
                    location = FontDiagnosticLocation.FaceId(id),
                ),
            )
        }
        return FontOperationResult.Success(
            TrueTypeFontInstance(
                key = instanceKey(descriptor.layoutSize, effectiveGeometry),
                descriptor = descriptor.copy(variation = null, geometry = effectiveGeometry),
                resource = resource,
                faceId = id,
                generation = generation,
                parsedFont = parsedFont,
                outlineRouteSupported = outlineRouteSupported,
                paintGraphSupported = paintGraphSupported,
                colrV1Supported = colrV1Supported,
                svgRouteSupported = svgRouteSupported,
                bitmapRouteSupported = bitmapRouteSupported,
                cbdtCblcRouteSupported = cbdtCblcRouteSupported,
                sbixRouteSupported = sbixRouteSupported,
            ),
            successDiagnostics,
        )
    }

    // The metadata surface cannot express a malformed-table failure, so absent or unparseable
    // fvar collapses to an empty snapshot here; normalize() reports the failure instead.
    private fun readFvarOrNull(): FvarData? = (readFvarResult() as? FontOperationResult.Success)?.value

    override fun variationAxes(): List<FontVariationAxis> =
        readFvarOrNull()?.axes?.map { axis ->
            FontVariationAxis(axis.tag, axis.minValue, axis.defaultValue, axis.maxValue, axis.nameId, axis.hidden)
        } ?: emptyList()

    override fun namedInstances(): List<FontNamedInstance> =
        readFvarOrNull()?.instances?.mapIndexed { index, instance ->
            FontNamedInstance(index, instance.subfamilyNameId, instance.postScriptNameId, FontVariationCoordinates(instance.coordinates))
        } ?: emptyList()

    override fun normalize(design: FontVariationCoordinates): FontOperationResult<List<FontAxisCoordinate>> {
        val fvar = when (val fvarResult = readFvarResult()) {
            is FontOperationResult.Success -> fvarResult.value
                ?: return failure(
                    FontError.FontDataFailure(
                        code = "font.variation.not-variable",
                        message = "This face has no usable fvar table.",
                        location = FontDiagnosticLocation.FaceId(id),
                    ),
                )
            is FontOperationResult.Failure -> return fvarResult
            is FontOperationResult.Cancelled -> return fvarResult
        }
        val avar = when (val avarResult = readAvarResult(fvar.axes.size)) {
            is FontOperationResult.Success -> avarResult.value
            is FontOperationResult.Failure -> return avarResult
            is FontOperationResult.Cancelled -> return avarResult
        }
        return VariationNormalizer.normalize(design, fvar, avar)
    }

    private fun readFvarResult(): FontOperationResult<FvarData?> {
        val record = parsedFont.tableRecords["fvar"] ?: return FontOperationResult.Success(null)
        val table = slice(resource.preparedFont.copySourceBytes(), record)
            ?: return failure(FontError.InvalidFontData("fvar table exceeds embedded source bytes.", FontDiagnosticLocation.Table("fvar")))
        return when (val result = FvarReader.read(table)) {
            is FontOperationResult.Success -> FontOperationResult.Success(result.value)
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

    private fun readAvarResult(axisCount: Int): FontOperationResult<AvarData?> {
        val record = parsedFont.tableRecords["avar"] ?: return FontOperationResult.Success(null)
        val table = slice(resource.preparedFont.copySourceBytes(), record)
            ?: return failure(FontError.InvalidFontData("avar table exceeds embedded source bytes.", FontDiagnosticLocation.Table("avar")))
        return when (val result = AvarReader.read(table, axisCount)) {
            is FontOperationResult.Success -> FontOperationResult.Success(result.value)
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

    private fun instanceKey(layoutSize: LayoutUnit, geometry: FontGeometryParameters): FontInstanceKey =
        FontInstanceKey(
            face = id,
            interpretation = FontDataInterpretationVersion(
                pipelineId = "org.graphiks.kalligraphie.true-type",
                version = "1",
            ),
            layoutSize = layoutSize,
            geometry = geometry,
        )
}

internal data class TrueTypeFontInstance(
    override val key: FontInstanceKey,
    private val descriptor: FontInstanceDescriptor,
    private val resource: PreparedFontResource,
    private val faceId: FontFaceId,
    private val generation: FontCatalogGeneration,
    private val parsedFont: ParsedTrueTypeFont,
    private val outlineRouteSupported: Boolean,
    private val paintGraphSupported: Boolean,
    private val colrV1Supported: Boolean = false,
    private val svgRouteSupported: Boolean,
    private val bitmapRouteSupported: Boolean,
    private val cbdtCblcRouteSupported: Boolean,
    private val sbixRouteSupported: Boolean,
) : FontInstance {
    override fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> {
        return resource.preparedFont.resolveGlyph(codePoint)
    }

    override fun resolveGlyph(
        codePoint: Int,
        variationSelector: Int,
    ): FontOperationResult<GlyphResolution> =
        resource.preparedFont.resolveGlyph(codePoint, variationSelector)

    override fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        resource.preparedFont.readGlyphMetrics(glyphId, descriptor.layoutSize.value, key.geometry.normalizedAxes)

    override fun verticalMetrics(glyphId: GlyphId): FontOperationResult<org.graphiks.kalligraphie.api.VerticalGlyphMetrics> =
        resource.preparedFont.readVerticalGlyphMetrics(glyphId, descriptor.layoutSize.value, key.geometry.normalizedAxes)

    /**
     * Returns the instance's font-wide metrics derived from `OS/2`, `hhea`, `post`, and `MVAR`.
     *
     * [FontInstance.fontMetrics]'s interface default deliberately remains an unsupported placeholder;
     * this portable TrueType instance is where the derivation is wired in, so a client that holds a
     * concrete TrueType instance gets real metrics while the shared interface contract is unchanged.
     */
    override fun fontMetrics(): FontOperationResult<FontMetrics> =
        resource.preparedFont.readFontMetrics(key.geometry.normalizedAxes)

    override fun copyOpenTypeData(): FontOperationResult<OpenTypeFontData> =
        FontOperationResult.Success(OpenTypeFontData(faceId, resource.preparedFont.copySourceBytes()))

    override fun estimateOpenTypeDataCopy(): FontOperationResult<OpenTypeDataCopyEstimate> {
        val size = resource.sourceByteSize.toLong()
        return FontOperationResult.Success(OpenTypeDataCopyEstimate(size, size * 2L))
    }

    override fun estimateRenderAssetBytes(
        renderVariant: FontRenderVariantSnapshot,
        profile: GlyphRepresentationProfile,
    ): FontOperationResult<Long> = if (isSupportedProfile(profile)) {
        FontOperationResult.Success(
            estimateEmbeddedRenderAssetBytes(resource, parsedFont, key, renderVariant, profile),
        )
    } else {
        failure(
            FontError.UnsupportedRepresentationProfile(
                "The embedded font cannot estimate an unsupported render-asset profile.",
                FontDiagnosticLocation.FaceId(faceId),
            ),
        )
    }

    override fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        variant: FontRenderVariantKey,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> =
        if (variant == FontRenderVariantKey.default) {
            acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements)
        } else {
            failure(
                FontError.UnsupportedRepresentationProfile(
                    "A full render-variant snapshot is required for a non-default embedded asset.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        }

    override fun acquireRenderAsset(
        resolver: FontAssetResolverHandle,
        renderVariant: FontRenderVariantSnapshot,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontRenderAssetHandle> {
        if (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE) {
            return failure(FontError.UnsupportedRepresentationProfile("A renderable access mode is required.", FontDiagnosticLocation.FaceId(faceId)))
        }
        val profiles = requirements.acceptedProfiles.filter(::isSupportedProfile)
        if (profiles.isEmpty()) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    "No accepted representation profile is supported by this embedded font.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        }
        if (resolver !is EmbeddedFontAssetResolver) {
            return failure(FontError.InvalidFontData("Resolver was not opened by the embedded TrueType catalog.", FontDiagnosticLocation.FaceId(faceId)))
        }
        if (resolver.generation != generation) {
            return failure(FontError.IncompatibleCatalogGeneration("Resolver generation does not match the font instance generation.", FontDiagnosticLocation.FaceId(faceId)))
        }
        val lease = resolver.acquireAssetLease(faceId)
            ?: return failure(FontError.ResourceClosed("Asset resolver is closed."))
        var leaseTransferred = false
        return try {
            var firstFailure: FontOperationResult.Failure? = null
            for (profile in profiles) {
                val outcome = when (profile) {
                is org.graphiks.kalligraphie.api.OutlineProfile -> {
                    if (renderVariant != FontRenderVariantSnapshot.default || profile.schemaVersion != 1) {
                        failure(
                            FontError.UnsupportedRepresentationProfile(
                                "Schema version 1 outline assets accept only the default render variant.",
                                FontDiagnosticLocation.FaceId(faceId),
                            ),
                        )
                    } else {
                        FontOperationResult.Success(
                            TrueTypeRenderAssetHandle(
                                faceId = faceId,
                                resourceLease = lease,
                                key = FontRenderAssetKey(key, renderVariant.key, profile, resolver.generation),
                            ),
                        )
                    }
                }

                is PaintGraphProfile -> {
                    if (profile.schemaVersion in 2..3 && colrV1Supported) {
                        when (val colorData = readColrV1(profile, renderVariant)) {
                            is FontOperationResult.Success -> {
                                val svgResult = if (svgRouteSupported) readMixedSvgSource(profile)
                                    else FontOperationResult.Success(null)
                                when (svgResult) {
                                    is FontOperationResult.Success -> FontOperationResult.Success(
                                        ColrV1RenderAssetHandle(
                                            faceId = faceId,
                                            resourceLease = lease,
                                            key = FontRenderAssetKey(key, renderVariant.key, profile, resolver.generation, variantSnapshot = renderVariant.takeUnless { it == FontRenderVariantSnapshot.default }),
                                            profile = profile,
                                            colorData = colorData.value,
                                            svgSource = svgResult.value,
                                        ),
                                    )
                                    is FontOperationResult.Failure -> svgResult
                                    is FontOperationResult.Cancelled -> svgResult
                                }
                            }
                            is FontOperationResult.Failure -> colorData
                            is FontOperationResult.Cancelled -> colorData
                        }
                    } else if (profile.schemaVersion != 1 && !(profile.schemaVersion in 2..3 && svgRouteSupported)) {
                        failure(FontError.UnsupportedRepresentationProfile("Only paint-graph schema versions 1, 2, and 3 are supported by embedded routes.", FontDiagnosticLocation.FaceId(faceId)))
                    } else {
                        when (val colorData = if (paintGraphSupported) readColrCpalV0(profile) else null) {
                            is FontOperationResult.Failure -> colorData
                            is FontOperationResult.Cancelled -> colorData
                            is FontOperationResult.Success,
                            null,
                            -> {
                                val resolvedColorData = colorData?.value
                                if (svgRouteSupported) {
                                    if (resolvedColorData == null && renderVariant != FontRenderVariantSnapshot.default) {
                                        failure(
                                            FontError.UnsupportedRepresentationProfile(
                                                "SVG-in-OpenType paint assets without COLR fallback accept only the default render variant.",
                                                FontDiagnosticLocation.FaceId(faceId),
                                            ),
                                        )
                                    } else {
                                        val paletteIndex = renderVariant.cpalPaletteIndex ?: 0
                                        if (resolvedColorData != null && paletteIndex !in 0 until resolvedColorData.paletteCount) {
                                            failure(
                                                FontError.UnsupportedRepresentationProfile(
                                                    "The selected CPAL palette is unavailable in this font.",
                                                    FontDiagnosticLocation.FaceId(faceId),
                                                ),
                                            )
                                        } else {
                                            when (val svgData = readSvgOpenType(profile)) {
                                                is FontOperationResult.Success -> FontOperationResult.Success(
                                                    SvgOpenTypeRenderAssetHandle(
                                                        faceId = faceId,
                                                        resourceLease = lease,
                                                        key = FontRenderAssetKey(
                                                            fontInstanceKey = key,
                                                            variant = renderVariant.key,
                                                            representationProfile = profile,
                                                            generation = resolver.generation,
                                                            variantSnapshot = renderVariant.takeUnless { it == FontRenderVariantSnapshot.default },
                                                        ),
                                                        profile = profile,
                                                        svgData = svgData.value,
                                                        glyphCount = parsedFont.metadata.glyphCount,
                                                        colorData = resolvedColorData,
                                                        paletteIndex = resolvedColorData?.let { paletteIndex },
                                                        foregroundColor = renderVariant.foregroundColor ?: GlyphColor(0, 0, 0),
                                                    ),
                                                )

                                                is FontOperationResult.Failure -> svgData
                                                is FontOperationResult.Cancelled -> svgData
                                            }
                                        }
                                    }
                                } else if (resolvedColorData == null) {
                                    failure(
                                        FontError.UnsupportedRepresentationProfile(
                                            "The font has no supported COLR/CPAL or SVG-in-OpenType paint route.",
                                            FontDiagnosticLocation.FaceId(faceId),
                                        ),
                                    )
                                } else {
                                    val paletteIndex = renderVariant.cpalPaletteIndex ?: 0
                                    if (paletteIndex !in 0 until resolvedColorData.paletteCount) {
                                        failure(
                                            FontError.UnsupportedRepresentationProfile(
                                                "The selected CPAL palette is unavailable in this font.",
                                                FontDiagnosticLocation.FaceId(faceId),
                                            ),
                                        )
                                    } else {
                                    FontOperationResult.Success(
                                        ColrV0RenderAssetHandle(
                                            faceId = faceId,
                                            resourceLease = lease,
                                            key = FontRenderAssetKey(
                                                fontInstanceKey = key,
                                                variant = renderVariant.key,
                                                representationProfile = profile,
                                                generation = resolver.generation,
                                                variantSnapshot = renderVariant.takeUnless { it == FontRenderVariantSnapshot.default },
                                            ),
                                            profile = profile,
                                            colorData = resolvedColorData,
                                            paletteIndex = paletteIndex,
                                            foregroundColor = renderVariant.foregroundColor ?: GlyphColor(0, 0, 0),
                                        ),
                                    )
                                    }
                                }
                            }
                        }
                    }
                }

                is BitmapProfile -> {
                    if (renderVariant != FontRenderVariantSnapshot.default || profile.schemaVersion != 2) {
                        failure(
                            FontError.UnsupportedRepresentationProfile(
                                "Bitmap assets require schema version 2 and the default render variant.",
                                FontDiagnosticLocation.FaceId(faceId),
                            ),
                        )
                    } else {
                        when (profile.strike.bitDepth) {
                            1 -> when (val bitmapData = readEbdtFormatOne(profile)) {
                                is FontOperationResult.Success -> FontOperationResult.Success(
                                    bitmapHandle(profile, renderVariant, resolver, lease, EbdtMonoBitmapRoute(bitmapData.value)),
                                )

                                is FontOperationResult.Failure -> bitmapData
                                is FontOperationResult.Cancelled -> bitmapData
                            }

                            32 -> {
                                // Deterministic colour-strike priority with no cross-route
                                // fallthrough: CBDT/CBLC is the standardized colour route, so a
                                // face that carries both colour tables resolves through it and a
                                // CBDT/CBLC failure is the final result rather than a silent retry
                                // against sbix.
                                when {
                                    cbdtCblcRouteSupported -> when (val bitmapData = readCbdtCblc(profile)) {
                                        is FontOperationResult.Success -> FontOperationResult.Success(
                                            bitmapHandle(profile, renderVariant, resolver, lease, CbdtCblcBitmapRoute(bitmapData.value)),
                                        )

                                        is FontOperationResult.Failure -> bitmapData
                                        is FontOperationResult.Cancelled -> bitmapData
                                    }

                                    sbixRouteSupported -> when (val bitmapData = readSbix(profile)) {
                                        is FontOperationResult.Success -> FontOperationResult.Success(
                                            bitmapHandle(profile, renderVariant, resolver, lease, SbixBitmapRoute(bitmapData.value)),
                                        )

                                        is FontOperationResult.Failure -> bitmapData
                                        is FontOperationResult.Cancelled -> bitmapData
                                    }

                                    else -> failure(
                                        FontError.UnsupportedRepresentationProfile(
                                            "The font has no supported 32-bit colour bitmap route.",
                                            FontDiagnosticLocation.FaceId(faceId),
                                        ),
                                    )
                                }
                            }

                            else -> failure(
                                FontError.UnsupportedRepresentationProfile(
                                    "Only bitmap strikes with bit depth 1 or 32 are supported.",
                                    FontDiagnosticLocation.FaceId(faceId),
                                ),
                            )
                        }
                    }
                }

                    else -> failure(
                        FontError.UnsupportedRepresentationProfile(
                            "The embedded TrueType provider supports only outline, COLR version 0 or 1, SVG-in-OpenType paint, EBLC/EBDT format 1, CBLC/CBDT formats 17 and 18, and sbix 'png ' bitmap profiles.",
                            FontDiagnosticLocation.FaceId(faceId),
                        ),
                    )
                }
                when (outcome) {
                    is FontOperationResult.Success -> {
                        leaseTransferred = true
                        return outcome
                    }

                    is FontOperationResult.Failure -> if (firstFailure == null) firstFailure = outcome
                    is FontOperationResult.Cancelled -> return outcome
                }
            }
            firstFailure ?: failure(
                FontError.UnsupportedRepresentationProfile(
                    "No accepted representation profile can be certified by this embedded font.",
                    FontDiagnosticLocation.FaceId(faceId),
                ),
            )
        } finally {
            if (!leaseTransferred) lease.release()
        }
    }

    private fun isSupportedProfile(profile: org.graphiks.kalligraphie.api.GlyphRepresentationProfile): Boolean =
        when (profile) {
            is org.graphiks.kalligraphie.api.OutlineProfile ->
                profile.schemaVersion == 1 && outlineRouteSupported
            is PaintGraphProfile -> (profile.schemaVersion == 1 && (paintGraphSupported || svgRouteSupported)) ||
                (profile.schemaVersion in 2..3 && (colrV1Supported || svgRouteSupported))
            is BitmapProfile -> profile.schemaVersion == 2 && when (profile.strike.bitDepth) {
                1 -> bitmapRouteSupported
                32 -> cbdtCblcRouteSupported || sbixRouteSupported
                else -> false
            }
            else -> false
        }

    private fun bitmapHandle(
        profile: BitmapProfile,
        renderVariant: FontRenderVariantSnapshot,
        resolver: EmbeddedFontAssetResolver,
        lease: PreparedFontResourceLease,
        route: BitmapRouteData,
    ): BitmapRenderAssetHandle = BitmapRenderAssetHandle(
        faceId = faceId,
        resourceLease = lease,
        key = FontRenderAssetKey(
            fontInstanceKey = key,
            variant = renderVariant.key,
            representationProfile = profile,
            generation = resolver.generation,
        ),
        route = route,
    )

    private fun readColrV1(profile: PaintGraphProfile, variant: FontRenderVariantSnapshot): FontOperationResult<ColrV1Data> {
        val colrRecord = parsedFont.tableRecords["COLR"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no COLR table.", FontDiagnosticLocation.FaceId(faceId)))
        val cpalRecord = parsedFont.tableRecords["CPAL"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no CPAL table.", FontDiagnosticLocation.FaceId(faceId)))
        if (colrRecord.length > profile.limits.maxSourceBytes.toLong() - cpalRecord.length) {
            return failure(FontError.ResourceLimitExceeded("COLR and CPAL source-byte limit exceeded.", FontDiagnosticLocation.Table("COLR")))
        }
        val source = resource.preparedFont.copySourceBytes()
        val colr = slice(source, colrRecord)
            ?: return failure(FontError.InvalidFontData("COLR table exceeds embedded source bytes.", FontDiagnosticLocation.Table("COLR")))
        val cpal = slice(source, cpalRecord)
            ?: return failure(FontError.InvalidFontData("CPAL table exceeds embedded source bytes.", FontDiagnosticLocation.Table("CPAL")))
        val orderedAxes = when (val result = resource.preparedFont.orderedVariationAxes(key.geometry.normalizedAxes)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        return ColrV1Reader.read(
            colr,
            cpal,
            parsedFont.metadata.glyphCount,
            profile,
            variant.cpalPaletteIndex ?: 0,
            variant.foregroundColor ?: GlyphColor(0, 0, 0),
            orderedAxes,
        )
    }

    private fun readColrCpalV0(profile: PaintGraphProfile): FontOperationResult<ColrCpalV0Data> {
        val colrRecord = parsedFont.tableRecords["COLR"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no COLR table.", FontDiagnosticLocation.FaceId(faceId)))
        val cpalRecord = parsedFont.tableRecords["CPAL"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no CPAL table.", FontDiagnosticLocation.FaceId(faceId)))
        val totalBytes = colrRecord.length + cpalRecord.length
        if (totalBytes < 0L || totalBytes > profile.limits.maxSourceBytes.toLong()) {
            return failure(FontError.ResourceLimitExceeded("COLR and CPAL source-byte limit exceeded.", FontDiagnosticLocation.FaceId(faceId)))
        }
        val sourceBytes = resource.preparedFont.copySourceBytes()
        val colr = slice(sourceBytes, colrRecord)
            ?: return failure(FontError.InvalidFontData("COLR table exceeds embedded source bytes.", FontDiagnosticLocation.Table("COLR")))
        val cpal = slice(sourceBytes, cpalRecord)
            ?: return failure(FontError.InvalidFontData("CPAL table exceeds embedded source bytes.", FontDiagnosticLocation.Table("CPAL")))
        return ColrCpalReader.read(
            colrTable = colr,
            cpalTable = cpal,
            limits = ColrCpalV0Limits(
                maxPalettes = profile.limits.maxPalettes,
                maxPaletteEntries = profile.limits.maxPaletteEntries,
                maxColorRecords = profile.limits.maxColorRecords,
                maxDecodedPaletteBytes = profile.limits.maxDecodedPaletteBytes,
                maxBaseGlyphRecords = profile.limits.maxBaseGlyphRecords,
                maxLayerRecords = profile.limits.maxLayerRecords,
            ),
            glyphCount = parsedFont.metadata.glyphCount,
        )
    }

    private fun readEbdtFormatOne(profile: BitmapProfile): FontOperationResult<EbdtFormatOneData> {
        val eblcRecord = parsedFont.tableRecords["EBLC"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no EBLC table.", FontDiagnosticLocation.FaceId(faceId)))
        val ebdtRecord = parsedFont.tableRecords["EBDT"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no EBDT table.", FontDiagnosticLocation.FaceId(faceId)))
        val sourceBytes = resource.preparedFont.copySourceBytes()
        val eblc = slice(sourceBytes, eblcRecord)
            ?: return failure(FontError.InvalidFontData("EBLC table exceeds embedded source bytes.", FontDiagnosticLocation.Table("EBLC")))
        val ebdt = slice(sourceBytes, ebdtRecord)
            ?: return failure(FontError.InvalidFontData("EBDT table exceeds embedded source bytes.", FontDiagnosticLocation.Table("EBDT")))
        return EbdtFormatOneReader.read(eblc, ebdt, parsedFont.metadata.glyphCount, profile)
    }

    private fun readCbdtCblc(profile: BitmapProfile): FontOperationResult<CbdtCblcData> {
        val cblcRecord = parsedFont.tableRecords["CBLC"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no CBLC table.", FontDiagnosticLocation.FaceId(faceId)))
        val cbdtRecord = parsedFont.tableRecords["CBDT"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no CBDT table.", FontDiagnosticLocation.FaceId(faceId)))
        val sourceBytes = resource.preparedFont.copySourceBytes()
        val cblc = slice(sourceBytes, cblcRecord)
            ?: return failure(FontError.InvalidFontData("CBLC table exceeds embedded source bytes.", FontDiagnosticLocation.Table("CBLC")))
        val cbdt = slice(sourceBytes, cbdtRecord)
            ?: return failure(FontError.InvalidFontData("CBDT table exceeds embedded source bytes.", FontDiagnosticLocation.Table("CBDT")))
        return CbdtCblcReader.read(cblc, cbdt, parsedFont.metadata.glyphCount, profile)
    }

    /**
     * Reads the face's sbix version 1 colour strike into the normalized route data.
     *
     * Advances resolve directly from the face's `hhea`/`hmtx` tables, the dependency OpenType's
     * sbix contract requires; `glyf` is not required for advance resolution. A face whose `hmtx`
     * is missing or unusable does not advertise this route, so a usable advance provider is
     * expected here; if it is absent this reports an unsupported profile instead of publishing
     * partial data.
     */
    private fun readSbix(profile: BitmapProfile): FontOperationResult<SbixData> {
        val sbixRecord = parsedFont.tableRecords["sbix"]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no sbix table.", FontDiagnosticLocation.FaceId(faceId)))
        val sbix = slice(resource.preparedFont.copySourceBytes(), sbixRecord)
            ?: return failure(FontError.InvalidFontData("sbix table exceeds embedded source bytes.", FontDiagnosticLocation.Table("sbix")))
        val advanceDesignUnits = hmtxAdvanceDesignUnitsProvider(resource, parsedFont)
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no usable hmtx advance table for sbix.", FontDiagnosticLocation.Table("hmtx")))
        return SbixReader.read(
            sbixTable = sbix,
            glyphCount = parsedFont.metadata.glyphCount,
            unitsPerEm = parsedFont.metadata.unitsPerEm,
            advanceDesignUnits = advanceDesignUnits,
            profile = profile,
        )
    }

    private fun readSvgOpenType(profile: PaintGraphProfile): FontOperationResult<SvgOpenTypeData> {
        val svgRecord = parsedFont.tableRecords["SVG "]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no SVG table.", FontDiagnosticLocation.FaceId(faceId)))
        if (svgRecord.length > profile.limits.maxSourceBytes.toLong()) {
            return failure(FontError.ResourceLimitExceeded("SVG source-byte limit exceeded.", FontDiagnosticLocation.Table("SVG ")))
        }
        val svg = slice(resource.preparedFont.copySourceBytes(), svgRecord)
            ?: return failure(FontError.InvalidFontData("SVG table exceeds embedded source bytes.", FontDiagnosticLocation.Table("SVG ")))
        return SvgOpenTypeReader.read(svg, parsedFont.metadata.glyphCount, profile)
    }

    private fun readMixedSvgSource(profile: PaintGraphProfile): FontOperationResult<ColrV1SvgSource> {
        val svgRecord = parsedFont.tableRecords["SVG "]
            ?: return failure(FontError.UnsupportedRepresentationProfile("The font has no SVG table.", FontDiagnosticLocation.FaceId(faceId)))
        if (svgRecord.length > profile.limits.maxSourceBytes.toLong()) {
            return failure(FontError.ResourceLimitExceeded("SVG source-byte limit exceeded.", FontDiagnosticLocation.Table("SVG ")))
        }
        val svg = slice(resource.preparedFont.copySourceBytes(), svgRecord)
            ?: return failure(FontError.InvalidFontData("SVG table exceeds embedded source bytes.", FontDiagnosticLocation.Table("SVG ")))
        return when (val result = SvgOpenTypeReader.validateIndex(svg, parsedFont.metadata.glyphCount, profile)) {
            is FontOperationResult.Success -> FontOperationResult.Success(ColrV1SvgSource(svg, parsedFont.metadata.glyphCount), result.diagnostics)
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

}

internal class TrueTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
) : FontRenderAssetHandle {
    private val profile: org.graphiks.kalligraphie.api.OutlineProfile = requireNotNull(key.outlineProfile) {
        "TrueTypeRenderAssetHandle requires an outline asset key."
    }
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                TrueTypeRenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key.copy(representationProfile = profile.copy()),
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled()
            }
            val preparedFont = resourceLease?.preparedFont
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val resource = resourceLease?.resource
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val glyphId = GlyphId(request.glyphId)
            val representationKey = GlyphRepresentationKey(
                assetKey = key,
                glyphId = glyphId,
                variant = key.variant,
                profile = GlyphRepresentationProfileKey.outline(profile),
                routeParameters = GLYF_OUTLINE_ROUTE_PARAMETERS,
            )
            resource.cachedRepresentation(representationKey)?.let { cached -> return cached }
            val outline = when (
                val result = preparedFont.readGlyphOutline(
                    glyphId,
                    profile,
                    cancellationToken,
                    key.fontInstanceKey.geometry.normalizedAxes,
                )
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (cancellationToken.isCancellationRequested()) {
                return FontOperationResult.Cancelled()
            }
            when (val materialized = OutlineMaterializer.materialize(outline, profile, cancellationToken)) {
                is FontOperationResult.Success -> {
                    if (cancellationToken.isCancellationRequested()) FontOperationResult.Cancelled()
                    else materialized.also { success -> resource.cacheRepresentation(representationKey, success) }
                }

                is FontOperationResult.Failure -> materialized
                is FontOperationResult.Cancelled -> materialized
            }
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

/** Asset handle for the normalized SVG-in-OpenType route and its profile-certified color or `glyf` fallback. */
internal class SvgOpenTypeRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val profile: PaintGraphProfile,
    private val svgData: SvgOpenTypeData,
    private val glyphCount: Int,
    private val colorData: ColrCpalV0Data?,
    private val paletteIndex: Int?,
    private val foregroundColor: GlyphColor,
) : FontRenderAssetHandle {
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                SvgOpenTypeRenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key.copy(representationProfile = profile),
                    profile = profile,
                    svgData = svgData,
                    glyphCount = glyphCount,
                    colorData = colorData,
                    paletteIndex = paletteIndex,
                    foregroundColor = foregroundColor,
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val preparedFont = resourceLease?.preparedFont
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val resource = resourceLease?.resource
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            if (request.glyphId !in 0 until glyphCount) return failure(FontError.GlyphOutOfRange(request.glyphId))
            val glyphId = GlyphId(request.glyphId)
            val svgPaint = svgData.glyphPaint(glyphId)
            val colorLayers = colorData?.layersFor(glyphId).orEmpty()
            val representationKey = GlyphRepresentationKey(
                assetKey = key,
                glyphId = glyphId,
                variant = key.variant,
                profile = GlyphRepresentationProfileKey.paintGraph(profile),
                routeParameters = when {
                    svgPaint != null -> SVG_OPEN_TYPE_V0_ROUTE_PARAMETERS
                    colorLayers.isNotEmpty() -> SVG_COLR_CPAL_V0_FALLBACK_ROUTE_PARAMETERS
                    else -> SVG_GLYF_OUTLINE_FALLBACK_ROUTE_PARAMETERS
                },
            )
            resource.cachedRepresentation(representationKey)?.let { cached -> return cached }
            val representation = when (svgPaint) {
                null -> if (colorLayers.isEmpty()) {
                    materializeGlyfOutlineFallback(
                        preparedFont,
                        glyphId,
                        cancellationToken,
                        key.fontInstanceKey.geometry.normalizedAxes,
                    )
                } else {
                    materializeColrV0Paint(
                        preparedFont = preparedFont,
                        profile = profile,
                        layers = colorLayers,
                        palette = requireNotNull(colorData).palette(requireNotNull(paletteIndex)),
                        foregroundColor = foregroundColor,
                        glyphId = glyphId,
                        normalizedAxes = key.fontInstanceKey.geometry.normalizedAxes,
                        cancellationToken = cancellationToken,
                    )
                }
                SvgGlyphPaint.Empty -> FontOperationResult.Success(GlyphRepresentation.Empty)
                is SvgGlyphPaint.Paint -> FontOperationResult.Success(GlyphRepresentation.Paint(svgPaint.paint))
            }
            when (representation) {
                is FontOperationResult.Success -> if (cancellationToken.isCancellationRequested()) {
                    FontOperationResult.Cancelled()
                } else {
                    representation.also { success -> resource.cacheRepresentation(representationKey, success) }
                }

                is FontOperationResult.Failure -> representation
                is FontOperationResult.Cancelled -> representation
            }
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }

    private fun materializeGlyfOutlineFallback(
        preparedFont: PreparedTrueTypeFont,
        glyphId: GlyphId,
        cancellationToken: CancellationToken,
        normalizedAxes: List<FontAxisCoordinate>,
    ): FontOperationResult<GlyphRepresentation> {
        val outline = when (
            val result = preparedFont.readGlyphOutline(glyphId, profile.outlineProfile, cancellationToken, normalizedAxes)
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val materialized = when (val result = OutlineMaterializer.materialize(outline, profile.outlineProfile, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val outlineRepresentation = materialized as? GlyphRepresentation.Outline
            ?: return FontOperationResult.Success(GlyphRepresentation.Empty)
        val paint = GlyphPaintIR(
            schemaVersion = profile.schemaVersion,
            rootNode = 0,
            nodes = listOf(GlyphPaintNode.SolidOutline(outlineRepresentation.outline, GlyphColor(0, 0, 0))),
        )
        return if (profile.accepts(paint)) {
            FontOperationResult.Success(GlyphRepresentation.Paint(paint))
        } else {
            failure(
                FontError.UnsupportedRepresentationProfile(
                    "The selected paint profile does not accept the glyf outline fallback for a glyph outside the SVG range.",
                    FontDiagnosticLocation.Glyph(glyphId.value),
                ),
            )
        }
    }
}

/** Asset handle for the explicitly supported COLR version 0 and CPAL version 0 paint route. */
internal class ColrV0RenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val profile: PaintGraphProfile,
    private val colorData: ColrCpalV0Data,
    private val paletteIndex: Int,
    private val foregroundColor: GlyphColor,
) : FontRenderAssetHandle {
    private val palette: List<GlyphColor> = colorData.palette(paletteIndex)
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                ColrV0RenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key.copy(representationProfile = profile),
                    profile = profile,
                    colorData = colorData,
                    paletteIndex = paletteIndex,
                    foregroundColor = foregroundColor,
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val preparedFont = resourceLease?.preparedFont
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val resource = resourceLease?.resource
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val glyphId = GlyphId(request.glyphId)
            val representationKey = GlyphRepresentationKey(
                assetKey = key,
                glyphId = glyphId,
                variant = key.variant,
                profile = GlyphRepresentationProfileKey.paintGraph(profile),
                routeParameters = COLR_CPAL_V0_ROUTE_PARAMETERS,
            )
            resource.cachedRepresentation(representationKey)?.let { cached -> return cached }
            val layers = colorData.layersFor(glyphId)
            val materializedGlyphIds = if (layers.isEmpty()) {
                listOf(ColrV0Layer(glyphId, ColrV0Layer.foregroundColorIndex))
            } else {
                layers
            }
            when (
                val materialized = materializeColrV0Paint(
                    preparedFont = preparedFont,
                    profile = profile,
                    layers = materializedGlyphIds,
                    palette = palette,
                    foregroundColor = foregroundColor,
                    glyphId = glyphId,
                    normalizedAxes = key.fontInstanceKey.geometry.normalizedAxes,
                    cancellationToken = cancellationToken,
                )
            ) {
                is FontOperationResult.Success -> if (cancellationToken.isCancellationRequested()) FontOperationResult.Cancelled()
                else materialized.also { success -> resource.cacheRepresentation(representationKey, success) }

                is FontOperationResult.Failure -> materialized
                is FontOperationResult.Cancelled -> materialized
            }
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

private fun materializeColrV0Paint(
    preparedFont: PreparedTrueTypeFont,
    profile: PaintGraphProfile,
    layers: List<ColrV0Layer>,
    palette: List<GlyphColor>,
    foregroundColor: GlyphColor,
    glyphId: GlyphId,
    normalizedAxes: List<FontAxisCoordinate>,
    cancellationToken: CancellationToken,
): FontOperationResult<GlyphRepresentation> {
    val nodes = ArrayList<GlyphPaintNode>(layers.size + 1)
    for (layer in layers) {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val outline = when (
            val result = preparedFont.readGlyphOutline(layer.glyphId, profile.outlineProfile, cancellationToken, normalizedAxes)
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val representation = when (val result = OutlineMaterializer.materialize(outline, profile.outlineProfile, cancellationToken)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val outlineIr = (representation as? GlyphRepresentation.Outline)?.outline ?: continue
        val color = if (layer.paletteIndex == ColrV0Layer.foregroundColorIndex) foregroundColor else palette[layer.paletteIndex]
        nodes += GlyphPaintNode.SolidOutline(outlineIr, color)
    }
    if (nodes.isEmpty()) return FontOperationResult.Success(GlyphRepresentation.Empty)
    val root = if (nodes.size == 1) {
        0
    } else {
        nodes += GlyphPaintNode.Group((nodes.indices).toList())
        nodes.lastIndex
    }
    val paint = GlyphPaintIR(schemaVersion = profile.schemaVersion, rootNode = root, nodes = nodes)
    if (!profile.accepts(paint)) {
        return failure(
            FontError.ResourceLimitExceeded(
                "COLR version 0 paint graph exceeds the selected profile.",
                FontDiagnosticLocation.Glyph(glyphId.value),
            ),
        )
    }
    return FontOperationResult.Success(GlyphRepresentation.Paint(paint))
}

/** Asset handle for one validated bitmap route held by the embedded face. */
internal class BitmapRenderAssetHandle(
    override val faceId: FontFaceId,
    private var resourceLease: PreparedFontResourceLease?,
    override val key: FontRenderAssetKey,
    private val route: BitmapRouteData,
) : FontRenderAssetHandle {
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    override fun detach(): FontOperationResult<FontRenderAssetHandle> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            val detachedResourceLease = resourceLease?.resource?.acquireLease()
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            FontOperationResult.Success(
                BitmapRenderAssetHandle(
                    faceId = faceId,
                    resourceLease = detachedResourceLease,
                    key = key,
                    route = route,
                ),
            )
        } finally {
            lease.release()
        }
    }

    override fun resolveGlyph(request: FontGlyphRequest): FontOperationResult<GlyphRepresentation> =
        resolveGlyph(request, CancellationToken.none)

    override fun resolveGlyph(
        request: FontGlyphRequest,
        cancellationToken: CancellationToken,
    ): FontOperationResult<GlyphRepresentation> {
        val lease = lifecycle.acquireLease()
            ?: return failure(FontError.ResourceClosed("Render asset is closed."))
        return try {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val resource = resourceLease?.resource
                ?: return failure(FontError.ResourceClosed("Render asset is closed."))
            val glyphId = GlyphId(request.glyphId)
            val profile = requireNotNull(key.representationProfile as? BitmapProfile) {
                "Bitmap render asset requires a bitmap asset key."
            }
            val representationKey = GlyphRepresentationKey(
                assetKey = key,
                glyphId = glyphId,
                variant = key.variant,
                profile = GlyphRepresentationProfileKey.bitmap(profile),
                routeParameters = route.routeParameters,
            )
            resource.cachedRepresentation(representationKey)?.let { cached -> return cached }
            when (val decoded = route.decode(glyphId, cancellationToken)) {
                is FontOperationResult.Success -> {
                    if (cancellationToken.isCancellationRequested()) FontOperationResult.Cancelled()
                    else {
                        val representation: GlyphRepresentation = if (
                            decoded.value.copyDecodedPixels().any { pixel -> pixel != 0.toByte() }
                        ) {
                            GlyphRepresentation.Bitmap(decoded.value)
                        } else {
                            GlyphRepresentation.Empty
                        }
                        FontOperationResult.Success(representation, decoded.diagnostics)
                            .also { success -> resource.cacheRepresentation(representationKey, success) }
                    }
                }

                is FontOperationResult.Failure -> decoded
                is FontOperationResult.Cancelled -> decoded
            }
        } finally {
            lease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

internal fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
    FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
