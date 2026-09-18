@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.sfnt

import org.graphiks.kalligraphie.api.BitmapGlyphIR
import org.graphiks.kalligraphie.api.BitmapGlyphMetrics
import org.graphiks.kalligraphie.api.BitmapLimits
import org.graphiks.kalligraphie.api.BitmapPixelFormat
import org.graphiks.kalligraphie.api.BitmapProfile
import org.graphiks.kalligraphie.api.BitmapResourceLimit
import org.graphiks.kalligraphie.api.BitmapStrike
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphColorSpace
import org.graphiks.kalligraphie.api.GlyphId

/**
 * Fully validated sbix version 1 colour bitmap route.
 *
 * This value supports only `'png '` and `'dupe'` glyph records in an exactly selected strike.
 * JPEG, TIFF, PDF, mask, and other graphic types are rejected while opening the route. It retains
 * only the selected strike's validated source records, never decoded pixels, and never exposes raw
 * sbix bytes to a consumer.
 *
 * [BitmapGlyphIR.originX] and [BitmapGlyphIR.originY] are the record's origin offsets converted to
 * strike pixels and published relative to the glyph design origin. The specification's
 * contour-dependent placement rule (drawing origin without a contour, glyph bounding-box
 * lower-left with one) is a renderer concern and is not applied here.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public class SbixData internal constructor(
    private val strike: BitmapStrike,
    private val glyphCount: Int,
    private val limits: BitmapLimits,
    private val records: Map<GlyphId, SbixRecord>,
) {
    /**
     * Decodes one validated glyph into a complete immutable colour bitmap.
     *
     * Cancellation is observed at entry and again after PNG decoding, before any pixels are
     * published.
     *
     * @return the decoded bitmap, a typed failure when [glyphId] is outside the face or the
     * selected strike has no record for it, or cancellation without partial pixels. An absent
     * strike record is not evidence that the glyph has no ink.
     */
    public fun decode(
        glyphId: GlyphId,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<BitmapGlyphIR> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId.value !in 0 until glyphCount) {
            return FontOperationResult.Failure(FontError.GlyphOutOfRange(glyphId.value, location = FontDiagnosticLocation.Glyph(glyphId.value)))
        }
        val record = records[glyphId] ?: return FontOperationResult.Failure(
            FontError.GlyphRepresentationUnavailable(
                glyphId = glyphId.value,
                message = "The selected sbix strike at ${strike.pixelsPerEmX} ppem has no bitmap for glyph ${glyphId.value}.",
            ),
        )
        val decoded = when (val result = PngDecoder.decode(record.pngBytes, limits, SBIT_TABLE)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (decoded.width != record.width || decoded.height != record.height) return dimensionMismatch()
        return FontOperationResult.Success(
            BitmapGlyphIR(
                glyphId = glyphId,
                strike = strike,
                width = record.width,
                height = record.height,
                originX = record.originX,
                originY = record.originY,
                metrics = record.metrics,
                pixelFormat = record.pixelFormat,
                colorSpace = GlyphColorSpace.SRGB,
                decodedPixels = decoded.copyPixels(),
            ),
        )
    }
}

