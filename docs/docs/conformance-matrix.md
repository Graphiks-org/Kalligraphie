# Conformance matrix

Kalligraphie's portable conformance authority is the set of public-interface
tests in the non-published `:kalligraphie:conformance` module. The module is not
a consumer artifact: it declares the portable capability surface of each
reference platform and exercises the public facade directly.

This page publishes the resulting matrix: which portable capabilities each
reference platform declares, which tests carry the conformance authority and on
which platforms they execute, why no numeric tolerance is required today, and
what remains out of scope.

## Capability declaration

`PortableCapability` enumerates the portable capabilities whose availability
must be declared explicitly: `UNICODE_ANALYSIS`, `SHAPING`, `END_TO_END_LAYOUT`,
and `GLYPH_REPRESENTATION_VARIANTS`. Each platform returns a
`PortableCapabilityIdentity` from the `expect`/`actual`
`currentPortableCapabilityIdentity()`, built from one `CapabilityDeclaration`
per capability (`capability`, `available`, `profileId`).

Capability availability is declared, never inferred from compilation.
`PortableCapabilityIdentity` validates that every capability is declared exactly
once and that none is omitted, then exposes `presenceOf(capability)` for the
declared availability and `absenceDiagnostic(capability)` for the deterministic
`CAPABILITY_ABSENCE_DIAGNOSTIC_CODE = "conformance.portable-capability-absent"`
diagnostic returned when — and only when — the capability is unavailable.

## Platform capability matrix

| Platform | Unicode analysis | Shaping | End-to-end layout | Glyph representation variants | Profile |
| --- | --- | --- | --- | --- | --- |
| JVM | Present | Present | Present | Present | `jvm-reference` |
| iOS | Present | Present | Present | Present | `portable-unicode-16.0` / `bundled-harfbuzz` / `portable-paragraph` / `portable-glyph` |
| Android | Present | Present | Present | Present | `portable-unicode-16.0` / `bundled-harfbuzz` / `portable-paragraph` / `portable-glyph` |
| Web (js) | Present | Present | Present | Present | `portable-unicode-16.0` / `bundled-harfbuzz` / `portable-paragraph` / `portable-glyph` |
| Web (wasmJs) | Present | Present | Present | Present | `portable-unicode-16.0` / `bundled-harfbuzz` / `portable-paragraph` / `portable-glyph` |

Every platform declares the complete portable capability surface, and the results
are the same on each of them. The JVM is the reference. Android, iOS and the web
targets take shaping from the bundled HarfBuzz backend (Android requires API 28 or
later; on web the WebAssembly module is instantiated asynchronously, so a consumer
`await`s `initialize()` before opening a facade), the glyph representation route
from portable code, and the remaining two from this repository's own portable
code: Unicode analysis and line breaking resolve from the generated Unicode 16.0
tables, and the paragraph facade composes text through them, so a device's own
Unicode engine cannot change a result. The absence diagnostic is emitted for every
absent capability, independently of whether a caller requires it; no capability is
absent anywhere, so nothing emits it today, and the declaration still carries it
per platform for the next platform that is not complete.

## Test coverage

Portable decoding, cancellation, and capability declaration are portable
behaviors rather than gated capabilities: every platform that runs the shared
suite executes them, and the observable results are platform-invariant.

| Test | Observable coverage | Platforms executed |
| --- | --- | --- |
| `PortableDecodingConformanceTest` (`commonTest`) | UTF-8 scalars and source widths; UTF-16 surrogate pair; malformed UTF-8 → `U+FFFD` with `text.malformed-utf8`; a slice seam that splits one scalar throws. | JVM, iOS |
| `CancellationConformanceTest` (`commonTest`) | Pre-cancelled and mid-traversal cancellation → `Cancelled`; scalar and source-unit limits → `LimitExceeded`; non-success outcomes carry no partial snapshot. | JVM, iOS |
| `PlatformCapabilityConformanceTest` (`commonTest`) | The declared capability matrix per platform; the absence diagnostic exactly when a capability is unavailable; decoding runs regardless of capabilities. | JVM, iOS |
| `AndroidPortableConformanceTest` (`androidDeviceTest`) | The same facade decoding and cancellation, and the Android capability identity, on a real Android runtime. | Android |
| `IosPortableConformanceTest` (`iosSimulatorArm64Test`) | The same facade decoding and cancellation, and the iOS simulator capability identity, on the iOS simulator runtime. | iOS |

The shared suite runs from `commonTest` on JVM and iOS. `androidDeviceTest` does
not inherit `commonTest`, so `AndroidPortableConformanceTest` exercises the
public facade directly on Android rather than reusing the shared tests;
`iosSimulatorArm64Test` inherits `commonTest` and additionally runs
`IosPortableConformanceTest` on the simulator runtime.

### Verification commands

```bash
./gradlew :kalligraphie:conformance:jvmTest
./gradlew :kalligraphie:conformance:iosSimulatorArm64Test
./gradlew :kalligraphie:conformance:connectedAndroidDeviceTest
```

The Android device test runs through `connectedAndroidDeviceTest` or the
declared `mediumPhone` managed device. `iosArm64` is compiled but the tests
execute on `iosSimulatorArm64`. The `mediumPhone` managed device task is declared
in the build but not wired into CI yet.

## Numeric tolerance

No numeric tolerance is required. The portable pipeline currently produces only
bit-identical decoding results — integer scalars, source widths, and diagnostic
codes — so every conformance assertion is exact. A tolerance will be introduced
only when portable geometry exists to compare.

## Known limitations

- Android declares shaping present through the bundled HarfBuzz backend, which
  requires API 28 or later; the shared Android library floor was raised from API
  24 to API 28, a deliberate breaking change for API 24–27 consumers. iOS
  declares shaping present through the bundled HarfBuzz backend.
- The Android *host* test compilation declares less than the Android platform:
  it runs on a JVM and cannot load the shared library the shaping route needs, so
  it declares the portable Unicode analysis and the portable glyph route alone.
  The device runtime carries the full declaration.
- Reading and rasterizing fonts is not part of this module.
- `iosArm64` is compiled but tests execute on `iosSimulatorArm64`; device
  execution is not performed on hosted runners.
- `androidDeviceTest` does not inherit `commonTest`, so the Android device tests
  exercise the public facade directly rather than the shared test suite.
- The `mediumPhone` Gradle Managed Device task is declared in the build but not
  wired into CI yet.
