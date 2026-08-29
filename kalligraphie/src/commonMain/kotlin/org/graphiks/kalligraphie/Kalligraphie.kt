package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceProvenance
import org.graphiks.kalligraphie.font.core.EmbeddedFontCatalog
import org.graphiks.kalligraphie.font.sfnt.SfntReader

public object Kalligraphie {
    public fun embedded(
        sourceBytes: ByteArray,
        provenance: FontSourceProvenance,
    ): FontOperationResult<FontCatalogSnapshot> {
        val source = FontSource(sourceBytes = sourceBytes, provenance = provenance)
        return when (val parsed = SfntReader.readMetadata(source)) {
            is FontOperationResult.Success<*> -> {
                val parsedFont = parsed.value as org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
                FontOperationResult.Success(EmbeddedFontCatalog(source, parsedFont), parsed.diagnostics)
            }
            is FontOperationResult.Failure -> parsed
            is FontOperationResult.Cancelled -> parsed
        }
    }
}
