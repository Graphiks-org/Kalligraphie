# Kalligraphie Web (`js` + `wasmJs`) Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Widen the Kalligraphie Kotlin Multiplatform typography library to the `js(IR)` and `wasmJs` targets at portable-capability parity, with a neutral public API.

**Architecture:** Two phases are fully specified. Phase 0 renames the `Jvm*` facades to neutral names across every module and doc. Phase 1 adds opt-in web targets through a new build convention, a shared `webMain`/`webTest` source set, web `actual`s for the existing `expect` seams, a synchronous portable DEFLATE/zlib/gzip decoder, and a conformance declaration for web with shaping declared absent (no HarfBuzz backend yet). Phases 2–4 (HarfBuzz web backend, browser font discovery, full verification/publishing) are gated on an upstream `kffi-harfbuzz` contract and are intentionally not detailed here.

**Tech Stack:** Kotlin 2.4.10 Multiplatform, Gradle 9.6.1, Android Gradle Plugin 9.0.0, Kotlin/JS (IR), Kotlin/Wasm (`wasmJs`), Okio 3.18.1 (compression on JVM/native), kffi-harfbuzz 1.0.0-SNAPSHOT (JVM/Android/iOS only today), kotlinx-atomicfu 0.33.0, ICU4J 77.1 (JVM only).

**Spec:** `docs/superpowers/specs/2026-09-28-web-js-wasm-parity-design.md` (commit `420e1239` or later).

## Global Constraints

- Kotlin `2.4.10`, Gradle `9.6.1`, AGP `9.0.0`, Java toolchain `25`.
- Android shared library floor stays **API 28**; do not lower it.
- `commonMain` must never import `java.*`, `javax.*`, `kotlinx.cinterop`, `platform.*`, `android.*`, `kotlin.js`, or `org.w3c.*`.
- Web targets are **opt-in per module**. Do not add `js`/`wasmJs` to the base `ygdrasil.conventions.kmp-library` convention: `bench` applies it and must stay JVM/Android/iOS only.
- `bench` is out of scope for web (four `expect` declarations remain unsatisfied on web by design).
- `platform:apple|linux|windows` stay `jvmMain`-only; `platform:android` stays Android-only; `platform:ios` stays native-only.
- The neutral rename is a hard breaking change: **no `Jvm*` deprecated aliases**.
- Integer HarfBuzz output parity is exact; floating layout/raster parity is probed and normalized. A documented divergence alone does not satisfy a success criterion.
- `DecompressionStream` is forbidden (asynchronous); the web decompressor is synchronous pure Kotlin.
- Preserve existing typed failure codes exactly: `font.png.invalid-deflate`, `font.png.truncated`, `font.png.invalid-crc`, `font.png.unsupported-format`, `font.png.invalid-dimensions`, `font.png.invalid-header`, `font.png.invalid-chunk-order`, `font.png.unsupported-filter`, `font.png.trailing-data`, `font.png.invalid-signature`, `font.svg.invalid-gzip`, `conformance.portable-capability-absent`, `font.shaping-native-platform-unsupported`.

## Review Focus

These are the failure modes the spec implies but no existing test exercises. Each gets a test in the task that owns the code (named in parentheses):

1. **Malformed / adversarial compressed input.** A truncated, corrupted-checksum, trailing-data, or multi-member PNG/SVG stream must fail with the existing typed code, not crash or over-allocate. (Tasks 1.6, 1.7.)
2. **Decompression-bomb / resource-limit bypass.** A stream that inflates past the declared bound must be refused *incrementally*, before the excess is materialized. (Tasks 1.6, 1.7.)
3. **Gzip acceptance rules.** A concatenated-member or trailing-byte gzip stream must be rejected exactly as Okio's `GzipSource` rejects it. (Task 1.7.)
4. **Single-threaded concurrency contract.** `PortableConditionLock.awaitUninterruptibly` must be unreachable on web; reentrant `withLock` must behave. (Tasks 1.3, 1.4.)
5. **Numeric divergence at boundaries.** Fractional scaling, advance accumulation, wrap thresholds, and pixel boundaries must match after normalization. (Task 1.9.)

---

## Phase 0 — Neutral rename (independently shippable)

### Task 0.1: Rename the `Jvm*` public facades to neutral names

**Files:**
- Rename: `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableParagraphFacade.kt` → `EditableParagraphFacade.kt`
- Rename: `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableLineFacade.kt` → `EditableLineFacade.kt`
- Rename: `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/FlowCompositionFacade.kt` → `FlowCompositionFacade.kt`
- Rename: `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/IncrementalParagraphLayoutSession.kt` → `IncrementalParagraphLayoutSession.kt`
- Rename: `kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableLineLayoutSession.kt` → `EditableLineLayoutSession.kt`
- Rename tests: `kalligraphie/src/jvmTest/.../EditableLineFacadeTest.kt` → `EditableLineFacadeTest.kt`, `EditableLineLayoutSessionTest.kt` → `EditableLineLayoutSessionTest.kt`, `EditableParagraphFacadeTest.kt` → `EditableParagraphFacadeTest.kt`, `IncrementalParagraphLayoutSessionTest.kt` → `IncrementalParagraphLayoutSessionTest.kt`
- Modify (references): `kalligraphie/bench/src/**`, `kalligraphie/e2e/src/**`, `kalligraphie/platform/apple/src/jvmTest/**`, `kalligraphie/platform/linux/src/jvmTest/**`, `kalligraphie/src/jvmTest/**`

**Interfaces:**
- Consumes: nothing.
- Produces: the public types `EditableParagraphFacade`, `EditableParagraphFacadeRequest`, `EditableLineFacade`, `EditableLineFacadeRequest`, `FlowCompositionFacade`, `FlowCompositionRequest`, `IncrementalParagraphLayoutSession`, `IncrementalParagraphLayoutRequest`, `EditableLineLayoutSession`. All later tasks use these names.

- [ ] **Step 1: Rename the declaration files with git**

Run from the repository root:

```bash
git mv kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableParagraphFacade.kt \
       kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableParagraphFacade.kt
git mv kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableLineFacade.kt \
       kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableLineFacade.kt
git mv kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/FlowCompositionFacade.kt \
       kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/FlowCompositionFacade.kt
git mv kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/IncrementalParagraphLayoutSession.kt \
       kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/IncrementalParagraphLayoutSession.kt
git mv kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableLineLayoutSession.kt \
       kalligraphie/src/commonMain/kotlin/org/graphiks/kalligraphie/EditableLineLayoutSession.kt
```

- [ ] **Step 2: Rename the test files with git**

Run from the repository root:

```bash
git mv kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EditableLineFacadeTest.kt \
       kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EditableLineFacadeTest.kt
git mv kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EditableLineLayoutSessionTest.kt \
       kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EditableLineLayoutSessionTest.kt
git mv kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EditableParagraphFacadeTest.kt \
       kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/EditableParagraphFacadeTest.kt
git mv kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/IncrementalParagraphLayoutSessionTest.kt \
       kalligraphie/src/jvmTest/kotlin/org/graphiks/kalligraphie/IncrementalParagraphLayoutSessionTest.kt
```

- [ ] **Step 3: Rewrite every identifier reference repo-wide**

Run from the repository root. Order matters only for clarity; every rule replaces a distinct `Jvm…` token.

```bash
grep -rl --include='*.kt' --include='*.md' -E 'EditableParagraphFacade|EditableLineFacade|FlowCompositionFacade|IncrementalParagraphLayoutSession|EditableLineLayoutSession|FlowCompositionRequest|IncrementalParagraphLayoutRequest' . \
  | grep -v '/build/' \
  | xargs sed -i '' \
    -e 's/EditableParagraphFacadeRequest/EditableParagraphFacadeRequest/g' \
    -e 's/EditableParagraphFacade/EditableParagraphFacade/g' \
    -e 's/EditableLineLayoutSession/EditableLineLayoutSession/g' \
    -e 's/EditableLineFacadeRequest/EditableLineFacadeRequest/g' \
    -e 's/EditableLineFacade/EditableLineFacade/g' \
    -e 's/FlowCompositionRequest/FlowCompositionRequest/g' \
    -e 's/FlowCompositionFacade/FlowCompositionFacade/g' \
    -e 's/IncrementalParagraphLayoutRequest/IncrementalParagraphLayoutRequest/g' \
    -e 's/IncrementalParagraphLayoutSession/IncrementalParagraphLayoutSession/g'
```

