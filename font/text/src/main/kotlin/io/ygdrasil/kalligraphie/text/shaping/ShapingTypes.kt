package io.ygdrasil.kalligraphie.text.shaping

import java.security.MessageDigest
import io.ygdrasil.kalligraphie.font.FallbackRequest
import io.ygdrasil.kalligraphie.font.FontResolver
import io.ygdrasil.kalligraphie.font.ResolvedFontRun
import io.ygdrasil.kalligraphie.font.TypefaceID
import io.ygdrasil.kalligraphie.font.sfnt.CMapTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeAnchor
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGdefTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposCursiveLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposMalformedLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposMarkToBaseAttachment
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposMarkToBaseLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposMarkToLigatureAttachment
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposMarkToLigatureLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposMarkToMarkLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubChainingContextGlyphLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubChainingContextGlyphRule
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubContextClassLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubContextClassRule
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubContextClassSubtable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubContextCoverageLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubContextCoverageRule
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubContextGlyphLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubContextGlyphRule
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubLigatureSubstitution
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubLigatureSubstitutionLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubMultipleSubstitutionLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubNestedLookupRecord
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubSingleSubstitutionLookup
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGsubTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposPairAdjustment
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposPairTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposSingleTable
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeGposValueRecord
import io.ygdrasil.kalligraphie.font.sfnt.OpenTypeKernTable

/**
 * Describes one text shaping operation for the pure Kotlin text stack.
 *
 * @property text UTF-16 input text owned by the caller.
 * @property textRange Inclusive range inside [text] that must be shaped.
 * @property typefaceId Stable typeface identifier selected by font resolution
 * when the caller wants to shape against a specific face.
 * @property fontSize Text size in logical pixels.
 * @property features OpenType feature choices to apply during shaping.
 * @property locale Optional BCP 47 locale tag used for language-sensitive shaping.
 * @property paragraphDirection Requested paragraph direction, where negative means RTL, positive means LTR, and zero means auto.
 * @property preferredFamilies Ordered font family names passed to fallback resolution.
 */
public data class ShapingRequest(
    public val text: String,
    public val textRange: IntRange = text.indices,
    public val typefaceId: TypefaceID? = null,
    public val fontSize: Float = 12f,
    public val features: FeatureSet = FeatureSet(),
    public val locale: String? = null,
    public val paragraphDirection: Int = 0,
    public val preferredFamilies: List<String> = emptyList(),
)

/**
 * Carries the complete output of shaping a [ShapingRequest].
 *
 * @property glyphRuns Ordered glyph runs produced for script, direction, font, or fallback boundaries.
 * @property diagnostics Non-fatal shaping diagnostics gathered while resolving the request.
 */
public data class ShapingResult(
    public val glyphRuns: List<ShapedGlyphRun> = emptyList(),
    public val diagnostics: List<ShapingDiagnostic> = emptyList(),
)

internal data class RuntimeShapingTraceResult(
    val result: ShapingResult,
    val runs: List<RuntimeShapingRunTrace> = emptyList(),
) {
    val gsubLookups: List<RuntimeGsubLookupTrace>
        get() = runs.flatMap(RuntimeShapingRunTrace::gsubLookups)

    val gposLookups: List<RuntimeGposLookupTrace>
        get() = runs.flatMap(RuntimeShapingRunTrace::gposLookups)
}

internal data class RuntimeShapingRunTrace(
    val inputGlyphIds: List<Int>,
    val featureOrder: List<String>,
    val bidiLevel: Int,
    val preGposClusterMetrics: List<RuntimeClusterMetric> = emptyList(),
    val gsubLookups: List<RuntimeGsubLookupTrace> = emptyList(),
    val gposLookups: List<RuntimeGposLookupTrace> = emptyList(),
)

internal data class RuntimeClusterMetric(
    val advanceX: Float,
    val offsetX: Float,
    val offsetY: Float,
)

internal data class RuntimeGsubLookupTrace(
    val lookupIndex: Int,
    val lookupType: Int,
    val featureTag: String,
    val inputGlyphIds: List<Int>,
    val outputGlyphIds: List<Int>,
    val clusterAction: String,
    val contextFormat: Int? = null,
)

internal data class RuntimeGposLookupTrace(
    val lookupIndex: Int,
    val lookupType: Int,
    val featureTag: String,
    val matchedGlyphIds: List<Int>,
    val glyphClasses: List<Int> = emptyList(),
    val markClass: Int? = null,
    val anchorFormats: List<Int> = emptyList(),
    val attachmentVector: List<Int> = emptyList(),
    val cursiveChain: List<List<Int>> = emptyList(),
)

/**
 * Represents one positioned run of glyphs that share shaping state.
 *
 * @property glyphIds Font-specific glyph identifiers in visual order for this run.
 * @property clusters Logical text clusters mapped to the emitted glyphs.
 * @property advanceX Total horizontal advance in logical pixels.
 * @property advanceY Total vertical advance in logical pixels for vertical writing support.
 * @property script ISO 15924 script tag associated with this run.
 * @property bidiLevel Unicode Bidirectional Algorithm embedding level for the run.
 * @property typefaceId Stable typeface identifier used to produce the glyph ids.
 * @property fontSize Text size in logical pixels used for deterministic advances.
 */
public data class ShapedGlyphRun(
    public val glyphIds: List<Int> = emptyList(),
    public val clusters: List<GlyphCluster> = emptyList(),
    public val advanceX: Float = 0f,
    public val advanceY: Float = 0f,
    public val script: String = "Zyyy",
    public val bidiLevel: Int = 0,
    public val typefaceId: TypefaceID? = null,
    public val fontSize: Float = 12f,
)

/**
 * Maps one logical text cluster to one or more glyphs in a shaped run.
 *
 * @property textRange Inclusive UTF-16 range covered by the cluster in the original text.
 * @property glyphRange Inclusive range of glyph indices covered by the cluster in its run.
 * @property advanceX Horizontal advance contributed by this cluster.
 * @property offsetX Horizontal placement adjustment for the cluster origin.
 * @property offsetY Vertical placement adjustment for the cluster origin.
 */
public data class GlyphCluster(
    public val textRange: IntRange,
    public val glyphRange: IntRange,
    public val advanceX: Float = 0f,
    public val offsetX: Float = 0f,
    public val offsetY: Float = 0f,
)

/**
 * Holds OpenType feature selections for a shaping request.
 *
 * @property values Map from four-character OpenType feature tags to integer feature values.
 */
public data class FeatureSet(
    public val values: Map<String, Int> = emptyMap(),
)

/**
 * Describes a contiguous text range that shares one Unicode script.
 *
 * @property textRange Inclusive UTF-16 range covered by the script run.
 * @property script ISO 15924 script tag such as `Latn`, `Arab`, or `Zyyy`.
 */
public data class ScriptRun(
    public val textRange: IntRange,
    public val script: String,
)

/**
 * Describes a contiguous text range that shares one resolved bidi level.
 *
 * @property textRange Inclusive UTF-16 range covered by the bidi run.
 * @property level Unicode Bidirectional Algorithm embedding level.
 * @property isRightToLeft True when [level] resolves to right-to-left visual order.
 */
public data class BidiRun(
    public val textRange: IntRange,
    public val level: Int,
    public val isRightToLeft: Boolean,
)

/**
 * Provides Unicode classification data required by segmentation, bidi resolution, and script itemization.
 */
public interface UnicodeData {
    /**
     * Returns the ISO 15924 script tag for [codePoint].
     */
    public fun scriptOf(codePoint: Int): String

    /**
     * Returns the Unicode bidi class for [codePoint].
     */
    public fun bidiClassOf(codePoint: Int): String

    /**
     * Returns true when [codePoint] has the Default_Ignorable_Code_Point property.
     */
    public fun isDefaultIgnorable(codePoint: Int): Boolean
}

/**
 * Minimal built-in Unicode data used by the first pure Kotlin shaping primitives.
 *
 * This table is intentionally bounded. It covers Latin, Hebrew, Arabic,
 * Common, Inherited combining marks, a base emoji symbol range, and common
 * default-ignorable code points needed by early shaping work. It is not a
 * replacement for the complete Unicode Character Database.
 */
public object BasicUnicodeData : UnicodeData {
    /**
     * Returns a bounded ISO 15924-style script tag for [codePoint].
     */
    override fun scriptOf(codePoint: Int): String =
        when {
            isCombiningMark(codePoint) || isVariationSelector(codePoint) -> SCRIPT_INHERITED
            isBaseEmoji(codePoint) -> SCRIPT_EMOJI
            isLatin(codePoint) -> SCRIPT_LATIN
            isHebrew(codePoint) -> SCRIPT_HEBREW
            isArabic(codePoint) -> SCRIPT_ARABIC
            else -> SCRIPT_COMMON
        }

    /**
     * Returns a small subset of Unicode bidi classes for [codePoint].
     */
    override fun bidiClassOf(codePoint: Int): String =
        when {
            isDefaultIgnorable(codePoint) -> BIDI_BOUNDARY_NEUTRAL
            isCombiningMark(codePoint) -> BIDI_NONSPACING_MARK
            isLatin(codePoint) -> BIDI_LEFT_TO_RIGHT
            isHebrew(codePoint) -> BIDI_RIGHT_TO_LEFT
            isArabicNumber(codePoint) -> BIDI_ARABIC_NUMBER
            isArabic(codePoint) -> BIDI_ARABIC_LETTER
            isEuropeanNumber(codePoint) -> BIDI_EUROPEAN_NUMBER
            isParagraphSeparator(codePoint) -> BIDI_PARAGRAPH_SEPARATOR
            isSegmentSeparator(codePoint) -> BIDI_SEGMENT_SEPARATOR
            isWhitespace(codePoint) -> BIDI_WHITESPACE
            else -> BIDI_OTHER_NEUTRAL
        }

    /**
     * Returns true for common Default_Ignorable_Code_Point ranges.
     */
    override fun isDefaultIgnorable(codePoint: Int): Boolean =
        codePoint == 0x00AD ||
            codePoint == 0x034F ||
            codePoint == 0x061C ||
            codePoint in 0x115F..0x1160 ||
            codePoint in 0x17B4..0x17B5 ||
            codePoint in 0x180B..0x180F ||
            codePoint in 0x200B..0x200F ||
            codePoint in 0x202A..0x202E ||
            codePoint in 0x2060..0x206F ||
            codePoint == 0x3164 ||
            codePoint in 0xFE00..0xFE0F ||
            codePoint == 0xFEFF ||
            codePoint in 0xFFF0..0xFFF8 ||
            codePoint in 0x1BCA0..0x1BCA3 ||
            codePoint in 0x1D173..0x1D17A ||
            codePoint in 0xE0000..0xE0FFF
}

/**
 * Splits text into shaping-safe ranges such as grapheme, word, and sentence boundaries.
 */
public interface TextSegmenter {
    /**
     * Segments [text] and returns inclusive UTF-16 ranges for the requested segmentation mode.
     */
    public fun segment(text: String): List<IntRange>
}

/**
 * Segments text into pinned KFONT-M5-002 grapheme clusters for early shaping.
 *
 * The default constructor delegates to [DefaultGraphemeTextSegmenter]. The
 * [UnicodeData] constructor keeps the previous conservative code point
 * clustering available for compatibility in callers that explicitly request a
 * bounded legacy Unicode data source.
 *
 * @param delegate Text segmenter used by this compatibility facade.
 */
public class BasicTextSegmenter(
    private val delegate: TextSegmenter = DefaultGraphemeTextSegmenter,
) : TextSegmenter {
    public constructor(unicodeData: UnicodeData) : this(LegacyCodePointTextSegmenter(unicodeData))

    /**
     * Returns inclusive UTF-16 ranges for grapheme clusters.
     */
    override fun segment(text: String): List<IntRange> =
        delegate.segment(text)
}

private class LegacyCodePointTextSegmenter(
    private val unicodeData: UnicodeData,
) : TextSegmenter {
    override fun segment(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (codePointRange in codePointRanges(text)) {
            if (ranges.isNotEmpty() && shouldAttachToPreviousCluster(codePointRange.codePoint)) {
                val previous = ranges.removeAt(ranges.lastIndex)
                ranges += previous.first..codePointRange.textRange.last
            } else {
                ranges += codePointRange.textRange
            }
        }
        return ranges
    }

    private fun shouldAttachToPreviousCluster(codePoint: Int): Boolean =
        isCombiningMark(codePoint) || unicodeData.isDefaultIgnorable(codePoint)
}

/**
 * Resolves logical text into bidi runs using Unicode bidi data and paragraph direction.
 */
public interface BidiResolver {
    /**
     * Resolves bidi runs for [request].
     */
    public fun resolve(request: ShapingRequest): List<BidiRun>
}

/**
 * Resolves bounded bidi runs for the pure Kotlin text stack.
 *
 * The default constructor delegates to the pinned KFONT-M5-003 resolver. The
 * [UnicodeData] constructor keeps the previous conservative resolver available
 * for callers that explicitly request a bounded legacy Unicode data source.
 */
public class BasicBidiResolver(
    private val delegate: BidiResolver = DefaultBidiResolver(PinnedUnicodeDataSetResources.load()),
) : DetailedBidiResolver {
    public constructor(unicodeData: UnicodeData) : this(LegacyBasicBidiResolver(unicodeData))

    /**
     * Resolves bidi runs for [request].
     */
    override fun resolve(request: ShapingRequest): List<BidiRun> =
        delegate.resolve(request)

    /**
     * Resolves detailed bidi facts when the delegate supports them.
     */
    override fun resolveDetailed(
        request: ShapingRequest,
        requireParagraphOrdering: Boolean,
    ): BidiResolution =
        (delegate as? DetailedBidiResolver)?.resolveDetailed(request, requireParagraphOrdering)
            ?: legacyDetailedResolution(request, requireParagraphOrdering)

    private fun legacyDetailedResolution(
        request: ShapingRequest,
        requireParagraphOrdering: Boolean,
    ): BidiResolution {
        val runs = delegate.resolve(request)
        val paragraphDirection = legacyParagraphDirectionFor(request, runs)
        val detailedRuns = runs.map { run ->
            ResolvedBidiRun(
                logicalUtf16Range = run.textRange,
                clusterRange = 0..0,
                embeddingLevel = run.level,
                direction = if (run.isRightToLeft) "R" else "L",
                paragraphDirection = paragraphDirection,
                resolvedBidiClasses = emptyList(),
                sourceControls = emptyList(),
            )
        }
        val diagnostics = if (
            !requireParagraphOrdering &&
            detailedRuns.any { run -> run.direction != legacyBaseDirectionCode(paragraphDirection) }
        ) {
            listOf(
                ShapingDiagnostic(
                    code = TEXT_SHAPING_PARAGRAPH_BIDI_REQUIRED_DIAGNOSTIC_CODE,
                    message = "Paragraph-level visual bidi ordering is required for mixed-direction text; M8 owns line ordering.",
                    textRange = request.textRange,
                ),
            )
        } else {
            emptyList()
        }
        return BidiResolution(
            unicodeVersion = PinnedUnicodeDataGenerator.PinnedUnicodeVersion,
            sourceTextHash = request.scopedTextForLegacyBidi().legacyBidiSourceTextHash(),
            paragraphDirection = paragraphDirection,
            clusters = emptyList(),
            runs = detailedRuns,
            sourceControls = emptyList(),
            trace = emptyList(),
            diagnostics = diagnostics,
        )
    }

    private fun legacyParagraphDirectionFor(request: ShapingRequest, runs: List<BidiRun>): String =
        when {
            request.paragraphDirection < 0 -> "RightToLeft"
            request.paragraphDirection > 0 -> "LeftToRight"
            runs.firstOrNull()?.isRightToLeft == true -> "RightToLeft"
            else -> "LeftToRight"
        }

    private fun legacyBaseDirectionCode(paragraphDirection: String): String =
        if (paragraphDirection == "RightToLeft") "R" else "L"

    private fun ShapingRequest.scopedTextForLegacyBidi(): String {
        if (text.isEmpty()) return ""
        val first = textRange.first.coerceAtLeast(0)
        val last = textRange.last.coerceAtMost(text.lastIndex)
        return if (first <= last) text.substring(first..last) else ""
    }

    private fun String.legacyBidiSourceTextHash(): String {
        val bytes = ByteArray(length * 2)
        forEachIndexed { index, char ->
            val value = char.code
            bytes[index * 2] = (value ushr 8).toByte()
            bytes[index * 2 + 1] = value.toByte()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xFF)
        }
    }
}

