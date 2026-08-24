# Font Source Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Import the Kanvas OpenType font stack into Kalligraphie as tested JVM modules, retaining every component under font/ except gpu-api and GPU-only handoff code.

**Architecture:** The import is a JVM-first Gradle module tree. Package names move mechanically beneath io.ygdrasil.kalligraphie while preserving the distinct font, glyph, and text branches. Former gpu-api consumers use the text model or a local deterministic atlas packer; no GPU contract is copied.

**Tech Stack:** Kotlin 2.4.10, Gradle 9.5.0, JDK 25, Kotlin/JVM, Kotlin test/JUnit Platform, OpenType/SFNT fixtures.

**Spec:** docs/superpowers/specs/2026-08-24-font-repatriement-design.md

## Global Constraints

- Import source only from https://github.com/ygdrasil-io/kanvas.git at commit 71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b.
- Do not copy font/gpu-api/, ColorGlyphGpuHandoff.kt, or ColorGlyphGpuHandoffTest.kt.
- Keep this package mapping:
  - org.graphiks.kanvas.font... → io.ygdrasil.kalligraphie.font...
  - org.graphiks.kanvas.glyph... → io.ygdrasil.kalligraphie.glyph...
  - org.graphiks.kanvas.text... → io.ygdrasil.kalligraphie.text...
- Keep imported production code in JVM modules. Do not add it as a dependency of shared/commonMain.
- Copy the required reports/font/fixtures subtree to font/fixtures, preserving provenance/index.json and license texts. Replace only source/test fixture paths from reports/font/fixtures to font/fixtures.
- Keep the JVM toolchain at 25. Do not add HarfBuzz, FreeType, JNI, native shapers, or a GPU backend.
- Every commit must pass rtk git diff --check.

---

## Target File Structure

| Path | Responsibility |
| --- | --- |
| buildSrc/src/main/kotlin/ygdrasil/conventions/jvm-library.gradle.kts | Common Kotlin/JVM and JDK 25 convention. |
| font/build.gradle.kts | Façade module and fontTest aggregate task. |
| font/{core,sfnt,colr,scaler,text,glyph}/build.gradle.kts | Module boundaries without gpu-api. |
| font/fixtures/ | Required font, Unicode, expected output, provenance and license data. |
| font/UPSTREAM.md | Reproducible source snapshot, package mapping and exclusions. |
| font/glyph/.../GlyphSurface.kt | Renderer-neutral routing and local row-atlas packing. |
| font/glyph/.../color/ColorGlyphSurface.kt | Colour-glyph planning from ShapedGlyphRun. |
| font/.../atlas/GlyphAtlasUploadPlan.kt | Renderer-neutral byte-atlas packing. |
| .github/workflows/ci.yml | Runs imported JVM tests. |

## Module Dependency Graph

~~~text
:font:core
 ├── :font:sfnt
 │    ├── :font:colr
 │    └── :font:scaler
 │         └── :font:text
 └── :font:glyph  ← core, text, scaler, colr
:font             ← core, sfnt, scaler, text, glyph, colr

Deferred: :font-gpu-contracts; no source is copied for it.
~~~

### Task 1: Establish JVM build boundaries before importing sources

**Files:**
- Create: buildSrc/src/main/kotlin/ygdrasil/conventions/jvm-library.gradle.kts
- Create: font/build.gradle.kts and font/{core,sfnt,colr,scaler,text,glyph}/build.gradle.kts
- Modify: settings.gradle.kts
- Test: Gradle project discovery and the fontTest aggregate task.

**Interfaces:**
- Consumes: the existing Kotlin Gradle plugin and JDK 25 declared by buildSrc.
- Produces: :font, :font:core, :font:sfnt, :font:colr, :font:scaler, :font:text and :font:glyph.

- [ ] **Step 1: Write the failing project-discovery check**

Run: rtk ./gradlew --no-daemon :font:fontTest

Expected: FAIL because project :font is not included.

- [ ] **Step 2: Add the reusable JVM convention**

