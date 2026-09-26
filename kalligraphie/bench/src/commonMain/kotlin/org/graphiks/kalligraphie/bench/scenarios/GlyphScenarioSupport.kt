package org.graphiks.kalligraphie.bench.scenarios

import kotlin.io.encoding.Base64
import kotlin.time.TimeSource
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphPaintCompositionMode
import org.graphiks.kalligraphie.api.GlyphPaintNodeKind
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.PaintGraphLimits
import org.graphiks.kalligraphie.api.PaintGraphProfile
import org.graphiks.kalligraphie.bench.MeasurementScenario
import org.graphiks.kalligraphie.bench.ScenarioObservations
import org.graphiks.kalligraphie.bench.ScenarioRoute

/**
 * Shared portable machinery for the glyph-materialization scenarios, ported from
 * `GlyphMaterializationBenchmark` with only two kinds of change: JVM file access goes through the
 * [FixtureCorpus] seam, and the hand-rolled timing loop is gone — the scenario exposes
 * [MeasurementScenario.prepare]/[MeasurementScenario.operation]/[MeasurementScenario.release] and
 * the platform harness times [MeasurementScenario.operation].
 */
internal class CorpusFixture(
    val name: String,
    val provenance: String,
    val glyphId: GlyphId,
    val bytes: ByteArray,
)

internal class OpenAsset(
    val resolver: FontAssetResolverHandle,
    val instance: FontInstance,
    val asset: FontRenderAssetHandle,
) {
    fun close() {
        asset.close()
        resolver.close()
    }
}

internal fun <T> success(result: FontOperationResult<T>): T = when (result) {
    is FontOperationResult.Success -> result.value
    is FontOperationResult.Failure -> error("Materialization measurement failed: ${result.error.code}: ${result.error.message}")
    is FontOperationResult.Cancelled -> error("Materialization measurement was unexpectedly cancelled.")
}

internal fun openAsset(
    fixture: CorpusFixture,
    requirements: FontAccessRequirementsSnapshot,
    variant: FontRenderVariantSnapshot = FontRenderVariantSnapshot.default,
    cachePolicy: FontMaterializationCachePolicy,
): OpenAsset {
    val catalog = success(Kalligraphie.embedded(fixture.bytes, FontSourceProvenance(fixture.provenance), cachePolicy))
    val resolver = success(catalog.openAssetResolver())
    return try {
        val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
        val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(16f))))
        val asset = success(instance.acquireRenderAsset(resolver, variant, requirements))
        OpenAsset(resolver, instance, asset)
    } catch (failure: Throwable) {
        try {
            resolver.close()
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
        throw failure
    }
}

internal val CACHE_POLICY = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1_000_000)

internal val PRESSURE_CACHE_POLICY = FontMaterializationCachePolicy(maxEvictableBytesPerFace = 10_000)

internal fun outlineProfile(): OutlineProfile = OutlineProfile(
    maxBytes = 1_000_000,
    maxContours = 1_024,
    maxPoints = 65_536,
    maxCompositeDepth = 16,
    maxCompositeComponents = 256,
)

internal fun colrRequirements(): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(
    listOf(
        PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.SOLID_OUTLINE, GlyphPaintNodeKind.GROUP),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(3, 2, 2, 16_384, maxPaths = 2, maxPalettes = 9, maxPaletteEntries = 2, maxColorRecords = 16, maxDecodedPaletteBytes = 72, maxBaseGlyphRecords = 288, maxLayerRecords = 576),
            outlineProfile = outlineProfile(),
        ),
    ),
)

internal fun svgRequirements(maxSourceBytes: Int = 16 * 1024): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(
    listOf(
        PaintGraphProfile(
            acceptedNodeKinds = listOf(GlyphPaintNodeKind.PATH, GlyphPaintNodeKind.GROUP),
            acceptedCompositionModes = listOf(GlyphPaintCompositionMode.SOURCE_OVER),
            limits = PaintGraphLimits(4, 4, 2, maxSourceBytes, maxPaths = 2),
            outlineProfile = outlineProfile(),
        ),
    ),
)

