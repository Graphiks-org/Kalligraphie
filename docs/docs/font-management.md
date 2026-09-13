# Font Management

Kalligraphie exposes an embedded TrueType path through
`org.graphiks:kalligraphie` on the JVM reference target only. The public
contracts stay portable, but this executable route is JVM-only. A
consumer supplies captured SFNT bytes to `Kalligraphie.embedded(...)`,
selects a stable face record, creates a font instance, and uses a render asset handle to
materialize a portable glyph representation.

The supported functional scope is intentionally narrow:

- JVM reference target only;
- static SFNT TrueType only: `0x00010000` and `true`;
- embedded OpenType sources with face index `0` for each source;
- `LAYOUT_ONLY` for cmap and metrics;
- `RENDERABLE` with an explicit `OutlineProfile`, `PaintGraphProfile`, or
  `BitmapProfile` when the selected face advertises the matching route;
- `glyf` outlines in design units, with separately scaled `LayoutUnit`
  metrics;
- schema 1 COLR version 0 / CPAL version 0 paint graphs made from solid
  outlines and ordered groups, plus schema 2 or schema 3 static COLR version 1
  paint graphs backed by CPAL version 0 or 1;
- SVG-in-OpenType table version 0 with raw UTF-8 or single-member gzip UTF-8
  documents: `svg` and `g` containers; self-closing `path` elements with
  `M`, `L`, `H`, `V`, `C`, `S`, and `Z`; and a static
  `defs`/`linearGradient`/`stop` subset applied only to self-closing `rect`
  elements. Paths and rectangles accept opaque `#RRGGBB` or `fill="none"`,
  while rectangles may also reference a preceding local linear
  gradient. `translate` and `scale` are supported. The exact gradient subset
  and remaining exclusions are described below;
- EBLC version 2 / EBDT version 2 bitmap strikes using index subtable format 1
  and image format 1 only: byte-aligned one-bit alpha decoded to `ALPHA_8` in
  sRGB, with an exact requested strike;
- detached render assets that keep resolving after the owning resolver or
  attached handle is closed.

### SVG document transport and bounds

SVG-in-OpenType document bytes are decoded while a render asset is acquired.
The transport may be raw UTF-8 or a single gzip member containing UTF-8. gzip
decoding is streamed while its bounds are enforced. gzip is a compressed
document transport, not a representation exposed to a
consumer: after decoding and validation, the asset retains only immutable
portable IR (intermediate representation) for the supported SVG subset.
Consequently, `resolveGlyph(...)` receives that retained immutable IR, never
the encoded SVG, XML, gzip member, URI, or a renderer resource.

`PaintGraphLimits` applies all of the following bounds before publication:

- `maxSourceBytes` bounds the complete SVG table and the cumulative encoded
  document bytes;
- `maxSvgCompressedDocumentBytes` bounds the encoded bytes of each gzip
  document;
- `maxSvgDecodedDocumentBytes` bounds the decoded UTF-8 bytes of each raw or
  gzip document; and
- `maxSvgTotalDecodedBytes` bounds decoded UTF-8 bytes accumulated across all
  document records.

Each dedicated gzip/decoded limit defaults to `maxSourceBytes` (whose default
is 1,048,576 bytes). Exceeding any of these bounds returns
`FontError.ResourceLimitExceeded` at the `SVG ` table; no partial asset or
certificate is published. A malformed gzip header, a non-DEFLATE member,
reserved gzip header flags, failed gzip integrity checks, concatenated gzip
members, or trailing bytes after a member returns
`FontError.FontDataFailure` with code `font.svg.invalid-gzip`, likewise before
publication. Invalid UTF-8 or malformed SVG follows the existing typed
`FontDataFailure` contract, and SVG markup outside the safe subset returns
`FontError.UnsupportedRepresentationProfile`.

### Safe static SVG gradients

The accepted paint-server subset consists of `defs` containing named
`linearGradient` definitions, which may contain self-closing `stop` elements.
A self-closing `rect` may use an opaque `#RRGGBB` fill, `fill="none"`, or a
`url(#id)` reference to a unique linear gradient defined earlier in the same
document; an absent fill defaults to opaque black. Gradient coordinates use
`objectBoundingBox` only: unitless values and percentages are
resolved relative to the rectangle before its `translate` or `scale` transform
is applied. The SVG defaults are `x1=0%`, `y1=0%`, `x2=100%`, `y2=0%`,
`spreadMethod=pad`, and sRGB interpolation.

