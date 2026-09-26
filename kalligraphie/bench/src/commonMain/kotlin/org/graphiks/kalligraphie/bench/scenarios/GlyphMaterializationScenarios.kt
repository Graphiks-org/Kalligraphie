package org.graphiks.kalligraphie.bench.scenarios

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderVariantSnapshot
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.bench.MeasurementScenario
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

// The scenarios below port the portable half of `GlyphMaterializationBenchmark` one-to-one: each
// class keeps the original profile's route, timed boundary, cache state and operation order, while
// the hand-rolled warmup/iteration loop is replaced by the harness. Where the original excluded
// per-sample cleanup from timing via a finally hook, the boundary text says so explicitly.

private val COLR_BYTES_PATH = "/fonts/bungee-color/BungeeColor-Regular.ttf"
private val SVG_BYTES_PATH = "/fonts/twemoji-svginot-glyph5/TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf.base64"
private val BITMAP_BYTES_PATH = "/fonts/skia-ebdt-format1/ebdt_fmt1.ttf"
internal val LIBERATION_BYTES_PATH = "/fonts/liberation/LiberationSans-Regular.ttf"

private class ColdResolution(
    name: String,
    private val fixture: CorpusFixture,
    private val requirements: () -> FontAccessRequirementsSnapshot,
    route: String,
) : PortableScenario(
    name = name,
    route = route,
    timedBoundary =
        "starts before embedded catalog creation and ends after the resolved immutable representation " +
            "is consumed and the handle is closed; the closure is inside the boundary, matching " +
            "resolveCold in the original harness",
    cacheState = "cold: a new embedded catalog and resolver are created for every sample",
) {
    override fun operation() {
        val opened = openAsset(fixture, requirements(), cachePolicy = CACHE_POLICY)
        try {
            observe(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))), fixture.bytes.size.toLong())
        } finally {
            opened.close()
        }
    }
}

private class WarmResolution(
    private val fixture: CorpusFixture,
    private val requirements: () -> FontAccessRequirementsSnapshot,
    name: String,
    route: String,
) : PortableScenario(
    name = name,
    route = route,
    timedBoundary =
        "starts immediately before resolveGlyph and ends after the cached immutable representation is " +
            "consumed; setup and closure are excluded, as in the original harness",
    cacheState =
        "warm: one untimed successful seed and the harness warmup populate the per-face portable " +
            "representation cache",
) {
    private var opened: OpenAsset? = null

    override fun prepare() {
        opened = openAsset(fixture, requirements(), cachePolicy = CACHE_POLICY)
        consumeSeed(success(checkNotNull(opened).asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
    }

    override fun operation() {
        observe(success(checkNotNull(opened).asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))), sourceBytes = 0)
    }

    override fun release() {
        opened?.close()
        opened = null
    }
}

