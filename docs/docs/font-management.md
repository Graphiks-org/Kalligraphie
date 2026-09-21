# Font Management

Kalligraphie exposes embedded and directory-captured TrueType paths through
`org.graphiks:kalligraphie` on the JVM reference target only. The public
contracts stay portable, but this executable route is JVM-only. A
consumer supplies captured SFNT bytes to `Kalligraphie.embedded(...)`,
selects a stable face record, creates a font instance, and uses a render asset handle to
materialize a portable glyph representation.

The optional [platform font access](platform-font-access.md) module adds an
explicit CoreText route on supported macOS JVMs without changing the portable
shaping or editing pipeline. Platform-resource lifetime and rendering remain under
consumer ownership.

The supported functional scope is intentionally narrow:

- JVM reference target only;
- static SFNT TrueType only: `0x00010000` and `true`;
- standalone embedded OpenType sources with face index `0`, and directory-captured
  TTC version 1 or 2 sources with their original collection face indices;
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
  `defs`/`linearGradient`/`radialGradient`/`stop` subset. Paths and rectangles
  accept `#RRGGBB` or `fill="none"`, with optional `fill-opacity`, and may
  reference a preceding local linear or concentric radial gradient in the
  coordinate spaces described below. They may also reference a bounded
  `userSpaceOnUse` `clipPath` with one path or sharp-cornered rectangle child.
  `translate`, `scale`, `rotate`, `skewX`, `skewY`, and
  six-coefficient affine `matrix` transforms are supported. The exact gradient
  subset and remaining exclusions are described below;