/**
 * Reads the declared sbix version 1 colour-bitmap route into portable bounded records.
 *
 * The route validates every declared strike offset and every covered record in the selected
 * strike. Glyph metrics come from the consumer's `hmtx` advance provider because sbix strikes
 * carry no advance; origins are published as design-unit offsets converted to strike pixels.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public object SbixReader {
    /**
     * Reports whether the table declares the only header version this route can open.
     *
     * This inexpensive check validates the minimum header length and the version only. Call [read]
     * with an exact [BitmapProfile] to validate strike offsets, records, codecs, and resource
     * limits before issuing a certificate.
     */
    public fun hasSupportedVersionOneHeader(sbixTable: ByteArray): Boolean =
        sbixTable.size >= SBIT_HEADER_BASE_LENGTH && readUInt16(sbixTable, 0)?.toInt() == SBIT_VERSION_ONE

    /**
     * Reports whether every declared strike can use this reader's exact `'png '` and `'dupe'`
     * route.
     *
     * The predicate is conservative: a table is accepted only when *every* declared strike is
     * route-valid, so a single malformed or over-budget strike withdraws the route from the face.
     * It walks all strikes in one bounded pass that validates version, flags, strike offsets,
     * glyph offset arrays, record payloads, dupe chains, and resource limits with conservative
     * implementation limits while retaining no records and no pixels. Compressed and decoded byte
     * budgets accumulate across every declared strike, and a breach exits early with `false`.
     *
     * These cumulative totals are deliberately stricter than [read], which resets both totals for
     * the one strike its [BitmapProfile] selects. The two entry points are asymmetric: a `false`
     * here does not imply that any individual strike's [read] would fail, and a successful [read]
     * does not imply the table passes this predicate. The predicate publishes no data and is
     * intended only for a face capability prefilter. Call [read] again with the consumer's exact
     * [BitmapProfile] before issuing a certificate.
     */
    public fun hasStructurallyValidTable(
        sbixTable: ByteArray,
        glyphCount: Int,
        unitsPerEm: Int,
        advanceDesignUnits: (Int) -> Int?,
    ): Boolean {
        if (!hasSupportedVersionOneHeader(sbixTable) || glyphCount <= 0 || unitsPerEm !in MIN_UNITS_PER_EM..MAX_UNITS_PER_EM) {
            return false
        }
        if (sbixTable.size > MAX_CAPABILITY_TABLE_BYTES) return false
        if (!hasSupportedHeaderFlags(sbixTable)) return false
        val strikeCount = readUInt32(sbixTable, 4)?.toLong() ?: return false
        if (strikeCount !in 1L..MAX_CAPABILITY_STRIKES.toLong()) return false
        if (checkedRangeEnd(SBIT_HEADER_BASE_LENGTH.toLong(), strikeCount * STRIKE_OFFSET_LENGTH, sbixTable.size) == null) {
            return false
        }
        val budget = SbixCumulativeBudget()
        val seenStrikes = HashSet<BitmapStrike>()
        repeat(strikeCount.toInt()) { index ->
            val strikeOffset = readUInt32(sbixTable, SBIT_HEADER_BASE_LENGTH + index * STRIKE_OFFSET_LENGTH)?.toLong()
                ?: return false
            val strikeHeaderLength = STRIKE_HEADER_BASE_LENGTH + (glyphCount.toLong() + 1L) * GLYPH_OFFSET_LENGTH
            if (checkedRangeEnd(strikeOffset, strikeHeaderLength, sbixTable.size) == null) return false
            val ppem = readUInt16(sbixTable, strikeOffset.toInt())?.toInt() ?: return false
            if (ppem == 0) return false
            if (!seenStrikes.add(BitmapStrike(ppem, ppem, SBIT_BIT_DEPTH))) return false
            val walked = visitStrike(
                table = sbixTable,
                glyphCount = glyphCount,
                unitsPerEm = unitsPerEm,
                advanceDesignUnits = advanceDesignUnits,
                profile = capabilityProfile(ppem),
                strikeOffset = strikeOffset,
                ppem = ppem,
                retain = false,
                resolved = null,
                budget = budget,
            )
            if (walked !is FontOperationResult.Success) return false
        }
        return true
    }

    /**
     * Parses the exact strike requested by [profile].
     *
     * The operation validates every covered glyph record in the selected strike, resolves `'dupe'`
     * chains, and applies [BitmapProfile.limits] before it returns. A different strike, an
     * unsupported graphic type, a truncated table, a cycle, or a limit breach is returned as a
     * typed failure rather than deferred to a renderer. Duplicate declarations of the requested
     * strike are rejected (`font.sbix.duplicate-strike`); the capability predicate additionally
     * rejects any duplicate declared strike. Any strike with `ppem == 0` invalidates the table,
     * because this route does not model the `resolution`/`ppi` field and selection must be exact
     * and deterministic.
     *
     * @param sbixTable exact bytes of the OpenType `sbix` table.
     * @param glyphCount number of glyph identifiers declared by the face.
     * @param unitsPerEm design units per em declared by the face, used to convert record origins.
     * @param advanceDesignUnits exact `hmtx` horizontal advance for a glyph in design units, or
     * `null` when the face has no advance for it.
     * @param profile exact strike, format, color-space, and resource requirements.
     */
    public fun read(
        sbixTable: ByteArray,
        glyphCount: Int,
        unitsPerEm: Int,
        advanceDesignUnits: (Int) -> Int?,
        profile: BitmapProfile,
    ): FontOperationResult<SbixData> {
        if (profile.schemaVersion != 2) {
            return unsupported("Only schema version 2 sbix colour bitmap pixels are supported.")
        }
        if (profile.strike.bitDepth != SBIT_BIT_DEPTH ||
            BitmapPixelFormat.RGBA_8888 !in profile.acceptedPixelFormats ||
            GlyphColorSpace.SRGB !in profile.acceptedColorSpaces
        ) {
            return unsupported("Only 32-bit RGBA sbix bitmap pixels in sRGB are supported.")
        }
        if (glyphCount <= 0) return invalid("font.sbix.invalid-glyph-count", "sbix requires a positive face glyph count.", SBIT_TABLE)
        if (unitsPerEm !in MIN_UNITS_PER_EM..MAX_UNITS_PER_EM) {
            return invalid(
                "font.sbix.invalid-units-per-em",
                "sbix requires a units-per-em value between $MIN_UNITS_PER_EM and $MAX_UNITS_PER_EM.",
                SBIT_TABLE,
            )
        }
        if (sbixTable.size > profile.limits.maxSourceTableBytes) {
            return limit(BitmapResourceLimit.SOURCE_TABLE_BYTES, sbixTable.size.toLong(), profile.limits.maxSourceTableBytes, SBIT_TABLE)
        }
        if (sbixTable.size < SBIT_HEADER_BASE_LENGTH) return invalid("font.sbix.truncated", "sbix header is truncated.", SBIT_TABLE)
        if (readUInt16(sbixTable, 0)?.toInt() != SBIT_VERSION_ONE) {
            return unsupported("Only sbix version 1 is supported.")
        }
        if (!hasSupportedHeaderFlags(sbixTable)) {
            return invalid("font.sbix.invalid-flags", "sbix header flags are invalid.", SBIT_TABLE)
        }
        val strikeCount = readUInt32(sbixTable, 4)?.toLong()
            ?: return invalid("font.sbix.truncated", "sbix strike count is truncated.", SBIT_TABLE)
        if (strikeCount == 0L) return invalid("font.sbix.invalid-strike-count", "sbix requires at least one strike.", SBIT_TABLE)
        if (strikeCount > profile.limits.maxStrikes.toLong()) {
            return limit(BitmapResourceLimit.STRIKES, strikeCount, profile.limits.maxStrikes, SBIT_TABLE)
        }
        checkedRangeEnd(SBIT_HEADER_BASE_LENGTH.toLong(), strikeCount * STRIKE_OFFSET_LENGTH, sbixTable.size)
            ?: return invalid("font.sbix.truncated", "sbix strike offsets are truncated.", SBIT_TABLE)

        var selectedOffset: Long? = null
        var selectedPpem = 0
        repeat(strikeCount.toInt()) { index ->
            val strikeOffset = readUInt32(sbixTable, SBIT_HEADER_BASE_LENGTH + index * STRIKE_OFFSET_LENGTH)?.toLong()
                ?: return invalid("font.sbix.truncated", "sbix strike offset is truncated.", SBIT_TABLE)
            val strikeHeaderLength = STRIKE_HEADER_BASE_LENGTH + (glyphCount.toLong() + 1L) * GLYPH_OFFSET_LENGTH
            checkedRangeEnd(strikeOffset, strikeHeaderLength, sbixTable.size)
                ?: return invalid("font.sbix.invalid-strike-offset", "sbix strike offset is outside the table.", SBIT_TABLE)
            val ppem = readUInt16(sbixTable, strikeOffset.toInt())?.toInt()
                ?: return invalid("font.sbix.truncated", "sbix strike header is truncated.", SBIT_TABLE)
            if (ppem == 0) return invalid("font.sbix.invalid-strike", "sbix strike ppem must be positive.", SBIT_TABLE)
            if (BitmapStrike(ppem, ppem, SBIT_BIT_DEPTH) == profile.strike) {
                if (selectedOffset != null) {
                    return invalid("font.sbix.duplicate-strike", "sbix declares the requested strike more than once.", SBIT_TABLE)
                }
                selectedOffset = strikeOffset
                selectedPpem = ppem
            }
        }
        val strikeOffset = selectedOffset ?: return unsupported("The exact requested bitmap strike is unavailable.")
        return readStrike(sbixTable, glyphCount, unitsPerEm, advanceDesignUnits, profile, strikeOffset, selectedPpem)
    }

    private fun readStrike(
        table: ByteArray,
        glyphCount: Int,
        unitsPerEm: Int,
        advanceDesignUnits: (Int) -> Int?,
        profile: BitmapProfile,
        strikeOffset: Long,
        ppem: Int,
    ): FontOperationResult<SbixData> {
        val resolved = LinkedHashMap<GlyphId, SbixRecord>()
        return when (
            val walked = visitStrike(
                table = table,
                glyphCount = glyphCount,
                unitsPerEm = unitsPerEm,
                advanceDesignUnits = advanceDesignUnits,
                profile = profile,
                strikeOffset = strikeOffset,
                ppem = ppem,
                retain = true,
                resolved = resolved,
                budget = SbixCumulativeBudget(),
            )
        ) {
            is FontOperationResult.Success -> FontOperationResult.Success(
                SbixData(BitmapStrike(ppem, ppem, SBIT_BIT_DEPTH), glyphCount, profile.limits, resolved),
            )

            is FontOperationResult.Failure -> walked
            is FontOperationResult.Cancelled -> walked
        }
    }

    private fun visitStrike(
        table: ByteArray,
        glyphCount: Int,
        unitsPerEm: Int,
        advanceDesignUnits: (Int) -> Int?,
        profile: BitmapProfile,
        strikeOffset: Long,
        ppem: Int,
        retain: Boolean,
        resolved: MutableMap<GlyphId, SbixRecord>?,
        budget: SbixCumulativeBudget,
    ): FontOperationResult<Unit> {
        val offsets = LongArray(glyphCount + 1)
        var previousOffset = -1L
        repeat(glyphCount + 1) { index ->
            val value = readUInt32(table, strikeOffset.toInt() + STRIKE_HEADER_BASE_LENGTH + index * GLYPH_OFFSET_LENGTH)?.toLong()
                ?: return invalid("font.sbix.truncated", "sbix glyph offsets are truncated.", SBIT_TABLE)
            if (value < previousOffset) {
                return invalid("font.sbix.invalid-offset-order", "sbix glyph data offsets are not monotonic.", SBIT_TABLE)
            }
            offsets[index] = value
            previousOffset = value
        }
        val kinds = ByteArray(glyphCount)
        val duplicateTargets = IntArray(glyphCount)
        val retained = if (retain) LinkedHashMap<Int, RawSbixRecord>() else null
        var recordCount = 0
        repeat(glyphCount) { glyph ->
            val length = offsets[glyph + 1] - offsets[glyph]
            if (length == 0L) return@repeat
            recordCount++
            if (recordCount > profile.limits.maxRecordCount) {
                return limit(BitmapResourceLimit.RECORD_COUNT, recordCount.toLong(), profile.limits.maxRecordCount, SBIT_TABLE)
            }
            if (length < GLYPH_RECORD_HEADER_LENGTH.toLong()) {
                return invalid("font.sbix.invalid-record", "sbix glyph interval is shorter than a record header.", SBIT_TABLE)
            }
            val dataLength = length - GLYPH_RECORD_HEADER_LENGTH
            if (dataLength > profile.limits.maxCompressedBytes.toLong()) {
                return limit(BitmapResourceLimit.COMPRESSED_BYTES, dataLength, profile.limits.maxCompressedBytes, SBIT_TABLE)
            }
            if (exceedsCumulativeLimit(budget.compressed, dataLength, profile.limits.maxTotalCompressedBytes)) {
                return limit(BitmapResourceLimit.TOTAL_COMPRESSED_BYTES, budget.compressed + dataLength, profile.limits.maxTotalCompressedBytes, SBIT_TABLE)
            }
            val dataOffset = strikeOffset + offsets[glyph]
            val dataEnd = checkedRangeEnd(dataOffset, length, table.size)
                ?: return invalid("font.sbix.truncated", "sbix glyph data is truncated.", SBIT_TABLE)
            val originXDesignUnits = readInt16(table, dataOffset.toInt())
                ?: return invalid("font.sbix.truncated", "sbix glyph record is truncated.", SBIT_TABLE)
            val originYDesignUnits = readInt16(table, dataOffset.toInt() + 2)
                ?: return invalid("font.sbix.truncated", "sbix glyph record is truncated.", SBIT_TABLE)
            val graphicType = table.decodeAsciiTag(dataOffset.toInt() + ORIGIN_OFFSETS_LENGTH)
            val glyphId = GlyphId(glyph)
            val advanceDesignUnit = advanceDesignUnits(glyphId.value)
                ?: return invalid(
                    "font.sbix.missing-advance",
                    "sbix requires an hmtx advance for glyph ${glyphId.value}.",
                    SBIT_TABLE,
                )
            if (advanceDesignUnit > MAX_HMTX_ADVANCE) {
                return invalid(
                    "font.sbix.invalid-advance",
                    "sbix hmtx advance for glyph ${glyphId.value} is outside the uint16 domain.",
                    SBIT_TABLE,
                )
            }
            val metrics = BitmapGlyphMetrics(
                advanceX = convert(maxOf(0, advanceDesignUnit), ppem, unitsPerEm),
                advanceY = 0,
            )
            when (graphicType) {
                PNG_GRAPHIC_TYPE -> {
                    val payloadStart = dataOffset.toInt() + GLYPH_RECORD_HEADER_LENGTH
                    val header = when (val inspected = PngDecoder.inspectHeader(table, payloadStart, dataEnd, profile.limits, SBIT_TABLE)) {
                        is FontOperationResult.Success -> inspected.value
                        is FontOperationResult.Failure -> return inspected
                        is FontOperationResult.Cancelled -> return inspected
                    }
                    val decodedByteCount = header.width.toLong() * header.height.toLong() * RGBA_BYTES_PER_PIXEL.toLong()
                    if (exceedsCumulativeLimit(budget.decoded, decodedByteCount, profile.limits.maxTotalDecodedBytes)) {
                        return limit(
                            BitmapResourceLimit.TOTAL_DECODED_BYTES,
                            budget.decoded + decodedByteCount,
                            profile.limits.maxTotalDecodedBytes,
                            SBIT_TABLE,
                        )
                    }
                    budget.decoded += decodedByteCount
                    kinds[glyph] = RECORD_IMAGE.toByte()
                    if (retain) {
                        retained!![glyph] = RawSbixRecord.Image(
                            originX = convert(originXDesignUnits, ppem, unitsPerEm),
                            originY = convert(originYDesignUnits, ppem, unitsPerEm),
                            metrics = metrics,
                            width = header.width,
                            height = header.height,
                            pixelFormat = header.pixelFormat,
                            pngBytes = table.copyOfRange(payloadStart, dataEnd),
                        )
                    }
                }

                DUPE_GRAPHIC_TYPE -> {
                    if (dataLength != DUPE_PAYLOAD_LENGTH.toLong()) {
                        return invalid("font.sbix.invalid-dupe", "sbix dupe record must contain exactly one glyph identifier.", SBIT_TABLE)
                    }
                    val target = readUInt16(table, dataOffset.toInt() + GLYPH_RECORD_HEADER_LENGTH)?.toInt()
                        ?: return invalid("font.sbix.truncated", "sbix dupe target is truncated.", SBIT_TABLE)
                    if (target !in 0 until glyphCount) {
                        return invalid("font.sbix.invalid-dupe", "sbix dupe target $target is outside the face.", SBIT_TABLE)
                    }
                    kinds[glyph] = RECORD_DUPE.toByte()
                    duplicateTargets[glyph] = target
                    if (retain) {
                        retained!![glyph] = RawSbixRecord.Duplicate(
                            originX = convert(originXDesignUnits, ppem, unitsPerEm),
                            originY = convert(originYDesignUnits, ppem, unitsPerEm),
                            metrics = metrics,
                            target = target,
                        )
                    }
                }

                else -> return unsupported("Only sbix PNG and dupe glyph records are supported.")
            }
            budget.compressed += dataLength
        }

        val state = ByteArray(glyphCount)
        val path = ArrayList<Int>()
        for (start in 0 until glyphCount) {
            if (kinds[start].toInt() == RECORD_NONE || state[start].toInt() == RESOLVED_RECORD) continue
            path.clear()
            var current = start
            while (true) {
                when (state[current].toInt()) {
                    VISITING_RECORD -> return invalid(
                        "font.sbix.duplicate-cycle",
                        "sbix dupe records form a cycle through glyph $current.",
                        SBIT_TABLE,
                    )

                    RESOLVED_RECORD -> break
                }
                when (kinds[current].toInt()) {
                    RECORD_NONE -> return invalid(
                        "font.sbix.invalid-dupe",
                        "sbix dupe record references glyph $current without bitmap data.",
                        SBIT_TABLE,
                    )

                    RECORD_IMAGE -> {
                        if (retain) {
                            val image = retained!![current] as RawSbixRecord.Image
                            resolved!![GlyphId(current)] = SbixRecord(
                                width = image.width,
                                height = image.height,
                                originX = image.originX,
                                originY = image.originY,
                                metrics = image.metrics,
                                pixelFormat = image.pixelFormat,
                                pngBytes = image.pngBytes,
                            )
                        }
                        state[current] = RESOLVED_RECORD.toByte()
                        break
                    }

                    else -> {
                        state[current] = VISITING_RECORD.toByte()
                        path.add(current)
                        current = duplicateTargets[current]
                    }
                }
            }
            if (retain) {
                var source = resolved!![GlyphId(current)]
                    ?: return invalid("font.sbix.invalid-dupe", "sbix dupe record references glyph $current without bitmap data.", SBIT_TABLE)
                for (index in path.indices.reversed()) {
                    val id = path[index]
                    val raw = retained!![id] as RawSbixRecord.Duplicate
                    val duplicate = SbixRecord(
                        width = source.width,
                        height = source.height,
                        originX = raw.originX,
                        originY = raw.originY,
                        metrics = raw.metrics,
                        pixelFormat = source.pixelFormat,
                        pngBytes = source.pngBytes,
                    )
                    resolved[GlyphId(id)] = duplicate
                    state[id] = RESOLVED_RECORD.toByte()
                    source = duplicate
                }
            } else {
                for (id in path) {
                    state[id] = RESOLVED_RECORD.toByte()
                }
            }
        }
        return FontOperationResult.Success(Unit)
    }

    private fun capabilityProfile(ppem: Int): BitmapProfile = BitmapProfile(
        strike = BitmapStrike(ppem, ppem, SBIT_BIT_DEPTH),
        acceptedPixelFormats = listOf(BitmapPixelFormat.RGBA_8888),
        acceptedColorSpaces = listOf(GlyphColorSpace.SRGB),
        limits = BitmapLimits(
            maxStrikes = MAX_CAPABILITY_STRIKES,
            maxIndexSubtables = MAX_CAPABILITY_INDEX_SUBTABLES,
            maxRecordCount = MAX_CAPABILITY_RECORDS,
            maxIndexTableBytes = MAX_CAPABILITY_TABLE_BYTES,
            maxSourceTableBytes = MAX_CAPABILITY_TABLE_BYTES,
            maxWidth = MAX_CAPABILITY_DIMENSION,
            maxHeight = MAX_CAPABILITY_DIMENSION,
            maxPixels = MAX_CAPABILITY_PIXELS,
            maxCompressedBytes = MAX_CAPABILITY_TABLE_BYTES,
            maxTotalCompressedBytes = MAX_CAPABILITY_TABLE_BYTES,
            maxDecodedBytes = MAX_CAPABILITY_DECODED_BYTES,
            maxTotalDecodedBytes = MAX_CAPABILITY_DECODED_BYTES,
        ),
        schemaVersion = 2,
    )

    private fun hasSupportedHeaderFlags(sbixTable: ByteArray): Boolean {
        val flags = readUInt16(sbixTable, 2)?.toInt() ?: return false
        return flags and REQUIRED_FLAG != 0 && flags and RESERVED_FLAGS_MASK == 0
    }

    private fun invalid(code: String, message: String, table: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, FontDiagnosticLocation.Table(table)))

    private fun limit(
        dimension: BitmapResourceLimit,
        observed: Long,
        maximum: Int,
        table: String,
    ): FontOperationResult.Failure =
        FontOperationResult.Failure(
            FontError.BitmapResourceLimitExceeded(
                limit = dimension,
                observed = observed,
                maximum = maximum.toLong(),
                location = FontDiagnosticLocation.Table(table),
            ),
        )

    private fun unsupported(message: String): FontOperationResult.Failure =
        FontOperationResult.Failure(FontError.UnsupportedRepresentationProfile(message, FontDiagnosticLocation.Table(SBIT_TABLE)))
}