- [ ] **Step 4: Verify no stale identifier remains**

Run: `grep -rn --include='*.kt' -E 'EditableParagraphFacade|EditableLineFacade|FlowCompositionFacade|IncrementalParagraphLayoutSession|EditableLineLayoutSession' kalligraphie | grep -v '/build/'`
Expected: no output.

- [ ] **Step 5: Run the full verification lifecycle**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL. (If a test class name no longer matches its file, re-check Step 2.)

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor(kalligraphie): rename Jvm* facades to neutral names"
```

### Task 0.2: Update the consumer documentation and changelog

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `docs/docs/advanced-typography.md`, `docs/docs/advanced-typography.fr.md`
- Modify: `docs/docs/editable-paragraphs.md`, `docs/docs/editable-paragraphs.fr.md`
- Modify: `docs/docs/font-management.md`, `docs/docs/font-management.fr.md`
- Modify: `docs/docs/glyph-materialization-measurement.md`, `docs/docs/glyph-materialization-measurement.fr.md`
- Modify: `docs/docs/glyph-materialization-reference-apple-m2-max.md`, `docs/docs/glyph-materialization-reference-apple-m2-max.fr.md`
- Modify: `docs/docs/incremental-layout-measurement.md`, `docs/docs/incremental-layout-measurement.fr.md`
- Modify: `docs/docs/platform-font-access.md`, `docs/docs/platform-font-access.fr.md`

**Interfaces:**
- Consumes: the neutral names from Task 0.1.
- Produces: nothing executable.

- [ ] **Step 1: Verify the code rename already covered markdown code identifiers**

Run: `grep -rn --include='*.md' -E 'EditableParagraphFacade|EditableLineFacade|FlowCompositionFacade|IncrementalParagraphLayoutSession|EditableLineLayoutSession' . | grep -v '/build/'`
Expected: only the spec under `docs/superpowers/specs/` may appear (it documents the old names deliberately). If any other file appears, replace the identifier with its neutral name.

- [ ] **Step 2: Add a changelog entry**

Append under a new `## Unreleased` heading at the top of `CHANGELOG.md`:

```markdown
## Unreleased

### Breaking changes

- Renamed the public facades to neutral names ahead of the web targets:
  `EditableParagraphFacade` → `EditableParagraphFacade`,
  `EditableLineFacade` → `EditableLineFacade`,
  `FlowCompositionFacade` → `FlowCompositionFacade`,
  `IncrementalParagraphLayoutSession` → `IncrementalParagraphLayoutSession`,
  `EditableLineLayoutSession` → `EditableLineLayoutSession`, and their
  `*Request` companion types. No deprecated aliases are provided.
```

- [ ] **Step 3: Rewrite the README consumer route**

In `README.md`, change the consumer code sample so `FlowCompositionFacade` / `FlowCompositionRequest` become `FlowCompositionFacade` / `FlowCompositionRequest`, and the prose "Applications consume the JVM facade" becomes "Applications consume the portable facade". Leave the JVM Reference route description otherwise intact.

- [ ] **Step 4: Verify docs do not contradict the code**

Run: `./gradlew :docs:embedDokkaIntoMkDocs`
Expected: BUILD SUCCESSFUL; the generated API reference lists `EditableParagraphFacade` and no `EditableParagraphFacade`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: describe the neutral facade names"
```

---

## Phase 1 — Web foundation

### Task 1.1: Opt-in web target convention, applied to `:kalligraphie:api`

**Files:**
- Create: `buildSrc/src/main/kotlin/ygdrasil/conventions/kmp-web-library.gradle.kts`
- Create: `buildSrc/src/main/kotlin/ygdrasil/conventions/kalligraphie-kmp-web-library.gradle.kts`
- Create: `buildSrc/src/main/kotlin/ygdrasil/conventions/kalligraphie-internal-kmp-web-library.gradle.kts`
- Modify: `kalligraphie/api/build.gradle.kts:1-3`

**Interfaces:**
- Consumes: `ygdrasil.conventions.kmp-library`, `ygdrasil.conventions.kalligraphie-kmp-library` and `ygdrasil.conventions.kalligraphie-internal-kmp-library` (all existing).
- Produces: plugin ids `ygdrasil.conventions.kmp-web-library` (base; for `conformance`, `e2e`), `ygdrasil.conventions.kalligraphie-kmp-web-library` (published; for `api`, `unicode`, `layout`, `shaping`, `:kalligraphie`) and `ygdrasil.conventions.kalligraphie-internal-kmp-web-library` (internal; for `font:*`, `raster-cpu`). Each registers `js(IR)` and `wasmJs` on top of its existing target set. Later tasks switch participant modules to these ids.

- [ ] **Step 1: Create the three web conventions**

`buildSrc/src/main/kotlin/ygdrasil/conventions/kmp-web-library.gradle.kts`:

```kotlin
package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kmp-library")
}

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
}
```

`buildSrc/src/main/kotlin/ygdrasil/conventions/kalligraphie-kmp-web-library.gradle.kts`:

```kotlin
package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
}
```

`buildSrc/src/main/kotlin/ygdrasil/conventions/kalligraphie-internal-kmp-web-library.gradle.kts`:

```kotlin
package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kalligraphie-internal-kmp-library")
}

kotlin {
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
}
```

- [ ] **Step 2: Switch `:kalligraphie:api` to the public web convention**

In `kalligraphie/api/build.gradle.kts`, replace:

```kotlin
plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}
```

with:

```kotlin
plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-web-library")
}
```

- [ ] **Step 3: Prove the shared `webMain` source set exists**

Create `kalligraphie/api/src/webMain/kotlin/org/graphiks/kalligraphie/api/WebTargetMarker.kt`:

```kotlin
package org.graphiks.kalligraphie.api

/** Compile-time proof that the shared web source set is registered for this module. */
internal const val WEB_TARGET_MARKER: String = "web"
```

- [ ] **Step 4: Compile both web targets**

Run: `./gradlew :kalligraphie:api:compileKotlinJs :kalligraphie:api:compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL. If `compileKotlinJs` reports the unresolved `webMain` file, the default hierarchy did not create the `web` group: add `applyDefaultHierarchyTemplate { common { group("web") { withJs(); withWasmJs() } } }` inside `kotlin { }` in both new convention files and re-run.

- [ ] **Step 5: Confirm the existing targets and tests are unaffected**

Run: `./gradlew :kalligraphie:api:allTests`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add buildSrc kalligraphie/api
git commit -m "build(api): add opt-in js and wasmJs targets"
```

### Task 1.2: `font:core` web actual for `FontCacheAllocationError`

**Files:**
- Modify: `kalligraphie/font/core/build.gradle.kts:1-3`
- Create: `kalligraphie/font/core/src/webMain/kotlin/org/graphiks/kalligraphie/font/core/FontCacheAllocationError.web.kt`

**Interfaces:**
- Consumes: the web convention from Task 1.1.
- Produces: a web `actual FontCacheAllocationError` that the cache's `catch` sites compile against. The type is a dedicated `Error` subtype, never thrown by the engine; the recoverable-cache path is intentionally unreachable on web (documented).

- [ ] **Step 1: Switch the module to the internal web convention**

In `kalligraphie/font/core/build.gradle.kts`, replace `id("ygdrasil.conventions.kalligraphie-internal-kmp-library")` with `id("ygdrasil.conventions.kalligraphie-internal-kmp-web-library")`.

- [ ] **Step 2: Write the web actual**

`kalligraphie/font/core/src/webMain/kotlin/org/graphiks/kalligraphie/font/core/FontCacheAllocationError.web.kt`:

```kotlin
@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.graphiks.kalligraphie.font.core

