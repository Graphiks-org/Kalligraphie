package org.graphiks.kalligraphie.font.handoff

import org.graphiks.kalligraphie.font.atlas.GlyphAtlasPlacement
import org.graphiks.kalligraphie.font.atlas.GlyphAtlasUploadPlan
import org.graphiks.kalligraphie.font.glyph.GlyphStrikeKey

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
