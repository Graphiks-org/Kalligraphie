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

## Exact editable Unicode lines

The JVM reference target also provides one complete headless route for a
single non-wrapped editable line. `Kalligraphie.decodeUtf8(...)` or
`Kalligraphie.decodeUtf16(...)` creates an immutable `TextSnapshot`. The
JVM-only `JvmEditableLineFacade` then analyzes Unicode, resolves script and
BiDi runs, shapes each run with its embedded HarfBuzz backend, and positions
the final line.

```kotlin
val decoded = Kalligraphie.decodeUtf8(
    version = TextVersion.create(),
    slices = listOf(TextSlice.Utf8(editorBytes)),
)
val result = JvmEditableLineFacade.layout(
    JvmEditableLineFacadeRequest(
        snapshot = decoded.snapshot,
        font = instance,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
        materialization = EditableLineMaterialization.LayoutOnly,
    ),
)
```

Direction, language, feature policy, feature overrides, line metrics, and
publication mode are required inputs. Script and resolved run direction are
produced by the pinned Unicode analysis and passed explicitly to every shaping
request. The result is `EditableLineResult`: on success it contains shaped and
positioned glyphs, text-to-cluster-to-glyph mappings, logical and visual caret
navigation, selection geometry, and deterministic hit testing.

For `RENDERABLE` output, replace `LayoutOnly` with
`EditableLineMaterialization.Renderable` and provide an open resolver, a
variant, and an `OutlineProfile`. Every published final glyph then carries an
outline-route certificate tied to its exact `FontRenderAssetKey`. The resolver
remains caller-owned; the facade borrows it only during the synchronous call.

The embedded HarfBuzz 14.3.0 backend is the JVM reference implementation. Its
Linux and macOS x64/arm64 resources are pinned, hash-verified, and never found
through a system-library search. Public contracts contain no JNI or native
types. Android and Apple do not yet provide executable shaping adapters, so
this route must not be treated as conformant on those platforms.

Out of scope: wrapping, paragraphs, fallback across fonts, hyphenation,
justification, vertical writing, rendering pixels, GPU APIs, TTC/OTC,
CFF/CFF2, variations, synthetic styles, COLR, SVG, bitmap glyphs, and system
fonts.
