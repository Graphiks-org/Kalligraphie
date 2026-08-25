package org.graphiks.kalligraphie.font

import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.uuid.Uuid

/**
 * Stable identifier for a physical or virtual font source known to the pure Kotlin font stack.
 *
 * The identifier uses the Kotlin 2.4 standard `Uuid` type so it has value
 * semantics, deterministic text formatting, and no dependency on JVM-only
 * `java.util.UUID`.
 *
 * @property value Opaque UUID that remains stable across parse and cache layers.
 */
@JvmInline
value class FontSourceID(
    val value: Uuid,
)

/**
 * Stable identifier for one parsed typeface within a font source.
 *
 * The identifier uses the Kotlin 2.4 standard `Uuid` type. The UUID must be
 * derived from all facts that influence the resolved face, including source
 * identity, collection index, variation coordinates, palette, and parser
 * generation.
 *
 * @property value Opaque UUID for a single face, including collection index or
 * variation identity when needed.
 */
@JvmInline
value class TypefaceID(
    val value: Uuid,
)

/**
 * Describes where a font source originated before it entered the pure Kotlin font stack.
 */
enum class FontSourceKind {
    /** Font bytes supplied directly by application memory. */
    MEMORY,

    /** Font discovered through the host system font registry. */
    SYSTEM,

    /** Font loaded from a file path or application asset path. */
    FILE,

    /** Font loaded through an application or remote resource URI. */
    RESOURCE,

    /** Deterministic font bytes committed with fixture provenance. */
    BUNDLED_FIXTURE,

    /** Deterministic generated font bytes used as parser, scaler, shaping, or color fixtures. */
    GENERATED_FIXTURE,

    /** Font bytes supplied directly by application memory with caller provenance. */
    USER_DATA,

    /** Caller stream copied into bounded immutable font data before parsing. */
    USER_STREAM,

    /** Caller file copied or hashed under an explicit content policy. */
    USER_FILE,

    /** Pure Kotlin scan result from host directories, never normative until bytes are captured. */
    SYSTEM_SCANNED,
}

/**
 * Stable provenance facts used as part of [FontSourceIdentityPreimage].
 *
 * This model deliberately carries display-level source facts, not filesystem
 * paths, native handles, or platform font API objects.
 */
data class FontSourceProvenance(
    val kind: FontSourceKind,
    val declaredName: String,
    val licenseId: String? = null,
    val originPath: String? = null,
    val hostDependent: Boolean = kind.isHostDependentByDefault(),
) {
    init {
        require(declaredName.isNotBlank()) { "declaredName must not be blank." }
        require(originPath == null || originPath.isNotBlank()) {
            "originPath must not be blank when present."
        }
    }

    internal fun toCanonicalJson(indent: String): String = buildString {
        append(indent).append("{\n")
        appendFontJsonField("kind", kind.serializedName, indent = "$indent  ", comma = true)
        appendFontJsonField("declaredName", declaredName, indent = "$indent  ", comma = true)
        append(indent).append("  ").append("\"licenseId\": ")
        append(licenseId.toFontJsonNullableString())
        append(",\n")
        append(indent).append("  ").append("\"originPath\": ")
        append(originPath.toFontJsonNullableString())
        append(",\n")
        appendFontJsonField("hostDependent", hostDependent, indent = "$indent  ", comma = false)
        append(indent).append("}")
    }
}

/**
 * Stable source-level diagnostic included before [FontSourceID] derivation.
 *
 * [FontSourceDiagnostic] is still used after a source ID exists. This value
 * object avoids circular identity derivation by storing only stable source
 * diagnostic facts in the preimage.
 */
data class FontSourceIdentityDiagnostic(
    val code: String,
    val detail: String? = null,
) {
    init {
        require(code.isStableDiagnosticCode()) { "code must be a stable one-line diagnostic code." }
    }

    internal fun toCanonicalJson(indent: String): String = buildString {
        append(indent).append("{\n")
        appendFontJsonField("code", code, indent = "$indent  ", comma = true)
        append(indent).append("  ").append("\"detail\": ")
        append(detail.toFontJsonNullableString())
        append("\n")
        append(indent).append("}")
    }
}

/**
 * Canonical preimage for deriving and auditing [FontSourceID].
 *
 * It includes only deterministic provenance and parser-supplied source facts.
 * The font core does not parse tables here; callers provide face counts,
 * table tags, diagnostics, and parser generation after their owning parser or
 * scanner has produced those facts.
 */
class FontSourceIdentityPreimage(
    val provenance: FontSourceProvenance,
    val contentSha256: String?,
    val byteLength: Long?,
    val faceCount: Int,
    tableTags: List<String>,
    val parserGeneration: Int,
    diagnostics: List<FontSourceIdentityDiagnostic> = emptyList(),
) {
    val tableTags: List<String> = tableTags.normalizedTableTags()
    val diagnostics: List<FontSourceIdentityDiagnostic> =
        diagnostics.sortedWith(compareBy<FontSourceIdentityDiagnostic> { it.code }.thenBy { it.detail.orEmpty() })
    val kind: FontSourceKind
        get() = provenance.kind
    val hostDependent: Boolean
        get() = provenance.hostDependent

    init {
        require((contentSha256 == null) == (byteLength == null)) {
            "contentSha256 and byteLength must both be present or both be absent."
        }
        require(contentSha256 == null || contentSha256.isLowercaseSha256Hex()) {
            "contentSha256 must be a lowercase hexadecimal SHA-256 digest."
        }
        require(byteLength == null || byteLength >= 0) { "byteLength must be non-negative." }
        require(faceCount >= 0) { "faceCount must be non-negative." }
        require(parserGeneration >= 0) { "parserGeneration must be non-negative." }
    }

    /**
     * Serializes every [FontSourceID]-affecting fact as canonical JSON.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendFontJsonField("schema", PreimageSchema, indent = "  ", comma = true)
        append("  \"provenance\": ")
        append(provenance.toCanonicalJson(indent = "  ").trimStart())
        append(",\n")
        append("  \"contentSha256\": ")
        append(contentSha256.toFontJsonNullableString())
        append(",\n")
        append("  \"byteLength\": ")
        append(byteLength?.toString() ?: "null")
        append(",\n")
        appendFontJsonField("faceCount", faceCount, indent = "  ", comma = true)
        append("  \"tableTags\": ")
        append(tableTags.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.evidenceQuoted() })
        append(",\n")
        appendFontJsonField("parserGeneration", parserGeneration, indent = "  ", comma = true)
        append("  \"diagnostics\": ")
        if (diagnostics.isEmpty()) {
            append("[]\n")
        } else {
            append("[\n")
            append(diagnostics.joinToString(",\n") { it.toCanonicalJson(indent = "    ") })
            append("\n  ]\n")
        }
        append("}\n")
    }

    /**
     * Derives a deterministic UUID-backed source ID from [toCanonicalJson].
     */
    fun deriveFontSourceID(): FontSourceID =
        FontSourceID(stableUuidFromSha256("kanvas-font-source-id-v1\n${toCanonicalJson()}"))

    fun sourceId(): FontSourceID = deriveFontSourceID()

    override fun equals(other: Any?): Boolean =
        this === other || other is FontSourceIdentityPreimage &&
            provenance == other.provenance &&
            contentSha256 == other.contentSha256 &&
            byteLength == other.byteLength &&
            faceCount == other.faceCount &&
            tableTags == other.tableTags &&
            parserGeneration == other.parserGeneration &&
            diagnostics == other.diagnostics

    override fun hashCode(): Int {
        var result = provenance.hashCode()
        result = 31 * result + (contentSha256?.hashCode() ?: 0)
        result = 31 * result + (byteLength?.hashCode() ?: 0)
        result = 31 * result + faceCount
        result = 31 * result + tableTags.hashCode()
        result = 31 * result + parserGeneration
        result = 31 * result + diagnostics.hashCode()
        return result
    }

    companion object {
        const val PreimageSchema: String = "org.graphiks.kanvas.font.FontSourceIdentityPreimage.v1"

        /**
         * Creates an identity preimage for sources whose bytes were captured.
         */
        fun fromCapturedBytes(
            kind: FontSourceKind,
            declaredName: String,
            licenseId: String?,
            bytes: ByteArray,
            faceCount: Int,
            tableTags: List<String> = emptyList(),
            parserGeneration: Int,
            diagnostics: List<FontSourceIdentityDiagnostic> = emptyList(),
            hostDependent: Boolean = kind.isHostDependentByDefault(),
            originPath: String? = null,
        ): FontSourceIdentityPreimage =
            FontSourceIdentityPreimage(
                provenance = FontSourceProvenance(
                    kind = kind,
                    declaredName = declaredName,
                    licenseId = licenseId,
                    originPath = originPath,
                    hostDependent = hostDependent,
                ),
                contentSha256 = bytes.sha256Hex(),
                byteLength = bytes.size.toLong(),
                faceCount = faceCount,
                tableTags = tableTags,
                parserGeneration = parserGeneration,
                diagnostics = diagnostics,
            )

        /**
         * Creates an identity preimage for a host-dependent system scan.
         */
        fun systemScanned(
            declaredName: String,
            faceCount: Int,
            tableTags: List<String> = emptyList(),
            parserGeneration: Int,
            diagnostics: List<FontSourceIdentityDiagnostic> = emptyList(),
        ): FontSourceIdentityPreimage =
            FontSourceIdentityPreimage(
                provenance = FontSourceProvenance(
                    kind = FontSourceKind.SYSTEM_SCANNED,
                    declaredName = declaredName,
                    licenseId = null,
                    originPath = null,
                    hostDependent = true,
                ),
                contentSha256 = null,
                byteLength = null,
                faceCount = faceCount,
                tableTags = tableTags,
                parserGeneration = parserGeneration,
                diagnostics = diagnostics,
            )
    }
}

data class FontSourceIdentityReportEntry(
    val label: String,
    val preimage: FontSourceIdentityPreimage,
    val manifestFixtureId: String? = null,
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank." }
        require(manifestFixtureId == null || manifestFixtureId.isStableManifestToken()) {
            "manifestFixtureId must be a stable manifest token when present."
        }
        require(!claimPromotionAllowed) {
            "Font source identity evidence cannot promote rendering support claims."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("label", label, comma = true)
        appendFontCompactJsonField("sourceId", preimage.sourceId().value.toHexDashString(), comma = true)
        append("manifestFixtureId".evidenceQuoted()).append(":")
        append(manifestFixtureId.toFontJsonNullableString())
        append(",")
        append("\"preimage\":")
        append(preimage.toCanonicalJson().trim())
        append(",")
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("}")
    }
}

