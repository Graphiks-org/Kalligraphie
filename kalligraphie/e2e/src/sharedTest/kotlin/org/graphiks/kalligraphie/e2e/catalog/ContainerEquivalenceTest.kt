// ContainerEquivalenceTest.kt
package org.graphiks.kalligraphie.e2e.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.e2e.fixture.E2eTestEnvironment
import org.graphiks.kalligraphie.e2e.golden.openOutlineFixture
import org.graphiks.kalligraphie.e2e.golden.representationOf

/**
 * Proves the two containers of the `woff-ibm-plex` family decode to the same face.
 *
 * The two files wrap the same IBMPlexSans glyph data, but a WOFF 1.0 table may be zlib-deflated
 * and a WOFF 2.0 one Brotli-compressed and transformed (`glyf`/`loca`, `hmtx`), so a byte-wise
 * comparison of the sources would prove nothing. The assertion goes through the public facade
 * instead — glyph resolution, metrics and materialised outlines are the observable behaviour the
 * decoder is responsible for — and compares the two containers probe for probe: a capital, a
 * composite accented capital and an empty glyph.
 */
class ContainerEquivalenceTest {
    @Test
    fun woffAndWoff2ResolveTheSameGlyphBehaviour() {
        val corpus = E2eTestEnvironment.corpus
        for (codePoint in listOf(0x41, 0x00C9, 0x20)) {
            assertEquals(glyphIdOf(corpus.bytes(WoffPaths.WOFF2), codePoint), glyphIdOf(corpus.bytes(WoffPaths.WOFF), codePoint))
            assertEquals(advanceOf(corpus.bytes(WoffPaths.WOFF2), codePoint), advanceOf(corpus.bytes(WoffPaths.WOFF), codePoint))
            assertEquals(outlineCommandsOf(corpus.bytes(WoffPaths.WOFF2), codePoint), outlineCommandsOf(corpus.bytes(WoffPaths.WOFF), codePoint))
        }
    }
}

/** Resource paths of the two containers the family's scenes and this test read. */
internal object WoffPaths {
    /** WOFF 1.0 container wrapping IBMPlexSans. */
    const val WOFF: String = WOFF_IBM_PLEX_WOFF

    /** WOFF 2.0 container wrapping the same IBMPlexSans face. */
    const val WOFF2: String = WOFF_IBM_PLEX_WOFF2
}

/** Resolves [codePoint] to its glyph id through the facade. */
private fun glyphIdOf(bytes: ByteArray, codePoint: Int): Int =
    openOutlineFixture(bytes).use { fixture ->
        assertIs<FontOperationResult.Success<GlyphResolution>>(fixture.instance.resolveGlyph(codePoint)).value.glyphId.value
    }

/** Resolves [codePoint]'s advance width in design units through the facade. */
private fun advanceOf(bytes: ByteArray, codePoint: Int): Int =
    openOutlineFixture(bytes).use { fixture ->
        val glyph = assertIs<FontOperationResult.Success<GlyphResolution>>(
            fixture.instance.resolveGlyph(codePoint),
        ).value.glyphId
        assertIs<FontOperationResult.Success<GlyphMetrics>>(fixture.instance.metrics(glyph)).value.advanceWidthDesignUnits
    }

/**
 * Resolves [codePoint]'s outline commands through the facade.
 *
 * An empty glyph is materialised as [GlyphRepresentation.Empty], which is the same observable
 * behaviour as an outline with no commands: both containers must agree on which it is, so the
 * empty case is mapped to the empty list rather than refused.
 */
private fun outlineCommandsOf(bytes: ByteArray, codePoint: Int): List<String> =
    openOutlineFixture(bytes).use { fixture ->
        when (val representation = fixture.representationOf(codePoint)) {
            is GlyphRepresentation.Outline -> representation.outline.commands.map { command -> command.toString() }
            is GlyphRepresentation.Empty -> emptyList()
            else -> error("U+${codePoint.toString(16).uppercase()} resolved to a non-outline representation: $representation")
        }
    }
