package org.graphiks.kalligraphie.coroutines

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.graphiks.kalligraphie.JvmEditableLineFacadeRequest
import org.graphiks.kalligraphie.Kalligraphie
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal val COROUTINES_OUTLINE_PROFILE: OutlineProfile = OutlineProfile(
    maxBytes = 1_000_000,
    maxContours = 256,
    maxPoints = 16_384,
    maxCompositeDepth = 8,
    maxCompositeComponents = 256,
)

internal fun <T> success(result: FontOperationResult<T>): T =
    assertIs<FontOperationResult.Success<T>>(result).value

internal fun assertClosed(result: FontOperationResult<Unit>) {
    assertIs<FontOperationResult.Success<Unit>>(result)
}

internal fun liberationBytes(): ByteArray = checkNotNull(
    LineFixture::class.java.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf"),
) { "fixture font is missing" }.use { it.readBytes() }

internal fun utf8Snapshot(text: String): TextSnapshot = Kalligraphie.decodeUtf8(
    TextVersion.create(),
    listOf(TextSlice.Utf8(text.encodeToByteArray())),
).snapshot

internal class LineFixture(
    val snapshot: TextSnapshot,
    val font: FontInstance,
    val resolver: FontAssetResolverHandle,
)

internal fun lineFixture(text: String = "A"): LineFixture {
    val catalog = success(
        Kalligraphie.embedded(
            sourceBytes = liberationBytes(),
            provenance = FontSourceProvenance("Liberation Sans Regular"),
        ),
    )
    val resolver = success(catalog.openAssetResolver())
    val requirements = FontAccessRequirementsSnapshot.renderable(COROUTINES_OUTLINE_PROFILE)
    val face = success(catalog.resolveFace(catalog.faces.single().id, requirements))
    val font = success(face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(2048f))))
    return LineFixture(utf8Snapshot(text), font, resolver)
}

internal fun lineRequest(
    fixture: LineFixture,
    cancellationToken: CancellationToken = CancellationToken.none,
    materialization: EditableLineMaterialization = EditableLineMaterialization.LayoutOnly,
): JvmEditableLineFacadeRequest = JvmEditableLineFacadeRequest(
    snapshot = fixture.snapshot,
    font = fixture.font,
    baseDirection = BaseDirection.LEFT_TO_RIGHT,
    language = "en",
    featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
    features = emptyList(),
    verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
    materialization = materialization,
    positioning = null,
    cancellationToken = cancellationToken,
)

internal fun lineFingerprint(line: org.graphiks.kalligraphie.api.EditableLine): List<Any> = listOf(
    line.range,
    line.baseDirection,
    line.verticalMetrics,
    line.positionedGlyphRuns.flatMap { run -> run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId to glyph.origin } },
    line.allCaretCandidates.map { candidate -> candidate.position to candidate.geometry },
)

/**
 * Runs [block] in an unconfined coroutine and returns the [KalligraphieCancellationException] it
 * throws, or fails the test when no cancellation exception is produced.
 */
internal fun captureCancellation(
    block: suspend CoroutineScope.(CoroutineContext) -> Unit,
): KalligraphieCancellationException {
    val captured = AtomicReference<KalligraphieCancellationException?>()
    val failure = AtomicReference<Throwable?>()
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val job = scope.launch {
        try {
            block(this, coroutineContext)
            failure.compareAndSet(null, AssertionError("expected a KalligraphieCancellationException"))
        } catch (cancelled: KalligraphieCancellationException) {
            captured.set(cancelled)
        } catch (other: Throwable) {
            failure.compareAndSet(null, other)
        }
    }
    // Unconfined launch runs synchronously up to the first suspension; the block has no suspension.
    assertTrue(job.isCompleted || job.isCancelled, "the unconfined coroutine did not complete synchronously")
    failure.get()?.let { throw it }
    return checkNotNull(captured.get()) { "no cancellation exception was captured" }
}

internal fun EditableLineResult.line(): org.graphiks.kalligraphie.api.EditableLine =
    assertIs<EditableLineResult.Success>(this).line