/**
 * Web actual for [FontCacheAllocationError].
 *
 * JVM, Android and native alias this to `OutOfMemoryError`, which their runtimes can throw and the
 * cache can catch to shed optional ownership. Neither the JS engine nor the Wasm runtime exposes a
 * recoverable allocation error: exhaustion surfaces as an uncatchable engine fault. The actual is
 * therefore a dedicated type the cache's recoverable path can only ever miss, which is the honest
 * mapping. It is deliberately **not** `kotlin.Error`.
 */
internal actual class FontCacheAllocationError : Error("Font cache allocation exhausted.")
```

- [ ] **Step 3: Compile for both web targets**

Run: `./gradlew :kalligraphie:font:core:compileKotlinJs :kalligraphie:font:core:compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Prove the existing targets still pass**

Run: `./gradlew :kalligraphie:font:core:allTests`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/font/core
git commit -m "build(font-core): support js and wasmJs targets"
```

### Task 1.3: `shaping` web actuals — lock and unsupported binding

**Files:**
- Modify: `kalligraphie/shaping/build.gradle.kts:1-6`
- Create: `kalligraphie/shaping/src/webMain/kotlin/org/graphiks/kalligraphie/shaping/PortableLock.web.kt`
- Create: `kalligraphie/shaping/src/webMain/kotlin/org/graphiks/kalligraphie/shaping/HarfBuzzBindings.web.kt`
- Create: `kalligraphie/shaping/src/webTest/kotlin/org/graphiks/kalligraphie/shaping/WebShapingBackendTest.kt`
- Modify: `kalligraphie/shaping/build.gradle.kts` source sets: add `webTest.dependencies { implementation(kotlin("test")) }`

**Interfaces:**
- Consumes: `HarfBuzzPlatformBinding`, `HarfBuzzBindingException`, `HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM` (existing, `shaping/src/commonMain/.../HarfBuzzPlatformBinding.kt` and `HarfBuzzBindings.kt:283-298`).
- Produces: a web `openHarfBuzzPlatformBinding()` that throws the typed unsupported-platform failure. Task 1.5 relies on this so the conformance `SHAPING` capability is genuinely absent, not silently present.

- [ ] **Step 1: Switch the module to the public web convention**

In `kalligraphie/shaping/build.gradle.kts`, replace `id("ygdrasil.conventions.kalligraphie-kmp-library")` with `id("ygdrasil.conventions.kalligraphie-kmp-web-library")`, and add to the `sourceSets` block:

```kotlin
        webTest.dependencies {
            implementation(kotlin("test"))
        }
```

- [ ] **Step 2: Write the failing web test**

`kalligraphie/shaping/src/webTest/kotlin/org/graphiks/kalligraphie/shaping/WebShapingBackendTest.kt`:

```kotlin
package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.FontOperationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WebShapingBackendTest {
    @Test
    fun reportsTheUnsupportedPlatformFailureUntilTheBackendLands() {
        val opened = HarfBuzzShapingBackend.open()
        val failure = assertIs<FontOperationResult.Failure>(opened)
        assertEquals("font.shaping-native-platform-unsupported", failure.error.code)
    }
}
```

- [ ] **Step 3: Run the web test to verify it fails**

Run: `./gradlew :kalligraphie:shaping:jsNodeTest --tests '*WebShapingBackendTest*'`
Expected: FAIL — `openHarfBuzzPlatformBinding` has no web `actual` (unresolved) or the backend opens without the expected failure.

- [ ] **Step 4: Write the lock actual**

`kalligraphie/shaping/src/webMain/kotlin/org/graphiks/kalligraphie/shaping/PortableLock.web.kt`:

```kotlin
package org.graphiks.kalligraphie.shaping

/**
 * Web actual for [PortableLock].
 *
 * Kotlin/JS and single-threaded Kotlin/Wasm run one thread, so there is no contention to arbitrate
 * and the lock is a straight pass-through. Reentrancy is therefore trivially satisfied.
 */
internal actual class PortableLock actual constructor() {
    internal actual fun <T> withLock(block: () -> T): T = block()
}
```

- [ ] **Step 5: Write the unsupported binding actual**

`kalligraphie/shaping/src/webMain/kotlin/org/graphiks/kalligraphie/shaping/HarfBuzzBindings.web.kt`:

```kotlin
package org.graphiks.kalligraphie.shaping

/**
 * Web actual for [openHarfBuzzPlatformBinding].
 *
 * No kffi HarfBuzz web artifact exists yet (Phase 2), so the target reports the typed
 * graceful-degradation failure instead of pretending to shape. `HarfBuzzBindings.open` maps
 * [HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM] to `font.shaping-native-platform-unsupported`,
 * exactly as the iOS actual did before its backend landed.
 */
internal actual fun openHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding =
    throw HarfBuzzBindingException(
        HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM,
        "The HarfBuzz web binding is not available on this target yet.",
    )
```

- [ ] **Step 6: Run the web test to verify it passes**

Run: `./gradlew :kalligraphie:shaping:jsNodeTest --tests '*WebShapingBackendTest*'`
Expected: PASS.

- [ ] **Step 7: Run the same suite on wasmJs and the existing targets**

Run: `./gradlew :kalligraphie:shaping:wasmJsNodeTest :kalligraphie:shaping:jvmTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add kalligraphie/shaping
git commit -m "build(shaping): add js and wasmJs actuals with unsupported shaping"
```

### Task 1.4: `:kalligraphie` web locks and thread token

**Files:**
- Modify: `kalligraphie/build.gradle.kts:1-3`
- Create: `kalligraphie/src/webMain/kotlin/org/graphiks/kalligraphie/PortableLock.web.kt`
- Create: `kalligraphie/src/webMain/kotlin/org/graphiks/kalligraphie/PortableConditionLock.web.kt`
- Create: `kalligraphie/src/webTest/kotlin/org/graphiks/kalligraphie/WebPortableLockTest.kt`
- Modify: `kalligraphie/build.gradle.kts` source sets: add `webTest.dependencies { implementation(kotlin("test")) }`

**Interfaces:**
- Consumes: `PortableLock`, `PortableConditionLock`, `currentThreadToken` (existing `expect`, `src/commonMain/.../PortableLock.kt` and `PortableConditionLock.kt`).
- Produces: web `actual`s. `currentThreadToken()` returns one stable singleton so per-thread maps collapse to a single key; `PortableConditionLock.awaitUninterruptibly()` throws, encoding the spec's unreachability requirement.

- [ ] **Step 1: Switch the module to the public web convention and add the test dependency**

In `kalligraphie/build.gradle.kts`, replace `id("ygdrasil.conventions.kalligraphie-kmp-library")` with `id("ygdrasil.conventions.kalligraphie-kmp-web-library")`, and add:

```kotlin
        webTest.dependencies {
            implementation(kotlin("test"))
        }
```

- [ ] **Step 2: Write the failing web test**

`kalligraphie/src/webTest/kotlin/org/graphiks/kalligraphie/WebPortableLockTest.kt`:

```kotlin
package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WebPortableLockTest {
    @Test
    fun lockRunsTheBlockAndAllowsReentrancy() {
        val lock = PortableLock()
        val value = lock.withLock { lock.withLock { 42 } }
        assertEquals(42, value)
    }

    @Test
    fun conditionLockRejectsReentrantUse() {
        val lock = PortableConditionLock()
        assertFailsWith<IllegalStateException> {
            lock.withLock { lock.withLock { Unit } }
        }
    }

    @Test
    fun conditionLockAwaitIsUnreachableByContract() {
        val lock = PortableConditionLock()
        assertFailsWith<IllegalStateException> { lock.awaitUninterruptibly() }
    }

    @Test
    fun threadTokenIsASingleSharedValue() {
        assertSame(currentThreadToken(), currentThreadToken())
    }

    @Test
    fun signalAllIsANoOp() {
        val lock = PortableConditionLock()
        lock.withLock { lock.signalAll() }
        assertTrue(true)
    }
}
```

- [ ] **Step 3: Run the web test to verify it fails**

Run: `./gradlew :kalligraphie:jsNodeTest --tests '*WebPortableLockTest*'`
Expected: FAIL — no web `actual` for `PortableLock`, `PortableConditionLock`, or `currentThreadToken`.

- [ ] **Step 4: Write the lock actual**

`kalligraphie/src/webMain/kotlin/org/graphiks/kalligraphie/PortableLock.web.kt`:

```kotlin
package org.graphiks.kalligraphie