class FontSourceIdentityReport(
    val fixtureName: String,
    entries: List<FontSourceIdentityReportEntry>,
) {
    val entries: List<FontSourceIdentityReportEntry> = entries.toList()

    init {
        require(fixtureName.isNotBlank()) { "fixtureName must not be blank." }
        require(this.entries.map { entry -> entry.label }.distinct().size == this.entries.size) {
            "font source identity report entries must have unique labels."
        }
        require(this.entries.all { entry -> !entry.claimPromotionAllowed }) {
            "font source identity report cannot contain claim-promoting rows."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("schema", FONT_SOURCE_IDENTITY_REPORT_SCHEMA, comma = true)
        appendFontCompactJsonField("fixtureName", fixtureName, comma = true)
        append("\"entries\":")
        append(entries.joinToString(separator = ",", prefix = "[", postfix = "]") { entry ->
            entry.toCanonicalJson()
        })
        append("}")
    }
}

fun fontSourceIdentityDiagnostic(
    code: String,
    message: String? = null,
): FontSourceIdentityDiagnostic = FontSourceIdentityDiagnostic(
    code = code,
    detail = message,
)

fun fontSourceIdentityPreimage(
    kind: FontSourceKind,
    declaredName: String,
    licenseId: String? = null,
    originPath: String? = null,
    contentBytes: ByteArray?,
    faceCount: Int,
    tableTags: List<String> = emptyList(),
    parserGeneration: Int,
    diagnostics: List<FontSourceIdentityDiagnostic> = emptyList(),
): FontSourceIdentityPreimage =
    if (contentBytes == null) {
        FontSourceIdentityPreimage(
            provenance = FontSourceProvenance(
                kind = kind,
                declaredName = declaredName,
                licenseId = licenseId,
                originPath = originPath,
                hostDependent = kind.isHostDependentByDefault(),
            ),
            contentSha256 = null,
            byteLength = null,
            faceCount = faceCount,
            tableTags = tableTags,
            parserGeneration = parserGeneration,
            diagnostics = diagnostics,
        )
    } else {
        FontSourceIdentityPreimage.fromCapturedBytes(
            kind = kind,
            declaredName = declaredName,
            licenseId = licenseId,
            bytes = contentBytes,
            faceCount = faceCount,
            tableTags = tableTags,
            parserGeneration = parserGeneration,
            diagnostics = diagnostics,
            originPath = originPath,
        )
    }

private fun liberationSansManifestSourcePreimage(): FontSourceIdentityPreimage =
    FontSourceIdentityPreimage(
        provenance = FontSourceProvenance(
            kind = FontSourceKind.BUNDLED_FIXTURE,
            declaredName = "Liberation Sans Regular",
            licenseId = "SIL-OFL-1.1",
            originPath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            hostDependent = false,
        ),
        contentSha256 = "76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8",
        byteLength = 410712,
        faceCount = 1,
        tableTags = listOf("cmap", "glyf", "head", "name"),
        parserGeneration = 1,
    )

fun defaultFontSourceIdentityReport(): FontSourceIdentityReport = FontSourceIdentityReport(
    fixtureName = "font-source.json",
    entries = listOf(
        FontSourceIdentityReportEntry(
            label = "bundled-fixture",
            manifestFixtureId = "single-ttf-liberation-sans",
            preimage = liberationSansManifestSourcePreimage(),
        ),
        FontSourceIdentityReportEntry(
            label = "generated-fixture",
            preimage = fontSourceIdentityPreimage(
                kind = FontSourceKind.GENERATED_FIXTURE,
                declaredName = "Generated Minimal",
                contentBytes = byteArrayOf(4, 5, 6),
                faceCount = 1,
                tableTags = listOf("cmap", "head"),
                parserGeneration = 1,
            ),
        ),
        FontSourceIdentityReportEntry(
            label = "user-data",
            preimage = fontSourceIdentityPreimage(
                kind = FontSourceKind.USER_DATA,
                declaredName = "User Upload",
                contentBytes = byteArrayOf(7, 8, 9),
                faceCount = 1,
                tableTags = listOf("cmap"),
                parserGeneration = 1,
            ),
        ),
        FontSourceIdentityReportEntry(
            label = "system-scanned-host-dependent",
            preimage = fontSourceIdentityPreimage(
                kind = FontSourceKind.SYSTEM_SCANNED,
                declaredName = "Host Scan Sans",
                originPath = "system-fonts/HostScanSans.ttf",
                contentBytes = null,
                faceCount = 0,
                tableTags = emptyList(),
                parserGeneration = 1,
                diagnostics = listOf(
                    fontSourceIdentityDiagnostic(
                        code = "font.source.host-dependent",
                        message = "System scan entry has no captured fixture bytes.",
                    ),
                ),
            ),
        ),
    ),
)

enum class FontFixtureNormativeStatus(
    val serializedName: String,
) {
    NORMATIVE("normative"),
    NON_NORMATIVE("non-normative"),
    PLANNED_GENERATED("planned-generated"),
}

data class FontFixtureManifestDiagnostic(
    val code: String,
    val detail: String? = null,
    val fixtureId: String? = null,
) {
    init {
        require(code.isStableDiagnosticCode()) { "code must be a stable one-line diagnostic code." }
        require(fixtureId == null || fixtureId.isStableManifestToken()) {
            "fixtureId must be a stable manifest token when present."
        }
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("code", code, comma = true)
        append("fixtureId".evidenceQuoted()).append(":")
        append(fixtureId.toFontJsonNullableString())
        append(",")
        append("detail".evidenceQuoted()).append(":")
        append(detail.toFontJsonNullableString())
        append("}")
    }
}

class BundledFontFixtureManifestEntry(
    val fixtureId: String,
    val sourceKind: FontSourceKind,
    val relativePath: String?,
    val generatorId: String?,
    generatorParameters: List<String> = emptyList(),
    val licenseId: String?,
    val licensePath: String?,
    val provenance: String?,
    val contentSha256: String?,
    val byteLength: Long?,
    val faceCount: Int,
    coverageTags: List<String>,
    val normativeStatus: FontFixtureNormativeStatus,
    val remainingGate: String?,
    diagnostics: List<FontFixtureManifestDiagnostic> = emptyList(),
    fontSourceReportLabels: List<String> = emptyList(),
    typefaceReportLabels: List<String> = emptyList(),
    val claimPromotionAllowed: Boolean = false,
) {
    val generatorParameters: List<String> = generatorParameters.normalizedManifestStrings("generatorParameters")
    val coverageTags: List<String> = coverageTags.normalizedManifestStrings("coverageTags")
    val diagnostics: List<FontFixtureManifestDiagnostic> =
        diagnostics.sortedWith(compareBy<FontFixtureManifestDiagnostic> { it.code }.thenBy { it.fixtureId.orEmpty() }.thenBy { it.detail.orEmpty() })
    val fontSourceReportLabels: List<String> = fontSourceReportLabels.normalizedManifestStrings("fontSourceReportLabels")
    val typefaceReportLabels: List<String> = typefaceReportLabels.normalizedManifestStrings("typefaceReportLabels")

    init {
        require(fixtureId.isStableManifestToken()) { "fixtureId must be a stable manifest token." }
        require(relativePath == null || relativePath.isStableManifestString()) {
            "relativePath must be one stable JSON-line string when present."
        }
        require(generatorId == null || generatorId.isStableManifestToken()) {
            "generatorId must be a stable manifest token when present."
        }
        require(licenseId == null || licenseId.isStableManifestToken()) {
            "licenseId must be a stable manifest token when present."
        }
        require(licensePath == null || licensePath.isStableManifestString()) {
            "licensePath must be one stable JSON-line string when present."
        }
        require(provenance == null || provenance.isStableManifestString()) {
            "provenance must be one stable JSON-line string when present."
        }
        require(contentSha256 == null || contentSha256.isLowercaseSha256Hex()) {
            "contentSha256 must be a lowercase hexadecimal SHA-256 digest."
        }
        require(byteLength == null || byteLength >= 0) { "byteLength must be non-negative." }
        require(faceCount >= 0) { "faceCount must be non-negative." }
        require(remainingGate == null || remainingGate.isStableManifestString()) {
            "remainingGate must be one stable JSON-line string when present."
        }
        if (sourceKind == FontSourceKind.GENERATED_FIXTURE) {
            require(generatorId != null) {
                "generated fixture manifest entries require a generatorId."
            }
            require(this.generatorParameters.isNotEmpty()) {
                "generated fixture manifest entries require generatorParameters."
            }
            require(relativePath == null) {
                "generated fixture manifest entries must not use relativePath until generated bytes are captured."
            }
        }
        if (normativeStatus == FontFixtureNormativeStatus.PLANNED_GENERATED) {
            require(sourceKind == FontSourceKind.GENERATED_FIXTURE) {
                "planned generated fixture entries must use GeneratedFixtureFontSource."
            }
            require(contentSha256 == null && byteLength == null) {
                "planned generated fixture entries must not carry captured byte facts."
            }
            require(generatorId != null && this.generatorParameters.isNotEmpty()) {
                "planned generated fixture entries require generator provenance."
            }
        }
        require(!claimPromotionAllowed) {
            "Font fixture manifest entries cannot promote parser, scaler, rendering, fallback, glyph, or GPU claims."
        }
    }

    fun normativeEvidenceDiagnostics(): List<FontFixtureManifestDiagnostic> {
        if (normativeStatus != FontFixtureNormativeStatus.NORMATIVE) return emptyList()
        val result = mutableListOf<FontFixtureManifestDiagnostic>()
        val hasCapturedBytes = contentSha256 != null && byteLength != null && relativePath != null
        if (sourceKind.isHostDependentByDefault() && !hasCapturedBytes) {
            result += FontFixtureManifestDiagnostic(
                code = "font.fixture.host-dependent-normative-refused",
                fixtureId = fixtureId,
                detail = "Host-dependent sources cannot be normative without captured bytes.",
            )
        }
        if (
            licenseId == null ||
            licensePath == null ||
            provenance == null ||
            contentSha256 == null ||
            byteLength == null ||
            relativePath == null
        ) {
            result += FontFixtureManifestDiagnostic(
                code = "font.fixture.provenance-missing",
                fixtureId = fixtureId,
                detail = "Normative fixture entries require license, provenance, path, SHA-256, and byte length.",
            )
        }
        if (coverageTags.isEmpty()) {
            result += FontFixtureManifestDiagnostic(
                code = "font.fixture.coverage-missing",
                fixtureId = fixtureId,
                detail = "Normative fixture entries require intended coverage tags.",
            )
        }
        return result.sortedWith(
            compareBy<FontFixtureManifestDiagnostic> { it.code }
                .thenBy { it.fixtureId.orEmpty() }
                .thenBy { it.detail.orEmpty() },
        )
    }

    internal fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("fixtureId", fixtureId, comma = true)
        appendFontCompactJsonField("sourceKind", sourceKind.serializedName, comma = true)
        appendFontCompactJsonField("normativeStatus", normativeStatus.serializedName, comma = true)
        append("relativePath".evidenceQuoted()).append(":")
        append(relativePath.toFontJsonNullableString())
        append(",")
        append("generatorId".evidenceQuoted()).append(":")
        append(generatorId.toFontJsonNullableString())
        append(",")
        appendStringArrayField("generatorParameters", generatorParameters, comma = true)
        append("licenseId".evidenceQuoted()).append(":")
        append(licenseId.toFontJsonNullableString())
        append(",")
        append("licensePath".evidenceQuoted()).append(":")
        append(licensePath.toFontJsonNullableString())
        append(",")
        append("provenance".evidenceQuoted()).append(":")
        append(provenance.toFontJsonNullableString())
        append(",")
        append("contentSha256".evidenceQuoted()).append(":")
        append(contentSha256.toFontJsonNullableString())
        append(",")
        append("byteLength".evidenceQuoted()).append(":")
        append(byteLength?.toString() ?: "null")
        append(",")
        append("faceCount".evidenceQuoted()).append(":")
        append(faceCount)
        append(",")
        appendStringArrayField("coverageTags", coverageTags, comma = true)
        appendStringArrayField("fontSourceReportLabels", fontSourceReportLabels, comma = true)
        appendStringArrayField("typefaceReportLabels", typefaceReportLabels, comma = true)
        append("diagnostics".evidenceQuoted()).append(":")
        append(diagnostics.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
        append(",")
        append("remainingGate".evidenceQuoted()).append(":")
        append(remainingGate.toFontJsonNullableString())
        append(",")
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("}")
    }
}

class FontFixtureManifest(
    val schemaVersion: Int = 1,
    val manifestName: String = "font-fixtures-manifest.json",
    entries: List<BundledFontFixtureManifestEntry>,
    val dashboardClassification: String = "fixture-gated",
    nonClaims: List<String> = FontFixtureManifestNonClaims,
    val claimPromotionAllowed: Boolean = false,
) {
    val entries: List<BundledFontFixtureManifestEntry> = entries.sortedBy { it.fixtureId }
    val nonClaims: List<String> = nonClaims.normalizedManifestStrings("nonClaims")

    init {
        require(schemaVersion == 1) { "unsupported font fixture manifest schema version." }
        require(manifestName == "font-fixtures-manifest.json") { "font fixture manifest output name is fixed." }
        require(dashboardClassification == "fixture-gated") { "font fixture manifest dashboard row must stay fixture-gated." }
        require(this.entries.map { it.fixtureId }.distinct().size == this.entries.size) {
            "font fixture manifest entries must have unique fixture IDs."
        }
        require(!claimPromotionAllowed) {
            "Font fixture manifests cannot promote parser, scaler, rendering, fallback, glyph, or GPU claims."
        }
    }

    fun normativeEvidenceDiagnostics(): List<FontFixtureManifestDiagnostic> =
        entries.flatMap { it.normativeEvidenceDiagnostics() }.sortedWith(
            compareBy<FontFixtureManifestDiagnostic> { it.code }
                .thenBy { it.fixtureId.orEmpty() }
                .thenBy { it.detail.orEmpty() },
        )

    fun toCanonicalJson(): CanonicalFontIdentityJson = CanonicalFontIdentityJson(
        buildString {
            append("{")
            appendFontCompactJsonField("schema", FONT_FIXTURE_MANIFEST_SCHEMA, comma = true)
            append("schemaVersion".evidenceQuoted()).append(":").append(schemaVersion).append(",")
            appendFontCompactJsonField("manifestName", manifestName, comma = true)
            append("dashboardRow".evidenceQuoted()).append(":{")
            appendFontCompactJsonField("name", "bundled font fixture manifest", comma = true)
            appendFontCompactJsonField("classification", dashboardClassification, comma = true)
            appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
            append("},")
            append("entries".evidenceQuoted()).append(":")
            append(entries.joinToString(separator = ",", prefix = "[", postfix = "]") { it.toCanonicalJson() })
            append(",")
            appendStringArrayField("nonClaims", nonClaims, comma = true)
            append("normativeEvidenceDiagnostics".evidenceQuoted()).append(":")
            append(normativeEvidenceDiagnostics().joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
            append(",")
            appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
            append("}")
        },
    )
}

fun defaultFontFixtureManifest(): FontFixtureManifest = FontFixtureManifest(
    entries = listOf(
        BundledFontFixtureManifestEntry(
            fixtureId = "single-ttf-liberation-sans",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/liberation/LiberationSans-Regular.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/liberation-OFL-1.1.txt",
            provenance = "Liberation Fonts; repo-vendored-existing; font/fixtures/fonts/liberation",
            contentSha256 = "76d04c18ea243f426b7de1f3ad208e927008f961dc5945e5aad352d0dfde8ee8",
            byteLength = 410712,
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "single-ttf", "table:cmap", "table:glyf", "table:head", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Parser and scaler evidence must be attached by later tickets before support promotion.",
            fontSourceReportLabels = listOf("bundled-fixture"),
            typefaceReportLabels = listOf("single-face-ttf"),
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "otf-cff-source-serif",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/scaler/SourceSerif4-Regular.otf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/source-serif-OFL-1.1.txt",
            provenance = "Source Serif 4.005R; vendored external fixture from Adobe Source Serif release",
            contentSha256 = "edf160d0d584deee8a3bb2c3371b2a7624ca63580fbe02c57c1f4c91e84d8787",
            byteLength = 241392,
            faceCount = 1,
            coverageTags = listOf("otf-cff-candidate", "sfnt-source", "table:CFF", "table:cmap", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "CFF parser and scaler tickets must attach separate evidence before support promotion.",
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "variable-ttf-roboto-flex",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/scaler/RobotoFlex-Variable.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/roboto-flex-OFL-1.1.txt",
            provenance = "Roboto Flex; googlefonts/roboto-flex blob 0abe2ee29292f1b39f59103d069feda87cde585e",
            contentSha256 = "94a7ea95ccee28c54885a507e3cc0a534ce41ec61d413935df0e07261a7ffe63",
            byteLength = 1775480,
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "table:cmap", "table:fvar", "table:glyf", "table:gvar", "variable-font-candidate"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Variable parser and scaler tickets must attach separate evidence before support promotion.",
            typefaceReportLabels = listOf("variable-axis-change"),
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "single-ttf-noto-sans-hebrew",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/fallback/NotoSansHebrew-Regular.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/noto-OFL-1.1.txt",
            provenance = "Noto Sans Hebrew v3.001; vendored external fixture from notofonts/hebrew release",
            contentSha256 = "671951828bd5c95db818e5bb12dcea2d0c0dda00311888522be061ee6835125e",
            byteLength = 66156,
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "single-ttf", "table:cmap", "table:glyf", "table:head", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Fallback routing, glyph coverage, and shaping tickets must attach separate evidence before support promotion.",
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "single-ttf-noto-naskh-arabic",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/fallback/NotoNaskhArabic-Regular.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/noto-OFL-1.1.txt",
            provenance = "Noto Naskh Arabic v2.021; vendored external fixture from notofonts/arabic release",
            contentSha256 = "6f0a92031367b2f5a2078fe9d24f3433122b61a0bad57c423aad8f3c39aa2e6e",
            byteLength = 247336,
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "single-ttf", "table:cmap", "table:glyf", "table:head", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Arabic shaping review evidence now covers joining forms, ticket-local GSUB/GPOS runtime traces, marks, mixed-bidi refusal, and a separate reviewed generic gdef-required refusal on gpos-missing-gdef.otf for Arabic base+mark input; lam-alef still has only a bounded non-promotional runtime check, vendored positive cursive, and Arabic-specific refusal fixtures remain separate shaping gates before support promotion.",
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "single-ttf-noto-sans-devanagari",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/fallback/NotoSansDevanagari-Regular.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/noto-OFL-1.1.txt",
            provenance = "Noto Sans Devanagari v2.006; vendored external fixture from notofonts/devanagari release",
            contentSha256 = "da2d2135e978c6f68852cfd8201c3a067df1acf73232fe80c58f89e6302ee6e8",
            byteLength = 282060,
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "single-ttf", "table:cmap", "table:glyf", "table:head", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Devanagari review evidence now covers pinned Script_Extensions Deva script selection on the vendored pre-base matra case, consonant-cluster preservation, reph-like shaping, and mark placement only; syllable-plan evidence, the full required feature set, Devanagari-specific refusal fixtures/codes, and trace dumps remain separate shaping gates before support promotion.",
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "single-ttf-noto-sans-thai",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/fallback/NotoSansThai-Regular.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/noto-OFL-1.1.txt",
            provenance = "Noto Sans Thai v2.002; vendored external fixture from notofonts/thai release",
            contentSha256 = "7f890179ceabb5f34a17702271c83c015c807fc78deaf52de859f6ec8847c1cf",
            byteLength = 72772,
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "single-ttf", "table:cmap", "table:glyf", "table:head", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Thai/CJK boundary review evidence now covers Thai tone-mark positioning and mixed Latin/Thai script boundaries on vendored Noto Sans Thai only; paragraph-owned dictionary diagnostics, Thai refusal fixtures/codes, and ticket-local trace dumps remain separate gates before support promotion.",
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "single-otf-noto-sans-sc",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/fallback/NotoSansSC-Regular.otf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/noto-cjk-OFL-1.1.txt",
            provenance = "Noto Sans SC Sans2.004 subset asset 18_NotoSansSC.zip; vendored external fixture from notofonts/noto-cjk release",
            contentSha256 = "faa6c9df652116dde789d351359f3d7e5d2285a2b2a1f04a2d7244df706d5ea9",
            byteLength = 8331336,
            faceCount = 1,
            coverageTags = listOf("sfnt-source", "single-otf", "table:CFF", "table:cmap", "table:head", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Thai/CJK boundary review evidence now covers optional vert kana alternates on vendored Noto Sans SC only; Han direct-mapping rows, cmap format 14 variation selectors, Hangul coverage/refusals, paragraph-owned ruby/line-break diagnostics, and ticket-local trace dumps remain separate gates before support promotion.",
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "emoji-colrv1-noto-color",
            sourceKind = FontSourceKind.BUNDLED_FIXTURE,
            relativePath = "font/fixtures/fonts/fallback/Noto-COLRv1-noflags.ttf",
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = "SIL-OFL-1.1",
            licensePath = "font/fixtures/licenses/noto-emoji-OFL-1.1.txt",
            provenance = "Noto Emoji v2.051 noflags COLRv1 build; vendored external fixture from googlefonts/noto-emoji commit f3ae03f5e9b3b8516fa151f7168159ca1a3e7515",
            contentSha256 = "469e3b92d63cfc203789f8742f1835b8672c7b5995ab4a832f1699b712a5afcc",
            byteLength = 2992136,
            faceCount = 1,
            coverageTags = listOf("color-colrv1", "sfnt-source", "single-ttf", "table:COLR", "table:CPAL", "table:cmap", "table:glyf", "table:head", "table:name"),
            normativeStatus = FontFixtureNormativeStatus.NORMATIVE,
            remainingGate = "Emoji sequence shaping, color glyph rendering, and GPU text tickets must attach separate evidence before support promotion.",
        ),
        plannedGeneratedFixtureManifestEntry(
            fixtureId = "ttc-face-index-planned-generated",
            generatorId = "kfont.fixture.ttc-face-index.v1",
            generatorParameters = listOf("container=ttc", "faceCount=2", "outline=glyf", "selectedFaceIndex=1"),
            faceCount = 2,
            coverageTags = listOf("collection-index", "sfnt-source", "ttc-face-index"),
            remainingGate = "Generate and check in deterministic TTC bytes before normative collection evidence.",
            typefaceReportLabels = listOf("ttc-face-index-variant"),
        ),
        plannedGeneratedFixtureManifestEntry(
            fixtureId = "malformed-directory-planned-generated",
            generatorId = "kfont.fixture.malformed-directory.v1",
            generatorParameters = listOf("directory=malformed", "faceCount=0", "tableDirectory=truncated"),
            faceCount = 0,
            coverageTags = listOf("malformed-directory", "sfnt-directory-refusal"),
            remainingGate = "Generate deterministic malformed bytes and refusal dump before normative parser evidence.",
        ),
        plannedGeneratedFixtureManifestEntry(
            fixtureId = "missing-required-table-planned-generated",
            generatorId = "kfont.fixture.missing-required-table.v1",
            generatorParameters = listOf("faceCount=0", "missing=head", "requiredTable=head"),
            faceCount = 0,
            coverageTags = listOf("missing-required-table", "sfnt-required-table-refusal"),
            remainingGate = "Generate deterministic missing-required-table bytes and refusal dump before normative parser evidence.",
        ),
        BundledFontFixtureManifestEntry(
            fixtureId = "system-scanned-host-dependent",
            sourceKind = FontSourceKind.SYSTEM_SCANNED,
            relativePath = null,
            generatorId = null,
            generatorParameters = emptyList(),
            licenseId = null,
            licensePath = null,
            provenance = "Host system scan source without captured fixture bytes; non-normative drift input only.",
            contentSha256 = null,
            byteLength = null,
            faceCount = 0,
            coverageTags = listOf("host-dependent", "system-scan-non-normative"),
            normativeStatus = FontFixtureNormativeStatus.NON_NORMATIVE,
            remainingGate = "Capture source bytes, license, provenance, hash, and byte length before normative evidence use.",
            diagnostics = listOf(
                FontFixtureManifestDiagnostic(
                    code = "font.source.host-dependent",
                    fixtureId = "system-scanned-host-dependent",
                    detail = "Host-scanned fonts are non-normative until bytes are captured into the fixture manifest.",
                ),
            ),
            fontSourceReportLabels = listOf("system-scanned-host-dependent"),
        ),
    ),
)

private fun plannedGeneratedFixtureManifestEntry(
    fixtureId: String,
    generatorId: String,
    generatorParameters: List<String>,
    faceCount: Int,
    coverageTags: List<String>,
    remainingGate: String,
    typefaceReportLabels: List<String> = emptyList(),
): BundledFontFixtureManifestEntry = BundledFontFixtureManifestEntry(
    fixtureId = fixtureId,
    sourceKind = FontSourceKind.GENERATED_FIXTURE,
    relativePath = null,
    generatorId = generatorId,
    generatorParameters = generatorParameters,
    licenseId = "Kanvas-generated-fixture",
    licensePath = null,
    provenance = "Planned pure Kotlin generated fixture; bytes are not checked in yet.",
    contentSha256 = null,
    byteLength = null,
    faceCount = faceCount,
    coverageTags = coverageTags,
    normativeStatus = FontFixtureNormativeStatus.PLANNED_GENERATED,
    remainingGate = remainingGate,
    diagnostics = listOf(
        FontFixtureManifestDiagnostic(
            code = "font.fixture.generated-bytes-missing",
            fixtureId = fixtureId,
            detail = "Generated fixture provenance exists, but deterministic bytes are not checked in.",
        ),
    ),
    typefaceReportLabels = typefaceReportLabels,
)

object FontFixtureManifestWriter {
    fun writeManifestJson(
        manifest: FontFixtureManifest = defaultFontFixtureManifest(),
    ): CanonicalFontIdentityJson = CanonicalFontIdentityJson("${manifest.toCanonicalJson().value}\n")
}

/**
 * Stable outline family facts included in [TypefaceIdentityPreimage].
 */
enum class TypefaceOutlineFormat(
    val serializedName: String,
) {
    TRUE_TYPE_GLYF("TrueTypeGlyf"),
    CFF("CFF"),
    CFF2("CFF2"),
    BITMAP_ONLY("BitmapOnly"),
    UNKNOWN("Unknown"),
}

/**
 * Stable scaler route facts included in [TypefaceIdentityPreimage].
 */
enum class TypefaceScalerMode(
    val serializedName: String,
) {
    OUTLINE("Outline"),
    A8("A8"),
    SDF("SDF"),
    DRIFT_ONLY("DriftOnly"),
}

/**
 * Canonical facts for the selected character map used by a typeface.
 */
data class TypefaceCMapSelection(
    val platformId: Int,
    val encodingId: Int,
    val format: Int,
    val language: Int,
    val unicode: Boolean,
) {
    init {
        require(platformId >= 0) { "platformId must be non-negative." }
        require(encodingId >= 0) { "encodingId must be non-negative." }
        require(format >= 0) { "format must be non-negative." }
        require(language >= 0) { "language must be non-negative." }
    }

    internal fun toCanonicalJson(indent: String): String = buildString {
        append(indent).append("{\n")
        appendFontJsonField("platformId", platformId, indent = "$indent  ", comma = true)
        appendFontJsonField("encodingId", encodingId, indent = "$indent  ", comma = true)
        appendFontJsonField("format", format, indent = "$indent  ", comma = true)
        appendFontJsonField("language", language, indent = "$indent  ", comma = true)
        appendFontJsonField("unicode", unicode, indent = "$indent  ", comma = false)
        append(indent).append("}")
    }
}

/**
 * One normalized OpenType variation coordinate.
 */
class TypefaceVariationCoordinate(
    val axisTag: String,
    value: Double,
) {
    val value: Double = value.normalizedTypefaceVariationValue()

    init {
        require(axisTag.isStableSfntTableTag()) {
            "axisTag must be a four-character printable ASCII OpenType tag."
        }
        require(!value.isNaN() && !value.isInfinite()) {
            "variation coordinate value must be finite."
        }
    }

    internal fun toCanonicalJson(indent: String): String = buildString {
        append(indent).append("{\n")
        appendFontJsonField("axisTag", axisTag, indent = "$indent  ", comma = true)
        append(indent).append("  ").append("value".evidenceQuoted()).append(": ")
        append(value.toTypefaceJsonNumber())
        append("\n")
        append(indent).append("}")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TypefaceVariationCoordinate &&
            axisTag == other.axisTag &&
            value == other.value

    override fun hashCode(): Int {
        var result = axisTag.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }

override fun toString(): String =
        "TypefaceVariationCoordinate(axisTag=$axisTag, value=$value)"
}

/**
 * One named variable-font instance advertised by a typeface for fallback selection.
 */
class TypefaceNamedInstance(
    val name: String,
    coordinates: List<TypefaceVariationCoordinate>,
) {
    val coordinates: List<TypefaceVariationCoordinate> = coordinates.normalizedVariationCoordinates()

    init {
        require(name.isNotBlank()) { "named instance name must not be blank." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TypefaceNamedInstance &&
            name == other.name &&
            coordinates == other.coordinates

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + coordinates.hashCode()
        return result
    }

    override fun toString(): String =
        "TypefaceNamedInstance(name=$name, coordinates=$coordinates)"
}

/**
 * One variable-font axis advertised by a typeface for fallback selection.
 */
class TypefaceVariationAxisRange(
    val axisTag: String,
    val minimum: Double,
    val defaultValue: Double,
    val maximum: Double,
) {
    init {
        require(axisTag.isStableSfntTableTag()) {
            "variation axis tag must be a four-character printable ASCII OpenType tag."
        }
        require(minimum.isFinite()) { "variation axis minimum must be finite." }
        require(defaultValue.isFinite()) { "variation axis default value must be finite." }
        require(maximum.isFinite()) { "variation axis maximum must be finite." }
        require(minimum <= defaultValue) {
            "variation axis default value must be greater than or equal to the minimum."
        }
        require(defaultValue <= maximum) {
            "variation axis default value must be less than or equal to the maximum."
        }
    }

    fun clamp(requestedValue: Double): Double =
        requestedValue.coerceIn(minimum, maximum).normalizedTypefaceVariationValue()
}

/**
 * Palette selection facts that can affect color glyph output.
 */
class TypefacePaletteSelection(
    val index: Int,
    overrides: List<String> = emptyList(),
) {
    val overrides: List<String> = overrides.distinct().sorted()

    init {
        require(index >= 0) { "palette index must be non-negative." }
        require(this.overrides.all { override -> override.isNotBlank() && override.none { it < ' ' } }) {
            "palette overrides must be stable one-line strings."
        }
    }

    internal fun toCanonicalJson(indent: String): String = buildString {
        append(indent).append("{\n")
        appendFontJsonField("index", index, indent = "$indent  ", comma = true)
        append(indent).append("  ").append("overrides".evidenceQuoted()).append(": ")
        append(overrides.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.evidenceQuoted() })
        append("\n")
        append(indent).append("}")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is TypefacePaletteSelection &&
            index == other.index &&
            overrides == other.overrides

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + overrides.hashCode()
        return result
    }

    override fun toString(): String =
        "TypefacePaletteSelection(index=$index, overrides=$overrides)"
}

/**
 * Stable diagnostic included in typeface identity evidence.
 */
data class TypefaceIdentityDiagnostic(
    val code: String,
    val detail: String? = null,
) {
    init {
        require(code.isStableDiagnosticCode()) { "code must be a stable one-line diagnostic code." }
    }

    internal fun toCanonicalJson(indent: String): String = buildString {
        append(indent).append("{\n")
        appendFontJsonField("code", code, indent = "$indent  ", comma = true)
        append(indent).append("  ").append("detail".evidenceQuoted()).append(": ")
        append(detail.toFontJsonNullableString())
        append("\n")
        append(indent).append("}")
    }
}

/**
 * Canonical preimage for deriving and auditing [TypefaceID].
 */
class TypefaceIdentityPreimage(
    val sourceId: FontSourceID,
    val collectionIndex: Int,
    val postScriptName: String?,
    val familyName: String,
    val styleName: String,
    val style: FontStyle,
    val outlineFormat: TypefaceOutlineFormat,
    val selectedCMap: TypefaceCMapSelection,
    val scalerMode: TypefaceScalerMode,
    variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val palette: TypefacePaletteSelection? = null,
    val fallbackCatalogGeneration: Int? = null,
    tableTags: List<String> = emptyList(),
    diagnostics: List<TypefaceIdentityDiagnostic> = emptyList(),
) {
    val variationCoordinates: List<TypefaceVariationCoordinate> = variationCoordinates.normalizedVariationCoordinates()
    val tableTags: List<String> = tableTags.normalizedTableTags()
    val diagnostics: List<TypefaceIdentityDiagnostic> =
        diagnostics.sortedWith(compareBy<TypefaceIdentityDiagnostic> { it.code }.thenBy { it.detail.orEmpty() })

    init {
        require(collectionIndex >= 0) { "collectionIndex must be non-negative." }
        require(postScriptName == null || postScriptName.isNotBlank()) {
            "postScriptName must not be blank when present."
        }
        require(familyName.isNotBlank()) { "familyName must not be blank." }
        require(styleName.isNotBlank()) { "styleName must not be blank." }
        require(selectedCMap.unicode) { "selectedCMap must be a usable Unicode cmap." }
        require(fallbackCatalogGeneration == null || fallbackCatalogGeneration >= 0) {
            "fallbackCatalogGeneration must be non-negative when present."
        }
    }

    /**
     * Serializes every [TypefaceID]-affecting fact as canonical JSON.
     */
    fun toCanonicalJson(): String = buildString {
        append("{\n")
        appendFontJsonField("schema", PreimageSchema, indent = "  ", comma = true)
        appendFontJsonField("sourceId", sourceId.value.toHexDashString(), indent = "  ", comma = true)
        appendFontJsonField("collectionIndex", collectionIndex, indent = "  ", comma = true)
        append("  ").append("postScriptName".evidenceQuoted()).append(": ")
        append(postScriptName.toFontJsonNullableString())
        append(",\n")
        appendFontJsonField("familyName", familyName, indent = "  ", comma = true)
        appendFontJsonField("styleName", styleName, indent = "  ", comma = true)
        append("  ").append("style".evidenceQuoted()).append(": {\n")
        appendFontJsonField("weight", style.weight, indent = "    ", comma = true)
        appendFontJsonField("width", style.width, indent = "    ", comma = true)
        appendFontJsonField("slant", style.slant.typefaceSerializedName, indent = "    ", comma = false)
        append("  },\n")
        appendFontJsonField("outlineFormat", outlineFormat.serializedName, indent = "  ", comma = true)
        append("  ").append("selectedCMap".evidenceQuoted()).append(": ")
        append(selectedCMap.toCanonicalJson(indent = "  ").trimStart())
        append(",\n")
        appendFontJsonField("scalerMode", scalerMode.serializedName, indent = "  ", comma = true)
        append("  ").append("variationCoordinates".evidenceQuoted()).append(": ")
        if (variationCoordinates.isEmpty()) {
            append("[]")
        } else {
            append("[\n")
            append(variationCoordinates.joinToString(",\n") { it.toCanonicalJson(indent = "    ") })
            append("\n  ]")
        }
        append(",\n")
        append("  ").append("palette".evidenceQuoted()).append(": ")
        append(palette?.toCanonicalJson(indent = "  ")?.trimStart() ?: "null")
        append(",\n")
        append("  ").append("fallbackCatalogGeneration".evidenceQuoted()).append(": ")
        append(fallbackCatalogGeneration?.toString() ?: "null")
        append(",\n")
        append("  ").append("tableTags".evidenceQuoted()).append(": ")
        append(tableTags.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.evidenceQuoted() })
        append(",\n")
        append("  ").append("diagnostics".evidenceQuoted()).append(": ")
        if (diagnostics.isEmpty()) {
            append("[]\n")
        } else {
            append("[\n")
            append(diagnostics.joinToString(",\n") { it.toCanonicalJson(indent = "    ") })
            append("\n  ]\n")
        }
        append("}\n")
    }

    /**
     * Derives a deterministic UUID-backed typeface ID from [toCanonicalJson].
     */
    fun deriveTypefaceID(): TypefaceID =
        TypefaceID(stableUuidFromSha256("kanvas-typeface-id-v1\n${toCanonicalJson()}"))

    fun typefaceId(): TypefaceID = deriveTypefaceID()

    override fun equals(other: Any?): Boolean =
        this === other || other is TypefaceIdentityPreimage &&
            sourceId == other.sourceId &&
            collectionIndex == other.collectionIndex &&
            postScriptName == other.postScriptName &&
            familyName == other.familyName &&
            styleName == other.styleName &&
            style == other.style &&
            outlineFormat == other.outlineFormat &&
            selectedCMap == other.selectedCMap &&
            scalerMode == other.scalerMode &&
            variationCoordinates == other.variationCoordinates &&
            palette == other.palette &&
            fallbackCatalogGeneration == other.fallbackCatalogGeneration &&
            tableTags == other.tableTags &&
            diagnostics == other.diagnostics

    override fun hashCode(): Int {
        var result = sourceId.hashCode()
        result = 31 * result + collectionIndex
        result = 31 * result + (postScriptName?.hashCode() ?: 0)
        result = 31 * result + familyName.hashCode()
        result = 31 * result + styleName.hashCode()
        result = 31 * result + style.hashCode()
        result = 31 * result + outlineFormat.hashCode()
        result = 31 * result + selectedCMap.hashCode()
        result = 31 * result + scalerMode.hashCode()
        result = 31 * result + variationCoordinates.hashCode()
        result = 31 * result + (palette?.hashCode() ?: 0)
        result = 31 * result + (fallbackCatalogGeneration ?: 0)
        result = 31 * result + tableTags.hashCode()
        result = 31 * result + diagnostics.hashCode()
        return result
    }

    companion object {
        const val PreimageSchema: String = "org.graphiks.kanvas.font.TypefaceIdentityPreimage.v1"
    }
}

/**
 * One deterministic row in [TypefaceIdentityReport].
 */
class TypefaceIdentityReportEntry(
    val label: String,
    val preimage: TypefaceIdentityPreimage?,
    diagnostics: List<TypefaceIdentityDiagnostic> = preimage?.diagnostics ?: emptyList(),
    val claimPromotionAllowed: Boolean = false,
) {
    val diagnostics: List<TypefaceIdentityDiagnostic> =
        diagnostics.sortedWith(compareBy<TypefaceIdentityDiagnostic> { it.code }.thenBy { it.detail.orEmpty() })

    init {
        require(label.isNotBlank()) { "label must not be blank." }
        require(preimage != null || this.diagnostics.isNotEmpty()) {
            "diagnostic-only typeface identity rows must include diagnostics."
        }
        require(!claimPromotionAllowed) {
            "Typeface identity evidence cannot promote rendering support claims."
        }
    }

    fun typefaceId(): TypefaceID? = preimage?.typefaceId()

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("label", label, comma = true)
        append("typefaceId".evidenceQuoted()).append(":")
        val typefaceId = typefaceId()
        append(typefaceId?.value?.toHexDashString()?.evidenceQuoted() ?: "null")
        append(",")
        append("preimage".evidenceQuoted()).append(":")
        append(preimage?.toCanonicalJson()?.trim() ?: "null")
        append(",")
        append("diagnostics".evidenceQuoted()).append(":")
        if (diagnostics.isEmpty()) {
            append("[]")
        } else {
            append("[")
            append(diagnostics.joinToString(",") { it.toCanonicalJson(indent = "    ").trim() })
            append("]")
        }
        append(",")
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("}")
    }
}

/**
 * Deterministic report for KFONT-M1-002 typeface identity evidence.
 */
class TypefaceIdentityReport(
    val fixtureName: String,
    entries: List<TypefaceIdentityReportEntry>,
) {
    val entries: List<TypefaceIdentityReportEntry> = entries.toList()
    val legacyGate: String = "typeface"
    val gateStatus: String = "open"
    val claimPromotionAllowed: Boolean = false

    init {
        require(fixtureName.isNotBlank()) { "fixtureName must not be blank." }
        require(this.entries.map { entry -> entry.label }.distinct().size == this.entries.size) {
            "typeface identity report entries must have unique labels."
        }
        require(this.entries.all { entry -> !entry.claimPromotionAllowed }) {
            "typeface identity report cannot contain claim-promoting rows."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("schema", TYPEFACE_IDENTITY_REPORT_SCHEMA, comma = true)
        appendFontCompactJsonField("fixtureName", fixtureName, comma = true)
        appendFontCompactJsonField("legacyGate", legacyGate, comma = true)
        appendFontCompactJsonField("gateStatus", gateStatus, comma = true)
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = true)
        append("dashboardRow".evidenceQuoted()).append(":{")
        appendFontCompactJsonField("name", "TypefaceID glyph-affecting identity", comma = true)
        appendFontCompactJsonField("classification", "tracked-gap", comma = true)
        appendFontCompactJsonField("legacyGate", legacyGate, comma = true)
        appendFontCompactJsonField("gateStatus", gateStatus, comma = true)
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("},")
        append("entries".evidenceQuoted()).append(":")
        append(entries.joinToString(separator = ",", prefix = "[", postfix = "]") { entry ->
            entry.toCanonicalJson()
        })
        append("}")
    }
}

fun typefaceIdentityDiagnostic(
    code: String,
    message: String? = null,
): TypefaceIdentityDiagnostic = TypefaceIdentityDiagnostic(
    code = code,
    detail = message,
)

fun typefaceIdentityPreimage(
    sourceId: FontSourceID,
    collectionIndex: Int,
    postScriptName: String?,
    familyName: String,
    styleName: String,
    style: FontStyle = FontStyle.fromStyleName(styleName),
    outlineFormat: TypefaceOutlineFormat,
    selectedCMap: TypefaceCMapSelection,
    scalerMode: TypefaceScalerMode,
    variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    palette: TypefacePaletteSelection? = null,
    fallbackCatalogGeneration: Int? = null,
    tableTags: List<String> = emptyList(),
    diagnostics: List<TypefaceIdentityDiagnostic> = emptyList(),
): TypefaceIdentityPreimage = TypefaceIdentityPreimage(
    sourceId = sourceId,
    collectionIndex = collectionIndex,
    postScriptName = postScriptName,
    familyName = familyName,
    styleName = styleName,
    style = style,
    outlineFormat = outlineFormat,
    selectedCMap = selectedCMap,
    scalerMode = scalerMode,
    variationCoordinates = variationCoordinates,
    palette = palette,
    fallbackCatalogGeneration = fallbackCatalogGeneration,
    tableTags = tableTags,
    diagnostics = diagnostics,
)

fun defaultTypefaceIdentityReport(): TypefaceIdentityReport {
    val singleFaceSourceId = liberationSansManifestSourcePreimage().sourceId()
    val collectionSourceId = fontSourceIdentityPreimage(
        kind = FontSourceKind.GENERATED_FIXTURE,
        declaredName = "Fixture Collection TTC",
        contentBytes = byteArrayOf(10, 11, 12),
        faceCount = 2,
        tableTags = listOf("cmap", "glyf", "head", "name"),
        parserGeneration = 1,
    ).sourceId()
    val variableSourceId = fontSourceIdentityPreimage(
        kind = FontSourceKind.GENERATED_FIXTURE,
        declaredName = "Fixture Variable Sans",
        contentBytes = byteArrayOf(20, 21, 22),
        faceCount = 1,
        tableTags = listOf("cmap", "fvar", "gvar", "glyf", "head", "name"),
        parserGeneration = 1,
    ).sourceId()
    val paletteSourceId = fontSourceIdentityPreimage(
        kind = FontSourceKind.GENERATED_FIXTURE,
        declaredName = "Fixture Color Palette",
        contentBytes = byteArrayOf(30, 31, 32),
        faceCount = 1,
        tableTags = listOf("COLR", "CPAL", "cmap", "glyf", "head", "name"),
        parserGeneration = 1,
    ).sourceId()
    val unicodeCMap = TypefaceCMapSelection(
        platformId = 3,
        encodingId = 10,
        format = 12,
        language = 0,
        unicode = true,
    )

    return TypefaceIdentityReport(
        fixtureName = "typeface-id.json",
        entries = listOf(
            TypefaceIdentityReportEntry(
                label = "single-face-ttf",
                preimage = typefaceIdentityPreimage(
                    sourceId = singleFaceSourceId,
                    collectionIndex = 0,
                    postScriptName = "FixtureSans-Regular",
                    familyName = "Fixture Sans",
                    styleName = "Regular",
                    outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
                    selectedCMap = unicodeCMap,
                    scalerMode = TypefaceScalerMode.OUTLINE,
                    tableTags = listOf("name", "cmap", "glyf", "head"),
                ),
            ),
            TypefaceIdentityReportEntry(
                label = "ttc-face-index-variant",
                preimage = typefaceIdentityPreimage(
                    sourceId = collectionSourceId,
                    collectionIndex = 1,
                    postScriptName = "FixtureCollectionSans-Bold",
                    familyName = "Fixture Collection Sans",
                    styleName = "Bold",
                    style = FontStyle(weight = 700, width = 5, slant = FontSlant.UPRIGHT),
                    outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
                    selectedCMap = unicodeCMap,
                    scalerMode = TypefaceScalerMode.OUTLINE,
                    tableTags = listOf("cmap", "glyf", "head", "name"),
                ),
            ),
            TypefaceIdentityReportEntry(
                label = "variable-axis-change",
                preimage = typefaceIdentityPreimage(
                    sourceId = variableSourceId,
                    collectionIndex = 0,
                    postScriptName = "FixtureVariableSans-Regular",
                    familyName = "Fixture Variable Sans",
                    styleName = "Regular",
                    outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
                    selectedCMap = unicodeCMap,
                    scalerMode = TypefaceScalerMode.OUTLINE,
                    variationCoordinates = listOf(
                        TypefaceVariationCoordinate(axisTag = "wght", value = 700.0),
                    ),
                    tableTags = listOf("name", "gvar", "cmap", "head", "fvar", "glyf"),
                ),
            ),
            TypefaceIdentityReportEntry(
                label = "palette-change",
                preimage = typefaceIdentityPreimage(
                    sourceId = paletteSourceId,
                    collectionIndex = 0,
                    postScriptName = "FixtureColorPalette-Regular",
                    familyName = "Fixture Color Palette",
                    styleName = "Regular",
                    outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
                    selectedCMap = unicodeCMap,
                    scalerMode = TypefaceScalerMode.OUTLINE,
                    palette = TypefacePaletteSelection(
                        index = 1,
                        overrides = listOf("gid=42:#ff0000ff", "gid=7:#000000ff", "gid=42:#ff0000ff"),
                    ),
                    tableTags = listOf("CPAL", "COLR", "name", "cmap", "head", "glyf"),
                ),
            ),
            TypefaceIdentityReportEntry(
                label = "invalid-collection-index-diagnostic",
                preimage = null,
                diagnostics = listOf(
                    typefaceIdentityDiagnostic(
                        code = "font.collection-index-invalid",
                        message = "Requested collection index 3 but the fixture reports 2 faces.",
                    ),
                    typefaceIdentityDiagnostic(
                        code = "font.sfnt.identity-facts-incomplete",
                        message = "Collection face facts are incomplete; no TypefaceID was derived.",
                    ),
                ),
            ),
            TypefaceIdentityReportEntry(
                label = "no-usable-unicode-cmap-diagnostic",
                preimage = null,
                diagnostics = listOf(
                    typefaceIdentityDiagnostic(
                        code = "font.sfnt.cmap-unusable",
                        message = "No usable Unicode cmap subtable was selected for this fixture face.",
                    ),
                    typefaceIdentityDiagnostic(
                        code = "font.sfnt.identity-facts-incomplete",
                        message = "Selected Unicode cmap facts are missing; no TypefaceID was derived.",
                    ),
                ),
            ),
        ),
    )
}

data class FontCatalogDiagnostic(
    val code: String,
    val detail: String? = null,
    val fixtureId: String? = null,
    val familyName: String? = null,
    val styleName: String? = null,
) {
    init {
        require(code.isStableDiagnosticCode()) { "code must be a stable one-line diagnostic code." }
        require(fixtureId == null || fixtureId.isStableManifestToken()) {
            "fixtureId must be a stable manifest token when present."
        }
        require(familyName == null || familyName.isStableManifestString()) {
            "familyName must be a stable one-line string when present."
        }
        require(styleName == null || styleName.isStableManifestString()) {
            "styleName must be a stable one-line string when present."
        }
        require(detail == null || detail.isStableManifestString()) {
            "detail must be a stable one-line string when present."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("code", code, comma = true)
        append("fixtureId".evidenceQuoted()).append(":").append(fixtureId.toFontJsonNullableString()).append(",")
        append("familyName".evidenceQuoted()).append(":").append(familyName.toFontJsonNullableString()).append(",")
        append("styleName".evidenceQuoted()).append(":").append(styleName.toFontJsonNullableString()).append(",")
        append("detail".evidenceQuoted()).append(":").append(detail.toFontJsonNullableString())
        append("}")
    }
}

class BundledFontCatalogInput(
    val manifestEntry: BundledFontFixtureManifestEntry,
    val typefacePreimage: TypefaceIdentityPreimage,
    genericFamilies: List<String> = emptyList(),
    scriptCoverage: List<String> = emptyList(),
    localeHints: List<String> = emptyList(),
    colorFormats: List<String> = emptyList(),
    val emojiCapable: Boolean = false,
    val colorCapable: Boolean = false,
) {
    val genericFamilies: List<String> = genericFamilies.normalizedCatalogFacts("genericFamilies")
    val scriptCoverage: List<String> = scriptCoverage.normalizedCatalogFacts("scriptCoverage")
    val localeHints: List<String> = localeHints.normalizedCatalogFacts("localeHints")
    val colorFormats: List<String> = colorFormats.normalizedCatalogFacts("colorFormats")
}

class BundledFontCatalogEntry(
    val fixtureId: String,
    val sourceKind: String,
    val sourceId: FontSourceID,
    val typefaceId: TypefaceID,
    val familyName: String,
    val styleName: String,
    val postScriptName: String?,
    genericFamilies: List<String>,
    scriptCoverage: List<String>,
    localeHints: List<String>,
    colorFormats: List<String>,
    val emojiCapable: Boolean,
    val colorCapable: Boolean,
    val collectionIndex: Int,
    val outlineFormat: String,
    val scalerMode: String,
    val contentSha256: String,
    val relativePath: String,
    val licenseId: String?,
    val licensePath: String?,
    val provenance: String?,
    val paletteIndex: Int?,
    variationAxes: List<String>,
    diagnostics: List<FontCatalogDiagnostic> = emptyList(),
) {
    val genericFamilies: List<String> = genericFamilies.normalizedCatalogFacts("genericFamilies")
    val scriptCoverage: List<String> = scriptCoverage.normalizedCatalogFacts("scriptCoverage")
    val localeHints: List<String> = localeHints.normalizedCatalogFacts("localeHints")
    val colorFormats: List<String> = colorFormats.normalizedCatalogFacts("colorFormats")
    val variationAxes: List<String> = variationAxes.normalizedCatalogFacts("variationAxes")
    val diagnostics: List<FontCatalogDiagnostic> = diagnostics.sortedWith(
        compareBy<FontCatalogDiagnostic> { it.code }
            .thenBy { it.fixtureId.orEmpty() }
            .thenBy { it.familyName.orEmpty() }
            .thenBy { it.styleName.orEmpty() }
            .thenBy { it.detail.orEmpty() },
    )

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("fixtureId", fixtureId, comma = true)
        appendFontCompactJsonField("sourceKind", sourceKind, comma = true)
        appendFontCompactJsonField("sourceId", sourceId.value.toHexDashString(), comma = true)
        appendFontCompactJsonField("typefaceId", typefaceId.value.toHexDashString(), comma = true)
        appendFontCompactJsonField("familyName", familyName, comma = true)
        appendFontCompactJsonField("styleName", styleName, comma = true)
        append("postScriptName".evidenceQuoted()).append(":").append(postScriptName.toFontJsonNullableString()).append(",")
        appendStringArrayField("genericFamilies", genericFamilies, comma = true)
        appendStringArrayField("scriptCoverage", scriptCoverage, comma = true)
        appendStringArrayField("localeHints", localeHints, comma = true)
        appendStringArrayField("colorFormats", colorFormats, comma = true)
        appendFontCompactJsonField("emojiCapable", emojiCapable, comma = true)
        appendFontCompactJsonField("colorCapable", colorCapable, comma = true)
        append("collectionIndex".evidenceQuoted()).append(":").append(collectionIndex).append(",")
        appendFontCompactJsonField("outlineFormat", outlineFormat, comma = true)
        appendFontCompactJsonField("scalerMode", scalerMode, comma = true)
        appendFontCompactJsonField("contentSha256", contentSha256, comma = true)
        appendFontCompactJsonField("relativePath", relativePath, comma = true)
        append("licenseId".evidenceQuoted()).append(":").append(licenseId.toFontJsonNullableString()).append(",")
        append("licensePath".evidenceQuoted()).append(":").append(licensePath.toFontJsonNullableString()).append(",")
        append("provenance".evidenceQuoted()).append(":").append(provenance.toFontJsonNullableString()).append(",")
        append("paletteIndex".evidenceQuoted()).append(":").append(paletteIndex?.toString() ?: "null").append(",")
        appendStringArrayField("variationAxes", variationAxes, comma = true)
        append("diagnostics".evidenceQuoted()).append(":")
        append(diagnostics.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
        append("}")
    }
}

class BundledFontCatalog(
    val generation: Int,
    entries: List<BundledFontCatalogEntry>,
    diagnostics: List<FontCatalogDiagnostic>,
    nonClaims: List<String> = BundledFontCatalogNonClaims,
    val claimPromotionAllowed: Boolean = false,
) {
    val catalogId: String = "font-catalog"
    val entries: List<BundledFontCatalogEntry> = entries.sortedWith(
        compareBy<BundledFontCatalogEntry> { it.familyName.lowercase() }
            .thenBy { it.familyName }
            .thenBy { it.styleName.lowercase() }
            .thenBy { it.styleName }
            .thenBy { it.fixtureId },
    )
    val diagnostics: List<FontCatalogDiagnostic> = diagnostics.sortedWith(
        compareBy<FontCatalogDiagnostic> { it.code }
            .thenBy { it.fixtureId.orEmpty() }
            .thenBy { it.familyName.orEmpty() }
            .thenBy { it.styleName.orEmpty() }
            .thenBy { it.detail.orEmpty() },
    )
    val nonClaims: List<String> = nonClaims.normalizedCatalogFacts("nonClaims")
    val entryCount: Int
        get() = entries.size
    val catalogSha256: String
        get() = catalogPayloadJson().toByteArray(Charsets.UTF_8).sha256Hex()

    init {
        require(generation >= 0) { "generation must be non-negative." }
        require(!claimPromotionAllowed) {
            "Bundled font catalogs cannot promote fallback, shaping, rendering, glyph, parser, or GPU claims."
        }
    }

    fun toCanonicalJson(): CanonicalFontIdentityJson = CanonicalFontIdentityJson(
        buildString {
            append("{")
            appendFontCompactJsonField("schema", BUNDLED_FONT_CATALOG_SCHEMA, comma = true)
            appendFontCompactJsonField("catalogId", catalogId, comma = true)
            append("generation".evidenceQuoted()).append(":").append(generation).append(",")
            append("entryCount".evidenceQuoted()).append(":").append(entryCount).append(",")
            appendFontCompactJsonField("catalogSha256", catalogSha256, comma = true)
            append("entries".evidenceQuoted()).append(":")
            append(entries.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
            append(",")
            append("diagnostics".evidenceQuoted()).append(":")
            append(diagnostics.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
            append(",")
            appendStringArrayField("nonClaims", nonClaims, comma = true)
            appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
            append("}")
        },
    )

    private fun catalogPayloadJson(): String = buildString {
        append("{")
        append("generation".evidenceQuoted()).append(":").append(generation).append(",")
        append("entries".evidenceQuoted()).append(":")
        append(entries.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
        append(",")
        append("diagnostics".evidenceQuoted()).append(":")
        append(diagnostics.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
        append("}")
    }
}

object BundledFontCatalogBuilder {
    fun build(
        generation: Int = 1,
        inputs: List<BundledFontCatalogInput>,
    ): BundledFontCatalog {
        val entries = mutableListOf<BundledFontCatalogEntry>()
        val diagnostics = mutableListOf<FontCatalogDiagnostic>()
        val seenFaces = linkedSetOf<String>()
        val orderedInputs = inputs.sortedWith(
            compareBy<BundledFontCatalogInput> { it.typefacePreimage.familyName.lowercase() }
                .thenBy { it.typefacePreimage.familyName }
                .thenBy { it.typefacePreimage.styleName.lowercase() }
                .thenBy { it.typefacePreimage.styleName }
                .thenBy { it.manifestEntry.fixtureId },
        )

        for (input in orderedInputs) {
            val manifest = input.manifestEntry
            val typeface = input.typefacePreimage
            val familyName = typeface.familyName.trim()
            val styleName = typeface.styleName.trim()
            val faceKey = buildString {
                append(familyName.familyKey())
                append('|')
                append(styleName.lowercase())
                append('|')
                append(typeface.collectionIndex)
            }

            if (manifest.sourceKind.isHostDependentByDefault()) {
                diagnostics += FontCatalogDiagnostic(
                    code = "font.source.host-dependent",
                    detail = "Host-scanned catalog entries remain non-normative until bytes are captured.",
                    fixtureId = manifest.fixtureId,
                    familyName = familyName,
                    styleName = styleName,
                )
                continue
            }

            if (!seenFaces.add(faceKey)) {
                diagnostics += FontCatalogDiagnostic(
                    code = "font.catalog.duplicate-face",
                    detail = "Duplicate family/style rows keep the first deterministic entry.",
                    fixtureId = manifest.fixtureId,
                    familyName = familyName,
                    styleName = styleName,
                )
                continue
            }

            if (
                manifest.licenseId == null ||
                manifest.licensePath == null ||
                manifest.provenance == null ||
                manifest.contentSha256 == null ||
                manifest.byteLength == null ||
                manifest.relativePath == null
            ) {
                diagnostics += FontCatalogDiagnostic(
                    code = "font.catalog.provenance-missing",
                    detail = "Catalog entries require license, provenance, path, SHA-256, and byte length facts.",
                    fixtureId = manifest.fixtureId,
                    familyName = familyName,
                    styleName = styleName,
                )
            }

            val entryDiagnostics = buildList {
                addAll(
                    manifest.diagnostics.map { diagnostic ->
                        FontCatalogDiagnostic(
                            code = diagnostic.code,
                            detail = diagnostic.detail,
                            fixtureId = manifest.fixtureId,
                            familyName = familyName,
                            styleName = styleName,
                        )
                    },
                )
                addAll(
                    typeface.diagnostics.map { diagnostic ->
                        FontCatalogDiagnostic(
                            code = diagnostic.code,
                            detail = diagnostic.detail,
                            fixtureId = manifest.fixtureId,
                            familyName = familyName,
                            styleName = styleName,
                        )
                    },
                )
                if (
                    typeface.outlineFormat != TypefaceOutlineFormat.TRUE_TYPE_GLYF &&
                    typeface.outlineFormat != TypefaceOutlineFormat.CFF &&
                    typeface.outlineFormat != TypefaceOutlineFormat.CFF2 &&
                    none { it.code == "font.outline-format-unsupported" }
                ) {
                    add(
                        FontCatalogDiagnostic(
                            code = "font.outline-format-unsupported",
                            detail = "Catalog entries require a supported outline format.",
                            fixtureId = manifest.fixtureId,
                            familyName = familyName,
                            styleName = styleName,
                        ),
                    )
                }
            }.sortedWith(
                compareBy<FontCatalogDiagnostic> { it.code }
                    .thenBy { it.fixtureId.orEmpty() }
                    .thenBy { it.familyName.orEmpty() }
                    .thenBy { it.styleName.orEmpty() }
                    .thenBy { it.detail.orEmpty() },
            )

            diagnostics += entryDiagnostics
            entries += BundledFontCatalogEntry(
                fixtureId = manifest.fixtureId,
                sourceKind = manifest.sourceKind.serializedName,
                sourceId = typeface.sourceId,
                typefaceId = typeface.typefaceId(),
                familyName = familyName,
                styleName = styleName,
                postScriptName = typeface.postScriptName,
                genericFamilies = input.genericFamilies,
                scriptCoverage = input.scriptCoverage,
                localeHints = input.localeHints,
                colorFormats = input.colorFormats,
                emojiCapable = input.emojiCapable,
                colorCapable = input.colorCapable,
                collectionIndex = typeface.collectionIndex,
                outlineFormat = typeface.outlineFormat.serializedName,
                scalerMode = typeface.scalerMode.serializedName,
                contentSha256 = manifest.contentSha256 ?: typeface.toCanonicalJson().toByteArray(Charsets.UTF_8).sha256Hex(),
                relativePath = manifest.relativePath ?: "",
                licenseId = manifest.licenseId,
                licensePath = manifest.licensePath,
                provenance = manifest.provenance,
                paletteIndex = typeface.palette?.index,
                variationAxes = typeface.variationCoordinates.map { it.axisTag },
                diagnostics = entryDiagnostics,
            )
        }

        return BundledFontCatalog(
            generation = generation,
            entries = entries,
            diagnostics = diagnostics.distinctBy { diagnostic ->
                listOf(
                    diagnostic.code,
                    diagnostic.fixtureId.orEmpty(),
                    diagnostic.familyName.orEmpty(),
                    diagnostic.styleName.orEmpty(),
                    diagnostic.detail.orEmpty(),
                )
            },
        )
    }
}

fun defaultBundledFontCatalog(): BundledFontCatalog =
    BundledFontCatalogBuilder.build(
        generation = 1,
        inputs = defaultBundledFontCatalogInputs(),
    )

object BundledFontCatalogWriter {
    fun writeCatalogJson(
        catalog: BundledFontCatalog = defaultBundledFontCatalog(),
    ): CanonicalFontIdentityJson = CanonicalFontIdentityJson("${catalog.toCanonicalJson().value}\n")
}

private data class DefaultBundledCatalogFixtureSpec(
    val fixtureId: String,
    val declaredName: String,
    val familyName: String,
    val styleName: String,
    val postScriptName: String,
    val genericFamilies: List<String>,
    val scriptCoverage: List<String>,
    val localeHints: List<String>,
    val selectedCMap: TypefaceCMapSelection,
    val outlineFormat: TypefaceOutlineFormat,
    val scalerMode: TypefaceScalerMode,
    val variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val colorFormats: List<String> = emptyList(),
    val emojiCapable: Boolean = false,
    val colorCapable: Boolean = false,
)

private fun defaultBundledFontCatalogInputs(): List<BundledFontCatalogInput> {
    val manifestById = defaultFontFixtureManifest().entries.associateBy { it.fixtureId }
    val unicodeCMap = TypefaceCMapSelection(
        platformId = 3,
        encodingId = 10,
        format = 12,
        language = 0,
        unicode = true,
    )
    val fixtures = listOf(
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "single-ttf-liberation-sans",
            declaredName = "Liberation Sans Regular",
            familyName = "Liberation Sans",
            styleName = "Regular",
            postScriptName = "LiberationSans-Regular",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Cyrillic", "Greek", "Latin"),
            localeHints = listOf("en"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
            scalerMode = TypefaceScalerMode.OUTLINE,
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "otf-cff-source-serif",
            declaredName = "Source Serif 4 Regular",
            familyName = "Source Serif 4",
            styleName = "Regular",
            postScriptName = "SourceSerif4-Regular",
            genericFamilies = listOf("serif"),
            scriptCoverage = listOf("Cyrillic", "Greek", "Latin"),
            localeHints = listOf("en"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.CFF,
            scalerMode = TypefaceScalerMode.OUTLINE,
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "variable-ttf-roboto-flex",
            declaredName = "Roboto Flex Variable",
            familyName = "Roboto Flex",
            styleName = "Regular",
            postScriptName = "RobotoFlex-Regular",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Latin"),
            localeHints = listOf("en"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
            scalerMode = TypefaceScalerMode.OUTLINE,
            variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 400.0)),
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "single-ttf-noto-sans-hebrew",
            declaredName = "Noto Sans Hebrew Regular",
            familyName = "Noto Sans Hebrew",
            styleName = "Regular",
            postScriptName = "NotoSansHebrew-Regular",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Hebrew"),
            localeHints = listOf("he"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
            scalerMode = TypefaceScalerMode.OUTLINE,
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "single-ttf-noto-naskh-arabic",
            declaredName = "Noto Naskh Arabic Regular",
            familyName = "Noto Naskh Arabic",
            styleName = "Regular",
            postScriptName = "NotoNaskhArabic-Regular",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Arabic"),
            localeHints = listOf("ar"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
            scalerMode = TypefaceScalerMode.OUTLINE,
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "single-ttf-noto-sans-devanagari",
            declaredName = "Noto Sans Devanagari Regular",
            familyName = "Noto Sans Devanagari",
            styleName = "Regular",
            postScriptName = "NotoSansDevanagari-Regular",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Devanagari"),
            localeHints = listOf("hi"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
            scalerMode = TypefaceScalerMode.OUTLINE,
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "single-ttf-noto-sans-thai",
            declaredName = "Noto Sans Thai Regular",
            familyName = "Noto Sans Thai",
            styleName = "Regular",
            postScriptName = "NotoSansThai-Regular",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Thai"),
            localeHints = listOf("th"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
            scalerMode = TypefaceScalerMode.OUTLINE,
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "single-otf-noto-sans-sc",
            declaredName = "Noto Sans SC Regular",
            familyName = "Noto Sans SC",
            styleName = "Regular",
            postScriptName = "NotoSansSC-Regular",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Han"),
            localeHints = listOf("zh"),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.CFF,
            scalerMode = TypefaceScalerMode.OUTLINE,
        ),
        DefaultBundledCatalogFixtureSpec(
            fixtureId = "emoji-colrv1-noto-color",
            declaredName = "Noto Color Emoji COLRv1 No Flags",
            familyName = "Noto Color Emoji",
            styleName = "Regular",
            postScriptName = "NotoColorEmoji",
            genericFamilies = listOf("sans-serif"),
            scriptCoverage = listOf("Emoji"),
            localeHints = emptyList(),
            selectedCMap = unicodeCMap,
            outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
            scalerMode = TypefaceScalerMode.OUTLINE,
            colorFormats = listOf("COLRv1"),
            emojiCapable = true,
            colorCapable = true,
        ),
    )

    return fixtures.map { spec ->
        val manifest = manifestById.getValue(spec.fixtureId)
        val tableTags = manifest.coverageTags.mapNotNull { tag ->
            tag.removePrefix("table:").takeIf { tag.startsWith("table:") }?.let { tableTag ->
                when (tableTag) {
                    "CFF" -> "CFF "
                    "SVG" -> "SVG "
                    else -> tableTag
                }
            }
        }
        val sourcePreimage = FontSourceIdentityPreimage(
            provenance = FontSourceProvenance(
                kind = manifest.sourceKind,
                declaredName = spec.declaredName,
                licenseId = manifest.licenseId,
                originPath = manifest.relativePath,
                hostDependent = manifest.sourceKind.isHostDependentByDefault(),
            ),
            contentSha256 = manifest.contentSha256,
            byteLength = manifest.byteLength,
            faceCount = manifest.faceCount,
            tableTags = tableTags,
            parserGeneration = 1,
            diagnostics = manifest.diagnostics.map { FontSourceIdentityDiagnostic(it.code, it.detail) },
        )
        BundledFontCatalogInput(
            manifestEntry = manifest,
            typefacePreimage = typefaceIdentityPreimage(
                sourceId = sourcePreimage.sourceId(),
                collectionIndex = 0,
                postScriptName = spec.postScriptName,
                familyName = spec.familyName,
                styleName = spec.styleName,
                outlineFormat = spec.outlineFormat,
                selectedCMap = spec.selectedCMap,
                scalerMode = spec.scalerMode,
                variationCoordinates = spec.variationCoordinates,
                tableTags = tableTags,
            ),
            genericFamilies = spec.genericFamilies,
            scriptCoverage = spec.scriptCoverage,
            localeHints = spec.localeHints,
            colorFormats = spec.colorFormats,
            emojiCapable = spec.emojiCapable,
            colorCapable = spec.colorCapable,
        )
    }
}

@JvmInline
value class CanonicalFontIdentityJson(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "canonical font identity JSON must not be blank." }
        require(value.isSingleJsonObjectWithOptionalTerminalNewline()) {
            "canonical font identity JSON must be one complete JSON object with at most one terminal newline."
        }
    }
}

class FontIdentityDumpSchema(
    val schemaVersion: Int = SchemaVersion,
    val schema: String = SchemaName,
    outputFiles: List<String> = OutputFiles,
    requiredFields: List<String> = RequiredFields,
    orderingRules: List<String> = OrderingRules,
    val claimPromotionAllowed: Boolean = false,
) {
    val outputFiles: List<String> = outputFiles.toList()
    val requiredFields: List<String> = requiredFields.toList()
    val orderingRules: List<String> = orderingRules.toList()

    init {
        require(schemaVersion == SchemaVersion) { "unsupported font identity dump schema version." }
        require(schema.isNotBlank()) { "schema must not be blank." }
        require(this.outputFiles == OutputFiles) { "font identity dump output file order is fixed by the schema." }
        require(this.requiredFields == RequiredFields) { "font identity dump required fields are fixed by the schema." }
        require(this.orderingRules == OrderingRules) { "font identity dump ordering rules are fixed by the schema." }
        require(!claimPromotionAllowed) {
            "Font identity dumps are evidence plumbing only and cannot promote support claims."
        }
    }

    fun toCanonicalJson(): CanonicalFontIdentityJson = CanonicalFontIdentityJson(
        buildString {
            append("{")
            appendFontCompactJsonField("schema", schema, comma = true)
            append("schemaVersion".evidenceQuoted()).append(":").append(schemaVersion).append(",")
            appendStringArrayField("outputFiles", outputFiles, comma = true)
            appendStringArrayField("requiredFields", requiredFields, comma = true)
            appendStringArrayField("orderingRules", orderingRules, comma = true)
            appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
            append("}")
        },
    )

    companion object {
        const val SchemaVersion: Int = 1
        const val SchemaName: String = "org.graphiks.kanvas.font.FontIdentityDumpSchema.v1"

        val OutputFiles: List<String> = listOf(
            "font-source.json",
            "typeface-id.json",
            "identity-dump-schema.json",
        )

        val RequiredFields: List<String> = listOf(
            "font source kind",
            "font source face count",
            "font source table tags",
            "typeface collection index",
            "typeface selected cmap",
            "typeface variation coordinates",
            "typeface palette identity",
            "host-dependent marker",
            "diagnostics",
            "claimPromotionAllowed",
        )

        val OrderingRules: List<String> = listOf(
            "reports preserve explicit fixture row order",
            "sorted table tags",
            "sorted variation coordinates",
            "sorted palette overrides",
            "diagnostics sorted by code and detail",
            "byte-for-byte UTF-8 comparison includes schema description",
        )

        val Default: FontIdentityDumpSchema = FontIdentityDumpSchema()
    }
}

data class FontIdentityDumpBundle(
    val fontSourceJson: CanonicalFontIdentityJson,
    val typefaceIdJson: CanonicalFontIdentityJson,
    val schemaDescriptionJson: CanonicalFontIdentityJson = FontIdentityDumpSchema.Default.toCanonicalJson(),
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(!claimPromotionAllowed) {
            "Font identity dump bundles cannot promote support claims."
        }
    }

    fun toCanonicalJson(): CanonicalFontIdentityJson = CanonicalFontIdentityJson(
        buildString {
            append("{")
            appendFontCompactJsonField("schema", FONT_IDENTITY_DUMP_BUNDLE_SCHEMA, comma = true)
            append("schemaVersion".evidenceQuoted()).append(":")
            append(FontIdentityDumpSchema.SchemaVersion)
            append(",")
            appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = true)
            append("files".evidenceQuoted()).append(":[")
            append(dumpFiles().joinToString(separator = ",") { (label, json) ->
                buildString {
                    append("{")
                    appendFontCompactJsonField("label", label, comma = true)
                    append("json".evidenceQuoted()).append(":").append(json.value)
                    append("}")
                }
            })
            append("]")
            append("}")
        },
    )

    internal fun dumpFiles(): List<Pair<String, CanonicalFontIdentityJson>> = listOf(
        "font-source.json" to fontSourceJson,
        "typeface-id.json" to typefaceIdJson,
        "identity-dump-schema.json" to schemaDescriptionJson,
    )
}

class FontIdentityDumpDeterminismResult(
    val matches: Boolean,
    val firstSha256: String,
    val secondSha256: String,
    differingFiles: List<String>,
) {
    val differingFiles: List<String> = differingFiles.toList()

    init {
        require(firstSha256.isLowercaseSha256Hex()) { "firstSha256 must be a lowercase SHA-256 digest." }
        require(secondSha256.isLowercaseSha256Hex()) { "secondSha256 must be a lowercase SHA-256 digest." }
        require(this.differingFiles == this.differingFiles.distinct()) { "differing files must be unique." }
        require(matches == (firstSha256 == secondSha256 && this.differingFiles.isEmpty())) {
            "determinism result must agree with hashes and differing files."
        }
    }

    fun toCanonicalJson(): CanonicalFontIdentityJson = CanonicalFontIdentityJson(
        buildString {
            append("{")
            appendFontCompactJsonField("schema", FONT_IDENTITY_DUMP_DETERMINISM_SCHEMA, comma = true)
            appendFontCompactJsonField("matches", matches, comma = true)
            appendFontCompactJsonField("firstSha256", firstSha256, comma = true)
            appendFontCompactJsonField("secondSha256", secondSha256, comma = true)
            appendStringArrayField("differingFiles", differingFiles, comma = false)
            append("}")
        },
    )
}

object FontIdentityDumpWriter {
    fun writeFontSourceJson(
        report: FontSourceIdentityReport = defaultFontSourceIdentityReport(),
    ): CanonicalFontIdentityJson = CanonicalFontIdentityJson("${report.toCanonicalJson()}\n")

    fun writeTypefaceIdJson(
        report: TypefaceIdentityReport = defaultTypefaceIdentityReport(),
    ): CanonicalFontIdentityJson = CanonicalFontIdentityJson("${report.toCanonicalJson()}\n")

    fun writeBundle(
        fontSourceReport: FontSourceIdentityReport = defaultFontSourceIdentityReport(),
        typefaceReport: TypefaceIdentityReport = defaultTypefaceIdentityReport(),
        schema: FontIdentityDumpSchema = FontIdentityDumpSchema.Default,
    ): FontIdentityDumpBundle = FontIdentityDumpBundle(
        fontSourceJson = writeFontSourceJson(fontSourceReport),
        typefaceIdJson = writeTypefaceIdJson(typefaceReport),
        schemaDescriptionJson = schema.toCanonicalJson(),
        claimPromotionAllowed = false,
    )

    fun assertDeterministicDump(
        run: () -> FontIdentityDumpBundle,
    ): FontIdentityDumpDeterminismResult = verifyDeterministicRuns(
        first = run(),
        second = run(),
    )

    fun verifyDeterministicRuns(
        first: FontIdentityDumpBundle,
        second: FontIdentityDumpBundle,
    ): FontIdentityDumpDeterminismResult {
        val firstFiles = first.dumpFiles()
        val secondFiles = second.dumpFiles()
        val differingFiles = firstFiles.zip(secondFiles)
            .filter { (firstFile, secondFile) ->
                firstFile.first != secondFile.first || firstFile.second.value != secondFile.second.value
            }
            .map { (firstFile, secondFile) ->
                if (firstFile.first == secondFile.first) firstFile.first else "${firstFile.first}|${secondFile.first}"
            }
        val firstSha256 = firstFiles.toDumpBytePreimage().toByteArray(Charsets.UTF_8).sha256Hex()
        val secondSha256 = secondFiles.toDumpBytePreimage().toByteArray(Charsets.UTF_8).sha256Hex()
        return FontIdentityDumpDeterminismResult(
            matches = firstSha256 == secondSha256 && differingFiles.isEmpty(),
            firstSha256 = firstSha256,
            secondSha256 = secondSha256,
            differingFiles = differingFiles,
        )
    }
}

enum class FontDiagnosticClaimImpact(
    val serializedName: String,
) {
    TARGET_SUPPORTED("target-supported"),
    CURRENT_SUPPORTED("current-supported"),
    TRACKED_GAP("tracked-gap"),
    DEPENDENCY_GATED("DependencyGated"),
    FIXTURE_GATED("fixture-gated"),
    GPU_GATED("GPU-gated"),
    EXPECTED_UNSUPPORTED("expected-unsupported"),
    DRIFT_ONLY("drift-only"),
}

enum class FontDiagnosticSeverity(
    val serializedName: String,
) {
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
}

class FontDiagnosticCode(
    val code: String,
    val namespace: String,
    val claimImpact: FontDiagnosticClaimImpact,
    val severity: FontDiagnosticSeverity,
    val route: String,
    requiredFields: List<String>,
    val claimPromotionAllowed: Boolean = false,
) {
    val requiredFields: List<String> =
        (FontDiagnosticCommonRequiredFields + requiredFields).normalizedDiagnosticFieldNames()

    init {
        require(code.isStableDiagnosticCode()) { "diagnostic code must be a stable one-line token." }
        require(namespace in FontDiagnosticAcceptedNamespaces) {
            "diagnostic namespace must be one of the accepted font taxonomy namespaces."
        }
        require(code.startsWith("$namespace.")) {
            "diagnostic code must belong to its declared namespace."
        }
        require(route.isStableDiagnosticCode()) { "diagnostic route must be a stable one-line token." }
        require(claimImpact.isNonClaimImpact()) {
            "KFONT M0 diagnostic taxonomy rows cannot promote support claims."
        }
        require(!claimPromotionAllowed) {
            "Font diagnostic taxonomy rows cannot promote support claims."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("code", code, comma = true)
        appendFontCompactJsonField("namespace", namespace, comma = true)
        appendFontCompactJsonField("claimImpact", claimImpact.serializedName, comma = true)
        appendFontCompactJsonField("severity", severity.serializedName, comma = true)
        appendFontCompactJsonField("route", route, comma = true)
        appendStringArrayField("requiredFields", requiredFields, comma = true)
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("}")
    }
}

class LegacyFontDiagnosticMapping(
    val legacyCode: String,
    val targetCode: String,
    val classification: FontDiagnosticClaimImpact,
    val gateStatus: String = "open",
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(legacyCode.isStableDiagnosticCode()) { "legacy diagnostic code must be stable." }
        require(targetCode.isStableDiagnosticCode()) { "target diagnostic code must be stable." }
        require(classification.isNonClaimImpact()) {
            "Legacy diagnostic mappings cannot promote support claims."
        }
        require(gateStatus == "open") { "KFONT M0 legacy diagnostic gates must remain open." }
        require(!claimPromotionAllowed) {
            "Legacy diagnostic mappings cannot promote support claims."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("legacyCode", legacyCode, comma = true)
        appendFontCompactJsonField("targetCode", targetCode, comma = true)
        appendFontCompactJsonField("classification", classification.serializedName, comma = true)
        appendFontCompactJsonField("gateStatus", gateStatus, comma = true)
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("}")
    }
}

class FontDiagnosticClassification(
    val inputCode: String,
    val accepted: Boolean,
    val targetCode: String?,
    val namespace: String?,
    val classification: FontDiagnosticClaimImpact,
    val reason: String,
    val claimPromotionAllowed: Boolean = false,
) {
    init {
        require(inputCode.isNotBlank()) { "input diagnostic code must not be blank." }
        require(targetCode == null || targetCode.isStableDiagnosticCode()) {
            "target diagnostic code must be stable when present."
        }
        require(namespace == null || namespace in FontDiagnosticAcceptedNamespaces) {
            "classified namespace must be accepted when present."
        }
        require(classification.isNonClaimImpact()) {
            "Diagnostic classifications cannot promote support claims."
        }
        require(reason.isStableDiagnosticCode()) { "classification reason must be stable." }
        require(!claimPromotionAllowed) {
            "Diagnostic classifications cannot promote support claims."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("inputCode", inputCode, comma = true)
        appendFontCompactJsonField("accepted", accepted, comma = true)
        append("targetCode".evidenceQuoted()).append(":")
        append(targetCode.toFontJsonNullableString())
        append(",")
        append("namespace".evidenceQuoted()).append(":")
        append(namespace.toFontJsonNullableString())
        append(",")
        appendFontCompactJsonField("classification", classification.serializedName, comma = true)
        appendFontCompactJsonField("reason", reason, comma = true)
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("}")
    }
}

class FontDiagnosticSample(
    val label: String,
    val code: String,
    val subject: String,
    val route: String,
    val severity: FontDiagnosticSeverity,
    val classification: FontDiagnosticClaimImpact,
    fields: Map<String, String>,
    val claimPromotionAllowed: Boolean = false,
) {
    val fields: Map<String, String> = normalizedDiagnosticFieldMap(
        mapOf(
            "subject" to subject,
            "route" to route,
            "severity" to severity.serializedName,
            "claimImpact" to classification.serializedName,
        ) + fields,
    )

    init {
        require(label.isStableManifestToken()) { "sample diagnostic label must be stable." }
        require(code.isStableDiagnosticCode()) { "sample diagnostic code must be stable." }
        require(subject.isStableManifestString()) { "sample diagnostic subject must be stable." }
        require(route.isStableDiagnosticCode()) { "sample diagnostic route must be stable." }
        require(classification.isNonClaimImpact()) {
            "Sample diagnostics cannot promote support claims."
        }
        require(!claimPromotionAllowed) {
            "Sample diagnostics cannot promote support claims."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("label", label, comma = true)
        appendFontCompactJsonField("code", code, comma = true)
        appendFontCompactJsonField("subject", subject, comma = true)
        appendFontCompactJsonField("route", route, comma = true)
        appendFontCompactJsonField("severity", severity.serializedName, comma = true)
        appendFontCompactJsonField("classification", classification.serializedName, comma = true)
        append("fields".evidenceQuoted()).append(":{")
        append(fields.entries.joinToString(separator = ",") { (name, value) ->
            "${name.evidenceQuoted()}:${value.evidenceQuoted()}"
        })
        append("},")
        appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
        append("}")
    }
}

class FontDiagnosticTaxonomy(
    val acceptedNamespaces: List<String>,
    codes: List<FontDiagnosticCode>,
    legacyMappings: List<LegacyFontDiagnosticMapping>,
    sampleDiagnostics: List<FontDiagnosticSample>,
    rejectedDiagnostics: List<FontDiagnosticClassification>,
    val dashboardClassification: String = "tracked-gap",
    val claimPromotionAllowed: Boolean = false,
) {
    val codes: List<FontDiagnosticCode> = codes.sortedBy { code -> code.code }
    val legacyMappings: List<LegacyFontDiagnosticMapping> = legacyMappings.sortedBy { mapping -> mapping.legacyCode }
    val sampleDiagnostics: List<FontDiagnosticSample> = sampleDiagnostics.sortedBy { sample -> sample.label }
    val rejectedDiagnostics: List<FontDiagnosticClassification> =
        rejectedDiagnostics.sortedBy { classification -> classification.inputCode }
    private val codesByCode: Map<String, FontDiagnosticCode> = this.codes.associateBy { code -> code.code }
    private val legacyByCode: Map<String, LegacyFontDiagnosticMapping> =
        this.legacyMappings.associateBy { mapping -> mapping.legacyCode }

    init {
        require(acceptedNamespaces == FontDiagnosticAcceptedNamespaces) {
            "accepted font diagnostic namespaces are fixed by KFONT M0."
        }
        require(this.codes.isNotEmpty()) { "font diagnostic taxonomy must define at least one code." }
        require(this.codes.size == codesByCode.size) { "font diagnostic codes must be unique." }
        require(this.codes.all { code -> code.namespace in acceptedNamespaces }) {
            "all font diagnostic codes must belong to accepted namespaces."
        }
        require(this.legacyMappings.size == legacyByCode.size) {
            "legacy diagnostic mappings must have unique legacy codes."
        }
        require(this.legacyMappings.all { mapping -> mapping.targetCode in codesByCode }) {
            "legacy diagnostic mappings must target accepted taxonomy codes."
        }
        require(this.sampleDiagnostics.map { sample -> sample.label }.distinct().size == this.sampleDiagnostics.size) {
            "sample diagnostic labels must be unique."
        }
        require(this.sampleDiagnostics.all { sample -> sample.code in codesByCode }) {
            "sample diagnostics must use accepted taxonomy codes."
        }
        require(dashboardClassification == "tracked-gap") {
            "KFONT M0 diagnostic taxonomy evidence must remain tracked-gap."
        }
        require(!claimPromotionAllowed) {
            "Font diagnostic taxonomy cannot promote support claims."
        }
    }

    fun code(code: String): FontDiagnosticCode =
        codesByCode[code] ?: error("unknown font diagnostic taxonomy code: $code")

    fun legacyMapping(legacyCode: String): LegacyFontDiagnosticMapping =
        legacyByCode[legacyCode] ?: error("unknown legacy font diagnostic code: $legacyCode")

    fun classify(inputCode: String): FontDiagnosticClassification {
        val direct = codesByCode[inputCode]
        if (direct != null) {
            return FontDiagnosticClassification(
                inputCode = inputCode,
                accepted = true,
                targetCode = direct.code,
                namespace = direct.namespace,
                classification = direct.claimImpact,
                reason = "accepted-taxonomy-code",
            )
        }

        val legacy = legacyByCode[inputCode]
        if (legacy != null) {
            val target = code(legacy.targetCode)
            return FontDiagnosticClassification(
                inputCode = inputCode,
                accepted = false,
                targetCode = legacy.targetCode,
                namespace = target.namespace,
                classification = legacy.classification,
                reason = "legacy-diagnostic-mapped",
            )
        }

        return FontDiagnosticClassification(
            inputCode = inputCode,
            accepted = false,
            targetCode = null,
            namespace = null,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            reason = "generic-or-unknown-diagnostic",
        )
    }

    fun toCanonicalJson(): CanonicalFontIdentityJson = CanonicalFontIdentityJson(
        buildString {
            append("{")
            appendFontCompactJsonField("schema", FONT_DIAGNOSTIC_TAXONOMY_SCHEMA, comma = true)
            append("schemaVersion".evidenceQuoted()).append(":1,")
            append("dashboardRow".evidenceQuoted()).append(":{")
            appendFontCompactJsonField("name", "pure-kotlin-font diagnostic taxonomy", comma = true)
            appendFontCompactJsonField("classification", dashboardClassification, comma = true)
            appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
            append("},")
            appendStringArrayField("acceptedNamespaces", acceptedNamespaces, comma = true)
            append("codes".evidenceQuoted()).append(":")
            append(codes.joinToString(prefix = "[", postfix = "]", separator = ",") { code -> code.toCanonicalJson() })
            append(",")
            append("legacyMappings".evidenceQuoted()).append(":")
            append(legacyMappings.joinToString(prefix = "[", postfix = "]", separator = ",") { mapping ->
                mapping.toCanonicalJson()
            })
            append(",")
            append("sampleDiagnostics".evidenceQuoted()).append(":")
            append(sampleDiagnostics.joinToString(prefix = "[", postfix = "]", separator = ",") { sample ->
                sample.toCanonicalJson()
            })
            append(",")
            append("rejectedDiagnostics".evidenceQuoted()).append(":")
            append(rejectedDiagnostics.joinToString(prefix = "[", postfix = "]", separator = ",") { classification ->
                classification.toCanonicalJson()
            })
            append(",")
            appendStringArrayField("nonClaims", FontDiagnosticTaxonomyNonClaims, comma = true)
            appendFontCompactJsonField("claimPromotionAllowed", claimPromotionAllowed, comma = false)
            append("}")
        },
    )
}

fun defaultFontDiagnosticTaxonomy(): FontDiagnosticTaxonomy = FontDiagnosticTaxonomy(
    acceptedNamespaces = FontDiagnosticAcceptedNamespaces,
    codes = listOf(
        fontDiagnosticCode(
            code = "font.catalog.duplicate-face",
            namespace = "font.catalog",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.WARNING,
            route = "fallback-catalog",
            requiredFields = listOf("fixtureId", "familyName", "styleName"),
        ),
        fontDiagnosticCode(
            code = "font.catalog.provenance-missing",
            namespace = "font.catalog",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "fallback-catalog",
            requiredFields = listOf("fixtureId", "familyName", "styleName"),
        ),
        fontDiagnosticCode(
            code = "font.source.bytes-unavailable",
            namespace = "font.source",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "font-source",
            requiredFields = listOf("sourceId", "sourceKind"),
        ),
        fontDiagnosticCode(
            code = "font.source.native-engine-request-unsupported",
            namespace = "font.source",
            claimImpact = FontDiagnosticClaimImpact.EXPECTED_UNSUPPORTED,
            severity = FontDiagnosticSeverity.INFO,
            route = "external-drift",
            requiredFields = listOf("externalEngine", "requestedBehavior"),
        ),
        fontDiagnosticCode(
            code = "font.sfnt.required-table-missing",
            namespace = "font.sfnt",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "sfnt-directory",
            requiredFields = listOf("sourceId", "tableTag"),
        ),
        fontDiagnosticCode(
            code = "font.sfnt.optional-table-malformed",
            namespace = "font.sfnt",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.WARNING,
            route = "sfnt-directory",
            requiredFields = listOf("sourceId", "tableTag"),
        ),
        fontDiagnosticCode(
            code = "font.sfnt.cmap-format-unsupported",
            namespace = "font.sfnt",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "sfnt-cmap",
            requiredFields = listOf("sourceId", "format", "platformId", "encodingId"),
        ),
        fontDiagnosticCode(
            code = "font.sfnt.cmap-unusable",
            namespace = "font.sfnt",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "sfnt-cmap",
            requiredFields = listOf("sourceId"),
        ),
        fontDiagnosticCode(
            code = "font.sfnt.table-duplicate",
            namespace = "font.sfnt",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "sfnt-directory",
            requiredFields = listOf("sourceId", "tableTag", "offset", "length", "sourceLength"),
        ),
        fontDiagnosticCode(
            code = "font.sfnt.table-out-of-bounds",
            namespace = "font.sfnt",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "sfnt-directory",
            requiredFields = listOf("sourceId", "tableTag", "offset", "length", "sourceLength"),
        ),
        fontDiagnosticCode(
            code = "font.sfnt.table-overlap",
            namespace = "font.sfnt",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "sfnt-directory",
            requiredFields = listOf("sourceId", "tableTag", "offset", "length", "sourceLength"),
        ),
        fontDiagnosticCode(
            code = "font.scaler.outline-unavailable",
            namespace = "font.scaler",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.ERROR,
            route = "scaler-outline",
            requiredFields = listOf("sourceId", "typefaceId", "glyphId"),
        ),
        fontDiagnosticCode(
            code = "text.shaping.emoji-sequence-unsupported",
            namespace = "text.shaping",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.WARNING,
            route = "shaping-emoji",
            requiredFields = listOf("textRange", "script"),
        ),
        fontDiagnosticCode(
            code = "text.paragraph.line-breaker-dependency-gated",
            namespace = "text.paragraph",
            claimImpact = FontDiagnosticClaimImpact.DEPENDENCY_GATED,
            severity = FontDiagnosticSeverity.WARNING,
            route = "paragraph-layout",
            requiredFields = listOf("textRange", "paragraphRoute"),
        ),
        fontDiagnosticCode(
            code = "text.glyph.cache-key-nondeterministic",
            namespace = "text.glyph",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.WARNING,
            route = "glyph-strike-key",
            requiredFields = listOf("glyphId", "attemptedRoute", "reason"),
        ),
        fontDiagnosticCode(
            code = "text.glyph.LCD-future-research",
            namespace = "text.glyph",
            claimImpact = FontDiagnosticClaimImpact.EXPECTED_UNSUPPORTED,
            severity = FontDiagnosticSeverity.WARNING,
            route = "glyph-strike-key",
            requiredFields = listOf("glyphId", "attemptedRoute", "fallbackRoute"),
        ),
        fontDiagnosticCode(
            code = "glyph.artifact.bitmap-strike-unavailable",
            namespace = "glyph.artifact",
            claimImpact = FontDiagnosticClaimImpact.TRACKED_GAP,
            severity = FontDiagnosticSeverity.WARNING,
            route = "bitmap-glyph-artifact",
            requiredFields = listOf("typefaceId", "glyphId", "strikeKey"),
        ),
        fontDiagnosticCode(
            code = "text.gpu.artifact-unregistered",
            namespace = "text.gpu",
            claimImpact = FontDiagnosticClaimImpact.GPU_GATED,
            severity = FontDiagnosticSeverity.ERROR,
            route = "gpu-text-handoff",
            requiredFields = listOf("artifactId", "generation"),
        ),
        fontDiagnosticCode(
            code = FontTextRefusalCodes.ARTIFACT_UNREGISTERED,
            namespace = "unsupported.text",
            claimImpact = FontDiagnosticClaimImpact.GPU_GATED,
            severity = FontDiagnosticSeverity.ERROR,
            route = "gpu-renderer-text",
            requiredFields = listOf("rendererRoute", "artifactId"),
        ),
    ),
    legacyMappings = listOf(
        LegacyFontDiagnosticMapping(
            legacyCode = "font.native-engine-unavailable",
            targetCode = "font.source.native-engine-request-unsupported",
            classification = FontDiagnosticClaimImpact.EXPECTED_UNSUPPORTED,
        ),
        LegacyFontDiagnosticMapping(
            legacyCode = "font.bitmap-strike-unavailable",
            targetCode = "glyph.artifact.bitmap-strike-unavailable",
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
        ),
        LegacyFontDiagnosticMapping(
            legacyCode = "font.emoji-sequence-shaping-unsupported",
            targetCode = "text.shaping.emoji-sequence-unsupported",
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
        ),
    ),
    sampleDiagnostics = listOf(
        fontDiagnosticSample(
            label = "source-failure",
            code = "font.source.bytes-unavailable",
            subject = "source:generated-malformed-directory",
            route = "font-source",
            severity = FontDiagnosticSeverity.ERROR,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            fields = mapOf(
                "sourceId" to "550e8400-e29b-41d4-a716-446655440200",
                "sourceKind" to FontSourceKind.GENERATED_FIXTURE.serializedName,
            ),
        ),
        fontDiagnosticSample(
            label = "sfnt-failure",
            code = "font.sfnt.required-table-missing",
            subject = "sfnt:missing-head",
            route = "sfnt-directory",
            severity = FontDiagnosticSeverity.ERROR,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            fields = mapOf(
                "sourceId" to "550e8400-e29b-41d4-a716-446655440201",
                "tableTag" to "head",
            ),
        ),
        fontDiagnosticSample(
            label = "sfnt-cmap-refusal",
            code = "font.sfnt.cmap-format-unsupported",
            subject = "sfnt:cmap-format-13",
            route = "sfnt-cmap",
            severity = FontDiagnosticSeverity.ERROR,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            fields = mapOf(
                "sourceId" to "550e8400-e29b-41d4-a716-446655440203",
                "format" to "13",
                "platformId" to "3",
                "encodingId" to "10",
            ),
        ),
        fontDiagnosticSample(
            label = "scaler-failure",
            code = "font.scaler.outline-unavailable",
            subject = "glyph:42",
            route = "scaler-outline",
            severity = FontDiagnosticSeverity.ERROR,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            fields = mapOf(
                "sourceId" to "550e8400-e29b-41d4-a716-446655440202",
                "typefaceId" to "550e8400-e29b-41d4-a716-446655440302",
                "glyphId" to "42",
            ),
        ),
        fontDiagnosticSample(
            label = "shaping-refusal",
            code = "text.shaping.emoji-sequence-unsupported",
            subject = "text-range:0..7",
            route = "shaping-emoji",
            severity = FontDiagnosticSeverity.WARNING,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            fields = mapOf(
                "script" to "Emoji",
                "textRange" to "0..7",
            ),
        ),
        fontDiagnosticSample(
            label = "glyph-strike-key-refusal",
            code = "text.glyph.cache-key-nondeterministic",
            subject = "glyph:49",
            route = "glyph-strike-key",
            severity = FontDiagnosticSeverity.WARNING,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            fields = mapOf(
                "attemptedRoute" to "text.glyph.mask.A8",
                "glyphId" to "49",
                "reason" to "forbidden-live-handle-fields",
                "typefaceId" to "550e8400-e29b-41d4-a716-446655442010",
            ),
        ),
        fontDiagnosticSample(
            label = "gpu-text-route-refusal",
            code = FontTextRefusalCodes.ARTIFACT_UNREGISTERED,
            subject = "artifact:text-a8-atlas",
            route = "gpu-renderer-text",
            severity = FontDiagnosticSeverity.ERROR,
            classification = FontDiagnosticClaimImpact.GPU_GATED,
            fields = mapOf(
                "artifactId" to "text-a8-atlas",
                "rendererRoute" to "a8-atlas",
            ),
        ),
    ),
    rejectedDiagnostics = listOf(
        FontDiagnosticClassification(
            inputCode = "font missing",
            accepted = false,
            targetCode = null,
            namespace = null,
            classification = FontDiagnosticClaimImpact.TRACKED_GAP,
            reason = "generic-or-unknown-diagnostic",
        ),
    ),
)

object FontDiagnosticTaxonomyWriter {
    fun writeTaxonomyJson(
        taxonomy: FontDiagnosticTaxonomy = defaultFontDiagnosticTaxonomy(),
    ): CanonicalFontIdentityJson = CanonicalFontIdentityJson("${taxonomy.toCanonicalJson().value}\n")
}

private fun fontDiagnosticCode(
    code: String,
    namespace: String,
    claimImpact: FontDiagnosticClaimImpact,
    severity: FontDiagnosticSeverity,
    route: String,
    requiredFields: List<String>,
): FontDiagnosticCode = FontDiagnosticCode(
    code = code,
    namespace = namespace,
    claimImpact = claimImpact,
    severity = severity,
    route = route,
    requiredFields = requiredFields,
)

private fun fontDiagnosticSample(
    label: String,
    code: String,
    subject: String,
    route: String,
    severity: FontDiagnosticSeverity,
    classification: FontDiagnosticClaimImpact,
    fields: Map<String, String>,
): FontDiagnosticSample = FontDiagnosticSample(
    label = label,
    code = code,
    subject = subject,
    route = route,
    severity = severity,
    classification = classification,
    fields = fields,
)

private const val FONT_SOURCE_IDENTITY_REPORT_SCHEMA =
    "org.graphiks.kanvas.font.FontSourceIdentityReport.v1"

private const val TYPEFACE_IDENTITY_REPORT_SCHEMA =
    "org.graphiks.kanvas.font.TypefaceIdentityReport.v1"

private const val FONT_IDENTITY_DUMP_BUNDLE_SCHEMA =
    "org.graphiks.kanvas.font.FontIdentityDumpBundle.v1"

private const val FONT_IDENTITY_DUMP_DETERMINISM_SCHEMA =
    "org.graphiks.kanvas.font.FontIdentityDumpDeterminismResult.v1"

private const val FONT_FIXTURE_MANIFEST_SCHEMA =
    "org.graphiks.kanvas.font.FontFixtureManifest.v1"

private const val BUNDLED_FONT_CATALOG_SCHEMA =
    "org.graphiks.kanvas.font.BundledFontCatalog.v1"

private const val FONT_DIAGNOSTIC_TAXONOMY_SCHEMA =
    "org.graphiks.kanvas.font.FontDiagnosticTaxonomy.v1"

private val FontFixtureManifestNonClaims: List<String> = listOf(
    "no-fallback-support-claim",
    "no-glyph-support-claim",
    "no-gpu-support-claim",
    "no-parser-support-claim",
    "no-rendering-support-claim",
    "no-scaler-support-claim",
    "no-shaping-support-claim",
)

private val BundledFontCatalogNonClaims: List<String> = listOf(
    "no-complete-target-support-claim",
    "no-fallback-support-claim",
    "no-platform-font-fallback-claim",
)

private val FontDiagnosticAcceptedNamespaces: List<String> = listOf(
    "font.catalog",
    "font.source",
    "font.sfnt",
    "font.scaler",
    "text.shaping",
    "text.paragraph",
    "text.glyph",
    "glyph.artifact",
    "text.gpu",
    "unsupported.text",
)

private val FontDiagnosticCommonRequiredFields: List<String> = listOf(
    "subject",
    "route",
    "severity",
    "claimImpact",
)

private val FontDiagnosticTaxonomyNonClaims: List<String> = listOf(
    "legacy-gates-remain-open",
    "no-external-engine-product-dependency",
    "no-gpu-route-support-claim",
    "no-rendering-support-claim",
    "no-shaping-support-claim",
    "taxonomy-only-tracked-gap",
)

/**
 * Slant axis used by portable font style matching.
 */
enum class FontSlant {
    /** Upright roman style. */
    UPRIGHT,

    /** Italic style selected by authored italic faces. */
    ITALIC,

    /** Oblique style selected by slanted synthetic or authored faces. */
    OBLIQUE,
}

/**
 * Portable style request and face metadata used by font fallback.
 *
 * @property weight CSS/OpenType-like weight in the inclusive `1..1000` range.
 * @property width CSS/OpenType-like width class in the inclusive `1..9` range,
 * where `5` is normal width.
 * @property slant Upright, italic, or oblique style.
 */
data class FontStyle(
    val weight: Int = 400,
    val width: Int = 5,
    val slant: FontSlant = FontSlant.UPRIGHT,
) {
    init {
        require(weight in 1..1000) { "weight must be in 1..1000." }
        require(width in 1..9) { "width must be in 1..9." }
    }

    companion object {
        /**
         * Derives a conservative style from a font style name.
         *
         * @param styleName Name such as `Regular`, `Bold Italic`, or `Condensed`.
         * @return Parsed style metadata suitable for deterministic fallback matching.
         */
        fun fromStyleName(styleName: String): FontStyle {
            val key = styleName.lowercase()
            val weight = when {
                "thin" in key -> 100
                "extralight" in key || "extra light" in key || "ultralight" in key || "ultra light" in key -> 200
                "light" in key -> 300
                "medium" in key -> 500
                "semibold" in key || "semi bold" in key || "demibold" in key || "demi bold" in key -> 600
                "extrabold" in key || "extra bold" in key || "ultrabold" in key || "ultra bold" in key -> 800
                "black" in key || "heavy" in key -> 900
                "bold" in key -> 700
                else -> 400
            }
            val width = when {
                "ultracondensed" in key || "ultra condensed" in key -> 1
                "extracondensed" in key || "extra condensed" in key -> 2
                "condensed" in key -> 3
                "semicondensed" in key || "semi condensed" in key -> 4
                "semiexpanded" in key || "semi expanded" in key -> 6
                "extraexpanded" in key || "extra expanded" in key -> 8
                "ultraexpanded" in key || "ultra expanded" in key -> 9
                "expanded" in key -> 7
                else -> 5
            }
            val slant = when {
                "italic" in key -> FontSlant.ITALIC
                "oblique" in key -> FontSlant.OBLIQUE
                else -> FontSlant.UPRIGHT
            }
            return FontStyle(weight = weight, width = width, slant = slant)
        }
    }
}

/**
 * Raw font input plus provenance information before SFNT parsing or scaler selection.
 *
 * @property id Stable identifier used by diagnostics, caches, and downstream parsed faces.
 * @property kind Origin category for policy decisions such as sandboxing or system fallback.
 * @property displayName Human-readable name for logs, diagnostics, and tooling.
 * @property bytes Raw font bytes in their original container format.
 */
data class FontSource(
    val id: FontSourceID,
    val kind: FontSourceKind,
    val displayName: String,
    val bytes: ByteArray,
) {
    /**
     * Compares font sources by metadata and byte content instead of JVM array identity.
     *
     * @param other Candidate object to compare with this source.
     * @return True when all metadata fields and raw bytes match.
     */
    override fun equals(other: Any?): Boolean =
        this === other || other is FontSource &&
            id == other.id &&
            kind == other.kind &&
            displayName == other.displayName &&
            bytes.contentEquals(other.bytes)

    /**
     * Produces a hash code that includes the raw byte content.
     *
     * @return Hash value suitable for collections keyed by font source.
     */
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

/**
 * Non-fatal diagnostic reported while loading, parsing, resolving, or preparing a font source.
 *
 * @property sourceId Source that produced the diagnostic.
 * @property message Human-readable diagnostic text.
 * @property causeCode Optional stable machine-readable cause code.
 * @property causeMessage Optional dumpable cause detail without retaining a
 * platform exception object.
 */
data class FontSourceDiagnostic(
    val sourceId: FontSourceID,
    val message: String,
    val causeCode: String? = null,
    val causeMessage: String? = null,
) {
    init {
        require(causeCode == null || causeCode.isStableDiagnosticCode()) {
            "causeCode must be a stable one-line diagnostic code."
        }
    }

    /**
     * Serializes the diagnostic as one stable line for route and provenance evidence.
     *
     * @return Dumpable diagnostic text without retaining platform exception identity.
     */
    fun dump(): String = buildString {
        append(causeCode ?: "font.source.diagnostic")
        append(" sourceId=")
        append(sourceId.value.toHexDashString())
        append(" message=")
        append(message.evidenceQuoted())
        causeMessage?.let {
            append(" causeMessage=")
            append(it.evidenceQuoted())
        }
    }
}

/**
 * Deterministic provenance and parser-supplied evidence for one font source.
 *
 * `font/core` does not parse SFNT/OpenType data here. Callers provide face
 * counts, table tags, and diagnostics after bounded parsing or explicit
 * refusal; this value object only makes those facts stable and dumpable.
 *
 * @property sourceId Stable identity of the source.
 * @property kind Provenance classification for the source.
 * @property displayName Human-readable source name for diagnostics.
 * @property contentSha256 SHA-256 digest of captured source bytes.
 * @property hostDependent True when the source came from host system scanning.
 * @property faceCount Number of faces reported by the caller.
 * @property tableTags Supported SFNT/OpenType table tags, sorted and deduplicated.
 * @property diagnostics Stable diagnostics associated with the source.
 */
data class FontSourceEvidence(
    val sourceId: FontSourceID,
    val kind: FontSourceKind,
    val displayName: String,
    val contentSha256: String,
    val hostDependent: Boolean,
    val faceCount: Int,
    val tableTags: List<String> = emptyList(),
    val diagnostics: List<FontSourceDiagnostic> = emptyList(),
) {
    init {
        require(faceCount >= 0) { "faceCount must be non-negative." }
        require(contentSha256.isLowercaseSha256Hex()) {
            "contentSha256 must be a lowercase hexadecimal SHA-256 digest."
        }
        require(tableTags == tableTags.normalizedTableTags()) {
            "tableTags must be four-character printable ASCII SFNT tags, sorted and deduplicated."
        }
    }

    /**
     * Serializes provenance and parser-supplied facts as one stable line.
     *
     * @return Deterministic evidence suitable for tests and PM dumps.
     */
    fun dump(): String = buildString {
        append("sourceId=")
        append(sourceId.value.toHexDashString())
        append(" kind=")
        append(kind.name)
        append(" displayName=")
        append(displayName.evidenceQuoted())
        append(" contentSha256=")
        append(contentSha256)
        append(" hostDependent=")
        append(hostDependent)
        append(" faceCount=")
        append(faceCount)
        append(" tableTags=")
        append(tableTags.joinToString(prefix = "[", postfix = "]", separator = ","))
        append(" diagnostics=")
        append(diagnostics.joinToString(prefix = "[", postfix = "]", separator = ";") { it.dump() })
    }

    companion object {
        /**
         * Builds evidence from captured source bytes and caller-provided parser facts.
         *
         * @param source Font source with captured bytes.
         * @param faceCount Number of faces reported by parsing or explicit refusal.
         * @param tableTags Table tags reported by parsing.
         * @param diagnostics Source diagnostics to include in the dump.
         * @return Normalized source evidence.
         */
        fun fromSource(
            source: FontSource,
            faceCount: Int,
            tableTags: List<String> = emptyList(),
            diagnostics: List<FontSourceDiagnostic> = emptyList(),
        ): FontSourceEvidence =
            FontSourceEvidence(
                sourceId = source.id,
                kind = source.kind,
                displayName = source.displayName,
                contentSha256 = source.bytes.sha256Hex(),
                hostDependent = source.kind.isHostDependentByDefault(),
                faceCount = faceCount,
                tableTags = tableTags.normalizedTableTags(),
                diagnostics = diagnostics,
            )
    }
}

/**
 * Builds deterministic provenance evidence for this captured font source.
 *
 * @param faceCount Number of faces reported by parsing or explicit refusal.
 * @param tableTags Supported table tags reported by parsing.
 * @param diagnostics Source diagnostics to include in the dump.
 * @return Normalized source evidence without scanning paths or parsing font bytes.
 */
fun FontSource.provenanceEvidence(
    faceCount: Int,
    tableTags: List<String> = emptyList(),
    diagnostics: List<FontSourceDiagnostic> = emptyList(),
): FontSourceEvidence =
    FontSourceEvidence.fromSource(
        source = this,
        faceCount = faceCount,
        tableTags = tableTags,
        diagnostics = diagnostics,
    )

/**
 * Parsed typeface metadata shared by resolvers, fallback catalogs, and scaler implementations.
 *
 * @property id Stable identifier for this parsed face.
 * @property source Raw source that produced the typeface.
 * @property familyName Preferred family name exposed to font matching.
 * @property styleName Preferred style name such as Regular, Italic, or Bold.
 * @property style Portable style metadata used when multiple faces share one family.
 * @property diagnostics Non-fatal diagnostics collected while preparing the typeface.
 */
data class TypefaceData(
    val id: TypefaceID,
    val source: FontSource,
    val familyName: String,
    val styleName: String,
    val style: FontStyle = FontStyle.fromStyleName(styleName),
    val variationAxes: List<TypefaceVariationAxisRange> = emptyList(),
    val variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val namedInstances: List<TypefaceNamedInstance> = emptyList(),
    val variationMetricsSupported: Boolean = true,
    val identityPreimage: TypefaceIdentityPreimage? = null,
    val diagnostics: List<FontSourceDiagnostic> = emptyList(),
) {
    init {
        require(variationAxes.map { axis -> axis.axisTag }.distinct().size == variationAxes.size) {
            "variationAxes must use unique axis tags."
        }
        require(
            variationAxes.map { axis -> axis.axisTag } ==
                variationAxes.map { axis -> axis.axisTag }.sorted(),
        ) {
            "variationAxes must be sorted by axis tag."
        }
        require(variationMetricsSupported || variationAxes.isNotEmpty() || variationCoordinates.isEmpty()) {
            "variationMetricsSupported may be false only for variable faces."
        }
        require(namedInstances.map { instance -> instance.name }.distinct().size == namedInstances.size) {
            "namedInstances must use unique names."
        }
        require(namedInstances.map { instance -> instance.name } == namedInstances.map { instance -> instance.name }.sorted()) {
            "namedInstances must be sorted by name."
        }
        val variationAxisTags = variationAxes.map { axis -> axis.axisTag }.toSet()
        require(namedInstances.all { instance -> instance.coordinates.all { coordinate -> coordinate.axisTag in variationAxisTags } }) {
            "namedInstances coordinates must reference declared variationAxes."
        }
        if (identityPreimage != null) {
            require(identityPreimage.variationCoordinates == variationCoordinates.normalizedVariationCoordinates()) {
                "identityPreimage variationCoordinates must match TypefaceData variationCoordinates."
            }
        }
    }
}

/**
 * Public face entry used by font collections and fallback runs.
 *
 * @property typeface Parsed typeface metadata for the face.
 */
data class FontFace(
    val typeface: TypefaceData,
)

/**
 * Immutable group of font faces available to a resolver.
 *
 * @property faces Ordered faces in matching priority order.
 */
data class FontCollection(
    val faces: List<FontFace> = emptyList(),
)

/**
 * Diagnostic emitted while scanning explicit font roots.
 *
 * The scanner records non-fatal failures instead of throwing so callers can
 * surface missing roots, unreadable directories, or unreadable candidate files
 * in deterministic logs.
 *
 * @property code Stable machine-readable diagnostic code.
 * @property root Root that was being scanned when the diagnostic was emitted.
 * @property path Optional child path related to the failure.
 * @property message Human-readable detail suitable for dumps and test evidence.
 */
data class FontFileScanDiagnostic(
    val code: String,
    val root: Path,
    val path: Path? = null,
    val message: String = "",
) {
    /**
     * Serializes the diagnostic as one stable line.
     *
     * @return Deterministic diagnostic text for reports and assertions.
     */
    fun dump(): String = buildString {
        append(code)
        append(" root=")
        append(root.toAbsolutePath().normalize())
        path?.let {
            append(" path=")
            append(it.toAbsolutePath().normalize())
        }
        if (message.isNotBlank()) {
            append(" message=")
            append(message.evidenceQuoted())
        }
    }
}

/**
 * Result of scanning explicit filesystem roots for font files.
 *
 * @property files Supported font files, resolved to real paths and sorted
 * deterministically by their textual path.
 * @property diagnostics Non-fatal scan diagnostics in discovery order.
 */
data class FontFileScanResult(
    val files: List<Path>,
    val diagnostics: List<FontFileScanDiagnostic> = emptyList(),
) {
    /**
     * Serializes all diagnostics as newline-separated stable lines.
     *
     * @return Empty string when there are no diagnostics.
     */
    fun dumpDiagnostics(): String = diagnostics.joinToString(separator = "\n") { it.dump() }
}

/**
 * Pure Kotlin/JVM scanner for font files under caller-provided roots.
 *
 * The scanner never reads platform system font directories on its own. It only
 * walks roots passed to [scanRoots], accepts `.ttf`, `.otf`, `.ttc`, and `.otc`
 * files case-insensitively, and reports non-fatal root/file failures through
 * [FontFileScanDiagnostic].
 */
object FontFileScanner {
    /**
     * Supported font container extensions, lower-cased and without leading dots.
     */
    val supportedExtensions: Set<String> = setOf("ttf", "otf", "ttc", "otc")

    /**
     * Scans explicit roots for supported font files.
     *
     * @param roots Directories to scan recursively.
     * @param reportSkippedFiles True to emit diagnostics for regular files
     * under the explicit roots that are skipped because they do not use a
     * supported font container extension.
     * @return Deterministically ordered files plus non-fatal diagnostics.
     */
    fun scanRoots(
        roots: List<Path>,
        reportSkippedFiles: Boolean = false,
    ): FontFileScanResult {
        val files = linkedSetOf<Path>()
        val diagnostics = mutableListOf<FontFileScanDiagnostic>()

        for (root in roots) {
            when {
                !Files.exists(root) -> {
                    diagnostics += rootDiagnostic("font.scan.root-missing", root, "Root does not exist.")
                    continue
                }
                !Files.isDirectory(root) -> {
                    diagnostics += rootDiagnostic("font.scan.root-not-directory", root, "Root is not a directory.")
                    continue
                }
            }

            try {
                Files.walk(root).use { stream ->
                    val iterator = stream.iterator()
                    while (iterator.hasNext()) {
                        val candidate = iterator.next()
                        if (!candidate.isSupportedFontFile()) {
                            if (reportSkippedFiles && candidate.isSkippedRegularFile()) {
                                diagnostics += fileDiagnostic(
                                    code = "font.scan.file-skipped",
                                    root = root,
                                    path = candidate,
                                    message = "Unsupported font file extension.",
                                )
                            }
                            continue
                        }
                        val realPath = try {
                            candidate.toRealPath()
                        } catch (e: IOException) {
                            diagnostics += fileDiagnostic(
                                code = "font.scan.file-unreadable",
                                root = root,
                                path = candidate,
                                message = e.message ?: "Font file path could not be resolved.",
                            )
                            continue
                        } catch (e: SecurityException) {
                            diagnostics += fileDiagnostic(
                                code = "font.scan.file-unreadable",
                                root = root,
                                path = candidate,
                                message = e.message ?: "Font file path is not readable.",
                            )
                            continue
                        }
                        files.add(realPath)
                    }
                }
            } catch (e: IOException) {
                diagnostics += rootDiagnostic(
                    code = "font.scan.root-unreadable",
                    root = root,
                    message = e.message ?: "Root could not be scanned.",
                )
            } catch (e: UncheckedIOException) {
                diagnostics += rootDiagnostic(
                    code = "font.scan.root-unreadable",
                    root = root,
                    message = e.message ?: "Root could not be scanned.",
                )
            } catch (e: SecurityException) {
                diagnostics += rootDiagnostic(
                    code = "font.scan.root-unreadable",
                    root = root,
                    message = e.message ?: "Root is not readable.",
                )
            }
        }

        return FontFileScanResult(
            files = files.sortedBy { it.toString() },
            diagnostics = diagnostics,
        )
    }

    private fun Path.isSupportedFontFile(): Boolean {
        val supported = try {
            isRegularFile() && extension.lowercase() in supportedExtensions
        } catch (e: SecurityException) {
            false
        }
        return supported
    }

    private fun Path.isSkippedRegularFile(): Boolean {
        val skipped = try {
            isRegularFile() && extension.lowercase() !in supportedExtensions
        } catch (e: SecurityException) {
            false
        }
        return skipped
    }

    private fun rootDiagnostic(
        code: String,
        root: Path,
        message: String,
    ): FontFileScanDiagnostic =
        FontFileScanDiagnostic(
            code = code,
            root = root,
            message = message,
        )

    private fun fileDiagnostic(
        code: String,
        root: Path,
        path: Path,
        message: String,
    ): FontFileScanDiagnostic =
        FontFileScanDiagnostic(
            code = code,
            root = root,
            path = path,
            message = message,
        )
}

/**
 * Catalog entry for one discovered font file.
 *
 * This does not parse SFNT/OpenType tables. It records deterministic file-level
 * metadata that can feed later parsing, family indexing, or fallback tooling.
 *
 * @property path Real filesystem path for the font file.
 * @property displayName File name suitable for diagnostics and tooling.
 * @property extension Lower-cased supported extension without a leading dot.
 * @property sourceKind Provenance used when converting the entry into a
 * [FontSource].
 */
data class FontFileCatalogEntry(
    val path: Path,
    val displayName: String,
    val extension: String,
    val sourceKind: FontSourceKind = FontSourceKind.FILE,
) {
    companion object {
        /**
         * Creates a catalog entry from a discovered path.
         *
         * @param path Font file path, normally from [FontFileScanResult.files].
         * @return File-level catalog entry with normalized extension metadata.
         */
        fun fromPath(path: Path): FontFileCatalogEntry =
            FontFileCatalogEntry(
                path = path,
                displayName = path.fileName?.toString() ?: path.toString(),
                extension = path.extension.lowercase(),
            )
    }
}

/**
 * Deterministic catalog of font files discovered from explicit roots.
 *
 * @property entries File-level entries in scan order.
 * @property diagnostics Non-fatal diagnostics from the scan that produced the
 * catalog.
 */
data class FontFileCatalog(
    val entries: List<FontFileCatalogEntry>,
    val diagnostics: List<FontFileScanDiagnostic> = emptyList(),
) {
    /**
     * Serializes all scan diagnostics as newline-separated stable lines.
     *
     * @return Empty string when there are no diagnostics.
     */
    fun dumpDiagnostics(): String = diagnostics.joinToString(separator = "\n") { it.dump() }

    companion object {
        /**
         * Scans explicit roots and catalogs supported font files.
         *
         * @param roots Directories to scan recursively.
         * @return File catalog with scan diagnostics.
         */
        fun scanRoots(roots: List<Path>): FontFileCatalog =
            fromScan(FontFileScanner.scanRoots(roots))

        /**
         * Builds a catalog from an existing scan result.
         *
         * @param scan Scan result to convert.
         * @return File catalog preserving scan file order and diagnostics.
         */
        fun fromScan(scan: FontFileScanResult): FontFileCatalog =
            FontFileCatalog(
                entries = scan.files.map(FontFileCatalogEntry::fromPath),
                diagnostics = scan.diagnostics,
            )
    }
}

/**
 * Resolves text and requested family information to concrete font runs.
 */
interface FontResolver {
    /**
     * Resolves a fallback request into ordered font runs.
     *
     * @param request Text, locale, and family preferences to resolve.
     * @return Runs that cover the request text with concrete font faces.
     */
    fun resolve(request: FallbackRequest): List<ResolvedFontRun>
}

/**
 * Answers whether a parsed typeface can draw a Unicode code point.
 *
 * `font/core` does not parse cmap, glyph coverage, OpenType, SFNT, Skia, GPU,
 * or platform font data directly. Callers provide this boundary so fallback
 * resolution can assign faces while real coverage evidence stays in the parser,
 * host registry, or higher-level font stack that owns those facts.
 */
fun interface FontCoverageProvider {
    /**
     * Reports coverage for one typeface/code point pair.
     *
     * @param typefaceId Typeface being considered for the requested code point.
     * @param codePoint Unicode scalar value from the request text.
     * @return True when the typeface is known to support the code point; false
     * when support is absent or unknown.
     */
    fun supports(typefaceId: TypefaceID, codePoint: Int): Boolean
}

/**
 * Concrete catalog-backed resolver for portable font fallback assignments.
 *
 * The resolver walks [FallbackRequest.text] as Unicode code points over the
 * original UTF-16 string, including surrogate pairs, and emits
 * [ResolvedFontRun] ranges in UTF-16 offsets. For each code point it asks
 * [policy] to order catalog families, prefixes that plan with all available
 * [FallbackRequest.preferredFamilies] in request order, then selects the first
 * planned face whose [coverage] reports support. If no planned face reports
 * support, the resolver deliberately assigns the first planned face when one
 * exists so downstream shaping or raster code can surface `.notdef` instead of
 * hiding missing coverage. When the catalog has no usable faces, resolution
 * returns an empty list.
 *
 * @property catalog Family-to-face catalog available for resolution.
 * @property policy Portable family fallback policy used per code point.
 * @property coverage External source of real glyph coverage facts.
 */
class CatalogFontResolver(
    private val catalog: FallbackCatalog,
    private val policy: FontFallbackPolicy,
    private val coverage: FontCoverageProvider,
) : FontResolver {
    data class AxisSelection(
        val selectedVariationCoordinates: List<TypefaceVariationCoordinate>,
        val selectedNamedInstance: String?,
        val supportedAxes: List<String>,
        val unsupportedAxes: List<String>,
        val clampedAxes: List<String>,
        val metricsVariationUnavailable: Boolean,
        val namedInstanceMatched: Boolean,
        val namedInstanceRequestedButUnmatched: Boolean,
    )

    private data class CandidateResolution(
        val familyName: String,
        val face: FontFace,
        val selectedFace: FontFace,
        val trace: FallbackCandidateTrace,
        val covered: Boolean,
        val unsupportedAxisCount: Int,
        val clampedAxisCount: Int,
        val metricsVariationUnavailable: Boolean,
        val namedInstanceMatched: Boolean,
        val namedInstanceRequestedButUnmatched: Boolean,
    )

    /**
     * Resolves text to contiguous face runs using catalog order, fallback policy,
     * and external coverage facts.
     *
     * @param request Text, locale, and preferred families to resolve.
     * @return Contiguous UTF-16 ranges assigned to concrete faces, or an empty
     * list when no catalog face can be planned.
     */
    override fun resolve(request: FallbackRequest): List<ResolvedFontRun> {
        val runs = mutableListOf<ResolvedFontRun>()
        for (decision in resolveDecisions(request)) {
            val face = decision.face
            if (face != null) {
                val previous = runs.lastOrNull()
                if (previous != null && previous.face == face && previous.end == decision.trace.start) {
                    runs[runs.lastIndex] = previous.copy(end = decision.trace.end)
                } else {
                    runs += ResolvedFontRun(start = decision.trace.start, end = decision.trace.end, face = face)
                }
            }
        }
        return runs
    }

    /**
     * Records the per-code-point fallback decisions made by [resolve].
     *
     * @param request Text, locale, and preferred families to resolve.
     * @return Deterministic trace with one decision per Unicode code point.
     */
    fun trace(request: FallbackRequest): FallbackResolutionTrace =
        FallbackResolutionTrace(decisions = resolveDecisions(request).map { it.trace })

    fun evidenceCase(
        fixtureId: String,
        request: FallbackRequest,
        additionalDiagnostics: List<String> = emptyList(),
    ): FallbackEvidenceCase {
        require(fixtureId.isStableManifestToken()) { "fixtureId must be a stable manifest token." }
        val decisions = resolveDecisions(request)
        val traces = decisions.map { it.trace }
        val runs = buildEvidenceRuns(decisions)
        val diagnostics = linkedSetOf<String>()
        traces.mapNotNullTo(diagnostics) { it.diagnosticCode }
        additionalDiagnostics.forEach { diagnostic ->
            require(diagnostic.isStableDiagnosticCode()) { "additionalDiagnostics must use stable one-line codes." }
            diagnostics += diagnostic
        }
        return FallbackEvidenceCase(
            fixtureId = fixtureId,
            request = request,
            decisions = traces,
            runs = runs,
            diagnostics = diagnostics.toList(),
        )
    }

    private fun resolveDecisions(request: FallbackRequest): List<ResolvedFallbackDecision> {
        if (request.text.isEmpty()) return emptyList()

        val decisions = mutableListOf<ResolvedFallbackDecision>()
        var offset = 0
        while (offset < request.text.length) {
            val codePoint = request.text.codePointAt(offset)
            val nextOffset = offset + Character.charCount(codePoint)
            decisions += resolveDecision(
                request = request,
                codePoint = codePoint,
                start = offset,
                end = nextOffset,
            )
            offset = nextOffset
        }
        return decisions
    }

    private fun resolveDecision(
        request: FallbackRequest,
        codePoint: Int,
        start: Int,
        end: Int,
    ): ResolvedFallbackDecision {
        val requestedVariationCoordinates = request.variationCoordinates.normalizedVariationCoordinates()
        val availableFamilyNames = catalog.availableFamilyNames()
        val plan = policy.planFamilyNames(
            availableFamilyNames = availableFamilyNames,
            requestedFamily = request.preferredFamilies.firstOrNull(),
            locales = listOfNotNull(request.locale),
            codePoint = codePoint,
        )
        val candidateFamilies = preferredThenPlannedFamilies(
            preferredFamilies = request.preferredFamilies,
            availableFamilyNames = availableFamilyNames,
            plannedFamilies = plan.orderedFamilies,
        )
        val genericChain = policy.genericFallbackChains[plan.genericFamily].orEmpty()
        val localeChains = plan.locales.flatMap { locale -> policy.localeFallbackChains[locale].orEmpty() }
        val scriptChain = policy.scriptFallbackChains[plan.script].orEmpty()
        var firstPlannedCandidate: CandidateResolution? = null
        val candidates = mutableListOf<CandidateResolution>()
        for (family in candidateFamilies) {
            for (face in catalog.families[family]?.faces.orEmpty().orderedByStyleDistance(request.style)) {
                val axisSelection = face.typeface.selectVariationForFallback(
                    requestedVariationCoordinates = requestedVariationCoordinates,
                    requestedNamedInstance = request.namedInstance,
                )
                val selectedFace = face.withResolvedVariationCoordinates(axisSelection.selectedVariationCoordinates)
                val covered = coverage.supports(face.typeface.id, codePoint)
                val reasons = buildList {
                    if (familyMatchesRequested(family, request.preferredFamilies)) add("requested-family")
                    if (family in localeChains) add("locale-hint")
                    if (family in scriptChain) add("script-fallback")
                    if (plan.script == "emoji" && family in policy.emojiPreferredFamilies) add("emoji-preference")
                    if (family in genericChain) add("generic-family")
                    if (axisSelection.namedInstanceMatched) add("named-instance")
                    if (axisSelection.supportedAxes.isNotEmpty()) add("variation-axis")
                    if (covered) add("glyph-coverage")
                }
                val candidate = CandidateResolution(
                    familyName = family,
                    face = face,
                    selectedFace = selectedFace,
                    trace = FallbackCandidateTrace(
                        familyName = family,
                        typefaceId = selectedFace.typeface.id,
                        covered = covered,
                        styleDistance = face.typeface.style.distanceTo(request.style),
                        reasons = reasons.ifEmpty { listOf("catalog-order") },
                        selectedNamedInstance = axisSelection.selectedNamedInstance,
                        supportedAxes = axisSelection.supportedAxes,
                        selectedVariationCoordinates = axisSelection.selectedVariationCoordinates,
                    ),
                    covered = covered,
                    unsupportedAxisCount = axisSelection.unsupportedAxes.size,
                    clampedAxisCount = axisSelection.clampedAxes.size,
                    metricsVariationUnavailable = axisSelection.metricsVariationUnavailable,
                    namedInstanceMatched = axisSelection.namedInstanceMatched,
                    namedInstanceRequestedButUnmatched = axisSelection.namedInstanceRequestedButUnmatched,
                )
                candidates += candidate
                if (firstPlannedCandidate == null) {
                    firstPlannedCandidate = candidate
                }
            }
        }
        val selectedCovered = candidates.filter { candidate -> candidate.covered }
            .minWithOrNull(
                compareBy<CandidateResolution> { candidate -> candidate.unsupportedAxisCount }
                    .thenBy { candidate -> candidate.clampedAxisCount }
                    .thenBy { candidate -> if (request.namedInstance != null && !candidate.namedInstanceMatched) 1 else 0 }
                    .thenBy { candidate -> if (candidate.metricsVariationUnavailable) 1 else 0 }
                    .thenBy { candidate -> candidate.trace.styleDistance },
            )
        if (selectedCovered != null) {
            return ResolvedFallbackDecision(
                trace = FallbackDecisionTrace(
                    start = start,
                    end = end,
                    codePoint = codePoint,
                    requestedFamilies = request.preferredFamilies,
                    genericFamily = plan.genericFamily,
                    script = plan.script,
                    locales = plan.locales,
                    candidateFamilies = candidateFamilies,
                    candidates = candidates.map { candidate -> candidate.trace }.markSelected(selectedCovered.selectedFace.typeface.id, null),
                    selectedFamily = selectedCovered.familyName,
                    selectedTypefaceId = selectedCovered.selectedFace.typeface.id,
                    requestedVariationCoordinates = requestedVariationCoordinates,
                    selectedNamedInstance = selectedCovered.trace.selectedNamedInstance,
                    selectedVariationCoordinates = selectedCovered.selectedFace.typeface.variationCoordinates,
                    covered = true,
                    diagnosticCode = selectedCovered.fallbackDiagnosticCode(),
                ),
                face = selectedCovered.selectedFace,
            )
        }
        return if (firstPlannedCandidate != null) {
            ResolvedFallbackDecision(
                trace = FallbackDecisionTrace(
                    start = start,
                    end = end,
                    codePoint = codePoint,
                    requestedFamilies = request.preferredFamilies,
                    genericFamily = plan.genericFamily,
                    script = plan.script,
                    locales = plan.locales,
                    candidateFamilies = candidateFamilies,
                    candidates = candidates.map { candidate -> candidate.trace }
                        .markSelected(firstPlannedCandidate.selectedFace.typeface.id, "glyph-missing"),
                    selectedFamily = firstPlannedCandidate.familyName,
                    selectedTypefaceId = firstPlannedCandidate.selectedFace.typeface.id,
                    requestedVariationCoordinates = requestedVariationCoordinates,
                    selectedNamedInstance = firstPlannedCandidate.trace.selectedNamedInstance,
                    selectedVariationCoordinates = firstPlannedCandidate.selectedFace.typeface.variationCoordinates,
                    covered = false,
                    diagnosticCode = firstPlannedCandidate.fallbackDiagnosticCode(defaultDiagnosticCode = "font.fallback-glyph-unavailable"),
                ),
                face = firstPlannedCandidate.selectedFace,
            )
        } else {
            ResolvedFallbackDecision(
                trace = FallbackDecisionTrace(
                    start = start,
                    end = end,
                    codePoint = codePoint,
                    requestedFamilies = request.preferredFamilies,
                    genericFamily = plan.genericFamily,
                    script = plan.script,
                    locales = plan.locales,
                    candidateFamilies = candidateFamilies,
                    candidates = emptyList(),
                    selectedFamily = null,
                    selectedTypefaceId = null,
                    requestedVariationCoordinates = requestedVariationCoordinates,
                    selectedNamedInstance = null,
                    selectedVariationCoordinates = emptyList(),
                    covered = false,
                    diagnosticCode = "font.fallback-family-unavailable",
                ),
                face = null,
            )
        }
    }

    private fun preferredThenPlannedFamilies(
        preferredFamilies: List<String>,
        availableFamilyNames: List<String>,
        plannedFamilies: List<String>,
    ): List<String> {
        val availableByKey = linkedMapOf<String, String>()
        for (family in availableFamilyNames) {
            val key = family.familyKey()
            if (key.isNotEmpty()) availableByKey.putIfAbsent(key, family)
        }

        val ordered = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        fun addAvailableFamily(family: String) {
            val key = family.familyKey()
            val available = availableByKey[key] ?: return
            if (seen.add(available.familyKey())) ordered += available
        }

        preferredFamilies.forEach(::addAvailableFamily)
        plannedFamilies.forEach(::addAvailableFamily)
        return ordered
    }

    private fun buildEvidenceRuns(decisions: List<ResolvedFallbackDecision>): List<ResolvedFontRunEvidence> {
        if (decisions.isEmpty()) return emptyList()
        val runs = mutableListOf<ResolvedFontRunEvidence>()
        var codePointIndex = 0
        while (codePointIndex < decisions.size) {
            val decision = decisions[codePointIndex]
            val face = decision.face
            if (face == null) {
                codePointIndex += 1
                continue
            }
            var endDecisionIndex = codePointIndex
            var utf16End = decision.trace.end
            while (endDecisionIndex + 1 < decisions.size) {
                val next = decisions[endDecisionIndex + 1]
                if (next.face != face || next.trace.start != utf16End) break
                utf16End = next.trace.end
                endDecisionIndex += 1
            }
            runs += ResolvedFontRunEvidence(
                start = decision.trace.start,
                end = utf16End,
                clusterStart = codePointIndex,
                clusterEnd = endDecisionIndex,
                typefaceId = face.typeface.id,
                familyName = face.typeface.familyName.trim(),
                hostDependent = face.typeface.source.kind.isHostDependentByDefault(),
                variationCoordinates = face.typeface.variationCoordinates,
                fallbackReason = decision.trace.primaryFallbackReason(),
                diagnosticCode = decision.trace.shapingDiagnosticCode(),
            )
            codePointIndex = endDecisionIndex + 1
        }
        return runs
    }

    private data class ResolvedFallbackDecision(
        val trace: FallbackDecisionTrace,
        val face: FontFace?,
    )

    private fun CandidateResolution.fallbackDiagnosticCode(
        defaultDiagnosticCode: String? = null,
    ): String? =
        when {
            unsupportedAxisCount > 0 -> "font.variation-axis-unsupported"
            metricsVariationUnavailable -> "font.metrics-variation-unavailable"
            clampedAxisCount > 0 -> "font.fallback.axis-clamped"
            namedInstanceRequestedButUnmatched -> "text.fallback.variation-defaulted"
            else -> defaultDiagnosticCode
        }
}

/**
 * Catalog of family names to collections used during fallback resolution.
 *
 * @property families Mapping from normalized or display family names to font collections.
 */
data class FallbackCatalog(
    val families: Map<String, FontCollection> = emptyMap(),
) {
    /**
     * Returns available family names in deterministic display order.
     *
     * @return Catalog family names sorted case-insensitively with display-name
     * tie-breaking.
     */
    fun availableFamilyNames(): List<String> =
        families.keys.sortedWith(compareBy<String> { it.lowercase() }.thenBy { it })

    companion object {
        /**
         * Builds a fallback catalog from already parsed font faces.
         *
         * This is the pure Kotlin equivalent of the family grouping previously
         * done by the renderer-specific system font manager. The method does
         * not parse SFNT/OpenType data and does not infer styles; callers pass
         * fully prepared [FontFace] objects, usually after a parser module has
         * converted source bytes into [TypefaceData]. Families with blank names
         * are ignored because they cannot be requested or matched
         * deterministically. Duplicate typeface ids inside one family keep the
         * first occurrence so scanner retries or repeated sources do not
         * inflate fallback order.
         *
         * @param faces Parsed faces to group by display family name.
         * @return Catalog keyed by trimmed family names in deterministic family
         * order, while preserving first-seen face order within each family.
         */
        fun fromFaces(faces: Iterable<FontFace>): FallbackCatalog {
            val displayNameByKey = linkedMapOf<String, String>()
            val grouped = linkedMapOf<String, MutableList<FontFace>>()
            val seenByFamily = linkedMapOf<String, MutableSet<TypefaceID>>()

            for (face in faces) {
                val familyName = face.typeface.familyName.trim()
                if (familyName.isEmpty()) continue
                val familyKey = familyName.familyKey()
                val displayName = displayNameByKey.getOrPut(familyKey) { familyName }
                val seen = seenByFamily.getOrPut(familyKey) { linkedSetOf() }
                if (!seen.add(face.typeface.id)) continue
                grouped.getOrPut(displayName) { mutableListOf() } += face
            }

            val orderedFamilies = grouped.keys.sortedWith(compareBy<String> { it.lowercase() }.thenBy { it })
            return FallbackCatalog(
                families = orderedFamilies.associateWith { familyName ->
                    FontCollection(faces = grouped.getValue(familyName).toList())
                },
            )
        }
    }
}

/**
 * Request object for portable font fallback planning.
 *
 * @property availableFamilyNames Families known to the caller, in any order.
 * @property requestedFamily Optional family or generic family requested by
 * style.
 * @property locales Optional BCP 47 locale tags that can influence fallback
 * order.
 * @property codePoint Optional Unicode code point being resolved.
 */
data class FontFallbackPlanRequest(
    val availableFamilyNames: List<String>,
    val requestedFamily: String? = null,
    val locales: List<String> = emptyList(),
    val codePoint: Int? = null,
)

/**
 * Portable family-order fallback plan.
 *
 * @property orderedFamilies Available families ordered by requested family,
 * locale, script, emoji, generic, and final catalog fallback policy.
 * @property requestedFamily Family name requested by the caller, if any.
 * @property genericFamily Normalized generic family used by the plan.
 * @property script Script bucket inferred from [FontFallbackPlanRequest.codePoint].
 * @property locales Normalized language tags used by locale chains.
 */
data class FontFallbackPlan(
    val orderedFamilies: List<String>,
    val requestedFamily: String?,
    val genericFamily: String,
    val script: String,
    val locales: List<String>,
) {
    /**
     * Serializes the plan as one stable line for diagnostics.
     *
     * @return Dumpable fallback planning evidence.
     */
    fun dump(): String = buildString {
        append("requested=")
        append(requestedFamily ?: "none")
        append(" generic=")
        append(genericFamily)
        append(" script=")
        append(script)
        append(" locales=")
        append(locales.joinToString(prefix = "[", postfix = "]"))
        append(" families=")
        append(orderedFamilies.joinToString(prefix = "[", postfix = "]"))
    }
}

/**
 * Portable fallback family policy independent of Skia APIs.
 *
 * The policy contains generic chains such as `sans-serif`, locale chains keyed
 * by BCP 47 language subtags, script chains keyed by coarse script buckets, and
 * emoji-preferred families. Planning only orders families that are present in
 * the caller-provided availability list.
 *
 * @property defaultGeneric Generic family used when no requested generic can be
 * inferred.
 * @property genericFallbackChains Generic family to ordered family chain map.
 * @property scriptFallbackChains Script bucket to ordered family chain map.
 * @property localeFallbackChains Locale language to ordered family chain map.
 * @property emojiPreferredFamilies Families that should be tried early for
 * emoji code points.
 */
data class FontFallbackPolicy(
    val defaultGeneric: String,
    val genericFallbackChains: Map<String, List<String>>,
    val scriptFallbackChains: Map<String, List<String>>,
    val localeFallbackChains: Map<String, List<String>>,
    val emojiPreferredFamilies: List<String>,
) {
    /**
     * Plans fallback family order for explicit inputs.
     *
     * @param availableFamilyNames Families that may be returned.
     * @param requestedFamily Optional requested display or generic family.
     * @param locales Optional BCP 47 locale tags.
     * @param codePoint Optional Unicode code point used for script policy.
     * @return Ordered family plan containing only available family names.
     */
    fun planFamilyNames(
        availableFamilyNames: List<String>,
        requestedFamily: String? = null,
        locales: List<String> = emptyList(),
        codePoint: Int? = null,
    ): FontFallbackPlan =
        plan(
            FontFallbackPlanRequest(
                availableFamilyNames = availableFamilyNames,
                requestedFamily = requestedFamily,
                locales = locales,
                codePoint = codePoint,
            ),
        )

    /**
     * Plans fallback family order for an existing fallback request and catalog.
     *
     * @param request Text and family request from the public resolver surface.
     * @param catalog Available families from the caller.
     * @return Ordered family plan for the request's first code point.
     */
    fun plan(request: FallbackRequest, catalog: FallbackCatalog): FontFallbackPlan =
        planFamilyNames(
            availableFamilyNames = catalog.availableFamilyNames(),
            requestedFamily = request.preferredFamilies.firstOrNull(),
            locales = listOfNotNull(request.locale),
            codePoint = request.text.firstCodePointOrNull(),
        )

    /**
     * Plans fallback family order from a structured request.
     *
     * @param request Explicit fallback planning request.
     * @return Ordered family plan containing only available family names.
     */
    fun plan(request: FontFallbackPlanRequest): FontFallbackPlan {
        val availableByKey = linkedMapOf<String, String>()
        for (family in request.availableFamilyNames) {
            val key = family.familyKey()
            if (key.isNotEmpty()) availableByKey.putIfAbsent(key, family)
        }

        val ordered = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        fun addFamily(name: String?) {
            val key = name?.familyKey().orEmpty()
            if (key.isEmpty()) return
            val available = availableByKey[key] ?: return
            if (seen.add(available.familyKey())) ordered.add(available)
        }
        fun addFamilies(names: Iterable<String>) {
            for (name in names) addFamily(name)
        }

        val normalizedLocales = request.locales.normalizedLocaleLanguages()
        val script = classifyFallbackScript(request.codePoint)
        val generic = genericFor(request.requestedFamily)

        addFamily(request.requestedFamily)
        for (locale in normalizedLocales) addFamilies(localeFallbackChains[locale].orEmpty())
        addFamilies(scriptFallbackChains[script].orEmpty())
        if (script == "emoji") addFamilies(emojiPreferredFamilies)
        addFamilies(genericFallbackChains[generic].orEmpty())
        addFamilies(request.availableFamilyNames)

        return FontFallbackPlan(
            orderedFamilies = ordered,
            requestedFamily = request.requestedFamily,
            genericFamily = generic,
            script = script,
            locales = normalizedLocales,
        )
    }

    private fun genericFor(requestedFamily: String?): String {
        val requestedGeneric = requestedFamily?.toGenericFamilyKey()
        val defaultKey = defaultGeneric.toGenericFamilyKey() ?: defaultGeneric.familyKey()
        return when {
            requestedGeneric != null && requestedGeneric in genericFallbackChains -> requestedGeneric
            defaultKey in genericFallbackChains -> defaultKey
            else -> genericFallbackChains.keys.firstOrNull() ?: defaultKey
        }
    }

    companion object {
        /**
         * Conservative portable fallback policy based on widely deployed
         * families and coarse Unicode script ranges.
         */
        val Default: FontFallbackPolicy = FontFallbackPolicy(
            defaultGeneric = "sans-serif",
            genericFallbackChains = mapOf(
                "sans-serif" to listOf("Noto Sans", "Liberation Sans", "Arial", "DejaVu Sans"),
                "serif" to listOf("Noto Serif", "Liberation Serif", "Times New Roman", "DejaVu Serif"),
                "monospace" to listOf("Noto Sans Mono", "Liberation Mono", "Courier New", "DejaVu Sans Mono"),
            ),
            scriptFallbackChains = mapOf(
                "arabic" to listOf("Noto Naskh Arabic", "Noto Sans Arabic", "Arial"),
                "devanagari" to listOf("Noto Sans Devanagari", "Mangal"),
                "han" to listOf("Noto Sans CJK SC", "Noto Sans CJK JP", "Source Han Sans SC"),
                "hebrew" to listOf("Noto Sans Hebrew", "Arial"),
                "japanese" to listOf("Noto Sans CJK JP", "Yu Gothic", "Hiragino Sans"),
                "korean" to listOf("Noto Sans CJK KR", "Malgun Gothic"),
                "emoji" to listOf("Noto Color Emoji", "Apple Color Emoji", "Segoe UI Emoji"),
                "latin" to listOf("Liberation Sans", "Arial"),
            ),
            localeFallbackChains = mapOf(
                "ar" to listOf("Noto Naskh Arabic", "Noto Sans Arabic"),
                "hi" to listOf("Noto Sans Devanagari", "Mangal"),
                "ja" to listOf("Noto Sans CJK JP", "Yu Gothic"),
                "ko" to listOf("Noto Sans CJK KR", "Malgun Gothic"),
                "zh" to listOf("Noto Sans CJK SC", "Source Han Sans SC", "Noto Sans CJK JP"),
            ),
            emojiPreferredFamilies = listOf("Noto Color Emoji", "Apple Color Emoji", "Segoe UI Emoji"),
        )
    }
}

/**
 * Input to fallback resolution for a span of text.
 *
 * @property text Text whose code points require font coverage.
 * @property locale Optional BCP 47 locale tag for script and regional fallback policy.
 * @property preferredFamilies Ordered family names requested by caller style.
 * @property style Requested style used to choose among faces inside a matching family.
 */
data class FallbackRequest(
    val text: String,
    val locale: String? = null,
    val preferredFamilies: List<String> = emptyList(),
    val style: FontStyle = FontStyle(),
    val variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val namedInstance: String? = null,
)

/**
 * Concrete font assignment for a contiguous UTF-16 range in a fallback request.
 *
 * @property start Inclusive UTF-16 start offset in the request text.
 * @property end Exclusive UTF-16 end offset in the request text.
 * @property face Font face selected for this run.
 */
data class ResolvedFontRun(
    val start: Int,
    val end: Int,
    val face: FontFace,
)

data class ResolvedFontRunEvidence(
    val start: Int,
    val end: Int,
    val clusterStart: Int,
    val clusterEnd: Int,
    val typefaceId: TypefaceID,
    val familyName: String,
    val hostDependent: Boolean,
    val variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val fallbackReason: String?,
    val diagnosticCode: String?,
) {
    init {
        require(start >= 0) { "start must be non-negative." }
        require(end > start) { "end must be greater than start." }
        require(clusterStart >= 0) { "clusterStart must be non-negative." }
        require(clusterEnd >= clusterStart) { "clusterEnd must be greater than or equal to clusterStart." }
        require(familyName.isNotBlank()) { "familyName must not be blank." }
        require(fallbackReason == null || fallbackReason.isStableDiagnosticCode()) {
            "fallbackReason must be a stable one-line token when present."
        }
        require(diagnosticCode == null || diagnosticCode.isStableDiagnosticCode()) {
            "diagnosticCode must be a stable one-line diagnostic code when present."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("textRange", "$start..${end - 1}", comma = true)
        appendFontCompactJsonField("clusterRange", "$clusterStart..$clusterEnd", comma = true)
        appendFontCompactJsonField("typefaceId", typefaceId.value.toHexDashString(), comma = true)
        appendFontCompactJsonField("familyName", familyName, comma = true)
        appendFontCompactJsonField("hostDependent", hostDependent, comma = true)
        if (variationCoordinates.isNotEmpty()) {
            append("variationCoordinates".evidenceQuoted()).append(":").append(variationCoordinates.toCompactJson()).append(",")
        }
        append("fallbackReason".evidenceQuoted()).append(":").append(fallbackReason.toFontJsonNullableString()).append(",")
        append("diagnosticCode".evidenceQuoted()).append(":").append(diagnosticCode.toFontJsonNullableString())
        append("}")
    }
}

data class FallbackDiagnosticRangeEvidence(
    val start: Int,
    val end: Int,
    val clusterStart: Int,
    val clusterEnd: Int,
    val diagnosticCode: String,
) {
    init {
        require(start >= 0) { "start must be non-negative." }
        require(end > start) { "end must be greater than start." }
        require(clusterStart >= 0) { "clusterStart must be non-negative." }
        require(clusterEnd >= clusterStart) { "clusterEnd must be greater than or equal to clusterStart." }
        require(diagnosticCode.isStableDiagnosticCode()) {
            "diagnosticCode must be a stable one-line diagnostic code."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("textRange", "$start..${end - 1}", comma = true)
        appendFontCompactJsonField("clusterRange", "$clusterStart..$clusterEnd", comma = true)
        appendFontCompactJsonField("diagnosticCode", diagnosticCode, comma = false)
        append("}")
    }
}

/**
 * Deterministic trace for the catalog fallback decisions used by [CatalogFontResolver].
 *
 * @property decisions One decision per Unicode code point in UTF-16 input order.
 */
data class FallbackResolutionTrace(
    val decisions: List<FallbackDecisionTrace>,
) {
    /**
     * Serializes all decisions as newline-separated stable lines.
     *
     * @return Empty string when the traced request has no text.
     */
    fun dump(): String = decisions.joinToString(separator = "\n") { it.dump() }
}

/**
 * One catalog fallback decision for one Unicode code point.
 *
 * @property start Inclusive UTF-16 start offset in the request text.
 * @property end Exclusive UTF-16 end offset in the request text.
 * @property codePoint Unicode scalar value being resolved.
 * @property requestedFamilies Caller-preferred families in request order.
 * @property candidateFamilies Available candidate families in actual fallback order.
 * @property selectedFamily Family selected for this code point, when any face
 * could be planned.
 * @property selectedTypefaceId Typeface selected for this code point, when any
 * face could be planned.
 * @property covered True when [FontCoverageProvider] reported direct glyph
 * coverage for the selected face.
 * @property diagnosticCode Stable reason code for fallback refusals or `.notdef`
 * routing, or null when coverage was found.
 */
data class FallbackDecisionTrace(
    val start: Int,
    val end: Int,
    val codePoint: Int,
    val requestedFamilies: List<String>,
    val genericFamily: String = "sans-serif",
    val script: String = "default",
    val locales: List<String> = emptyList(),
    val candidateFamilies: List<String>,
    val candidates: List<FallbackCandidateTrace> = emptyList(),
    val selectedFamily: String?,
    val selectedTypefaceId: TypefaceID?,
    val requestedVariationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val selectedNamedInstance: String? = null,
    val selectedVariationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val covered: Boolean,
    val diagnosticCode: String? = null,
) {
    init {
        require(start >= 0) { "start must be non-negative." }
        require(end > start) { "end must be greater than start." }
        require(Character.isValidCodePoint(codePoint)) { "codePoint must be a valid Unicode scalar value." }
        require(diagnosticCode == null || diagnosticCode.isStableDiagnosticCode()) {
            "diagnosticCode must be a stable one-line diagnostic code."
        }
        require((selectedFamily == null) == (selectedTypefaceId == null)) {
            "selectedFamily and selectedTypefaceId must both be present or both be absent."
        }
        require(genericFamily.isNotBlank()) { "genericFamily must not be blank." }
        require(script.isNotBlank()) { "script must not be blank." }
    }

    /**
     * Serializes the decision as one stable line for fallback evidence.
     *
     * @return Dumpable fallback decision evidence without object identity or live handles.
     */
    fun dump(): String = buildString {
        append("start=")
        append(start)
        append(" end=")
        append(end)
        append(" codePoint=")
        append(codePoint.toCodePointEvidence())
        append(" requestedFamilies=")
        append(requestedFamilies.joinToString(prefix = "[", postfix = "]", separator = ","))
        append(" genericFamily=")
        append(genericFamily)
        append(" script=")
        append(script)
        append(" locales=")
        append(locales.joinToString(prefix = "[", postfix = "]", separator = ","))
        append(" candidateFamilies=")
        append(candidateFamilies.joinToString(prefix = "[", postfix = "]", separator = ","))
        append(" selectedFamily=")
        append(selectedFamily?.evidenceQuoted() ?: "none")
        append(" selectedTypefaceId=")
        append(selectedTypefaceId?.value?.toHexDashString() ?: "none")
        if (requestedVariationCoordinates.isNotEmpty()) {
            append(" requestedVariationCoordinates=")
            append(requestedVariationCoordinates.joinToString(prefix = "[", postfix = "]") { coordinate ->
                "${coordinate.axisTag}=${coordinate.value.toTypefaceJsonNumber()}"
            })
        }
        if (selectedNamedInstance != null) {
            append(" selectedNamedInstance=")
            append(selectedNamedInstance.evidenceQuoted())
        }
        if (selectedVariationCoordinates.isNotEmpty()) {
            append(" selectedVariationCoordinates=")
            append(selectedVariationCoordinates.joinToString(prefix = "[", postfix = "]") { coordinate ->
                "${coordinate.axisTag}=${coordinate.value.toTypefaceJsonNumber()}"
            })
        }
        append(" covered=")
        append(covered)
        append(" diagnostic=")
        append(diagnosticCode ?: "none")
    }
}

data class FallbackCandidateTrace(
    val familyName: String,
    val typefaceId: TypefaceID,
    val covered: Boolean,
    val styleDistance: Int,
    val reasons: List<String>,
    val selectedNamedInstance: String? = null,
    val supportedAxes: List<String> = emptyList(),
    val selectedVariationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val selected: Boolean = false,
    val rejectionReason: String? = null,
) {
    init {
        require(familyName.isNotBlank()) { "familyName must not be blank." }
        require(styleDistance >= 0) { "styleDistance must be non-negative." }
        require(reasons.isNotEmpty()) { "reasons must not be empty." }
        require(reasons.all { it.isStableDiagnosticCode() }) {
            "reasons must use stable one-line tokens."
        }
        require(rejectionReason == null || rejectionReason.isStableDiagnosticCode()) {
            "rejectionReason must use a stable one-line token when present."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("familyName", familyName, comma = true)
        appendFontCompactJsonField("typefaceId", typefaceId.value.toHexDashString(), comma = true)
        appendFontCompactJsonField("covered", covered, comma = true)
        append("styleDistance".evidenceQuoted()).append(":").append(styleDistance).append(",")
        appendFontCompactJsonField("reason", primaryReason(), comma = true)
        appendStringArrayField("reasons", reasons, comma = true)
        if (selectedNamedInstance != null) {
            appendFontCompactJsonField("selectedNamedInstance", selectedNamedInstance, comma = true)
        }
        if (supportedAxes.isNotEmpty()) {
            appendStringArrayField("supportedAxes", supportedAxes, comma = true)
        }
        if (selectedVariationCoordinates.isNotEmpty()) {
            append("selectedVariationCoordinates".evidenceQuoted()).append(":")
            append(selectedVariationCoordinates.toCompactJson())
            append(",")
        }
        appendFontCompactJsonField("selected", selected, comma = true)
        append("rejectionReason".evidenceQuoted()).append(":").append(rejectionReason.toFontJsonNullableString())
        append("}")
    }

    private fun primaryReason(): String =
        reasons.firstOrNull { it != "glyph-coverage" } ?: reasons.first()
}

data class FallbackEvidenceCase(
    val fixtureId: String,
    val request: FallbackRequest,
    val decisions: List<FallbackDecisionTrace>,
    val runs: List<ResolvedFontRunEvidence>,
    val diagnostics: List<String>,
)

data class FallbackEvidenceBundle(
    val fallbackDecisionTraceJson: String,
    val resolvedFontRunsJson: String,
    val fixtureJsonById: Map<String, String>,
)

private data class FallbackRequestSummary(
    val text: String,
    val locale: String?,
    val preferredFamilies: List<String>,
    val style: FontStyle,
    val variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    val namedInstance: String? = null,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("text", text, comma = true)
        append("locale".evidenceQuoted()).append(":").append(locale.toFontJsonNullableString()).append(",")
        appendStringArrayField("preferredFamilies", preferredFamilies, comma = true)
        append("style".evidenceQuoted()).append(":{")
        append("weight".evidenceQuoted()).append(":").append(style.weight).append(",")
        append("width".evidenceQuoted()).append(":").append(style.width).append(",")
        appendFontCompactJsonField("slant", style.slant.typefaceSerializedName, comma = false)
        append("}")
        if (variationCoordinates.isNotEmpty()) {
            append(",")
            append("variationCoordinates".evidenceQuoted()).append(":").append(variationCoordinates.toCompactJson())
        }
        if (namedInstance != null) {
            append(",")
            appendFontCompactJsonField("namedInstance", namedInstance, comma = false)
        }
        append("}")
    }
}

private data class FallbackDecisionCaseDump(
    val fixtureId: String,
    val request: FallbackRequestSummary,
    val decisions: List<FallbackDecisionTrace>,
    val diagnostics: List<String>,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("fixtureId", fixtureId, comma = true)
        append("request".evidenceQuoted()).append(":").append(request.toCanonicalJson()).append(",")
        append("decisions".evidenceQuoted()).append(":")
        append(decisions.mapIndexed { index, decision ->
            decision.toCanonicalJson(clusterStart = index, clusterEnd = index)
        }.joinToString(prefix = "[", postfix = "]", separator = ",") { it })
        append(",")
        appendStringArrayField("diagnostics", diagnostics, comma = false)
        append("}")
    }
}

private data class ResolvedFontRunsCaseDump(
    val fixtureId: String,
    val request: FallbackRequestSummary,
    val runs: List<ResolvedFontRunEvidence>,
    val diagnosticRanges: List<FallbackDiagnosticRangeEvidence>,
    val diagnostics: List<String>,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        appendFontCompactJsonField("fixtureId", fixtureId, comma = true)
        append("request".evidenceQuoted()).append(":").append(request.toCanonicalJson()).append(",")
        append("runs".evidenceQuoted()).append(":")
        append(runs.joinToString(prefix = "[", postfix = "]", separator = ",") { run -> run.toCanonicalJson() })
        append(",")
        append("diagnosticRanges".evidenceQuoted()).append(":")
        append(diagnosticRanges.joinToString(prefix = "[", postfix = "]", separator = ",") { range -> range.toCanonicalJson() })
        append(",")
        appendStringArrayField("diagnostics", diagnostics, comma = false)
        append("}")
    }
}

private data class FallbackFixtureDump(
    val fixtureId: String,
    val request: FallbackRequestSummary,
    val decisions: List<FallbackDecisionTrace>,
    val runs: List<ResolvedFontRunEvidence>,
    val diagnosticRanges: List<FallbackDiagnosticRangeEvidence>,
    val diagnostics: List<String>,
    val nonClaims: List<String>,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        append("\"schemaVersion\":1,")
        appendFontCompactJsonField("dumpId", "fallback-fixture", comma = true)
        appendStringArrayField("ownerTickets", listOf("KFONT-M7-002", "KFONT-M7-003"), comma = true)
        appendFontCompactJsonField("fixtureId", fixtureId, comma = true)
        append("request".evidenceQuoted()).append(":").append(request.toCanonicalJson()).append(",")
        append("decisions".evidenceQuoted()).append(":")
        append(decisions.mapIndexed { index, decision ->
            decision.toCanonicalJson(clusterStart = index, clusterEnd = index)
        }.joinToString(prefix = "[", postfix = "]", separator = ",") { it })
        append(",")
        append("runs".evidenceQuoted()).append(":")
        append(runs.joinToString(prefix = "[", postfix = "]", separator = ",") { run -> run.toCanonicalJson() })
        append(",")
        append("diagnosticRanges".evidenceQuoted()).append(":")
        append(diagnosticRanges.joinToString(prefix = "[", postfix = "]", separator = ",") { range -> range.toCanonicalJson() })
        append(",")
        appendStringArrayField("diagnostics", diagnostics, comma = true)
        appendStringArrayField("nonClaims", nonClaims, comma = false)
        append("}")
    }
}

fun FallbackDecisionTrace.toCanonicalJson(
    clusterStart: Int,
    clusterEnd: Int = clusterStart,
): String = buildString {
    append("{")
    appendFontCompactJsonField("textRange", "$start..${end - 1}", comma = true)
    appendFontCompactJsonField("clusterRange", "$clusterStart..$clusterEnd", comma = true)
    appendFontCompactJsonField("codePoint", codePoint.toCodePointEvidence(), comma = true)
    appendStringArrayField("requestedFamilies", requestedFamilies, comma = true)
    appendFontCompactJsonField("genericFamily", genericFamily, comma = true)
    appendFontCompactJsonField("script", script, comma = true)
    appendStringArrayField("locales", locales, comma = true)
    appendStringArrayField("candidateFamilies", candidateFamilies, comma = true)
    append("candidates".evidenceQuoted()).append(":")
    append(candidates.joinToString(prefix = "[", postfix = "]", separator = ",") { candidate -> candidate.toCanonicalJson() })
    append(",")
    append("selectedFamily".evidenceQuoted()).append(":").append(selectedFamily.toFontJsonNullableString()).append(",")
    append("selectedTypefaceId".evidenceQuoted()).append(":").append(selectedTypefaceId?.value?.toHexDashString()?.evidenceQuoted() ?: "null").append(",")
    if (requestedVariationCoordinates.isNotEmpty()) {
        append("requestedVariationCoordinates".evidenceQuoted()).append(":")
        append(requestedVariationCoordinates.toCompactJson())
        append(",")
    }
    if (selectedNamedInstance != null) {
        appendFontCompactJsonField("selectedNamedInstance", selectedNamedInstance, comma = true)
    }
    if (selectedVariationCoordinates.isNotEmpty()) {
        append("selectedVariationCoordinates".evidenceQuoted()).append(":")
        append(selectedVariationCoordinates.toCompactJson())
        append(",")
    }
    appendFontCompactJsonField("covered", covered, comma = true)
    append("diagnosticCode".evidenceQuoted()).append(":").append(diagnosticCode.toFontJsonNullableString())
    append("}")
}

private fun buildFallbackDiagnosticRanges(
    decisions: List<FallbackDecisionTrace>,
): List<FallbackDiagnosticRangeEvidence> =
    decisions.flatMapIndexed { clusterIndex, decision ->
        buildList {
            decision.diagnosticCode?.let { diagnosticCode ->
                add(
                    FallbackDiagnosticRangeEvidence(
                        start = decision.start,
                        end = decision.end,
                        clusterStart = clusterIndex,
                        clusterEnd = clusterIndex,
                        diagnosticCode = diagnosticCode,
                    ),
                )
            }
            decision.shapingDiagnosticCode()?.let { diagnosticCode ->
                add(
                    FallbackDiagnosticRangeEvidence(
                        start = decision.start,
                        end = decision.end,
                        clusterStart = clusterIndex,
                        clusterEnd = clusterIndex,
                        diagnosticCode = diagnosticCode,
                    ),
                )
            }
        }.distinct()
    }

object FallbackEvidenceWriter {
    fun writeBundle(
        cases: List<FallbackEvidenceCase>,
    ): FallbackEvidenceBundle {
        val fixtureNonClaims = listOf(
            "no-cluster-safe-fallback-claim",
            "no-complete-target-support-claim",
            "no-emoji-rendering-claim",
            "no-platform-font-fallback-claim",
            "no-shaping-engine-claim",
        )
        val orderedCases = cases.sortedBy { it.fixtureId }
        val traceCases = orderedCases.map { case ->
            FallbackDecisionCaseDump(
                fixtureId = case.fixtureId,
                request = FallbackRequestSummary(
                    text = case.request.text,
                    locale = case.request.locale,
                    preferredFamilies = case.request.preferredFamilies,
                    style = case.request.style,
                    variationCoordinates = case.request.variationCoordinates,
                    namedInstance = case.request.namedInstance,
                ),
                decisions = case.decisions,
                diagnostics = case.diagnostics.sorted(),
            )
        }
        val runCases = orderedCases.map { case ->
            ResolvedFontRunsCaseDump(
                fixtureId = case.fixtureId,
                request = FallbackRequestSummary(
                    text = case.request.text,
                    locale = case.request.locale,
                    preferredFamilies = case.request.preferredFamilies,
                    style = case.request.style,
                    variationCoordinates = case.request.variationCoordinates,
                    namedInstance = case.request.namedInstance,
                ),
                runs = case.runs,
                diagnosticRanges = buildFallbackDiagnosticRanges(case.decisions),
                diagnostics = case.diagnostics.sorted(),
            )
        }
        val fixtureJsonById = orderedCases.associate { case ->
            case.fixtureId to FallbackFixtureDump(
                fixtureId = case.fixtureId,
                request = FallbackRequestSummary(
                    text = case.request.text,
                    locale = case.request.locale,
                    preferredFamilies = case.request.preferredFamilies,
                    style = case.request.style,
                    variationCoordinates = case.request.variationCoordinates,
                    namedInstance = case.request.namedInstance,
                ),
                decisions = case.decisions,
                runs = case.runs,
                diagnosticRanges = buildFallbackDiagnosticRanges(case.decisions),
                diagnostics = case.diagnostics.sorted(),
                nonClaims = fixtureNonClaims,
            ).toCanonicalJson()
        }
        return FallbackEvidenceBundle(
            fallbackDecisionTraceJson = buildString {
                append("{\n")
                append("  \"schemaVersion\": 1,\n")
                append("  \"dumpId\": \"fallback-decision-trace\",\n")
                append("  \"ownerTickets\": [\"KFONT-M7-002\", \"KFONT-M7-003\"],\n")
                append("  \"cases\": [\n")
                append(traceCases.joinToString(",\n") { dump -> dump.toCanonicalJson().prependIndent("    ") })
                append("\n  ],\n")
                append("  \"nonClaims\": [\"no-complete-target-support-claim\", \"no-cluster-safe-fallback-claim\", \"no-platform-font-fallback-claim\", \"no-emoji-rendering-claim\"]\n")
                append("}")
            },
            resolvedFontRunsJson = buildString {
                append("{\n")
                append("  \"schemaVersion\": 1,\n")
                append("  \"dumpId\": \"resolved-font-runs\",\n")
                append("  \"ownerTickets\": [\"KFONT-M7-002\", \"KFONT-M7-003\"],\n")
                append("  \"cases\": [\n")
                append(runCases.joinToString(",\n") { dump -> dump.toCanonicalJson().prependIndent("    ") })
                append("\n  ],\n")
                append("  \"nonClaims\": [\"no-complete-target-support-claim\", \"no-cluster-safe-fallback-claim\", \"no-platform-font-fallback-claim\", \"no-shaping-engine-claim\"]\n")
                append("}")
            },
            fixtureJsonById = fixtureJsonById,
        )
    }
}

fun defaultFallbackEvidenceCases(): List<FallbackEvidenceCase> =
    listOf(
        fallbackAxisClampedEvidenceCase(),
        fallbackAxisMissingEvidenceCase(),
        fallbackFamilyGenericEvidenceCase(),
        fallbackScriptArabicEvidenceCase(),
        fallbackLocaleSerbianEvidenceCase(),
        fallbackMetricsVariationMissingEvidenceCase(),
        fallbackNamedInstanceEvidenceCase(),
        fallbackMultiAxisEvidenceCase(),
        fallbackEmojiPreferenceEvidenceCase(),
        fallbackMissingGlyphEvidenceCase(),
        fallbackFamilyUnavailableEvidenceCase(),
        fallbackVariableCff2EvidenceCase(),
    )

fun defaultFallbackClusterEvidenceCases(): List<FallbackEvidenceCase> =
    listOf(
        fallbackClusterArabicMarkEvidenceCase(),
        fallbackClusterCjkVariationSelectorEvidenceCase(),
        fallbackClusterDevanagariEvidenceCase(),
        fallbackClusterEmojiZwjEvidenceCase(),
        fallbackClusterLatinMarkEvidenceCase(),
        fallbackClusterNegativeSplitEvidenceCase(),
        fallbackClusterSkinToneEvidenceCase(),
        fallbackClusterThaiEvidenceCase(),
        fallbackClusterVs15Vs16EvidenceCase(),
    ).sortedBy { case -> case.fixtureId }

fun defaultFallbackEvidenceBundle(): FallbackEvidenceBundle =
    FallbackEvidenceWriter.writeBundle(cases = defaultFallbackEvidenceCases())

fun defaultFallbackClusterEvidenceBundle(): FallbackEvidenceBundle =
    FallbackEvidenceWriter.writeBundle(cases = defaultFallbackClusterEvidenceCases())

private fun List<String>.normalizedDiagnosticFieldNames(): List<String> {
    for (fieldName in this) {
        require(fieldName.isStableDiagnosticFieldName()) {
            "diagnostic field names must be stable one-line field identifiers."
        }
    }
    return distinct().sorted()
}

private fun normalizedDiagnosticFieldMap(fields: Map<String, String>): Map<String, String> {
    for ((fieldName, value) in fields) {
        require(fieldName.isStableDiagnosticFieldName()) {
            "diagnostic field names must be stable one-line field identifiers."
        }
        require(value.isStableManifestString()) {
            "diagnostic field values must be stable one-line strings."
        }
    }
    return fields.toSortedMap()
}

private fun String.isStableDiagnosticFieldName(): Boolean =
    isNotBlank() && all { character ->
        character in 'A'..'Z' ||
            character in 'a'..'z' ||
            character in '0'..'9' ||
            character == '-'
    }

private fun FontDiagnosticClaimImpact.isNonClaimImpact(): Boolean =
    this != FontDiagnosticClaimImpact.TARGET_SUPPORTED &&
        this != FontDiagnosticClaimImpact.CURRENT_SUPPORTED

private fun String.familyKey(): String = trim().lowercase()

private fun FontSourceKind.isHostDependentByDefault(): Boolean =
    this == FontSourceKind.SYSTEM || this == FontSourceKind.SYSTEM_SCANNED

val FontSourceKind.serializedName: String
    get() = when (this) {
        FontSourceKind.BUNDLED_FIXTURE -> "BundledFontSource"
        FontSourceKind.GENERATED_FIXTURE -> "GeneratedFixtureFontSource"
        FontSourceKind.USER_DATA -> "UserDataFontSource"
        FontSourceKind.USER_STREAM -> "UserStreamFontSource"
        FontSourceKind.USER_FILE -> "UserFileFontSource"
        FontSourceKind.SYSTEM_SCANNED -> "SystemScannedFontSource"
        FontSourceKind.MEMORY -> "UserDataFontSource"
        FontSourceKind.SYSTEM -> "SystemScannedFontSource"
        FontSourceKind.FILE -> "UserFileFontSource"
        FontSourceKind.RESOURCE -> "UserDataFontSource"
    }

private fun List<String>.normalizedTableTags(): List<String> {
    for (tag in this) {
        require(tag.isStableSfntTableTag()) {
            "tableTags must contain four-character printable ASCII SFNT tags."
        }
    }
    return distinct().sorted()
}

private fun List<String>.normalizedManifestStrings(fieldName: String): List<String> {
    for (value in this) {
        require(value.isStableManifestString()) { "$fieldName must contain stable single-line strings." }
    }
    return distinct().sorted()
}

private fun List<TypefaceVariationCoordinate>.normalizedVariationCoordinates(): List<TypefaceVariationCoordinate> {
    val sorted = sortedBy { coordinate -> coordinate.axisTag }
    require(sorted.map { coordinate -> coordinate.axisTag }.distinct().size == sorted.size) {
        "variation coordinates must have unique axis tags."
    }
    return sorted
}

private fun List<TypefaceVariationCoordinate>.mergeVariationCoordinates(
    overrides: List<TypefaceVariationCoordinate>,
): List<TypefaceVariationCoordinate> {
    val merged = linkedMapOf<String, TypefaceVariationCoordinate>()
    normalizedVariationCoordinates().forEach { coordinate -> merged[coordinate.axisTag] = coordinate }
    overrides.normalizedVariationCoordinates().forEach { coordinate -> merged[coordinate.axisTag] = coordinate }
    return merged.values.toList().normalizedVariationCoordinates()
}

private fun List<TypefaceVariationCoordinate>.toCompactJson(): String =
    normalizedVariationCoordinates().joinToString(prefix = "[", postfix = "]", separator = ",") { coordinate ->
        buildString {
            append("{")
            appendFontCompactJsonField("axisTag", coordinate.axisTag, comma = true)
            append("value".evidenceQuoted()).append(":").append(coordinate.value.toTypefaceJsonNumber())
            append("}")
        }
    }

private fun String.isStableSfntTableTag(): Boolean =
    length == 4 && all { character -> character.code in 0x20..0x7E }

private fun String.isStableManifestToken(): Boolean =
    isNotBlank() && all { character -> character.code in 0x21..0x7E }

private fun String.isStableManifestString(): Boolean =
    isNotBlank() && all { character -> character.code in 0x20..0x7E }

private val FontSlant.typefaceSerializedName: String
    get() = when (this) {
        FontSlant.UPRIGHT -> "upright"
        FontSlant.ITALIC -> "italic"
        FontSlant.OBLIQUE -> "oblique"
    }

private fun String.isStableDiagnosticCode(): Boolean =
    isNotEmpty() && all { character -> character.code in 0x21..0x7E }

private fun String.isLowercaseSha256Hex(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }

private fun Double.toTypefaceJsonNumber(): String {
    return normalizedTypefaceVariationValue().toString()
}

private fun Double.normalizedTypefaceVariationValue(): Double =
    if (this == 0.0) 0.0 else this

private fun ByteArray.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(this)
    val chars = CharArray(digest.size * 2)
    for (index in digest.indices) {
        val value = digest[index].toInt() and 0xFF
        chars[index * 2] = HexDigits[value ushr 4]
        chars[index * 2 + 1] = HexDigits[value and 0x0F]
    }
    return chars.concatToString()
}

private fun stableUuidFromSha256(preimage: String): Uuid {
    val digest = MessageDigest.getInstance("SHA-256").digest(preimage.toByteArray(Charsets.UTF_8))
    val uuidBytes = digest.copyOfRange(0, 16)
    uuidBytes[6] = ((uuidBytes[6].toInt() and 0x0f) or 0x50).toByte()
    uuidBytes[8] = ((uuidBytes[8].toInt() and 0x3f) or 0x80).toByte()
    return Uuid.parse(uuidBytes.toUuidString())
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

private fun String?.toFontJsonNullableString(): String =
    this?.evidenceQuoted() ?: "null"

private fun StringBuilder.appendFontJsonField(
    name: String,
    value: String,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(name.evidenceQuoted()).append(": ").append(value.evidenceQuoted())
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendFontJsonField(
    name: String,
    value: Int,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(name.evidenceQuoted()).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendFontJsonField(
    name: String,
    value: Boolean,
    indent: String,
    comma: Boolean,
) {
    append(indent).append(name.evidenceQuoted()).append(": ").append(value)
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendFontCompactJsonField(
    name: String,
    value: String,
    comma: Boolean,
) {
    append(name.evidenceQuoted())
    append(":")
    append(value.evidenceQuoted())
    if (comma) append(",")
}

private fun StringBuilder.appendFontCompactJsonField(
    name: String,
    value: Boolean,
    comma: Boolean,
) {
    append(name.evidenceQuoted())
    append(":")
    append(value)
    if (comma) append(",")
}

private fun StringBuilder.appendStringArrayField(
    name: String,
    values: List<String>,
    comma: Boolean,
) {
    append(name.evidenceQuoted())
    append(":")
    append(values.joinToString(prefix = "[", postfix = "]", separator = ",") { value -> value.evidenceQuoted() })
    if (comma) append(",")
}

private fun List<String>.normalizedCatalogFacts(fieldName: String): List<String> {
    require(all { value -> value.isNotBlank() && value.isStableManifestString() }) {
        "$fieldName must use stable non-blank one-line strings."
    }
    return distinct().sorted()
}

private fun List<FallbackCandidateTrace>.markSelected(
    selectedTypefaceId: TypefaceID,
    uncoveredRejectionReason: String?,
): List<FallbackCandidateTrace> =
    map { candidate ->
        when {
            candidate.typefaceId == selectedTypefaceId -> candidate.copy(selected = true, rejectionReason = null)
            candidate.covered -> candidate.copy(rejectionReason = "earlier-candidate-selected")
            else -> candidate.copy(rejectionReason = uncoveredRejectionReason ?: "glyph-missing")
        }
    }

private fun TypefaceData.selectVariationForFallback(
    requestedVariationCoordinates: List<TypefaceVariationCoordinate>,
    requestedNamedInstance: String? = null,
): CatalogFontResolver.AxisSelection {
    val normalizedRequestedCoordinates = requestedVariationCoordinates.normalizedVariationCoordinates()
    val matchedNamedInstance = requestedNamedInstance?.let { namedInstance ->
        namedInstances.firstOrNull { instance -> instance.name == namedInstance }
    }
    val effectiveRequestedCoordinates = matchedNamedInstance
        ?.coordinates
        ?.mergeVariationCoordinates(overrides = normalizedRequestedCoordinates)
        ?: normalizedRequestedCoordinates
    if (effectiveRequestedCoordinates.isEmpty()) {
        return CatalogFontResolver.AxisSelection(
            selectedVariationCoordinates = variationCoordinates,
            selectedNamedInstance = null,
            supportedAxes = emptyList(),
            unsupportedAxes = emptyList(),
            clampedAxes = emptyList(),
            metricsVariationUnavailable = false,
            namedInstanceMatched = false,
            namedInstanceRequestedButUnmatched = requestedNamedInstance != null,
        )
    }
    val axesByTag = variationAxes.associateBy { axis -> axis.axisTag }
    val supportedAxes = mutableListOf<String>()
    val unsupportedAxes = mutableListOf<String>()
    val clampedAxes = mutableListOf<String>()
    val selectedCoordinates = mutableListOf<TypefaceVariationCoordinate>()
    for (coordinate in effectiveRequestedCoordinates) {
        val axis = axesByTag[coordinate.axisTag]
        if (axis == null) {
            unsupportedAxes += coordinate.axisTag
            continue
        }
        supportedAxes += coordinate.axisTag
        val selectedValue = axis.clamp(coordinate.value)
        if (selectedValue != coordinate.value.normalizedTypefaceVariationValue()) {
            clampedAxes += coordinate.axisTag
        }
        selectedCoordinates += TypefaceVariationCoordinate(axisTag = coordinate.axisTag, value = selectedValue)
    }
    val normalizedSelectedCoordinates = selectedCoordinates.normalizedVariationCoordinates()
    val selectedNamedInstance = matchedNamedInstance
        ?.takeIf { instance -> instance.coordinates == normalizedSelectedCoordinates }
        ?.name
    return CatalogFontResolver.AxisSelection(
        selectedVariationCoordinates = normalizedSelectedCoordinates,
        selectedNamedInstance = selectedNamedInstance,
        supportedAxes = supportedAxes.distinct().sorted(),
        unsupportedAxes = unsupportedAxes.distinct().sorted(),
        clampedAxes = clampedAxes.distinct().sorted(),
        metricsVariationUnavailable = supportedAxes.isNotEmpty() && !variationMetricsSupported,
        namedInstanceMatched = selectedNamedInstance != null,
        namedInstanceRequestedButUnmatched = requestedNamedInstance != null && selectedNamedInstance == null,
    )
}

private fun FontFace.withResolvedVariationCoordinates(
    selectedVariationCoordinates: List<TypefaceVariationCoordinate>,
): FontFace {
    val normalizedCoordinates = selectedVariationCoordinates.normalizedVariationCoordinates()
    if (normalizedCoordinates == typeface.variationCoordinates.normalizedVariationCoordinates()) return this
    val preimage = typeface.identityPreimage ?: return this
    val updatedPreimage = typefaceIdentityPreimage(
        sourceId = preimage.sourceId,
        collectionIndex = preimage.collectionIndex,
        postScriptName = preimage.postScriptName,
        familyName = preimage.familyName,
        styleName = preimage.styleName,
        style = preimage.style,
        outlineFormat = preimage.outlineFormat,
        selectedCMap = preimage.selectedCMap,
        scalerMode = preimage.scalerMode,
        variationCoordinates = normalizedCoordinates,
        palette = preimage.palette,
        fallbackCatalogGeneration = preimage.fallbackCatalogGeneration,
        tableTags = preimage.tableTags,
        diagnostics = preimage.diagnostics,
    )
    return copy(
        typeface = typeface.copy(
            id = updatedPreimage.typefaceId(),
            variationCoordinates = normalizedCoordinates,
            identityPreimage = updatedPreimage,
        ),
    )
}

private fun familyMatchesRequested(
    familyName: String,
    requestedFamilies: List<String>,
): Boolean {
    val key = familyName.familyKey()
    return requestedFamilies.any { requested -> requested.familyKey() == key }
}

private fun FallbackDecisionTrace.primaryFallbackReason(): String? =
    when {
        covered && candidates.any { candidate -> candidate.selected && "emoji-preference" in candidate.reasons } -> "emoji-preference"
        covered && candidates.any { candidate -> candidate.selected && "locale-hint" in candidate.reasons } -> "locale-hint"
        covered && candidates.any { candidate -> candidate.selected && "script-fallback" in candidate.reasons } -> "script-fallback"
        covered && candidates.any { candidate -> candidate.selected && "generic-family" in candidate.reasons } -> "generic-family"
        !covered && diagnosticCode == "font.fallback-glyph-unavailable" -> "missing-glyph"
        !covered && diagnosticCode == "font.fallback-family-unavailable" -> "family-unavailable"
        else -> null
    }

private fun FallbackDecisionTrace.shapingDiagnosticCode(): String? =
    when (diagnosticCode) {
        "font.fallback-family-unavailable", "font.fallback-glyph-unavailable" -> "text.shaping.fallback-missing"
        else -> null
    }

private fun fallbackFamilyGenericEvidenceCase(): FallbackEvidenceCase {
    val fallbackSerif = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440900",
        familyName = "Fallback Serif",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Fallback Serif" to FontCollection(listOf(fallbackSerif)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf(
                "serif" to listOf("Fallback Serif"),
                "sans-serif" to emptyList(),
                "monospace" to emptyList(),
            ),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(fallbackSerif.typeface.id to setOf('x'.code)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-family-generic",
        request = FallbackRequest(text = "x", preferredFamilies = listOf("serif")),
    )
}

private fun fallbackClusterArabicMarkEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440910",
        familyName = "Alpha Sans",
    )
    val arabic = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440911",
        familyName = "Arabic Naskh",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Arabic Naskh" to FontCollection(listOf(arabic)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "Arabic Naskh")),
            scriptFallbackChains = mapOf("arabic" to listOf("Arabic Naskh")),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(arabic.typeface.id to setOf(0x0627, 0x0651)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-arabic-mark",
        request = FallbackRequest(text = "\u0627\u0651", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackClusterCjkVariationSelectorEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440912",
        familyName = "Alpha Sans",
    )
    val cjk = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440913",
        familyName = "CJK Sans",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "CJK Sans" to FontCollection(listOf(cjk)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "CJK Sans")),
            scriptFallbackChains = mapOf("cjk" to listOf("CJK Sans")),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(cjk.typeface.id to setOf(0x4E00, 0x3003, 0xFE0F)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-cjk-vs",
        request = FallbackRequest(text = "\u4E00\u3003\uFE0F", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackClusterDevanagariEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440914",
        familyName = "Alpha Sans",
    )
    val devanagari = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440915",
        familyName = "Devanagari Sans",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Devanagari Sans" to FontCollection(listOf(devanagari)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "Devanagari Sans")),
            scriptFallbackChains = mapOf("devanagari" to listOf("Devanagari Sans")),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(devanagari.typeface.id to setOf(0x0915, 0x094D, 0x0937, 0x093E)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-devanagari",
        request = FallbackRequest(text = "\u0915\u094D\u0937\u093E", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackClusterEmojiZwjEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440916",
        familyName = "Alpha Sans",
    )
    val emoji = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440917",
        familyName = "Noto Color Emoji",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Noto Color Emoji" to FontCollection(listOf(emoji)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "Noto Color Emoji")),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = listOf("Noto Color Emoji"),
        ),
        coverage = fallbackTestCoverage(emoji.typeface.id to setOf(0x1F466, 0x1F3FB, 0x200D)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-emoji-zwj",
        request = FallbackRequest(text = "\uD83D\uDC66\uD83C\uDFFB\u200D\uD83D\uDC66", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackClusterLatinMarkEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440918",
        familyName = "Alpha Sans",
    )
    val accent = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440919",
        familyName = "Accent Sans",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Accent Sans" to FontCollection(listOf(accent)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "Accent Sans")),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(accent.typeface.id to setOf('A'.code, 0x0301)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-latin-mark",
        request = FallbackRequest(text = "A\u0301", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackClusterNegativeSplitEvidenceCase(): FallbackEvidenceCase {
    val text = "\uD83D\uDC66\uD83C\uDFFB\u200D\uD83D\uDC66"
    val request = FallbackRequest(text = text, preferredFamilies = listOf("Alpha Sans"))
    val decisions = mutableListOf<FallbackDecisionTrace>()
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val nextOffset = offset + Character.charCount(codePoint)
        decisions += FallbackDecisionTrace(
            start = offset,
            end = nextOffset,
            codePoint = codePoint,
            requestedFamilies = request.preferredFamilies,
            genericFamily = "sans-serif",
            script = "emoji",
            locales = emptyList(),
            candidateFamilies = listOf("Alpha Sans", "Noto Color Emoji"),
            candidates = emptyList(),
            selectedFamily = null,
            selectedTypefaceId = null,
            covered = false,
            diagnosticCode = "text.fallback.cluster-split-forbidden",
        )
        offset = nextOffset
    }
    return FallbackEvidenceCase(
        fixtureId = "fallback-cluster-negative-split",
        request = request,
        decisions = decisions,
        runs = emptyList(),
        diagnostics = listOf(
            "text.fallback.cluster-split-forbidden",
            "text.fallback.emoji-fallback-unavailable",
            "text.shaping.emoji-sequence-unsupported",
            "text.shaping.fallback-missing",
        ),
    )
}

private fun fallbackClusterSkinToneEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440922",
        familyName = "Alpha Sans",
    )
    val emoji = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440923",
        familyName = "Noto Color Emoji",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Noto Color Emoji" to FontCollection(listOf(emoji)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "Noto Color Emoji")),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = listOf("Noto Color Emoji"),
        ),
        coverage = fallbackTestCoverage(emoji.typeface.id to setOf(0x1F466, 0x1F3FB)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-skin-tone",
        request = FallbackRequest(text = "\uD83D\uDC66\uD83C\uDFFB", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackClusterThaiEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440924",
        familyName = "Alpha Sans",
    )
    val thai = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440925",
        familyName = "Thai Sans",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Thai Sans" to FontCollection(listOf(thai)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "Thai Sans")),
            scriptFallbackChains = mapOf("thai" to listOf("Thai Sans")),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(thai.typeface.id to setOf(0x0E01, 0x0E49)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-thai",
        request = FallbackRequest(text = "\u0E01\u0E49", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackClusterVs15Vs16EvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440926",
        familyName = "Alpha Sans",
    )
    val emoji = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440927",
        familyName = "Noto Color Emoji",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Noto Color Emoji" to FontCollection(listOf(emoji)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans", "Noto Color Emoji")),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = listOf("Noto Color Emoji"),
        ),
        coverage = fallbackTestCoverage(emoji.typeface.id to setOf(0x2764, 0xFE0E, 0xFE0F)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-cluster-vs15-vs16",
        request = FallbackRequest(text = "\u2764\uFE0E\u2764\uFE0F", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackAxisClampedEvidenceCase(): FallbackEvidenceCase {
    val variable = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440908",
        familyName = "Variable Sans Clamp",
        variationAxes = listOf(
            TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 700.0),
        ),
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(families = mapOf("Variable Sans Clamp" to FontCollection(listOf(variable)))),
        policy = FontFallbackPolicy.Default,
        coverage = fallbackTestCoverage(variable.typeface.id to setOf('x'.code)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-axis-clamped",
        request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Variable Sans Clamp"),
            variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 900.0)),
        ),
    )
}

private fun fallbackAxisMissingEvidenceCase(): FallbackEvidenceCase {
    val staticFace = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440909",
        familyName = "Static Sans",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(families = mapOf("Static Sans" to FontCollection(listOf(staticFace)))),
        policy = FontFallbackPolicy.Default,
        coverage = fallbackTestCoverage(staticFace.typeface.id to setOf('x'.code)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-axis-missing",
        request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Static Sans"),
            variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 650.0)),
        ),
    )
}

private fun fallbackScriptArabicEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440901",
        familyName = "Alpha Sans",
    )
    val arabic = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440902",
        familyName = "Arabic Naskh",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Arabic Naskh" to FontCollection(listOf(arabic)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            scriptFallbackChains = mapOf("arabic" to listOf("Arabic Naskh")),
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans")),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(arabic.typeface.id to setOf(0x0627)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-script-arabic",
        request = FallbackRequest(text = "\u0627", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackLocaleSerbianEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440903",
        familyName = "Latin Sans",
    )
    val serbian = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440904",
        familyName = "Serbian Sans",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Latin Sans" to FontCollection(listOf(latin)),
                "Serbian Sans" to FontCollection(listOf(serbian)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            localeFallbackChains = mapOf("sr" to listOf("Serbian Sans")),
            genericFallbackChains = mapOf("sans-serif" to listOf("Latin Sans")),
            scriptFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(serbian.typeface.id to setOf('A'.code)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-locale-serbian",
        request = FallbackRequest(text = "A", locale = "sr-Cyrl-RS", preferredFamilies = listOf("Missing Sans")),
    )
}

private fun fallbackEmojiPreferenceEvidenceCase(): FallbackEvidenceCase {
    val latin = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440905",
        familyName = "Alpha Sans",
    )
    val emoji = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440906",
        familyName = "Noto Color Emoji",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Alpha Sans" to FontCollection(listOf(latin)),
                "Noto Color Emoji" to FontCollection(listOf(emoji)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Alpha Sans")),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = listOf("Noto Color Emoji"),
        ),
        coverage = fallbackTestCoverage(emoji.typeface.id to setOf(0x1F600)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-emoji-preference",
        request = FallbackRequest(text = "\uD83D\uDE00", preferredFamilies = listOf("Alpha Sans")),
    )
}

private fun fallbackMetricsVariationMissingEvidenceCase(): FallbackEvidenceCase {
    val variable = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440910",
        familyName = "Variable Sans Metrics",
        variationAxes = listOf(
            TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
        ),
        variationMetricsSupported = false,
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(families = mapOf("Variable Sans Metrics" to FontCollection(listOf(variable)))),
        policy = FontFallbackPolicy.Default,
        coverage = fallbackTestCoverage(variable.typeface.id to setOf('x'.code)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-metrics-variation-missing",
        request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Variable Sans Metrics"),
            variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 650.0)),
        ),
    )
}

private fun fallbackNamedInstanceEvidenceCase(): FallbackEvidenceCase {
    val axisOnly = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440914",
        familyName = "Axis Only Sans",
        variationAxes = listOf(
            TypefaceVariationAxisRange(axisTag = "wdth", minimum = 75.0, defaultValue = 100.0, maximum = 125.0),
            TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
        ),
    )
    val namedInstance = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440915",
        familyName = "Named Instance Sans",
        variationAxes = listOf(
            TypefaceVariationAxisRange(axisTag = "wdth", minimum = 75.0, defaultValue = 100.0, maximum = 125.0),
            TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
        ),
        namedInstances = listOf(
            TypefaceNamedInstance(
                name = "Condensed Bold",
                coordinates = listOf(
                    TypefaceVariationCoordinate(axisTag = "wdth", value = 80.0),
                    TypefaceVariationCoordinate(axisTag = "wght", value = 700.0),
                ),
            ),
        ),
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Axis Only Sans" to FontCollection(listOf(axisOnly)),
                "Named Instance Sans" to FontCollection(listOf(namedInstance)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Axis Only Sans", "Named Instance Sans")),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(
            axisOnly.typeface.id to setOf('x'.code),
            namedInstance.typeface.id to setOf('x'.code),
        ),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-named-instance",
        request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Missing Sans"),
            namedInstance = "Condensed Bold",
        ),
    )
}

