package io.ygdrasil.shared.font

import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.uuid.Uuid

class FontStackSmokeTest {
    @Test
    fun exposesFontSourceToJvmConsumers() {
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440000")),
            kind = FontSourceKind.MEMORY,
            displayName = "memory.ttf",
            bytes = byteArrayOf(0, 1, 2),
        )

        assertContentEquals(byteArrayOf(0, 1, 2), source.bytes)
    }
}