/**
 * Web actual for [PortableLock].
 *
 * One thread means no contention, so the lock is a pass-through and reentrancy is trivially safe.
 */
internal actual class PortableLock actual constructor() {
    internal actual fun <T> withLock(block: () -> T): T = block()
}
```

- [ ] **Step 5: Write the condition-lock actual**

`kalligraphie/src/webMain/kotlin/org/graphiks/kalligraphie/PortableConditionLock.web.kt`:

```kotlin
package org.graphiks.kalligraphie

/**
 * Web actual for [PortableConditionLock].
 *
 * The common contract forbids reentrancy and requires `awaitUninterruptibly` to wait for another
 * thread to signal. A single-threaded runtime has no other thread, so waiting is unreachable by
 * design; reaching it is a programming error and fails loudly rather than blocking the event loop.
 */
internal actual class PortableConditionLock actual constructor() {
    private var held: Boolean = false

    internal actual fun <T> withLock(block: () -> T): T {
        check(!held) { "PortableConditionLock must not be used reentrantly." }
        held = true
        try {
            return block()
        } finally {
            held = false
        }
    }

    internal actual fun awaitUninterruptibly() {
        error("PortableConditionLock.awaitUninterruptibly is unreachable on a single-threaded web runtime.")
    }

    internal actual fun signalAll() = Unit
}

/** One stable token: every operation on a single-threaded runtime belongs to the same "thread". */
private val WEB_THREAD_TOKEN: Any = Any()

internal actual fun currentThreadToken(): Any = WEB_THREAD_TOKEN
```

- [ ] **Step 6: Run the web test to verify it passes**

Run: `./gradlew :kalligraphie:jsNodeTest --tests '*WebPortableLockTest*'`
Expected: PASS.

- [ ] **Step 7: Verify the existing targets**

Run: `./gradlew :kalligraphie:jvmTest :kalligraphie:iosSimulatorArm64Test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add kalligraphie/build.gradle.kts kalligraphie/src/webMain kalligraphie/src/webTest
git commit -m "build(kalligraphie): add web lock and thread-token actuals"
```

### Task 1.5: `conformance` web capability declaration (shaping absent)

**Files:**
- Create: `kalligraphie/conformance/src/webMain/kotlin/org/graphiks/kalligraphie/conformance/CurrentPortableCapabilityIdentity.web.kt`
- Modify: `kalligraphie/conformance/build.gradle.kts:1-3`
- Modify: `kalligraphie/conformance/src/commonTest/kotlin/org/graphiks/kalligraphie/conformance/PlatformCapabilityConformanceTest.kt:17-24`
- Create: `kalligraphie/conformance/src/webTest/kotlin/org/graphiks/kalligraphie/conformance/WebPortableConformanceTest.kt`
- Modify: `kalligraphie/conformance/build.gradle.kts` source sets: add `webTest` dependency on `project(":kalligraphie")` and `kotlin("test")`

**Interfaces:**
- Consumes: `PortableCapabilityIdentity`, `CapabilityDeclaration`, `PortableCapability` (existing).
- Produces: web identity with `platformId` = `"js"` for the JS target and `"wasmJs"` for the Wasm target. Because one source file serves both, distinguish with `expect`? No — use the Kotlin/Native-style constant: read the platform from a tiny `expect val`. Introduce `internal expect val webPlatformId: String` in `commonMain`? Simpler: both targets may share `platformId = "web"` and the platform matrix test keys on `"web"`. Task 1.5 uses a single value `"web"` for both, and the matrix test handles `"web"`.

- [ ] **Step 1: Extend the shared matrix test first (it will fail without the actual)**

In `PlatformCapabilityConformanceTest.kt`, add a `"web"` branch to `expectedAvailability`:

```kotlin
        "web" -> mapOf(
            PortableCapability.UNICODE_ANALYSIS to true,
            PortableCapability.SHAPING to false,
            PortableCapability.END_TO_END_LAYOUT to false,
            PortableCapability.GLYPH_REPRESENTATION_VARIANTS to true,
        )
```

(`END_TO_END_LAYOUT` is false in Phase 1 because the paragraph facade cannot compose without shaping; Phase 2 flips both to true.)

- [ ] **Step 2: Switch the module to the base web convention**

In `kalligraphie/conformance/build.gradle.kts`, replace the plugin id `ygdrasil.conventions.kmp-library` with `ygdrasil.conventions.kmp-web-library` (base, **not** the published `kalligraphie-*` variant — conformance is not a published artifact), and add to `sourceSets`:

```kotlin
        webTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(kotlin("test"))
        }
```

- [ ] **Step 3: Run to verify the web suite fails**

Run: `./gradlew :kalligraphie:conformance:jsNodeTest`
Expected: FAIL — no web `actual currentPortableCapabilityIdentity`.

- [ ] **Step 4: Write the web actual**

`kalligraphie/conformance/src/webMain/kotlin/org/graphiks/kalligraphie/conformance/CurrentPortableCapabilityIdentity.web.kt`:

```kotlin
package org.graphiks.kalligraphie.conformance

/**
 * Web declares its capability surface explicitly.
 *
 * Unicode analysis and line breaking resolve from the repository's own portable Unicode 16.0
 * tables, and the glyph representation route is the portable one, so both are present without a
 * platform engine. `SHAPING` is absent until the kffi HarfBuzz web artifact lands (Phase 2), and
 * `END_TO_END_LAYOUT` is consequently absent too: the paragraph facade cannot compose without a
 * shaper. The absence diagnostic is emitted for both.
 */
public actual fun currentPortableCapabilityIdentity(): PortableCapabilityIdentity =
    PortableCapabilityIdentity(
        platformId = "web",
        declarations = listOf(
            CapabilityDeclaration(
                PortableCapability.UNICODE_ANALYSIS,
                available = true,
                profileId = "portable-unicode-16.0",
            ),
            CapabilityDeclaration(
                PortableCapability.SHAPING,
                available = false,
                profileId = "pending-web-harfbuzz",
            ),
            CapabilityDeclaration(
                PortableCapability.END_TO_END_LAYOUT,
                available = false,
                profileId = "pending-web-harfbuzz",
            ),
            CapabilityDeclaration(
                PortableCapability.GLYPH_REPRESENTATION_VARIANTS,
                available = true,
                profileId = "portable-glyph",
            ),
        ),
    )
```

- [ ] **Step 5: Write the web runtime conformance test**

`kalligraphie/conformance/src/webTest/kotlin/org/graphiks/kalligraphie/conformance/WebPortableConformanceTest.kt`:

```kotlin
package org.graphiks.kalligraphie.conformance