private class LegacyBasicBidiResolver(
    private val unicodeData: UnicodeData = BasicUnicodeData,
) : BidiResolver {
    override fun resolve(request: ShapingRequest): List<BidiRun> {
        val codePoints = codePointRangesFor(request)
        if (codePoints.isEmpty()) return emptyList()

        val baseDirection = baseDirectionFor(request, codePoints)
        val pendingNeutralRanges = mutableListOf<IntRange>()
        val runs = mutableListOf<BidiRun>()
        var currentDirection: TextDirection? = null
        var currentStart = -1
        var currentEnd = -1

        for (codePointRange in codePoints) {
            val strongDirection = strongDirectionOf(codePointRange.codePoint)
            if (strongDirection == null) {
                if (currentDirection == null) {
                    pendingNeutralRanges += codePointRange.textRange
                } else {
                    currentEnd = codePointRange.textRange.last
                }
                continue
            }

            if (currentDirection == null) {
                currentDirection = strongDirection
                currentStart = pendingNeutralRanges.firstOrNull()?.first ?: codePointRange.textRange.first
                currentEnd = codePointRange.textRange.last
                pendingNeutralRanges.clear()
            } else if (currentDirection == strongDirection) {
                currentEnd = codePointRange.textRange.last
            } else {
                runs += bidiRun(currentStart..currentEnd, currentDirection, baseDirection)
                currentDirection = strongDirection
                currentStart = codePointRange.textRange.first
                currentEnd = codePointRange.textRange.last
            }
        }

        if (currentDirection == null) {
            val textRange = pendingNeutralRanges.first().first..pendingNeutralRanges.last().last
            return listOf(bidiRun(textRange, baseDirection, baseDirection))
        }

        runs += bidiRun(currentStart..currentEnd, currentDirection, baseDirection)
        return runs
    }

    private fun baseDirectionFor(request: ShapingRequest, codePoints: List<CodePointRange>): TextDirection =
        when {
            request.paragraphDirection < 0 -> TextDirection.RightToLeft
            request.paragraphDirection > 0 -> TextDirection.LeftToRight
            else -> codePoints.firstNotNullOfOrNull { strongDirectionOf(it.codePoint) } ?: TextDirection.LeftToRight
        }

    private fun strongDirectionOf(codePoint: Int): TextDirection? =
        when (unicodeData.bidiClassOf(codePoint)) {
            BIDI_LEFT_TO_RIGHT -> TextDirection.LeftToRight
            BIDI_RIGHT_TO_LEFT,
            BIDI_ARABIC_LETTER,
            -> TextDirection.RightToLeft
            else -> null
        }
}

/**
 * Groups text into script runs before OpenType shaping.
 */
public interface ScriptItemizer {
    /**
     * Itemizes [request] into script runs.
     */
    public fun itemize(request: ShapingRequest): List<ScriptRun>
}

/**
 * Groups text into bounded script runs for early OpenType shaping.
 *
 * This itemizer is not a complete Unicode Script_Extensions implementation.
 * It groups strong scripts from [UnicodeData.scriptOf] and attaches Common or
 * Inherited code points to the previous strong script, or to the next strong
 * script for leading neutral text.
 *
 * @param unicodeData Unicode data source used for script classification.
 */
public class BasicScriptItemizer(
    private val unicodeData: UnicodeData = BasicUnicodeData,
) : ScriptItemizer {
    /**
     * Returns coarse script runs for [request].
     */
    override fun itemize(request: ShapingRequest): List<ScriptRun> {
        val codePoints = codePointRangesFor(request)
        if (codePoints.isEmpty()) return emptyList()

        val pendingNeutralRanges = mutableListOf<IntRange>()
        val runs = mutableListOf<ScriptRun>()
        var currentScript: String? = null
        var currentStart = -1
        var currentEnd = -1

        for (codePointRange in codePoints) {
            val script = unicodeData.scriptOf(codePointRange.codePoint)
            if (!script.isStrongScript()) {
                if (currentScript == null) {
                    pendingNeutralRanges += codePointRange.textRange
                } else {
                    currentEnd = codePointRange.textRange.last
                }
                continue
            }

            if (currentScript == null) {
                currentScript = script
                currentStart = pendingNeutralRanges.firstOrNull()?.first ?: codePointRange.textRange.first
                currentEnd = codePointRange.textRange.last
                pendingNeutralRanges.clear()
            } else if (currentScript == script) {
                currentEnd = codePointRange.textRange.last
            } else {
                runs += ScriptRun(currentStart..currentEnd, currentScript)
                currentScript = script
                currentStart = codePointRange.textRange.first
                currentEnd = codePointRange.textRange.last
            }
        }

        if (currentScript == null) {
            val fallbackScript = fallbackNeutralScript(codePoints)
            val textRange = pendingNeutralRanges.first().first..pendingNeutralRanges.last().last
            return listOf(ScriptRun(textRange, fallbackScript))
        }

        runs += ScriptRun(currentStart..currentEnd, currentScript)
        return runs
    }

    private fun fallbackNeutralScript(codePoints: List<CodePointRange>): String {
        val scripts = codePoints.map { unicodeData.scriptOf(it.codePoint) }
        return if (scripts.all { it == SCRIPT_INHERITED }) SCRIPT_INHERITED else SCRIPT_COMMON
    }
}

/**
 * Maps a Unicode code point to a glyph id for a resolved typeface.
 *
 * The mapper is intentionally injectable so early pure Kotlin shaping can use
 * deterministic test maps, parsed SFNT cmap readers, or later font fallback
 * sources without changing the shaping engine.
 */
public interface GlyphMapper {
    /**
     * Returns the glyph id for [codePoint] in [typefaceId], or null when the
     * typeface has no supported glyph for that code point.
     */
    public fun glyphIdFor(typefaceId: TypefaceID?, codePoint: Int): Int?
}

/**
 * Maps Unicode code points through parsed SFNT `cmap` tables.
 *
 * When [typefaceId][GlyphMapper.glyphIdFor] is non-null, the mapper uses only
 * the matching table from [cmapsByTypefaceId] because glyph ids are specific to
 * that resolved face. [defaultCMap] is used only for null typeface requests.
 * The mapper returns `null` when no eligible table is available or when the
 * selected table resolves the code point to `.notdef` glyph ID `0`.
 *
 * @param cmapsByTypefaceId Parsed `cmap` tables keyed by resolved typeface identifier.
 * @param defaultCMap Optional `cmap` table used only when no typeface id is supplied.
 */
public class CMapGlyphMapper(
    private val cmapsByTypefaceId: Map<TypefaceID, CMapTable> = emptyMap(),
    private val defaultCMap: CMapTable? = null,
) : GlyphMapper {
    /**
     * Returns the SFNT glyph id for [codePoint] in [typefaceId], or `null` when unavailable.
     */
    override fun glyphIdFor(typefaceId: TypefaceID?, codePoint: Int): Int? {
        val cmap = if (typefaceId == null) {
            defaultCMap
        } else {
            cmapsByTypefaceId[typefaceId]
        } ?: return null
        val glyphId = cmap.lookupGlyphId(codePoint) ?: return null
        return glyphId.takeIf { it != 0 }
    }
}

/**
 * Coordinates Unicode analysis, OpenType substitution, OpenType positioning, and emoji shaping.
 */
public interface OpenTypeShapingEngine {
    /**
     * Shapes [request] and returns positioned glyph runs.
     */
    public fun shape(request: ShapingRequest): ShapingResult
}

/**
 * Produces deterministic glyph runs from the bounded pure Kotlin text primitives.
 *
 * This engine coordinates [TextSegmenter], [ScriptItemizer], [BidiResolver],
 * and [GlyphMapper]. It preserves UTF-16 cluster ranges from segmentation,
 * expands ranges that would otherwise split surrogate pairs, maps each code
 * point through [glyphMapper], and assigns one [ShapingRequest.fontSize]
 * advance per cluster before optional pair-position adjustments. It does not
 * implement full Unicode segmentation, the full Unicode Bidirectional
 * Algorithm, full GSUB, full GPOS, or emoji sequence shaping.
 *
 * Pair kerning is intentionally opt-in. Supplying [kernTablesByTypefaceId]
 * makes parsed legacy OpenType `kern` format `0` pairs available for matching
 * [ShapingRequest.typefaceId] values. Supplying [gposPairTablesByTypefaceId]
 * makes the bounded Kanvas GPOS `kern` pair-position subset available for the
 * same matching policy. Supplying [gposSingleTablesByTypefaceId] makes the
 * bounded Kanvas GPOS single-position subset available for glyph offset and
 * advance adjustments. Supplying [kernUnitsPerEmByTypefaceId] for the same
 * typeface converts signed font-unit adjustments to logical pixel adjustments
 * using `request.fontSize / unitsPerEm`. Pair adjustments apply to adjacent
 * glyphs and update the owning cluster advances and offsets in place. GPOS
 * pairs take precedence over legacy `kern` pairs for the same typeface.
 *
 * @param glyphMapper Code point to glyph id mapper for the selected typeface.
 * @param segmenter Text segmenter used to form conservative shaping clusters.
 * @param scriptItemizer Script itemizer used to group clusters by script.
 * @param bidiResolver Bidi resolver used to assign embedding levels.
 * @param missingGlyphId Glyph id emitted when [glyphMapper] has no mapping.
 * @param kernTablesByTypefaceId Parsed OpenType `kern` tables keyed by the
 * resolved typeface that produced their glyph IDs. Only matching request
 * typefaces participate in kerning.
 * @param gposPairTablesByTypefaceId Parsed bounded OpenType GPOS pair tables
 * keyed by the resolved typeface that produced their glyph IDs. These tables
 * take precedence over legacy `kern` tables when both are present.
 * @param gposSingleTablesByTypefaceId Parsed bounded OpenType GPOS single
 * tables keyed by the resolved typeface that produced their glyph IDs.
 * @param kernUnitsPerEmByTypefaceId Design units per em for the parsed `kern`
 * and GPOS pair tables keyed by typeface. A table present without a positive
 * units-per-em entry is left unapplied and reported through
 * [KERN_TABLE_UNAPPLIED_DIAGNOSTIC_CODE].
 */
