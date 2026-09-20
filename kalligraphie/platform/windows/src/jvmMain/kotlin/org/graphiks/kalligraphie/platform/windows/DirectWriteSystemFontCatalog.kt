@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.platform.windows

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicLong
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontCatalogGeneration
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontProviderId
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalogEntry
import org.graphiks.kalligraphie.font.sfnt.SfntReader
import org.graphiks.kalligraphie.font.sfnt.TrueTypeCollectionReader

/** One font face reported by the platform's DirectWrite system font collection. */
public data class DirectWriteRegisteredFont(
    /** Family name reported by DirectWrite (`IDWriteFontFamily::GetFontFamilyNames`). */
    public val familyName: String,
    /** Face name reported by DirectWrite (`IDWriteFont::GetFaceNames`), or empty when unknown. */
    public val faceName: String,
    /** Absolute file path resolved by DirectWrite, or empty when unknown. */
    public val filePath: String,
    /** PostScript name reported by DirectWrite, or empty when unknown. */
    public val postScriptName: String,
)

/**
 * Seam over the platform font registry.
 *
 * Production uses [KffiDirectWriteFontRegistry]; tests supply a controlled
 * implementation so a catalogue can be exercised against a deterministic
 * platform-visible set. Implementations must be read-only and must not mutate
 * caller state.
 */
public fun interface DirectWriteFontRegistry {
    /** Returns the font faces the platform currently reports for its system collection. */
    public fun availableFonts(): List<DirectWriteRegisteredFont>
}

/**
 * Registry backed by `kffi-directwrite`'s `DirectWrite`.
 *
 * Construction loads the system DirectWrite library and may throw;
 * [DirectWriteSystemFontCatalog.open] converts that into a typed failure rather
 * than leaking an exception.
 */
public class KffiDirectWriteFontRegistry : DirectWriteFontRegistry {
    private val directWrite = org.graphiks.kffi.directwrite.DirectWrite()

    override fun availableFonts(): List<DirectWriteRegisteredFont> =
        directWrite.systemFonts().map { font ->
            DirectWriteRegisteredFont(font.family, font.faceName, font.filePath, font.postScriptName)
        }
}

/** Immutable positive bounds for one DirectWrite-registry capture. */
public class DirectWriteSystemFontCatalogOptions(
    /** Registered files inspected, including missing or unreadable ones. */
    public val maxFiles: Int = 512,
    /** Accepted faces retained across all registered files. */
    public val maxFaces: Int = 128,
    /** Bytes read per source container. */
    public val maxSourceBytes: Int = 16 * 1024 * 1024,
    /** Retained bytes of unique accepted containers. */
    public val maxTotalSourceBytes: Long = 256L * 1024 * 1024,
    /** Returned diagnostics, including a truncation diagnostic. */
    public val maxDiagnostics: Int = 64,
) {
    init {
        require(maxFiles > 0 && maxFaces > 0 && maxSourceBytes > 0 && maxTotalSourceBytes > 0 && maxDiagnostics > 0) {
            "DirectWrite capture limits must be positive."
        }
    }
}

/**
 * Faithful Windows system-font provider.
 *
 * Family and face discovery comes from the platform's **DirectWrite system font
 * collection** — the set DirectWrite reports for the active configuration, not a
 * conventional directory listing — while the portable data remains the captured
 * OpenType bytes. Each success owns a fresh `directwrite-registry` generation and
 * detached captured bytes, so later installation, removal or configuration
 * change cannot change an earlier snapshot. Callers close their resolvers and
 * assets.
 *
 * The provider captures bytes for the reported files; it never matches by family
 * name and never creates a platform font handle. A reported file that is missing,
 * unreadable or exceeds a bound contributes a bounded diagnostic and is skipped;
 * cancellation publishes no partial catalogue.
 */
public object DirectWriteSystemFontCatalog {
    private val generationCounter = AtomicLong()

