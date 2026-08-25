package org.graphiks.kalligraphie.text.shaping

import org.graphiks.kalligraphie.font.FontSourceKind
import org.graphiks.kalligraphie.font.fontSourceIdentityPreimage
import org.graphiks.kalligraphie.font.serializedName
import org.graphiks.kalligraphie.font.sfnt.BoundedFontBytes
import org.graphiks.kalligraphie.font.sfnt.DefaultSFNTParser
import org.graphiks.kalligraphie.font.sfnt.SFNTParseRequest
import org.graphiks.kalligraphie.font.sfnt.SFNTTableTag
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.Comparator

data class SystemFontScanEvidenceBundle(
    val systemFontScanJson: String,
    val fontCatalogLinkJson: String,
    val fallbackTraceJson: String,
)

private data class SystemFontScanConfig(
    val root: Path,
    val rootLabel: String,
    val maxBytesPerFile: Long,
    val mtimePolicy: String,
    val contentHashPolicy: String,
    val forcedUnreadablePaths: Set<String> = emptySet(),
)

private data class SystemFontScanDiagnostic(
    val code: String,
    val detail: String,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        appendJsonField("diagnosticCode", code, comma = true)
        appendJsonField("detail", detail, comma = false)
        append("}")
    }
}

private data class SystemFontScanEntry(
    val entryId: String,
    val relativePath: String,
    val byteLength: Long?,
    val hostDependent: Boolean,
    val faceCount: Int?,
    val tableTags: List<String>,
    val diagnostics: List<SystemFontScanDiagnostic>,
    val duplicateOf: String? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        appendJsonField("entryId", entryId, comma = true)
        appendJsonField("relativePath", relativePath, comma = true)
        appendJsonField("byteLength", byteLength, comma = true)
        appendJsonField("hostDependent", hostDependent, comma = true)
        appendJsonField("faceCount", faceCount, comma = true)
        appendStringArrayField("tableTags", tableTags, comma = true)
        append("diagnostics".quoted()).append(":")
        append(diagnostics.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
        if (duplicateOf != null) {
            append(",")
            appendJsonField("duplicateOf", duplicateOf, comma = false)
        }
        append("}")
    }
}

private data class SystemFontScanReport(
    val config: SystemFontScanConfig,
    val entries: List<SystemFontScanEntry>,
) {
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"system-font-scan\",\n")
        append("  \"ownerTickets\": [\"KFONT-M7-005\"],\n")
        append("  \"config\": {\n")
        append("    \"rootLabel\": ").append(config.rootLabel.quoted()).append(",\n")
        append("    \"hostDependent\": true,\n")
        append("    \"maxBytesPerFile\": ").append(config.maxBytesPerFile).append(",\n")
        append("    \"mtimePolicy\": ").append(config.mtimePolicy.quoted()).append(",\n")
        append("    \"contentHashPolicy\": ").append(config.contentHashPolicy.quoted()).append(",\n")
        append("    \"configSha256\": ").append(config.configSha256().quoted()).append("\n")
        append("  },\n")
        append("  \"entries\": [\n")
        append(entries.joinToString(",\n") { entry -> entry.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"nonClaims\": [\"no-normative-system-font-claim\", \"no-platform-font-fallback-claim\", \"no-native-font-api-claim\"]\n")
        append("}")
    }
}

fun defaultSystemFontScanEvidenceBundle(): SystemFontScanEvidenceBundle {
    val fixtureRoot = Files.createTempDirectory("kanvas-system-font-scan")
    return try {
        val fixtureConfig = writeSystemFontScanFixtures(fixtureRoot)
        val report = buildSystemFontScanReport(fixtureConfig)
        SystemFontScanEvidenceBundle(
            systemFontScanJson = report.toCanonicalJson(),
            fontCatalogLinkJson = buildFontCatalogLinkJson(report),
            fallbackTraceJson = buildFallbackTraceJson(report),
        )
    } finally {
        deleteRecursively(fixtureRoot)
    }
}