public class BasicOpenTypeShapingEngine(
    private val glyphMapper: GlyphMapper,
    private val segmenter: TextSegmenter = BasicTextSegmenter(),
    private val scriptItemizer: ScriptItemizer = BasicScriptItemizer(),
    private val bidiResolver: BidiResolver = BasicBidiResolver(),
    private val missingGlyphId: Int = 0,
    private val gsubTablesByTypefaceId: Map<TypefaceID, OpenTypeGsubTable> = emptyMap(),
    private val kernTablesByTypefaceId: Map<TypefaceID, OpenTypeKernTable> = emptyMap(),
    private val gdefTablesByTypefaceId: Map<TypefaceID, OpenTypeGdefTable> = emptyMap(),
    private val gposTablesByTypefaceId: Map<TypefaceID, OpenTypeGposTable> = emptyMap(),
    private val gposSingleTablesByTypefaceId: Map<TypefaceID, OpenTypeGposSingleTable> = emptyMap(),
    private val gposPairTablesByTypefaceId: Map<TypefaceID, OpenTypeGposPairTable> = emptyMap(),
    private val kernUnitsPerEmByTypefaceId: Map<TypefaceID, Int> = emptyMap(),
) : OpenTypeShapingEngine {
    /**
     * Shapes [request] into bounded script/bidi glyph runs.
     */
    override fun shape(request: ShapingRequest): ShapingResult {
        return shapeInternal(request, runtimeTraceCollector = null)
    }

    internal fun shapeWithRuntimeTrace(request: ShapingRequest): RuntimeShapingTraceResult {
        val runtimeTraceCollector = RuntimeShapingTraceCollector()
        return RuntimeShapingTraceResult(
            result = shapeInternal(request, runtimeTraceCollector = runtimeTraceCollector),
            runs = runtimeTraceCollector.snapshotRuns(),
        )
    }

    private fun shapeInternal(
        request: ShapingRequest,
        runtimeTraceCollector: RuntimeShapingTraceCollector?,
    ): ShapingResult {
        val requestedTextRange = codePointSafeTextRange(request.text, request.textRange) ?: return ShapingResult()
        val textRange = clusterSafeTextRange(request.text, requestedTextRange)
        val scopedRequest = request.copy(textRange = textRange)
        val scriptRuns = scriptItemizer.itemize(scopedRequest)
        val bidiResolution = (bidiResolver as? DetailedBidiResolver)
            ?.resolveDetailed(scopedRequest, requireParagraphOrdering = false)
        val bidiRuns = bidiResolution?.runs?.map { run ->
            BidiRun(
                textRange = run.logicalUtf16Range,
                level = run.embeddingLevel,
                isRightToLeft = run.direction == "R",
            )
        } ?: bidiResolver.resolve(scopedRequest)
        val shapingClusters = shapingClustersFor(request.text, textRange, scriptRuns, bidiRuns)
        val diagnostics = bidiResolution?.diagnostics.orEmpty().toMutableList()
        if (shapingClusters.isEmpty()) return ShapingResult(diagnostics = diagnostics)

        val glyphRuns = shapingClusters
            .groupByShapingState()
            .map { group -> shapeGroup(request, group, diagnostics, runtimeTraceCollector) }

        return ShapingResult(glyphRuns = glyphRuns, diagnostics = diagnostics)
    }

    private fun clusterSafeTextRange(text: String, requestedTextRange: IntRange): IntRange {
        val overlappingClusters = segmenter.segment(text)
            .mapNotNull { clusterRange -> codePointSafeTextRange(text, clusterRange) }
            .filter { clusterRange -> clusterRange.overlaps(requestedTextRange) }

        return if (overlappingClusters.isEmpty()) {
            requestedTextRange
        } else {
            overlappingClusters.minOf { it.first }..overlappingClusters.maxOf { it.last }
        }
    }

    private fun shapingClustersFor(
        text: String,
        textRange: IntRange,
        scriptRuns: List<ScriptRun>,
        bidiRuns: List<BidiRun>,
    ): List<BasicShapingCluster> =
        segmenter.segment(text)
            .mapNotNull { clusterRange -> codePointSafeTextRange(text, clusterRange) }
            .filter { clusterRange -> clusterRange.overlaps(textRange) }
            .distinct()
            .sortedBy { clusterRange -> clusterRange.first }
            .map { clusterRange ->
                BasicShapingCluster(
                    textRange = clusterRange,
                    script = scriptRuns.firstOrNull { it.textRange.overlaps(clusterRange) }?.script ?: SCRIPT_COMMON,
                    bidiRun = bidiRuns.firstOrNull { it.textRange.overlaps(clusterRange) }
                        ?: BidiRun(clusterRange, level = 0, isRightToLeft = false),
                )
            }

    private fun List<BasicShapingCluster>.groupByShapingState(): List<BasicShapingGroup> {
        val groups = mutableListOf<BasicShapingGroup>()
        for (cluster in this) {
            val previous = groups.lastOrNull()
            if (previous != null && previous.canAppend(cluster)) {
                previous.clusterRanges += cluster.textRange
            } else {
                groups += BasicShapingGroup(
                    script = cluster.script,
                    bidiLevel = cluster.bidiRun.level,
                    isRightToLeft = cluster.bidiRun.isRightToLeft,
                    clusterRanges = mutableListOf(cluster.textRange),
                )
            }
        }
        return groups
    }

    private fun shapeGroup(
        request: ShapingRequest,
        group: BasicShapingGroup,
        diagnostics: MutableList<ShapingDiagnostic>,
        runtimeTraceCollector: RuntimeShapingTraceCollector?,
    ): ShapedGlyphRun {
        val resolvedFeatures = resolveRuntimeFeatureSet(request, group)
        val glyphUnits = mutableListOf<ShapingGlyphUnit>()
        val clusterRanges = if (group.isRightToLeft) group.clusterRanges.asReversed() else group.clusterRanges

        clusterRanges.forEach { clusterRange ->
            for (codePointRange in codePointRanges(request.text, clusterRange)) {
                val glyphId = glyphMapper.glyphIdFor(request.typefaceId, codePointRange.codePoint)
                if (glyphId == null) {
                    diagnostics += missingGlyphDiagnostic(codePointRange)
                }
                glyphUnits += ShapingGlyphUnit(
                    glyphId = glyphId ?: missingGlyphId,
                    textRange = clusterRange,
                    codePoint = codePointRange.codePoint,
                )
            }
        }

        applyStandardLigatures(request, resolvedFeatures, glyphUnits)
        runtimeTraceCollector?.beginRun(
            inputGlyphIds = glyphUnits.map(ShapingGlyphUnit::glyphId),
            featureOrder = resolvedFeatures.resolved.enabled.map { feature -> feature.tag },
            bidiLevel = group.bidiLevel,
        )
        applyGsubLookups(request, resolvedFeatures, glyphUnits, diagnostics, runtimeTraceCollector)
        val glyphIds = glyphUnits.map { it.glyphId }
        val clusters = glyphClustersFor(
            glyphUnits = glyphUnits,
            fontSize = request.fontSize,
            preservePerGlyphClusters = shouldPreservePerGlyphClusters(request.typefaceId, glyphIds),
        )
        runtimeTraceCollector?.recordPreGposClusters(clusters)
        val baseAdvanceX = clusters.sumOf { cluster -> cluster.advanceX.toDouble() }
        val totalAdvanceAdjustment = applyPositionAdjustments(
            request = request,
            group = group,
            features = resolvedFeatures,
            glyphIds = glyphIds,
            clusters = clusters,
            diagnostics = diagnostics,
            runtimeTraceCollector = runtimeTraceCollector,
        )

        return ShapedGlyphRun(
            glyphIds = glyphIds,
            clusters = clusters,
            advanceX = (baseAdvanceX + totalAdvanceAdjustment).toFloat(),
            advanceY = 0f,
            script = group.script,
            bidiLevel = group.bidiLevel,
            typefaceId = request.typefaceId,
            fontSize = request.fontSize,
        )
    }

    private fun missingGlyphDiagnostic(codePointRange: CodePointRange): ShapingDiagnostic =
        ShapingDiagnostic(
            code = MISSING_GLYPH_DIAGNOSTIC_CODE,
            message = "Missing glyph for U+${codePointRange.codePoint.toUpperHex()}.",
            textRange = codePointRange.textRange,
        )

    private fun applyStandardLigatures(
        request: ShapingRequest,
        features: RuntimeFeatureGateSet,
        glyphUnits: MutableList<ShapingGlyphUnit>,
    ) {
        if (!features.isRuntimeEnabled("liga")) return
        val ligatureGlyphId = glyphMapper.glyphIdFor(request.typefaceId, LATIN_SMALL_FI_LIGATURE_CODE_POINT)
            ?: return

        var glyphIndex = 0
        while (glyphIndex + 1 < glyphUnits.size) {
            val first = glyphUnits[glyphIndex]
            val second = glyphUnits[glyphIndex + 1]
            if (
                first.codePoint == LATIN_SMALL_F_CODE_POINT &&
                second.codePoint == LATIN_SMALL_I_CODE_POINT
            ) {
                glyphUnits.removeAt(glyphIndex)
                glyphUnits.removeAt(glyphIndex)
                glyphUnits.add(
                    glyphIndex,
                    ShapingGlyphUnit(
                        glyphId = ligatureGlyphId,
                        textRange = first.textRange.first..second.textRange.last,
                        codePoint = LATIN_SMALL_FI_LIGATURE_CODE_POINT,
                    ),
                )
                glyphIndex += 1
            } else {
                glyphIndex += 1
            }
        }
    }

    private fun applyGsubLookups(
        request: ShapingRequest,
        features: RuntimeFeatureGateSet,
        glyphUnits: MutableList<ShapingGlyphUnit>,
        diagnostics: MutableList<ShapingDiagnostic>,
        runtimeTraceCollector: RuntimeShapingTraceCollector?,
    ) {
        val typefaceId = request.typefaceId ?: return
        val gsubTable = gsubTablesByTypefaceId[typefaceId] ?: return
        val lookupsByIndex = gsubTable.lookups.associateBy { it.lookupIndex }

        gsubTable.lookups.forEach { lookup ->
            if (lookup.topLevelFeatureTags.isEmpty()) {
                return@forEach
            }
            if (lookup.topLevelFeatureTags.none(features::isRuntimeEnabled)) {
                return@forEach
            }
            val beforeUnits = runtimeTraceCollector?.let { glyphUnits.toList() }
            when (lookup) {
                is OpenTypeGsubSingleSubstitutionLookup -> applySingleSubstitutionLookup(glyphUnits, lookup)
                is OpenTypeGsubMultipleSubstitutionLookup -> applyMultipleSubstitutionLookup(glyphUnits, lookup)
                is OpenTypeGsubLigatureSubstitutionLookup -> applyLigatureSubstitutionLookup(glyphUnits, lookup)
                is OpenTypeGsubContextGlyphLookup -> applyContextGlyphLookup(glyphUnits, lookup, lookupsByIndex, diagnostics)
                is OpenTypeGsubContextClassLookup ->
                    applyContextClassLookup(glyphUnits, lookup, lookupsByIndex, diagnostics)
                is OpenTypeGsubContextCoverageLookup ->
                    applyContextCoverageLookup(glyphUnits, lookup, lookupsByIndex, diagnostics)
                is OpenTypeGsubChainingContextGlyphLookup ->
                    applyChainingContextGlyphLookup(glyphUnits, lookup, lookupsByIndex, diagnostics)
            }
            val afterUnits = runtimeTraceCollector?.let { glyphUnits.toList() }
            if (
                beforeUnits != null &&
                afterUnits != null &&
                beforeUnits.map(ShapingGlyphUnit::glyphId) != afterUnits.map(ShapingGlyphUnit::glyphId)
            ) {
                runtimeTraceCollector.recordGsub(
                    RuntimeGsubLookupTrace(
                        lookupIndex = lookup.lookupIndex,
                        lookupType = lookup.lookupType(),
                        featureTag = lookup.primaryFeatureTag(),
                        inputGlyphIds = beforeUnits.map(ShapingGlyphUnit::glyphId),
                        outputGlyphIds = afterUnits.map(ShapingGlyphUnit::glyphId),
                        clusterAction = gsubClusterAction(beforeUnits, afterUnits),
                        contextFormat = lookup.contextFormatOrNull(),
                    ),
                )
            }
        }
    }

    private fun applySingleSubstitutionLookup(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        lookup: OpenTypeGsubSingleSubstitutionLookup,
    ) {
        val replacements = lookup.substitutions.associateBy { it.inputGlyphId }
        glyphUnits.replaceAll { glyphUnit ->
            replacements[glyphUnit.glyphId]?.let { substitution ->
                glyphUnit.copy(glyphId = substitution.replacementGlyphId)
            } ?: glyphUnit
        }
    }

    private fun applyMultipleSubstitutionLookup(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        lookup: OpenTypeGsubMultipleSubstitutionLookup,
    ) {
        val substitutions = lookup.substitutions.associateBy { it.inputGlyphId }
        var glyphIndex = 0
        while (glyphIndex < glyphUnits.size) {
            val substitution = substitutions[glyphUnits[glyphIndex].glyphId]
            if (substitution == null) {
                glyphIndex += 1
                continue
            }

            val sourceRange = glyphUnits[glyphIndex].textRange
            glyphUnits.removeAt(glyphIndex)
            glyphUnits.addAll(
                glyphIndex,
                substitution.replacementGlyphIds.map { replacementGlyphId ->
                    ShapingGlyphUnit(glyphId = replacementGlyphId, textRange = sourceRange, codePoint = null)
                },
            )
            glyphIndex += substitution.replacementGlyphIds.size
        }
    }

    private fun applyLigatureSubstitutionLookup(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        lookup: OpenTypeGsubLigatureSubstitutionLookup,
    ) {
        var glyphIndex = 0
        while (glyphIndex < glyphUnits.size) {
            val substitution = lookup.substitutions.firstOrNull { candidate ->
                ligatureMatchesAt(glyphUnits, glyphIndex, candidate)
            }
            if (substitution == null) {
                glyphIndex += 1
                continue
            }

            val matchedUnits = glyphUnits.subList(glyphIndex, glyphIndex + substitution.inputGlyphIds.size).toList()
            repeat(substitution.inputGlyphIds.size) {
                glyphUnits.removeAt(glyphIndex)
            }
            glyphUnits.add(
                glyphIndex,
                ShapingGlyphUnit(
                    glyphId = substitution.replacementGlyphId,
                    textRange = matchedUnits.minOf { it.textRange.first }..matchedUnits.maxOf { it.textRange.last },
                    codePoint = null,
                ),
            )
            glyphIndex += 1
        }
    }

    private fun applyContextGlyphLookup(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        lookup: OpenTypeGsubContextGlyphLookup,
        lookupsByIndex: Map<Int, OpenTypeGsubLookup>,
        diagnostics: MutableList<ShapingDiagnostic>,
    ) {
        var glyphIndex = 0
        while (glyphIndex < glyphUnits.size) {
            val rule = lookup.rules.firstOrNull { contextGlyphRuleMatchesAt(glyphUnits, glyphIndex, it) }
            if (rule == null) {
                glyphIndex += 1
                continue
            }
            val stop = applyNestedLookupsForMatch(
                glyphUnits = glyphUnits,
                matchStart = glyphIndex,
                matchLength = rule.inputGlyphIds.size,
                nestedLookups = rule.nestedLookups,
                lookupsByIndex = lookupsByIndex,
                diagnostics = diagnostics,
                lookupStack = listOf(lookup.lookupIndex),
            )
            glyphIndex += if (stop) rule.inputGlyphIds.size else 1
        }
    }

    private fun applyContextClassLookup(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        lookup: OpenTypeGsubContextClassLookup,
        lookupsByIndex: Map<Int, OpenTypeGsubLookup>,
        diagnostics: MutableList<ShapingDiagnostic>,
    ) {
        var glyphIndex = 0
        while (glyphIndex < glyphUnits.size) {
            val match = lookup.contextClassSubtables().asSequence().mapNotNull { subtable ->
                subtable.rules.firstOrNull {
                    contextClassRuleMatchesAt(glyphUnits, glyphIndex, subtable.firstGlyphCoverage, subtable.classDefinitions, it)
                }?.let { rule -> subtable to rule }
            }.firstOrNull()
            if (match == null) {
                glyphIndex += 1
                continue
            }
            val (_, rule) = match
            val stop = applyNestedLookupsForMatch(
                glyphUnits = glyphUnits,
                matchStart = glyphIndex,
                matchLength = rule.inputClasses.size,
                nestedLookups = rule.nestedLookups,
                lookupsByIndex = lookupsByIndex,
                diagnostics = diagnostics,
                lookupStack = listOf(lookup.lookupIndex),
            )
            glyphIndex += if (stop) rule.inputClasses.size else 1
        }
    }

    private fun applyContextCoverageLookup(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        lookup: OpenTypeGsubContextCoverageLookup,
        lookupsByIndex: Map<Int, OpenTypeGsubLookup>,
        diagnostics: MutableList<ShapingDiagnostic>,
    ) {
        var glyphIndex = 0
        while (glyphIndex < glyphUnits.size) {
            val rule = lookup.rules.firstOrNull { contextCoverageRuleMatchesAt(glyphUnits, glyphIndex, it) }
            if (rule == null) {
                glyphIndex += 1
                continue
            }
            val stop = applyNestedLookupsForMatch(
                glyphUnits = glyphUnits,
                matchStart = glyphIndex,
                matchLength = rule.inputCoverages.size,
                nestedLookups = rule.nestedLookups,
                lookupsByIndex = lookupsByIndex,
                diagnostics = diagnostics,
                lookupStack = listOf(lookup.lookupIndex),
            )
            glyphIndex += if (stop) rule.inputCoverages.size else 1
        }
    }

    private fun applyChainingContextGlyphLookup(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        lookup: OpenTypeGsubChainingContextGlyphLookup,
        lookupsByIndex: Map<Int, OpenTypeGsubLookup>,
        diagnostics: MutableList<ShapingDiagnostic>,
    ) {
        var glyphIndex = 0
        while (glyphIndex < glyphUnits.size) {
            val rule = lookup.rules.firstOrNull { chainingContextGlyphRuleMatchesAt(glyphUnits, glyphIndex, it) }
            if (rule == null) {
                glyphIndex += 1
                continue
            }
            val stop = applyNestedLookupsForMatch(
                glyphUnits = glyphUnits,
                matchStart = glyphIndex,
                matchLength = rule.inputGlyphIds.size,
                nestedLookups = rule.nestedLookups,
                lookupsByIndex = lookupsByIndex,
                diagnostics = diagnostics,
                lookupStack = listOf(lookup.lookupIndex),
            )
            glyphIndex += if (stop) rule.inputGlyphIds.size else 1
        }
    }

    private fun chainingContextGlyphRuleMatchesAt(
        glyphUnits: List<ShapingGlyphUnit>,
        glyphIndex: Int,
        rule: OpenTypeGsubChainingContextGlyphRule,
    ): Boolean {
        val inputEnd = glyphIndex + rule.inputGlyphIds.size
        if (inputEnd > glyphUnits.size) return false
        if (rule.inputGlyphIds.indices.any { offset ->
                glyphUnits[glyphIndex + offset].glyphId != rule.inputGlyphIds[offset]
            }) return false
        rule.backtrackGlyphIds.indices.reversed().forEach { offset ->
            val backtrackIndex = glyphIndex - offset - 1
            if (backtrackIndex < 0) return false
            if (glyphUnits[backtrackIndex].glyphId != rule.backtrackGlyphIds[offset]) return false
        }
        rule.lookAheadGlyphIds.indices.forEach { offset ->
            val lookaheadIndex = inputEnd + offset
            if (lookaheadIndex >= glyphUnits.size) return false
            if (glyphUnits[lookaheadIndex].glyphId != rule.lookAheadGlyphIds[offset]) return false
        }
        return true
    }

    private fun contextGlyphRuleMatchesAt(
        glyphUnits: List<ShapingGlyphUnit>,
        glyphIndex: Int,
        rule: OpenTypeGsubContextGlyphRule,
    ): Boolean {
        val endIndex = glyphIndex + rule.inputGlyphIds.size
        if (endIndex > glyphUnits.size) return false
        return rule.inputGlyphIds.indices.all { offset ->
            glyphUnits[glyphIndex + offset].glyphId == rule.inputGlyphIds[offset]
        }
    }

    private fun contextClassRuleMatchesAt(
        glyphUnits: List<ShapingGlyphUnit>,
        glyphIndex: Int,
        firstGlyphCoverage: Set<Int>,
        classDefinitions: Map<Int, Int>,
        rule: OpenTypeGsubContextClassRule,
    ): Boolean {
        val endIndex = glyphIndex + rule.inputClasses.size
        if (endIndex > glyphUnits.size) return false
        if (firstGlyphCoverage.isNotEmpty() && glyphUnits[glyphIndex].glyphId !in firstGlyphCoverage) return false
        return rule.inputClasses.indices.all { offset ->
            val glyphClass = classDefinitions[glyphUnits[glyphIndex + offset].glyphId] ?: 0
            glyphClass == rule.inputClasses[offset]
        }
    }

    private fun contextCoverageRuleMatchesAt(
        glyphUnits: List<ShapingGlyphUnit>,
        glyphIndex: Int,
        rule: OpenTypeGsubContextCoverageRule,
    ): Boolean {
        val endIndex = glyphIndex + rule.inputCoverages.size
        if (endIndex > glyphUnits.size) return false
        return rule.inputCoverages.indices.all { offset ->
            glyphUnits[glyphIndex + offset].glyphId in rule.inputCoverages[offset]
        }
    }

    private fun applyNestedLookupsForMatch(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        matchStart: Int,
        matchLength: Int,
        nestedLookups: List<OpenTypeGsubNestedLookupRecord>,
        lookupsByIndex: Map<Int, OpenTypeGsubLookup>,
        diagnostics: MutableList<ShapingDiagnostic>,
        lookupStack: List<Int>,
    ): Boolean {
        val textRange = glyphUnits.subList(matchStart, matchStart + matchLength).let { matched ->
            matched.minOf { it.textRange.first }..matched.maxOf { it.textRange.last }
        }
        val matchSnapshot = glyphUnits.toList()
        val positionShifts = IntArray(matchLength)
        for (record in nestedLookups) {
            if (record.sequenceIndex !in 0 until matchLength) {
                diagnostics += ShapingDiagnostic(
                    code = TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE,
                    message = "GSUB contextual nested lookup sequence index is outside the matched glyph range.",
                    textRange = textRange,
                )
                glyphUnits.clear()
                glyphUnits.addAll(matchSnapshot)
                return true
            }
            val nestedLookup = lookupsByIndex[record.lookupIndex]
            if (nestedLookup == null) {
                diagnostics += ShapingDiagnostic(
                    code = TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE,
                    message = "GSUB contextual nested lookup index is missing from the lookup list.",
                    textRange = textRange,
                )
                glyphUnits.clear()
                glyphUnits.addAll(matchSnapshot)
                return true
            }
            if (record.lookupIndex in lookupStack) {
                diagnostics += ShapingDiagnostic(
                    code = TEXT_SHAPING_LOOKUP_CYCLE_DETECTED_DIAGNOSTIC_CODE,
                    message = "GSUB contextual nested lookup cycle detected.",
                    textRange = textRange,
                )
                glyphUnits.clear()
                glyphUnits.addAll(matchSnapshot)
                return true
            }
            val targetIndex = matchStart + record.sequenceIndex + positionShifts[record.sequenceIndex]
            if (targetIndex !in glyphUnits.indices) {
                diagnostics += ShapingDiagnostic(
                    code = TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE,
                    message = "GSUB contextual nested lookup sequence index is outside the matched glyph range.",
                    textRange = textRange,
                )
                glyphUnits.clear()
                glyphUnits.addAll(matchSnapshot)
                return true
            }
            val sizeBefore = glyphUnits.size
            val shouldStop = applyLookupAtIndex(
                glyphUnits = glyphUnits,
                glyphIndex = targetIndex,
                lookup = nestedLookup,
                lookupsByIndex = lookupsByIndex,
                diagnostics = diagnostics,
                lookupStack = lookupStack + record.lookupIndex,
                textRange = textRange,
            )
            if (shouldStop) {
                glyphUnits.clear()
                glyphUnits.addAll(matchSnapshot)
                return true
            }
            val sizeDelta = glyphUnits.size - sizeBefore
            if (sizeDelta != 0) {
                for (position in record.sequenceIndex + 1 until matchLength) {
                    positionShifts[position] += sizeDelta
                }
            }
        }
        return false
    }

    private fun applyLookupAtIndex(
        glyphUnits: MutableList<ShapingGlyphUnit>,
        glyphIndex: Int,
        lookup: OpenTypeGsubLookup,
        lookupsByIndex: Map<Int, OpenTypeGsubLookup>,
        diagnostics: MutableList<ShapingDiagnostic>,
        lookupStack: List<Int>,
        textRange: IntRange,
    ): Boolean {
        when (lookup) {
            is OpenTypeGsubSingleSubstitutionLookup -> {
                lookup.substitutions.firstOrNull { it.inputGlyphId == glyphUnits[glyphIndex].glyphId }?.let { substitution ->
                    glyphUnits[glyphIndex] = glyphUnits[glyphIndex].copy(glyphId = substitution.replacementGlyphId)
                }
            }
            is OpenTypeGsubMultipleSubstitutionLookup -> {
                lookup.substitutions.firstOrNull { it.inputGlyphId == glyphUnits[glyphIndex].glyphId }?.let { substitution ->
                    val sourceRange = glyphUnits[glyphIndex].textRange
                    glyphUnits.removeAt(glyphIndex)
                    glyphUnits.addAll(
                        glyphIndex,
                        substitution.replacementGlyphIds.map { replacementGlyphId ->
                            ShapingGlyphUnit(glyphId = replacementGlyphId, textRange = sourceRange, codePoint = null)
                        },
                    )
                }
            }
            is OpenTypeGsubLigatureSubstitutionLookup -> {
                lookup.substitutions.firstOrNull { ligatureMatchesAt(glyphUnits, glyphIndex, it) }?.let { substitution ->
                    val matchedUnits = glyphUnits.subList(glyphIndex, glyphIndex + substitution.inputGlyphIds.size).toList()
                    repeat(substitution.inputGlyphIds.size) {
                        glyphUnits.removeAt(glyphIndex)
                    }
                    glyphUnits.add(
                        glyphIndex,
                        ShapingGlyphUnit(
                            glyphId = substitution.replacementGlyphId,
                            textRange = matchedUnits.minOf { it.textRange.first }..matchedUnits.maxOf { it.textRange.last },
                            codePoint = null,
                        ),
                    )
                }
            }
            is OpenTypeGsubContextGlyphLookup -> {
                val rule = lookup.rules.firstOrNull { contextGlyphRuleMatchesAt(glyphUnits, glyphIndex, it) } ?: return false
                val shouldStop = applyNestedLookupsForMatch(
                    glyphUnits = glyphUnits,
                    matchStart = glyphIndex,
                    matchLength = rule.inputGlyphIds.size,
                    nestedLookups = rule.nestedLookups,
                    lookupsByIndex = lookupsByIndex,
                    diagnostics = diagnostics,
                    lookupStack = lookupStack,
                )
                if (shouldStop) return true
            }
            is OpenTypeGsubContextClassLookup -> {
                val rule = lookup.contextClassSubtables().asSequence().mapNotNull { subtable ->
                    subtable.rules.firstOrNull {
                        contextClassRuleMatchesAt(glyphUnits, glyphIndex, subtable.firstGlyphCoverage, subtable.classDefinitions, it)
                    }
                }.firstOrNull() ?: return false
                val shouldStop = applyNestedLookupsForMatch(
                    glyphUnits = glyphUnits,
                    matchStart = glyphIndex,
                    matchLength = rule.inputClasses.size,
                    nestedLookups = rule.nestedLookups,
                    lookupsByIndex = lookupsByIndex,
                    diagnostics = diagnostics,
                    lookupStack = lookupStack,
                )
                if (shouldStop) return true
            }
            is OpenTypeGsubContextCoverageLookup -> {
                val rule = lookup.rules.firstOrNull { contextCoverageRuleMatchesAt(glyphUnits, glyphIndex, it) } ?: return false
                val shouldStop = applyNestedLookupsForMatch(
                    glyphUnits = glyphUnits,
                    matchStart = glyphIndex,
                    matchLength = rule.inputCoverages.size,
                    nestedLookups = rule.nestedLookups,
                    lookupsByIndex = lookupsByIndex,
                    diagnostics = diagnostics,
                    lookupStack = lookupStack,
                )
                if (shouldStop) return true
            }
            is OpenTypeGsubChainingContextGlyphLookup -> {
                val rule = lookup.rules.firstOrNull { chainingContextGlyphRuleMatchesAt(glyphUnits, glyphIndex, it) } ?: return false
                val shouldStop = applyNestedLookupsForMatch(
                    glyphUnits = glyphUnits,
                    matchStart = glyphIndex,
                    matchLength = rule.inputGlyphIds.size,
                    nestedLookups = rule.nestedLookups,
                    lookupsByIndex = lookupsByIndex,
                    diagnostics = diagnostics,
                    lookupStack = lookupStack,
                )
                if (shouldStop) return true
            }
        }
        val hasOverlappingGlyph = glyphUnits.any { glyph ->
            glyph.textRange.first <= textRange.last && glyph.textRange.last >= textRange.first
        }
        val escapedClusterRange = glyphUnits.any { glyph ->
            glyph.textRange.first <= textRange.last &&
                glyph.textRange.last >= textRange.first &&
                (glyph.textRange.first < textRange.first || glyph.textRange.last > textRange.last)
        }
        if (!hasOverlappingGlyph || escapedClusterRange) {
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE,
                message = "GSUB contextual lookup left the matched cluster range.",
                textRange = textRange,
            )
            return true
        }
        return false
    }

    private fun OpenTypeGsubContextClassLookup.contextClassSubtables(): List<OpenTypeGsubContextClassSubtable> =
        subtables.ifEmpty {
            listOf(
                OpenTypeGsubContextClassSubtable(
                    firstGlyphCoverage = firstGlyphCoverage,
                    classDefinitions = classDefinitions,
                    rules = rules,
                ),
            )
        }

    private fun ligatureMatchesAt(
        glyphUnits: List<ShapingGlyphUnit>,
        glyphIndex: Int,
        substitution: OpenTypeGsubLigatureSubstitution,
    ): Boolean {
        val endIndex = glyphIndex + substitution.inputGlyphIds.size
        if (endIndex > glyphUnits.size) return false
        return substitution.inputGlyphIds.indices.all { offset ->
            glyphUnits[glyphIndex + offset].glyphId == substitution.inputGlyphIds[offset]
        }
    }

    private fun shouldPreservePerGlyphClusters(typefaceId: TypefaceID?, glyphIds: List<Int>): Boolean {
        if (glyphIds.isEmpty()) return false
        val gdefTable = typefaceId?.let(gdefTablesByTypefaceId::get) ?: return false
        val gposTable = typefaceId.let(gposTablesByTypefaceId::get) ?: return false
        if (gdefTable.glyphClasses.isEmpty()) return false
        val relevantLookups = gposTable.lookups.filter { lookup ->
            lookup !is OpenTypeGposMalformedLookup &&
                lookup.featureTag in setOf("mark", "mkmk", "curs")
        }
        if (relevantLookups.isEmpty()) return false
        if (
            relevantLookups.any { lookup -> lookup.featureTag in setOf("mark", "mkmk") } &&
            glyphIds.any { glyphId -> gdefTable.glyphClasses[glyphId] == GDEF_MARK_GLYPH_CLASS }
        ) {
            return true
        }
        val cursiveLookups = relevantLookups.filterIsInstance<OpenTypeGposCursiveLookup>()
        if (cursiveLookups.isEmpty() || glyphIds.size < 2) return false
        return glyphIds.zipWithNext().any { (previousGlyphId, currentGlyphId) ->
            cursiveLookups.any { lookup ->
                val hasExitAnchor = lookup.attachments.any { attachment ->
                    attachment.glyphId == previousGlyphId && attachment.exitAnchor != null
                }
                val hasEntryAnchor = lookup.attachments.any { attachment ->
                    attachment.glyphId == currentGlyphId && attachment.entryAnchor != null
                }
                hasExitAnchor && hasEntryAnchor
            }
        }
    }

    private fun glyphClustersFor(
        glyphUnits: List<ShapingGlyphUnit>,
        fontSize: Float,
        preservePerGlyphClusters: Boolean,
    ): MutableList<GlyphCluster> {
        if (glyphUnits.isEmpty()) return mutableListOf()
        if (preservePerGlyphClusters) {
            return glyphUnits.mapIndexed { glyphIndex, glyphUnit ->
                GlyphCluster(
                    textRange = glyphUnit.textRange,
                    glyphRange = glyphIndex..glyphIndex,
                    advanceX = fontSize,
                )
            }.toMutableList()
        }

        val clusters = mutableListOf<GlyphCluster>()
        var glyphIndex = 0
        while (glyphIndex < glyphUnits.size) {
            val textRange = glyphUnits[glyphIndex].textRange
            var glyphEnd = glyphIndex
            while (glyphEnd + 1 < glyphUnits.size && glyphUnits[glyphEnd + 1].textRange == textRange) {
                glyphEnd += 1
            }
            clusters += GlyphCluster(
                textRange = textRange,
                glyphRange = glyphIndex..glyphEnd,
                advanceX = fontSize,
            )
            glyphIndex = glyphEnd + 1
        }
        return clusters
    }

    private fun applyPositionAdjustments(
        request: ShapingRequest,
        group: BasicShapingGroup,
        features: RuntimeFeatureGateSet,
        glyphIds: List<Int>,
        clusters: MutableList<GlyphCluster>,
        diagnostics: MutableList<ShapingDiagnostic>,
        runtimeTraceCollector: RuntimeShapingTraceCollector?,
    ): Double {
        if (
            kernTablesByTypefaceId.isEmpty() &&
            gposTablesByTypefaceId.isEmpty() &&
            gposSingleTablesByTypefaceId.isEmpty() &&
            gposPairTablesByTypefaceId.isEmpty()
        ) {
            if (group.script == SCRIPT_ARABIC) {
                if (features.isRuntimeEnabled("curs")) {
                    diagnostics += ShapingDiagnostic(
                        code = TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE,
                        message = "No GPOS table available for cursive attachment on typeface ${request.typefaceId?.value}.",
                        textRange = group.textRange(),
                    )
                }
                if (features.isRuntimeEnabled("mark") || features.isRuntimeEnabled("mkmk")) {
                    diagnostics += ShapingDiagnostic(
                        code = TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE,
                        message = "No GPOS table available for mark positioning on typeface ${request.typefaceId?.value}.",
                        textRange = group.textRange(),
                    )
                }
            }
            return 0.0
        }

        val adjustmentContext = adjustmentContextFor(request, group.textRange(), diagnostics) ?: return 0.0
        val glyphClusterIndexes = glyphClusterIndexes(clusters, glyphIds.size)
        var totalAdvanceAdjustment = 0.0
        totalAdvanceAdjustment += applyGposSingleAdjustments(
            glyphIds = glyphIds,
            clusters = clusters,
            glyphClusterIndexes = glyphClusterIndexes,
            features = features,
            adjustmentContext = adjustmentContext,
            diagnostics = diagnostics,
        )
            totalAdvanceAdjustment += applyGposAnchorAdjustments(
                glyphIds = glyphIds,
                clusters = clusters,
                glyphClusterIndexes = glyphClusterIndexes,
                features = features,
                adjustmentContext = adjustmentContext,
                script = group.script,
                textRange = group.textRange(),
                diagnostics = diagnostics,
                runtimeTraceCollector = runtimeTraceCollector,
            )
        if (!features.isRuntimeEnabled("kern")) {
            return totalAdvanceAdjustment
        }
        if (glyphIds.size < 2) {
            return totalAdvanceAdjustment
        }

        for (leftGlyphIndex in 0 until glyphIds.lastIndex) {
            val leftGlyphId = glyphIds[leftGlyphIndex]
            val rightGlyphId = glyphIds[leftGlyphIndex + 1]
            val pairAdjustment = adjustmentContext.lookupPairAdjustment(
                leftGlyphId = leftGlyphId,
                rightGlyphId = rightGlyphId,
                textRange = clusters.getOrNull(glyphClusterIndexes[leftGlyphIndex])?.textRange,
                diagnostics = diagnostics,
            )
            if (pairAdjustment == null) continue

            totalAdvanceAdjustment += applyValueRecordToCluster(
                valueRecord = pairAdjustment.firstValueRecord,
                clusterIndex = glyphClusterIndexes[leftGlyphIndex],
                clusters = clusters,
                fontUnitsToFontSizeUnitsScale = adjustmentContext.fontUnitsToFontSizeUnitsScale,
            )
            totalAdvanceAdjustment += applyValueRecordToCluster(
                valueRecord = pairAdjustment.secondValueRecord,
                clusterIndex = glyphClusterIndexes[leftGlyphIndex + 1],
                clusters = clusters,
                fontUnitsToFontSizeUnitsScale = adjustmentContext.fontUnitsToFontSizeUnitsScale,
            )
        }
        return totalAdvanceAdjustment
    }

    private fun applyGposAnchorAdjustments(
        glyphIds: List<Int>,
        clusters: MutableList<GlyphCluster>,
        glyphClusterIndexes: IntArray,
        features: RuntimeFeatureGateSet,
        adjustmentContext: BasicPositionAdjustmentContext,
        script: String,
        textRange: IntRange,
        diagnostics: MutableList<ShapingDiagnostic>,
        runtimeTraceCollector: RuntimeShapingTraceCollector?,
    ): Double {
        val gposTable = adjustmentContext.gposTable ?: return 0.0
        val enabledLookups = gposTable.lookups.filter { lookup ->
            features.isRuntimeEnabled(lookup.featureTag)
        }
        if (enabledLookups.isEmpty()) return 0.0

        enabledLookups.filterIsInstance<OpenTypeGposMalformedLookup>().forEach { lookup ->
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_LOOKUP_MALFORMED_DIAGNOSTIC_CODE,
                message = "GPOS lookup ${lookup.lookupIndex} for feature ${lookup.featureTag} is malformed: ${lookup.message}",
                textRange = textRange,
            )
        }
        if (enabledLookups.any { it is OpenTypeGposMalformedLookup }) {
            return 0.0
        }

        val gdefTable = adjustmentContext.gdefTable
        val markLookupsPresent = enabledLookups.any { lookup ->
            lookup is OpenTypeGposMarkToBaseLookup ||
                lookup is OpenTypeGposMarkToLigatureLookup ||
                lookup is OpenTypeGposMarkToMarkLookup
        }
        if (markLookupsPresent && gdefTable == null) {
            diagnostics += ShapingDiagnostic(
                code = TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE,
                message = "GDEF glyph classes are required for mark positioning on typeface ${adjustmentContext.typefaceId.value}.",
                textRange = textRange,
            )
            return 0.0
        }

        var totalAdvanceAdjustment = 0.0
        totalAdvanceAdjustment += applyMarkAnchorAdjustments(
            glyphIds = glyphIds,
            clusters = clusters,
            glyphClusterIndexes = glyphClusterIndexes,
            adjustmentContext = adjustmentContext,
            enabledLookups = enabledLookups,
            script = script,
            diagnostics = diagnostics,
            runtimeTraceCollector = runtimeTraceCollector,
        )
        totalAdvanceAdjustment += applyCursiveAnchorAdjustments(
            glyphIds = glyphIds,
            clusters = clusters,
            glyphClusterIndexes = glyphClusterIndexes,
            adjustmentContext = adjustmentContext,
            enabledLookups = enabledLookups,
            script = script,
            diagnostics = diagnostics,
            runtimeTraceCollector = runtimeTraceCollector,
        )
        return totalAdvanceAdjustment
    }

    private fun applyMarkAnchorAdjustments(
        glyphIds: List<Int>,
        clusters: MutableList<GlyphCluster>,
        glyphClusterIndexes: IntArray,
        adjustmentContext: BasicPositionAdjustmentContext,
        enabledLookups: List<OpenTypeGposLookup>,
        script: String,
        diagnostics: MutableList<ShapingDiagnostic>,
        runtimeTraceCollector: RuntimeShapingTraceCollector?,
    ): Double {
        val gdefTable = adjustmentContext.gdefTable ?: return 0.0
        var totalAdvanceAdjustment = 0.0

        glyphIds.forEachIndexed { glyphIndex, markGlyphId ->
            val markClass = gdefTable.glyphClasses[markGlyphId] ?: return@forEachIndexed
            if (markClass != GDEF_MARK_GLYPH_CLASS) return@forEachIndexed
            val clusterIndex = glyphClusterIndexes.getOrNull(glyphIndex) ?: return@forEachIndexed
            if (clusterIndex !in clusters.indices) return@forEachIndexed

            val markToMarkAttachment = nearestGlyphIndex(glyphIndex, glyphIds) { candidateIndex ->
                gdefTable.glyphClasses[glyphIds[candidateIndex]] == GDEF_MARK_GLYPH_CLASS
            }?.let { candidateIndex ->
                enabledLookups.filterIsInstance<OpenTypeGposMarkToMarkLookup>().firstNotNullOfOrNull { lookup ->
                    lookup.attachments.firstOrNull { attachment ->
                        attachment.mark1GlyphId == markGlyphId && attachment.mark2GlyphId == glyphIds[candidateIndex]
                    }?.let { attachment -> Triple(candidateIndex, lookup, attachment) }
                }
            }
            if (markToMarkAttachment != null) {
                val (candidateIndex, lookup, attachment) = markToMarkAttachment
                totalAdvanceAdjustment += attachMarkGlyph(
                    clusters = clusters,
                    currentClusterIndex = clusterIndex,
                    targetClusterIndex = glyphClusterIndexes[candidateIndex],
                    markAnchor = attachment.mark1Anchor,
                    targetAnchor = attachment.mark2Anchor,
                    scale = adjustmentContext.fontUnitsToFontSizeUnitsScale,
                )
                runtimeTraceCollector?.recordGpos(
                    RuntimeGposLookupTrace(
                        lookupIndex = lookup.lookupIndex,
                        lookupType = lookup.lookupType,
                        featureTag = lookup.featureTag,
                        matchedGlyphIds = listOf(markGlyphId, glyphIds[candidateIndex]),
                        glyphClasses = listOf(GDEF_MARK_GLYPH_CLASS, GDEF_MARK_GLYPH_CLASS),
                        markClass = attachment.markClass,
                        anchorFormats = listOf(attachment.mark1Anchor.format, attachment.mark2Anchor.format),
                        attachmentVector = listOf(
                            attachment.mark2Anchor.x - attachment.mark1Anchor.x,
                            attachment.mark2Anchor.y - attachment.mark1Anchor.y,
                        ),
                    ),
                )
                return@forEachIndexed
            }

            val baseOrLigatureIndex = nearestGlyphIndex(glyphIndex, glyphIds) { candidateIndex ->
                gdefTable.glyphClasses[glyphIds[candidateIndex]] in setOf(GDEF_BASE_GLYPH_CLASS, GDEF_LIGATURE_GLYPH_CLASS)
            } ?: return@forEachIndexed
            val baseOrLigatureGlyphId = glyphIds[baseOrLigatureIndex]
            when (gdefTable.glyphClasses[baseOrLigatureGlyphId]) {
                GDEF_BASE_GLYPH_CLASS -> {
                    val lookupAndAttachment =
                        enabledLookups.filterIsInstance<OpenTypeGposMarkToBaseLookup>().firstNotNullOfOrNull { lookup ->
                            lookup.attachments.firstOrNull { candidate ->
                                candidate.markGlyphId == markGlyphId && candidate.baseGlyphId == baseOrLigatureGlyphId
                            }?.let { attachment -> lookup to attachment }
                        }
                    if (lookupAndAttachment != null) {
                        val (lookup, attachment) = lookupAndAttachment
                        totalAdvanceAdjustment += attachMarkGlyph(
                            clusters = clusters,
                            currentClusterIndex = clusterIndex,
                            targetClusterIndex = glyphClusterIndexes[baseOrLigatureIndex],
                            markAnchor = attachment.markAnchor,
                            targetAnchor = attachment.baseAnchor,
                            scale = adjustmentContext.fontUnitsToFontSizeUnitsScale,
                        )
                        runtimeTraceCollector?.recordGpos(
                            RuntimeGposLookupTrace(
                                lookupIndex = lookup.lookupIndex,
                                lookupType = lookup.lookupType,
                                featureTag = lookup.featureTag,
                                matchedGlyphIds = listOf(baseOrLigatureGlyphId, markGlyphId),
                                glyphClasses = listOf(GDEF_BASE_GLYPH_CLASS, GDEF_MARK_GLYPH_CLASS),
                                markClass = attachment.markClass,
                                anchorFormats = listOf(attachment.baseAnchor.format, attachment.markAnchor.format),
                                attachmentVector = listOf(
                                    attachment.baseAnchor.x - attachment.markAnchor.x,
                                    attachment.baseAnchor.y - attachment.markAnchor.y,
                                ),
                            ),
                        )
                    } else {
                        diagnostics += ShapingDiagnostic(
                            code = markPositioningDiagnosticCode(script),
                            message = "No mark-to-base attachment matched glyphs $baseOrLigatureGlyphId,$markGlyphId on typeface ${adjustmentContext.typefaceId.value}.",
                            textRange = clusters[clusterIndex].textRange,
                        )
                    }
                }
                GDEF_LIGATURE_GLYPH_CLASS -> {
                    val lookupAttachments = enabledLookups.filterIsInstance<OpenTypeGposMarkToLigatureLookup>().flatMap { lookup ->
                        lookup.attachments.filter { candidate ->
                            candidate.markGlyphId == markGlyphId && candidate.ligatureGlyphId == baseOrLigatureGlyphId
                        }.map { attachment -> lookup to attachment }
                    }
                    val attachments = lookupAttachments.map { (_, attachment) -> attachment }
                    val distinctComponentIndexes = attachments.map(OpenTypeGposMarkToLigatureAttachment::componentIndex).distinct()
                    val lookupAndAttachment =
                        if (distinctComponentIndexes.size == 1) lookupAttachments.firstOrNull() else null
                    if (lookupAndAttachment != null) {
                        val (lookup, attachment) = lookupAndAttachment
                        totalAdvanceAdjustment += attachMarkGlyph(
                            clusters = clusters,
                            currentClusterIndex = clusterIndex,
                            targetClusterIndex = glyphClusterIndexes[baseOrLigatureIndex],
                            markAnchor = attachment.markAnchor,
                            targetAnchor = attachment.ligatureAnchor,
                            scale = adjustmentContext.fontUnitsToFontSizeUnitsScale,
                        )
                        runtimeTraceCollector?.recordGpos(
                            RuntimeGposLookupTrace(
                                lookupIndex = lookup.lookupIndex,
                                lookupType = lookup.lookupType,
                                featureTag = lookup.featureTag,
                                matchedGlyphIds = listOf(baseOrLigatureGlyphId, markGlyphId),
                                glyphClasses = listOf(GDEF_LIGATURE_GLYPH_CLASS, GDEF_MARK_GLYPH_CLASS),
                                markClass = attachment.markClass,
                                anchorFormats = listOf(attachment.ligatureAnchor.format, attachment.markAnchor.format),
                                attachmentVector = listOf(
                                    attachment.ligatureAnchor.x - attachment.markAnchor.x,
                                    attachment.ligatureAnchor.y - attachment.markAnchor.y,
                                ),
                            ),
                        )
                    } else {
                        val detail = if (distinctComponentIndexes.size > 1) {
                            "Ambiguous ligature component indexes ${distinctComponentIndexes.sorted()} matched"
                        } else {
                            "No mark-to-ligature attachment matched"
                        }
                        diagnostics += ShapingDiagnostic(
                            code = markPositioningDiagnosticCode(script),
                            message = "$detail glyphs $baseOrLigatureGlyphId,$markGlyphId on typeface ${adjustmentContext.typefaceId.value}.",
                            textRange = clusters[clusterIndex].textRange,
                        )
                    }
                }
            }
        }
        return totalAdvanceAdjustment
    }

    private fun applyCursiveAnchorAdjustments(
        glyphIds: List<Int>,
        clusters: MutableList<GlyphCluster>,
        glyphClusterIndexes: IntArray,
        adjustmentContext: BasicPositionAdjustmentContext,
        enabledLookups: List<OpenTypeGposLookup>,
        script: String,
        diagnostics: MutableList<ShapingDiagnostic>,
        runtimeTraceCollector: RuntimeShapingTraceCollector?,
    ): Double {
        val cursiveLookups = enabledLookups.filterIsInstance<OpenTypeGposCursiveLookup>()
        if (cursiveLookups.isEmpty()) return 0.0

        var totalAdvanceAdjustment = 0.0
        var matchedAttachmentChain = false
        for (glyphIndex in 1 until glyphIds.size) {
            val previousGlyphId = glyphIds[glyphIndex - 1]
            val currentGlyphId = glyphIds[glyphIndex]
            val previousClusterIndex = glyphClusterIndexes[glyphIndex - 1]
            val currentClusterIndex = glyphClusterIndexes[glyphIndex]
            if (previousClusterIndex !in clusters.indices || currentClusterIndex !in clusters.indices) continue

            val lookupAndMatch = cursiveLookups.firstNotNullOfOrNull { lookup ->
                val previousAttachment = lookup.attachments.firstOrNull { attachment ->
                    attachment.glyphId == previousGlyphId && attachment.exitAnchor != null
                }
                val currentAttachment = lookup.attachments.firstOrNull { attachment ->
                    attachment.glyphId == currentGlyphId && attachment.entryAnchor != null
                }
                if (previousAttachment == null || currentAttachment == null) null
                else Triple(lookup, previousAttachment.exitAnchor!!, currentAttachment.entryAnchor!!)
            }
            if (lookupAndMatch == null) continue

            matchedAttachmentChain = true
            val (lookup, exitAnchor, entryAnchor) = lookupAndMatch
            val deltaX = (exitAnchor.x - entryAnchor.x) * adjustmentContext.fontUnitsToFontSizeUnitsScale
            val deltaY = (exitAnchor.y - entryAnchor.y) * adjustmentContext.fontUnitsToFontSizeUnitsScale
            val previousCluster = clusters[previousClusterIndex]
            clusters[previousClusterIndex] = previousCluster.copy(
                advanceX = previousCluster.advanceX + deltaX.toFloat(),
            )
            val currentCluster = clusters[currentClusterIndex]
            clusters[currentClusterIndex] = currentCluster.copy(
                offsetY = currentCluster.offsetY + deltaY.toFloat(),
            )
            totalAdvanceAdjustment += deltaX
            runtimeTraceCollector?.recordGpos(
                RuntimeGposLookupTrace(
                    lookupIndex = lookup.lookupIndex,
                    lookupType = lookup.lookupType,
                    featureTag = lookup.featureTag,
                    matchedGlyphIds = listOf(previousGlyphId, currentGlyphId),
                    anchorFormats = listOf(exitAnchor.format, entryAnchor.format),
                    attachmentVector = listOf(exitAnchor.x - entryAnchor.x, exitAnchor.y - entryAnchor.y),
                    cursiveChain = listOf(listOf(previousGlyphId, currentGlyphId)),
                ),
            )
        }

        if (!matchedAttachmentChain && glyphIds.size > 1) {
            diagnostics += ShapingDiagnostic(
                code = cursiveAttachmentDiagnosticCode(script),
                message = "No cursive attachment chain matched the shaped glyph sequence on typeface ${adjustmentContext.typefaceId.value}.",
                textRange = clusters.minOf { cluster -> cluster.textRange.first }..clusters.maxOf { cluster -> cluster.textRange.last },
            )
        }
        return totalAdvanceAdjustment
    }

    private fun attachMarkGlyph(
        clusters: MutableList<GlyphCluster>,
        currentClusterIndex: Int,
        targetClusterIndex: Int,
        markAnchor: OpenTypeAnchor,
        targetAnchor: OpenTypeAnchor,
        scale: Double,
    ): Double {
        if (currentClusterIndex !in clusters.indices || targetClusterIndex !in clusters.indices) return 0.0
        val currentCluster = clusters[currentClusterIndex]
        val currentAdvance = currentCluster.advanceX.toDouble()
        val targetCluster = clusters[targetClusterIndex]
        val targetOriginX = clusterOriginX(clusters, targetClusterIndex) +
            targetCluster.offsetX -
            if (targetClusterIndex > currentClusterIndex) currentAdvance else 0.0
        val targetOriginY = targetCluster.offsetY
        val currentOriginX = clusterOriginX(clusters, currentClusterIndex)
        val desiredOriginX = targetOriginX + (targetAnchor.x - markAnchor.x) * scale
        val desiredOriginY = targetOriginY + (targetAnchor.y - markAnchor.y) * scale
        val advanceAdjustment = -currentAdvance
        clusters[currentClusterIndex] = currentCluster.copy(
            advanceX = 0f,
            offsetX = (desiredOriginX - currentOriginX).toFloat(),
            offsetY = desiredOriginY.toFloat(),
        )
        return advanceAdjustment
    }

    private fun gsubClusterAction(
        beforeUnits: List<ShapingGlyphUnit>,
        afterUnits: List<ShapingGlyphUnit>,
    ): String {
        val beforeRanges = beforeUnits.map(ShapingGlyphUnit::textRange).distinct()
        val afterRanges = afterUnits.map(ShapingGlyphUnit::textRange).distinct()
        return when {
            afterUnits.size > beforeUnits.size && beforeRanges.size == 1 && afterRanges == beforeRanges ->
                "expanded-single-cluster"
            afterUnits.size < beforeUnits.size && afterRanges.size <= beforeRanges.size ->
                "merged-clusters"
            else -> "preserved"
        }
    }

    private fun OpenTypeGsubLookup.primaryFeatureTag(): String =
        topLevelFeatureTags.firstOrNull() ?: "unknown"

    private fun OpenTypeGsubLookup.lookupType(): Int =
        when (this) {
            is OpenTypeGsubSingleSubstitutionLookup -> 1
            is OpenTypeGsubMultipleSubstitutionLookup -> 2
            is OpenTypeGsubLigatureSubstitutionLookup -> 4
            is OpenTypeGsubContextGlyphLookup,
            is OpenTypeGsubContextClassLookup,
            is OpenTypeGsubContextCoverageLookup,
            -> 5
            is OpenTypeGsubChainingContextGlyphLookup -> 6
        }

    private fun OpenTypeGsubLookup.contextFormatOrNull(): Int? =
        when (this) {
            is OpenTypeGsubContextGlyphLookup -> 1
            is OpenTypeGsubContextClassLookup -> 2
            is OpenTypeGsubContextCoverageLookup -> 3
            is OpenTypeGsubChainingContextGlyphLookup -> null
            else -> null
        }

    private class RuntimeShapingTraceCollector {
        private val runs = mutableListOf<MutableRuntimeShapingRunTrace>()
        private var currentRun: MutableRuntimeShapingRunTrace? = null

        fun beginRun(
            inputGlyphIds: List<Int>,
            featureOrder: List<String>,
            bidiLevel: Int,
        ) {
            currentRun = MutableRuntimeShapingRunTrace(
                inputGlyphIds = inputGlyphIds,
                featureOrder = featureOrder,
                bidiLevel = bidiLevel,
            ).also(runs::add)
        }

        fun recordPreGposClusters(clusters: List<GlyphCluster>) {
            currentRun?.preGposClusterMetrics = clusters.map { cluster ->
                RuntimeClusterMetric(
                    advanceX = cluster.advanceX,
                    offsetX = cluster.offsetX,
                    offsetY = cluster.offsetY,
                )
            }
        }

        fun recordGsub(trace: RuntimeGsubLookupTrace) {
            currentRun?.gsubLookups?.add(trace)
        }

        fun recordGpos(trace: RuntimeGposLookupTrace) {
            currentRun?.gposLookups?.add(trace)
        }

        fun snapshotRuns(): List<RuntimeShapingRunTrace> =
            runs.map { run ->
                RuntimeShapingRunTrace(
                    inputGlyphIds = run.inputGlyphIds,
                    featureOrder = run.featureOrder,
                    bidiLevel = run.bidiLevel,
                    preGposClusterMetrics = run.preGposClusterMetrics,
                    gsubLookups = run.gsubLookups.toList(),
                    gposLookups = run.gposLookups.toList(),
                )
            }

        private data class MutableRuntimeShapingRunTrace(
            val inputGlyphIds: List<Int>,
            val featureOrder: List<String>,
            val bidiLevel: Int,
            var preGposClusterMetrics: List<RuntimeClusterMetric> = emptyList(),
            val gsubLookups: MutableList<RuntimeGsubLookupTrace> = mutableListOf(),
            val gposLookups: MutableList<RuntimeGposLookupTrace> = mutableListOf(),
        )
    }

    private fun applyGposSingleAdjustments(
        glyphIds: List<Int>,
        clusters: MutableList<GlyphCluster>,
        glyphClusterIndexes: IntArray,
        features: RuntimeFeatureGateSet,
        adjustmentContext: BasicPositionAdjustmentContext,
        diagnostics: MutableList<ShapingDiagnostic>,
    ): Double {
        val gposSingleTable = adjustmentContext.gposSingleTable ?: return 0.0
        if (!features.isRuntimeEnabled("kern")) return 0.0
        var totalAdvanceAdjustment = 0.0
        glyphIds.forEachIndexed { glyphIndex, glyphId ->
            val valueRecord =
                try {
                    gposSingleTable.lookupAdjustment(glyphId)
                } catch (error: IllegalArgumentException) {
                    diagnostics += ShapingDiagnostic(
                        code = KERN_TABLE_UNAPPLIED_DIAGNOSTIC_CODE,
                        message = "${adjustmentContext.tableLabel} for typeface ${adjustmentContext.typefaceId.value} cannot apply to glyph $glyphId: ${error.message}",
                        textRange = clusters.getOrNull(glyphClusterIndexes[glyphIndex])?.textRange,
                    )
                    null
                } ?: return@forEachIndexed

            totalAdvanceAdjustment += applyValueRecordToCluster(
                valueRecord = valueRecord,
                clusterIndex = glyphClusterIndexes[glyphIndex],
                clusters = clusters,
                fontUnitsToFontSizeUnitsScale = adjustmentContext.fontUnitsToFontSizeUnitsScale,
            )
        }
        return totalAdvanceAdjustment
    }

    private fun applyValueRecordToCluster(
        valueRecord: OpenTypeGposValueRecord,
        clusterIndex: Int,
        clusters: MutableList<GlyphCluster>,
        fontUnitsToFontSizeUnitsScale: Double,
    ): Double {
        if (clusterIndex !in clusters.indices || valueRecord == OpenTypeGposValueRecord()) return 0.0
        val cluster = clusters[clusterIndex]
        val advanceAdjustment = valueRecord.xAdvance.toDouble() * fontUnitsToFontSizeUnitsScale
        val offsetXAdjustment = valueRecord.xPlacement.toDouble() * fontUnitsToFontSizeUnitsScale
        val offsetYAdjustment = valueRecord.yPlacement.toDouble() * fontUnitsToFontSizeUnitsScale
        clusters[clusterIndex] = cluster.copy(
            advanceX = cluster.advanceX + advanceAdjustment.toFloat(),
            offsetX = cluster.offsetX + offsetXAdjustment.toFloat(),
            offsetY = cluster.offsetY + offsetYAdjustment.toFloat(),
        )
        return advanceAdjustment
    }

    private fun resolveRuntimeFeatureSet(
        request: ShapingRequest,
        group: BasicShapingGroup,
    ): RuntimeFeatureGateSet {
        val requested = request.features.values.entries
            .sortedBy { it.key }
            .map { (tag, value) -> ShapingFeatureRequest(tag, value) }
        val scriptRun = ScriptItemizationRun(
            clusterRange = 0..0,
            utf16Range = group.textRange(),
            codePointRange = group.textRange(),
            selectedScript = group.script,
            openTypeScriptTags = emptyList(),
            extensionCandidates = listOf(group.script),
            languageHint = request.locale,
            reason = "basic-open-type-runtime",
        )
        val hasPolicy = RequiredScriptFeaturePolicies.rows.any { policy ->
            group.script in policy.selectedScripts
        }
        return if (hasPolicy) {
            RuntimeFeatureGateSet(
                resolved = RequiredScriptFeaturePolicies.resolve(scriptRun, requested),
                defaultEnabledWhenUnspecified = false,
            )
        } else {
            RuntimeFeatureGateSet(
                resolved = ResolvedFeatureSet(
                    requested = requested,
                    enabled = requested.filter { it.value > 0 },
                    disabled = requested.filter { it.value <= 0 },
                    languageSystem = DEFAULT_OPEN_TYPE_LANGUAGE_SYSTEM,
                ),
                defaultEnabledWhenUnspecified = true,
            )
        }
    }

    private fun adjustmentContextFor(
        request: ShapingRequest,
        textRange: IntRange,
        diagnostics: MutableList<ShapingDiagnostic>,
    ): BasicPositionAdjustmentContext? {
        val typefaceId = request.typefaceId
        if (typefaceId == null) {
            diagnostics += ShapingDiagnostic(
                code = KERN_TABLE_UNAPPLIED_DIAGNOSTIC_CODE,
                message = "Pair-position tables cannot apply because request typefaceId is missing.",
                textRange = textRange,
            )
            return null
        }

        val gposSingleTable = gposSingleTablesByTypefaceId[typefaceId]
        val gdefTable = gdefTablesByTypefaceId[typefaceId]
        val gposAnchorTable = gposTablesByTypefaceId[typefaceId]
        val gposTable = gposPairTablesByTypefaceId[typefaceId]
        val kernTable = kernTablesByTypefaceId[typefaceId]
        if (gdefTable == null && gposAnchorTable == null && gposSingleTable == null && gposTable == null && kernTable == null) return null
        val tableLabel = pairAdjustmentTableLabel(
            kernTable = kernTable,
            gposAnchorTable = gposAnchorTable,
            gposSingleTable = gposSingleTable,
            gposPairTable = gposTable,
        )

        val unitsPerEm = kernUnitsPerEmByTypefaceId[typefaceId]
        if (unitsPerEm == null) {
            diagnostics += ShapingDiagnostic(
                code = KERN_TABLE_UNAPPLIED_DIAGNOSTIC_CODE,
                message = "$tableLabel for typeface ${typefaceId.value} cannot apply because unitsPerEm is missing.",
                textRange = textRange,
            )
            return null
        }
        if (unitsPerEm <= 0) {
            diagnostics += ShapingDiagnostic(
                code = KERN_TABLE_UNAPPLIED_DIAGNOSTIC_CODE,
                message = "$tableLabel for typeface ${typefaceId.value} cannot apply because unitsPerEm $unitsPerEm is not positive.",
                textRange = textRange,
            )
            return null
        }

        return BasicPositionAdjustmentContext(
            typefaceId = typefaceId,
            gdefTable = gdefTable,
            gposTable = gposAnchorTable,
            gposSingleTable = gposSingleTable,
            kernTable = kernTable,
            gposPairTable = gposTable,
            tableLabel = tableLabel,
            fontUnitsToFontSizeUnitsScale = request.fontSize.toDouble() / unitsPerEm.toDouble(),
        )
    }

    private fun glyphClusterIndexes(clusters: List<GlyphCluster>, glyphCount: Int): IntArray {
        val indexes = IntArray(glyphCount) { -1 }
        clusters.forEachIndexed { clusterIndex, cluster ->
            for (glyphIndex in cluster.glyphRange) {
                if (glyphIndex in indexes.indices) {
                    indexes[glyphIndex] = clusterIndex
                }
            }
        }
        return indexes
    }

    private fun clusterOriginX(clusters: List<GlyphCluster>, clusterIndex: Int): Double =
        clusters.take(clusterIndex).sumOf { cluster -> cluster.advanceX.toDouble() }

    private fun nearestGlyphIndex(
        glyphIndex: Int,
        glyphIds: List<Int>,
        matches: (Int) -> Boolean,
    ): Int? {
        for (previousIndex in glyphIndex - 1 downTo 0) {
            if (previousIndex in glyphIds.indices && matches(previousIndex)) {
                return previousIndex
            }
        }
        for (nextIndex in glyphIndex + 1 until glyphIds.size) {
            if (matches(nextIndex)) {
                return nextIndex
            }
        }
        return null
    }

    private fun BasicPositionAdjustmentContext.lookupPairAdjustment(
        leftGlyphId: Int,
        rightGlyphId: Int,
        textRange: IntRange?,
        diagnostics: MutableList<ShapingDiagnostic>,
    ): OpenTypeGposPairAdjustment? =
        try {
            gposPairTable?.lookupAdjustment(leftGlyphId, rightGlyphId)
                ?: kernTable?.lookupKerningAdjustment(leftGlyphId, rightGlyphId)?.takeIf { it != 0 }?.let { xAdvance ->
                    OpenTypeGposPairAdjustment(leftGlyphId = leftGlyphId, rightGlyphId = rightGlyphId, xAdvance = xAdvance)
                }
        } catch (error: IllegalArgumentException) {
            diagnostics += ShapingDiagnostic(
                code = KERN_TABLE_UNAPPLIED_DIAGNOSTIC_CODE,
                message = "$tableLabel for typeface ${typefaceId.value} cannot apply to glyph pair $leftGlyphId,$rightGlyphId: ${error.message}",
                textRange = textRange,
            )
            null
        }

}

