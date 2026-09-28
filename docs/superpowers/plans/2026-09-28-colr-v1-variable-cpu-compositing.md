# COLR v1 variable CPU compositing — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the catalogue's CPU compositor compose `GlyphClip`, `Solid`, `LinearGradient`, and `Transform`, then promote `color.colr-v1-variable` to a `Supported` golden scene with a committed fingerprint.

**Architecture:** `PaintCompositor` gains a context (`clips`, `transform`, `unitsPerEm`) propagated through the graph. Clipping is an exact per-sample intersection at the rasterizer's sixteen fixed sub-pixel positions, not a product of A8 masks. An unbounded `Solid`/gradient is bounded by the clips enclosing it. A linear gradient is shaded in a committed integer sRGB↔linear transfer, so bytes are identical on JVM, ART, and Native. A new E2E paint sheet renders the fixture's three glyphs at `wght` 400 and 900 and asserts per raw raster.

**Tech Stack:** Kotlin Multiplatform, Gradle, `kotlin.test`, the repo's `:kalligraphie:api` and `:kalligraphie:e2e` harnesses. No new dependency.

**Spec:** `docs/superpowers/specs/2026-09-28-colr-v1-variable-cpu-compositing-design.md`

## Global Constraints

- Package of every raster change: `org.graphiks.kalligraphie.raster`; every new type is `internal` unless the spec names it public.
- No new public `RasterLimits` field. Limits are enforced before allocation, and every refusal throws `RasterLimitReached` / `RasterRequestRejected` (both internal), which `GlyphRasterizer.runRaster` converts to typed diagnostics.
- Determinism: no `pow`, `exp`, `StrictMath`, locale, or platform math at raster time. Basic `Double` `+ - * /` and integer arithmetic only.
- The existing legacy integer sRGB `SOURCE_OVER` for `Group` is preserved; COLR v1 linear-light blending is out of scope.
- Golden verification has no numeric tolerance and runs on JVM, Android ART, and iOS. Any non-deterministic byte fails CI.
- Commit type/scope must satisfy `.github/contributing-policy.toml`; after Task 1 the scope `raster-cpu` is registered.
- Run `./gradlew check` before the final task is considered done.

## Review Focus

- A `Solid` or `LinearGradient` root with no `clipBounds` must be refused typed, never rendered unbounded and never throw uncaught.
- The fixture's `wght=900` gradient stops start at `0.25` and end at `1.125`: `REPEAT` must period over `0.875`, not over `[0,1]`.
- A `GlyphClip` whose outline is empty (no contours) must paint nothing, not fall through to "unbounded".
- A `LinearGradient` declaring `SRGB` or `UNPREMULTIPLIED` must be refused typed, not silently shaded as `LINEAR_SRGB`/`PREMULTIPLIED`.
- A deep chain of `Transform`/`GlyphClip` nodes must hit `maxPaintNodes`/`maxPaintDepth` as `LimitExceeded` with no partial image.

---

### Task 1: Register the `raster-cpu` commit scope

**Files:**
- Modify: `.github/contributing-policy.toml`
- Modify: `CONTRIBUTING.md` (scope table)

**Interfaces:**
- Consumes: nothing.
- Produces: the `raster-cpu` scope, used by every later commit.

- [ ] **Step 1: Add the scope to the machine policy**

In `.github/contributing-policy.toml`, add `"raster-cpu",` to `allowed_scopes` immediately after `"e2e",`:

```toml
    "conformance",
    "e2e",
    "raster-cpu",
    "bench",
```

- [ ] **Step 2: Add the scope row to the CONTRIBUTING table**

In `CONTRIBUTING.md`, in the scopes table, add a row after the `e2e` row:

```markdown
| `e2e` | End-to-end golden fingerprint verification in `:kalligraphie:e2e` |
| `raster-cpu` | Deterministic CPU rasterization used by tests and demonstrations in `:kalligraphie:raster-cpu` |
| `platform` | Platform integration across the `kalligraphie/platform/` module family |
```

- [ ] **Step 3: Verify the policy parses and accepts the scope**

Run: `python3 .github/scripts/validate_pr_policy.py --help`
Expected: usage printed, exit 0. Then grep to confirm the scope landed:

Run: `grep -n "raster-cpu" .github/contributing-policy.toml CONTRIBUTING.md`
Expected: one match in each file.

- [ ] **Step 4: Commit**

```bash
git add .github/contributing-policy.toml CONTRIBUTING.md
git commit -m "chore(ci): register the raster-cpu commit scope"
```

---

### Task 2: Committed sRGB transfer table

**Files:**
- Create: `scripts/raster/generate_srgb_transfer.py`
- Create (generated): `kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/SrgbTransfer.kt`
- Test: `kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/SrgbTransferTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `internal object SrgbTransfer` with `fun toLinear(channel8: Int): Int` (0..255 → 0..65535) and `fun toSrgb(linear16: Int): Int` (0..65535 → 0..255).

- [ ] **Step 1: Write the generator script**

Create `scripts/raster/generate_srgb_transfer.py`:

```python
#!/usr/bin/env python3
"""Generates the committed sRGB<->linear-light transfer table for :kalligraphie:raster-cpu.

The table is data, not computation: committing it keeps the rasterizer free of pow()/exp() so
identical inputs produce identical bytes on the JVM, Android ART and Kotlin/Native.
"""

from __future__ import annotations

import pathlib

OUT = pathlib.Path(__file__).resolve().parents[2] / (
    "kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/SrgbTransfer.kt"
)


def to_linear(channel: int) -> int:
    value = channel / 255.0
    linear = value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4
    return int(round(linear * 65535))


