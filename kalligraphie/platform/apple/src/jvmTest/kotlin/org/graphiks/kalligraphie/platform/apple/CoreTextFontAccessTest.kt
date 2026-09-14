package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*
import kotlin.test.*

class CoreTextFontAccessTest {
    @Test fun excludedColorSourceKeepsItsAuditedPortableLayersUnderOrderedNegotiation() {
        val catalog = success(CoreTextFontCatalog.capture(portableFixture("/fonts/bungee-color/BungeeColor-Regular.ttf"), generousPolicy))
        val paintProfile = PaintGraphProfile(acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(maxNodes = 3, maxReferences = 2, maxDepth = 2, maxSourceBytes = 16_384, maxPaths = 2, maxPalettes = 9,
                maxPaletteEntries = 2, maxColorRecords = 16, maxDecodedPaletteBytes = 72, maxBaseGlyphRecords = 288, maxLayerRecords = 576),
            outlineProfile = portableOutlineProfile)
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.platformProfile, paintProfile))
        val resolver = success(catalog.openAssetResolver())
        try {
            val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
            val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
            assertEquals(GlyphId(43), success(font.resolveGlyph(0x41)).glyphId)
            val asset = success(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements))
            try {
                val paint = assertIs<GlyphRepresentation.Paint>(success(asset.resolveGlyph(FontGlyphRequest(43)))).paint
                val layers = paint.nodes.filterIsInstance<GlyphPaintNode.SolidOutline>()
                assertEquals(listOf(292, 293), layers.map { it.outline.glyphId })
                assertEquals(listOf(GlyphColor(201, 9, 0, 255), GlyphColor(255, 149, 128, 255)), layers.map { it.color })
            } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }
    @Test fun cancellationTransfersNoSnapshotOrAssetAndSubsequentNativeAccessStillWorks() {
        assertIs<FontOperationResult.Cancelled>(CoreTextFontCatalog.capture(portableFixture("/fonts/liberation/LiberationSans-Regular.ttf"), generousPolicy, CancellationToken.cancelled))
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        try {
            val font = platformFont(catalog)
            val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.platformProfile))
            assertIs<FontOperationResult.Cancelled>(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements, CancellationToken.cancelled))
            assertIs<FontOperationResult.Cancelled>(font.acquireRenderAsset(resolver, FontRenderVariantKey("unknown.variant"), requirements, CancellationToken.cancelled))
            val asset = platformAsset(catalog, resolver)
            try {
                assertIs<FontOperationResult.Cancelled>(resolver.reopen(asset.key, CancellationToken.cancelled))
                assertIs<FontOperationResult.Cancelled>(asset.acquirePlatformFontLease(CancellationToken.cancelled))
                val lease = success(asset.acquirePlatformFontLease())
                try { assertIs<FontOperationResult.Cancelled>(lease.validateGlyph(GlyphId(36), CancellationToken.cancelled)) }
                finally { success(lease.close()) }
                assertAuditedAdvance(asset)
            } finally { success(asset.close()) }
            val later = platformAsset(catalog, resolver)
            try { assertAuditedAdvance(later) } finally { success(later.close()) }
        } finally { success(resolver.close()) }
    }
    @Test fun exactCapturedSourceKeepsTheDistinctAdvanceOfItsUnchangedNamesake() {
        val portable = success(org.graphiks.kalligraphie.Kalligraphie.embedded(liberationWithDistinctAdvance(), FontSourceProvenance("Liberation unchanged names, independently audited A advance 2000")))
        val catalog = success(CoreTextFontCatalog.capture(portable, generousPolicy))
        val resolver = success(catalog.openAssetResolver())
        try {
            val asset = platformAsset(catalog, resolver)
            try { assertAuditedAdvance(asset, 2000.0) } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }
    @Test fun nativeRouteRejectsAnotherBridgeAndUnsupportedVisualOrGeometricSelection() {
        val catalog = liberationCatalog()
        val resolver = success(catalog.openAssetResolver())
        try {
            val font = platformFont(catalog)
            val wrongProfiles = listOf(catalog.platformProfile.copy(bridgeId = "another.bridge"), catalog.platformProfile.copy(bridgeVersion = "another.version"), catalog.platformProfile.copy(schemaVersion = 2))
            for (profile in wrongProfiles) {
                assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, FontAccessRequirementsSnapshot.renderable(listOf(profile)))).error)
            }
            val platformRequirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.platformProfile))
            assertIs<FontError.UnsupportedRepresentationProfile>(assertIs<FontOperationResult.Failure>(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot(cpalPaletteIndex = 1), platformRequirements)).error)
            val face = success(catalog.resolveFace(catalog.faces.single().id, platformRequirements))
            assertIs<FontError.InvalidInstanceDescriptor>(assertIs<FontOperationResult.Failure>(face.instantiate(FontInstanceDescriptor(LayoutUnit(2048f), FontGeometryParameters(syntheticBold = true)))).error)
            assertIs<FontError.InvalidInstanceDescriptor>(assertIs<FontOperationResult.Failure>(face.instantiate(FontInstanceDescriptor(LayoutUnit(0f)))).error)
            val asset = platformAsset(catalog, resolver)
            try { assertAuditedAdvance(asset) } finally { success(asset.close()) }
        } finally { success(resolver.close()) }
    }
    @Test fun captureRefusesTheCompleteTwoFaceSourceTotal() {
        val paths = listOf("/fonts/liberation/LiberationSans-Regular.ttf", "/fonts/amiri/Amiri-Regular.ttf")
        val sources = paths.map { path -> FontSource(checkNotNull(javaClass.getResourceAsStream(path)).use { it.readBytes() }, FontSourceProvenance("audited native source total")) }
        val portable = success(org.graphiks.kalligraphie.Kalligraphie.embedded(sources))
        val rejected = CoreTextFontCatalog.capture(portable, CoreTextFontAccessPolicy(2_000_000L, 841_827L, 8_000_000L))
        val exceeded = assertIs<PlatformFontAccessLimitExceeded>(assertIs<FontOperationResult.Failure>(rejected).error)
        assertEquals(PlatformFontAccessPhase.SOURCE_CAPTURE, exceeded.phase)
        assertEquals(PlatformFontAccessDimension.CAPTURED_SOURCE_BYTES, exceeded.dimension)
        assertEquals(841_827L, exceeded.maximum)
        assertEquals(841_828L, exceeded.observed)
    }
    @Test fun captureRefusesAnIndividualSourceAboveTheCallerLimit() {
        val rejected = CoreTextFontCatalog.capture(portableFixture("/fonts/liberation/LiberationSans-Regular.ttf"), CoreTextFontAccessPolicy(410_711L, 8_000_000L, 8_000_000L))
        val exceeded = assertIs<PlatformFontAccessLimitExceeded>(assertIs<FontOperationResult.Failure>(rejected).error)
        assertEquals(PlatformFontAccessPhase.SOURCE_CAPTURE, exceeded.phase)
        assertEquals(PlatformFontAccessDimension.SOURCE_BYTES_PER_FACE, exceeded.dimension)
        assertEquals(410_711L, exceeded.maximum)
        assertEquals(410_712L, exceeded.observed)
    }
    @Test fun capturedLiberationSourceSuppliesItsAuditedGlyphAtTwoSizes() {
        val catalog = success(CoreTextFontCatalog.capture(portableFixture("/fonts/liberation/LiberationSans-Regular.ttf"), generousPolicy))
        val resolver = success(catalog.openAssetResolver())
        val requirements = FontAccessRequirementsSnapshot.renderable(listOf(catalog.platformProfile))
        try {
            for ((size, advance) in listOf(1024f to 683.0, 2048f to 1366.0)) {
                val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
                val font = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(size))))
                val asset = assertIs<PlatformFontRenderAssetHandle>(success(font.acquireRenderAsset(resolver, FontRenderVariantSnapshot.default, requirements, CancellationToken.none)))
                try {
                    val lease = assertIs<CoreTextFontLease>(success(asset.acquirePlatformFontLease()))
                    try {
                        success(lease.validateGlyph(GlyphId(36)))
                        val ref = success(lease.fontRef())
                        assertEquals(size.toDouble(), CoreTextConsumerProbe.size(ref))
                        assertEquals(2048, CoreTextConsumerProbe.unitsPerEm(ref))
                        assertEquals(2620L, CoreTextConsumerProbe.glyphCount(ref))
                        assertEquals(advance, CoreTextConsumerProbe.horizontalAdvance(ref, 36), 0.000001)
                    } finally { success(lease.close()) }
                } finally { success(asset.close()) }
            }
        } finally { success(resolver.close()) }
    }
}