private fun cursiveAttachmentDiagnosticCode(script: String): String =
    if (script == SCRIPT_ARABIC) TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE
    else TEXT_SHAPING_CURSIVE_ATTACHMENT_UNAVAILABLE_DIAGNOSTIC_CODE

private fun markPositioningDiagnosticCode(script: String): String =
    if (script == SCRIPT_ARABIC) TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE
    else TEXT_SHAPING_MARK_POSITIONING_UNAVAILABLE_DIAGNOSTIC_CODE

/**
 * Shapes text through font fallback runs resolved by the pure Kotlin font core.
 *
 * The engine asks [fontResolver] for concrete [TypefaceID] assignments over the
 * requested text slice, then shapes each resolved run with an internal
 * [BasicOpenTypeShapingEngine] configured from [glyphMapper]. Supplying a
 * [CMapGlyphMapper] connects resolved faces to parsed SFNT `cmap` tables while
 * keeping this layer independent of renderers, scalers, glyph storage, and GPU
 * backends. The original [ShapingRequest.text] is always passed to the
 * delegate with absolute UTF-16 ranges, so emitted [GlyphCluster.textRange]
 * values remain in caller coordinates.
 *
 * Resolver output is normalized to [segmenter] cluster boundaries before
 * delegation. When a resolver splits a base plus combining-mark cluster across
 * multiple runs, the cluster is shaped once with the first covering typeface
 * and adjacent clusters with the same selected typeface are merged back into a
 * single delegated range. If different typefaces cover the same expanded
 * cluster, [CONFLICTING_FONT_RUN_DIAGNOSTIC_CODE] records the conflict and the
 * later conflicting typefaces are skipped for that cluster.
 *
 * Resolved font runs use [ResolvedFontRun.start] and [ResolvedFontRun.end] as
 * offsets relative to the fallback request text. Those offsets are intersected
 * with the cluster-expanded shaping range, sorted, and consumed without
 * duplicating overlapping resolver output. Gaps that no resolver run covers
 * produce [UNRESOLVED_FONT_RUN_DIAGNOSTIC_CODE] diagnostics and no glyphs. Both
 * unresolved fallback runs and `.notdef` glyph mapping use the same stable
 * `text.shaping.fallback-missing` diagnostic family; callers that need the
 * narrower cause must inspect the diagnostic message and text range.
 *
 * @param fontResolver Font core resolver used to assign concrete fallback font
 * runs for the requested text slice.
 * @param glyphMapper Code point to glyph id mapper used by the delegated
 * [BasicOpenTypeShapingEngine]. Use [CMapGlyphMapper] when parsed SFNT `cmap`
 * tables are available.
 * @param segmenter Text segmenter passed to the delegated shaping engine.
 * @param scriptItemizer Script itemizer passed to the delegated shaping engine.
 * @param bidiResolver Bidi resolver passed to the delegated shaping engine.
 * @param missingGlyphId Glyph id emitted by the delegated shaping engine when a
 * resolved font lacks a glyph mapping.
 * @param kernTablesByTypefaceId Parsed OpenType `kern` tables passed to the
 * delegated shaping engine by resolved typeface id.
 * @param kernUnitsPerEmByTypefaceId Design units per em passed to the delegated
 * shaping engine for scaling kern table values into font-size units.
 */