def main() -> None:
    table = [to_linear(c) for c in range(256)]
    values = ", ".join(str(v) for v in table)
    header = (
        "// Generated by scripts/raster/generate_srgb_transfer.py; do not edit by hand.\n"
        "package org.graphiks.kalligraphie.raster\n\n"
        "/** Committed sRGB<->linear-light transfer; see the generator for the exact formula. */\n"
        "internal object SrgbTransfer {\n"
        "    /** 16-bit linear-light value of each of the 256 sRGB channel literals. */\n"
        "    private val LINEAR: IntArray = intArrayOf(\n        "
    )
    body = "\n        ".join(values.split(", "))
    footer = (
        ",\n    )\n\n"
        "    /** Returns the 16-bit linear-light value of one 8-bit sRGB channel. */\n"
        "    fun toLinear(channel8: Int): Int = LINEAR[channel8]\n\n"
        "    /**\n"
        "     * Returns the nearest 8-bit sRGB channel for [linear16], choosing the highest sRGB\n"
        "     * channel whose linear value does not exceed it (a fixed, monotone tie rule).\n"
        "     */\n"
        "    fun toSrgb(linear16: Int): Int {\n"
        "        var low = 0\n"
        "        var high = 255\n"
        "        while (low < high) {\n"
        "            val middle = (low + high + 1) ushr 1\n"
        "            if (LINEAR[middle] <= linear16) low = middle else high = middle - 1\n"
        "        }\n"
        "        return low\n"
        "    }\n"
        "}\n"
    )
    OUT.write_text(header + body + footer, encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Generate the table**

Run: `python3 scripts/raster/generate_srgb_transfer.py`
Expected: `SrgbTransfer.kt` created with `LINEAR[0] == 0` and `LINEAR[255] == 65535`.

- [ ] **Step 3: Write the failing test**

Create `SrgbTransferTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals

class SrgbTransferTest {
    @Test
    fun theEndpointsAreExact() {
        assertEquals(0, SrgbTransfer.toLinear(0))
        assertEquals(65535, SrgbTransfer.toLinear(255))
        assertEquals(0, SrgbTransfer.toSrgb(0))
        assertEquals(255, SrgbTransfer.toSrgb(65535))
    }

    @Test
    fun theTransferIsMonotone() {
        for (channel in 1..255) {
            assert(SrgbTransfer.toLinear(channel - 1) <= SrgbTransfer.toLinear(channel))
        }
        for (linear in 1..65535) {
            assert(SrgbTransfer.toSrgb(linear - 1) <= SrgbTransfer.toSrgb(linear))
        }
    }

    @Test
    fun halfLinearIsBrighterThanHalfSrgb() {
        // sRGB encoding is non-linear: 50% linear light sits above the 0x80 sRGB literal.
        assert(SrgbTransfer.toSrgb(32768) > 0x80)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.SrgbTransferTest"`
Expected: FAIL to compile (SrgbTransfer not defined) — if the generator has not been run yet.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.SrgbTransferTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add scripts/raster/generate_srgb_transfer.py \
    kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/SrgbTransfer.kt \
    kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/SrgbTransferTest.kt
git commit -m "feat(raster-cpu): add the committed sRGB transfer table"
```

---

### Task 3: Affine matrix in the contour flattener

**Files:**
- Modify: `kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/ContourFlattener.kt`
- Test: `kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/ContourFlattenerTransformTest.kt`

**Interfaces:**
- Consumes: `org.graphiks.kalligraphie.api.GlyphAffineTransform`.
- Produces: `flattenOutline(..., transform: GlyphAffineTransform = GlyphAffineTransform.IDENTITY)` and `flattenPath(..., transform: GlyphAffineTransform = GlyphAffineTransform.IDENTITY)`, applying `transform` in design space before `scale`/`origin`.

- [ ] **Step 1: Write the failing test**

Create `ContourFlattenerTransformTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class ContourFlattenerTransformTest {
    @Test
    fun aTranslationMovesFlattenedPoints() {
        val square = GlyphContour(
            listOf(
                GlyphOutlineCommand.MoveTo(0.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 2.0),
                GlyphOutlineCommand.LineTo(0.0, 2.0),
                GlyphOutlineCommand.Close,
            ),
        )
        val contours = ContourFlattener.flattenOutline(
            contours = listOf(square),
            scale = 1.0,
            originX = 0.0,
            originY = 0.0,
            limits = RasterLimits.Default,
            transform = GlyphAffineTransform(xx = 1.0, yx = 0.0, xy = 0.0, yy = 1.0, dx = 10.0, dy = 0.0),
        )
        assertEquals(10.0, contours.single().points.first().x)
    }

    @Test
    fun theDefaultTransformLeavesPointsUnchanged() {
        val square = GlyphContour(
            listOf(
                GlyphOutlineCommand.MoveTo(0.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 0.0),
                GlyphOutlineCommand.LineTo(2.0, 2.0),
                GlyphOutlineCommand.Close,
            ),
        )
        val plain = ContourFlattener.flattenOutline(listOf(square), 1.0, 0.0, 0.0, RasterLimits.Default)
        val explicit = ContourFlattener.flattenOutline(
            listOf(square), 1.0, 0.0, 0.0, RasterLimits.Default, GlyphAffineTransform.IDENTITY,
        )
        assertEquals(plain.single().points, explicit.single().points)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.ContourFlattenerTransformTest"`
Expected: FAIL to compile (`transform` parameter and/or `GlyphAffineTransform.IDENTITY` not defined).

- [ ] **Step 3: Confirm whether `GlyphAffineTransform.IDENTITY` exists**

Run: `grep -n "IDENTITY" kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/GlyphPaintRepresentation.kt`
Expected: no match is likely. If absent, add to `GlyphAffineTransform` in Task 3's implementation:

```kotlin
    /** The transform that leaves every point unchanged. */
    public companion object {
        /** Identity transform `(1,0,0,1,0,0)`. */
        public val IDENTITY: GlyphAffineTransform = GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
    }
```

Place it at the end of the `GlyphAffineTransform` body and commit it in this task.

- [ ] **Step 4: Implement the transform in the flattener**

Add a private mapper and thread the transform through both public functions and the two `toEdge` converters. Replace `x * scale + originX` with `device(x, y, transform, scale, origin).x` and the analogous `y`:

```kotlin
    private fun deviceX(x: Double, y: Double, transform: GlyphAffineTransform, scale: Double, originX: Double): Double =
        (transform.xx * x + transform.xy * y + transform.dx) * scale + originX

    private fun deviceY(x: Double, y: Double, transform: GlyphAffineTransform, scale: Double, originY: Double): Double =
        (transform.yx * x + transform.yy * y + transform.dy) * scale + originY
```

Change the signatures:

```kotlin
    fun flattenOutline(
        contours: List<GlyphContour>,
        scale: Double,
        originX: Double,
        originY: Double,
        limits: RasterLimits,
        transform: GlyphAffineTransform = GlyphAffineTransform.IDENTITY,
    ): List<FlatContour>
```

```kotlin
    fun flattenPath(
        commands: List<GlyphPaintPathCommand>,
        scale: Double,
        originX: Double,
        originY: Double,
        limits: RasterLimits,
        transform: GlyphAffineTransform = GlyphAffineTransform.IDENTITY,
    ): List<FlatContour>
```

Pass `transform` into `toEdge`; update `GlyphOutlineCommand.toEdge` and `GlyphPaintPathCommand.toEdge` to take it and use `deviceX`/`deviceY`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.ContourFlattener*"`
Expected: PASS (existing flatten tests plus the new transform test).

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/ContourFlattener.kt \
    kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/ContourFlattenerTransformTest.kt \
    kalligraphie/api/src/commonMain/kotlin/org/graphiks/kalligraphie/api/GlyphPaintRepresentation.kt
git commit -m "feat(raster-cpu): apply an affine transform while flattening"
```

---

### Task 4: Exact clipped coverage

**Files:**
- Modify: `kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/CoverageRaster.kt`
- Test: `kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/CoverageRasterClipTest.kt`

**Interfaces:**
- Consumes: `FlatContour`, `FlatPoint`.
- Produces: `CoverageRaster.rasterizeLeaf(geometry: List<FlatContour>?, clips: List<List<FlatContour>>, left, top, width, height): A8Image`, where `geometry == null` means "no own shape" (an unbounded fill), an empty non-null geometry paints nothing, and a sample is inside only when it is inside the geometry (or the geometry is null) and inside every clip.

- [ ] **Step 1: Write the failing test**

Create `CoverageRasterClipTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster

import kotlin.test.Test
import kotlin.test.assertEquals

class CoverageRasterClipTest {
    private fun square(left: Double, top: Double, right: Double, bottom: Double): List<FlatContour> =
        listOf(
            FlatContour(
                listOf(
                    FlatPoint(left, top),
                    FlatPoint(right, top),
                    FlatPoint(right, bottom),
                    FlatPoint(left, bottom),
                ),
            ),
        )

    @Test
    fun anUnboundedFillIsFullyCoveredWhereInsideEveryClip() {
        val clip = listOf(square(0.0, 0.0, 4.0, 4.0))
        val image = CoverageRaster.rasterizeLeaf(null, listOf(clip), 0, 0, 4, 4)
        assertEquals(255, image[2, 2])
    }

    @Test
    fun anUnboundedFillIsEmptyOutsideTheClip() {
        val clip = listOf(square(0.0, 0.0, 2.0, 2.0))
        val image = CoverageRaster.rasterizeLeaf(null, listOf(clip), 0, 0, 4, 4)
        assertEquals(0, image[3, 3])
    }

    @Test
    fun nestedIdenticalClipsDoNotSquareTheEdge() {
        val clip = listOf(square(0.0, 0.0, 2.0, 2.0))
        val once = CoverageRaster.rasterizeLeaf(null, listOf(clip), 0, 0, 2, 2)
        val twice = CoverageRaster.rasterizeLeaf(null, listOf(clip, clip), 0, 0, 2, 2)
        assertEquals(once[0, 0], twice[0, 0])
    }

    @Test
    fun anEmptyBoundedGeometryPaintsNothing() {
        val clip = listOf(square(0.0, 0.0, 4.0, 4.0))
        val image = CoverageRaster.rasterizeLeaf(emptyList(), listOf(clip), 0, 0, 4, 4)
        assertEquals(0, image[2, 2])
    }

    @Test
    fun aBoundedShapeIsIntersectedWithTheClip() {
        val shape = square(0.0, 0.0, 4.0, 4.0)
        val clip = listOf(square(0.0, 0.0, 2.0, 2.0))
        val image = CoverageRaster.rasterizeLeaf(shape, listOf(clip), 0, 0, 4, 4)
        assertEquals(255, image[1, 1])
        assertEquals(0, image[3, 3])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.CoverageRasterClipTest"`
Expected: FAIL to compile (`rasterizeLeaf` not defined).

- [ ] **Step 3: Implement `rasterizeLeaf`**

Add to `CoverageRaster`, reusing the existing sample offsets and `windingNumber`:

```kotlin
    /**
     * Rasterizes the coverage of a leaf restricted to [clips]: a sample counts when it is inside
     * the leaf's winding (or the leaf is [geometry] `null`, an unbounded fill) and inside every
     * clip. Intersecting at the samples is exact and never squares a shared edge.
     */
    fun rasterizeLeaf(
        geometry: List<FlatContour>?,
        clips: List<List<FlatContour>>,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): A8Image {
        val pixels = ByteArray(width * height)
        for (row in 0 until height) {
            val baseY = top + row
            for (column in 0 until width) {
                val baseX = left + column
                var inside = 0
                for (offsetY in sampleOffsets) {
                    val sampleY = baseY + offsetY
                    for (offsetX in sampleOffsets) {
                        val sampleX = baseX + offsetX
                        if (geometry != null && windingNumber(geometry, sampleX, sampleY) == 0) continue
                        if (clips.any { clip -> windingNumber(clip, sampleX, sampleY) == 0 }) continue
                        inside += 1
                    }
                }
                if (inside > 0) {
                    pixels[row * width + column] = (((inside * 255) + 8) / 16).toByte()
                }
            }
        }
        return A8Image(width, height, left, top, pixels)
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.CoverageRaster*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/CoverageRaster.kt \
    kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/CoverageRasterClipTest.kt
git commit -m "feat(raster-cpu): rasterize leaf coverage intersected with clips"
```

---

### Task 5: Compositor context, `Transform`, and `GlyphClip`

**Files:**
- Modify: `kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/PaintCompositor.kt`
- Test: `kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/PaintClipTest.kt`

**Interfaces:**
- Consumes: `CoverageRaster.rasterizeLeaf`, `ContourFlattener.flattenOutline/flattenPath(..., transform)`, `GlyphAffineTransform.IDENTITY`.
- Produces: the restructured `PaintCompositor` with `Context(clips: List<List<FlatContour>>, transform: GlyphAffineTransform, unitsPerEm: Int)`; `GlyphClip`, `Transform`, root `clipBounds`, and `SolidOutline`/`Path`/`Group` keep working. `Solid`/gradients remain refused for now (Task 6).

- [ ] **Step 1: Write the failing test**

Create `PaintClipTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPath
import org.graphiks.kalligraphie.api.GlyphPaintPathCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaintClipTest {
    private fun squareOutline(left: Double, top: Double, right: Double, bottom: Double): GlyphOutlineIR =
        GlyphOutlineIR(
            unitsPerEm = 1_000,
            contours = listOf(
                GlyphContour(
                    listOf(
                        GlyphOutlineCommand.MoveTo(left, top),
                        GlyphOutlineCommand.LineTo(right, top),
                        GlyphOutlineCommand.LineTo(right, bottom),
                        GlyphOutlineCommand.LineTo(left, bottom),
                        GlyphOutlineCommand.Close,
                    ),
                ),
            ),
        )

    private fun squarePath(left: Double, top: Double, right: Double, bottom: Double): GlyphPaintPath =
        GlyphPaintPath(
            listOf(
                GlyphPaintPathCommand.MoveTo(left, top),
                GlyphPaintPathCommand.LineTo(right, top),
                GlyphPaintPathCommand.LineTo(right, bottom),
                GlyphPaintPathCommand.LineTo(left, bottom),
                GlyphPaintPathCommand.Close,
            ),
        )

    @Test
    fun aClipRestrictsABoundedOutline() {
        // Path paints a 4x4 square; the clip keeps only the 2x2 top-left corner.
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val clip = GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 2.0, 2.0), paint = 0)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(path, clip))
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0xFF0000FF.toInt(), image[1, 1])
        assertEquals(0, image[3, 3])
    }

    @Test
    fun aRootClipBoundsClipsABoundedOutline() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 0,
            nodes = listOf(path),
            clipBounds = org.graphiks.kalligraphie.api.DesignBounds(0.0, 0.0, 2.0, 2.0),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0, image[3, 3])
    }

    @Test
    fun aTransformMovesABoundedOutline() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 2.0, 2.0), GlyphColor(255, 0, 0))
        val moved = GlyphPaintNode.Transform(
            paint = 0,
            matrix = GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 2.0, 0.0),
        )
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(path, moved))
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0, image[1, 1])
        assertEquals(0xFF0000FF.toInt(), image[3, 1])
    }

    @Test
    fun anEmptyClipPaintsNothing() {
        // The clip outline has no contours: the whole child is empty, not unbounded.
        val empty = GlyphPaintNode.GlyphClip(GlyphOutlineIR(unitsPerEm = 1_000, contours = emptyList()), paint = 0)
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(path, empty))
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0, image.width * image.height)
    }

    @Test
    fun aClipAroundAGroupIsRefused() {
        val path = GlyphPaintNode.Path(squarePath(0.0, 0.0, 4.0, 4.0), GlyphColor(255, 0, 0))
        val group = GlyphPaintNode.Group(listOf(0))
        val clip = GlyphPaintNode.GlyphClip(squareOutline(0.0, 0.0, 2.0, 2.0), paint = 1)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(path, group, clip))
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        }
        assertEquals("nodeKind", refusal.field)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.PaintClipTest"`
Expected: FAIL (`GlyphClip`/`Transform` produce `nodeKind` refusals, or `DesignBounds` import unresolved).

- [ ] **Step 3: Restructure the compositor context**

Rewrite `PaintCompositor` around the context below (keep `Layer`, `tinted`, `composite`, `blendInto`, `blendChannel`, `checkCanvas`). `unitsPerEm` in the context is the request's upem at the root.

```kotlin
    private class Context(
        val clips: List<List<FlatContour>>,
        val transform: GlyphAffineTransform,
        val unitsPerEm: Int,
    )

    fun rasterize(
        paint: GlyphPaintIR,
        pixelsPerEm: Double,
        unitsPerEm: Int,
        originX: Int,
        originY: Int,
        limits: RasterLimits,
    ): Rgba8Image {
        val rootClips = paint.clipBounds?.let { bounds ->
            listOf(
                listOf(
                    FlatContour(
                        listOf(
                            FlatPoint(bounds.minX, bounds.minY),
                            FlatPoint(bounds.maxX, bounds.minY),
                            FlatPoint(bounds.maxX, bounds.maxY),
                            FlatPoint(bounds.minX, bounds.maxY),
                        ),
                    ),
                ),
            )
        } ?: emptyList()
        val context = Context(rootClips, GlyphAffineTransform.IDENTITY, unitsPerEm)
        val root = context.build(paint, paint.rootNode, depth = 0)
        ...
    }
```

Move the counting and depth checks into a `Context.build` method, as today, and switch on the node:

```kotlin
        fun build(paint: GlyphPaintIR, nodeIndex: Int, depth: Int): Layer? {
            visitedNodes += 1
            if (visitedNodes > limits.maxPaintNodes) {
                throw RasterLimitReached("maxPaintNodes", visitedNodes.toLong(), limits.maxPaintNodes.toLong())
            }
            if (depth > limits.maxPaintDepth) {
                throw RasterLimitReached("maxPaintDepth", depth.toLong(), limits.maxPaintDepth.toLong())
            }
            return when (val node = paint.nodes[nodeIndex]) {
                is GlyphPaintNode.SolidOutline -> buildOutline(node, paint, depth)
                is GlyphPaintNode.Path -> buildPath(node, paint, depth)
                is GlyphPaintNode.GlyphClip -> {
                    val clipped = flattenClip(node, paint)
                    build(paint, node.paint, depth + 1, copy(clips = clips + listOf(clipped), unitsPerEm = node.outline.unitsPerEm))
                }
                is GlyphPaintNode.Transform -> {
                    val composed = compose(transform, node.matrix)
                    requireFinite(composed)
                    build(paint, node.paint, depth + 1, copy(transform = composed))
                }
                is GlyphPaintNode.Group -> {
                    if (clips.isNotEmpty()) {
                        throw RasterRequestRejected("nodeKind", "a clip around a composite is not supported.")
                    }
                    if (node.compositionMode != GlyphPaintCompositionMode.SOURCE_OVER) {
                        throw RasterRequestRejected("compositionMode", "unsupported paint composition mode.")
                    }
                    composite(node.children.mapNotNull { child -> build(paint, child, depth + 1, this) })
                }
                is GlyphPaintNode.Solid -> buildSolid(node, paint)
                is GlyphPaintNode.LinearGradient -> buildLinearGradient(node, paint)
                else -> throw RasterRequestRejected(
                    "nodeKind",
                    "unsupported paint node kind ${node::class.simpleName}.",
                )
            }
        }
```

`build(nodeIndex, depth, context)` becomes a method taking the context (so recursion passes a modified copy). Implement a private `fun build(paint, nodeIndex, depth, context): Layer?`.

`buildOutline` / `buildPath` compute coverage via `CoverageRaster.rasterizeLeaf(geometry, clips, ...)`, where geometry is the flattened contours and `clips` the context clips. Use `boundsOf(contours, limits)` for the region (unchanged); for a clip with no contours, `flattenClip` returns an empty contour list and `boundsOf` returns null; a `GlyphClip` whose flattened clip is empty must return `null` (paint nothing) without building the child:

```kotlin
        private fun flattenClip(node: GlyphPaintNode.GlyphClip, paint: GlyphPaintIR): List<FlatContour> =
            ContourFlattener.flattenOutline(
                contours = node.outline.contours,
                scale = pixelsPerEm / node.outline.unitsPerEm,
                originX = originX.toDouble(),
                originY = originY.toDouble(),
                limits = limits,
                transform = transform,
            )
```

Handle the empty-clip short-circuit in the `GlyphClip` branch before recursing:

```kotlin
                is GlyphPaintNode.GlyphClip -> {
                    val clipped = flattenClip(node, paint)
                    if (clipped.isEmpty()) {
                        null
                    } else {
                        build(paint, node.paint, depth + 1, copy(clips = clips + listOf(clipped), unitsPerEm = node.outline.unitsPerEm))
                    }
                }
```

Add the transform composition and finiteness guard:

```kotlin
    private fun compose(outer: GlyphAffineTransform, inner: GlyphAffineTransform): GlyphAffineTransform =
        GlyphAffineTransform(
            xx = outer.xx * inner.xx + outer.xy * inner.yx,
            yx = outer.yx * inner.xx + outer.yy * inner.yx,
            xy = outer.xx * inner.xy + outer.xy * inner.yy,
            yy = outer.yx * inner.xy + outer.yy * inner.yy,
            dx = outer.xx * inner.dx + outer.xy * inner.dy + outer.dx,
            dy = outer.yx * inner.dx + outer.yy * inner.dy + outer.dy,
        )

    private fun requireFinite(transform: GlyphAffineTransform) {
        val finite = transform.xx.isFinite() && transform.yx.isFinite() && transform.xy.isFinite() &&
            transform.yy.isFinite() && transform.dx.isFinite() && transform.dy.isFinite()
        if (!finite) throw RasterRequestRejected("transform", "the accumulated transform is not finite.")
    }
```

`buildOutline` and `buildPath` become:

```kotlin
        private fun buildOutline(node: GlyphPaintNode.SolidOutline, paint: GlyphPaintIR, depth: Int): Layer? {
            val contours = ContourFlattener.flattenOutline(
                contours = node.outline.contours,
                scale = pixelsPerEm / node.outline.unitsPerEm,
                originX = originX.toDouble(),
                originY = originY.toDouble(),
                limits = limits,
                transform = transform,
            )
            val bounds = boundsOf(contours, limits) ?: return null
            checkCanvas(bounds.width, bounds.height)
            val coverage = CoverageRaster.rasterizeLeaf(contours, clips, bounds.left, bounds.top, bounds.width, bounds.height)
            return tinted(coverage, node.color)
        }
```

`buildPath` is identical with `node.path.commands` and `unitsPerEm`.

`buildSolid` and `buildLinearGradient` throw `RasterRequestRejected("nodeKind", "unsupported paint node kind Solid.")` in this task; Task 6 replaces them.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.PaintClipTest" --tests "org.graphiks.kalligraphie.raster.PaintCompositorTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/PaintCompositor.kt \
    kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/PaintClipTest.kt
git commit -m "feat(raster-cpu): compose GlyphClip and Transform with an exact clip"
```

---

### Task 6: `Solid` and deterministic `LinearGradient`

**Files:**
- Create: `kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/LinearGradientShader.kt`
- Modify: `kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/PaintCompositor.kt`
- Test: `kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/PaintGradientTest.kt`

**Interfaces:**
- Consumes: `SrgbTransfer`, `CoverageRaster.rasterizeLeaf`, the `Context` from Task 5.
- Produces: `LinearGradientShader.shade(gradient, coverage, p0, p1, p2): ByteArray` (non-premultiplied RGBA over `coverage`'s samples) and `tintedSolid(coverage, color, opacity): ByteArray`.

- [ ] **Step 1: Write the failing test**

Create `PaintGradientTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintAlphaInterpolationMode
import org.graphiks.kalligraphie.api.GlyphPaintColorLine
import org.graphiks.kalligraphie.api.GlyphPaintColorStop
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintInterpolationSpace
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphPaintPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaintGradientTest {
    private val clip = GlyphOutlineIR(
        unitsPerEm = 1_000,
        contours = listOf(
            GlyphContour(
                listOf(
                    GlyphOutlineCommand.MoveTo(0.0, 0.0),
                    GlyphOutlineCommand.LineTo(4.0, 0.0),
                    GlyphOutlineCommand.LineTo(4.0, 4.0),
                    GlyphOutlineCommand.LineTo(0.0, 4.0),
                    GlyphOutlineCommand.Close,
                ),
            ),
        ),
    )

    private fun redBlueLine(extend: GlyphPaintExtendMode, stop0: Double = 0.0, stop1: Double = 1.0): GlyphPaintColorLine =
        GlyphPaintColorLine(
            extendMode = extend,
            colorStops = listOf(GlyphPaintColorStop(stop0, GlyphColor(255, 0, 0), 1.0), GlyphPaintColorStop(stop1, GlyphColor(0, 0, 255), 1.0)),
        )

    private fun gradientRoot(gradient: GlyphPaintNode.LinearGradient, schema: Int = 2): GlyphPaintIR =
        GlyphPaintIR(
            schemaVersion = schema,
            rootNode = 1,
            nodes = listOf(gradient, GlyphPaintNode.GlyphClip(clip, paint = 0)),
        )

    @Test
    fun aSolidIsBoundedByItsClip() {
        val solid = GlyphPaintNode.Solid(GlyphColor(10, 20, 30), 1.0)
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 1,
            nodes = listOf(solid, GlyphPaintNode.GlyphClip(clip, paint = 0)),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0xFF0A141E.toInt(), image[1, 1])
    }

    @Test
    fun anUnboundedRootIsRefused() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 0, nodes = listOf(solid))
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        }
        assertEquals("nodeKind", refusal.field)
    }

    @Test
    fun aRootClipBoundsBoundsAnUnboundedSolid() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val paint = GlyphPaintIR(
            schemaVersion = 2,
            rootNode = 0,
            nodes = listOf(solid),
            clipBounds = DesignBounds(0.0, 0.0, 2.0, 2.0),
        )
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(2, image.width)
    }

    @Test
    fun repeatPeriodsOverTheStopSpan() {
        // Stops 0.25..1.125: the period is 0.875, not 1.0. At a gradient parameter of 1.125 the
        // colour must wrap to the first stop (red), not clamp to the last (blue).
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = redBlueLine(GlyphPaintExtendMode.REPEAT, stop0 = 0.25, stop1 = 1.125),
            p0 = GlyphPaintPoint(0.0, 2.0),
            p1 = GlyphPaintPoint(1.0, 2.0),
            p2 = GlyphPaintPoint(0.0, 3.0),
        )
        val image = PaintCompositor.rasterize(gradientRoot(gradient), 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        // Column 3 is the right edge of the 4px clip; the exact channel is pinned by the
        // implementation and only asserted to differ from the left edge.
        assert(image[0, 2] != image[3, 2])
    }

    @Test
    fun anUnsupportedInterpolationSpaceIsRefused() {
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = GlyphPaintColorLine(
                GlyphPaintExtendMode.PAD,
                listOf(GlyphPaintColorStop(0.0, GlyphColor(255, 0, 0), 1.0), GlyphPaintColorStop(1.0, GlyphColor(0, 0, 255), 1.0)),
                interpolationSpace = GlyphPaintInterpolationSpace.SRGB,
            ),
            p0 = GlyphPaintPoint(0.0, 2.0),
            p1 = GlyphPaintPoint(1.0, 2.0),
            p2 = GlyphPaintPoint(0.0, 3.0),
        )
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(gradientRoot(gradient, schema = 3), 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        }
        assertEquals("interpolationSpace", refusal.field)
    }

    @Test
    fun anUnsupportedAlphaModeIsRefused() {
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = GlyphPaintColorLine(
                GlyphPaintExtendMode.PAD,
                listOf(GlyphPaintColorStop(0.0, GlyphColor(255, 0, 0), 1.0), GlyphPaintColorStop(1.0, GlyphColor(0, 0, 255), 1.0)),
                alphaInterpolationMode = GlyphPaintAlphaInterpolationMode.UNPREMULTIPLIED,
            ),
            p0 = GlyphPaintPoint(0.0, 2.0),
            p1 = GlyphPaintPoint(1.0, 2.0),
            p2 = GlyphPaintPoint(0.0, 3.0),
        )
        val refusal = assertFailsWith<RasterRequestRejected> {
            PaintCompositor.rasterize(gradientRoot(gradient, schema = 3), 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        }
        assertEquals("alphaInterpolationMode", refusal.field)
    }

    @Test
    fun aTransformMovesAGradient() {
        val gradient = GlyphPaintNode.LinearGradient(
            colorLine = redBlueLine(GlyphPaintExtendMode.PAD),
            p0 = GlyphPaintPoint(0.0, 2.0),
            p1 = GlyphPaintPoint(1.0, 2.0),
            p2 = GlyphPaintPoint(0.0, 3.0),
        )
        val moved = GlyphPaintNode.Transform(paint = 0, matrix = GlyphAffineTransform(1.0, 0.0, 0.0, 1.0, 2.0, 0.0))
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(gradient, moved, GlyphPaintNode.GlyphClip(clip, paint = 1)))
        val image = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(0xFF0000FF.toInt(), image[3, 2])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.PaintGradientTest"`