private fun writeSystemFontScanFixtures(root: Path): SystemFontScanConfig {
    val valid = minimalSfntBytes(tableTags = listOf("cmap", "head", "name"))
    val malformed = minimalSfntBytes(tableTags = listOf("cmap", "name"))
    Files.write(root.resolve("valid.ttf"), valid)
    Files.write(root.resolve("duplicate.ttf"), valid)
    Files.write(root.resolve("malformed.otf"), malformed)
    Files.write(root.resolve("unsupported-wrapper.pfb"), "%!PS-AdobeFont-1.0".toByteArray(Charsets.US_ASCII))
    Files.write(root.resolve("oversized.ttf"), ByteArray(73) { 0x41.toByte() })
    val unreadable = root.resolve("unreadable.ttf")
    Files.write(unreadable, valid)

    val forcedUnreadable = mutableSetOf<String>()
    try {
        Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("---------"))
        if (Files.isReadable(unreadable)) {
            forcedUnreadable += "unreadable.ttf"
        }
    } catch (_: UnsupportedOperationException) {
        forcedUnreadable += "unreadable.ttf"
    } catch (_: IOException) {
        forcedUnreadable += "unreadable.ttf"
    } catch (_: SecurityException) {
        forcedUnreadable += "unreadable.ttf"
    }

    return SystemFontScanConfig(
        root = root,
        rootLabel = "system-scan-fixture-dir",
        maxBytesPerFile = 72,
        mtimePolicy = "ignored-for-determinism",
        contentHashPolicy = "captured-when-within-max-bytes",
        forcedUnreadablePaths = forcedUnreadable,
    )
}

private fun buildSystemFontScanReport(config: SystemFontScanConfig): SystemFontScanReport {
    val parser = DefaultSFNTParser()
    val requiredTables = setOf(SFNTTableTag("cmap"), SFNTTableTag("head"), SFNTTableTag("name"))
    val seenHashes = linkedMapOf<String, String>()
    val entries = Files.walk(config.root).use { stream ->
        stream
            .filter { path -> Files.isRegularFile(path) }
            .map { path -> path.toAbsolutePath().normalize() }
            .sorted(Comparator.comparing { path -> config.root.relativize(path).toString() })
            .toList()
    }.map { absolutePath ->
        val relativePath = config.root.relativize(absolutePath).toString().replace('\\', '/')
        val diagnostics = mutableListOf(
            SystemFontScanDiagnostic(
                code = "font.source.host-dependent",
                detail = "Host-scanned entries remain non-normative until bytes are captured as fixtures.",
            ),
        )
        val extension = relativePath.substringAfterLast('.', "")
        if (relativePath in config.forcedUnreadablePaths || !Files.isReadable(absolutePath)) {
            diagnostics += SystemFontScanDiagnostic(
                code = "font.source.unreadable",
                detail = "Fixture marks the source as unreadable for deterministic scan evidence.",
            )
            return@map SystemFontScanEntry(
                entryId = relativePath,
                relativePath = relativePath,
                byteLength = null,
                hostDependent = true,
                faceCount = null,
                tableTags = emptyList(),
                diagnostics = diagnostics,
            )
        }
        if (extension !in SUPPORTED_FONT_EXTENSIONS) {
            diagnostics += SystemFontScanDiagnostic(
                code = "font.outline-format.unsupported-wrapper",
                detail = "Unsupported wrapper or extension outside the pure Kotlin system scan contract.",
            )
            return@map SystemFontScanEntry(
                entryId = relativePath,
                relativePath = relativePath,
                byteLength = Files.size(absolutePath),
                hostDependent = true,
                faceCount = null,
                tableTags = emptyList(),
                diagnostics = diagnostics,
            )
        }

        val byteLength = Files.size(absolutePath)
        if (byteLength > config.maxBytesPerFile) {
            diagnostics += SystemFontScanDiagnostic(
                code = "font.source.bytes-unavailable",
                detail = "Source exceeds the bounded byte budget for deterministic host-scan evidence.",
            )
            return@map SystemFontScanEntry(
                entryId = relativePath,
                relativePath = relativePath,
                byteLength = byteLength,
                hostDependent = true,
                faceCount = null,
                tableTags = emptyList(),
                diagnostics = diagnostics,
            )
        }

        val bytes = Files.readAllBytes(absolutePath)
        val sourceId = fontSourceIdentityPreimage(
            kind = FontSourceKind.SYSTEM_SCANNED,
            declaredName = relativePath,
            originPath = relativePath,
            contentBytes = bytes,
            faceCount = 1,
            tableTags = emptyList(),
            parserGeneration = 1,
            diagnostics = emptyList(),
        ).sourceId()
        val parseResult = parser.parse(
            SFNTParseRequest(
                sourceId = sourceId,
                sourceKind = FontSourceKind.SYSTEM_SCANNED,
                displayName = relativePath,
                bytes = BoundedFontBytes(bytes),
                parserGeneration = 1,
                requiredTables = requiredTables,
            ),
        )
        parseResult.directoryFacts?.directoryDiagnostics.orEmpty().forEach { diagnostic ->
            val code = when (diagnostic.code) {
                "font.sfnt.required-table-missing" -> "font.required-table-missing"
                else -> diagnostic.code
            }
            diagnostics += SystemFontScanDiagnostic(
                code = code,
                detail = diagnostic.message,
            )
        }
        parseResult.diagnostics.forEach { diagnostic ->
            diagnostics += SystemFontScanDiagnostic(
                code = when (diagnostic.code) {
                    "font.outline-format-unsupported" -> "font.outline-format.unsupported-wrapper"
                    else -> diagnostic.code
                },
                detail = diagnostic.message,
            )
        }
        val duplicateOf = seenHashes.putIfAbsent(bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }, relativePath)
        if (duplicateOf != null) {
            diagnostics += SystemFontScanDiagnostic(
                code = "font.catalog.duplicate-face",
                detail = "Duplicate host-scanned face bytes keep the first deterministic entry.",
            )
        }
        SystemFontScanEntry(
            entryId = relativePath,
            relativePath = relativePath,
            byteLength = byteLength,
            hostDependent = true,
            faceCount = parseResult.faceCount,
            tableTags = parseResult.tableSlices.map { it.tag }.distinct().sorted(),
            diagnostics = diagnostics.sortedBy { it.code },
            duplicateOf = duplicateOf,
        )
    }
    return SystemFontScanReport(config = config, entries = entries)
}