public class FallbackOpenTypeShapingEngine(
    private val fontResolver: FontResolver,
    glyphMapper: GlyphMapper,
    private val segmenter: TextSegmenter = BasicTextSegmenter(),
    scriptItemizer: ScriptItemizer = BasicScriptItemizer(),
    bidiResolver: BidiResolver = BasicBidiResolver(),
    missingGlyphId: Int = 0,
    gsubTablesByTypefaceId: Map<TypefaceID, OpenTypeGsubTable> = emptyMap(),
    kernTablesByTypefaceId: Map<TypefaceID, OpenTypeKernTable> = emptyMap(),
    gdefTablesByTypefaceId: Map<TypefaceID, OpenTypeGdefTable> = emptyMap(),
    gposTablesByTypefaceId: Map<TypefaceID, OpenTypeGposTable> = emptyMap(),
    gposSingleTablesByTypefaceId: Map<TypefaceID, OpenTypeGposSingleTable> = emptyMap(),
    gposPairTablesByTypefaceId: Map<TypefaceID, OpenTypeGposPairTable> = emptyMap(),
    kernUnitsPerEmByTypefaceId: Map<TypefaceID, Int> = emptyMap(),
) : OpenTypeShapingEngine {
    private val delegate = BasicOpenTypeShapingEngine(
        glyphMapper = glyphMapper,
        segmenter = segmenter,
        scriptItemizer = scriptItemizer,
        bidiResolver = bidiResolver,
        missingGlyphId = missingGlyphId,
        gsubTablesByTypefaceId = gsubTablesByTypefaceId,
        kernTablesByTypefaceId = kernTablesByTypefaceId,
        gdefTablesByTypefaceId = gdefTablesByTypefaceId,
        gposTablesByTypefaceId = gposTablesByTypefaceId,
        gposSingleTablesByTypefaceId = gposSingleTablesByTypefaceId,
        gposPairTablesByTypefaceId = gposPairTablesByTypefaceId,
        kernUnitsPerEmByTypefaceId = kernUnitsPerEmByTypefaceId,
    )

    /**
     * Resolves fallback font runs for [request] and shapes each resolved run.
     */
    override fun shape(request: ShapingRequest): ShapingResult {
        val requestedTextRange = codePointSafeTextRange(request.text, request.textRange) ?: return ShapingResult()
        val clusterTextRange = clusterSafeTextRange(request.text, requestedTextRange)
        val fallbackRequest = FallbackRequest(
            text = request.text.substring(clusterTextRange.first, clusterTextRange.last + 1),
            locale = request.locale,
            preferredFamilies = request.preferredFamilies,
        )

        val glyphRuns = mutableListOf<ShapedGlyphRun>()
        val diagnostics = mutableListOf<ShapingDiagnostic>()
        val rawResolvedRuns = fontResolver.resolve(fallbackRequest)
            .mapIndexedNotNull { order, run -> run.toAbsoluteShapingRun(clusterTextRange, order) }
        val resolvedRuns = clusterSafeResolvedRuns(request.text, clusterTextRange, rawResolvedRuns, diagnostics)
        var cursor = clusterTextRange.first

        for (run in resolvedRuns) {
            if (run.textRange.last < cursor) continue

            val runStart = maxOf(run.textRange.first, cursor)
            if (cursor < runStart) {
                diagnostics += unresolvedFontRunDiagnostic(cursor untilExclusive runStart)
            }

            val runRange = runStart..run.textRange.last
            val runResult = delegate.shape(
                request.copy(
                    textRange = runRange,
                    typefaceId = run.typefaceId,
                ),
            )
            glyphRuns += runResult.glyphRuns
            diagnostics += runResult.diagnostics
            cursor = run.textRange.last + 1
        }

        if (cursor <= clusterTextRange.last) {
            diagnostics += unresolvedFontRunDiagnostic(cursor..clusterTextRange.last)
        }

        return ShapingResult(glyphRuns = glyphRuns, diagnostics = diagnostics)
    }

    private fun clusterSafeTextRange(text: String, requestedTextRange: IntRange): IntRange {
        val overlappingClusters = clusterRanges(text, requestedTextRange)
        return if (overlappingClusters.isEmpty()) {
            requestedTextRange
        } else {
            overlappingClusters.minOf { it.first }..overlappingClusters.maxOf { it.last }
        }
    }

    private fun clusterRanges(text: String, textRange: IntRange): List<IntRange> =
        segmenter.segment(text)
            .mapNotNull { clusterRange -> codePointSafeTextRange(text, clusterRange) }
            .filter { clusterRange -> clusterRange.overlaps(textRange) }
            .distinct()
            .sortedBy { clusterRange -> clusterRange.first }

    private fun clusterSafeResolvedRuns(
        text: String,
        textRange: IntRange,
        rawRuns: List<ResolvedShapingFontRun>,
        diagnostics: MutableList<ShapingDiagnostic>,
    ): List<ResolvedShapingFontRun> {
        val resolvedRuns = mutableListOf<ResolvedShapingFontRun>()
        val clusters = clusterRanges(text, textRange).ifEmpty { listOf(textRange) }

        for (clusterRange in clusters) {
            val coveringRuns = rawRuns
                .filter { run -> run.textRange.overlaps(clusterRange) }
                .sortedWith(compareBy<ResolvedShapingFontRun> { it.textRange.first }.thenBy { it.order })
            if (coveringRuns.isEmpty()) continue

            val selectedRun = coveringRuns.first()
            val conflictingTypefaceIds = coveringRuns
                .map { run -> run.typefaceId }
                .distinct()
                .filter { typefaceId -> typefaceId != selectedRun.typefaceId }
            if (conflictingTypefaceIds.isNotEmpty()) {
                diagnostics += conflictingFontRunDiagnostic(clusterRange, selectedRun.typefaceId, conflictingTypefaceIds)
            }

            resolvedRuns.appendCluster(clusterRange, selectedRun.typefaceId, selectedRun.order)
        }

        return resolvedRuns
    }

    private fun MutableList<ResolvedShapingFontRun>.appendCluster(
        clusterRange: IntRange,
        typefaceId: TypefaceID,
        order: Int,
    ) {
        val previous = lastOrNull()
        if (previous != null &&
            previous.typefaceId == typefaceId &&
            previous.textRange.last + 1 == clusterRange.first
        ) {
            this[lastIndex] = previous.copy(textRange = previous.textRange.first..clusterRange.last)
        } else {
            this += ResolvedShapingFontRun(
                textRange = clusterRange,
                typefaceId = typefaceId,
                order = order,
            )
        }
    }

    private fun ResolvedFontRun.toAbsoluteShapingRun(
        requestedTextRange: IntRange,
        order: Int,
    ): ResolvedShapingFontRun? {
        if (end <= start) return null

        val absoluteStart = requestedTextRange.first + start
        val absoluteEnd = requestedTextRange.first + end - 1
        val first = absoluteStart.coerceAtLeast(requestedTextRange.first)
        val last = absoluteEnd.coerceAtMost(requestedTextRange.last)
        if (first > last) return null

        return ResolvedShapingFontRun(
            textRange = first..last,
            typefaceId = face.typeface.id,
            order = order,
        )
    }

    private infix fun Int.untilExclusive(endExclusive: Int): IntRange =
        this..(endExclusive - 1)

    private fun unresolvedFontRunDiagnostic(textRange: IntRange): ShapingDiagnostic =
        ShapingDiagnostic(
            code = UNRESOLVED_FONT_RUN_DIAGNOSTIC_CODE,
            message = "No resolved font for text range ${textRange.first}..${textRange.last}.",
            textRange = textRange,
        )

    private fun conflictingFontRunDiagnostic(
        textRange: IntRange,
        selectedTypefaceId: TypefaceID,
        skippedTypefaceIds: List<TypefaceID>,
    ): ShapingDiagnostic {
        val skipped = skippedTypefaceIds.joinToString(", ") { typefaceId -> typefaceId.value.toString() }
        return ShapingDiagnostic(
            code = CONFLICTING_FONT_RUN_DIAGNOSTIC_CODE,
            message = "Conflicting resolved fonts for cluster ${textRange.first}..${textRange.last}; using typeface ${selectedTypefaceId.value} and skipping $skipped.",
            textRange = textRange,
        )
    }

}

