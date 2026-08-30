package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FontIdentityContractTest {
    @Test
    fun identityDomainsKeepSourceFaceAndInstanceDimensionsDistinct() {
        val portable = FontSourceId.Portable(FontContentDigest("a".repeat(64)))
        val opaqueGenerationOne = FontSourceId.Opaque(
            providerId = "system-font-provider",
            catalogGeneration = "catalog-1",
            sourceToken = "font-token",
        )
        val opaqueGenerationTwo = FontSourceId.Opaque(
            providerId = "system-font-provider",
            catalogGeneration = "catalog-2",
            sourceToken = "font-token",
        )
        assertNotEquals(opaqueGenerationOne, opaqueGenerationTwo)
        assertNotEquals<FontSourceId>(portable, opaqueGenerationOne)

        val face = FontFaceId(source = portable, faceIndex = 2)
        assertEquals(portable, face.source)
        assertEquals(2, face.faceIndex)
        assertNotEquals(face, FontFaceId(source = portable, faceIndex = 3))

        val geometry = FontGeometryParameters(
            normalizedAxes = listOf(
                FontAxisCoordinate(tag = "wght", value = 700f),
                FontAxisCoordinate(tag = "wdth", value = 100f),
            ),
            syntheticBold = true,
            syntheticItalic = false,
        )
        val key = FontInstanceKey(
            face = face,
            interpretation = FontDataInterpretationVersion(
                pipelineId = "org.graphiks.kalligraphie.true-type",
                version = "1",
            ),
            layoutSize = LayoutUnit(12f),
            geometry = geometry,
        )

        assertEquals(face, key.face)
        assertEquals(2, key.face.faceIndex)
        assertEquals(2, key.geometry.normalizedAxes.size)
        assertNotEquals(key, key.copy(layoutSize = LayoutUnit(13f)))
        assertNotEquals(
            key,
            key.copy(interpretation = FontDataInterpretationVersion("org.graphiks.kalligraphie.true-type", "2")),
        )
        assertNotEquals(
            key,
            key.copy(geometry = FontGeometryParameters(syntheticItalic = true)),
        )
    }
}
