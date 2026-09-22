package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSnapshot

/**
 * Canonical, platform-independent serialization of a shaped run, used as a frozen golden.
 *
 * Every metric is encoded as its exact IEEE-754 bit pattern (glyph ids, cluster tokens and scalar
 * ordinals as decimal integers), so the golden is byte-exact on every target without a numeric
 * tolerance. This reuses the `:kalligraphie:e2e` golden pattern — the JVM reference backend
 * freezes the golden and every target asserts the same bytes — without depending on image types.
 *
 * This file lives in a neutral `sharedTest/` directory added to both `androidDeviceTest` and
 * `iosSimulatorArm64Test` (see `build.gradle.kts`): the Android device-test compilation cannot see
 * `commonTest` in this module, so a shared directory is the single-serializer option that compiles
 * for both device targets without duplicating the serializer.
 *
 * [snapshot] must be the snapshot the [run] was shaped from; it resolves the opaque cluster
 * boundaries back to scalar ordinals.
 */
internal fun canonicalShapingGolden(snapshot: TextSnapshot, run: ShapedGlyphRun): String {
    fun ordinal(index: TextIndex): Int {
        for (candidate in snapshot.scalars.indices) {
            if (snapshot.textIndexAtScalarBoundary(candidate) == index) return candidate
        }
        check(snapshot.textIndexAtScalarBoundary(snapshot.scalars.size) == index) {
            "A shaped boundary is not a scalar boundary of the shaped snapshot."
        }
        return snapshot.scalars.size
    }

    fun ordinalRange(range: TextRange): String = "${ordinal(range.start)}..${ordinal(range.endExclusive)}"

    return buildString {
        appendLine("range=${ordinalRange(run.range)}")
        appendLine("direction=${run.direction}")
        appendLine("script=${run.script.value}")
        appendLine("language=${run.language}")
        appendLine("bidiLevel=${run.bidiLevel}")
        appendLine("bot=${run.bot};eot=${run.eot}")
        appendLine("glyphs=${run.glyphs.size}")
        run.glyphs.forEachIndexed { index, glyph ->
            append("glyph[").append(index).append("] id=").append(glyph.glyphId.value)
            append(" xAdv=").append(glyph.xAdvance.value.toRawBits())
            append(" yAdv=").append(glyph.yAdvance.value.toRawBits())
            append(" xOff=").append(glyph.xOffset.value.toRawBits())
            append(" yOff=").append(glyph.yOffset.value.toRawBits())
            append(" ubr=").append(if (glyph.safetyFlags.unsafeToBreak) 1 else 0)
            append(" utc=").append(if (glyph.safetyFlags.unsafeToConcat) 1 else 0)
            append(" tokens=").append(glyph.clusterTokens.joinToString(",") { token -> token.value.toString() })
            appendLine()
        }
        appendLine("clusters=${run.clusters.size}")
        run.clusters.forEach { cluster ->
            append("cluster[").append(cluster.token.value).append("] src=").append(ordinalRange(cluster.sourceRange))
            append(" scalars=[").append(cluster.scalarRanges.joinToString(",") { range -> ordinalRange(range) }).append("]")
            append(
                " boundaries=[" +
                    cluster.admissibleGraphemeBoundaries.joinToString(",") { boundary -> ordinal(boundary).toString() } +
                    "]",
            )
            appendLine()
        }
        appendLine("carets=${run.ligatureCaretFacts.size}")
        run.ligatureCaretFacts.forEach { caret ->
            append("caret[glyph=").append(caret.glyphIndex).append("] state=").append(caret.state.name)
            append(
                " boundaries=[" +
                    caret.logicalSourceBoundaries.joinToString(",") { boundary -> ordinal(boundary).toString() } +
                    "]",
            )
            append(
                " positions=[" +
                    caret.positions.joinToString(",") { position -> position.value.toRawBits().toString() } +
                    "]",
            )
            appendLine()
        }
    }
}