private fun exceedsCumulativeLimit(total: Long, increment: Long, maximum: Int): Boolean =
    increment > maximum.toLong() || total > maximum.toLong() - increment

private class SbixCumulativeBudget {
    var compressed: Long = 0L
    var decoded: Long = 0L
}

private fun dimensionMismatch(): FontOperationResult.Failure =
    FontOperationResult.Failure(
        FontError.FontDataFailure(
            code = "font.sbix.dimension-mismatch",
            message = "sbix PNG dimensions do not match the glyph metrics.",
            location = FontDiagnosticLocation.Table(SBIT_TABLE),
        ),
    )

private fun convert(designUnits: Int, ppem: Int, unitsPerEm: Int): Int {
    // The validated domain (|designUnits| <= 65535, ppem <= 65535, unitsPerEm >= 16) cannot reach
    // Int.MAX_VALUE; the saturation only guards a future caller that widens that domain.
    val magnitude = designUnits.toLong().let { if (it < 0) -it else it }
    val rounded = ((magnitude * ppem) + unitsPerEm / 2L) / unitsPerEm
    val saturated = rounded.coerceAtMost(Int.MAX_VALUE.toLong())
    return if (designUnits < 0) -saturated.toInt() else saturated.toInt()
}

private sealed interface RawSbixRecord {
    val originX: Int
    val originY: Int
    val metrics: BitmapGlyphMetrics

