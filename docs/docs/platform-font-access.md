# Platform font access

Kalligraphie can certify an explicitly accepted CoreText font route while keeping
text analysis, shaping, fallback, positioning and editing geometry in the portable
pipeline. The application still owns its document and renderer. Platform access
does not replace a `GlyphRun` with a platform text layout or draw pixels for you.

Platform access describes a route dependent on a consumer-accepted platform
bridge, not the programming language used to implement it. A platform handle
is an opaque resource reference, not necessarily a memory address. In this
guide, native calls, pointers and allocations refer specifically to the
CoreText C interoperability implementation.

## Target capabilities and discovery

Directory discovery and platform rendering are separate capabilities. The main
JVM artifact offers `FontDirectoryCatalog`, `LinuxSystemFontCatalog` and
`MacosSystemFontCatalog`; see [capture usage and bounds](font-management.md#capture-font-directories-on-the-jvm).
The JVM directory providers capture accessible directory files rather than an
exact activated registry; the macOS `CoreTextSystemFontCatalog` in
`:kalligraphie:platform:apple` captures the activated CoreText registry instead,
and the Linux `FontconfigSystemFontCatalog` in `:kalligraphie:platform:linux`
captures the activated Fontconfig configuration. The Windows
`DirectWriteSystemFontCatalog` in `:kalligraphie:platform:windows` captures the
activated DirectWrite system font collection, and the Android
`AndroidSystemFontCatalog` in `:kalligraphie:platform:android` captures the
platform system font collection on Android 10 and later, and the iOS
`IosSystemFontCatalog` in `:kalligraphie:platform:ios` rebuilds the CoreText
registry fonts into portable containers because iOS sandboxes system font files.
Every provider requires explicit
reopening to refresh and preserves independently owned resources from the previous
generation.

| Target / provider | Discovery and source data | Operational shaping | Glyph access and refresh |
|---|---|---|---|
| JVM `FontDirectoryCatalog` | Explicit readable roots; standalone static TrueType and TTC 1/2, original source/index | Bundled HarfBuzz on Linux/macOS x64 and arm64 | Portable advertised outline/paint/bitmap profiles; new `open` for refresh |
| Linux JVM `LinuxSystemFontCatalog` | System, legacy user and XDG roots, or explicit roots; same TrueType/TTC capture | Bundled HarfBuzz on Linux x64 and arm64 | Same portable routes; no Fontconfig registry matching or automatic refresh |
| Linux JVM `FontconfigSystemFontCatalog` (`:kalligraphie:platform:linux`) | Activated Fontconfig configuration through `kffi-fontconfig`, not a directory listing; captured `.ttf`/`.ttc`/`.otf` bytes with original face indices | Bundled HarfBuzz on Linux x64 and arm64 | Same portable routes; a new `open` observes controlled install/removal and mints a new `fontconfig-registry` generation |
| macOS JVM `MacosSystemFontCatalog` | Standard system/user roots, or explicit roots; same TrueType/TTC capture | Bundled HarfBuzz on macOS x64 and arm64 | Same portable routes; no CoreText registry matching or automatic refresh |
| macOS JVM `CoreTextSystemFontCatalog` (`:kalligraphie:platform:apple`) | Activated CoreText registry through `kffi-coretext`, not a directory listing; captured `.ttf`/`.ttc`/`.otf` bytes with original face indices | Bundled HarfBuzz on macOS x64 and arm64 | Same portable routes; a new `open` observes controlled install/removal and mints a new `coretext-registry` generation |
| macOS JVM optional CoreText adapter | Exact bytes from a portable catalogue; eligible standalone static monochrome TrueType only | Preserves portable shaping; no CoreText layout substitution | Explicitly accepted platform handle, or underlying portable routes; collections excluded from the platform route |
| Windows JVM `DirectWriteSystemFontCatalog` (`:kalligraphie:platform:windows`) | Activated DirectWrite system font collection through `kffi-directwrite`, not a directory listing; captured `.ttf`/`.ttc`/`.otf` bytes with original face indices | No bundled operational HarfBuzz target | Same portable routes; a new `open` observes controlled install/removal and mints a new `directwrite-registry` generation |
| Android JVM `AndroidSystemFontCatalog` (`:kalligraphie:platform:android`) | Platform system font collection through `android.graphics.fonts.SystemFonts` (Android 10+), not arbitrary path scanning; captured `.ttf`/`.ttc`/`.otf` bytes with original face indices; family and face names come from parsing the captured bytes | No bundled HarfBuzz backend in this module; the platform text stack applies | Same portable routes; a new `open` observes a controlled change and mints a new `android-platform-fonts` generation |
| iOS `IosSystemFontCatalog` (`:kalligraphie:platform:ios`) | CoreText registry through the platform CoreText bindings, not a directory listing; iOS sandboxes system font files, so the `.ttf`/`.ttc`/`.otf` content is rebuilt from each font's copied tables | No bundled HarfBuzz backend in this module; the platform text stack applies | Same portable routes; a new `open` observes a controlled change and mints a new `ios-coretext-registry` generation |
| Kotlin Native (other targets) | No system-font provider in these targets | No implemented end-to-end shaping route | Common contracts are portable; these executable font journeys are not implemented |
| CFF/CFF2 data on any target | Standalone CFF1 `.otf` and CFF2 outlines are read; collections carrying CFF faces are captured | CFF1 shaping through the portable shaper; CFF2 materialized at the default instance only | Portable cubic outline route for CFF1 and CFF2 (default instance); no CFF2 non-default variation instance and no CoreText CFF route |

The standalone embedded route remains available on the JVM. File extensions do
not establish outline support: `.otf` may contain TrueType, CFF1 or CFF2
outlines. CFF1 cubic outlines are delivered end to end; CFF2 is materialized at
its default instance only, and a non-default variation instance is not supported. Directory admission is bounded: a TTC/OTC source whose complete face count
exceeds the remaining examination budget is rejected whole, with a typed limit
diagnostic, before examining any of its directories. No partially examined
collection prefix is published. Separate completely examined sources can still
form a partial catalog; the accepted-face cap applies independently after source
examination, preserving original selected indices. These checks do not claim
general equivalence with HarfBuzz's sanitizer for unsupported tables. A discovered face is not
a guarantee that every backend or representation profile can use it. The
four-target Linux/macOS JVM CI matrix runs actual directory and system-catalog
shaping/glyph journeys alongside the full shaper tests and native dependency
audit. Windows, Android and iOS carry no bundled HarfBuzz backend, so their
journeys stop before shaping and use the platform text stack; every provider
journey runs on a controlled provider boundary over audited fonts, and
installed-font checks remain optional smoke tests rather than the oracle.

New raw native symbols, types, ABI declarations, constants and library access
belong in kffi. Kalligraphie owns typographic adaptation, capture, provenance,
identity, generations, diagnostics and font-resource lifetime. The optional Apple
module uses kffi's dedicated CoreText bindings and Darwin system-information
service; native framework loading, symbols, signatures and matrix layout belong
to kffi. The JVM HarfBuzz shaping backend likewise uses the published
`org.graphiks:kffi-harfbuzz-jvm` binding: native library loading, ABI
signatures, memory layouts and native owners belong to kffi, while Kalligraphie
keeps the OpenType feature policy, cluster and GDEF ligature-caret
interpretation and design-to-layout conversion. Directory capture adds no raw
native bindings.

## Platform registry providers

Each published platform exposes an optional provider that captures the fonts the
platform reports for its active configuration. All of them return the same
portable catalogue contract — immutable generations, content-based identity,
typed stale-generation refusal and bounded diagnostics — and every one requires
an explicit `open` to observe a change. The capability matrix is maintained by
hand and kept consistent with the cross-platform conformance corpus; a provider
never claims a capability its target does not have.

| Provider | Discovery | Portable data | Platform route | Refresh | Limitations |
|---|---|---|---|---|---|
| macOS `CoreTextSystemFontCatalog` | Activated CoreText registry through the kffi CoreText bindings | Registered `.ttf`/`.ttc`/`.otf` bytes with original face indices | CoreText handle route for eligible faces only | A new `open` mints a `coretext-registry` generation | A stale key from another generation is refused rather than reinterpreted |
| Linux `FontconfigSystemFontCatalog` | Activated Fontconfig configuration through the kffi Fontconfig bindings | Registered file bytes | Portable routes | A new `open` mints a `fontconfig-registry` generation | A registered file that is missing or unreadable is skipped with a bounded diagnostic |
| Windows `DirectWriteSystemFontCatalog` | DirectWrite system collection through the kffi DirectWrite bindings | Reported file bytes; opaque COM keys are resolved to paths through the local font file loader | Portable routes | A new `open` mints a `directwrite-registry` generation | No bundled HarfBuzz backend: shaping uses the platform text stack |
| Android `AndroidSystemFontCatalog` | `android.graphics.fonts.SystemFonts` (Android 10 and later) | Reported file bytes | Portable routes | A new `open` mints an `android-platform-fonts` generation | Below Android 10 no supported enumeration route exists, so the provider fails with a typed error instead of scanning unknown paths |
| iOS `IosSystemFontCatalog` | CoreText registry through the platform CoreText bindings | CoreText tables rebuilt into a standalone SFNT container with a recomputed table directory checksum and `head.checkSumAdjustment` | Portable routes | A new `open` mints an `ios-coretext-registry` generation | System font files are sandboxed, so no path is available; a rebuilt container is not byte-identical to the original file and its `DSIG` signature becomes stale |

Family and face names are taken from the parsed captured bytes, so a provider
never matches by name on the platform. A provider that cannot capture a face —
missing file, unreadable source or an exceeded bound — contributes a bounded,
typed diagnostic and skips it; cancellation publishes no partial catalogue.

## Optional Apple module

Use `:kalligraphie:platform:apple` alongside the main `:kalligraphie` module.
Its publication coordinate is `org.graphiks:kalligraphie-platform-apple`; a
Kotlin Multiplatform consumer resolves the JVM variant. This route requires
macOS 15 or later, an x64 or arm64 JVM, and JDK 25. Start the application JVM
with `--enable-native-access=ALL-UNNAMED` for its native calls.

See the [Apple API reference](api/kalligraphie/platform/apple/org.graphiks.kalligraphie.platform.apple/index.md)
for catalogue, policy and lease contracts.

The main artifact does not depend on this module and does not load Apple
frameworks. The common API carries platform route identities and ownership
contracts, not CoreText pointers or kffi types. The optional module uses kffi
CoreText bindings internally and loads only the native surface needed for font
access, when the opted-in factory creates its adapter on a supported platform.
The portable targets retain their existing requirements, including Android API 24.

The internal kffi dependencies use
`org.graphiks:kffi-coretext-jvm:1.0.0-SNAPSHOT` (Apple module) and
`org.graphiks:kffi-harfbuzz-jvm:1.0.0-SNAPSHOT` (JVM shaping), following the
latest publication of the current development line. Projects resolving them
need the Central Portal snapshot repository, narrowly filtered to the CoreText,
HarfBuzz and generic runtime root/JVM artifacts required by their publication
metadata. The consuming modules recheck changing artifacts on every online
dependency resolution; they do not pin a timestamped artifact or enforce a
global dependency-checksum policy. A newer publication can change between
builds and may require source adaptation.

Add this repository in the consuming project's `settings.gradle.kts`,
alongside its normal Maven repositories:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content {
                includeModule("org.graphiks", "kffi-coretext")
                includeModule("org.graphiks", "kffi-coretext-jvm")
                includeModule("org.graphiks", "kffi-harfbuzz")
                includeModule("org.graphiks", "kffi-harfbuzz-jvm")
                includeModule("org.graphiks", "kffi")
                includeModule("org.graphiks", "kffi-jvm")
            }
        }
    }
}
```

Dependency-cache settings are not published to consumers. To check for a
newer kffi publication immediately, run the consuming build with
`--refresh-dependencies` or configure its changing-module cache lifetime.
Offline builds use already cached dependencies.

## Capture an exact font source

`CoreTextFontCatalog.capture(portable, policy, cancellationToken)` adapts a
portable catalogue with immutable accessible OpenType bytes and a reliable
copy preflight. It preserves face IDs, metadata and complete underlying
`FontInstanceKey` values, including size and geometry. Portable mapping,
metrics and shaping interpretation are unchanged.

The platform font is constructed from those exact captured bytes through
`CGFont` and `CTFont`, never by looking up a family name. No platform font
matching, hidden fallback or new character-to-glyph mapping is authorized.

Platform eligibility is deliberately conservative: static monochrome
single-face TrueType, default geometry and default render variant only.
Collections, CFF/CFF2, variation data, synthetic bold/italic, color/bitmap
tables and non-default visual variants are outside this route. Unsupported
platform faces retain the portable capabilities of their underlying provider.

The factory requires explicit `CoreTextFontAccessPolicy` limits; there is no
implicitly unlimited capture. For example, an application may choose:

```kotlin
val policy = CoreTextFontAccessPolicy(
    maxSourceBytesPerFace = 2_000_000L,
    maxCapturedSourceBytes = 8_000_000L,
    maxTransientOwnedBytes = 8_000_000L,
)
```

These are application example values, not recommended universal thresholds.
Handle the factory's typed success, failure or cancellation before using the
adapted catalogue.

## Negotiate platform or portable access

The adapted catalogue exposes its exact `platformProfile`. Include that value
in `FontAccessRequirementsSnapshot.renderable(...)` only if the consumer can
use this bridge. Ordered profiles express preferences, not permission to
hide cancellation or native allocation failures behind a cheaper route.

To accept platform access followed by a portable outline alternative:

```kotlin
val requirements = FontAccessRequirementsSnapshot.renderable(
    acceptedProfiles = listOf(catalog.platformProfile, outlineProfile),
)
```

Here `catalog` is a successful adapted catalogue and `outlineProfile` is the
consumer's supported portable profile. Set `portableDataRequired = true`
when an actual portable outline, paint graph or bitmap is needed. This
excludes platform profiles before negotiation; a platform certificate is not
portable glyph data.

Use the adapted resolver and catalogue generation consistently in layout
requests. Portable assets acquired through that resolver expose its public
generation while delegating to independently owned underlying resources.

## Final glyph certification

The portable shaper and layout produce the final glyph IDs and placements.
Platform certification validates the exact font context, then every new distinct
final ID against its verified platform glyph count and `CGGlyph` range. This
includes ligatures, substitutions and layout-derived glyphs such as a visible
hyphen at a line break. It does not remap their source characters.

Glyph zero and a glyph without ink can be valid platform IDs. The existing
missing-character policy still determines whether they belong in a run.
An out-of-range glyph is a rejection, not a fabricated empty representation.
Certification never generates paths, rasterizes glyphs or draws them.

A `PLATFORM_HANDLE` certificate carries the actual provider-issued asset key.
It promises the matching platform access route while its asset is live, subject
to distinct operational failures. Calling portable `resolveGlyph` on a
platform-only asset returns a typed route incompatibility, not a fake outline.

## Own the platform-resource lifetime

The immutable layout value and its keys own no font resources. Open a
`LayoutHandle` while its matching resolver is live, then retain the exact
published certificate with `retainFontAsset(certificate)`. The returned
asset is an independent owner and can detach another independent owner.

`JvmEditableParagraphFacade.layout` owns its used shaping backend and closes
it before publishing the paragraph result. The published immutable paragraph
does not keep that backend alive; its matching resolver must still be live
when opening a layout owner.

A `PlatformFontRenderAssetHandle` acquires a `PlatformFontLease`. For this bridge,
the platform-specific lease is a `CoreTextFontLease`; `fontRef()` returns
the usable CoreText font pointer under that lease's lifetime. Handle these
operations as `FontOperationResult`, including cancellation and typed errors.

Closing an originating layout handle, resolver or render asset does not
invalidate an already admitted independent child. A child may be used from
another thread and must itself be closed. A closed owner rejects new
acquisitions. Close is idempotent and does not wait for its admitted children;
the last owner/operation releases the underlying native context.

The adapted resolver preserves its private portable resolver's typed cleanup
result when closure drains immediately. If admitted acquire/reopen operations
are still running, close returns without waiting; the last completing
operation carries deferred cleanup diagnostics. A cleanup refusal is terminal
(`font.platform-resolver-cleanup-failed`), while primary cancellation stays
cancelled. Any asset that cannot be transferred is closed first. Repeated
close does not retry drainage. Portable resolve/instantiate and owned
acquire/reopen/detach adaptation preserve successful provider diagnostics;
detachment checks the complete underlying key
before exposing the original public key.

**Raw pointer obligation:** keep the owning lease open for every unmanaged
native call that uses the pointer, and do not close that lease concurrently
with such a call. Retaining a pointer alone keeps nothing alive. Kotlin
callbacks and scoped helpers cannot mechanically prevent pointer escape;
they are not a substitute for ownership. Kalligraphie's own operations
protect themselves with admitted child operations.

## Drawing geometry belongs to the consumer

`CTFont` uses an identity matrix and a numeric point size equal to
`layoutSize.value`. This convention does not convert layout units into
physical display points.

Feed the final positioned glyph IDs and origins, including shaping offsets,
to the consumer renderer. Respect each placement's visual transform around
its origin. Apply layout-unit-to-device conversion, zoom and axis conventions
consistently once. Do not reshape, recalculate advances/carets, or multiply
already positioned origins by the font size a second time.

When using `CTFontDrawGlyphs`, save/restore the consumer's graphics state and
preserve the text matrix explicitly with `CGContextGetTextMatrix` and
`CGContextSetTextMatrix`: the call changes the context's font, text size and
text matrix, and the text matrix is not included in the documented saved
graphics-state parameters. See Apple's [drawing contract](https://developer.apple.com/documentation/coretext/ctfontdrawglyphs(_:_:_:_:_:))
and [saved graphics state](https://developer.apple.com/documentation/coregraphics/cgcontext/savegstate()).
Platform drawing and portable representation rasterization are not promised
to be pixel-identical.

The following Kotlin integration uses the application's CoreGraphics/CoreText
bindings (these C drawing functions are not exported by Kalligraphie). `success`
means the application's exhaustive handling of `FontOperationResult`; failures
and cancellation must stop drawing. `paragraph` is the published
`ParagraphLayout`, and `resolver` is its matching live resolver:

```kotlin
val layoutOwner = success(paragraph.openLayoutHandle(resolver))
try {
    for (line in paragraph.lines) {
        for (run in line.positionedGlyphRuns) {
            for (glyph in run.glyphs) {
                val certificate = requireNotNull(glyph.materializationCertificate)
                require(certificate.route == GlyphMaterializationRoute.PLATFORM_HANDLE)
                val asset = success(layoutOwner.retainFontAsset(certificate))
                    as PlatformFontRenderAssetHandle
                try {
                    val lease = success(asset.acquirePlatformFontLease()) as CoreTextFontLease
                    try {
                        val font = success(lease.fontRef())
                        val previousTextMatrix = CGContextGetTextMatrix(context)
                        CGContextSaveGState(context)
                        try {
                            CGContextTranslateCTM(context,
                                glyph.origin.x.value.toDouble(), glyph.origin.y.value.toDouble())
                            val t = glyph.transform
                            CGContextConcatCTM(context, CGAffineTransform(
                                t.a.toDouble(), t.b.toDouble(), t.c.toDouble(), t.d.toDouble(), 0.0, 0.0))
                            CGContextScaleCTM(context, 1.0, -1.0)
                            CGContextSetTextMatrix(context, CGAffineTransformIdentity)
                            CTFontDrawGlyphs(font, listOf(certificate.glyphId.value),
                                listOf(CGPoint(0.0, 0.0)), 1, context)
                        } finally {
                            CGContextRestoreGState(context)
                            CGContextSetTextMatrix(context, previousTextMatrix)
                        }
                    } finally { success(lease.close()) }
                } finally { success(asset.close()) }
            }
        }
    }
} finally { success(layoutOwner.close()) }
```

The bindings marshal the glyph list as `CGGlyph` and positions as `CGPoint`
buffers whose lifetime covers the call. The context's current transformation
matrix (CTM) must already map physical layout coordinates to device coordinates.
For example, a device scale of `0.1` maps 10 layout units to one pixel;
apply zoom there once. The drawing mapping is
`deviceFromLayout * translate(origin) * glyphTransform * flipGlyphY`.
Layout coordinates run x-right/y-down; CoreText glyph-local coordinates run
x-right/y-up. The local flip does not flip the published origin or multiply
the font size. `LineLayout.positionedGlyphRuns` and fragment origins already
include paragraph translation: never add `line.baseline` again.
`EditableLine.positionedGlyphRuns` instead uses line-baseline-relative origins
and needs exactly one explicit line-to-paragraph translation.

For the audited Liberation Sans A at size 2048 and paragraph origin `(100,950)`,
device scale `0.1` and device translation `(20,0)`, design-space crossbar point
`(686,480)` maps to `(98.6,47)`, while counter point `(686,800)` maps to
`(98.6,15)`. These independently audited interior points exercise placement
and axis conversion without requiring platform/portable pixel equality.

## Reopening and immutable identity

Keep an asset key when later reopening is required, but do not treat it as
a universal font locator. Reopening requires a live resolver from the exact
adapted provider/generation and the same profile, complete variant,
bridge contract and captured runtime interpretation.

The platform reopening token is opaque, not a native address. Reopening creates
a semantically equivalent font for the exact key; it does not promise the same
pointer. Portable semantic identities can share equal source content across
generations; platform semantic identities retain their provider/generation and
bridge/runtime context.

## Cancellation and failures

Token-aware acquisition and `reopen` overloads preserve the historical
signatures' `CancellationToken.none` behavior. The layout pipeline and
`openLayoutHandle(resolver, cancellationToken)` propagate their operation
token through platform preparation. Checks occur before work, between native
creation steps, between newly validated glyph IDs and before ownership
transfer. A native C call cannot necessarily be interrupted while executing;
checks resume when it returns. Failure/cancellation transfers no partial
owner or certificate, and cleanup is unconditional and non-cancellable.

An unsupported platform font subset/profile/geometry/variant can proceed to a
subsequent explicitly accepted alternative. Closed resources, wrong
generation/context, mandatory estimate failures, owning-operation/access
limits, cancellation, library/symbol loading and native allocation/creation
failures stop the operation. A native constructor returning `NULL` is an
operational creation failure, not evidence of an empty glyph.

Default cooperative overloads call the historical operation. Kotlin interface
delegation is different: `FontAssetResolverHandle by delegate` and
`FontInstance by delegate` independently delegate every overload when compiled
against the updated interface. A decorator customizing `reopen` or
`acquireRenderAsset` must also override the token-aware overload used by the
caller and forward its received token. Overriding only the historical overload
does not intercept the independently delegated token-aware overload.

## Controlled bytes, not process memory

`FontInstance.estimateOpenTypeDataCopy()` reports exact immutable source
bytes and a conservative bound on provider-owned source-copy allocations,
including the returned data container. A provider that cannot establish
the mandatory bound is rejected before copying; unknown is not zero.

`maxSourceBytesPerFace` bounds each captured source.
`maxCapturedSourceBytes` bounds the new captured-source total.
`maxTransientOwnedBytes` admits simultaneously controlled source-copy and
native-creation buffers across resolvers from this snapshot. Reservations
are released on transfer, failure or cancellation. Structured access-limit
errors identify the phase, dimension, maximum and observed charge.

Normal acquisition and direct reopening both apply bridge admission; an
unlimited optional operation-pool byte limit does not bypass it. Existing
operation limits also bound the live assets owned by a layout operation.
Borrowing/detaching shares the existing context without copying source data
or creating another font.

These charges exclude original caller-owned catalogue data, JVM object
overhead, delayed garbage collection and private OS allocations. They are
not a bound on process RSS. Consumer-retained owners require explicit closure
and are not cache entries that the engine evicts.

## Shared context retention

CoreText context retention is disabled by default. An enabled local policy without
a scope retains within a private capture budget. To aggregate portable representations
and eligible platform contexts, supply one scope explicitly to both captures:

```kotlin
val budget = FontCacheBudget(16L * 1024 * 1024, 1_000_000, 8L * 1024 * 1024, 32)
val policy = FontMaterializationCachePolicy(budget, budget)
val scope = Kalligraphie.fontCacheScope(budget)
val portable = success(Kalligraphie.embedded(bytes, provenance, policy, scope))
val native = success(CoreTextFontCatalog.capture(
    portable, accessPolicy, cachePolicy = policy, cacheScope = scope,
))
```

The portable catalog's policy/scope is not changed by adaptation. All four
scope/capture/face dimensions must fit simultaneously. Only the existing static,
monochrome TrueType route with default geometry and variant is retained; this adds
no format, provider or engine. Custom providers' independent caches are excluded.
Each context charges a conservative managed envelope of 4096 bytes plus 1024 bytes
of fixed key/owner metadata and variable strings, source length N for CFData's known
copy, zero decoded pixels and four native units: CFData, CGDataProvider, CGFont,
CTFont. These units count explicit resources, not internal allocation calls or
unknown framework memory. Transient creation admission remains independent.

Active, reserved, retiring and residual charges all count at every level. An evicted
cache reference remains charged until confirmed relinquishment; failed or partial
cleanup conservatively retains its full residual charge. Once the cache reference
is relinquished, independently owned consumer resources become external memory,
even when they keep the physical context alive. Captured sources, caller-only
owners, JVM overhead, GC timing and private OS memory do not count; ongoing cache
release is not an exclusion.

Closing the last resolver after its admitted operations drain clears the capture's
cache references. Reopening preserves its capture identity and outstanding charges.
Cache-only cleanup faults do not turn successful typographic acquisition into failure:
the first and repeated resolver `close()` report the first known bounded fault,
including a fault learned during deferred drainage. A portable close failure or
cancellation remains primary and receives cache diagnostics. Repeated closes never
retry the cache reference's release.

`scope.close()` disables retention and drains references outside coordination. It
reports known faults and can return before concurrent cleanup finishes. Existing
consumer owners, subsequent acquisitions and new captures using the closed scope
remain usable uncached; there is no private replacement. Close resolvers/scopes
outside the rendering critical path, since drainage can release every retained
entry and native release latency is not universally bounded. Close every independent
asset, detached owner and lease according to its ordinary lifetime contract.
A practical shutdown order is to close each resolver when its acquisition work
ends, then close the shared scope when reuse ends, both off the rendering path.
Delayed render assets and leases may remain usable and close independently later.

Changed trailing JVM capture/constructor signatures require consumer recompilation;
ordinary Kotlin calls retain defaults after recompilation. Internal assembly
declarations do not expose a supported custom-cache SPI. The
[optional measurement](glyph-materialization-measurement.md#shared-retention-and-native-ownership)
separately records event-time charge maxima, bounded admission work and physical
resource ownership; these observations are not a universal rendering latency promise.
