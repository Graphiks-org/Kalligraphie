@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.platform.android

import android.os.Build
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

/**
 * One font file reported by the platform's system font collection.
 *
 * Android's platform API enumerates reachable font files and collection
 * indices, not family or face names; the family and face names of a captured
 * entry come from parsing its SFNT data, exactly as for every other provider.
 */
public data class AndroidRegisteredFont(
    /** Absolute file path reported by the platform. */
    public val filePath: String,
)

/**
 * Seam over the platform font registry.
 *
 * Production uses [PlatformSystemFontRegistry]; tests supply a controlled
 * implementation so a catalogue can be exercised against a deterministic
 * platform-visible set. Implementations must be read-only and must not mutate
 * caller state.
 */
public fun interface AndroidSystemFontRegistry {
    /** Returns the font files the platform currently reports as available. */
    public fun availableFonts(): List<AndroidRegisteredFont>
}

/**
 * Registry backed by `android.graphics.fonts.SystemFonts`.
 *
 * Android exposes the system font set as files through `SystemFonts` only from
 * API 29 (Android 10); on earlier releases the platform offers no supported
 * enumeration route, so the registry reports an empty set and
 * [AndroidSystemFontCatalog.open] returns a typed failure instead of scanning
 * arbitrary paths.
 */
public class PlatformSystemFontRegistry : AndroidSystemFontRegistry {
    @Suppress("NewApi")
    override fun availableFonts(): List<AndroidRegisteredFont> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val fonts: Set<android.graphics.fonts.Font> = android.graphics.fonts.SystemFonts.getAvailableFonts()
        val paths = LinkedHashSet<String>()
        for (font in fonts) {
            val file = font.file ?: continue
            paths += file.absolutePath
        }
        return paths.map { AndroidRegisteredFont(it) }
    }
}

/** Immutable positive bounds for one Android-registry capture. */
public class AndroidSystemFontCatalogOptions(
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
            "Android capture limits must be positive."
        }
    }
}

/**
 * Android system-font provider.
 *
 * Discovery comes from the platform's **system font collection** reported by
 * `android.graphics.fonts.SystemFonts` (API 29+) — not arbitrary path scanning —
 * while the portable data remains the captured OpenType bytes. Each success owns
 * a fresh `android-platform-fonts` generation and detached captured bytes, so a
 * later system-font change cannot alter an earlier snapshot. Callers close their
 * resolvers and assets.
 *
 * The provider captures bytes for the reported files; it never matches by family
 * name and never creates a platform font handle. A reported file that is missing,
 * unreadable or exceeds a bound contributes a bounded diagnostic and is skipped;
 * cancellation publishes no partial catalogue.
 */
public object AndroidSystemFontCatalog {
    private val generationCounter = AtomicLong()

    /**
     * Enumerates the platform system font collection and captures the portable catalogue.
     *
     * @param registry platform registry seam; defaults to the platform-backed
     * implementation, whose absence of a supported API below API 29 is a typed
     * failure rather than an exploration of unknown paths.
     */
    public fun open(
        options: AndroidSystemFontCatalogOptions = AndroidSystemFontCatalogOptions(),
        cancellationToken: CancellationToken = CancellationToken.none,
        registry: AndroidSystemFontRegistry? = null,
    ): FontOperationResult<FontCatalogSnapshot> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val sourceRegistry = registry ?: try {
            PlatformSystemFontRegistry()
        } catch (failure: Throwable) {
            return failure(
                "font.android.registry-unavailable",
                "The Android font registry is unavailable: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        val registered = try {
            sourceRegistry.availableFonts()
        } catch (failure: Throwable) {
            return failure(
                "font.android.registry-unavailable",
                "The Android font registry could not be enumerated: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        val paths = registered.map(AndroidRegisteredFont::filePath).filter(String::isNotBlank).distinct().sorted()
        if (paths.isEmpty()) {
            return failure("font.android.no-registered-fonts", "The Android registry reported no font files.")
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
                diagnostics.add("font.android.registered-file-missing", "Registered font file is absent or not a regular file.")
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
            return failure("font.android.no-capturable-fonts", "No registered font face could be captured.")
        }
        val generation = FontCatalogGeneration(
            FontProviderId("android-platform-fonts"),
            "android-fonts-${generationCounter.incrementAndGet()}",
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
        diagnostics.add("font.android.registered-file-unreadable", "Registered font file could not be read.")
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
