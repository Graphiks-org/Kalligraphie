package org.graphiks.kalligraphie

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionCandidate
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.HorizontalParagraphConstraints
import org.graphiks.kalligraphie.api.LayoutRect
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineLayout
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.api.TypographySnapshot
import org.graphiks.kalligraphie.api.TypographyVersion

internal data class IncrementalFontFixture(
    val relativePath: String,
    val declaredName: String,
)

internal data class IncrementalRealFontFixture(
    val snapshot: TextSnapshot,
    val catalog: FontCatalogSnapshot,
    val policy: FontResolutionPolicySnapshot,
    val typography: TypographySnapshot,
    val faces: List<FontFaceId>,
) {
    fun withText(value: String): IncrementalRealFontFixture = copy(
        snapshot = incrementalSnapshot(value),
    )

    fun withTypography(
        policy: FontResolutionPolicySnapshot = this.policy,
        fontInstanceDescriptor: FontInstanceDescriptor = typography.fontInstanceDescriptor,
        features: List<OpenTypeFeature> = typography.features,
        shapingConfigurationIdentity: String = typography.shapingConfigurationIdentity,
    ): IncrementalRealFontFixture = copy(
        policy = policy,
        typography = TypographySnapshot(
            version = TypographyVersion.create(),
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = fontInstanceDescriptor,
            features = features,
            shapingConfigurationIdentity = shapingConfigurationIdentity,
        ),
    )
}

internal fun incrementalRealFontFixture(
    value: String,
    fonts: List<IncrementalFontFixture> = listOf(
        IncrementalFontFixture("gdef-kern/GdefKerningFixture.ttf", "GDEF kerning fixture"),
        IncrementalFontFixture("amiri/Amiri-Regular.ttf", "Amiri Regular"),
    ),
    policyId: String = "incremental-session-fixture",
): IncrementalRealFontFixture {
    val sources = fonts.map { font -> incrementalFontSource(font.relativePath, font.declaredName) }
    val catalog = assertIs<FontOperationResult.Success<FontCatalogSnapshot>>(
        Kalligraphie.embedded(sources),
    ).value
    val faces = sources.map { source -> FontFaceId(source.id, 0) }
    val policy = FontResolutionPolicySnapshot(
        generation = catalog.generation,
        policyId = policyId,
        version = "1",
        candidates = faces.map(::FontResolutionCandidate),
        lastResortFace = faces.last(),
    )
    return IncrementalRealFontFixture(
        snapshot = incrementalSnapshot(value),
        catalog = catalog,
        policy = policy,
        typography = TypographySnapshot(
            version = TypographyVersion.create(),
            fontCatalog = catalog,
            resolutionPolicy = policy,
            fontInstanceDescriptor = FontInstanceDescriptor(LayoutUnit(1_000f)),
        ),
        faces = faces,
    )
}

internal fun incrementalSnapshot(value: String): TextSnapshot = Kalligraphie.decodeUtf16(
    TextVersion.create(),
    listOf(TextSlice.Utf16(value.toCharArray())),
).snapshot

internal fun incrementalTestConstraints(
    width: Float = 1_400f,
    top: Float = 50f,
    height: Float = 3_600f,
): HorizontalParagraphConstraints = HorizontalParagraphConstraints(
    region = LayoutRect(LayoutUnit(100f), LayoutUnit(top), LayoutUnit(100f + width), LayoutUnit(top + height)),
    lineMetrics = LineVerticalMetrics(LayoutUnit(900f), LayoutUnit(300f)),
)

internal fun TextSnapshot.incrementalRange(start: Int, endExclusive: Int): TextRange = TextRange(
    textIndexAtScalarBoundary(start),
    textIndexAtScalarBoundary(endExclusive),
)

internal fun LineLayout.glyphIds(): List<Int> = positionedGlyphRuns.flatMap { run ->
    run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value }
}

internal fun LineLayout.glyphAdvances(): List<Float> = positionedGlyphRuns.flatMap { run ->
    run.glyphs.map { glyph -> glyph.advance.x.value }
}

private fun incrementalFontSource(relativePath: String, declaredName: String): FontSource = FontSource(
    sourceBytes = incrementalFixtureBytes(relativePath),
    provenance = FontSourceProvenance(declaredName),
)

private fun incrementalFixtureBytes(relativePath: String): ByteArray {
    IncrementalRealFontFixture::class.java.getResourceAsStream("/fonts/$relativePath")?.use { stream ->
        return stream.readBytes()
    }
    val candidates = listOf(
        Path.of("test-fixtures", "fonts", relativePath),
        Path.of("..", "test-fixtures", "fonts", relativePath),
        Path.of("..", "..", "test-fixtures", "fonts", relativePath),
    )
    return Files.readAllBytes(checkNotNull(candidates.firstOrNull(Files::isRegularFile)))
}
