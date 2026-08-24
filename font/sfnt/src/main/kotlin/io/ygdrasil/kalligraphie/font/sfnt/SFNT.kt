package io.ygdrasil.kalligraphie.font.sfnt

import io.ygdrasil.kalligraphie.font.FontSource
import io.ygdrasil.kalligraphie.font.FontSourceDiagnostic
import io.ygdrasil.kalligraphie.font.FontSourceID
import io.ygdrasil.kalligraphie.font.FontSourceKind
import io.ygdrasil.kalligraphie.font.FontSlant
import io.ygdrasil.kalligraphie.font.FontStyle
import io.ygdrasil.kalligraphie.font.TypefaceData
import io.ygdrasil.kalligraphie.font.TypefaceID
import java.security.MessageDigest
import kotlin.uuid.Uuid

/**
 * Four-byte SFNT table tag represented as text.
 *
 * @property value Tag text such as `cmap`, `name`, `glyf`, or `CFF `.
 */
@JvmInline
value class SFNTTableTag(
    val value: String,
)

/**
 * Top-level SFNT table directory read from an OpenType or TrueType container.
 *
 * @property scalerType Raw scaler type from the SFNT header.
 * @property tables Ordered table records advertised by the font file.
 */
data class SFNTTableDirectory(
    val scalerType: UInt,
    val tables: List<SFNTTableRecord> = emptyList(),
)

/**
 * Directory entry describing one SFNT table payload.
 *
 * @property tag Four-byte table tag.
 * @property checksum Raw checksum stored in the directory.
 * @property offset Byte offset from the start of the font data.
 * @property length Byte length of the table payload.
 */
data class SFNTTableRecord(
    val tag: SFNTTableTag,
    val checksum: UInt,
    val offset: UInt,
    val length: UInt,
)

/**
 * Stable diagnostic for bounded SFNT table-directory validation.
 *
 * @property code Stable diagnostic code from the pure Kotlin text diagnostic taxonomy.
 * @property tag Table tag associated with the diagnostic, when one is known.
 * @property offset Advertised table offset, when a record is involved.
 * @property length Advertised table length, when a record is involved.
 * @property sourceLength Total bounded byte-source length supplied to validation.
 * @property message Deterministic human-readable detail.
 */
data class SFNTTableDirectoryDiagnostic(
    val code: String,
    val tag: SFNTTableTag?,
    val offset: Long?,
    val length: Long?,
    val sourceLength: Long,
    val message: String,
) {
    init {
        require(code.isStableSFNTDiagnosticToken()) {
            "SFNT table directory diagnostic code must be a stable one-line diagnostic token."
        }
        require(tag == null || tag.value.isStableSFNTTableTag()) {
            "SFNT table directory diagnostic tag must be a four-character printable ASCII SFNT tag."
        }
        require(offset == null || offset >= 0L) { "SFNT table directory diagnostic offset must be non-negative." }
        require(length == null || length >= 0L) { "SFNT table directory diagnostic length must be non-negative." }
        require(sourceLength >= 0L) { "SFNT table directory diagnostic sourceLength must be non-negative." }
    }

    /**
     * Serializes the diagnostic as one stable line for table-directory evidence.
     *
     * @return Dumpable diagnostic evidence without parser exceptions or object identity.
     */
    fun dump(): String = buildString {
        append(code)
        append(" tag=")
        append(tag?.value?.sfntEvidenceQuoted() ?: "none")
        append(" offset=")
        append(this@SFNTTableDirectoryDiagnostic.offset ?: "none")
        append(" length=")
        append(this@SFNTTableDirectoryDiagnostic.length ?: "none")
        append(" sourceLength=")
        append(sourceLength)
        append(" message=")
        append(message.sfntEvidenceQuoted())
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("code", code, indent = "  ", comma = true)
        appendSFNTJsonNullableField("tag", tag?.value, indent = "  ", comma = true)
        appendSFNTJsonNullableField("offset", offset, indent = "  ", comma = true)
        appendSFNTJsonNullableField("length", this@SFNTTableDirectoryDiagnostic.length, indent = "  ", comma = true)
        appendSFNTJsonField("sourceLength", sourceLength, indent = "  ", comma = true)
        appendSFNTJsonField("message", message, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Deterministic validator for already-read SFNT table-directory records.
 *
 * This validator does not parse table payloads and does not repair malformed
 * offsets. It produces bounded diagnostics that callers can include in
 * directory evidence before deciding whether a face is safe to parse.
 */
object SFNTTableDirectoryValidator {
    /**
     * Validates table directory records against a bounded source length.
     *
     * @param directory Directory records to inspect.
     * @param sourceLength Total byte length of the bounded source.
     * @param requiredTables Required table tags for the caller's outline contract.
     * @return Deterministically sorted diagnostics for missing, duplicate,
     * overlapping, zero-length, and out-of-bounds table records.
     */
    fun validate(
        directory: SFNTTableDirectory,
        sourceLength: Long,
        requiredTables: Set<SFNTTableTag>,
    ): List<SFNTTableDirectoryDiagnostic> {
        require(sourceLength >= 0L) { "sourceLength must be non-negative." }

        val diagnostics = mutableListOf<SFNTTableDirectoryDiagnostic>()
        val recordsByTag = directory.tables.groupBy { it.tag }

        for (required in requiredTables.sortedBy { it.value }) {
            val records = recordsByTag[required].orEmpty()
            if (records.isEmpty()) {
                diagnostics += diagnostic(
                    code = "font.sfnt.required-table-missing",
                    tag = required,
                    sourceLength = sourceLength,
                    message = "Required table is not present.",
                )
            } else if (records.any { it.length == 0u }) {
                val record = records.first { it.length == 0u }
                diagnostics += diagnostic(
                    code = "font.sfnt.required-table-missing",
                    record = record,
                    sourceLength = sourceLength,
                    message = "Required table is present with zero length.",
                )
            }
        }

        for ((tag, records) in recordsByTag.entries.sortedBy { (tag, _) -> tag.value }) {
            if (records.size > 1) {
                for (record in records.drop(1).sortedWith(SFNT_RECORD_ORDER)) {
                    diagnostics += diagnostic(
                        code = "font.sfnt.table-duplicate",
                        record = record,
                        sourceLength = sourceLength,
                        message = "Duplicate SFNT table tag.",
                    )
                }
            }
        }

        for (record in directory.tables.sortedWith(SFNT_RECORD_ORDER)) {
            val offset = record.offset.toLong()
            val length = record.length.toLong()
            val end = offset + length
            if (offset > sourceLength || end > sourceLength) {
                diagnostics += diagnostic(
                    code = "font.sfnt.table-out-of-bounds",
                    record = record,
                    sourceLength = sourceLength,
                    message = "Table range exceeds source length.",
                )
            }
        }

        var previousEnd = 0L
        var previousRecord: SFNTTableRecord? = null
        for (record in directory.tables.sortedWith(SFNT_RECORD_ORDER)) {
            val offset = record.offset.toLong()
            val end = offset + record.length.toLong()
            if (previousRecord != null && record.length > 0u && offset < previousEnd) {
                diagnostics += diagnostic(
                    code = "font.sfnt.table-overlap",
                    record = record,
                    sourceLength = sourceLength,
                    message = "Table range overlaps previous table range ending at $previousEnd.",
                )
            }
            if (end > previousEnd) {
                previousEnd = end
                previousRecord = record
            }
        }

        return diagnostics.sortedWith(SFNT_TABLE_DIRECTORY_DIAGNOSTIC_ORDER)
    }

    private fun diagnostic(
        code: String,
        tag: SFNTTableTag? = null,
        record: SFNTTableRecord? = null,
        sourceLength: Long,
        message: String,
    ): SFNTTableDirectoryDiagnostic =
        SFNTTableDirectoryDiagnostic(
            code = code,
            tag = record?.tag ?: tag,
            offset = record?.offset?.toLong(),
            length = record?.length?.toLong(),
            sourceLength = sourceLength,
            message = message,
        )

    private val SFNT_RECORD_ORDER = compareBy<SFNTTableRecord>(
        { it.offset.toLong() },
        { it.length.toLong() },
        { it.tag.value },
        { it.checksum.toLong() },
    )

    private val SFNT_TABLE_DIRECTORY_DIAGNOSTIC_ORDER = compareBy<SFNTTableDirectoryDiagnostic>(
        { it.code },
        { it.tag?.value.orEmpty() },
        { it.offset ?: -1L },
        { it.length ?: -1L },
        { it.message },
    )
}

/**
 * Low-level reader for SFNT table directories and table byte ranges.
 */
interface SFNTReader {
    /**
     * Reads the SFNT directory from raw font data.
     *
     * @param source Source bytes to inspect.
     * @return Parsed SFNT directory.
     */
    fun readDirectory(source: FontSource): SFNTTableDirectory

    /**
     * Reads the raw bytes for a single SFNT table.
     *
     * @param source Source bytes containing the table.
     * @param record Directory record describing table location and length.
     * @return Raw table payload.
     */
    fun readTable(source: FontSource, record: SFNTTableRecord): ByteArray
}

/**
 * Default bounded SFNT reader for table directories and raw table payloads.
 */
class DefaultSFNTReader : SFNTReader {
    override fun readDirectory(source: FontSource): SFNTTableDirectory {
        val bytes = source.bytes
        require(bytes.size >= SFNT_HEADER_SIZE) {
            "SFNT source must contain at least $SFNT_HEADER_SIZE bytes for the header."
        }

        val scalerType = bytes.readUInt32(0)
        require(scalerType != TTC_SCALER_TYPE) {
            "TTC/ttcf font collections are not implemented; provide a single-face SFNT source."
        }
        require(scalerType.isSupportedSingleFaceScalerType()) {
            "Unsupported SFNT scaler type ${scalerType.toHexScalerType()}; expected 0x00010000, OTTO, true, or typ1."
        }
        val numTables = bytes.readUInt16(4)
        val directoryLength = SFNT_HEADER_SIZE + numTables * SFNT_TABLE_RECORD_SIZE
        require(directoryLength <= bytes.size) {
            "SFNT table directory exceeds source length."
        }

        val tables = List(numTables) { index ->
            val offset = SFNT_HEADER_SIZE + index * SFNT_TABLE_RECORD_SIZE
            SFNTTableRecord(
                tag = SFNTTableTag(bytes.readTag(offset)),
                checksum = bytes.readUInt32(offset + 4),
                offset = bytes.readUInt32(offset + 8),
                length = bytes.readUInt32(offset + 12),
            )
        }
        return SFNTTableDirectory(
            scalerType = scalerType,
            tables = tables,
        )
    }

    override fun readTable(source: FontSource, record: SFNTTableRecord): ByteArray {
        val offset = record.offset.toLong()
        val length = record.length.toLong()
        val end = offset + length
        val sourceLength = source.bytes.size.toLong()
        require(offset <= sourceLength && end <= sourceLength) {
            "SFNT table ${record.tag.value} range [$offset, $end) exceeds source length $sourceLength."
        }
        return source.bytes.copyOfRange(offset.toInt(), end.toInt())
    }

    private fun ByteArray.readUInt16(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or
            (this[offset + 1].toInt() and 0xff)

    private fun ByteArray.readUInt32(offset: Int): UInt =
        (((this[offset].toInt() and 0xff).toUInt()) shl 24) or
            (((this[offset + 1].toInt() and 0xff).toUInt()) shl 16) or
            (((this[offset + 2].toInt() and 0xff).toUInt()) shl 8) or
            (this[offset + 3].toInt() and 0xff).toUInt()

    private fun ByteArray.readTag(offset: Int): String =
        String(this, offset, SFNT_TAG_SIZE, Charsets.ISO_8859_1)

    private companion object {
        private const val SFNT_HEADER_SIZE = 12
        private const val SFNT_TABLE_RECORD_SIZE = 16
        private const val SFNT_TAG_SIZE = 4
        private val TTC_SCALER_TYPE = 0x74746366u
        private val TRUE_TYPE_SCALER_TYPE = 0x00010000u
        private val CFF_SCALER_TYPE = 0x4f54544fu
        private val APPLE_TRUE_TYPE_SCALER_TYPE = 0x74727565u
        private val TYPE1_SCALER_TYPE = 0x74797031u

        private fun UInt.isSupportedSingleFaceScalerType(): Boolean =
            this == TRUE_TYPE_SCALER_TYPE ||
                this == CFF_SCALER_TYPE ||
                this == APPLE_TRUE_TYPE_SCALER_TYPE ||
                this == TYPE1_SCALER_TYPE

        private fun UInt.toHexScalerType(): String =
            "0x${toString(radix = 16).padStart(8, '0')}"
    }
}

/**
 * Parser that converts OpenType or TrueType source bytes into typed table containers.
 */
interface OpenTypeFaceParser {
    /**
     * Parses one face from a font source.
     *
     * @param source Raw font source to parse.
     * @param faceIndex Zero-based face index for TrueType/OpenType collections.
     * @return Parsed OpenType face data.
     */
    fun parse(source: FontSource, faceIndex: Int = 0): OpenTypeFaceData
}

/**
 * Bounded view over caller-provided font bytes for one parse request.
 *
 * The parser only receives [byteOffset, byteOffset + byteLength) from
 * [rawBytes]. This lets callers keep provenance for a larger stream or file
 * while preventing SFNT directory reads from escaping the requested byte range.
 *
 * @property rawBytes Caller-owned byte array containing the requested range.
 * @property byteOffset Start of the bounded font byte range.
 * @property byteLength Length of the bounded font byte range.
 */
data class BoundedFontBytes(
    val rawBytes: ByteArray,
    val byteOffset: Int = 0,
    val byteLength: Int = rawBytes.size - byteOffset,
) {
    init {
        val end = byteOffset.toLong() + byteLength.toLong()
        require(byteOffset >= 0) { "Bounded font byte offset must be non-negative." }
        require(byteLength >= 0) { "Bounded font byte length must be non-negative." }
        require(end <= rawBytes.size.toLong()) {
            "Bounded font byte range [$byteOffset, $end) exceeds source length ${rawBytes.size}."
        }
    }

    /**
     * Copies only the bounded bytes requested by the parse contract.
     */
    fun toByteArray(): ByteArray =
        rawBytes.copyOfRange(byteOffset, byteOffset + byteLength)
}

/**
 * Container type observed by the SFNT parser entry point.
 */
enum class SFNTContainerKind {
    /** A raw single-face SFNT font. */
    SINGLE_FACE,

    /** A TrueType Collection using the `ttcf` wrapper. */
    TTC_COLLECTION,

    /** An OpenType/CFF collection using the same collection wrapper. */
    OTC_COLLECTION,

    /** A wrapper or top-level container the pure Kotlin SFNT parser does not accept. */
    UNKNOWN_WRAPPER,
}

/**
 * One bounded SFNT parser request shared by single-face fonts and collections.
 *
 * @property sourceId Stable source identity supplied by font core.
 * @property sourceKind Source provenance kind used to build a [FontSource].
 * @property displayName Deterministic source label for diagnostics.
 * @property bytes Bounded byte range to parse.
 * @property collectionIndex Requested zero-based face index, or `0` when absent.
 * @property parserGeneration Parser contract generation recorded in evidence.
 * @property requiredTables Required table tags for bounded directory diagnostics.
 */
data class SFNTParseRequest(
    val sourceId: FontSourceID,
    val sourceKind: FontSourceKind,
    val displayName: String,
    val bytes: BoundedFontBytes,
    val collectionIndex: Int? = 0,
    val parserGeneration: Int,
    val requiredTables: Set<SFNTTableTag> = emptySet(),
) {
    init {
        require(displayName.isNotBlank()) { "SFNT parse request displayName must be non-blank." }
        require(parserGeneration >= 0) { "SFNT parse request parserGeneration must be non-negative." }
        require(requiredTables.all { it.value.isStableSFNTTableTag() }) {
            "SFNT parse request requiredTables must contain stable SFNT tags."
        }
    }
}

/**
 * Stable container-level SFNT parser diagnostic.
 *
 * These diagnostics are separate from table-specific face parser facts. Codes
 * use the `font.*` taxonomy so invalid collection indices and unsupported
 * wrappers are observable without exceptions or platform font APIs.
 */
data class SFNTParseDiagnostic(
    val code: String,
    val message: String,
    val sourceId: FontSourceID,
    val parserGeneration: Int,
    val requestedCollectionIndex: Int,
    val containerKind: SFNTContainerKind,
    val faceCount: Int?,
    val causeMessage: String? = null,
) {
    init {
        require(code.startsWith("font.") && code.isStableSFNTDiagnosticToken()) {
            "SFNT parse diagnostic code must be a stable font.* diagnostic token."
        }
        require(parserGeneration >= 0) { "SFNT parse diagnostic parserGeneration must be non-negative." }
        require(faceCount == null || faceCount >= 0) { "SFNT parse diagnostic faceCount must be non-negative." }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("code", code, indent = "  ", comma = true)
        appendSFNTJsonField("message", message, indent = "  ", comma = true)
        appendSFNTJsonField("sourceId", sourceId.value.toString(), indent = "  ", comma = true)
        appendSFNTJsonField("parserGeneration", parserGeneration, indent = "  ", comma = true)
        appendSFNTJsonField("requestedCollectionIndex", requestedCollectionIndex, indent = "  ", comma = true)
        appendSFNTJsonField("containerKind", containerKind.name, indent = "  ", comma = true)
        appendSFNTJsonNullableField("faceCount", faceCount, indent = "  ", comma = true)
        appendSFNTJsonNullableField("causeMessage", causeMessage, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Directory-level facts for the selected face of an SFNT parse request.
 */
data class SFNTDirectoryFacts(
    val scalerType: String,
    val scalerTypeLabel: String,
    val tableRecords: List<SFNTTableEvidence>,
    val directoryDiagnostics: List<SFNTTableDirectoryDiagnostic> = emptyList(),
) {
    init {
        require(scalerType.matches(SFNT_HEX_UINT32_PATTERN)) {
            "SFNT directory facts scalerType must be lowercase hexadecimal uint32 text."
        }
        require(tableRecords == tableRecords.sortedWith(SFNT_TABLE_EVIDENCE_ORDER)) {
            "SFNT directory facts tableRecords must be sorted."
        }
        require(directoryDiagnostics == directoryDiagnostics.sortedWith(SFNT_TABLE_DIRECTORY_DIAGNOSTIC_EVIDENCE_ORDER)) {
            "SFNT directory facts diagnostics must be sorted."
        }
    }
}

/**
 * Container-level parse result shared by single-face SFNT, TTC, and OTC inputs.
 */
data class SFNTParseResult(
    val sourceId: FontSourceID,
    val sourceKind: FontSourceKind,
    val displayName: String,
    val parserGeneration: Int,
    val sourceByteOffset: Int,
    val sourceByteLength: Int,
    val containerKind: SFNTContainerKind,
    val requestedCollectionIndex: Int,
    val selectedFaceIndex: Int?,
    val faceCount: Int?,
    val directoryFacts: SFNTDirectoryFacts?,
    val faceFacts: OpenTypeFaceEvidence?,
    val tableSlices: List<SFNTTableEvidence>,
    val diagnostics: List<SFNTParseDiagnostic>,
    val dashboardClassification: String = "tracked-gap",
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(parserGeneration >= 0) { "SFNT parse result parserGeneration must be non-negative." }
        require(sourceByteOffset >= 0) { "SFNT parse result sourceByteOffset must be non-negative." }
        require(sourceByteLength >= 0) { "SFNT parse result sourceByteLength must be non-negative." }
        require(selectedFaceIndex == null || selectedFaceIndex >= 0) {
            "SFNT parse result selectedFaceIndex must be non-negative when present."
        }
        require(faceCount == null || faceCount >= 0) { "SFNT parse result faceCount must be non-negative." }
        require(tableSlices == tableSlices.sortedWith(SFNT_TABLE_EVIDENCE_ORDER)) {
            "SFNT parse result tableSlices must be sorted."
        }
        require(dashboardClassification == "tracked-gap") {
            "SFNT parser entry-point result must remain tracked-gap."
        }
        require(!claimPromotionAllowed) {
            "SFNT parser entry-point result cannot promote support claims."
        }
    }
}

/**
 * Public SFNT parser entry point for bounded single-face and collection requests.
 */
interface SFNTParser {
    /**
     * Parses one bounded request into container-level directory evidence.
     */
    fun parse(request: SFNTParseRequest): SFNTParseResult
}

/**
 * Default pure Kotlin SFNT parser entry point.
 *
 * This class normalizes single-face SFNT and collection requests, then reads
 * only the selected face directory and bounded table slices. Invalid collection
 * indices and unsupported wrappers return stable diagnostics instead of
 * falling through to face `0` or invoking external font engines.
 */
class DefaultSFNTParser(
    private val reader: SFNTReader = DefaultSFNTReader(),
) : SFNTParser {
    override fun parse(request: SFNTParseRequest): SFNTParseResult {
        val boundedBytes = request.bytes.toByteArray()
        val requestedIndex = request.collectionIndex ?: 0
        val detectedKind = boundedBytes.detectSFNTContainerKind()
        val faceCount = boundedBytes.collectionFaceCountOrNull()
            ?: if (detectedKind == SFNTContainerKind.SINGLE_FACE) 1 else null

        if (requestedIndex < 0) {
            return request.diagnosticResult(
                boundedBytes = boundedBytes,
                containerKind = detectedKind,
                requestedIndex = requestedIndex,
                faceCount = faceCount,
                diagnostic = request.collectionIndexDiagnostic(
                    containerKind = detectedKind,
                    requestedIndex = requestedIndex,
                    faceCount = faceCount,
                ),
            )
        }

        if (detectedKind == SFNTContainerKind.UNKNOWN_WRAPPER) {
            return request.diagnosticResult(
                boundedBytes = boundedBytes,
                containerKind = detectedKind,
                requestedIndex = requestedIndex,
                faceCount = faceCount,
                diagnostic = request.unsupportedWrapperDiagnostic(
                    boundedBytes = boundedBytes,
                    requestedIndex = requestedIndex,
                ),
            )
        }

        if (detectedKind == SFNTContainerKind.SINGLE_FACE && requestedIndex != 0) {
            return request.diagnosticResult(
                boundedBytes = boundedBytes,
                containerKind = detectedKind,
                requestedIndex = requestedIndex,
                faceCount = 1,
                diagnostic = request.collectionIndexDiagnostic(
                    containerKind = detectedKind,
                    requestedIndex = requestedIndex,
                    faceCount = 1,
                ),
            )
        }

        if (detectedKind == SFNTContainerKind.TTC_COLLECTION && faceCount != null && requestedIndex >= faceCount) {
            return request.diagnosticResult(
                boundedBytes = boundedBytes,
                containerKind = detectedKind,
                requestedIndex = requestedIndex,
                faceCount = faceCount,
                diagnostic = request.collectionIndexDiagnostic(
                    containerKind = detectedKind,
                    requestedIndex = requestedIndex,
                    faceCount = faceCount,
                ),
            )
        }

        val source = FontSource(
            id = request.sourceId,
            kind = request.sourceKind,
            displayName = request.displayName,
            bytes = boundedBytes,
        )

        return runCatching {
            selectDirectoryOnlyFaceInput(
                source = source,
                boundedBytes = boundedBytes,
                containerKind = detectedKind,
                faceIndex = requestedIndex,
            )
        }
            .fold(
                onSuccess = { selectedFace ->
                    val tableSlices = selectedFace.directory.tables
                        .map { record ->
                            val rawBytes = runCatching { selectedFace.readTable(record) }.getOrNull()
                            SFNTTableEvidence(
                                tag = record.tag.value,
                                checksum = record.checksum.toSFNTUInt32Hex(),
                                offset = record.offset.toLong(),
                                length = record.length.toLong(),
                                rawByteLength = rawBytes?.size,
                                rawSha256 = rawBytes?.sfntSha256Hex(),
                            )
                        }
                        .sortedWith(SFNT_TABLE_EVIDENCE_ORDER)
                    val directoryDiagnostics = SFNTTableDirectoryValidator.validate(
                        directory = selectedFace.directory,
                        sourceLength = boundedBytes.size.toLong(),
                        requiredTables = request.requiredTables,
                    )
                    val containerKind = detectedKind.refineCollectionKind(selectedFace.directory)
                    val directoryFacts = SFNTDirectoryFacts(
                        scalerType = selectedFace.directory.scalerType.toSFNTUInt32Hex(),
                        scalerTypeLabel = selectedFace.directory.scalerType.toSFNTScalerTypeLabel(),
                        tableRecords = tableSlices,
                        directoryDiagnostics = directoryDiagnostics,
                    )
                    SFNTParseResult(
                        sourceId = request.sourceId,
                        sourceKind = request.sourceKind,
                        displayName = request.displayName,
                        parserGeneration = request.parserGeneration,
                        sourceByteOffset = request.bytes.byteOffset,
                        sourceByteLength = boundedBytes.size,
                        containerKind = containerKind,
                        requestedCollectionIndex = requestedIndex,
                        selectedFaceIndex = requestedIndex,
                        faceCount = faceCount,
                        directoryFacts = directoryFacts,
                        faceFacts = null,
                        tableSlices = tableSlices,
                        diagnostics = emptyList(),
                    )
                },
                onFailure = { error ->
                    val diagnostic = request.parseFailureDiagnostic(
                        containerKind = detectedKind,
                        requestedIndex = requestedIndex,
                        faceCount = faceCount,
                        error = error,
                    )
                    request.diagnosticResult(
                        boundedBytes = boundedBytes,
                        containerKind = detectedKind,
                        requestedIndex = requestedIndex,
                        faceCount = faceCount,
                        diagnostic = diagnostic,
                    )
                },
            )
    }

    private fun selectDirectoryOnlyFaceInput(
        source: FontSource,
        boundedBytes: ByteArray,
        containerKind: SFNTContainerKind,
        faceIndex: Int,
    ): SelectedSFNTFaceInput =
        when (containerKind) {
            SFNTContainerKind.SINGLE_FACE -> SelectedSFNTFaceInput(
                directory = reader.readDirectory(source),
                readTable = { record -> reader.readTable(source, record) },
            )

            SFNTContainerKind.TTC_COLLECTION,
            SFNTContainerKind.OTC_COLLECTION,
            -> boundedBytes.selectTtcDirectoryOnlyFaceInput(faceIndex)

            SFNTContainerKind.UNKNOWN_WRAPPER -> error("Unsupported wrapper cannot select an SFNT face.")
        }
}

/**
 * One entry in the deterministic `sfnt-directory.json` report.
 */
data class SFNTDirectoryReportEntry(
    val entryId: String,
    val fixtureId: String,
    val fixtureKind: String,
    val sourceId: FontSourceID,
    val sourceKind: FontSourceKind,
    val displayName: String,
    val parserGeneration: Int,
    val sourceByteOffset: Int,
    val sourceByteLength: Int,
    val sourceSha256: String? = null,
    val containerKind: SFNTContainerKind,
    val requestedCollectionIndex: Int,
    val selectedFaceIndex: Int?,
    val faceCount: Int?,
    val tableRecords: List<SFNTTableEvidence>,
    val directoryDiagnostics: List<SFNTTableDirectoryDiagnostic>,
    val faceDiagnostics: List<OpenTypeParseDiagnosticEvidence> = emptyList(),
    val diagnostics: List<SFNTParseDiagnostic>,
    val dashboardClassification: String,
    val claimPromotionAllowed: Boolean,
) {
    init {
        require(entryId.isNotBlank()) { "SFNT directory report entryId must be non-blank." }
        require(fixtureId.isNotBlank()) { "SFNT directory report fixtureId must be non-blank." }
        require(fixtureKind.isNotBlank()) { "SFNT directory report fixtureKind must be non-blank." }
        require(sourceSha256 == null || sourceSha256.matches(SFNT_SHA256_PATTERN)) {
            "SFNT directory report sourceSha256 must be lowercase SHA-256 when present."
        }
        require(tableRecords == tableRecords.sortedWith(SFNT_TABLE_EVIDENCE_ORDER)) {
            "SFNT directory report tableRecords must be sorted."
        }
        require(directoryDiagnostics == directoryDiagnostics.sortedWith(SFNT_TABLE_DIRECTORY_DIAGNOSTIC_EVIDENCE_ORDER)) {
            "SFNT directory report directoryDiagnostics must be sorted."
        }
        require(faceDiagnostics == faceDiagnostics.sortedWith(SFNT_DIAGNOSTIC_EVIDENCE_ORDER)) {
            "SFNT directory report faceDiagnostics must be sorted."
        }
        require(dashboardClassification == "tracked-gap") {
            "SFNT directory report entries must remain tracked-gap."
        }
        require(!claimPromotionAllowed) {
            "SFNT directory report entries cannot promote support claims."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("entryId", entryId, indent = "  ", comma = true)
        appendSFNTJsonField("fixtureId", fixtureId, indent = "  ", comma = true)
        appendSFNTJsonField("fixtureKind", fixtureKind, indent = "  ", comma = true)
        appendSFNTJsonField("sourceId", sourceId.value.toString(), indent = "  ", comma = true)
        appendSFNTJsonField("sourceKind", sourceKind.name, indent = "  ", comma = true)
        appendSFNTJsonField("displayName", displayName, indent = "  ", comma = true)
        appendSFNTJsonField("parserGeneration", parserGeneration, indent = "  ", comma = true)
        appendSFNTJsonField("sourceByteOffset", sourceByteOffset, indent = "  ", comma = true)
        appendSFNTJsonField("sourceByteLength", sourceByteLength, indent = "  ", comma = true)
        appendSFNTJsonNullableField("sourceSha256", sourceSha256, indent = "  ", comma = true)
        appendSFNTJsonField("containerKind", containerKind.name, indent = "  ", comma = true)
        appendSFNTJsonField("requestedCollectionIndex", requestedCollectionIndex, indent = "  ", comma = true)
        appendSFNTJsonNullableField("selectedFaceIndex", selectedFaceIndex, indent = "  ", comma = true)
        appendSFNTJsonNullableField("faceCount", faceCount, indent = "  ", comma = true)
        appendSFNTJsonField("dashboardClassification", dashboardClassification, indent = "  ", comma = true)
        appendSFNTJsonField("claimPromotionAllowed", claimPromotionAllowed, indent = "  ", comma = true)
        append("  \"tableRecords\": [")
        if (tableRecords.isNotEmpty()) {
            append("\n")
            append(tableRecords.joinToString(",\n") { record -> record.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("],\n")
        append("  \"directoryDiagnostics\": [")
        if (directoryDiagnostics.isNotEmpty()) {
            append("\n")
            append(directoryDiagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("],\n")
        append("  \"faceDiagnostics\": [")
        if (faceDiagnostics.isNotEmpty()) {
            append("\n")
            append(faceDiagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("],\n")
        append("  \"diagnostics\": [")
        if (diagnostics.isNotEmpty()) {
            append("\n")
            append(diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("]\n")
        append("}")
    }

    companion object {
        /**
         * Builds a report entry from a container-level parse result.
         */
        fun fromResult(
            entryId: String,
            fixtureId: String,
            fixtureKind: String,
            result: SFNTParseResult,
            sourceSha256: String? = null,
        ): SFNTDirectoryReportEntry =
            SFNTDirectoryReportEntry(
                entryId = entryId,
                fixtureId = fixtureId,
                fixtureKind = fixtureKind,
                sourceId = result.sourceId,
                sourceKind = result.sourceKind,
                displayName = result.displayName,
                parserGeneration = result.parserGeneration,
                sourceByteOffset = result.sourceByteOffset,
                sourceByteLength = result.sourceByteLength,
                sourceSha256 = sourceSha256,
                containerKind = result.containerKind,
                requestedCollectionIndex = result.requestedCollectionIndex,
                selectedFaceIndex = result.selectedFaceIndex,
                faceCount = result.faceCount,
                tableRecords = result.tableSlices,
                directoryDiagnostics = result.directoryFacts?.directoryDiagnostics.orEmpty(),
                faceDiagnostics = emptyList(),
                diagnostics = result.diagnostics,
                dashboardClassification = result.dashboardClassification,
                claimPromotionAllowed = result.claimPromotionAllowed,
            )

        /**
         * Builds a report entry from already parsed face data without changing
         * the directory-only [DefaultSFNTParser] route.
         */
        fun fromFaceData(
            entryId: String,
            fixtureId: String,
            fixtureKind: String,
            face: OpenTypeFaceData,
        ): SFNTDirectoryReportEntry {
            val evidence = face.faceEvidence()
            return SFNTDirectoryReportEntry(
                entryId = entryId,
                fixtureId = fixtureId,
                fixtureKind = fixtureKind,
                sourceId = face.source.id,
                sourceKind = face.source.kind,
                displayName = face.source.displayName,
                parserGeneration = 1,
                sourceByteOffset = 0,
                sourceByteLength = face.source.bytes.size,
                sourceSha256 = face.source.bytes.sfntSha256Hex(),
                containerKind = SFNTContainerKind.SINGLE_FACE,
                requestedCollectionIndex = face.faceIndex,
                selectedFaceIndex = face.faceIndex,
                faceCount = 1,
                tableRecords = evidence.tableRecords,
                directoryDiagnostics = evidence.directoryDiagnostics,
                faceDiagnostics = evidence.diagnostics,
                diagnostics = emptyList(),
                dashboardClassification = "tracked-gap",
                claimPromotionAllowed = false,
            )
        }
    }
}

/**
 * Deterministic directory report for M2 SFNT parser and diagnostic evidence.
 */
data class SFNTDirectoryReport(
    val entries: List<SFNTDirectoryReportEntry>,
    val schemaVersion: Int = 1,
    val ticketIds: List<String> = listOf("KFONT-M2-001", "KFONT-M2-002", "KFONT-M2-005"),
    val dashboardClassification: String = "tracked-gap",
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(schemaVersion == 1) { "SFNT directory report schemaVersion must be 1." }
        require(ticketIds == listOf("KFONT-M2-001", "KFONT-M2-002", "KFONT-M2-005")) {
            "SFNT directory report ticketIds must be KFONT-M2-001, KFONT-M2-002, and KFONT-M2-005."
        }
        require(dashboardClassification == "tracked-gap") {
            "SFNT directory report must remain tracked-gap."
        }
        require(!claimPromotionAllowed) {
            "SFNT directory report cannot promote support claims."
        }
    }
}

/**
 * Canonical writer for `reports/pure-kotlin-text/sfnt-directory.json`.
 */
object SFNTDirectoryReportWriter {
    fun write(report: SFNTDirectoryReport): String = buildString {
        append("{\n")
        appendSFNTJsonField("schema", "org.graphiks.kanvas.font.sfnt.SFNTDirectoryReport.v1", indent = "  ", comma = true)
        appendSFNTJsonField("schemaVersion", report.schemaVersion, indent = "  ", comma = true)
        appendStringArrayField("ticketIds", report.ticketIds, indent = "  ", comma = true)
        appendSFNTJsonField("dashboardClassification", report.dashboardClassification, indent = "  ", comma = true)
        appendSFNTJsonField("claimPromotionAllowed", report.claimPromotionAllowed, indent = "  ", comma = true)
        append("  \"entries\": [")
        val sortedEntries = report.entries.sortedBy { it.entryId }
        if (sortedEntries.isNotEmpty()) {
            append("\n")
            append(sortedEntries.joinToString(",\n") { entry -> entry.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("]\n")
        append("}\n")
    }
}

/**
 * Stable diagnostic snapshot for one malformed SFNT fixture.
 */
data class MalformedSFNTFixtureDiagnosticSnapshot(
    val source: String,
    val code: String,
    val table: String?,
    val message: String,
) {
    init {
        require(source in MALFORMED_SFNT_DIAGNOSTIC_SOURCES) {
            "Malformed SFNT fixture diagnostic source must be canonical."
        }
        require(code.startsWith("font.") && code.isStableSFNTDiagnosticToken()) {
            "Malformed SFNT fixture diagnostic code must be a stable font.* token."
        }
        require(table == null || table.isStableSFNTTableTag()) {
            "Malformed SFNT fixture diagnostic table must be a four-character printable ASCII SFNT tag."
        }
        require(message.isNotBlank()) { "Malformed SFNT fixture diagnostic message must be non-blank." }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("source", source, indent = "  ", comma = true)
        appendSFNTJsonField("code", code, indent = "  ", comma = true)
        appendSFNTJsonNullableField("table", table, indent = "  ", comma = true)
        appendSFNTJsonField("message", message, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * One generated malformed SFNT fixture row in the KFONT-M2-005 suite.
 */
data class MalformedSFNTFixtureSuiteEntry(
    val caseId: String,
    val fixtureId: String,
    val fixtureKind: String,
    val generatorId: String,
    val generatorParameters: List<String>,
    val contentSha256: String,
    val byteLength: Int,
    val expectedDiagnostic: String,
    val expectedOutcome: String,
    val linkedEvidencePath: String,
    val linkedEvidenceEntryId: String,
    val diagnostics: List<MalformedSFNTFixtureDiagnosticSnapshot>,
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(caseId in MALFORMED_SFNT_CASE_IDS) { "Malformed SFNT fixture caseId must be canonical." }
        require(fixtureId.isStableSFNTDiagnosticToken()) { "Malformed SFNT fixture fixtureId must be stable." }
        require(fixtureKind == "GeneratedFixtureFontSource") {
            "Malformed SFNT fixture rows must use generated fixture provenance."
        }
        require(generatorId.isStableSFNTDiagnosticToken()) {
            "Malformed SFNT fixture generatorId must be stable."
        }
        require(generatorParameters.isNotEmpty() && generatorParameters == generatorParameters.sorted()) {
            "Malformed SFNT fixture generatorParameters must be non-empty and sorted."
        }
        require(generatorParameters.all { it.isStableSFNTManifestLine() }) {
            "Malformed SFNT fixture generatorParameters must be one-line stable strings."
        }
        require(contentSha256.matches(SFNT_SHA256_PATTERN)) {
            "Malformed SFNT fixture contentSha256 must be lowercase SHA-256 text."
        }
        require(byteLength > 0) { "Malformed SFNT fixture byteLength must be positive." }
        require(expectedDiagnostic.startsWith("font.") && expectedDiagnostic.isStableSFNTDiagnosticToken()) {
            "Malformed SFNT fixture expectedDiagnostic must be stable."
        }
        require(expectedOutcome in MALFORMED_SFNT_EXPECTED_OUTCOMES) {
            "Malformed SFNT fixture expectedOutcome must be canonical."
        }
        require(linkedEvidencePath in MALFORMED_SFNT_LINKED_EVIDENCE_PATHS) {
            "Malformed SFNT fixture linkedEvidencePath must be a canonical M2 evidence dump."
        }
        require(linkedEvidenceEntryId.isStableSFNTDiagnosticToken()) {
            "Malformed SFNT fixture linkedEvidenceEntryId must be stable."
        }
        require(diagnostics.map { it.code }.contains(expectedDiagnostic)) {
            "Malformed SFNT fixture diagnostics must include the expected primary diagnostic."
        }
        require(diagnostics == diagnostics.sortedWith(MALFORMED_SFNT_DIAGNOSTIC_ORDER)) {
            "Malformed SFNT fixture diagnostics must be sorted."
        }
        require(!claimPromotionAllowed) {
            "Malformed SFNT fixture rows cannot promote support claims."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("caseId", caseId, indent = "  ", comma = true)
        appendSFNTJsonField("fixtureId", fixtureId, indent = "  ", comma = true)
        appendSFNTJsonField("fixtureKind", fixtureKind, indent = "  ", comma = true)
        appendSFNTJsonField("generatorId", generatorId, indent = "  ", comma = true)
        appendStringArrayField("generatorParameters", generatorParameters, indent = "  ", comma = true)
        appendSFNTJsonField("contentSha256", contentSha256, indent = "  ", comma = true)
        appendSFNTJsonField("byteLength", byteLength, indent = "  ", comma = true)
        appendSFNTJsonField("expectedDiagnostic", expectedDiagnostic, indent = "  ", comma = true)
        appendSFNTJsonField("expectedOutcome", expectedOutcome, indent = "  ", comma = true)
        appendSFNTJsonField("linkedEvidencePath", linkedEvidencePath, indent = "  ", comma = true)
        appendSFNTJsonField("linkedEvidenceEntryId", linkedEvidenceEntryId, indent = "  ", comma = true)
        appendSFNTJsonField("claimPromotionAllowed", claimPromotionAllowed, indent = "  ", comma = true)
        append("  \"diagnostics\": [")
        if (diagnostics.isNotEmpty()) {
            append("\n")
            append(diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("]\n")
        append("}")
    }
}

/**
 * Canonical KFONT-M2-005 malformed SFNT fixture-suite evidence.
 */
class MalformedSFNTFixtureSuiteReport(
    entries: List<MalformedSFNTFixtureSuiteEntry>,
    val schemaVersion: Int = 1,
    val ticketIds: List<String> = listOf("KFONT-M2-005"),
    val dashboardClassification: String = "fixture-gated",
    val claimPromotionAllowed: Boolean = false,
    val nonClaims: List<String> = MALFORMED_SFNT_NON_CLAIMS,
) {
    val entries: List<MalformedSFNTFixtureSuiteEntry> = entries.sortedBy { MALFORMED_SFNT_CASE_IDS.indexOf(it.caseId) }

    init {
        require(schemaVersion == 1) { "Malformed SFNT fixture suite schemaVersion must be 1." }
        require(ticketIds == listOf("KFONT-M2-005")) {
            "Malformed SFNT fixture suite ticketIds must contain only KFONT-M2-005."
        }
        require(this.entries.map { it.caseId } == MALFORMED_SFNT_CASE_IDS) {
            "Malformed SFNT fixture suite must cover each required KFONT-M2-005 case once."
        }
        require(this.entries.map { it.fixtureId }.toSet().size == this.entries.size) {
            "Malformed SFNT fixture suite fixture IDs must be unique."
        }
        require(dashboardClassification == "fixture-gated") {
            "Malformed SFNT fixture suite must remain fixture-gated."
        }
        require(!claimPromotionAllowed) {
            "Malformed SFNT fixture suite cannot promote support claims."
        }
        require(nonClaims == MALFORMED_SFNT_NON_CLAIMS) {
            "Malformed SFNT fixture suite nonClaims must stay canonical."
        }
    }
}

/**
 * Canonical writer for `reports/pure-kotlin-text/malformed-sfnt-fixtures.json`.
 */
object MalformedSFNTFixtureSuiteReportWriter {
    fun write(report: MalformedSFNTFixtureSuiteReport): String = buildString {
        append("{\n")
        appendSFNTJsonField("schema", "org.graphiks.kanvas.font.sfnt.MalformedSFNTFixtureSuiteReport.v1", indent = "  ", comma = true)
        appendSFNTJsonField("schemaVersion", report.schemaVersion, indent = "  ", comma = true)
        appendStringArrayField("ticketIds", report.ticketIds, indent = "  ", comma = true)
        appendSFNTJsonField("dashboardClassification", report.dashboardClassification, indent = "  ", comma = true)
        appendSFNTJsonField("claimPromotionAllowed", report.claimPromotionAllowed, indent = "  ", comma = true)
        appendStringArrayField("nonClaims", report.nonClaims, indent = "  ", comma = true)
        append("  \"entries\": [\n")
        append(report.entries.joinToString(",\n") { entry -> entry.toCanonicalJson().prependIndent("    ") })
        append("\n  ]\n")
        append("}\n")
    }
}

/**
 * Deterministic link to the KFONT-M2-003 `cmap-map.json` evidence bundle.
 *
 * The table fact report links only metadata-level `cmap` selection facts. It
 * does not duplicate glyph mapping payloads or promote shaping/scaler support.
 */
data class OpenTypeTableFactCMapLink(
    val reportPath: String,
    val linkedTicketIds: List<String>,
    val linkedEntryIds: List<String>,
    val linkage: String,
) {
    init {
        require(reportPath == "reports/pure-kotlin-text/cmap-map.json") {
            "OpenType table fact cmap link must point at reports/pure-kotlin-text/cmap-map.json."
        }
        require(linkedTicketIds == linkedTicketIds.sorted()) {
            "OpenType table fact cmap linkedTicketIds must be sorted."
        }
        require(linkedEntryIds == linkedEntryIds.sorted()) {
            "OpenType table fact cmap linkedEntryIds must be sorted."
        }
        require(linkage == "metadata-only-cmap-facts") {
            "OpenType table fact cmap link must remain metadata-only."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("reportPath", reportPath, indent = "  ", comma = true)
        appendStringArrayField("linkedTicketIds", linkedTicketIds, indent = "  ", comma = true)
        appendStringArrayField("linkedEntryIds", linkedEntryIds, indent = "  ", comma = true)
        appendSFNTJsonField("linkage", linkage, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Bounded byte range for a present SFNT table.
 */
data class OpenTypeTableByteRange(
    val offset: Long,
    val length: Long,
    val endExclusive: Long,
    val sourceLength: Long,
) {
    init {
        require(offset >= 0L) { "OpenType table byte range offset must be non-negative." }
        require(length >= 0L) { "OpenType table byte range length must be non-negative." }
        require(endExclusive == offset + length) {
            "OpenType table byte range endExclusive must equal offset + length."
        }
        require(sourceLength >= 0L) { "OpenType table byte range sourceLength must be non-negative." }
        require(endExclusive <= sourceLength) {
            "OpenType table byte range must fit within the bounded source length."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("offset", offset, indent = "  ", comma = true)
        appendSFNTJsonField("length", this@OpenTypeTableByteRange.length, indent = "  ", comma = true)
        appendSFNTJsonField("endExclusive", endExclusive, indent = "  ", comma = true)
        appendSFNTJsonField("sourceLength", sourceLength, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Stable table-specific diagnostic embedded in `sfnt-tables.json`.
 */
data class OpenTypeTableFactDiagnostic(
    val source: String,
    val code: String,
    val table: String?,
    val offset: Long?,
    val length: Long?,
    val sourceLength: Long?,
    val causeCode: String?,
    val message: String,
    val causeMessage: String?,
) {
    init {
        require(source == "directory" || source == "face-parser") {
            "OpenType table fact diagnostic source must be directory or face-parser."
        }
        require(code.startsWith("font.") && code.isStableSFNTDiagnosticToken()) {
            "OpenType table fact diagnostic code must be a stable font.* diagnostic token."
        }
        require(table == null || table.isStableSFNTTableTag()) {
            "OpenType table fact diagnostic table must be a four-character printable ASCII SFNT tag."
        }
        require(offset == null || offset >= 0L) { "OpenType table fact diagnostic offset must be non-negative." }
        require(length == null || length >= 0L) { "OpenType table fact diagnostic length must be non-negative." }
        require(sourceLength == null || sourceLength >= 0L) {
            "OpenType table fact diagnostic sourceLength must be non-negative."
        }
        require(causeCode == null || causeCode.isStableSFNTDiagnosticToken()) {
            "OpenType table fact diagnostic causeCode must be stable."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("source", source, indent = "  ", comma = true)
        appendSFNTJsonField("code", code, indent = "  ", comma = true)
        appendSFNTJsonNullableField("table", table, indent = "  ", comma = true)
        appendSFNTJsonNullableField("offset", offset, indent = "  ", comma = true)
        appendSFNTJsonNullableField("length", this@OpenTypeTableFactDiagnostic.length, indent = "  ", comma = true)
        appendSFNTJsonNullableField("sourceLength", sourceLength, indent = "  ", comma = true)
        appendSFNTJsonNullableField("causeCode", causeCode, indent = "  ", comma = true)
        appendSFNTJsonField("message", message, indent = "  ", comma = true)
        appendSFNTJsonNullableField("causeMessage", causeMessage, indent = "  ", comma = false)
        append("}")
    }

    companion object {
        fun fromDirectory(diagnostic: SFNTTableDirectoryDiagnostic): OpenTypeTableFactDiagnostic =
            OpenTypeTableFactDiagnostic(
                source = "directory",
                code = diagnostic.code,
                table = diagnostic.tag?.value,
                offset = diagnostic.offset,
                length = diagnostic.length,
                sourceLength = diagnostic.sourceLength,
                causeCode = null,
                message = diagnostic.message,
                causeMessage = null,
            )

        fun fromFaceParser(diagnostic: OpenTypeParseDiagnosticEvidence): OpenTypeTableFactDiagnostic =
            OpenTypeTableFactDiagnostic(
                source = "face-parser",
                code = diagnostic.causeCode?.takeIf { it.startsWith("font.") } ?: "font.sfnt.table-parse-diagnostic",
                table = diagnostic.table?.value,
                offset = null,
                length = null,
                sourceLength = null,
                causeCode = diagnostic.causeCode,
                message = diagnostic.message,
                causeMessage = diagnostic.causeMessage,
            )
    }
}

/**
 * Canonical fact for one required or high-value OpenType table tag.
 */
data class OpenTypeTableFact(
    val tag: String,
    val role: String,
    val supportClassification: String,
    val present: Boolean,
    val byteRange: OpenTypeTableByteRange?,
    val checksum: String?,
    val rawSha256: String?,
    val parserStatus: String,
    val diagnostics: List<OpenTypeTableFactDiagnostic>,
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(tag.isStableSFNTTableTag()) {
            "OpenType table fact tag must be a four-character printable ASCII SFNT tag."
        }
        require(role in OPEN_TYPE_TABLE_FACT_ROLES) {
            "OpenType table fact role must be one of the canonical table roles."
        }
        require(supportClassification.startsWith("metadata-")) {
            "OpenType table fact supportClassification must remain metadata-only."
        }
        require(present == (byteRange != null)) {
            "OpenType table fact byteRange must be present only when the table is present."
        }
        require((present && checksum != null) || (!present && checksum == null)) {
            "OpenType table fact checksum must be present only when the table is present."
        }
        require(checksum == null || checksum.matches(SFNT_HEX_UINT32_PATTERN)) {
            "OpenType table fact checksum must be lowercase hexadecimal uint32 text."
        }
        require(rawSha256 == null || rawSha256.matches(SFNT_SHA256_PATTERN)) {
            "OpenType table fact rawSha256 must be lowercase SHA-256 text."
        }
        require(parserStatus in OPEN_TYPE_TABLE_FACT_PARSER_STATUSES) {
            "OpenType table fact parserStatus must be canonical."
        }
        require(diagnostics == diagnostics.sortedWith(OPEN_TYPE_TABLE_FACT_DIAGNOSTIC_ORDER)) {
            "OpenType table fact diagnostics must be sorted."
        }
        require(!claimPromotionAllowed) {
            "OpenType table facts cannot promote support claims."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("tag", tag, indent = "  ", comma = true)
        appendSFNTJsonField("role", role, indent = "  ", comma = true)
        appendSFNTJsonField("supportClassification", supportClassification, indent = "  ", comma = true)
        appendSFNTJsonField("present", present, indent = "  ", comma = true)
        append("  \"byteRange\": ")
        append(byteRange?.toCanonicalJson()?.prependIndent("  ")?.trimStart() ?: "null")
        append(",\n")
        appendSFNTJsonNullableField("checksum", checksum, indent = "  ", comma = true)
        appendSFNTJsonNullableField("rawSha256", rawSha256, indent = "  ", comma = true)
        appendSFNTJsonField("parserStatus", parserStatus, indent = "  ", comma = true)
        appendSFNTJsonField("claimPromotionAllowed", claimPromotionAllowed, indent = "  ", comma = true)
        append("  \"diagnostics\": [")
        if (diagnostics.isNotEmpty()) {
            append("\n")
            append(diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("]\n")
        append("}")
    }
}

/**
 * One face row in the canonical KFONT-M2-004 OpenType table fact dump.
 */
data class OpenTypeTableFactReportEntry(
    val entryId: String,
    val fixtureId: String,
    val fixtureKind: String,
    val sourceId: FontSourceID,
    val typefaceId: TypefaceID,
    val fontSourceReportLabel: String?,
    val typefaceReportLabel: String?,
    val sourceKind: FontSourceKind,
    val displayName: String,
    val faceIndex: Int,
    val parserGeneration: Int,
    val sourceByteLength: Int,
    val sourceSha256: String,
    val scalerType: String,
    val scalerTypeLabel: String,
    val tableFacts: List<OpenTypeTableFact>,
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(entryId.isNotBlank()) { "OpenType table fact report entryId must be non-blank." }
        require(fixtureId.isNotBlank()) { "OpenType table fact report fixtureId must be non-blank." }
        require(fixtureKind.isNotBlank()) { "OpenType table fact report fixtureKind must be non-blank." }
        require(faceIndex >= 0) { "OpenType table fact report faceIndex must be non-negative." }
        require(parserGeneration >= 0) { "OpenType table fact report parserGeneration must be non-negative." }
        require(sourceByteLength >= 0) { "OpenType table fact report sourceByteLength must be non-negative." }
        require(sourceSha256.matches(SFNT_SHA256_PATTERN)) {
            "OpenType table fact report sourceSha256 must be lowercase SHA-256 text."
        }
        require(scalerType.matches(SFNT_HEX_UINT32_PATTERN)) {
            "OpenType table fact report scalerType must be lowercase hexadecimal uint32 text."
        }
        require(tableFacts.map { it.tag } == OpenTypeTableFactReport.canonicalTableTags) {
            "OpenType table fact report entries must use canonical table ordering."
        }
        require(!claimPromotionAllowed) {
            "OpenType table fact report entries cannot promote support claims."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("entryId", entryId, indent = "  ", comma = true)
        appendSFNTJsonField("fixtureId", fixtureId, indent = "  ", comma = true)
        appendSFNTJsonField("fixtureKind", fixtureKind, indent = "  ", comma = true)
        appendSFNTJsonField("sourceId", sourceId.value.toString(), indent = "  ", comma = true)
        appendSFNTJsonField("typefaceId", typefaceId.value.toString(), indent = "  ", comma = true)
        appendSFNTJsonNullableField("fontSourceReportLabel", fontSourceReportLabel, indent = "  ", comma = true)
        appendSFNTJsonNullableField("typefaceReportLabel", typefaceReportLabel, indent = "  ", comma = true)
        appendSFNTJsonField("sourceKind", sourceKind.name, indent = "  ", comma = true)
        appendSFNTJsonField("displayName", displayName, indent = "  ", comma = true)
        appendSFNTJsonField("faceIndex", faceIndex, indent = "  ", comma = true)
        appendSFNTJsonField("parserGeneration", parserGeneration, indent = "  ", comma = true)
        appendSFNTJsonField("sourceByteLength", sourceByteLength, indent = "  ", comma = true)
        appendSFNTJsonField("sourceSha256", sourceSha256, indent = "  ", comma = true)
        appendSFNTJsonField("scalerType", scalerType, indent = "  ", comma = true)
        appendSFNTJsonField("scalerTypeLabel", scalerTypeLabel, indent = "  ", comma = true)
        appendSFNTJsonField("claimPromotionAllowed", claimPromotionAllowed, indent = "  ", comma = true)
        append("  \"tableFacts\": [\n")
        append(tableFacts.joinToString(",\n") { fact -> fact.toCanonicalJson().prependIndent("    ") })
        append("\n  ]\n")
        append("}")
    }

    companion object {
        fun fromFaceData(
            entryId: String,
            fixtureId: String,
            fixtureKind: String,
            face: OpenTypeFaceData,
            requiredTables: Set<SFNTTableTag>,
            fontSourceReportLabel: String? = null,
            typefaceReportLabel: String? = null,
            typefaceId: TypefaceID = face.id,
            parserGeneration: Int = 1,
        ): OpenTypeTableFactReportEntry {
            val evidence = face.faceEvidence(requiredTables = requiredTables)
            return OpenTypeTableFactReportEntry(
                entryId = entryId,
                fixtureId = fixtureId,
                fixtureKind = fixtureKind,
                sourceId = face.source.id,
                typefaceId = typefaceId,
                fontSourceReportLabel = fontSourceReportLabel,
                typefaceReportLabel = typefaceReportLabel,
                sourceKind = face.source.kind,
                displayName = face.source.displayName,
                faceIndex = face.faceIndex,
                parserGeneration = parserGeneration,
                sourceByteLength = face.source.bytes.size,
                sourceSha256 = face.source.bytes.sfntSha256Hex(),
                scalerType = evidence.scalerType,
                scalerTypeLabel = evidence.scalerTypeLabel,
                tableFacts = buildOpenTypeTableFacts(
                    evidence = evidence,
                    requiredTables = requiredTables,
                    sourceLength = face.source.bytes.size.toLong(),
                ),
            )
        }
    }
}

/**
 * Canonical `reports/pure-kotlin-text/sfnt-tables.json` report.
 */
data class OpenTypeTableFactReport(
    val entries: List<OpenTypeTableFactReportEntry>,
    val cmapMapLink: OpenTypeTableFactCMapLink,
    val schemaVersion: Int = 1,
    val ticketIds: List<String> = listOf("KFONT-M2-004"),
    val dashboardClassification: String = "tracked-gap",
    val claimPromotionAllowed: Boolean = false,
    val nonClaims: List<String> = OPEN_TYPE_TABLE_FACT_NON_CLAIMS,
) {
    init {
        require(schemaVersion == 1) { "OpenType table fact report schemaVersion must be 1." }
        require(ticketIds == listOf("KFONT-M2-004")) {
            "OpenType table fact report ticketIds must contain only KFONT-M2-004."
        }
        require(entries.map { it.entryId }.toSet().size == entries.size) {
            "OpenType table fact report entries must have unique entryIds."
        }
        require(dashboardClassification == "tracked-gap") {
            "OpenType table fact report must remain tracked-gap."
        }
        require(!claimPromotionAllowed) {
            "OpenType table fact report cannot promote support claims."
        }
        require(nonClaims == OPEN_TYPE_TABLE_FACT_NON_CLAIMS) {
            "OpenType table fact report nonClaims must stay canonical."
        }
    }

    companion object {
        val canonicalTableTags: List<String>
            get() = OPEN_TYPE_TABLE_FACT_SPECS.map { it.tag.value }

        val trueTypeRequiredTableTags: Set<SFNTTableTag>
            get() = TRUE_TYPE_REQUIRED_TABLE_TAGS
    }
}

/**
 * Canonical writer for `reports/pure-kotlin-text/sfnt-tables.json`.
 */
object OpenTypeTableFactReportWriter {
    fun write(report: OpenTypeTableFactReport): String = buildString {
        append("{\n")
        appendSFNTJsonField("schema", "org.graphiks.kanvas.font.sfnt.OpenTypeTableFactReport.v1", indent = "  ", comma = true)
        appendSFNTJsonField("schemaVersion", report.schemaVersion, indent = "  ", comma = true)
        appendStringArrayField("ticketIds", report.ticketIds, indent = "  ", comma = true)
        appendSFNTJsonField("dashboardClassification", report.dashboardClassification, indent = "  ", comma = true)
        appendSFNTJsonField("claimPromotionAllowed", report.claimPromotionAllowed, indent = "  ", comma = true)
        appendStringArrayField("nonClaims", report.nonClaims, indent = "  ", comma = true)
        append("  \"cmapMapLink\": ")
        append(report.cmapMapLink.toCanonicalJson().prependIndent("  ").trimStart())
        append(",\n")
        append("  \"entries\": [")
        if (report.entries.isNotEmpty()) {
            append("\n")
            append(report.entries.sortedBy { it.entryId }.joinToString(",\n") { entry -> entry.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("]\n")
        append("}\n")
    }
}

/**
 * Default parser for OpenType or TrueType SFNT faces.
 *
 * Single-face SFNT sources continue to flow through [SFNTReader]. TrueType or
 * OpenType Collection sources (`ttcf`) are handled by this parser because face
 * selection is a higher-level concern than directory reading: the parser reads
 * the collection header, validates [OpenTypeFaceParser.parse] `faceIndex`,
 * bounds the selected face directory, and reads advertised table payloads from
 * their collection-relative byte ranges. That keeps unrelated collection bytes
 * out of raw table snapshots while preserving the original table bytes and the
 * directory offsets authored by the collection.
 *
 * After a single face has been selected, the parser snapshots raw table payloads
 * for downstream layers and invokes the currently available typed parsers for
 * `name`, `cmap`, and horizontal metric tables. This class does not parse glyph
 * outlines or `loca`; those payloads remain available through
 * [OpenTypeFaceData.rawTables] when present so later scaler layers can decide
 * how to consume them without coupling this parser to outline formats.
 *
 * Non-fatal table gaps and table-specific parse failures are reported through
 * [OpenTypeFaceData.diagnostics]. Structural SFNT or TTC directory failures are
 * left as [IllegalArgumentException] because the face cannot be bounded safely.
 *
 * @property reader Low-level SFNT reader used to read the face directory and
 * table payload byte ranges for non-collection sources.
 */
class DefaultOpenTypeFaceParser(
    private val reader: SFNTReader = DefaultSFNTReader(),
) : OpenTypeFaceParser {
    /**
     * Parses [faceIndex] from [source] into [OpenTypeFaceData].
     *
     * @param source Raw font source containing either a single SFNT face or a
     * TTC/OpenType collection.
     * @param faceIndex Zero-based face index. Single-face SFNT sources support
     * only `0`; TTC/OpenType collection sources accept any index advertised by
     * their collection header.
     * @return Parsed face data with raw table payloads and non-fatal diagnostics.
     * @throws IllegalArgumentException when [faceIndex] is negative, outside a
     * TTC collection range, non-zero for a single-face source, or when the SFNT
     * or TTC directory cannot be read safely.
     */
    override fun parse(source: FontSource, faceIndex: Int): OpenTypeFaceData {
        val faceInput = selectFaceInput(source, faceIndex)
        val directory = faceInput.directory
        val diagnostics = mutableListOf<OpenTypeParseDiagnostic>()
        val rawTableBytes = mutableMapOf<SFNTTableTag, ByteArray>()

        directory.tables.forEach { record ->
            val table = runCatching { faceInput.readTable(record) }
                .onFailure { error ->
                    diagnostics += tableDiagnostic(
                        source = source,
                        table = record.tag,
                        message = "Unable to read SFNT table ${record.tag.value}.",
                        causeCode = "INVALID_TABLE_RANGE",
                        cause = error,
                    )
                }
                .getOrNull()
            if (table != null) {
                rawTableBytes[record.tag] = table
            }
        }

        val names = parseOptionalTable(
            source = source,
            table = NAME_TABLE_TAG,
            rawTableBytes = rawTableBytes,
            diagnostics = diagnostics,
            defaultValue = NameTable(),
            parser = OpenTypeNameTableParser::parse,
        )
        val cmap = parseOptionalTable(
            source = source,
            table = CMAP_TABLE_TAG,
            rawTableBytes = rawTableBytes,
            diagnostics = diagnostics,
            defaultValue = CMapTable(),
            parser = OpenTypeCMapTableParser::parse,
        )
        val metrics = parseMetrics(source, rawTableBytes, diagnostics)
        val style = parseStyle(names, metrics, rawTableBytes)
        val variations = parseVariations(source, rawTableBytes, diagnostics)
        val layout = parseLayout(source, rawTableBytes, metrics, diagnostics)
        val color = parseColor(source, rawTableBytes, metrics, diagnostics)

        return OpenTypeFaceData(
            id = deterministicTypefaceId(source, faceIndex),
            source = source,
            faceIndex = faceIndex,
            directory = directory,
            cmap = cmap,
            names = names,
            metrics = metrics,
            style = style,
            variations = variations,
            layout = layout,
            color = color,
            rawTables = rawTableBytes.mapValues { (_, bytes) -> bytes.toUnsignedByteList() },
            diagnostics = diagnostics,
        )
    }

    /**
     * Resolves [source] and [faceIndex] to one safely bounded SFNT directory.
     *
     * Single-face SFNT input remains delegated to [reader] so custom reader
     * behavior is preserved. TTC input is selected here because the collection
     * header owns the mapping from a zero-based face index to a nested SFNT
     * directory and because TTC table offsets are relative to the collection
     * bytes, not to a synthetic per-face byte slice.
     *
     * @param source Font source bytes in their original container format.
     * @param faceIndex Requested zero-based face index.
     * @return A selected face with a directory and table reader for that face.
     * @throws IllegalArgumentException when [faceIndex] is invalid for [source]
     * or when the selected directory cannot be bounded.
     */
    private fun selectFaceInput(source: FontSource, faceIndex: Int): SelectedSFNTFaceInput {
        require(faceIndex >= 0) {
            "OpenType faceIndex must be non-negative; received $faceIndex."
        }

        if (source.bytes.startsWithTtcTag()) {
            return selectTtcFaceInput(source, faceIndex)
        }

        require(faceIndex == 0) {
            "Single-face SFNT sources support only faceIndex 0; received $faceIndex."
        }

        return SelectedSFNTFaceInput(
            directory = reader.readDirectory(source),
            readTable = { record -> reader.readTable(source, record) },
        )
    }

    /**
     * Selects one face from a TTC/OpenType collection source.
     *
     * The returned reader uses the original [source] byte array and the absolute
     * table offsets stored in the selected SFNT directory. It copies only the raw
     * payload requested for each table, matching [SFNTReader.readTable] behavior
     * for single-face sources without materializing unrelated collection bytes.
     *
     * @param source Original TTC/OpenType collection source.
     * @param faceIndex Zero-based collection face index to select.
     * @return Selected face directory and table reader.
     * @throws IllegalArgumentException when the TTC header, offset table, face
     * index, selected face directory, or selected table ranges are malformed.
     */
    private fun selectTtcFaceInput(source: FontSource, faceIndex: Int): SelectedSFNTFaceInput {
        val bytes = source.bytes
        require(bytes.size >= TTC_HEADER_SIZE) {
            "TTC/ttcf source must contain at least $TTC_HEADER_SIZE bytes for the collection header."
        }

        val version = bytes.readUInt32BE(4, "TTC/ttcf version")
        require(version == TTC_VERSION_1 || version == TTC_VERSION_2) {
            "Unsupported TTC/ttcf version ${version.toHexUInt32()}; expected 0x00010000 or 0x00020000."
        }

        val faceCount = bytes.readUInt32BE(8, "TTC/ttcf numFonts")
        require(faceCount in 1..Int.MAX_VALUE.toLong()) {
            "TTC/ttcf numFonts $faceCount must be between 1 and ${Int.MAX_VALUE}."
        }
        require(faceIndex.toLong() < faceCount) {
            "TTC/ttcf faceIndex $faceIndex is outside collection range 0..${faceCount - 1}."
        }

        val offsetTableEnd = TTC_HEADER_SIZE.toLong() + faceCount * TTC_OFFSET_TABLE_ENTRY_SIZE.toLong()
        require(offsetTableEnd <= bytes.size.toLong()) {
            "TTC/ttcf offset table range [$TTC_HEADER_SIZE, $offsetTableEnd) exceeds source length ${bytes.size}."
        }

        val faceOffsetRecord = TTC_HEADER_SIZE + faceIndex * TTC_OFFSET_TABLE_ENTRY_SIZE
        val faceOffset = bytes.readUInt32BE(faceOffsetRecord, "TTC/ttcf face offset[$faceIndex]")
        require(faceOffset <= Int.MAX_VALUE.toLong() && faceOffset + SFNT_DIRECTORY_HEADER_SIZE.toLong() <= bytes.size.toLong()) {
            "TTC/ttcf face $faceIndex offset $faceOffset must allow an SFNT header within source length ${bytes.size}."
        }

        val directory = bytes.readSfntDirectoryAt(
            directoryOffset = faceOffset.toInt(),
            label = "TTC/ttcf face $faceIndex",
        )

        return SelectedSFNTFaceInput(
            directory = directory,
            readTable = { record -> bytes.readSfntTable(record, tableLabel = "TTC/ttcf") },
        )
    }

    private fun parseLayout(
        source: FontSource,
        rawTableBytes: Map<SFNTTableTag, ByteArray>,
        metrics: MetricsTables,
        diagnostics: MutableList<OpenTypeParseDiagnostic>,
    ): OpenTypeLayoutTables {
        val layoutTables = rawTableBytes
            .filterKeys { it in LAYOUT_TABLE_TAGS }
            .mapValues { (_, bytes) -> bytes.toUnsignedByteList() }
        val kern = rawTableBytes[KERN_TABLE_TAG]?.let { table ->
            runCatching { OpenTypeKernTableParser.parse(table) }
                .getOrElse { error ->
                diagnostics += tableDiagnostic(
                    source = source,
                    table = KERN_TABLE_TAG,
                    message = "Unable to parse OpenType table ${KERN_TABLE_TAG.value}.",
                    causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                    cause = error,
                )
                null
            }
        }
        val gsub = rawTableBytes[GSUB_TABLE_TAG]?.let { table ->
            runCatching { OpenTypeGsubTableParser.parse(table).takeIf { it.lookups.isNotEmpty() } }
                .getOrElse { error ->
                diagnostics += tableDiagnostic(
                    source = source,
                    table = GSUB_TABLE_TAG,
                    message = "Unable to parse OpenType table ${GSUB_TABLE_TAG.value}.",
                    causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                    cause = error,
                )
                null
            }
        }
        val gdef = rawTableBytes[GDEF_TABLE_TAG]?.let { table ->
            runCatching { OpenTypeGdefTableParser.parse(table).takeIf { parsed ->
                parsed.glyphClasses.isNotEmpty() ||
                    parsed.markAttachmentClasses.isNotEmpty() ||
                    parsed.ligatureCaretPositions.isNotEmpty()
            } }.getOrElse { error ->
                diagnostics += tableDiagnostic(
                    source = source,
                    table = GDEF_TABLE_TAG,
                    message = "Unable to parse OpenType table ${GDEF_TABLE_TAG.value}.",
                    causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                    cause = error,
                )
                null
            }
        }
        val gposParseResult = rawTableBytes[GPOS_TABLE_TAG]?.let { table ->
            var generalGposDiagnosticEmitted = false
            val generalGpos = runCatching { OpenTypeGposTableParser.parse(table).takeIf { it.lookups.isNotEmpty() } }
                .getOrElse { error ->
                    generalGposDiagnosticEmitted = true
                    diagnostics += tableDiagnostic(
                        source = source,
                        table = GPOS_TABLE_TAG,
                        message = "Unable to parse OpenType table ${GPOS_TABLE_TAG.value}.",
                        causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                        cause = error,
                    )
                    null
                }
            val suppressPairSubsetFailure = generalGposDiagnosticEmitted
            val singles = runCatching { OpenTypeGposPairTableParser.parseSingles(table).takeIf { it.adjustments.isNotEmpty() } }
                .getOrElse { error ->
                    if (!suppressPairSubsetFailure) {
                        diagnostics += tableDiagnostic(
                            source = source,
                            table = GPOS_TABLE_TAG,
                            message = "Unable to parse OpenType table ${GPOS_TABLE_TAG.value}.",
                            causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                            cause = error,
                        )
                    }
                    null
                }
            val pairs = runCatching {
                OpenTypeGposPairTableParser.parse(
                    table = table,
                    numGlyphs = metrics.numGlyphs ?: 0,
                ).takeIf { it.pairs.isNotEmpty() }
            }.getOrElse { error ->
                if (!suppressPairSubsetFailure) {
                    diagnostics += tableDiagnostic(
                        source = source,
                        table = GPOS_TABLE_TAG,
                        message = "Unable to parse OpenType table ${GPOS_TABLE_TAG.value}.",
                        causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                        cause = error,
                    )
                }
                null
            }
            Triple(singles, pairs, generalGpos)
        }

        return OpenTypeLayoutTables(
            tables = layoutTables,
            kern = kern,
            gsub = gsub,
            gdef = gdef,
            gposSingles = gposParseResult?.first,
            gposPairs = gposParseResult?.second,
            gpos = gposParseResult?.third,
        )
    }

    private fun parseColor(
        source: FontSource,
        rawTableBytes: Map<SFNTTableTag, ByteArray>,
        metrics: MetricsTables,
        diagnostics: MutableList<OpenTypeParseDiagnostic>,
    ): ColorFontTables {
        val colorTables = rawTableBytes
            .filterKeys { it in COLOR_FONT_TABLE_TAGS }
            .mapValues { (_, bytes) -> bytes.toUnsignedByteList() }
        val svg = rawTableBytes[SVG_TABLE_TAG]?.let { table ->
            runCatching { parseSvgTable(table) }
                .getOrElse { error ->
                diagnostics += tableDiagnostic(
                    source = source,
                    table = SVG_TABLE_TAG,
                    message = "Unable to parse OpenType table ${SVG_TABLE_TAG.value}.",
                    causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                    cause = error,
                )
                null
            }
        }
        val bitmap = parseBitmapFont(
            source = source,
            rawTableBytes = rawTableBytes,
            numGlyphs = metrics.numGlyphs,
            diagnostics = diagnostics,
        )

        return ColorFontTables(
            tables = colorTables,
            svg = svg,
            bitmap = bitmap,
        )
    }

    private fun parseSvgTable(table: ByteArray): OpenTypeSvgTable {
        table.requireRange(0, SVG_TABLE_HEADER_SIZE, "OpenType SVG table header")
        val version = table.readUInt16BE(0, "OpenType SVG version")
        require(version == 0) {
            "OpenType SVG table version $version is not supported; expected 0."
        }
        val documentListOffset = table.readUInt32BE(2, "OpenType SVG documentListOffset")
        require(documentListOffset in 1..Int.MAX_VALUE.toLong()) {
            "OpenType SVG documentListOffset $documentListOffset must be between 1 and ${Int.MAX_VALUE}."
        }
        val documentListStart = documentListOffset.toInt()
        table.requireRange(documentListStart, UINT16_BYTE_LENGTH, "OpenType SVG DocumentList header")
        val documentCount = table.readUInt16BE(documentListStart, "OpenType SVG DocumentList numEntries")
        require(documentCount in 1..MAX_SVG_DOCUMENT_RECORDS) {
            "OpenType SVG DocumentList numEntries $documentCount must be between 1 and $MAX_SVG_DOCUMENT_RECORDS."
        }
        val recordsStart = documentListStart + UINT16_BYTE_LENGTH
        table.requireArrayRange(
            offset = recordsStart,
            count = documentCount,
            recordSize = SVG_DOCUMENT_RECORD_SIZE,
            label = "OpenType SVG DocumentList records",
        )
        val recordsEnd = recordsStart + documentCount * SVG_DOCUMENT_RECORD_SIZE
        val records = ArrayList<OpenTypeSvgDocumentRecord>(documentCount)
        val documents = ArrayList<OpenTypeSvgDocument>(documentCount)
        var previousEndGlyphId = -1

        repeat(documentCount) { index ->
            val recordOffset = recordsStart + index * SVG_DOCUMENT_RECORD_SIZE
            val startGlyphId = table.readUInt16BE(recordOffset, "OpenType SVG document record $index startGlyphID")
            val endGlyphId = table.readUInt16BE(recordOffset + 2, "OpenType SVG document record $index endGlyphID")
            val svgDocOffset = table.readUInt32BE(recordOffset + 4, "OpenType SVG document record $index svgDocOffset")
            val svgDocLength = table.readUInt32BE(recordOffset + 8, "OpenType SVG document record $index svgDocLength")
            require(svgDocOffset in 1..Int.MAX_VALUE.toLong()) {
                "OpenType SVG document record $index svgDocOffset $svgDocOffset must be positive and addressable."
            }
            require(svgDocLength in 1..Int.MAX_VALUE.toLong()) {
                "OpenType SVG document record $index svgDocLength $svgDocLength must be positive and addressable."
            }
            require(startGlyphId <= endGlyphId) {
                "OpenType SVG document record $index startGlyphID $startGlyphId exceeds endGlyphID $endGlyphId."
            }
            require(startGlyphId > previousEndGlyphId) {
                "OpenType SVG document record $index overlaps or is not sorted after glyph $previousEndGlyphId."
            }
            val glyphCount = endGlyphId - startGlyphId + 1
            require(glyphCount <= MAX_SVG_GLYPHS_PER_RECORD) {
                "OpenType SVG document record $index covers $glyphCount glyphs, exceeding $MAX_SVG_GLYPHS_PER_RECORD."
            }
            val documentStart = documentListStart.toLong() + svgDocOffset
            val documentEnd = documentStart + svgDocLength
            require(documentStart in recordsEnd.toLong()..Int.MAX_VALUE.toLong() && documentEnd <= table.size.toLong()) {
                "OpenType SVG document record $index byte range [$documentStart, $documentEnd) must follow records ending at $recordsEnd and fit table length ${table.size}."
            }

            val record = OpenTypeSvgDocumentRecord(
                startGlyphId = startGlyphId,
                endGlyphId = endGlyphId,
                offset = documentStart.toInt(),
                length = svgDocLength.toInt(),
            )
            records += record
            documents += OpenTypeSvgDocument(
                startGlyphId = startGlyphId,
                endGlyphId = endGlyphId,
                bytes = table.copyOfRange(documentStart.toInt(), documentEnd.toInt()).toUnsignedByteList(),
            )
            previousEndGlyphId = endGlyphId
        }

        return OpenTypeSvgTable(
            version = version,
            documentListOffset = documentListStart,
            records = records,
            documents = documents,
        )
    }

    private fun parseBitmapFont(
        source: FontSource,
        rawTableBytes: Map<SFNTTableTag, ByteArray>,
        numGlyphs: Int?,
        diagnostics: MutableList<OpenTypeParseDiagnostic>,
    ): OpenTypeBitmapFont? {
        if (CBDT_TABLE_TAG !in rawTableBytes &&
            CBLC_TABLE_TAG !in rawTableBytes &&
            SBIX_TABLE_TAG !in rawTableBytes
        ) {
            return null
        }
        if (numGlyphs == null) {
            diagnostics += OpenTypeParseDiagnostic(
                sourceId = source.id,
                message = "Unable to parse OpenType bitmap tables without a parsed maxp numGlyphs value.",
                table = CBDT_TABLE_TAG.takeIf { it in rawTableBytes } ?: SBIX_TABLE_TAG,
                causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                causeMessage = "OpenType bitmap parsing requires maxp numGlyphs.",
            )
            return null
        }

        val glyphs = LinkedHashMap<Int, OpenTypeBitmapGlyph>()
        if (CBDT_TABLE_TAG in rawTableBytes || CBLC_TABLE_TAG in rawTableBytes) {
            runCatching {
                parseCbdtCblcTables(
                    cbdt = rawTableBytes[CBDT_TABLE_TAG],
                    cblc = rawTableBytes[CBLC_TABLE_TAG],
                    numGlyphs = numGlyphs,
                )
            }.getOrElse { error ->
                diagnostics += tableDiagnostic(
                    source = source,
                    table = CBDT_TABLE_TAG,
                    message = "Unable to parse OpenType bitmap tables ${CBDT_TABLE_TAG.value}/${CBLC_TABLE_TAG.value}.",
                    causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                    cause = error,
                )
                emptyMap()
            }.forEach { (glyphId, glyph) ->
                glyphs.putIfAbsent(glyphId, glyph)
            }
        }

        rawTableBytes[SBIX_TABLE_TAG]?.let { sbix ->
            runCatching { parseSbixTable(sbix, numGlyphs) }
                .getOrElse { error ->
                    diagnostics += tableDiagnostic(
                        source = source,
                        table = SBIX_TABLE_TAG,
                        message = "Unable to parse OpenType table ${SBIX_TABLE_TAG.value}.",
                        causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                        cause = error,
                    )
                    emptyMap()
                }.forEach { (glyphId, glyph) ->
                    glyphs.putIfAbsent(glyphId, glyph)
                }
        }

        return glyphs.takeIf { it.isNotEmpty() }?.let { OpenTypeBitmapFont(glyphs = it.toMap()) }
    }

    private fun parseCbdtCblcTables(
        cbdt: ByteArray?,
        cblc: ByteArray?,
        numGlyphs: Int,
    ): Map<Int, OpenTypeBitmapGlyph> {
        require(cbdt != null && cblc != null) {
            "OpenType CBDT/CBLC bitmap parsing requires both CBDT and CBLC tables."
        }
        cbdt.requireRange(0, CBDT_TABLE_HEADER_SIZE, "OpenType CBDT header")
        cblc.requireRange(0, CBLC_TABLE_HEADER_SIZE, "OpenType CBLC header")
        require(cbdt.readUInt16BE(0, "OpenType CBDT majorVersion") == 3) {
            "OpenType CBDT majorVersion must be 3."
        }
        require(cblc.readUInt16BE(0, "OpenType CBLC majorVersion") == 3) {
            "OpenType CBLC majorVersion must be 3."
        }
        require(cbdt.readUInt16BE(2, "OpenType CBDT minorVersion") in 0..99) {
            "OpenType CBDT minorVersion must be between 0 and 99."
        }
        require(cblc.readUInt16BE(2, "OpenType CBLC minorVersion") in 0..99) {
            "OpenType CBLC minorVersion must be between 0 and 99."
        }
        val sizeCount = cblc.readUInt32BE(4, "OpenType CBLC numSizes")
        require(sizeCount in 1..MAX_BITMAP_STRIKES.toLong()) {
            "OpenType CBLC numSizes $sizeCount must be between 1 and $MAX_BITMAP_STRIKES."
        }
        cblc.requireArrayRange(
            offset = CBLC_TABLE_HEADER_SIZE,
            count = sizeCount.toInt(),
            recordSize = CBLC_BITMAP_SIZE_TABLE_SIZE,
            label = "OpenType CBLC bitmapSizeTable records",
        )

        val out = LinkedHashMap<Int, OpenTypeBitmapGlyph>()
        repeat(sizeCount.toInt()) { sizeIndex ->
            val sizeOffset = CBLC_TABLE_HEADER_SIZE + sizeIndex * CBLC_BITMAP_SIZE_TABLE_SIZE
            val subtableArrayOffset = cblc.readUInt32BE(sizeOffset, "OpenType CBLC bitmapSizeTable $sizeIndex indexSubTableArrayOffset")
            val subtableCount = cblc.readUInt32BE(sizeOffset + 8, "OpenType CBLC bitmapSizeTable $sizeIndex numberOfIndexSubTables")
            val startGlyph = cblc.readUInt16BE(sizeOffset + 40, "OpenType CBLC bitmapSizeTable $sizeIndex startGlyphIndex")
            val endGlyph = cblc.readUInt16BE(sizeOffset + 42, "OpenType CBLC bitmapSizeTable $sizeIndex endGlyphIndex")
            val ppemX = cblc.readUInt8(sizeOffset + 44, "OpenType CBLC bitmapSizeTable $sizeIndex ppemX")
            val ppemY = cblc.readUInt8(sizeOffset + 45, "OpenType CBLC bitmapSizeTable $sizeIndex ppemY")
            val bitDepth = cblc.readUInt8(sizeOffset + 46, "OpenType CBLC bitmapSizeTable $sizeIndex bitDepth")
            require(startGlyph <= endGlyph && endGlyph < numGlyphs) {
                "OpenType CBLC bitmapSizeTable $sizeIndex glyph range [$startGlyph, $endGlyph] must fit numGlyphs $numGlyphs."
            }
            require(subtableCount in 1..MAX_BITMAP_SUBTABLES.toLong()) {
                "OpenType CBLC bitmapSizeTable $sizeIndex numberOfIndexSubTables $subtableCount must be between 1 and $MAX_BITMAP_SUBTABLES."
            }
            require(subtableArrayOffset <= Int.MAX_VALUE.toLong()) {
                "OpenType CBLC bitmapSizeTable $sizeIndex indexSubTableArrayOffset $subtableArrayOffset exceeds addressable range."
            }
            val arrayStart = subtableArrayOffset.toInt()
            cblc.requireArrayRange(
                offset = arrayStart,
                count = subtableCount.toInt(),
                recordSize = CBLC_INDEX_SUBTABLE_ARRAY_RECORD_SIZE,
                label = "OpenType CBLC indexSubTableArray $sizeIndex",
            )
            repeat(subtableCount.toInt()) { index ->
                val entry = arrayStart + index * CBLC_INDEX_SUBTABLE_ARRAY_RECORD_SIZE
                val firstGlyph = cblc.readUInt16BE(entry, "OpenType CBLC indexSubTableArray $sizeIndex record $index firstGlyphIndex")
                val lastGlyph = cblc.readUInt16BE(entry + 2, "OpenType CBLC indexSubTableArray $sizeIndex record $index lastGlyphIndex")
                val subtableOffset = cblc.readUInt32BE(entry + 4, "OpenType CBLC indexSubTableArray $sizeIndex record $index additionalOffsetToIndexSubtable")
                require(firstGlyph <= lastGlyph && firstGlyph >= startGlyph && lastGlyph <= endGlyph) {
                    "OpenType CBLC indexSubTableArray $sizeIndex record $index glyph range [$firstGlyph, $lastGlyph] must fit strike range [$startGlyph, $endGlyph]."
                }
                require(subtableOffset <= Int.MAX_VALUE.toLong()) {
                    "OpenType CBLC indexSubTableArray $sizeIndex record $index additionalOffsetToIndexSubtable $subtableOffset exceeds addressable range."
                }
                parseCblcIndexSubtable(
                    cbdt = cbdt,
                    cblc = cblc,
                    subtableStart = checkedByteOffset(
                        base = arrayStart,
                        offset = subtableOffset.toInt(),
                        label = "OpenType CBLC indexSubTableArray $sizeIndex record $index subtable",
                    ),
                    firstGlyph = firstGlyph,
                    lastGlyph = lastGlyph,
                    ppemX = ppemX,
                    ppemY = ppemY,
                    bitDepth = bitDepth,
                    out = out,
                )
            }
        }

        return out
    }

    private fun parseCblcIndexSubtable(
        cbdt: ByteArray,
        cblc: ByteArray,
        subtableStart: Int,
        firstGlyph: Int,
        lastGlyph: Int,
        ppemX: Int,
        ppemY: Int,
        bitDepth: Int,
        out: MutableMap<Int, OpenTypeBitmapGlyph>,
    ) {
        cblc.requireRange(subtableStart, CBLC_INDEX_SUBTABLE_HEADER_SIZE, "OpenType CBLC indexSubTable header")
        val indexFormat = cblc.readUInt16BE(subtableStart, "OpenType CBLC indexSubTable indexFormat")
        val imageFormat = cblc.readUInt16BE(subtableStart + 2, "OpenType CBLC indexSubTable imageFormat")
        if (imageFormat !in CBDT_PNG_IMAGE_FORMATS) {
            return
        }
        val imageDataOffset = cblc.readUInt32BE(subtableStart + 4, "OpenType CBLC indexSubTable imageDataOffset")
        require(imageDataOffset <= Int.MAX_VALUE.toLong()) {
            "OpenType CBLC indexSubTable imageDataOffset $imageDataOffset exceeds addressable range."
        }
        val glyphCount = lastGlyph - firstGlyph + 1
        val offsets = when (indexFormat) {
            1 -> {
                cblc.requireArrayRange(
                    offset = subtableStart + CBLC_INDEX_SUBTABLE_HEADER_SIZE,
                    count = glyphCount + 1,
                    recordSize = UINT32_BYTE_LENGTH,
                    label = "OpenType CBLC indexSubTable format 1 offsets",
                )
                IntArray(glyphCount + 1) { index ->
                    val offset = cblc.readUInt32BE(
                        subtableStart + CBLC_INDEX_SUBTABLE_HEADER_SIZE + index * UINT32_BYTE_LENGTH,
                        "OpenType CBLC indexSubTable format 1 offset[$index]",
                    )
                    require(offset <= Int.MAX_VALUE.toLong()) {
                        "OpenType CBLC indexSubTable format 1 offset[$index] $offset exceeds addressable range."
                    }
                    offset.toInt()
                }
            }
            3 -> {
                cblc.requireArrayRange(
                    offset = subtableStart + CBLC_INDEX_SUBTABLE_HEADER_SIZE,
                    count = glyphCount + 1,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType CBLC indexSubTable format 3 offsets",
                )
                IntArray(glyphCount + 1) { index ->
                    cblc.readUInt16BE(
                        subtableStart + CBLC_INDEX_SUBTABLE_HEADER_SIZE + index * UINT16_BYTE_LENGTH,
                        "OpenType CBLC indexSubTable format 3 offset[$index]",
                    )
                }
            }
            else -> return
        }
        for (index in 0 until offsets.lastIndex) {
            require(offsets[index] <= offsets[index + 1]) {
                "OpenType CBLC indexSubTable offsets must be nondecreasing at index $index."
            }
        }

        for (index in 0 until glyphCount) {
            val payloadLength = offsets[index + 1] - offsets[index]
            if (payloadLength <= 0) {
                continue
            }
            require(payloadLength <= MAX_BITMAP_PAYLOAD_BYTES) {
                "OpenType CBDT bitmap payload length $payloadLength exceeds $MAX_BITMAP_PAYLOAD_BYTES bytes."
            }
            val payloadOffset = checkedByteOffset(
                base = imageDataOffset.toInt(),
                offset = offsets[index],
                label = "OpenType CBDT bitmap payload offset",
            )
            cbdt.requireRange(payloadOffset, payloadLength, "OpenType CBDT bitmap payload")
            val imageHeaderLength = when (imageFormat) {
                17 -> CBDT_SMALL_METRICS_HEADER_SIZE
                18 -> CBDT_BIG_METRICS_HEADER_SIZE
                19 -> 0
                else -> return
            }
            val pngOffset = checkedByteOffset(
                base = payloadOffset,
                offset = imageHeaderLength,
                label = "OpenType CBDT bitmap PNG payload offset",
            )
            val pngLength = payloadLength - imageHeaderLength
            require(pngLength > 0 && isPngPayload(cbdt, pngOffset, pngLength)) {
                "OpenType CBDT bitmap payload for glyph ${firstGlyph + index} does not contain a valid PNG payload."
            }
            out.putIfAbsent(
                firstGlyph + index,
                OpenTypeBitmapGlyph(
                    glyphId = firstGlyph + index,
                    source = OpenTypeBitmapGlyphSource.CBDT_CBLC,
                    ppemX = ppemX,
                    ppemY = ppemY,
                    bitDepth = bitDepth,
                    originOffsetX = 0,
                    originOffsetY = 0,
                    imageFormat = "png ",
                    bytes = cbdt.copyOfRange(pngOffset, pngOffset + pngLength).toUnsignedByteList(),
                ),
            )
        }
    }

    private fun parseSbixTable(table: ByteArray, numGlyphs: Int): Map<Int, OpenTypeBitmapGlyph> {
        table.requireRange(0, SBIX_TABLE_HEADER_SIZE, "OpenType sbix header")
        val version = table.readUInt16BE(0, "OpenType sbix version")
        require(version == 1) {
            "OpenType sbix version $version is not supported; expected 1."
        }
        val strikeCount = table.readUInt32BE(4, "OpenType sbix numStrikes")
        require(strikeCount in 1..MAX_BITMAP_STRIKES.toLong()) {
            "OpenType sbix numStrikes $strikeCount must be between 1 and $MAX_BITMAP_STRIKES."
        }
        table.requireArrayRange(
            offset = SBIX_TABLE_HEADER_SIZE,
            count = strikeCount.toInt(),
            recordSize = UINT32_BYTE_LENGTH,
            label = "OpenType sbix strike offsets",
        )

        val out = LinkedHashMap<Int, OpenTypeBitmapGlyph>()
        repeat(strikeCount.toInt()) { strikeIndex ->
            val strikeOffset = table.readUInt32BE(
                SBIX_TABLE_HEADER_SIZE + strikeIndex * UINT32_BYTE_LENGTH,
                "OpenType sbix strike offset[$strikeIndex]",
            )
            require(strikeOffset <= Int.MAX_VALUE.toLong()) {
                "OpenType sbix strike offset[$strikeIndex] $strikeOffset exceeds addressable range."
            }
            val strikeStart = strikeOffset.toInt()
            table.requireRange(
                strikeStart,
                SBIX_STRIKE_HEADER_SIZE + (numGlyphs + 1) * UINT32_BYTE_LENGTH,
                "OpenType sbix strike $strikeIndex header and glyph offsets",
            )
            val ppem = table.readUInt16BE(strikeStart, "OpenType sbix strike $strikeIndex ppem")
            table.readUInt16BE(strikeStart + UINT16_BYTE_LENGTH, "OpenType sbix strike $strikeIndex resolution")
            val offsetsStart = strikeStart + SBIX_STRIKE_HEADER_SIZE
            val offsets = IntArray(numGlyphs + 1) { glyphIndex ->
                val offset = table.readUInt32BE(
                    offsetsStart + glyphIndex * UINT32_BYTE_LENGTH,
                    "OpenType sbix strike $strikeIndex glyphDataOffset[$glyphIndex]",
                )
                require(offset <= Int.MAX_VALUE.toLong()) {
                    "OpenType sbix strike $strikeIndex glyphDataOffset[$glyphIndex] $offset exceeds addressable range."
                }
                offset.toInt()
            }
            for (index in 0 until offsets.lastIndex) {
                require(offsets[index] <= offsets[index + 1]) {
                    "OpenType sbix strike $strikeIndex glyphDataOffsets must be nondecreasing at index $index."
                }
            }

            for (glyphId in 0 until numGlyphs) {
                val glyphStart = checkedByteOffset(
                    base = strikeStart,
                    offset = offsets[glyphId],
                    label = "OpenType sbix strike $strikeIndex glyph $glyphId start",
                )
                val glyphEnd = checkedByteOffset(
                    base = strikeStart,
                    offset = offsets[glyphId + 1],
                    label = "OpenType sbix strike $strikeIndex glyph $glyphId end",
                )
                val payloadLength = glyphEnd - glyphStart
                if (payloadLength <= 0) {
                    continue
                }
                require(payloadLength in SBIX_GLYPH_HEADER_SIZE..MAX_BITMAP_PAYLOAD_BYTES) {
                    "OpenType sbix strike $strikeIndex glyph $glyphId payload length $payloadLength must be between $SBIX_GLYPH_HEADER_SIZE and $MAX_BITMAP_PAYLOAD_BYTES."
                }
                table.requireRange(glyphStart, payloadLength, "OpenType sbix strike $strikeIndex glyph $glyphId payload")
                val graphicType = table.readTag(glyphStart + 4, "OpenType sbix strike $strikeIndex glyph $glyphId graphicType")
                if (graphicType != "png ") {
                    continue
                }
                val pngOffset = checkedByteOffset(
                    base = glyphStart,
                    offset = SBIX_GLYPH_HEADER_SIZE,
                    label = "OpenType sbix strike $strikeIndex glyph $glyphId PNG payload",
                )
                val pngLength = glyphEnd - pngOffset
                require(isPngPayload(table, pngOffset, pngLength)) {
                    "OpenType sbix strike $strikeIndex glyph $glyphId does not contain a valid PNG payload."
                }
                out.putIfAbsent(
                    glyphId,
                    OpenTypeBitmapGlyph(
                        glyphId = glyphId,
                        source = OpenTypeBitmapGlyphSource.SBIX,
                        ppemX = ppem,
                        ppemY = ppem,
                        bitDepth = 32,
                        originOffsetX = table.readInt16BE(glyphStart, "OpenType sbix strike $strikeIndex glyph $glyphId originOffsetX"),
                        originOffsetY = table.readInt16BE(glyphStart + UINT16_BYTE_LENGTH, "OpenType sbix strike $strikeIndex glyph $glyphId originOffsetY"),
                        imageFormat = graphicType,
                        bytes = table.copyOfRange(pngOffset, glyphEnd).toUnsignedByteList(),
                    ),
                )
            }
        }

        return out
    }

    private fun isPngPayload(bytes: ByteArray, offset: Int, length: Int): Boolean {
        if (length < PNG_SIGNATURE.size) {
            return false
        }
        return runCatching {
            bytes.requireRange(offset, PNG_SIGNATURE.size, "OpenType bitmap PNG signature")
            PNG_SIGNATURE.indices.all { index -> bytes[offset + index] == PNG_SIGNATURE[index] }
        }.getOrDefault(false)
    }

    private fun parseVariations(
        source: FontSource,
        rawTableBytes: Map<SFNTTableTag, ByteArray>,
        diagnostics: MutableList<OpenTypeParseDiagnostic>,
    ): VariationTables {
        val fvar = rawTableBytes[FVAR_TABLE_TAG] ?: return VariationTables()
        val fvarTables = runCatching { OpenTypeFvarTableParser.parse(fvar) }
            .getOrElse { error ->
                diagnostics += tableDiagnostic(
                    source = source,
                    table = FVAR_TABLE_TAG,
                    message = "Unable to parse OpenType table ${FVAR_TABLE_TAG.value}.",
                    causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                    cause = error,
                )
                return VariationTables()
            }

        val avar = rawTableBytes[AVAR_TABLE_TAG] ?: return fvarTables
        return runCatching {
            fvarTables.copy(
                axisSegmentMaps = OpenTypeAvarTableParser.parse(
                    table = avar,
                    axisCount = fvarTables.axes.size,
                ),
            )
        }.getOrElse { error ->
            diagnostics += tableDiagnostic(
                source = source,
                table = AVAR_TABLE_TAG,
                message = "Unable to parse OpenType table ${AVAR_TABLE_TAG.value}.",
                causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                cause = error,
            )
            fvarTables
        }
    }

    private fun parseMetrics(
        source: FontSource,
        rawTableBytes: Map<SFNTTableTag, ByteArray>,
        diagnostics: MutableList<OpenTypeParseDiagnostic>,
    ): MetricsTables {
        val missingTables = METRIC_TABLE_TAGS.filterNot(rawTableBytes::containsKey)
        if (missingTables.isNotEmpty()) {
            missingTables.forEach { table ->
                diagnostics += missingTableDiagnostic(source, table)
            }
            return MetricsTables()
        }

        val baseMetrics = runCatching {
            OpenTypeMetricsTableParser.parse(
                head = rawTableBytes.getValue(HEAD_TABLE_TAG),
                hhea = rawTableBytes.getValue(HHEA_TABLE_TAG),
                maxp = rawTableBytes.getValue(MAXP_TABLE_TAG),
                hmtx = rawTableBytes.getValue(HMTX_TABLE_TAG),
                os2 = rawTableBytes[OS_2_TABLE_TAG],
                post = rawTableBytes[POST_TABLE_TAG],
            )
        }.getOrElse { error ->
            diagnostics += tableDiagnostic(
                source = source,
                table = metricTableTagFor(error),
                message = "Unable to parse OpenType metric tables.",
                causeCode = "INVALID_METRICS",
                cause = error,
            )
            MetricsTables()
        }

        val vhea = rawTableBytes[VHEA_TABLE_TAG] ?: return baseMetrics
        val verticalHeader = runCatching {
            OpenTypeMetricsTableParser.parseVerticalHeader(vhea)
        }.getOrElse { error ->
            diagnostics += tableDiagnostic(
                source = source,
                table = VHEA_TABLE_TAG,
                message = "Unable to parse OpenType table ${VHEA_TABLE_TAG.value}.",
                causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                cause = error,
            )
            return baseMetrics
        }
        var metrics = baseMetrics.copy(
            verticalAscender = verticalHeader.verticalAscender,
            verticalDescender = verticalHeader.verticalDescender,
            verticalLineGap = verticalHeader.verticalLineGap,
            maxAdvanceHeight = verticalHeader.maxAdvanceHeight,
            numberOfVMetrics = verticalHeader.numberOfVMetrics,
        )

        val vmtx = rawTableBytes[VMTX_TABLE_TAG] ?: return metrics
        val numGlyphs = metrics.numGlyphs ?: return metrics
        val numberOfVMetrics = metrics.numberOfVMetrics ?: return metrics
        val verticalMetrics = runCatching {
            OpenTypeMetricsTableParser.parseVerticalMetrics(
                table = vmtx,
                numGlyphs = numGlyphs,
                numberOfVMetrics = numberOfVMetrics,
            )
        }.getOrElse { error ->
            diagnostics += tableDiagnostic(
                source = source,
                table = VMTX_TABLE_TAG,
                message = "Unable to parse OpenType table ${VMTX_TABLE_TAG.value}.",
                causeCode = OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC,
                cause = error,
            )
            return metrics
        }

        metrics = metrics.copy(verticalMetrics = verticalMetrics)
        return metrics
    }

    private fun parseStyle(
        names: NameTable,
        metrics: MetricsTables,
        rawTableBytes: Map<SFNTTableTag, ByteArray>,
    ): OpenTypeStyle =
        OpenTypeStyleParser.parse(
            names = names,
            weightClass = metrics.weightClass,
            widthClass = metrics.widthClass,
            fsSelection = metrics.fsSelection,
            head = rawTableBytes[HEAD_TABLE_TAG],
            post = rawTableBytes[POST_TABLE_TAG],
        )

    private fun metricTableTagFor(error: Throwable): SFNTTableTag? {
        val message = error.message.orEmpty()
        return when {
            message.contains("OpenType head ") -> HEAD_TABLE_TAG
            message.contains("OpenType hhea ") -> HHEA_TABLE_TAG
            message.contains("OpenType maxp ") -> MAXP_TABLE_TAG
            message.contains("OpenType hmtx ") -> HMTX_TABLE_TAG
            message.contains("OpenType vhea ") -> VHEA_TABLE_TAG
            message.contains("OpenType vmtx ") -> VMTX_TABLE_TAG
            message.contains("OpenType OS/2 ") -> OS_2_TABLE_TAG
            message.contains("OpenType post ") -> POST_TABLE_TAG
            else -> null
        }
    }

    private fun <T> parseOptionalTable(
        source: FontSource,
        table: SFNTTableTag,
        rawTableBytes: Map<SFNTTableTag, ByteArray>,
        diagnostics: MutableList<OpenTypeParseDiagnostic>,
        defaultValue: T,
        parser: (ByteArray) -> T,
    ): T {
        val bytes = rawTableBytes[table]
        if (bytes == null) {
            diagnostics += missingTableDiagnostic(source, table)
            return defaultValue
        }

        return runCatching { parser(bytes) }
            .getOrElse { error ->
                diagnostics += tableDiagnostic(
                    source = source,
                    table = table,
                    message = "Unable to parse OpenType table ${table.value}.",
                    causeCode = "INVALID_TABLE",
                    cause = error,
                )
                defaultValue
            }
    }

    private fun missingTableDiagnostic(
        source: FontSource,
        table: SFNTTableTag,
    ): OpenTypeParseDiagnostic = OpenTypeParseDiagnostic(
        sourceId = source.id,
        message = "OpenType table ${table.value} is not present in the SFNT directory.",
        table = table,
        causeCode = "MISSING_TABLE",
    )

    private fun tableDiagnostic(
        source: FontSource,
        table: SFNTTableTag?,
        message: String,
        causeCode: String,
        cause: Throwable,
    ): OpenTypeParseDiagnostic = OpenTypeParseDiagnostic(
        sourceId = source.id,
        message = message,
        table = table,
        causeCode = causeCode,
        causeMessage = cause.message,
    )
}

/**
 * Parsed OpenType face data used by higher-level font APIs and scalers.
 *
 * @property id Stable identifier assigned to this parsed face.
 * @property source Raw source that produced the face.
 * @property directory SFNT table directory for the face.
 * @property cmap Parsed character-to-glyph mapping tables.
 * @property names Parsed naming metadata.
 * @property metrics Parsed horizontal, vertical, and global metric tables.
 * @property style Parsed OpenType style metadata for family matching.
 * @property variations Parsed variable-font tables.
 * @property layout Parsed OpenType Layout tables.
 * @property color Parsed color font tables.
 * @property rawTables Raw SFNT table payloads keyed by tag, stored as unsigned
 * byte values in table-directory units for later layers that need payloads this
 * parser does not interpret.
 * @property diagnostics Non-fatal parse diagnostics.
 * @property faceIndex Zero-based selected face index from a single-face SFNT or
 * TTC/OpenType collection.
 */
data class OpenTypeFaceData(
    val id: TypefaceID,
    val source: FontSource,
    val directory: SFNTTableDirectory,
    val cmap: CMapTable = CMapTable(),
    val names: NameTable = NameTable(),
    val metrics: MetricsTables = MetricsTables(),
    val style: OpenTypeStyle = OpenTypeStyle(),
    val variations: VariationTables = VariationTables(),
    val layout: OpenTypeLayoutTables = OpenTypeLayoutTables(),
    val color: ColorFontTables = ColorFontTables(),
    val rawTables: Map<SFNTTableTag, List<Int>> = emptyMap(),
    val diagnostics: List<OpenTypeParseDiagnostic> = emptyList(),
    val faceIndex: Int = 0,
) {
    init {
        require(faceIndex >= 0) {
            "OpenType faceIndex must be non-negative; received $faceIndex."
        }
    }
}

/**
 * Deterministic evidence for one parsed SFNT/OpenType face.
 *
 * This is a dump surface for already-parsed facts. It does not parse outlines,
 * claim scaler completeness, or promote CFF/CFF2 support.
 *
 * @property faceIndex Zero-based selected face index.
 * @property sourceId Stable source identity.
 * @property typefaceId Stable parsed typeface identity.
 * @property sourceKind Source provenance kind from font core.
 * @property scalerType Raw SFNT scaler type as lowercase hexadecimal.
 * @property scalerTypeLabel Human-readable raw scaler tag or version label.
 * @property tableRecords Directory table records sorted for deterministic dumps.
 * @property directoryDiagnostics Bounded table-directory diagnostics for this face.
 * @property preferredCMap Preferred parsed `cmap` facts used by lookup, when present.
 * @property metrics Parsed metric summary.
 * @property diagnostics Non-fatal parse diagnostics.
 */
data class OpenTypeFaceEvidence(
    val faceIndex: Int,
    val sourceId: FontSourceID,
    val typefaceId: TypefaceID,
    val sourceKind: FontSourceKind,
    val scalerType: String,
    val scalerTypeLabel: String,
    val tableRecords: List<SFNTTableEvidence>,
    val directoryDiagnostics: List<SFNTTableDirectoryDiagnostic> = emptyList(),
    val preferredCMap: OpenTypeCMapEvidence?,
    val metrics: OpenTypeMetricsEvidence,
    val diagnostics: List<OpenTypeParseDiagnosticEvidence>,
) {
    init {
        require(faceIndex >= 0) {
            "OpenType face evidence faceIndex must be non-negative."
        }
        require(scalerType.matches(SFNT_HEX_UINT32_PATTERN)) {
            "OpenType face evidence scalerType must be lowercase hexadecimal uint32 text."
        }
        require(tableRecords == tableRecords.sortedWith(SFNT_TABLE_EVIDENCE_ORDER)) {
            "OpenType face evidence tableRecords must be sorted by tag, offset, length, and checksum."
        }
        require(directoryDiagnostics == directoryDiagnostics.sortedWith(SFNT_TABLE_DIRECTORY_DIAGNOSTIC_EVIDENCE_ORDER)) {
            "OpenType face evidence directoryDiagnostics must be sorted by code, table, offset, length, and message."
        }
        require(diagnostics == diagnostics.sortedWith(SFNT_DIAGNOSTIC_EVIDENCE_ORDER)) {
            "OpenType face evidence diagnostics must be sorted by table, causeCode, message, and causeMessage."
        }
    }

    /**
     * Serializes the evidence as canonical JSON with stable field order.
     *
     * @return deterministic JSON ending with a newline.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("faceIndex", faceIndex, indent = "  ", comma = true)
        appendSFNTJsonField("sourceId", sourceId.value.toString(), indent = "  ", comma = true)
        appendSFNTJsonField("typefaceId", typefaceId.value.toString(), indent = "  ", comma = true)
        appendSFNTJsonField("sourceKind", sourceKind.name, indent = "  ", comma = true)
        appendSFNTJsonField("scalerType", scalerType, indent = "  ", comma = true)
        appendSFNTJsonField("scalerTypeLabel", scalerTypeLabel, indent = "  ", comma = true)
        append("  \"tables\": [")
        if (tableRecords.isNotEmpty()) {
            append("\n")
            append(tableRecords.joinToString(",\n") { record -> record.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("],\n")
        append("  \"directoryDiagnostics\": [")
        if (directoryDiagnostics.isNotEmpty()) {
            append("\n")
            append(directoryDiagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("],\n")
        append("  \"preferredCMap\": ")
        append(preferredCMap?.toCanonicalJson()?.prependIndent("  ")?.trimStart() ?: "null")
        append(",\n")
        append("  \"metrics\": ")
        append(metrics.toCanonicalJson().prependIndent("  ").trimStart())
        append(",\n")
        append("  \"diagnostics\": [")
        if (diagnostics.isNotEmpty()) {
            append("\n")
            append(diagnostics.joinToString(",\n") { diagnostic -> diagnostic.toCanonicalJson().prependIndent("    ") })
            append("\n  ")
        }
        append("]\n")
        append("}\n")
    }
}

/**
 * Deterministic evidence for one SFNT table directory record and copied raw payload.
 */
data class SFNTTableEvidence(
    val tag: String,
    val checksum: String,
    val offset: Long,
    val length: Long,
    val rawByteLength: Int?,
    val rawSha256: String?,
) {
    init {
        require(tag.isStableSFNTTableTag()) {
            "SFNT table evidence tag must be a four-character printable ASCII SFNT tag."
        }
        require(checksum.matches(SFNT_HEX_UINT32_PATTERN)) {
            "SFNT table evidence checksum must be lowercase hexadecimal uint32 text."
        }
        require(offset >= 0L) { "SFNT table evidence offset must be non-negative." }
        require(length >= 0L) { "SFNT table evidence length must be non-negative." }
        require(rawByteLength == null || rawByteLength >= 0) {
            "SFNT table evidence rawByteLength must be non-negative when present."
        }
        require(rawSha256 == null || rawSha256.matches(SFNT_SHA256_PATTERN)) {
            "SFNT table evidence rawSha256 must be lowercase hexadecimal SHA-256 text when present."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("tag", tag, indent = "  ", comma = true)
        appendSFNTJsonField("checksum", checksum, indent = "  ", comma = true)
        appendSFNTJsonField("offset", offset, indent = "  ", comma = true)
        appendSFNTJsonField("length", this@SFNTTableEvidence.length, indent = "  ", comma = true)
        appendSFNTJsonNullableField("rawByteLength", rawByteLength, indent = "  ", comma = true)
        appendSFNTJsonNullableField("rawSha256", rawSha256, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Preferred parsed `cmap` subtable facts for face evidence.
 */
data class OpenTypeCMapEvidence(
    val platformId: Int,
    val encodingId: Int,
    val format: Int,
    val offset: Int,
    val length: Int,
    val mappingKind: String,
    val mappingEntryCount: Int,
    val encodingRecordCount: Int,
    val parsedSubtableCount: Int,
) {
    init {
        require(platformId >= 0) { "OpenType cmap evidence platformId must be non-negative." }
        require(encodingId >= 0) { "OpenType cmap evidence encodingId must be non-negative." }
        require(format >= 0) { "OpenType cmap evidence format must be non-negative." }
        require(offset >= 0) { "OpenType cmap evidence offset must be non-negative." }
        require(length >= 0) { "OpenType cmap evidence length must be non-negative." }
        require(mappingEntryCount >= 0) { "OpenType cmap evidence mappingEntryCount must be non-negative." }
        require(encodingRecordCount >= 0) { "OpenType cmap evidence encodingRecordCount must be non-negative." }
        require(parsedSubtableCount >= 0) { "OpenType cmap evidence parsedSubtableCount must be non-negative." }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("platformId", platformId, indent = "  ", comma = true)
        appendSFNTJsonField("encodingId", encodingId, indent = "  ", comma = true)
        appendSFNTJsonField("format", format, indent = "  ", comma = true)
        appendSFNTJsonField("offset", offset, indent = "  ", comma = true)
        appendSFNTJsonField("length", this@OpenTypeCMapEvidence.length, indent = "  ", comma = true)
        appendSFNTJsonField("mappingKind", mappingKind, indent = "  ", comma = true)
        appendSFNTJsonField("mappingEntryCount", mappingEntryCount, indent = "  ", comma = true)
        appendSFNTJsonField("encodingRecordCount", encodingRecordCount, indent = "  ", comma = true)
        appendSFNTJsonField("parsedSubtableCount", parsedSubtableCount, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Parsed metric summary for face evidence.
 */
data class OpenTypeMetricsEvidence(
    val unitsPerEm: Int?,
    val ascender: Int?,
    val descender: Int?,
    val lineGap: Int?,
    val numGlyphs: Int?,
    val numberOfHMetrics: Int?,
    val horizontalMetricCount: Int,
    val indexToLocFormat: Int?,
    val bounds: OpenTypeBoundsEvidence?,
) {
    init {
        require(horizontalMetricCount >= 0) {
            "OpenType metrics evidence horizontalMetricCount must be non-negative."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonNullableField("unitsPerEm", unitsPerEm, indent = "  ", comma = true)
        appendSFNTJsonNullableField("ascender", ascender, indent = "  ", comma = true)
        appendSFNTJsonNullableField("descender", descender, indent = "  ", comma = true)
        appendSFNTJsonNullableField("lineGap", lineGap, indent = "  ", comma = true)
        appendSFNTJsonNullableField("numGlyphs", numGlyphs, indent = "  ", comma = true)
        appendSFNTJsonNullableField("numberOfHMetrics", numberOfHMetrics, indent = "  ", comma = true)
        appendSFNTJsonField("horizontalMetricCount", horizontalMetricCount, indent = "  ", comma = true)
        appendSFNTJsonNullableField("indexToLocFormat", indexToLocFormat, indent = "  ", comma = true)
        append("  \"bounds\": ")
        append(bounds?.toCanonicalJson()?.prependIndent("  ")?.trimStart() ?: "null")
        append("\n")
        append("}")
    }
}

/**
 * Parsed global font bounds for face evidence.
 */
data class OpenTypeBoundsEvidence(
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
) {
    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonField("xMin", xMin, indent = "  ", comma = true)
        appendSFNTJsonField("yMin", yMin, indent = "  ", comma = true)
        appendSFNTJsonField("xMax", xMax, indent = "  ", comma = true)
        appendSFNTJsonField("yMax", yMax, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Dumpable non-fatal parse diagnostic facts for face evidence.
 */
data class OpenTypeParseDiagnosticEvidence(
    val table: SFNTTableTag?,
    val causeCode: String?,
    val message: String,
    val causeMessage: String?,
) {
    init {
        require(table == null || table.value.isStableSFNTTableTag()) {
            "OpenType parse diagnostic evidence table must be a four-character printable ASCII SFNT tag."
        }
        require(causeCode == null || causeCode.isStableSFNTDiagnosticToken()) {
            "OpenType parse diagnostic evidence causeCode must be a stable one-line diagnostic token."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendSFNTJsonNullableField("table", table?.value, indent = "  ", comma = true)
        appendSFNTJsonNullableField("causeCode", causeCode, indent = "  ", comma = true)
        appendSFNTJsonField("message", message, indent = "  ", comma = true)
        appendSFNTJsonNullableField("causeMessage", causeMessage, indent = "  ", comma = false)
        append("}")
    }
}

/**
 * Builds deterministic evidence for this parsed face.
 *
 * @param requiredTables Required table tags for bounded table-directory diagnostics.
 * @return selected face, identity, table, preferred `cmap`, metric, and diagnostic facts.
 */
fun OpenTypeFaceData.faceEvidence(
    requiredTables: Set<SFNTTableTag> = emptySet(),
): OpenTypeFaceEvidence =
    OpenTypeFaceEvidence(
        faceIndex = faceIndex,
        sourceId = source.id,
        typefaceId = id,
        sourceKind = source.kind,
        scalerType = directory.scalerType.toSFNTUInt32Hex(),
        scalerTypeLabel = directory.scalerType.toSFNTScalerTypeLabel(),
        tableRecords = directory.tables
            .map { record ->
                val rawBytes = rawTables[record.tag]?.toSFNTRawTableByteArray(record.tag)
                SFNTTableEvidence(
                    tag = record.tag.value,
                    checksum = record.checksum.toSFNTUInt32Hex(),
                    offset = record.offset.toLong(),
                    length = record.length.toLong(),
                    rawByteLength = rawBytes?.size,
                    rawSha256 = rawBytes?.sfntSha256Hex(),
                )
            }
            .sortedWith(SFNT_TABLE_EVIDENCE_ORDER),
        directoryDiagnostics = SFNTTableDirectoryValidator.validate(
            directory = directory,
            sourceLength = source.bytes.size.toLong(),
            requiredTables = requiredTables,
        ),
        preferredCMap = cmap.preferredSubtable?.toEvidence(cmap),
        metrics = metrics.toEvidence(),
        diagnostics = diagnostics
            .map(OpenTypeParseDiagnostic::toEvidence)
            .sortedWith(SFNT_DIAGNOSTIC_EVIDENCE_ORDER),
    )

/**
 * Parsed `cmap` table container for Unicode to glyph ID mappings.
 *
 * @property subtables Compatibility map of raw parsed subtable bytes keyed as
 * `platform:encoding:format`.
 * @property version Raw `cmap` table version from the header.
 * @property encodingRecords Encoding records in table order.
 * @property mappings Parsed format-specific mappings in table order.
 * @property preferredSubtable Preferred Unicode subtable used for lookups.
 * @property variationSubtable Parsed format 14 variation-selector subtable, when present.
 * @property diagnostics Non-fatal `cmap` support/refusal diagnostics.
 */
data class CMapTable(
    val subtables: Map<String, List<Int>> = emptyMap(),
    val version: Int = 0,
    val encodingRecords: List<CMapEncodingRecord> = emptyList(),
    val mappings: List<CMapSubtable> = emptyList(),
    val preferredSubtable: CMapSubtable? = mappings.preferredUnicodeCMapSubtable(),
    val variationSubtable: CMapSubtable? = mappings.firstOrNull { it.mapping is CMapFormat14Mapping },
    val diagnostics: List<CMapDiagnostic> = emptyList(),
) {
    /**
     * Returns the glyph ID for [codePoint] from the preferred Unicode subtable.
     *
     * Missing code points return stable glyph ID `0`. When [variationSelector]
     * is supplied and a format 14 subtable contains a non-default mapping, the
     * explicit variation glyph ID wins; default variation ranges preserve the
     * base mapping semantics.
     *
     * @throws IllegalArgumentException when [codePoint] is outside the Unicode scalar range.
     */
    fun lookupGlyphId(codePoint: Int, variationSelector: Int? = null): Int? {
        require(codePoint in UNICODE_CODE_POINT_RANGE) {
            "Unicode code point $codePoint is outside range 0..0x10ffff."
        }
        require(variationSelector == null || variationSelector in UNICODE_CODE_POINT_RANGE) {
            "Unicode variation selector $variationSelector is outside range 0..0x10ffff."
        }

        val baseGlyphId = preferredSubtable?.lookupGlyphId(codePoint) ?: 0
        if (variationSelector == null) {
            return baseGlyphId
        }

        val variationGlyphId = variationSubtable
            ?.lookupVariationGlyphId(
                codePoint = codePoint,
                variationSelector = variationSelector,
                baseGlyphId = baseGlyphId,
            )
        return variationGlyphId ?: baseGlyphId
    }
}

/**
 * Header encoding record from an OpenType `cmap` table.
 *
 * @property platformId OpenType platform identifier.
 * @property encodingId OpenType platform-specific encoding identifier.
 * @property offset Byte offset of the subtable from the start of the `cmap` table.
 * @property format Parsed subtable format when the offset points at a readable subtable.
 */
data class CMapEncodingRecord(
    val platformId: Int,
    val encodingId: Int,
    val offset: Int,
    val format: Int? = null,
)

/**
 * Non-fatal diagnostic produced while parsing an OpenType `cmap` table.
 *
 * @property code Stable diagnostic code from the pure Kotlin text taxonomy.
 * @property format `cmap` format associated with the diagnostic, when known.
 * @property platformId Encoding record platform ID associated with the diagnostic.
 * @property encodingId Encoding record encoding ID associated with the diagnostic.
 * @property offset Byte offset of the subtable from the start of the `cmap` table.
 * @property message Deterministic human-readable detail.
 */
data class CMapDiagnostic(
    val code: String,
    val format: Int?,
    val platformId: Int?,
    val encodingId: Int?,
    val offset: Int?,
    val message: String,
) {
    init {
        require(code.startsWith("font.") && code.isStableSFNTDiagnosticToken()) {
            "OpenType cmap diagnostic code must be a stable font.* diagnostic token."
        }
        require(format == null || format >= 0) { "OpenType cmap diagnostic format must be non-negative." }
        require(platformId == null || platformId >= 0) { "OpenType cmap diagnostic platformId must be non-negative." }
        require(encodingId == null || encodingId >= 0) { "OpenType cmap diagnostic encodingId must be non-negative." }
        require(offset == null || offset >= 0) { "OpenType cmap diagnostic offset must be non-negative." }
    }

    /**
     * Serializes the diagnostic as one stable line for `cmap` evidence.
     */
    fun dump(): String = buildString {
        append(code)
        append(" format=")
        append(format ?: "none")
        append(" platformId=")
        append(platformId ?: "none")
        append(" encodingId=")
        append(encodingId ?: "none")
        append(" offset=")
        append(offset ?: "none")
        append(" message=")
        append(message.sfntEvidenceQuoted())
    }
}

/**
 * Parsed OpenType `cmap` subtable with its platform metadata and mapping body.
 *
 * @property platformId OpenType platform identifier from the encoding record.
 * @property encodingId OpenType encoding identifier from the encoding record.
 * @property offset Byte offset of the subtable from the start of the `cmap` table.
 * @property length Declared byte length of the subtable.
 * @property format OpenType `cmap` subtable format.
 * @property mapping Format-specific mapping implementation.
 */
data class CMapSubtable(
    val platformId: Int,
    val encodingId: Int,
    val offset: Int,
    val length: Int,
    val format: Int,
    val mapping: CMapMapping,
) {
    /**
     * Returns the glyph ID for [codePoint], or `null` when this subtable does not map it.
     *
     * @throws IllegalArgumentException when [codePoint] is outside the Unicode scalar range.
     */
    fun lookupGlyphId(codePoint: Int): Int? {
        require(codePoint in UNICODE_CODE_POINT_RANGE) {
            "Unicode code point $codePoint is outside range 0..0x10ffff."
        }
        return mapping.lookupGlyphId(codePoint)
    }
}

/**
 * Format-specific Unicode code point to glyph ID mapping.
 */
sealed interface CMapMapping {
    /**
     * Returns the glyph ID for [codePoint], or `null` when the code point is unmapped.
     */
    fun lookupGlyphId(codePoint: Int): Int?
}

/**
 * Character-code interpretation used by legacy byte-oriented `cmap` subtables.
 *
 * Modern Unicode subtables use [DIRECT], where the Unicode scalar value is also the cmap character
 * code. Macintosh Roman subtables use [MAC_ROMAN], where Unicode scalars must first be translated
 * to their MacRoman byte code before the glyph array can be indexed.
 */
enum class CMapCharacterEncoding {
    /**
     * Treats the Unicode code point as the cmap character code.
     */
    DIRECT,

    /**
     * Translates Unicode scalars through the classic Macintosh Roman byte encoding.
     */
    MAC_ROMAN,
}

/**
 * Parsed OpenType `cmap` format 0 byte-encoding mapping.
 *
 * Format 0 maps exactly the first 256 character codes through a fixed byte-sized glyph ID array.
 * Glyph ID `0` is treated as a missing mapping, matching the other Kanvas `cmap` mappings.
 *
 * @property glyphIds Fixed 256-entry glyph ID array in character-code order.
 * @property characterEncoding Character-code interpretation used before indexing [glyphIds].
 */
data class CMapFormat0Mapping(
    val glyphIds: List<Int>,
    val characterEncoding: CMapCharacterEncoding = CMapCharacterEncoding.DIRECT,
) : CMapMapping {
    init {
        require(glyphIds.size == FORMAT0_GLYPH_COUNT) {
            "OpenType cmap format 0 mapping must contain exactly $FORMAT0_GLYPH_COUNT glyph IDs."
        }
        require(glyphIds.all { it in 0..UINT8_MAX }) {
            "OpenType cmap format 0 glyph IDs must be unsigned 8-bit values."
        }
    }

    /**
     * Returns the glyph ID for an 8-bit character code, or `null` for glyph `0`/out-of-range codes.
     */
    override fun lookupGlyphId(codePoint: Int): Int? {
        val characterCode = characterEncoding.characterCodeFor(codePoint) ?: return null
        return if (characterCode in 0 until FORMAT0_GLYPH_COUNT) {
            glyphIds[characterCode].takeIf { it != 0 }
        } else {
            null
        }
    }
}

/**
 * Parsed OpenType `cmap` format 6 trimmed-table mapping.
 *
 * Format 6 stores a contiguous BMP code-point range beginning at [firstCode]. The mapping is
 * useful for legacy Unicode and Macintosh fonts that do not expose format 4/12 subtables.
 *
 * @property firstCode First BMP code point covered by [glyphIds].
 * @property glyphIds Glyph IDs for the contiguous range, in character-code order.
 * @property characterEncoding Character-code interpretation used before applying [firstCode].
 */
data class CMapFormat6Mapping(
    val firstCode: Int,
    val glyphIds: List<Int>,
    val characterEncoding: CMapCharacterEncoding = CMapCharacterEncoding.DIRECT,
) : CMapMapping {
    init {
        require(firstCode in 0..BMP_CODE_POINT_MAX) {
            "OpenType cmap format 6 firstCode $firstCode is outside BMP range 0..0xffff."
        }
        require(glyphIds.size <= UINT16_MAX + 1) {
            "OpenType cmap format 6 glyph ID array is too large: ${glyphIds.size}."
        }
        require(firstCode.toLong() + glyphIds.size <= BMP_CODE_POINT_MAX.toLong() + 1L) {
            "OpenType cmap format 6 range starting at $firstCode exceeds BMP range 0..0xffff."
        }
        require(glyphIds.all { it in 0..UINT16_MAX }) {
            "OpenType cmap format 6 glyph IDs must be unsigned 16-bit values."
        }
    }

    /**
     * Returns the glyph ID for a code point in the trimmed range, or `null` when unmapped.
     */
    override fun lookupGlyphId(codePoint: Int): Int? {
        val characterCode = characterEncoding.characterCodeFor(codePoint) ?: return null
        val index = characterCode - firstCode
        return glyphIds.getOrNull(index)?.takeIf { it != 0 }
    }
}

/**
 * Parsed OpenType `cmap` format 4 BMP mapping.
 *
 * @property segments Segment records in subtable order.
 */
data class CMapFormat4Mapping(
    val segments: List<CMapFormat4Segment>,
) : CMapMapping {
    /**
     * Returns the BMP glyph ID for [codePoint], or `null` when no segment maps it.
     */
    override fun lookupGlyphId(codePoint: Int): Int? {
        if (codePoint !in 0..BMP_CODE_POINT_MAX) {
            return null
        }

        val segment = segments.firstOrNull { codePoint in it.startCode..it.endCode } ?: return null
        return segment.lookupGlyphId(codePoint)
    }
}

/**
 * Parsed OpenType `cmap` format 4 segment.
 *
 * @property startCode First BMP code point in the segment.
 * @property endCode Last BMP code point in the segment.
 * @property idDelta Signed delta applied by the segment.
 * @property idRangeOffset Raw range offset from the segment's `idRangeOffset` field.
 * @property glyphIds Resolved glyph IDs for range-offset segments, empty when `idRangeOffset` is zero.
 */
data class CMapFormat4Segment(
    val startCode: Int,
    val endCode: Int,
    val idDelta: Int,
    val idRangeOffset: Int,
    val glyphIds: List<Int> = emptyList(),
) {
    /**
     * Returns the glyph ID for [codePoint], or `null` when the code point is outside this segment.
     */
    fun lookupGlyphId(codePoint: Int): Int? {
        if (codePoint !in startCode..endCode) {
            return null
        }
        if (idRangeOffset == 0) {
            return ((codePoint + idDelta) and UINT16_MAX).takeIf { it != 0 }
        }

        return glyphIds.getOrNull(codePoint - startCode)?.takeIf { it != 0 }
    }
}

/**
 * Parsed OpenType `cmap` format 12 segmented coverage mapping.
 *
 * @property groups Sequential map groups in subtable order.
 */
data class CMapFormat12Mapping(
    val groups: List<CMapFormat12Group>,
) : CMapMapping {
    /**
     * Returns the glyph ID for [codePoint], or `null` when no group maps it.
     */
    override fun lookupGlyphId(codePoint: Int): Int? {
        val group = groups.firstOrNull { codePoint in it.startCharCode..it.endCharCode } ?: return null
        return (group.startGlyphId + (codePoint - group.startCharCode)).takeIf { it != 0 }
    }
}

/**
 * Parsed OpenType `cmap` format 12 group.
 *
 * @property startCharCode First Unicode code point in the group.
 * @property endCharCode Last Unicode code point in the group.
 * @property startGlyphId Glyph ID corresponding to [startCharCode].
 */
data class CMapFormat12Group(
    val startCharCode: Int,
    val endCharCode: Int,
    val startGlyphId: Int,
)

/**
 * Parsed OpenType `cmap` format 14 variation-selector mapping.
 *
 * @property records Variation selector records in subtable order.
 */
data class CMapFormat14Mapping(
    val records: List<CMapVariationSelectorRecord>,
) : CMapMapping {
    override fun lookupGlyphId(codePoint: Int): Int? = null

    /**
     * Returns the variation glyph ID for a Unicode variation sequence.
     *
     * Non-default mappings return their explicit glyph ID. Default variation
     * ranges return the supplied base glyph ID, preserving base cmap semantics.
     * A missing variation sequence returns `null` so callers can apply their
     * fallback policy.
     */
    fun lookupGlyphId(
        codePoint: Int,
        variationSelector: Int,
        baseGlyphId: Int,
    ): Int? {
        val record = records.firstOrNull { it.variationSelector == variationSelector } ?: return null
        val explicitGlyphId = record.nonDefaultMappings[codePoint]
        if (explicitGlyphId != null) {
            return explicitGlyphId.takeIf { it != 0 } ?: 0
        }
        if (record.defaultRanges.any { codePoint in it.startCodePoint..it.endCodePoint }) {
            return baseGlyphId
        }
        return null
    }
}

/**
 * One format 14 variation selector record.
 *
 * @property variationSelector Unicode variation selector scalar value.
 * @property defaultRanges Code point ranges that keep the base `cmap` glyph.
 * @property nonDefaultMappings Explicit Unicode variation sequence glyph IDs.
 */
data class CMapVariationSelectorRecord(
    val variationSelector: Int,
    val defaultRanges: List<CMapUnicodeRange> = emptyList(),
    val nonDefaultMappings: Map<Int, Int> = emptyMap(),
)

/**
 * Inclusive Unicode range from a format 14 default UVS table.
 */
data class CMapUnicodeRange(
    val startCodePoint: Int,
    val endCodePoint: Int,
)

/**
 * Parser for raw OpenType `cmap` table bytes.
 */
object OpenTypeCMapTableParser {
    /**
     * Parses raw OpenType `cmap` bytes into a typed [CMapTable].
     *
     * Supported mapping bodies are formats 0, 4, 6, 12, and 14. Unsupported formats
     * remain visible through [CMapTable.encodingRecords] but are not added to
     * [CMapTable.mappings].
     *
     * @throws IllegalArgumentException when the header, records, subtable offsets, or mapping ranges are invalid.
     */
    fun parse(table: ByteArray): CMapTable {
        require(table.size >= CMAP_HEADER_SIZE) {
            "OpenType cmap table must contain at least $CMAP_HEADER_SIZE bytes for the header."
        }

        val version = table.readUInt16BE(0, "OpenType cmap header version")
        val recordCount = table.readUInt16BE(2, "OpenType cmap header numTables")
        val recordsEnd = CMAP_HEADER_SIZE + recordCount * CMAP_ENCODING_RECORD_SIZE
        require(recordsEnd <= table.size) {
            "OpenType cmap encoding record array ends at $recordsEnd but table length is ${table.size}."
        }

        val encodingRecords = List(recordCount) { index ->
            val recordOffset = CMAP_HEADER_SIZE + index * CMAP_ENCODING_RECORD_SIZE
            val platformId = table.readUInt16BE(recordOffset, "OpenType cmap encoding record $index platformID")
            val encodingId = table.readUInt16BE(recordOffset + 2, "OpenType cmap encoding record $index encodingID")
            val subtableOffset = table.readUInt32BE(recordOffset + 4, "OpenType cmap encoding record $index offset")
            require(subtableOffset <= Int.MAX_VALUE && subtableOffset + UINT16_BYTE_LENGTH <= table.size) {
                "OpenType cmap encoding record $index subtable offset $subtableOffset must allow a format field within table length ${table.size}."
            }
            val format = table.readUInt16BE(subtableOffset.toInt(), "OpenType cmap encoding record $index format")
            CMapEncodingRecord(
                platformId = platformId,
                encodingId = encodingId,
                offset = subtableOffset.toInt(),
                format = format,
            )
        }

        val diagnostics = mutableListOf<CMapDiagnostic>()
        val mappings = encodingRecords.mapIndexedNotNull { index, record ->
            parseSubtable(
                table = table,
                recordIndex = index,
                record = record,
                diagnostics = diagnostics,
            )
        }
        val legacySubtables = mappings.associate { subtable ->
            "${subtable.platformId}:${subtable.encodingId}:${subtable.format}" to
                table.unsignedBytes(subtable.offset, subtable.offset + subtable.length)
        }
        val preferredSubtable = mappings.preferredUnicodeCMapSubtable()
        if (preferredSubtable == null) {
            diagnostics += CMapDiagnostic(
                code = "font.sfnt.cmap-unusable",
                format = null,
                platformId = null,
                encodingId = null,
                offset = null,
                message = "No usable Unicode cmap subtable was parsed.",
            )
        }

        return CMapTable(
            subtables = legacySubtables,
            version = version,
            encodingRecords = encodingRecords,
            mappings = mappings,
            preferredSubtable = preferredSubtable,
            variationSubtable = mappings.firstOrNull { it.mapping is CMapFormat14Mapping },
            diagnostics = diagnostics,
        )
    }

    private fun parseSubtable(
        table: ByteArray,
        recordIndex: Int,
        record: CMapEncodingRecord,
        diagnostics: MutableList<CMapDiagnostic>,
    ): CMapSubtable? =
        when (record.format) {
            0 -> parseFormat0(table, recordIndex, record)
            6 -> parseFormat6(table, recordIndex, record)
            4 -> parseFormat4(table, recordIndex, record)
            12 -> parseFormat12(table, recordIndex, record)
            14 -> parseFormat14(table, recordIndex, record)
            else -> {
                diagnostics += CMapDiagnostic(
                    code = "font.sfnt.cmap-format-unsupported",
                    format = record.format,
                    platformId = record.platformId,
                    encodingId = record.encodingId,
                    offset = record.offset,
                    message = "Unsupported cmap format ${record.format} is not selected for Unicode lookup.",
                )
                null
            }
        }

    private fun parseFormat0(
        table: ByteArray,
        recordIndex: Int,
        record: CMapEncodingRecord,
    ): CMapSubtable {
        val offset = record.offset
        table.requireRange(offset, FORMAT0_LENGTH, "OpenType cmap format 0 subtable for record $recordIndex")
        val length = table.readUInt16BE(offset + 2, "OpenType cmap format 0 length for record $recordIndex")
        require(length == FORMAT0_LENGTH) {
            "OpenType cmap format 0 record $recordIndex length $length must be $FORMAT0_LENGTH."
        }
        val glyphIds = List(FORMAT0_GLYPH_COUNT) { glyphIndex ->
            table.readUInt8(
                offset + FORMAT0_HEADER_SIZE + glyphIndex,
                "OpenType cmap format 0 glyphIdArray[$glyphIndex] for record $recordIndex",
            )
        }

        return CMapSubtable(
            platformId = record.platformId,
            encodingId = record.encodingId,
            offset = offset,
            length = length,
            format = 0,
            mapping = CMapFormat0Mapping(
                glyphIds = glyphIds,
                characterEncoding = record.characterEncoding(),
            ),
        )
    }

    private fun parseFormat6(
        table: ByteArray,
        recordIndex: Int,
        record: CMapEncodingRecord,
    ): CMapSubtable {
        val offset = record.offset
        table.requireRange(offset, FORMAT6_HEADER_SIZE, "OpenType cmap format 6 header for record $recordIndex")
        val length = table.readUInt16BE(offset + 2, "OpenType cmap format 6 length for record $recordIndex")
        require(length >= FORMAT6_HEADER_SIZE) {
            "OpenType cmap format 6 record $recordIndex length $length is shorter than header size $FORMAT6_HEADER_SIZE."
        }
        table.requireRange(offset, length, "OpenType cmap format 6 subtable for record $recordIndex")
        val firstCode = table.readUInt16BE(offset + 6, "OpenType cmap format 6 firstCode for record $recordIndex")
        val entryCount = table.readUInt16BE(offset + 8, "OpenType cmap format 6 entryCount for record $recordIndex")
        val expectedLength = FORMAT6_HEADER_SIZE + entryCount * UINT16_BYTE_LENGTH
        require(length == expectedLength) {
            "OpenType cmap format 6 record $recordIndex length $length must match entryCount payload size $expectedLength."
        }
        require(firstCode.toLong() + entryCount <= BMP_CODE_POINT_MAX.toLong() + 1L) {
            "OpenType cmap format 6 record $recordIndex range starting at $firstCode with $entryCount entries exceeds BMP range 0..0xffff."
        }
        val glyphIds = List(entryCount) { glyphIndex ->
            table.readUInt16BE(
                offset + FORMAT6_HEADER_SIZE + glyphIndex * UINT16_BYTE_LENGTH,
                "OpenType cmap format 6 glyphIdArray[$glyphIndex] for record $recordIndex",
            )
        }

        return CMapSubtable(
            platformId = record.platformId,
            encodingId = record.encodingId,
            offset = offset,
            length = length,
            format = 6,
            mapping = CMapFormat6Mapping(
                firstCode = firstCode,
                glyphIds = glyphIds,
                characterEncoding = record.characterEncoding(),
            ),
        )
    }

    private fun parseFormat4(
        table: ByteArray,
        recordIndex: Int,
        record: CMapEncodingRecord,
    ): CMapSubtable {
        val offset = record.offset
        table.requireRange(offset, FORMAT4_HEADER_SIZE, "OpenType cmap format 4 header for record $recordIndex")
        val length = table.readUInt16BE(offset + 2, "OpenType cmap format 4 length for record $recordIndex")
        require(length >= FORMAT4_HEADER_SIZE) {
            "OpenType cmap format 4 record $recordIndex length $length is shorter than header size $FORMAT4_HEADER_SIZE."
        }
        table.requireRange(offset, length, "OpenType cmap format 4 subtable for record $recordIndex")

        val segCountX2 = table.readUInt16BE(offset + 6, "OpenType cmap format 4 segCountX2 for record $recordIndex")
        require(segCountX2 > 0 && segCountX2 % 2 == 0) {
            "OpenType cmap format 4 record $recordIndex segCountX2 must be positive and even, was $segCountX2."
        }
        val segCount = segCountX2 / 2
        val endCodeOffset = offset + 14
        val reservedPadOffset = endCodeOffset + segCount * UINT16_BYTE_LENGTH
        val startCodeOffset = reservedPadOffset + UINT16_BYTE_LENGTH
        val idDeltaOffset = startCodeOffset + segCount * UINT16_BYTE_LENGTH
        val idRangeOffsetOffset = idDeltaOffset + segCount * UINT16_BYTE_LENGTH
        val glyphIdArrayOffset = idRangeOffsetOffset + segCount * UINT16_BYTE_LENGTH
        val subtableEnd = offset + length

        require(glyphIdArrayOffset <= subtableEnd) {
            "OpenType cmap format 4 arrays for record $recordIndex end at $glyphIdArrayOffset but subtable ends at $subtableEnd."
        }

        val segments = List(segCount) { segmentIndex ->
            val endCode = table.readUInt16BE(
                endCodeOffset + segmentIndex * UINT16_BYTE_LENGTH,
                "OpenType cmap format 4 endCode[$segmentIndex] for record $recordIndex",
            )
            val startCode = table.readUInt16BE(
                startCodeOffset + segmentIndex * UINT16_BYTE_LENGTH,
                "OpenType cmap format 4 startCode[$segmentIndex] for record $recordIndex",
            )
            val idDelta = table.readInt16BE(
                idDeltaOffset + segmentIndex * UINT16_BYTE_LENGTH,
                "OpenType cmap format 4 idDelta[$segmentIndex] for record $recordIndex",
            )
            val idRangeOffset = table.readUInt16BE(
                idRangeOffsetOffset + segmentIndex * UINT16_BYTE_LENGTH,
                "OpenType cmap format 4 idRangeOffset[$segmentIndex] for record $recordIndex",
            )
            require(startCode <= endCode) {
                "OpenType cmap format 4 segment $segmentIndex startCode $startCode exceeds endCode $endCode."
            }

            val glyphIds = if (idRangeOffset == 0) {
                emptyList()
            } else {
                val glyphStart = idRangeOffsetOffset.toLong() +
                    segmentIndex.toLong() * UINT16_BYTE_LENGTH +
                    idRangeOffset
                val glyphEnd = glyphStart + (endCode - startCode + 1).toLong() * UINT16_BYTE_LENGTH
                require(glyphStart >= glyphIdArrayOffset.toLong() && glyphEnd <= subtableEnd.toLong()) {
                    "OpenType cmap format 4 segment $segmentIndex glyph range [$glyphStart, $glyphEnd) exceeds subtable range [$glyphIdArrayOffset, $subtableEnd)."
                }
                List(endCode - startCode + 1) { glyphIndex ->
                    val rawGlyphId = table.readUInt16BE(
                        (glyphStart + glyphIndex.toLong() * UINT16_BYTE_LENGTH).toInt(),
                        "OpenType cmap format 4 glyphIdArray[$glyphIndex] for segment $segmentIndex",
                    )
                    if (rawGlyphId == 0) {
                        0
                    } else {
                        (rawGlyphId + idDelta) and UINT16_MAX
                    }
                }
            }

            CMapFormat4Segment(
                startCode = startCode,
                endCode = endCode,
                idDelta = idDelta,
                idRangeOffset = idRangeOffset,
                glyphIds = glyphIds,
            )
        }

        return CMapSubtable(
            platformId = record.platformId,
            encodingId = record.encodingId,
            offset = offset,
            length = length,
            format = 4,
            mapping = CMapFormat4Mapping(segments),
        )
    }

    private fun parseFormat12(
        table: ByteArray,
        recordIndex: Int,
        record: CMapEncodingRecord,
    ): CMapSubtable {
        val offset = record.offset
        table.requireRange(offset, FORMAT12_HEADER_SIZE, "OpenType cmap format 12 header for record $recordIndex")
        val length = table.readUInt32BE(offset + 4, "OpenType cmap format 12 length for record $recordIndex")
        require(length <= Int.MAX_VALUE && length >= FORMAT12_HEADER_SIZE) {
            "OpenType cmap format 12 record $recordIndex length $length must be between $FORMAT12_HEADER_SIZE and ${Int.MAX_VALUE}."
        }
        table.requireRange(offset, length.toInt(), "OpenType cmap format 12 subtable for record $recordIndex")

        val groupCount = table.readUInt32BE(offset + 12, "OpenType cmap format 12 nGroups for record $recordIndex")
        require(groupCount <= Int.MAX_VALUE) {
            "OpenType cmap format 12 record $recordIndex group count $groupCount exceeds ${Int.MAX_VALUE}."
        }
        val groupsEnd = FORMAT12_HEADER_SIZE.toLong() + groupCount * FORMAT12_GROUP_SIZE
        require(groupsEnd <= length) {
            "OpenType cmap format 12 group array for record $recordIndex ends at $groupsEnd but declared length is $length."
        }

        val groups = List(groupCount.toInt()) { groupIndex ->
            val groupOffset = offset + FORMAT12_HEADER_SIZE + groupIndex * FORMAT12_GROUP_SIZE
            val startCharCode = table.readUInt32BE(groupOffset, "OpenType cmap format 12 startCharCode[$groupIndex]")
            val endCharCode = table.readUInt32BE(groupOffset + 4, "OpenType cmap format 12 endCharCode[$groupIndex]")
            val startGlyphId = table.readUInt32BE(groupOffset + 8, "OpenType cmap format 12 startGlyphID[$groupIndex]")

            require(startCharCode <= UNICODE_CODE_POINT_MAX && endCharCode <= UNICODE_CODE_POINT_MAX) {
                "OpenType cmap format 12 group $groupIndex code point range [$startCharCode, $endCharCode] exceeds 0x10ffff."
            }
            require(startCharCode <= endCharCode) {
                "OpenType cmap format 12 group $groupIndex startCharCode $startCharCode exceeds endCharCode $endCharCode."
            }
            val glyphEnd = startGlyphId + (endCharCode - startCharCode)
            require(glyphEnd <= Int.MAX_VALUE) {
                "OpenType cmap format 12 group $groupIndex glyph ID range ending at $glyphEnd exceeds ${Int.MAX_VALUE}."
            }

            CMapFormat12Group(
                startCharCode = startCharCode.toInt(),
                endCharCode = endCharCode.toInt(),
                startGlyphId = startGlyphId.toInt(),
            )
        }

        return CMapSubtable(
            platformId = record.platformId,
            encodingId = record.encodingId,
            offset = offset,
            length = length.toInt(),
            format = 12,
            mapping = CMapFormat12Mapping(groups),
        )
    }

    private fun parseFormat14(
        table: ByteArray,
        recordIndex: Int,
        record: CMapEncodingRecord,
    ): CMapSubtable {
        val offset = record.offset
        table.requireRange(offset, FORMAT14_HEADER_SIZE, "OpenType cmap format 14 header for record $recordIndex")
        val length = table.readUInt32BE(offset + 2, "OpenType cmap format 14 length for record $recordIndex")
        require(length <= Int.MAX_VALUE && length >= FORMAT14_HEADER_SIZE) {
            "OpenType cmap format 14 record $recordIndex length $length must be between $FORMAT14_HEADER_SIZE and ${Int.MAX_VALUE}."
        }
        table.requireRange(offset, length.toInt(), "OpenType cmap format 14 subtable for record $recordIndex")

        val recordCount = table.readUInt32BE(offset + 6, "OpenType cmap format 14 numVarSelectorRecords for record $recordIndex")
        require(recordCount <= Int.MAX_VALUE) {
            "OpenType cmap format 14 record $recordIndex selector count $recordCount exceeds ${Int.MAX_VALUE}."
        }
        val recordsEnd = FORMAT14_HEADER_SIZE.toLong() + recordCount * FORMAT14_SELECTOR_RECORD_SIZE
        require(recordsEnd <= length) {
            "OpenType cmap format 14 selector records for record $recordIndex end at $recordsEnd but declared length is $length."
        }

        val records = List(recordCount.toInt()) { selectorIndex ->
            val selectorOffset = offset + FORMAT14_HEADER_SIZE + selectorIndex * FORMAT14_SELECTOR_RECORD_SIZE
            val variationSelector = table.readUInt24BE(
                selectorOffset,
                "OpenType cmap format 14 varSelector[$selectorIndex] for record $recordIndex",
            )
            require(variationSelector in UNICODE_CODE_POINT_RANGE) {
                "OpenType cmap format 14 varSelector[$selectorIndex] $variationSelector exceeds 0x10ffff."
            }
            val defaultUVSOffset = table.readUInt32BE(
                selectorOffset + FORMAT14_UINT24_BYTE_LENGTH,
                "OpenType cmap format 14 defaultUVSOffset[$selectorIndex] for record $recordIndex",
            )
            val nonDefaultUVSOffset = table.readUInt32BE(
                selectorOffset + FORMAT14_UINT24_BYTE_LENGTH + UINT32_BYTE_LENGTH,
                "OpenType cmap format 14 nonDefaultUVSOffset[$selectorIndex] for record $recordIndex",
            )
            CMapVariationSelectorRecord(
                variationSelector = variationSelector,
                defaultRanges = parseFormat14DefaultRanges(
                    table = table,
                    subtableOffset = offset,
                    subtableLength = length.toInt(),
                    defaultUVSOffset = defaultUVSOffset,
                    recordIndex = recordIndex,
                    selectorIndex = selectorIndex,
                ),
                nonDefaultMappings = parseFormat14NonDefaultMappings(
                    table = table,
                    subtableOffset = offset,
                    subtableLength = length.toInt(),
                    nonDefaultUVSOffset = nonDefaultUVSOffset,
                    recordIndex = recordIndex,
                    selectorIndex = selectorIndex,
                ),
            )
        }

        return CMapSubtable(
            platformId = record.platformId,
            encodingId = record.encodingId,
            offset = offset,
            length = length.toInt(),
            format = 14,
            mapping = CMapFormat14Mapping(records),
        )
    }

    private fun parseFormat14DefaultRanges(
        table: ByteArray,
        subtableOffset: Int,
        subtableLength: Int,
        defaultUVSOffset: Long,
        recordIndex: Int,
        selectorIndex: Int,
    ): List<CMapUnicodeRange> {
        if (defaultUVSOffset == 0L) {
            return emptyList()
        }
        val tableOffset = table.cmapSubtableRelativeOffset(
            subtableOffset = subtableOffset,
            subtableLength = subtableLength,
            relativeOffset = defaultUVSOffset,
            requiredLength = UINT32_BYTE_LENGTH.toLong(),
            label = "OpenType cmap format 14 default UVS table for record $recordIndex selector $selectorIndex",
        )
        val rangeCount = table.readUInt32BE(
            tableOffset,
            "OpenType cmap format 14 default UVS count for record $recordIndex selector $selectorIndex",
        )
        require(rangeCount <= Int.MAX_VALUE) {
            "OpenType cmap format 14 default UVS count $rangeCount exceeds ${Int.MAX_VALUE}."
        }
        table.cmapSubtableRelativeOffset(
            subtableOffset = subtableOffset,
            subtableLength = subtableLength,
            relativeOffset = defaultUVSOffset,
            requiredLength = UINT32_BYTE_LENGTH.toLong() + rangeCount * FORMAT14_DEFAULT_RANGE_RECORD_SIZE.toLong(),
            label = "OpenType cmap format 14 default UVS ranges for record $recordIndex selector $selectorIndex",
        )

        return List(rangeCount.toInt()) { rangeIndex ->
            val rangeOffset = tableOffset + UINT32_BYTE_LENGTH + rangeIndex * FORMAT14_DEFAULT_RANGE_RECORD_SIZE
            val startUnicodeValue = table.readUInt24BE(
                rangeOffset,
                "OpenType cmap format 14 default UVS startUnicodeValue[$rangeIndex]",
            )
            val additionalCount = table.readUInt8(
                rangeOffset + FORMAT14_UINT24_BYTE_LENGTH,
                "OpenType cmap format 14 default UVS additionalCount[$rangeIndex]",
            )
            val endUnicodeValue = startUnicodeValue + additionalCount
            require(endUnicodeValue <= UNICODE_CODE_POINT_MAX) {
                "OpenType cmap format 14 default UVS range $rangeIndex ending at $endUnicodeValue exceeds 0x10ffff."
            }
            CMapUnicodeRange(
                startCodePoint = startUnicodeValue,
                endCodePoint = endUnicodeValue,
            )
        }
    }

    private fun parseFormat14NonDefaultMappings(
        table: ByteArray,
        subtableOffset: Int,
        subtableLength: Int,
        nonDefaultUVSOffset: Long,
        recordIndex: Int,
        selectorIndex: Int,
    ): Map<Int, Int> {
        if (nonDefaultUVSOffset == 0L) {
            return emptyMap()
        }
        val tableOffset = table.cmapSubtableRelativeOffset(
            subtableOffset = subtableOffset,
            subtableLength = subtableLength,
            relativeOffset = nonDefaultUVSOffset,
            requiredLength = UINT32_BYTE_LENGTH.toLong(),
            label = "OpenType cmap format 14 non-default UVS table for record $recordIndex selector $selectorIndex",
        )
        val mappingCount = table.readUInt32BE(
            tableOffset,
            "OpenType cmap format 14 non-default UVS count for record $recordIndex selector $selectorIndex",
        )
        require(mappingCount <= Int.MAX_VALUE) {
            "OpenType cmap format 14 non-default UVS count $mappingCount exceeds ${Int.MAX_VALUE}."
        }
        table.cmapSubtableRelativeOffset(
            subtableOffset = subtableOffset,
            subtableLength = subtableLength,
            relativeOffset = nonDefaultUVSOffset,
            requiredLength = UINT32_BYTE_LENGTH.toLong() + mappingCount * FORMAT14_NON_DEFAULT_RECORD_SIZE.toLong(),
            label = "OpenType cmap format 14 non-default UVS mappings for record $recordIndex selector $selectorIndex",
        )

        return List(mappingCount.toInt()) { mappingIndex ->
            val mappingOffset = tableOffset + UINT32_BYTE_LENGTH + mappingIndex * FORMAT14_NON_DEFAULT_RECORD_SIZE
            val unicodeValue = table.readUInt24BE(
                mappingOffset,
                "OpenType cmap format 14 non-default UVS unicodeValue[$mappingIndex]",
            )
            val glyphId = table.readUInt16BE(
                mappingOffset + FORMAT14_UINT24_BYTE_LENGTH,
                "OpenType cmap format 14 non-default UVS glyphID[$mappingIndex]",
            )
            require(unicodeValue in UNICODE_CODE_POINT_RANGE) {
                "OpenType cmap format 14 non-default UVS unicodeValue[$mappingIndex] $unicodeValue exceeds 0x10ffff."
            }
            unicodeValue to glyphId
        }.toMap()
    }
}

/**
 * One parsed OpenType `name` table record and its decoded string value.
 *
 * @property platformId OpenType platform identifier from the name record.
 * @property encodingId OpenType encoding identifier from the name record.
 * @property languageId OpenType language identifier from the name record.
 * @property nameId OpenType name identifier, such as family or subfamily.
 * @property length Byte length of the encoded string payload.
 * @property offset Byte offset from the `name` table string storage area.
 * @property value Decoded string value for the record.
 */
data class OpenTypeNameRecord(
    val platformId: Int,
    val encodingId: Int,
    val languageId: Int,
    val nameId: Int,
    val length: Int,
    val offset: Int,
    val value: String,
)

/**
 * One localized OpenType name with a normalized BCP 47 language tag.
 *
 * @property value Decoded localized name text.
 * @property languageTag Best-effort BCP 47 tag derived from OpenType platform/language IDs.
 * @property nameId OpenType name identifier that produced [value].
 */
data class OpenTypeLocalizedName(
    val value: String,
    val languageTag: String,
    val nameId: Int,
)

/**
 * Parsed `name` table container for family, style, and localized strings.
 *
 * @property records Compatibility map keyed as `platform:encoding:language:name`.
 * @property nameRecords Typed name records in table order.
 * @property format Raw `name` table format from the table header.
 * @property stringOffset Byte offset from the start of the table to string storage.
 */
data class NameTable(
    val records: Map<String, String> = emptyMap(),
    val nameRecords: List<OpenTypeNameRecord> = emptyList(),
    val format: Int = 0,
    val stringOffset: Int = 0,
) {
    /**
     * Creates a name table from typed records while preserving the compatibility map.
     */
    constructor(
        nameRecords: List<OpenTypeNameRecord>,
        format: Int = 0,
        stringOffset: Int = 0,
    ) : this(
        records = nameRecords.toLegacyNameRecordMap(),
        nameRecords = nameRecords,
        format = format,
        stringOffset = stringOffset,
    )

    /**
     * Returns the preferred decoded string for a name identifier and optional language identifier.
     *
     * US English Windows Unicode records are preferred when no language is
     * requested, then other Windows records, then the first matching typed record.
     */
    fun lookupName(nameId: Int, languageId: Int? = null): String? {
        val candidates = nameRecords.filter { record ->
            record.nameId == nameId && (languageId == null || record.languageId == languageId)
        }

        return candidates.firstOrNull { record ->
            record.platformId == WINDOWS_PLATFORM_ID &&
                (languageId != null || record.languageId == WINDOWS_US_ENGLISH_LANGUAGE_ID)
        }?.value
            ?: candidates.firstOrNull { it.platformId == WINDOWS_PLATFORM_ID }?.value
            ?: candidates.firstOrNull()?.value
            ?: records[nameId.toString()]
    }

    /**
     * Returns the preferred family name, preferring typographic family ID 16 over legacy ID 1.
     */
    fun preferredFamilyName(): String? =
        preferredName(listOf(16, 1))

    /**
     * Returns the preferred style/subfamily name, preferring typographic subfamily ID 17 over legacy ID 2.
     */
    fun preferredStyleName(): String? =
        preferredName(listOf(17, 2))

    /**
     * Returns the preferred PostScript name from name ID 6.
     */
    fun preferredPostScriptName(): String? =
        preferredName(listOf(6))

    /**
     * Returns localized family/display-family names with deterministic de-duplication.
     */
    fun localizedFamilyNames(): List<OpenTypeLocalizedName> {
        val seen = linkedSetOf<Pair<String, String>>()
        return nameRecords
            .asSequence()
            .filter { record -> record.nameId == 1 || record.nameId == 4 || record.nameId == 16 }
            .map { record ->
                OpenTypeLocalizedName(
                    value = record.value,
                    languageTag = openTypeLanguageTag(
                        platformId = record.platformId,
                        languageId = record.languageId,
                    ),
                    nameId = record.nameId,
                )
            }
            .filter { name -> name.value.isNotBlank() }
            .filter { name -> seen.add(name.value to name.languageTag) }
            .toList()
    }

    private fun preferredName(nameIds: List<Int>): String? {
        for (nameId in nameIds) {
            val candidates = nameRecords.filter { record -> record.nameId == nameId && record.value.isNotBlank() }
            val preferred = candidates.firstOrNull { record ->
                record.platformId == WINDOWS_PLATFORM_ID &&
                    record.languageId == WINDOWS_US_ENGLISH_LANGUAGE_ID
            }?.value
                ?: candidates.firstOrNull { record -> record.platformId == UNICODE_PLATFORM_ID }?.value
                ?: candidates.firstOrNull { record ->
                    record.platformId == MACINTOSH_PLATFORM_ID &&
                        record.languageId == MACINTOSH_ENGLISH_LANGUAGE_ID
                }?.value
                ?: candidates.firstOrNull()?.value
            if (preferred != null) {
                return preferred
            }
        }
        return null
    }
}

/**
 * Parsed OpenType style metadata independent of the higher-level fallback API.
 *
 * The values mirror OpenType/CSS style classes rather than Skia types so this module can remain a
 * pure SFNT parser. Consumers in `font/core` can map [slant] to their own style enum without
 * introducing a dependency from `font/sfnt` back to core policy code.
 *
 * @property weight OpenType `usWeightClass`, normalized to the inclusive `1..1000` range.
 * @property width OpenType `usWidthClass`, normalized to the inclusive `1..9` range.
 * @property slant Resolved upright, italic, or oblique style from `OS/2`, `head`, `post`, and
 * style-name evidence.
 * @property hasMetadata True when at least one OpenType style metadata source was present.
 */
data class OpenTypeStyle(
    val weight: Int = 400,
    val width: Int = 5,
    val slant: OpenTypeStyleSlant = OpenTypeStyleSlant.UPRIGHT,
    val hasMetadata: Boolean = false,
) {
    init {
        require(weight in 1..1000) { "OpenType style weight must be in 1..1000." }
        require(width in 1..9) { "OpenType style width must be in 1..9." }
    }
}

/**
 * Slant style resolved from OpenType metadata.
 */
enum class OpenTypeStyleSlant {
    /** Upright roman style. */
    UPRIGHT,

    /** Authored italic style. */
    ITALIC,

    /** Authored or metadata-declared oblique style. */
    OBLIQUE,
}

/**
 * Converts parsed SFNT style metadata into the core fallback style model.
 *
 * @return Core font style suitable for family matching and fallback ordering.
 */
fun OpenTypeStyle.toFontStyle(): FontStyle =
    FontStyle(
        weight = weight,
        width = width,
        slant = when (slant) {
            OpenTypeStyleSlant.UPRIGHT -> FontSlant.UPRIGHT
            OpenTypeStyleSlant.ITALIC -> FontSlant.ITALIC
            OpenTypeStyleSlant.OBLIQUE -> FontSlant.OBLIQUE
        },
    )

/**
 * Converts parsed OpenType face data into the core typeface metadata model.
 *
 * Typographic family/subfamily name IDs 16/17 are preferred when present, then legacy family and
 * style IDs 1/2, then stable source defaults. The parsed [OpenTypeFaceData.style] is used directly
 * so consumers do not need to re-derive style from a localized style-name string.
 *
 * @return Core typeface metadata for resolver and catalog layers.
 */
fun OpenTypeFaceData.toTypefaceData(): TypefaceData =
    TypefaceData(
        id = id,
        source = source,
        familyName = names.preferredFamilyName()
            ?: source.displayName,
        styleName = names.preferredStyleName()
            ?: DEFAULT_STYLE_NAME,
        style = style.toFontStyle(),
        diagnostics = diagnostics.map { diagnostic ->
            FontSourceDiagnostic(
                sourceId = diagnostic.sourceId,
                message = diagnostic.table?.let { table -> "${diagnostic.message} table=${table.value}" }
                    ?: diagnostic.message,
                causeCode = diagnostic.causeCode,
                causeMessage = diagnostic.causeMessage,
            )
        },
    )

/**
 * Returns raw SFNT table bytes for [tag] as a JVM byte array.
 *
 * [OpenTypeFaceData.rawTables] stores unsigned byte values for dumpability and multiplatform
 * safety. This helper mirrors Skia-style `copyTableData` ergonomics without exposing mutable
 * internal state.
 *
 * @throws IllegalArgumentException when a manually constructed raw-table entry contains a value
 * outside `0..255`.
 */
fun OpenTypeFaceData.rawTableBytes(tag: SFNTTableTag): ByteArray? =
    rawTables[tag]?.mapIndexed { index, value ->
        require(value in 0..UINT8_MAX) {
            "OpenType raw table ${tag.value} byte at index $index must be in 0..255, found $value."
        }
        value.toByte()
    }?.toByteArray()

/**
 * Returns raw SFNT table bytes for a four-character [tag].
 */
fun OpenTypeFaceData.rawTableBytes(tag: String): ByteArray? =
    rawTableBytes(SFNTTableTag(tag))

private fun CMapSubtable.toEvidence(table: CMapTable): OpenTypeCMapEvidence =
    OpenTypeCMapEvidence(
        platformId = platformId,
        encodingId = encodingId,
        format = format,
        offset = offset,
        length = length,
        mappingKind = mapping.evidenceKind(),
        mappingEntryCount = mapping.evidenceEntryCount(),
        encodingRecordCount = table.encodingRecords.size,
        parsedSubtableCount = table.mappings.size,
    )

private fun CMapMapping.evidenceKind(): String =
    when (this) {
        is CMapFormat0Mapping -> "format0-byte-array"
        is CMapFormat6Mapping -> "format6-trimmed-array"
        is CMapFormat4Mapping -> "format4-segments"
        is CMapFormat12Mapping -> "format12-segmented-coverage"
        is CMapFormat14Mapping -> "format14-variation-sequences"
    }

private fun CMapMapping.evidenceEntryCount(): Int =
    when (this) {
        is CMapFormat0Mapping -> glyphIds.size
        is CMapFormat6Mapping -> glyphIds.size
        is CMapFormat4Mapping -> segments.size
        is CMapFormat12Mapping -> groups.size
        is CMapFormat14Mapping -> records.size
    }

private fun MetricsTables.toEvidence(): OpenTypeMetricsEvidence =
    OpenTypeMetricsEvidence(
        unitsPerEm = unitsPerEm,
        ascender = ascender,
        descender = descender,
        lineGap = lineGap,
        numGlyphs = numGlyphs,
        numberOfHMetrics = numberOfHMetrics,
        horizontalMetricCount = horizontalMetrics.size,
        indexToLocFormat = indexToLocFormat,
        bounds = bounds?.let { bounds ->
            OpenTypeBoundsEvidence(
                xMin = bounds.xMin,
                yMin = bounds.yMin,
                xMax = bounds.xMax,
                yMax = bounds.yMax,
            )
        },
    )

private fun OpenTypeParseDiagnostic.toEvidence(): OpenTypeParseDiagnosticEvidence =
    OpenTypeParseDiagnosticEvidence(
        table = table,
        causeCode = causeCode,
        message = message,
        causeMessage = causeMessage,
    )

private fun List<Int>.toSFNTRawTableByteArray(tag: SFNTTableTag): ByteArray =
    mapIndexed { index, value ->
        require(value in 0..UINT8_MAX) {
            "OpenType raw table ${tag.value} byte at index $index must be in 0..255, found $value."
        }
        value.toByte()
    }.toByteArray()

private fun UInt.toSFNTUInt32Hex(): String =
    "0x${toString(radix = 16).padStart(8, '0')}"

private fun UInt.toSFNTScalerTypeLabel(): String =
    when (this) {
        0x00010000u -> "TrueType"
        0x4f54544fu -> "OTTO"
        0x74727565u -> "true"
        0x74797031u -> "typ1"
        else -> "unknown"
    }

private fun String.isStableSFNTTableTag(): Boolean =
    length == 4 && all { character -> character.code in 0x20..0x7E }

private fun String.isStableSFNTDiagnosticToken(): Boolean =
    isNotEmpty() && all { character -> character.code in 0x21..0x7E }

private fun String.isStableSFNTManifestLine(): Boolean =
    isNotBlank() && all { character -> character.code in 0x20..0x7E }

private fun ByteArray.sfntSha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun StringBuilder.appendSFNTJsonField(
    name: String,
    value: String,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ").append(sfntJsonString(value))
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendSFNTJsonField(
    name: String,
    value: Int,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendSFNTJsonField(
    name: String,
    value: Long,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendSFNTJsonField(
    name: String,
    value: Boolean,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendStringArrayField(
    name: String,
    values: List<String>,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ")
    append(values.joinToString(prefix = "[", postfix = "]", separator = ", ") { value -> sfntJsonString(value) })
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendSFNTJsonNullableField(
    name: String,
    value: String?,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ")
    append(value?.let(::sfntJsonString) ?: "null")
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendSFNTJsonNullableField(
    name: String,
    value: Int?,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ")
    append(value?.toString() ?: "null")
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendSFNTJsonNullableField(
    name: String,
    value: Long?,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(sfntJsonString(name)).append(": ")
    append(value?.toString() ?: "null")
    if (comma) append(",")
    append("\n")
}

private fun sfntJsonString(value: String): String = buildString {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (ch.code < 0x20 || ch.code > 0x7e) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
    append('"')
}

private fun String.sfntEvidenceQuoted(): String = sfntJsonString(this)

private fun buildOpenTypeTableFacts(
    evidence: OpenTypeFaceEvidence,
    requiredTables: Set<SFNTTableTag>,
    sourceLength: Long,
): List<OpenTypeTableFact> {
    val recordsByTag = evidence.tableRecords.groupBy { record -> record.tag }
    val directoryDiagnosticsByTag = evidence.directoryDiagnostics
        .map(OpenTypeTableFactDiagnostic::fromDirectory)
        .groupBy { diagnostic -> diagnostic.table }
    val faceDiagnosticsByTag = evidence.diagnostics
        .map(OpenTypeTableFactDiagnostic::fromFaceParser)
        .groupBy { diagnostic -> diagnostic.table }

    return OPEN_TYPE_TABLE_FACT_SPECS.map { spec ->
        val tag = spec.tag.value
        val record = recordsByTag[tag].orEmpty().minWithOrNull(SFNT_TABLE_EVIDENCE_ORDER)
        val diagnostics = (
            directoryDiagnosticsByTag[tag].orEmpty() +
                faceDiagnosticsByTag[tag].orEmpty()
            ).sortedWith(OPEN_TYPE_TABLE_FACT_DIAGNOSTIC_ORDER)
        val present = record != null
        val role = if (spec.tag in requiredTables) "required" else spec.role
        val parserStatus = tableFactParserStatus(
            tag = spec.tag,
            present = present,
            role = role,
            diagnostics = diagnostics,
        )

        OpenTypeTableFact(
            tag = tag,
            role = role,
            supportClassification = spec.supportClassification,
            present = present,
            byteRange = record?.let {
                OpenTypeTableByteRange(
                    offset = it.offset,
                    length = it.length,
                    endExclusive = it.offset + it.length,
                    sourceLength = sourceLength,
                )
            },
            checksum = record?.checksum,
            rawSha256 = record?.rawSha256,
            parserStatus = parserStatus,
            diagnostics = diagnostics,
        )
    }
}

private fun tableFactParserStatus(
    tag: SFNTTableTag,
    present: Boolean,
    role: String,
    diagnostics: List<OpenTypeTableFactDiagnostic>,
): String =
    when {
        diagnostics.any { it.code == "font.sfnt.required-table-missing" } -> "missing-required"
        diagnostics.any { it.code == OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC } -> "malformed-optional"
        !present && role == "required" -> "missing-required"
        !present -> "not-present"
        tag in CURRENT_METADATA_PARSED_TABLE_TAGS -> "parsed-current-metadata"
        else -> "metadata-only"
    }

private fun SFNTParseRequest.diagnosticResult(
    boundedBytes: ByteArray,
    containerKind: SFNTContainerKind,
    requestedIndex: Int,
    faceCount: Int?,
    diagnostic: SFNTParseDiagnostic,
): SFNTParseResult =
    SFNTParseResult(
        sourceId = sourceId,
        sourceKind = sourceKind,
        displayName = displayName,
        parserGeneration = parserGeneration,
        sourceByteOffset = bytes.byteOffset,
        sourceByteLength = boundedBytes.size,
        containerKind = containerKind,
        requestedCollectionIndex = requestedIndex,
        selectedFaceIndex = null,
        faceCount = faceCount,
        directoryFacts = null,
        faceFacts = null,
        tableSlices = emptyList(),
        diagnostics = listOf(diagnostic),
    )

private fun SFNTParseRequest.collectionIndexDiagnostic(
    containerKind: SFNTContainerKind,
    requestedIndex: Int,
    faceCount: Int?,
): SFNTParseDiagnostic =
    SFNTParseDiagnostic(
        code = "font.collection-index-invalid",
        message = when (faceCount) {
            null -> "Requested collection index $requestedIndex is invalid for the source container."
            1 -> "Requested collection index $requestedIndex is invalid for a single-face SFNT source."
            else -> "Requested collection index $requestedIndex is outside collection range 0..${faceCount - 1}."
        },
        sourceId = sourceId,
        parserGeneration = parserGeneration,
        requestedCollectionIndex = requestedIndex,
        containerKind = containerKind,
        faceCount = faceCount,
    )

private fun SFNTParseRequest.unsupportedWrapperDiagnostic(
    boundedBytes: ByteArray,
    requestedIndex: Int,
): SFNTParseDiagnostic {
    val code = if (boundedBytes.hasKnownUnsupportedSFNTWrapper()) {
        "font.sfnt.wrapper-unsupported"
    } else {
        "font.outline-format-unsupported"
    }
    return SFNTParseDiagnostic(
        code = code,
        message = if (code == "font.sfnt.wrapper-unsupported") {
            "Unsupported SFNT wrapper/container; provide bounded raw SFNT, TTC, or OTC bytes."
        } else {
            "Unsupported SFNT outline scaler type at the bounded source entry point."
        },
        sourceId = sourceId,
        parserGeneration = parserGeneration,
        requestedCollectionIndex = requestedIndex,
        containerKind = SFNTContainerKind.UNKNOWN_WRAPPER,
        faceCount = null,
        causeMessage = boundedBytes.sfntLeadingTagDescription(),
    )
}

private fun SFNTParseRequest.parseFailureDiagnostic(
    containerKind: SFNTContainerKind,
    requestedIndex: Int,
    faceCount: Int?,
    error: Throwable,
): SFNTParseDiagnostic {
    val message = error.message.orEmpty()
    val code = when {
        "faceIndex" in message -> "font.collection-index-invalid"
        "Unsupported" in message && "scaler type" in message -> "font.outline-format-unsupported"
        else -> "font.sfnt.wrapper-unsupported"
    }
    return SFNTParseDiagnostic(
        code = code,
        message = when (code) {
            "font.collection-index-invalid" -> "Requested collection index $requestedIndex is invalid for the source container."
            "font.outline-format-unsupported" -> "Unsupported SFNT outline scaler type at the bounded source entry point."
            else -> "Unable to parse a bounded SFNT directory from the source container."
        },
        sourceId = sourceId,
        parserGeneration = parserGeneration,
        requestedCollectionIndex = requestedIndex,
        containerKind = containerKind,
        faceCount = faceCount,
        causeMessage = error.message,
    )
}

private fun ByteArray.detectSFNTContainerKind(): SFNTContainerKind {
    if (startsWithTtcTag()) {
        return SFNTContainerKind.TTC_COLLECTION
    }
    if (size < SFNT_TAG_BYTE_LENGTH) {
        return SFNTContainerKind.UNKNOWN_WRAPPER
    }
    val tag = readUInt32BE(0, "SFNT parser source tag")
    return if (tag.isSupportedSFNTScalerType()) {
        SFNTContainerKind.SINGLE_FACE
    } else {
        SFNTContainerKind.UNKNOWN_WRAPPER
    }
}

private fun ByteArray.collectionFaceCountOrNull(): Int? {
    if (!startsWithTtcTag()) {
        return null
    }
    return runCatching {
        requireRange(0, TTC_HEADER_SIZE, "SFNT parser TTC header")
        val version = readUInt32BE(4, "SFNT parser TTC version")
        require(version == TTC_VERSION_1 || version == TTC_VERSION_2) {
            "Unsupported TTC/ttcf version ${version.toHexUInt32()}."
        }
        val count = readUInt32BE(8, "SFNT parser TTC numFonts")
        require(count in 1..Int.MAX_VALUE.toLong()) {
            "TTC/ttcf numFonts $count must be between 1 and ${Int.MAX_VALUE}."
        }
        count.toInt()
    }.getOrNull()
}

private fun ByteArray.selectTtcDirectoryOnlyFaceInput(faceIndex: Int): SelectedSFNTFaceInput {
    requireRange(0, TTC_HEADER_SIZE, "SFNT parser TTC header")
    val version = readUInt32BE(4, "SFNT parser TTC version")
    require(version == TTC_VERSION_1 || version == TTC_VERSION_2) {
        "Unsupported TTC/ttcf version ${version.toHexUInt32()}; expected 0x00010000 or 0x00020000."
    }
    val faceCount = readUInt32BE(8, "SFNT parser TTC numFonts")
    require(faceCount in 1..Int.MAX_VALUE.toLong()) {
        "TTC/ttcf numFonts $faceCount must be between 1 and ${Int.MAX_VALUE}."
    }
    require(faceIndex.toLong() < faceCount) {
        "TTC/ttcf faceIndex $faceIndex is outside collection range 0..${faceCount - 1}."
    }

    val offsetTableEnd = TTC_HEADER_SIZE.toLong() + faceCount * TTC_OFFSET_TABLE_ENTRY_SIZE.toLong()
    require(offsetTableEnd <= size.toLong()) {
        "TTC/ttcf offset table range [$TTC_HEADER_SIZE, $offsetTableEnd) exceeds source length $size."
    }

    val faceOffsetRecord = TTC_HEADER_SIZE + faceIndex * TTC_OFFSET_TABLE_ENTRY_SIZE
    val faceOffset = readUInt32BE(faceOffsetRecord, "SFNT parser TTC face offset[$faceIndex]")
    require(faceOffset <= Int.MAX_VALUE.toLong() && faceOffset + SFNT_DIRECTORY_HEADER_SIZE.toLong() <= size.toLong()) {
        "TTC/ttcf face $faceIndex offset $faceOffset must allow an SFNT header within source length $size."
    }

    return SelectedSFNTFaceInput(
        directory = readSfntDirectoryAt(
            directoryOffset = faceOffset.toInt(),
            label = "SFNT parser TTC face $faceIndex",
        ),
        readTable = { record -> readSfntTable(record, tableLabel = "SFNT parser TTC") },
    )
}

private fun SFNTContainerKind.refineCollectionKind(directory: SFNTTableDirectory): SFNTContainerKind =
    if (this == SFNTContainerKind.TTC_COLLECTION && directory.scalerType == 0x4f54544fu) {
        SFNTContainerKind.OTC_COLLECTION
    } else {
        this
    }

private fun ByteArray.hasKnownUnsupportedSFNTWrapper(): Boolean {
    if (size < SFNT_TAG_BYTE_LENGTH) {
        return true
    }
    return readTag(0, "SFNT parser wrapper tag") in setOf("wOFF", "wOF2")
}

private fun ByteArray.sfntLeadingTagDescription(): String =
    if (size < SFNT_TAG_BYTE_LENGTH) {
        "Source contains $size bytes, fewer than a four-byte SFNT tag."
    } else {
        "Leading tag ${readTag(0, "SFNT parser leading tag").sfntEvidenceQuoted()}."
    }

/**
 * Pure Kotlin factory for turning OpenType sources into core typeface metadata.
 */
object OpenTypeTypefaceDataFactory {
    /**
     * Parses [source] and converts the selected face into [TypefaceData].
     *
     * @param source Raw OpenType/SFNT source.
     * @param faceIndex Collection face index, or zero for single-face sources.
     * @param parser Parser implementation used for SFNT decoding.
     * @return Core typeface metadata backed by parsed OpenType data.
     */
    fun parse(
        source: FontSource,
        faceIndex: Int = 0,
        parser: OpenTypeFaceParser = DefaultOpenTypeFaceParser(),
    ): TypefaceData =
        parser.parse(source = source, faceIndex = faceIndex).toTypefaceData()

    /**
     * Parses in-memory font [bytes] without requiring callers to construct [FontSource].
     *
     * @param sourceId Stable source identifier supplied by the caller.
     * @param displayName Human-readable source label used for fallback names and diagnostics.
     * @param bytes Raw OpenType/SFNT bytes.
     * @param faceIndex Collection face index, or zero for single-face sources.
     * @param parser Parser implementation used for SFNT decoding.
     * @return Core typeface metadata backed by parsed OpenType data.
     */
    fun fromBytes(
        sourceId: FontSourceID,
        displayName: String,
        bytes: ByteArray,
        faceIndex: Int = 0,
        parser: OpenTypeFaceParser = DefaultOpenTypeFaceParser(),
    ): TypefaceData =
        parse(
            source = FontSource(
                id = sourceId,
                kind = FontSourceKind.MEMORY,
                displayName = displayName,
                bytes = bytes,
            ),
            faceIndex = faceIndex,
            parser = parser,
        )
}

/**
 * Parser for raw OpenType `name` table bytes.
 */
object OpenTypeNameTableParser {
    /**
     * Parses a raw OpenType `name` table into a typed [NameTable].
     *
     * Unicode and Windows platform records are decoded as UTF-16BE. Other
     * platforms fall back to ISO-8859-1 until platform-specific decoders are introduced.
     *
     * @throws IllegalArgumentException when the header, record array, or string ranges are invalid.
     */
    fun parse(table: ByteArray): NameTable {
        require(table.size >= NAME_TABLE_HEADER_SIZE) {
            "OpenType name table must contain at least $NAME_TABLE_HEADER_SIZE bytes for the header."
        }

        val format = table.readUInt16BE(0)
        val count = table.readUInt16BE(2)
        val stringOffset = table.readUInt16BE(4)
        val recordsEnd = NAME_TABLE_HEADER_SIZE + count * NAME_RECORD_SIZE

        require(recordsEnd <= table.size) {
            "OpenType name table record array ends at $recordsEnd but table length is ${table.size}."
        }
        require(stringOffset in recordsEnd..table.size) {
            "OpenType name table stringOffset $stringOffset must be between record end $recordsEnd and table length ${table.size}."
        }

        val nameRecords = List(count) { index ->
            val recordOffset = NAME_TABLE_HEADER_SIZE + index * NAME_RECORD_SIZE
            val platformId = table.readUInt16BE(recordOffset)
            val encodingId = table.readUInt16BE(recordOffset + 2)
            val languageId = table.readUInt16BE(recordOffset + 4)
            val nameId = table.readUInt16BE(recordOffset + 6)
            val length = table.readUInt16BE(recordOffset + 8)
            val offset = table.readUInt16BE(recordOffset + 10)
            val stringStart = stringOffset.toLong() + offset.toLong()
            val stringEnd = stringStart + length.toLong()
            val tableLength = table.size.toLong()

            require(stringStart <= tableLength && stringEnd <= tableLength) {
                "OpenType name record $index string range [$stringStart, $stringEnd) exceeds name table length ${table.size}."
            }

            val value = table.decodeNameString(
                recordIndex = index,
                platformId = platformId,
                offset = stringStart.toInt(),
                length = length,
            )

            OpenTypeNameRecord(
                platformId = platformId,
                encodingId = encodingId,
                languageId = languageId,
                nameId = nameId,
                length = length,
                offset = offset,
                value = value,
            )
        }

        return NameTable(
            records = nameRecords.toLegacyNameRecordMap(),
            nameRecords = nameRecords,
            format = format,
            stringOffset = stringOffset,
        )
    }

    private const val NAME_TABLE_HEADER_SIZE = 6
    private const val NAME_RECORD_SIZE = 12
}

/**
 * Resolves OpenType style metadata from parsed names and raw SFNT style tables.
 */
object OpenTypeStyleParser {
    /**
     * Parses portable style metadata from `OS/2`, `head`, `post`, and style-name records.
     *
     * @param names Parsed name table used for style-name fallbacks.
     * @param weightClass Raw `OS/2.usWeightClass` when known.
     * @param widthClass Raw `OS/2.usWidthClass` when known.
     * @param fsSelection Raw `OS/2.fsSelection` when known.
     * @param head Raw `head` table bytes used for `macStyle` fallback.
     * @param post Raw `post` table bytes used for `italicAngle` fallback.
     * @return Parsed style metadata independent of `font/core` APIs.
     */
    fun parse(
        names: NameTable,
        weightClass: Int?,
        widthClass: Int?,
        fsSelection: Int?,
        head: ByteArray?,
        post: ByteArray?,
    ): OpenTypeStyle {
        val normalizedWeight = (weightClass ?: DEFAULT_FONT_WEIGHT).coerceIn(1, 1000)
        val normalizedWidth = (widthClass ?: DEFAULT_FONT_WIDTH).coerceIn(1, 9)
        val macStyle = head?.readUInt16BEOrNull(44, "OpenType head macStyle") ?: 0
        val italicAngle = post?.readInt16BEOrNull(4, "OpenType post italicAngle high word") ?: 0
        val styleName = names.lookupName(2).orEmpty().lowercase()

        val italic = fsSelection.hasFlag(OS2_FS_SELECTION_ITALIC_FLAG) ||
            (macStyle and HEAD_MAC_STYLE_ITALIC_FLAG) != 0 ||
            "italic" in styleName
        val oblique = fsSelection.hasFlag(OS2_FS_SELECTION_OBLIQUE_FLAG) ||
            italicAngle != 0 ||
            "oblique" in styleName
        val bold = fsSelection.hasFlag(OS2_FS_SELECTION_BOLD_FLAG) ||
            (macStyle and HEAD_MAC_STYLE_BOLD_FLAG) != 0 ||
            "bold" in styleName
        val resolvedWeight = if (bold && normalizedWeight < BOLD_FONT_WEIGHT) {
            BOLD_FONT_WEIGHT
        } else {
            normalizedWeight
        }
        val slant = when {
            italic -> OpenTypeStyleSlant.ITALIC
            oblique -> OpenTypeStyleSlant.OBLIQUE
            else -> OpenTypeStyleSlant.UPRIGHT
        }

        return OpenTypeStyle(
            weight = resolvedWeight,
            width = normalizedWidth,
            slant = slant,
            hasMetadata = weightClass != null ||
                widthClass != null ||
                fsSelection != null ||
                head != null ||
                post != null ||
                styleName.isNotBlank(),
        )
    }

    private fun Int?.hasFlag(flag: Int): Boolean =
        this?.let { (it and flag) != 0 } == true
}

private const val WINDOWS_PLATFORM_ID = 3
private const val UNICODE_PLATFORM_ID = 0
private const val MACINTOSH_PLATFORM_ID = 1
private const val MACINTOSH_ROMAN_ENCODING_ID = 0
private const val MACINTOSH_ENGLISH_LANGUAGE_ID = 0
private const val WINDOWS_UNICODE_BMP_ENCODING_ID = 1
private const val WINDOWS_UNICODE_FULL_ENCODING_ID = 10
private const val WINDOWS_US_ENGLISH_LANGUAGE_ID = 0x0409
private const val DEFAULT_FONT_WEIGHT = 400
private const val BOLD_FONT_WEIGHT = 700
private const val DEFAULT_FONT_WIDTH = 5
private const val DEFAULT_STYLE_NAME = "Regular"
private const val OS2_FS_SELECTION_ITALIC_FLAG = 0x0001
private const val OS2_FS_SELECTION_BOLD_FLAG = 0x0020
private const val OS2_USE_TYPO_METRICS_FLAG = 0x0080
private const val OS2_FS_SELECTION_OBLIQUE_FLAG = 0x0200
private const val HEAD_MAC_STYLE_BOLD_FLAG = 0x0001
private const val HEAD_MAC_STYLE_ITALIC_FLAG = 0x0002
private val SFNT_HEX_UINT32_PATTERN = Regex("0x[0-9a-f]{8}")
private val SFNT_SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val SFNT_TABLE_EVIDENCE_ORDER = compareBy<SFNTTableEvidence>(
    { it.tag },
    { it.offset },
    { it.length },
    { it.checksum },
)
private val SFNT_TABLE_DIRECTORY_DIAGNOSTIC_EVIDENCE_ORDER = compareBy<SFNTTableDirectoryDiagnostic>(
    { it.code },
    { it.tag?.value.orEmpty() },
    { it.offset ?: -1L },
    { it.length ?: -1L },
    { it.message },
)
private val SFNT_DIAGNOSTIC_EVIDENCE_ORDER = compareBy<OpenTypeParseDiagnosticEvidence>(
    { it.table?.value.orEmpty() },
    { it.causeCode.orEmpty() },
    { it.message },
    { it.causeMessage.orEmpty() },
)
private val OPEN_TYPE_TABLE_FACT_DIAGNOSTIC_ORDER = compareBy<OpenTypeTableFactDiagnostic>(
    { it.table.orEmpty() },
    { it.source },
    { it.code },
    { it.offset ?: -1L },
    { it.length ?: -1L },
    { it.message },
    { it.causeMessage.orEmpty() },
)
private val NAME_TABLE_TAG = SFNTTableTag("name")
private val CMAP_TABLE_TAG = SFNTTableTag("cmap")
private val HEAD_TABLE_TAG = SFNTTableTag("head")
private val HHEA_TABLE_TAG = SFNTTableTag("hhea")
private val MAXP_TABLE_TAG = SFNTTableTag("maxp")
private val HMTX_TABLE_TAG = SFNTTableTag("hmtx")
private val OS_2_TABLE_TAG = SFNTTableTag("OS/2")
private val POST_TABLE_TAG = SFNTTableTag("post")
private val LOCA_TABLE_TAG = SFNTTableTag("loca")
private val GLYF_TABLE_TAG = SFNTTableTag("glyf")
private val CFF_TABLE_TAG = SFNTTableTag("CFF ")
private val CFF2_TABLE_TAG = SFNTTableTag("CFF2")
private val VHEA_TABLE_TAG = SFNTTableTag("vhea")
private val VMTX_TABLE_TAG = SFNTTableTag("vmtx")
private val GDEF_TABLE_TAG = SFNTTableTag("GDEF")
private val GSUB_TABLE_TAG = SFNTTableTag("GSUB")
private val KERN_TABLE_TAG = SFNTTableTag("kern")
private val BASE_TABLE_TAG = SFNTTableTag("BASE")
private val GPOS_TABLE_TAG = SFNTTableTag("GPOS")
private val FVAR_TABLE_TAG = SFNTTableTag("fvar")
private val AVAR_TABLE_TAG = SFNTTableTag("avar")
private val GVAR_TABLE_TAG = SFNTTableTag("gvar")
private val HVAR_TABLE_TAG = SFNTTableTag("HVAR")
private val VVAR_TABLE_TAG = SFNTTableTag("VVAR")
private val MVAR_TABLE_TAG = SFNTTableTag("MVAR")
private val COLR_TABLE_TAG = SFNTTableTag("COLR")
private val CPAL_TABLE_TAG = SFNTTableTag("CPAL")
private val CBDT_TABLE_TAG = SFNTTableTag("CBDT")
private val CBLC_TABLE_TAG = SFNTTableTag("CBLC")
private val SBIX_TABLE_TAG = SFNTTableTag("sbix")
private val SVG_TABLE_TAG = SFNTTableTag("SVG ")
private val TRUE_TYPE_REQUIRED_TABLE_TAGS = setOf(
    CMAP_TABLE_TAG,
    HEAD_TABLE_TAG,
    HHEA_TABLE_TAG,
    HMTX_TABLE_TAG,
    MAXP_TABLE_TAG,
    NAME_TABLE_TAG,
    OS_2_TABLE_TAG,
    POST_TABLE_TAG,
    LOCA_TABLE_TAG,
    GLYF_TABLE_TAG,
)
private val METRIC_TABLE_TAGS = listOf(HEAD_TABLE_TAG, HHEA_TABLE_TAG, MAXP_TABLE_TAG, HMTX_TABLE_TAG)
private val LAYOUT_TABLE_TAGS = setOf(KERN_TABLE_TAG, GDEF_TABLE_TAG, GPOS_TABLE_TAG, GSUB_TABLE_TAG)
private val COLOR_FONT_TABLE_TAGS = setOf(COLR_TABLE_TAG, CPAL_TABLE_TAG, CBDT_TABLE_TAG, CBLC_TABLE_TAG, SBIX_TABLE_TAG, SVG_TABLE_TAG)
private const val OPTIONAL_TABLE_MALFORMED_DIAGNOSTIC = "font.sfnt.optional-table-malformed"
private val OPEN_TYPE_TABLE_FACT_ROLES = setOf(
    "required",
    "required-cff-outline",
    "required-cff2-outline",
    "optional-shaping",
    "optional-vertical",
    "optional-variation",
    "optional-color",
    "optional-bitmap",
    "optional-svg",
)
private val OPEN_TYPE_TABLE_FACT_PARSER_STATUSES = setOf(
    "parsed-current-metadata",
    "metadata-only",
    "missing-required",
    "not-present",
    "malformed-optional",
)
private val OPEN_TYPE_TABLE_FACT_NON_CLAIMS = listOf(
    "table-presence-is-metadata-only",
    "no-shaping-support-claim",
    "no-scaler-support-claim",
    "no-color-glyph-rendering-claim",
    "no-native-engine-oracle",
    "no-gpu-support-claim",
)
private val MALFORMED_SFNT_CASE_IDS = listOf(
    "bad-sfnt-version",
    "truncated-header",
    "invalid-ttc-index",
    "out-of-bounds-table-record",
    "overlapping-tables",
    "duplicate-tag",
    "missing-required-table",
    "malformed-optional-table",
    "unsupported-cmap-format",
)
private val MALFORMED_SFNT_EXPECTED_OUTCOMES = setOf(
    "parser-refused",
    "directory-diagnostic",
    "optional-table-fallback-diagnostic",
    "cmap-refusal-diagnostic",
)
private val MALFORMED_SFNT_LINKED_EVIDENCE_PATHS = setOf(
    "reports/pure-kotlin-text/sfnt-directory.json",
    "reports/pure-kotlin-text/sfnt-tables.json",
    "reports/pure-kotlin-text/cmap-map.json",
)
private val MALFORMED_SFNT_DIAGNOSTIC_SOURCES = setOf(
    "sfnt-parser",
    "sfnt-directory",
    "face-parser",
    "cmap-parser",
)
private val MALFORMED_SFNT_NON_CLAIMS = listOf(
    "fixture-suite-only",
    "no-parser-support-promotion",
    "no-malformed-recovery-support-claim",
    "no-scaler-support-claim",
    "no-shaping-support-claim",
    "no-color-glyph-rendering-claim",
    "no-gpu-support-claim",
)
private val MALFORMED_SFNT_DIAGNOSTIC_ORDER = compareBy<MalformedSFNTFixtureDiagnosticSnapshot>(
    { it.source },
    { it.table.orEmpty() },
    { it.code },
    { it.message },
)
private val CURRENT_METADATA_PARSED_TABLE_TAGS = setOf(
    CMAP_TABLE_TAG,
    NAME_TABLE_TAG,
    HEAD_TABLE_TAG,
    HHEA_TABLE_TAG,
    HMTX_TABLE_TAG,
    VHEA_TABLE_TAG,
    VMTX_TABLE_TAG,
    MAXP_TABLE_TAG,
    OS_2_TABLE_TAG,
    POST_TABLE_TAG,
    KERN_TABLE_TAG,
    GPOS_TABLE_TAG,
    FVAR_TABLE_TAG,
    AVAR_TABLE_TAG,
    SVG_TABLE_TAG,
    CBDT_TABLE_TAG,
    CBLC_TABLE_TAG,
    SBIX_TABLE_TAG,
)
private data class OpenTypeTableFactSpec(
    val tag: SFNTTableTag,
    val role: String,
    val supportClassification: String,
)
private val OPEN_TYPE_TABLE_FACT_SPECS = listOf(
    OpenTypeTableFactSpec(CMAP_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(HEAD_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(HHEA_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(HMTX_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(MAXP_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(NAME_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(OS_2_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(POST_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(LOCA_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(GLYF_TABLE_TAG, "required", "metadata-required-table"),
    OpenTypeTableFactSpec(CFF_TABLE_TAG, "required-cff-outline", "metadata-conditional-outline-table"),
    OpenTypeTableFactSpec(CFF2_TABLE_TAG, "required-cff2-outline", "metadata-conditional-outline-table"),
    OpenTypeTableFactSpec(VHEA_TABLE_TAG, "optional-vertical", "metadata-vertical-table"),
    OpenTypeTableFactSpec(VMTX_TABLE_TAG, "optional-vertical", "metadata-vertical-table"),
    OpenTypeTableFactSpec(GDEF_TABLE_TAG, "optional-shaping", "metadata-shaping-table"),
    OpenTypeTableFactSpec(GSUB_TABLE_TAG, "optional-shaping", "metadata-shaping-table"),
    OpenTypeTableFactSpec(GPOS_TABLE_TAG, "optional-shaping", "metadata-shaping-table"),
    OpenTypeTableFactSpec(BASE_TABLE_TAG, "optional-shaping", "metadata-shaping-table"),
    OpenTypeTableFactSpec(KERN_TABLE_TAG, "optional-shaping", "metadata-shaping-table"),
    OpenTypeTableFactSpec(FVAR_TABLE_TAG, "optional-variation", "metadata-variation-table"),
    OpenTypeTableFactSpec(AVAR_TABLE_TAG, "optional-variation", "metadata-variation-table"),
    OpenTypeTableFactSpec(GVAR_TABLE_TAG, "optional-variation", "metadata-variation-table"),
    OpenTypeTableFactSpec(HVAR_TABLE_TAG, "optional-variation", "metadata-variation-table"),
    OpenTypeTableFactSpec(VVAR_TABLE_TAG, "optional-variation", "metadata-variation-table"),
    OpenTypeTableFactSpec(MVAR_TABLE_TAG, "optional-variation", "metadata-variation-table"),
    OpenTypeTableFactSpec(COLR_TABLE_TAG, "optional-color", "metadata-color-table"),
    OpenTypeTableFactSpec(CPAL_TABLE_TAG, "optional-color", "metadata-color-table"),
    OpenTypeTableFactSpec(CBDT_TABLE_TAG, "optional-bitmap", "metadata-bitmap-table"),
    OpenTypeTableFactSpec(CBLC_TABLE_TAG, "optional-bitmap", "metadata-bitmap-table"),
    OpenTypeTableFactSpec(SBIX_TABLE_TAG, "optional-bitmap", "metadata-bitmap-table"),
    OpenTypeTableFactSpec(SVG_TABLE_TAG, "optional-svg", "metadata-svg-table"),
)
private const val CMAP_HEADER_SIZE = 4
private const val CMAP_ENCODING_RECORD_SIZE = 8
private const val FORMAT0_HEADER_SIZE = 6
private const val FORMAT0_GLYPH_COUNT = 256
private const val FORMAT0_LENGTH = FORMAT0_HEADER_SIZE + FORMAT0_GLYPH_COUNT
private const val FORMAT6_HEADER_SIZE = 10
private const val FORMAT4_HEADER_SIZE = 14
private const val FORMAT12_HEADER_SIZE = 16
private const val FORMAT12_GROUP_SIZE = 12
private const val FORMAT14_HEADER_SIZE = 10
private const val FORMAT14_SELECTOR_RECORD_SIZE = 11
private const val FORMAT14_UINT24_BYTE_LENGTH = 3
private const val FORMAT14_DEFAULT_RANGE_RECORD_SIZE = 4
private const val FORMAT14_NON_DEFAULT_RECORD_SIZE = 5
private const val SVG_TABLE_HEADER_SIZE = 10
private const val SVG_DOCUMENT_RECORD_SIZE = 12
private const val MAX_SVG_DOCUMENT_RECORDS = 8192
private const val MAX_SVG_GLYPHS_PER_RECORD = 4096
private const val CBDT_TABLE_HEADER_SIZE = 4
private const val CBLC_TABLE_HEADER_SIZE = 8
private const val CBLC_BITMAP_SIZE_TABLE_SIZE = 48
private const val CBLC_INDEX_SUBTABLE_ARRAY_RECORD_SIZE = 8
private const val CBLC_INDEX_SUBTABLE_HEADER_SIZE = 8
private const val CBDT_SMALL_METRICS_HEADER_SIZE = 5
private const val CBDT_BIG_METRICS_HEADER_SIZE = 8
private const val SBIX_TABLE_HEADER_SIZE = 8
private const val SBIX_STRIKE_HEADER_SIZE = 4
private const val SBIX_GLYPH_HEADER_SIZE = 8
private const val MAX_BITMAP_STRIKES = 64
private const val MAX_BITMAP_SUBTABLES = 4096
private const val MAX_BITMAP_PAYLOAD_BYTES = 16 * 1024 * 1024
private const val KERN_TABLE_HEADER_SIZE = 4
private const val KERN_SUBTABLE_HEADER_SIZE = 6
private const val KERN_FORMAT0_HEADER_SIZE = 8
private const val KERN_FORMAT0_PAIR_SIZE = 6
private const val KERN_HORIZONTAL_FLAG = 0x0001
private const val KERN_MINIMUM_FLAG = 0x0002
private const val KERN_CROSS_STREAM_FLAG = 0x0004
private const val KERN_OVERRIDE_FLAG = 0x0008
private const val GPOS_HEADER_SIZE = 10
private const val GPOS_SCRIPT_RECORD_SIZE = 6
private const val GPOS_LANG_SYS_RECORD_SIZE = 6
private const val GPOS_LANG_SYS_HEADER_SIZE = 6
private const val GPOS_FEATURE_RECORD_SIZE = 6
private const val GPOS_FEATURE_HEADER_SIZE = 4
private const val GPOS_LOOKUP_HEADER_SIZE = 6
private const val GPOS_PAIR_SUBTABLE_FORMAT1_HEADER_SIZE = 10
private const val GPOS_PAIR_SUBTABLE_FORMAT2_HEADER_SIZE = 16
private const val GPOS_PAIR_VALUE_RECORD_GLYPH_SIZE = 2
private const val GPOS_COVERAGE_FORMAT1_HEADER_SIZE = 4
private const val GPOS_COVERAGE_FORMAT2_HEADER_SIZE = 4
private const val GPOS_COVERAGE_RANGE_RECORD_SIZE = 6
private const val GPOS_CLASS_DEF_FORMAT1_HEADER_SIZE = 6
private const val GPOS_CLASS_DEF_FORMAT2_HEADER_SIZE = 4
private const val GPOS_CLASS_RANGE_RECORD_SIZE = 6
private const val GPOS_SINGLE_ADJUSTMENT_LOOKUP_TYPE = 1
private const val GPOS_PAIR_ADJUSTMENT_LOOKUP_TYPE = 2
private const val GPOS_CURSIVE_ATTACHMENT_LOOKUP_TYPE = 3
private const val GPOS_MARK_TO_BASE_LOOKUP_TYPE = 4
private const val GPOS_MARK_TO_LIGATURE_LOOKUP_TYPE = 5
private const val GPOS_MARK_TO_MARK_LOOKUP_TYPE = 6
private const val GPOS_CONTEXTUAL_POSITIONING_LOOKUP_TYPE = 7
private const val GPOS_CHAINING_CONTEXT_POSITIONING_LOOKUP_TYPE = 8
private const val GPOS_EXTENSION_POSITIONING_LOOKUP_TYPE = 9
private const val GPOS_CURSIVE_SUBTABLE_HEADER_SIZE = 6
private const val GPOS_MARK_SUBTABLE_HEADER_SIZE = 12
private const val GPOS_MARK_ARRAY_HEADER_SIZE = 2
private const val GPOS_MARK_RECORD_SIZE = 4
private const val GPOS_BASE_ARRAY_HEADER_SIZE = 2
private const val GPOS_LIGATURE_ARRAY_HEADER_SIZE = 2
private const val GPOS_COMPONENT_RECORD_ANCHOR_OFFSET_SIZE = 2
private const val GPOS_ENTRY_EXIT_RECORD_SIZE = 4
private const val GPOS_ANCHOR_FORMAT1_SIZE = 6
private const val GPOS_ANCHOR_FORMAT2_SIZE = 8
private const val GPOS_ANCHOR_FORMAT3_SIZE = 10
private const val GDEF_HEADER_V1_SIZE = 12
private const val GDEF_HEADER_V12_SIZE = 14
private const val GDEF_LIG_CARET_LIST_HEADER_SIZE = 4
private const val GDEF_LIG_GLYPH_HEADER_SIZE = 2
private const val GDEF_GLYPH_CARET_VALUE_FORMAT1 = 1
private const val GDEF_CARET_VALUE_FORMAT1_SIZE = 4
private const val GPOS_VALUE_X_PLACEMENT = 0x0001
private const val GPOS_VALUE_Y_PLACEMENT = 0x0002
private const val GPOS_VALUE_X_ADVANCE = 0x0004
private const val GPOS_REQUIRED_FEATURE_NONE = 0xffff
private const val GPOS_MAX_COVERAGE_GLYPHS = 65_536
private const val GPOS_MAX_CLASS_PAIR_RECORDS = 1_048_576L
private const val GPOS_MAX_EXPANDED_CLASS_GLYPH_PAIRS = 65_536L
private const val FVAR_HEADER_SIZE = 16
private const val FVAR_AXIS_RECORD_SIZE = 20
private const val FIXED16_DOT16_SCALE = 65536.0
private const val UINT16_BYTE_LENGTH = 2
private const val UINT32_BYTE_LENGTH = 4
private const val UINT16_MAX = 0xffff
private const val UINT8_MAX = 0xff
private const val ASCII_CODE_POINT_MAX = 0x7f
private const val MAC_ROMAN_EXTENDED_START = 0x80
private const val BMP_CODE_POINT_MAX = 0xffff
private const val UNICODE_CODE_POINT_MAX = 0x10ffff
private val UNICODE_CODE_POINT_RANGE = 0..UNICODE_CODE_POINT_MAX
private val OPENTYPE_GLYPH_ID_RANGE = 0..UINT16_MAX
private val MAC_ROMAN_EXTENDED_UNICODE = intArrayOf(
    0x00C4, 0x00C5, 0x00C7, 0x00C9, 0x00D1, 0x00D6, 0x00DC, 0x00E1,
    0x00E0, 0x00E2, 0x00E4, 0x00E3, 0x00E5, 0x00E7, 0x00E9, 0x00E8,
    0x00EA, 0x00EB, 0x00ED, 0x00EC, 0x00EE, 0x00EF, 0x00F1, 0x00F3,
    0x00F2, 0x00F4, 0x00F6, 0x00F5, 0x00FA, 0x00F9, 0x00FB, 0x00FC,
    0x2020, 0x00B0, 0x00A2, 0x00A3, 0x00A7, 0x2022, 0x00B6, 0x00DF,
    0x00AE, 0x00A9, 0x2122, 0x00B4, 0x00A8, 0x2260, 0x00C6, 0x00D8,
    0x221E, 0x00B1, 0x2264, 0x2265, 0x00A5, 0x00B5, 0x2202, 0x2211,
    0x220F, 0x03C0, 0x222B, 0x00AA, 0x00BA, 0x03A9, 0x00E6, 0x00F8,
    0x00BF, 0x00A1, 0x00AC, 0x221A, 0x0192, 0x2248, 0x2206, 0x00AB,
    0x00BB, 0x2026, 0x00A0, 0x00C0, 0x00C3, 0x00D5, 0x0152, 0x0153,
    0x2013, 0x2014, 0x201C, 0x201D, 0x2018, 0x2019, 0x00F7, 0x25CA,
    0x00FF, 0x0178, 0x2044, 0x20AC, 0x2039, 0x203A, 0xFB01, 0xFB02,
    0x2021, 0x00B7, 0x201A, 0x201E, 0x2030, 0x00C2, 0x00CA, 0x00C1,
    0x00CB, 0x00C8, 0x00CD, 0x00CE, 0x00CF, 0x00CC, 0x00D3, 0x00D4,
    0xF8FF, 0x00D2, 0x00DA, 0x00DB, 0x00D9, 0x0131, 0x02C6, 0x02DC,
    0x00AF, 0x02D8, 0x02D9, 0x02DA, 0x00B8, 0x02DD, 0x02DB, 0x02C7,
)
private const val SFNT_DIRECTORY_HEADER_SIZE = 12
private const val SFNT_DIRECTORY_TABLE_RECORD_SIZE = 16
private const val SFNT_TAG_BYTE_LENGTH = 4
private const val TTC_HEADER_SIZE = 12
private const val TTC_OFFSET_TABLE_ENTRY_SIZE = 4
private const val TTC_COLLECTION_TAG = 0x74746366L
private const val TTC_VERSION_1 = 0x00010000L
private const val TTC_VERSION_2 = 0x00020000L
private const val SFNT_TRUE_TYPE_SCALER_TYPE = 0x00010000L
private const val SFNT_CFF_SCALER_TYPE = 0x4f54544fL
private const val SFNT_APPLE_TRUE_TYPE_SCALER_TYPE = 0x74727565L
private const val SFNT_TYPE1_SCALER_TYPE = 0x74797031L
private val CBDT_PNG_IMAGE_FORMATS = setOf(17, 18, 19)
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

/**
 * Parser-local view of a single selected SFNT face.
 *
 * The directory contains the offsets advertised by the source container. The
 * [readTable] function knows whether those offsets should be interpreted by the
 * injected [SFNTReader] for raw single-face sources or directly against the
 * original TTC/OpenType collection bytes for collection sources.
 *
 * @property directory Bounded SFNT directory for the selected face.
 * @property readTable Reads a raw table payload for one record in [directory].
 */
private data class SelectedSFNTFaceInput(
    val directory: SFNTTableDirectory,
    val readTable: (SFNTTableRecord) -> ByteArray,
)

/**
 * Returns true when this byte array begins with the TTC/OpenType Collection tag.
 *
 * Short sources deliberately return false so the normal single-face SFNT reader
 * can report its existing header-length error instead of replacing it with a
 * collection-specific diagnostic for data that is not identifiable as `ttcf`.
 *
 * @return True when bytes `[0, 4)` are the `ttcf` tag.
 */
private fun ByteArray.startsWithTtcTag(): Boolean =
    size >= SFNT_TAG_BYTE_LENGTH &&
        readUInt32BE(0, "OpenType source tag") == TTC_COLLECTION_TAG

/**
 * Reads a single SFNT directory from [directoryOffset] within a larger byte array.
 *
 * This is used for TTC face selection where the selected face directory is not
 * necessarily at byte zero. Table record offsets are preserved exactly as stored
 * in the directory because TTC collections use collection-relative table
 * offsets and may share table payloads between faces.
 *
 * @param directoryOffset Byte offset of the selected SFNT directory.
 * @param label Human-readable source label included in bounds errors.
 * @return Parsed SFNT table directory.
 * @throws IllegalArgumentException when the header, scaler type, record array,
 * or table record fields cannot be read safely.
 */
private fun ByteArray.readSfntDirectoryAt(
    directoryOffset: Int,
    label: String,
): SFNTTableDirectory {
    requireRange(directoryOffset, SFNT_DIRECTORY_HEADER_SIZE, "$label SFNT header")

    val scalerType = readUInt32BE(directoryOffset, "$label SFNT scaler type")
    require(scalerType != TTC_COLLECTION_TAG) {
        "$label SFNT directory must point to a single face, not another TTC/ttcf collection."
    }
    require(scalerType.isSupportedSFNTScalerType()) {
        "Unsupported $label SFNT scaler type ${scalerType.toHexUInt32()}; expected 0x00010000, OTTO, true, or typ1."
    }

    val tableCount = readUInt16BE(directoryOffset + 4, "$label SFNT numTables")
    val directoryEnd = directoryOffset.toLong() +
        SFNT_DIRECTORY_HEADER_SIZE.toLong() +
        tableCount.toLong() * SFNT_DIRECTORY_TABLE_RECORD_SIZE.toLong()
    require(directoryEnd <= size.toLong()) {
        "$label SFNT table directory range [$directoryOffset, $directoryEnd) exceeds source length $size."
    }

    val tables = List(tableCount) { index ->
        val recordOffset = directoryOffset + SFNT_DIRECTORY_HEADER_SIZE + index * SFNT_DIRECTORY_TABLE_RECORD_SIZE
        SFNTTableRecord(
            tag = SFNTTableTag(readTag(recordOffset, "$label SFNT table record $index tag")),
            checksum = readUInt32BE(recordOffset + 4, "$label SFNT table record $index checksum").toUInt(),
            offset = readUInt32BE(recordOffset + 8, "$label SFNT table record $index offset").toUInt(),
            length = readUInt32BE(recordOffset + 12, "$label SFNT table record $index length").toUInt(),
        )
    }

    return SFNTTableDirectory(
        scalerType = scalerType.toUInt(),
        tables = tables,
    )
}

/**
 * Reads one SFNT table payload from this source using the offset stored in [record].
 *
 * The offset is interpreted relative to the byte array that owns the record.
 * For TTC/OpenType collections that owner is the full collection, which allows
 * selected faces to preserve absolute table offsets and shared table data.
 *
 * @param record Table record whose offset and length describe the payload.
 * @param tableLabel Human-readable source label included in bounds errors.
 * @return Raw table payload bytes.
 * @throws IllegalArgumentException when the range cannot be bounded by this byte array.
 */
private fun ByteArray.readSfntTable(
    record: SFNTTableRecord,
    tableLabel: String,
): ByteArray {
    val offset = record.offset.toLong()
    val length = record.length.toLong()
    val end = offset + length
    val sourceLength = size.toLong()
    require(offset <= sourceLength && end <= sourceLength) {
        "$tableLabel table ${record.tag.value} range [$offset, $end) exceeds source length $sourceLength."
    }
    return copyOfRange(offset.toInt(), end.toInt())
}

/**
 * Reads an ISO-8859-1 SFNT tag from this byte array.
 *
 * @param offset First byte of the four-byte tag.
 * @param label Human-readable field label included in bounds errors.
 * @return Four-character tag string.
 * @throws IllegalArgumentException when the four tag bytes are outside this byte array.
 */
private fun ByteArray.readTag(offset: Int, label: String): String {
    requireRange(offset, SFNT_TAG_BYTE_LENGTH, label)
    return String(this, offset, SFNT_TAG_BYTE_LENGTH, Charsets.ISO_8859_1)
}

/**
 * Returns true when this raw scaler type is a supported single-face SFNT type.
 *
 * @return True for TrueType `0x00010000`, CFF `OTTO`, Apple `true`, or Type 1 `typ1`.
 */
private fun Long.isSupportedSFNTScalerType(): Boolean =
    this == SFNT_TRUE_TYPE_SCALER_TYPE ||
        this == SFNT_CFF_SCALER_TYPE ||
        this == SFNT_APPLE_TRUE_TYPE_SCALER_TYPE ||
        this == SFNT_TYPE1_SCALER_TYPE

/**
 * Formats a 32-bit unsigned integer value as an eight-digit hexadecimal string.
 *
 * @return Text such as `0x00010000` or `0x74746366`.
 */
private fun Long.toHexUInt32(): String =
    "0x${toString(radix = 16).padStart(8, '0')}"

private fun deterministicTypefaceId(source: FontSource, faceIndex: Int): TypefaceID {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("kanvas-sfnt-face-parser-v1:${source.id.value}:$faceIndex".toByteArray(Charsets.UTF_8))
    val uuidBytes = digest.copyOfRange(0, 16)
    uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0f) or 0x50).toByte()
    uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3f) or 0x80).toByte()
    return TypefaceID(Uuid.parse(uuidBytes.toUuidString()))
}

private fun ByteArray.toUuidString(): String = buildString(36) {
    for (index in this@toUuidString.indices) {
        if (index == 4 || index == 6 || index == 8 || index == 10) {
            append('-')
        }
        val value = this@toUuidString[index].toInt() and 0xff
        append(value.toString(16).padStart(2, '0'))
    }
}

private fun ByteArray.toUnsignedByteList(): List<Int> = map { it.toInt() and 0xff }

private fun openTypeGlyphPairKey(leftGlyphId: Int, rightGlyphId: Int): Int =
    (leftGlyphId shl 16) or rightGlyphId

private fun List<CMapSubtable>.preferredUnicodeCMapSubtable(): CMapSubtable? {
    var selected: CMapSubtable? = null
    var selectedPriority = Int.MAX_VALUE

    for (subtable in this) {
        val priority = subtable.unicodePreferencePriority()
        if (priority < selectedPriority) {
            selected = subtable
            selectedPriority = priority
        }
    }

    return selected
}

private fun CMapSubtable.unicodePreferencePriority(): Int =
    when {
        format == 12 &&
            platformId == WINDOWS_PLATFORM_ID &&
            encodingId == WINDOWS_UNICODE_FULL_ENCODING_ID -> 0
        format == 12 && platformId == UNICODE_PLATFORM_ID -> 1
        format == 4 &&
            platformId == WINDOWS_PLATFORM_ID &&
            encodingId == WINDOWS_UNICODE_BMP_ENCODING_ID -> 2
        format == 4 && platformId == UNICODE_PLATFORM_ID -> 3
        format == 6 && (platformId == UNICODE_PLATFORM_ID || platformId == WINDOWS_PLATFORM_ID) -> 4
        format == 0 && (platformId == UNICODE_PLATFORM_ID || platformId == WINDOWS_PLATFORM_ID) -> 5
        format == 6 -> 6
        format == 0 -> 7
        else -> Int.MAX_VALUE
    }

private fun CMapSubtable.lookupVariationGlyphId(
    codePoint: Int,
    variationSelector: Int,
    baseGlyphId: Int,
): Int? =
    (mapping as? CMapFormat14Mapping)?.lookupGlyphId(
        codePoint = codePoint,
        variationSelector = variationSelector,
        baseGlyphId = baseGlyphId,
    )

private fun CMapEncodingRecord.characterEncoding(): CMapCharacterEncoding =
    if (platformId == MACINTOSH_PLATFORM_ID && encodingId == MACINTOSH_ROMAN_ENCODING_ID) {
        CMapCharacterEncoding.MAC_ROMAN
    } else {
        CMapCharacterEncoding.DIRECT
    }

private fun CMapCharacterEncoding.characterCodeFor(codePoint: Int): Int? =
    when (this) {
        CMapCharacterEncoding.DIRECT -> codePoint
        CMapCharacterEncoding.MAC_ROMAN -> macRomanCharacterCodeFor(codePoint)
    }

private fun macRomanCharacterCodeFor(codePoint: Int): Int? {
    if (codePoint in 0..ASCII_CODE_POINT_MAX) {
        return codePoint
    }
    val extendedIndex = MAC_ROMAN_EXTENDED_UNICODE.indexOf(codePoint)
    return if (extendedIndex >= 0) MAC_ROMAN_EXTENDED_START + extendedIndex else null
}

private fun List<OpenTypeNameRecord>.toLegacyNameRecordMap(): Map<String, String> =
    associate { record ->
        "${record.platformId}:${record.encodingId}:${record.languageId}:${record.nameId}" to record.value
    }

private fun openTypeLanguageTag(platformId: Int, languageId: Int): String =
    when (platformId) {
        MACINTOSH_PLATFORM_ID -> macLanguageTag(languageId)
        WINDOWS_PLATFORM_ID -> windowsLanguageTag(languageId)
        else -> "und"
    }

private fun macLanguageTag(languageId: Int): String =
    when (languageId) {
        0 -> "en"
        1 -> "fr"
        2 -> "de"
        3 -> "it"
        4 -> "nl"
        5 -> "sv"
        6 -> "es"
        11 -> "ja"
        12 -> "ar"
        19 -> "zh-Hant"
        23 -> "ko"
        33 -> "zh-Hans"
        else -> "und"
    }

private fun windowsLanguageTag(languageId: Int): String =
    when (languageId) {
        0x0401 -> "ar-SA"
        0x0404 -> "zh-TW"
        0x0407 -> "de-DE"
        0x0409 -> "en-US"
        0x040A -> "es-ES"
        0x040C -> "fr-FR"
        0x0410 -> "it-IT"
        0x0411 -> "ja-JP"
        0x0412 -> "ko-KR"
        0x0413 -> "nl-NL"
        0x041D -> "sv-SE"
        0x0804 -> "zh-CN"
        0x0809 -> "en-GB"
        0x0C0A -> "es-ES"
        else -> "und"
    }

private fun ByteArray.readUInt16BE(offset: Int): Int =
    ((this[offset].toInt() and 0xff) shl 8) or
        (this[offset + 1].toInt() and 0xff)

private fun ByteArray.readUInt16BE(offset: Int, label: String): Int {
    requireRange(offset, UINT16_BYTE_LENGTH, label)
    return ((this[offset].toInt() and 0xff) shl 8) or
        (this[offset + 1].toInt() and 0xff)
}

private fun ByteArray.readUInt16BEOrNull(offset: Int, label: String): Int? {
    if (offset < 0 || offset.toLong() + UINT16_BYTE_LENGTH.toLong() > size.toLong()) {
        return null
    }
    return readUInt16BE(offset, label)
}

private fun ByteArray.readUInt8(offset: Int, label: String): Int {
    requireRange(offset, 1, label)
    return this[offset].toInt() and 0xff
}

private fun ByteArray.readInt16BE(offset: Int, label: String): Int {
    val value = readUInt16BE(offset, label)
    return if (value and 0x8000 == 0) value else value - 0x10000
}

private fun ByteArray.readInt16BEOrNull(offset: Int, label: String): Int? {
    if (offset < 0 || offset.toLong() + UINT16_BYTE_LENGTH.toLong() > size.toLong()) {
        return null
    }
    return readInt16BE(offset, label)
}

private fun ByteArray.readF2Dot14(offset: Int, label: String): Double =
    readInt16BE(offset, label).toDouble() / 16384.0

private fun ByteArray.readInt32BE(offset: Int, label: String): Int =
    readUInt32BE(offset, label).toInt()

private fun ByteArray.readUInt32BE(offset: Int, label: String): Long {
    requireRange(offset, 4, label)
    return ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or
        ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)
}

private fun ByteArray.readUInt24BE(offset: Int, label: String): Int {
    requireRange(offset, FORMAT14_UINT24_BYTE_LENGTH, label)
    return ((this[offset].toInt() and 0xff) shl 16) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        (this[offset + 2].toInt() and 0xff)
}

private fun ByteArray.unsignedBytes(start: Int, end: Int): List<Int> {
    requireRange(start, end - start, "OpenType cmap compatibility subtable bytes")
    return copyOfRange(start, end).map { it.toInt() and 0xff }
}

private fun ByteArray.cmapSubtableRelativeOffset(
    subtableOffset: Int,
    subtableLength: Int,
    relativeOffset: Long,
    requiredLength: Long,
    label: String,
): Int {
    val relativeEnd = relativeOffset + requiredLength
    require(
        subtableLength >= 0 &&
            relativeOffset >= 0 &&
            requiredLength >= 0 &&
            relativeEnd <= subtableLength.toLong(),
    ) {
        "$label relative range [$relativeOffset, $relativeEnd) exceeds cmap subtable length $subtableLength."
    }
    val absoluteOffset = subtableOffset.toLong() + relativeOffset
    require(absoluteOffset <= Int.MAX_VALUE && requiredLength <= Int.MAX_VALUE) {
        "$label absolute range [$absoluteOffset, ${absoluteOffset + requiredLength}) exceeds addressable Int range."
    }
    requireRange(absoluteOffset.toInt(), requiredLength.toInt(), label)
    return absoluteOffset.toInt()
}

private fun ByteArray.requireRange(offset: Int, length: Int, label: String) {
    val start = offset.toLong()
    val end = start + length.toLong()
    require(offset >= 0 && length >= 0 && end <= size.toLong()) {
        "$label range [$start, $end) exceeds table length $size."
    }
}

private fun ByteArray.requireArrayRange(
    offset: Int,
    count: Int,
    recordSize: Int,
    label: String,
) {
    val length = count.toLong() * recordSize.toLong()
    require(length <= Int.MAX_VALUE.toLong()) {
        "$label byte length $length exceeds addressable Int range."
    }
    requireRange(offset, length.toInt(), label)
}

private fun checkedByteOffset(base: Int, offset: Int, label: String): Int {
    val absolute = base.toLong() + offset.toLong()
    require(absolute in 0..Int.MAX_VALUE.toLong()) {
        "$label offset $offset from base $base is outside addressable Int range."
    }
    return absolute.toInt()
}

private fun ByteArray.decodeNameString(
    recordIndex: Int,
    platformId: Int,
    offset: Int,
    length: Int,
): String {
    if (platformId == UNICODE_PLATFORM_ID || platformId == WINDOWS_PLATFORM_ID) {
        require(length % 2 == 0) {
            "OpenType name record $recordIndex UTF-16BE string length must be even, was $length."
        }
        return String(this, offset, length, Charsets.UTF_16BE)
    }

    return String(this, offset, length, Charsets.ISO_8859_1)
}

/**
 * Parsed metric table container for `head`, `hhea`, `hmtx`, `vhea`, `vmtx`, `OS/2`, and related data.
 *
 * @property unitsPerEm Design units per em when known.
 * @property ascender Typographic ascender in font units when known.
 * @property descender Typographic descender in font units when known.
 * @property indexToLocFormat `loca` offset format from the `head` table when known.
 * @property bounds Global font bounding box from the `head` table when known.
 * @property numGlyphs Glyph count from the `maxp` table when known.
 * @property lineGap Typographic line gap in font units when known.
 * @property numberOfHMetrics Count of long horizontal metric records from `hhea` when known.
 * @property horizontalMetrics Horizontal metrics indexed by glyph ID.
 * @property verticalAscender Vertical typographic ascender from `vhea` when known.
 * @property verticalDescender Vertical typographic descender from `vhea` when known.
 * @property verticalLineGap Vertical typographic line gap from `vhea` when known.
 * @property maxAdvanceHeight Maximum vertical advance from `vhea.advanceHeightMax` when known.
 * @property numberOfVMetrics Count of long vertical metric records from `vhea` when known.
 * @property verticalMetrics Vertical metrics indexed by glyph ID from `vmtx` when known.
 * @property xHeight Lowercase x-height from `OS/2.sxHeight` in font units when known.
 * @property capHeight Uppercase cap height from `OS/2.sCapHeight` in font units when known.
 * @property averageCharWidth Average character width from `OS/2.xAvgCharWidth` in font units when known.
 * @property maxCharWidth Maximum horizontal advance from `hhea.advanceWidthMax` in font units when known.
 * @property underlineThickness Underline stroke thickness from `post.underlineThickness` in font units when known.
 * @property underlinePosition Underline baseline offset from `post.underlinePosition` in font units when known.
 * @property strikeoutThickness Strikeout stroke thickness from `OS/2.yStrikeoutSize` in font units when known.
 * @property strikeoutPosition Strikeout baseline offset from `OS/2.yStrikeoutPosition` in font units when known.
 * @property weightClass Raw `OS/2.usWeightClass` when known.
 * @property widthClass Raw `OS/2.usWidthClass` when known.
 * @property fsSelection Raw `OS/2.fsSelection` when known.
 */
data class MetricsTables(
    val unitsPerEm: Int? = null,
    val ascender: Int? = null,
    val descender: Int? = null,
    val indexToLocFormat: Int? = null,
    val bounds: OpenTypeFontBounds? = null,
    val numGlyphs: Int? = null,
    val lineGap: Int? = null,
    val numberOfHMetrics: Int? = null,
    val horizontalMetrics: List<HorizontalGlyphMetric> = emptyList(),
    val verticalAscender: Int? = null,
    val verticalDescender: Int? = null,
    val verticalLineGap: Int? = null,
    val maxAdvanceHeight: Int? = null,
    val numberOfVMetrics: Int? = null,
    val verticalMetrics: List<VerticalGlyphMetric> = emptyList(),
    val xHeight: Int? = null,
    val capHeight: Int? = null,
    val averageCharWidth: Int? = null,
    val maxCharWidth: Int? = null,
    val underlineThickness: Int? = null,
    val underlinePosition: Int? = null,
    val strikeoutThickness: Int? = null,
    val strikeoutPosition: Int? = null,
    val weightClass: Int? = null,
    val widthClass: Int? = null,
    val fsSelection: Int? = null,
)

/**
 * Global font bounds from the OpenType `head` table.
 *
 * Values are signed font-unit coordinates for the union of glyph bounds advertised by the font.
 *
 * @property xMin Minimum x coordinate in font units.
 * @property yMin Minimum y coordinate in font units.
 * @property xMax Maximum x coordinate in font units.
 * @property yMax Maximum y coordinate in font units.
 */
data class OpenTypeFontBounds(
    val xMin: Int,
    val yMin: Int,
    val xMax: Int,
    val yMax: Int,
)

/**
 * Horizontal metric for one glyph ID from the OpenType `hmtx` table.
 *
 * @property glyphId Zero-based glyph identifier.
 * @property advanceWidth Unsigned horizontal advance width in font units.
 * @property leftSideBearing Signed left side bearing in font units.
 */
data class HorizontalGlyphMetric(
    val glyphId: Int,
    val advanceWidth: Int,
    val leftSideBearing: Int,
)

/**
 * Vertical metric for one glyph ID from the OpenType `vmtx` table.
 *
 * @property glyphId Zero-based glyph identifier.
 * @property advanceHeight Unsigned vertical advance height in font units.
 * @property topSideBearing Signed top side bearing in font units.
 */
data class VerticalGlyphMetric(
    val glyphId: Int,
    val advanceHeight: Int,
    val topSideBearing: Int,
)

/**
 * Parser for the required OpenType metric tables `head`, `hhea`, `maxp`, and `hmtx`.
 */
object OpenTypeMetricsTableParser {
    /**
     * Parses bounded raw metric table bytes into [MetricsTables].
     *
     * The `hmtx` parser follows OpenType long-horizontal-metric rules: glyphs
     * after `numberOfHMetrics` reuse the last advance width and read additional
     * left-side-bearing values.
     *
     * @throws IllegalArgumentException when any required table is too short or metric counts are inconsistent.
     */
    fun parse(
        head: ByteArray,
        hhea: ByteArray,
        maxp: ByteArray,
        hmtx: ByteArray,
        vhea: ByteArray? = null,
        vmtx: ByteArray? = null,
        os2: ByteArray? = null,
        post: ByteArray? = null,
    ): MetricsTables {
        val headMetrics = parseHead(head)
        val hheaMetrics = parseHhea(hhea)
        val numGlyphs = parseMaxp(maxp)
        val horizontalMetrics = parseHmtx(
            table = hmtx,
            numGlyphs = numGlyphs,
            numberOfHMetrics = hheaMetrics.numberOfHMetrics,
        )
        val os2Metrics = os2?.let(::parseOs2)
        val postMetrics = post?.let(::parsePost)
        val typographicMetrics = os2Metrics?.takeIf { it.useTypographicLineMetrics }
        val verticalHeaderMetrics = vhea?.let(::parseVerticalHeader)
        val verticalMetrics = if (verticalHeaderMetrics != null && vmtx != null) {
            parseVerticalMetrics(
                table = vmtx,
                numGlyphs = numGlyphs,
                numberOfVMetrics = requireNotNull(verticalHeaderMetrics.numberOfVMetrics),
            )
        } else {
            emptyList()
        }

        return MetricsTables(
            unitsPerEm = headMetrics.unitsPerEm,
            ascender = typographicMetrics?.typoAscender ?: hheaMetrics.ascender,
            descender = typographicMetrics?.typoDescender ?: hheaMetrics.descender,
            indexToLocFormat = headMetrics.indexToLocFormat,
            bounds = headMetrics.bounds,
            numGlyphs = numGlyphs,
            lineGap = typographicMetrics?.typoLineGap ?: hheaMetrics.lineGap,
            numberOfHMetrics = hheaMetrics.numberOfHMetrics,
            horizontalMetrics = horizontalMetrics,
            verticalAscender = verticalHeaderMetrics?.verticalAscender,
            verticalDescender = verticalHeaderMetrics?.verticalDescender,
            verticalLineGap = verticalHeaderMetrics?.verticalLineGap,
            maxAdvanceHeight = verticalHeaderMetrics?.maxAdvanceHeight,
            numberOfVMetrics = verticalHeaderMetrics?.numberOfVMetrics,
            verticalMetrics = verticalMetrics,
            xHeight = os2Metrics?.xHeight,
            capHeight = os2Metrics?.capHeight,
            averageCharWidth = os2Metrics?.averageCharWidth,
            maxCharWidth = hheaMetrics.advanceWidthMax,
            underlineThickness = postMetrics?.underlineThickness,
            underlinePosition = postMetrics?.underlinePosition,
            strikeoutThickness = os2Metrics?.strikeoutThickness,
            strikeoutPosition = os2Metrics?.strikeoutPosition,
            weightClass = os2Metrics?.weightClass,
            widthClass = os2Metrics?.widthClass,
            fsSelection = os2Metrics?.fsSelection,
        )
    }

    fun parseVerticalHeader(table: ByteArray): MetricsTables {
        val verticalHeader = parseVhea(table)
        return MetricsTables(
            verticalAscender = verticalHeader.verticalAscender,
            verticalDescender = verticalHeader.verticalDescender,
            verticalLineGap = verticalHeader.verticalLineGap,
            maxAdvanceHeight = verticalHeader.maxAdvanceHeight,
            numberOfVMetrics = verticalHeader.numberOfVMetrics,
        )
    }

    fun parseVerticalMetrics(
        table: ByteArray,
        numGlyphs: Int,
        numberOfVMetrics: Int,
    ): List<VerticalGlyphMetric> = parseVmtx(
        table = table,
        numGlyphs = numGlyphs,
        numberOfVMetrics = numberOfVMetrics,
    )

    private fun parseHead(table: ByteArray): ParsedHeadMetrics {
        require(table.size >= HEAD_TABLE_SIZE) {
            "OpenType head table must contain at least $HEAD_TABLE_SIZE bytes, was ${table.size}."
        }

        val unitsPerEm = table.readUInt16BE(18, "OpenType head unitsPerEm")
        require(unitsPerEm in 16..16384) {
            "OpenType head unitsPerEm $unitsPerEm must be between 16 and 16384."
        }
        val indexToLocFormat = table.readInt16BE(50, "OpenType head indexToLocFormat")
        require(indexToLocFormat == 0 || indexToLocFormat == 1) {
            "OpenType head indexToLocFormat $indexToLocFormat must be 0 or 1."
        }

        return ParsedHeadMetrics(
            unitsPerEm = unitsPerEm,
            bounds = OpenTypeFontBounds(
                xMin = table.readInt16BE(36, "OpenType head xMin"),
                yMin = table.readInt16BE(38, "OpenType head yMin"),
                xMax = table.readInt16BE(40, "OpenType head xMax"),
                yMax = table.readInt16BE(42, "OpenType head yMax"),
            ),
            indexToLocFormat = indexToLocFormat,
        )
    }

    private fun parseHhea(table: ByteArray): ParsedHheaMetrics {
        require(table.size >= HHEA_TABLE_SIZE) {
            "OpenType hhea table must contain at least $HHEA_TABLE_SIZE bytes, was ${table.size}."
        }

        return ParsedHheaMetrics(
            ascender = table.readInt16BE(4, "OpenType hhea ascender"),
            descender = table.readInt16BE(6, "OpenType hhea descender"),
            lineGap = table.readInt16BE(8, "OpenType hhea lineGap"),
            advanceWidthMax = table.readUInt16BE(10, "OpenType hhea advanceWidthMax"),
            numberOfHMetrics = table.readUInt16BE(34, "OpenType hhea numberOfHMetrics"),
        )
    }

    private fun parseVhea(table: ByteArray): ParsedVheaMetrics {
        require(table.size >= VHEA_TABLE_SIZE) {
            "OpenType vhea table must contain at least $VHEA_TABLE_SIZE bytes, was ${table.size}."
        }

        return ParsedVheaMetrics(
            verticalAscender = table.readInt16BE(4, "OpenType vhea ascender"),
            verticalDescender = table.readInt16BE(6, "OpenType vhea descender"),
            verticalLineGap = table.readInt16BE(8, "OpenType vhea lineGap"),
            maxAdvanceHeight = table.readUInt16BE(10, "OpenType vhea advanceHeightMax"),
            numberOfVMetrics = table.readUInt16BE(34, "OpenType vhea numberOfVMetrics"),
        )
    }

    private fun parseOs2(table: ByteArray): ParsedOs2Metrics =
        ParsedOs2Metrics(
            averageCharWidth = table.readInt16BEOrNull(2, "OpenType OS/2 xAvgCharWidth"),
            weightClass = table.readUInt16BEOrNull(4, "OpenType OS/2 usWeightClass"),
            widthClass = table.readUInt16BEOrNull(6, "OpenType OS/2 usWidthClass"),
            strikeoutThickness = table.readInt16BEOrNull(26, "OpenType OS/2 yStrikeoutSize"),
            strikeoutPosition = table.readInt16BEOrNull(28, "OpenType OS/2 yStrikeoutPosition"),
            fsSelection = table.readUInt16BEOrNull(62, "OpenType OS/2 fsSelection"),
            typoAscender = table.readInt16BEOrNull(68, "OpenType OS/2 sTypoAscender"),
            typoDescender = table.readInt16BEOrNull(70, "OpenType OS/2 sTypoDescender"),
            typoLineGap = table.readInt16BEOrNull(72, "OpenType OS/2 sTypoLineGap"),
            xHeight = table.readInt16BEOrNull(86, "OpenType OS/2 sxHeight"),
            capHeight = table.readInt16BEOrNull(88, "OpenType OS/2 sCapHeight"),
        )

    private fun parsePost(table: ByteArray): ParsedPostMetrics =
        ParsedPostMetrics(
            underlinePosition = table.readInt16BEOrNull(8, "OpenType post underlinePosition"),
            underlineThickness = table.readInt16BEOrNull(10, "OpenType post underlineThickness"),
        )

    private fun parseMaxp(table: ByteArray): Int {
        require(table.size >= MAXP_TABLE_SIZE) {
            "OpenType maxp table must contain at least $MAXP_TABLE_SIZE bytes, was ${table.size}."
        }

        return table.readUInt16BE(4, "OpenType maxp numGlyphs")
    }

    private fun parseHmtx(
        table: ByteArray,
        numGlyphs: Int,
        numberOfHMetrics: Int,
    ): List<HorizontalGlyphMetric> {
        require(numberOfHMetrics <= numGlyphs) {
            "OpenType hhea numberOfHMetrics $numberOfHMetrics exceeds maxp numGlyphs $numGlyphs."
        }
        require(numGlyphs == 0 || numberOfHMetrics > 0) {
            "OpenType hhea numberOfHMetrics must be positive when maxp numGlyphs is $numGlyphs."
        }

        val requiredLength = numberOfHMetrics * LONG_HORIZONTAL_METRIC_SIZE +
            (numGlyphs - numberOfHMetrics) * UINT16_BYTE_LENGTH
        require(table.size >= requiredLength) {
            "OpenType hmtx table length ${table.size} is shorter than required $requiredLength bytes for $numGlyphs glyphs and $numberOfHMetrics horizontal metrics."
        }

        var lastAdvanceWidth = 0
        return List(numGlyphs) { glyphId ->
            if (glyphId < numberOfHMetrics) {
                val metricOffset = glyphId * LONG_HORIZONTAL_METRIC_SIZE
                val advanceWidth = table.readUInt16BE(
                    metricOffset,
                    "OpenType hmtx advanceWidth[$glyphId]",
                )
                val leftSideBearing = table.readInt16BE(
                    metricOffset + UINT16_BYTE_LENGTH,
                    "OpenType hmtx leftSideBearing[$glyphId]",
                )
                lastAdvanceWidth = advanceWidth
                HorizontalGlyphMetric(
                    glyphId = glyphId,
                    advanceWidth = advanceWidth,
                    leftSideBearing = leftSideBearing,
                )
            } else {
                val bearingOffset = numberOfHMetrics * LONG_HORIZONTAL_METRIC_SIZE +
                    (glyphId - numberOfHMetrics) * UINT16_BYTE_LENGTH
                HorizontalGlyphMetric(
                    glyphId = glyphId,
                    advanceWidth = lastAdvanceWidth,
                    leftSideBearing = table.readInt16BE(
                        bearingOffset,
                        "OpenType hmtx leftSideBearing[$glyphId]",
                    ),
                )
            }
        }
    }

    private fun parseVmtx(
        table: ByteArray,
        numGlyphs: Int,
        numberOfVMetrics: Int,
    ): List<VerticalGlyphMetric> {
        require(numberOfVMetrics <= numGlyphs) {
            "OpenType vhea numberOfVMetrics $numberOfVMetrics exceeds maxp numGlyphs $numGlyphs."
        }
        require(numGlyphs == 0 || numberOfVMetrics > 0) {
            "OpenType vhea numberOfVMetrics must be positive when maxp numGlyphs is $numGlyphs."
        }

        val requiredLength = numberOfVMetrics * LONG_VERTICAL_METRIC_SIZE +
            (numGlyphs - numberOfVMetrics) * UINT16_BYTE_LENGTH
        require(table.size >= requiredLength) {
            "OpenType vmtx table length ${table.size} is shorter than required $requiredLength bytes for $numGlyphs glyphs and $numberOfVMetrics vertical metrics."
        }

        var lastAdvanceHeight = 0
        return List(numGlyphs) { glyphId ->
            if (glyphId < numberOfVMetrics) {
                val metricOffset = glyphId * LONG_VERTICAL_METRIC_SIZE
                val advanceHeight = table.readUInt16BE(
                    metricOffset,
                    "OpenType vmtx advanceHeight[$glyphId]",
                )
                val topSideBearing = table.readInt16BE(
                    metricOffset + UINT16_BYTE_LENGTH,
                    "OpenType vmtx topSideBearing[$glyphId]",
                )
                lastAdvanceHeight = advanceHeight
                VerticalGlyphMetric(
                    glyphId = glyphId,
                    advanceHeight = advanceHeight,
                    topSideBearing = topSideBearing,
                )
            } else {
                val bearingOffset = numberOfVMetrics * LONG_VERTICAL_METRIC_SIZE +
                    (glyphId - numberOfVMetrics) * UINT16_BYTE_LENGTH
                VerticalGlyphMetric(
                    glyphId = glyphId,
                    advanceHeight = lastAdvanceHeight,
                    topSideBearing = table.readInt16BE(
                        bearingOffset,
                        "OpenType vmtx topSideBearing[$glyphId]",
                    ),
                )
            }
        }
    }

    private data class ParsedHeadMetrics(
        val unitsPerEm: Int,
        val bounds: OpenTypeFontBounds,
        val indexToLocFormat: Int,
    )

    private data class ParsedHheaMetrics(
        val ascender: Int,
        val descender: Int,
        val lineGap: Int,
        val advanceWidthMax: Int,
        val numberOfHMetrics: Int,
    )

    private data class ParsedVheaMetrics(
        val verticalAscender: Int,
        val verticalDescender: Int,
        val verticalLineGap: Int,
        val maxAdvanceHeight: Int,
        val numberOfVMetrics: Int,
    )

    private data class ParsedOs2Metrics(
        val xHeight: Int?,
        val capHeight: Int?,
        val averageCharWidth: Int?,
        val strikeoutThickness: Int?,
        val strikeoutPosition: Int?,
        val weightClass: Int?,
        val widthClass: Int?,
        val fsSelection: Int?,
        val typoAscender: Int?,
        val typoDescender: Int?,
        val typoLineGap: Int?,
    ) {
        val useTypographicLineMetrics: Boolean =
            fsSelection?.let { (it and OS2_USE_TYPO_METRICS_FLAG) != 0 } == true &&
                typoAscender != null &&
                typoDescender != null &&
                typoLineGap != null
    }

    private data class ParsedPostMetrics(
        val underlineThickness: Int?,
        val underlinePosition: Int?,
    )

    private const val HEAD_TABLE_SIZE = 54
    private const val HHEA_TABLE_SIZE = 36
    private const val VHEA_TABLE_SIZE = 36
    private const val MAXP_TABLE_SIZE = 6
    private const val LONG_HORIZONTAL_METRIC_SIZE = 4
    private const val LONG_VERTICAL_METRIC_SIZE = 4
}

/**
 * Parsed legacy OpenType `kern` table.
 *
 * This model covers the version `0` `kern` table used by many TrueType and
 * OpenType fonts for simple pair-position kerning. Only horizontal format `0`
 * subtables are interpreted because they provide direct glyph-pair adjustment
 * records and match the legacy Kanvas support being migrated into this pure
 * Kotlin SFNT module. Unsupported `kern` subtable formats can still be kept as
 * raw bytes through [OpenTypeLayoutTables.tables], but they do not participate
 * in lookup.
 *
 * @property version Raw table version from the `kern` table header. The parser
 * currently accepts only `0`.
 * @property subtables Parsed horizontal format `0` subtables in table order.
 */
data class OpenTypeKernTable(
    val version: Int = 0,
    val subtables: List<OpenTypeKernFormat0Subtable> = emptyList(),
) {
    /**
     * Returns the signed horizontal kerning adjustment for a glyph pair.
     *
     * The adjustment is expressed in font design units and is the sum of all
     * parsed horizontal format `0` subtables that contain [leftGlyphId] followed
     * by [rightGlyphId]. Summing preserves the behavior of the previous Kanvas
     * OpenType implementation for duplicate pairs and multiple usable `kern`
     * subtables. A missing pair returns `0`.
     *
     * @param leftGlyphId Glyph ID of the leading glyph in the pair.
     * @param rightGlyphId Glyph ID of the trailing glyph in the pair.
     * @return Signed kerning adjustment in font design units, or `0` when the
     * pair is not present.
     * @throws IllegalArgumentException when either glyph ID is outside the
     * OpenType unsigned 16-bit glyph ID range.
     */
    fun lookupKerningAdjustment(leftGlyphId: Int, rightGlyphId: Int): Int {
        require(leftGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType kern left glyph ID $leftGlyphId is outside range 0..65535."
        }
        require(rightGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType kern right glyph ID $rightGlyphId is outside range 0..65535."
        }
        return subtables.sumOf { subtable ->
            subtable.lookupKerningAdjustment(leftGlyphId, rightGlyphId)
        }
    }
}

/**
 * Parsed legacy OpenType `kern` format `0` subtable.
 *
 * Format `0` stores sorted glyph-pair records with signed `FWORD` kerning
 * values. The search metadata is exposed because it is part of the on-disk
 * subtable header, even though this parser performs a direct bounded read and
 * the lookup API intentionally hides binary-search mechanics from callers.
 *
 * @property version Raw subtable version. The parser accepts only `0`.
 * @property length Declared byte length of this subtable, including its six-byte
 * subtable header.
 * @property coverage Parsed coverage flags and format selector.
 * @property searchRange Raw binary-search searchRange field from the format `0`
 * header.
 * @property entrySelector Raw binary-search entrySelector field from the format
 * `0` header.
 * @property rangeShift Raw binary-search rangeShift field from the format `0`
 * header.
 * @property pairs Parsed glyph-pair kerning records in subtable order.
 */
data class OpenTypeKernFormat0Subtable(
    val version: Int,
    val length: Int,
    val coverage: OpenTypeKernCoverage,
    val searchRange: Int,
    val entrySelector: Int,
    val rangeShift: Int,
    val pairs: List<OpenTypeKernPair>,
) {
    /**
     * Returns the signed adjustment contributed by this subtable for a glyph pair.
     *
     * Duplicate records are accumulated to match the previous Kanvas behavior.
     * A pair not present in [pairs] contributes `0`.
     */
    fun lookupKerningAdjustment(leftGlyphId: Int, rightGlyphId: Int): Int =
        pairs.sumOf { pair ->
            if (pair.leftGlyphId == leftGlyphId && pair.rightGlyphId == rightGlyphId) {
                pair.value
            } else {
                0
            }
        }
}

/**
 * Coverage flags from a version `0` OpenType `kern` subtable.
 *
 * @property raw Raw unsigned 16-bit coverage word.
 * @property format Subtable format selector from the high byte.
 * @property horizontal Whether this subtable applies horizontal kerning.
 * @property minimum Whether this subtable carries minimum values instead of
 * kerning adjustments.
 * @property crossStream Whether this subtable applies cross-stream kerning.
 * @property overridePrevious Whether the subtable requests override behavior
 * for earlier subtables. The current lookup API preserves legacy Kanvas
 * accumulation behavior and exposes this bit as metadata.
 */
data class OpenTypeKernCoverage(
    val raw: Int,
    val format: Int = raw ushr 8,
    val horizontal: Boolean = raw and KERN_HORIZONTAL_FLAG != 0,
    val minimum: Boolean = raw and KERN_MINIMUM_FLAG != 0,
    val crossStream: Boolean = raw and KERN_CROSS_STREAM_FLAG != 0,
    val overridePrevious: Boolean = raw and KERN_OVERRIDE_FLAG != 0,
)

/**
 * One glyph-pair adjustment record from an OpenType `kern` format `0` subtable.
 *
 * @property leftGlyphId Glyph ID of the leading glyph in the pair.
 * @property rightGlyphId Glyph ID of the trailing glyph in the pair.
 * @property value Signed kerning adjustment in font design units.
 */
data class OpenTypeKernPair(
    val leftGlyphId: Int,
    val rightGlyphId: Int,
    val value: Int,
)

/**
 * Parser for legacy OpenType `kern` table bytes.
 */
object OpenTypeKernTableParser {
    /**
     * Parses a bounded version `0` OpenType `kern` table.
     *
     * The parser validates all declared headers and pair arrays before reading
     * them. It interprets horizontal format `0` subtables whose `minimum` and
     * `crossStream` bits are clear. Other bounded subtable formats are ignored
     * by the typed model and remain available through raw SFNT table storage.
     *
     * @param table Raw `kern` table payload bytes.
     * @return Parsed [OpenTypeKernTable] with any usable horizontal format `0`
     * subtables.
     * @throws IllegalArgumentException when the table header, subtable headers,
     * declared lengths, or format `0` pair arrays exceed the supplied bytes or
     * use an unsupported table/subtable version.
     */
    fun parse(table: ByteArray): OpenTypeKernTable {
        require(table.size >= KERN_TABLE_HEADER_SIZE) {
            "OpenType kern table must contain at least $KERN_TABLE_HEADER_SIZE bytes for the header."
        }

        val version = table.readUInt16BE(0, "OpenType kern table version")
        require(version == 0) {
            "OpenType kern table version $version is not supported; expected 0."
        }
        val subtableCount = table.readUInt16BE(2, "OpenType kern table nTables")
        val subtables = mutableListOf<OpenTypeKernFormat0Subtable>()
        var subtableOffset = KERN_TABLE_HEADER_SIZE

        repeat(subtableCount) { subtableIndex ->
            table.requireRange(
                subtableOffset,
                KERN_SUBTABLE_HEADER_SIZE,
                "OpenType kern subtable $subtableIndex header",
            )
            val subtableVersion = table.readUInt16BE(
                subtableOffset,
                "OpenType kern subtable $subtableIndex version",
            )
            require(subtableVersion == 0) {
                "OpenType kern subtable $subtableIndex version $subtableVersion is not supported; expected 0."
            }
            val subtableLength = table.readUInt16BE(
                subtableOffset + 2,
                "OpenType kern subtable $subtableIndex length",
            )
            require(subtableLength >= KERN_SUBTABLE_HEADER_SIZE) {
                "OpenType kern subtable $subtableIndex length $subtableLength is shorter than header size $KERN_SUBTABLE_HEADER_SIZE."
            }
            table.requireRange(
                subtableOffset,
                subtableLength,
                "OpenType kern subtable $subtableIndex",
            )

            val coverage = OpenTypeKernCoverage(
                raw = table.readUInt16BE(
                    subtableOffset + 4,
                    "OpenType kern subtable $subtableIndex coverage",
                ),
            )
            if (coverage.format == 0 &&
                coverage.horizontal &&
                !coverage.minimum &&
                !coverage.crossStream
            ) {
                subtables += parseFormat0Subtable(
                    table = table,
                    subtableIndex = subtableIndex,
                    subtableOffset = subtableOffset,
                    subtableVersion = subtableVersion,
                    subtableLength = subtableLength,
                    coverage = coverage,
                )
            }
            subtableOffset += subtableLength
        }

        return OpenTypeKernTable(
            version = version,
            subtables = subtables,
        )
    }

    private fun parseFormat0Subtable(
        table: ByteArray,
        subtableIndex: Int,
        subtableOffset: Int,
        subtableVersion: Int,
        subtableLength: Int,
        coverage: OpenTypeKernCoverage,
    ): OpenTypeKernFormat0Subtable {
        val bodyOffset = subtableOffset + KERN_SUBTABLE_HEADER_SIZE
        table.requireRange(
            bodyOffset,
            KERN_FORMAT0_HEADER_SIZE,
            "OpenType kern format 0 header for subtable $subtableIndex",
        )

        val pairCount = table.readUInt16BE(bodyOffset, "OpenType kern format 0 nPairs for subtable $subtableIndex")
        val searchRange = table.readUInt16BE(bodyOffset + 2, "OpenType kern format 0 searchRange for subtable $subtableIndex")
        val entrySelector = table.readUInt16BE(bodyOffset + 4, "OpenType kern format 0 entrySelector for subtable $subtableIndex")
        val rangeShift = table.readUInt16BE(bodyOffset + 6, "OpenType kern format 0 rangeShift for subtable $subtableIndex")
        val pairArrayOffset = bodyOffset + KERN_FORMAT0_HEADER_SIZE
        val pairArrayEnd = pairArrayOffset.toLong() + pairCount.toLong() * KERN_FORMAT0_PAIR_SIZE
        val subtableEnd = subtableOffset.toLong() + subtableLength
        require(pairArrayEnd <= subtableEnd) {
            "OpenType kern format 0 pair array for subtable $subtableIndex ends at $pairArrayEnd but subtable ends at $subtableEnd."
        }

        val pairs = List(pairCount) { pairIndex ->
            val pairOffset = pairArrayOffset + pairIndex * KERN_FORMAT0_PAIR_SIZE
            OpenTypeKernPair(
                leftGlyphId = table.readUInt16BE(
                    pairOffset,
                    "OpenType kern format 0 left glyph ID[$pairIndex] for subtable $subtableIndex",
                ),
                rightGlyphId = table.readUInt16BE(
                    pairOffset + UINT16_BYTE_LENGTH,
                    "OpenType kern format 0 right glyph ID[$pairIndex] for subtable $subtableIndex",
                ),
                value = table.readInt16BE(
                    pairOffset + UINT16_BYTE_LENGTH * 2,
                    "OpenType kern format 0 value[$pairIndex] for subtable $subtableIndex",
                ),
            )
        }

        return OpenTypeKernFormat0Subtable(
            version = subtableVersion,
            length = subtableLength,
            coverage = coverage,
            searchRange = searchRange,
            entrySelector = entrySelector,
            rangeShift = rangeShift,
            pairs = pairs,
        )
    }
}

/**
 * Parsed bounded subset of OpenType GPOS value-record fields.
 *
 * Only the fields currently consumed by the pure Kotlin text shaping slice are
 * surfaced. Missing fields default to `0` and unsupported bits are ignored
 * after bounded traversal has advanced past them.
 *
 * @property xPlacement Signed horizontal placement adjustment in font design units.
 * @property yPlacement Signed vertical placement adjustment in font design units.
 * @property xAdvance Signed horizontal advance adjustment in font design units.
 */
data class OpenTypeGposValueRecord(
    val xPlacement: Int = 0,
    val yPlacement: Int = 0,
    val xAdvance: Int = 0,
)

/**
 * One GPOS single-position adjustment associated with a glyph ID.
 *
 * @property glyphId Glyph ID that receives the value record.
 * @property valueRecord Parsed placement/advance adjustments in font design units.
 */
data class OpenTypeGposSingleAdjustment(
    val glyphId: Int,
    val valueRecord: OpenTypeGposValueRecord,
) {
    init {
        require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS glyph ID $glyphId is outside range 0..65535."
        }
    }
}

/**
 * Parsed bounded subset of OpenType GPOS single positioning.
 *
 * @property adjustments Parsed glyph adjustments in table traversal order.
 */
data class OpenTypeGposSingleTable(
    val adjustments: List<OpenTypeGposSingleAdjustment> = emptyList(),
) {
    private val adjustmentsByGlyphId: Map<Int, OpenTypeGposValueRecord> =
        adjustments.associate { adjustment -> adjustment.glyphId to adjustment.valueRecord }

    /**
     * Returns the parsed value record for [glyphId], or `null` when absent.
     *
     * @throws IllegalArgumentException when [glyphId] is outside the unsigned
     * 16-bit OpenType glyph ID range.
     */
    fun lookupAdjustment(glyphId: Int): OpenTypeGposValueRecord? {
        require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS glyph ID $glyphId is outside range 0..65535."
        }
        return adjustmentsByGlyphId[glyphId]
    }
}

/**
 * Parsed bounded OpenType GDEF metadata used by shaping.
 *
 * @property glyphClasses Map from glyph id to GDEF glyph class.
 * @property markAttachmentClasses Map from glyph id to mark attachment class.
 * @property ligatureCaretPositions Map from glyph id to ligature caret x positions.
 */
data class OpenTypeGdefTable(
    val glyphClasses: Map<Int, Int> = emptyMap(),
    val markAttachmentClasses: Map<Int, Int> = emptyMap(),
    val ligatureCaretPositions: Map<Int, List<Int>> = emptyMap(),
)

/**
 * Parsed OpenType anchor table fact used by GPOS mark and cursive lookups.
 *
 * @property format Anchor format as stored in the font.
 * @property x Signed x coordinate in font design units.
 * @property y Signed y coordinate in font design units.
 * @property pointIndex Optional contour point index for format 2 anchors.
 * @property variationDeviceOffset Optional device/variation offset placeholder for format 3 anchors.
 */
data class OpenTypeAnchor(
    val format: Int,
    val x: Int,
    val y: Int,
    val pointIndex: Int? = null,
    val variationDeviceOffset: Int? = null,
)

/**
 * Parsed bounded OpenType GPOS lookup table subset in feature traversal order.
 *
 * @property lookups Parsed GPOS lookups in active feature traversal order.
 */
data class OpenTypeGposTable(
    val lookups: List<OpenTypeGposLookup> = emptyList(),
)

/**
 * One parsed bounded GPOS lookup tied to the feature that activates it.
 */
sealed interface OpenTypeGposLookup {
    val featureTag: String
    val lookupIndex: Int
    val lookupType: Int
}

data class OpenTypeGposCursiveAttachment(
    val glyphId: Int,
    val entryAnchor: OpenTypeAnchor?,
    val exitAnchor: OpenTypeAnchor?,
)

data class OpenTypeGposCursiveLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
    val attachments: List<OpenTypeGposCursiveAttachment> = emptyList(),
) : OpenTypeGposLookup {
    override val lookupType: Int = GPOS_CURSIVE_ATTACHMENT_LOOKUP_TYPE
}

data class OpenTypeGposMarkToBaseAttachment(
    val markGlyphId: Int,
    val baseGlyphId: Int,
    val markClass: Int,
    val markAnchor: OpenTypeAnchor,
    val baseAnchor: OpenTypeAnchor,
)

data class OpenTypeGposMarkToBaseLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
    val attachments: List<OpenTypeGposMarkToBaseAttachment> = emptyList(),
) : OpenTypeGposLookup {
    override val lookupType: Int = GPOS_MARK_TO_BASE_LOOKUP_TYPE
}

data class OpenTypeGposMarkToLigatureAttachment(
    val markGlyphId: Int,
    val ligatureGlyphId: Int,
    val componentIndex: Int,
    val markClass: Int,
    val markAnchor: OpenTypeAnchor,
    val ligatureAnchor: OpenTypeAnchor,
)

data class OpenTypeGposMarkToLigatureLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
    val attachments: List<OpenTypeGposMarkToLigatureAttachment> = emptyList(),
) : OpenTypeGposLookup {
    override val lookupType: Int = GPOS_MARK_TO_LIGATURE_LOOKUP_TYPE
}

data class OpenTypeGposMarkToMarkAttachment(
    val mark1GlyphId: Int,
    val mark2GlyphId: Int,
    val markClass: Int,
    val mark1Anchor: OpenTypeAnchor,
    val mark2Anchor: OpenTypeAnchor,
)

data class OpenTypeGposMarkToMarkLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
    val attachments: List<OpenTypeGposMarkToMarkAttachment> = emptyList(),
) : OpenTypeGposLookup {
    override val lookupType: Int = GPOS_MARK_TO_MARK_LOOKUP_TYPE
}

data class OpenTypeGposMalformedLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
    override val lookupType: Int,
    val message: String,
) : OpenTypeGposLookup

/**
 * Parsed bounded GSUB lookup table subset in feature traversal order.
 *
 * The current slice preserves only the simple substitution lookup classes
 * needed by KFONT-M6-002: single, multiple, and ligature substitutions.
 *
 * @property lookups Parsed GSUB lookups in active feature traversal order.
 */
data class OpenTypeGsubTable(
    val lookups: List<OpenTypeGsubLookup> = emptyList(),
)

/**
 * One parsed bounded GSUB lookup tied to the feature that activates it.
 */
sealed interface OpenTypeGsubLookup {
    val featureTag: String
    val extraFeatureTags: Set<String>
    val lookupIndex: Int
    val lookupType: Int

    val topLevelFeatureTags: Set<String>
        get() = linkedSetOf<String>().apply {
            if (featureTag.isNotBlank()) add(featureTag)
            addAll(extraFeatureTags)
        }
}

/**
 * Parsed GSUB single substitution lookup.
 */
data class OpenTypeGsubSingleSubstitutionLookup(
    override val featureTag: String,
    override val extraFeatureTags: Set<String> = emptySet(),
    override val lookupIndex: Int = 0,
    val substitutions: List<OpenTypeGsubSingleSubstitution> = emptyList(),
) : OpenTypeGsubLookup {
    override val lookupType: Int = GSUB_SINGLE_SUBSTITUTION_LOOKUP_TYPE
}

/**
 * One one-to-one GSUB glyph replacement.
 */
data class OpenTypeGsubSingleSubstitution(
    val inputGlyphId: Int,
    val replacementGlyphId: Int,
) {
    init {
        require(inputGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GSUB input glyph ID $inputGlyphId is outside range 0..65535."
        }
        require(replacementGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GSUB replacement glyph ID $replacementGlyphId is outside range 0..65535."
        }
    }
}

/**
 * Parsed GSUB multiple substitution lookup.
 */
data class OpenTypeGsubMultipleSubstitutionLookup(
    override val featureTag: String,
    override val extraFeatureTags: Set<String> = emptySet(),
    override val lookupIndex: Int = 0,
    val substitutions: List<OpenTypeGsubMultipleSubstitution> = emptyList(),
) : OpenTypeGsubLookup {
    override val lookupType: Int = GSUB_MULTIPLE_SUBSTITUTION_LOOKUP_TYPE
}

/**
 * One one-to-many GSUB glyph replacement.
 */
data class OpenTypeGsubMultipleSubstitution(
    val inputGlyphId: Int,
    val replacementGlyphIds: List<Int>,
) {
    init {
        require(inputGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GSUB input glyph ID $inputGlyphId is outside range 0..65535."
        }
        require(replacementGlyphIds.isNotEmpty()) {
            "OpenType GSUB multiple substitution for glyph $inputGlyphId must contain at least one replacement glyph."
        }
        replacementGlyphIds.forEach { glyphId ->
            require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB replacement glyph ID $glyphId is outside range 0..65535."
            }
        }
    }
}

/**
 * Parsed GSUB ligature substitution lookup.
 */
data class OpenTypeGsubLigatureSubstitutionLookup(
    override val featureTag: String,
    override val extraFeatureTags: Set<String> = emptySet(),
    override val lookupIndex: Int = 0,
    val substitutions: List<OpenTypeGsubLigatureSubstitution> = emptyList(),
) : OpenTypeGsubLookup {
    override val lookupType: Int = GSUB_LIGATURE_SUBSTITUTION_LOOKUP_TYPE
}

/**
 * One many-to-one GSUB ligature replacement.
 */
data class OpenTypeGsubLigatureSubstitution(
    val inputGlyphIds: List<Int>,
    val replacementGlyphId: Int,
) {
    init {
        require(inputGlyphIds.size >= 2) {
            "OpenType GSUB ligature substitution must consume at least two glyphs."
        }
        inputGlyphIds.forEach { glyphId ->
            require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB input glyph ID $glyphId is outside range 0..65535."
            }
        }
        require(replacementGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GSUB ligature glyph ID $replacementGlyphId is outside range 0..65535."
        }
    }
}

data class OpenTypeGsubNestedLookupRecord(
    val sequenceIndex: Int,
    val lookupIndex: Int,
) {
    init {
        require(sequenceIndex in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GSUB nested sequence index $sequenceIndex is outside range 0..65535."
        }
        require(lookupIndex in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GSUB nested lookup index $lookupIndex is outside range 0..65535."
        }
    }
}

data class OpenTypeGsubContextGlyphLookup(
    override val featureTag: String,
    override val extraFeatureTags: Set<String> = emptySet(),
    override val lookupIndex: Int = 0,
    val rules: List<OpenTypeGsubContextGlyphRule> = emptyList(),
) : OpenTypeGsubLookup {
    override val lookupType: Int = GSUB_CONTEXTUAL_SUBSTITUTION_LOOKUP_TYPE
}

data class OpenTypeGsubContextGlyphRule(
    val inputGlyphIds: List<Int>,
    val nestedLookups: List<OpenTypeGsubNestedLookupRecord>,
) {
    init {
        require(inputGlyphIds.size >= 2) {
            "OpenType GSUB contextual glyph rule must consume at least two glyphs."
        }
        inputGlyphIds.forEach { glyphId ->
            require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB contextual glyph ID $glyphId is outside range 0..65535."
            }
        }
        require(nestedLookups.isNotEmpty()) {
            "OpenType GSUB contextual glyph rule must contain at least one nested lookup record."
        }
    }
}

data class OpenTypeGsubContextClassLookup(
    override val featureTag: String,
    override val extraFeatureTags: Set<String> = emptySet(),
    override val lookupIndex: Int = 0,
    val firstGlyphCoverage: Set<Int> = emptySet(),
    val classDefinitions: Map<Int, Int> = emptyMap(),
    val rules: List<OpenTypeGsubContextClassRule> = emptyList(),
    val subtables: List<OpenTypeGsubContextClassSubtable> = emptyList(),
) : OpenTypeGsubLookup {
    override val lookupType: Int = GSUB_CONTEXTUAL_SUBSTITUTION_LOOKUP_TYPE

    init {
        firstGlyphCoverage.forEach { glyphId ->
            require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB contextual class coverage glyph ID $glyphId is outside range 0..65535."
            }
        }
    }
}

data class OpenTypeGsubContextClassSubtable(
    val firstGlyphCoverage: Set<Int> = emptySet(),
    val classDefinitions: Map<Int, Int> = emptyMap(),
    val rules: List<OpenTypeGsubContextClassRule> = emptyList(),
) {
    init {
        firstGlyphCoverage.forEach { glyphId ->
            require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB contextual class coverage glyph ID $glyphId is outside range 0..65535."
            }
        }
        classDefinitions.forEach { (glyphId, classId) ->
            require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB contextual class-definition glyph ID $glyphId is outside range 0..65535."
            }
            require(classId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB contextual class-definition class ID $classId is outside range 0..65535."
            }
        }
    }
}

data class OpenTypeGsubContextClassRule(
    val inputClasses: List<Int>,
    val nestedLookups: List<OpenTypeGsubNestedLookupRecord>,
) {
    init {
        require(inputClasses.size >= 2) {
            "OpenType GSUB contextual class rule must consume at least two glyph positions."
        }
        inputClasses.forEach { classId ->
            require(classId in OPENTYPE_GLYPH_ID_RANGE) {
                "OpenType GSUB contextual class ID $classId is outside range 0..65535."
            }
        }
        require(nestedLookups.isNotEmpty()) {
            "OpenType GSUB contextual class rule must contain at least one nested lookup record."
        }
    }
}

data class OpenTypeGsubContextCoverageLookup(
    override val featureTag: String,
    override val extraFeatureTags: Set<String> = emptySet(),
    override val lookupIndex: Int = 0,
    val rules: List<OpenTypeGsubContextCoverageRule> = emptyList(),
) : OpenTypeGsubLookup {
    override val lookupType: Int = GSUB_CONTEXTUAL_SUBSTITUTION_LOOKUP_TYPE
}

data class OpenTypeGsubContextCoverageRule(
    val inputCoverages: List<Set<Int>>,
    val nestedLookups: List<OpenTypeGsubNestedLookupRecord>,
) {
    init {
        require(inputCoverages.size >= 2) {
            "OpenType GSUB contextual coverage rule must consume at least two glyph positions."
        }
        inputCoverages.forEachIndexed { index, coverage ->
            require(coverage.isNotEmpty()) {
                "OpenType GSUB contextual coverage at index $index must not be empty."
            }
            coverage.forEach { glyphId ->
                require(glyphId in OPENTYPE_GLYPH_ID_RANGE) {
                    "OpenType GSUB contextual coverage glyph ID $glyphId is outside range 0..65535."
                }
            }
        }
        require(nestedLookups.isNotEmpty()) {
            "OpenType GSUB contextual coverage rule must contain at least one nested lookup record."
        }
    }
}

/**
 * GSUB chaining context substitution (lookup type 6) glyph rule for format 1.
 */
data class OpenTypeGsubChainingContextGlyphRule(
    val backtrackGlyphIds: List<Int>,
    val inputGlyphIds: List<Int>,
    val lookAheadGlyphIds: List<Int>,
    val nestedLookups: List<OpenTypeGsubNestedLookupRecord>,
) {
    init {
        require(inputGlyphIds.isNotEmpty()) {
            "OpenType GSUB chaining context glyph rule must consume at least one glyph."
        }
        require(nestedLookups.isNotEmpty()) {
            "OpenType GSUB chaining context glyph rule must contain at least one nested lookup record."
        }
    }
}

/**
 * GSUB chaining context substitution format 1 (glyph) lookup.
 */
data class OpenTypeGsubChainingContextGlyphLookup(
    override val featureTag: String,
    override val extraFeatureTags: Set<String> = emptySet(),
    override val lookupIndex: Int = 0,
    val rules: List<OpenTypeGsubChainingContextGlyphRule> = emptyList(),
) : OpenTypeGsubLookup {
    override val lookupType: Int = GSUB_CHAINING_CONTEXT_SUBSTITUTION_LOOKUP_TYPE
}

/**
 * GPOS contextual positioning (lookup type 7) placeholder.
 * Parsed but not applied at shaping runtime.
 */
data class OpenTypeGposContextualLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
) : OpenTypeGposLookup {
    override val lookupType: Int = GPOS_CONTEXTUAL_POSITIONING_LOOKUP_TYPE
}

/**
 * GPOS chaining contextual positioning (lookup type 8) placeholder.
 */
data class OpenTypeGposChainingLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
) : OpenTypeGposLookup {
    override val lookupType: Int = GPOS_CHAINING_CONTEXT_POSITIONING_LOOKUP_TYPE
}

/**
 * GPOS extension positioning (lookup type 9) placeholder.
 */
data class OpenTypeGposExtensionLookup(
    override val featureTag: String,
    override val lookupIndex: Int,
) : OpenTypeGposLookup {
    override val lookupType: Int = GPOS_EXTENSION_POSITIONING_LOOKUP_TYPE
}

/**
 * Parser for the Kanvas-supported subset of OpenType GSUB lookups.
 */
object OpenTypeGsubTableParser {
    fun parse(table: ByteArray): OpenTypeGsubTable {
        table.requireRange(0, GPOS_HEADER_SIZE, "OpenType GSUB header")

        val majorVersion = table.readUInt16BE(0, "OpenType GSUB majorVersion")
        val minorVersion = table.readUInt16BE(2, "OpenType GSUB minorVersion")
        require(majorVersion == 1 && minorVersion in 0..1) {
            "OpenType GSUB table version $majorVersion.$minorVersion is not supported; expected 1.0 or 1.1."
        }

        val scriptListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(4, "OpenType GSUB ScriptList offset"),
            label = "OpenType GSUB ScriptList",
        )
        val featureListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(6, "OpenType GSUB FeatureList offset"),
            label = "OpenType GSUB FeatureList",
        )
        val lookupListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(8, "OpenType GSUB LookupList offset"),
            label = "OpenType GSUB LookupList",
        )
        val featureLookups = parseActiveFeatureLookups(
            table = table,
            scriptListStart = scriptListStart,
            featureListStart = featureListStart,
        )
        if (featureLookups.isEmpty()) {
            return OpenTypeGsubTable()
        }

        table.requireRange(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GSUB LookupList header")
        val lookupCount = table.readUInt16BE(lookupListStart, "OpenType GSUB LookupList lookupCount")
        table.requireArrayRange(
            offset = table.checkedOffset(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GSUB LookupList offsets"),
            count = lookupCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB LookupList offsets",
        )

        val topLevelFeatureTagsByLookupIndex = LinkedHashMap<Int, LinkedHashSet<String>>()
        featureLookups.forEach { featureLookup ->
            require(featureLookup.lookupIndex in 0 until lookupCount) {
                "OpenType GSUB lookup index ${featureLookup.lookupIndex} is outside LookupList range 0 until $lookupCount."
            }
            topLevelFeatureTagsByLookupIndex.getOrPut(featureLookup.lookupIndex) { LinkedHashSet() } += featureLookup.featureTag
        }

        val lookups = mutableListOf<OpenTypeGsubLookup>()
        repeat(lookupCount) { lookupIndex ->
            val lookupOffset = table.readUInt16BE(
                lookupListStart + UINT16_BYTE_LENGTH + lookupIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LookupList offset[$lookupIndex]",
            )
            parseLookup(
                table = table,
                lookupStart = table.checkedOffset(
                    base = lookupListStart,
                    offset = lookupOffset,
                    label = "OpenType GSUB lookup $lookupIndex",
                ),
                lookupIndex = lookupIndex,
                featureTags = topLevelFeatureTagsByLookupIndex[lookupIndex].orEmpty(),
            )?.let(lookups::add)
        }

        return OpenTypeGsubTable(lookups = lookups)
    }

    private fun parseActiveFeatureLookups(
        table: ByteArray,
        scriptListStart: Int,
        featureListStart: Int,
    ): List<OpenTypeGsubFeatureLookupRef> {
        val activeFeatureIndices = parseActiveFeatureIndices(table, scriptListStart)
        if (activeFeatureIndices.isEmpty()) {
            return emptyList()
        }

        table.requireRange(featureListStart, UINT16_BYTE_LENGTH, "OpenType GSUB FeatureList header")
        val featureCount = table.readUInt16BE(featureListStart, "OpenType GSUB FeatureList featureCount")
        table.requireArrayRange(
            offset = table.checkedOffset(featureListStart, UINT16_BYTE_LENGTH, "OpenType GSUB FeatureRecords"),
            count = featureCount,
            recordSize = GPOS_FEATURE_RECORD_SIZE,
            label = "OpenType GSUB FeatureRecords",
        )

        val lookups = mutableListOf<OpenTypeGsubFeatureLookupRef>()
        repeat(featureCount) { featureIndex ->
            val record = featureListStart + UINT16_BYTE_LENGTH + featureIndex * GPOS_FEATURE_RECORD_SIZE
            val tag = table.readTag(record, "OpenType GSUB FeatureRecord $featureIndex tag")
            val featureOffset = table.readUInt16BE(
                record + SFNT_TAG_BYTE_LENGTH,
                "OpenType GSUB FeatureRecord $featureIndex offset",
            )
            if (featureIndex !in activeFeatureIndices) {
                return@repeat
            }

            val featureStart = table.checkedOffset(
                base = featureListStart,
                offset = featureOffset,
                label = "OpenType GSUB FeatureTable $featureIndex",
            )
            table.requireRange(featureStart, GPOS_FEATURE_HEADER_SIZE, "OpenType GSUB FeatureTable $featureIndex")
            val lookupIndexCount = table.readUInt16BE(
                featureStart + UINT16_BYTE_LENGTH,
                "OpenType GSUB FeatureTable $featureIndex lookupIndexCount",
            )
            table.requireArrayRange(
                offset = table.checkedOffset(featureStart, GPOS_FEATURE_HEADER_SIZE, "OpenType GSUB Feature lookup indices"),
                count = lookupIndexCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GSUB FeatureTable $featureIndex lookup indices",
            )
            repeat(lookupIndexCount) { lookupIndexRecord ->
                lookups += OpenTypeGsubFeatureLookupRef(
                    featureTag = tag,
                    lookupIndex = table.readUInt16BE(
                        featureStart + GPOS_FEATURE_HEADER_SIZE + lookupIndexRecord * UINT16_BYTE_LENGTH,
                        "OpenType GSUB FeatureTable $featureIndex lookupListIndex[$lookupIndexRecord]",
                    ),
                )
            }
        }

        return lookups
    }

    private fun parseActiveFeatureIndices(
        table: ByteArray,
        scriptListStart: Int,
    ): Set<Int> {
        table.requireRange(scriptListStart, UINT16_BYTE_LENGTH, "OpenType GSUB ScriptList header")
        val scriptCount = table.readUInt16BE(scriptListStart, "OpenType GSUB ScriptList scriptCount")
        table.requireArrayRange(
            offset = table.checkedOffset(scriptListStart, UINT16_BYTE_LENGTH, "OpenType GSUB ScriptRecords"),
            count = scriptCount,
            recordSize = GPOS_SCRIPT_RECORD_SIZE,
            label = "OpenType GSUB ScriptRecords",
        )

        val features = LinkedHashSet<Int>()
        repeat(scriptCount) { scriptIndex ->
            val record = scriptListStart + UINT16_BYTE_LENGTH + scriptIndex * GPOS_SCRIPT_RECORD_SIZE
            val tag = table.readTag(record, "OpenType GSUB ScriptRecord $scriptIndex tag")
            val scriptOffset = table.readUInt16BE(
                record + SFNT_TAG_BYTE_LENGTH,
                "OpenType GSUB ScriptRecord $scriptIndex offset",
            )
            if (tag == "DFLT" || tag == "latn") {
                collectScriptFeatureIndices(
                    table = table,
                    scriptStart = table.checkedOffset(
                        base = scriptListStart,
                        offset = scriptOffset,
                        label = "OpenType GSUB ScriptTable $tag",
                    ),
                    features = features,
                )
            }
        }

        return features
    }

    private fun collectScriptFeatureIndices(
        table: ByteArray,
        scriptStart: Int,
        features: MutableSet<Int>,
    ) {
        table.requireRange(scriptStart, 4, "OpenType GSUB ScriptTable header")
        val defaultLangSysOffset = table.readUInt16BE(
            scriptStart,
            "OpenType GSUB ScriptTable defaultLangSys offset",
        )
        val langSysCount = table.readUInt16BE(
            scriptStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB ScriptTable langSysCount",
        )

        if (defaultLangSysOffset != 0) {
            collectLangSysFeatureIndices(
                table = table,
                langSysStart = table.checkedOffset(
                    base = scriptStart,
                    offset = defaultLangSysOffset,
                    label = "OpenType GSUB default LangSys",
                ),
                features = features,
            )
        }

        table.requireArrayRange(
            offset = table.checkedOffset(scriptStart, 4, "OpenType GSUB LangSysRecords"),
            count = langSysCount,
            recordSize = GPOS_LANG_SYS_RECORD_SIZE,
            label = "OpenType GSUB LangSysRecords",
        )
        repeat(langSysCount) { langSysIndex ->
            val record = scriptStart + 4 + langSysIndex * GPOS_LANG_SYS_RECORD_SIZE
            val langSysOffset = table.readUInt16BE(
                record + SFNT_TAG_BYTE_LENGTH,
                "OpenType GSUB LangSysRecord $langSysIndex offset",
            )
            collectLangSysFeatureIndices(
                table = table,
                langSysStart = table.checkedOffset(
                    base = scriptStart,
                    offset = langSysOffset,
                    label = "OpenType GSUB LangSysRecord $langSysIndex",
                ),
                features = features,
            )
        }
    }

    private fun collectLangSysFeatureIndices(
        table: ByteArray,
        langSysStart: Int,
        features: MutableSet<Int>,
    ) {
        table.requireRange(langSysStart, GPOS_LANG_SYS_HEADER_SIZE, "OpenType GSUB LangSys table")
        val requiredFeatureIndex = table.readUInt16BE(
            langSysStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB LangSys requiredFeatureIndex",
        )
        if (requiredFeatureIndex != GPOS_REQUIRED_FEATURE_NONE) {
            features += requiredFeatureIndex
        }

        val featureIndexCount = table.readUInt16BE(
            langSysStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB LangSys featureIndexCount",
        )
        table.requireArrayRange(
            offset = table.checkedOffset(langSysStart, GPOS_LANG_SYS_HEADER_SIZE, "OpenType GSUB LangSys feature indices"),
            count = featureIndexCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB LangSys feature indices",
        )
        repeat(featureIndexCount) { featureIndexRecord ->
            features += table.readUInt16BE(
                langSysStart + GPOS_LANG_SYS_HEADER_SIZE + featureIndexRecord * UINT16_BYTE_LENGTH,
                "OpenType GSUB LangSys featureIndex[$featureIndexRecord]",
            )
        }
    }

    private fun parseLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubLookup? {
        table.requireRange(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GSUB LookupTable header")
        val lookupType = table.readUInt16BE(lookupStart, "OpenType GSUB LookupTable lookupType")
        val subtableCount = table.readUInt16BE(
            lookupStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB LookupTable subTableCount",
        )
        table.requireArrayRange(
            offset = table.checkedOffset(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GSUB LookupTable subtable offsets"),
            count = subtableCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB LookupTable subtable offsets",
        )

        return when (lookupType) {
            GSUB_SINGLE_SUBSTITUTION_LOOKUP_TYPE -> parseSingleLookup(table, lookupStart, lookupIndex, subtableCount, featureTags)
            GSUB_MULTIPLE_SUBSTITUTION_LOOKUP_TYPE -> parseMultipleLookup(table, lookupStart, lookupIndex, subtableCount, featureTags)
            GSUB_LIGATURE_SUBSTITUTION_LOOKUP_TYPE -> parseLigatureLookup(table, lookupStart, lookupIndex, subtableCount, featureTags)
            GSUB_CONTEXTUAL_SUBSTITUTION_LOOKUP_TYPE -> parseContextLookup(table, lookupStart, lookupIndex, subtableCount, featureTags)
            GSUB_CHAINING_CONTEXT_SUBSTITUTION_LOOKUP_TYPE -> parseChainingContextLookup(table, lookupStart, lookupIndex, subtableCount, featureTags)
            GSUB_EXTENSION_SUBSTITUTION_LOOKUP_TYPE -> parseExtensionLookup(table, lookupStart, lookupIndex, subtableCount, featureTags)
            GSUB_REVERSE_CHAINING_SUBSTITUTION_LOOKUP_TYPE -> parseReverseChainingLookup(table, lookupStart, lookupIndex, subtableCount, featureTags)
            else -> null
        }
    }

    private fun parseExtensionLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        subtableCount: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubLookup? {
        val singleSubstitutions = LinkedHashMap<Int, Int>()
        val ligatureSubstitutions = mutableListOf<OpenTypeGsubLigatureSubstitution>()
        var extensionLookupType: Int? = null
        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LookupTable subtableOffset[$subtableIndex]",
            )
            val extensionStart = table.checkedOffset(
                base = lookupStart,
                offset = subtableOffset,
                label = "OpenType GSUB ExtensionSubst subtable $subtableIndex",
            )
            table.requireRange(extensionStart, 8, "OpenType GSUB ExtensionSubst subtable header")
            val substFormat = table.readUInt16BE(
                extensionStart,
                "OpenType GSUB ExtensionSubst substFormat",
            )
            require(substFormat == 1) {
                "OpenType GSUB ExtensionSubst format $substFormat is not supported."
            }
            val parsedLookupType = table.readUInt16BE(
                extensionStart + UINT16_BYTE_LENGTH,
                "OpenType GSUB ExtensionSubst extensionLookupType",
            )
            if (extensionLookupType == null) {
                extensionLookupType = parsedLookupType
            } else {
                require(extensionLookupType == parsedLookupType) {
                    "OpenType GSUB ExtensionSubst target lookup types must stay consistent within one lookup."
                }
            }
            val extensionOffset = table.readUInt32BE(
                extensionStart + UINT16_BYTE_LENGTH * 2,
                "OpenType GSUB ExtensionSubst extensionOffset",
            )
            val targetStart = table.checkedOffset(
                base = extensionStart,
                offset = extensionOffset.toInt(),
                label = "OpenType GSUB ExtensionSubst target subtable $subtableIndex",
            )
            when (parsedLookupType) {
                GSUB_SINGLE_SUBSTITUTION_LOOKUP_TYPE -> parseSingleSubtable(
                    table = table,
                    subtableStart = targetStart,
                    substitutions = singleSubstitutions,
                )
                GSUB_LIGATURE_SUBSTITUTION_LOOKUP_TYPE -> parseLigatureSubtable(
                    table = table,
                    subtableStart = targetStart,
                    substitutions = ligatureSubstitutions,
                )
                else -> throw IllegalArgumentException(
                    "OpenType GSUB ExtensionSubst target lookup type $parsedLookupType is not supported.",
                )
            }
        }
        val orderedFeatureTags = featureTags.toList()
        return when (extensionLookupType) {
            GSUB_SINGLE_SUBSTITUTION_LOOKUP_TYPE -> singleSubstitutions.takeUnless { it.isEmpty() }?.let { parsed ->
                OpenTypeGsubSingleSubstitutionLookup(
                    featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                    extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                    lookupIndex = lookupIndex,
                    substitutions = parsed.map { (inputGlyphId, replacementGlyphId) ->
                        OpenTypeGsubSingleSubstitution(inputGlyphId = inputGlyphId, replacementGlyphId = replacementGlyphId)
                    },
                )
            }
            GSUB_LIGATURE_SUBSTITUTION_LOOKUP_TYPE -> ligatureSubstitutions.takeUnless { it.isEmpty() }?.let { parsed ->
                OpenTypeGsubLigatureSubstitutionLookup(
                    featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                    extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                    lookupIndex = lookupIndex,
                    substitutions = parsed,
                )
            }
            else -> null
        }
    }

    private fun parseSingleLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        subtableCount: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubSingleSubstitutionLookup? {
        val substitutions = LinkedHashMap<Int, Int>()
        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LookupTable subtableOffset[$subtableIndex]",
            )
            parseSingleSubtable(
                table = table,
                subtableStart = table.checkedOffset(
                    base = lookupStart,
                    offset = subtableOffset,
                    label = "OpenType GSUB SingleSubst subtable $subtableIndex",
                ),
                substitutions = substitutions,
            )
        }
        return substitutions.takeUnless { it.isEmpty() }?.let { parsed ->
            val orderedFeatureTags = featureTags.toList()
            OpenTypeGsubSingleSubstitutionLookup(
                featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                lookupIndex = lookupIndex,
                substitutions = parsed.map { (inputGlyphId, replacementGlyphId) ->
                    OpenTypeGsubSingleSubstitution(inputGlyphId = inputGlyphId, replacementGlyphId = replacementGlyphId)
                },
            )
        }
    }

    private fun parseSingleSubtable(
        table: ByteArray,
        subtableStart: Int,
        substitutions: MutableMap<Int, Int>,
    ) {
        table.requireRange(subtableStart, 6, "OpenType GSUB SingleSubst subtable header")
        val substFormat = table.readUInt16BE(subtableStart, "OpenType GSUB SingleSubst substFormat")
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB SingleSubst coverageOffset",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GSUB SingleSubst coverage",
            ),
        )

        when (substFormat) {
            1 -> {
                val deltaGlyphId = table.readInt16BE(
                    subtableStart + UINT16_BYTE_LENGTH * 2,
                    "OpenType GSUB SingleSubst deltaGlyphID",
                )
                coverage.forEach { glyphId ->
                    substitutions[glyphId] = (glyphId + deltaGlyphId) and UINT16_MAX
                }
            }
            2 -> {
                val glyphCount = table.readUInt16BE(
                    subtableStart + UINT16_BYTE_LENGTH * 2,
                    "OpenType GSUB SingleSubst glyphCount",
                )
                require(glyphCount == coverage.size) {
                    "OpenType GSUB SingleSubst format 2 glyphCount $glyphCount must equal coverage glyph count ${coverage.size}."
                }
                val substitutesStart = table.checkedOffset(
                    subtableStart,
                    6,
                    "OpenType GSUB SingleSubst substitutes",
                )
                table.requireArrayRange(
                    offset = substitutesStart,
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GSUB SingleSubst substitutes",
                )
                repeat(glyphCount) { glyphIndex ->
                    substitutions[coverage[glyphIndex]] = table.readUInt16BE(
                        substitutesStart + glyphIndex * UINT16_BYTE_LENGTH,
                        "OpenType GSUB SingleSubst substituteGlyphID[$glyphIndex]",
                    )
                }
            }
            else -> throw IllegalArgumentException("OpenType GSUB SingleSubst format $substFormat is not supported.")
        }
    }

    private fun parseMultipleLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        subtableCount: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubMultipleSubstitutionLookup? {
        val substitutions = LinkedHashMap<Int, List<Int>>()
        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LookupTable subtableOffset[$subtableIndex]",
            )
            parseMultipleSubtable(
                table = table,
                subtableStart = table.checkedOffset(
                    base = lookupStart,
                    offset = subtableOffset,
                    label = "OpenType GSUB MultipleSubst subtable $subtableIndex",
                ),
                substitutions = substitutions,
            )
        }
        return substitutions.takeUnless { it.isEmpty() }?.let { parsed ->
            val orderedFeatureTags = featureTags.toList()
            OpenTypeGsubMultipleSubstitutionLookup(
                featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                lookupIndex = lookupIndex,
                substitutions = parsed.map { (inputGlyphId, replacementGlyphIds) ->
                    OpenTypeGsubMultipleSubstitution(inputGlyphId = inputGlyphId, replacementGlyphIds = replacementGlyphIds)
                },
            )
        }
    }

    private fun parseMultipleSubtable(
        table: ByteArray,
        subtableStart: Int,
        substitutions: MutableMap<Int, List<Int>>,
    ) {
        table.requireRange(subtableStart, 6, "OpenType GSUB MultipleSubst subtable header")
        val substFormat = table.readUInt16BE(subtableStart, "OpenType GSUB MultipleSubst substFormat")
        require(substFormat == 1) {
            "OpenType GSUB MultipleSubst format $substFormat is not supported."
        }
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB MultipleSubst coverageOffset",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GSUB MultipleSubst coverage",
            ),
        )
        val sequenceCount = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB MultipleSubst sequenceCount",
        )
        require(sequenceCount == coverage.size) {
            "OpenType GSUB MultipleSubst sequenceCount $sequenceCount must equal coverage glyph count ${coverage.size}."
        }
        val offsetsStart = table.checkedOffset(
            subtableStart,
            6,
            "OpenType GSUB MultipleSubst sequence offsets",
        )
        table.requireArrayRange(
            offset = offsetsStart,
            count = sequenceCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB MultipleSubst sequence offsets",
        )
        repeat(sequenceCount) { sequenceIndex ->
            val sequenceOffset = table.readUInt16BE(
                offsetsStart + sequenceIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB MultipleSubst sequenceOffset[$sequenceIndex]",
            )
            val sequenceStart = table.checkedOffset(
                base = subtableStart,
                offset = sequenceOffset,
                label = "OpenType GSUB MultipleSubst Sequence $sequenceIndex",
            )
            table.requireRange(sequenceStart, UINT16_BYTE_LENGTH, "OpenType GSUB MultipleSubst Sequence $sequenceIndex header")
            val glyphCount = table.readUInt16BE(
                sequenceStart,
                "OpenType GSUB MultipleSubst Sequence $sequenceIndex glyphCount",
            )
            require(glyphCount > 0) {
                "OpenType GSUB MultipleSubst Sequence $sequenceIndex glyphCount must be positive."
            }
            val glyphArrayStart = table.checkedOffset(
                sequenceStart,
                UINT16_BYTE_LENGTH,
                "OpenType GSUB MultipleSubst Sequence $sequenceIndex substitutes",
            )
            table.requireArrayRange(
                offset = glyphArrayStart,
                count = glyphCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GSUB MultipleSubst Sequence $sequenceIndex substitutes",
            )
            substitutions[coverage[sequenceIndex]] = List(glyphCount) { glyphIndex ->
                table.readUInt16BE(
                    glyphArrayStart + glyphIndex * UINT16_BYTE_LENGTH,
                    "OpenType GSUB MultipleSubst Sequence $sequenceIndex substituteGlyphID[$glyphIndex]",
                )
            }
        }
    }

    private fun parseContextLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        subtableCount: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubLookup? {
        val glyphRules = mutableListOf<OpenTypeGsubContextGlyphRule>()
        val classSubtables = mutableListOf<OpenTypeGsubContextClassSubtable>()
        val coverageRules = mutableListOf<OpenTypeGsubContextCoverageRule>()
        var format: Int? = null
        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LookupTable subtableOffset[$subtableIndex]",
            )
            val subtableStart = table.checkedOffset(
                base = lookupStart,
                offset = subtableOffset,
                label = "OpenType GSUB ContextSubst subtable $subtableIndex",
            )
            table.requireRange(subtableStart, 4, "OpenType GSUB ContextSubst subtable header")
            val subtableFormat = table.readUInt16BE(subtableStart, "OpenType GSUB ContextSubst substFormat")
            if (format == null) {
                format = subtableFormat
            } else {
                require(format == subtableFormat) {
                    "OpenType GSUB ContextSubst subtable formats must stay consistent within one lookup."
                }
            }
            when (subtableFormat) {
                1 -> parseContextFormat1Subtable(table, subtableStart, glyphRules)
                2 -> parseContextFormat2Subtable(table, subtableStart)?.let(classSubtables::add)
                3 -> parseContextFormat3Subtable(table, subtableStart, coverageRules)
                else -> throw IllegalArgumentException("OpenType GSUB ContextSubst format $subtableFormat is not supported.")
            }
        }
        val orderedFeatureTags = featureTags.toList()
        return when (format) {
            1 -> glyphRules.takeUnless { it.isEmpty() }?.let {
                OpenTypeGsubContextGlyphLookup(
                    featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                    extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                    lookupIndex = lookupIndex,
                    rules = it,
                )
            }
            2 -> classSubtables.takeUnless { it.isEmpty() }?.let {
                val singleSubtable = it.singleOrNull()
                OpenTypeGsubContextClassLookup(
                    featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                    extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                    lookupIndex = lookupIndex,
                    firstGlyphCoverage = singleSubtable?.firstGlyphCoverage.orEmpty(),
                    classDefinitions = singleSubtable?.classDefinitions.orEmpty(),
                    rules = singleSubtable?.rules.orEmpty(),
                    subtables = if (it.size > 1) it else emptyList(),
                )
            }
            3 -> coverageRules.takeUnless { it.isEmpty() }?.let {
                OpenTypeGsubContextCoverageLookup(
                    featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                    extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                    lookupIndex = lookupIndex,
                    rules = it,
                )
            }
            else -> null
        }
    }

    private fun parseContextFormat1Subtable(
        table: ByteArray,
        subtableStart: Int,
        rules: MutableList<OpenTypeGsubContextGlyphRule>,
    ) {
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB ContextSubst format 1 coverageOffset",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GSUB ContextSubst format 1 coverage",
            ),
        )
        val subRuleSetCount = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB ContextSubst format 1 subRuleSetCount",
        )
        require(subRuleSetCount == coverage.size) {
            "OpenType GSUB ContextSubst format 1 subRuleSetCount $subRuleSetCount must equal coverage glyph count ${coverage.size}."
        }
        val subRuleSetOffsetsStart = table.checkedOffset(
            subtableStart,
            6,
            "OpenType GSUB ContextSubst format 1 subRuleSet offsets",
        )
        table.requireArrayRange(
            offset = subRuleSetOffsetsStart,
            count = subRuleSetCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB ContextSubst format 1 subRuleSet offsets",
        )
        repeat(subRuleSetCount) { setIndex ->
            val subRuleSetOffset = table.readUInt16BE(
                subRuleSetOffsetsStart + setIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB ContextSubst format 1 subRuleSetOffset[$setIndex]",
            )
            if (subRuleSetOffset == 0) return@repeat
            val subRuleSetStart = table.checkedOffset(
                base = subtableStart,
                offset = subRuleSetOffset,
                label = "OpenType GSUB ContextSubst format 1 SubRuleSet $setIndex",
            )
            table.requireRange(
                subRuleSetStart,
                UINT16_BYTE_LENGTH,
                "OpenType GSUB ContextSubst format 1 SubRuleSet $setIndex header",
            )
            val subRuleCount = table.readUInt16BE(
                subRuleSetStart,
                "OpenType GSUB ContextSubst format 1 SubRuleSet $setIndex subRuleCount",
            )
            val subRuleOffsetsStart = table.checkedOffset(
                subRuleSetStart,
                UINT16_BYTE_LENGTH,
                "OpenType GSUB ContextSubst format 1 SubRuleSet $setIndex offsets",
            )
            table.requireArrayRange(
                offset = subRuleOffsetsStart,
                count = subRuleCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GSUB ContextSubst format 1 SubRuleSet $setIndex offsets",
            )
            repeat(subRuleCount) { ruleIndex ->
                val subRuleOffset = table.readUInt16BE(
                    subRuleOffsetsStart + ruleIndex * UINT16_BYTE_LENGTH,
                    "OpenType GSUB ContextSubst format 1 SubRuleSet $setIndex subRuleOffset[$ruleIndex]",
                )
                val subRuleStart = table.checkedOffset(
                    base = subRuleSetStart,
                    offset = subRuleOffset,
                    label = "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex",
                )
                table.requireRange(subRuleStart, 4, "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex header")
                val glyphCount = table.readUInt16BE(
                    subRuleStart,
                    "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex glyphCount",
                )
                require(glyphCount >= 2) {
                    "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex glyphCount $glyphCount must be at least 2."
                }
                val substCount = table.readUInt16BE(
                    subRuleStart + UINT16_BYTE_LENGTH,
                    "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex substCount",
                )
                val inputStart = table.checkedOffset(
                    subRuleStart,
                    4,
                    "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex input array",
                )
                table.requireArrayRange(
                    offset = inputStart,
                    count = glyphCount - 1,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex input array",
                )
                val lookupRecordsStart = table.checkedOffset(
                    inputStart,
                    (glyphCount - 1) * UINT16_BYTE_LENGTH,
                    "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex lookup records",
                )
                val nestedLookups = parseNestedLookupRecords(
                    table = table,
                    recordsStart = lookupRecordsStart,
                    substCount = substCount,
                    matchLength = glyphCount,
                    label = "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex",
                )
                rules += OpenTypeGsubContextGlyphRule(
                    inputGlyphIds = buildList(glyphCount) {
                        add(coverage[setIndex])
                        repeat(glyphCount - 1) { glyphIndex ->
                            add(
                                table.readUInt16BE(
                                    inputStart + glyphIndex * UINT16_BYTE_LENGTH,
                                    "OpenType GSUB ContextSubst format 1 SubRule $setIndex/$ruleIndex inputGlyphID[$glyphIndex]",
                                ),
                            )
                        }
                    },
                    nestedLookups = nestedLookups,
                )
            }
        }
    }

    private fun parseContextFormat2Subtable(
        table: ByteArray,
        subtableStart: Int,
    ): OpenTypeGsubContextClassSubtable? {
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB ContextSubst format 2 coverageOffset",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GSUB ContextSubst format 2 coverage",
            ),
        )
        val firstGlyphCoverage = coverage.toCollection(LinkedHashSet())
        val classDefOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB ContextSubst format 2 classDefOffset",
        )
        val parsedClassDefinitions = parseGsubClassDefTable(
            table = table,
            classDefStart = table.checkedOffset(
                base = subtableStart,
                offset = classDefOffset,
                label = "OpenType GSUB ContextSubst format 2 ClassDef",
            ),
        )
        val subClassSetCount = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 3,
            "OpenType GSUB ContextSubst format 2 subClassSetCount",
        )
        val subClassSetOffsetsStart = table.checkedOffset(
            subtableStart,
            8,
            "OpenType GSUB ContextSubst format 2 subClassSet offsets",
        )
        table.requireArrayRange(
            offset = subClassSetOffsetsStart,
            count = subClassSetCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB ContextSubst format 2 subClassSet offsets",
        )
        val rules = mutableListOf<OpenTypeGsubContextClassRule>()
        repeat(subClassSetCount) { firstClass ->
            val subClassSetOffset = table.readUInt16BE(
                subClassSetOffsetsStart + firstClass * UINT16_BYTE_LENGTH,
                "OpenType GSUB ContextSubst format 2 subClassSetOffset[$firstClass]",
            )
            if (subClassSetOffset == 0) return@repeat
            val subClassSetStart = table.checkedOffset(
                base = subtableStart,
                offset = subClassSetOffset,
                label = "OpenType GSUB ContextSubst format 2 SubClassSet $firstClass",
            )
            table.requireRange(
                subClassSetStart,
                UINT16_BYTE_LENGTH,
                "OpenType GSUB ContextSubst format 2 SubClassSet $firstClass header",
            )
            val subClassRuleCount = table.readUInt16BE(
                subClassSetStart,
                "OpenType GSUB ContextSubst format 2 SubClassSet $firstClass subClassRuleCount",
            )
            val subClassRuleOffsetsStart = table.checkedOffset(
                subClassSetStart,
                UINT16_BYTE_LENGTH,
                "OpenType GSUB ContextSubst format 2 SubClassSet $firstClass offsets",
            )
            table.requireArrayRange(
                offset = subClassRuleOffsetsStart,
                count = subClassRuleCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GSUB ContextSubst format 2 SubClassSet $firstClass offsets",
            )
            repeat(subClassRuleCount) { ruleIndex ->
                val subClassRuleOffset = table.readUInt16BE(
                    subClassRuleOffsetsStart + ruleIndex * UINT16_BYTE_LENGTH,
                    "OpenType GSUB ContextSubst format 2 SubClassSet $firstClass subClassRuleOffset[$ruleIndex]",
                )
                val subClassRuleStart = table.checkedOffset(
                    base = subClassSetStart,
                    offset = subClassRuleOffset,
                    label = "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex",
                )
                table.requireRange(
                    subClassRuleStart,
                    4,
                    "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex header",
                )
                val glyphCount = table.readUInt16BE(
                    subClassRuleStart,
                    "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex glyphCount",
                )
                require(glyphCount >= 2) {
                    "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex glyphCount $glyphCount must be at least 2."
                }
                val substCount = table.readUInt16BE(
                    subClassRuleStart + UINT16_BYTE_LENGTH,
                    "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex substCount",
                )
                val classArrayStart = table.checkedOffset(
                    subClassRuleStart,
                    4,
                    "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex class array",
                )
                table.requireArrayRange(
                    offset = classArrayStart,
                    count = glyphCount - 1,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex class array",
                )
                val lookupRecordsStart = table.checkedOffset(
                    classArrayStart,
                    (glyphCount - 1) * UINT16_BYTE_LENGTH,
                    "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex lookup records",
                )
                val nestedLookups = parseNestedLookupRecords(
                    table = table,
                    recordsStart = lookupRecordsStart,
                    substCount = substCount,
                    matchLength = glyphCount,
                    label = "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex",
                )
                rules += OpenTypeGsubContextClassRule(
                    inputClasses = buildList(glyphCount) {
                        add(firstClass)
                        repeat(glyphCount - 1) { classIndex ->
                            add(
                                table.readUInt16BE(
                                    classArrayStart + classIndex * UINT16_BYTE_LENGTH,
                                    "OpenType GSUB ContextSubst format 2 SubClassRule $firstClass/$ruleIndex class[$classIndex]",
                                ),
                            )
                        }
                    },
                    nestedLookups = nestedLookups,
                )
            }
        }
        return OpenTypeGsubContextClassSubtable(
            firstGlyphCoverage = firstGlyphCoverage,
            classDefinitions = parsedClassDefinitions.toMap(),
            rules = rules,
        ).takeUnless { it.firstGlyphCoverage.isEmpty() || it.rules.isEmpty() }
    }

    private fun parseContextFormat3Subtable(
        table: ByteArray,
        subtableStart: Int,
        rules: MutableList<OpenTypeGsubContextCoverageRule>,
    ) {
        table.requireRange(subtableStart, 6, "OpenType GSUB ContextSubst format 3 header")
        val glyphCount = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB ContextSubst format 3 glyphCount",
        )
        require(glyphCount >= 2) {
            "OpenType GSUB ContextSubst format 3 glyphCount $glyphCount must be at least 2."
        }
        val substCount = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB ContextSubst format 3 substCount",
        )
        val coverageOffsetsStart = table.checkedOffset(
            subtableStart,
            6,
            "OpenType GSUB ContextSubst format 3 coverage offsets",
        )
        table.requireArrayRange(
            offset = coverageOffsetsStart,
            count = glyphCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB ContextSubst format 3 coverage offsets",
        )
        val lookupRecordsStart = table.checkedOffset(
            coverageOffsetsStart,
            glyphCount * UINT16_BYTE_LENGTH,
            "OpenType GSUB ContextSubst format 3 lookup records",
        )
        val nestedLookups = parseNestedLookupRecords(
            table = table,
            recordsStart = lookupRecordsStart,
            substCount = substCount,
            matchLength = glyphCount,
            label = "OpenType GSUB ContextSubst format 3",
        )
        rules += OpenTypeGsubContextCoverageRule(
            inputCoverages = List(glyphCount) { coverageIndex ->
                val coverageOffset = table.readUInt16BE(
                    coverageOffsetsStart + coverageIndex * UINT16_BYTE_LENGTH,
                    "OpenType GSUB ContextSubst format 3 coverageOffset[$coverageIndex]",
                )
                parseCoverageTable(
                    table = table,
                    coverageStart = table.checkedOffset(
                        base = subtableStart,
                        offset = coverageOffset,
                        label = "OpenType GSUB ContextSubst format 3 coverage[$coverageIndex]",
                    ),
                ).toCollection(LinkedHashSet())
            },
            nestedLookups = nestedLookups,
        )
    }

    private fun parseChainingContextLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        subtableCount: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubLookup? {
        val chainingContextGlyphRules = mutableListOf<OpenTypeGsubChainingContextGlyphRule>()
        var chainingContextFormat = 0
        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LookupTable subtableOffset[$subtableIndex]",
            )
            val subtableStart = table.checkedOffset(
                base = lookupStart,
                offset = subtableOffset,
                label = "OpenType GSUB LookupTable Subtable $subtableIndex",
            )
            chainingContextFormat = table.readUInt16BE(subtableStart, "OpenType GSUB ChainingContextSubst format")
            try {
                when (chainingContextFormat) {
                    1 -> parseChainingContextFormat1Subtable(table, subtableStart, chainingContextGlyphRules)
                    else -> {} // format 2/3 not yet implemented
                }
            } catch (e: IllegalArgumentException) {
                // Malformed chaining subtable - skip gracefully
            }
        }
        val orderedFeatureTags = featureTags.toList()
        return when (chainingContextFormat) {
            1 -> chainingContextGlyphRules.takeUnless { it.isEmpty() }?.let {
                OpenTypeGsubChainingContextGlyphLookup(
                    featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                    extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                    lookupIndex = lookupIndex,
                    rules = it,
                )
            }
            else -> null
        }
    }

    private fun parseChainingContextFormat1Subtable(
        table: ByteArray,
        subtableStart: Int,
        rules: MutableList<OpenTypeGsubChainingContextGlyphRule>,
    ) {
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB ChainingContextSubst format 1 coverageOffset",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GSUB ChainingContextSubst format 1 coverage",
            ),
        )
        val subRuleSetCount = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB ChainingContextSubst format 1 subRuleSetCount",
        )
        require(subRuleSetCount == coverage.size) {
            "OpenType GSUB ChainingContextSubst format 1 subRuleSetCount $subRuleSetCount must equal coverage glyph count ${coverage.size}."
        }
        val subRuleSetOffsetsStart = table.checkedOffset(
            subtableStart, 6,
            "OpenType GSUB ChainingContextSubst format 1 subRuleSet offsets",
        )
        table.requireArrayRange(
            offset = subRuleSetOffsetsStart,
            count = subRuleSetCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB ChainingContextSubst format 1 subRuleSet offsets",
        )
        repeat(subRuleSetCount) { setIndex ->
            val subRuleSetOffset = table.readUInt16BE(
                subRuleSetOffsetsStart + setIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB ChainingContextSubst format 1 subRuleSetOffset[$setIndex]",
            )
            if (subRuleSetOffset == 0) return@repeat
            val subRuleSetStart = table.checkedOffset(
                base = subtableStart,
                offset = subRuleSetOffset,
                label = "OpenType GSUB ChainingContextSubst format 1 SubRuleSet $setIndex",
            )
            table.requireRange(
                subRuleSetStart, UINT16_BYTE_LENGTH,
                "OpenType GSUB ChainingContextSubst format 1 SubRuleSet $setIndex header",
            )
            val subRuleCount = table.readUInt16BE(
                subRuleSetStart,
                "OpenType GSUB ChainingContextSubst format 1 SubRuleSet $setIndex subRuleCount",
            )
            val subRuleOffsetsStart = table.checkedOffset(
                subRuleSetStart, UINT16_BYTE_LENGTH,
                "OpenType GSUB ChainingContextSubst format 1 SubRuleSet $setIndex offsets",
            )
            table.requireArrayRange(
                offset = subRuleOffsetsStart,
                count = subRuleCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GSUB ChainingContextSubst format 1 SubRuleSet $setIndex offsets",
            )
            repeat(subRuleCount) { ruleIndex ->
                val subRuleOffset = table.readUInt16BE(
                    subRuleOffsetsStart + ruleIndex * UINT16_BYTE_LENGTH,
                    "OpenType GSUB ChainingContextSubst format 1 SubRuleSet $setIndex subRuleOffset[$ruleIndex]",
                )
                val subRuleStart = table.checkedOffset(
                    base = subRuleSetStart,
                    offset = subRuleOffset,
                    label = "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex",
                )
                table.requireRange(
                    subRuleStart, 2,
                    "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex header",
                )
                val backtrackGlyphCount = table.readUInt16BE(
                    subRuleStart,
                    "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex backtrackGlyphCount",
                )
                var cursor = subRuleStart + UINT16_BYTE_LENGTH
                val backtrackGlyphIds = buildList(backtrackGlyphCount) {
                    table.requireArrayRange(
                        offset = cursor,
                        count = backtrackGlyphCount,
                        recordSize = UINT16_BYTE_LENGTH,
                        label = "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex backtrack array",
                    )
                    repeat(backtrackGlyphCount) {
                        add(table.readUInt16BE(cursor, "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex backtrack[$it]"))
                        cursor += UINT16_BYTE_LENGTH
                    }
                }
                val inputGlyphCount = table.readUInt16BE(
                    cursor,
                    "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex inputGlyphCount",
                )
                require(inputGlyphCount >= 1) {
                    "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex inputGlyphCount $inputGlyphCount must be at least 1."
                }
                cursor += UINT16_BYTE_LENGTH
                val inputGlyphIds = buildList(inputGlyphCount) {
                    table.requireArrayRange(
                        offset = cursor,
                        count = inputGlyphCount,
                        recordSize = UINT16_BYTE_LENGTH,
                        label = "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex input array",
                    )
                    repeat(inputGlyphCount) {
                        add(table.readUInt16BE(cursor, "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex input[$it]"))
                        cursor += UINT16_BYTE_LENGTH
                    }
                }
                val lookAheadGlyphCount = table.readUInt16BE(
                    cursor,
                    "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex lookAheadGlyphCount",
                )
                cursor += UINT16_BYTE_LENGTH
                val lookAheadGlyphIds = buildList(lookAheadGlyphCount) {
                    table.requireArrayRange(
                        offset = cursor,
                        count = lookAheadGlyphCount,
                        recordSize = UINT16_BYTE_LENGTH,
                        label = "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex lookAhead array",
                    )
                    repeat(lookAheadGlyphCount) {
                        add(table.readUInt16BE(cursor, "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex lookAhead[$it]"))
                        cursor += UINT16_BYTE_LENGTH
                    }
                }
                val substCount = table.readUInt16BE(
                    cursor,
                    "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex substCount",
                )
                cursor += UINT16_BYTE_LENGTH
                val nestedLookups = parseNestedLookupRecords(
                    table = table,
                    recordsStart = cursor,
                    substCount = substCount,
                    matchLength = inputGlyphCount,
                    label = "OpenType GSUB ChainingContextSubst format 1 SubRule $setIndex/$ruleIndex",
                )
                rules += OpenTypeGsubChainingContextGlyphRule(
                    backtrackGlyphIds = backtrackGlyphIds,
                    inputGlyphIds = inputGlyphIds,
                    lookAheadGlyphIds = lookAheadGlyphIds,
                    nestedLookups = nestedLookups,
                )
            }
        }
    }

    private fun parseReverseChainingLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        subtableCount: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubLookup? = null // not yet implemented

    private fun parseNestedLookupRecords(
        table: ByteArray,
        recordsStart: Int,
        substCount: Int,
        matchLength: Int,
        label: String,
    ): List<OpenTypeGsubNestedLookupRecord> {
        table.requireArrayRange(
            offset = recordsStart,
            count = substCount,
            recordSize = 4,
            label = "$label lookup records",
        )
        return List(substCount) { recordIndex ->
            val sequenceIndex = table.readUInt16BE(
                recordsStart + recordIndex * 4,
                "$label sequenceIndex[$recordIndex]",
            )
            require(sequenceIndex in 0 until matchLength) {
                "$label sequenceIndex[$recordIndex] $sequenceIndex must be within matched glyph count $matchLength."
            }
            OpenTypeGsubNestedLookupRecord(
                sequenceIndex = sequenceIndex,
                lookupIndex = table.readUInt16BE(
                    recordsStart + recordIndex * 4 + UINT16_BYTE_LENGTH,
                    "$label lookupListIndex[$recordIndex]",
                ),
            )
        }
    }

    private fun parseGsubClassDefTable(
        table: ByteArray,
        classDefStart: Int,
    ): Map<Int, Int> {
        table.requireRange(classDefStart, 4, "OpenType GSUB ClassDef table header")
        return when (val format = table.readUInt16BE(classDefStart, "OpenType GSUB ClassDef format")) {
            1 -> {
                val startGlyphId = table.readUInt16BE(
                    classDefStart + UINT16_BYTE_LENGTH,
                    "OpenType GSUB ClassDef format 1 startGlyphID",
                )
                val glyphCount = table.readUInt16BE(
                    classDefStart + UINT16_BYTE_LENGTH * 2,
                    "OpenType GSUB ClassDef format 1 glyphCount",
                )
                require(startGlyphId + glyphCount.toLong() <= GPOS_MAX_COVERAGE_GLYPHS) {
                    "OpenType GSUB ClassDef format 1 glyph range starts at $startGlyphId with count $glyphCount and exceeds glyph ID range."
                }
                val classValueArrayStart = table.checkedOffset(
                    classDefStart,
                    6,
                    "OpenType GSUB ClassDef format 1 classValueArray",
                )
                table.requireArrayRange(
                    offset = classValueArrayStart,
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GSUB ClassDef format 1 classValueArray",
                )
                buildMap {
                    repeat(glyphCount) { glyphIndex ->
                        put(
                            startGlyphId + glyphIndex,
                            table.readUInt16BE(
                                classValueArrayStart + glyphIndex * UINT16_BYTE_LENGTH,
                                "OpenType GSUB ClassDef format 1 classValueArray[$glyphIndex]",
                            ),
                        )
                    }
                }
            }
            2 -> {
                val classRangeCount = table.readUInt16BE(
                    classDefStart + UINT16_BYTE_LENGTH,
                    "OpenType GSUB ClassDef format 2 classRangeCount",
                )
                val classRangeRecordsStart = table.checkedOffset(
                    classDefStart,
                    4,
                    "OpenType GSUB ClassDef format 2 class range records",
                )
                table.requireArrayRange(
                    offset = classRangeRecordsStart,
                    count = classRangeCount,
                    recordSize = 6,
                    label = "OpenType GSUB ClassDef format 2 class range records",
                )
                val classes = LinkedHashMap<Int, Int>()
                var expandedGlyphs = 0
                repeat(classRangeCount) { rangeIndex ->
                    val rangeStart = classRangeRecordsStart + rangeIndex * 6
                    val startGlyphId = table.readUInt16BE(
                        rangeStart,
                        "OpenType GSUB ClassDef format 2 range $rangeIndex startGlyphID",
                    )
                    val endGlyphId = table.readUInt16BE(
                        rangeStart + UINT16_BYTE_LENGTH,
                        "OpenType GSUB ClassDef format 2 range $rangeIndex endGlyphID",
                    )
                    val classId = table.readUInt16BE(
                        rangeStart + UINT16_BYTE_LENGTH * 2,
                        "OpenType GSUB ClassDef format 2 range $rangeIndex class",
                    )
                    require(endGlyphId >= startGlyphId) {
                        "OpenType GSUB ClassDef format 2 range $rangeIndex endGlyphID $endGlyphId is before startGlyphID $startGlyphId."
                    }
                    val glyphCount = endGlyphId - startGlyphId + 1
                    require(expandedGlyphs + glyphCount <= GPOS_MAX_COVERAGE_GLYPHS) {
                        "OpenType GSUB ClassDef format 2 ranges expand beyond $GPOS_MAX_COVERAGE_GLYPHS glyphs."
                    }
                    repeat(glyphCount) { offset ->
                        classes[startGlyphId + offset] = classId
                    }
                    expandedGlyphs += glyphCount
                }
                classes
            }
            else -> throw IllegalArgumentException("OpenType GSUB ClassDef format $format is not supported.")
        }
    }

    private fun parseLigatureLookup(
        table: ByteArray,
        lookupStart: Int,
        lookupIndex: Int,
        subtableCount: Int,
        featureTags: Collection<String>,
    ): OpenTypeGsubLigatureSubstitutionLookup? {
        val substitutions = mutableListOf<OpenTypeGsubLigatureSubstitution>()
        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LookupTable subtableOffset[$subtableIndex]",
            )
            parseLigatureSubtable(
                table = table,
                subtableStart = table.checkedOffset(
                    base = lookupStart,
                    offset = subtableOffset,
                    label = "OpenType GSUB LigatureSubst subtable $subtableIndex",
                ),
                substitutions = substitutions,
            )
        }
        return substitutions.takeUnless { it.isEmpty() }?.let { parsed ->
            val orderedFeatureTags = featureTags.toList()
            OpenTypeGsubLigatureSubstitutionLookup(
                featureTag = orderedFeatureTags.firstOrNull().orEmpty(),
                extraFeatureTags = orderedFeatureTags.drop(1).toSet(),
                lookupIndex = lookupIndex,
                substitutions = parsed,
            )
        }
    }

    private fun parseLigatureSubtable(
        table: ByteArray,
        subtableStart: Int,
        substitutions: MutableList<OpenTypeGsubLigatureSubstitution>,
    ) {
        table.requireRange(subtableStart, 6, "OpenType GSUB LigatureSubst subtable header")
        val substFormat = table.readUInt16BE(subtableStart, "OpenType GSUB LigatureSubst substFormat")
        require(substFormat == 1) {
            "OpenType GSUB LigatureSubst format $substFormat is not supported."
        }
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GSUB LigatureSubst coverageOffset",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GSUB LigatureSubst coverage",
            ),
        )
        val ligSetCount = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GSUB LigatureSubst ligSetCount",
        )
        require(ligSetCount == coverage.size) {
            "OpenType GSUB LigatureSubst ligSetCount $ligSetCount must equal coverage glyph count ${coverage.size}."
        }
        val ligSetOffsetsStart = table.checkedOffset(
            subtableStart,
            6,
            "OpenType GSUB LigatureSubst ligature set offsets",
        )
        table.requireArrayRange(
            offset = ligSetOffsetsStart,
            count = ligSetCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GSUB LigatureSubst ligature set offsets",
        )
        repeat(ligSetCount) { ligSetIndex ->
            val ligSetOffset = table.readUInt16BE(
                ligSetOffsetsStart + ligSetIndex * UINT16_BYTE_LENGTH,
                "OpenType GSUB LigatureSubst ligatureSetOffset[$ligSetIndex]",
            )
            val ligSetStart = table.checkedOffset(
                base = subtableStart,
                offset = ligSetOffset,
                label = "OpenType GSUB LigatureSubst LigatureSet $ligSetIndex",
            )
            table.requireRange(ligSetStart, UINT16_BYTE_LENGTH, "OpenType GSUB LigatureSubst LigatureSet $ligSetIndex header")
            val ligatureCount = table.readUInt16BE(
                ligSetStart,
                "OpenType GSUB LigatureSubst LigatureSet $ligSetIndex ligatureCount",
            )
            val ligatureOffsetsStart = table.checkedOffset(
                ligSetStart,
                UINT16_BYTE_LENGTH,
                "OpenType GSUB LigatureSubst LigatureSet $ligSetIndex offsets",
            )
            table.requireArrayRange(
                offset = ligatureOffsetsStart,
                count = ligatureCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GSUB LigatureSubst LigatureSet $ligSetIndex offsets",
            )
            repeat(ligatureCount) { ligatureIndex ->
                val ligatureOffset = table.readUInt16BE(
                    ligatureOffsetsStart + ligatureIndex * UINT16_BYTE_LENGTH,
                    "OpenType GSUB LigatureSubst LigatureSet $ligSetIndex ligatureOffset[$ligatureIndex]",
                )
                val ligatureStart = table.checkedOffset(
                    base = ligSetStart,
                    offset = ligatureOffset,
                    label = "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex",
                )
                table.requireRange(ligatureStart, 4, "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex header")
                val ligGlyph = table.readUInt16BE(
                    ligatureStart,
                    "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex ligGlyph",
                )
                val compCount = table.readUInt16BE(
                    ligatureStart + UINT16_BYTE_LENGTH,
                    "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex compCount",
                )
                require(compCount >= 2) {
                    "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex compCount $compCount must be at least 2."
                }
                table.requireArrayRange(
                    offset = table.checkedOffset(
                        ligatureStart,
                        4,
                        "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex component array",
                    ),
                    count = compCount - 1,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex component array",
                )
                val inputGlyphIds = buildList(compCount) {
                    add(coverage[ligSetIndex])
                    repeat(compCount - 1) { componentIndex ->
                        add(
                            table.readUInt16BE(
                                ligatureStart + 4 + componentIndex * UINT16_BYTE_LENGTH,
                                "OpenType GSUB LigatureSubst Ligature $ligSetIndex/$ligatureIndex componentGlyphID[$componentIndex]",
                            ),
                        )
                    }
                }
                substitutions += OpenTypeGsubLigatureSubstitution(
                    inputGlyphIds = inputGlyphIds,
                    replacementGlyphId = ligGlyph,
                )
            }
        }
    }

    private fun parseCoverageTable(table: ByteArray, coverageStart: Int): List<Int> {
        table.requireRange(coverageStart, 4, "OpenType GSUB coverage table header")
        return when (val format = table.readUInt16BE(coverageStart, "OpenType GSUB coverage format")) {
            1 -> {
                val glyphCount = table.readUInt16BE(
                    coverageStart + UINT16_BYTE_LENGTH,
                    "OpenType GSUB coverage format 1 glyphCount",
                )
                table.requireArrayRange(
                    offset = table.checkedOffset(
                        coverageStart,
                        GPOS_COVERAGE_FORMAT1_HEADER_SIZE,
                        "OpenType GSUB coverage format 1 glyph array",
                    ),
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GSUB coverage format 1 glyph array",
                )
                List(glyphCount) { glyphIndex ->
                    table.readUInt16BE(
                        coverageStart + GPOS_COVERAGE_FORMAT1_HEADER_SIZE + glyphIndex * UINT16_BYTE_LENGTH,
                        "OpenType GSUB coverage format 1 glyphArray[$glyphIndex]",
                    )
                }
            }
            2 -> {
                val rangeCount = table.readUInt16BE(
                    coverageStart + UINT16_BYTE_LENGTH,
                    "OpenType GSUB coverage format 2 rangeCount",
                )
                table.requireArrayRange(
                    offset = table.checkedOffset(
                        coverageStart,
                        GPOS_COVERAGE_FORMAT2_HEADER_SIZE,
                        "OpenType GSUB coverage format 2 range records",
                    ),
                    count = rangeCount,
                    recordSize = GPOS_COVERAGE_RANGE_RECORD_SIZE,
                    label = "OpenType GSUB coverage format 2 range records",
                )
                val glyphs = ArrayList<Int>()
                repeat(rangeCount) { rangeIndex ->
                    val rangeStart = coverageStart + GPOS_COVERAGE_FORMAT2_HEADER_SIZE +
                        rangeIndex * GPOS_COVERAGE_RANGE_RECORD_SIZE
                    val startGlyphId = table.readUInt16BE(
                        rangeStart,
                        "OpenType GSUB coverage format 2 range $rangeIndex startGlyphID",
                    )
                    val endGlyphId = table.readUInt16BE(
                        rangeStart + UINT16_BYTE_LENGTH,
                        "OpenType GSUB coverage format 2 range $rangeIndex endGlyphID",
                    )
                    require(endGlyphId >= startGlyphId) {
                        "OpenType GSUB coverage format 2 range $rangeIndex endGlyphID $endGlyphId is before startGlyphID $startGlyphId."
                    }
                    val glyphCount = endGlyphId - startGlyphId + 1
                    require(glyphs.size + glyphCount <= GPOS_MAX_COVERAGE_GLYPHS) {
                        "OpenType GSUB coverage table expands beyond $GPOS_MAX_COVERAGE_GLYPHS glyphs."
                    }
                    for (glyphId in startGlyphId..endGlyphId) {
                        glyphs += glyphId
                    }
                }
                glyphs
            }
            else -> throw IllegalArgumentException("OpenType GSUB coverage format $format is not supported.")
        }
    }

    private fun ByteArray.checkedOffset(base: Int, offset: Int, label: String): Int {
        val absolute = base.toLong() + offset.toLong()
        require(absolute in 0..Int.MAX_VALUE.toLong()) {
            "$label offset $offset from base $base is outside addressable Int range."
        }
        return absolute.toInt()
    }

    private fun ByteArray.requireArrayRange(
        offset: Int,
        count: Int,
        recordSize: Int,
        label: String,
    ) {
        val length = count.toLong() * recordSize.toLong()
        require(length <= Int.MAX_VALUE.toLong()) {
            "$label byte length $length exceeds addressable Int range."
        }
        requireRange(offset, length.toInt(), label)
    }

    private data class OpenTypeGsubFeatureLookupRef(
        val featureTag: String,
        val lookupIndex: Int,
    )
}

private const val GSUB_SINGLE_SUBSTITUTION_LOOKUP_TYPE = 1
private const val GSUB_MULTIPLE_SUBSTITUTION_LOOKUP_TYPE = 2
private const val GSUB_LIGATURE_SUBSTITUTION_LOOKUP_TYPE = 4
private const val GSUB_CONTEXTUAL_SUBSTITUTION_LOOKUP_TYPE = 5
private const val GSUB_CHAINING_CONTEXT_SUBSTITUTION_LOOKUP_TYPE = 6
private const val GSUB_EXTENSION_SUBSTITUTION_LOOKUP_TYPE = 7
private const val GSUB_REVERSE_CHAINING_SUBSTITUTION_LOOKUP_TYPE = 8

/**
 * Parsed bounded subset of OpenType GPOS pair-position kerning.
 *
 * This model intentionally preserves only the value-record fields currently
 * consumed by the pure Kotlin text shaping slice.
 *
 * @property pairs Parsed glyph-pair adjustments in table traversal order.
 */
data class OpenTypeGposPairTable(
    val pairs: List<OpenTypeGposPairAdjustment> = emptyList(),
) {
    private val pairAdjustmentsByKey: Map<Int, OpenTypeGposPairAdjustment> =
        pairs.associate { pair ->
            openTypeGlyphPairKey(pair.leftGlyphId, pair.rightGlyphId) to pair
        }

    /**
     * Returns the signed GPOS `xAdvance` adjustment for a glyph pair.
     *
     * The value is expressed in font design units. When duplicate pairs are
     * encountered, the last parsed adjustment wins, matching the legacy Kanvas
     * GPOS pair table migration behavior. A missing pair returns `0`.
     *
     * @throws IllegalArgumentException when either glyph ID is outside the
     * OpenType unsigned 16-bit glyph ID range.
     */
    fun lookupXAdvanceAdjustment(leftGlyphId: Int, rightGlyphId: Int): Int {
        require(leftGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS left glyph ID $leftGlyphId is outside range 0..65535."
        }
        require(rightGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS right glyph ID $rightGlyphId is outside range 0..65535."
        }
        return lookupAdjustment(leftGlyphId, rightGlyphId)?.xAdvance ?: 0
    }

    /**
     * Returns the parsed pair adjustment for a glyph pair, or `null` when absent.
     */
    fun lookupAdjustment(leftGlyphId: Int, rightGlyphId: Int): OpenTypeGposPairAdjustment? {
        require(leftGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS left glyph ID $leftGlyphId is outside range 0..65535."
        }
        require(rightGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS right glyph ID $rightGlyphId is outside range 0..65535."
        }
        return pairAdjustmentsByKey[openTypeGlyphPairKey(leftGlyphId, rightGlyphId)]
    }
}

/**
 * One GPOS pair-position adjustment.
 *
 * @property leftGlyphId Glyph ID of the leading glyph in the pair.
 * @property rightGlyphId Glyph ID of the trailing glyph in the pair.
 * @property firstValueRecord Value record applied to the leading glyph.
 * @property secondValueRecord Value record applied to the trailing glyph.
 */
data class OpenTypeGposPairAdjustment(
    val leftGlyphId: Int,
    val rightGlyphId: Int,
    val firstValueRecord: OpenTypeGposValueRecord = OpenTypeGposValueRecord(),
    val secondValueRecord: OpenTypeGposValueRecord = OpenTypeGposValueRecord(),
) {
    init {
        require(leftGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS left glyph ID $leftGlyphId is outside range 0..65535."
        }
        require(rightGlyphId in OPENTYPE_GLYPH_ID_RANGE) {
            "OpenType GPOS right glyph ID $rightGlyphId is outside range 0..65535."
        }
    }

    val xAdvance: Int
        get() = firstValueRecord.xAdvance

    constructor(leftGlyphId: Int, rightGlyphId: Int, xAdvance: Int) : this(
        leftGlyphId = leftGlyphId,
        rightGlyphId = rightGlyphId,
        firstValueRecord = OpenTypeGposValueRecord(xAdvance = xAdvance),
    )
}

/**
 * Parser for the bounded OpenType GDEF metadata needed by shaping slices.
 */
object OpenTypeGdefTableParser {
    fun parse(table: ByteArray): OpenTypeGdefTable {
        table.requireRange(0, GDEF_HEADER_V1_SIZE, "OpenType GDEF header")

        val majorVersion = table.readUInt16BE(0, "OpenType GDEF majorVersion")
        val minorVersion = table.readUInt16BE(2, "OpenType GDEF minorVersion")
        require(majorVersion == 1 && minorVersion in 0..3) {
            "OpenType GDEF table version $majorVersion.$minorVersion is not supported; expected 1.0 through 1.3."
        }

        val glyphClassDefOffset = table.readUInt16BE(4, "OpenType GDEF glyphClassDefOffset")
        val ligCaretListOffset = table.readUInt16BE(8, "OpenType GDEF ligCaretListOffset")
        val markAttachClassDefOffset = table.readUInt16BE(10, "OpenType GDEF markAttachClassDefOffset")

        if (minorVersion >= 2) {
            table.requireRange(0, GDEF_HEADER_V12_SIZE, "OpenType GDEF extended header")
        }

        val glyphClasses = if (glyphClassDefOffset != 0) {
            parseClassDefTable(
                table = table,
                classDefStart = table.checkedOffset(0, glyphClassDefOffset, "OpenType GDEF glyphClassDef"),
            )
        } else {
            emptyMap()
        }
        val markAttachmentClasses = if (markAttachClassDefOffset != 0) {
            parseClassDefTable(
                table = table,
                classDefStart = table.checkedOffset(0, markAttachClassDefOffset, "OpenType GDEF markAttachClassDef"),
            )
        } else {
            emptyMap()
        }
        val ligatureCaretPositions = if (ligCaretListOffset != 0) {
            parseLigCaretList(
                table = table,
                ligCaretListStart = table.checkedOffset(0, ligCaretListOffset, "OpenType GDEF ligCaretList"),
            )
        } else {
            emptyMap()
        }

        return OpenTypeGdefTable(
            glyphClasses = glyphClasses,
            markAttachmentClasses = markAttachmentClasses,
            ligatureCaretPositions = ligatureCaretPositions,
        )
    }

    private fun parseLigCaretList(table: ByteArray, ligCaretListStart: Int): Map<Int, List<Int>> {
        table.requireRange(ligCaretListStart, GDEF_LIG_CARET_LIST_HEADER_SIZE, "OpenType GDEF LigCaretList header")
        val coverageOffset = table.readUInt16BE(ligCaretListStart, "OpenType GDEF LigCaretList coverageOffset")
        val ligGlyphCount = table.readUInt16BE(
            ligCaretListStart + UINT16_BYTE_LENGTH,
            "OpenType GDEF LigCaretList ligGlyphCount",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(ligCaretListStart, coverageOffset, "OpenType GDEF LigCaretList coverage"),
        )
        require(coverage.size == ligGlyphCount) {
            "OpenType GDEF LigCaretList ligGlyphCount $ligGlyphCount must equal coverage glyph count ${coverage.size}."
        }
        table.requireArrayRange(
            offset = table.checkedOffset(
                ligCaretListStart,
                GDEF_LIG_CARET_LIST_HEADER_SIZE,
                "OpenType GDEF LigCaretList ligGlyph offsets",
            ),
            count = ligGlyphCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GDEF LigCaretList ligGlyph offsets",
        )

        return buildMap {
            repeat(ligGlyphCount) { ligGlyphIndex ->
                val ligGlyphOffset = table.readUInt16BE(
                    ligCaretListStart + GDEF_LIG_CARET_LIST_HEADER_SIZE + ligGlyphIndex * UINT16_BYTE_LENGTH,
                    "OpenType GDEF LigCaretList ligGlyphOffset[$ligGlyphIndex]",
                )
                val ligGlyphStart = table.checkedOffset(
                    ligCaretListStart,
                    ligGlyphOffset,
                    "OpenType GDEF LigGlyph $ligGlyphIndex",
                )
                table.requireRange(ligGlyphStart, GDEF_LIG_GLYPH_HEADER_SIZE, "OpenType GDEF LigGlyph $ligGlyphIndex header")
                val caretCount = table.readUInt16BE(ligGlyphStart, "OpenType GDEF LigGlyph $ligGlyphIndex caretCount")
                table.requireArrayRange(
                    offset = table.checkedOffset(ligGlyphStart, GDEF_LIG_GLYPH_HEADER_SIZE, "OpenType GDEF LigGlyph $ligGlyphIndex caret offsets"),
                    count = caretCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GDEF LigGlyph $ligGlyphIndex caret offsets",
                )
                val carets = ArrayList<Int>(caretCount)
                repeat(caretCount) { caretIndex ->
                    val caretOffset = table.readUInt16BE(
                        ligGlyphStart + GDEF_LIG_GLYPH_HEADER_SIZE + caretIndex * UINT16_BYTE_LENGTH,
                        "OpenType GDEF LigGlyph $ligGlyphIndex caretOffset[$caretIndex]",
                    )
                    if (caretOffset == 0) return@repeat
                    val caretValueStart = table.checkedOffset(
                        ligGlyphStart,
                        caretOffset,
                        "OpenType GDEF LigGlyph $ligGlyphIndex CaretValue $caretIndex",
                    )
                    table.requireRange(caretValueStart, GDEF_CARET_VALUE_FORMAT1_SIZE, "OpenType GDEF CaretValue header")
                    val format = table.readUInt16BE(caretValueStart, "OpenType GDEF CaretValue format")
                    require(format == GDEF_GLYPH_CARET_VALUE_FORMAT1) {
                        "OpenType GDEF CaretValue format $format is not supported."
                    }
                    carets += table.readInt16BE(caretValueStart + UINT16_BYTE_LENGTH, "OpenType GDEF CaretValue coordinate")
                }
                put(coverage[ligGlyphIndex], carets)
            }
        }
    }

    private fun parseCoverageTable(table: ByteArray, coverageStart: Int): List<Int> {
        table.requireRange(coverageStart, 4, "OpenType GDEF coverage table header")
        return when (val format = table.readUInt16BE(coverageStart, "OpenType GDEF coverage format")) {
            1 -> {
                val glyphCount = table.readUInt16BE(
                    coverageStart + UINT16_BYTE_LENGTH,
                    "OpenType GDEF coverage format 1 glyphCount",
                )
                table.requireArrayRange(
                    offset = table.checkedOffset(coverageStart, GPOS_COVERAGE_FORMAT1_HEADER_SIZE, "OpenType GDEF coverage format 1 glyph array"),
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GDEF coverage format 1 glyph array",
                )
                List(glyphCount) { glyphIndex ->
                    table.readUInt16BE(
                        coverageStart + GPOS_COVERAGE_FORMAT1_HEADER_SIZE + glyphIndex * UINT16_BYTE_LENGTH,
                        "OpenType GDEF coverage format 1 glyphArray[$glyphIndex]",
                    )
                }
            }
            2 -> {
                val rangeCount = table.readUInt16BE(
                    coverageStart + UINT16_BYTE_LENGTH,
                    "OpenType GDEF coverage format 2 rangeCount",
                )
                table.requireArrayRange(
                    offset = table.checkedOffset(coverageStart, GPOS_COVERAGE_FORMAT2_HEADER_SIZE, "OpenType GDEF coverage format 2 range records"),
                    count = rangeCount,
                    recordSize = GPOS_COVERAGE_RANGE_RECORD_SIZE,
                    label = "OpenType GDEF coverage format 2 range records",
                )
                buildList {
                    repeat(rangeCount) { rangeIndex ->
                        val rangeStart = coverageStart + GPOS_COVERAGE_FORMAT2_HEADER_SIZE +
                            rangeIndex * GPOS_COVERAGE_RANGE_RECORD_SIZE
                        val startGlyphId = table.readUInt16BE(rangeStart, "OpenType GDEF coverage range $rangeIndex startGlyphID")
                        val endGlyphId = table.readUInt16BE(rangeStart + UINT16_BYTE_LENGTH, "OpenType GDEF coverage range $rangeIndex endGlyphID")
                        require(endGlyphId >= startGlyphId) {
                            "OpenType GDEF coverage range $rangeIndex endGlyphID $endGlyphId is before startGlyphID $startGlyphId."
                        }
                        for (glyphId in startGlyphId..endGlyphId) {
                            add(glyphId)
                        }
                    }
                }
            }
            else -> throw IllegalArgumentException("OpenType GDEF coverage format $format is not supported.")
        }
    }

    private fun parseClassDefTable(table: ByteArray, classDefStart: Int): Map<Int, Int> {
        table.requireRange(classDefStart, 4, "OpenType GDEF ClassDef table header")
        val classes = HashMap<Int, Int>()
        when (val format = table.readUInt16BE(classDefStart, "OpenType GDEF ClassDef format")) {
            1 -> {
                table.requireRange(classDefStart, GPOS_CLASS_DEF_FORMAT1_HEADER_SIZE, "OpenType GDEF ClassDef format 1 header")
                val startGlyphId = table.readUInt16BE(classDefStart + UINT16_BYTE_LENGTH, "OpenType GDEF ClassDef format 1 startGlyphID")
                val glyphCount = table.readUInt16BE(classDefStart + UINT16_BYTE_LENGTH * 2, "OpenType GDEF ClassDef format 1 glyphCount")
                table.requireArrayRange(
                    offset = table.checkedOffset(classDefStart, GPOS_CLASS_DEF_FORMAT1_HEADER_SIZE, "OpenType GDEF ClassDef format 1 classValueArray"),
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GDEF ClassDef format 1 classValueArray",
                )
                repeat(glyphCount) { glyphIndex ->
                    classes[startGlyphId + glyphIndex] = table.readUInt16BE(
                        classDefStart + GPOS_CLASS_DEF_FORMAT1_HEADER_SIZE + glyphIndex * UINT16_BYTE_LENGTH,
                        "OpenType GDEF ClassDef format 1 classValueArray[$glyphIndex]",
                    )
                }
            }
            2 -> {
                val classRangeCount = table.readUInt16BE(classDefStart + UINT16_BYTE_LENGTH, "OpenType GDEF ClassDef format 2 classRangeCount")
                table.requireArrayRange(
                    offset = table.checkedOffset(classDefStart, GPOS_CLASS_DEF_FORMAT2_HEADER_SIZE, "OpenType GDEF ClassDef format 2 class range records"),
                    count = classRangeCount,
                    recordSize = GPOS_CLASS_RANGE_RECORD_SIZE,
                    label = "OpenType GDEF ClassDef format 2 class range records",
                )
                repeat(classRangeCount) { rangeIndex ->
                    val rangeStart = classDefStart + GPOS_CLASS_DEF_FORMAT2_HEADER_SIZE + rangeIndex * GPOS_CLASS_RANGE_RECORD_SIZE
                    val startGlyphId = table.readUInt16BE(rangeStart, "OpenType GDEF ClassDef range $rangeIndex startGlyphID")
                    val endGlyphId = table.readUInt16BE(rangeStart + UINT16_BYTE_LENGTH, "OpenType GDEF ClassDef range $rangeIndex endGlyphID")
                    val klass = table.readUInt16BE(rangeStart + UINT16_BYTE_LENGTH * 2, "OpenType GDEF ClassDef range $rangeIndex class")
                    require(endGlyphId >= startGlyphId) {
                        "OpenType GDEF ClassDef range $rangeIndex endGlyphID $endGlyphId is before startGlyphID $startGlyphId."
                    }
                    for (glyphId in startGlyphId..endGlyphId) {
                        classes[glyphId] = klass
                    }
                }
            }
            else -> throw IllegalArgumentException("OpenType GDEF ClassDef format $format is not supported.")
        }
        return classes
    }

    private fun ByteArray.checkedOffset(base: Int, offset: Int, label: String): Int {
        val absolute = base.toLong() + offset.toLong()
        require(absolute in 0..Int.MAX_VALUE.toLong()) {
            "$label offset $offset from base $base is outside addressable Int range."
        }
        return absolute.toInt()
    }

    private fun ByteArray.requireArrayRange(offset: Int, count: Int, recordSize: Int, label: String) {
        val length = count.toLong() * recordSize.toLong()
        require(length <= Int.MAX_VALUE.toLong()) {
            "$label byte length $length exceeds addressable Int range."
        }
        requireRange(offset, length.toInt(), label)
    }
}

/**
 * Parser for bounded OpenType GPOS cursive and mark-positioning lookups.
 */
object OpenTypeGposTableParser {
    fun parse(table: ByteArray): OpenTypeGposTable {
        table.requireRange(0, GPOS_HEADER_SIZE, "OpenType GPOS header")

        val majorVersion = table.readUInt16BE(0, "OpenType GPOS majorVersion")
        val minorVersion = table.readUInt16BE(2, "OpenType GPOS minorVersion")
        require(majorVersion == 1 && minorVersion in 0..1) {
            "OpenType GPOS table version $majorVersion.$minorVersion is not supported; expected 1.0 or 1.1."
        }

        val scriptListStart = table.checkedOffset(0, table.readUInt16BE(4, "OpenType GPOS ScriptList offset"), "OpenType GPOS ScriptList")
        val featureListStart = table.checkedOffset(0, table.readUInt16BE(6, "OpenType GPOS FeatureList offset"), "OpenType GPOS FeatureList")
        val lookupListStart = table.checkedOffset(0, table.readUInt16BE(8, "OpenType GPOS LookupList offset"), "OpenType GPOS LookupList")
        val featureLookups = parseActiveFeatureLookups(
            table = table,
            scriptListStart = scriptListStart,
            featureListStart = featureListStart,
        )
        if (featureLookups.isEmpty()) return OpenTypeGposTable()

        table.requireRange(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GPOS LookupList header")
        val lookupCount = table.readUInt16BE(lookupListStart, "OpenType GPOS LookupList lookupCount")
        table.requireArrayRange(
            offset = table.checkedOffset(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GPOS LookupList offsets"),
            count = lookupCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LookupList offsets",
        )

        val lookups = mutableListOf<OpenTypeGposLookup>()
        featureLookups.forEach { featureLookup ->
            require(featureLookup.lookupIndex in 0 until lookupCount) {
                "OpenType GPOS lookup index ${featureLookup.lookupIndex} is outside LookupList range 0 until $lookupCount."
            }
            val lookupOffset = table.readUInt16BE(
                lookupListStart + UINT16_BYTE_LENGTH + featureLookup.lookupIndex * UINT16_BYTE_LENGTH,
                "OpenType GPOS LookupList offset[${featureLookup.lookupIndex}]",
            )
            parseLookup(
                table = table,
                lookupStart = table.checkedOffset(lookupListStart, lookupOffset, "OpenType GPOS lookup ${featureLookup.lookupIndex}"),
                featureTag = featureLookup.featureTag,
                lookupIndex = featureLookup.lookupIndex,
            )?.let(lookups::add)
        }

        return OpenTypeGposTable(lookups = lookups)
    }

    private fun parseLookup(
        table: ByteArray,
        lookupStart: Int,
        featureTag: String,
        lookupIndex: Int,
    ): OpenTypeGposLookup? {
        table.requireRange(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GPOS LookupTable header")
        val lookupType = table.readUInt16BE(lookupStart, "OpenType GPOS LookupTable lookupType")
        if (lookupType !in setOf(
                GPOS_CURSIVE_ATTACHMENT_LOOKUP_TYPE,
                GPOS_MARK_TO_BASE_LOOKUP_TYPE,
                GPOS_MARK_TO_LIGATURE_LOOKUP_TYPE,
                GPOS_MARK_TO_MARK_LOOKUP_TYPE,
                GPOS_CONTEXTUAL_POSITIONING_LOOKUP_TYPE,
                GPOS_CHAINING_CONTEXT_POSITIONING_LOOKUP_TYPE,
                GPOS_EXTENSION_POSITIONING_LOOKUP_TYPE,
            )
        ) {
            return null
        }
        val subtableCount = table.readUInt16BE(lookupStart + UINT16_BYTE_LENGTH * 2, "OpenType GPOS LookupTable subTableCount")
        table.requireArrayRange(
            offset = table.checkedOffset(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GPOS LookupTable subtable offsets"),
            count = subtableCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LookupTable subtable offsets",
        )

        return runCatching {
            when (lookupType) {
                GPOS_CURSIVE_ATTACHMENT_LOOKUP_TYPE -> OpenTypeGposCursiveLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                    attachments = buildList {
                        repeat(subtableCount) { subtableIndex ->
                            val subtableStart = lookupSubtableStart(table, lookupStart, subtableIndex)
                            addAll(parseCursiveSubtable(table, subtableStart))
                        }
                    },
                )
                GPOS_MARK_TO_BASE_LOOKUP_TYPE -> OpenTypeGposMarkToBaseLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                    attachments = buildList {
                        repeat(subtableCount) { subtableIndex ->
                            val subtableStart = lookupSubtableStart(table, lookupStart, subtableIndex)
                            addAll(parseMarkToBaseSubtable(table, subtableStart))
                        }
                    },
                )
                GPOS_MARK_TO_LIGATURE_LOOKUP_TYPE -> OpenTypeGposMarkToLigatureLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                    attachments = buildList {
                        repeat(subtableCount) { subtableIndex ->
                            val subtableStart = lookupSubtableStart(table, lookupStart, subtableIndex)
                            addAll(parseMarkToLigatureSubtable(table, subtableStart))
                        }
                    },
                )
                GPOS_MARK_TO_MARK_LOOKUP_TYPE -> OpenTypeGposMarkToMarkLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                    attachments = buildList {
                        repeat(subtableCount) { subtableIndex ->
                            val subtableStart = lookupSubtableStart(table, lookupStart, subtableIndex)
                            addAll(parseMarkToMarkSubtable(table, subtableStart))
                        }
                    },
                )
                GPOS_CONTEXTUAL_POSITIONING_LOOKUP_TYPE -> OpenTypeGposContextualLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                )
                GPOS_CHAINING_CONTEXT_POSITIONING_LOOKUP_TYPE -> OpenTypeGposChainingLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                )
                GPOS_EXTENSION_POSITIONING_LOOKUP_TYPE -> OpenTypeGposExtensionLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                )
                else -> OpenTypeGposMalformedLookup(
                    featureTag = featureTag,
                    lookupIndex = lookupIndex,
                    lookupType = lookupType,
                    message = "Unsupported GPOS lookup type $lookupType.",
                )
            }
        }.getOrElse { error ->
            OpenTypeGposMalformedLookup(
                featureTag = featureTag,
                lookupIndex = lookupIndex,
                lookupType = lookupType,
                message = error.message ?: "Unknown GPOS lookup parse failure.",
            )
        }
    }

    private fun parseCursiveSubtable(table: ByteArray, subtableStart: Int): List<OpenTypeGposCursiveAttachment> {
        table.requireRange(subtableStart, GPOS_CURSIVE_SUBTABLE_HEADER_SIZE, "OpenType GPOS cursive subtable header")
        val posFormat = table.readUInt16BE(subtableStart, "OpenType GPOS cursive posFormat")
        require(posFormat == 1) { "OpenType GPOS cursive posFormat $posFormat is not supported." }
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH, "OpenType GPOS cursive coverageOffset"),
                "OpenType GPOS cursive coverage",
            ),
        )
        val entryExitCount = table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 2, "OpenType GPOS cursive entryExitCount")
        require(entryExitCount == coverage.size) {
            "OpenType GPOS cursive entryExitCount $entryExitCount must equal coverage glyph count ${coverage.size}."
        }
        table.requireArrayRange(
            offset = table.checkedOffset(subtableStart, GPOS_CURSIVE_SUBTABLE_HEADER_SIZE, "OpenType GPOS cursive entryExit records"),
            count = entryExitCount,
            recordSize = GPOS_ENTRY_EXIT_RECORD_SIZE,
            label = "OpenType GPOS cursive entryExit records",
        )
        return List(entryExitCount) { glyphIndex ->
            val recordStart = subtableStart + GPOS_CURSIVE_SUBTABLE_HEADER_SIZE + glyphIndex * GPOS_ENTRY_EXIT_RECORD_SIZE
            OpenTypeGposCursiveAttachment(
                glyphId = coverage[glyphIndex],
                entryAnchor = readAnchorOffset(
                    table = table,
                    base = subtableStart,
                    offset = table.readUInt16BE(recordStart, "OpenType GPOS cursive entryAnchorOffset[$glyphIndex]"),
                    label = "OpenType GPOS cursive entryAnchor[$glyphIndex]",
                ),
                exitAnchor = readAnchorOffset(
                    table = table,
                    base = subtableStart,
                    offset = table.readUInt16BE(recordStart + UINT16_BYTE_LENGTH, "OpenType GPOS cursive exitAnchorOffset[$glyphIndex]"),
                    label = "OpenType GPOS cursive exitAnchor[$glyphIndex]",
                ),
            )
        }
    }

    private fun parseMarkToBaseSubtable(table: ByteArray, subtableStart: Int): List<OpenTypeGposMarkToBaseAttachment> {
        table.requireRange(subtableStart, GPOS_MARK_SUBTABLE_HEADER_SIZE, "OpenType GPOS mark-to-base subtable header")
        val posFormat = table.readUInt16BE(subtableStart, "OpenType GPOS mark-to-base posFormat")
        require(posFormat == 1) { "OpenType GPOS mark-to-base posFormat $posFormat is not supported." }
        val markCoverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH, "OpenType GPOS mark-to-base markCoverageOffset"),
                "OpenType GPOS mark-to-base markCoverage",
            ),
        )
        val baseCoverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 2, "OpenType GPOS mark-to-base baseCoverageOffset"),
                "OpenType GPOS mark-to-base baseCoverage",
            ),
        )
        val classCount = table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 3, "OpenType GPOS mark-to-base classCount")
        val marks = parseMarkArray(
            table = table,
            markArrayStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 4, "OpenType GPOS mark-to-base markArrayOffset"),
                "OpenType GPOS mark-to-base markArray",
            ),
            coverage = markCoverage,
            label = "OpenType GPOS mark-to-base",
        )
        val baseArrayStart = table.checkedOffset(
            subtableStart,
            table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 5, "OpenType GPOS mark-to-base baseArrayOffset"),
            "OpenType GPOS mark-to-base baseArray",
        )
        val baseAnchors = parseBaseAnchors(
            table = table,
            baseArrayStart = baseArrayStart,
            coverage = baseCoverage,
            classCount = classCount,
            label = "OpenType GPOS mark-to-base",
        )
        return buildList {
            marks.forEach { mark ->
                baseAnchors.forEach { (baseGlyphId, anchors) ->
                    anchors.getOrNull(mark.first)?.let { baseAnchor ->
                        add(
                            OpenTypeGposMarkToBaseAttachment(
                                markGlyphId = mark.second,
                                baseGlyphId = baseGlyphId,
                                markClass = mark.first,
                                markAnchor = mark.third,
                                baseAnchor = baseAnchor,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun parseMarkToLigatureSubtable(table: ByteArray, subtableStart: Int): List<OpenTypeGposMarkToLigatureAttachment> {
        table.requireRange(subtableStart, GPOS_MARK_SUBTABLE_HEADER_SIZE, "OpenType GPOS mark-to-ligature subtable header")
        val posFormat = table.readUInt16BE(subtableStart, "OpenType GPOS mark-to-ligature posFormat")
        require(posFormat == 1) { "OpenType GPOS mark-to-ligature posFormat $posFormat is not supported." }
        val markCoverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH, "OpenType GPOS mark-to-ligature markCoverageOffset"),
                "OpenType GPOS mark-to-ligature markCoverage",
            ),
        )
        val ligatureCoverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 2, "OpenType GPOS mark-to-ligature ligatureCoverageOffset"),
                "OpenType GPOS mark-to-ligature ligatureCoverage",
            ),
        )
        val classCount = table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 3, "OpenType GPOS mark-to-ligature classCount")
        val marks = parseMarkArray(
            table = table,
            markArrayStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 4, "OpenType GPOS mark-to-ligature markArrayOffset"),
                "OpenType GPOS mark-to-ligature markArray",
            ),
            coverage = markCoverage,
            label = "OpenType GPOS mark-to-ligature",
        )
        val ligatureArrayStart = table.checkedOffset(
            subtableStart,
            table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 5, "OpenType GPOS mark-to-ligature ligatureArrayOffset"),
            "OpenType GPOS mark-to-ligature ligatureArray",
        )
        table.requireRange(ligatureArrayStart, GPOS_LIGATURE_ARRAY_HEADER_SIZE, "OpenType GPOS mark-to-ligature LigatureArray header")
        val ligatureCount = table.readUInt16BE(ligatureArrayStart, "OpenType GPOS mark-to-ligature ligatureCount")
        require(ligatureCount == ligatureCoverage.size) {
            "OpenType GPOS mark-to-ligature ligatureCount $ligatureCount must equal coverage glyph count ${ligatureCoverage.size}."
        }
        table.requireArrayRange(
            offset = table.checkedOffset(ligatureArrayStart, GPOS_LIGATURE_ARRAY_HEADER_SIZE, "OpenType GPOS mark-to-ligature ligature offsets"),
            count = ligatureCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS mark-to-ligature ligature offsets",
        )
        return buildList {
            repeat(ligatureCount) { ligatureIndex ->
                val ligatureStart = table.checkedOffset(
                    ligatureArrayStart,
                    table.readUInt16BE(
                        ligatureArrayStart + GPOS_LIGATURE_ARRAY_HEADER_SIZE + ligatureIndex * UINT16_BYTE_LENGTH,
                        "OpenType GPOS mark-to-ligature ligatureOffset[$ligatureIndex]",
                    ),
                    "OpenType GPOS mark-to-ligature LigatureAttach $ligatureIndex",
                )
                table.requireRange(ligatureStart, UINT16_BYTE_LENGTH, "OpenType GPOS mark-to-ligature LigatureAttach $ligatureIndex header")
                val componentCount = table.readUInt16BE(ligatureStart, "OpenType GPOS mark-to-ligature componentCount[$ligatureIndex]")
                val componentStart = table.checkedOffset(ligatureStart, UINT16_BYTE_LENGTH, "OpenType GPOS mark-to-ligature component records")
                table.requireArrayRange(
                    offset = componentStart,
                    count = componentCount * classCount,
                    recordSize = GPOS_COMPONENT_RECORD_ANCHOR_OFFSET_SIZE,
                    label = "OpenType GPOS mark-to-ligature component anchor offsets",
                )
                repeat(componentCount) { componentIndex ->
                    val anchors = List(classCount) { classIndex ->
                        readAnchorOffset(
                            table = table,
                            base = ligatureStart,
                            offset = table.readUInt16BE(
                                componentStart + (componentIndex * classCount + classIndex) * UINT16_BYTE_LENGTH,
                                "OpenType GPOS mark-to-ligature component[$componentIndex] class[$classIndex] anchorOffset",
                            ),
                            label = "OpenType GPOS mark-to-ligature component[$componentIndex] class[$classIndex] anchor",
                        )
                    }
                    marks.forEach { mark ->
                        anchors.getOrNull(mark.first)?.let { ligatureAnchor ->
                            add(
                                OpenTypeGposMarkToLigatureAttachment(
                                    markGlyphId = mark.second,
                                    ligatureGlyphId = ligatureCoverage[ligatureIndex],
                                    componentIndex = componentIndex,
                                    markClass = mark.first,
                                    markAnchor = mark.third,
                                    ligatureAnchor = ligatureAnchor,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun parseMarkToMarkSubtable(table: ByteArray, subtableStart: Int): List<OpenTypeGposMarkToMarkAttachment> {
        table.requireRange(subtableStart, GPOS_MARK_SUBTABLE_HEADER_SIZE, "OpenType GPOS mark-to-mark subtable header")
        val posFormat = table.readUInt16BE(subtableStart, "OpenType GPOS mark-to-mark posFormat")
        require(posFormat == 1) { "OpenType GPOS mark-to-mark posFormat $posFormat is not supported." }
        val mark1Coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH, "OpenType GPOS mark-to-mark mark1CoverageOffset"),
                "OpenType GPOS mark-to-mark mark1Coverage",
            ),
        )
        val mark2Coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 2, "OpenType GPOS mark-to-mark mark2CoverageOffset"),
                "OpenType GPOS mark-to-mark mark2Coverage",
            ),
        )
        val classCount = table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 3, "OpenType GPOS mark-to-mark classCount")
        val marks = parseMarkArray(
            table = table,
            markArrayStart = table.checkedOffset(
                subtableStart,
                table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 4, "OpenType GPOS mark-to-mark mark1ArrayOffset"),
                "OpenType GPOS mark-to-mark mark1Array",
            ),
            coverage = mark1Coverage,
            label = "OpenType GPOS mark-to-mark",
        )
        val mark2ArrayStart = table.checkedOffset(
            subtableStart,
            table.readUInt16BE(subtableStart + UINT16_BYTE_LENGTH * 5, "OpenType GPOS mark-to-mark mark2ArrayOffset"),
            "OpenType GPOS mark-to-mark mark2Array",
        )
        val mark2Anchors = parseBaseAnchors(
            table = table,
            baseArrayStart = mark2ArrayStart,
            coverage = mark2Coverage,
            classCount = classCount,
            label = "OpenType GPOS mark-to-mark",
        )
        return buildList {
            marks.forEach { mark ->
                mark2Anchors.forEach { (mark2GlyphId, anchors) ->
                    anchors.getOrNull(mark.first)?.let { mark2Anchor ->
                        add(
                            OpenTypeGposMarkToMarkAttachment(
                                mark1GlyphId = mark.second,
                                mark2GlyphId = mark2GlyphId,
                                markClass = mark.first,
                                mark1Anchor = mark.third,
                                mark2Anchor = mark2Anchor,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun parseMarkArray(
        table: ByteArray,
        markArrayStart: Int,
        coverage: List<Int>,
        label: String,
    ): List<Triple<Int, Int, OpenTypeAnchor>> {
        table.requireRange(markArrayStart, GPOS_MARK_ARRAY_HEADER_SIZE, "$label MarkArray header")
        val markCount = table.readUInt16BE(markArrayStart, "$label markCount")
        require(markCount == coverage.size) {
            "$label markCount $markCount must equal coverage glyph count ${coverage.size}."
        }
        table.requireArrayRange(
            offset = table.checkedOffset(markArrayStart, GPOS_MARK_ARRAY_HEADER_SIZE, "$label MarkArray records"),
            count = markCount,
            recordSize = GPOS_MARK_RECORD_SIZE,
            label = "$label MarkArray records",
        )
        return List(markCount) { markIndex ->
            val recordStart = markArrayStart + GPOS_MARK_ARRAY_HEADER_SIZE + markIndex * GPOS_MARK_RECORD_SIZE
            val markClass = table.readUInt16BE(recordStart, "$label markClass[$markIndex]")
            val markAnchor = readAnchorOffset(
                table = table,
                base = markArrayStart,
                offset = table.readUInt16BE(recordStart + UINT16_BYTE_LENGTH, "$label markAnchorOffset[$markIndex]"),
                label = "$label markAnchor[$markIndex]",
            ) ?: throw IllegalArgumentException("$label markAnchor[$markIndex] must not be null.")
            Triple(markClass, coverage[markIndex], markAnchor)
        }
    }

    private fun parseBaseAnchors(
        table: ByteArray,
        baseArrayStart: Int,
        coverage: List<Int>,
        classCount: Int,
        label: String,
    ): List<Pair<Int, List<OpenTypeAnchor?>>> {
        table.requireRange(baseArrayStart, GPOS_BASE_ARRAY_HEADER_SIZE, "$label BaseArray header")
        val baseCount = table.readUInt16BE(baseArrayStart, "$label baseCount")
        require(baseCount == coverage.size) {
            "$label baseCount $baseCount must equal coverage glyph count ${coverage.size}."
        }
        table.requireArrayRange(
            offset = table.checkedOffset(baseArrayStart, GPOS_BASE_ARRAY_HEADER_SIZE, "$label BaseArray anchor offsets"),
            count = baseCount * classCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "$label BaseArray anchor offsets",
        )
        return List(baseCount) { baseIndex ->
            val anchors = List(classCount) { classIndex ->
                readAnchorOffset(
                    table = table,
                    base = baseArrayStart,
                    offset = table.readUInt16BE(
                        baseArrayStart + GPOS_BASE_ARRAY_HEADER_SIZE + (baseIndex * classCount + classIndex) * UINT16_BYTE_LENGTH,
                        "$label base[$baseIndex] class[$classIndex] anchorOffset",
                    ),
                    label = "$label base[$baseIndex] class[$classIndex] anchor",
                )
            }
            coverage[baseIndex] to anchors
        }
    }

    private fun readAnchorOffset(table: ByteArray, base: Int, offset: Int, label: String): OpenTypeAnchor? =
        if (offset == 0) {
            null
        } else {
            parseAnchorTable(
                table = table,
                anchorStart = table.checkedOffset(base, offset, label),
                label = label,
            )
        }

    private fun parseAnchorTable(table: ByteArray, anchorStart: Int, label: String): OpenTypeAnchor {
        table.requireRange(anchorStart, GPOS_ANCHOR_FORMAT1_SIZE, "$label header")
        return when (val format = table.readUInt16BE(anchorStart, "$label format")) {
            1 -> OpenTypeAnchor(
                format = format,
                x = table.readInt16BE(anchorStart + UINT16_BYTE_LENGTH, "$label xCoordinate"),
                y = table.readInt16BE(anchorStart + UINT16_BYTE_LENGTH * 2, "$label yCoordinate"),
            )
            2 -> {
                table.requireRange(anchorStart, GPOS_ANCHOR_FORMAT2_SIZE, "$label format 2")
                OpenTypeAnchor(
                    format = format,
                    x = table.readInt16BE(anchorStart + UINT16_BYTE_LENGTH, "$label xCoordinate"),
                    y = table.readInt16BE(anchorStart + UINT16_BYTE_LENGTH * 2, "$label yCoordinate"),
                    pointIndex = table.readUInt16BE(anchorStart + UINT16_BYTE_LENGTH * 3, "$label anchorPoint"),
                )
            }
            3 -> {
                table.requireRange(anchorStart, GPOS_ANCHOR_FORMAT3_SIZE, "$label format 3")
                OpenTypeAnchor(
                    format = format,
                    x = table.readInt16BE(anchorStart + UINT16_BYTE_LENGTH, "$label xCoordinate"),
                    y = table.readInt16BE(anchorStart + UINT16_BYTE_LENGTH * 2, "$label yCoordinate"),
                    variationDeviceOffset = table.readUInt16BE(anchorStart + UINT16_BYTE_LENGTH * 3, "$label xDeviceOffset"),
                )
            }
            else -> throw IllegalArgumentException("OpenType GPOS anchor format $format is not supported.")
        }
    }

    private fun parseActiveFeatureLookups(
        table: ByteArray,
        scriptListStart: Int,
        featureListStart: Int,
    ): List<FeatureLookupRef> {
        val activeFeatureIndices = parseActiveFeatureIndices(table, scriptListStart)
        if (activeFeatureIndices.isEmpty()) return emptyList()

        table.requireRange(featureListStart, UINT16_BYTE_LENGTH, "OpenType GPOS FeatureList header")
        val featureCount = table.readUInt16BE(featureListStart, "OpenType GPOS FeatureList featureCount")
        table.requireArrayRange(
            offset = table.checkedOffset(featureListStart, UINT16_BYTE_LENGTH, "OpenType GPOS FeatureRecords"),
            count = featureCount,
            recordSize = GPOS_FEATURE_RECORD_SIZE,
            label = "OpenType GPOS FeatureRecords",
        )

        val lookups = mutableListOf<FeatureLookupRef>()
        repeat(featureCount) { featureIndex ->
            val record = featureListStart + UINT16_BYTE_LENGTH + featureIndex * GPOS_FEATURE_RECORD_SIZE
            val tag = table.readTag(record, "OpenType GPOS FeatureRecord $featureIndex tag")
            val featureOffset = table.readUInt16BE(record + SFNT_TAG_BYTE_LENGTH, "OpenType GPOS FeatureRecord $featureIndex offset")
            if (featureIndex !in activeFeatureIndices) return@repeat
            val featureStart = table.checkedOffset(featureListStart, featureOffset, "OpenType GPOS FeatureTable $featureIndex")
            table.requireRange(featureStart, GPOS_FEATURE_HEADER_SIZE, "OpenType GPOS FeatureTable $featureIndex")
            val lookupIndexCount = table.readUInt16BE(featureStart + UINT16_BYTE_LENGTH, "OpenType GPOS FeatureTable $featureIndex lookupIndexCount")
            table.requireArrayRange(
                offset = table.checkedOffset(featureStart, GPOS_FEATURE_HEADER_SIZE, "OpenType GPOS Feature lookup indices"),
                count = lookupIndexCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GPOS FeatureTable $featureIndex lookup indices",
            )
            repeat(lookupIndexCount) { lookupIndexRecord ->
                lookups += FeatureLookupRef(
                    featureTag = tag,
                    lookupIndex = table.readUInt16BE(
                        featureStart + GPOS_FEATURE_HEADER_SIZE + lookupIndexRecord * UINT16_BYTE_LENGTH,
                        "OpenType GPOS FeatureTable $featureIndex lookupListIndex[$lookupIndexRecord]",
                    ),
                )
            }
        }
        return lookups
    }

    private fun parseActiveFeatureIndices(table: ByteArray, scriptListStart: Int): Set<Int> {
        table.requireRange(scriptListStart, UINT16_BYTE_LENGTH, "OpenType GPOS ScriptList header")
        val scriptCount = table.readUInt16BE(scriptListStart, "OpenType GPOS ScriptList scriptCount")
        table.requireArrayRange(
            offset = table.checkedOffset(scriptListStart, UINT16_BYTE_LENGTH, "OpenType GPOS ScriptRecords"),
            count = scriptCount,
            recordSize = GPOS_SCRIPT_RECORD_SIZE,
            label = "OpenType GPOS ScriptRecords",
        )
        val features = LinkedHashSet<Int>()
        repeat(scriptCount) { scriptIndex ->
            val record = scriptListStart + UINT16_BYTE_LENGTH + scriptIndex * GPOS_SCRIPT_RECORD_SIZE
            val scriptOffset = table.readUInt16BE(record + SFNT_TAG_BYTE_LENGTH, "OpenType GPOS ScriptRecord $scriptIndex offset")
            collectScriptFeatureIndices(
                table = table,
                scriptStart = table.checkedOffset(scriptListStart, scriptOffset, "OpenType GPOS ScriptTable $scriptIndex"),
                features = features,
            )
        }
        return features
    }

    private fun collectScriptFeatureIndices(table: ByteArray, scriptStart: Int, features: MutableSet<Int>) {
        table.requireRange(scriptStart, 4, "OpenType GPOS ScriptTable header")
        val defaultLangSysOffset = table.readUInt16BE(scriptStart, "OpenType GPOS ScriptTable defaultLangSys offset")
        val langSysCount = table.readUInt16BE(scriptStart + UINT16_BYTE_LENGTH, "OpenType GPOS ScriptTable langSysCount")
        if (defaultLangSysOffset != 0) {
            collectLangSysFeatureIndices(
                table = table,
                langSysStart = table.checkedOffset(scriptStart, defaultLangSysOffset, "OpenType GPOS default LangSys"),
                features = features,
            )
        }
        table.requireArrayRange(
            offset = table.checkedOffset(scriptStart, 4, "OpenType GPOS LangSysRecords"),
            count = langSysCount,
            recordSize = GPOS_LANG_SYS_RECORD_SIZE,
            label = "OpenType GPOS LangSysRecords",
        )
        repeat(langSysCount) { langSysIndex ->
            val record = scriptStart + 4 + langSysIndex * GPOS_LANG_SYS_RECORD_SIZE
            val langSysOffset = table.readUInt16BE(record + SFNT_TAG_BYTE_LENGTH, "OpenType GPOS LangSysRecord $langSysIndex offset")
            collectLangSysFeatureIndices(
                table = table,
                langSysStart = table.checkedOffset(scriptStart, langSysOffset, "OpenType GPOS LangSysRecord $langSysIndex"),
                features = features,
            )
        }
    }

    private fun collectLangSysFeatureIndices(table: ByteArray, langSysStart: Int, features: MutableSet<Int>) {
        table.requireRange(langSysStart, GPOS_LANG_SYS_HEADER_SIZE, "OpenType GPOS LangSys table")
        val requiredFeatureIndex = table.readUInt16BE(langSysStart + UINT16_BYTE_LENGTH, "OpenType GPOS LangSys requiredFeatureIndex")
        if (requiredFeatureIndex != GPOS_REQUIRED_FEATURE_NONE) {
            features += requiredFeatureIndex
        }
        val featureIndexCount = table.readUInt16BE(langSysStart + UINT16_BYTE_LENGTH * 2, "OpenType GPOS LangSys featureIndexCount")
        table.requireArrayRange(
            offset = table.checkedOffset(langSysStart, GPOS_LANG_SYS_HEADER_SIZE, "OpenType GPOS LangSys feature indices"),
            count = featureIndexCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LangSys feature indices",
        )
        repeat(featureIndexCount) { featureIndexRecord ->
            features += table.readUInt16BE(
                langSysStart + GPOS_LANG_SYS_HEADER_SIZE + featureIndexRecord * UINT16_BYTE_LENGTH,
                "OpenType GPOS LangSys featureIndex[$featureIndexRecord]",
            )
        }
    }

    private fun lookupSubtableStart(table: ByteArray, lookupStart: Int, subtableIndex: Int): Int {
        val subtableOffset = table.readUInt16BE(
            lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
            "OpenType GPOS LookupTable subtableOffset[$subtableIndex]",
        )
        return table.checkedOffset(lookupStart, subtableOffset, "OpenType GPOS subtable $subtableIndex")
    }

    private fun parseCoverageTable(table: ByteArray, coverageStart: Int): List<Int> {
        table.requireRange(coverageStart, 4, "OpenType GPOS coverage table header")
        return when (val format = table.readUInt16BE(coverageStart, "OpenType GPOS coverage format")) {
            1 -> {
                val glyphCount = table.readUInt16BE(coverageStart + UINT16_BYTE_LENGTH, "OpenType GPOS coverage format 1 glyphCount")
                table.requireArrayRange(
                    offset = table.checkedOffset(coverageStart, GPOS_COVERAGE_FORMAT1_HEADER_SIZE, "OpenType GPOS coverage format 1 glyph array"),
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GPOS coverage format 1 glyph array",
                )
                List(glyphCount) { glyphIndex ->
                    table.readUInt16BE(
                        coverageStart + GPOS_COVERAGE_FORMAT1_HEADER_SIZE + glyphIndex * UINT16_BYTE_LENGTH,
                        "OpenType GPOS coverage format 1 glyphArray[$glyphIndex]",
                    )
                }
            }
            2 -> {
                val rangeCount = table.readUInt16BE(coverageStart + UINT16_BYTE_LENGTH, "OpenType GPOS coverage format 2 rangeCount")
                table.requireArrayRange(
                    offset = table.checkedOffset(coverageStart, GPOS_COVERAGE_FORMAT2_HEADER_SIZE, "OpenType GPOS coverage format 2 range records"),
                    count = rangeCount,
                    recordSize = GPOS_COVERAGE_RANGE_RECORD_SIZE,
                    label = "OpenType GPOS coverage format 2 range records",
                )
                buildList {
                    repeat(rangeCount) { rangeIndex ->
                        val rangeStart = coverageStart + GPOS_COVERAGE_FORMAT2_HEADER_SIZE + rangeIndex * GPOS_COVERAGE_RANGE_RECORD_SIZE
                        val startGlyphId = table.readUInt16BE(rangeStart, "OpenType GPOS coverage range $rangeIndex startGlyphID")
                        val endGlyphId = table.readUInt16BE(rangeStart + UINT16_BYTE_LENGTH, "OpenType GPOS coverage range $rangeIndex endGlyphID")
                        require(endGlyphId >= startGlyphId) {
                            "OpenType GPOS coverage range $rangeIndex endGlyphID $endGlyphId is before startGlyphID $startGlyphId."
                        }
                        for (glyphId in startGlyphId..endGlyphId) add(glyphId)
                    }
                }
            }
            else -> throw IllegalArgumentException("OpenType GPOS coverage format $format is not supported.")
        }
    }

    private fun ByteArray.checkedOffset(base: Int, offset: Int, label: String): Int {
        val absolute = base.toLong() + offset.toLong()
        require(absolute in 0..Int.MAX_VALUE.toLong()) {
            "$label offset $offset from base $base is outside addressable Int range."
        }
        return absolute.toInt()
    }

    private fun ByteArray.requireArrayRange(offset: Int, count: Int, recordSize: Int, label: String) {
        val length = count.toLong() * recordSize.toLong()
        require(length <= Int.MAX_VALUE.toLong()) {
            "$label byte length $length exceeds addressable Int range."
        }
        requireRange(offset, length.toInt(), label)
    }

    private data class FeatureLookupRef(
        val featureTag: String,
        val lookupIndex: Int,
    )
}

/**
 * Parser for the Kanvas-supported subset of OpenType GPOS pair positioning.
 */
object OpenTypeGposPairTableParser {
    /**
     * Parses GPOS version `1.0` or `1.1` single adjustment lookups.
     *
     * The parser follows the same active `DFLT`/`latn` plus `kern` feature
     * selection as the bounded pair parser.
     */
    fun parseSingles(table: ByteArray): OpenTypeGposSingleTable {
        table.requireRange(0, GPOS_HEADER_SIZE, "OpenType GPOS header")

        val majorVersion = table.readUInt16BE(0, "OpenType GPOS majorVersion")
        val minorVersion = table.readUInt16BE(2, "OpenType GPOS minorVersion")
        require(majorVersion == 1 && minorVersion in 0..1) {
            "OpenType GPOS table version $majorVersion.$minorVersion is not supported; expected 1.0 or 1.1."
        }

        val scriptListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(4, "OpenType GPOS ScriptList offset"),
            label = "OpenType GPOS ScriptList",
        )
        val featureListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(6, "OpenType GPOS FeatureList offset"),
            label = "OpenType GPOS FeatureList",
        )
        val lookupListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(8, "OpenType GPOS LookupList offset"),
            label = "OpenType GPOS LookupList",
        )
        val kernLookupIndices = parseGposKernLookupIndices(
            table = table,
            scriptListStart = scriptListStart,
            featureListStart = featureListStart,
        )
        if (kernLookupIndices.isEmpty()) {
            return OpenTypeGposSingleTable()
        }

        table.requireRange(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GPOS LookupList header")
        val lookupCount = table.readUInt16BE(lookupListStart, "OpenType GPOS LookupList lookupCount")
        table.requireArrayRange(
            offset = table.checkedOffset(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GPOS LookupList offsets"),
            count = lookupCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LookupList offsets",
        )

        val adjustments = LinkedHashMap<Int, OpenTypeGposSingleAdjustment>()
        for (lookupIndex in kernLookupIndices) {
            require(lookupIndex in 0 until lookupCount) {
                "OpenType GPOS kern lookup index $lookupIndex is outside LookupList range 0 until $lookupCount."
            }
            val lookupOffset = table.readUInt16BE(
                lookupListStart + UINT16_BYTE_LENGTH + lookupIndex * UINT16_BYTE_LENGTH,
                "OpenType GPOS LookupList offset[$lookupIndex]",
            )
            parseGposSingleLookup(
                table = table,
                lookupStart = table.checkedOffset(
                    base = lookupListStart,
                    offset = lookupOffset,
                    label = "OpenType GPOS lookup $lookupIndex",
                ),
                adjustments = adjustments,
            )
        }

        return OpenTypeGposSingleTable(adjustments = adjustments.values.toList())
    }

    /**
     * Parses GPOS version `1.0` or `1.1` pair adjustment lookups.
     *
     * The parser follows active `DFLT` and `latn` scripts, selects the `kern`
     * feature, and interprets lookup type `2` pair-position subtables in format
     * `1` and `2`. Only `valueFormat1` `xAdvance` is surfaced; other value
     * fields are still counted for bounded record traversal.
     *
     * @param table Raw GPOS table payload bytes.
     * @param numGlyphs Glyph count used to expand class `0` in format `2`
     * subtables.
     * @return Parsed pair adjustments. The table can be empty when no supported
     * `kern` pair-position adjustment is present.
     * @throws IllegalArgumentException when a referenced GPOS structure points
     * outside [table], uses an unsupported table version, or declares internally
     * inconsistent counts.
     */
    fun parse(table: ByteArray, numGlyphs: Int = 0): OpenTypeGposPairTable {
        require(numGlyphs in 0..UINT16_MAX) {
            "OpenType GPOS numGlyphs $numGlyphs is outside range 0..65535."
        }
        table.requireRange(0, GPOS_HEADER_SIZE, "OpenType GPOS header")

        val majorVersion = table.readUInt16BE(0, "OpenType GPOS majorVersion")
        val minorVersion = table.readUInt16BE(2, "OpenType GPOS minorVersion")
        require(majorVersion == 1 && minorVersion in 0..1) {
            "OpenType GPOS table version $majorVersion.$minorVersion is not supported; expected 1.0 or 1.1."
        }

        val scriptListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(4, "OpenType GPOS ScriptList offset"),
            label = "OpenType GPOS ScriptList",
        )
        val featureListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(6, "OpenType GPOS FeatureList offset"),
            label = "OpenType GPOS FeatureList",
        )
        val lookupListStart = table.checkedOffset(
            base = 0,
            offset = table.readUInt16BE(8, "OpenType GPOS LookupList offset"),
            label = "OpenType GPOS LookupList",
        )
        val kernLookupIndices = parseGposKernLookupIndices(
            table = table,
            scriptListStart = scriptListStart,
            featureListStart = featureListStart,
        )
        if (kernLookupIndices.isEmpty()) {
            return OpenTypeGposPairTable()
        }

        table.requireRange(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GPOS LookupList header")
        val lookupCount = table.readUInt16BE(lookupListStart, "OpenType GPOS LookupList lookupCount")
        table.requireArrayRange(
            offset = table.checkedOffset(lookupListStart, UINT16_BYTE_LENGTH, "OpenType GPOS LookupList offsets"),
            count = lookupCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LookupList offsets",
        )

        val pairs = LinkedHashMap<Int, OpenTypeGposPairAdjustment>()
        for (lookupIndex in kernLookupIndices) {
            require(lookupIndex in 0 until lookupCount) {
                "OpenType GPOS kern lookup index $lookupIndex is outside LookupList range 0 until $lookupCount."
            }
            val lookupOffset = table.readUInt16BE(
                lookupListStart + UINT16_BYTE_LENGTH + lookupIndex * UINT16_BYTE_LENGTH,
                "OpenType GPOS LookupList offset[$lookupIndex]",
            )
            parseGposPairLookup(
                table = table,
                lookupStart = table.checkedOffset(
                    base = lookupListStart,
                    offset = lookupOffset,
                    label = "OpenType GPOS lookup $lookupIndex",
                ),
                numGlyphs = numGlyphs,
                pairs = pairs,
            )
        }

        return OpenTypeGposPairTable(pairs = pairs.values.toList())
    }

    private fun parseGposKernLookupIndices(
        table: ByteArray,
        scriptListStart: Int,
        featureListStart: Int,
    ): Set<Int> {
        val activeFeatureIndices = parseGposActiveFeatureIndices(table, scriptListStart)
        if (activeFeatureIndices.isEmpty()) {
            return emptySet()
        }

        table.requireRange(featureListStart, UINT16_BYTE_LENGTH, "OpenType GPOS FeatureList header")
        val featureCount = table.readUInt16BE(featureListStart, "OpenType GPOS FeatureList featureCount")
        table.requireArrayRange(
            offset = table.checkedOffset(featureListStart, UINT16_BYTE_LENGTH, "OpenType GPOS FeatureRecords"),
            count = featureCount,
            recordSize = GPOS_FEATURE_RECORD_SIZE,
            label = "OpenType GPOS FeatureRecords",
        )

        val lookups = LinkedHashSet<Int>()
        repeat(featureCount) { featureIndex ->
            val record = featureListStart + UINT16_BYTE_LENGTH + featureIndex * GPOS_FEATURE_RECORD_SIZE
            val tag = table.readTag(record, "OpenType GPOS FeatureRecord $featureIndex tag")
            val featureOffset = table.readUInt16BE(
                record + SFNT_TAG_BYTE_LENGTH,
                "OpenType GPOS FeatureRecord $featureIndex offset",
            )
            if (tag != "kern" || featureIndex !in activeFeatureIndices) {
                return@repeat
            }

            val featureStart = table.checkedOffset(
                base = featureListStart,
                offset = featureOffset,
                label = "OpenType GPOS FeatureTable $featureIndex",
            )
            table.requireRange(featureStart, GPOS_FEATURE_HEADER_SIZE, "OpenType GPOS FeatureTable $featureIndex")
            val lookupIndexCount = table.readUInt16BE(
                featureStart + UINT16_BYTE_LENGTH,
                "OpenType GPOS FeatureTable $featureIndex lookupIndexCount",
            )
            table.requireArrayRange(
                offset = table.checkedOffset(featureStart, GPOS_FEATURE_HEADER_SIZE, "OpenType GPOS Feature lookup indices"),
                count = lookupIndexCount,
                recordSize = UINT16_BYTE_LENGTH,
                label = "OpenType GPOS FeatureTable $featureIndex lookup indices",
            )
            repeat(lookupIndexCount) { lookupIndexRecord ->
                lookups += table.readUInt16BE(
                    featureStart + GPOS_FEATURE_HEADER_SIZE + lookupIndexRecord * UINT16_BYTE_LENGTH,
                    "OpenType GPOS FeatureTable $featureIndex lookupListIndex[$lookupIndexRecord]",
                )
            }
        }

        return lookups
    }

    private fun parseGposSingleLookup(
        table: ByteArray,
        lookupStart: Int,
        adjustments: MutableMap<Int, OpenTypeGposSingleAdjustment>,
    ) {
        table.requireRange(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GPOS LookupTable header")
        val lookupType = table.readUInt16BE(lookupStart, "OpenType GPOS LookupTable lookupType")
        val subtableCount = table.readUInt16BE(
            lookupStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GPOS LookupTable subTableCount",
        )
        table.requireArrayRange(
            offset = table.checkedOffset(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GPOS LookupTable subtable offsets"),
            count = subtableCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LookupTable subtable offsets",
        )
        if (lookupType != GPOS_SINGLE_ADJUSTMENT_LOOKUP_TYPE) {
            return
        }

        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GPOS LookupTable subtableOffset[$subtableIndex]",
            )
            parseGposSingleSubtable(
                table = table,
                subtableStart = table.checkedOffset(
                    base = lookupStart,
                    offset = subtableOffset,
                    label = "OpenType GPOS SinglePos subtable $subtableIndex",
                ),
                adjustments = adjustments,
            )
        }
    }

    private fun parseGposSingleSubtable(
        table: ByteArray,
        subtableStart: Int,
        adjustments: MutableMap<Int, OpenTypeGposSingleAdjustment>,
    ) {
        table.requireRange(subtableStart, 6, "OpenType GPOS SinglePos subtable header")
        val posFormat = table.readUInt16BE(subtableStart, "OpenType GPOS SinglePos posFormat")
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GPOS SinglePos coverageOffset",
        )
        val valueFormat = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GPOS SinglePos valueFormat",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GPOS SinglePos coverage",
            ),
        )
        when (posFormat) {
            1 -> parseGposSingleFormat1(
                table = table,
                subtableStart = subtableStart,
                coverage = coverage,
                valueFormat = valueFormat,
                adjustments = adjustments,
            )
            2 -> parseGposSingleFormat2(
                table = table,
                subtableStart = subtableStart,
                coverage = coverage,
                valueFormat = valueFormat,
                adjustments = adjustments,
            )
        }
    }

    private fun parseGposSingleFormat1(
        table: ByteArray,
        subtableStart: Int,
        coverage: List<Int>,
        valueFormat: Int,
        adjustments: MutableMap<Int, OpenTypeGposSingleAdjustment>,
    ) {
        val valueRecordStart = table.checkedOffset(
            subtableStart,
            6,
            "OpenType GPOS SinglePos format 1 value record",
        )
        val recordSize = valueRecordSize(valueFormat)
        table.requireRange(valueRecordStart, recordSize, "OpenType GPOS SinglePos format 1 value record")
        val valueRecord = readGposValueRecord(
            table = table,
            valueStart = valueRecordStart,
            valueFormat = valueFormat,
            label = "OpenType GPOS SinglePos format 1 valueRecord",
        )
        if (valueRecord == OpenTypeGposValueRecord()) {
            return
        }
        coverage.forEach { glyphId ->
            adjustments[glyphId] = OpenTypeGposSingleAdjustment(glyphId = glyphId, valueRecord = valueRecord)
        }
    }

    private fun parseGposSingleFormat2(
        table: ByteArray,
        subtableStart: Int,
        coverage: List<Int>,
        valueFormat: Int,
        adjustments: MutableMap<Int, OpenTypeGposSingleAdjustment>,
    ) {
        table.requireRange(subtableStart, 8, "OpenType GPOS SinglePos format 2 header")
        val valueCount = table.readUInt16BE(
            subtableStart + 6,
            "OpenType GPOS SinglePos format 2 valueCount",
        )
        require(valueCount == coverage.size) {
            "OpenType GPOS SinglePos format 2 valueCount $valueCount must equal coverage glyph count ${coverage.size}."
        }
        val recordSize = valueRecordSize(valueFormat)
        val valuesStart = table.checkedOffset(
            subtableStart,
            8,
            "OpenType GPOS SinglePos format 2 value records",
        )
        table.requireArrayRange(
            offset = valuesStart,
            count = valueCount,
            recordSize = recordSize,
            label = "OpenType GPOS SinglePos format 2 value records",
        )
        repeat(valueCount) { index ->
            val valueRecord = readGposValueRecord(
                table = table,
                valueStart = valuesStart + index * recordSize,
                valueFormat = valueFormat,
                label = "OpenType GPOS SinglePos format 2 valueRecord[$index]",
            )
            if (valueRecord != OpenTypeGposValueRecord()) {
                adjustments[coverage[index]] = OpenTypeGposSingleAdjustment(
                    glyphId = coverage[index],
                    valueRecord = valueRecord,
                )
            }
        }
    }

    private fun parseGposActiveFeatureIndices(
        table: ByteArray,
        scriptListStart: Int,
    ): Set<Int> {
        table.requireRange(scriptListStart, UINT16_BYTE_LENGTH, "OpenType GPOS ScriptList header")
        val scriptCount = table.readUInt16BE(scriptListStart, "OpenType GPOS ScriptList scriptCount")
        table.requireArrayRange(
            offset = table.checkedOffset(scriptListStart, UINT16_BYTE_LENGTH, "OpenType GPOS ScriptRecords"),
            count = scriptCount,
            recordSize = GPOS_SCRIPT_RECORD_SIZE,
            label = "OpenType GPOS ScriptRecords",
        )

        val features = LinkedHashSet<Int>()
        repeat(scriptCount) { scriptIndex ->
            val record = scriptListStart + UINT16_BYTE_LENGTH + scriptIndex * GPOS_SCRIPT_RECORD_SIZE
            val tag = table.readTag(record, "OpenType GPOS ScriptRecord $scriptIndex tag")
            val scriptOffset = table.readUInt16BE(
                record + SFNT_TAG_BYTE_LENGTH,
                "OpenType GPOS ScriptRecord $scriptIndex offset",
            )
            if (tag == "DFLT" || tag == "latn") {
                collectGposScriptFeatureIndices(
                    table = table,
                    scriptStart = table.checkedOffset(
                        base = scriptListStart,
                        offset = scriptOffset,
                        label = "OpenType GPOS ScriptTable $tag",
                    ),
                    features = features,
                )
            }
        }

        return features
    }

    private fun collectGposScriptFeatureIndices(
        table: ByteArray,
        scriptStart: Int,
        features: MutableSet<Int>,
    ) {
        table.requireRange(scriptStart, 4, "OpenType GPOS ScriptTable header")
        val defaultLangSysOffset = table.readUInt16BE(
            scriptStart,
            "OpenType GPOS ScriptTable defaultLangSys offset",
        )
        val langSysCount = table.readUInt16BE(
            scriptStart + UINT16_BYTE_LENGTH,
            "OpenType GPOS ScriptTable langSysCount",
        )

        if (defaultLangSysOffset != 0) {
            collectGposLangSysFeatureIndices(
                table = table,
                langSysStart = table.checkedOffset(
                    base = scriptStart,
                    offset = defaultLangSysOffset,
                    label = "OpenType GPOS default LangSys",
                ),
                features = features,
            )
        }

        table.requireArrayRange(
            offset = table.checkedOffset(scriptStart, 4, "OpenType GPOS LangSysRecords"),
            count = langSysCount,
            recordSize = GPOS_LANG_SYS_RECORD_SIZE,
            label = "OpenType GPOS LangSysRecords",
        )
        repeat(langSysCount) { langSysIndex ->
            val record = scriptStart + 4 + langSysIndex * GPOS_LANG_SYS_RECORD_SIZE
            val langSysOffset = table.readUInt16BE(
                record + SFNT_TAG_BYTE_LENGTH,
                "OpenType GPOS LangSysRecord $langSysIndex offset",
            )
            collectGposLangSysFeatureIndices(
                table = table,
                langSysStart = table.checkedOffset(
                    base = scriptStart,
                    offset = langSysOffset,
                    label = "OpenType GPOS LangSysRecord $langSysIndex",
                ),
                features = features,
            )
        }
    }

    private fun collectGposLangSysFeatureIndices(
        table: ByteArray,
        langSysStart: Int,
        features: MutableSet<Int>,
    ) {
        table.requireRange(langSysStart, GPOS_LANG_SYS_HEADER_SIZE, "OpenType GPOS LangSys table")
        val requiredFeatureIndex = table.readUInt16BE(
            langSysStart + UINT16_BYTE_LENGTH,
            "OpenType GPOS LangSys requiredFeatureIndex",
        )
        if (requiredFeatureIndex != GPOS_REQUIRED_FEATURE_NONE) {
            features += requiredFeatureIndex
        }

        val featureIndexCount = table.readUInt16BE(
            langSysStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GPOS LangSys featureIndexCount",
        )
        table.requireArrayRange(
            offset = table.checkedOffset(langSysStart, GPOS_LANG_SYS_HEADER_SIZE, "OpenType GPOS LangSys feature indices"),
            count = featureIndexCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LangSys feature indices",
        )
        repeat(featureIndexCount) { featureIndexRecord ->
            features += table.readUInt16BE(
                langSysStart + GPOS_LANG_SYS_HEADER_SIZE + featureIndexRecord * UINT16_BYTE_LENGTH,
                "OpenType GPOS LangSys featureIndex[$featureIndexRecord]",
            )
        }
    }

    private fun parseGposPairLookup(
        table: ByteArray,
        lookupStart: Int,
        numGlyphs: Int,
        pairs: MutableMap<Int, OpenTypeGposPairAdjustment>,
    ) {
        table.requireRange(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GPOS LookupTable header")
        val lookupType = table.readUInt16BE(lookupStart, "OpenType GPOS LookupTable lookupType")
        val subtableCount = table.readUInt16BE(
            lookupStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GPOS LookupTable subTableCount",
        )
        table.requireArrayRange(
            offset = table.checkedOffset(lookupStart, GPOS_LOOKUP_HEADER_SIZE, "OpenType GPOS LookupTable subtable offsets"),
            count = subtableCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS LookupTable subtable offsets",
        )
        if (lookupType != GPOS_PAIR_ADJUSTMENT_LOOKUP_TYPE) {
            return
        }

        repeat(subtableCount) { subtableIndex ->
            val subtableOffset = table.readUInt16BE(
                lookupStart + GPOS_LOOKUP_HEADER_SIZE + subtableIndex * UINT16_BYTE_LENGTH,
                "OpenType GPOS LookupTable subtableOffset[$subtableIndex]",
            )
            parseGposPairSubtable(
                table = table,
                subtableStart = table.checkedOffset(
                    base = lookupStart,
                    offset = subtableOffset,
                    label = "OpenType GPOS PairPos subtable $subtableIndex",
                ),
                numGlyphs = numGlyphs,
                pairs = pairs,
            )
        }
    }

    private fun parseGposPairSubtable(
        table: ByteArray,
        subtableStart: Int,
        numGlyphs: Int,
        pairs: MutableMap<Int, OpenTypeGposPairAdjustment>,
    ) {
        table.requireRange(
            subtableStart,
            GPOS_PAIR_SUBTABLE_FORMAT1_HEADER_SIZE,
            "OpenType GPOS PairPos subtable header",
        )
        val posFormat = table.readUInt16BE(subtableStart, "OpenType GPOS PairPos posFormat")
        val coverageOffset = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH,
            "OpenType GPOS PairPos coverageOffset",
        )
        val valueFormat1 = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 2,
            "OpenType GPOS PairPos valueFormat1",
        )
        val valueFormat2 = table.readUInt16BE(
            subtableStart + UINT16_BYTE_LENGTH * 3,
            "OpenType GPOS PairPos valueFormat2",
        )
        val coverage = parseCoverageTable(
            table = table,
            coverageStart = table.checkedOffset(
                base = subtableStart,
                offset = coverageOffset,
                label = "OpenType GPOS PairPos coverage",
            ),
        )

        when (posFormat) {
            1 -> parseGposPairFormat1(
                table = table,
                subtableStart = subtableStart,
                coverage = coverage,
                valueFormat1 = valueFormat1,
                valueFormat2 = valueFormat2,
                pairs = pairs,
            )
            2 -> parseGposPairFormat2(
                table = table,
                subtableStart = subtableStart,
                coverage = coverage,
                valueFormat1 = valueFormat1,
                valueFormat2 = valueFormat2,
                numGlyphs = numGlyphs,
                pairs = pairs,
            )
        }
    }

    private fun parseGposPairFormat1(
        table: ByteArray,
        subtableStart: Int,
        coverage: List<Int>,
        valueFormat1: Int,
        valueFormat2: Int,
        pairs: MutableMap<Int, OpenTypeGposPairAdjustment>,
    ) {
        val pairSetCount = table.readUInt16BE(
            subtableStart + 8,
            "OpenType GPOS pair adjustment format 1 pairSetCount",
        )
        require(pairSetCount == coverage.size) {
            "OpenType GPOS pair adjustment format 1 pairSetCount $pairSetCount must equal coverage glyph count ${coverage.size}."
        }
        table.requireArrayRange(
            offset = table.checkedOffset(
                subtableStart,
                GPOS_PAIR_SUBTABLE_FORMAT1_HEADER_SIZE,
                "OpenType GPOS pair adjustment format 1 pairSet offsets",
            ),
            count = pairSetCount,
            recordSize = UINT16_BYTE_LENGTH,
            label = "OpenType GPOS pair adjustment format 1 pairSet offsets",
        )

        val value1Size = valueRecordSize(valueFormat1)
        val value2Size = valueRecordSize(valueFormat2)
        val recordSize = GPOS_PAIR_VALUE_RECORD_GLYPH_SIZE + value1Size + value2Size
        repeat(pairSetCount) { pairSetIndex ->
            val pairSetOffset = table.readUInt16BE(
                subtableStart + GPOS_PAIR_SUBTABLE_FORMAT1_HEADER_SIZE + pairSetIndex * UINT16_BYTE_LENGTH,
                "OpenType GPOS pair adjustment format 1 pairSetOffset[$pairSetIndex]",
            )
            val pairSetStart = table.checkedOffset(
                base = subtableStart,
                offset = pairSetOffset,
                label = "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex",
            )
            table.requireRange(
                pairSetStart,
                UINT16_BYTE_LENGTH,
                "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex header",
            )
            val pairValueCount = table.readUInt16BE(
                pairSetStart,
                "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex pairValueCount",
            )
            val pairValuesStart = table.checkedOffset(
                pairSetStart,
                UINT16_BYTE_LENGTH,
                "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex records",
            )
            table.requireArrayRange(
                offset = pairValuesStart,
                count = pairValueCount,
                recordSize = recordSize,
                label = "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex records",
            )

            repeat(pairValueCount) { pairValueIndex ->
                val pairValueStart = pairValuesStart + pairValueIndex * recordSize
                val rightGlyphId = table.readUInt16BE(
                    pairValueStart,
                    "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex secondGlyph[$pairValueIndex]",
                )
                val firstValueRecord = readGposValueRecord(
                    table = table,
                    valueStart = pairValueStart + GPOS_PAIR_VALUE_RECORD_GLYPH_SIZE,
                    valueFormat = valueFormat1,
                    label = "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex valueRecord1[$pairValueIndex]",
                )
                val secondValueRecord = readGposValueRecord(
                    table = table,
                    valueStart = pairValueStart + GPOS_PAIR_VALUE_RECORD_GLYPH_SIZE + value1Size,
                    valueFormat = valueFormat2,
                    label = "OpenType GPOS pair adjustment format 1 PairSet $pairSetIndex valueRecord2[$pairValueIndex]",
                )
                pairs.putGposPair(
                    leftGlyphId = coverage[pairSetIndex],
                    rightGlyphId = rightGlyphId,
                    firstValueRecord = firstValueRecord,
                    secondValueRecord = secondValueRecord,
                )
            }
        }
    }

    private fun parseGposPairFormat2(
        table: ByteArray,
        subtableStart: Int,
        coverage: List<Int>,
        valueFormat1: Int,
        valueFormat2: Int,
        numGlyphs: Int,
        pairs: MutableMap<Int, OpenTypeGposPairAdjustment>,
    ) {
        table.requireRange(
            subtableStart,
            GPOS_PAIR_SUBTABLE_FORMAT2_HEADER_SIZE,
            "OpenType GPOS pair adjustment format 2 header",
        )
        val classDef1Offset = table.readUInt16BE(
            subtableStart + 8,
            "OpenType GPOS pair adjustment format 2 classDef1Offset",
        )
        val classDef2Offset = table.readUInt16BE(
            subtableStart + 10,
            "OpenType GPOS pair adjustment format 2 classDef2Offset",
        )
        val class1Count = table.readUInt16BE(
            subtableStart + 12,
            "OpenType GPOS pair adjustment format 2 class1Count",
        )
        val class2Count = table.readUInt16BE(
            subtableStart + 14,
            "OpenType GPOS pair adjustment format 2 class2Count",
        )
        val classDef1 = parseClassDefTable(
            table = table,
            classDefStart = table.checkedOffset(
                base = subtableStart,
                offset = classDef1Offset,
                label = "OpenType GPOS pair adjustment format 2 classDef1",
            ),
        )
        val classDef2 = parseClassDefTable(
            table = table,
            classDefStart = table.checkedOffset(
                base = subtableStart,
                offset = classDef2Offset,
                label = "OpenType GPOS pair adjustment format 2 classDef2",
            ),
        )

        val value1Size = valueRecordSize(valueFormat1)
        val value2Size = valueRecordSize(valueFormat2)
        val recordSize = value1Size + value2Size
        val classRecordCount = class1Count.toLong() * class2Count.toLong()
        require(classRecordCount <= GPOS_MAX_CLASS_PAIR_RECORDS) {
            "OpenType GPOS pair adjustment format 2 class matrix record count $classRecordCount exceeds supported limit $GPOS_MAX_CLASS_PAIR_RECORDS."
        }
        val classRecordsStart = table.checkedOffset(
            subtableStart,
            GPOS_PAIR_SUBTABLE_FORMAT2_HEADER_SIZE,
            "OpenType GPOS pair adjustment format 2 class records",
        )
        table.requireArrayRange(
            offset = classRecordsStart,
            count = classRecordCount.toInt(),
            recordSize = recordSize,
            label = "OpenType GPOS pair adjustment format 2 class records",
        )
        if (recordSize == 0) {
            return
        }

        var recordStart = classRecordsStart
        var expandedPairCount = 0L
        for (class1 in 0 until class1Count) {
            for (class2 in 0 until class2Count) {
                val firstValueRecord = readGposValueRecord(
                    table = table,
                    valueStart = recordStart,
                    valueFormat = valueFormat1,
                    label = "OpenType GPOS pair adjustment format 2 classValueRecord[$class1][$class2] valueRecord1",
                )
                val secondValueRecord = readGposValueRecord(
                    table = table,
                    valueStart = recordStart + value1Size,
                    valueFormat = valueFormat2,
                    label = "OpenType GPOS pair adjustment format 2 classValueRecord[$class1][$class2] valueRecord2",
                )
                if (firstValueRecord != OpenTypeGposValueRecord() || secondValueRecord != OpenTypeGposValueRecord()) {
                    val leftGlyphs = coverage.filter { classDef1.classOf(it) == class1 }
                    val rightGlyphCount = classDef2.glyphCountForClass(class2, numGlyphs)
                    val addedPairCount = leftGlyphs.size.toLong() * rightGlyphCount.toLong()
                    val nextExpandedPairCount = expandedPairCount + addedPairCount
                    require(nextExpandedPairCount <= GPOS_MAX_EXPANDED_CLASS_GLYPH_PAIRS) {
                        "OpenType GPOS pair adjustment format 2 expanded glyph pair count $nextExpandedPairCount exceeds supported limit $GPOS_MAX_EXPANDED_CLASS_GLYPH_PAIRS."
                    }
                    expandedPairCount = nextExpandedPairCount
                    val rightGlyphs = classDef2.glyphsForClass(class2, numGlyphs)
                    for (leftGlyphId in leftGlyphs) {
                        for (rightGlyphId in rightGlyphs) {
                            pairs.putGposPair(
                                leftGlyphId = leftGlyphId,
                                rightGlyphId = rightGlyphId,
                                firstValueRecord = firstValueRecord,
                                secondValueRecord = secondValueRecord,
                            )
                        }
                    }
                }
                recordStart += recordSize
            }
        }
    }

    private fun parseCoverageTable(table: ByteArray, coverageStart: Int): List<Int> {
        table.requireRange(coverageStart, 4, "OpenType GPOS coverage table header")
        return when (val format = table.readUInt16BE(coverageStart, "OpenType GPOS coverage format")) {
            1 -> {
                val glyphCount = table.readUInt16BE(
                    coverageStart + UINT16_BYTE_LENGTH,
                    "OpenType GPOS coverage format 1 glyphCount",
                )
                table.requireArrayRange(
                    offset = table.checkedOffset(
                        coverageStart,
                        GPOS_COVERAGE_FORMAT1_HEADER_SIZE,
                        "OpenType GPOS coverage format 1 glyph array",
                    ),
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GPOS coverage format 1 glyph array",
                )
                List(glyphCount) { glyphIndex ->
                    table.readUInt16BE(
                        coverageStart + GPOS_COVERAGE_FORMAT1_HEADER_SIZE + glyphIndex * UINT16_BYTE_LENGTH,
                        "OpenType GPOS coverage format 1 glyphArray[$glyphIndex]",
                    )
                }
            }
            2 -> {
                val rangeCount = table.readUInt16BE(
                    coverageStart + UINT16_BYTE_LENGTH,
                    "OpenType GPOS coverage format 2 rangeCount",
                )
                table.requireArrayRange(
                    offset = table.checkedOffset(
                        coverageStart,
                        GPOS_COVERAGE_FORMAT2_HEADER_SIZE,
                        "OpenType GPOS coverage format 2 range records",
                    ),
                    count = rangeCount,
                    recordSize = GPOS_COVERAGE_RANGE_RECORD_SIZE,
                    label = "OpenType GPOS coverage format 2 range records",
                )
                val glyphs = ArrayList<Int>()
                repeat(rangeCount) { rangeIndex ->
                    val rangeStart = coverageStart + GPOS_COVERAGE_FORMAT2_HEADER_SIZE +
                        rangeIndex * GPOS_COVERAGE_RANGE_RECORD_SIZE
                    val startGlyphId = table.readUInt16BE(
                        rangeStart,
                        "OpenType GPOS coverage format 2 range $rangeIndex startGlyphID",
                    )
                    val endGlyphId = table.readUInt16BE(
                        rangeStart + UINT16_BYTE_LENGTH,
                        "OpenType GPOS coverage format 2 range $rangeIndex endGlyphID",
                    )
                    require(endGlyphId >= startGlyphId) {
                        "OpenType GPOS coverage format 2 range $rangeIndex endGlyphID $endGlyphId is before startGlyphID $startGlyphId."
                    }
                    val glyphCount = endGlyphId - startGlyphId + 1
                    require(glyphs.size + glyphCount <= GPOS_MAX_COVERAGE_GLYPHS) {
                        "OpenType GPOS coverage table expands beyond $GPOS_MAX_COVERAGE_GLYPHS glyphs."
                    }
                    for (glyphId in startGlyphId..endGlyphId) {
                        glyphs += glyphId
                    }
                }
                glyphs
            }
            else -> throw IllegalArgumentException("OpenType GPOS coverage format $format is not supported.")
        }
    }

    private fun parseClassDefTable(table: ByteArray, classDefStart: Int): OpenTypeGposClassDef {
        table.requireRange(classDefStart, 4, "OpenType GPOS ClassDef table header")
        val classes = HashMap<Int, Int>()
        when (val format = table.readUInt16BE(classDefStart, "OpenType GPOS ClassDef format")) {
            1 -> {
                table.requireRange(
                    classDefStart,
                    GPOS_CLASS_DEF_FORMAT1_HEADER_SIZE,
                    "OpenType GPOS ClassDef format 1 header",
                )
                val startGlyphId = table.readUInt16BE(
                    classDefStart + UINT16_BYTE_LENGTH,
                    "OpenType GPOS ClassDef format 1 startGlyphID",
                )
                val glyphCount = table.readUInt16BE(
                    classDefStart + UINT16_BYTE_LENGTH * 2,
                    "OpenType GPOS ClassDef format 1 glyphCount",
                )
                require(startGlyphId + glyphCount.toLong() <= GPOS_MAX_COVERAGE_GLYPHS) {
                    "OpenType GPOS ClassDef format 1 glyph range starts at $startGlyphId with count $glyphCount and exceeds glyph ID range."
                }
                table.requireArrayRange(
                    offset = table.checkedOffset(
                        classDefStart,
                        GPOS_CLASS_DEF_FORMAT1_HEADER_SIZE,
                        "OpenType GPOS ClassDef format 1 classValueArray",
                    ),
                    count = glyphCount,
                    recordSize = UINT16_BYTE_LENGTH,
                    label = "OpenType GPOS ClassDef format 1 classValueArray",
                )
                repeat(glyphCount) { glyphIndex ->
                    classes[startGlyphId + glyphIndex] = table.readUInt16BE(
                        classDefStart + GPOS_CLASS_DEF_FORMAT1_HEADER_SIZE + glyphIndex * UINT16_BYTE_LENGTH,
                        "OpenType GPOS ClassDef format 1 classValueArray[$glyphIndex]",
                    )
                }
            }
            2 -> {
                val classRangeCount = table.readUInt16BE(
                    classDefStart + UINT16_BYTE_LENGTH,
                    "OpenType GPOS ClassDef format 2 classRangeCount",
                )
                table.requireArrayRange(
                    offset = table.checkedOffset(
                        classDefStart,
                        GPOS_CLASS_DEF_FORMAT2_HEADER_SIZE,
                        "OpenType GPOS ClassDef format 2 class range records",
                    ),
                    count = classRangeCount,
                    recordSize = GPOS_CLASS_RANGE_RECORD_SIZE,
                    label = "OpenType GPOS ClassDef format 2 class range records",
                )
                var expandedGlyphs = 0
                repeat(classRangeCount) { rangeIndex ->
                    val rangeStart = classDefStart + GPOS_CLASS_DEF_FORMAT2_HEADER_SIZE +
                        rangeIndex * GPOS_CLASS_RANGE_RECORD_SIZE
                    val startGlyphId = table.readUInt16BE(
                        rangeStart,
                        "OpenType GPOS ClassDef format 2 range $rangeIndex startGlyphID",
                    )
                    val endGlyphId = table.readUInt16BE(
                        rangeStart + UINT16_BYTE_LENGTH,
                        "OpenType GPOS ClassDef format 2 range $rangeIndex endGlyphID",
                    )
                    val klass = table.readUInt16BE(
                        rangeStart + UINT16_BYTE_LENGTH * 2,
                        "OpenType GPOS ClassDef format 2 range $rangeIndex class",
                    )
                    require(endGlyphId >= startGlyphId) {
                        "OpenType GPOS ClassDef format 2 range $rangeIndex endGlyphID $endGlyphId is before startGlyphID $startGlyphId."
                    }
                    val glyphCount = endGlyphId - startGlyphId + 1
                    require(expandedGlyphs + glyphCount <= GPOS_MAX_COVERAGE_GLYPHS) {
                        "OpenType GPOS ClassDef format 2 ranges expand beyond $GPOS_MAX_COVERAGE_GLYPHS glyphs."
                    }
                    expandedGlyphs += glyphCount
                    for (glyphId in startGlyphId..endGlyphId) {
                        classes[glyphId] = klass
                    }
                }
            }
            else -> throw IllegalArgumentException("OpenType GPOS ClassDef format $format is not supported.")
        }

        return OpenTypeGposClassDef(classes)
    }

    private fun readGposValueRecord(
        table: ByteArray,
        valueStart: Int,
        valueFormat: Int,
        label: String,
    ): OpenTypeGposValueRecord {
        var valueOffset = valueStart
        val xPlacement = if (valueFormat and GPOS_VALUE_X_PLACEMENT != 0) {
            table.readInt16BE(valueOffset, "$label xPlacement").also {
                valueOffset += UINT16_BYTE_LENGTH
            }
        } else {
            0
        }
        val yPlacement = if (valueFormat and GPOS_VALUE_Y_PLACEMENT != 0) {
            table.readInt16BE(valueOffset, "$label yPlacement").also {
                valueOffset += UINT16_BYTE_LENGTH
            }
        } else {
            0
        }
        val xAdvance = if (valueFormat and GPOS_VALUE_X_ADVANCE != 0) {
            table.readInt16BE(valueOffset, "$label xAdvance")
        } else {
            0
        }
        return OpenTypeGposValueRecord(
            xPlacement = xPlacement,
            yPlacement = yPlacement,
            xAdvance = xAdvance,
        )
    }

    private fun valueRecordSize(valueFormat: Int): Int =
        Integer.bitCount(valueFormat and UINT16_MAX) * UINT16_BYTE_LENGTH

    private fun MutableMap<Int, OpenTypeGposPairAdjustment>.putGposPair(
        leftGlyphId: Int,
        rightGlyphId: Int,
        firstValueRecord: OpenTypeGposValueRecord,
        secondValueRecord: OpenTypeGposValueRecord,
    ) {
        if (firstValueRecord != OpenTypeGposValueRecord() || secondValueRecord != OpenTypeGposValueRecord()) {
            this[openTypeGlyphPairKey(leftGlyphId, rightGlyphId)] = OpenTypeGposPairAdjustment(
                leftGlyphId = leftGlyphId,
                rightGlyphId = rightGlyphId,
                firstValueRecord = firstValueRecord,
                secondValueRecord = secondValueRecord,
            )
        }
    }

    private fun ByteArray.checkedOffset(base: Int, offset: Int, label: String): Int {
        val absolute = base.toLong() + offset.toLong()
        require(absolute in 0..Int.MAX_VALUE.toLong()) {
            "$label offset $offset from base $base is outside addressable Int range."
        }
        return absolute.toInt()
    }

    private fun ByteArray.requireArrayRange(
        offset: Int,
        count: Int,
        recordSize: Int,
        label: String,
    ) {
        val length = count.toLong() * recordSize.toLong()
        require(length <= Int.MAX_VALUE.toLong()) {
            "$label byte length $length exceeds addressable Int range."
        }
        requireRange(offset, length.toInt(), label)
    }

    private data class OpenTypeGposClassDef(
        private val classes: Map<Int, Int>,
    ) {
        fun classOf(glyphId: Int): Int = classes[glyphId] ?: 0

        fun glyphCountForClass(klass: Int, numGlyphs: Int): Int =
            if (klass == 0) {
                (0 until numGlyphs).count { classOf(it) == 0 }
            } else {
                classes.count { it.value == klass }
            }

        fun glyphsForClass(klass: Int, numGlyphs: Int): List<Int> =
            if (klass == 0) {
                (0 until numGlyphs).filter { classOf(it) == 0 }
            } else {
                classes.entries.asSequence()
                    .filter { it.value == klass }
                    .map { it.key }
                    .toList()
            }
    }
}

/**
 * Four-byte OpenType variation axis tag exposed both as text and as the packed
 * 32-bit integer used by Skia-compatible and SFNT-facing APIs.
 *
 * The textual value preserves the exact ISO-8859-1 bytes from the `fvar` axis
 * record, so standard tags such as `wght`, `wdth`, `opsz`, and `slnt` are
 * readable without losing access to the raw packed representation.
 *
 * @property text Four-character axis tag text decoded from the axis record.
 * @property rawValue Packed big-endian 32-bit tag value.
 */
data class OpenTypeVariationAxisTag(
    val text: String,
    val rawValue: Int,
)

/**
 * Signed OpenType `Fixed` value with 16 integer bits and 16 fractional bits.
 *
 * OpenType `fvar` axis coordinates are stored as signed fixed-point numbers.
 * [rawValue] preserves the exact table value while [value] exposes the decoded
 * design coordinate for callers that need arithmetic or presentation.
 *
 * @property rawValue Raw signed 16.16 fixed-point integer from the SFNT table.
 */
@JvmInline
value class OpenTypeFixed16Dot16(
    val rawValue: Int,
) {
    /**
     * Decoded fixed-point value as a floating-point design coordinate.
     */
    val value: Double
        get() = rawValue.toDouble() / FIXED16_DOT16_SCALE
}

/**
 * Parsed OpenType `fvar` axis record.
 *
 * Version 1.0 `fvar` axis records describe one user-selectable design axis and
 * its legal coordinate range. The parser keeps all numeric fields in their
 * original OpenType units: axis coordinates remain [OpenTypeFixed16Dot16],
 * [flags] is the raw `uint16` flags field, and [nameId] is the raw `name` table
 * identifier for localized axis labels.
 *
 * @property tag Axis tag as both readable text and packed integer value.
 * @property minimum Minimum supported design coordinate for the axis.
 * @property defaultValue Default design coordinate for the axis.
 * @property maximum Maximum supported design coordinate for the axis.
 * @property flags Raw OpenType axis flags field.
 * @property nameId OpenType `name` table identifier for the axis label.
 */
data class OpenTypeVariationAxis(
    val tag: OpenTypeVariationAxisTag,
    val minimum: OpenTypeFixed16Dot16,
    val defaultValue: OpenTypeFixed16Dot16,
    val maximum: OpenTypeFixed16Dot16,
    val flags: Int,
    val nameId: Int,
)

/**
 * Parsed variable-font table container for `fvar`, `avar`, `gvar`, `HVAR`,
 * `MVAR`, and related variation data.
 *
 * `fvar` axis metadata and `avar` segment maps are parsed today. Other
 * variation tables remain available through [OpenTypeFaceData.rawTables] until
 * their pure Kotlin readers are added.
 *
 * @property axes Parsed OpenType `fvar` variation axes in table order.
 * @property axisSegmentMaps Parsed OpenType `avar` segment maps in the same
 * order as [axes].
 */
data class VariationTables(
    val axes: List<OpenTypeVariationAxis> = emptyList(),
    val axisSegmentMaps: List<OpenTypeAvarAxisSegmentMap> = emptyList(),
)

/**
 * Parsed OpenType `avar` segment map for one variation axis.
 *
 * @property segments Ordered from/to coordinate pairs decoded from F2DOT14 values.
 */
data class OpenTypeAvarAxisSegmentMap(
    val segments: List<OpenTypeAvarSegment> = emptyList(),
)

/**
 * One OpenType `avar` normalized-coordinate remapping segment.
 *
 * @property fromCoordinate Input normalized coordinate in the `-1.0..1.0` design range.
 * @property toCoordinate Output normalized coordinate after axis variation remapping.
 */
data class OpenTypeAvarSegment(
    val fromCoordinate: Double,
    val toCoordinate: Double,
)

/**
 * Parser for OpenType `fvar` version 1.0 variable-font axis metadata.
 */
object OpenTypeFvarTableParser {
    /**
     * Parses a bounded raw `fvar` table into [VariationTables].
     *
     * This parser accepts version `1.0` tables, reads axis records from the
     * declared axis array offset, preserves extra bytes in axis records whose
     * size is greater than the version 1.0 minimum, and validates every accessed
     * byte range before reading. Instance records are intentionally not decoded
     * yet; their bytes remain available through [OpenTypeFaceData.rawTables].
     *
     * @param table Raw `fvar` table payload bytes.
     * @return Parsed variation tables containing the `fvar` axis records.
     * @throws IllegalArgumentException when the table header, version, axis
     * record size, or axis record array range is malformed.
     */
    fun parse(table: ByteArray): VariationTables {
        require(table.size >= FVAR_HEADER_SIZE) {
            "OpenType fvar table must contain at least $FVAR_HEADER_SIZE bytes, was ${table.size}."
        }

        val majorVersion = table.readUInt16BE(0, "OpenType fvar majorVersion")
        val minorVersion = table.readUInt16BE(2, "OpenType fvar minorVersion")
        require(majorVersion == 1 && minorVersion == 0) {
            "OpenType fvar table version must be 1.0, was $majorVersion.$minorVersion."
        }

        val axesArrayOffset = table.readUInt16BE(4, "OpenType fvar axesArrayOffset")
        val axisCount = table.readUInt16BE(8, "OpenType fvar axisCount")
        val axisSize = table.readUInt16BE(10, "OpenType fvar axisSize")
        require(axisCount == 0 || axesArrayOffset >= FVAR_HEADER_SIZE) {
            "OpenType fvar axesArrayOffset must point after the $FVAR_HEADER_SIZE-byte header when axes are present."
        }
        require(axisSize >= FVAR_AXIS_RECORD_SIZE) {
            "OpenType fvar axisSize must be at least $FVAR_AXIS_RECORD_SIZE bytes, was $axisSize."
        }

        val axesStart = axesArrayOffset
        val axesEnd = axesStart.toLong() + axisCount.toLong() * axisSize.toLong()
        require(axesEnd <= table.size.toLong()) {
            "OpenType fvar axis record array range [$axesStart, $axesEnd) exceeds table length ${table.size}."
        }

        val axes = List(axisCount) { axisIndex ->
            val axisOffset = axesStart + axisIndex * axisSize
            OpenTypeVariationAxis(
                tag = OpenTypeVariationAxisTag(
                    text = table.readTag(axisOffset, "OpenType fvar axis $axisIndex tag"),
                    rawValue = table.readUInt32BE(axisOffset, "OpenType fvar axis $axisIndex tag").toInt(),
                ),
                minimum = OpenTypeFixed16Dot16(
                    table.readInt32BE(axisOffset + 4, "OpenType fvar axis $axisIndex minValue"),
                ),
                defaultValue = OpenTypeFixed16Dot16(
                    table.readInt32BE(axisOffset + 8, "OpenType fvar axis $axisIndex defaultValue"),
                ),
                maximum = OpenTypeFixed16Dot16(
                    table.readInt32BE(axisOffset + 12, "OpenType fvar axis $axisIndex maxValue"),
                ),
                flags = table.readUInt16BE(axisOffset + 16, "OpenType fvar axis $axisIndex flags"),
                nameId = table.readUInt16BE(axisOffset + 18, "OpenType fvar axis $axisIndex nameId"),
            )
        }

        return VariationTables(axes = axes)
    }
}

/**
 * Parser for OpenType `avar` version 1.0 axis variation maps.
 */
object OpenTypeAvarTableParser {
    /**
     * Parses a bounded raw `avar` table into axis segment maps.
     *
     * @param table Raw `avar` table payload bytes.
     * @param axisCount Expected axis count from `fvar`.
     * @return Parsed segment maps in `fvar` axis order.
     * @throws IllegalArgumentException when version, axis count, or segment ranges are malformed.
     */
    fun parse(table: ByteArray, axisCount: Int): List<OpenTypeAvarAxisSegmentMap> {
        require(axisCount >= 0) { "OpenType avar axisCount must be non-negative." }
        require(table.size >= AVAR_HEADER_SIZE) {
            "OpenType avar table must contain at least $AVAR_HEADER_SIZE bytes, was ${table.size}."
        }

        val majorVersion = table.readUInt16BE(0, "OpenType avar majorVersion")
        val minorVersion = table.readUInt16BE(2, "OpenType avar minorVersion")
        require(majorVersion == 1 && minorVersion == 0) {
            "OpenType avar table version must be 1.0, was $majorVersion.$minorVersion."
        }

        val declaredAxisCount = table.readUInt16BE(6, "OpenType avar axisCount")
        require(declaredAxisCount == axisCount) {
            "OpenType avar axisCount $declaredAxisCount must match fvar axisCount $axisCount."
        }

        val maps = ArrayList<OpenTypeAvarAxisSegmentMap>(declaredAxisCount)
        var offset = AVAR_HEADER_SIZE
        repeat(declaredAxisCount) { axisIndex ->
            table.requireRange(offset, UINT16_BYTE_LENGTH, "OpenType avar axis $axisIndex positionMapCount")
            val segmentCount = table.readUInt16BE(offset, "OpenType avar axis $axisIndex positionMapCount")
            offset += UINT16_BYTE_LENGTH
            val byteCount = segmentCount.toLong() * AVAR_SEGMENT_RECORD_SIZE.toLong()
            require(byteCount <= Int.MAX_VALUE) {
                "OpenType avar axis $axisIndex segment byte count overflows Int."
            }
            table.requireRange(offset, byteCount.toInt(), "OpenType avar axis $axisIndex segment records")

            val segments = List(segmentCount) { segmentIndex ->
                val segmentOffset = offset + segmentIndex * AVAR_SEGMENT_RECORD_SIZE
                OpenTypeAvarSegment(
                    fromCoordinate = table.readF2Dot14(segmentOffset, "OpenType avar axis $axisIndex segment $segmentIndex fromCoordinate"),
                    toCoordinate = table.readF2Dot14(segmentOffset + UINT16_BYTE_LENGTH, "OpenType avar axis $axisIndex segment $segmentIndex toCoordinate"),
                )
            }
            maps += OpenTypeAvarAxisSegmentMap(segments = segments)
            offset += byteCount.toInt()
        }

        require(offset == table.size) {
            "OpenType avar table has ${table.size - offset} trailing bytes after axis segment maps."
        }
        return maps
    }

    private const val AVAR_HEADER_SIZE = 8
    private const val AVAR_SEGMENT_RECORD_SIZE = 4
}

/**
 * Parsed OpenType Layout table container for shaping-related tables.
 *
 * @property tables Opaque layout table payloads keyed by table tag and stored
 * as immutable byte values.
 * @property kern Parsed legacy `kern` table data when a bounded version `0`
 * table with horizontal format `0` subtables is present.
 * @property gsub Parsed bounded GSUB simple substitution lookups when
 * supported substitution subtables are present.
 * @property gdef Parsed bounded GDEF metadata used by GSUB/GPOS shaping.
 * @property gposSingles Parsed GPOS single-position `kern` feature adjustments
 * when supported single-position subtables are present.
 * @property gposPairs Parsed GPOS pair-position `kern` feature adjustments
 * when supported pair-position subtables are present.
 * @property gpos Parsed bounded GPOS mark/cursive lookups when supported
 * positioning subtables are present.
 */
data class OpenTypeLayoutTables(
    val tables: Map<SFNTTableTag, List<Int>> = emptyMap(),
    val kern: OpenTypeKernTable? = null,
    val gsub: OpenTypeGsubTable? = null,
    val gdef: OpenTypeGdefTable? = null,
    val gposSingles: OpenTypeGposSingleTable? = null,
    val gposPairs: OpenTypeGposPairTable? = null,
    val gpos: OpenTypeGposTable? = null,
)

/**
 * SVG document payload advertised by an OpenType `SVG ` table.
 *
 * This model exposes metadata and bytes only. Kanvas does not render or
 * sanitize the SVG at this layer; downstream glyph/color modules decide how to
 * interpret the payload.
 *
 * @property startGlyphId First glyph ID covered by this SVG document.
 * @property endGlyphId Last glyph ID covered by this SVG document.
 * @property bytes Immutable unsigned UTF-8 SVG document bytes.
 */
data class OpenTypeSvgDocument(
    val startGlyphId: Int,
    val endGlyphId: Int,
    val bytes: List<Int>,
) {
    /**
     * Decodes [bytes] as UTF-8 for callers that need the SVG XML text.
     */
    val text: String
        get() = String(ByteArray(bytes.size) { index -> bytes[index].toByte() }, Charsets.UTF_8)
}

/**
 * One document record from an OpenType `SVG ` table DocumentList.
 *
 * Offsets are table-relative byte offsets after validation and are exposed so
 * callers can correlate parsed metadata with raw table bytes when needed.
 *
 * @property startGlyphId First glyph ID covered by the document.
 * @property endGlyphId Last glyph ID covered by the document.
 * @property offset Table-relative byte offset of the SVG document payload.
 * @property length Byte length of the SVG document payload.
 */
data class OpenTypeSvgDocumentRecord(
    val startGlyphId: Int,
    val endGlyphId: Int,
    val offset: Int,
    val length: Int,
)

/**
 * Parsed OpenType `SVG ` table metadata and payloads.
 *
 * @property version Raw table version. Only version `0` is parsed.
 * @property documentListOffset Table-relative offset of the DocumentList.
 * @property records DocumentList records in table order.
 * @property documents Copied SVG payloads corresponding to [records].
 */
data class OpenTypeSvgTable(
    val version: Int,
    val documentListOffset: Int,
    val records: List<OpenTypeSvgDocumentRecord> = emptyList(),
    val documents: List<OpenTypeSvgDocument> = emptyList(),
) {
    /**
     * Returns the SVG document covering [glyphId], or `null` when no record covers it.
     */
    fun documentForGlyph(glyphId: Int): OpenTypeSvgDocument? =
        documents.firstOrNull { glyphId in it.startGlyphId..it.endGlyphId }
}

/**
 * Source table that supplied an embedded bitmap glyph.
 */
enum class OpenTypeBitmapGlyphSource {
    /**
     * Glyph image was resolved through paired `CBDT` and `CBLC` tables.
     */
    CBDT_CBLC,

    /**
     * Glyph image was resolved through an `sbix` table.
     */
    SBIX,
}

/**
 * Embedded bitmap glyph metadata and copied image payload.
 *
 * The parser currently exposes PNG bitmap payloads only. It does not decode or
 * render the PNG data.
 *
 * @property glyphId Glyph ID represented by this bitmap.
 * @property source Source OpenType bitmap table family.
 * @property ppemX Horizontal pixels-per-em strike size.
 * @property ppemY Vertical pixels-per-em strike size.
 * @property bitDepth Advertised bitmap bit depth.
 * @property originOffsetX Signed horizontal origin offset for `sbix`, or `0`
 * for `CBDT`/`CBLC` glyphs where this parser does not expose metrics offsets.
 * @property originOffsetY Signed vertical origin offset for `sbix`, or `0`
 * for `CBDT`/`CBLC` glyphs where this parser does not expose metrics offsets.
 * @property imageFormat Four-byte image format tag. Parsed payloads use `png `.
 * @property bytes Immutable unsigned PNG payload bytes.
 */
data class OpenTypeBitmapGlyph(
    val glyphId: Int,
    val source: OpenTypeBitmapGlyphSource,
    val ppemX: Int,
    val ppemY: Int,
    val bitDepth: Int,
    val originOffsetX: Int,
    val originOffsetY: Int,
    val imageFormat: String,
    val bytes: List<Int>,
)

/**
 * Parsed embedded bitmap font payloads keyed by glyph ID.
 *
 * @property glyphs Bitmap glyphs selected from supported PNG `CBDT`/`CBLC` and
 * `sbix` strikes. When multiple bitmap sources contain the same glyph ID, the
 * first parsed source wins.
 */
data class OpenTypeBitmapFont(
    val glyphs: Map<Int, OpenTypeBitmapGlyph> = emptyMap(),
) {
    /**
     * Returns bitmap metadata and payload for [glyphId], or `null` when absent.
     */
    fun glyph(glyphId: Int): OpenTypeBitmapGlyph? = glyphs[glyphId]
}

/**
 * Parsed color font table container for COLR, CPAL, CBDT, CBLC, sbix, and SVG data.
 *
 * @property tables Opaque color table payloads keyed by table tag and stored
 * as immutable byte values.
 * @property svg Parsed OpenType `SVG ` table metadata and payloads when present.
 * @property bitmap Parsed embedded PNG bitmap glyphs from `CBDT`/`CBLC` and
 * `sbix` tables when present.
 */
data class ColorFontTables(
    val tables: Map<SFNTTableTag, List<Int>> = emptyMap(),
    val svg: OpenTypeSvgTable? = null,
    val bitmap: OpenTypeBitmapFont? = null,
)

/**
 * Non-fatal diagnostic produced while parsing OpenType or SFNT data.
 *
 * @property sourceId Source that produced the diagnostic.
 * @property message Human-readable parse diagnostic.
 * @property table Optional table tag associated with the diagnostic.
 * @property causeCode Optional stable machine-readable parse cause code.
 * @property causeMessage Optional dumpable parse cause detail without retaining
 * a platform exception object.
 */
data class OpenTypeParseDiagnostic(
    val sourceId: FontSourceID,
    val message: String,
    val table: SFNTTableTag? = null,
    val causeCode: String? = null,
    val causeMessage: String? = null,
)
