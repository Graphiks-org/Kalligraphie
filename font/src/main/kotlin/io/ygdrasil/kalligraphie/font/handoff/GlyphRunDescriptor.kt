package io.ygdrasil.kalligraphie.font.handoff

import io.ygdrasil.kalligraphie.font.atlas.GlyphAtlasPlacement
import io.ygdrasil.kalligraphie.font.atlas.GlyphAtlasUploadPlan
import io.ygdrasil.kalligraphie.font.glyph.GlyphStrikeKey

data class GlyphDescriptor(
    val strikeKey: GlyphStrikeKey,
    val placement: GlyphAtlasPlacement,
    val drawX: Float,
    val drawY: Float,
)

data class GlyphRunDescriptor(
    val glyphs: List<GlyphDescriptor>,
    val atlasPlan: GlyphAtlasUploadPlan,
)
