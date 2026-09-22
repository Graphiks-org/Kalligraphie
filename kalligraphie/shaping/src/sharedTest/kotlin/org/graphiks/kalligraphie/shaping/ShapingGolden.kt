package org.graphiks.kalligraphie.shaping

import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.OpenTypeScript
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextIndex
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.unicode.TextSnapshots

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
 * for both device targets without duplicating the serializer. The frozen [LATIN_LIGATURE_GOLDEN],
 * [AMIRI_LIGATURE_GOLDEN], [HEBREW_RTL_GOLDEN] and [COMBINING_MARK_GOLDEN] constants and the pure
 * shaping helpers below live here for the same reason: the two device suites then reference exactly
 * one definition and cannot drift.
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

/**
 * Frozen goldens produced by the JVM reference backend; their individual
 * metrics are the values frozen in `HarfBuzzPortableBackendTest`. The Android and iOS bundled
 * bindings must reproduce them byte for byte. Every metric is the exact IEEE-754 bit pattern, so
 * there is no numeric tolerance.
 */
internal val LATIN_LIGATURE_GOLDEN: String = """
    range=0..2
    direction=LEFT_TO_RIGHT
    script=Latn
    language=en
    bidiLevel=0
    bot=true;eot=true
    glyphs=1
    glyph[0] id=5042 xAdv=1151418368 yAdv=0 xOff=0 yOff=0 ubr=0 utc=0 tokens=0
    clusters=1
    cluster[0] src=0..2 scalars=[0..1,1..2] boundaries=[0,1,2]
    carets=1
    caret[glyph=0] state=ABSENT boundaries=[1] positions=[]
""".trimIndent() + "\n"

internal val AMIRI_LIGATURE_GOLDEN: String = """
    range=0..3
    direction=LEFT_TO_RIGHT
    script=Latn
    language=en
    bidiLevel=0
    bot=true;eot=true
    glyphs=1
    glyph[0] id=6631 xAdv=1145487360 yAdv=0 xOff=0 yOff=0 ubr=0 utc=0 tokens=0
    clusters=1
    cluster[0] src=0..3 scalars=[0..1,1..2,2..3] boundaries=[0,1,2,3]
    carets=1
    caret[glyph=0] state=AVAILABLE boundaries=[1,2] positions=[1132888064,1141260288]
""".trimIndent() + "\n"

internal val HEBREW_RTL_GOLDEN: String = """
    range=0..4
    direction=RIGHT_TO_LEFT
    script=Hebr
    language=he
    bidiLevel=1
    bot=true;eot=true
    glyphs=4
    glyph[0] id=1293 xAdv=1152229376 yAdv=0 xOff=0 yOff=0 ubr=0 utc=0 tokens=3
    glyph[1] id=1285 xAdv=1141178368 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=2
    glyph[2] id=1292 xAdv=1149739008 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=1
    glyph[3] id=1305 xAdv=1153097728 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=0
    clusters=4
    cluster[0] src=0..1 scalars=[0..1] boundaries=[0,1]
    cluster[1] src=1..2 scalars=[1..2] boundaries=[1,2]
    cluster[2] src=2..3 scalars=[2..3] boundaries=[2,3]
    cluster[3] src=3..4 scalars=[3..4] boundaries=[3,4]
    carets=0
""".trimIndent() + "\n"

internal val COMBINING_MARK_GOLDEN: String = """
    range=0..2
    direction=LEFT_TO_RIGHT
    script=Latn
    language=en
    bidiLevel=0
    bot=true;eot=true
    glyphs=2
    glyph[0] id=91 xAdv=1149239296 yAdv=0 xOff=0 yOff=0 ubr=0 utc=1 tokens=0
    glyph[1] id=707 xAdv=0 yAdv=0 xOff=-1015480320 yOff=-1012269056 ubr=1 utc=1 tokens=1
    clusters=2
    cluster[0] src=0..1 scalars=[0..1] boundaries=[0]
    cluster[1] src=1..2 scalars=[1..2] boundaries=[2]
    carets=0
""".trimIndent() + "\n"

/** A decoded UTF-16 snapshot plus the scalar ranges the shaping requests need. */
internal class PreparedText(val snapshot: TextSnapshot) {
    fun scalarRanges(): List<TextRange> = snapshot.scalars.indices.map { scalar ->
        TextRange(snapshot.textIndexAtScalarBoundary(scalar), snapshot.textIndexAtScalarBoundary(scalar + 1))
    }
}

/** Decodes a UTF-16 literal into a [PreparedText] exactly as the shared suites shape it. */
internal fun text(value: String): PreparedText {
    val snapshot = TextSnapshots.decodeUtf16(
        version = TextVersion.create(),
        slices = listOf(TextSlice.Utf16(value.toCharArray())),
    ).snapshot
    return PreparedText(snapshot)
}

/** Builds the audited [ShapingRequest] shape every device suite submits to the bundled backend. */
internal fun request(
    prepared: PreparedText,
    font: FontInstance,
    direction: ShapingDirection,
    script: OpenTypeScript,
    language: String,
    bidiLevel: Int,
    featurePolicy: ShapingFeaturePolicy = HarfBuzzShapingBackend.pinnedFeaturePolicy,
    features: List<OpenTypeFeature> = emptyList(),
    graphemeRanges: List<TextRange> = prepared.scalarRanges(),
    resourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
    itemRange: TextRange = prepared.snapshot.range,
    contextRange: TextRange = itemRange,
): ShapingRequest = ShapingRequest(
    snapshot = prepared.snapshot,
    itemRange = itemRange,
    contextRange = contextRange,
    font = font,
    direction = direction,
    script = script,
    language = language,
    bidiLevel = bidiLevel,
    bot = itemRange.start == contextRange.start,
    eot = itemRange.endExclusive == contextRange.endExclusive,
    featurePolicy = featurePolicy,
    features = features,
    graphemeClusters = graphemeRanges,
    resourceProfile = resourceProfile,
)

/** Scalar-boundary [TextRange] helper shared by the two device suites. */
internal fun range(text: PreparedText, start: Int, endExclusive: Int): TextRange =
    TextRange(index(text, start), index(text, endExclusive))

/** Resolves a scalar ordinal to the snapshot's opaque [TextIndex]. */
internal fun index(text: PreparedText, ordinal: Int) = text.snapshot.textIndexAtScalarBoundary(ordinal)

/** Encodes both safety flags as the 1/2 bit mask the goldens freeze. */
internal fun safetyMask(glyph: ShapedGlyph): Int =
    (if (glyph.safetyFlags.unsafeToBreak) 1 else 0) or (if (glyph.safetyFlags.unsafeToConcat) 2 else 0)

/** Unwraps a typed [FontOperationResult] success or fails the assertion like the device suites do. */
internal fun <T> FontOperationResult<T>.successValue(): T =
    assertIs<FontOperationResult.Success<T>>(this).value