Create buildSrc/src/main/kotlin/ygdrasil/conventions/jvm-library.gradle.kts:

~~~kotlin
package ygdrasil.conventions

import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    id("java-library")
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
~~~

- [ ] **Step 3: Include the exact project paths and dependency graph**

Append to settings.gradle.kts:

~~~kotlin
include(":font")
include(":font:core")
include(":font:sfnt")
include(":font:colr")
include(":font:scaler")
include(":font:text")
include(":font:glyph")
~~~

Apply the new convention to every module. Use these non-trivial dependencies:

~~~kotlin
// font/sfnt/build.gradle.kts
dependencies { api(project(":font:core")) }

// font/colr/build.gradle.kts
dependencies {
    implementation(project(":font:core"))
    implementation(project(":font:sfnt"))
}

// font/scaler/build.gradle.kts
dependencies {
    api(project(":font:core"))
    implementation(project(":font:sfnt"))
    implementation(project(":font:colr"))
}

// font/text/build.gradle.kts
dependencies {
    api(project(":font:core"))
    api(project(":font:sfnt"))
    implementation(project(":font:scaler"))
}

// font/glyph/build.gradle.kts
dependencies {
    api(project(":font:core"))
    api(project(":font:text"))
    implementation(project(":font:colr"))
    implementation(project(":font:scaler"))
}
~~~

Every one of the seven module build files must also declare:

~~~kotlin
dependencies {
    testImplementation(kotlin("test"))
}
~~~

In font/build.gradle.kts expose core, sfnt, scaler, text, glyph and colr, then register:

~~~kotlin
tasks.register("fontTest") {
    group = "verification"
    dependsOn(
        ":font:core:test",
        ":font:sfnt:test",
        ":font:colr:test",
        ":font:scaler:test",
        ":font:text:test",
        ":font:glyph:test",
        ":font:test",
    )
}
~~~

- [ ] **Step 4: Run the empty module graph**

Run: rtk ./gradlew --no-daemon :font:fontTest

Expected: PASS. Every project resolves with JDK 25 and no :font:gpu-api project exists.

- [ ] **Step 5: Commit**

~~~bash
git add settings.gradle.kts buildSrc/src/main/kotlin/ygdrasil/conventions/jvm-library.gradle.kts font
git commit -m "build: add JVM font module graph"
~~~

### Task 2: Pin Kanvas and import all test fixtures required by the source suite