/**
 * Applies OpenType GSUB glyph substitution lookups.
 *
 * This boundary is intentionally conservative until parsed lookup tables are
 * wired into the text stack. Calling [substitute] without concrete lookup data
 * returns [run] unchanged instead of throwing at runtime or pretending to apply
 * full OpenType substitution.
 */
public class GSUBEngine {
    /**
     * Applies glyph substitutions for [run] with [features].
     */
    public fun substitute(run: ShapedGlyphRun, features: FeatureSet): ShapedGlyphRun =
        run
}

/**
 * Applies OpenType GPOS glyph positioning lookups.
 *
 * This boundary is intentionally conservative until parsed lookup tables are
 * wired into the text stack. Calling [position] without concrete lookup data
 * returns [run] unchanged instead of throwing at runtime or inventing glyph
 * offsets.
 */
public class GPOSEngine {
    /**
     * Applies glyph positioning for [run] with [features].
     */
    public fun position(run: ShapedGlyphRun, features: FeatureSet): ShapedGlyphRun =
        run
}

/**
 * Stores OpenType GDEF metadata needed by GSUB and GPOS.
 *
 * @property glyphClasses Map from glyph id to OpenType glyph class.
 * @property markAttachmentClasses Map from glyph id to mark attachment class.
 * @property ligatureCaretPositions Map from glyph id to caret x positions for ligature editing.
 */
