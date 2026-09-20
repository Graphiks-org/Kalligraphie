package org.graphiks.kalligraphie.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile

/**
 * CoreText-registry tests for the iOS system provider.
 *
 * The provider enumerates the real CoreText registry through `CTFontCollection`,
 * so these run on an iOS simulator: they rebuild each font's tables into an
 * SFNT container and assert the portable catalogue materializes from them.
 */
class IosSystemFontCatalogTest {
    @Test
    fun capturesTheCoreTextRegistryAndMaterializesFromTheRebuiltBytes() {
        val catalog = success(IosSystemFontCatalog.open())

        assertTrue(catalog.faces.isNotEmpty(), "the CoreText registry must report at least one face")
        assertTrue(catalog.faces.all { it.metadata.familyName.isNotBlank() }, "every face must carry a family name")
        assertEquals("ios-coretext-registry", catalog.generation.provider.value)

        val resolver = success(catalog.openAssetResolver())
        try {
            val face = catalog.faces.first()
            val instance = success(success(catalog.resolveFace(face.id, renderable())).instantiate(FontInstanceDescriptor(LayoutUnit(2048f))))
            val asset = success(instance.acquireRenderAsset(resolver, FontRenderVariantKey.default, renderable()))
            asset.close()
        } finally {
            resolver.close()
        }
    }

    @Test
    fun refreshMintsANewGenerationWithoutInvalidatingThePreviousSnapshot() {
        val first = success(IosSystemFontCatalog.open())
        val firstFaceIds = first.faces.map { it.id }

        val second = success(IosSystemFontCatalog.open())

        assertNotEquals(first.generation, second.generation)
        assertEquals(firstFaceIds, first.faces.map { it.id }, "the previous snapshot must stay immutable")
        assertEquals(second.faces.map { it.id }, second.faces.map { it.id })
    }

    @Test
    fun refusesAnEmptyRegistryWithATypedError() {
        val result = IosSystemFontCatalog.open(registry = IosFontRegistry { emptyList() })

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

    private fun <T> success(result: FontOperationResult<T>): T =
        assertIs<FontOperationResult.Success<T>>(result).value
}
