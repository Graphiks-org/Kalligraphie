package io.ygdrasil.kalligraphie.font.glyph

import java.security.MessageDigest

data class GlyphStrikeKey(
    val glyphId: Int,
    val size: Float,
    val subpixelX: Int,
    val subpixelY: Int,
) {
    fun cacheHash(): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(glyphId.toString().encodeToByteArray())
        md.update(size.toBits().toString().encodeToByteArray())
        md.update(subpixelX.toString().encodeToByteArray())
        md.update(subpixelY.toString().encodeToByteArray())
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