**Files:**
- Create: font/UPSTREAM.md
- Create: font/fixtures/** from upstream reports/font/fixtures/**
- Modify: every imported font source/test path that names reports/font/fixtures
- Test: source SHA, fixture provenance, required assets and licenses.

**Interfaces:**
- Consumes: the Kanvas commit and reports/font/fixtures/provenance/index.json.
- Produces: an offline fixture root at font/fixtures.

- [ ] **Step 1: Write the source snapshot manifest**

Create font/UPSTREAM.md:

~~~markdown
# Kanvas font import

- Source: https://github.com/ygdrasil-io/kanvas.git
- Commit: 71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b
- Imported modules: core, sfnt, colr, scaler, text, glyph and font.
- Excluded module: gpu-api.
- Excluded GPU bridge: glyph/color/ColorGlyphGpuHandoff.kt and its test.
- Package root: io.ygdrasil.kalligraphie.
- Fixtures: font/fixtures, copied from reports/font/fixtures with provenance and licenses unchanged.
~~~

- [ ] **Step 2: Retrieve the immutable source snapshot**

Run:

~~~bash
font_import_dir=$(mktemp -d /private/tmp/kalligraphie-font-import.XXXXXX)
git clone --filter=blob:none --no-checkout https://github.com/ygdrasil-io/kanvas.git "$font_import_dir"
git -C "$font_import_dir" sparse-checkout set font reports/font/fixtures
git -C "$font_import_dir" checkout 71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b
git -C "$font_import_dir" rev-parse HEAD
~~~

Expected final output: 71eb60ea270fab46dbcdcbc58bb923ddcfd8ef5b.

- [ ] **Step 3: Copy the fixture root before importing tests**

Copy $font_import_dir/reports/font/fixtures/ to font/fixtures/. Preserve fonts/, expected/, licenses/ and provenance/. For every copied Kotlin source/test file, replace this literal path:

~~~text
reports/font/fixtures
font/fixtures
~~~

Do not rewrite fixtureRoot inside provenance/index.json; it is source provenance.

- [ ] **Step 4: Verify assets and provenance**

Run:

~~~bash
test -f font/fixtures/provenance/index.json
test -f font/fixtures/licenses/liberation-OFL-1.1.txt
test -f font/fixtures/licenses/test_glyphs_colrv1-Apache-2.0.txt
test -f font/fixtures/fonts/liberation/LiberationSans-Regular.ttf
test -f font/fixtures/fonts/color/test_glyphs-glyf_colr_1.ttf
rtk jq -e '.schemaVersion == 1 and .fixtureRoot == "reports/font/fixtures"' font/fixtures/provenance/index.json
~~~

Expected: every command exits 0.

- [ ] **Step 5: Commit**

~~~bash
git add font/UPSTREAM.md font/fixtures
git commit -m "test: vendor pinned font fixtures"
~~~

### Task 3: Import core and SFNT

**Files:**
- Create: font/core/src/{main,test}/kotlin/io/ygdrasil/kalligraphie/font/**
- Create: font/sfnt/src/{main,test}/kotlin/io/ygdrasil/kalligraphie/font/sfnt/**
- Test: FontCoreSurfaceTest and SFNTSurfaceTest.

**Interfaces:**
- Consumes: fixture data from font/fixtures.
- Produces: source/typeface identity and SFNT table parsing.

- [ ] **Step 1: Write a failing target-namespace test, then copy sources**

Create font/core/src/test/kotlin/io/ygdrasil/kalligraphie/font/FontNamespaceTest.kt:

~~~kotlin
package io.ygdrasil.kalligraphie.font

import kotlin.test.Test
import kotlin.test.assertEquals

class FontNamespaceTest {
    @Test
    fun exposesTheKalligraphieFontNamespace() {
        assertEquals(
            "io.ygdrasil.kalligraphie.font.FontSource",
            FontSource::class.qualifiedName,
        )
    }
}
~~~

Run: rtk ./gradlew --no-daemon :font:core:test

Expected: FAIL with an unresolved FontSource reference. Then copy every Kotlin source/test from upstream font/core and font/sfnt.

- [ ] **Step 2: Apply the mechanical font package migration**

In the two copied modules replace:

~~~text
org.graphiks.kanvas.font
io.ygdrasil.kalligraphie.font
~~~

Move source paths from org/graphiks/kanvas/font to io/ygdrasil/kalligraphie/font. Do not change persisted schema identifiers merely because they contain the upstream namespace; preserve or version those identifiers intentionally.

- [ ] **Step 3: Adapt the fixture root without weakening tests**

Replace reports/font/fixtures with font/fixtures in FontCore.kt and every core/SFNT test.

- [ ] **Step 4: Run the layer tests**

Run: rtk ./gradlew --no-daemon :font:core:test :font:sfnt:test

Expected: PASS with deterministic source identity and SFNT parsing tests.

- [ ] **Step 5: Commit**

~~~bash
git add font/core font/sfnt
git commit -m "feat: import OpenType core and SFNT parsing"
~~~

+### Task 4: Import COLR and glyph scaling

**Files:**
- Create: font/colr/src/main/kotlin/io/ygdrasil/kalligraphie/font/colr/**
- Create: font/scaler/src/{main,test}/kotlin/io/ygdrasil/kalligraphie/font/scaler/**
- Create: font/scaler/src/test/resources/fonts/**
- Test: GlyphScalerTest.

**Interfaces:**
- Consumes: :font:core and :font:sfnt for table and typeface facts.
- Produces: COLR/CPAL parsing and scaled glyph outlines for the text and glyph layers.

- [ ] **Step 1: Copy the colour and scaler source trees**

Copy upstream font/colr/src and font/scaler/src, including scaler test resources. Run: rtk ./gradlew --no-daemon :font:colr:test :font:scaler:test

Expected: FAIL with unresolved org.graphiks.kanvas.font references before the target package migration.

- [ ] **Step 2: Rename the copied font packages**

In both modules replace:

~~~text
org.graphiks.kanvas.font
io.ygdrasil.kalligraphie.font
~~~

Move Kotlin source paths from org/graphiks/kanvas/font to io/ygdrasil/kalligraphie/font. Keep scaler test resources module-local because GlyphScalerTest resolves them from the test classpath.

- [ ] **Step 3: Verify parsing and scaling behaviour**

Run: rtk ./gradlew --no-daemon :font:colr:test :font:scaler:test

Expected: PASS with CPAL/COLR table and glyph-scaling checks. Do not add a gpu-api dependency.

- [ ] **Step 4: Commit**

~~~bash
git add font/colr font/scaler
git commit -m "feat: import colour fonts and glyph scaling"
~~~


### Task 5: Import text, shaping and Unicode data

**Files:**
- Create: font/text/src/main/kotlin/io/ygdrasil/kalligraphie/text/**
- Create: font/text/src/test/kotlin/io/ygdrasil/kalligraphie/text/**
- Create: font/text/src/main/resources/io/ygdrasil/kalligraphie/text/unicode/16.0.0/**
- Test: GraphemeSegmentationTest, BidiSegmentationTest and ArabicShapingFixtureTest.

**Interfaces:**
- Consumes: core, SFNT and scaler APIs.
- Produces: io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun with glyphIds, typefaceId, script and bidiLevel.

- [ ] **Step 1: Write a failing target-namespace test, then copy text files**

Create font/text/src/test/kotlin/io/ygdrasil/kalligraphie/text/TextNamespaceTest.kt:

~~~kotlin
package io.ygdrasil.kalligraphie.text

import io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun
import kotlin.test.Test
import kotlin.test.assertEquals

class TextNamespaceTest {
    @Test
    fun exposesTheKalligraphieTextNamespace() {
        assertEquals(
            "io.ygdrasil.kalligraphie.text.shaping.ShapedGlyphRun",
            ShapedGlyphRun::class.qualifiedName,
        )
    }
}
~~~

Run: rtk ./gradlew --no-daemon :font:text:test

Expected: FAIL because ShapedGlyphRun is not present under the target namespace. Then copy upstream font/text/src.

- [ ] **Step 2: Rename code and resource paths together**

Apply these replacements throughout font/text:

~~~text
org.graphiks.kanvas.text
io.ygdrasil.kalligraphie.text

org/graphiks/kanvas/text
io/ygdrasil/kalligraphie/text

org.graphiks.kanvas.font
io.ygdrasil.kalligraphie.font
~~~

Move Unicode resources to the matching io/ygdrasil/kalligraphie/text path and retain byte-for-byte content.

- [ ] **Step 3: Preserve upstream test breadth**

Replace fixture literals with font/fixtures. Import all text tests, including Arabic, Devanagari, CJK, bidi, GPOS and GSUB cases. Do not skip tests or reduce assertions because a fixture is synthetic.

- [ ] **Step 4: Verify text behavior and imports**

Run:

~~~bash
rtk ./gradlew --no-daemon :font:text:test
! rtk rg -n 'org\.graphiks\.kanvas\.(font|text)' font/text --glob '*.kt'
~~~

Expected: text tests PASS and the static package/import scan has no output.

- [ ] **Step 5: Commit**

~~~bash
git add font/text
git commit -m "feat: import JVM text shaping and Unicode data"
~~~

### Task 6: Import glyph and colour-glyph logic without GPU contracts

**Files:**
- Create: font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/GlyphMaskBlur.kt
- Create: font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/GlyphMaskKey.kt
- Create: font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/GlyphSurface.kt
- Create: font/glyph/src/main/kotlin/io/ygdrasil/kalligraphie/glyph/color/ColorGlyphSurface.kt
- Create: font/glyph/src/test/kotlin/io/ygdrasil/kalligraphie/glyph/**
- Exclude: ColorGlyphGpuHandoff.kt and ColorGlyphGpuHandoffTest.kt
- Modify: font/glyph/build.gradle.kts
- Test: GlyphSurfaceTest, ColorGlyphSurfaceTest and a static GPU-boundary scan.

**Interfaces:**
- Consumes: ShapedGlyphRun from :font:text.
- Produces: route, mask, outline, colour-route and atlas plans with no GPU type in a public signature.

- [ ] **Step 1: Copy glyph files excluding only the GPU bridge**

Copy upstream font/glyph sources and tests except the two excluded handoff files. Run: rtk ./gradlew --no-daemon :font:glyph:test

Expected: FAIL with unresolved org.graphiks.kanvas.glyph.gpu types. Do not copy gpu-api to make this pass.

- [ ] **Step 2: Replace the GPU run descriptor with ShapedGlyphRun**

In GlyphSurface.kt and color/ColorGlyphSurface.kt replace GPUGlyphRunDescriptor with ShapedGlyphRun, and replace run.glyphIDs with run.glyphIds. The two planner interfaces become:

~~~kotlin
interface GlyphArtifactPlanner {
    fun plan(
        run: ShapedGlyphRun,
        strikeKey: GlyphStrikeKey,
    ): GlyphArtifactPlan
}

interface ColorGlyphPlanner {
    fun plan(
        run: ShapedGlyphRun,
        strikeKey: GlyphStrikeKey,
    ): ColorGlyphPlanningResult
}
~~~

In both test files replace the helper constructor with ShapedGlyphRun(glyphIds = glyphIds).

- [ ] **Step 3: Replace GPU rectangle packing with a local deterministic row packer**

In GlyphSurface.kt keep packAtlasItems but replace GPUTextAtlasRectPacker, GPUTextAtlasRectItem and GPUTextAtlasPackingResult. The local cursor must obey:

~~~kotlin
var cursorX = padding
var cursorY = padding
var rowHeight = 0

for (item in items) {
    if (cursorX + item.width + padding > atlasWidth && cursorX > padding) {
        cursorX = padding
        cursorY += rowHeight + padding
        rowHeight = 0
    }
    placements += GlyphAtlasPlacement(
        glyphId = item.glyphId,
        x = cursorX,
        y = cursorY,
        width = item.width,
        height = item.height,
    )
    cursorX += item.width + padding
    rowHeight = maxOf(rowHeight, item.height)
}
~~~

Preserve the upstream validations for dimensions and maximum width. Add a two-row test asserting that the first glyph of row two has x equal to padding and y equal to padding plus firstRowHeight plus padding.

- [ ] **Step 4: Rename packages and prove there is no GPU leakage**

Apply:

~~~text
org.graphiks.kanvas.glyph
io.ygdrasil.kalligraphie.glyph

org.graphiks.kanvas.text
io.ygdrasil.kalligraphie.text

org.graphiks.kanvas.font
io.ygdrasil.kalligraphie.font
~~~

Run:

~~~bash
rtk ./gradlew --no-daemon :font:glyph:test
! rtk rg -n 'org\.graphiks\.kanvas\.glyph\.gpu|GPUGlyphRunDescriptor|GPUTextAtlas' font/glyph --glob '*.kt'
~~~

Expected: both commands succeed.

- [ ] **Step 5: Commit**

~~~bash
git add font/glyph
git commit -m "feat: import glyph planning without GPU contracts"
~~~

### Task 7: Import and de-GPU the font façade module

**Files:**
- Create: font/src/main/kotlin/io/ygdrasil/kalligraphie/font/atlas/GlyphAtlasUploadPlan.kt
- Create: font/src/main/kotlin/io/ygdrasil/kalligraphie/font/glyph/{A8Rasterizer.kt,GlyphCache.kt,GlyphStrikeKey.kt}
- Create: font/src/main/kotlin/io/ygdrasil/kalligraphie/font/handoff/GlyphRunDescriptor.kt
- Create: font/src/test/kotlin/io/ygdrasil/kalligraphie/font/**
- Create: font/src/test/resources/fonts/liberation/LiberationSans-Regular.ttf
- Modify: font/build.gradle.kts
- Test: A8RasterizerTest, GlyphAtlasUploadPlanTest and fontTest.

**Interfaces:**
- Consumes: masks from :font:glyph and scaled glyphs from :font:scaler.
- Produces: A8 byte atlases and renderer-neutral GlyphRunDescriptor handoff data.

- [ ] **Step 1: Copy the façade source and demonstrate the remaining GPU compile failure**

Copy all upstream font/src sources, tests and test resources. Run: rtk ./gradlew --no-daemon :font:test

Expected: FAIL because GlyphAtlasUploadPlan.kt imports GPUTextAtlasPageCursor and GPUTextAtlasRectItem.

- [ ] **Step 2: Add a local private atlas cursor**

Remove those two imports and use this private helper:

~~~kotlin
private class AtlasCursor(
    private val width: Int,
    private val height: Int,
) {
    private var x = 0
    private var y = 0
    private var rowHeight = 0

    fun place(itemWidth: Int, itemHeight: Int): AtlasRegion? {
        if (itemWidth > width || itemHeight > height) return null
        if (x + itemWidth > width) {
            x = 0
            y += rowHeight
            rowHeight = 0
        }
        if (y + itemHeight > height) return null
        val region = AtlasRegion(x, y, itemWidth, itemHeight)
        x += itemWidth
        rowHeight = maxOf(rowHeight, itemHeight)
        return region
    }
}
~~~

Make GlyphAtlasPacker.place call cursor.place(bitmap.width, bitmap.height). Preserve GlyphAtlasUploadPlan, GlyphAtlasPlacement and GlyphRunDescriptor public types unchanged.

- [ ] **Step 3: Rename façade packages without merging distinct glyph models**

Replace org.graphiks.kanvas.font with io.ygdrasil.kalligraphie.font and org.graphiks.kanvas.glyph with io.ygdrasil.kalligraphie.glyph. The façade package io.ygdrasil.kalligraphie.font.glyph must remain distinct from io.ygdrasil.kalligraphie.glyph; do not merge their GlyphStrikeKey types.

- [ ] **Step 4: Run focused façade and aggregate tests**

Run:

~~~bash
rtk ./gradlew --no-daemon :font:test :font:fontTest
! rtk rg -n 'glyph\.gpu|GPUTextAtlasPageCursor|GPUTextAtlasRectItem' font/src --glob '*.kt'
~~~

Expected: PASS and no static GPU references.

- [ ] **Step 5: Commit**

~~~bash
git add font/src font/build.gradle.kts
git commit -m "feat: add renderer-neutral font atlas façade"
~~~

### Task 8: Exercise the font stack from JVM and enforce it in CI

**Files:**
- Modify: shared/build.gradle.kts
- Create: shared/src/jvmTest/kotlin/io/ygdrasil/shared/font/FontStackSmokeTest.kt
- Modify: .github/workflows/ci.yml
- Create: docs/docs/font-management.md
- Create: docs/docs/font-management.fr.md
- Modify: docs/mkdocs.yml
- Test: shared JVM smoke and both CI Gradle commands.

**Interfaces:**
- Consumes: public :font classes only from jvmTest.
- Produces: a documented JVM-only stack; shared/commonMain stays independent.

- [ ] **Step 1: Write the failing JVM consumer smoke test**

Create shared/src/jvmTest/kotlin/io/ygdrasil/shared/font/FontStackSmokeTest.kt:

~~~kotlin
package io.ygdrasil.shared.font

import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.uuid.Uuid

class FontStackSmokeTest {
    @Test
    fun exposesFontSourceToJvmConsumers() {
        val source = FontSource(
            id = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440000")),
            kind = FontSourceKind.MEMORY,
            displayName = "memory.ttf",
            bytes = byteArrayOf(0, 1, 2),
        )

        assertContentEquals(byteArrayOf(0, 1, 2), source.bytes)
    }
}
~~~

Run: rtk ./gradlew --no-daemon :shared:jvmTest

Expected: FAIL with unresolved io.ygdrasil.kalligraphie.font classes.

- [ ] **Step 2: Add the façade only to JVM test dependencies**

In shared/build.gradle.kts add:

~~~kotlin
jvmTest.dependencies {
    implementation(project(":font"))
}
~~~

Do not add this dependency to commonMain, androidMain or iosMain.

- [ ] **Step 3: Extend both CI paths**

Replace the fast-track command with:

~~~bash
./gradlew :shared:jvmTest :font:fontTest --no-daemon --stacktrace
~~~

Replace the deep command with:

~~~bash
./gradlew allTests :font:fontTest --no-daemon --stacktrace
~~~

- [ ] **Step 4: Document scope and limits**

Create English and French pages saying:

~~~text
Supported now: JVM loading from bytes/files, deterministic provenance,
SFNT/OpenType parsing, scaling, Unicode data, shaping and glyph planning.

Not supported: Android/iOS execution, GPU text contracts, GPU atlas upload,
native shaping bridges, automatic system fallback, or a complete emoji/rendering claim.
~~~

Add Font management: font-management.md to docs/mkdocs.yml directly after Getting Started.

- [ ] **Step 5: Verify and commit**

Run:

~~~bash
rtk ./gradlew --no-daemon :shared:jvmTest :font:fontTest
rtk git diff --check
~~~

Expected: PASS.

~~~bash
git add shared/build.gradle.kts shared/src/jvmTest .github/workflows/ci.yml docs
git commit -m "test: exercise imported JVM font stack in CI"
~~~

### Task 9: Close the migration with full verification and a deferred GPU record

**Files:**
- Modify: CHANGELOG.md
- Modify: font/UPSTREAM.md
- Test: all font modules, shared JVM, static scans and clean-worktree check.

**Interfaces:**
- Consumes: the complete JVM module graph and pinned provenance.
- Produces: a migration record with an explicit future GPU decision point.

- [ ] **Step 1: Add the release-note entry**

Add this to CHANGELOG.md:

~~~markdown
## Unreleased

### Added
- JVM-first OpenType font management imported from the pinned Kanvas font source.
- Deterministic SFNT parsing, scaling, text shaping, glyph planning and fixture provenance.

### Deferred
- GPU text contracts and gpu-api remain intentionally excluded.
- Android and iOS font implementations are not part of this release.
~~~

- [ ] **Step 2: Record the exact deferred boundary**

Append to font/UPSTREAM.md:

~~~markdown
## Deferred GPU boundary

No file from font/gpu-api/ is present in this repository. The upstream GPU-only
bridges ColorGlyphGpuHandoff.kt and its test are excluded. A future renderer
integration must introduce a reviewed :font-gpu-contracts module rather than
adding GPU types to :font:glyph.
~~~

- [ ] **Step 3: Run all functional tests**

Run:

~~~bash
rtk ./gradlew --no-daemon :font:fontTest :shared:jvmTest
rtk ./gradlew --no-daemon allTests
~~~

Expected: PASS. Investigate failures; do not weaken assertions, skip tests, or downgrade fixture checks.

- [ ] **Step 4: Verify provenance and the import boundary**

Run:

~~~bash
! rtk rg -n 'project\(":font:gpu-api"\)|org\.graphiks\.kanvas\.glyph\.gpu|GPUGlyphRunDescriptor|GPUTextAtlas' font --glob '*.kt' --glob '*.kts'
rtk jq -e '.schemaVersion == 1' font/fixtures/provenance/index.json
rtk git diff --check
rtk git status --short
~~~

Expected: the first three commands succeed with no matches or errors. The final status contains only the release-note and manifest changes before commit.

- [ ] **Step 5: Commit**

~~~bash
git add CHANGELOG.md font/UPSTREAM.md
git commit -m "docs: record font import scope and GPU deferral"
~~~
