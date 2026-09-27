package org.graphiks.kalligraphie.bench.scenarios

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.bench.MeasurementInstrument
import org.graphiks.kalligraphie.bench.MeasurementScenario
import org.graphiks.kalligraphie.bench.ThreadAllocationProbe
import org.graphiks.kalligraphie.bench.fixture.FixtureCorpus

/**
 * The one paragraph profile whose measure is a harness instrument rather than a product capability:
 * `ConcurrentResolveWarm` needs four persistent OS threads and a per-thread allocation counter.
 *
 * The file is compiled into the Java-family source sets alone — `kotlin.srcDir` adds it to `jvmMain`
 * and `androidMain` — so no platform can construct a profile it has no instrument to measure, and
 * no platform can measure a sequential loop under the concurrent profile's name. A platform without
 * the instrument reports it as deferred, by name, from
 * [MeasurementInstrument.PARALLEL_WORKERS]'s description.
 */

/** The four persistent workers of the original harness, one per partition. */
private const val WORKER_COUNT = 4

internal class WorkerObservation(val checksum: Long, val allocatedBytes: Long?)

/**
 * The scenarios the worker instrument contributes, in canonical report order. Every scenario here
 * needs [MeasurementInstrument.PARALLEL_WORKERS], and every caller filters on that before running
 * one. Public like the module's other scenario catalogues, because an instrumented target compiles
 * its harness in a separate compilation and reads the catalogue through it.
 */
public fun threadedInstrumentScenarios(corpus: FixtureCorpus): List<MeasurementScenario> {
    val liberation = CorpusFixture(
        "LiberationSans-Regular.ttf",
        "Liberation Sans Regular",
        GlyphId(36),
        corpus.bytes(LIBERATION_BYTES_PATH),
    )
    return listOf(ConcurrentResolve(corpus, liberation))
}

private class ConcurrentResolve(private val corpus: FixtureCorpus, private val fixture: CorpusFixture) : ParagraphScenario(
    name = "ConcurrentResolveWarm",
    route = "one renderer-owned Liberation Sans asset from public JvmEditableLineLayoutSession.layout -> openLayoutHandle -> retainFontAsset; 35 fixed distinct nonzero glyphs partitioned round-robin over four persistent workers",
    timedBoundary = "whole-wave wall time from dispatch until all four workers resolve and consume every corpus glyph exactly once; never divided by operations; allocation probes run inside workers",
    cacheState = "session/backend, resolver and layout handle closed before warmup; all corpus glyphs pre-resolved; workers started before timing; shared renderer asset closed after all waves",
) {
    override val requiredInstrument: MeasurementInstrument = MeasurementInstrument.PARALLEL_WORKERS

    private var asset: FontRenderAssetHandle? = null
    private var workers: List<ThreadPoolExecutor> = emptyList()

    override fun prepare() {
        validateHandoffFixture(this, corpus, fixture)
        val opened = rendererAssetFromLayout(fixture)
        consumeOutlines(this, opened, HANDOFF_GLYPH_CORPUS)
        asset = opened
        workers = newPersistentWorkers()
    }

    override fun operation() {
        val results = dispatchConcurrentWave(checkNotNull(asset), workers)
        results.forEach { sink(it.checksum) }
        val workerAllocations = if (results.all { it.allocatedBytes != null }) {
            results.sumOf { checkNotNull(it.allocatedBytes) }
        } else {
            null
        }
        if (workerAllocations != null) record("workerAllocatedBytes", workerAllocations)
        count("waves")
    }

    override fun release() {
        workers.forEach(::closeWorker)
        workers = emptyList()
        success(checkNotNull(asset).close())
        asset = null
    }
}

internal fun closeWorker(executor: ThreadPoolExecutor) {
    executor.shutdownNow()
    var interrupted = false
    while (!executor.isTerminated) {
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

internal fun dispatchConcurrentWave(asset: FontRenderAssetHandle, executors: List<ThreadPoolExecutor>): List<WorkerObservation> {
    val partitions = List(WORKER_COUNT) { worker -> HANDOFF_GLYPH_CORPUS.filterIndexed { index, _ -> index % WORKER_COUNT == worker } }
    return partitions.mapIndexed { worker, glyphs ->
        executors[worker].submit(
            Callable {
                val before = ThreadAllocationProbe.currentBytes()
                var checksum = 0L
                glyphs.forEach { glyph ->
                    val representation = success(asset.resolveGlyph(FontGlyphRequest(glyph)))
                    checksum += when (representation) {
                        is GlyphRepresentation.Outline -> representation.outline.let { outline ->
                            outline.glyphId.toLong() + outline.unitsPerEm + outline.bounds.minX + outline.bounds.minY +
                                outline.bounds.maxX + outline.bounds.maxY + outline.contours.size + outline.commands.size
                        }

                        GlyphRepresentation.Empty -> glyph.value.toLong()
                        else -> error("Concurrent outline measurement received $representation")
                    }
                }
                val after = ThreadAllocationProbe.currentBytes()
                WorkerObservation(checksum, if (before != null && after != null && after >= before) after - before else null)
            },
        )
    }.map { future -> future.get() }
}

internal fun newPersistentWorkers(): List<ThreadPoolExecutor> = List(WORKER_COUNT) {
    (Executors.newFixedThreadPool(1) as ThreadPoolExecutor).also { executor ->
        executor.prestartAllCoreThreads()
    }
}
