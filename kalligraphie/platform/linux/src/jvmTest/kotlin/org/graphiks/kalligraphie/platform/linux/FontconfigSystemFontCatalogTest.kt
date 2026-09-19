package org.graphiks.kalligraphie.platform.linux

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile

/**
 * Controlled-boundary tests for the Fontconfig system provider.
 *
 * The production provider enumerates the real Fontconfig configuration through
 * `kffi-fontconfig`; these tests substitute a deterministic registry over audited
 * fixture files so discovery, refresh and cross-generation refusal are verified
 * without depending on what is installed on the machine.
 */
class FontconfigSystemFontCatalogTest {
    @Test
    fun capturesExactlyTheControlledRegistrySet() {
        withFixtures { liberation, collection ->
            val registry = registryOf(
                liberation to "Liberation Sans",
                collection to "Amiri",
                Path.of(liberation.parent.toString(), "absent.ttf") to "Absent",
            )

            val catalog = success(FontconfigSystemFontCatalog.open(registry = registry))

            assertTrue(catalog.faces.any { it.metadata.familyName == "Liberation Sans" })
            assertTrue(catalog.faces.any { it.metadata.familyName == "Amiri" }, "the collection face must be captured")
            assertEquals("fontconfig-registry", catalog.generation.provider.value)
            assertTrue(catalog.faces.all { it.id.source is FontSourceId.Portable }, "captured sources must be portable")
        }
    }

    @Test
    fun refreshMintsANewGenerationWithoutInvalidatingThePreviousSnapshot() {
        withFixtures { liberation, collection ->
            val first = success(FontconfigSystemFontCatalog.open(registry = registryOf(liberation to "Liberation Sans")))
            val firstFaceIds = first.faces.map { it.id }

            val firstResolver = success(first.openAssetResolver())
            val face = first.faces.single { it.metadata.familyName == "Liberation Sans" }
            val instance = success(
                success(first.resolveFace(face.id, renderable())).instantiate(FontInstanceDescriptor(LayoutUnit(2048f))),
            )
            val asset = success(instance.acquireRenderAsset(firstResolver, FontRenderVariantKey.default, renderable()))
            try {
                val second = success(
                    FontconfigSystemFontCatalog.open(
                        registry = registryOf(liberation to "Liberation Sans", collection to "Amiri"),
                    ),
                )

                assertNotEquals(first.generation, second.generation)
                assertEquals(firstFaceIds, first.faces.map { it.id }, "the previous snapshot must stay immutable")
                assertTrue(second.faces.any { it.metadata.familyName == "Amiri" })

                val secondResolver = success(second.openAssetResolver())
                try {
                    assertIs<FontError.IncompatibleCatalogGeneration>(
                        assertIs<FontOperationResult.Failure>(secondResolver.reopen(asset.key)).error,
                    )
                } finally {
                    secondResolver.close()
                }
            } finally {
                asset.close()
                firstResolver.close()
            }
        }
    }

    @Test
    fun refusesAnEmptyRegistryWithATypedError() {
        val result = FontconfigSystemFontCatalog.open(registry = FontconfigFontRegistry { emptyList() })

        assertIs<FontError.FontDataFailure>(assertIs<FontOperationResult.Failure>(result).error)
    }

    private fun renderable(): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(
        OutlineProfile(
            maxBytes = 1_000_000,
            maxContours = 4_096,
            maxPoints = 1_000_000,
            maxCompositeDepth = 16,
            maxCompositeComponents = 1_024,
        ),
    )

    private fun registryOf(vararg files: Pair<Path, String>): FontconfigFontRegistry = FontconfigFontRegistry {
        files.map { (path, family) -> FontconfigRegisteredFont(family, "Regular", path.toString(), family) }
    }

    private fun withFixtures(block: (liberation: Path, collection: Path) -> Unit) {
        val root = Files.createTempDirectory("fontconfig-registry")
        try {
            val liberation = root.resolve("LiberationSans-Regular.ttf")
            Files.write(liberation, fixture("/fonts/liberation/LiberationSans-Regular.ttf"))
            val collection = root.resolve("LiberationAmiri.ttc")
            Files.write(collection, fixture("/fonts/liberation-amiri-collection/LiberationAmiri.ttc"))
            block(liberation, collection)
        } finally {
            Files.walk(root).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun fixture(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(path)) { "Missing fixture $path" }.use { it.readBytes() }

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