private fun fallbackMultiAxisEvidenceCase(): FallbackEvidenceCase {
    val weightOnly = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440916",
        familyName = "Weight Only Sans",
        variationAxes = listOf(
            TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
        ),
    )
    val multiAxis = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440917",
        familyName = "Multi Axis Sans",
        variationAxes = listOf(
            TypefaceVariationAxisRange(axisTag = "wdth", minimum = 75.0, defaultValue = 100.0, maximum = 125.0),
            TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
        ),
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(
            families = mapOf(
                "Weight Only Sans" to FontCollection(listOf(weightOnly)),
                "Multi Axis Sans" to FontCollection(listOf(multiAxis)),
            ),
        ),
        policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf("sans-serif" to listOf("Weight Only Sans", "Multi Axis Sans")),
            scriptFallbackChains = emptyMap(),
            localeFallbackChains = emptyMap(),
            emojiPreferredFamilies = emptyList(),
        ),
        coverage = fallbackTestCoverage(
            weightOnly.typeface.id to setOf('x'.code),
            multiAxis.typeface.id to setOf('x'.code),
        ),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-multi-axis",
        request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Missing Sans"),
            variationCoordinates = listOf(
                TypefaceVariationCoordinate(axisTag = "wdth", value = 80.0),
                TypefaceVariationCoordinate(axisTag = "wght", value = 700.0),
            ),
        ),
    )
}