Expected: FAIL (`Solid` and `LinearGradient` still refuse with `nodeKind`; the gradient refusals and unbounded-root cases are not implemented yet).

- [ ] **Step 3: Implement the shader**

Create `LinearGradientShader.kt`. It evaluates the colour line at each pixel centre and multiplies the resulting alpha by the coverage sample. Full colour-line rules per the spec: empty → transparent; one stop → everywhere; `PAD`/`REPEAT`/`REFLECT` over `[first, last]`; duplicate offsets pick the first below and the last at/above; zero-span `REPEAT`/`REFLECT` paints nothing.

```kotlin
package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphPaintExtendMode
import org.graphiks.kalligraphie.api.GlyphPaintNode

/** Shades a clipped region with one linear gradient. */
internal object LinearGradientShader {
    private const val DENOMINATOR_EPSILON = 1e-12

    fun shade(
        gradient: GlyphPaintNode.LinearGradient,
        coverage: A8Image,
        p0: FlatPoint,
        p1: FlatPoint,
        p2: FlatPoint,
    ): ByteArray {
        val nx = -(p2.y - p0.y)
        val ny = p2.x - p0.x
        val denominator = (p1.x - p0.x) * nx + (p1.y - p0.y) * ny
        if (!denominator.isFinite() || kotlin.math.abs(denominator) < DENOMINATOR_EPSILON) {
            throw RasterRequestRejected("gradient", "the linear gradient projection is degenerate.")
        }
        val pixels = ByteArray(coverage.width * coverage.height * 4)
        for (row in 0 until coverage.height) {
            for (column in 0 until coverage.width) {
                val sample = coverage[row * coverage.width + column].toInt() and 0xFF
                if (sample == 0) continue
                val x = coverage.left + column + 0.5
                val y = coverage.top + row + 0.5
                val t = ((x - p0.x) * nx + (y - p0.y) * ny) / denominator
                val argb = colorAt(gradient, t)
                val alpha = (((argb ushr 24) and 0xFF) * sample + 127) / 255
                val base = (row * coverage.width + column) * 4
                pixels[base] = (argb ushr 16).toByte()
                pixels[base + 1] = (argb ushr 8).toByte()
                pixels[base + 2] = argb.toByte()
                pixels[base + 3] = alpha.toByte()
            }
        }
        return pixels
    }

    /** Returns the straight `0xAARRGGBB` colour of the line at [t]. */
    private fun colorAt(gradient: GlyphPaintNode.LinearGradient, raw: Double): Int {
        val stops = gradient.colorLine.colorStops
        if (stops.isEmpty()) return 0
        if (stops.size == 1) return straight(stops.first().color, stops.first().opacity)
        val first = stops.first().offset
        val last = stops.last().offset
        val span = last - first
        val t = when (gradient.colorLine.extendMode) {
            GlyphPaintExtendMode.PAD -> if (raw <= first) first else if (raw >= last) last else raw
            GlyphPaintExtendMode.REPEAT -> {
                if (span <= 0.0) return 0
                first + (((raw - first) % span) + span) % span
            }
            GlyphPaintExtendMode.REFLECT -> {
                if (span <= 0.0) return 0
                val phase = (((raw - first) % (2 * span)) + 2 * span) % (2 * span)
                if (phase <= span) first + phase else last - (phase - span)
            }
        }
        return interpolate(stops, t)
    }

    private fun interpolate(stops: List<org.graphiks.kalligraphie.api.GlyphPaintColorStop>, t: Double): Int {
        var index = 0
        while (index < stops.size - 1 && stops[index + 1].offset <= t) index += 1
        if (index == stops.size - 1) return straight(stops[index].color, stops[index].opacity)
        val left = stops[index]
        val right = stops[index + 1]
        val width = right.offset - left.offset
        val u = if (width <= 0.0) 0.0 else ((t - left.offset) / width).coerceIn(0.0, 1.0)
        val leftAlpha = effectiveAlpha(left.color, left.opacity)
        val rightAlpha = effectiveAlpha(right.color, right.opacity)
        val alpha = lerp(leftAlpha, rightAlpha, u)
        if (alpha == 0) return 0
        val red = lerp(SrgbTransfer.toLinear(left.color.red) * leftAlpha / 255, SrgbTransfer.toLinear(right.color.red) * rightAlpha / 255, u) / alpha * 255
        val green = lerp(SrgbTransfer.toLinear(left.color.green) * leftAlpha / 255, SrgbTransfer.toLinear(right.color.green) * rightAlpha / 255, u) / alpha * 255
        val blue = lerp(SrgbTransfer.toLinear(left.color.blue) * leftAlpha / 255, SrgbTransfer.toLinear(right.color.blue) * rightAlpha / 255, u) / alpha * 255
        return (round8(alpha) shl 24) or
            (SrgbTransfer.toSrgb(round16(red)) shl 16) or
            (SrgbTransfer.toSrgb(round16(green)) shl 8) or
            SrgbTransfer.toSrgb(round16(blue))
    }

    private fun effectiveAlpha(color: GlyphColor, opacity: Double): Double = color.alpha / 255.0 * opacity

    private fun straight(color: GlyphColor, opacity: Double): Int {
        val alpha = round8(effectiveAlpha(color, opacity))
        return (alpha shl 24) or (color.red shl 16) or (color.green shl 8) or color.blue
    }

    private fun lerp(left: Double, right: Double, u: Double): Double = left + (right - left) * u

    private fun round8(value: Double): Int = kotlin.math.round(value).toInt().coerceIn(0, 255)

    private fun round16(value: Double): Int = kotlin.math.round(value).toInt().coerceIn(0, 65535)
}
```

