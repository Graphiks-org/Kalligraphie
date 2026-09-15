# Kalligraphie logo rendered by the CPU rasterizer — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce the README logo (Amiri `K` badge + Great Vibes wordmark) with the project's own CPU rasterizer and commit it under `docs/assets/`.

**Architecture:** A demo generator in `kalligraphie/raster-cpu`'s `jvmTest` source set composes a `GlyphPaintIR` (rounded-square `Path` node, knocked-out `K` `SolidOutline`, shaped wordmark `SolidOutline` nodes) and composites it with `GlyphRasterizer.rasterizePaint` — the same route the module already certifies. Glyphs are shaped by the project's HarfBuzz backend so the script joins correctly. An opt-in Gradle task writes two PNGs and a manifest into the repository; a conformance test in `check` pins the render fingerprint and the committed files.

**Tech Stack:** Kotlin Multiplatform, Gradle, `kotlin.test`, `:kalligraphie` JVM facade (`Kalligraphie.embedded`), `JvmHarfBuzzShapingBackend`, `GlyphRasterizer`, `java.util.zip` for PNG encoding.

**Spec:** `docs/superpowers/specs/2026-09-16-logo-raster-cpu-design.md`

---

## File Structure

| File | Responsibility |
| --- | --- |
| `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/GreatVibes-Regular.ttf` | Pinned font fixture |
| `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/OFL.txt` | Licence of the fixture |
| `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/PROVENANCE.md` | Upstream revision, digests, sizes |
| `.../raster/logo/GlyphOutlines.kt` | Outline translation and ink-bounds helpers |
| `.../raster/logo/PngEncoder.kt` | Deterministic RGBA PNG encoding |
| `.../raster/logo/KalligraphieLogoFonts.kt` | Opens both fixtures, shapes the wordmark, resolves outlines |
| `.../raster/logo/KalligraphieLogo.kt` | Layout, paint-graph composition, padding |
| `.../raster/logo/KalligraphieLogoDumpTest.kt` | Opt-in runner that writes the asset files |
| `.../raster/logo/KalligraphieLogoConformanceTest.kt` | Sealed fingerprints and committed-file integrity |
| `.../raster/logo/GreatVibesFixtureTest.kt` | Fixture identity test |
| `kalligraphie/raster-cpu/src/jvmTest/kotlin/.../raster/RasterFixtureSupport.kt` | Modified: layout-size parameter, repository-root helper |
| `kalligraphie/raster-cpu/build.gradle.kts` | Modified: `renderLogo` task, runner excluded from `jvmTest` |
| `docs/assets/kalligraphie-logo-light.png` | Committed asset |
| `docs/assets/kalligraphie-logo-dark.png` | Committed asset |
| `docs/assets/kalligraphie-logo.manifest.md` | Committed digests |
| `README.md` | Modified: `<picture>` block |

All commands run from the repository root.

---

## Task 1: Bundle the Great Vibes fixture

**Files:**
- Create: `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/GreatVibes-Regular.ttf`
- Create: `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/OFL.txt`
- Create: `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/PROVENANCE.md`
- Test: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GreatVibesFixtureTest.kt`

- [ ] **Step 1: Write the failing test**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GreatVibesFixtureTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.raster.fixtureBytes
import org.graphiks.kalligraphie.raster.sha256
import kotlin.test.Test
import kotlin.test.assertEquals

class GreatVibesFixtureTest {
    @Test
    fun bundlesThePinnedGreatVibesFixtureUnchanged() {
        val font = fixtureBytes("/fonts/great-vibes/GreatVibes-Regular.ttf")
        val licence = fixtureBytes("/fonts/great-vibes/OFL.txt")

        assertEquals(457_588, font.size)
        assertEquals("8d509802186f1b51572531ecf313e8098f9a5bfdfaca93f0c9b34467f9982d15", sha256(font))
        assertEquals(4_399, licence.size)
        assertEquals("61093a21f5e63dedf54222b3c09997e54c0fe43e3851d21386e02ddcbc246d49", sha256(licence))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.GreatVibesFixtureTest"`

Expected: FAIL — `fixture resource /fonts/great-vibes/GreatVibes-Regular.ttf is missing`.

- [ ] **Step 3: Download the pinned fixture and licence**

```bash
mkdir -p kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes
cd kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes
curl -fsSL -o GreatVibes-Regular.ttf \
  "https://raw.githubusercontent.com/google/fonts/a6039f387a790a092e417b4e8dbdd5b57fe4d6d4/ofl/greatvibes/GreatVibes-Regular.ttf"
curl -fsSL -o OFL.txt \
  "https://raw.githubusercontent.com/google/fonts/a6039f387a790a092e417b4e8dbdd5b57fe4d6d4/ofl/greatvibes/OFL.txt"
shasum -a 256 GreatVibes-Regular.ttf OFL.txt
```

Expected output (must match exactly):

```text
8d509802186f1b51572531ecf313e8098f9a5bfdfaca93f0c9b34467f9982d15  GreatVibes-Regular.ttf
61093a21f5e63dedf54222b3c09997e54c0fe43e3851d21386e02ddcbc246d49  OFL.txt
```

Return to the repository root before continuing.

- [ ] **Step 4: Write `PROVENANCE.md`**

Create `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/PROVENANCE.md`:

