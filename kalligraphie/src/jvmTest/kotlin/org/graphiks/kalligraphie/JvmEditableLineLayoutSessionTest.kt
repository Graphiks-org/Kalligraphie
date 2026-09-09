package org.graphiks.kalligraphie

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.graphiks.kalligraphie.api.BaseDirection
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.CaretAffinity
import org.graphiks.kalligraphie.api.EditableLineError
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.LineVerticalMetrics
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingResourceLimit
import org.graphiks.kalligraphie.api.ShapingResourceProfile
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextSnapshot
import org.graphiks.kalligraphie.api.TextVersion
import org.graphiks.kalligraphie.shaping.JvmHarfBuzzShapingBackend

class JvmEditableLineLayoutSessionTest {
    @Test
    fun oneSessionShapesSuccessiveRealEditsToAuditedGlyphClusterAndCaretGeometry() {
        val font = liberationSans()
        val session = openSession()
        try {
            val first = snapshot("office שלום")
            val second = snapshot("office! שלום")

            assertEquals(firstOracle(), observe(first, session.layout(request(first, font))))
            assertEquals(secondOracle(), observe(second, session.layout(request(second, font))))
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(session.close())
        }
    }

    @Test
    fun concurrentLayoutsOnOneSessionMatchTheSameCompleteAuditedLine() {
        val font = liberationSans()
        val text = snapshot("office שלום")
        val session = openSession()
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<EditableLineResult>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            val workers = List(8) {
                thread(name = "editable-line-session-layout-$it") {
                    try {
                        ready.countDown()
                        start.await()
                        results += session.layout(request(text, font))
                    } catch (error: Throwable) {
                        failures += error
                    }
                }
            }
            ready.await()
            start.countDown()
            workers.forEach { worker ->
                worker.join(10_000)
                assertTrue(!worker.isAlive, "A concurrent layout did not complete.")
            }

            assertEquals(emptyList(), failures)
            assertEquals(8, results.size)
            assertEquals(List(8) { firstOracle() }, results.map { observe(text, it) })
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(session.close())
        }
    }

    @Test
    fun closeWaitsForAdmittedLayoutRejectsLaterWorkAndIsConcurrentSafe() {
        val font = liberationSans()
        val text = snapshot("office שלום")
        val session = openSession()
        val admittedReachedCancellation = CountDownLatch(1)
        val releaseAdmitted = CountDownLatch(1)
        val admittedResult = AtomicReference<EditableLineResult>()
        val admittedFailure = AtomicReference<Throwable?>()
        val admitted = thread(name = "admitted-editable-line-layout") {
            try {
                admittedResult.set(
                    session.layout(
                        request(
                            text,
                            font,
                            cancellationToken = CancellationToken {
                                admittedReachedCancellation.countDown()
                                releaseAdmitted.await()
                                false
                            },
                        ),
                    ),
                )
            } catch (error: Throwable) {
                admittedFailure.set(error)
            }
        }
        val closeStarted = CountDownLatch(4)
        val closeResults = Collections.synchronizedList(mutableListOf<FontOperationResult<Unit>>())
        val closeFailures = Collections.synchronizedList(mutableListOf<Throwable>())
        var closers: List<Thread> = emptyList()
        try {
            assertTrue(admittedReachedCancellation.await(10, java.util.concurrent.TimeUnit.SECONDS))
            closers = List(4) {
                thread(name = "editable-line-session-close-$it") {
                    try {
                        closeStarted.countDown()
                        closeResults += session.close()
                    } catch (error: Throwable) {
                        closeFailures += error
                    }
                }
            }
            closeStarted.await()

            var racingResult: EditableLineResult
            do {
                racingResult = session.layout(request(text, font))
                if (racingResult is EditableLineResult.Success) {
                    assertEquals(firstOracle(), observe(text, racingResult))
                }
                Thread.yield()
            } while (racingResult is EditableLineResult.Success)

            assertClosedFailure(racingResult)
            assertEquals(emptyList(), closeResults)
        } finally {
            releaseAdmitted.countDown()
        }

        admitted.join(10_000)
        assertTrue(!admitted.isAlive, "The admitted layout did not finish after release.")
        closers.forEach { closer ->
            closer.join(10_000)
            assertTrue(!closer.isAlive, "A concurrent close did not finish.")
        }
        assertEquals(null, admittedFailure.get())
        assertEquals(firstOracle(), observe(text, admittedResult.get()))
        assertEquals(emptyList(), closeFailures)
        assertEquals(4, closeResults.size)
        closeResults.forEach { result -> assertIs<FontOperationResult.Success<Unit>>(result) }
        assertIs<FontOperationResult.Success<Unit>>(session.close())
        assertClosedFailure(session.layout(request(text, font)))
    }

    @Test
    fun realGlyphLimitFailureDoesNotPoisonThePreparedSessionForAnExactRetry() {
        val font = liberationSans()
        val text = snapshot("office שלום")
        val session = openSession()
        try {
            val failed = session.layout(
                request(
                    text,
                    font,
                    shapingResourceProfile = ShapingResourceProfile(maxGlyphs = 0),
                ),
            )
            val shapingFailure = assertIs<EditableLineError.ShapingFailure>(
                assertIs<EditableLineResult.Failure>(failed).error,
            )
            val limit = assertIs<FontError.ShapingResourceLimitExceeded>(shapingFailure.fontError)
            assertEquals(ShapingResourceLimit.GLYPHS, limit.limit)
            assertEquals(7, limit.observed)

            assertEquals(firstOracle(), observe(text, session.layout(request(text, font))))
        } finally {
            assertIs<FontOperationResult.Success<Unit>>(session.close())
        }
    }

    private fun openSession(): JvmEditableLineLayoutSession =
        assertIs<FontOperationResult.Success<JvmEditableLineLayoutSession>>(
            JvmEditableLineLayoutSession.open(),
        ).value

    private fun request(
        snapshot: TextSnapshot,
        font: FontInstance,
        cancellationToken: CancellationToken = CancellationToken.none,
        shapingResourceProfile: ShapingResourceProfile = ShapingResourceProfile.unbounded,
    ): JvmEditableLineFacadeRequest = JvmEditableLineFacadeRequest(
        snapshot = snapshot,
        font = font,
        baseDirection = BaseDirection.LEFT_TO_RIGHT,
        language = "en",
        featurePolicy = JvmHarfBuzzShapingBackend.pinnedFeaturePolicy,
        features = emptyList(),
        verticalMetrics = LineVerticalMetrics(LayoutUnit(18f), LayoutUnit(6f)),
        materialization = EditableLineMaterialization.LayoutOnly,
        cancellationToken = cancellationToken,
        shapingResourceProfile = shapingResourceProfile,
    )

    private fun snapshot(text: String): TextSnapshot = Kalligraphie.decodeUtf16(
        version = TextVersion.create(),
        slices = listOf(TextSlice.Utf16(text.toCharArray())),
    ).snapshot

    private fun liberationSans(): FontInstance {
        val catalog = assertIs<FontOperationResult.Success<org.graphiks.kalligraphie.api.FontCatalogSnapshot>>(
            Kalligraphie.embedded(
                sourceBytes = fixtureBytes(),
                provenance = FontSourceProvenance(declaredName = "Liberation Sans Regular"),
            ),
        ).value
        val face = assertIs<FontOperationResult.Success<FontFace>>(
            catalog.resolveFace(catalog.faces.single().id, FontAccessRequirementsSnapshot.layoutOnly()),
        ).value
        return assertIs<FontOperationResult.Success<FontInstance>>(
            face.instantiate(FontInstanceDescriptor(layoutSize = LayoutUnit(2048f))),
        ).value
    }

    private fun fixtureBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fonts/liberation/LiberationSans-Regular.ttf")) {
            "fixture font resource is missing"
        }.use { it.readBytes() }

    private fun observe(snapshot: TextSnapshot, result: EditableLineResult): LineOracle {
        val line = assertIs<EditableLineResult.Success>(result).line
        return LineOracle(
            runs = line.positionedGlyphRuns.map { run ->
                RunOracle(
                    visualOrder = run.visualOrder,
                    direction = run.sourceRun.direction,
                    glyphIds = run.glyphs.map { glyph -> glyph.shapedGlyph.glyphId.value },
                    advances = run.glyphs.map { glyph -> glyph.advance.x.value },
                    clusters = run.sourceRun.clusters.map { cluster -> scalarRange(snapshot, cluster.sourceRange) },
                )
            },
            carets = line.allCaretCandidates.map { caret ->
                CaretOracle(
                    scalarBoundary = scalarBoundary(snapshot, caret.position.index),
                    affinity = caret.position.affinity,
                    x = caret.geometry.start.x.value,
                    visualOrder = caret.visualOrder,
                    visualRunOrder = caret.visualRunOrder,
                    bidiLevel = caret.bidiLevel,
                    direction = caret.direction,
                )
            },
        )
    }

    private fun assertClosedFailure(result: EditableLineResult) {
        val error = assertIs<EditableLineError.ShapingFailure>(
            assertIs<EditableLineResult.Failure>(result).error,
        ).fontError
        assertIs<FontError.ResourceClosed>(error)
    }

    private fun scalarRange(snapshot: TextSnapshot, range: TextRange): Pair<Int, Int> =
        scalarBoundary(snapshot, range.start) to scalarBoundary(snapshot, range.endExclusive)

    private fun scalarBoundary(snapshot: TextSnapshot, index: org.graphiks.kalligraphie.api.TextIndex): Int =
        (0..snapshot.scalars.size).single { ordinal -> snapshot.textIndexAtScalarBoundary(ordinal) == index }

    private fun firstOracle(): LineOracle = LineOracle(
        runs = listOf(
            RunOracle(
                visualOrder = 0,
                direction = ShapingDirection.LEFT_TO_RIGHT,
                glyphIds = listOf(82, 73, 73, 76, 70, 72, 3),
                advances = listOf(1139f, 532f, 569f, 455f, 1024f, 1139f, 569f),
                clusters = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7),
            ),
            RunOracle(
                visualOrder = 1,
                direction = ShapingDirection.RIGHT_TO_LEFT,
                glyphIds = listOf(1293, 1285, 1292, 1305),
                advances = listOf(1389f, 532f, 1085f, 1495f),
                clusters = listOf(7 to 8, 8 to 9, 9 to 10, 10 to 11),
            ),
        ),
        carets = listOf(
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 1139f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 1671f, 2, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 2240f, 3, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 2695f, 4, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(5, CaretAffinity.DOWNSTREAM, 3719f, 5, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(6, CaretAffinity.DOWNSTREAM, 4858f, 6, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(7, CaretAffinity.UPSTREAM, 5427f, 7, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(11, CaretAffinity.UPSTREAM, 5427f, 8, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(10, CaretAffinity.DOWNSTREAM, 6816f, 9, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(9, CaretAffinity.DOWNSTREAM, 7348f, 10, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(8, CaretAffinity.DOWNSTREAM, 8433f, 11, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(7, CaretAffinity.DOWNSTREAM, 9928f, 12, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
        ),
    )

    private fun secondOracle(): LineOracle = LineOracle(
        runs = listOf(
            RunOracle(
                visualOrder = 0,
                direction = ShapingDirection.LEFT_TO_RIGHT,
                glyphIds = listOf(82, 73, 73, 76, 70, 72, 4, 3),
                advances = listOf(1139f, 532f, 569f, 455f, 1024f, 1139f, 569f, 569f),
                clusters = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8),
            ),
            RunOracle(
                visualOrder = 1,
                direction = ShapingDirection.RIGHT_TO_LEFT,
                glyphIds = listOf(1293, 1285, 1292, 1305),
                advances = listOf(1389f, 532f, 1085f, 1495f),
                clusters = listOf(8 to 9, 9 to 10, 10 to 11, 11 to 12),
            ),
        ),
        carets = listOf(
            CaretOracle(0, CaretAffinity.DOWNSTREAM, 0f, 0, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(1, CaretAffinity.DOWNSTREAM, 1139f, 1, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(2, CaretAffinity.DOWNSTREAM, 1671f, 2, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(3, CaretAffinity.DOWNSTREAM, 2240f, 3, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(4, CaretAffinity.DOWNSTREAM, 2695f, 4, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(5, CaretAffinity.DOWNSTREAM, 3719f, 5, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(6, CaretAffinity.DOWNSTREAM, 4858f, 6, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(7, CaretAffinity.DOWNSTREAM, 5427f, 7, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(8, CaretAffinity.UPSTREAM, 5996f, 8, 0, 0, ShapingDirection.LEFT_TO_RIGHT),
            CaretOracle(12, CaretAffinity.UPSTREAM, 5996f, 9, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(11, CaretAffinity.DOWNSTREAM, 7385f, 10, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(10, CaretAffinity.DOWNSTREAM, 7917f, 11, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(9, CaretAffinity.DOWNSTREAM, 9002f, 12, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
            CaretOracle(8, CaretAffinity.DOWNSTREAM, 10497f, 13, 1, 1, ShapingDirection.RIGHT_TO_LEFT),
        ),
    )

    private data class LineOracle(
        val runs: List<RunOracle>,
        val carets: List<CaretOracle>,
    )

    private data class RunOracle(
        val visualOrder: Int,
        val direction: ShapingDirection,
        val glyphIds: List<Int>,
        val advances: List<Float>,
        val clusters: List<Pair<Int, Int>>,
    )

    private data class CaretOracle(
        val scalarBoundary: Int,
        val affinity: CaretAffinity,
        val x: Float,
        val visualOrder: Int,
        val visualRunOrder: Int,
        val bidiLevel: Int,
        val direction: ShapingDirection,
    )
}