Note: `kotlin.math.round` / `abs` are deterministic basic library functions (not platform math); they are accepted here. If the team prefers to avoid them, replace `round` with `floor(x + 0.5)`; keep the plan's determinism requirement in mind.

- [ ] **Step 4: Implement `Solid` and the gradient branch in the compositor**

Add a `boundsOfClips(clips, limits): PixelBounds?` helper returning the union of `boundsOf(clip, limits)`, or `null` when no clip has area. In `Context`:

```kotlin
        private fun buildSolid(node: GlyphPaintNode.Solid): Layer? {
            val bounds = boundsOfClips(clips, limits) ?: return null
            checkCanvas(bounds.width, bounds.height)
            val coverage = CoverageRaster.rasterizeLeaf(null, clips, bounds.left, bounds.top, bounds.width, bounds.height)
            return tintedSolid(coverage, node.color, node.opacity)
        }

        private fun buildLinearGradient(node: GlyphPaintNode.LinearGradient): Layer? {
            if (node.colorLine.interpolationSpace != GlyphPaintInterpolationSpace.LINEAR_SRGB) {
                throw RasterRequestRejected("interpolationSpace", "only LINEAR_SRGB gradients are supported.")
            }
            if (node.colorLine.alphaInterpolationMode != GlyphPaintAlphaInterpolationMode.PREMULTIPLIED) {
                throw RasterRequestRejected("alphaInterpolationMode", "only PREMULTIPLIED gradients are supported.")
            }
            val bounds = boundsOfClips(clips, limits) ?: return null
            checkCanvas(bounds.width, bounds.height)
            val coverage = CoverageRaster.rasterizeLeaf(null, clips, bounds.left, bounds.top, bounds.width, bounds.height)
            val scale = pixelsPerEm / unitsPerEm
            val p0 = mapPoint(node.p0, transform, scale, originX.toDouble(), originY.toDouble())
            val p1 = mapPoint(node.p1, transform, scale, originX.toDouble(), originY.toDouble())
            val p2 = mapPoint(node.p2, transform, scale, originX.toDouble(), originY.toDouble())
            val pixels = LinearGradientShader.shade(node, coverage, p0, p1, p2)
            return Layer(bounds.left, bounds.top, bounds.width, bounds.height, pixels)
        }
```

