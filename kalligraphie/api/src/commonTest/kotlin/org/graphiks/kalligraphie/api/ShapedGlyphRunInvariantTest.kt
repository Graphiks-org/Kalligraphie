package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ShapedGlyphRunInvariantTest {
    @Test
    fun rejectsCaretFactWhoseBoundaryIsNotInternalToItsGlyphCluster() {
        val snapshot = snapshotOf("abcd")

        assertFailsWith<IllegalArgumentException> {
            shapedRun(
                snapshot = snapshot,
                clusters = listOf(cluster(snapshot, ShaperClusterToken(0), 0, 2), cluster(snapshot, ShaperClusterToken(1), 2, 4)),
                glyphs = listOf(glyph(ShaperClusterToken(0)), glyph(ShaperClusterToken(1))),
                caretFacts = listOf(
                    GdefLigatureCaretFact(
                        glyphIndex = 0,
                        state = GdefLigatureCaretState.ABSENT,
                        logicalSourceBoundaries = listOf(snapshot.textIndexAtScalarBoundary(3)),
                    ),
                ),
            )
        }
    }

    @Test
    fun rejectsRunWithAmbiguousRepeatedClusterToken() {
        val snapshot = snapshotOf("ab")

        assertFailsWith<IllegalArgumentException> {
            shapedRun(
                snapshot = snapshot,
                clusters = listOf(cluster(snapshot, ShaperClusterToken(0), 0, 1), cluster(snapshot, ShaperClusterToken(0), 1, 2)),
                glyphs = listOf(glyph(ShaperClusterToken(0)), glyph(ShaperClusterToken(0))),
            )
        }
    }

    private fun shapedRun(
        snapshot: TextSnapshot,
        clusters: List<ShaperCluster>,
        glyphs: List<ShapedGlyph>,
        caretFacts: List<GdefLigatureCaretFact> = emptyList(),
    ): ShapedGlyphRun =
        ShapedGlyphRun(
            range = snapshot.range,
            fontInstanceKey = FontInstanceKey(
                face = FontFaceId(FontSourceId.Opaque("test", "one", "face"), 0),
                interpretation = FontDataInterpretationVersion("test", "one"),
                layoutSize = LayoutUnit(1f),
            ),
            backendIdentity = ShapingBackendIdentity(
                backendId = "test",
                nativeVersion = "one",
                nativeSourceRevision = "one",
                nativeArtifactId = "test",
                nativeArtifactSha256 = "0".repeat(64),
                featurePolicy = ShapingFeaturePolicy(
                    policyId = "test",
                    version = "one",
                    application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
                ),
                configurationFingerprint = "test",
            ),
            direction = ShapingDirection.LEFT_TO_RIGHT,
            script = OpenTypeScript("Latn"),
            language = "en",
            bidiLevel = 0,
            bot = true,
            eot = true,
            featurePolicy = ShapingFeaturePolicy(
                policyId = "test",
                version = "one",
                application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
            ),
            features = emptyList(),
            graphemeClusters = snapshot.scalarRanges(snapshot.range),
            glyphs = glyphs,
            clusters = clusters,
            ligatureCaretFacts = caretFacts,
        )

    private fun glyph(token: ShaperClusterToken): ShapedGlyph =
        ShapedGlyph(
            glyphId = GlyphId(1),
            xAdvance = LayoutUnit(1f),
            yAdvance = LayoutUnit(0f),
            xOffset = LayoutUnit(0f),
            yOffset = LayoutUnit(0f),
            safetyFlags = ShapingSafetyFlags(unsafeToBreak = false, unsafeToConcat = false),
            clusterTokens = listOf(token),
        )

    private fun cluster(snapshot: TextSnapshot, token: ShaperClusterToken, start: Int, endExclusive: Int): ShaperCluster =
        ShaperCluster(
            token = token,
            sourceRange = range(snapshot, start, endExclusive),
            scalarRanges = (start until endExclusive).map { scalar -> range(snapshot, scalar, scalar + 1) },
            admissibleGraphemeBoundaries = (start..endExclusive).map(snapshot::textIndexAtScalarBoundary),
        )

    private fun snapshotOf(value: String): TextSnapshot {
        val version = TextVersion.create()
        return TextSnapshot(
            version = version,
            sourceEncoding = SourceEncoding.UTF16,
            scalars = value.map(Char::code),
            sourceRanges = value.indices.map { offset ->
                SourceRange(
                    SourceOffset(version, SourceEncoding.UTF16, offset),
                    SourceOffset(version, SourceEncoding.UTF16, offset + 1),
                )
            },
        )
    }

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(snapshot.textIndexAtScalarBoundary(start), snapshot.textIndexAtScalarBoundary(endExclusive))
}
