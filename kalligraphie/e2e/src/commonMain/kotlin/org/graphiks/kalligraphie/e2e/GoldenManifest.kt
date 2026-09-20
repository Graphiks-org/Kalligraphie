package org.graphiks.kalligraphie.e2e

/**
 * Ordered, canonical set of golden fingerprints.
 *
 * Entries are unique by [GoldenFingerprint.sceneId] and kept sorted by id so the
 * serialized text is a stable, human-reviewable diff.
 */
public class GoldenManifest private constructor(
    /** Fingerprints sorted by scene id. */
    public val entries: List<GoldenFingerprint>,
) {
    private val byId: Map<String, GoldenFingerprint> = entries.associateBy { entry -> entry.sceneId }

    /** Returns the fingerprint registered for [sceneId], or `null`. */
    public fun fingerprintOf(sceneId: String): GoldenFingerprint? = byId[sceneId]

    override fun equals(other: Any?): Boolean =
        other is GoldenManifest && entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    override fun toString(): String = "GoldenManifest(entries=${entries.size})"

    public companion object {
        /** Manifest header line identifying the schema and the canonicalization version. */
        public const val HEADER: String = "kalligraphie.golden/v1"

        /**
         * Canonicalization algorithm version. Bumping this value invalidates every
         * existing manifest wholesale, forcing a deliberate regeneration.
         */
        public const val CANONICALIZATION_VERSION: Int = 1

        /** Builds a manifest from [entries], sorted by scene id and rejecting duplicates. */
        public fun of(entries: List<GoldenFingerprint>): GoldenManifest {
            val sorted = entries.sortedBy { entry -> entry.sceneId }
            require(sorted.map { entry -> entry.sceneId }.distinct().size == sorted.size) {
                "A golden manifest must not contain duplicate scene ids."
            }
            return GoldenManifest(sorted)
        }

        /** Serializes [manifest] to its canonical text form. */
        public fun serialize(manifest: GoldenManifest): String = buildString {
            append(HEADER)
            append(" canonicalization=")
            append(CANONICALIZATION_VERSION)
            append('\n')
            for (entry in manifest.entries) {
                append(entry.sceneId)
                append('\t')
                append(entry.family.name)
                append('\t')
                append(entry.width)
                append('x')
                append(entry.height)
                append('\t')
                append(entry.format.name)
                append("\tsha256:")
                append(entry.sha256)
                append('\n')
            }
        }

        /** Parses canonical manifest [text], failing closed with a typed code. */
        public fun parse(text: String): GoldenManifestParseResult {
            val lines = text.split('\n').map { line -> line.removeSuffix("\r") }
            val records = if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
            if (records.isEmpty()) {
                return GoldenManifestParseResult.Rejected(GoldenDiagnosticCode.MANIFEST_MALFORMED, "empty manifest")
            }

            val header = records.first()
            val expectedPrefix = "$HEADER canonicalization="
            if (!header.startsWith(expectedPrefix)) {
                return GoldenManifestParseResult.Rejected(
                    GoldenDiagnosticCode.MANIFEST_MALFORMED,
                    "missing or unrecognised header: $header",
                )
            }
            val versionText = header.removePrefix(expectedPrefix)
            val version = versionText.toIntOrNull()
            if (version == null || version.toString() != versionText) {
                return GoldenManifestParseResult.Rejected(
                    GoldenDiagnosticCode.MANIFEST_MALFORMED,
                    "unparsable canonicalization version: $header",
                )
            }
            if (version != CANONICALIZATION_VERSION) {
                return GoldenManifestParseResult.Rejected(
                    GoldenDiagnosticCode.CANONICALIZATION_VERSION_MISMATCH,
                    "manifest canonicalization=$version, harness expects $CANONICALIZATION_VERSION; run updateE2eGolden",
                )
            }

            val entries = ArrayList<GoldenFingerprint>(records.size - 1)
            var previousId: String? = null
            for (record in records.drop(1)) {
                val fields = record.split('\t')
                if (fields.size != 5) {
                    return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED,
                        "expected 5 tab-separated fields: $record",
                    )
                }
                val id = fields[0]
                if (id.isBlank()) {
                    return GoldenManifestParseResult.Rejected(GoldenDiagnosticCode.MANIFEST_MALFORMED, "blank scene id")
                }
                if (previousId != null && id <= previousId) {
                    return GoldenManifestParseResult.Rejected(
                        if (id == previousId) GoldenDiagnosticCode.MANIFEST_DUPLICATE_ID else GoldenDiagnosticCode.MANIFEST_MALFORMED,
                        "scene ids must be unique and sorted: $id",
                    )
                }
                previousId = id

                val family = GoldenSceneFamily.entries.firstOrNull { candidate -> candidate.name == fields[1] }
                    ?: return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unknown family: ${fields[1]}",
                    )
                val dimensions = fields[2].split('x')
                val widthText = dimensions.getOrNull(0)
                val heightText = dimensions.getOrNull(1)
                val width = widthText?.toIntOrNull()
                val height = heightText?.toIntOrNull()
                if (dimensions.size != 2 || width == null || height == null ||
                    widthText != width.toString() || heightText != height.toString() ||
                    width < 0 || height < 0
                ) {
                    return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unparsable dimensions: ${fields[2]}",
                    )
                }
                val format = PixelFormat.entries.firstOrNull { candidate -> candidate.name == fields[3] }
                    ?: return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unknown pixel format: ${fields[3]}",
                    )
                val digest = fields[4].removePrefix("sha256:")
                if (!fields[4].startsWith("sha256:") || digest.length != 64 || digest.any { char -> char !in "0123456789abcdef" }) {
                    return GoldenManifestParseResult.Rejected(
                        GoldenDiagnosticCode.MANIFEST_MALFORMED, "unparsable digest: ${fields[4]}",
                    )
                }

                entries.add(GoldenFingerprint(id, family, width, height, format, digest))
            }
            return GoldenManifestParseResult.Parsed(GoldenManifest(entries))
        }
    }
}

/** Outcome of parsing canonical manifest text. */
public sealed interface GoldenManifestParseResult {
    /** The manifest parsed and validated. */
    public data class Parsed(
        /** Parsed manifest. */
        public val manifest: GoldenManifest,
    ) : GoldenManifestParseResult

    /** The manifest was refused with a stable code and a human-readable detail. */
    public data class Rejected(
        /** Stable diagnostic code. */
        public val code: GoldenDiagnosticCode,
        /** Human-readable reason without sensitive data. */
        public val detail: String,
    ) : GoldenManifestParseResult
}