private fun fallbackMissingGlyphEvidenceCase(): FallbackEvidenceCase {
    val requested = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440907",
        familyName = "Requested Sans",
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(families = mapOf("Requested Sans" to FontCollection(listOf(requested)))),
        policy = FontFallbackPolicy.Default,
        coverage = fallbackTestCoverage(),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-missing-glyph",
        request = FallbackRequest(text = "x", preferredFamilies = listOf("Requested Sans")),
        additionalDiagnostics = listOf("text.shaping.fallback-missing"),
    )
}

private fun fallbackFamilyUnavailableEvidenceCase(): FallbackEvidenceCase {
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(),
        policy = FontFallbackPolicy.Default,
        coverage = fallbackTestCoverage(),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-family-unavailable",
        request = FallbackRequest(text = "\u10D0", preferredFamilies = listOf("Missing Sans")),
        additionalDiagnostics = listOf("text.shaping.script-unsupported"),
    )
}

private fun fallbackVariableCff2EvidenceCase(): FallbackEvidenceCase {
    val cff2 = fallbackFixtureFace(
        uuid = "550e8400-e29b-41d4-a716-446655440911",
        familyName = "Variable CFF2 Sans",
        outlineFormat = TypefaceOutlineFormat.CFF2,
        variationAxes = listOf(
            TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
        ),
    )
    val resolver = CatalogFontResolver(
        catalog = FallbackCatalog(families = mapOf("Variable CFF2 Sans" to FontCollection(listOf(cff2)))),
        policy = FontFallbackPolicy.Default,
        coverage = fallbackTestCoverage(cff2.typeface.id to setOf('x'.code)),
    )
    return resolver.evidenceCase(
        fixtureId = "fallback-variable-cff2",
        request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Variable CFF2 Sans"),
            variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 650.0)),
        ),
    )
}