    /**
     * Enumerates the DirectWrite system collection and captures the portable catalogue.
     *
     * @param registry platform registry seam; defaults to the kffi-backed
     * implementation, created lazily so an unavailable platform is a typed
     * failure rather than a thrown exception.
     */
    public fun open(
        options: DirectWriteSystemFontCatalogOptions = DirectWriteSystemFontCatalogOptions(),
        cancellationToken: CancellationToken = CancellationToken.none,
        registry: DirectWriteFontRegistry? = null,
    ): FontOperationResult<FontCatalogSnapshot> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val sourceRegistry = registry ?: try {
            KffiDirectWriteFontRegistry()
        } catch (failure: Throwable) {
            return failure(
                "font.directwrite.registry-unavailable",
                "The DirectWrite registry is unavailable: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        val registered = try {
            sourceRegistry.availableFonts()
        } catch (failure: Throwable) {
            return failure(
                "font.directwrite.registry-unavailable",
                "The DirectWrite registry could not be enumerated: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        val paths = registered.map(DirectWriteRegisteredFont::filePath).filter(String::isNotBlank).distinct().sorted()
        if (paths.isEmpty()) {
            return failure("font.directwrite.no-registered-fonts", "The DirectWrite registry reported no font files.")
        }

        val diagnostics = Diagnostics(options.maxDiagnostics)
        val entries = ArrayList<EmbeddedFontCatalogEntry>()
        val seen = HashSet<FontSourceId>()
        var retainedBytes = 0L
        var inspected = 0
        for (path in paths) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
            if (entries.size >= options.maxFaces) { diagnostics.limit("Accepted face limit reached."); break }
            if (inspected >= options.maxFiles) { diagnostics.limit("Registered file limit reached."); break }
            inspected++
            val file = Path.of(path)
            if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                diagnostics.add("font.directwrite.registered-file-missing", "Registered font file is absent or not a regular file.")
                continue
            }
            val bytes = readBounded(file, options.maxSourceBytes, cancellationToken, diagnostics)
            if (bytes == null) {
                if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
                continue
            }
            val source = FontSource(bytes, FontSourceProvenance(file.fileName.toString()))
            if (!seen.add(source.id)) continue
            if (retainedBytes + bytes.size > options.maxTotalSourceBytes) {
                diagnostics.limit("Aggregate source byte limit reached.")
                continue
            }
            val faces = readFaces(source, bytes, options.maxFaces - entries.size, cancellationToken, diagnostics)
            if (faces == null) {
                if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
                continue
            }
            var accepted = false
            for ((index, parsed) in faces) {
                if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
                if (entries.size >= options.maxFaces) { diagnostics.limit("Accepted face limit reached."); break }
                when (parsed) {
                    is FontOperationResult.Success -> {
                        entries += EmbeddedFontCatalogEntry(source, parsed.value, index)
                        diagnostics.addAll(parsed.diagnostics)
                        accepted = true
                    }

                    is FontOperationResult.Failure -> diagnostics.addAll(parsed.diagnostics.ifEmpty { listOf(parsed.error.toDiagnostic()) })
                    is FontOperationResult.Cancelled -> return FontOperationResult.Cancelled(diagnostics.values())
                }
            }
            if (accepted) retainedBytes += bytes.size
        }
        if (entries.isEmpty()) {
            return failure("font.directwrite.no-capturable-fonts", "No registered font face could be captured.")
        }
        val generation = FontCatalogGeneration(
            FontProviderId("directwrite-registry"),
            "directwrite-${generationCounter.incrementAndGet()}",
        )
        return FontOperationResult.Success(EmbeddedFontCatalog(generation, entries), diagnostics.values())
    }

    private fun readFaces(
        source: FontSource,
        bytes: ByteArray,
        remainingFaces: Int,
        token: CancellationToken,
        diagnostics: Diagnostics,
    ): List<Pair<Int, FontOperationResult<org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont>>>? {
        val isCollection = bytes.size >= 4 &&
            bytes[0] == 't'.code.toByte() && bytes[1] == 't'.code.toByte() &&
            bytes[2] == 'c'.code.toByte() && bytes[3] == 'f'.code.toByte()
        if (!isCollection) return listOf(0 to SfntReader.readMetadata(source))
        return when (val result = TrueTypeCollectionReader.readMetadata(source, remainingFaces, token)) {
            is FontOperationResult.Success -> result.value.map { it.faceIndex to it.metadata }
            is FontOperationResult.Failure -> { diagnostics.addAll(result.diagnostics); null }
            is FontOperationResult.Cancelled -> null
        }
    }

    private fun readBounded(
        file: Path,
        maxSourceBytes: Int,
        token: CancellationToken,
        diagnostics: Diagnostics,
    ): ByteArray? = try {
        Files.newByteChannel(file, setOf(StandardOpenOption.READ, NOFOLLOW_LINKS)).use { channel ->
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            var exceeded = false
            while (true) {
                if (token.isCancellationRequested()) return null
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), maxSourceBytes.toLong() - output.size() + 1L).toInt())
                val count = channel.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxSourceBytes) { exceeded = true; break }
                output.write(buffer.array(), 0, count)
            }
            if (exceeded) { diagnostics.limit("Source byte limit reached."); null } else output.toByteArray()
        }
    } catch (_: Exception) {
        diagnostics.add("font.directwrite.registered-file-unreadable", "Registered font file could not be read.")
        null
    }

    private fun failure(code: String, message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(
            FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source),
        )
}

private class Diagnostics(private val maximum: Int) {
    private val retained = ArrayList<FontDiagnostic>()
    private var truncated = false

    fun add(code: String, message: String) {
        addAll(listOf(FontDiagnostic(code, FontDiagnosticSeverity.WARNING, FontDiagnosticLocation.Source, message)))
    }

    fun addAll(values: List<FontDiagnostic>) {
        for (value in values) if (retained.size < maximum) retained += value else truncated = true
    }

    fun limit(message: String) {
        add("font.resource-limit-exceeded", message)
    }

    fun values(): List<FontDiagnostic> = if (!truncated) retained.toList() else retained.take(maximum - 1) + FontDiagnostic(
        "font.capture.diagnostics-truncated",
        FontDiagnosticSeverity.WARNING,
        FontDiagnosticLocation.Source,
        "Capture diagnostics were truncated.",
    )
}