internal fun bitmapRequirements(): FontAccessRequirementsSnapshot = FontAccessRequirementsSnapshot.renderable(
    listOf(
        BitmapProfile(
            strike = BitmapStrike(16, 16, 1),
            acceptedPixelFormats = listOf(BitmapPixelFormat.ALPHA_8),
            acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
            limits = BitmapLimits(3, 16, 16, 16_384, 16_384, 16, 16, 256, 64, 1_024, 256, 1_024),
        ),
    ),
)

internal fun trueTypeRequirements(): FontAccessRequirementsSnapshot =
    FontAccessRequirementsSnapshot.renderable(outlineProfile())

internal const val TRUE_TYPE_PARAGRAPH: String =
    "Readable typography keeps words, punctuation, carets, and 0123456789 responsive while an editor changes text."

/** Iterates [text] one Unicode scalar at a time, without the JVM's `Character` helpers. */
internal fun codePoints(text: String): List<Int> {
    val scalars = mutableListOf<Int>()
    var index = 0
    while (index < text.length) {
        val first = text[index]
        if (first.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
            scalars += ((first.code and 0x3ff) shl 10 or (text[index + 1].code and 0x3ff)) + 0x10000
            index += 2
        } else {
            scalars += first.code
            index += 1
        }
    }
    return scalars
}

/** Decodes the SVG-in-OpenType fixture, committed as a base64 envelope, without the JVM decoder. */
internal fun decodeSvgFixture(encoded: ByteArray): ByteArray = Base64.Mime.decode(encoded)

/**
 * A cancellation token that requests cancellation at its [cancelAtCheck]-th poll and records when it
 * first did, so the cancellation scenario can prove the signal arrived inside the measured work.
 */
internal class CancelsOnCheck(private val cancelAtCheck: Int) : CancellationToken {
    private var checks: Int = 0

    var signaledAt: TimeSource.Monotonic.ValueTimeMark? = null
        private set

    override fun isCancellationRequested(): Boolean {
        checks += 1
        if (checks < cancelAtCheck) return false
        if (signaledAt == null) signaledAt = TimeSource.Monotonic.markNow()
        return true
    }
}

/**
 * Base for the portable scenarios: accumulates the evidence checksum the harness hands to its black
 * hole, plus the counters that prove the work happened.
 */
internal abstract class PortableScenario(
    final override val name: String,
    final override val route: String,
    final override val timedBoundary: String,
    final override val cacheState: String,
) : MeasurementScenario {
    final override val scenarioRoute: ScenarioRoute = ScenarioRoute.PORTABLE_GLYPHS

    private val recorded = ScenarioObservations()
    private var checksum = 0L

    final override val evidence: Long
        get() = checksum

    internal fun sink(value: Long) {
        checksum = checksum xor value
    }

    internal fun count(name: String, amount: Long = 1L) {
        recorded.count(name, amount)
    }

    internal fun record(name: String, value: Long) {
        recorded.record(name, value)
    }

    /** Consumes a representation from the untimed setup, feeding the checksum but not the counters. */
    protected fun consumeSeed(representation: GlyphRepresentation) {
        sink(representation.hashCode().toLong())
    }

    /**
     * Observes one resolved glyph: feeds the checksum, counts the glyph and, when the representation
     * carries them, its decoded bytes, normalized nodes or decoded pixels.
     */
    protected fun observe(representation: GlyphRepresentation, sourceBytes: Long) {
        consumeSeed(representation)
        if (sourceBytes > 0) count("sourceBytes", sourceBytes)
        count("glyphsMaterialized")
        when (representation) {
            is GlyphRepresentation.Paint ->
                if (representation.paint.nodes.isNotEmpty()) count("normalizedNodes", representation.paint.nodes.size.toLong())

            is GlyphRepresentation.Bitmap -> {
                if (representation.bitmap.decodedByteCount > 0) count("decodedBytes", representation.bitmap.decodedByteCount.toLong())
                count("decodedPixels", representation.bitmap.width.toLong() * representation.bitmap.height.toLong())
            }

            else -> {}
        }
    }

    final override fun observations(): ScenarioObservations = recorded
}