private fun fallbackFixtureFace(
    uuid: String,
    familyName: String,
    styleName: String = "Regular",
    outlineFormat: TypefaceOutlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
    variationAxes: List<TypefaceVariationAxisRange> = emptyList(),
    variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
    namedInstances: List<TypefaceNamedInstance> = emptyList(),
    variationMetricsSupported: Boolean = true,
): FontFace {
    val sourceId = FontSourceID(Uuid.parse(uuid.replaceRange(uuid.length - 1, uuid.length, "0")))
    val typefaceId = TypefaceID(Uuid.parse(uuid))
    val tableTags = buildList {
        add("cmap")
        add("head")
        add("name")
        when (outlineFormat) {
            TypefaceOutlineFormat.CFF -> add("CFF ")
            TypefaceOutlineFormat.CFF2 -> add("CFF2")
            TypefaceOutlineFormat.TRUE_TYPE_GLYF -> add("glyf")
            TypefaceOutlineFormat.BITMAP_ONLY -> add("CBDT")
            TypefaceOutlineFormat.UNKNOWN -> add("name")
        }
        if (variationAxes.isNotEmpty()) add("fvar")
    }
    val identityPreimage = typefaceIdentityPreimage(
        sourceId = sourceId,
        collectionIndex = 0,
        postScriptName = "$familyName-$styleName",
        familyName = familyName,
        styleName = styleName,
        outlineFormat = outlineFormat,
        selectedCMap = TypefaceCMapSelection(
            platformId = 3,
            encodingId = 10,
            format = 12,
            language = 0,
            unicode = true,
        ),
        scalerMode = TypefaceScalerMode.OUTLINE,
        variationCoordinates = variationCoordinates,
        tableTags = tableTags,
    )
    return FontFace(
        typeface = TypefaceData(
            id = typefaceId,
            source = FontSource(
                id = sourceId,
                kind = FontSourceKind.BUNDLED_FIXTURE,
                displayName = "$familyName $styleName",
                bytes = byteArrayOf(1),
            ),
            familyName = familyName,
            styleName = styleName,
            variationAxes = variationAxes.sortedBy { axis -> axis.axisTag },
            variationCoordinates = variationCoordinates.normalizedVariationCoordinates(),
            namedInstances = namedInstances.sortedBy { instance -> instance.name },
            variationMetricsSupported = variationMetricsSupported,
            identityPreimage = identityPreimage,
        ),
    )
}