public data class GDEFData(
    public val glyphClasses: Map<Int, Int> = emptyMap(),
    public val markAttachmentClasses: Map<Int, Int> = emptyMap(),
    public val ligatureCaretPositions: Map<Int, List<Float>> = emptyMap(),
)

/**
 * Detects emoji sequences and maps them to emoji glyph clusters when a font supports them.
 *
 * This shaper does not perform font-specific emoji substitution. It detects
 * bounded emoji-style clusters that later GSUB/COLR/bitmap routing can map to
 * actual glyphs: a base emoji with optional variation selectors, followed by
 * zero or more ZWJ plus base emoji parts with their own optional variation
 * selectors. Non-emoji text is skipped.
 */
public enum class EmojiSequenceKind {
    Base,
    VariationSelector,
    SkinTone,
    ZWJ,
    Keycap,
    Flag,
    Unsupported,
}

public data class EmojiSequenceFact(
    public val textRange: IntRange,
    public val kind: EmojiSequenceKind,
    public val codePoints: List<Int>,
)

public class EmojiSequenceShaper {
    /**
     * Shapes emoji sequences in [request].
     */
    public fun shapeEmoji(request: ShapingRequest): List<GlyphCluster> {
        val facts = sequenceFacts(request)
        return facts.mapIndexed { index, fact ->
            GlyphCluster(
                textRange = fact.textRange,
                glyphRange = index..index,
                advanceX = request.fontSize,
            )
        }
    }

    /**
     * Extracts typed emoji sequence facts for [request].
     */
    public fun sequenceFacts(request: ShapingRequest): List<EmojiSequenceFact> {
        val textRange = codePointSafeTextRange(request.text, request.textRange) ?: return emptyList()
        val codePoints = codePointRanges(request.text, textRange)
        val facts = mutableListOf<EmojiSequenceFact>()
        var index = 0

        while (index < codePoints.size) {
            val sequence = emojiSequenceAt(codePoints, index)
            if (sequence == null) {
                index += 1
                continue
            }

            facts += EmojiSequenceFact(
                textRange = sequence.textRange,
                kind = sequence.kind,
                codePoints = sequence.codePoints,
            )
            index = sequence.nextIndex
        }

        return facts
    }

    private fun emojiSequenceAt(codePoints: List<CodePointRange>, startIndex: Int): EmojiSequence? {
        val first = codePoints.getOrNull(startIndex) ?: return null
        flagSequenceAt(codePoints, startIndex)?.let { return it }
        keycapSequenceAt(codePoints, startIndex)?.let { return it }
        if (!isBaseEmoji(first.codePoint)) return null

        var index = consumeEmojiComponent(codePoints, startIndex) ?: return null
        var last = codePoints[index - 1].textRange.last
        var hasJoiner = false

        while (index + 1 < codePoints.size && codePoints[index].codePoint == ZERO_WIDTH_JOINER) {
            val nextComponentEnd = consumeEmojiComponent(codePoints, index + 1) ?: break
            last = codePoints[nextComponentEnd - 1].textRange.last
            index = nextComponentEnd
            hasJoiner = true
        }

        val codePointSlice = codePoints.subList(startIndex, index).map { it.codePoint }
        val kind = when {
            hasJoiner && isExplicitUnsupportedEmojiSequence(codePointSlice) -> EmojiSequenceKind.Unsupported
            hasJoiner -> EmojiSequenceKind.ZWJ
            codePointSlice.any(::isEmojiModifier) -> EmojiSequenceKind.SkinTone
            codePointSlice.any(::isVariationSelector) -> EmojiSequenceKind.VariationSelector
            else -> EmojiSequenceKind.Base
        }
        return EmojiSequence(
            textRange = first.textRange.first..last,
            kind = kind,
            codePoints = codePointSlice,
            nextIndex = index,
        )
    }

    private fun flagSequenceAt(codePoints: List<CodePointRange>, startIndex: Int): EmojiSequence? {
        val first = codePoints.getOrNull(startIndex) ?: return null
        val second = codePoints.getOrNull(startIndex + 1) ?: return null
        if (!isRegionalIndicator(first.codePoint) || !isRegionalIndicator(second.codePoint)) return null
        return EmojiSequence(
            textRange = first.textRange.first..second.textRange.last,
            kind = EmojiSequenceKind.Flag,
            codePoints = listOf(first.codePoint, second.codePoint),
            nextIndex = startIndex + 2,
        )
    }

    private fun keycapSequenceAt(codePoints: List<CodePointRange>, startIndex: Int): EmojiSequence? {
        val first = codePoints.getOrNull(startIndex) ?: return null
        if (!isKeycapBase(first.codePoint)) return null

        var index = startIndex + 1
        if (codePoints.getOrNull(index)?.codePoint == VARIATION_SELECTOR_16) {
            index += 1
        }
        val combiningKeycap = codePoints.getOrNull(index) ?: return null
        if (combiningKeycap.codePoint != COMBINING_ENCLOSING_KEYCAP) return null

        return EmojiSequence(
            textRange = first.textRange.first..combiningKeycap.textRange.last,
            kind = EmojiSequenceKind.Keycap,
            codePoints = codePoints.subList(startIndex, index + 1).map { it.codePoint },
            nextIndex = index + 1,
        )
    }

    private fun consumeEmojiComponent(codePoints: List<CodePointRange>, startIndex: Int): Int? {
        if (!isBaseEmoji(codePoints.getOrNull(startIndex)?.codePoint ?: return null)) return null
        var index = startIndex + 1
        while (index < codePoints.size && isVariationSelector(codePoints[index].codePoint)) {
            index += 1
        }
        if (index < codePoints.size && isEmojiModifier(codePoints[index].codePoint)) {
            index += 1
        }
        while (index < codePoints.size && isVariationSelector(codePoints[index].codePoint)) {
            index += 1
        }
        return index
    }