internal class PreparedTrueType(
    val catalog: FontCatalogSnapshot,
    val face: FontFace,
    val instance: FontInstance,
)

internal fun captureTrueTypeCatalog(fixture: CorpusFixture): FontCatalogSnapshot = success(
    Kalligraphie.embedded(fixture.bytes, FontSourceProvenance(fixture.provenance), CACHE_POLICY),
)

internal fun instantiateTrueType(catalog: FontCatalogSnapshot): PreparedTrueType {
    val face = success(catalog.resolveFace(catalog.faces.single().id, trueTypeRequirements()))
    val instance = success(face.instantiate(FontInstanceDescriptor(LayoutUnit(2_048f))))
    return PreparedTrueType(catalog, face, instance)
}

internal fun prepareTrueType(fixture: CorpusFixture): PreparedTrueType =
    instantiateTrueType(captureTrueTypeCatalog(fixture))

internal fun consumePreparedTrueType(scenario: PortableScenario, prepared: PreparedTrueType) {
    scenario.sink(prepared.catalog.generation.hashCode().toLong())
    scenario.sink(prepared.face.id.hashCode().toLong())
    scenario.sink(prepared.face.metadata.unitsPerEm.toLong())
    scenario.sink(prepared.instance.key.hashCode().toLong())
}

internal fun resolveParagraphGlyphs(scenario: PortableScenario, instance: FontInstance, scalars: List<Int>): List<GlyphId> =
    scalars.map { scalar ->
        val resolution = success(instance.resolveGlyph(scalar))
        scenario.sink(resolution.glyphId.value.toLong())
        resolution.glyphId
    }

internal fun consumeGlyphMetrics(scenario: PortableScenario, instance: FontInstance, glyphIds: List<GlyphId>) {
    glyphIds.forEach { glyphId ->
        val metrics = success(instance.metrics(glyphId))
        scenario.sink(metrics.advanceWidthDesignUnits.toLong())
        scenario.sink(metrics.advanceWidth.value.toRawBits().toLong())
        scenario.sink(metrics.bounds.minX.toLong())
        scenario.sink(metrics.bounds.minY.toLong())
        scenario.sink(metrics.bounds.maxX.toLong())
        scenario.sink(metrics.bounds.maxY.toLong())
        scenario.sink(metrics.scaledBounds.minX.value.toRawBits().toLong())
        scenario.sink(metrics.scaledBounds.minY.value.toRawBits().toLong())
        scenario.sink(metrics.scaledBounds.maxX.value.toRawBits().toLong())
        scenario.sink(metrics.scaledBounds.maxY.value.toRawBits().toLong())
    }
}

internal fun consumeOutlines(scenario: PortableScenario, asset: FontRenderAssetHandle, glyphIds: List<GlyphId>) {
    glyphIds.forEach { glyphId ->
        consumeOutlineRepresentation(scenario, glyphId = glyphId, representation = success(asset.resolveGlyph(FontGlyphRequest(glyphId))))
    }
}

internal fun consumeOutlineRepresentation(scenario: PortableScenario, glyphId: GlyphId, representation: GlyphRepresentation) {
    when (representation) {
        is GlyphRepresentation.Outline -> {
            val outline = representation.outline
            scenario.sink(outline.glyphId.toLong())
            scenario.sink(outline.unitsPerEm.toLong())
            scenario.sink(outline.bounds.minX.toLong())
            scenario.sink(outline.bounds.minY.toLong())
            scenario.sink(outline.bounds.maxX.toLong())
            scenario.sink(outline.bounds.maxY.toLong())
            scenario.sink(outline.contours.size.toLong())
            scenario.sink(outline.commands.size.toLong())
            scenario.count("glyphsMaterialized")
        }

        GlyphRepresentation.Empty -> scenario.sink(glyphId.value.toLong())
        else -> error("Portable TrueType outline measurement received $representation for glyph ${glyphId.value}.")
    }
}