private fun fallbackTestCoverage(vararg entries: Pair<TypefaceID, Set<Int>>): FontCoverageProvider {
    val supported = entries.toMap()
    return FontCoverageProvider { typefaceId, codePoint ->
        supported[typefaceId]?.contains(codePoint) == true
    }
}
private fun List<Pair<String, CanonicalFontIdentityJson>>.toDumpBytePreimage(): String =
    joinToString(separator = "\n") { (label, json) ->
        "$label\n${json.value}"
    }

private fun String.isSingleJsonObjectWithOptionalTerminalNewline(): Boolean {
    if (isEmpty() || first() != '{') return false
    val endExclusive = if (last() == '\n') length - 1 else length
    if (endExclusive <= 0 || this[endExclusive - 1] != '}') return false

    var depth = 0
    var inString = false
    var escaping = false
    for (index in 0 until endExclusive) {
        val character = this[index]
        if (inString) {
            when {
                escaping -> escaping = false
                character == '\\' -> escaping = true
                character == '"' -> inString = false
            }
            continue
        }

        when (character) {
            '"' -> inString = true
            '{' -> depth += 1
            '}' -> {
                depth -= 1
                if (depth < 0) return false
                if (depth == 0 && index != endExclusive - 1) return false
            }
        }
    }
    return depth == 0 && !inString && !escaping
}

