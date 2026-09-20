package org.graphiks.kalligraphie.platform.windows

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile

/**
 * The consumer journey for the DirectWrite registry provider, on a controlled
 * provider boundary over an audited font collection.
 *
 * Windows carries no bundled HarfBuzz backend, so the journey reaches the
 * catalogue, selection, instance creation, render-asset certification and
 * retention: captured bytes are materialized into a portable render asset. The
 * registry is substituted so the journey never depends on what is installed.
 */
class DirectWriteRegistryJourneyTest {
    @Test
    fun certifiesAndRetainsTheSelectedAmiriFace() {
        withCollectionFile { collection ->
            val catalog = success(DirectWriteSystemFontCatalog.open(registry = registryOf(collection)))

            val requirements = renderable()
            val face = catalog.faces.single { it.metadata.familyName == "Amiri" }
            val instance = success(
                success(catalog.resolveFace(face.id, requirements)).instantiate(FontInstanceDescriptor(LayoutUnit(1000f))),
            )
            val resolver = success(catalog.openAssetResolver())
            try {
                val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, requirements))
                asset.close()
            } finally {
                resolver.close()
            }
        }
    }

    @Test
    fun aControlledInstallIsObservedByANewOpenWithoutInvalidatingThePreviousSnapshot() {
        withCollectionFile { collection ->
            val first = success(DirectWriteSystemFontCatalog.open(registry = registryOf(collection)))
            val firstFaceIds = first.faces.map { it.id }
            val firstGeneration = first.generation

            val second = success(
                DirectWriteSystemFontCatalog.open(
                    registry = DirectWriteFontRegistry {
                        registryOf(collection).availableFonts() + DirectWriteRegisteredFont(
                            familyName = "Liberation Sans",
                            faceName = "Regular",
                            filePath = collection.toString(),
                            postScriptName = "LiberationSans",
                        )
                    },
                ),
            )

            assertNotEquals(firstGeneration, second.generation, "a controlled change must mint a new generation")
            assertEquals(firstFaceIds, first.faces.map { it.id }, "the previous snapshot must stay immutable")
            assertTrue(second.faces.isNotEmpty())
        }
    }

    private fun registryOf(collection: Path): DirectWriteFontRegistry = DirectWriteFontRegistry {
        listOf(
            DirectWriteRegisteredFont(
                familyName = "Amiri",
                faceName = "Regular",
                filePath = collection.toString(),
                postScriptName = "Amiri-Regular",
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

    private fun withCollectionFile(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("directwrite-registry-journey")
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
