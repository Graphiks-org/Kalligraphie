package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceMetadata
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontInstanceDescriptor
import org.graphiks.kalligraphie.api.FontInstanceKey
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.GlyphMetrics
import org.graphiks.kalligraphie.api.GlyphResolution
import org.graphiks.kalligraphie.font.scaler.CmapReader
import org.graphiks.kalligraphie.font.scaler.MetricsReader
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont

internal class TrueTypeFace(
    sourceId: FontSourceId,
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
) : FontFace {
    override val metadata: FontFaceMetadata = parsedFont.metadata
    override val id: FontFaceId = FontFaceId("${sourceId.value}#0")

    override fun instantiate(descriptor: FontInstanceDescriptor): FontOperationResult<FontInstance> =
        FontOperationResult.Success(
            TrueTypeFontInstance(
                key = FontInstanceKey("${id.value}@${descriptor.layoutSize.value}"),
                descriptor = descriptor,
                sourceBytes = sourceBytes,
                parsedFont = parsedFont,
            ),
        )
}

private data class TrueTypeFontInstance(
    override val key: FontInstanceKey,
    private val descriptor: FontInstanceDescriptor,
    private val sourceBytes: ByteArray,
    private val parsedFont: ParsedTrueTypeFont,
) : FontInstance {
    override fun resolveGlyph(codePoint: Int): FontOperationResult<GlyphResolution> {
        val cmapTable = sourceBytes.sliceFor(parsedFont, "cmap") ?: return missingTable("cmap")
        return when (val result = CmapReader.resolveGlyphId(cmapTable, codePoint)) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                GlyphResolution(codePoint = codePoint, glyphId = result.value.glyphId),
                result.diagnostics,
            )
            is FontOperationResult.Failure -> result
            is FontOperationResult.Cancelled -> result
        }
    }

    override fun metrics(glyphId: GlyphId): FontOperationResult<GlyphMetrics> =
        MetricsReader.readGlyphMetrics(sourceBytes, parsedFont, glyphId, descriptor.layoutSize.value)

    private fun missingTable(tag: String): FontOperationResult.Failure =
        FontOperationResult.Failure(org.graphiks.kalligraphie.api.FontError.MissingRequiredTable(tag))

    private fun ByteArray.sliceFor(parsedFont: ParsedTrueTypeFont, tag: String): ByteArray? =
        parsedFont.tableRecords[tag]?.let { record ->
            org.graphiks.kalligraphie.font.sfnt.slice(this, record)
        }
}
