package io.ygdrasil.kalligraphie.font

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FontIdentityAuthorityTest {
    @Test
    fun `memory font identity changes with content and preserves caller bytes`() {
        val first = byteArrayOf(0, 1, 2, 3)
        val same = first.copyOf()
        val changed = byteArrayOf(0, 1, 2, 4)

        val firstId = FontIdentityAuthority.memorySource(first, "fixture").sourceId()

        assertEquals(firstId, FontIdentityAuthority.memorySource(same, "fixture").sourceId())
        assertNotEquals(firstId, FontIdentityAuthority.memorySource(changed, "fixture").sourceId())
        first[0] = 99
        assertEquals(firstId, FontIdentityAuthority.memorySource(same, "fixture").sourceId())
    }

    @Test
    fun `memory font identity records collection face count when advertised`() {
        val collectionHeader = byteArrayOf(
            0x74, 0x74, 0x63, 0x66,
            0x00, 0x01, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x02,
        )

        val preimage = FontIdentityAuthority.memorySource(
            bytes = collectionHeader,
            declaredName = "fixture.ttc",
            parserGeneration = 7,
        )

        assertEquals(FontSourceKind.MEMORY, preimage.kind)
        assertEquals(2, preimage.faceCount)
        assertEquals(emptyList(), preimage.tableTags)
        assertEquals(7, preimage.parserGeneration)
    }
}