private fun String.evidenceQuoted(): String = buildString {
    append('"')
    for (character in this@evidenceQuoted) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (character < ' ') {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
    }
    append('"')
}

private const val HexDigits: String = "0123456789abcdef"

private fun String.toGenericFamilyKey(): String? =
    when (familyKey()) {
        "sans", "sans serif", "sans-serif", "ui-sans-serif" -> "sans-serif"
        "serif", "ui-serif" -> "serif"
        "mono", "monospace", "monospaced", "ui-monospace" -> "monospace"
        else -> null
    }

private fun List<String>.normalizedLocaleLanguages(): List<String> {
    val languages = linkedSetOf<String>()
    for (locale in this) {
        val language = locale
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.substringBefore('-')
            ?.substringBefore('_')
            ?.lowercase()
            .orEmpty()
        if (language.isNotEmpty()) languages.add(language)
    }
    return languages.toList()
}

private fun String.firstCodePointOrNull(): Int? =
    if (isEmpty()) null else codePointAt(0)

private fun List<FontFace>.orderedByStyleDistance(requestedStyle: FontStyle): List<FontFace> =
    withIndex()
        .sortedWith(
            compareBy<IndexedValue<FontFace>> { (_, face) -> face.typeface.style.distanceTo(requestedStyle) }
                .thenBy { (index, _) -> index },
        )
        .map { (_, face) -> face }