`boundsOfClips`, `mapPoint`, and `tintedSolid` live in `PaintCompositor`; `tintedSolid` mirrors `tinted` with alpha `color.alpha × opacity × coverage / 255`.

```kotlin
    private fun mapPoint(point: GlyphPaintPoint, transform: GlyphAffineTransform, scale: Double, originX: Double, originY: Double): FlatPoint =
        FlatPoint(
            (transform.xx * point.x + transform.xy * point.y + transform.dx) * scale + originX,
            (transform.yx * point.x + transform.yy * point.y + transform.dy) * scale + originY,
        )
```

```kotlin
    private fun boundsOfClips(clips: List<List<FlatContour>>, limits: RasterLimits): PixelBounds? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        var found = false
        for (clip in clips) {
            val bounds = boundsOf(clip, limits) ?: continue
            found = true
            left = minOf(left, bounds.left)
            top = minOf(top, bounds.top)
            right = maxOf(right, bounds.left + bounds.width)
            bottom = maxOf(bottom, bounds.top + bounds.height)
        }
        if (!found) return null
        return PixelBounds(left, top, right - left, bottom - top)
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.PaintGradientTest"`
Expected: PASS. Adjust the exact expected channel in Step 1 only after reading the first run's actual value; the assertions must pin real bytes, not tolerate them.