private class PaletteChange(private val fixture: CorpusFixture) : PortableScenario(
    name = "PaletteChange",
    route = "COLR v0 / CPAL v0 palette 0 to palette 1",
    timedBoundary =
        "starts before palette-1 asset acquisition and ends after its paint graph is consumed and the " +
            "palette-1 handle is closed; the palette-0 seed and its closure are excluded",
    cacheState = "palette 0 is seeded before each sample; palette 1 is a distinct render variant and representation key",
) {
    private var opened: OpenAsset? = null

    override fun prepare() {
        opened = openAsset(fixture, colrRequirements(), FontRenderVariantSnapshot(cpalPaletteIndex = 0), CACHE_POLICY)
        consumeSeed(success(checkNotNull(opened).asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
    }

    override fun operation() {
        val base = checkNotNull(opened)
        val paletteOne = success(
            base.instance.acquireRenderAsset(
                base.resolver,
                FontRenderVariantSnapshot(cpalPaletteIndex = 1),
                colrRequirements(),
            ),
        )
        try {
            observe(success(paletteOne.resolveGlyph(FontGlyphRequest(fixture.glyphId))), sourceBytes = 0)
        } finally {
            paletteOne.close()
        }
    }

    override fun release() {
        opened?.close()
        opened = null
    }
}

private class CachePressureAndEviction(private val fixture: CorpusFixture) : PortableScenario(
    name = "CachePressureAndEviction",
    route = "SVG-in-OpenType v0 with profile-key pressure and LRU eviction",
    timedBoundary =
        "starts before the original glyph is seeded, includes five distinct certified profile keys, and " +
            "ends after the original glyph is resolved again; the opened handle is closed inside the " +
            "boundary, matching the original harness",
    cacheState = "10,000-byte face budget; the pressure profile is intentionally distinct while producing the same SVG route",
) {

    override fun operation() {
        val opened = openAsset(fixture, svgRequirements(), cachePolicy = PRESSURE_CACHE_POLICY)
        try {
            consumeSeed(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
            repeat(5) { profileOffset ->
                val pressure = success(
                    opened.instance.acquireRenderAsset(
                        opened.resolver,
                        FontRenderVariantSnapshot.default,
                        svgRequirements(maxSourceBytes = 16 * 1024 + profileOffset + 1),
                    ),
                )
                try {
                    consumeSeed(success(pressure.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
                } finally {
                    pressure.close()
                }
            }
            observe(success(opened.asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))), fixture.bytes.size.toLong())
        } finally {
            opened.close()
        }
    }
}

private class CooperativeCancellation(private val fixture: CorpusFixture) : PortableScenario(
    name = "CooperativeCancellation",
    route = "COLR v0 / CPAL v0 outline materialization",
    timedBoundary =
        "starts before resolveGlyph with a cooperative token and ends at the typed cancellation return; " +
            "the token allocation, excluded by the original harness, is included because the standard " +
            "harness has no per-invocation untimed hook",
    cacheState = "cache disabled so cancellation reaches a real two-layer COLR materialization instead of a cache hit",
) {
    private var opened: OpenAsset? = null

    override fun prepare() {
        opened = openAsset(fixture, colrRequirements(), cachePolicy = FontMaterializationCachePolicy.disabled)
    }

    override fun operation() {
        val token = CancelsOnCheck(2)
        val result = checkNotNull(opened).asset.resolveGlyph(FontGlyphRequest(fixture.glyphId), token)
        check(result is FontOperationResult.Cancelled) {
            "Cancellation profile must return a typed cancelled result."
        }
        val signal = checkNotNull(token.signaledAt) { "Cancellation token did not signal during materialization." }
        record("cancellationDelayNanos", (kotlin.time.TimeSource.Monotonic.markNow() - signal).inWholeNanoseconds)
        count("glyphsMaterialized")
    }

    override fun release() {
        opened?.close()
        opened = null
    }
}

private class TrueTypeColdPreparation(private val fixture: CorpusFixture) : PortableScenario(
    name = "TrueTypeColdPreparation",
    route = "Liberation Sans embedded TrueType catalog capture, face resolution, and instance creation",
    timedBoundary = "starts before embedded catalog capture and ends after the resolved face and created instance are consumed",
    cacheState = "cold: a new embedded catalog is captured for every sample",
) {

    override fun operation() {
        val prepared = prepareTrueType(fixture)
        consumePreparedTrueType(this, prepared)
        count("sourceBytes", fixture.bytes.size.toLong())
    }
}

private class TrueTypeWarmPreparation(private val fixture: CorpusFixture) : PortableScenario(
    name = "TrueTypeWarmPreparation",
    route = "Liberation Sans face resolution and instance creation from one captured embedded catalog",
    timedBoundary = "starts before face resolution and ends after the created instance is consumed; catalog capture is excluded",
    cacheState = "warm: one captured embedded catalog is reused for every face resolution and instance creation",
) {
    private var catalog: org.graphiks.kalligraphie.api.FontCatalogSnapshot? = null

    override fun prepare() {
        catalog = captureTrueTypeCatalog(fixture)
    }

    override fun operation() {
        consumePreparedTrueType(this, instantiateTrueType(checkNotNull(catalog)))
    }

    override fun release() {
        catalog = null
    }
}

private class TrueTypeColdTextMapping(private val fixture: CorpusFixture, private val scalars: List<Int>) : PortableScenario(
    name = "TrueTypeColdTextMapping",
    route = "Liberation Sans Unicode scalar to glyph mapping for the stable editor paragraph",
    timedBoundary = "starts before new catalog, face, and instance preparation and ends after every paragraph glyph id is consumed",
    cacheState = "cold: a new embedded catalog and font instance are created for every sample",
) {

    override fun operation() {
        val prepared = prepareTrueType(fixture)
        resolveParagraphGlyphs(this, prepared.instance, scalars)
        count("sourceBytes", fixture.bytes.size.toLong())
    }
}

private class TrueTypeWarmTextMapping(private val fixture: CorpusFixture, private val scalars: List<Int>) : PortableScenario(
    name = "TrueTypeWarmTextMapping",
    route = "Liberation Sans Unicode scalar to glyph mapping for the stable editor paragraph",
    timedBoundary =
        "starts before resolving the first scalar and ends after every paragraph glyph id is consumed; " +
            "instance preparation is excluded",
    cacheState = "warm: one prepared font instance is reused and the paragraph scalars are enumerated outside the timed operations",
) {
    private var instance: org.graphiks.kalligraphie.api.FontInstance? = null

    override fun prepare() {
        instance = prepareTrueType(fixture).instance
    }

    override fun operation() {
        resolveParagraphGlyphs(this, checkNotNull(instance), scalars)
    }

    override fun release() {
        instance = null
    }
}

private class TrueTypeColdMetrics(private val fixture: CorpusFixture, private val scalars: List<Int>) : PortableScenario(
    name = "TrueTypeColdMetrics",
    route = "Liberation Sans mapping and horizontal metrics for every stable-paragraph glyph",
    timedBoundary =
        "starts before new catalog, face, and instance preparation, includes full paragraph mapping, and " +
            "ends after every advance and bounds value is consumed",
    cacheState = "cold: a new embedded catalog and font instance are created for every sample",
) {

    override fun operation() {
        val prepared = prepareTrueType(fixture)
        consumeGlyphMetrics(this, prepared.instance, resolveParagraphGlyphs(this, prepared.instance, scalars))
        count("sourceBytes", fixture.bytes.size.toLong())
    }
}

private class TrueTypeWarmMetrics(private val fixture: CorpusFixture, private val scalars: List<Int>) : PortableScenario(
    name = "TrueTypeWarmMetrics",
    route = "Liberation Sans horizontal metrics for pre-mapped stable-paragraph glyphs",
    timedBoundary =
        "starts before the first metrics lookup and ends after every advance and bounds value is " +
            "consumed; preparation and mapping are excluded",
    cacheState = "warm: one prepared instance and one pre-mapped paragraph glyph sequence are reused",
) {
    private var instance: org.graphiks.kalligraphie.api.FontInstance? = null
    private var glyphIds: List<GlyphId> = emptyList()

    override fun prepare() {
        val prepared = prepareTrueType(fixture)
        instance = prepared.instance
        glyphIds = resolveParagraphGlyphs(this, prepared.instance, scalars)
    }

    override fun operation() {
        consumeGlyphMetrics(this, checkNotNull(instance), glyphIds)
    }

    override fun release() {
        instance = null
        glyphIds = emptyList()
    }
}

private class TrueTypeColdOutlines(private val fixture: CorpusFixture, private val glyphIds: List<GlyphId>) : PortableScenario(
    name = "TrueTypeColdOutlines",
    route = "Liberation Sans portable glyf outlines for distinct nonzero stable-paragraph glyphs",
    timedBoundary =
        "starts before new catalog, resolver, instance, and asset creation and ends after every distinct " +
            "nonzero paragraph representation is consumed; the resolver and asset closure, excluded by " +
            "the original harness through its cleanup hook, is included because the standard harness " +
            "has no per-invocation untimed hook",
    cacheState = "cold: a new resolver and attached outline asset are created and closed for every sample",
) {

    override fun operation() {
        val opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
        try {
            consumeOutlines(this, opened.asset, glyphIds)
            count("sourceBytes", fixture.bytes.size.toLong())
        } finally {
            opened.close()
        }
    }
}

private class TrueTypeWarmOutlines(private val fixture: CorpusFixture, private val glyphIds: List<GlyphId>) : PortableScenario(
    name = "TrueTypeWarmOutlines",
    route = "Liberation Sans cached portable glyf outlines for distinct nonzero stable-paragraph glyphs",
    timedBoundary =
        "starts before the first cached outline resolution and ends after every distinct nonzero " +
            "paragraph representation is consumed; setup, seed, and closure are excluded",
    cacheState = "warm: one attached asset is seeded for every distinct nonzero paragraph glyph before repeated resolution",
) {
    private var opened: OpenAsset? = null

    override fun prepare() {
        opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
        consumeOutlines(this, checkNotNull(opened).asset, glyphIds)
    }

    override fun operation() {
        consumeOutlines(this, checkNotNull(opened).asset, glyphIds)
    }

    override fun release() {
        opened?.close()
        opened = null
    }
}

private class TrueTypeColdDetach(private val fixture: CorpusFixture) : PortableScenario(
    name = "TrueTypeColdDetach",
    route = "Liberation Sans attached-to-detached portable outline asset lifetime",
    timedBoundary =
        "starts before new attached asset creation, includes detachment and attached-owner closure, and " +
            "ends after glyph 36 is resolved and consumed through the detached handle; the detached and " +
            "attached closures, excluded by the original harness through its cleanup hook, are included " +
            "because the standard harness has no per-invocation untimed hook",
    cacheState = "cold: a new catalog, resolver, and attached asset are created for every detach sample",
) {

    override fun operation() {
        val opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
        val detached = success(opened.asset.detach())
        try {
            success(opened.asset.close())
            success(opened.resolver.close())
            consumeOutlineRepresentation(this, glyphId = fixture.glyphId, representation = success(detached.resolveGlyph(FontGlyphRequest(fixture.glyphId))))
            count("sourceBytes", fixture.bytes.size.toLong())
        } finally {
            detached.close()
        }
    }
}

private class TrueTypeWarmDetach(private val fixture: CorpusFixture) : PortableScenario(
    name = "TrueTypeWarmDetach",
    route = "Liberation Sans repeated independent detached outline handles",
    timedBoundary =
        "starts before detachment from one prepared attached asset and ends after glyph 36 is resolved, " +
            "consumed, and the independent detached handle is closed",
    cacheState = "warm: one prepared attached asset remains open across independent detach, consume, and close cycles",
) {
    private var opened: OpenAsset? = null

    override fun prepare() {
        opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
        consumeOutlineRepresentation(
            this,
            glyphId = fixture.glyphId,
            representation = success(checkNotNull(opened).asset.resolveGlyph(FontGlyphRequest(fixture.glyphId))),
        )
    }

    override fun operation() {
        val detached = success(checkNotNull(opened).asset.detach())
        try {
            consumeOutlineRepresentation(
                this,
                glyphId = fixture.glyphId,
                representation = success(detached.resolveGlyph(FontGlyphRequest(fixture.glyphId))),
            )
        } finally {
            detached.close()
        }
    }

    override fun release() {
        opened?.close()
        opened = null
    }
}


/**
 * The portable glyph-materialization scenarios, in the canonical report order: the nine
 * representation profiles, then the ten portable TrueType editor stages. The consumer, session,
 * handoff and concurrent profiles of the original file need `END_TO_END_LAYOUT` and are provided by
 * the JVM's paragraph scenarios instead.
 */
public fun glyphMaterializationScenarios(corpus: FixtureCorpus): List<MeasurementScenario> {
    val colr = CorpusFixture("BungeeColor-Regular.ttf", "Bungee Color COLR v0", GlyphId(43), corpus.bytes(COLR_BYTES_PATH))
    val svg = CorpusFixture(
        "TwitterColorEmoji-SVGinOT-15.1.0-glyph5.ttf",
        "TwitterColorEmoji SVG-in-OpenType",
        GlyphId(1),
        decodeSvgFixture(corpus.bytes(SVG_BYTES_PATH)),
    )
    val bitmap = CorpusFixture("ebdt_fmt1.ttf", "Skia EBDT format 1", GlyphId(3), corpus.bytes(BITMAP_BYTES_PATH))
    val liberation = CorpusFixture("LiberationSans-Regular.ttf", "Liberation Sans Regular", GlyphId(36), corpus.bytes(LIBERATION_BYTES_PATH))
    val trueTypeScalars = codePoints(TRUE_TYPE_PARAGRAPH)
    val trueTypeGlyphIds = trueTypeParagraphGlyphIds(liberation, trueTypeScalars)
    return listOf(
        ColdResolution("ColrColdNormalization", colr, ::colrRequirements, "COLR v0 / CPAL v0 to GlyphPaintIR"),
        WarmResolution(colr, ::colrRequirements, "ColrWarmResolution", "COLR v0 / CPAL v0 cached GlyphPaintIR"),
        ColdResolution("SvgColdNormalization", svg, ::svgRequirements, "SVG-in-OpenType v0 to GlyphPaintIR"),
        WarmResolution(svg, ::svgRequirements, "SvgWarmResolution", "SVG-in-OpenType v0 cached GlyphPaintIR"),
        ColdResolution("BitmapColdDecode", bitmap, ::bitmapRequirements, "EBLC v2 / EBDT v2 format 1 to BitmapGlyphIR"),
        WarmResolution(bitmap, ::bitmapRequirements, "BitmapWarmResolution", "EBLC v2 / EBDT v2 cached BitmapGlyphIR"),
        PaletteChange(colr),
        CachePressureAndEviction(svg),
        CooperativeCancellation(colr),
        TrueTypeColdPreparation(liberation),
        TrueTypeWarmPreparation(liberation),
        TrueTypeColdTextMapping(liberation, trueTypeScalars),
        TrueTypeWarmTextMapping(liberation, trueTypeScalars),
        TrueTypeColdMetrics(liberation, trueTypeScalars),
        TrueTypeWarmMetrics(liberation, trueTypeScalars),
        TrueTypeColdOutlines(liberation, trueTypeGlyphIds),
        TrueTypeWarmOutlines(liberation, trueTypeGlyphIds),
        TrueTypeColdDetach(liberation),
        TrueTypeWarmDetach(liberation),
    )
}

private fun trueTypeParagraphGlyphIds(fixture: CorpusFixture, scalars: List<Int>): List<GlyphId> {
    val probe = PortableScenarioProbe()
    val glyphIds = resolveParagraphGlyphs(probe, prepareTrueType(fixture).instance, scalars)
        .filter { glyphId -> glyphId.value != 0 }
        .distinct()
    check(glyphIds.isNotEmpty()) { "The portable TrueType paragraph must resolve at least one nonzero glyph." }
    return glyphIds
}

/** A throwaway sink for provider-time resolution; it publishes nothing. */
private class PortableScenarioProbe : PortableScenario("probe", "probe", "probe", "probe") {
    override fun operation() = error("The probe is never measured.")
}
