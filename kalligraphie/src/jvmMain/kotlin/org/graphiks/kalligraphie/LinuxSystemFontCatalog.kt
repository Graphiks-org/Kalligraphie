package org.graphiks.kalligraphie

import java.nio.file.Path
import org.graphiks.kalligraphie.api.*

/** Captures Linux font files from directories; this does not reproduce the Fontconfig registry. */
public object LinuxSystemFontCatalog {
    /** Returns deduplicated system, legacy user and XDG font locations without inspecting files. */
    public fun defaultRoots(): List<String> {
        val userHome = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && Path.of(it).isAbsolute } ?: "$userHome/.local/share"
        val dataDirs = System.getenv("XDG_DATA_DIRS")?.takeIf(String::isNotBlank) ?: "/usr/local/share:/usr/share"
        return (listOf("/usr/share/fonts", "/usr/local/share/fonts", "$userHome/.fonts", "$dataHome/fonts") + dataDirs.split(':').filter { it.isNotBlank() && Path.of(it).isAbsolute }.map { "$it/fonts" }).distinct()
    }
    /**
     * Uses [FontDirectoryCatalog] capture semantics in the `linux-system-opentype` domain.
     * Every success owns a fresh generation. Off Linux returns a typed unsupported error;
     * cancellation publishes no partial catalog. Callers close acquired assets and resolvers.
     */
    public fun open(options: FontDirectoryCatalogOptions = FontDirectoryCatalogOptions(defaultRoots()), cancellationToken: CancellationToken = CancellationToken.none): FontOperationResult<FontCatalogSnapshot> {
        if (!System.getProperty("os.name").startsWith("Linux")) return FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile("The Linux font provider is available only on Linux."))
        return captureFontDirectories(options, cancellationToken, "linux-system-opentype")
    }
}