- [ ] **Step 6: Commit**

```bash
git add kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/LinearGradientShader.kt \
    kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/PaintCompositor.kt \
    kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/PaintGradientTest.kt
git commit -m "feat(raster-cpu): compose unbounded solids and linear gradients"
```

---

### Task 7: Limits and determinism through clips and transforms

**Files:**
- Modify: `kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/RasterContracts.kt` (KDoc only)
- Test: `kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/PaintLimitsTest.kt`

**Interfaces:**
- Consumes: the restructured `PaintCompositor`.
- Produces: no new signature; pins that clip/transform recursion is counted and that repeated runs are byte-identical.

- [ ] **Step 1: Write the failing test**

Create `PaintLimitsTest.kt`:

```kotlin
package org.graphiks.kalligraphie.raster

import org.graphiks.kalligraphie.api.GlyphAffineTransform
import org.graphiks.kalligraphie.api.GlyphColor
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.GlyphOutlineIR
import org.graphiks.kalligraphie.api.GlyphPaintIR
import org.graphiks.kalligraphie.api.GlyphPaintNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaintLimitsTest {
    private val clip = GlyphOutlineIR(
        unitsPerEm = 1_000,
        contours = listOf(
            GlyphContour(
                listOf(
                    GlyphOutlineCommand.MoveTo(0.0, 0.0),
                    GlyphOutlineCommand.LineTo(4.0, 0.0),
                    GlyphOutlineCommand.LineTo(4.0, 4.0),
                    GlyphOutlineCommand.LineTo(0.0, 4.0),
                    GlyphOutlineCommand.Close,
                ),
            ),
        ),
    )

    @Test
    fun aClipAndTransformChainStillConsumesTheNodeBudget() {
        val solid = GlyphPaintNode.Solid(GlyphColor(0, 0, 0), 1.0)
        val transform = GlyphPaintNode.Transform(paint = 0, matrix = GlyphAffineTransform.IDENTITY)
        val clipNode = GlyphPaintNode.GlyphClip(clip, paint = 1)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 2, nodes = listOf(solid, transform, clipNode))
        val limits = RasterLimits.Default.copy(maxPaintNodes = 2)
        val refusal = assertFailsWith<RasterLimitReached> {
            PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, limits)
        }
        assertEquals("maxPaintNodes", refusal.field)
    }

    @Test
    fun repeatedRunsAreByteIdentical() {
        val solid = GlyphPaintNode.Solid(GlyphColor(10, 20, 30), 0.5)
        val paint = GlyphPaintIR(schemaVersion = 2, rootNode = 1, nodes = listOf(solid, GlyphPaintNode.GlyphClip(clip, paint = 0)))
        val first = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        val second = PaintCompositor.rasterize(paint, 1_000.0, 1_000, 0, 0, RasterLimits.Default)
        assertEquals(first.copyPixels().toList(), second.copyPixels().toList())
    }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:raster-cpu:jvmTest --tests "org.graphiks.kalligraphie.raster.PaintLimitsTest"`
Expected: PASS (the implementation from Tasks 5–6 already counts visits; this pins it).

- [ ] **Step 3: Update the two KDoc statements**

In `RasterContracts.kt`, change:

```kotlin
    /** Maximum number of paint nodes visited while rasterizing one paint graph. */
    public val maxPaintNodes: Int,
    /** Maximum paint-graph nesting depth accepted while rasterizing (groups, clips, transforms). */
    public val maxPaintDepth: Int,
```

and in `PaintRasterRequest`:

```kotlin
 * [unitsPerEm] is the design-unit scale of portable paint paths and of the unbounded
 * `Solid`/gradient paints a `GlyphClip` encloses; solid outline and clip nodes use their own
 * `unitsPerEm`. [originX] and [originY] translate the result in whole pixels.
```

- [ ] **Step 4: Commit**

```bash
git add kalligraphie/raster-cpu/src/commonMain/kotlin/org/graphiks/kalligraphie/raster/RasterContracts.kt \
    kalligraphie/raster-cpu/src/commonTest/kotlin/org/graphiks/kalligraphie/raster/PaintLimitsTest.kt
git commit -m "test(raster-cpu): pin clip and transform limits and determinism"
```

---

### Task 8: E2E family, profile, and paint-sheet scene

**Files:**
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenSceneFamily.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/golden/E2eFontFixture.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/SceneFontPaths.kt`
- Create: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/golden/VariablePaintSheetScene.kt`
- Test: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/golden/VariablePaintSheetSceneTest.kt`

**Interfaces:**
- Consumes: `openRenderableFixture(..., variation)`, `GlyphRasterizer.rasterizePaint`, `RgbaCanvas`.
- Produces: `VariablePaintSheetScene.sheet(corpus, fontPath, codepoints, weights, pixelsPerEm): GoldenImage`, `colrV1PaintRequirements(): FontAccessRequirementsSnapshot`, `GoldenSceneFamily.PAINT_SHEET`, `KALLIGRAPHIE_VAR_COLR`.

- [ ] **Step 1: Add the family and the corpus path**

In `GoldenSceneFamily.kt` add:

```kotlin
    /** A multi-glyph paint-graph sheet at one or more variation instances. */
    PAINT_SHEET,
