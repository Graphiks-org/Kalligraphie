package org.graphiks.kalligraphie.platform.apple

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.JvmEditableLineFacadeRequest
import org.graphiks.kalligraphie.layout.openLayoutHandle
import org.graphiks.kalligraphie.JvmEditableLineLayoutSession
import org.graphiks.kalligraphie.shaping.HarfBuzzShapingBackend

/**
 * The complete consumer journey for the CoreText registry provider, on a
 * controlled provider boundary over an audited font collection.
 *
 * macOS only: the portable shaping backend is bundled for linux/macos, so this
 * is one of the two targets where the journey reaches shaping and layout. The
 * registry is substituted with a deterministic one so the journey never depends
 * on what happens to be installed.
 */
class CoreTextRegistryJourneyTest {
    @Test
    fun shapesLaysOutCertifiesAndRetainsTheSelectedAmiriFace() {
        withCollectionFile { collection ->
            val catalog = success(CoreTextSystemFontCatalog.open(registry = registryOf(collection)))

            val requirements = renderable()
            val face = catalog.faces.single { it.metadata.familyName == "Amiri" }
            val font = success(
                success(catalog.resolveFace(face.id, requirements)).instantiate(FontInstanceDescriptor(LayoutUnit(1000f))),
            )
            val resolver = success(catalog.openAssetResolver())
            val session = success(JvmEditableLineLayoutSession.open())
            try {
                val snapshot = Kalligraphie.decodeUtf8(TextVersion.create(), listOf(TextSlice.Utf8("Affi".encodeToByteArray()))).snapshot
                val line = assertIs<EditableLineResult.Success>(
                    session.layout(
                        JvmEditableLineFacadeRequest(
                            snapshot = snapshot,
                            font = font,
                            baseDirection = BaseDirection.LEFT_TO_RIGHT,
                            language = "en",
                            featurePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
                            features = emptyList(),
                            verticalMetrics = LineVerticalMetrics(LayoutUnit(1000f), LayoutUnit(250f)),
                            materialization = EditableLineMaterialization.Renderable(resolver, FontRenderVariantSnapshot.default, requirements),
                        ),
                    ),
                ).line

                val glyphs = line.positionedGlyphRuns.flatMap { it.glyphs }
                assertEquals(listOf(6227, 6631), glyphs.map { it.shapedGlyph.glyphId.value }, "audited Amiri glyph ids")
                assertEquals(listOf(612f, 795f), glyphs.map { it.advance.x.value }, "audited Amiri advances")
                assertTrue(glyphs.all { it.materializationCertificate != null }, "every glyph must be certified")

                val handle = success(line.openLayoutHandle(resolver))
                try {
                    for (glyph in glyphs) {
                        val certificate = assertNotNull(glyph.materializationCertificate)
                        val retained = success(handle.retainFontAsset(certificate))
                        try {
                            val representation = success(retained.resolveGlyph(FontGlyphRequest(glyph.shapedGlyph.glyphId)))
                            assertTrue(
                                assertIs<GlyphRepresentation.Outline>(representation).outline.contours.isNotEmpty(),
                                "the retained asset must materialize a portable outline",
                            )
                        } finally {
                            retained.close()
                        }
                    }
                } finally {
                    handle.close()
                }
            } finally {
                session.close()
                resolver.close()
            }
        }
    }

    @Test
    fun aControlledInstallIsObservedByANewOpenWithoutInvalidatingThePreviousSnapshot() {
        withCollectionFile { collection ->
            val first = success(CoreTextSystemFontCatalog.open(registry = registryOf(collection)))
            val firstFaceIds = first.faces.map { it.id }
            val firstGeneration = first.generation

            val second = success(
                CoreTextSystemFontCatalog.open(
                    registry = CoreTextFontRegistry {
                        registryOf(collection).availableFonts() + CoreTextRegisteredFont(
                            familyName = "Liberation Sans",
                            styleName = "Regular",
                            postScriptName = "LiberationSans",
                            filePath = collection.toString(),
                        )
                    },
                ),
            )

            assertNotEquals(firstGeneration, second.generation, "a controlled change must mint a new generation")
            assertEquals(firstFaceIds, first.faces.map { it.id }, "the previous snapshot must stay immutable")
        }
    }

    private fun registryOf(collection: java.nio.file.Path): CoreTextFontRegistry = CoreTextFontRegistry {
        listOf(
            CoreTextRegisteredFont(
                familyName = "Amiri",
                styleName = "Regular",
                postScriptName = "Amiri-Regular",
                filePath = collection.toString(),
            ),
        )
    }

    private fun renderable(): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(
        OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 16_384,
            maxPoints = 1_000_000,
            maxCompositeDepth = 32,
            maxCompositeComponents = 16_384,
        ),
    )

    private fun withCollectionFile(block: (java.nio.file.Path) -> Unit) {
        val root = Files.createTempDirectory("coretext-registry-journey")
        val collection = root.resolve("LiberationAmiri.ttc")
        try {
            Files.write(collection, collectionFixture())
            block(collection)
        } finally {
            Files.deleteIfExists(collection)
            Files.deleteIfExists(root)
        }
    }

    private fun collectionFixture(): ByteArray = checkNotNull(
        javaClass.getResourceAsStream("/fonts/liberation-amiri-collection/LiberationAmiri.ttc"),
    ) { "Missing the audited Liberation/Amiri collection fixture" }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result, result.toString()).value
}