```markdown
# Great Vibes logo fixture

## Source

- Upstream project: [google/fonts](https://github.com/google/fonts)
- Font: Great Vibes Regular; units per em: `1000`
- Pinned source revision: [`a6039f387a790a092e417b4e8dbdd5b57fe4d6d4`](https://github.com/google/fonts/tree/a6039f387a790a092e417b4e8dbdd5b57fe4d6d4/ofl/greatvibes)
- Source URL: <https://raw.githubusercontent.com/google/fonts/a6039f387a790a092e417b4e8dbdd5b57fe4d6d4/ofl/greatvibes/GreatVibes-Regular.ttf>
- SHA-256 of `GreatVibes-Regular.ttf`: `8d509802186f1b51572531ecf313e8098f9a5bfdfaca93f0c9b34467f9982d15`
- Size of `GreatVibes-Regular.ttf`: `457588` bytes
- License: [SIL Open Font License 1.1](OFL.txt), SHA-256 `61093a21f5e63dedf54222b3c09997e54c0fe43e3851d21386e02ddcbc246d49`, size `4399` bytes

The font is an unchanged test fixture. It was not subsetted, hinted,
normalized, or regenerated.

## Why it is bundled

The font carries the README wordmark. It is shaped through the pinned HarfBuzz
backend (`GPOS` and `GSUB` present, no legacy `kern` table), so the joined script
renders correctly, and its outlines are rasterized by the deterministic CPU
rasterizer.
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.GreatVibesFixtureTest"`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GreatVibesFixtureTest.kt
git commit -m "test(logo): bundle the pinned Great Vibes fixture"
```

---

## Task 2: Layout-size parameter in the shared test support

`KalligraphieLogoFonts` must instantiate both fonts at a known layout size so shaped positions can be converted back to design units exactly. `RasterFixture` currently hardcodes `LayoutUnit(2_048f)`.

**Files:**
- Modify: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/RasterFixtureSupport.kt`
- Test: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/RasterFixtureSupportTest.kt`

- [ ] **Step 1: Write the failing test**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/RasterFixtureSupportTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.raster.fixtureBytes
import org.graphiks.kalligraphie.raster.openRasterFixture
import org.graphiks.kalligraphie.raster.outlineRequirements
import kotlin.test.Test
import kotlin.test.assertEquals

class RasterFixtureSupportTest {
    @Test
    fun opensAFixtureAtTheRequestedLayoutSize() {
        openRasterFixture(
            bytes = fixtureBytes("/fonts/great-vibes/GreatVibes-Regular.ttf"),
            requirements = outlineRequirements(),
            layoutSize = LayoutUnit(1_000f),
        ).use { fixture ->
            assertEquals(LayoutUnit(1_000f), fixture.instance.key.layoutSize)
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.RasterFixtureSupportTest"`

Expected: FAIL — `no value passed for parameter` / `cannot find a parameter with this name: layoutSize` (compile error).

- [ ] **Step 3: Add the parameter**

In `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/RasterFixtureSupport.kt`, change the signature and the instance creation:

```kotlin
/** Opens a fixture; on acquisition failure the resolver lease is released before the error is rethrown. */
internal fun openRasterFixture(
    bytes: ByteArray,
    requirements: FontAccessRequirementsSnapshot,
    renderVariant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
    layoutSize: LayoutUnit = LayoutUnit(2_048f),
): RasterFixture {
```

and replace the instantiation line with:

```kotlin
        val instance = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize)),
        ).value
```

Add the import `org.graphiks.kalligraphie.api.LayoutUnit` if it is not already present.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.RasterFixtureSupportTest"`

Expected: PASS.

- [ ] **Step 5: Confirm no existing test regressed**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest`

Expected: PASS — the default value keeps every existing call site unchanged.

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/RasterFixtureSupport.kt \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/RasterFixtureSupportTest.kt
git commit -m "test(raster): allow opening a fixture at a chosen layout size"
```

---

## Task 3: Outline translation and ink bounds

**Files:**
- Create: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlines.kt`
- Test: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlinesTest.kt`

- [ ] **Step 1: Write the failing test**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlinesTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import kotlin.test.Test
import kotlin.test.assertEquals

class GlyphOutlinesTest {
    private fun square(): GlyphOutlineIR = GlyphOutlineIR(
        glyphId = 7,
        unitsPerEm = 1_000,
        bounds = DesignBounds(minX = 0, minY = 0, maxX = 100, maxY = 200),
        commands = listOf(
            GlyphOutlineIR.Command.MoveTo(0, 0),
            GlyphOutlineIR.Command.LineTo(100, 0),
            GlyphOutlineIR.Command.QuadraticTo(100, 200, 0, 200),
            GlyphOutlineIR.Command.Close,
        ),
    )

    @Test
    fun translatesEveryCommandAndTheBounds() {
        val translated = square().translated(dx = 5.5, dy = -3.25)

        assertEquals(7, translated.glyphId)
        assertEquals(1_000, translated.unitsPerEm)
        assertEquals(
            listOf(
                GlyphOutlineIR.Command.MoveTo(5.5, -3.25),
                GlyphOutlineIR.Command.LineTo(105.5, -3.25),
                GlyphOutlineIR.Command.QuadraticTo(105.5, 196.75, 5.5, 196.75),
                GlyphOutlineIR.Command.Close,
            ),
            translated.commands,
        )
        assertEquals(DesignBounds(minX = 5, minY = -4, maxX = 106, maxY = 197), translated.bounds)
    }