`spreadMethod` accepts all three portable modes: `pad`, `repeat`, and `reflect`
map to `PAD`, `REPEAT`, and `REFLECT`. An absent `color-interpolation` or the
value `sRGB` maps to `SRGB`; `linearRGB` maps to `LINEAR_SRGB`. Stops accept a
finite SVG number or percentage `offset`, an opaque `#RRGGBB` `stop-color`, and
a finite SVG number or percentage `stop-opacity`; their respective defaults
are `0`, opaque black, and `1`. The same strict SVG number grammar applies to
rectangle and gradient coordinates and dimensions. Decimal exponent notation
is accepted, but a decimal point must be followed by at least one digit and a
percentage sign must immediately follow its number. Forms such as `1.`,
`1.e2`, and `50 %`, Java-only hexadecimal floating-point forms, and `f`/`d`
suffixes are rejected. Offsets and opacities are clamped to `0.0..1.0`, then offsets
are made non-decreasing in document order. For a multi-stop `repeat` or
`reflect` definition whose first or last offset does not reach `0` or `1`,
Kalligraphie inserts copies of the corresponding terminal stops at those
endpoints. Equal-offset discontinuities remain in source order, and inserted
stops count toward the reached graph's `maxColorStops` limit.
A definition with no stops contributes no ink. For a rectangle whose final
transform preserves area, a one-stop gradient or a source vector with
identical endpoints normalizes to the final stop as `Solid` under the
rectangle's `PathClip`. For any other gradient with at least two stops,
Kalligraphie first resolves its normalized `p0` and `p1`; if those points
coincide, it performs the same solid reduction, otherwise it emits a
`LinearGradient` under that rectangle path. Before emission, normalized `p0`,
`p1`, and `p2` must form a non-collinear triplet. A collinear triplet returns
`font.svg.invalid-gradient`, and no partial asset is published. A singular
final transform omits the rectangle after its fill and reference have been
validated.

Every authored linear gradient requires an exact schema-3 `PaintGraphProfile`.
When normalization actually emits `LinearGradient`, the profile must accept
the reached interpolation space, `UNPREMULTIPLIED` alpha interpolation, and
extend mode together with
`LINEAR_GRADIENT` and `PATH_CLIP`. A solid reduction instead requires `SOLID`
and `PATH_CLIP`, but does not require the definition's interpolation space or
alpha or extend mode. Solid rectangles use `PATH`. Documents with several
painted roots also require `GROUP` and `SOURCE_OVER`. Existing limits
are checked before publication: source and decoded bytes, transforms, parsed
gradient definitions and stops, and generated nodes, references, paths,
clips, gradients, color stops, and depth must all fit. Paint visits are also
bounded for schema 2 and later; schema 1 retains its historical node and depth
checks without applying `maxPaintVisits`. Each
generated rectangle path must also satisfy the profile's `outlineProfile`.
Ordered profile fallback may therefore skip a schema-3 profile that does not
declare every reached capability and select a later compatible profile.

All element IDs accepted on `svg`, `g`, and `linearGradient` are globally
unique. Glyph targets remain unique by glyph ID as a separate invariant.
Paint references are local, fragment-only, and backward-only; unresolved,
forward, external, or otherwise URI-bearing references fail before an asset is
published, even when the rectangle would later contribute no ink. Malformed
or unsupported input never publishes a partial graph.

The subset does not support `viewBox`, `userSpaceOnUse`, `gradientTransform`,
`href`, radial gradients, gradient fills on `path`, CSS or `style` attributes,
general SVG clips or clip paths, masks, strokes, scripts, entities, animation,
external resources, or unlisted elements and attributes. Compression formats
other than the permitted single-member gzip transport remain rejected.

### Paint-graph schemas and static COLR version 1

A `GlyphPaintIR` is a complete immutable paint graph, not pixels or drawing
commands sent to a platform API. `GlyphRepresentation.Paint` and a
`GlyphMaterializationRoute.PAINT_GRAPH` certificate are the public boundary;
Kalligraphie validates and materializes the portable graph but supplies no
renderer or rasterizer.