```

In `SceneFontPaths.kt` add:

```kotlin
internal const val KALLIGRAPHIE_VAR_COLR = "/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf"
```

- [ ] **Step 2: Add the COLR v1 profile and the variation seam**

In `E2eFontFixture.kt` change `openRenderableFixture` to accept a variation and use it in the descriptor:

```kotlin
internal fun openRenderableFixture(
    bytes: ByteArray,
    requirements: FontAccessRequirementsSnapshot,
    renderVariant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
    layoutSize: LayoutUnit = LayoutUnit(2_048f),
    variation: FontVariationCoordinates? = null,
): E2eFontFixture {
    ...
        val instance = assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize = layoutSize, variation = variation)),
        ).value
    ...
}
```

(Confirm the exact `FontInstanceDescriptor` constructor parameter names with `grep -n "class FontInstanceDescriptor" kalligraphie/api`; use the same names the code uses.)

Add the profile:

```kotlin
internal fun colrV1PaintRequirements(): FontAccessRequirementsSnapshot =
    FontAccessRequirementsSnapshot.renderable(listOf(colrV1PaintProfile()))

internal fun colrV1PaintProfile(): PaintGraphProfile = PaintGraphProfile(
    acceptedNodeKinds = listOf(
        GlyphPaintNodeKind.GLYPH_CLIP,
        GlyphPaintNodeKind.SOLID,
        GlyphPaintNodeKind.LINEAR_GRADIENT,
        GlyphPaintNodeKind.TRANSFORM,
    ),
    acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
    acceptedGradientExtendModes = listOf(
        GlyphPaintExtendMode.PAD,
        GlyphPaintExtendMode.REPEAT,
        GlyphPaintExtendMode.REFLECT,
    ),
    limits = PaintGraphLimits(
        maxNodes = 64,
        maxReferences = 64,
        maxDepth = 8,
        maxGradients = 4,
        maxColorStops = 32,
        maxClips = 4,
        maxTransforms = 4,
        maxPaintVisits = 128,
        maxSourceBytes = 200_000,
        maxPaths = 6,
        maxPalettes = 9,
        maxPaletteEntries = 2_000,
        maxColorRecords = 2_000,
        maxBaseGlyphRecords = 3_000,
        maxLayerRecords = 30_000,
    ),
    outlineProfile = outlineProfile(),
    schemaVersion = 2,
)
```

Add the needed imports (`GlyphPaintExtendMode`). Confirm `acceptedGradientInterpolationSpaces` / `acceptedGradientAlphaInterpolationModes` default to `LINEAR_SRGB` / `PREMULTIPLIED` for schema 2 (they do per `PaintGraphProfile`).

- [ ] **Step 3: Write the failing scene test**

Create `VariablePaintSheetSceneTest.kt`:

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import kotlin.test.Test
import kotlin.test.assertTrue

class VariablePaintSheetSceneTest {
    @Test
    fun theSheetRendersEveryCellAndVariesWithTheInstance() {
        val corpus = org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment.corpus
        val image = VariablePaintSheetScene.sheet(
            corpus = corpus,
            fontPath = "/fonts/kalligraphie-var-colr/KalligraphieVarCOLRv1.ttf",
            codepoints = listOf(0x41, 0x42, 0x43),
            weights = listOf(400f, 900f),
            pixelsPerEm = 64.0,
        )
        assertTrue(image.width > 0 && image.height > 0)
    }
}
```

The harness exposes no `FixtureCorpus.fromClasspath()`; `E2eTestEnvironment.corpus` is the shared seam every golden test uses (see `GoldenSceneCatalog`).

- [ ] **Step 4: Run the test to verify it fails**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests "org.graphiks.kalligraphie.e2e.golden.VariablePaintSheetSceneTest"`
Expected: FAIL to compile (`VariablePaintSheetScene` not defined).

- [ ] **Step 5: Implement the scene**

Create `VariablePaintSheetScene.kt`. It resolves each cell, asserts on the **raw** rasters, then composes over white:

```kotlin
package org.graphiks.kalligraphie.e2e.golden

import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontVariationCoordinate
import org.graphiks.kalligraphie.api.FontVariationCoordinates
import org.graphiks.kalligraphie.api.GlyphPaintNode
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.e2e.GoldenImage
import org.graphiks.kalligraphie.e2e.fixture.FixtureCorpus
import org.graphiks.kalligraphie.raster.GlyphRasterizer
import org.graphiks.kalligraphie.raster.PaintRasterRequest
import org.graphiks.kalligraphie.raster.RasterResult
import org.graphiks.kalligraphie.raster.Rgba8Image
import kotlin.test.assertIs

/** Renders the variable COLR v1 fixture as a paint sheet across [weights] and [codepoints]. */
internal object VariablePaintSheetScene {
    private const val PADDING = 2

    fun sheet(
        corpus: FixtureCorpus,
        fontPath: String,
        codepoints: List<Int>,
        weights: List<Float>,
        pixelsPerEm: Double,
    ): GoldenImage {
        require(codepoints.isNotEmpty() && weights.size >= 2)
        val rows = weights.map { weight -> codepoints.map { codePoint -> rasterize(corpus, fontPath, codePoint, weight, pixelsPerEm) } }
        // Assertions on the raw rasters, before the white backing hides alpha.
        rows.forEach { row -> row.forEach { image -> check(image.width > 0 && image.height > 0) } }
        rows.forEachIndexed { index, row ->
            if (index == 0) return@forEachIndexed
            row.forEachIndexed { column, image ->
                check(image.copyPixels().toList() != rows[0][column].copyPixels().toList()) {
                    "U+" + codepoints[column].toString(16) + " is identical at wght ${weights[0]} and ${weights[index]}"
                }
            }
        }
        // The fixture's Transform wraps a uniform solid: columns 1 and 2 must be pixel-identical.
        rows.forEachIndexed { index, row ->
            check(row[1].copyPixels().toList() == row[2].copyPixels().toList()) {
                "the inert transform changed a pixel at wght ${weights[index]}"
            }
        }
        return composeOverWhite(rows, codepoints.size)
    }

    private fun rasterize(corpus: FixtureCorpus, fontPath: String, codePoint: Int, weight: Float, pixelsPerEm: Double): Rgba8Image {
        openRenderableFixture(
            bytes = corpus.bytes(fontPath),
            requirements = colrV1PaintRequirements(),
            variation = FontVariationCoordinates(listOf(FontVariationCoordinate("wght", weight))),
        ).use { fixture ->
            val glyph = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.GlyphResolution>>(
                fixture.instance.resolveGlyph(codePoint),
            ).value.glyphId
            val paint = assertIs<GlyphRepresentation.Paint>(
                assertIs<FontOperationResult.Success<GlyphRepresentation>>(
                    fixture.asset.resolveGlyph(FontGlyphRequest(glyph)),
                ).value,
            ).paint
            val unitsPerEm = assertIs<GlyphPaintNode.GlyphClip>(paint.nodes[paint.rootNode]).outline.unitsPerEm
            return assertIs<RasterResult.Success<Rgba8Image>>(
                GlyphRasterizer.rasterizePaint(paint, PaintRasterRequest(pixelsPerEm, unitsPerEm)),
            ).value
        }
    }

    private fun composeOverWhite(rows: List<List<Rgba8Image>>, columns: Int): GoldenImage {
        val cellWidth = rows.flatten().maxOf { image -> image.width } + 2 * PADDING
        val cellHeight = rows.flatten().maxOf { image -> image.height } + 2 * PADDING
        val canvas = RgbaCanvas(columns * cellWidth, rows.size * cellHeight)
        rows.forEachIndexed { row, images ->
            images.forEachIndexed { column, image ->
                canvas.drawColor(
                    image = image,
                    penX = column * cellWidth + PADDING,
                    baselineY = row * cellHeight + PADDING + image.height,
                )
            }
        }
        return canvas.toGoldenImage()
    }
}
```

Confirm `RgbaCanvas.drawColor(image, penX, baselineY)`'s exact signature and baseline convention with `grep -n "fun drawColor" CompositionCanvas.kt`, and adapt the placement so the glyph is not flipped; the existing `GlyphSheetScenes.renderColorSheet` shows the convention.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests "org.graphiks.kalligraphie.e2e.golden.VariablePaintSheetSceneTest"`
Expected: PASS. If an assertion fails, read the real raster and adjust the scene (e.g. the weights) before proceeding — do not weaken the assertion.

- [ ] **Step 7: Commit**

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/GoldenSceneFamily.kt \
    kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/golden/E2eFontFixture.kt \
    kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/SceneFontPaths.kt \
    kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/golden/VariablePaintSheetScene.kt \
    kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/golden/VariablePaintSheetSceneTest.kt