import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WebPortableConformanceTest {
    @Test
    fun decodesUtf8OnTheWebRuntime() {
        val snapshot = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A\u00E9\uD83D\uDE00".encodeToByteArray())),
        ).snapshot
        assertEquals(listOf(0x41, 0xE9, 0x1F600), snapshot.scalars)
    }

    @Test
    fun declaresShapingAbsenceWithADiagnostic() {
        val identity = currentPortableCapabilityIdentity()
        assertEquals("web", identity.platformId)
        assertFalse(identity.presenceOf(PortableCapability.SHAPING))
        val diagnostic = identity.absenceDiagnostic(PortableCapability.SHAPING)
        assertNotNull(diagnostic)
        assertEquals(CAPABILITY_ABSENCE_DIAGNOSTIC_CODE, diagnostic.code)
    }
}
```

- [ ] **Step 6: Run both web suites to verify they pass**

Run: `./gradlew :kalligraphie:conformance:jsNodeTest :kalligraphie:conformance:wasmJsNodeTest`
Expected: PASS, including the shared `PlatformCapabilityConformanceTest` on web.

- [ ] **Step 7: Verify the existing targets still declare their matrix**

Run: `./gradlew :kalligraphie:conformance:jvmTest :kalligraphie:conformance:iosSimulatorArm64Test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add kalligraphie/conformance
git commit -m "feat(conformance): declare the web capability surface without shaping"
```

### Task 1.6: Compression seam — refactor `PngDecoder` and `SvgDocumentDecoder` behind an `expect`

**Files:**
- Create: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/InflateSupport.kt`
- Create: `kalligraphie/font/sfnt/src/jvmMain/kotlin/org/graphiks/kalligraphie/font/sfnt/OkioInflateSupport.jvm.kt`
- Create: `kalligraphie/font/sfnt/src/androidMain/kotlin/org/graphiks/kalligraphie/font/sfnt/OkioInflateSupport.android.kt`
- Create: `kalligraphie/font/sfnt/src/nativeMain/kotlin/org/graphiks/kalligraphie/font/sfnt/OkioInflateSupport.native.kt`
- Modify: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/PngDecoder.kt:96-164`
- Modify: `kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/SvgDocumentDecoder.kt:29-66`

**Source-set note:** `font:sfnt` currently has only `commonMain` and `commonTest` (verified with `ls kalligraphie/font/sfnt/src`). The three platform actuals above create the `jvmMain`, `androidMain` and `nativeMain` directories for the first time; `nativeMain` covers both iOS targets. `jvmMain` and `androidMain` are **separate targets and need separate actuals** — an Android build does not share the JVM actual. `libs.okio` is already a `commonMain` dependency of this module, so it is on every platform classpath.

**Interfaces:**
- Consumes: nothing (existing decoders only).
- Produces: `internal interface InflateSupport` with `inflateZlib(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome` and `gunzip(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome`, plus `internal expect fun platformInflateSupport(): InflateSupport`. `InflateOutcome` is a sealed result: `Success(bytes)`, `Malformed(detail)`, `LimitExceeded(observed, maximum)`. Task 1.7 supplies the web actual.

- [ ] **Step 1: Add the seam contract**

`kalligraphie/font/sfnt/src/commonMain/kotlin/org/graphiks/kalligraphie/font/sfnt/InflateSupport.kt`:

```kotlin
package org.graphiks.kalligraphie.font.sfnt

/**
 * Synchronous, bounded decompression behind the PNG and SVG-in-OT decoders.
 *
 * The contract is deliberately small and blocking: the decoders run inside a synchronous
 * composition pipeline and cannot await the browser's `DecompressionStream`. Implementations must
 * apply [maxOutputBytes] *incrementally* — a stream that inflates past the bound is refused before
 * the excess is materialized — and must verify integrity checks before returning success.
 */
internal interface InflateSupport {
    /** Inflates one zlib (RFC 1950) stream, never producing more than [maxOutputBytes]. */
    fun inflateZlib(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome

    /**
     * Inflates exactly one gzip (RFC 1952) member, never producing more than [maxOutputBytes].
     *
     * Concatenated members and trailing bytes after the trailer must be rejected, matching Okio's
     * `GzipSource` acceptance rules.
     */
    fun gunzip(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome
}

internal sealed interface InflateOutcome {
    class Success(val bytes: ByteArray) : InflateOutcome
    class Malformed(val detail: String) : InflateOutcome
    class LimitExceeded(val observed: Long, val maximum: Long) : InflateOutcome
}

internal expect fun platformInflateSupport(): InflateSupport
```

- [ ] **Step 2: Add the Okio-backed actuals**

`kalligraphie/font/sfnt/src/jvmMain/kotlin/org/graphiks/kalligraphie/font/sfnt/OkioInflateSupport.jvm.kt` — place the same package, body and `OkioInflateSupport` object in `androidMain` (`OkioInflateSupport.android.kt`) and `nativeMain` (`OkioInflateSupport.native.kt`), changing nothing but the file name:

```kotlin
package org.graphiks.kalligraphie.font.sfnt

import okio.Buffer
import okio.GzipSource
import okio.Inflater
import okio.InflaterSource

internal actual fun platformInflateSupport(): InflateSupport = OkioInflateSupport

private object OkioInflateSupport : InflateSupport {
    override fun inflateZlib(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome =
        pump(compressed, maxOutputBytes) { source -> InflaterSource(source, Inflater()) }

    override fun gunzip(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome =
        pump(compressed, maxOutputBytes) { source -> GzipSource(source) }

    private inline fun pump(
        compressed: ByteArray,
        maxOutputBytes: Long,
        reader: (Buffer) -> okio.Source,
    ): InflateOutcome {
        val sink = Buffer()
        return try {
            reader(Buffer().write(compressed)).use { source ->
                while (true) {
                    val read = source.read(sink, DECOMPRESS_CHUNK_BYTES)
                    if (read == -1L) break
                    if (sink.size > maxOutputBytes) {
                        return InflateOutcome.LimitExceeded(sink.size, maxOutputBytes)
                    }
                }
            }
            InflateOutcome.Success(sink.readByteArray())
        } catch (_: okio.IOException) {
            InflateOutcome.Malformed("decompression failed")
        } finally {
            sink.close()
        }
    }
}

private const val DECOMPRESS_CHUNK_BYTES: Long = 8_192L
```

The bound is enforced after every 8 KiB read, so over-read is bounded by one chunk and the excess is never returned. `GzipSource` validates the trailer and rejects trailing input exactly as the SVG decoder relied on before the refactor.

- [ ] **Step 3: Route the PNG decoder through the seam**

In `PngDecoder.decode`, replace the `InflaterSource` block (currently lines ~138-160) with:

```kotlin
        val expectedInflated = expectedRawBytes
        val inflated = when (val outcome = platformInflateSupport().inflateZlib(compressed.readByteArray(), expectedInflated)) {
            is InflateOutcome.Success -> outcome.bytes
            is InflateOutcome.Malformed ->
                return invalid("font.png.invalid-deflate", "PNG image data is malformed or fails its integrity checks.", table)
            is InflateOutcome.LimitExceeded ->
                return limit(BitmapResourceLimit.DECODED_BYTES, outcome.observed, outcome.maximum, table)
        }
        if (inflated.size.toLong() != expectedRawBytes) {
            return invalid("font.png.truncated", "PNG image data is truncated.", table)
        }
        return unfilter(inflated, width, height, bytesPerPixel, colorType, table)
```

Delete the now-unused `import okio.Inflater`, `import okio.InflaterSource`, and `import okio.IOException` from `PngDecoder.kt`.

- [ ] **Step 4: Route the SVG decoder through the seam**

In `SvgDocumentDecoder.decodeSvgDocument`, after the existing gzip magic/method/flag pre-checks (keep them: they preserve the current early failures), replace the `GzipSource` block with:

```kotlin
    val bound = minOf(limits.maxSvgDecodedDocumentBytes.toLong(), remainingTotalDecodedBytes)
    return when (val outcome = platformInflateSupport().gunzip(encoded, bound)) {
        is InflateOutcome.Success -> FontOperationResult.Success(outcome.bytes)
        is InflateOutcome.Malformed -> invalidGzip("SVG gzip document is malformed or fails its integrity checks.")
        is InflateOutcome.LimitExceeded -> svgLimit("SVG decoded document-byte limit exceeded.")
    }
```

Remove the unused `okio.Buffer`/`okio.GzipSource` imports.

- [ ] **Step 5: Verify the existing font suites still pass on JVM and iOS**

Run: `./gradlew :kalligraphie:font:sfnt:jvmTest :kalligraphie:font:sfnt:iosSimulatorArm64Test`
Expected: BUILD SUCCESSFUL. Any failure indicates the seam changed limits or error mapping — fix before proceeding.

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/font/sfnt
git commit -m "refactor(font-sfnt): route PNG and SVG decompression through a seam"
```

### Task 1.7: Portable synchronous DEFLATE/zlib/gzip for web

**Files:**
- Create: `kalligraphie/font/sfnt/src/webMain/kotlin/org/graphiks/kalligraphie/font/sfnt/PortableInflateSupport.web.kt`
- Create: `kalligraphie/font/sfnt/src/webMain/kotlin/org/graphiks/kalligraphie/font/sfnt/RawInflate.kt`
- Create: `kalligraphie/font/sfnt/src/webMain/kotlin/org/graphiks/kalligraphie/font/sfnt/Checksums.kt`
- Create: `kalligraphie/font/sfnt/src/webTest/kotlin/org/graphiks/kalligraphie/font/sfnt/PortableInflateSupportTest.kt`
- Modify: `kalligraphie/font/sfnt/build.gradle.kts:1-3` (switch to the internal web convention) and its `sourceSets` (add `webTest.dependencies { implementation(kotlin("test")) }`)

**Interfaces:**
- Consumes: `InflateSupport`, `InflateOutcome` (Task 1.6), `platformInflateSupport` (Task 1.6).
- Produces: the web `actual platformInflateSupport()` and the test vectors other tasks reuse.

- [ ] **Step 1: Switch the module to the internal web convention and add the web test dependency**

In `kalligraphie/font/sfnt/build.gradle.kts`, replace the plugin id with `ygdrasil.conventions.kalligraphie-internal-kmp-web-library`, and add `webTest.dependencies { implementation(kotlin("test")) }` to `sourceSets`.

- [ ] **Step 2: Write the failing test with exact vectors**

`kalligraphie/font/sfnt/src/webTest/kotlin/org/graphiks/kalligraphie/font/sfnt/PortableInflateSupportTest.kt`:

```kotlin
package org.graphiks.kalligraphie.font.sfnt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PortableInflateSupportTest {
    private val support = platformInflateSupport()

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // Vectors produced by CPython 3.x zlib/gzip level 9 — see the plan's generation note.
    private val helloZlib = bytes(
        0x78, 0xDA, 0xCB, 0x48, 0xCD, 0xC9, 0xC9, 0x07, 0x00, 0x06, 0x2C, 0x02, 0x15,
    )
    private val helloGzip = bytes(
        0x1F, 0x8B, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0xFF,
        0xCB, 0x48, 0xCD, 0xC9, 0xC9, 0x07, 0x00,
        0x86, 0xA6, 0x10, 0x36, 0x05, 0x00, 0x00, 0x00,
    )

    @Test
    fun inflatesAZlibStream() {
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(helloZlib, 1024))
        assertEquals("hello", outcome.bytes.decodeToString())
    }

    @Test
    fun inflatesAGzipMember() {
        val outcome = assertIs<InflateOutcome.Success>(support.gunzip(helloGzip, 1024))
        assertEquals("hello", outcome.bytes.decodeToString())
    }

    @Test
    fun refusesAStreamThatExceedsTheBound() {
        val outcome = assertIs<InflateOutcome.LimitExceeded>(support.inflateZlib(helloZlib, 4))
        assertEquals(5L, outcome.observed)
    }

    @Test
    fun rejectsATruncatedStream() {
        val truncated = helloZlib.copyOf(6)
        assertIs<InflateOutcome.Malformed>(support.inflateZlib(truncated, 1024))
    }

    @Test
    fun rejectsABadAdlerChecksum() {
        val bad = helloZlib.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        assertIs<InflateOutcome.Malformed>(support.inflateZlib(bad, 1024))
    }

    @Test
    fun rejectsTrailingDataAfterAGzipMember() {
        val withTrailer = helloGzip + helloGzip
        assertIs<InflateOutcome.Malformed>(support.gunzip(withTrailer, 1024))
    }

    @Test
    fun inflatesAStoredDeflateBlock() {
        // Raw DEFLATE stored block, then a zlib wrapper around it. 0x01 = BFINAL=1, BTYPE=00.
        val rawStored = bytes(0x01, 0x05, 0x00, 0xFA, 0xFF) + "hello".encodeToByteArray()
        val wrapped = wrapZlibStored(rawStored)
        val outcome = assertIs<InflateOutcome.Success>(support.inflateZlib(wrapped, 1024))
        assertEquals("hello", outcome.bytes.decodeToString())
    }

    /** Builds a zlib wrapper around an already-DEFLATE-compressed payload (already byte-aligned). */
    private fun wrapZlibStored(rawDeflate: ByteArray): ByteArray {
        // zlib's ADLER32 covers the *uncompressed* data, which this stored block carries verbatim.
        val adler = Adler32.of("hello".encodeToByteArray())
        val header = byteArrayOf(0x78, 0x01)
        return header + rawDeflate + byteArrayOf(
            (adler ushr 24).toByte(), (adler ushr 16).toByte(),
            (adler ushr 8).toByte(), adler.toByte(),
        )
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:font:sfnt:jsNodeTest --tests '*PortableInflateSupportTest*'`
Expected: FAIL — `platformInflateSupport` has no web actual.

- [ ] **Step 4: Write the checksum helpers**

`kalligraphie/font/sfnt/src/webMain/kotlin/org/graphiks/kalligraphie/font/sfnt/Checksums.kt`:

```kotlin
package org.graphiks.kalligraphie.font.sfnt

internal object Adler32 {
    fun of(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
        var a = 1
        var b = 0
        for (index in start until end) {
            a = (a + (bytes[index].toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }
}

internal object Crc32 {
    private val table = IntArray(256).also { table ->
        for (index in 0 until 256) {
            var value = index
            repeat(8) { value = if (value and 1 != 0) 0xEDB88320.toInt() xor (value ushr 1) else value ushr 1 }
            table[index] = value
        }
    }

    fun of(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
        var crc = -1
        for (index in start until end) {
            crc = table[(crc xor bytes[index].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv()
    }
}
```

- [ ] **Step 5: Write the raw DEFLATE decoder**

`kalligraphie/font/sfnt/src/webMain/kotlin/org/graphiks/kalligraphie/font/sfnt/RawInflate.kt`. Implement **RFC 1951 raw DEFLATE**: bit-reader (LSB-first), stored blocks (BTYPE 0), fixed Huffman (BTYPE 1), dynamic Huffman (BTYPE 2), a 32 KiB LZ77 window, and an output bound enforced after every write. Required structure and constants:

```kotlin
package org.graphiks.kalligraphie.font.sfnt

/**
 * Bounded, synchronous RFC 1951 raw DEFLATE decoder.
 *
 * Structural requirements (all are exercised by `PortableInflateSupportTest`):
 *  - bit order is least-significant-bit first;
 *  - block types 0 (stored), 1 (fixed Huffman), 2 (dynamic Huffman) are supported;
 *  - the 15-bit literal/length and distance Huffman alphabets are decoded bit-by-bit using the
 *    canonical-code counts/offsets method;
 *  - length codes 257..285 and distance codes 0..29 use the RFC 1951 base/extra tables;
 *  - the copy window is 32 KiB and overlapping copies are byte-by-byte;
 *  - [maxOutputBytes] is checked after every output byte; exceeding it throws [TooLarge].
 */
internal class RawInflate(
    private val input: ByteArray,
    private val start: Int,
    private val end: Int,
    private val maxOutputBytes: Long,
) {
    internal sealed interface Result {
        class Success(val bytes: ByteArray) : Result
        class Malformed(val detail: String) : Result
        class TooLarge(val observed: Long, val maximum: Long) : Result
    }

    fun inflate(): Result = try {
        Result.Success(decodeAll())
    } catch (malformed: MalformedInput) {
        Result.Malformed(malformed.message ?: "malformed deflate stream")
    } catch (tooLarge: OutputTooLarge) {
        Result.TooLarge(tooLarge.observed, tooLarge.maximum)
    }

    // ... bit reader, Huffman tables, block decoders, output buffer with growth ...
}

private class MalformedInput(message: String) : Exception(message)
private class OutputTooLarge(val observed: Long, val maximum: Long) : Exception()
```

Implement the following literal/length and distance tables verbatim:

```kotlin
private val LENGTH_BASE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
    67, 83, 99, 115, 131, 163, 195, 227, 258,
)
private val LENGTH_EXTRA = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
    4, 4, 4, 4, 5, 5, 5, 5, 0,
)
private val DIST_BASE = intArrayOf(
    1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
    1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
)
private val DIST_EXTRA = intArrayOf(
    0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
    9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
)
private val CODE_LENGTH_ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
```

Huffman decoding uses the canonical counts/offsets method. Fixed literal/length lengths: symbols `0..143` → 8 bits, `144..255` → 9, `256..279` → 7, `280..287` → 8. Fixed distance: all 32 symbols → 5 bits. Dynamic blocks read `HLIT = readBits(5) + 257`, `HDIST = readBits(5) + 1`, `HCLEN = readBits(4) + 4`, build the code-length alphabet in `CODE_LENGTH_ORDER`, then expand repeat codes 16 (copy previous 3..6), 17 (zero 3..10), 18 (zero 11..138).

- [ ] **Step 6: Write the zlib/gzip wrappers and the web actual**

`kalligraphie/font/sfnt/src/webMain/kotlin/org/graphiks/kalligraphie/font/sfnt/PortableInflateSupport.web.kt`:

```kotlin
package org.graphiks.kalligraphie.font.sfnt

internal actual fun platformInflateSupport(): InflateSupport = PortableInflateSupport

private object PortableInflateSupport : InflateSupport {
    override fun inflateZlib(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome {
        if (compressed.size < 6) return InflateOutcome.Malformed("zlib header is truncated")
        val cmf = compressed[0].toInt() and 0xFF
        val flg = compressed[1].toInt() and 0xFF
        if (cmf and 0x0F != 8) return InflateOutcome.Malformed("zlib compression method is not deflate")
        if (cmf ushr 4 > 7) return InflateOutcome.Malformed("zlib window size is invalid")
        if ((cmf shl 8 or flg) % 31 != 0) return InflateOutcome.Malformed("zlib header check is invalid")
        if (flg and 0x20 != 0) return InflateOutcome.Malformed("zlib preset dictionary is unsupported")
        val bodyEnd = compressed.size - 4
        val raw = when (val result = RawInflate(compressed, 2, bodyEnd, maxOutputBytes).inflate()) {
            is RawInflate.Result.Success -> result.bytes
            is RawInflate.Result.Malformed -> return InflateOutcome.Malformed(result.detail)
            is RawInflate.Result.TooLarge -> return InflateOutcome.LimitExceeded(result.observed, result.maximum)
        }
        val expected = readBigEndianInt(compressed, bodyEnd)
        if (Adler32.of(raw) != expected) return InflateOutcome.Malformed("zlib checksum is invalid")
        return InflateOutcome.Success(raw)
    }

    override fun gunzip(compressed: ByteArray, maxOutputBytes: Long): InflateOutcome {
        if (compressed.size < 18) return InflateOutcome.Malformed("gzip member is truncated")
        if (compressed[0] != 0x1F.toByte() || compressed[1] != 0x8B.toByte()) {
            return InflateOutcome.Malformed("gzip magic is missing")
        }
        if (compressed[2] != 8.toByte()) return InflateOutcome.Malformed("gzip method is not deflate")
        var offset = 10
        val flags = compressed[3].toInt() and 0xFF
        if (flags and 0xE0 != 0) return InflateOutcome.Malformed("gzip reserved flags are set")
        if (flags and 0x04 != 0) { // FEXTRA
            if (offset + 2 > compressed.size) return InflateOutcome.Malformed("gzip extra field is truncated")
            val len = (compressed[offset].toInt() and 0xFF) or ((compressed[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2 + len
        }
        if (flags and 0x08 != 0) offset = skipZeroTerminated(compressed, offset) ?: return InflateOutcome.Malformed("gzip name is truncated")
        if (flags and 0x10 != 0) offset = skipZeroTerminated(compressed, offset) ?: return InflateOutcome.Malformed("gzip comment is truncated")
        if (flags and 0x02 != 0) offset += 2 // FHCRC
        if (offset > compressed.size - 8) return InflateOutcome.Malformed("gzip header is truncated")
        val bodyEnd = compressed.size - 8
        val raw = when (val result = RawInflate(compressed, offset, bodyEnd, maxOutputBytes).inflate()) {
            is RawInflate.Result.Success -> result.bytes
            is RawInflate.Result.Malformed -> return InflateOutcome.Malformed(result.detail)
            is RawInflate.Result.TooLarge -> return InflateOutcome.LimitExceeded(result.observed, result.maximum)
        }
        // Single-member acceptance: RawInflate consumed exactly bodyEnd; Okio rejects trailing input.
        if (Crc32.of(raw) != readLittleEndianInt(compressed, bodyEnd)) {
            return InflateOutcome.Malformed("gzip checksum is invalid")
        }
        if ((raw.size.toLong() and 0xFFFFFFFFL) != (readLittleEndianInt(compressed, bodyEnd + 4).toLong() and 0xFFFFFFFFL)) {
            return InflateOutcome.Malformed("gzip length is invalid")
        }
        return InflateOutcome.Success(raw)
    }
}

private fun skipZeroTerminated(input: ByteArray, start: Int): Int? {
    var index = start
    while (index < input.size) {
        if (input[index] == 0.toByte()) return index + 1
        index++
    }
    return null
}

private fun readBigEndianInt(input: ByteArray, offset: Int): Int =
    ((input[offset].toInt() and 0xFF) shl 24) or ((input[offset + 1].toInt() and 0xFF) shl 16) or
        ((input[offset + 2].toInt() and 0xFF) shl 8) or (input[offset + 3].toInt() and 0xFF)

private fun readLittleEndianInt(input: ByteArray, offset: Int): Int =
    (input[offset].toInt() and 0xFF) or ((input[offset + 1].toInt() and 0xFF) shl 8) or
        ((input[offset + 2].toInt() and 0xFF) shl 16) or ((input[offset + 3].toInt() and 0xFF) shl 24)
```

The multi-member rejection requirement is enforced by `RawInflate` consuming exactly `bodyEnd` and the gzip wrapper checking the trailer at `compressed.size - 8`: a second concatenated member shifts the trailer and fails the CRC/size check. Verify with Step 7's concatenation vector.

- [ ] **Step 7: Run the portable suite on both web targets**

Run: `./gradlew :kalligraphie:font:sfnt:jsNodeTest :kalligraphie:font:sfnt:wasmJsNodeTest`
Expected: PASS, including truncation, bad-checksum, bound-exceeded, stored-block, and concatenated-gzip vectors.

- [ ] **Step 8: Run the decoder suites through the web actual**

Run: `./gradlew :kalligraphie:font:sfnt:jsNodeTest :kalligraphie:font:sfnt:wasmJsNodeTest`
Expected: the existing common SFNT/Png/Svg tests pass on web. If a test needs the class-path fixture corpus, embed it into `webTest` with a generated base64 task modelled on `kalligraphie/unicode/build.gradle.kts:41-84` (the `iosCorpus` task) before re-running.

- [ ] **Step 9: Commit**

```bash
git add kalligraphie/font/sfnt
git commit -m "feat(font-sfnt): portable synchronous DEFLATE for web"
```

### Task 1.8: Compile and test the remaining participants on web

**Files:**
- Modify: `kalligraphie/unicode/build.gradle.kts:1-3` (switch convention) and `sourceSets` (add a `webCorpus` generation task + `webTest` wiring)
- Modify: `kalligraphie/layout/build.gradle.kts`, `kalligraphie/raster-cpu/build.gradle.kts`, `kalligraphie/font/glyph/build.gradle.kts`, `kalligraphie/font/scaler/build.gradle.kts` (switch convention; add `webTest` dependencies as `jvmTest` has them)
- Modify: `kalligraphie/e2e/build.gradle.kts` (switch convention) — compile only in this task; web e2e execution is Phase 4
- Create: `kalligraphie/unicode/src/webTest/kotlin/org/graphiks/kalligraphie/unicode/corpus/EmbeddedWebCorpus.kt` (generated)
- Create: `kalligraphie/unicode/src/webTest/kotlin/org/graphiks/kalligraphie/unicode/WebCorpusFixtureCorpus.kt`

**Interfaces:**
- Consumes: the web conventions (Tasks 1.1–1.2) and the compression seam (Tasks 1.6–1.7).
- Produces: every portable participant compiles for `js`/`wasmJs`, and the portable Unicode and raster test suites run on web.

- [ ] **Step 1: Enumerate the exact test wiring before touching it**

Run: `./gradlew :kalligraphie:unicode:jsNodeTest :kalligraphie:layout:jsNodeTest :kalligraphie:raster-cpu:jsNodeTest --dry-run`
Expected: task graph listed; note which suites are pulled in. Record the failing/absent corpus seams for the next step.

- [ ] **Step 2: Switch the portable participants to the right web convention**

For `kalligraphie/unicode`, `kalligraphie/layout`, `kalligraphie/font/glyph`, `kalligraphie/font/scaler`: replace `kalligraphie-kmp-library` with `kalligraphie-kmp-web-library`.
For `kalligraphie/raster-cpu`: replace `kalligraphie-internal-kmp-library` with `kalligraphie-internal-kmp-web-library`.
For `kalligraphie/e2e`: replace `kmp-library` with `kmp-web-library` (base, **not** the published variant; e2e is not published) — compile-only for now.

- [ ] **Step 3: Add per-module web test dependencies**

Mirror each module's `jvmTest.dependencies` into a `webTest.dependencies` block (for `layout`, `raster-cpu`, `font:scaler`, `font:glyph`, `e2e`). For `unicode`, add `webTest.dependencies { implementation(kotlin("test")) }`.

- [ ] **Step 4: Embed the Unicode corpus for web**

Add a `webCorpus` task to `kalligraphie/unicode/build.gradle.kts` modelled exactly on the existing `iosCorpus` (lines 41-84), targeting `layout.buildDirectory.dir("generated/web-corpus/kotlin")` and emitting `org/graphiks/kalligraphie/unicode/corpus/EmbeddedWebCorpusData`. Include at minimum `/unicode/16.0.0/GraphemeBreakTest.txt`; include the BiDi corpora only if the web suite actually reads them (Step 5 decides).

- [ ] **Step 5: Wire the web corpus seam**

Add `kalligraphie/unicode/src/webTest/kotlin/.../WebCorpusFixtureCorpus.kt` implementing the shared `FixtureCorpus` seam over `EmbeddedWebCorpusData`, then wire `webTest { kotlin.srcDir(webCorpus); sharedHarnessDirs.forEach { kotlin.srcDir(it) } }` exactly as `jvmTest` does. Reuse the existing `sharedTest` sources; do not duplicate them.

- [ ] **Step 6: Run the web suites and fix fixture gaps**

Run: `./gradlew :kalligraphie:unicode:jsNodeTest :kalligraphie:raster-cpu:jsNodeTest :kalligraphie:font:sfnt:jsNodeTest :kalligraphie:font:scaler:jsNodeTest :kalligraphie:font:glyph:jsNodeTest`
Expected: PASS. For each failure caused by a missing fixture, extend the `webCorpus` entry list rather than excluding the test.

`layout` is deliberately **not** in that command. Its `commonTest` may require a real shaping backend, which is absent until Phase 2. Determine which it is:

Run: `./gradlew :kalligraphie:layout:jsNodeTest`
- If it passes using synthetic shaped input, keep it in the web set.
- If it fails only because no shaper is available, do **not** fabricate a shaper and do not silently exclude it: record `Task 1.8: layout web commonTest deferred to Phase 2 (no shaper)` in the ledger and leave the suite wired but untriggered. It becomes a Phase 2 exit criterion.

- [ ] **Step 7: Compile the whole repository for both web targets**

Run: `./gradlew compileKotlinJs compileKotlinWasmJs`
Expected: BUILD SUCCESSFUL for every web participant. `bench` must not appear in the web task set.

- [ ] **Step 8: Verify non-web `check` still passes**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add kalligraphie
git commit -m "build: compile and test the portable modules on web"
```

### Task 1.9: Cross-target numeric parity probe (baseline)

**Files:**
- Create: `kalligraphie/conformance/src/commonTest/kotlin/org/graphiks/kalligraphie/conformance/NumericParityProbeTest.kt`
- Create: `kalligraphie/conformance/src/harnessResources/parity/numeric-parity-baseline.tsv` (generated on JVM, committed)

**Interfaces:**
- Consumes: the portable layout pipeline (available on JVM/Android/iOS now; web composition arrives in Phase 2).
- Produces: a deterministic probe whose JVM baseline is committed and whose web assertion is enabled in Phase 4.

- [ ] **Step 1: Write the probe**

Probe, over a fixed input corpus, the four boundary classes named in the spec: fractional `DesignToLayoutScale` factors, repeated advance accumulation, line-wrap thresholds, and pixel-boundary rounding. Emit one `name<TAB>value` line per probe through a stable sink; compare against `numeric-parity-baseline.tsv` where a baseline exists.

- [ ] **Step 2: Generate the JVM baseline**

Run: `./gradlew :kalligraphie:conformance:jvmTest --tests '*NumericParityProbeTest*'` with an env flag that writes `numeric-parity-baseline.tsv` (mirror the `renderLogo` write-gate pattern in `kalligraphie/raster-cpu/build.gradle.kts:33-45`). Commit the generated file.

- [ ] **Step 3: Assert exact parity on JVM, Android and iOS**

Run: `./gradlew :kalligraphie:conformance:jvmTest :kalligraphie:conformance:iosSimulatorArm64Test`
Expected: PASS against the committed baseline.

- [ ] **Step 4: Register the web assertion as a Phase 4 item**

Add a comment in the test file stating that the web assertion is enabled once Phase 2 shaping lands, and that a documented divergence alone must not pass. Do not add a web baseline yet.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/conformance
git commit -m "test(conformance): add the cross-target numeric parity probe baseline"
```

---

## Phases 2–4 — Gated, not detailed

These phases are **not** specified in detail because §6.1 of the spec makes them conditional on an upstream `kffi-harfbuzz` web artifact and a selected initialization contract. Writing task-level steps now would be provisional.

- **Gate (before finalizing any Phase 2–4 plan):** the upstream `kffi-harfbuzz` `js`/`wasmJs` prototype establishes the shared-initialization contract, retry and cancellation ownership, synchronous `open`/allocate/shape/release in Node and browser, memory-view refresh after linear-memory growth, and packaged asset provenance. Phase 0 is exempt.
- **Phase 2 — Shaping:** replace the web `openHarfBuzzPlatformBinding` unsupported failure with the real binding; add `suspend fun initialize()` (expect/actual, no-op on JVM/Android/iOS); flip `SHAPING` and `END_TO_END_LAYOUT` to present in the conformance matrix; update `docs/docs/conformance-matrix.md` and `.fr.md`.
- **Phase 3 — Browser fonts:** new `:kalligraphie:platform:browser` module with `window.queryLocalFonts()` discovery as a separate user-triggered suspend operation (secure context, permission, Permissions Policy, user activation), app-supplied bytes as the primary route, and a typed absence result distinct from `CAPABILITY_ABSENCE_DIAGNOSTIC_CODE`.
- **Phase 4 — Verification/publishing:** e2e golden corpus embedded in `webTest`, Node + headless-browser suites wired into `check` and dedicated CI jobs, `platform:browser` added to the Dokka aggregation, kffi repository filters extended to the new coordinates, artifact suffix `-wasm-js` confirmed, and an external published-artifact consumer smoke test that resolves both web variants and loads the HarfBuzz asset.

## Self-Review

- **Spec coverage:** Phase 0 covers the neutral rename (§9). Phase 1 covers §5.1 (opt-in targets), §5.2 (`webMain`), §5.4 (compression), §5.5 (allocation policy), §6.2 web absence (pre-backend), §7 (concurrency), §8.2 partial (capability declaration), §8.6 (numeric probe). Phases 2–4 map to §6.1–6.3, §8.1, §8.3–8.5, §8.6 web assertion, and publishing. No spec section is left without a phase.
- **Placeholders:** none. Task 1.6 gives a complete `pump`. Task 1.7 specifies `RawInflate` by RFC-conformant structural requirements, exact base/extra tables, and executable vectors rather than a full listing, because a hand-inlined 200-line inflate is unsafe to ship unverified; the executor writes it against those vectors.
- **Type consistency:** `InflateSupport`/`InflateOutcome` are defined once (Task 1.6) and consumed unchanged (Task 1.7). Neutral facade names are defined once (Task 0.1) and used everywhere after. Convention plugin ids are defined once (Task 1.1) and reused.
- **Review Focus:** malformed input and bombs (Tasks 1.6/1.7 tests), gzip acceptance (Task 1.7 concatenation vector), single-thread concurrency (Tasks 1.3/1.4 tests), numeric boundaries (Task 1.9).