    // Keep unsupported-sequence refusals fixture-bounded until broader Unicode
    // sequence coverage lands with pinned denominator evidence.
    private fun isExplicitUnsupportedEmojiSequence(codePoints: List<Int>): Boolean =
        codePoints == EXPLICIT_UNSUPPORTED_EMOJI_SEQUENCE

    private companion object {
        val EXPLICIT_UNSUPPORTED_EMOJI_SEQUENCE: List<Int> =
            listOf(0x270C, 0xFE0F, 0x1F3FF, 0x200D, 0x1F4BB)
    }
}

/**
 * Records a non-fatal event observed during shaping.
 *
 * @property code Stable diagnostic code for tooling and tests.
 * @property message Human-readable diagnostic message.
 * @property textRange Optional inclusive UTF-16 range associated with the diagnostic.
 */
public data class ShapingDiagnostic(
    public val code: String,
    public val message: String,
    public val textRange: IntRange? = null,
)

/**
 * Stable spec diagnostic family emitted when glyph or fallback font coverage is missing.
 */
public const val TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE: String = "text.shaping.fallback-missing"

/**
 * Stable spec diagnostic family emitted when a requested shaping feature cannot be applied.
 */
public const val TEXT_SHAPING_FEATURE_UNSUPPORTED_DIAGNOSTIC_CODE: String = "text.shaping.feature-unsupported"

/**
 * Stable spec diagnostic family emitted when shaping cluster invariants are violated.
 */
public const val TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE: String =
    "text.shaping.cluster-invariant-failed"

/**
 * Stable spec diagnostic family emitted when nested GSUB contextual lookups recurse back into
 * an already active lookup chain.
 */
public const val TEXT_SHAPING_LOOKUP_CYCLE_DETECTED_DIAGNOSTIC_CODE: String =
    "text.shaping.lookup-cycle-detected"

/**
 * Stable spec diagnostic family emitted when GDEF metadata is required for a shaping lookup.
 */
public const val TEXT_SHAPING_GDEF_REQUIRED_DIAGNOSTIC_CODE: String = "text.shaping.gdef-required"

/**
 * Stable spec diagnostic family emitted when mark positioning data cannot be applied.
 */
public const val TEXT_SHAPING_MARK_POSITIONING_UNAVAILABLE_DIAGNOSTIC_CODE: String =
    "text.shaping.mark-positioning-unavailable"

/**
 * Stable spec diagnostic family emitted when cursive attachment data cannot be applied.
 */
public const val TEXT_SHAPING_CURSIVE_ATTACHMENT_UNAVAILABLE_DIAGNOSTIC_CODE: String =
    "text.shaping.cursive-attachment-unavailable"

/**
 * Stable spec diagnostic family emitted when the shaping engine cannot form a valid Indic syllable.
 */
public const val TEXT_SHAPING_INDIC_SYLLABLE_UNSUPPORTED_DIAGNOSTIC_CODE: String =
    "text.shaping.indic-syllable-unsupported"

/**
 * Stable spec diagnostic family emitted when a Devanagari feature phase cannot be applied.
 */
public const val TEXT_SHAPING_DEVANAGARI_PHASE_UNSUPPORTED_DIAGNOSTIC_CODE: String =
    "text.shaping.devanagari-phase-unsupported"

/**
 * Stable spec diagnostic family emitted when cursive attachment is unavailable on an Arabic shaping run.
 */
public const val TEXT_SHAPING_ARABIC_CURSIVE_UNSUPPORTED_DIAGNOSTIC_CODE: String =
    "text.shaping.arabic-cursive-unsupported"

/**
 * Stable spec diagnostic family emitted when mark positioning is unavailable on an Arabic shaping run.
 */
public const val TEXT_SHAPING_ARABIC_MARK_UNSUPPORTED_DIAGNOSTIC_CODE: String =
    "text.shaping.arabic-mark-unsupported"

/**
 * Semantic alias emitted when glyph mapping falls back to `.notdef`.
 */
public const val MISSING_GLYPH_DIAGNOSTIC_CODE: String = TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE

/**
 * Semantic alias emitted when font fallback leaves a text range without a resolved font.
 */
public const val UNRESOLVED_FONT_RUN_DIAGNOSTIC_CODE: String = TEXT_SHAPING_FALLBACK_MISSING_DIAGNOSTIC_CODE

/**
 * Semantic alias emitted when an opt-in OpenType pair-position table is present but cannot be applied.
 */
public const val KERN_TABLE_UNAPPLIED_DIAGNOSTIC_CODE: String = TEXT_SHAPING_FEATURE_UNSUPPORTED_DIAGNOSTIC_CODE

/**
 * Semantic alias emitted when font fallback assigns different typefaces to the same shaping cluster.
 */
public const val CONFLICTING_FONT_RUN_DIAGNOSTIC_CODE: String =
    TEXT_SHAPING_CLUSTER_INVARIANT_FAILED_DIAGNOSTIC_CODE

private const val SCRIPT_LATIN = "Latn"
private const val SCRIPT_ARABIC = "Arab"
private const val SCRIPT_HEBREW = "Hebr"
private const val SCRIPT_COMMON = "Zyyy"
private const val SCRIPT_INHERITED = "Zinh"
private const val SCRIPT_EMOJI = "Zsye"
private const val ZERO_WIDTH_JOINER = 0x200D
private const val VARIATION_SELECTOR_16 = 0xFE0F
private const val COMBINING_ENCLOSING_KEYCAP = 0x20E3
private const val LATIN_SMALL_F_CODE_POINT = 0x0066
private const val LATIN_SMALL_I_CODE_POINT = 0x0069
private const val LATIN_SMALL_FI_LIGATURE_CODE_POINT = 0xFB01
private const val GDEF_BASE_GLYPH_CLASS = 1
private const val GDEF_LIGATURE_GLYPH_CLASS = 2
private const val GDEF_MARK_GLYPH_CLASS = 3

private const val BIDI_LEFT_TO_RIGHT = "L"
private const val BIDI_RIGHT_TO_LEFT = "R"
private const val BIDI_ARABIC_LETTER = "AL"
private const val BIDI_EUROPEAN_NUMBER = "EN"
private const val BIDI_ARABIC_NUMBER = "AN"
private const val BIDI_NONSPACING_MARK = "NSM"
private const val BIDI_BOUNDARY_NEUTRAL = "BN"
private const val BIDI_PARAGRAPH_SEPARATOR = "B"
private const val BIDI_SEGMENT_SEPARATOR = "S"
private const val BIDI_WHITESPACE = "WS"
private const val BIDI_OTHER_NEUTRAL = "ON"

private data class CodePointRange(
    val codePoint: Int,
    val textRange: IntRange,
)

private data class BasicShapingCluster(
    val textRange: IntRange,
    val script: String,
    val bidiRun: BidiRun,
)

private data class BasicShapingGroup(
    val script: String,
    val bidiLevel: Int,
    val isRightToLeft: Boolean,
    val clusterRanges: MutableList<IntRange>,
) {
    fun canAppend(cluster: BasicShapingCluster): Boolean {
        val lastClusterRange = clusterRanges.lastOrNull() ?: return false
        return script == cluster.script &&
            bidiLevel == cluster.bidiRun.level &&
            isRightToLeft == cluster.bidiRun.isRightToLeft &&
            lastClusterRange.last + 1 == cluster.textRange.first
    }
}

private fun BasicShapingGroup.textRange(): IntRange =
    clusterRanges.minOf { it.first }..clusterRanges.maxOf { it.last }

private data class RuntimeFeatureGateSet(
    val resolved: ResolvedFeatureSet,
    val defaultEnabledWhenUnspecified: Boolean,
) {
    fun isRuntimeEnabled(tag: String): Boolean {
        if (resolved.disabled.any { it.tag == tag }) return false
        return defaultEnabledWhenUnspecified || resolved.enabled.any { it.tag == tag }
    }
}

private data class ResolvedShapingFontRun(
    val textRange: IntRange,
    val typefaceId: TypefaceID,
    val order: Int,
)

private data class BasicPositionAdjustmentContext(
    val typefaceId: TypefaceID,
    val gdefTable: OpenTypeGdefTable?,
    val gposTable: OpenTypeGposTable?,
    val gposSingleTable: OpenTypeGposSingleTable?,
    val kernTable: OpenTypeKernTable?,
    val gposPairTable: OpenTypeGposPairTable?,
    val tableLabel: String,
    val fontUnitsToFontSizeUnitsScale: Double,
)

private data class ShapingGlyphUnit(
    val glyphId: Int,
    val textRange: IntRange,
    val codePoint: Int?,
)

private data class EmojiSequence(
    val textRange: IntRange,
    val kind: EmojiSequenceKind,
    val codePoints: List<Int>,
    val nextIndex: Int,
)

private enum class TextDirection {
    LeftToRight,
    RightToLeft,
}

private fun pairAdjustmentTableLabel(
    kernTable: OpenTypeKernTable?,
    gposAnchorTable: OpenTypeGposTable?,
    gposSingleTable: OpenTypeGposSingleTable?,
    gposPairTable: OpenTypeGposPairTable?,
): String =
    when {
        gposAnchorTable != null && (gposSingleTable != null || gposPairTable != null || kernTable != null) -> "GPOS position table"
        gposAnchorTable != null -> "GPOS anchor table"
        gposSingleTable != null && (gposPairTable != null || kernTable != null) -> "GPOS/Kern position table"
        gposSingleTable != null -> "GPOS single table"
        gposPairTable != null && kernTable != null -> "Pair-position table"
        gposPairTable != null -> "GPOS pair table"
        else -> "Kern table"
    }

private fun codePointRanges(text: String): List<CodePointRange> =
    codePointRanges(text, text.indices.takeUnless { it.isEmpty() })

private fun codePointRangesFor(request: ShapingRequest): List<CodePointRange> =
    codePointRanges(request.text, normalizedTextRange(request.text, request.textRange))

private fun codePointRanges(text: String, textRange: IntRange?): List<CodePointRange> {
    if (textRange == null) return emptyList()

    val ranges = mutableListOf<CodePointRange>()
    var index = textRange.first
    while (index <= textRange.last) {
        val codePoint = codePointAt(text, index, textRange.last)
        val last = if (codePoint > 0xFFFF && index < textRange.last) index + 1 else index
        ranges += CodePointRange(codePoint, index..last)
        index = last + 1
    }
    return ranges
}

private fun singleCodePointRange(text: String, textRange: IntRange): CodePointRange? =
    codePointRanges(text, textRange).singleOrNull()

private fun codePointSafeTextRange(text: String, requestedRange: IntRange): IntRange? {
    val normalizedRange = normalizedTextRange(text, requestedRange) ?: return null
    var first = normalizedRange.first
    var last = normalizedRange.last

    if (first > 0 && text[first].isLowSurrogate() && text[first - 1].isHighSurrogate()) {
        first -= 1
    }
    if (last < text.lastIndex && text[last].isHighSurrogate() && text[last + 1].isLowSurrogate()) {
        last += 1
    }

    return first..last
}

private fun normalizedTextRange(text: String, requestedRange: IntRange): IntRange? {
    if (text.isEmpty()) return null
    val first = requestedRange.first.coerceAtLeast(0)
    val last = requestedRange.last.coerceAtMost(text.lastIndex)
    return if (first <= last) first..last else null
}

private fun codePointAt(text: String, index: Int, lastIndex: Int): Int {
    val first = text[index].code
    if (first !in 0xD800..0xDBFF || index >= lastIndex) return first

    val second = text[index + 1].code
    return if (second in 0xDC00..0xDFFF) {
        ((first - 0xD800) shl 10) + (second - 0xDC00) + 0x10000
    } else {
        first
    }
}

private fun String.isStrongScript(): Boolean =
    this != SCRIPT_COMMON && this != SCRIPT_INHERITED

private fun IntRange.overlaps(other: IntRange): Boolean =
    first <= other.last && other.first <= last

private fun Int.toUpperHex(): String =
    toString(radix = 16).uppercase().padStart(4, '0')

private fun bidiRun(textRange: IntRange, direction: TextDirection, baseDirection: TextDirection): BidiRun {
    val level = when {
        direction == TextDirection.RightToLeft -> 1
        baseDirection == TextDirection.RightToLeft -> 2
        else -> 0
    }
    return BidiRun(textRange = textRange, level = level, isRightToLeft = level % 2 == 1)
}

private fun isLatin(codePoint: Int): Boolean =
    codePoint in 0x0041..0x005A ||
        codePoint in 0x0061..0x007A ||
        codePoint in 0x00C0..0x00FF && codePoint != 0x00D7 && codePoint != 0x00F7 ||
        codePoint in 0x0100..0x024F ||
        codePoint in 0x1E00..0x1EFF

private fun isHebrew(codePoint: Int): Boolean =
    codePoint in 0x0590..0x05FF

private fun isArabic(codePoint: Int): Boolean =
    codePoint in 0x0600..0x06FF ||
        codePoint in 0x0750..0x077F ||
        codePoint in 0x08A0..0x08FF ||
        codePoint in 0xFB50..0xFDFF ||
        codePoint in 0xFE70..0xFEFC

private fun isArabicNumber(codePoint: Int): Boolean =
    codePoint in 0x0660..0x0669 ||
        codePoint in 0x06F0..0x06F9

private fun isEuropeanNumber(codePoint: Int): Boolean =
    codePoint in 0x0030..0x0039 ||
        codePoint in 0xFF10..0xFF19

private fun isCombiningMark(codePoint: Int): Boolean =
    codePoint in 0x0300..0x036F ||
        codePoint in 0x0591..0x05BD ||
        codePoint == 0x05BF ||
        codePoint in 0x05C1..0x05C2 ||
        codePoint in 0x05C4..0x05C5 ||
        codePoint == 0x05C7 ||
        codePoint in 0x0610..0x061A ||
        codePoint in 0x064B..0x065F ||
        codePoint == 0x0670 ||
        codePoint in 0x06D6..0x06DC ||
        codePoint in 0x06DF..0x06E4 ||
        codePoint in 0x06E7..0x06E8 ||
        codePoint in 0x06EA..0x06ED ||
        codePoint in 0x1AB0..0x1AFF ||
        codePoint in 0x1DC0..0x1DFF ||
        codePoint in 0x20D0..0x20FF ||
        codePoint in 0xFE20..0xFE2F

private fun isVariationSelector(codePoint: Int): Boolean =
    codePoint in 0xFE00..0xFE0F ||
        codePoint in 0xE0100..0xE01EF

private fun isEmojiModifier(codePoint: Int): Boolean =
    codePoint in 0x1F3FB..0x1F3FF

private fun isRegionalIndicator(codePoint: Int): Boolean =
    codePoint in 0x1F1E6..0x1F1FF

private fun isKeycapBase(codePoint: Int): Boolean =
    codePoint in 0x0030..0x0039 || codePoint == 0x0023 || codePoint == 0x002A

private fun isBaseEmoji(codePoint: Int): Boolean =
    !isEmojiModifier(codePoint) && codePoint in 0x1F000..0x1FAFF ||
        codePoint in 0x2600..0x27BF

private fun isParagraphSeparator(codePoint: Int): Boolean =
    codePoint == 0x000A ||
        codePoint == 0x000D ||
        codePoint == 0x2029

private fun isSegmentSeparator(codePoint: Int): Boolean =
    codePoint == 0x0009 ||
        codePoint == 0x2028

private fun isWhitespace(codePoint: Int): Boolean =
    codePoint == 0x0020 ||
        codePoint == 0x00A0 ||
        codePoint == 0x1680 ||
        codePoint in 0x2000..0x200A ||
        codePoint == 0x202F ||
        codePoint == 0x205F ||
        codePoint == 0x3000