private fun buildFontCatalogLinkJson(report: SystemFontScanReport): String = buildString {
    append("{\n")
    append("  \"schemaVersion\": 1,\n")
    append("  \"dumpId\": \"font-catalog-system-scan-link\",\n")
    append("  \"ownerTickets\": [\"KFONT-M7-005\"],\n")
    append("  \"catalogId\": \"font-catalog\",\n")
    append("  \"linkedSystemScans\": [\n")
    append(
        report.entries.joinToString(",\n") { entry ->
            buildString {
                append("{")
                appendJsonField("entryId", entry.entryId, comma = true)
                appendJsonField("sourceKind", FontSourceKind.SYSTEM_SCANNED.serializedName, comma = true)
                appendJsonField("hostDependent", true, comma = true)
                appendJsonField("relativePath", entry.relativePath, comma = true)
                appendStringArrayField("diagnosticCodes", entry.diagnostics.map { it.code }, comma = false)
                append("}")
            }.prependIndent("    ")
        },
    )
    append("\n  ],\n")
    append("  \"nonClaims\": [\"no-bundled-catalog-promotion\", \"no-normative-system-font-catalog-claim\"]\n")
    append("}")
}

private fun buildFallbackTraceJson(report: SystemFontScanReport): String {
    val selectedEntry = report.entries.first { it.entryId == "valid.ttf" }
    return buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": \"fallback-decision-trace\",\n")
        append("  \"ownerTickets\": [\"KFONT-M7-005\"],\n")
        append("  \"cases\": [\n")
        append("    {\n")
        append("      \"fixtureId\": \"host-dependent-system-fallback\",\n")
        append("      \"request\": {\"text\": \"A\", \"locale\": null, \"preferredFamilies\": [\"System Fixture Sans\"]},\n")
        append("      \"decisions\": [\n")
        append("        {\"textRange\":\"0..0\",\"clusterRange\":\"0..0\",\"codePoint\":\"U+0041\",\"selectedFamily\":\"System Fixture Sans\",\"selectedSourceKind\":\"SystemScannedFontSource\",\"selectedScanEntryId\":")
        append(selectedEntry.entryId.quoted())
        append(",\"selectedHostDependent\":true,\"diagnosticCode\":\"font.source.host-dependent\"}\n")
        append("      ]\n")
        append("    }\n")
        append("  ],\n")
        append("  \"nonClaims\": [\"no-normative-system-font-fallback-claim\", \"no-platform-font-engine-claim\"]\n")
        append("}")
    }
}

