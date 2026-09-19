@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Structural view of one CFF2 table.
 *
 * CFF2 replaces the CFF1 Name/String indexes with a compact header, drops the
 * charset (glyph order is the CID order), and stores FDArray/FDSelect and an
 * ItemVariationStore. Charstrings have no width and no `endchar`; `blend` and
 * `vsindex` apply variation deltas.
 */
internal class Cff2Table private constructor(
    val topDict: CffDict,
    val globalSubrIndex: CffIndex,
    val charStringsIndex: CffIndex,
    val fontDicts: List<CffTable.CffFontDict>?,
    val fdSelect: IntArray?,
    val variationStoreOffset: Int?,
) {
    /** Number of glyphs in the CharStrings INDEX. */
    val glyphCount: Int
        get() = charStringsIndex.itemCount

    companion object {
        private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF2")

        /** Top DICT `CharStrings` operator. */
        const val CHAR_STRINGS_OP: Int = 17

        /** Top DICT `VariationStoreOffset` operator (single-byte 24). */
        const val VSTORE_OP: Int = 24

        /** Escaped Top DICT `FDArray` operator. */
        const val FD_ARRAY_OP: Int = 36

        /** Escaped Top DICT `FDSelect` operator. */
        const val FD_SELECT_OP: Int = 37

        /** Escaped Font DICT `Private` operator. */
        const val PRIVATE_OP: Int = 18

        /** Private DICT `Subrs` operator (relative to the Private DICT). */
        const val SUBRS_OP: Int = 19

        /** Reads the CFF2 table beginning at [offset]. */
        fun read(bytes: ByteArray, offset: Int = 0): FontOperationResult<Cff2Table> {
            if (offset < 0 || offset + 5 > bytes.size) return failure("CFF2 header is truncated.")
            val major = bytes[offset].toInt() and 0xFF
            if (major != 2) return failure("Only CFF2 major version 2 is supported by the CFF2 reader.")
            val headerSize = bytes[offset + 2].toInt() and 0xFF
            val topDictLength = readUInt16(bytes, offset + 3)
            if (headerSize < 5 || offset + headerSize + topDictLength > bytes.size) {
                return failure("CFF2 header or Top DICT is outside the source.")
            }
            val topDict = when (val result = CffDict.read(bytes, offset + headerSize, offset + headerSize + topDictLength)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val globalSubrIndex = when (val result = CffIndex.read(bytes, offset + headerSize + topDictLength, 4)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val charStringsOffset = topDict.integer(CHAR_STRINGS_OP)
                ?: return failure("CFF2 Top DICT does not declare a CharStrings offset.")
            if (charStringsOffset < 0 || charStringsOffset >= bytes.size) return failure("CFF2 CharStrings offset is outside the source.")
            val charStringsIndex = when (val result = CffIndex.read(bytes, charStringsOffset, 4)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val fontDicts = when (val result = readFontDicts(bytes, topDict)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val fdSelect = when (
                val result = readFdSelect(bytes, topDict, charStringsIndex.itemCount, fontDicts?.size ?: 0)
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val variationStoreOffset = topDict.integer(VSTORE_OP)
            if (variationStoreOffset != null && (variationStoreOffset < 0 || variationStoreOffset >= bytes.size)) {
                return failure("CFF2 vstore offset is outside the source.")
            }
            return FontOperationResult.Success(
                Cff2Table(topDict, globalSubrIndex, charStringsIndex, fontDicts, fdSelect, variationStoreOffset),
            )
        }

        private fun readFontDicts(bytes: ByteArray, topDict: CffDict): FontOperationResult<List<CffTable.CffFontDict>?> {
            val offset = topDict.integer(CffDict.escaped(FD_ARRAY_OP)) ?: return FontOperationResult.Success(null)
            if (offset < 0 || offset >= bytes.size) return failure("CFF2 FDArray offset is outside the source.")
            val index = when (val result = CffIndex.read(bytes, offset, 4)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val fontDicts = ArrayList<CffTable.CffFontDict>(index.itemCount)
            for (position in 0 until index.itemCount) {
                val item = index.item(position)
                val dict = when (val result = CffDict.read(item, 0, item.size)) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
                val ref = readPrivate(dict)
                val data = when (val result = readPrivateDict(bytes, ref)) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
                val subrs = when (val result = readLocalSubrs(bytes, ref, data)) {
                    is FontOperationResult.Success -> result.value
                    is FontOperationResult.Failure -> return result
                    is FontOperationResult.Cancelled -> return result
                }
                fontDicts.add(CffTable.CffFontDict(ref, data, subrs))
            }
            return FontOperationResult.Success(fontDicts)
        }

        private fun readPrivate(topDict: CffDict): CffTable.PrivateDictRef? {
            val size = topDict.integer(PRIVATE_OP, 0) ?: return null
            val offset = topDict.integer(PRIVATE_OP, 1) ?: return null
            return CffTable.PrivateDictRef(size, offset)
        }

        private fun readPrivateDict(
            bytes: ByteArray,
            ref: CffTable.PrivateDictRef?,
        ): FontOperationResult<CffDict?> {
            if (ref == null) return FontOperationResult.Success(null)
            if (ref.offset < 0 || ref.size < 0 || ref.offset.toLong() + ref.size.toLong() > bytes.size) {
                return failure("CFF2 Private DICT is outside the source.")
            }
            return when (val result = CffDict.read(bytes, ref.offset, ref.offset + ref.size)) {
                is FontOperationResult.Success -> FontOperationResult.Success(result.value)
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        }

        private fun readLocalSubrs(
            bytes: ByteArray,
            ref: CffTable.PrivateDictRef?,
            data: CffDict?,
        ): FontOperationResult<CffIndex?> {
            if (ref == null || data == null) return FontOperationResult.Success(null)
            val relative = data.integer(SUBRS_OP) ?: return FontOperationResult.Success(null)
            val absolute = ref.offset + relative
            if (relative < 0 || absolute < 0 || absolute >= bytes.size) return failure("CFF2 local Subrs offset is outside the source.")
            return when (val result = CffIndex.read(bytes, absolute, 4)) {
                is FontOperationResult.Success -> FontOperationResult.Success(result.value)
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        }

        private fun readFdSelect(
            bytes: ByteArray,
            topDict: CffDict,
            glyphCount: Int,
            fontDictCount: Int,
        ): FontOperationResult<IntArray?> {
            val offset = topDict.integer(CffDict.escaped(FD_SELECT_OP)) ?: return FontOperationResult.Success(null)
            if (offset < 0 || offset >= bytes.size) return failure("CFF2 FDSelect offset is outside the source.")
            val format = bytes[offset].toInt() and 0xFF
            val select = IntArray(glyphCount)
            when (format) {
                0 -> {
                    if (offset + 1 + glyphCount > bytes.size) return failure("CFF2 FDSelect format 0 is truncated.")
                    for (glyph in 0 until glyphCount) select[glyph] = bytes[offset + 1 + glyph].toInt() and 0xFF
                }

                3 -> {
                    var position = offset + 1
                    if (position + 2 > bytes.size) return failure("CFF2 FDSelect format 3 is truncated.")
                    val rangeCount = readUInt16(bytes, position); position += 2
                    if (position + rangeCount * 3 + 2 > bytes.size) return failure("CFF2 FDSelect ranges are truncated.")
                    val firsts = IntArray(rangeCount)
                    val assignments = IntArray(rangeCount)
                    for (range in 0 until rangeCount) {
                        firsts[range] = readUInt16(bytes, position); position += 2
                        assignments[range] = bytes[position].toInt() and 0xFF; position += 1
                    }
                    val sentinel = readUInt16(bytes, position)
                    for (glyph in 0 until glyphCount) {
                        if (glyph < firsts[0]) return failure("CFF2 FDSelect does not cover glyph $glyph.")
                        var range = 0
                        while (range + 1 < rangeCount && glyph >= firsts[range + 1]) range++
                        val end = if (range + 1 < rangeCount) firsts[range + 1] else sentinel
                        if (glyph >= end) return failure("CFF2 FDSelect does not cover glyph $glyph.")
                        select[glyph] = assignments[range]
                    }
                }

                else -> return failure("CFF2 FDSelect format $format is not defined.")
            }
            if (fontDictCount > 0) {
                for (value in select) if (value !in 0 until fontDictCount) return failure("CFF2 FDSelect references an undefined Font DICT.")
            }
            return FontOperationResult.Success(select)
        }

        private fun readUInt16(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun failure(message: String): FontOperationResult.Failure =
            FontOperationResult.Failure(FontError.InvalidFontData(message, location))
    }
}
