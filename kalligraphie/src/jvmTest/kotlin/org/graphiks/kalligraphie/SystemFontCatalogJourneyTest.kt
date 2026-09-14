package org.graphiks.kalligraphie

import java.nio.file.Files
import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.junit.Assume.assumeTrue
import kotlin.test.*

class SystemFontCatalogJourneyTest {
    @Test
    fun macosCollectionShapesTheSelectedAmiriFace() {
        assumeTrue(System.getProperty("os.name").startsWith("Mac"))
        withCollectionRoot { root ->
            val catalog = catalogSuccess(MacosSystemFontCatalog.open(MacosSystemFontCatalogOptions(roots = listOf(root))))
            assertCollectionJourney(catalog, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
        }
    }

    @Test
    fun linuxCollectionShapesTheSelectedAmiriFace() {
        assumeTrue(System.getProperty("os.name").startsWith("Linux"))
        withCollectionRoot { root ->
            val catalog = catalogSuccess(LinuxSystemFontCatalog.open(FontDirectoryCatalogOptions(listOf(root))))
            assertCollectionJourney(catalog, "Amiri", 1000f, listOf(6227, 6631), listOf(612f, 795f))
            assertAuditedA(catalog, "Amiri")
        }
    }
}

internal fun <T> catalogSuccess(result: FontOperationResult<T>): T = assertIs<FontOperationResult.Success<T>>(result, result.toString()).value

internal fun collectionBytes(v2: Boolean = false): ByteArray = checkNotNull(SystemFontCatalogJourneyTest::class.java.getResourceAsStream(
    "/fonts/liberation-amiri-collection/${if (v2) "LiberationAmiri-v2.ttc" else "LiberationAmiri.ttc"}",
)).use { it.readBytes() }

internal fun withCollectionRoot(v2: Boolean = false, block: (String) -> Unit) {
    val root = Files.createTempDirectory("kalligraphie-collection")
    val path = root.resolve("collection.ttc")
    try { Files.write(path, collectionBytes(v2)); block(root.toString()) }
    finally { Files.deleteIfExists(path); Files.deleteIfExists(root) }
}

internal fun assertCollectionJourney(catalog: FontCatalogSnapshot, family: String, size: Float, glyphs: List<Int>, advances: List<Float>) {
    val requirements = FontAccessRequirementsSnapshot.renderable(OutlineProfile(maxBytes = 1_000_000, maxContours = 16_384, maxPoints = 1_000_000, maxCompositeDepth = 32, maxCompositeComponents = 16_384))
    val record = catalog.faces.single { it.metadata.familyName == family }
    val font = catalogSuccess(catalogSuccess(catalog.resolveFace(record.id, requirements)).instantiate(FontInstanceDescriptor(LayoutUnit(size))))
    val resolver = catalogSuccess(catalog.openAssetResolver())
    val session = catalogSuccess(JvmEditableLineLayoutSession.open())
    try {
        val snapshot = Kalligraphie.decodeUtf8(TextVersion.create(), listOf(TextSlice.Utf8("Affi".encodeToByteArray()))).snapshot
        val line = assertIs<EditableLineResult.Success>(session.layout(JvmEditableLineFacadeRequest(
            snapshot = snapshot, font = font, baseDirection = BaseDirection.LEFT_TO_RIGHT,
            language = "en", featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy, features = emptyList(),
            verticalMetrics = LineVerticalMetrics(LayoutUnit(size), LayoutUnit(size / 4)),
            materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantSnapshot.default, requirements),
        ))).line
        val finalGlyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
        assertEquals(glyphs, finalGlyphs.map { it.shapedGlyph.glyphId.value })
        assertEquals(advances, finalGlyphs.map { it.advance.x.value })
        assertTrue(finalGlyphs.all { it.materializationCertificate != null })
        val handle = catalogSuccess(line.openLayoutHandle(resolver))
        try {
            for ((index, glyph) in finalGlyphs.withIndex()) {
                val certificate = assertNotNull(glyph.materializationCertificate)
                val retained = catalogSuccess(handle.retainFontAsset(certificate))
                try {
                    val representation = catalogSuccess(retained.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId)))
                    assertTrue(assertIs<GlyphRepresentation.Outline>(representation).outline.contours.isNotEmpty())
                    if (index == 0) assertAuditedOutline(representation, family)
                } finally { retained.close() }
            }
        } finally { handle.close() }
    } finally { session.close(); resolver.close() }
}
