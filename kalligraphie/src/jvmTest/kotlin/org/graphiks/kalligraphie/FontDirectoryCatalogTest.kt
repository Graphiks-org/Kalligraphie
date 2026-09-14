package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.api.*
import kotlin.test.*

class FontDirectoryCatalogTest {
    @Test
    fun bothCollectionVersionsExposeCompleteDifferentAuditedGlyphsAndShaping() {
        for (v2 in listOf(false, true)) withCollectionRoot(v2) { root ->
            val catalog = catalogSuccess(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root))))
            assertCollectionJourney(catalog, "Liberation Sans", 2048f, listOf(36, 73, 73, 76), listOf(1366f, 532f, 569f, 455f))
            assertCollectionJourney(catalog, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
            assertAuditedA(catalog, "Liberation Sans")
            assertAuditedA(catalog, "Amiri")
        }
    }

    @Test
    fun retainedCollectionGlyphsSurviveReplacementRemovalAndWrongGenerationReopening() {
        withCollectionRoot { root ->
            val options = FontDirectoryCatalogOptions(listOf(root))
            val first = catalogSuccess(FontDirectoryCatalog.open(options))
            val resolver = catalogSuccess(first.openAssetResolver())
            val font = collectionInstance(first, "Amiri")
            val asset = catalogSuccess(font.acquireRenderAsset(resolver, FontRenderVariantKey.default, collectionRequirements()))
            try {
                Files.write(Path.of(root, "collection.ttc"), collectionFixture("/fonts/liberation/LiberationSans-Regular.ttf"))
                val replaced = catalogSuccess(FontDirectoryCatalog.open(options))
                assertAuditedA(replaced, "Liberation Sans")
                val replacementResolver = catalogSuccess(replaced.openAssetResolver())
                try { assertIs<FontError.IncompatibleCatalogGeneration>(assertIs<FontOperationResult.Failure>(replacementResolver.reopen(asset.key)).error) }
                finally { replacementResolver.close() }
                Files.delete(Path.of(root, "collection.ttc"))
                assertIs<FontOperationResult.Failure>(FontDirectoryCatalog.open(options))
                resolver.close()
                assertAuditedOutline(catalogSuccess(asset.resolveGlyph(FontGlyphRequest(6227))), "Amiri")
                assertEquals(612, catalogSuccess(font.metrics(GlyphId(6227))).advanceWidthDesignUnits)
                assertCollectionJourney(first, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
            } finally { asset.close(); resolver.close() }
        }
    }

    @Test
    fun unchangedRecaptureRejectsOldAssetButKeepsBothGenerationsUsable() {
        withCollectionRoot { root ->
            val options = FontDirectoryCatalogOptions(listOf(root))
            val first = catalogSuccess(FontDirectoryCatalog.open(options))
            val second = catalogSuccess(FontDirectoryCatalog.open(options))
            val firstResolver = catalogSuccess(first.openAssetResolver())
            val secondResolver = catalogSuccess(second.openAssetResolver())
            val asset = catalogSuccess(collectionInstance(first, "Amiri").acquireRenderAsset(firstResolver, FontRenderVariantKey.default, collectionRequirements()))
            try {
                assertIs<FontError.IncompatibleCatalogGeneration>(assertIs<FontOperationResult.Failure>(secondResolver.reopen(asset.key)).error)
                assertAuditedOutline(catalogSuccess(asset.resolveGlyph(FontGlyphRequest(6227))), "Amiri")
                assertAuditedA(second, "Amiri")
            } finally { asset.close(); firstResolver.close(); secondResolver.close() }
        }
    }

    @Test
    fun invalidFirstFaceDoesNotRenumberOrSubstituteTheUsableAmiriFace() {
        withSources(mapOf("bad-first.ttc" to collectionBytes().also { putCollectionUInt(it, collectionUInt(it, 12), 0x4F54544Fu) })) { root ->
            val result = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root))))
            assertTrue(result.diagnostics.any { it.code == "font.unsupported-container" })
            assertEquals(1, result.value.faces.single().id.faceIndex)
            assertAuditedA(result.value, "Amiri")
            assertCollectionJourney(result.value, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
        }
    }

    @Test
    fun truncatedCollectionDirectoryRejectsItsSourceWhileSeparateAmiriGlyphsRemainUsable() {
        val bytes = collectionBytes()
        val directory = collectionUInt(bytes, 12)
        bytes[directory + 4] = 0xff.toByte()
        bytes[directory + 5] = 0xff.toByte()
        withSources(mapOf("a-truncated-directory.ttc" to bytes, "b-usable.ttc" to collectionBytes())) { root ->
            val result = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root))))
            assertEquals(directory.toLong() + 12L, result.diagnostics.single { it.code == "font.out-of-bounds" }.data.offset)
            assertAuditedA(result.value, "Amiri")
            assertCollectionJourney(result.value, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
            assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root), maxFacesToExamine = 1))).error)
        }
    }

    @Test
    fun unsupportedOnlyCandidateFailsWithItsTypedFormatError() {
        val cff = collectionFixture("/fonts/liberation/LiberationSans-Regular.ttf").also { putCollectionUInt(it, 0, 0x4F54544Fu) }
        withSources(mapOf("unsupported.OTF" to cff)) { root ->
            assertIs<FontError.UnsupportedContainer>(assertIs<FontOperationResult.Failure>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root)))).error)
        }
    }

    @Test
    fun rejectedCollectionsAndCffAreReportedAlongsideAUsableActualFont() {
        val v1 = collectionBytes()
        val v2 = collectionBytes(true)
        val badSources = listOf(
            v1.copyOf(8), v1.copyOf(15), v2.copyOf(28),
            v1.copyOf().also { putCollectionUInt(it, 8, UInt.MAX_VALUE) },
            v1.copyOf().also { putCollectionUInt(it, 12, UInt.MAX_VALUE); putCollectionUInt(it, 16, UInt.MAX_VALUE) },
            v1.copyOf().also { bytes -> for (position in listOf(12, 16)) putCollectionUInt(bytes, collectionUInt(bytes, position) + 20, UInt.MAX_VALUE) },
            v2.copyOf().also { putCollectionUInt(it, 20, 0x44534947u); putCollectionUInt(it, 24, 50u); putCollectionUInt(it, 28, UInt.MAX_VALUE) },
            v2.copyOf().also { putCollectionUInt(it, 24, 1u) },
            v1.copyOf().also { putCollectionUInt(it, 4, 0x00030000u) },
            collectionFixture("/fonts/liberation/LiberationSans-Regular.ttf").also { putCollectionUInt(it, 0, 0x4F54544Fu) },
        )
        val sources = badSources.mapIndexed { index, bytes -> "bad-$index.otc" to bytes }.toMap() + ("usable.TTC" to v1)
        withSources(sources) { root ->
            val result = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root))))
            assertTrue(result.diagnostics.size >= badSources.size, result.diagnostics.toString())
            assertTrue(result.diagnostics.any { it.code == "font.unsupported-container" })
            assertAuditedA(result.value, "Amiri")
        }
    }

    @Test
    fun duplicateActualContainersDoNotConsumeAggregateBudgetOrLoseSiblingGlyphs() {
        val bytes = collectionBytes()
        withSources(mapOf("first.ttc" to bytes, "second.otc" to bytes)) { root ->
            val catalog = catalogSuccess(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root), maxTotalSourceBytes = bytes.size)))
            assertAuditedA(catalog, "Liberation Sans")
            assertAuditedA(catalog, "Amiri")
            assertCollectionJourney(catalog, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
        }
    }

    @Test
    fun unexaminedCorruptSiblingCannotPublishAnOtherwiseRenderableCollectionPrefix() {
        for (v2 in listOf(false, true)) {
            val bytes = collectionBytes(v2)
            val siblingDirectory = collectionUInt(bytes, 16)
            bytes[siblingDirectory + 4] = 0xff.toByte()
            bytes[siblingDirectory + 5] = 0xff.toByte()
            withSources(mapOf("corrupt-sibling.ttc" to bytes)) { root ->
                val result = FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root), maxFacesToExamine = 1))
                assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(result).error)
            }
        }
    }

    @Test
    fun collectionThatCannotBeCompletelyExaminedLeavesBudgetForIndependentRenderableSource() {
        withSources(mapOf("a-collection.ttc" to collectionBytes(), "b-standalone.ttf" to collectionFixture("/fonts/amiri/Amiri-Regular.ttf"))) { root ->
            val result = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root), maxFacesToExamine = 1)))
            assertTrue(result.diagnostics.any { it.code == "font.resource-limit-exceeded" })
            assertAuditedA(result.value, "Amiri")
            assertCollectionJourney(result.value, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
        }
    }

    @Test
    fun sourceAggregateDiscoveryAndExaminedFaceCapsExcludeActualUsableGlyphs() {
        withCollectionRoot { root ->
            for (options in listOf(
                FontDirectoryCatalogOptions(listOf(root), maxSourceBytes = 841847),
                FontDirectoryCatalogOptions(listOf(root), maxTotalSourceBytes = 841847),
                FontDirectoryCatalogOptions(listOf(root), maxPathsToVisit = 1),
                FontDirectoryCatalogOptions(listOf(root), maxFacesToExamine = 1),
            )) assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(FontDirectoryCatalog.open(options)).error)
            for (options in listOf(FontDirectoryCatalogOptions(listOf(root), maxFaces = 1))) {
                val result = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(FontDirectoryCatalog.open(options))
                assertTrue(result.diagnostics.any { it.code == "font.resource-limit-exceeded" })
                assertAuditedA(result.value, "Liberation Sans")
                assertCollectionJourney(result.value, "Liberation Sans", 2048f, listOf(36, 73, 73, 76), listOf(1366f, 532f, 569f, 455f))
                assertFalse(result.value.faces.any { it.metadata.familyName == "Amiri" })
            }
        }
        withSources(mapOf("invalid.ttc" to collectionBytes().also { putCollectionUInt(it, collectionUInt(it, 12), 0x4F54544Fu) })) { root ->
            assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root), maxFacesToExamine = 1))).error)
        }
    }

    @Test
    fun nonexistentRootsCountAgainstDiscoveryAndDiagnosticsRemainBounded() {
        withCollectionRoot { root ->
            val missing = (0..4).map { "$root/missing-$it" }
            assertIs<FontError.ResourceLimitExceeded>(assertIs<FontOperationResult.Failure>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(missing + root, maxPathsToVisit = 5))).error)
            val result = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(missing + root, maxDiagnostics = 2)))
            assertEquals(2, result.diagnostics.size)
            assertTrue(result.diagnostics.any { it.code == "font.capture.diagnostics-truncated" })
            assertAuditedA(result.value, "Amiri")
        }
    }

    @Test
    fun cancellationDuringChunkedCollectionCaptureNeverPublishesAndIndependentRetryWorks() {
        withCollectionRoot { root ->
            val options = FontDirectoryCatalogOptions(listOf(root))
            assertIs<FontOperationResult.Cancelled>(FontDirectoryCatalog.open(options, CancellationToken.cancelled))
            var checks = 0
            assertIs<FontOperationResult.Cancelled>(FontDirectoryCatalog.open(options, CancellationToken { ++checks > 12 }))
            assertAuditedA(catalogSuccess(FontDirectoryCatalog.open(options)), "Amiri")
        }
    }

    @Test
    fun aggregateSourceLimitReportsExcludedFontWhileRetainedCollectionFacesStayUsable() {
        withSources(mapOf("a-collection.ttc" to collectionBytes(), "z-second.ttc" to collectionBytes(true))) { root ->
            val result = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root), maxTotalSourceBytes = 841848)))
            assertTrue(result.diagnostics.any { it.code == "font.resource-limit-exceeded" })
            assertAuditedA(result.value, "Amiri")
            assertCollectionJourney(result.value, "Liberation Sans", 2048f, listOf(36, 73, 73, 76), listOf(1366f, 532f, 569f, 455f))
        }
    }

    @Test
    fun closedSharedScopeStillAllowsNewCollectionCaptureAndRenderableGlyphs() {
        val scope = Kalligraphie.fontCacheScope(FontCacheBudget(1_000_000, 1_000_000, 0, 0))
        try {
            catalogSuccess(scope.close())
            withCollectionRoot { root ->
                val catalog = catalogSuccess(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root), materializationCachePolicy = FontMaterializationCachePolicy(1_000_000), cacheScope = scope)))
                assertAuditedA(catalog, "Amiri")
                assertCollectionJourney(catalog, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
            }
        } finally { scope.close() }
    }

    @Test
    fun symbolicCandidatesAndDirectoriesCannotEscapeTheConfiguredRoot() {
        withCollectionRoot(v2 = true) { outside ->
            withSources(mapOf("safe.ttc" to collectionBytes())) { root ->
                val fileLink = Path.of(root, "escaped.ttc")
                val directoryLink = Path.of(root, "escaped-directory")
                try {
                    Files.createSymbolicLink(fileLink, Path.of(outside, "collection.ttc"))
                    Files.createSymbolicLink(directoryLink, Path.of(outside))
                    val catalog = catalogSuccess(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(root))))
                    assertAuditedA(catalog, "Amiri")
                    assertEquals(2, catalog.faces.size)
                    assertIs<FontOperationResult.Failure>(FontDirectoryCatalog.open(FontDirectoryCatalogOptions(listOf(directoryLink.toString()))))
                } finally { Files.deleteIfExists(fileLink); Files.deleteIfExists(directoryLink) }
            }
        }
    }
}

