# Font Management

Kalligraphie exposes an embedded TrueType path through
`org.graphiks:kalligraphie` on the JVM reference target only. The public
contracts stay portable, but this executable route is JVM-only. A
consumer supplies captured SFNT bytes to `Kalligraphie.embedded(...)`,
resolves face `0`, creates a font instance, and uses a render asset handle to
materialize `GlyphOutlineIR` outlines.

The supported functional scope is intentionally narrow:

- JVM reference target only;
- static SFNT TrueType only: `0x00010000` and `true`;
- one embedded source and face index `0`;
- `LAYOUT_ONLY` for cmap and metrics;
- `RENDERABLE` only with `OutlineProfile` schema version `1`;
- glyph outlines in design units, with separately scaled `LayoutUnit`
  metrics;
- detached render assets that keep resolving after the owning resolver or
  attached handle is closed.

```kotlin
val catalogResult = Kalligraphie.embedded(bytes, provenance)
val faceRequest = FontFaceRequest(0)
val size = FontInstanceDescriptor(LayoutUnit(2048f))
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
```

Renderable glyph access requires an explicit outline profile. Closing a
resolver or render asset is idempotent. New acquisitions after closure return
`font.resource-closed`; a detached asset owns the immutable data required for
`resolveGlyph(...)`.

Out of scope: TTC/OTC, CFF/CFF2, variations, synthetic styles, COLR, SVG,
bitmap glyphs, system fonts, shaping, layout, hinting, rasterization, native
font engines, and platform font handles.