    @Test
    fun reportsInkBoundsOfPlacedGlyphs() {
        val glyphs = listOf(
            PlacedGlyph(glyphId = 1, x = 0.0, y = 0.0, outline = square()),
            PlacedGlyph(glyphId = 2, x = 0.0, y = 0.0, outline = square().translated(dx = 300.0, dy = 50.0)),
        )

        assertEquals(
            DesignBounds(minX = 0, minY = 0, maxX = 400, maxY = 250),
            inkBoundsOf(glyphs),
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.GlyphOutlinesTest"`

Expected: FAIL — unresolved references `translated`, `PlacedGlyph`, `inkBoundsOf`.

- [ ] **Step 3: Write the implementation**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlines.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import kotlin.math.ceil
import kotlin.math.floor

/** One glyph outline resolved from a font and placed at a pen position in its own design units. */
internal class PlacedGlyph(
    val glyphId: Int,
    val x: Double,
    val y: Double,
    val outline: GlyphOutlineIR,
)

/**
 * Returns the same outline shifted by [dx] and [dy] design units.
 *
 * The CPU rasterizer refuses transform nodes, so placement happens on the
 * geometry itself. The conservative integer envelope is recomputed with the
 * same floor/ceil rule the outline contract uses.
 */
internal fun GlyphOutlineIR.translated(dx: Double, dy: Double): GlyphOutlineIR =
    GlyphOutlineIR(
        glyphId = glyphId,
        unitsPerEm = unitsPerEm,
        bounds = DesignBounds(
            minX = floor(bounds.minX + dx).toInt(),
            minY = floor(bounds.minY + dy).toInt(),
            maxX = ceil(bounds.maxX + dx).toInt(),
            maxY = ceil(bounds.maxY + dy).toInt(),
        ),
        commands = commands.map { command ->
            when (command) {
                is GlyphOutlineIR.Command.MoveTo ->
                    GlyphOutlineIR.Command.MoveTo(command.x + dx, command.y + dy)

                is GlyphOutlineIR.Command.LineTo ->
                    GlyphOutlineIR.Command.LineTo(command.x + dx, command.y + dy)

                is GlyphOutlineIR.Command.QuadraticTo ->
                    GlyphOutlineIR.Command.QuadraticTo(
                        command.controlX + dx,
                        command.controlY + dy,
                        command.endX + dx,
                        command.endY + dy,
                    )

                GlyphOutlineIR.Command.Close -> GlyphOutlineIR.Command.Close
            }
        },
        fillRule = fillRule,
    )

/** Returns the union of the placed outlines' conservative envelopes. */
internal fun inkBoundsOf(glyphs: List<PlacedGlyph>): DesignBounds {
    require(glyphs.isNotEmpty()) { "ink bounds require at least one placed glyph." }
    return DesignBounds(
        minX = glyphs.minOf { glyph -> glyph.outline.bounds.minX },
        minY = glyphs.minOf { glyph -> glyph.outline.bounds.minY },
        maxX = glyphs.maxOf { glyph -> glyph.outline.bounds.maxX },
        maxY = glyphs.maxOf { glyph -> glyph.outline.bounds.maxY },
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.GlyphOutlinesTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlines.kt \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlinesTest.kt
git commit -m "test(logo): add outline translation and ink-bounds helpers"
```

---

## Task 4: Deterministic PNG encoder

**Files:**
- Create: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/PngEncoder.kt`
- Test: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/PngEncoderTest.kt`

- [ ] **Step 1: Write the failing test**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/PngEncoderTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PngEncoderTest {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    @Test
    fun encodesAValidPngWithUnfilteredScanlines() {
        val pixels = byteArrayOf(
            10, 20, 30, 255,
            40, 50, 60, 128,
        )

        val png = PngEncoder.encodeRgba8(width = 2, height = 1, pixels = pixels)

        assertContentEquals(signature, png.copyOfRange(0, 8))
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunkTypes(png))

        val header = chunkData(png, "IHDR")
        assertEquals(2, ByteBuffer.wrap(header, 0, 4).int)
        assertEquals(1, ByteBuffer.wrap(header, 4, 4).int)
        assertEquals(8, header[8].toInt())
        assertEquals(6, header[9].toInt())

        val inflated = InflaterInputStream(ByteArrayInputStream(chunkData(png, "IDAT"))).readBytes()
        assertContentEquals(byteArrayOf(0) + pixels, inflated)

        assertEquals(0, chunkData(png, "IEND").size)
        assertTrue(ByteBuffer.wrap(header, 12, 1).get().toInt() == 0)
    }

    private fun chunkTypes(png: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var offset = 8
        while (offset < png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            types += String(png, offset + 4, 4, Charsets.US_ASCII)
            crc(png, offset + 4, length + 4).let { expected ->
                assertEquals(expected, ByteBuffer.wrap(png, offset + 8 + length, 4).int, "CRC of ${types.last()}")
            }
            offset += 12 + length
        }
        return types
    }

    private fun chunkData(png: ByteArray, type: String): ByteArray {
        var offset = 8
        while (offset < png.size) {
            val length = ByteBuffer.wrap(png, offset, 4).int
            if (String(png, offset + 4, 4, Charsets.US_ASCII) == type) {
                return png.copyOfRange(offset + 8, offset + 8 + length)
            }
            offset += 12 + length
        }
        error("missing chunk $type")
    }

    private fun crc(bytes: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value.toInt()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.PngEncoderTest"`

Expected: FAIL — unresolved reference `PngEncoder`.

- [ ] **Step 3: Write the implementation**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/PngEncoder.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Minimal deterministic PNG encoder for non-premultiplied RGBA images.
 *
 * Every row uses filter type zero and the stream is compressed with a fixed
 * deflater level, so the same pixels always produce the same file on one JDK.
 * Callers must not compare file bytes across runtimes: `Deflater` depends on the
 * zlib build bundled with the JDK. Determinism of the *pixels* is what the
 * conformance test pins.
 */
internal object PngEncoder {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun encodeRgba8(width: Int, height: Int, pixels: ByteArray): ByteArray {
        require(width > 0 && height > 0) { "PNG dimensions must be positive." }
        require(pixels.size == width.toLong() * height.toLong() * 4L) {
            "Pixel buffer does not match the declared dimensions."
        }
        val raw = ByteArray(height * (1 + width * 4))
        var source = 0
        var target = 0
        repeat(height) {
            raw[target++] = 0
            pixels.copyInto(raw, target, source, source + width * 4)
            source += width * 4
            target += width * 4
        }
        return signature +
            chunk("IHDR", ihdr(width, height)) +
            chunk("IDAT", deflate(raw)) +
            chunk("IEND", ByteArray(0))
    }

    private fun ihdr(width: Int, height: Int): ByteArray = ByteBuffer.allocate(13).apply {
        putInt(width)
        putInt(height)
        put(8)
        put(6)
        put(0)
        put(0)
        put(0)
    }.array()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        return ByteBuffer.allocate(12 + data.size).apply {
            putInt(data.size)
            put(typeBytes)
            put(data)
            putInt(crc.value.toInt())
        }.array()
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DeflaterOutputStream(output, Deflater(Deflater.BEST_COMPRESSION)).use { stream -> stream.write(raw) }
        return output.toByteArray()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.PngEncoderTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/PngEncoder.kt \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/PngEncoderTest.kt
git commit -m "test(logo): add a deterministic RGBA PNG encoder"
```

---

## Task 5: Font opening, shaping and glyph resolution

**Files:**
- Create: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoFonts.kt`
- Test: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoFontsTest.kt`

- [ ] **Step 0: Clarify the `PlacedGlyph` contract in the helpers committed by Task 3**

The Task 3 review found that `PlacedGlyph`'s KDoc was ambiguous about whether `outline` is already translated. This task depends on that invariant, so make it explicit and lock it with assertions before writing new code.

In `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlines.kt`, replace the `PlacedGlyph` declaration and its comment with:

```kotlin
/**
 * One glyph outline placed at a pen position, in the font's own design units.
 *
 * [outline] is already translated to [x]/[y]; those fields record the position
 * for reference only. A consumer applies at most one further uniform shift to
 * the whole word and must never re-apply the pen position.
 */
internal class PlacedGlyph(
    val glyphId: Int,
    val x: Double,
    val y: Double,
    val outline: GlyphOutlineIR,
)
```

and append one sentence to `translated`'s KDoc, after "same floor/ceil rule the outline contract uses.":

```kotlin
 * The result is rebuilt from the legacy flattened command view, so `components`
 * is empty, `limits` are the compatibility limits, and `pointCount` is
 * recomputed.
```

In `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/GlyphOutlinesTest.kt`, add `import kotlin.test.assertFailsWith` and these two tests to the class:

```kotlin
    @Test
    fun roundsTheEnvelopeUpwardsOnTheMaximumSide() {
        val translated = square().translated(dx = 0.2, dy = 0.0)

        // ceil(100.2) = 101; rounding to nearest would give 100 and break conservatism.
        assertEquals(DesignBounds(minX = 0, minY = 0, maxX = 101, maxY = 200), translated.bounds)
    }

    @Test
    fun rejectsEmptyInkBounds() {
        assertFailsWith<IllegalArgumentException> { inkBoundsOf(emptyList()) }
    }
```

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.GlyphOutlinesTest"`

Expected: PASS — 4 tests, 0 failures.

- [ ] **Step 1: Write the failing test**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoFontsTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoFontsTest {
    @Test
    fun resolvesTheBadgeGlyphFromAmiri() {
        KalligraphieLogoFonts.open().use { fonts ->
            val badge = fonts.badgeGlyph()

            assertEquals(1_000, badge.unitsPerEm)
            assertTrue(badge.contours.isNotEmpty(), "the badge glyph must have ink")
            assertTrue(badge.bounds.maxY > badge.bounds.minY, "the badge glyph must have height")
        }
    }

    @Test
    fun shapesAndPlacesTheWordmarkWithIncreasingPenPositions() {
        KalligraphieLogoFonts.open().use { fonts ->
            val wordmark = fonts.wordmark("Kalligraphie")

            assertEquals(1_000, wordmark.unitsPerEm)
            assertTrue(wordmark.glyphs.size >= 10, "every wordmark letter must be shaped")
            assertTrue(wordmark.glyphs.all { glyph -> glyph.outline.contours.isNotEmpty() })

            val positions = wordmark.glyphs.map { glyph -> glyph.x }
            assertTrue(positions.zipWithNext().all { (left, right) -> right > left })

            // Each outline must carry its own pen position: a stacking regression would
            // leave every glyph at the origin and break this ordering.
            assertTrue(
                wordmark.glyphs.last().outline.bounds.minX > wordmark.glyphs.first().outline.bounds.maxX,
                "the last glyph must sit to the right of the first one",
            )
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoFontsTest"`

Expected: FAIL — unresolved reference `KalligraphieLogoFonts`.

- [ ] **Step 3: Write the implementation**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoFonts.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.raster.RasterFixture
import org.graphiks.kalligraphie.raster.fixtureBytes
import org.graphiks.kalligraphie.raster.openRasterFixture
import org.graphiks.kalligraphie.raster.outlineRequirements
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import org.graphiks.kalligraphie.unicode.TextSnapshots
import kotlin.test.assertIs

/**
 * One placed wordmark in the font's own design units.
 *
 * Every [PlacedGlyph.outline] is already translated to its pen position, so the
 * consumer only applies one uniform shift to place the whole word; the pen
 * positions are carried for reference and must never be applied twice.
 */
internal class PlacedWordmark(
    val unitsPerEm: Int,
    val glyphs: List<PlacedGlyph>,
)

/**
 * Opens the two logo fonts and shapes the wordmark through the pinned HarfBuzz backend.
 *
 * Both fonts are instantiated at [LogoLayoutSize] so shaped positions can be
 * converted back into design units with a single exact ratio.
 */
internal class KalligraphieLogoFonts private constructor(
    private val badge: RasterFixture,
    private val wordmarkFont: RasterFixture,
    private val backend: ShapingBackend,
) : AutoCloseable {

    /** Resolves the Amiri `K` outline used inside the badge. */
    fun badgeGlyph(): GlyphOutlineIR = outlineOf(badge, glyphIdOf(badge, 'K'.code))

    /** Shapes [text] and resolves every glyph outline at its pen position. */
    fun wordmark(text: String): PlacedWordmark {
        val run = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.ShapedGlyphRun>>(
            backend.shape(shapingRequest(text)),
        ).value
        val outlines = run.glyphs.map { glyph -> outlineOf(wordmarkFont, glyph.glyphId) }
        val unitsPerEm = outlines.first().unitsPerEm
        val designPerLayout = unitsPerEm.toDouble() / wordmarkFont.instance.key.layoutSize.value.toDouble()

        var pen = 0.0
        val placed = run.glyphs.mapIndexed { index, glyph ->
            val x = pen + glyph.xOffset.value.toDouble() * designPerLayout
            pen += glyph.xAdvance.value.toDouble() * designPerLayout
            val y = glyph.yOffset.value.toDouble() * designPerLayout
            PlacedGlyph(
                glyphId = glyph.glyphId.value,
                x = x,
                y = y,
                outline = outlines[index].translated(x, y),
            )
        }
        return PlacedWordmark(unitsPerEm, placed)
    }

    override fun close() {
        try {
            assertIs<FontOperationResult.Success<Unit>>(backend.close())
        } finally {
            try {
                wordmarkFont.close()
            } finally {
                badge.close()
            }
        }
    }

    private fun shapingRequest(text: String): ShapingRequest {
        val snapshot = TextSnapshots.decodeUtf16(
            version = TextVersion.create(),
            slices = listOf(TextSlice.Utf16(text.toCharArray())),
        ).snapshot
        val range = snapshot.range
        return ShapingRequest(
            snapshot = snapshot,
            itemRange = range,
            contextRange = range,
            font = wordmarkFont.instance,
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            bot = true,
            eot = true,
            featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
            features = emptyList(),
            graphemeClusters = snapshot.scalars.indices.map { scalar ->
                TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
            },
            resourceProfile = ShapingResourceProfile.unbounded,
            cancellationToken = CancellationToken.none,
        )
    }

    private fun glyphIdOf(fixture: RasterFixture, codePoint: Int): GlyphId =
        assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.GlyphResolution>>(
            fixture.instance.resolveGlyph(codePoint),
        ).value.glyphId

    private fun outlineOf(fixture: RasterFixture, glyphId: GlyphId): GlyphOutlineIR =
        when (val representation = assertIs<FontOperationResult.Success<GlyphRepresentation>>(
            fixture.asset.resolveGlyph(FontGlyphRequest(glyphId)),
        ).value) {
            is GlyphRepresentation.Outline -> representation.outline
            is GlyphRepresentation.Empty -> error("glyph ${glyphId.value} has no ink")
            is GlyphRepresentation.Paint -> error("glyph ${glyphId.value} resolved to paint, not an outline")
            is GlyphRepresentation.Bitmap -> error("glyph ${glyphId.value} resolved to a bitmap, not an outline")
        }

    companion object {
        /** Layout size used for both fonts; shaped positions are converted back from this. */
        const val LogoLayoutSize: Float = 1_000f

        const val BadgeFontResource: String = "/fonts/amiri/Amiri-Regular.ttf"
        const val WordmarkFontResource: String = "/fonts/great-vibes/GreatVibes-Regular.ttf"

        fun open(): KalligraphieLogoFonts {
            val badge = openRasterFixture(
                bytes = fixtureBytes(BadgeFontResource),
                requirements = outlineRequirements(),
                layoutSize = LayoutUnit(LogoLayoutSize),
            )
            val wordmark = try {
                openRasterFixture(
                    bytes = fixtureBytes(WordmarkFontResource),
                    requirements = outlineRequirements(),
                    layoutSize = LayoutUnit(LogoLayoutSize),
                )
            } catch (error: Throwable) {
                badge.close()
                throw error
            }
            val backend = assertIs<FontOperationResult.Success<ShapingBackend>>(JvmHarfBuzzShapingBackend.open()).value
            return KalligraphieLogoFonts(badge, wordmark, backend)
        }
    }
}
```

Replace both fully qualified inline types with imports to keep the file readable: add `import org.graphiks.kalligraphie.api.GlyphResolution` and `import org.graphiks.kalligraphie.api.ShapedGlyphRun`, then use `assertIs<FontOperationResult.Success<ShapedGlyphRun>>(...)` and `FontOperationResult.Success<GlyphResolution>`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoFontsTest"`

Expected: PASS. If HarfBuzz fails to load, confirm the test JVM keeps the existing `--enable-native-access=ALL-UNNAMED` argument in `kalligraphie/raster-cpu/build.gradle.kts` (it is already configured for all `Test` tasks).

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoFonts.kt \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoFontsTest.kt
git commit -m "test(logo): open the logo fonts and shape the wordmark"
```

---

## Task 6: Logo composition and rendering

**Files:**
- Create: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogo.kt`
- Test: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoTest.kt`

- [ ] **Step 1: Write the failing test**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoTest {
    @Test
    fun rendersABadgedLockupWithTransparentBackground() {
        KalligraphieLogoFonts.open().use { fonts ->
            val render = KalligraphieLogo.render(fonts, KalligraphieLogo.Ink)

            assertTrue(render.image.width in 1_150..1_250, "width was ${render.image.width}")
            assertTrue(render.image.height in 200..600, "height was ${render.image.height}")

            val pixels = render.image.copyPixels()
            val corner = 0
            assertEquals(0, pixels[corner + 3].toInt() and 0xFF, "the corner must stay transparent")

            val stride = render.image.width * 4
            val centreRow = render.image.height / 2
            var opaque = 0
            for (x in 0 until render.image.width) {
                if ((pixels[centreRow * stride + x * 4 + 3].toInt() and 0xFF) > 200) opaque += 1
            }
            assertTrue(opaque > 0, "the lockup must paint ink on its centre row")
        }
    }

    @Test
    fun lightAndDarkVariantsShareOneAlphaChannel() {
        KalligraphieLogoFonts.open().use { fonts ->
            val light = KalligraphieLogo.render(fonts, KalligraphieLogo.Ink)
            val dark = KalligraphieLogo.render(fonts, KalligraphieLogo.Paper)

            assertEquals(light.image.width, dark.image.width)
            assertEquals(light.image.height, dark.image.height)

            val lightPixels = light.image.copyPixels()
            val darkPixels = dark.image.copyPixels()
            assertTrue(
                lightPixels.indices.filter { index -> index % 4 == 3 }
                    .all { index -> lightPixels[index] == darkPixels[index] },
                "both variants must share one alpha channel",
            )
            assertTrue(
                lightPixels.indices.filter { index -> index % 4 != 3 }
                    .any { index -> lightPixels[index] != darkPixels[index] },
                "the two variants must differ in colour",
            )
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoTest"`

Expected: FAIL — unresolved reference `KalligraphieLogo`.

- [ ] **Step 3: Write the implementation**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogo.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterLimits
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.raster.Rgba8Image
import kotlin.math.roundToInt

/** One rendered variant: the padded image and the layout that produced it. */
internal class LogoRender(
    val image: Rgba8Image,
    val pixelsPerEm: Double,
    val badgeUnitsPerEm: Int,
)

/** Composes and renders the Kalligraphie lockup with the deterministic CPU rasterizer. */
internal object KalligraphieLogo {
    const val Wordmark: String = "Kalligraphie"

    /** Ink colour of the light variant. */
    val Ink: GlyphColor = GlyphColor(0, 0, 0)

    /** Paper colour used to knock the badge letter out of the filled square. */
    val Paper: GlyphColor = GlyphColor(255, 255, 255)

    /** Ink width target before the margin is added, so the final width is close to 1200 px. */
    private const val InkTargetWidthPx = 1_152

    /** Uniform transparent margin added around the tight ink canvas. */
    private const val MarginPx = 24

    /** Share of the badge square occupied by the letter's ink height. */
    private const val BadgeHeightRatio = 0.52

    /** Gap after the badge, as a share of the badge side. */
    private const val BadgeGapRatio = 0.5

    /** Corner radius, as a share of the badge side. */
    private const val CornerRadiusRatio = 0.22

    /** Cubic circle-arc constant. */
    private const val Kappa = 0.5522847498307936

    private val Limits = RasterLimits(
        maxWidthPx = 2_048,
        maxHeightPx = 1_024,
        maxPixelsPerImage = 2_097_152,
        maxContours = 4_096,
        maxTotalPoints = 262_144,
        maxPaintNodes = 64,
        maxPaintDepth = 8,
    )

    /** Renders the lockup with [ink] as the dark colour and its opposite as the knockout. */
    fun render(fonts: KalligraphieLogoFonts, ink: GlyphColor): LogoRender {
        val paper = GlyphColor(
            red = 255 - ink.red,
            green = 255 - ink.green,
            blue = 255 - ink.blue,
        )
        val layout = layout(fonts, ink, paper)
        val request = PaintRasterRequest(
            pixelsPerEm = layout.pixelsPerEm,
            unitsPerEm = layout.badgeUnitsPerEm,
            limits = Limits,
        )
        val rasterized = when (val result = GlyphRasterizer.rasterizePaint(layout.graph, request)) {
            is RasterResult.Success -> result.value
            is RasterResult.Failure -> error("logo rasterization failed: ${result.diagnostics}")
        }
        require(rasterized.width > 0 && rasterized.height > 0) { "the logo rendered no ink" }
        return LogoRender(
            image = pad(rasterized, MarginPx),
            pixelsPerEm = layout.pixelsPerEm,
            badgeUnitsPerEm = layout.badgeUnitsPerEm,
        )
    }

    /** Composes the paint graph and the scale that maps its ink to the target width. */
    internal fun layout(fonts: KalligraphieLogoFonts, ink: GlyphColor, paper: GlyphColor): LogoLayout {
        val badgeGlyph = fonts.badgeGlyph()
        val badgeUpem = badgeGlyph.unitsPerEm
        val wordmark = fonts.wordmark(Wordmark)

        val badgeInkHeight = (badgeGlyph.bounds.maxY - badgeGlyph.bounds.minY).toDouble()
        val side = badgeInkHeight / BadgeHeightRatio
        val centreX = (badgeGlyph.bounds.minX + badgeGlyph.bounds.maxX) / 2.0
        val centreY = (badgeGlyph.bounds.minY + badgeGlyph.bounds.maxY) / 2.0
        val left = centreX - side / 2.0
        val bottom = centreY - side / 2.0
        val right = left + side

        val wordInk = inkBoundsOf(wordmark.glyphs)
        val badgePerWordUnit = badgeUpem.toDouble() / wordmark.unitsPerEm.toDouble()
        val wordUnitPerBadge = 1.0 / badgePerWordUnit
        val wordLeft = right + side * BadgeGapRatio
        val wordCentreY = (wordInk.minY + wordInk.maxY) / 2.0
        val wordDx = wordLeft * wordUnitPerBadge - wordInk.minX
        val wordDy = centreY * wordUnitPerBadge - wordCentreY

        val placedWordLeft = (wordInk.minX + wordDx) * badgePerWordUnit
        val placedWordRight = (wordInk.maxX + wordDx) * badgePerWordUnit
        val unionWidth = maxOf(right, placedWordRight) - minOf(left, placedWordLeft)
        val pixelsPerEm = InkTargetWidthPx.toDouble() * badgeUpem / unionWidth

        val nodes = mutableListOf<GlyphPaintNode>()
        nodes += GlyphPaintNode.Path(roundedSquarePath(left, bottom, side), ink)
        nodes += GlyphPaintNode.SolidOutline(badgeGlyph, paper)
        wordmark.glyphs.forEach { glyph ->
            nodes += GlyphPaintNode.SolidOutline(
                glyph.outline.translated(wordDx, wordDy),
                ink,
            )
        }
        val root = nodes.size
        nodes += GlyphPaintNode.Group(children = (0 until root).toList())
        return LogoLayout(
            pixelsPerEm = pixelsPerEm,
            badgeUnitsPerEm = badgeUpem,
            graph = GlyphPaintIR(schemaVersion = 1, rootNode = root, nodes = nodes),
        )
    }

    private fun roundedSquarePath(left: Double, bottom: Double, side: Double): GlyphPaintPath {
        val right = left + side
        val top = bottom + side
        val radius = side * CornerRadiusRatio
        val handle = radius * Kappa
        return GlyphPaintPath(
            listOf(
                GlyphPaintPathCommand.MoveTo(left + radius, top),
                GlyphPaintPathCommand.LineTo(right - radius, top),
                GlyphPaintPathCommand.CubicTo(right - radius + handle, top, right, top - radius + handle, right, top - radius),
                GlyphPaintPathCommand.LineTo(right, bottom + radius),
                GlyphPaintPathCommand.CubicTo(right, bottom + radius - handle, right - radius + handle, bottom, right - radius, bottom),
                GlyphPaintPathCommand.LineTo(left + radius, bottom),
                GlyphPaintPathCommand.CubicTo(left + radius - handle, bottom, left, bottom + radius - handle, left, bottom + radius),
                GlyphPaintPathCommand.LineTo(left, top - radius),
                GlyphPaintPathCommand.CubicTo(left, top - radius + handle, left + radius - handle, top, left + radius, top),
                GlyphPaintPathCommand.Close,
            ),
        )
    }

    /** Returns a new image with [margin] transparent pixels on every side. */
    private fun pad(image: Rgba8Image, margin: Int): Rgba8Image {
        require(margin >= 0) { "margin must not be negative." }
        val width = image.width + margin * 2
        val height = image.height + margin * 2
        val source = image.copyPixels()
        val target = ByteArray(width * height * 4)
        for (y in 0 until image.height) {
            source.copyInto(
                target,
                destinationOffset = ((y + margin) * width + margin) * 4,
                startIndex = y * image.width * 4,
                endIndex = (y + 1) * image.width * 4,
            )
        }
        return Rgba8Image(width, height, 0, 0, target)
    }
}

/** One composed logo: the paint graph and the scale that maps its ink to pixels. */
internal class LogoLayout(
    val pixelsPerEm: Double,
    val badgeUnitsPerEm: Int,
    val graph: GlyphPaintIR,
)

/** Encodes [image] as PNG bytes. */
internal fun LogoRender.toPng(): ByteArray =
    PngEncoder.encodeRgba8(image.width, image.height, image.copyPixels())

/** Returns the SHA-256 digest of [image]'s pixel buffer. */
internal fun LogoRender.pixelDigest(): String =
    org.graphiks.kalligraphie.raster.sha256(image.copyPixels())
```

The file must compile with no unused imports: remove the `kotlin.math.roundToInt` and `DesignBounds` imports if nothing else in the file uses them.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogo.kt \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoTest.kt
git commit -m "test(logo): compose and render the Kalligraphie lockup"
```

---

## Task 7: Opt-in runner and Gradle task

**Files:**
- Create: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoDumpTest.kt`
- Modify: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/RasterFixtureSupport.kt` (add `rasterRepositoryRoot`)
- Modify: `kalligraphie/raster-cpu/build.gradle.kts`

- [ ] **Step 1: Add the repository-root helper**

In `RasterFixtureSupport.kt`, add at the end of the file:

```kotlin
/** Locates the repository root by walking up from the test working directory. */
internal fun rasterRepositoryRoot(): java.nio.file.Path {
    var candidate: java.nio.file.Path? = java.nio.file.Path.of("").toAbsolutePath().normalize()
    while (candidate != null) {
        if (java.nio.file.Files.exists(candidate.resolve(".git"))) return candidate
        candidate = candidate.parent
    }
    error("Could not locate the repository root from the test working directory.")
}
```

- [ ] **Step 2: Write the runner**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoDumpTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import java.nio.file.Files
import java.nio.file.Path
import org.graphiks.kalligraphie.raster.rasterRepositoryRoot
import org.graphiks.kalligraphie.raster.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoDumpTest {
    @Test
    fun writesTheLogoAssetsOnlyWhenExplicitlyEnabled() {
        if (System.getenv("KALLIGRAPHIE_LOGO") != "true") return
        val assets = rasterRepositoryRoot().resolve("docs/assets")
        Files.createDirectories(assets)

        val commit = gitCommit()
        val outputs = linkedMapOf<String, LogoOutput>()
        KalligraphieLogoFonts.open().use { fonts ->
            outputs["kalligraphie-logo-light.png"] = render(fonts, KalligraphieLogo.Ink)
            outputs["kalligraphie-logo-dark.png"] = render(fonts, KalligraphieLogo.Paper)
            write(assets, outputs, commit)
        }

        for (output in outputs.values) {
            assertTrue(output.image.width > 0 && output.image.height > 0)
        }
    }

    private fun render(fonts: KalligraphieLogoFonts, ink: org.graphiks.kalligraphie.api.GlyphColor): LogoOutput {
        val first = KalligraphieLogo.render(fonts, ink)
        val second = KalligraphieLogo.render(fonts, ink)
        assertEquals(
            sha256(first.image.copyPixels()),
            sha256(second.image.copyPixels()),
            "repeated renders must produce identical pixels",
        )
        return LogoOutput(png = first.toPng(), image = first.image, pixelDigest = first.pixelDigest())
    }

    private fun write(
        assets: Path,
        outputs: Map<String, LogoOutput>,
        commit: String,
    ) {
        val manifest = buildString {
            appendLine("# Kalligraphie logo manifest")
            appendLine()
            appendLine("- git commit: `$commit`")
            appendLine("- wordmark: `${KalligraphieLogo.Wordmark}`")
            appendLine("- fonts: ${KalligraphieLogoFonts.BadgeFontResource}, ${KalligraphieLogoFonts.WordmarkFontResource}")
            appendLine("- layout size: ${KalligraphieLogoFonts.LogoLayoutSize}")
            appendLine()
            appendLine("## Files")
            appendLine()
            outputs.forEach { (name, output) ->
                Files.write(assets.resolve(name), output.png)
                appendLine(
                    "- $name: sha256=${sha256(output.png)} bytes=${output.png.size} " +
                        "rgba-sha256=${output.pixelDigest}",
                )
            }
        }
        Files.writeString(assets.resolve("kalligraphie-logo.manifest.md"), manifest)
    }

    private fun gitCommit(): String {
        val process = ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0 && output.matches(Regex("[0-9a-f]{40}"))) {
            "Could not identify the dumped commit: $output"
        }
        return output
    }

    private class LogoOutput(
        val png: ByteArray,
        val image: org.graphiks.kalligraphie.raster.Rgba8Image,
        val pixelDigest: String,
    )
}
```

Add the import `org.graphiks.kalligraphie.api.GlyphColor` and use it in `render`'s signature instead of the fully qualified type.

- [ ] **Step 3: Wire the Gradle task**

In `kalligraphie/raster-cpu/build.gradle.kts`, extend the exclusion and register the task:

```kotlin
val rasterDumpClass = "org.graphiks.kalligraphie.raster.RasterDumpRunnerTest"
val logoDumpClass = "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoDumpTest"
val rasterJvmTestTask = tasks.named<Test>("jvmTest")

rasterJvmTestTask.configure {
    filter.excludeTestsMatching(rasterDumpClass)
    filter.excludeTestsMatching(logoDumpClass)
}
```

and after the existing `rasterDumps` registration:

```kotlin
tasks.register<Test>("renderLogo") {
    group = "verification"
    description = "Regenerates the README logo assets in docs/assets from the deterministic CPU rasterizer."
    testClassesDirs = rasterJvmTestTask.get().testClassesDirs
    classpath = rasterJvmTestTask.get().classpath
    filter.includeTestsMatching("$logoDumpClass.writesTheLogoAssetsOnlyWhenExplicitlyEnabled")
    outputs.upToDateWhen { false }
}
```

- [ ] **Step 4: Confirm the runner is inert without the opt-in**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest`

Expected: PASS, and `git status --short` shows no new files under `docs/assets/`.

- [ ] **Step 5: Generate the assets**

Run:

```bash
env KALLIGRAPHIE_LOGO=true ./gradlew :kalligraphie:raster-cpu:renderLogo
```

Expected: PASS, and `docs/assets/` now contains `kalligraphie-logo-light.png`, `kalligraphie-logo-dark.png`, and `kalligraphie-logo.manifest.md`.

```bash
ls -l docs/assets
git status --short docs/assets
```

- [ ] **Step 6: Look at the result before committing**

Open both PNGs in the OpenChamber browser panel and confirm the lockup reads correctly: filled rounded square, knocked-out `K`, joined `Kalligraphie` script, nothing clipped. Record what needs tuning for Task 10.

- [ ] **Step 7: Commit**

```bash
git add kalligraphie/raster-cpu/build.gradle.kts \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/RasterFixtureSupport.kt \
        kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoDumpTest.kt \
        docs/assets
git commit -m "feat(logo): render the README logo assets with the CPU rasterizer"
```

---

## Task 8: Sealed fingerprints and committed-file integrity

**Files:**
- Create: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoConformanceTest.kt`

- [ ] **Step 1: Write the test with deliberately wrong digests**

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoConformanceTest.kt` exactly as shown in Step 2, using `"unsealed"` for both fingerprint constants.

- [ ] **Step 2: Read the real digests from the failure, then seal them**

Run:

```bash
./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoConformanceTest"
```

Expected: FAIL, and each failure prints the observed digest, for example `expected:<unsealed> but was:<3f...>`. Paste the observed value into the corresponding constant, re-run until both fingerprint tests pass. Then run `cat docs/assets/kalligraphie-logo.manifest.md` and confirm the sealed values equal the two `rgba-sha256=` entries.

Create `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoConformanceTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster.logo

import java.nio.file.Files
import org.graphiks.kalligraphie.raster.rasterRepositoryRoot
import org.graphiks.kalligraphie.raster.sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KalligraphieLogoConformanceTest {
    private val assets = rasterRepositoryRoot().resolve("docs/assets")

    @Test
    fun theLightVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            val render = KalligraphieLogo.render(fonts, KalligraphieLogo.Ink)
            assertEquals("unsealed", render.pixelDigest())
        }
    }

    @Test
    fun theDarkVariantStillMatchesItsSealedFingerprint() {
        KalligraphieLogoFonts.open().use { fonts ->
            val render = KalligraphieLogo.render(fonts, KalligraphieLogo.Paper)
            assertEquals("unsealed", render.pixelDigest())
        }
    }

    @Test
    fun theCommittedImagesMatchTheManifest() {
        val manifest = Files.readString(assets.resolve("kalligraphie-logo.manifest.md"))
        val recorded = manifest.lines().mapNotNull { line ->
            ENTRY.matchEntire(line.trim())?.let { match ->
                match.groupValues[1] to (match.groupValues[2] to match.groupValues[3])
            }
        }.toMap()

        assertEquals(setOf("kalligraphie-logo-light.png", "kalligraphie-logo-dark.png"), recorded.keys)
        recorded.forEach { (name, digests) ->
            val bytes = Files.readAllBytes(assets.resolve(name))
            assertEquals(digests.first, sha256(bytes), "$name must match its manifest file digest")
            assertEquals(
                digests.second,
                renderedPixelDigest(name),
                "$name must still render to the pixels its manifest records",
            )
        }
    }

    @Test
    fun theGreatVibesFixtureMatchesItsProvenance() {
        val provenance = Files.readString(
            rasterRepositoryRoot().resolve(
                "kalligraphie/raster-cpu/src/jvmTest/resources/fonts/great-vibes/PROVENANCE.md",
            ),
        )
        assertTrue(
            provenance.contains("8d509802186f1b51572531ecf313e8098f9a5bfdfaca93f0c9b34467f9982d15"),
            "the provenance record must pin the bundled font digest",
        )
        assertTrue(
            provenance.contains("61093a21f5e63dedf54222b3c09997e54c0fe43e3851d21386e02ddcbc246d49"),
            "the provenance record must pin the bundled licence digest",
        )
    }

    private fun renderedPixelDigest(name: String): String {
        KalligraphieLogoFonts.open().use { fonts ->
            val ink = if (name.endsWith("light.png")) KalligraphieLogo.Ink else KalligraphieLogo.Paper
            return KalligraphieLogo.render(fonts, ink).pixelDigest()
        }
    }

    private companion object {
        val ENTRY = Regex("""^- (kalligraphie-logo-(?:light|dark)\.png): sha256=([0-9a-f]{64}) bytes=\d+ rgba-sha256=([0-9a-f]{64})$""")
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoConformanceTest"`

Expected: PASS. A mismatch means Step 1's digests were copied incorrectly, or the assets were not regenerated after a code change.

- [ ] **Step 4: Verify the guard actually fails on drift**

Temporarily edit one byte of `docs/assets/kalligraphie-logo-dark.png` (for example with `printf '\x00' | dd of=docs/assets/kalligraphie-logo-dark.png bs=1 seek=200 conv=notrunc`), re-run the test, and confirm `theCommittedImagesMatchTheManifest` FAILS. Restore the file with `git checkout -- docs/assets/kalligraphie-logo-dark.png`.

- [ ] **Step 5: Run the whole module check**

Run: `./gradlew :kalligraphie:raster-cpu:check`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogoConformanceTest.kt
git commit -m "test(logo): seal the logo fingerprints and commit integrity"
```

---

## Task 9: README picture block

**Files:**
- Modify: `README.md` (insert after line 1, `# Kalligraphie`)

- [ ] **Step 1: Insert the block**

Insert directly under the `# Kalligraphie` heading and before the badge row:

```html
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/kalligraphie-logo-dark.png">
  <img alt="Kalligraphie" src="docs/assets/kalligraphie-logo-light.png" width="600">
</picture>

```

- [ ] **Step 2: Verify the paths resolve**

Run: `ls -l docs/assets/kalligraphie-logo-light.png docs/assets/kalligraphie-logo-dark.png`

Expected: both files exist. Relative `docs/assets/...` paths are what GitHub resolves for a README at the repository root.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs(readme): show the Kalligraphie logo"
```

---

## Task 10: Visual checkpoint and tuning

**Files:**
- Modify: `kalligraphie/raster-cpu/src/jvmTest/kotlin/org/graphiks/kalligraphie/raster/logo/KalligraphieLogo.kt` (constants only)

- [ ] **Step 1: Review the committed PNGs in the browser**

Open `docs/assets/kalligraphie-logo-light.png` and `docs/assets/kalligraphie-logo-dark.png`, and confirm against the approved mockup:

- the corner radius reads as the approved rounded square (raise or lower `CornerRadiusRatio` from `0.22`);
- the margin does not look cramped (raise or lower `MarginPx` from `24`);
- the `K` fills the square without crowding it (raise or lower `BadgeHeightRatio` from `0.52`);
- the gap after the badge is balanced (raise or lower `BadgeGapRatio` from `0.5`).
- the `Amiri-Regular` `K` carries enough weight in the filled square. If it does not, add `Amiri-Bold.ttf` as a second documented fixture in `kalligraphie/raster-cpu/src/jvmTest/resources/fonts/amiri/` (font, `OFL.txt`, `PROVENANCE.md`), point `BadgeFontResource` at it, and repeat Step 3.

- [ ] **Step 2: Regenerate and re-seal after any change**

```bash
env KALLIGRAPHIE_LOGO=true ./gradlew :kalligraphie:raster-cpu:renderLogo
cat docs/assets/kalligraphie-logo.manifest.md
```

Update the two `rgba-sha256` constants in `KalligraphieLogoConformanceTest` with the new values, then:

Run: `./gradlew :kalligraphie:raster-cpu:check`

Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A kalligraphie/raster-cpu docs/assets
git commit -m "style(logo): tune the lockup geometry"
```

---

## Final verification

- [ ] `./gradlew :kalligraphie:raster-cpu:check` passes.
- [ ] `./gradlew check` passes for the whole repository.
- [ ] `git status --short` shows no generated file left behind and `.superpowers/` remains untracked (the user adds it to `.gitignore` if they want).
- [ ] `docs/assets/` contains exactly the two PNGs and the manifest, and the README displays them.