private fun minimalSfntBytes(tableTags: List<String>): ByteArray {
    val sortedTags = tableTags.distinct().sorted()
    val payloadLength = 4
    val directoryLength = 12 + sortedTags.size * 16
    val totalLength = directoryLength + sortedTags.size * payloadLength
    val bytes = ByteArray(totalLength)
    writeUInt32(bytes, 0, 0x00010000)
    writeUInt16(bytes, 4, sortedTags.size)
    writeUInt16(bytes, 6, 0)
    writeUInt16(bytes, 8, 0)
    writeUInt16(bytes, 10, 0)
    var payloadOffset = directoryLength
    sortedTags.forEachIndexed { index, tag ->
        val recordOffset = 12 + index * 16
        writeTag(bytes, recordOffset, tag)
        writeUInt32(bytes, recordOffset + 4, 0)
        writeUInt32(bytes, recordOffset + 8, payloadOffset)
        writeUInt32(bytes, recordOffset + 12, payloadLength)
        for (payloadIndex in 0 until payloadLength) {
            bytes[payloadOffset + payloadIndex] = (index + payloadIndex + 1).toByte()
        }
        payloadOffset += payloadLength
    }
    return bytes
}

private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 8) and 0xff).toByte()
    bytes[offset + 1] = (value and 0xff).toByte()
}

private fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = ((value ushr 24) and 0xff).toByte()
    bytes[offset + 1] = ((value ushr 16) and 0xff).toByte()
    bytes[offset + 2] = ((value ushr 8) and 0xff).toByte()
    bytes[offset + 3] = (value and 0xff).toByte()
}

private fun writeTag(bytes: ByteArray, offset: Int, tag: String) {
    require(tag.length == 4) { "SFNT tags must be four characters long." }
    val tagBytes = tag.toByteArray(Charsets.ISO_8859_1)
    for (index in tagBytes.indices) {
        bytes[offset + index] = tagBytes[index]
    }
}

private fun StringBuilder.appendStringArrayField(name: String, values: List<String>, comma: Boolean) {
    append(name.quoted())
    append(":")
    append(values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.quoted() })
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendJsonField(name: String, value: String?, comma: Boolean) {
    append(name.quoted()).append(":").append(value?.quoted() ?: "null")
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendJsonField(name: String, value: Long?, comma: Boolean) {
    append(name.quoted()).append(":").append(value?.toString() ?: "null")
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendJsonField(name: String, value: Int?, comma: Boolean) {
    append(name.quoted()).append(":").append(value?.toString() ?: "null")
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendJsonField(name: String, value: Boolean, comma: Boolean) {
    append(name.quoted()).append(":").append(value)
    if (comma) {
        append(",")
    }
}

private fun String.quoted(): String = "\"$this\""

private fun deleteRecursively(root: Path) {
    if (!Files.exists(root)) {
        return
    }
    Files.walk(root).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { path ->
            try {
                if (Files.isRegularFile(path)) {
                    try {
                        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
                    } catch (_: Exception) {
                        // Best effort only; deletion may still succeed.
                    }
                }
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                // Best effort cleanup for temporary evidence fixtures.
            }
        }
    }
}

private val SUPPORTED_FONT_EXTENSIONS: Set<String> = setOf("ttf", "otf", "ttc", "otc")

private fun SystemFontScanConfig.configSha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(
            listOf(rootLabel, maxBytesPerFile.toString(), mtimePolicy, contentHashPolicy)
                .joinToString(separator = "|")
                .toByteArray(Charsets.UTF_8),
        )
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