git commit -m "feat(e2e): render a variable COLR v1 paint sheet"
```

---

### Task 9: Promote the catalogue entry and register the scene

**Files:**
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ColorCatalog.kt`
- Modify: `kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogClaims.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogProbes.kt`
- Modify: `kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/PortableSceneRenderers.kt`

**Interfaces:**
- Consumes: `VariablePaintSheetScene.sheet`, `KALLIGRAPHIE_VAR_COLR`, `colrV1PaintRequirements`.
- Produces: the `Supported` entry and its renderer.

- [ ] **Step 1: Rewrite the catalogue entry**

Replace the `color.colr-v1-variable` block in `ColorCatalog.kt`:

```kotlin
        CatalogEntry(
            id = "color.colr-v1-variable",
            axis = CatalogAxis.COLOR,
            technology = CatalogText(
                "Variable COLR v1 paint graphs: GlyphClip, Solid, LinearGradient and Transform composed at two design weights",
                "Graphes de peinture COLR v1 variables : GlyphClip, Solid, LinearGradient et Transform composés à deux graisses",
            ),
            font = CorpusKeys.KALLIGRAPHIE_VAR_COLR,
            status = CatalogStatus.Supported(sinceCommit = "00000000"),
            tags = setOf("color:colr-v1", "variation:wght", "auto-sized"),
            tables = setOf("COLR", "CPAL", "fvar", "glyf", "loca"),
            family = GoldenSceneFamily.PAINT_SHEET,
            sceneId = "sheet.paint.kalligraphie-var-colr.64",
            route = CatalogRoute.PORTABLE_GLYPH,
            frame = SceneFramePolicy.AutoSized(padding = 2),
        ),
```

Set `sinceCommit` to the short hash of the commit that introduces this promotion (fill it in at the final commit of this task; see Step 5).

- [ ] **Step 2: Remove the exemption and register the renderer**

In `CatalogClaims.kt`, delete the `"kalligraphie-var-colr" to mapOf(...)` block from `UNREAD_TABLES` and the now-unused `REJECTED_FIXTURE_OUTLINES_REASON` constant.

In `CatalogProbes.kt`, delete the `"color.colr-v1-variable" to CatalogProbe(...)` entry and the now-unused `KALLIGRAPHIE_VAR_COLR` constant and `paintRequirements` import if nothing else uses them.

In `PortableSceneRenderers.kt`, add to `byId`:

```kotlin
        "color.colr-v1-variable" to CatalogSceneRenderer(
            fontPath = KALLIGRAPHIE_VAR_COLR,
            route = CatalogRoute.PORTABLE_GLYPH,
            sceneId = "sheet.paint.kalligraphie-var-colr.64",
        ) { corpus ->
            composed {
                VariablePaintSheetScene.sheet(
                    corpus = corpus,
                    fontPath = KALLIGRAPHIE_VAR_COLR,
                    codepoints = listOf(0x41, 0x42, 0x43),
                    weights = listOf(400f, 900f),
                    pixelsPerEm = 64.0,
                )
            }
        },
```

Add the `VariablePaintSheetScene` import.

- [ ] **Step 3: Generate the golden artifacts**

Run: `./gradlew :kalligraphie:e2e:updateE2eGolden`
Expected: `manifest.tsv`, `e2e-catalog-matrix.md`/`.fr.md`, and `claimed-tables.json` change; the new scene gains a line.

- [ ] **Step 4: Run the catalogue ratchets**

Run: `./gradlew :kalligraphie:e2e:jvmTest --tests "org.graphiks.kalligraphie.e2e.catalog.*"`
Expected: PASS (probe registry, pinned-frame ratchet, materialization, matrix).

- [ ] **Step 5: Record the promotion commit hash and commit**

After committing, read `git rev-parse --short HEAD` and update `sinceCommit`, then amend:

```bash
git add kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/ColorCatalog.kt \
    kalligraphie/e2e/src/commonMain/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogClaims.kt \
    kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/CatalogProbes.kt \
    kalligraphie/e2e/src/sharedTest/kotlin/org/graphiks/kalligraphie/e2e/catalog/PortableSceneRenderers.kt \
    kalligraphie/e2e/src/harnessResources/golden/manifest.tsv \
    kalligraphie/e2e/src/harnessResources/catalog/claimed-tables.json \
    docs/docs/generated/e2e-catalog-matrix.md docs/docs/generated/e2e-catalog-matrix.fr.md
git commit -m "feat(e2e): promote the variable COLR v1 paint proof"
HASH=$(git rev-parse --short HEAD)
# edit sinceCommit to $HASH, regenerate if needed, then amend
git add -A && git commit --amend --no-edit
```

Because `sinceCommit` cannot contain the commit's own hash, set it to the feature commit's short hash after the fact (the next task's docs commit does not change the support code). If the team prefers, use the squash-merge hash in a follow-up; record the choice in the PR body.

- [ ] **Step 6: Run the whole E2E check**

Run: `./gradlew :kalligraphie:e2e:check`
Expected: PASS.

---

### Task 10: Documentation, changelog, and full verification

**Files:**
- Modify: `kalligraphie/raster-cpu/README.md`
- Modify: `docs/docs/raster-cpu.md`, `docs/docs/raster-cpu.fr.md`
- Modify: `kalligraphie/e2e/README.md` (only if it lists scenes)
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: everything above.
- Produces: accurate documentation and the changelog entry.

- [ ] **Step 1: Update the raster-cpu README**

Replace the sentence that lists refused kinds (currently "Gradients, clips, transforms, composites, and unbounded solids from paint schema 2/3 are refused…") with the new surface: `GlyphClip`, `Solid`, `LinearGradient`, `Transform` are composed; `PathClip`, `RadialGradient`, `SweepGradient`, `Composite`, non-`SOURCE_OVER` modes, a clip around a `Group`, and non-`LINEAR_SRGB`/non-`PREMULTIPLIED` gradients are refused typed.

- [ ] **Step 2: Update the bilingual raster-cpu guide**

Apply the same change to `docs/docs/raster-cpu.md` and `docs/docs/raster-cpu.fr.md`, keeping the two languages in sync.

- [ ] **Step 3: Add the changelog entry**

Under `[Unreleased]` / `### Added`, add a bullet naming the new compositing surface and the promoted `color.colr-v1-variable` scene, with its scene id.

- [ ] **Step 4: Run the full local verification**

Run: `./gradlew check`
Expected: PASS. Fix any failure rather than relaxing an assertion.

- [ ] **Step 5: Commit**

```bash
git add kalligraphie/raster-cpu/README.md docs/docs/raster-cpu.md docs/docs/raster-cpu.fr.md \
    CHANGELOG.md kalligraphie/e2e/README.md
git commit -m "docs(raster-cpu): document COLR v1 variable compositing"
```

---

## Self-Review

- **Spec coverage:** §5.1 clip model → Tasks 4–5; §5.2 node semantics → Tasks 4–6; §5.3 flatten matrix → Task 3; §5.4 gradient + determinism → Tasks 2, 6; §5.5 refusals → Tasks 5–6; §5.6 limits → Task 7; §6.1 profile → Task 8; §6.2 scene → Task 8; §6.3 catalogue/claims/probe → Task 9; §6.4 lint → Task 9; §7 tests → Tasks 2–8; §8 docs → Task 10; §9 contract → Task 1 and the PR handoff.
- **Placeholder scan:** the only deferred value is `sinceCommit` (Task 9 Step 5), which the spec itself says is a post-hoc short hash; it is handled explicitly, not left as `TBD`.
- **Type consistency:** `CoverageRaster.rasterizeLeaf(geometry, clips, …)`, `Context(clips, transform, unitsPerEm)`, `GlyphAffineTransform.IDENTITY`, `colrV1PaintRequirements()`, and `VariablePaintSheetScene.sheet(...)` are named identically in every task that uses them.
- **Review Focus:** each of the five lines has a test: unbounded root (Task 6 `anUnboundedRootIsRefused`), stop-span repeat (Task 6 `repeatPeriodsOverTheStopSpan`), empty clip (Task 5 `anEmptyClipPaintsNothing`), unsupported gradient modes (Task 6), and limits (Task 7).
