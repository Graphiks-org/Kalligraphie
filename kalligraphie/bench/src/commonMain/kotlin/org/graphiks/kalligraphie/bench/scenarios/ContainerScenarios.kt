package org.graphiks.kalligraphie.bench.scenarios

import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.bench.MeasurementScenario
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

// The container scenarios extend the portable glyph catalogue with the two web-font wrappers the
// facade now decodes. They follow `TrueTypeColdPreparation`: every sample captures a fresh embedded
// catalog, so the decode (WOFF 1.0 zlib, or WOFF 2.0 Brotli plus the `glyf`/`loca` and `hmtx`
// transforms) is inside the timed boundary. The three profiles carry no required capability and no
// required instrument, so every platform that serves the portable glyph route measures them.

private const val WOFF_BYTES_PATH = "/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff"
private const val WOFF2_BYTES_PATH = "/fonts/woff-ibm-plex/IBMPlexSans-Regular.woff2"

/** U+0041 `A`, the capital the `container.woff`/`container.woff2` e2e scenes render. */
private const val SCENE_GLYPH_CODE_POINT = 0x41

/**
 * Captures one embedded container and consumes the resulting face and instance. The container bytes
 * are never interpreted here beyond the facade's own decode, so this isolates the wrapper cost from
 * the glyph route that [ContainerColdGlyph] adds on top.
 */
private class ContainerColdCapture(
    name: String,
    private val fixture: CorpusFixture,
    route: String,
) : PortableScenario(
    name = name,
    route = route,
    timedBoundary =
        "starts before embedded container capture and ends after the decoded catalog's face and " +
            "created instance are consumed",
    cacheState = "cold: a new embedded catalog is captured for every sample",
) {

    override fun operation() {
        consumePreparedTrueType(this, prepareTrueType(fixture))
        count("sourceBytes", fixture.bytes.size.toLong())
    }
}

/**
 * Decodes the WOFF 2.0 container far enough to resolve and consume the scene capital's outline, so
 * the profile measures the whole path from wrapper bytes to a portable glyph representation rather
 * than the capture alone. The scene glyph is resolved from its Unicode scalar, as the e2e scene
 * does, instead of a hardcoded glyph identifier.
 */
private class ContainerColdGlyph(private val fixture: CorpusFixture) : PortableScenario(
    name = "Woff2ColdGlyph",
    route = "IBM Plex Sans WOFF 2.0 container decode to the U+0041 outline through the portable glyph route",
    timedBoundary =
        "starts before embedded container capture and ends after the resolved U+0041 outline is " +
            "consumed; the resolver and attached asset closure is inside the boundary",
    cacheState =
        "cold: a new embedded catalog, resolver, instance and attached asset are created and " +
            "closed for every sample",
) {

    override fun operation() {
        val opened = openAsset(fixture, trueTypeRequirements(), cachePolicy = CACHE_POLICY)
        try {
            val glyphId: GlyphId = success(opened.instance.resolveGlyph(SCENE_GLYPH_CODE_POINT)).glyphId
            consumeOutlineRepresentation(
                this,
                glyphId = glyphId,
                representation = success(opened.asset.resolveGlyph(FontGlyphRequest(glyphId))),
            )
            count("sourceBytes", fixture.bytes.size.toLong())
        } finally {
            opened.close()
        }
    }
}

/**
 * The three portable container profiles, in the canonical report order: the WOFF 1.0 and WOFF 2.0
 * captures, then the WOFF 2.0 end-to-end glyph resolution. The fixture glyph identifier is unused —
 * [ContainerColdGlyph] resolves the scene glyph from its scalar — so the placeholder `0` keeps the
 * shared [CorpusFixture] shape without asserting a glyph the scenarios never request.
 */
public fun containerScenarios(corpus: FixtureCorpus): List<MeasurementScenario> {
    val woff = CorpusFixture(
        name = "IBMPlexSans-Regular.woff",
        provenance = "IBM Plex Sans Regular WOFF 1.0",
        glyphId = GlyphId(0),
        bytes = corpus.bytes(WOFF_BYTES_PATH),
    )
    val woff2 = CorpusFixture(
        name = "IBMPlexSans-Regular.woff2",
        provenance = "IBM Plex Sans Regular WOFF 2.0",
        glyphId = GlyphId(0),
        bytes = corpus.bytes(WOFF2_BYTES_PATH),
    )
    return listOf(
        ContainerColdCapture(
            name = "WoffColdCapture",
            fixture = woff,
            route = "IBM Plex Sans WOFF 1.0 container decode, catalog capture, face resolution and instance creation",
        ),
        ContainerColdCapture(
            name = "Woff2ColdCapture",
            fixture = woff2,
            route =
                "IBM Plex Sans WOFF 2.0 container decode (Brotli, glyf/loca and hmtx transforms), " +
                    "catalog capture, face resolution and instance creation",
        ),
        ContainerColdGlyph(woff2),
    )
}