private fun FontStyle.distanceTo(requestedStyle: FontStyle): Int {
    val slantPenalty = if (slant == requestedStyle.slant) 0 else 10_000
    val weightPenalty = kotlin.math.abs(weight - requestedStyle.weight) * 10
    val widthPenalty = kotlin.math.abs(width - requestedStyle.width) * 100
    return slantPenalty + weightPenalty + widthPenalty
}

private fun classifyFallbackScript(codePoint: Int?): String =
    when {
        codePoint == null -> "default"
        codePoint in 0x1F300..0x1FAFF ||
            codePoint in 0x2600..0x27BF -> "emoji"
        codePoint in 0x3040..0x30FF -> "japanese"
        codePoint in 0x4E00..0x9FFF -> "han"
        codePoint in 0xAC00..0xD7AF -> "korean"
        codePoint in 0x0600..0x06FF -> "arabic"
        codePoint in 0x0590..0x05FF -> "hebrew"
        codePoint in 0x0900..0x097F -> "devanagari"
        codePoint.isLatinLetterCodePoint() -> "latin"
        else -> "default"
    }

private fun Int.isLatinLetterCodePoint(): Boolean =
    Character.isValidCodePoint(this) &&
        Character.isLetter(this) &&
        Character.UnicodeScript.of(this) == Character.UnicodeScript.LATIN

private fun Int.toCodePointEvidence(): String {
    val minimumWidth = if (this <= 0xFFFF) 4 else 6
    return "U+" + toString(radix = 16).uppercase().padStart(minimumWidth, '0')
}