- bitmap schema version 2 strikes identified by their exact pixels-per-em
  (ppem) pair and bit depth, never by a neighbouring size: see the
  [bitmap format matrix](#bitmap-format-matrix);
- bitmap resource bounds reported per dimension through `BitmapResourceLimit`
  and `FontError.BitmapResourceLimitExceeded`, with declared dimensions checked
  before any pixel allocation and, for compressed images, before inflation, and
  no partial pixels published;
- detached render assets that keep resolving after the owning resolver or
  attached handle is closed.

## Bitmap format matrix

Bitmap schema version 2 strikes are identified by their exact pixels-per-em
(ppem) pair and bit depth, never by a neighbouring size, through three routes:

| Route / tables | Accepted versions and records | Selection | Decoded pixels | Exclusions |
| --- | --- | --- | --- | --- |
| `EBLC` / `EBDT` | version 2.0; index subtable format 1; image format 1 | exact `(ppemX, ppemY, 1)` | byte-aligned one-bit `ALPHA_8` monochrome in sRGB | index formats other than 1; image formats other than 1 |
| `CBLC` / `CBDT` | versions 2.0 or 3.0, each table checked independently (a mixed pair is accepted deliberately); index subtable format 1; image formats 17 or 18; horizontal metrics | exact `(ppemX, ppemY, 32)` | bounded PNG subset → straight non-premultiplied `RGBA_8888` in sRGB; metrics must equal the embedded image dimensions | uncompressed 32-bit BGRA; image format 19; vertical strikes |
| `sbix` | version 1 only (`flags` bit 0 set, reserved bits zero); `'png '` graphics only; `'dupe'` records resolve to the referenced glyph's image within the same strike (each dupe record keeps its own origin) | exact `ppem` (the `resolution` (`ppi`) field is ignored for selection) | bounded PNG subset → straight non-premultiplied `RGBA_8888` in sRGB; origins and advances normalized from design units to strike pixels with round-half-away-from-zero; advances come from `hhea`/`hmtx` (no `glyf` requirement) | `'jpg '`; `'tiff'`; `'pdf '`; `'mask'` |

Decoded pixels are straight (non-premultiplied) RGBA in sRGB, with bytes
ordered R, G, B, A, rows from top to bottom, no padding, and exactly
`width × height × 4` bytes. `ALPHA_8` is one-byte-per-pixel alpha. The
decoder performs no premultiplication.

PNG decoding accepts 8-bit truecolor (colour type 2) and 8-bit truecolor with
alpha (type 6), non-interlaced, compression and filter method zero, with every
chunk CRC verified. A suggested `PLTE` chunk is tolerated and ancillary chunks
are ignored; palette, greyscale, 16-bit and interlaced images are refused.
Declared dimensions are validated against the profile before any inflation, and
the inflate stream is capped at the exact declared scanline total, so a
decompression bomb is refused before pixels are allocated. PNG is never exposed
to the consumer.

Colour route priority is deterministic and face-wide: when one face certifies
both colour routes, CBDT/CBLC is chosen deterministically and its failure is
final. There is no cross-route fallback, because a different route is different
artwork, not a different size.

Bitmap capability discovery is conservative and face-wide: a face advertises a
route only when every declared strike of that route is structurally valid, so
one malformed unselected strike withdraws the route. Capability scan budgets
for compressed and decoded bytes accumulate across all declared strikes, while
materialization applies per-strike budgets. Duplicate declarations never provide
an implicit tie-break: the capability predicate withdraws a route when any
declared strike repeats, and reading a requested strike that is declared twice
fails as invalid data with `font.eblc.duplicate-strike`,
`font.cblc.duplicate-strike`, or `font.sbix.duplicate-strike`.

A glyph with no record in the selected strike fails with
`font.glyph-representation-unavailable`. A validated record whose decoded
pixels are all zero is a legitimate empty result (`GlyphRepresentation.Empty`),
never an error; absence of a record is never treated as "no ink".

No other bitmap table or record format is recognized.

## Example: an exact bitmap strike

Given an application-supplied `BitmapLimits` value, a consumer declares one
exact monochrome strike and obtains renderable requirements with
`FontAccessRequirementsSnapshot.renderable(listOf(bitmapProfile))`:

```kotlin
val bitmapProfile = BitmapProfile(
    strike = BitmapStrike(pixelsPerEmX = 16, pixelsPerEmY = 16, bitDepth = 1),
    acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
    acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
    limits = bitmapLimits,
)
```

Only the exact declared strike is certified: a face without that strike is
rejected with `font.unsupported-representation-profile`, and a glyph with no
record in the selected strike is rejected with
`font.glyph-representation-unavailable`; no neighbouring size is ever
substituted. A breach of any declared bound fails with
`font.bitmap-resource-limit-exceeded`, and that failure is terminal: the
calling resolution stops.

A colour strike uses the same profile shape with
`BitmapStrike(pixelsPerEmX = 16, pixelsPerEmY = 16, bitDepth = 32)` and
`BitmapPixelFormat.RGBA_8888`; a 32-bit strike is certified by the CBDT/CBLC
or sbix route, selected by the [documented priority](#bitmap-format-matrix), still with no
neighbouring-size substitution.

## Capture font directories on the JVM

`FontDirectoryCatalog.open(options, cancellationToken)` captures readable font
files from explicit roots. Linux and macOS convenience providers apply the same
capture rules with separate provider domains:

```kotlin
import org.graphiks.kalligraphie.FontDirectoryCatalog
import org.graphiks.kalligraphie.FontDirectoryCatalogOptions
import org.graphiks.kalligraphie.LinuxSystemFontCatalog
import org.graphiks.kalligraphie.MacosSystemFontCatalog
import org.graphiks.kalligraphie.MacosSystemFontCatalogOptions
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult

fun requireCapture(result: FontOperationResult<FontCatalogSnapshot>): FontCatalogSnapshot =
    when (result) {
        is FontOperationResult.Success -> {
            result.diagnostics.forEach { println(it) }
            result.value
        }
        is FontOperationResult.Failure -> {
            result.diagnostics.forEach { println(it) }
            error("Font capture failed: ${result.error}")
        }
        is FontOperationResult.Cancelled -> {
            result.diagnostics.forEach { println(it) }
            error("Font capture cancelled")
        }
    }

val options = FontDirectoryCatalogOptions(
    roots = listOf("/usr/share/fonts"),
    maxPathsToVisit = 512,
    maxFaces = 32,
    maxFacesToExamine = 128,
    maxSourceBytes = 16 * 1024 * 1024,
    maxTotalSourceBytes = 64 * 1024 * 1024,
    maxDiagnostics = 64,
)
val explicit = requireCapture(FontDirectoryCatalog.open(options))
// On Linux; omitting options uses system, legacy user and XDG font roots.
val linux = requireCapture(LinuxSystemFontCatalog.open(options = options))
// Alternatively on macOS; omitting options uses standard system and user roots.
val macos = requireCapture(MacosSystemFontCatalog.open(
    options = MacosSystemFontCatalogOptions(roots = listOf("/Library/Fonts")),
))
```

The OS-specific calls are alternatives: each returns a typed unsupported error
on another OS. Every `open` also accepts a `CancellationToken`; cancellation is
cooperative between filesystem operations and cannot interrupt a blocked OS
call. A cancelled capture publishes no partial snapshot. Invalid option values
(such as non-positive limits or repeated roots) are rejected at construction.

Discovery considers `.ttf`, `.otf`, `.ttc` and `.otc` candidates without following
symbolic links. An `.otf` extension does not imply CFF outlines: actual SFNT
content determines support. Static TrueType content is supported; CFF/CFF2 and
variable font data are outside this route. Captured candidates are ordered
lexically, with original index order inside each collection. Directory discovery
is bounded and can omit candidates; it is not an exact inventory of activated
Fontconfig or CoreText fonts. Filesystem capture is not globally atomic.

Inspect diagnostics even on `Success`: unreadable or absent roots, rejected
sources/faces and reached limits can leave a usable partial inventory. With no
accepted face the operation returns `Failure`. `maxDiagnostics` bounds returned
diagnostics; `font.capture.diagnostics-truncated` is included within that bound
when details are omitted. `maxPathsToVisit` charges inspected paths, including roots;
`maxFacesToExamine` charges attempted directories, including rejected faces;
`maxFaces` limits accepted faces after source examination. These limits have different meanings.

Every retained collection must fit its complete face count within the remaining
`maxFacesToExamine` budget. A larger TTC/OTC source is rejected whole with
`ResourceLimitExceeded` and a diagnostic before any of its face directories is
examined; no partially examined collection prefix is admitted. Thus a valid
two-face collection requires at least two remaining examination slots, even if
`maxFaces` is one. A count-based refusal does not consume face-examination slots,
so separate sources that fit can still form a usable partial catalog.

Collection headers and all their face-directory ranges must be safely addressable.
An unsafe attempted directory rejects its whole original container, with numeric
location diagnostics, and the attempted face is charged to the examination budget.
Safely addressed unsupported or metadata-invalid siblings may be excluded
individually; retained faces keep their original indices. After complete source
examination, the separate `maxFaces` cap may omit accepted siblings. These checks
prevent admission of an original container with unexamined directories; they do
not claim equivalence with HarfBuzz's sanitizer for arbitrary unsupported tables.

`FontFaceId.source` identifies the captured original container, and `faceIndex`
selects its face. `copyOpenTypeData()` returns original container bytes and the
selected face identity; it neither extracts a standalone font nor renumbers or
rewrites collection bytes. Siblings share the retained source.
`maxSourceBytes` bounds each read container; `maxTotalSourceBytes` counts unique
accepted containers once. Neither is a process-memory ceiling: defensive copies,
temporary reads, metadata and decoder memory are excluded. Use
`estimateOpenTypeDataCopy()` to preflight controlled copy allocations. Portable
representation retention is separately governed by `materializationCachePolicy`
and optional `cacheScope`; closing a shared scope still permits uncached work.

Refresh explicitly by calling `open` again after installation or removal:

```kotlin
val refreshed = requireCapture(FontDirectoryCatalog.open(options))
// Use refreshed.generation and a newly opened resolver for new layouts.
```

Every successful capture has a new generation, even for identical files. Keep layout,
resolver and asset keys in that generation; reopening an old asset key through
a new generation fails. Existing captured instances and independently owned
render assets keep their original data after file replacement or deletion.
Close each acquired resolver, layout owner and render asset when finished;
closing an originating owner does not invalidate admitted independent children.
The snapshot itself has no `close` operation.

Existing Kotlin calls to `MacosSystemFontCatalogOptions` and
`MacosSystemFontCatalog.open` remain source-compatible through trailing defaults.
Their JVM signatures changed: recompile consumers using the old constructors or
factory entry points; already compiled callers are not binary-compatible.

### SVG document transport and bounds

The fully normalized SVG route decodes all document bytes while a render
asset is acquired, then retains immutable portable IR (intermediate
representation) instead of encoded SVG payloads. The transport may be raw
UTF-8 or a single gzip member containing UTF-8; gzip decoding is streamed
while its bounds are enforced. A mixed SVG/COLR v1 asset instead privately
retains bounded encoded SVG source/index data and normalizes the entire
selected document when a covered glyph is requested. This preserves unrelated
uncovered COLR glyphs even if that SVG payload cannot be decoded or normalized.
Successful public `resolveGlyph(...)` results expose only immutable portable
paint or empty glyph data, never encoded SVG, XML, gzip, a URI, or a renderer
resource. Detached assets own the immutable data needed by their route.

`PaintGraphLimits` applies the following bounds:

- `maxSourceBytes` bounds the complete SVG table and the cumulative encoded
  document bytes;
- `maxSvgCompressedDocumentBytes` bounds the encoded bytes of each gzip
  document;
- `maxSvgDecodedDocumentBytes` bounds the decoded UTF-8 bytes of each raw or
  gzip document; and
- `maxSvgTotalDecodedBytes` bounds decoded UTF-8 bytes accumulated in one
  normalization operation: all document records in the fully normalized route,
  or the selected whole document in the mixed route.

Each dedicated gzip/decoded limit defaults to `maxSourceBytes` (whose default
is 1,048,576 bytes). Exceeding any of these bounds returns
`FontError.ResourceLimitExceeded` at the `SVG ` table. Complete encoded
source/index bounds apply at acquisition in both routes. Decoding, integrity,
UTF-8, markup and graph checks reject acquisition in the fully normalized
route, or reject the covered glyph request in the mixed route; no partial SVG
result is published and unrelated uncovered COLR certification remains valid.
A malformed gzip header, a non-DEFLATE member,
reserved gzip header flags, failed gzip integrity checks, concatenated gzip
members, or trailing bytes after a member returns
`FontError.FontDataFailure` with code `font.svg.invalid-gzip` at that route's
normalization boundary. Invalid UTF-8 or malformed SVG follows the existing typed
`FontDataFailure` contract, and SVG markup outside the safe subset returns
`FontError.UnsupportedRepresentationProfile`.

### Safe static SVG gradients

The accepted paint-server subset consists of `defs` containing named
`linearGradient` or concentric `radialGradient` definitions, which may contain
self-closing `stop` elements.
A self-closing `rect` may use an opaque `#RRGGBB` fill, `fill="none"`, or a
`url(#id)` reference to a unique gradient defined earlier in the same
document; an absent fill defaults to opaque black. A supported self-closing
`path` may use the same fills when the referenced gradient explicitly uses
`userSpaceOnUse`. Path references to `objectBoundingBox` gradients remain
unsupported, including empty, one-stop, degenerate, and singularly transformed
uses. With absent or explicit
`gradientUnits=objectBoundingBox`, unitless values and percentages are resolved
relative to the rectangle and are not clamped to its unit box. Linear defaults
are `x1=0%`, `y1=0%`, `x2=100%`, and `y2=0%`. Radial defaults are `cx=50%`,
`cy=50%`, `r=50%`, `fx=cx`, and `fy=cy`.

`gradientUnits=userSpaceOnUse` is accepted only for viewport-independent,
finite unitless coordinates. Linear definitions must explicitly provide all of
`x1`, `y1`, `x2`, and `y2`. Radial definitions must explicitly provide `cx`,
`cy`, and non-negative `r`; omitted `fx` and `fy` inherit the accepted absolute
`cx` and `cy`, while explicit focus coordinates must also be unitless.
Percentage coordinates and the percentage defaults selected by omitted required
attributes remain outside this bounded subset because they depend on the
current viewport and any `viewBox`. They yield
`UnsupportedRepresentationProfile`, as do unknown `gradientUnits` values;
malformed or non-finite absolute numbers and negative non-zero radial radii
yield `FontDataFailure`. Both gradient kinds default to `spreadMethod=pad` and
sRGB interpolation.

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

Group `transform` attributes, both gradient kinds, and bounded user-space clip
definitions use the same supported SVG transform operations. A gradient may
declare an absent, empty, or whitespace-only `gradientTransform` as the
identity, or a transform list
containing `translate`, `scale`, `rotate(angle)`, `rotate(angle cx cy)`,
`skewX(angle)`, `skewY(angle)`, and `matrix`. These are all six SVG 1.1
transform function names accepted by this bounded list. A matrix has exactly six
coefficients `matrix(a b c d e f)` and maps points as
`x' = a*x + c*y + e`, `y' = b*x + d*y + f`. Functions are separated by one
or more SVG comma-whitespace productions, so repeated commas are accepted
between functions but remain invalid between operands. `translate` and
`scale` accept one or two operands, `rotate` requires exactly one or three,
each skew requires exactly one, and `matrix` requires exactly six.
`rotate(angle)` rotates around the origin;
`rotate(angle cx cy)` rotates around the authored center `(cx, cy)`, equivalently
`translate(cx cy) rotate(angle) translate(-cx -cy)`, while remaining one
authored operation. `skewX(angle)` keeps `y` fixed and maps
`x' = x + tan(angle)*y` (the affine `c` coefficient); `skewY(angle)` keeps `x`
fixed and maps `y' = y + tan(angle)*x` (the affine `b` coefficient).
Angles are SVG degrees. Finite negative and wrapped angles are accepted.
Rotation canonicalizes exact multiples of 90 degrees to stable quadrant
matrices. Skew reduces finite angles by the 180-degree tangent period before
radian conversion, then canonicalizes exact zero and angles equivalent to
positive or negative 45 degrees to coefficients `0`, `1`, or `-1`, without
trigonometric residue. Every rotation or skew factor has positive determinant
orientation.
Unlike ordinary rectangle and gradient coordinates, transform operands accept
forms such as `1.` and `1.e2`. Finite non-zero negative scales and
negative-determinant matrices reflect the paint. A singular gradient transform
remains unsupported. Malformed syntax, including malformed rotation or skew
arity, separators, units, unknown function names, partial lists, non-finite
values, and composition outside the portable numeric domain are invalid data
and prevent successful normalization of the affected SVG data. Exact odd quarter turns
(`90 + 180*k` degrees) are skew asymptotes and therefore invalid. A scale
factor, matrix coefficient, rotation angle, or skew angle written as non-zero
but converted to zero is invalid, as is a non-finite tangent; center
coordinates use the existing translation-coordinate behavior. A composition
of area-preserving factors whose
stored product loses area or reverses the determinant orientation implied by
the factors is also invalid, including across nested groups. An explicitly
written singular group transform remains valid, while an explicitly singular
gradient transform is unsupported.

Determinant orientation is determined exactly for the decoded `Double`
coefficients by comparing `a*d` and `b*c`, without an epsilon, and propagated
as positive, negative, or singular through composition. A known singular
factor keeps the complete list or nested-group product singular even if
rounding its stored coefficients would otherwise appear to restore area.

Every complete transform function call consumes one authored SVG transform
operation, regardless of its operand count; in particular, each one-operand
`skewX` or `skewY` call costs exactly one. Group, gradient, and clip-definition
lists share this budget, and profile fallback restarts validation without
publishing partial data.

With column vectors, object-bounding-box paint uses `T * B * G`: `T` is the
rectangle's effective group transform, `B` maps its normalized object bounding
box, and `G` is the `gradientTransform` list composed in source order. Absolute
user-space paint instead uses `T * G`, independently of rectangle bounds. The
mapping is applied only to gradient geometry; the rectangle's clipping path
remains under `T`. Linear gradients bake the selected mapping into `p0`, `p1`,
and `p2` without adding a graph transform. Radial gradients retain either the
normalized or absolute authored circles and place the selected mapping on their
existing single `Transform` node.

A definition's coordinate space and coordinates are fully validated before an
empty or solid reduction, so viewport-dependent input cannot be hidden by a
definition with no stops, one stop, or zero radius. A valid definition with no
stops contributes no ink. For a rectangle or supported path whose effective
group transform `T` preserves area, a one-stop linear gradient or a source
vector with
identical endpoints normalizes to the final stop as `Solid` under the
shape's `PathClip`. For any other linear gradient with at least two stops,
Kalligraphie first resolves its normalized `p0` and `p1`; if those points
coincide, it performs the same solid reduction, otherwise it emits a
`LinearGradient` under that shape path. Before emission, normalized `p0`,
`p1`, and `p2` must form a non-collinear triplet. A collinear triplet returns
`font.svg.invalid-gradient`, and no partial asset is published. A singular
effective group transform `T` omits a rectangle or filled path after its
geometry and fill have been validated. For path gradients, local-reference,
coordinate-space, reached capability, and projected graph-limit validation
also precede that omission.

A radial definition with `r < 0` is invalid data. With one stop or `r == 0`,
it likewise reduces to the final stop as `Solid` under `PathClip`. A radial
definition with `r > 0` is accepted only when its focus is exactly concentric
after numeric parsing (`fx == cx` and `fy == cy`); any off-center focus is
valid SVG outside this subset and yields `UnsupportedRepresentationProfile`
without clamping. A non-degenerate radial paint keeps its two normalized
circles (`c0=(fx,fy), radius0=0`, `c1=(cx,cy), radius1=r`) below an explicit
`Transform` that carries `T * B * G`; an absolute user-space radial keeps the
same circle fields unscaled below `T * G`. Both are clipped by the referencing
shape path transformed by `T` alone. The object-bounding-box form is available
only to rectangles and preserves the ellipse produced by a non-square
rectangle.

Every authored linear or radial gradient requires an exact schema-3 `PaintGraphProfile`.
When normalization actually emits `LinearGradient` or `RadialGradient`, the profile must accept
the reached interpolation space, `UNPREMULTIPLIED` alpha interpolation, and
extend mode together with `PATH_CLIP` and the corresponding `LINEAR_GRADIENT`
or `RADIAL_GRADIENT` node kind. Radial paint also requires `TRANSFORM`. A solid reduction instead requires `SOLID`
and `PATH_CLIP`, but does not require the definition's interpolation space or
alpha or extend mode. Opaque solid rectangles and paths use `PATH`; translucent
solids use the `SOLID`/`PATH_CLIP` form described below. Documents with several
painted roots also require `GROUP` and `SOURCE_OVER`. Existing limits
are checked before publication: source and decoded bytes, transforms, parsed
gradient definitions and stops, and generated nodes, references, paths,
clips, gradients, color stops, and depth must all fit. Paint visits are also
bounded for schema 2 and later; schema 1 retains its historical node and depth
checks without applying `maxPaintVisits`. A generated radial transform counts
against `maxTransforms`, independently of authored group or gradient
transform function calls. Every complete authored operation counts once against
the shared `maxSvgTransformOperations` source budget for one normalization
operation: the complete table during fully normalized SVG acquisition, or the
selected whole document during a lazy mixed SVG/COLR glyph request. It is
charged when its definition is parsed, including identity operations and unused
definitions. A three-operand `rotate(angle cx cy)` call still counts once;
referencing one definition repeatedly does not charge it again.
Each generated shape path must also satisfy the profile's `outlineProfile`.
Ordered profile fallback may therefore skip a schema-3 profile that does not
declare every reached capability and select a later compatible profile.

### SVG fill opacity

Supported painted `path` and `rect` elements may declare `fill-opacity` as a
finite unitless number or percentage. It defaults to `1` and is clamped to
`0.0..1.0`; malformed or non-finite input returns `FontDataFailure` with code
`font.svg.invalid-fill-opacity`, even with `fill="none"`.

An opaque solid retains its existing `Path` node. A translucent solid instead
normalizes to `Solid(color, opacity)` below the shape's `PathClip`, requiring
`SOLID` and `PATH_CLIP` rather than `PATH`. Any external clip wraps that complete
subtree. For a gradient, each reference derives an immutable color line whose
stop opacities are multiplied by the shape opacity, including the terminal stop
of a solid reduction; the shared definition and subsequent uses remain
unchanged. Existing interpolation modes and gradient capabilities still apply.

Zero opacity omits the normalized paint only after reached shape geometry,
paint/reference validity, required node kinds, and projected resource limits
validate. It does not bypass an unsupported non-empty gradient or an invalid
shape. Valid empty gradient definitions, `fill="none"`, and zero-area painted
rectangles retain their existing distinct no-ink behavior. Clip children cannot
declare `fill-opacity`, `fill`, or other paint attributes. Element/group
`opacity` and CSS opacity remain unsupported.

### Bounded user-space SVG clipping

The clipping subset accepts a non-self-closing `clipPath` directly inside
`defs`. It must have a valid, globally unique `id`, and `clipPathUnits` must be
absent or exactly `userSpaceOnUse`. Its only child is exactly one self-closing
`path` with required `d`, using the supported static path commands and non-zero
fill rule, or one sharp-cornered `rect`. The rectangle accepts optional `x/y`
(default `0`) and required finite, unitless, non-negative `width/height`;
negative non-zero dimensions remain invalid even when numeric underflow would
decode them as zero. Rounded corners and other rectangle attributes are
unsupported. A supported painted `path` or `rect` may add one
`clip-path="url(#id)"` attribute that refers to a preceding local `clipPath`.
The definition and its child may each declare an absent, empty, or
whitespace-only `transform` as the identity, or a transform list using the same
bounded grammar described above. They cannot contain fills, styles,
`clip-rule`, IDs on the child, groups, other shapes, nested clips, references,
animation, or any unlisted element or attribute.

For each reached use, the shape's effective transform `T` materializes the
painted shape while the definition's path uses `T * C * P`, where `C` is the
definition transform and `P` is the child transform, both composed in authored
order. Authored clip coordinates must remain finite and satisfy the exact
`outlineProfile` limits even for unused definitions, but the integer
design-coordinate bounds are enforced only after this complete transform is
materialized for a reached reference. The existing paint subtree is
preserved exactly in topology and values, then wrapped in one outer `PathClip`.
The shape therefore remains under `T`; gradient geometry remains under its
existing `T * G` or `T * B * G` mapping. A gradient retains its shape
`PathClip`, and a radial gradient also retains its optional `Transform` below
that shape clip. The outer clip represents intersection through nesting,
without path unions or bounding-box calculations. Reusing one definition under
different transforms materializes and accounts for a distinct clip at every
reference without reparsing or recapturing `T`.

Clip definitions require exact paint schema 3, including unused definitions;
every reached clip also requires `PATH_CLIP`. Each use adds one generated node,
reference, path, clip, and depth level,
and is charged independently against `maxNodes`, `maxReferences`, `maxPaths`,
`maxClips`, `maxDepth`, and `maxPaintVisits`; its path and the painted path
must both satisfy the exact `outlineProfile`. Exceeding configured point,
contour, or byte limits returns `ResourceLimitExceeded` at the `SVG ` table,
not an unsupported-capability error. Authored definition and child operations
consume the shared `maxSvgTransformOperations` source budget once at parsing,
including unused definitions. The budget covers the complete table during fully
normalized acquisition or the selected whole document during lazy mixed
SVG/COLR materialization; reuse consumes no further source operations.
Clip materialization emits no `Transform` node and does not
charge `maxTransforms`, so existing child-paint gradient, stop, and transform
budgets remain unchanged. A singular effective `T * C * P`, or a zero-area
clip rectangle, omits paint only
after the shape, definition, reference, capabilities, outline limits, and
projected graph limits validate. `fill="none"` and zero-area rectangles retain
their no-ink result after source attributes and any local clip reference
validate.

`objectBoundingBox`, unknown units, empty or multiple-child definitions,
forbidden content, and malformed, external, forward, unresolved, or non-clip
references remain unsupported even when unused or when the shape would emit no
ink. Malformed transform syntax or composition, malformed path data, invalid
XML, and duplicate IDs remain typed `FontDataFailure`. Every failure is atomic
across the complete public acquisition, and ordered profile fallback may select
a later exact compatible profile.

All element IDs accepted on `svg`, `g`, `linearGradient`, `radialGradient`, and
`clipPath` are globally unique. Glyph targets remain unique by glyph ID as a
separate invariant. Paint and clip references are local, fragment-only, and
backward-only; unresolved, forward, external, or otherwise URI-bearing
references fail before an asset is published, even when the referencing shape
would later contribute no ink. Malformed or unsupported input never publishes
a partial graph.

The subset does not support `viewBox`, viewport-dependent user-space percentage
coordinates or defaults, `href`, `xlink:href`, radial `fr`, non-concentric radial focus,
`objectBoundingBox` gradient fills on `path`, CSS or `style` attributes, SVG
clipping beyond the exact single-child `userSpaceOnUse` subset above, masks,
strokes, scripts, entities, animation, external resources, or unlisted
elements and attributes. Compression formats other than the permitted
single-member gzip transport remain rejected.

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
SVG-in-OpenType route supports the static linear and concentric radial
gradients plus the bounded single-child user-space clips described above through
schema 3; it does not gain general SVG clips, masks, strokes, or animation.

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

#### Schema-3 additions

Graph-schema compatibility does not preserve JVM binary compatibility. The
`GlyphPaintColorLine` constructor now appends defaulted `interpolationSpace`
and `alphaInterpolationMode`: the defaults preserve historical
`LINEAR_SRGB` and `PREMULTIPLIED` COLR semantics. The `PaintGraphProfile`
constructor appends `acceptedGradientInterpolationSpaces` and
`acceptedGradientAlphaInterpolationModes`. Their defaults are empty for
schema 1 and accept only `LINEAR_SRGB` / `PREMULTIPLIED` for schema 2 and later;
selecting schema 3 alone does not admit sRGB or unpremultiplied SVG gradients.
Declare those capabilities explicitly, together with the needed node kinds.

`PaintGraphLimits` appends three defaulted transport bounds:
`maxSvgCompressedDocumentBytes`, `maxSvgDecodedDocumentBytes` and
`maxSvgTotalDecodedBytes`, each defaulting to `maxSourceBytes`. Its constructor,
default-argument constructor, generated `copy` and `copy$default` descriptors
change. `component1` through `component20` keep their existing positions and
`Int` return types; the new bounds occupy `component21` through `component23`.
Ordinary recompiled Kotlin constructor and named `copy` calls keep their
existing arguments, but the previous JVM constructor/generated descriptors
are not retained. Recompile every application and library using these changed
signatures, including callers using only the former two-argument
`GlyphPaintColorLine` constructor; old binaries can raise `NoSuchMethodError`.

Handle `GlyphPaintNode.PathClip`, `GlyphPaintNodeKind.PATH_CLIP`, and the new
`GlyphPaintInterpolationSpace` / `GlyphPaintAlphaInterpolationMode` enums in
consumer dispatch. Update exhaustive handlers; previously compiled sealed
handlers can raise `NoWhenBranchMatchedException`. `PATH_CLIP` is inserted
before `TRANSFORM`, shifting subsequent node-kind ordinals: never persist enum
ordinals, and use explicit versioned names/tags instead.

Canonical profile fingerprints add `gradientInterpolation=`,
`gradientAlphaInterpolation=` and the three transport bounds in `limits=`.
Even otherwise unchanged schema-1 or schema-2 profiles get different
fingerprints. Regenerate/invalidate persisted fingerprints and derived cache
entries, then reopen/certify with fresh live provider keys; stored fingerprints
are not resource locators.

#### Bitmap schema-2 additions

Bitmap profile fingerprints now include the exact strike bit depth. Consumers
that persist `GlyphRepresentationProfileKey` values must regenerate them once;
a stale fingerprint only causes a cache miss and never changes a materialized
glyph.

#### Earlier schema-2 changes

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
caller-owned assets or total process memory. Portable payloads retain immutable representation
data without owning a resolver, asset, catalog or platform resource. Closing the last resolver or asset lease of a face releases that face's
entries; detached assets keep their ordinary independent lease. Other faces remain usable.

### Sharing retention across captures

Create one caller-owned `FontCacheScope` and pass it to every participating capture:

```kotlin
val scope = Kalligraphie.fontCacheScope(
    FontCacheBudget(32L * 1024 * 1024, 4_000_000, 16L * 1024 * 1024, 64),
)
val first = Kalligraphie.embedded(firstBytes, firstProvenance, cachePolicy, scope)
val second = Kalligraphie.embedded(secondBytes, secondProvenance, cachePolicy, scope)
// With system fonts: MacosSystemFontCatalogOptions(materializationCachePolicy = cachePolicy, cacheScope = scope).
```

The scope adds an aggregate bound; each capture's `perCatalog` and `perFace` bounds
still apply in every dimension. A scope does not activate a disabled local policy.
Without an explicit scope, an enabled policy has a private capture budget. There is
no process-global cache or implicit sharing between equal font sources. Custom
providers retaining their own caches outside these factories do not participate.
To include CoreText contexts, pass the same scope to both the portable capture and
`CoreTextFontCatalog.capture(..., cachePolicy = nativePolicy, cacheScope = scope)`;
adapting a portable catalog does not reconfigure its policy or scope. See
[platform font access](platform-font-access.md#shared-context-retention).

Every bound includes active entries, pending reservations, retiring references and
residual charges from uncertain cleanup. Removing an indexed entry does not create
capacity until its cache reference is relinquished. The managed estimate covers keys,
diagnostics, immutable representation data and a conservative metadata envelope
(currently 4096 bytes per portable entry, plus variable key/result data). Captured
source snapshots, caller-only assets, temporary buffers, private OS memory and GC
timing are excluded. A pending or retiring cache reference is never excluded as
temporary or OS memory. Portable entries charge zero native bytes and units; the
current CoreText context charges source length N and four explicit native resource
units, independently of unknown framework memory.

`scope.close()` disables retention and drains cache references without closing
catalogs or consumer owners. Existing acquisitions, detached owners and new captures
using that closed scope remain usable uncached; no private replacement cache is
created. A first or repeated close reports only known cleanup faults and never
retries a partial release. Concurrent cleanup can finish after close returns, so
its success does not prove complete drainage. Drain outside the rendering critical
path: explicit close may release every retained entry. Consumer-only native memory
can remain alive after successful cache drainage and belongs to its independent
owners until they close.

Trailing scope parameters preserve ordinary recompiled Kotlin calls, but changed
JVM method/constructor signatures require recompilation. The assembly declarations
are internal opt-in contracts, not a supported custom-cache SPI. Functional glyph
tests establish observable transparency; the separate
[opt-in retention measurement](glyph-materialization-measurement.md#shared-retention-and-native-ownership)
records accounting and structural costs outside `check`.

On macOS, the JVM artifact also exposes `MacosSystemFontCatalog.open()`. It
captures bounded, regular `.ttf` files into a portable snapshot and uses the
same routes as embedded fonts. It does not expose CoreText handles, nor claim
support for `.otf` or `.ttc` files.

## Variable font selection

A variable face exposes its `fvar` axes and named instances through
`FontFace.variationAxes()` and `FontFace.namedInstances()`, and maps a design
selection to normalized coordinates through `FontFace.normalize(design)`. Both
metadata calls return immutable snapshots and are empty for a static face.
`FontFace.stat()` optionally returns a read-only `STAT` surface, reporting
`Success(null)` when the face has no usable `STAT` table.

Pass design coordinates to an instance with the optional
`FontInstanceDescriptor.variation` field:

```kotlin
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.LayoutUnit

val descriptor = FontInstanceDescriptor(
    layoutSize = LayoutUnit(72f),
    variation = FontVariationCoordinates(
        listOf(FontVariationCoordinate("wght", 700f)),
    ),
)
val variable = when (val result = face.instantiate(descriptor)) {
    is FontOperationResult.Success -> result.value
    is FontOperationResult.Failure -> error(result.error.message)
    is FontOperationResult.Cancelled -> error("Instantiation was cancelled.")
}
```

`instantiate` normalizes the design selection through the face's `fvar` and
`avar` version 1 tables, then rebuilds the normalized axes into
`FontInstanceKey.geometry`. Those axes participate in
`FontGeometryParameters.normalizedAxes`, so the selection is part of the
instance identity: two descriptors that differ only in axis values yield keys
that compare unequal. Call `normalize(design)` directly to preflight a
selection; it returns tag-sorted, tag-unique coordinates.

A value outside an axis's declared bounds is clamped to the nearest bound and
reported with an informational `font.variation.axis-clamped` diagnostic on the
successful result. An axis tag the face does not declare fails with
`font.variation.unknown-axis`. Combining a design selection with a non-empty
`FontGeometryParameters.normalizedAxes` fails with
`font.variation.ambiguous-request`, because the two describe the same selection
at different levels. Malformed variation tables fail with the typed codes
`font.variation.invalid-fvar`, `font.variation.unsupported-fvar-version`,
`font.variation.invalid-avar` and `font.variation.unsupported-avar-version`
(`avar` version 2 is not supported), and a face without a usable `fvar` table
fails with `font.variation.not-variable`.

A non-default selection now contributes both instance identity and glyph
outline variation. The portable TrueType outline route reads the face's `gvar`
table, evaluates each tuple's region scalar at the instance's normalized axes,
interpolates untouched simple-glyph points through TrueType IUP, applies the
resolved per-point deltas to simple-glyph coordinates and their recomputed
bounds, and applies composite glyph deltas to the component placement offsets
of composite glyphs. Component deltas apply only when the component selects
`ARGS_ARE_XY_VALUES`, are added to the raw offset before the optional
scaled-component-offset transform, and are ignored for point-matched
components, matching the OpenType `gvar` composite rules. The four
phantom-point deltas that follow the outline or component points are decoded
alongside the outline and exposed through the scaler's internal
`GlyphVariationPhantoms` (right-minus-left advance-width and top-minus-bottom
advance-height deltas), and are `null` at the default instance. A later metrics
step will consume them, so metrics are still returned at the default instance
and a non-default selection still does not change the metrics a portable
provider returns.

The portable CFF2 outline route now varies too. Each charstring's `blend`
operands are evaluated at the instance's normalized axes, with the region
scalars supplied by the charstring's item variation data through a shared,
bounded, cancellable format-1 `ItemVariationStore` evaluator
(`VariationStoreEvaluator` with `VariationStoreLimits`, in the
`org.graphiks.kalligraphie.font.sfnt.variation` package). Its declared
`maxRegions`, `maxItemData`, `maxAxes` and `maxSourceBytes` bounds are enforced
incrementally while decoding. The `vsindex` used before the first charstring
`blend` is seeded from the selected Font DICT's Private DICT (`vsindex`,
operator 22, resolved per glyph through `FDSelect` and defaulting to FD 0), and
a charstring `vsindex` override is validated against the store's region data.
The instance's normalized axes reach the route through the same axis-order
mapping (`VariationAxisOrder.orderedNormalizedAxes`) the `gvar` route uses. A
non-default selection now changes the CFF2 outline a portable provider returns:
the audited variable CFF2 fixture's `A` apex is 200 at the default instance and
300 at normalized `wght = 1.0`. A store whose format is not 1 fails with
`font.variation.unsupported-store-format`, a truncated store fails with
`font.variation.truncated-store`, an out-of-range region reference fails with
`font.variation.invalid-store`, and a breach of the declared bounds reuses
`font.resource-limit-exceeded`. The `fvar` axis tags are read lazily and only
when a non-empty location is supplied, so the default path adds no parsing and
takes the same call path as before. The shared evaluator corrects the region
rule, though, so a store that contains a zero-crossing region or an invalid
bound ordering changes at the default instance (a deliberate fix, not a
regression), and a store that declares zero item-data entries is now accepted
rather than rejected. `HVAR`/`VVAR`/`MVAR` metric variation, variable colour
and synthetic bold/italic geometry remain unimplemented, and CFF2 metrics stay
at the default instance. Malformed `gvar` data fails with
`font.variation.invalid-gvar`, an unsupported table version fails with
`font.variation.unsupported-gvar-version`, and `gvar` resource bounds reuse
`font.resource-limit-exceeded` with the `gvar` table location.

Two added surfaces are defaulted placeholders rather than implemented reads:
`FontFace.stat()` returns `Success(null)` and `FontInstance.fontMetrics()`
remains unsupported because the portable provider does not yet read `STAT` or
apply `HVAR`/`VVAR`/`MVAR` metric variation (deferred). The axis selection is
retained as given: an axis explicitly set to its default value is kept,
normalizes to `0`, and produces a distinct `FontInstanceKey` from omitting that
axis (there is no default-value pruning).

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
atlas, platform-rendering, or rendering policy.

The embedded HarfBuzz 14.3.0 backend is the JVM reference implementation. Its
Linux and macOS x64/arm64 and Windows x64 resources are delivered by the
published `org.graphiks:kffi-harfbuzz-jvm` binding, hash-verified when the
library is loaded, and never found through a system-library search. Public
contracts contain no JNI or native types. Android and Apple do not yet provide
executable shaping adapters, so this route must not be treated as conformant on
those platforms.

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