Schema versions are exact consumer capabilities:

- schema 1 retains `SolidOutline`, `Path`, and source-ordered `Group` nodes.
  Groups use `SOURCE_OVER`; schema 1 cannot advertise schema-2 node kinds,
  gradient extend modes, or another composition mode, and a schema-1 `Group`
  must contain at least one child. Existing COLR version 0 and solid-only
  restricted SVG-in-OpenType graphs remain representable with this schema;
- schema 2 adds unbounded `Solid`, `LinearGradient`, `RadialGradient`, and
  `SweepGradient` paints; `GlyphClip`, `Transform`, and `Composite`; gradient
  extension through `PAD`, `REPEAT`, and `REFLECT`; and all 28 named
  `GlyphPaintCompositionMode` values. A provider certifies a graph only when
  its complete reachable contents match the node kinds, extend modes,
  composition modes, and limits declared by the exact `PaintGraphProfile`. An
  empty schema-2 `Group` represents no paint and is structurally bounded.
  Gradient color lines in this schema use only `LINEAR_SRGB`, meaning that RGB
  interpolation occurs in linear-light sRGB, and only `PREMULTIPLIED` alpha
  interpolation. Schema 2 cannot advertise or carry `SRGB`,
  `UNPREMULTIPLIED`, or a `PathClip` node;
- schema 3 adds explicit interpolation capabilities to every gradient color
  line.
  `LINEAR_SRGB` preserves the schema-2 linear-light behavior, while `SRGB`
  interpolates in the sRGB transfer space. `PREMULTIPLIED` and
  `UNPREMULTIPLIED` distinguish whether RGB is premultiplied by effective
  alpha before interpolation. Schema 3 also adds `PathClip`, which
  restricts a child paint to the fill region of a portable path. A consumer
  declares the exact spaces and alpha modes it supports through
  `acceptedGradientInterpolationSpaces` and
  `acceptedGradientAlphaInterpolationModes`; their defaults remain
  `LINEAR_SRGB` and `PREMULTIPLIED` unless other capabilities are explicitly
  added. The graph is accepted only when each reached gradient uses declared
  space and alpha semantics. Schema 1 cannot advertise either gradient
  capability list.

The JVM embedded and captured-`.ttf` routes accept exactly schema 2 and schema
3 for static COLR version 1; schema 4 and later are not inferred. The same
COLR graph can be published with the selected schema 2 or schema 3 while
retaining `LINEAR_SRGB` interpolation, identical geometry, and identical
literal colors. These routes accept global structures and paint formats `1`,
`2`, `4`, `6`, `8`, `10`, `11`, `12`, `14`, `16`, `18`, `20`, `22`, `24`,
`26`, `28`, `30`, and `32`.
Specialized affine, translate, scale, rotate, and skew paints normalize to one
finite `GlyphAffineTransform`; referenced COLR glyph paints are resolved into
the graph rather than exposed as source-table references. A valid format-1
`PaintColrLayers` record with zero layers is preserved as a one-node paint
graph containing an empty `Group`; it is not collapsed to
`GlyphRepresentation.Empty`. ClipList format 1 with ClipBox format 1 is
accepted. Variable `PaintVar*` formats, ClipBox format 2, variation stores and
maps, CFF/CFF2, and variable CPAL/COLR values are not supported. The separate
SVG-in-OpenType route supports the rectangle-bound static linear gradients
described above through schema 3; it does not gain general SVG clips, masks,
strokes, or animation.

This schema extension adds no rendering backend. The consumer still owns
rasterization, GPU integration, and final display.

Palette and foreground selection are resolved before publication. A null
`FontRenderVariantSnapshot.cpalPaletteIndex` selects palette 0; an explicit
index selects that exact CPAL palette or fails. CPAL index `0xFFFF` resolves
to the variant's exact `foregroundColor`, or opaque black when it is null.
The graph therefore contains literal eight-bit, non-premultiplied sRGB
`GlyphColor` values, not palette indexes. Node and stop opacity remain
separate finite values in `0.0..1.0`. A renderer computes each stop's
effective alpha as `color.alpha / 255.0 * opacity` and represents RGB in the
declared space. With `PREMULTIPLIED`, it premultiplies every stop's RGB by its
effective alpha, then interpolates premultiplied RGB and alpha separately;
this is the COLR behavior and the constructor/profile default. With
`UNPREMULTIPLIED`, it interpolates non-premultiplied RGB and alpha separately,
then premultiplies the resulting RGB only when required for later compositing;
this is the normalized SVG behavior. `LINEAR_SRGB` first linearizes literal
sRGB components and later converts from linear light to the required output
encoding; `SRGB` performs RGB interpolation directly in the sRGB transfer
space. Palette or foreground changes
affect paint literals and asset identity, not shaping, advances, caret
positions, hit testing, or selection geometry.