internal fun collectionRequirements(): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(OutlineProfile(maxBytes = 1_000_000, maxContours = 16_384, maxPoints = 1_000_000, maxCompositeDepth = 32, maxCompositeComponents = 16_384))
internal fun collectionInstance(catalog: FontCatalogSnapshot, family: String): FontInstance {
    val record = catalog.faces.single { it.metadata.familyName == family }
    return catalogSuccess(catalogSuccess(catalog.resolveFace(record.id, collectionRequirements())).instantiate(FontInstanceDescriptor(LayoutUnit(if (family == "Amiri") 1000f else 2048f))))
}
internal fun assertAuditedA(catalog: FontCatalogSnapshot, family: String) {
    val font = collectionInstance(catalog, family)
    val glyph = catalogSuccess(font.resolveGlyph(0x41)).glyphId
    assertEquals(if (family == "Amiri") 6227 else 36, glyph.value)
    val metrics = catalogSuccess(font.metrics(glyph))
    assertEquals(if (family == "Amiri") 612 else 1366, metrics.advanceWidthDesignUnits)
    assertEquals(if (family == "Amiri") -14 else 4, metrics.leftSideBearingDesignUnits)
    assertEquals(if (family == "Amiri") DesignBounds(-14, -3, 619, 647) else DesignBounds(4, 0, 1362, 1409), metrics.bounds)
    val resolver = catalogSuccess(catalog.openAssetResolver())
    val asset = catalogSuccess(font.acquireRenderAsset(resolver, FontRenderVariantKey.default, collectionRequirements()))
    try { assertAuditedOutline(catalogSuccess(asset.resolveGlyph(FontGlyphRequest(glyph))), family) }
    finally { asset.close(); resolver.close() }
}
internal fun assertAuditedOutline(representation: GlyphRepresentation, family: String) {
    val outline = assertIs<GlyphRepresentation.Outline>(representation).outline
    assertEquals(if (family == "Amiri") 1000 else 2048, outline.unitsPerEm)
    assertEquals(if (family == "Amiri") DesignBounds(-14, -3, 619, 647) else DesignBounds(4, 0, 1362, 1409), outline.bounds)
    val expected = collectionFixture("/fonts/liberation-amiri-collection/${if (family == "Amiri") "amiri" else "liberation"}-A-outline.txt").decodeToString().trim().lines().map { row ->
        val values = row.split(' ')
        val coordinates = values.drop(1).map(String::toDouble)
        when (values.first()) {
            "M" -> GlyphOutlineIR.Command.MoveTo(coordinates[0], coordinates[1])
            "L" -> GlyphOutlineIR.Command.LineTo(coordinates[0], coordinates[1])
            "Q" -> GlyphOutlineIR.Command.QuadraticTo(coordinates[0], coordinates[1], coordinates[2], coordinates[3])
            else -> GlyphOutlineIR.Command.Close
        }
    }
    assertEquals(expected, outline.commands)
    assertEquals(2, outline.contours.size)
}
internal fun collectionFixture(path: String): ByteArray = checkNotNull(SystemFontCatalogJourneyTest::class.java.getResourceAsStream(path)).use { it.readBytes() }
internal fun withSources(sources: Map<String, ByteArray>, block: (String) -> Unit) {
    val root = Files.createTempDirectory("kalligraphie-capture")
    try { sources.forEach { (name, bytes) -> Files.write(root.resolve(name), bytes) }; block(root.toString()) }
    finally { sources.keys.forEach { Files.deleteIfExists(root.resolve(it)) }; Files.deleteIfExists(root) }
}
internal fun collectionUInt(bytes: ByteArray, offset: Int): Int = (0..3).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 255) }
internal fun putCollectionUInt(bytes: ByteArray, offset: Int, value: UInt) { repeat(4) { bytes[offset + it] = (value shr (24 - 8 * it)).toByte() } }
