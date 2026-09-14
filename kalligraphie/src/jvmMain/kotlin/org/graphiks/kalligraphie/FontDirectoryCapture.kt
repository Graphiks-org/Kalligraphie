@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import org.graphiks.kalligraphie.api.*
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalogEntry
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.font.sfnt.TrueTypeCollectionReader

internal fun captureFontDirectories(options: FontDirectoryCatalogOptions, token: CancellationToken, provider: String): FontOperationResult<FontCatalogSnapshot> {
    val diagnostics = CaptureDiagnostics(options.maxDiagnostics)
    val candidates = linkedSetOf<Path>()
    var pathsVisited = 0
    var limited = false
    var rejectedError: FontError? = null
    fun reject(error: FontError, values: List<FontDiagnostic>, context: String) {
        rejectedError = error
        diagnostics.addAll(values.ifEmpty { listOf(error.toDiagnostic()) }.map { it.copy(message = "$context: ${it.message}") })
    }
    fun limit(message: String) { limited = true; diagnostics.add(FontError.ResourceLimitExceeded(message, FontDiagnosticLocation.Source)) }
    if (token.isCancellationRequested()) return FontOperationResult.Cancelled()
    for (root in options.roots) {
        if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
        if (pathsVisited >= options.maxPathsToVisit) { limit("Path discovery limit reached."); break }
        pathsVisited++
        try {
            val configuredPath = Path.of(root).toAbsolutePath().normalize()
            if (Files.isSymbolicLink(configuredPath)) { diagnostics.add(FontError.InvalidFontData("Symbolic font root is excluded.")); continue }
            if (!Files.exists(configuredPath, NOFOLLOW_LINKS)) { diagnostics.add(FontError.InvalidFontData("Font root is absent.")); continue }
            val rootPath = configuredPath.toRealPath()
            Files.walk(rootPath).use { stream ->
                val iterator = stream.iterator()
                if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
                iterator.next() // walk always starts with the already charged root
                if (Files.isRegularFile(rootPath, NOFOLLOW_LINKS) && isFontCandidate(rootPath)) candidates.add(rootPath)
                while (true) {
                    if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
                    if (pathsVisited >= options.maxPathsToVisit) { limit("Path discovery limit reached."); break }
                    if (!iterator.hasNext()) break
                    val path = iterator.next()
                    pathsVisited++
                    if (Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && isFontCandidate(path)) candidates.add(path)
                }
            }
        } catch (_: Exception) { diagnostics.add(FontError.InvalidFontData("Font root could not be inspected.")) }
    }
    val entries = mutableListOf<EmbeddedFontCatalogEntry>()
    val seen = mutableSetOf<FontSourceId>()
    var retainedBytes = 0L
    var examined = 0
    for (path in candidates.sortedBy(Path::toString)) {
        if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
        if (entries.size >= options.maxFaces) { limit("Accepted face limit reached."); break }
        if (examined >= options.maxFacesToExamine) { limit("Face examination limit reached."); break }
        val bytes = try {
            if (hasSymbolicAncestor(path)) { diagnostics.add(FontError.InvalidFontData("Symbolic candidate is excluded.")); continue }
            Files.newByteChannel(path, setOf(java.nio.file.StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
                val output = ByteArrayOutputStream()
                val buffer = java.nio.ByteBuffer.allocate(8192)
                while (true) {
                    if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), options.maxSourceBytes.toLong() - output.size() + 1L).toInt())
                    val count = channel.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > options.maxSourceBytes) { limit("Source byte limit reached."); return@use null }
                    output.write(buffer.array(), 0, count)
                }
                output.toByteArray()
            }
        } catch (_: Exception) { diagnostics.add(FontError.InvalidFontData("Font candidate could not be read.")); null } ?: continue
        val source = FontSource(bytes, FontSourceProvenance(path.fileName.toString()))
        if (!seen.add(source.id)) continue
        if (retainedBytes + bytes.size > options.maxTotalSourceBytes) { limit("Aggregate source byte limit reached."); continue }
        val remaining = options.maxFacesToExamine - examined
        val faces = if (bytes.size >= 4 && bytes[0] == 't'.code.toByte() && bytes[1] == 't'.code.toByte() && bytes[2] == 'c'.code.toByte() && bytes[3] == 'f'.code.toByte()) {
            when (val result = TrueTypeCollectionReader.readMetadata(source, remaining, token, onFaceExamined = { examined++ })) {
                is FontOperationResult.Success -> { diagnostics.addAll(result.diagnostics); result.value.map { it.faceIndex to it.metadata } }
                is FontOperationResult.Failure -> {
                    if (result.error is FontError.ResourceLimitExceeded) limited = true
                    reject(result.error, result.diagnostics, path.fileName.toString())
                    continue
                }
                is FontOperationResult.Cancelled -> return FontOperationResult.Cancelled(diagnostics.values())
            }
        } else {
            examined++
            listOf(0 to SfntReader.readMetadata(source))
        }
        var acceptedSource = false
        for ((index, parsed) in faces) {
            if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
            if (entries.size >= options.maxFaces) { limit("Accepted face limit reached."); break }
            when (parsed) {
                is FontOperationResult.Success -> { entries += EmbeddedFontCatalogEntry(source, parsed.value, index); acceptedSource = true; diagnostics.addAll(parsed.diagnostics) }
                is FontOperationResult.Failure -> reject(parsed.error, parsed.diagnostics, "${path.fileName} face $index")
                is FontOperationResult.Cancelled -> return FontOperationResult.Cancelled(diagnostics.values())
            }
        }
        if (acceptedSource) retainedBytes += bytes.size
    }
    if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
    if (entries.isEmpty()) return FontOperationResult.Failure(
        if (limited) FontError.ResourceLimitExceeded("No face fits the capture limits.", FontDiagnosticLocation.Source)
        else rejectedError ?: FontError.InvalidFontData("No supported TrueType face was captured."), diagnostics.values(),
    )
    val generation = FontCatalogGeneration(FontProviderId(provider), "snapshot-${captureGeneration.incrementAndGet()}")
    val catalog = EmbeddedFontCatalog(generation, entries, options.materializationCachePolicy, options.cacheScope)
    if (token.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
    return FontOperationResult.Success(catalog, diagnostics.values())
}

private val captureGeneration = AtomicLong()
private fun isFontCandidate(path: Path): Boolean = path.fileName.toString().substringAfterLast('.', "").lowercase() in setOf("ttf", "otf", "ttc", "otc")
private fun hasSymbolicAncestor(path: Path): Boolean {
    var current: Path? = path
    while (current != null) { if (Files.isSymbolicLink(current)) return true; current = current.parent }
    return false
}
private class CaptureDiagnostics(private val maximum: Int) {
    private val retained = mutableListOf<FontDiagnostic>()
    private var truncated = false
    fun add(error: FontError) = addAll(listOf(error.toDiagnostic()))
    fun addAll(values: List<FontDiagnostic>) { for (value in values) { if (retained.size < maximum) retained += value else truncated = true } }
    fun values(): List<FontDiagnostic> = if (!truncated) retained.toList() else retained.take(maximum - 1) + FontDiagnostic(
        "font.capture.diagnostics-truncated", FontDiagnosticSeverity.WARNING, FontDiagnosticLocation.Source, "Capture diagnostics were truncated.",
    )
}
