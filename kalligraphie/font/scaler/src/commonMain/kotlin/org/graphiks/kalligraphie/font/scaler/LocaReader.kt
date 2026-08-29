package org.graphiks.kalligraphie.font.scaler

import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.readUInt16
import org.graphiks.kalligraphie.font.sfnt.readUInt32
import org.graphiks.kalligraphie.font.sfnt.slice

public object LocaReader {
    public fun readLoca(
        sourceBytes: ByteArray,
        parsedFont: ParsedTrueTypeFont,
        glyfLength: Int,
    ): FontOperationResult<LocaTable> {
        val locaRecord = parsedFont.tableRecords["loca"] ?: return failure(missingTable("loca"))
        val loca = slice(sourceBytes, locaRecord)
            ?: return failure(fontFailure("font.loca.out-of-range", "Table loca exceeds source length.", tableLocation("loca")))
        val entryCount = parsedFont.metadata.glyphCount + 1
        val entrySize = when (parsedFont.indexToLocFormat) {
            0 -> 2
            1 -> 4
            else -> return failure(
                fontFailure(
                    "font.loca.invalid-format",
                    "head.indexToLocFormat must be 0 or 1.",
                    tableLocation("head"),
                ),
            )
        }
        val expectedLength = entryCount * entrySize
        if (loca.size < expectedLength) {
            return failure(fontFailure("font.loca.truncated", "loca table is truncated.", tableLocation("loca")))
        }
        if (loca.size != expectedLength) {
            return failure(fontFailure("font.loca.invalid-length", "loca table length does not match glyph count.", tableLocation("loca")))
        }

        val offsets = ArrayList<Int>(entryCount)
        repeat(entryCount) { index ->
            val offset = when (parsedFont.indexToLocFormat) {
                0 -> {
                    val value = readUInt16(loca, index * 2)?.toInt()
                        ?: return failure(fontFailure("font.loca.truncated", "loca short entry is truncated.", tableLocation("loca")))
                    val doubled = value * 2
                    if (doubled > glyfLength) {
                        return failure(fontFailure("font.loca.out-of-range", "loca short offset exceeds glyf length.", tableLocation("loca")))
                    }
                    doubled
                }
                else -> {
                    val value = readUInt32(loca, index * 4)
                        ?: return failure(fontFailure("font.loca.truncated", "loca long entry is truncated.", tableLocation("loca")))
                    if (value > Int.MAX_VALUE.toUInt() || value.toLong() > glyfLength.toLong()) {
                        return failure(fontFailure("font.loca.out-of-range", "loca long offset exceeds glyf length.", tableLocation("loca")))
                    }
                    value.toInt()
                }
            }
            if (offsets.isNotEmpty() && offset < offsets.last()) {
                return failure(fontFailure("font.loca.non-monotonic", "loca offsets must be monotonic.", tableLocation("loca")))
            }
            offsets += offset
        }
        return FontOperationResult.Success(LocaTable(offsets))
    }

    private fun missingTable(tag: String): FontError.MissingRequiredTable = FontError.MissingRequiredTable(tag)
}

public data class LocaTable(
    public val offsets: List<Int>,
) {
    public fun rangeForGlyph(glyphId: GlyphId): FontOperationResult<GlyphDataRange> {
        if (glyphId.value !in 0 until offsets.lastIndex) {
            return failure(FontError.GlyphOutOfRange(glyphId.value))
        }
        return FontOperationResult.Success(
            GlyphDataRange(
                start = offsets[glyphId.value],
                endExclusive = offsets[glyphId.value + 1],
            ),
        )
    }
}

public data class GlyphDataRange(
    public val start: Int,
    public val endExclusive: Int,
) {
    init {
        require(start >= 0) { "start must be non-negative." }
        require(endExclusive >= start) { "endExclusive must be at least start." }
    }
}

internal fun fontFailure(code: String, message: String, location: FontDiagnosticLocation): FontError.FontDataFailure =
    FontError.FontDataFailure(code = code, message = message, location = location)

internal fun tableLocation(tag: String): FontDiagnosticLocation = FontDiagnosticLocation.Table(tag)

internal fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
    FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
