@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.platform.ios

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

/**
 * One font the platform's CoreText registry can supply, already assembled as a
 * standalone SFNT byte container.
 *
 * iOS sandboxes apps away from the system font files, so the platform cannot
 * hand out file paths; the bytes are rebuilt from the font's CoreText tables.
 * Family and face names therefore come from parsing these bytes.
 */
public class IosRegisteredFont(
    /** The assembled standalone SFNT container. */
    public val bytes: ByteArray,
)

/**
 * Seam over the platform font registry.
 *
 * Production uses [CoreTextFontRegistry]; tests supply a controlled
 * implementation so a catalogue can be exercised against a deterministic
 * platform-visible set. Implementations must be read-only and must not mutate
 * caller state.
 */
public fun interface IosFontRegistry {
    /** Returns the fonts the platform currently exposes to this process. */
    public fun availableFonts(): List<IosRegisteredFont>
}

/** Immutable positive bounds for one CoreText-registry capture. */
public class IosSystemFontCatalogOptions(
    /** Registered fonts inspected, including unusable ones. */
    public val maxFonts: Int = 512,
    /** Accepted faces retained across all registered fonts. */
    public val maxFaces: Int = 128,
    /** Bytes retained per assembled font. */
    public val maxSourceBytes: Int = 16 * 1024 * 1024,
    /** Retained bytes of unique accepted containers. */
    public val maxTotalSourceBytes: Long = 256L * 1024 * 1024,
    /** Returned diagnostics, including a truncation diagnostic. */
    public val maxDiagnostics: Int = 64,
) {
    init {
        require(maxFonts > 0 && maxFaces > 0 && maxSourceBytes > 0 && maxTotalSourceBytes > 0 && maxDiagnostics > 0) {
            "CoreText capture limits must be positive."
        }
    }
}

/**
 * iOS system-font provider.
 *
 * Discovery comes from the platform's **CoreText registry** — the fonts
 * CoreText reports for the process, not a directory listing — and the portable
 * data is the OpenType content rebuilt from each font's CoreText tables, because
 * iOS does not expose system font files to an app. Each success owns a fresh
 * `ios-coretext-registry` generation and detached captured bytes, so a later
 * registry change cannot alter an earlier snapshot. Callers close their
 * resolvers and assets.
 *
 * The provider never matches by family name and never creates a platform font
 * handle that escapes. A font that cannot be assembled or exceeds a bound
 * contributes a bounded diagnostic and is skipped; cancellation publishes no
 * partial catalogue.
 */
public object IosSystemFontCatalog {
    private var generationCounter = 0L

    /**
     * Enumerates the CoreText registry and captures the portable catalogue.
     *
     * @param registry platform registry seam; defaults to the CoreText-backed
     * implementation so a registry failure is a typed error rather than a thrown
     * exception.
     */
    public fun open(
        options: IosSystemFontCatalogOptions = IosSystemFontCatalogOptions(),
        cancellationToken: CancellationToken = CancellationToken.none,
        registry: IosFontRegistry? = null,
    ): FontOperationResult<FontCatalogSnapshot> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val sourceRegistry = registry ?: try {
            CoreTextFontRegistry()
        } catch (failure: Throwable) {
            return failure(
                "font.ios.registry-unavailable",
                "The CoreText registry is unavailable: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        val registered = try {
            sourceRegistry.availableFonts()
        } catch (failure: Throwable) {
            return failure(
                "font.ios.registry-unavailable",
                "The CoreText registry could not be enumerated: ${failure.message ?: failure::class.simpleName}.",
            )
        }
        if (registered.isEmpty()) {
            return failure("font.ios.no-registered-fonts", "The CoreText registry reported no fonts.")
        }

        val diagnostics = Diagnostics(options.maxDiagnostics)
        val entries = ArrayList<EmbeddedFontCatalogEntry>()
        val seen = HashSet<FontSourceId>()
        var retainedBytes = 0L
        var inspected = 0
        for (font in registered) {
            if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled(diagnostics.values())
            if (entries.size >= options.maxFaces) { diagnostics.limit("Accepted face limit reached."); break }
            if (inspected >= options.maxFonts) { diagnostics.limit("Registered font limit reached."); break }
            inspected++
            val bytes = font.bytes
            if (bytes.isEmpty()) {
                diagnostics.add("font.ios.registered-font-empty", "A registered font carried no tables.")
                continue
            }
            if (bytes.size > options.maxSourceBytes) {
                diagnostics.limit("Source byte limit reached.")
                continue
            }
            val source = FontSource(bytes, FontSourceProvenance("coretext-registry"))
            if (!seen.add(source.id)) continue
            if (retainedBytes + bytes.size > options.maxTotalSourceBytes) {
                diagnostics.limit("Aggregate source byte limit reached.")
                continue
            }
            when (val parsed = SfntReader.readMetadata(source)) {
                is FontOperationResult.Success -> {
                    if (entries.size >= options.maxFaces) { diagnostics.limit("Accepted face limit reached."); break }
                    entries += EmbeddedFontCatalogEntry(source, parsed.value, 0)
                    diagnostics.addAll(parsed.diagnostics)
                    retainedBytes += bytes.size
                }

                is FontOperationResult.Failure -> diagnostics.addAll(parsed.diagnostics.ifEmpty { listOf(parsed.error.toDiagnostic()) })
                is FontOperationResult.Cancelled -> return FontOperationResult.Cancelled(diagnostics.values())
            }
        }
        if (entries.isEmpty()) {
            return failure("font.ios.no-capturable-fonts", "No registered font face could be captured.")
        }
        val generation = FontCatalogGeneration(
            FontProviderId("ios-coretext-registry"),
            "ios-coretext-${++generationCounter}",
        )
        return FontOperationResult.Success(EmbeddedFontCatalog(generation, entries), diagnostics.values())
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
