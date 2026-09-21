@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult

/**
 * Structural view of one CFF1 table.
 *
 * The reader walks the fixed CFF1 layout — header, Name INDEX, Top DICT INDEX,
 * String INDEX, Global Subr INDEX — then resolves the CharStrings and optional
 * charset/Private entries declared by the Top DICT. It does not interpret
 * charstrings; [CffReader] owns outline decoding.
 *
 * Only charstring type 2 is accepted. A non-2 type is refused with a typed
 * `font.cff.unsupported-charstring-type` failure rather than mis-decoded.
 *
 * Offsets are absolute within the same byte array the table was read from; for a
 * standalone `.otf` the enclosing `CFF ` table slice starts at offset `0`.
 *
 * @property nameIndex the CFF Name INDEX.
 * @property topDict the parsed Top DICT.
 * @property stringIndex the CFF String INDEX.
 * @property globalSubrIndex the Global Subr INDEX.
 * @property charStringsOffset absolute offset of the CharStrings INDEX.
 * @property charsetOffset absolute charset offset, or `null` for the predefined ISOAdobe charset.
 * @property privateDict the Private DICT size/offset pair, or `null` when absent.
 * @property charStringType declared charstring type; always `2` on success.
 */
internal class CffTable private constructor(
    val nameIndex: CffIndex,
    val topDict: CffDict,
    val stringIndex: CffIndex,
    val globalSubrIndex: CffIndex,
    val charStringsOffset: Int,
    val charStringsIndex: CffIndex,
    val glyphCount: Int,
    val charset: CffCharset,
    val charsetOffset: Int?,
    val privateDict: PrivateDictRef?,
    val privateDictData: CffDict?,
    val localSubrs: CffIndex?,
    val nominalWidthX: Int,
    val defaultWidthX: Int,
    val fontDicts: List<CffFontDict>?,
    val fdSelect: IntArray?,
    val charStringType: Int,
) {
    /** Whether the Top DICT declares a CID-keyed `ROS` entry. */
    val isCidKeyed: Boolean
        get() = topDict.operands(CffDict.escaped(ROS_OP)) != null

    /** Size and absolute offset of one Private DICT. */
    data class PrivateDictRef(
        /** Private DICT size in bytes. */
        val size: Int,
        /** Absolute Private DICT offset. */
        val offset: Int,
    )

    /** One CID-keyed Font DICT: its Private DICT and resolved local subroutines. */
    data class CffFontDict(
        /** Raw Private DICT size/offset, or `null` when absent. */
        val privateDict: PrivateDictRef?,
        /** Parsed Private DICT, or `null` when absent. */
        val privateData: CffDict?,
        /** Local subroutines declared by this Font DICT. */
        val localSubrs: CffIndex?,
    )

    companion object {
        /** Top DICT `CharStrings` operator. */
        const val CHAR_STRINGS_OP: Int = 17

        /** Top DICT `Private` operator (operands: size, offset). */
        const val PRIVATE_OP: Int = 18

        /** Top DICT `charset` operator. */
        const val CHARSET_OP: Int = 15

        /** Escaped Top DICT `ROS` operator (CID-keyed marker). */
        const val ROS_OP: Int = 30

        /** Escaped Top DICT `CharstringType` operator. */
        const val CHAR_STRING_TYPE_OP: Int = 6

        /** Private DICT `Subrs` operator (offset relative to the Private DICT start). */
        const val SUBRS_OP: Int = 19

        /** Private DICT `defaultWidthX` operator. */
        const val DEFAULT_WIDTH_X_OP: Int = 20

        /** Private DICT `nominalWidthX` operator. */
        const val NOMINAL_WIDTH_X_OP: Int = 21

        /** Escaped Top DICT `FDArray` operator. */
        const val FD_ARRAY_OP: Int = 36

        /** Escaped Top DICT `FDSelect` operator. */
        const val FD_SELECT_OP: Int = 37

        private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF ")

        /**
         * Reads the CFF1 table beginning at [offset].
         *
         * @return the structural view, or a typed failure for a truncated header,
         * an unsupported major version, an unsupported charstring type, a missing
         * CharStrings entry, or an out-of-range referenced offset.
         */
        fun read(bytes: ByteArray, offset: Int = 0): FontOperationResult<CffTable> {
            if (offset < 0 || offset + 4 > bytes.size) return failure("CFF header is truncated.")
            val major = bytes[offset].toInt() and 0xFF
            if (major != 1) {
                return FontOperationResult.Failure(
                    FontError.FontDataFailure(
                        code = "font.cff.unsupported-major-version",
                        message = "Only CFF major version 1 is supported by the CFF1 reader.",
                        location = location,
                    ),
                )
            }
            val headerSize = bytes[offset + 2].toInt() and 0xFF
            if (headerSize < 4 || offset + headerSize > bytes.size) return failure("CFF header size is invalid.")

            val nameIndex = when (val result = CffIndex.read(bytes, offset + headerSize)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val topDictIndex = when (val result = CffIndex.read(bytes, nameIndex.endOffset)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            if (topDictIndex.itemCount == 0) return failure("CFF Top DICT INDEX is empty.")
            val topDictBytes = topDictIndex.item(0)
            val topDict = when (val result = CffDict.read(topDictBytes, 0, topDictBytes.size)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val stringIndex = when (val result = CffIndex.read(bytes, topDictIndex.endOffset)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val globalSubrIndex = when (val result = CffIndex.read(bytes, stringIndex.endOffset)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }

            val charStringType = topDict.integer(CffDict.escaped(CHAR_STRING_TYPE_OP)) ?: 2
            if (charStringType != 2) {
                return FontOperationResult.Failure(
                    FontError.FontDataFailure(
                        code = "font.cff.unsupported-charstring-type",
                        message = "CFF charstring type $charStringType is not supported; only Type 2 is implemented.",
                        location = location,
                    ),
                )
            }

            val charStringsOffset = topDict.integer(CHAR_STRINGS_OP)
                ?: return FontOperationResult.Failure(
                    FontError.FontDataFailure(
                        code = "font.cff.missing-charstrings",
                        message = "CFF Top DICT does not declare a CharStrings offset.",
                        location = location,
                    ),
                )
            if (charStringsOffset < 0 || charStringsOffset >= bytes.size) {
                return failure("CFF CharStrings offset is outside the source.")
            }
            val charStringsIndex = when (val result = CffIndex.read(bytes, charStringsOffset)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }

            val charsetOffset = topDict.integer(CHARSET_OP)
            val charset = when (
                val result = CffCharsetReader.read(
                    bytes,
                    charsetOffset ?: CffCharsetReader.ISO_ADOBE,
                    charStringsIndex.itemCount,
                )
            ) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }

            val privateDict = readPrivate(topDict)
            val parsedPrivate = when (val result = readPrivateDict(bytes, privateDict)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val localSubrs = when (val result = readLocalSubrs(bytes, privateDict, parsedPrivate)) {
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

            return FontOperationResult.Success(
                CffTable(
                    nameIndex = nameIndex,
                    topDict = topDict,
                    stringIndex = stringIndex,
                    globalSubrIndex = globalSubrIndex,
                    charStringsOffset = charStringsOffset,
                    charStringsIndex = charStringsIndex,
                    glyphCount = charStringsIndex.itemCount,
                    charset = charset,
                    charsetOffset = charsetOffset,
                    privateDict = privateDict,
                    privateDictData = parsedPrivate,
                    localSubrs = localSubrs,
                    nominalWidthX = parsedPrivate?.integer(NOMINAL_WIDTH_X_OP) ?: 0,
                    defaultWidthX = parsedPrivate?.integer(DEFAULT_WIDTH_X_OP) ?: 0,
                    fontDicts = fontDicts,
                    fdSelect = fdSelect,
                    charStringType = charStringType,
                ),
            )
        }

        private fun readPrivateDict(
            bytes: ByteArray,
            privateDict: PrivateDictRef?,
        ): FontOperationResult<CffDict?> {
            if (privateDict == null) return FontOperationResult.Success(null)
            val end = privateDict.offset.toLong() + privateDict.size.toLong()
            if (privateDict.offset < 0 || privateDict.size < 0 || end > bytes.size) {
                return failure("CFF Private DICT is outside the source.")
            }
            return when (
                val result = CffDict.read(bytes, privateDict.offset, privateDict.offset + privateDict.size)
            ) {
                is FontOperationResult.Success -> FontOperationResult.Success(result.value)
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        }

        private fun readLocalSubrs(
            bytes: ByteArray,
            privateDict: PrivateDictRef?,
            parsedPrivate: CffDict?,
        ): FontOperationResult<CffIndex?> {
            if (privateDict == null || parsedPrivate == null) return FontOperationResult.Success(null)
            val relative = parsedPrivate.integer(SUBRS_OP) ?: return FontOperationResult.Success(null)
            val absolute = privateDict.offset + relative
            if (relative < 0 || absolute < 0 || absolute >= bytes.size) {
                return failure("CFF local Subrs offset is outside the source.")
            }
            return when (val result = CffIndex.read(bytes, absolute)) {
                is FontOperationResult.Success -> FontOperationResult.Success(result.value)
                is FontOperationResult.Failure -> result
                is FontOperationResult.Cancelled -> result
            }
        }

        private fun readFontDicts(bytes: ByteArray, topDict: CffDict): FontOperationResult<List<CffFontDict>?> {
            val offset = topDict.integer(CffDict.escaped(FD_ARRAY_OP)) ?: return FontOperationResult.Success(null)
            if (offset < 0 || offset >= bytes.size) return failure("CFF FDArray offset is outside the source.")
            val index = when (val result = CffIndex.read(bytes, offset)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
            val fontDicts = ArrayList<CffFontDict>(index.itemCount)
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
                fontDicts.add(CffFontDict(ref, data, subrs))
            }
            return FontOperationResult.Success(fontDicts)
        }

        private fun readFdSelect(
            bytes: ByteArray,
            topDict: CffDict,
            glyphCount: Int,
            fontDictCount: Int,
        ): FontOperationResult<IntArray?> {
            val offset = topDict.integer(CffDict.escaped(FD_SELECT_OP)) ?: return FontOperationResult.Success(null)
            if (offset < 0 || offset >= bytes.size) return failure("CFF FDSelect offset is outside the source.")
            val format = bytes[offset].toInt() and 0xFF
            val select = IntArray(glyphCount)
            when (format) {
                0 -> {
                    if (offset + 1 + glyphCount > bytes.size) return failure("CFF FDSelect format 0 is truncated.")
                    for (glyph in 0 until glyphCount) select[glyph] = bytes[offset + 1 + glyph].toInt() and 0xFF
                }

                3 -> {
                    var position = offset + 1
                    if (position + 2 > bytes.size) return failure("CFF FDSelect format 3 is truncated.")
                    val rangeCount = readUInt16At(bytes, position)
                    position += 2
                    if (rangeCount == 0) return failure("CFF FDSelect format 3 declares no ranges.")
                    if (position + rangeCount * 3 + 2 > bytes.size) return failure("CFF FDSelect ranges are truncated.")
                    val firsts = IntArray(rangeCount)
                    val assignments = IntArray(rangeCount)
                    for (range in 0 until rangeCount) {
                        firsts[range] = readUInt16At(bytes, position); position += 2
                        assignments[range] = bytes[position].toInt() and 0xFF; position += 1
                    }
                    val sentinel = readUInt16At(bytes, position)
                    for (glyph in 0 until glyphCount) {
                        if (glyph < firsts[0]) return failure("CFF FDSelect does not cover glyph $glyph.")
                        var range = 0
                        while (range + 1 < rangeCount && glyph >= firsts[range + 1]) range++
                        val end = if (range + 1 < rangeCount) firsts[range + 1] else sentinel
                        if (glyph >= end) return failure("CFF FDSelect does not cover glyph $glyph.")
                        select[glyph] = assignments[range]
                    }
                }

                else -> return failure("CFF FDSelect format $format is not defined.")
            }
            if (fontDictCount > 0) {
                for (value in select) {
                    if (value !in 0 until fontDictCount) return failure("CFF FDSelect references an undefined Font DICT.")
                }
            }
            return FontOperationResult.Success(select)
        }

        private fun readUInt16At(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

        private fun readPrivate(topDict: CffDict): PrivateDictRef? {
            val size = topDict.integer(PRIVATE_OP, 0) ?: return null
            val offset = topDict.integer(PRIVATE_OP, 1) ?: return null
            return PrivateDictRef(size = size, offset = offset)
        }

        private fun failure(message: String): FontOperationResult.Failure =
            FontOperationResult.Failure(FontError.InvalidFontData(message, location))
    }
}