    class Image(
        override val originX: Int,
        override val originY: Int,
        override val metrics: BitmapGlyphMetrics,
        val width: Int,
        val height: Int,
        val pixelFormat: BitmapPixelFormat,
        val pngBytes: ByteArray,
    ) : RawSbixRecord

    class Duplicate(
        override val originX: Int,
        override val originY: Int,
        override val metrics: BitmapGlyphMetrics,
        val target: Int,
    ) : RawSbixRecord
}

internal data class SbixRecord(
    val width: Int,
    val height: Int,
    val originX: Int,
    val originY: Int,
    val metrics: BitmapGlyphMetrics,
    val pixelFormat: BitmapPixelFormat,
    val pngBytes: ByteArray,
)

private const val SBIT_TABLE = "sbix"
private const val SBIT_HEADER_BASE_LENGTH = 8
private const val STRIKE_OFFSET_LENGTH = 4
private const val STRIKE_HEADER_BASE_LENGTH = 4
private const val GLYPH_OFFSET_LENGTH = 4
private const val ORIGIN_OFFSETS_LENGTH = 4
private const val GLYPH_RECORD_HEADER_LENGTH = 8
private const val DUPE_PAYLOAD_LENGTH = 2
private const val RGBA_BYTES_PER_PIXEL = 4
private const val SBIT_VERSION_ONE = 1
private const val SBIT_BIT_DEPTH = 32
private const val MIN_UNITS_PER_EM = 16
private const val MAX_UNITS_PER_EM = 16_384
private const val MAX_HMTX_ADVANCE = 0xFFFF
private const val PNG_GRAPHIC_TYPE = "png "
private const val DUPE_GRAPHIC_TYPE = "dupe"
private const val REQUIRED_FLAG = 0x0001
private const val RESERVED_FLAGS_MASK = 0xFFFC
private const val VISITING_RECORD = 1
private const val RESOLVED_RECORD = 2
private const val RECORD_NONE = 0
private const val RECORD_IMAGE = 1
private const val RECORD_DUPE = 2
private const val MAX_CAPABILITY_TABLE_BYTES = 16 * 1024 * 1024
private const val MAX_CAPABILITY_STRIKES = 64
private const val MAX_CAPABILITY_INDEX_SUBTABLES = 4_096
private const val MAX_CAPABILITY_RECORDS = 65_536
private const val MAX_CAPABILITY_DIMENSION = 4_096
private const val MAX_CAPABILITY_PIXELS = MAX_CAPABILITY_DIMENSION * MAX_CAPABILITY_DIMENSION
private const val MAX_CAPABILITY_DECODED_BYTES = 64 * 1024 * 1024