All points, root bounds, outlines, and transforms use font design coordinates.
For `GlyphAffineTransform(xx, yx, xy, yy, dx, dy)`, consumers apply
`x' = xx*x + xy*y + dx` and `y' = yx*x + yy*y + dy`. `Composite.source` and
`Composite.backdrop` preserve the two OpenType roles; `children` is ordered
`[backdrop, source]`, which is paint order. A `Group` likewise paints children
in list order with `SOURCE_OVER`.

Sweep angles use positive x as zero and increase counter-clockwise in y-up
design space. Reversed endpoints retain clockwise color progression; do not
sort them. See the [OpenType sweep convention](https://learn.microsoft.com/en-us/typography/opentype/spec/colr#sweep-gradients).

`GlyphPaintIR.clipBounds`, when present, clips the complete root result. A
schema-2 or schema-3 graph without root bounds is accepted only when its
reachable root is structurally bounded: `SolidOutline`, `Path`, and
`GlyphClip` are bounded; `Solid` and the three gradients are unbounded;
`Transform` preserves its child's boundedness; and `Group` is bounded only
when every child is bounded. In schema 3, `PathClip` is bounded because its
portable path restricts the complete child paint, including an otherwise
unbounded gradient. Consequently, an empty schema-2 or schema-3 `Group` is
bounded. For composites, `CLEAR` is always bounded; `SOURCE` and `SOURCE_OUT`
follow the source; `DESTINATION` and `DESTINATION_OUT` follow the backdrop;
`SOURCE_IN` and `DESTINATION_IN` are bounded when either input is bounded;
every other composition mode requires both inputs to be bounded. A root clip
makes any otherwise accepted combination bounded.

`PaintGraphLimits` is enforced before certification. It bounds graph nodes,
references and depth; schema 2 and later also bound expanded paint visits.
It further bounds paths, gradients, color stops,
transforms, composites and glyph clips; source bytes; CPAL palettes, entries,
color records and decoded palette bytes; COLR base-glyph, layer and clip
records; and every referenced outline through `outlineProfile`. Each reached
`PathClip` consumes one entry from both `maxPaths` and `maxClips`, and its
portable path must satisfy `outlineProfile`; its path payload is also included
in conservative retained-size admission. Exceeding a bound returns
`FontError.ResourceLimitExceeded`. A reached but unadvertised
capability, or a CPAL palette selected by the consumer but unavailable in the
font, returns `FontError.UnsupportedRepresentationProfile`. Malformed COLR
references, cycles, geometry, or indexes return `FontError.InvalidFontData`.
Malformed or truncated CPAL structure returns `FontError.FontDataFailure` with
a stable code such as `font.cpal.truncated`, `font.cpal.invalid-table`, or
`font.cpal.invalid-palette-index`. None of these cases publishes a partial
graph or certificate.

Paint-route resolution and representation fallback are per final glyph. An
SVG-covered glyph keeps the existing SVG priority. Otherwise, a version-1
base-paint record is used when present;
when it is absent, a version-0 layer record is used when present; and only a
glyph absent from both color maps uses its `glyf` outline or an explicitly
inkless result. When a reached COLR v1
capability is unsupported or over limit, ordered representation profiles may
select a compatible result for that glyph. If none does, the editor's
configured font fallback proceeds according to its atomic fallback-unit
policy. The failure does not poison unrelated glyphs in the face.

In a mixed SVG/COLR v1 asset, complete SVG source/index bounds are checked
before acquisition, but payload capabilities and graph limits are checked only
when a covered SVG document is requested. SVG priority is preserved for its
covered glyphs; an unsupported SVG path cannot downgrade an uncovered COLR
glyph. The existing all-or-nothing validation of a selected SVG document is
unchanged, including documents targeting multiple glyphs.

### Migrating paint consumers

Graph-schema compatibility is not JVM binary compatibility. `GlyphPaintIR`
adds defaulted `clipBounds`, `PaintGraphProfile` adds defaulted
`acceptedGradientExtendModes`, and `PaintGraphLimits` adds six defaulted
fields. Ordinary Kotlin constructor calls keep working when recompiled, but
the previous JVM constructor descriptors, including default-argument
descriptors, are not retained. Recompile applications and libraries using
those constructors together with this version.

`PaintGraphLimits` is a data class: generated `copy` and `copy$default`
descriptors change with its fields. Existing `component1` through `component14`
retain their positions and `Int` return types; the six appended components
do not shift them. Recompiled named `copy` calls retain their existing field
names, while already compiled calls to the old generated signatures require
recompilation. This does not promise cross-version binary compatibility.

Update exhaustive Kotlin `when` handlers over `GlyphPaintNode`,
`GlyphPaintNodeKind` and `GlyphPaintCompositionMode` for the new cases. An
already compiled exhaustive handler can throw `NoWhenBranchMatchedException`
when supplied a new case. Do not persist enum ordinals: `SOURCE_OVER` moves
from ordinal 0 to 3. Prefer explicit versioned names/tags for stored formats.

Canonical profile fingerprints now include `gradientExtend=` and the six new
limits, even for unchanged schema-1 profiles. Regenerate/invalidate persisted
fingerprints and derived cache entries; do not assume equality across library
versions. Reopen/certify using fresh live provider keys rather than treating
persisted fingerprints as resource locators.

`Group(emptyList())` can now be constructed to express schema-2 no-paint.
The rejection for schema 1 moves from the `Group` constructor to
`GlyphPaintIR(schemaVersion = 1, ...)`, including nested empty groups.
Applications relying on the earlier validation point must validate at graph
construction instead; nonempty schema-1 routes remain unchanged.

```kotlin
val catalogResult = Kalligraphie.embedded(bytes, provenance)
val faceId = catalog.faces.single().id
val size = FontInstanceDescriptor(LayoutUnit(2048f))
val requirements = FontAccessRequirementsSnapshot.renderable(outlineProfile)
```

Renderable glyph access requires an explicit representation profile. Closing a
resolver or render asset is idempotent. New acquisitions after closure return
`font.resource-closed`; a detached asset owns the immutable data required for
`resolveGlyph(...)`.

### Bounded representation retention

`FontMaterializationCachePolicy` optionally retains complete immutable portable outline,
paint and bitmap successes. Retention is disabled by default. Pass the policy to
`Kalligraphie.embedded(...)` or `MacosSystemFontCatalogOptions`.

`FontCacheBudget` sets four independent non-negative limits: estimated retained bytes,
decoded bitmap pixels, native bytes and native allocations. Both `perFace` and `perCatalog`
must fit simultaneously. Bitmap pixels are width × height; outlines and paint charge zero
pixels. Portable entries charge zero native bytes and allocations. `Long.MAX_VALUE` leaves
a dimension practically unbounded.

Each catalog coordinates admission and least-recently-used (LRU) order atomically across
all its faces. Face pressure removes the oldest entry of that face; aggregate pressure
removes the oldest entry globally. Oversized results are returned without retention.
Route selection, representation identities, certificates and diagnostics remain unchanged.
Cancellation and operational failures are never retained.

```kotlin
val cachePolicy = FontMaterializationCachePolicy(
    perFace = FontCacheBudget(
        retainedBytes = 4L * 1024L * 1024L,
        decodedPixels = 1_000_000L,
        nativeBytes = 0L,
        nativeAllocations = 0L,
    ),
    perCatalog = FontCacheBudget(
        retainedBytes = 16L * 1024L * 1024L,
        decodedPixels = 4_000_000L,
        nativeBytes = 0L,
        nativeAllocations = 0L,
    ),
)
val catalogResult = Kalligraphie.embedded(bytes, provenance, cachePolicy)
```

The historical `FontMaterializationCachePolicy(maxEvictableBytesPerFace = 4L * 1024L * 1024L)`
constructor and `maxEvictableBytesPerFace` getter remain available. That constructor limits
only retained bytes per face; its other dimensions and catalog aggregate remain unbounded.
`FontMaterializationCachePolicy.disabled` retains no representation.

This is a source and binary breaking change for generated Kotlin operations despite preserving the
constructor and getter. Migrate `copy(maxEvictableBytesPerFace = …)` to `perFace`/`perCatalog`:
the first destructuring component changes from `Long` to `FontCacheBudget`, and JVM consumers that
used the old generated `copy`, `copy$default`, or `component1` operations must recompile.

```kotlin
val updatedPolicy = cachePolicy.copy(
    perFace = cachePolicy.perFace.copy(retainedBytes = 8L * 1024L * 1024L),
)
val (perFaceBudget, perCatalogBudget) = updatedPolicy
val retainedBytesPerFace = perFaceBudget.retainedBytes
```

These bounds cover evictable representations, keys and diagnostics, not source snapshots,
caller-owned assets or total process memory. No entry owns a resolver, asset, catalog or
native resource. Closing the last resolver or asset lease of a face releases that face's
entries; detached assets keep their ordinary independent lease. Other faces remain usable.

Catalogs do not share a provider-wide or engine-wide budget. Native resource participation
and that shared ownership scope will be introduced with a native route. Functional glyph
tests establish observable transparency; they do not measure retention or prove cache
admission. Accounting belongs to future opt-in instrumentation, outside `check`.

On macOS, the JVM artifact also exposes `MacosSystemFontCatalog.open()`. It
captures bounded, regular `.ttf` files into a portable snapshot and uses the
same routes as embedded fonts. It does not expose CoreText handles, nor claim
support for `.otf` or `.ttc` files.

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

The non-wrapped route rejects CR, LF, CRLF as one unit, vertical tab, form feed,
NEL, `U+2028 LINE SEPARATOR`, and `U+2029 PARAGRAPH SEPARATOR` before Unicode
analysis or shaping. The typed `EditableLineError.UnsupportedLineControl`
reports a `LineControlKind` and the exact snapshot-bound `TextRange` occupied by
the control. A `U+0009 CHARACTER TABULATION` is likewise rejected unless the
request supplies an explicit `ParagraphPositioningPolicy`.

With a positioning policy, TAB advances to the next explicit `TabStop`, or to
the next interval selected by `defaultTabInterval` when no explicit stop
applies. Shaping is split around each TAB: the scalar is never submitted as
U+0009, U+0020, or `.notdef`, and no font glyph is resolved or certified for
it. The result instead publishes a source-mapped `PositionedLineControl` with
the tab-stop geometry and, in renderable mode, the `EMPTY` materialization
route. The following content and every caret boundary retain the exact source
coverage. The BiDi
formatting controls LRE, RLE, PDF, LRI, RLI, FSI, and PDI remain accepted,
glyphless, and exactly source-mapped.

The JVM Unicode result is verified against every applicable Unicode 16.0 case
in `GraphemeBreakTest`, `BidiTest`, and `BidiCharacterTest`, and against the
complete `Script`, `Script_Extensions`, and `Bidi_Paired_Bracket` data. The
public request requires an explicit paragraph direction, so the official
auto-direction BiDi variants are outside this API contract. UAX #9 characters
removed by rule X9 are omitted only from the normative level and reordering
comparison; editable results retain their source positions.

For `RENDERABLE` output, replace `LayoutOnly` with
`EditableLineMaterialization.Renderable` and provide an open resolver, a
`FontRenderVariantSnapshot`, and `FontAccessRequirementsSnapshot` containing
one or more ordered representation profiles. The provider selects the first
profile it can certify. Every published final glyph then carries an exact
outline, paint-graph, bitmap, or inkless-route certificate tied to its
`FontRenderAssetKey`. The resolver remains caller-owned; the facade borrows it
only during the synchronous call.

### Own certified assets for delayed rendering

A successful renderable layout is resource-free, but that first success does
not promise that its certified font roots can be reopened later. While the
resolver is still open, call `openLayoutHandle(resolver)`. This is a second,
fallible and atomic success: it either returns a `LayoutHandle` owning every
certified root or publishes no handle. The same extension is available on
`EditableLine`, `ParagraphLayout`, and `FlowLayout`.

This ownership contract also covers schema-2 COLR version 1. The certificate
and immutable layout own no font asset. Each asset retained from the open
`LayoutHandle` owns the data needed to resolve its certified paint graphs after
the layout session, original resolver, attached asset, and layout handle have
all closed.

```kotlin
val layout = (renderableResult as EditableLineResult.Success).line
val handle = try {
    when (val opened = layout.openLayoutHandle(resolver)) {
        is FontOperationResult.Success -> opened.value
        is FontOperationResult.Failure -> error(opened.error.message)
        is FontOperationResult.Cancelled -> error("Asset ownership was cancelled.")
    }
} finally {
    session.close()
    resolver.close()
}

val certificatesByAsset = layout.positionedGlyphRuns
    .flatMap { run -> run.glyphs }
    .mapNotNull { glyph -> glyph.materializationCertificate }
    .groupBy { certificate -> certificate.assetKey }
val rendererAssets = mutableMapOf<FontRenderAssetKey, FontRenderAssetHandle>()
try {
    for ((key, certificates) in certificatesByAsset) {
        rendererAssets[key] = when (val retained = handle.retainFontAsset(certificates.first())) {
            is FontOperationResult.Success -> retained.value
            is FontOperationResult.Failure -> error(retained.error.message)
            is FontOperationResult.Cancelled -> error("Asset retention was cancelled.")
        }
    }

    val certificate = certificatesByAsset.values.first().first()
    val representation = rendererAssets.getValue(certificate.assetKey)
        .resolveGlyph(FontGlyphRequest(certificate.glyphId))
} finally {
    rendererAssets.values.forEach { it.close() }
    handle.close()
}
val geometryIsStillReadable = handle.layout
```

Group certificates by `assetKey` because one retained renderer asset serves
all certified glyphs for that exact font instance, variant, and representation
profile. Each successful `retainFontAsset(...)` returns an independently owned
asset: it remains valid after the layout handle closes and must be closed by
the renderer. Closing the handle prevents new retentions and releases its
roots, but never invalidates `handle.layout`; the immutable layout remains
readable.

Migration note: `FontError.CertificateNotInLayout` is a new member of the
sealed error surface used by this handoff route. Add a branch when recompiling
an exhaustive Kotlin `when` over `FontError`; method and JVM signatures remain
unchanged. An already compiled exhaustive handler can throw
`NoWhenBranchMatchedException` if new handoff code supplies this member, while
existing calls do not automatically begin returning it.

The handle owns external consumer memory, outside the internal cache budget.
Cancellation is checked between indivisible provider calls; it does not
interrupt a `reopen`, `detach`, or `close` already in progress. The initial
factory currently implements its atomic acquisition with reopen and detach,
but those operations are not part of the `LayoutHandle` abstraction. This API
does not make a layout session own these resources and introduces no GPU,
atlas, native-rendering, or rendering policy.

The embedded HarfBuzz 14.3.0 backend is the JVM reference implementation. Its
Linux and macOS x64/arm64 resources are pinned, hash-verified, and never found
through a system-library search. Public contracts contain no JNI or native
types. Android and Apple do not yet provide executable shaping adapters, so
this route must not be treated as conformant on those platforms.

## Deterministic multi-font fallback

`Kalligraphie.embedded(sources)` captures several audited OpenType sources in
one `FontCatalogGeneration`. `FontResolutionPolicySnapshot` binds a complete,
versioned candidate order and an explicit final last-resort face to that exact
generation. The JVM editable-line facade derives fallback units from Unicode
grapheme analysis, assigns every unit to one face, and shapes the affected
contiguous context.

In `LAYOUT_ONLY`, a candidate must map and shape the complete unit. In
`RENDERABLE`, it must additionally materialize every final shaped glyph with
one accepted representation profile. Failed candidates are blacklisted for the
operation and never silently retried for the same unit and profile. The
published `PositionedGlyphRun` records its actual `FontInstanceKey`; every
renderable glyph carries a certificate tied to its exact generation-bound
asset key. A resolver may reopen such a key only in the captured generation;
a detached asset remains independently usable after its originating resolver
closes.

Out of scope for the editable-line API: hyphenation,
justification, vertical writing, rendering pixels, GPU APIs, TTC/OTC,
CFF/CFF2, variations, and synthetic styles. See
[Editable Paragraphs](editable-paragraphs.md) for the JVM multiline paragraph
route.
