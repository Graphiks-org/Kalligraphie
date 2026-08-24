package io.ygdrasil.kalligraphie.font.colr

internal const val CPAL_V0_HEADER_SIZE = 12
internal const val CPAL_COLOR_RECORD_SIZE = 4

/**
 * Parses OpenType CPAL version 0 table bytes into pure Kotlin palette models.
 *
 * The parser accepts raw CPAL table bytes whose first byte is the CPAL table header. It performs
 * all reads through checked big-endian helpers, rejects unsupported versions, and caps advertised
 * counts before expanding palette records so malformed fonts cannot force unbounded allocation.
 */
object CPALV0Parser {
    /**
     * Parses a CPAL version 0 table.
     *
     * @param bytes raw CPAL table bytes starting at offset zero.
     * @return parsed CPAL table, or null when the bytes are unsupported, truncated, out of range,
     * or exceed the defensive color-font caps used by the pure Kotlin parser.
     */
    fun parse(bytes: ByteArray): CPALTable? =
        parseCommonPrefix(bytes = bytes, allowVersionOne = false)

    /**
     * Parses the version 0-compatible palette prefix retained by a CPAL version 1 table.
     *
     * This entry point shares all common count, offset and color-record validation with [parse].
     * CPAL version 1 metadata arrays are deliberately outside this version 0-prefix authority.
     */
    fun parseCompatibleV0Prefix(bytes: ByteArray): CPALTable? =
        parseCommonPrefix(bytes = bytes, allowVersionOne = true)

    private fun parseCommonPrefix(
        bytes: ByteArray,
        allowVersionOne: Boolean,
    ): CPALTable? {
        val reader = ColorTableReader(bytes)
        if (!reader.fits(0, CPAL_V0_HEADER_SIZE.toLong())) return null

        val version = reader.u16(0) ?: return null
        if (version != 0 && !(allowVersionOne && version == 1)) return null

        val numPaletteEntries = reader.u16(2) ?: return null
        val numPalettes = reader.u16(4) ?: return null
        val numColorRecords = reader.u16(6) ?: return null
        val colorRecordsArrayOffset = reader.u32(8)?.toIntOrNull() ?: return null

        if (numPaletteEntries > MAX_COLOR_PALETTE_ENTRIES) return null
        if (numPalettes > MAX_COLOR_PALETTES) return null
        if (numColorRecords > MAX_COLOR_RECORDS) return null
        if (numPalettes.toLong() * numPaletteEntries.toLong() > MAX_EXPANDED_COLOR_RECORDS) {
            return null
        }
        if (!reader.fits(CPAL_V0_HEADER_SIZE, numPalettes.toLong() * U16_SIZE_BYTES.toLong())) return null
        if (!reader.fits(colorRecordsArrayOffset, numColorRecords.toLong() * CPAL_COLOR_RECORD_SIZE.toLong())) {
            return null
        }

        val palettes = ArrayList<List<Int>>(numPalettes)
        repeat(numPalettes) { paletteIndex ->
            val firstColorRecordIndex = reader.u16(CPAL_V0_HEADER_SIZE + paletteIndex * U16_SIZE_BYTES)
                ?: return null
            if (firstColorRecordIndex + numPaletteEntries > numColorRecords) return null

            val colors = ArrayList<Int>(numPaletteEntries)
            repeat(numPaletteEntries) { entryIndex ->
                val colorOffset = colorRecordsArrayOffset +
                    (firstColorRecordIndex + entryIndex) * CPAL_COLOR_RECORD_SIZE
                val blue = reader.u8(colorOffset) ?: return null
                val green = reader.u8(colorOffset + 1) ?: return null
                val red = reader.u8(colorOffset + 2) ?: return null
                val alpha = reader.u8(colorOffset + 3) ?: return null
                colors += packArgb(alpha = alpha, red = red, green = green, blue = blue)
            }

            palettes += colors.toList()
        }

        val paletteTypesOffset = CPAL_V0_HEADER_SIZE + numPalettes * U16_SIZE_BYTES
        val paletteTypes = if (
            version == 0 &&
            reader.fits(paletteTypesOffset, numPalettes.toLong() * U16_SIZE_BYTES.toLong())
        ) {
            List(numPalettes) { index ->
                reader.u16(paletteTypesOffset + index * U16_SIZE_BYTES) ?: 0
            }
        } else {
            emptyList()
        }

        val paletteLabelsOffset = paletteTypesOffset + numPalettes * U16_SIZE_BYTES
        val paletteLabels = if (
            version == 0 &&
            reader.fits(paletteLabelsOffset, numPalettes.toLong() * U16_SIZE_BYTES.toLong())
        ) {
            List(numPalettes) { index ->
                reader.u16(paletteLabelsOffset + index * U16_SIZE_BYTES) ?: 0
            }
        } else {
            emptyList()
        }

        val paletteEntryLabelsOffset = paletteLabelsOffset + numPalettes * U16_SIZE_BYTES
        val paletteEntryLabels = if (
            version == 0 &&
            numPaletteEntries > 0 &&
            reader.fits(paletteEntryLabelsOffset, numPaletteEntries.toLong() * U16_SIZE_BYTES.toLong())
        ) {
            List(numPaletteEntries) { index ->
                reader.u16(paletteEntryLabelsOffset + index * U16_SIZE_BYTES) ?: 0
            }
        } else {
            null
        }

        return CPALTable(
            version = version,
            palettes = palettes.toList(),
            paletteTypes = paletteTypes,
            paletteLabels = paletteLabels,
            paletteEntryLabels = paletteEntryLabels,
        )
    }
}
