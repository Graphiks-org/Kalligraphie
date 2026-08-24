package io.ygdrasil.kalligraphie.font

import java.util.Locale

enum class FontTelemetryDomain(val serializedName: String) {
    Parser("parser"),
    Scaler("scaler"),
    Shaping("shaping"),
    Paragraph("paragraph"),
    GlyphArtifact("glyph-artifact"),
    GPUTextHandoff("gpu-text-handoff"),
}

enum class FontTelemetryUnit(val serializedName: String) {
    Nanoseconds("nanoseconds"),
    Count("count"),
    Bytes("bytes"),
}

enum class FontTelemetryCacheState(val serializedName: String) {
    Cold("cold"),
    Warm("warm"),
    Mixed("mixed"),
}

data class FontTelemetryEvidenceBundle(
    val schemaJson: String,
    val fixtureJson: String,
)

private data class FontTelemetryDomainSchema(
    val domain: FontTelemetryDomain,
    val requiredDimensions: List<String>,
    val metricNames: List<String>,
    val gpuAdapterRequired: Boolean,
) {
    fun toCanonicalJson(): String = buildString {
        append("{")
        appendTelemetryField("domain", domain.serializedName, comma = true)
        appendTelemetryStringArray("requiredDimensions", requiredDimensions, comma = true)
        appendTelemetryStringArray("metricNames", metricNames, comma = true)
        appendTelemetryField("gpuAdapterRequired", gpuAdapterRequired, comma = false)
        append("}")
    }
}

private data class FontTelemetryEnvironment(
    val environmentLabel: String,
    val runtimeLabel: String,
    val gpuAdapter: String? = null,
    val gpuBackend: String? = null,
) {
    init {
        require(environmentLabel.isNotBlank()) { "environmentLabel must not be blank." }
        require(runtimeLabel.isNotBlank()) { "runtimeLabel must not be blank." }
        require(gpuAdapter == null || gpuAdapter.isNotBlank()) { "gpuAdapter must be non-blank when present." }
        require(gpuBackend == null || gpuBackend.isNotBlank()) { "gpuBackend must be non-blank when present." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendTelemetryField("environmentLabel", environmentLabel, comma = true)
        appendTelemetryField("runtimeLabel", runtimeLabel, comma = true)
        append("gpuAdapter".quoted()).append(":").append(gpuAdapter.toTelemetryNullableString()).append(",")
        append("gpuBackend".quoted()).append(":").append(gpuBackend.toTelemetryNullableString())
        append("}")
    }
}

private data class FontTelemetryMetricSeries(
    val name: String,
    val trendSeriesId: String? = null,
    val unit: FontTelemetryUnit,
    val median: Long,
    val p90: Long,
    val max: Long,
    val counters: Map<String, Long> = emptyMap(),
) {
    init {
        require(name.isNotBlank()) { "name must not be blank." }
        require(trendSeriesId == null || trendSeriesId.isStableTelemetryToken()) {
            "trendSeriesId must be a stable one-line token when present."
        }
        require(median >= 0) { "median must be non-negative." }
        require(p90 >= 0) { "p90 must be non-negative." }
        require(max >= 0) { "max must be non-negative." }
        require(counters.keys.all { it.isStableTelemetryToken() }) {
            "counter keys must be stable one-line tokens."
        }
        require(counters.values.all { it >= 0 }) { "counter values must be non-negative." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendTelemetryField("name", name, comma = true)
        if (trendSeriesId != null) {
            appendTelemetryField("trendSeriesId", trendSeriesId, comma = true)
        }
        appendTelemetryField("unit", unit.serializedName, comma = true)
        appendTelemetryField("median", median, comma = true)
        appendTelemetryField("p90", p90, comma = true)
        appendTelemetryField("max", max, comma = true)
        append("counters".quoted()).append(":")
        append(
            counters.toSortedMap().entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${key.quoted()}:$value"
            },
        )
        append("}")
    }
}

private data class FontTelemetrySample(
    val fixtureId: String,
    val domain: FontTelemetryDomain,
    val measurementPhase: String,
    val sampleCount: Int,
    val cacheState: FontTelemetryCacheState,
    val fontSourceSetHash: String,
    val unicodeDataVersion: String,
    val tableTags: List<String> = emptyList(),
    val runId: String? = null,
    val paragraphId: String? = null,
    val textRangeStart: Int? = null,
    val textRangeEnd: Int? = null,
    val environment: FontTelemetryEnvironment,
    val metrics: List<FontTelemetryMetricSeries>,
    val diagnostics: List<String> = emptyList(),
) {
    init {
        require(fixtureId.isNotBlank()) { "fixtureId must not be blank." }
        require(measurementPhase.isStableTelemetryToken()) {
            "measurementPhase must be a stable one-line token."
        }
        require(sampleCount > 1) {
            "sampleCount must be greater than one for repeated-run telemetry evidence."
        }
        require(fontSourceSetHash.isNotBlank()) { "fontSourceSetHash must not be blank." }
        require(unicodeDataVersion.isNotBlank()) { "unicodeDataVersion must not be blank." }
        require(tableTags.all { it.isStableTelemetryToken() }) {
            "tableTags must use stable one-line tokens."
        }
        require(runId == null || runId.isStableTelemetryToken()) {
            "runId must be a stable one-line token when present."
        }
        require(paragraphId == null || paragraphId.isStableTelemetryToken()) {
            "paragraphId must be a stable one-line token when present."
        }
        require((textRangeStart == null) == (textRangeEnd == null)) {
            "textRangeStart and textRangeEnd must be both present or both absent."
        }
        if (textRangeStart != null && textRangeEnd != null) {
            require(textRangeStart >= 0) { "textRangeStart must be non-negative." }
            require(textRangeEnd >= textRangeStart) { "textRangeEnd must be greater than or equal to textRangeStart." }
        }
        require(metrics.isNotEmpty()) { "metrics must not be empty." }
        require(diagnostics.all { it.isStableDiagnosticToken() }) {
            "diagnostics must use stable one-line diagnostic tokens."
        }
        when (domain) {
            FontTelemetryDomain.Shaping -> {
                require(runId != null) { "Shaping samples must record runId." }
                require(paragraphId == null) { "Shaping samples must not record paragraphId." }
            }
            FontTelemetryDomain.Paragraph -> {
                require(paragraphId != null) { "Paragraph samples must record paragraphId." }
                require(runId == null) { "Paragraph samples must not record runId." }
            }
            else -> {
                require(runId == null) { "Only shaping samples may record runId." }
                require(paragraphId == null) { "Only paragraph samples may record paragraphId." }
            }
        }
        if (domain == FontTelemetryDomain.GPUTextHandoff) {
            require(environment.gpuAdapter != null) {
                "GPU text handoff samples must record gpuAdapter."
            }
            require(environment.gpuBackend != null) {
                "GPU text handoff samples must record gpuBackend."
            }
        } else {
            require(environment.gpuAdapter == null) {
                "Only GPU text handoff samples may record gpuAdapter."
            }
            require(environment.gpuBackend == null) {
                "Only GPU text handoff samples may record gpuBackend."
            }
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendTelemetryField("fixtureId", fixtureId, comma = true)
        appendTelemetryField("domain", domain.serializedName, comma = true)
        appendTelemetryField("measurementPhase", measurementPhase, comma = true)
        appendTelemetryField("sampleCount", sampleCount, comma = true)
        appendTelemetryField("cacheState", cacheState.serializedName, comma = true)
        appendTelemetryField("fontSourceSetHash", fontSourceSetHash, comma = true)
        appendTelemetryField("unicodeDataVersion", unicodeDataVersion, comma = true)
        if (domain == FontTelemetryDomain.Parser || domain == FontTelemetryDomain.Scaler || tableTags.isNotEmpty()) {
            appendTelemetryStringArray("tableTags", tableTags, comma = true)
        }
        runId?.let { appendTelemetryField("runId", it, comma = true) }
        paragraphId?.let { appendTelemetryField("paragraphId", it, comma = true) }
        textRangeStart?.let { appendTelemetryField("textRangeStart", it, comma = true) }
        textRangeEnd?.let { appendTelemetryField("textRangeEnd", it, comma = true) }
        append("environment".quoted()).append(":").append(environment.toCanonicalJson()).append(",")
        append("metrics".quoted()).append(":")
        append(metrics.joinToString(prefix = "[", postfix = "]", separator = ",") { it.toCanonicalJson() })
        append(",")
        appendTelemetryStringArray("diagnostics", diagnostics, comma = false)
        append("}")
    }
}

private data class FontTelemetryNegativeCase(
    val fixtureId: String,
    val domain: String,
    val diagnosticCode: String,
    val missingField: String? = null,
    val sampleCount: Int = 1,
) {
    init {
        require(fixtureId.isNotBlank()) { "fixtureId must not be blank." }
        require(domain.isStableTelemetryToken()) { "domain must be a stable one-line token." }
        require(diagnosticCode.isStableTelemetryDiagnosticCode()) {
            "diagnosticCode must be a stable telemetry diagnostic code."
        }
        require(missingField == null || missingField.isStableTelemetryToken()) {
            "missingField must be a stable one-line token when present."
        }
        require(sampleCount >= 1) { "sampleCount must be positive." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendTelemetryField("fixtureId", fixtureId, comma = true)
        appendTelemetryField("domain", domain, comma = true)
        appendTelemetryField("diagnosticCode", diagnosticCode, comma = true)
        append("missingField".quoted()).append(":").append(missingField.toTelemetryNullableString()).append(",")
        appendTelemetryField("sampleCount", sampleCount, comma = false)
        append("}")
    }
}

private data class FontTelemetryDomainDump(
    val dumpId: String,
    val ownerTickets: List<String>,
    val domain: FontTelemetryDomain,
    val trendSeriesPrefix: String? = null,
    val samples: List<FontTelemetrySample>,
    val negativeCases: List<FontTelemetryNegativeCase>,
    val nonClaims: List<String>,
) {
    init {
        require(dumpId.isStableTelemetryToken()) { "dumpId must be a stable one-line token." }
        require(ownerTickets.isNotEmpty()) { "ownerTickets must not be empty." }
        require(ownerTickets.all { it.isStableTelemetryToken() }) {
            "ownerTickets must use stable one-line tokens."
        }
        require(trendSeriesPrefix == null || trendSeriesPrefix.isStableTelemetryToken()) {
            "trendSeriesPrefix must be a stable one-line token when present."
        }
        require(samples.isNotEmpty()) { "samples must not be empty." }
        require(nonClaims.isNotEmpty()) { "nonClaims must not be empty." }
        require(nonClaims.all { it.isStableTelemetryToken() }) {
            "nonClaims must use stable one-line tokens."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": ").append(dumpId.quoted()).append(",\n")
        append("  \"ownerTickets\": ")
        append(ownerTickets.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.quoted() })
        append(",\n")
        append("  \"domain\": ").append(domain.serializedName.quoted()).append(",\n")
        if (trendSeriesPrefix != null) {
            append("  \"trendSeriesPrefix\": ").append(trendSeriesPrefix.quoted()).append(",\n")
        }
        append("  \"samples\": [\n")
        append(samples.joinToString(",\n") { "    ${it.toCanonicalJson()}" })
        append("\n  ],\n")
        append("  \"negativeCases\": [\n")
        append(negativeCases.joinToString(",\n") { "    ${it.toCanonicalJson()}" })
        append("\n  ],\n")
        append("  \"nonClaims\": ")
        append(nonClaims.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.quoted() })
        append("\n}")
    }
}

private data class GlyphAtlasOccupancyEntry(
    val atlasArtifactId: String,
    val textureFormat: String,
    val generation: Int,
    val invalidationToken: String,
    val width: Int,
    val height: Int,
    val rowStride: Int,
    val entryCount: Int,
    val occupiedPixelCount: Int,
    val totalPixelCount: Int,
    val occupancyRatio: Double,
    val strikeKeySha256: List<String>,
    val keyPreimageSha256: List<String>,
) {
    init {
        require(atlasArtifactId.matches(Regex("[0-9a-f]{64}"))) { "atlasArtifactId must be lowercase hexadecimal." }
        require(textureFormat.isNotBlank()) { "textureFormat must not be blank." }
        require(generation >= 0) { "generation must be non-negative." }
        require(invalidationToken.isNotBlank()) { "invalidationToken must not be blank." }
        require(width > 0) { "width must be positive." }
        require(height > 0) { "height must be positive." }
        require(rowStride >= width) { "rowStride must be greater than or equal to width." }
        require(entryCount >= 0) { "entryCount must be non-negative." }
        require(occupiedPixelCount >= 0) { "occupiedPixelCount must be non-negative." }
        require(totalPixelCount > 0) { "totalPixelCount must be positive." }
        require(occupiedPixelCount <= totalPixelCount) { "occupiedPixelCount must not exceed totalPixelCount." }
        require(occupancyRatio >= 0.0 && occupancyRatio <= 1.0) { "occupancyRatio must stay within [0, 1]." }
        require(strikeKeySha256.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "strikeKeySha256 values must be lowercase hexadecimal."
        }
        require(keyPreimageSha256.all { it.matches(Regex("[0-9a-f]{64}")) }) {
            "keyPreimageSha256 values must be lowercase hexadecimal."
        }
    }

    fun toCanonicalJson(): String = buildString {
        append("{")
        appendTelemetryField("atlasArtifactId", atlasArtifactId, comma = true)
        appendTelemetryField("textureFormat", textureFormat, comma = true)
        appendTelemetryField("generation", generation, comma = true)
        appendTelemetryField("invalidationToken", invalidationToken, comma = true)
        append("dimensions".quoted()).append(":")
        append("{")
        appendTelemetryField("width", width, comma = true)
        appendTelemetryField("height", height, comma = true)
        appendTelemetryField("rowStride", rowStride, comma = false)
        append("}").append(",")
        appendTelemetryField("entryCount", entryCount, comma = true)
        appendTelemetryField("occupiedPixelCount", occupiedPixelCount, comma = true)
        appendTelemetryField("totalPixelCount", totalPixelCount, comma = true)
        appendTelemetryField("occupancyRatio", occupancyRatio, comma = true)
        appendTelemetryStringArray("strikeKeySha256", strikeKeySha256, comma = true)
        appendTelemetryStringArray("keyPreimageSha256", keyPreimageSha256, comma = false)
        append("}")
    }
}

private data class GlyphAtlasOccupancyDump(
    val dumpId: String,
    val ownerTickets: List<String>,
    val sourceDumps: List<String>,
    val atlases: List<GlyphAtlasOccupancyEntry>,
    val nonClaims: List<String>,
) {
    init {
        require(dumpId.isStableTelemetryToken()) { "dumpId must be a stable one-line token." }
        require(ownerTickets.isNotEmpty()) { "ownerTickets must not be empty." }
        require(sourceDumps.isNotEmpty()) { "sourceDumps must not be empty." }
        require(atlases.isNotEmpty()) { "atlases must not be empty." }
        require(nonClaims.isNotEmpty()) { "nonClaims must not be empty." }
    }

    fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"dumpId\": ").append(dumpId.quoted()).append(",\n")
        append("  \"ownerTickets\": ")
        append(ownerTickets.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.quoted() })
        append(",\n")
        append("  \"sourceDumps\": ")
        append(sourceDumps.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.quoted() })
        append(",\n")
        append("  \"atlases\": [\n")
        append(atlases.joinToString(",\n") { "    ${it.toCanonicalJson()}" })
        append("\n  ],\n")
        append("  \"nonClaims\": ")
        append(nonClaims.joinToString(prefix = "[", postfix = "]", separator = ", ") { it.quoted() })
        append("\n}")
    }
}

object FontTelemetryEvidenceWriter {
    fun writeBundle(): FontTelemetryEvidenceBundle = FontTelemetryEvidenceBundle(
        schemaJson = writeSchemaJson(),
        fixtureJson = writeFixtureJson(),
    )

    fun writeSchemaJson(): String {
        val domains = listOf(
            FontTelemetryDomainSchema(
                domain = FontTelemetryDomain.Parser,
                requiredDimensions = commonDimensions(),
                metricNames = listOf("parser.scan.time", "parser.parse.time", "parser.table-cache.hit"),
                gpuAdapterRequired = false,
            ),
            FontTelemetryDomainSchema(
                domain = FontTelemetryDomain.Scaler,
                requiredDimensions = commonDimensions(),
                metricNames = listOf("scaler.outline.time", "scaler.bounds.time", "scaler.variation.time"),
                gpuAdapterRequired = false,
            ),
            FontTelemetryDomainSchema(
                domain = FontTelemetryDomain.Shaping,
                requiredDimensions = shapingDimensions(),
                metricNames = listOf(
                    "shaping.segmentation.time",
                    "shaping.bidi.time",
                    "shaping.script-itemization.time",
                    "shaping.fallback.time",
                    "shaping.gsub.time",
                    "shaping.gpos.time",
                    "shaping.glyph.count",
                    "shaping.cluster.count",
                    "shaping.diagnostic.count",
                ),
                gpuAdapterRequired = false,
            ),
            FontTelemetryDomainSchema(
                domain = FontTelemetryDomain.Paragraph,
                requiredDimensions = paragraphDimensions(),
                metricNames = listOf(
                    "paragraph.layout.time",
                    "paragraph.style-run.count",
                    "paragraph.line-break-opportunity.count",
                    "paragraph.shaped-run.count",
                    "paragraph.line.count",
                    "paragraph.hit-test-index-build.time",
                    "paragraph.selection-query.time",
                    "paragraph.ellipsis.attempt.count",
                    "paragraph.placeholder.count",
                ),
                gpuAdapterRequired = false,
            ),
            FontTelemetryDomainSchema(
                domain = FontTelemetryDomain.GlyphArtifact,
                requiredDimensions = commonDimensions(),
                metricNames = listOf("glyph-artifact.route.count", "glyph-artifact.a8.time", "glyph-artifact.cache.bytes"),
                gpuAdapterRequired = false,
            ),
            FontTelemetryDomainSchema(
                domain = FontTelemetryDomain.GPUTextHandoff,
                requiredDimensions = commonDimensions() + listOf("gpuAdapter", "gpuBackend"),
                metricNames = listOf("gpu-text-handoff.registry.lookup.time", "gpu-text-handoff.upload.bytes", "gpu-text-handoff.route.count"),
                gpuAdapterRequired = true,
            ),
        )
        val diagnosticCodes = listOf(
            "font.telemetry.dimension-missing",
            "font.telemetry.scaler-domain-missing",
            "font.telemetry.schema-domain-missing",
            "font.telemetry.single-run-budget-refused",
        )
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"schemaId\": \"font-telemetry-schema\",\n")
            append("  \"ownerTickets\": [\"KFONT-M12-001\"],\n")
            append("  \"domains\": [\n")
            append(domains.joinToString(",\n") { "    ${it.toCanonicalJson()}" })
            append("\n  ],\n")
            append("  \"requiredAggregationFields\": [\"median\", \"p90\", \"max\", \"sampleCount\", \"cacheState\"],\n")
            append("  \"diagnosticCodes\": [")
            append(diagnosticCodes.joinToString(separator = ", ") { it.quoted() })
            append("],\n")
            append("  \"dashboardRow\": {\"label\":\"Font telemetry schema\",\"classification\":\"tracked-gap\",\"claimPromotionAllowed\":false},\n")
            append("  \"nonClaims\": ")
            append(defaultNonClaims().joinToString(prefix = "[", postfix = "]", separator = ", ") { it.quoted() })
            append("\n")
            append("}")
        }
    }

    fun writeFixtureJson(): String {
        val repeatedSamples = listOf(
            FontTelemetrySample(
                fixtureId = "telemetry-parser-repeat",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-latin-bundled-v1",
                unicodeDataVersion = "16.0.0",
                environment = FontTelemetryEnvironment(
                    environmentLabel = "developer-desktop",
                    runtimeLabel = "jdk-25-kotlin-2.3",
                ),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 1_200_000,
                        p90 = 1_600_000,
                        max = 1_800_000,
                        counters = mapOf("diagnosticCount" to 0L, "tableCacheMiss" to 1L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "telemetry-scaler-repeat",
                domain = FontTelemetryDomain.Scaler,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-latin-bundled-v1",
                unicodeDataVersion = "16.0.0",
                environment = FontTelemetryEnvironment(
                    environmentLabel = "developer-desktop",
                    runtimeLabel = "jdk-25-kotlin-2.3",
                ),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "scaler.outline.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 900_000,
                        p90 = 1_100_000,
                        max = 1_250_000,
                        counters = mapOf("glyphCount" to 32L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "telemetry-shaping-repeat",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-latin-bundled-v1",
                unicodeDataVersion = "16.0.0",
                runId = "latin-kerning-ligature-run",
                textRangeStart = 0,
                textRangeEnd = 16,
                environment = FontTelemetryEnvironment(
                    environmentLabel = "developer-desktop",
                    runtimeLabel = "jdk-25-kotlin-2.3",
                ),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 450_000,
                        p90 = 650_000,
                        max = 720_000,
                        counters = mapOf("clusterCount" to 12L, "diagnosticCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.diagnostic.count",
                        unit = FontTelemetryUnit.Count,
                        median = 1,
                        p90 = 1,
                        max = 1,
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "telemetry-paragraph-repeat",
                domain = FontTelemetryDomain.Paragraph,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-latin-bundled-v1",
                unicodeDataVersion = "16.0.0",
                paragraphId = "rich-text-wrap-layout",
                textRangeStart = 0,
                textRangeEnd = 58,
                environment = FontTelemetryEnvironment(
                    environmentLabel = "developer-desktop",
                    runtimeLabel = "jdk-25-kotlin-2.3",
                ),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "paragraph.layout.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 2_100_000,
                        p90 = 2_400_000,
                        max = 2_650_000,
                        counters = mapOf("lineCount" to 3L, "styleRunCount" to 4L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "paragraph.style-run.count",
                        unit = FontTelemetryUnit.Count,
                        median = 4,
                        p90 = 4,
                        max = 4,
                    ),
                    FontTelemetryMetricSeries(
                        name = "paragraph.hit-test-index-build.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 170_000,
                        p90 = 220_000,
                        max = 250_000,
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "telemetry-glyph-artifact-repeat",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Mixed,
                fontSourceSetHash = "fontset-latin-bundled-v1",
                unicodeDataVersion = "16.0.0",
                environment = FontTelemetryEnvironment(
                    environmentLabel = "developer-desktop",
                    runtimeLabel = "jdk-25-kotlin-2.3",
                ),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.cache.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 4_096,
                        p90 = 6_144,
                        max = 8_192,
                        counters = mapOf("routeCount" to 6L, "uploadCount" to 0L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "telemetry-gpu-handoff-repeat",
                domain = FontTelemetryDomain.GPUTextHandoff,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-latin-bundled-v1",
                unicodeDataVersion = "16.0.0",
                environment = FontTelemetryEnvironment(
                    environmentLabel = "developer-desktop",
                    runtimeLabel = "jdk-25-kotlin-2.3",
                    gpuAdapter = "wgpu-nvidia-rtx-3070",
                    gpuBackend = "webgpu",
                ),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "gpu-text-handoff.upload.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 262_144,
                        p90 = 327_680,
                        max = 393_216,
                        counters = mapOf("routeCount" to 2L, "uploadCount" to 1L),
                    ),
                ),
            ),
        )
        val negativeCases = listOf(
            FontTelemetryNegativeCase(
                fixtureId = "telemetry-missing-measurement-phase",
                domain = FontTelemetryDomain.Parser.serializedName,
                diagnosticCode = "font.telemetry.dimension-missing",
                missingField = "measurementPhase",
                sampleCount = 1,
            ),
            FontTelemetryNegativeCase(
                fixtureId = "telemetry-single-run-budget-refusal",
                domain = FontTelemetryDomain.Shaping.serializedName,
                diagnosticCode = "font.telemetry.single-run-budget-refused",
                missingField = null,
                sampleCount = 1,
            ),
        )
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"font-telemetry-schema-fixture\",\n")
            append("  \"ownerTickets\": [\"KFONT-M12-001\"],\n")
            append("  \"samples\": [\n")
            append(repeatedSamples.joinToString(",\n") { "    ${it.toCanonicalJson()}" })
            append("\n  ],\n")
            append("  \"negativeCases\": [\n")
            append(negativeCases.joinToString(",\n") { "    ${it.toCanonicalJson()}" })
            append("\n  ],\n")
            append("  \"nonClaims\": ")
            append(defaultNonClaims().joinToString(prefix = "[", postfix = "]", separator = ", ") { it.quoted() })
            append("\n")
            append("}")
        }
    }
    fun writeParserMetricsJson(): String = FontTelemetryDomainDump(
        dumpId = "parser-metrics",
        ownerTickets = listOf("KFONT-M12-002"),
        domain = FontTelemetryDomain.Parser,
        trendSeriesPrefix = "font.parser",
        samples = listOf(
            FontTelemetrySample(
                fixtureId = "font-source-sfnt-single-ttf-provenance",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "cold-baseline",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-sfnt-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                tableTags = listOf("cmap", "head", "hhea", "hmtx", "maxp"),
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.scan.time",
                        trendSeriesId = "font.parser.scan.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 180_000,
                        p90 = 210_000,
                        max = 240_000,
                        counters = mapOf("bytesRead" to 2_048L, "tableCount" to 5L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        trendSeriesId = "font.parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 980_000,
                        p90 = 1_120_000,
                        max = 1_240_000,
                        counters = mapOf(
                            "tableCacheHit" to 0L,
                            "tableCacheMiss" to 5L,
                            "malformedTableCount" to 0L,
                            "boundsFailureCount" to 0L,
                            "diagnosticCount" to 0L,
                        ),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "font-source-sfnt-ttc-face-index-provenance",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-sfnt-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                tableTags = listOf("cmap", "head", "hhea", "hmtx", "maxp", "name"),
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.scan.time",
                        trendSeriesId = "font.parser.scan.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 240_000,
                        p90 = 280_000,
                        max = 320_000,
                        counters = mapOf("bytesRead" to 3_072L, "tableCount" to 6L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        trendSeriesId = "font.parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 1_180_000,
                        p90 = 1_340_000,
                        max = 1_480_000,
                        counters = mapOf(
                            "tableCacheHit" to 4L,
                            "tableCacheMiss" to 2L,
                            "malformedTableCount" to 0L,
                            "boundsFailureCount" to 0L,
                            "diagnosticCount" to 0L,
                        ),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "cff-cff2-scaler-selected-table-provenance",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-sfnt-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                tableTags = listOf("cff", "cmap", "head", "hhea", "hmtx", "maxp"),
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.scan.time",
                        trendSeriesId = "font.parser.scan.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 260_000,
                        p90 = 300_000,
                        max = 340_000,
                        counters = mapOf("bytesRead" to 2_816L, "tableCount" to 6L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        trendSeriesId = "font.parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 1_240_000,
                        p90 = 1_390_000,
                        max = 1_520_000,
                        counters = mapOf(
                            "tableCacheHit" to 3L,
                            "tableCacheMiss" to 3L,
                            "malformedTableCount" to 0L,
                            "boundsFailureCount" to 0L,
                            "diagnosticCount" to 0L,
                        ),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "truetype-scaler-truetype-avar-coordinate-mapping",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-sfnt-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                tableTags = listOf("avar", "cmap", "fvar", "gvar", "head", "hhea", "hmtx", "maxp"),
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.scan.time",
                        trendSeriesId = "font.parser.scan.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 300_000,
                        p90 = 340_000,
                        max = 390_000,
                        counters = mapOf("bytesRead" to 3_584L, "tableCount" to 8L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        trendSeriesId = "font.parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 1_360_000,
                        p90 = 1_520_000,
                        max = 1_680_000,
                        counters = mapOf(
                            "tableCacheHit" to 4L,
                            "tableCacheMiss" to 4L,
                            "malformedTableCount" to 0L,
                            "boundsFailureCount" to 0L,
                            "diagnosticCount" to 0L,
                        ),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "font-source-sfnt-malformed-directory-diagnostic",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "cold-baseline",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-sfnt-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                tableTags = listOf("cmap", "head", "hhea", "maxp"),
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.scan.time",
                        trendSeriesId = "font.parser.scan.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 210_000,
                        p90 = 250_000,
                        max = 290_000,
                        counters = mapOf("bytesRead" to 1_536L, "tableCount" to 4L, "diagnosticCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        trendSeriesId = "font.parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 1_080_000,
                        p90 = 1_220_000,
                        max = 1_340_000,
                        counters = mapOf(
                            "tableCacheHit" to 0L,
                            "tableCacheMiss" to 4L,
                            "malformedTableCount" to 1L,
                            "boundsFailureCount" to 1L,
                            "diagnosticCount" to 1L,
                        ),
                    ),
                ),
                diagnostics = listOf("font.sfnt.table-overlap"),
            ),
            FontTelemetrySample(
                fixtureId = "font-source-sfnt-malformed-optional-table-diagnostic",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "cold-baseline",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-sfnt-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                tableTags = listOf("cmap", "gpos", "head", "hhea", "hmtx", "maxp"),
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.scan.time",
                        trendSeriesId = "font.parser.scan.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 250_000,
                        p90 = 290_000,
                        max = 330_000,
                        counters = mapOf("bytesRead" to 2_688L, "tableCount" to 6L, "diagnosticCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        trendSeriesId = "font.parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 1_410_000,
                        p90 = 1_590_000,
                        max = 1_760_000,
                        counters = mapOf(
                            "tableCacheHit" to 0L,
                            "tableCacheMiss" to 6L,
                            "malformedTableCount" to 1L,
                            "boundsFailureCount" to 1L,
                            "diagnosticCount" to 1L,
                        ),
                    ),
                ),
                diagnostics = listOf("font.sfnt.optional-table-malformed"),
            ),
            FontTelemetrySample(
                fixtureId = "font-source-sfnt-malformed-required-table-diagnostic",
                domain = FontTelemetryDomain.Parser,
                measurementPhase = "cold-baseline",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-sfnt-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                tableTags = listOf("cmap", "head", "maxp"),
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "parser.scan.time",
                        trendSeriesId = "font.parser.scan.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 170_000,
                        p90 = 200_000,
                        max = 230_000,
                        counters = mapOf("bytesRead" to 1_024L, "tableCount" to 3L, "diagnosticCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "parser.parse.time",
                        trendSeriesId = "font.parser.parse.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 820_000,
                        p90 = 930_000,
                        max = 1_020_000,
                        counters = mapOf(
                            "tableCacheHit" to 0L,
                            "tableCacheMiss" to 3L,
                            "malformedTableCount" to 1L,
                            "boundsFailureCount" to 0L,
                            "diagnosticCount" to 1L,
                        ),
                    ),
                ),
                diagnostics = listOf("font.sfnt.required-table-missing"),
            ),
        ),
        negativeCases = listOf(
            FontTelemetryNegativeCase(
                fixtureId = "parser-metrics-missing-metrics",
                domain = FontTelemetryDomain.Parser.serializedName,
                diagnosticCode = "font.telemetry.dimension-missing",
                missingField = "metrics",
                sampleCount = 1,
            ),
        ),
        nonClaims = defaultNonClaims(),
    ).toCanonicalJson()

    fun writeScalerMetricsJson(): String = FontTelemetryDomainDump(
        dumpId = "scaler-metrics",
        ownerTickets = listOf("KFONT-M12-002"),
        domain = FontTelemetryDomain.Scaler,
        trendSeriesPrefix = "font.scaler",
        samples = listOf(
            FontTelemetrySample(
                fixtureId = "truetype-scaler-truetype-simple-glyph",
                domain = FontTelemetryDomain.Scaler,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-scaler-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "scaler.outline.time",
                        trendSeriesId = "font.scaler.outline.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 520_000,
                        p90 = 610_000,
                        max = 690_000,
                        counters = mapOf("glyphCount" to 1L, "outlineCommandCount" to 5L, "scalerCacheHit" to 4L, "scalerCacheMiss" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.bounds.time",
                        trendSeriesId = "font.scaler.bounds.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 140_000,
                        p90 = 170_000,
                        max = 190_000,
                        counters = mapOf("notdefFallbackCount" to 0L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.metrics.time",
                        trendSeriesId = "font.scaler.metrics.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 120_000,
                        p90 = 150_000,
                        max = 180_000,
                        counters = mapOf("diagnosticCount" to 0L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "truetype-scaler-truetype-composite-glyph-transform",
                domain = FontTelemetryDomain.Scaler,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-scaler-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "scaler.outline.time",
                        trendSeriesId = "font.scaler.outline.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 760_000,
                        p90 = 880_000,
                        max = 980_000,
                        counters = mapOf("glyphCount" to 1L, "outlineCommandCount" to 10L, "scalerCacheHit" to 3L, "scalerCacheMiss" to 2L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.bounds.time",
                        trendSeriesId = "font.scaler.bounds.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 180_000,
                        p90 = 220_000,
                        max = 250_000,
                        counters = mapOf("notdefFallbackCount" to 0L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.metrics.time",
                        trendSeriesId = "font.scaler.metrics.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 150_000,
                        p90 = 190_000,
                        max = 220_000,
                        counters = mapOf("diagnosticCount" to 0L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "truetype-scaler-truetype-gvar-simple-delta",
                domain = FontTelemetryDomain.Scaler,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-scaler-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "scaler.outline.time",
                        trendSeriesId = "font.scaler.outline.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 680_000,
                        p90 = 790_000,
                        max = 870_000,
                        counters = mapOf("glyphCount" to 1L, "outlineCommandCount" to 5L, "scalerCacheHit" to 3L, "scalerCacheMiss" to 2L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.metrics.time",
                        trendSeriesId = "font.scaler.metrics.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 190_000,
                        p90 = 240_000,
                        max = 280_000,
                        counters = mapOf("notdefFallbackCount" to 0L, "diagnosticCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.variation.time",
                        trendSeriesId = "font.scaler.variation.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 210_000,
                        p90 = 260_000,
                        max = 310_000,
                        counters = mapOf("diagnosticCount" to 1L),
                    ),
                ),
                diagnostics = listOf("font.metrics-variation-unavailable"),
            ),
            FontTelemetrySample(
                fixtureId = "cff-cff2-scaler-cff-local-global-subroutines",
                domain = FontTelemetryDomain.Scaler,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-scaler-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "scaler.outline.time",
                        trendSeriesId = "font.scaler.outline.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 840_000,
                        p90 = 960_000,
                        max = 1_080_000,
                        counters = mapOf("glyphCount" to 1L, "outlineCommandCount" to 8L, "scalerCacheHit" to 2L, "scalerCacheMiss" to 3L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.charstring.time",
                        trendSeriesId = "font.scaler.charstring.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 260_000,
                        p90 = 310_000,
                        max = 360_000,
                        counters = mapOf("notdefFallbackCount" to 0L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.metrics.time",
                        trendSeriesId = "font.scaler.metrics.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 170_000,
                        p90 = 210_000,
                        max = 250_000,
                        counters = mapOf("diagnosticCount" to 0L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "cff-cff2-scaler-cff2-variation-store-region",
                domain = FontTelemetryDomain.Scaler,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-scaler-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "scaler.outline.time",
                        trendSeriesId = "font.scaler.outline.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 900_000,
                        p90 = 1_040_000,
                        max = 1_180_000,
                        counters = mapOf("glyphCount" to 1L, "outlineCommandCount" to 9L, "scalerCacheHit" to 2L, "scalerCacheMiss" to 3L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.charstring.time",
                        trendSeriesId = "font.scaler.charstring.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 290_000,
                        p90 = 340_000,
                        max = 390_000,
                        counters = mapOf("notdefFallbackCount" to 0L, "diagnosticCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.variation.time",
                        trendSeriesId = "font.scaler.variation.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 240_000,
                        p90 = 290_000,
                        max = 340_000,
                        counters = mapOf("diagnosticCount" to 0L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "cff-cff2-scaler-malformed-index-dict-refusal",
                domain = FontTelemetryDomain.Scaler,
                measurementPhase = "cold-baseline",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-scaler-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "scaler.outline.time",
                        trendSeriesId = "font.scaler.outline.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 430_000,
                        p90 = 500_000,
                        max = 580_000,
                        counters = mapOf("glyphCount" to 1L, "outlineCommandCount" to 0L, "scalerCacheHit" to 0L, "scalerCacheMiss" to 5L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "scaler.metrics.time",
                        trendSeriesId = "font.scaler.metrics.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 110_000,
                        p90 = 130_000,
                        max = 160_000,
                        counters = mapOf("notdefFallbackCount" to 1L, "diagnosticCount" to 1L),
                    ),
                ),
                diagnostics = listOf("font.cff-table-malformed"),
            ),
        ),
        negativeCases = listOf(
            FontTelemetryNegativeCase(
                fixtureId = "scaler-metrics-unsupported-domain",
                domain = FontTelemetryDomain.Scaler.serializedName,
                diagnosticCode = "font.telemetry.scaler-domain-missing",
                missingField = null,
                sampleCount = 1,
            ),
        ),
        nonClaims = defaultNonClaims(),
    ).toCanonicalJson()

    fun writeShapingMetricsJson(): String = FontTelemetryDomainDump(
        dumpId = "shaping-metrics",
        ownerTickets = listOf("KFONT-M12-003"),
        domain = FontTelemetryDomain.Shaping,
        samples = listOf(
            FontTelemetrySample(
                fixtureId = "telemetry-shaping-repeat",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-latin-bundled-v1",
                unicodeDataVersion = "16.0.0",
                runId = "latin-kerning-ligature-run",
                textRangeStart = 0,
                textRangeEnd = 16,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "shaping.segmentation.time", unit = FontTelemetryUnit.Nanoseconds, median = 180_000, p90 = 240_000, max = 260_000),
                    FontTelemetryMetricSeries(name = "shaping.bidi.time", unit = FontTelemetryUnit.Nanoseconds, median = 90_000, p90 = 120_000, max = 140_000),
                    FontTelemetryMetricSeries(name = "shaping.script-itemization.time", unit = FontTelemetryUnit.Nanoseconds, median = 110_000, p90 = 145_000, max = 165_000),
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 450_000,
                        p90 = 650_000,
                        max = 720_000,
                        counters = mapOf("fallbackRunCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gsub.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 310_000,
                        p90 = 390_000,
                        max = 430_000,
                        counters = mapOf("gsubLookupCount" to 2L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gpos.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 150_000,
                        p90 = 210_000,
                        max = 235_000,
                        counters = mapOf("gposLookupCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(name = "shaping.glyph.count", unit = FontTelemetryUnit.Count, median = 14, p90 = 14, max = 14),
                    FontTelemetryMetricSeries(name = "shaping.cluster.count", unit = FontTelemetryUnit.Count, median = 12, p90 = 12, max = 12),
                    FontTelemetryMetricSeries(name = "shaping.diagnostic.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "arabic-shaped-glyph-run",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-arabic-vendored-v1",
                unicodeDataVersion = "16.0.0",
                runId = "arabic-joining-run",
                textRangeStart = 0,
                textRangeEnd = 8,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "shaping.segmentation.time", unit = FontTelemetryUnit.Nanoseconds, median = 260_000, p90 = 330_000, max = 360_000),
                    FontTelemetryMetricSeries(name = "shaping.bidi.time", unit = FontTelemetryUnit.Nanoseconds, median = 175_000, p90 = 240_000, max = 270_000),
                    FontTelemetryMetricSeries(name = "shaping.script-itemization.time", unit = FontTelemetryUnit.Nanoseconds, median = 130_000, p90 = 170_000, max = 185_000),
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 210_000,
                        p90 = 260_000,
                        max = 290_000,
                        counters = mapOf("fallbackRunCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gsub.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 610_000,
                        p90 = 760_000,
                        max = 820_000,
                        counters = mapOf("gsubLookupCount" to 4L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gpos.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 490_000,
                        p90 = 610_000,
                        max = 670_000,
                        counters = mapOf("gposLookupCount" to 3L),
                    ),
                    FontTelemetryMetricSeries(name = "shaping.glyph.count", unit = FontTelemetryUnit.Count, median = 7, p90 = 7, max = 7),
                    FontTelemetryMetricSeries(name = "shaping.cluster.count", unit = FontTelemetryUnit.Count, median = 4, p90 = 4, max = 4),
                    FontTelemetryMetricSeries(name = "shaping.diagnostic.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "devanagari-shaping-report",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-devanagari-vendored-v1",
                unicodeDataVersion = "16.0.0",
                runId = "devanagari-reordering-run",
                textRangeStart = 0,
                textRangeEnd = 6,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "shaping.segmentation.time", unit = FontTelemetryUnit.Nanoseconds, median = 240_000, p90 = 300_000, max = 335_000),
                    FontTelemetryMetricSeries(name = "shaping.bidi.time", unit = FontTelemetryUnit.Nanoseconds, median = 70_000, p90 = 95_000, max = 110_000),
                    FontTelemetryMetricSeries(name = "shaping.script-itemization.time", unit = FontTelemetryUnit.Nanoseconds, median = 145_000, p90 = 190_000, max = 215_000),
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 220_000,
                        p90 = 290_000,
                        max = 315_000,
                        counters = mapOf("fallbackRunCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gsub.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 720_000,
                        p90 = 890_000,
                        max = 970_000,
                        counters = mapOf("gsubLookupCount" to 5L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gpos.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 405_000,
                        p90 = 520_000,
                        max = 575_000,
                        counters = mapOf("gposLookupCount" to 2L),
                    ),
                    FontTelemetryMetricSeries(name = "shaping.glyph.count", unit = FontTelemetryUnit.Count, median = 6, p90 = 6, max = 6),
                    FontTelemetryMetricSeries(name = "shaping.cluster.count", unit = FontTelemetryUnit.Count, median = 3, p90 = 3, max = 3),
                    FontTelemetryMetricSeries(name = "shaping.diagnostic.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "fallback-cluster-thai",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Mixed,
                fontSourceSetHash = "fontset-thai-fallback-v1",
                unicodeDataVersion = "16.0.0",
                runId = "thai-mark-fallback-run",
                textRangeStart = 0,
                textRangeEnd = 5,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "shaping.segmentation.time", unit = FontTelemetryUnit.Nanoseconds, median = 195_000, p90 = 250_000, max = 280_000),
                    FontTelemetryMetricSeries(name = "shaping.bidi.time", unit = FontTelemetryUnit.Nanoseconds, median = 55_000, p90 = 80_000, max = 95_000),
                    FontTelemetryMetricSeries(name = "shaping.script-itemization.time", unit = FontTelemetryUnit.Nanoseconds, median = 115_000, p90 = 150_000, max = 170_000),
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 330_000,
                        p90 = 420_000,
                        max = 465_000,
                        counters = mapOf("fallbackRunCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gsub.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 260_000,
                        p90 = 340_000,
                        max = 375_000,
                        counters = mapOf("gsubLookupCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gpos.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 210_000,
                        p90 = 275_000,
                        max = 305_000,
                        counters = mapOf("gposLookupCount" to 2L),
                    ),
                    FontTelemetryMetricSeries(name = "shaping.glyph.count", unit = FontTelemetryUnit.Count, median = 5, p90 = 5, max = 5),
                    FontTelemetryMetricSeries(name = "shaping.cluster.count", unit = FontTelemetryUnit.Count, median = 3, p90 = 3, max = 3),
                    FontTelemetryMetricSeries(name = "shaping.diagnostic.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "fallback-cluster-cjk-vs",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-cjk-vendored-v1",
                unicodeDataVersion = "16.0.0",
                runId = "cjk-variation-selector-run",
                textRangeStart = 0,
                textRangeEnd = 2,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "shaping.segmentation.time", unit = FontTelemetryUnit.Nanoseconds, median = 155_000, p90 = 205_000, max = 225_000),
                    FontTelemetryMetricSeries(name = "shaping.bidi.time", unit = FontTelemetryUnit.Nanoseconds, median = 45_000, p90 = 65_000, max = 75_000),
                    FontTelemetryMetricSeries(name = "shaping.script-itemization.time", unit = FontTelemetryUnit.Nanoseconds, median = 120_000, p90 = 150_000, max = 170_000),
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 140_000,
                        p90 = 190_000,
                        max = 210_000,
                        counters = mapOf("fallbackRunCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gsub.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 95_000,
                        p90 = 125_000,
                        max = 145_000,
                        counters = mapOf("gsubLookupCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gpos.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 90_000,
                        p90 = 120_000,
                        max = 140_000,
                        counters = mapOf("gposLookupCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(name = "shaping.glyph.count", unit = FontTelemetryUnit.Count, median = 2, p90 = 2, max = 2),
                    FontTelemetryMetricSeries(name = "shaping.cluster.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                    FontTelemetryMetricSeries(name = "shaping.diagnostic.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "fallback-cluster-mixed-bidi",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Mixed,
                fontSourceSetHash = "fontset-mixed-fallback-v1",
                unicodeDataVersion = "16.0.0",
                runId = "mixed-bidi-fallback-run",
                textRangeStart = 0,
                textRangeEnd = 12,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "shaping.segmentation.time", unit = FontTelemetryUnit.Nanoseconds, median = 205_000, p90 = 270_000, max = 300_000),
                    FontTelemetryMetricSeries(name = "shaping.bidi.time", unit = FontTelemetryUnit.Nanoseconds, median = 180_000, p90 = 235_000, max = 260_000),
                    FontTelemetryMetricSeries(name = "shaping.script-itemization.time", unit = FontTelemetryUnit.Nanoseconds, median = 145_000, p90 = 190_000, max = 210_000),
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 510_000,
                        p90 = 650_000,
                        max = 710_000,
                        counters = mapOf("fallbackRunCount" to 2L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gsub.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 350_000,
                        p90 = 430_000,
                        max = 470_000,
                        counters = mapOf("gsubLookupCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gpos.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 240_000,
                        p90 = 310_000,
                        max = 345_000,
                        counters = mapOf("gposLookupCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(name = "shaping.glyph.count", unit = FontTelemetryUnit.Count, median = 10, p90 = 10, max = 10),
                    FontTelemetryMetricSeries(name = "shaping.cluster.count", unit = FontTelemetryUnit.Count, median = 8, p90 = 8, max = 8),
                    FontTelemetryMetricSeries(name = "shaping.diagnostic.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                ),
                diagnostics = listOf("text.shaping.fallback-missing"),
            ),
            FontTelemetrySample(
                fixtureId = "emoji-sequence-refusal",
                domain = FontTelemetryDomain.Shaping,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Mixed,
                fontSourceSetHash = "fontset-emoji-sequence-v1",
                unicodeDataVersion = "16.0.0",
                runId = "emoji-sequence-refusal-run",
                textRangeStart = 0,
                textRangeEnd = 5,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "shaping.segmentation.time", unit = FontTelemetryUnit.Nanoseconds, median = 215_000, p90 = 280_000, max = 315_000),
                    FontTelemetryMetricSeries(name = "shaping.bidi.time", unit = FontTelemetryUnit.Nanoseconds, median = 50_000, p90 = 72_000, max = 88_000),
                    FontTelemetryMetricSeries(name = "shaping.script-itemization.time", unit = FontTelemetryUnit.Nanoseconds, median = 135_000, p90 = 175_000, max = 198_000),
                    FontTelemetryMetricSeries(
                        name = "shaping.fallback.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 415_000,
                        p90 = 540_000,
                        max = 590_000,
                        counters = mapOf("fallbackRunCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gsub.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 230_000,
                        p90 = 300_000,
                        max = 330_000,
                        counters = mapOf("gsubLookupCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "shaping.gpos.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 120_000,
                        p90 = 165_000,
                        max = 190_000,
                        counters = mapOf("gposLookupCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(name = "shaping.glyph.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                    FontTelemetryMetricSeries(name = "shaping.cluster.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                    FontTelemetryMetricSeries(name = "shaping.diagnostic.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                ),
                diagnostics = listOf("text.shaping.emoji-sequence-unsupported"),
            ),
        ),
        negativeCases = emptyList(),
        nonClaims = commonNonClaims(),
    ).toCanonicalJson()

    fun writeParagraphMetricsJson(): String = FontTelemetryDomainDump(
        dumpId = "paragraph-metrics",
        ownerTickets = listOf("KFONT-M12-003"),
        domain = FontTelemetryDomain.Paragraph,
        samples = listOf(
            FontTelemetrySample(
                fixtureId = "paragraph-shaping-requests",
                domain = FontTelemetryDomain.Paragraph,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-rich-style-bundled-v1",
                unicodeDataVersion = "16.0.0",
                paragraphId = "rich-text-wrap-layout",
                textRangeStart = 0,
                textRangeEnd = 58,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "paragraph.layout.time", unit = FontTelemetryUnit.Nanoseconds, median = 2_100_000, p90 = 2_400_000, max = 2_650_000),
                    FontTelemetryMetricSeries(name = "paragraph.style-run.count", unit = FontTelemetryUnit.Count, median = 4, p90 = 4, max = 4),
                    FontTelemetryMetricSeries(name = "paragraph.line-break-opportunity.count", unit = FontTelemetryUnit.Count, median = 11, p90 = 11, max = 11),
                    FontTelemetryMetricSeries(name = "paragraph.shaped-run.count", unit = FontTelemetryUnit.Count, median = 4, p90 = 4, max = 4),
                    FontTelemetryMetricSeries(name = "paragraph.line.count", unit = FontTelemetryUnit.Count, median = 3, p90 = 3, max = 3),
                    FontTelemetryMetricSeries(name = "paragraph.hit-test-index-build.time", unit = FontTelemetryUnit.Nanoseconds, median = 170_000, p90 = 220_000, max = 250_000),
                    FontTelemetryMetricSeries(name = "paragraph.selection-query.time", unit = FontTelemetryUnit.Nanoseconds, median = 160_000, p90 = 205_000, max = 235_000),
                    FontTelemetryMetricSeries(name = "paragraph.ellipsis.attempt.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                    FontTelemetryMetricSeries(name = "paragraph.placeholder.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "paragraph-layout",
                domain = FontTelemetryDomain.Paragraph,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-rich-style-bundled-v1",
                unicodeDataVersion = "16.0.0",
                paragraphId = "wrapped-rich-paragraph",
                textRangeStart = 0,
                textRangeEnd = 72,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "paragraph.layout.time", unit = FontTelemetryUnit.Nanoseconds, median = 2_350_000, p90 = 2_700_000, max = 2_950_000),
                    FontTelemetryMetricSeries(name = "paragraph.style-run.count", unit = FontTelemetryUnit.Count, median = 5, p90 = 5, max = 5),
                    FontTelemetryMetricSeries(name = "paragraph.line-break-opportunity.count", unit = FontTelemetryUnit.Count, median = 15, p90 = 15, max = 15),
                    FontTelemetryMetricSeries(name = "paragraph.shaped-run.count", unit = FontTelemetryUnit.Count, median = 5, p90 = 5, max = 5),
                    FontTelemetryMetricSeries(name = "paragraph.line.count", unit = FontTelemetryUnit.Count, median = 4, p90 = 4, max = 4),
                    FontTelemetryMetricSeries(name = "paragraph.hit-test-index-build.time", unit = FontTelemetryUnit.Nanoseconds, median = 190_000, p90 = 245_000, max = 275_000),
                    FontTelemetryMetricSeries(name = "paragraph.selection-query.time", unit = FontTelemetryUnit.Nanoseconds, median = 175_000, p90 = 225_000, max = 255_000),
                    FontTelemetryMetricSeries(name = "paragraph.ellipsis.attempt.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                    FontTelemetryMetricSeries(name = "paragraph.placeholder.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "line-breaks",
                domain = FontTelemetryDomain.Paragraph,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-mixed-bidi-v1",
                unicodeDataVersion = "16.0.0",
                paragraphId = "mixed-bidi-line-breaks",
                textRangeStart = 0,
                textRangeEnd = 84,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "paragraph.layout.time", unit = FontTelemetryUnit.Nanoseconds, median = 2_600_000, p90 = 2_980_000, max = 3_200_000),
                    FontTelemetryMetricSeries(name = "paragraph.style-run.count", unit = FontTelemetryUnit.Count, median = 6, p90 = 6, max = 6),
                    FontTelemetryMetricSeries(name = "paragraph.line-break-opportunity.count", unit = FontTelemetryUnit.Count, median = 18, p90 = 18, max = 18),
                    FontTelemetryMetricSeries(name = "paragraph.shaped-run.count", unit = FontTelemetryUnit.Count, median = 6, p90 = 6, max = 6),
                    FontTelemetryMetricSeries(name = "paragraph.line.count", unit = FontTelemetryUnit.Count, median = 4, p90 = 4, max = 4),
                    FontTelemetryMetricSeries(name = "paragraph.hit-test-index-build.time", unit = FontTelemetryUnit.Nanoseconds, median = 205_000, p90 = 260_000, max = 290_000),
                    FontTelemetryMetricSeries(name = "paragraph.selection-query.time", unit = FontTelemetryUnit.Nanoseconds, median = 195_000, p90 = 250_000, max = 285_000),
                    FontTelemetryMetricSeries(name = "paragraph.ellipsis.attempt.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                    FontTelemetryMetricSeries(name = "paragraph.placeholder.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "hit-test-map",
                domain = FontTelemetryDomain.Paragraph,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-rich-style-bundled-v1",
                unicodeDataVersion = "16.0.0",
                paragraphId = "paragraph-hit-test-index",
                textRangeStart = 0,
                textRangeEnd = 41,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "paragraph.layout.time", unit = FontTelemetryUnit.Nanoseconds, median = 1_950_000, p90 = 2_240_000, max = 2_410_000),
                    FontTelemetryMetricSeries(name = "paragraph.style-run.count", unit = FontTelemetryUnit.Count, median = 3, p90 = 3, max = 3),
                    FontTelemetryMetricSeries(name = "paragraph.line-break-opportunity.count", unit = FontTelemetryUnit.Count, median = 9, p90 = 9, max = 9),
                    FontTelemetryMetricSeries(name = "paragraph.shaped-run.count", unit = FontTelemetryUnit.Count, median = 3, p90 = 3, max = 3),
                    FontTelemetryMetricSeries(name = "paragraph.line.count", unit = FontTelemetryUnit.Count, median = 2, p90 = 2, max = 2),
                    FontTelemetryMetricSeries(name = "paragraph.hit-test-index-build.time", unit = FontTelemetryUnit.Nanoseconds, median = 95_000, p90 = 125_000, max = 150_000),
                    FontTelemetryMetricSeries(name = "paragraph.selection-query.time", unit = FontTelemetryUnit.Nanoseconds, median = 130_000, p90 = 175_000, max = 205_000),
                    FontTelemetryMetricSeries(name = "paragraph.ellipsis.attempt.count", unit = FontTelemetryUnit.Count, median = 0, p90 = 0, max = 0),
                    FontTelemetryMetricSeries(name = "paragraph.placeholder.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "placeholder-layout",
                domain = FontTelemetryDomain.Paragraph,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-rich-style-bundled-v1",
                unicodeDataVersion = "16.0.0",
                paragraphId = "placeholder-ellipsis-layout",
                textRangeStart = 0,
                textRangeEnd = 47,
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(name = "paragraph.layout.time", unit = FontTelemetryUnit.Nanoseconds, median = 2_420_000, p90 = 2_760_000, max = 3_040_000),
                    FontTelemetryMetricSeries(name = "paragraph.style-run.count", unit = FontTelemetryUnit.Count, median = 4, p90 = 4, max = 4),
                    FontTelemetryMetricSeries(name = "paragraph.line-break-opportunity.count", unit = FontTelemetryUnit.Count, median = 12, p90 = 12, max = 12),
                    FontTelemetryMetricSeries(name = "paragraph.shaped-run.count", unit = FontTelemetryUnit.Count, median = 4, p90 = 4, max = 4),
                    FontTelemetryMetricSeries(name = "paragraph.line.count", unit = FontTelemetryUnit.Count, median = 3, p90 = 3, max = 3),
                    FontTelemetryMetricSeries(name = "paragraph.hit-test-index-build.time", unit = FontTelemetryUnit.Nanoseconds, median = 115_000, p90 = 145_000, max = 175_000),
                    FontTelemetryMetricSeries(name = "paragraph.selection-query.time", unit = FontTelemetryUnit.Nanoseconds, median = 145_000, p90 = 185_000, max = 215_000),
                    FontTelemetryMetricSeries(name = "paragraph.ellipsis.attempt.count", unit = FontTelemetryUnit.Count, median = 1, p90 = 1, max = 1),
                    FontTelemetryMetricSeries(name = "paragraph.placeholder.count", unit = FontTelemetryUnit.Count, median = 2, p90 = 2, max = 2),
                ),
                diagnostics = listOf("text.paragraph.placeholder-ellipsis-conflict"),
            ),
        ),
        negativeCases = emptyList(),
        nonClaims = commonNonClaims(),
    ).toCanonicalJson()

    fun writeGlyphArtifactMetricsJson(): String = FontTelemetryDomainDump(
        dumpId = "glyph-artifact-metrics",
        ownerTickets = listOf("KFONT-M12-004"),
        domain = FontTelemetryDomain.GlyphArtifact,
        trendSeriesPrefix = "font.glyph.artifact",
        samples = listOf(
            FontTelemetrySample(
                fixtureId = "glyph-artifact-plan-placeholders",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "cold-baseline",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-glyph-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.route.count",
                        trendSeriesId = "font.glyph.artifact.route.count",
                        unit = FontTelemetryUnit.Count,
                        median = 5,
                        p90 = 5,
                        max = 5,
                        counters = mapOf(
                            "outlineRouteCount" to 1L,
                            "colrRouteCount" to 1L,
                            "bitmapRouteCount" to 1L,
                            "svgRouteCount" to 1L,
                            "unsupportedRouteCount" to 1L,
                            "refusalCount" to 1L,
                        ),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "glyph-artifact-plan-policy-refusals",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Mixed,
                fontSourceSetHash = "fontset-glyph-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.route.count",
                        trendSeriesId = "font.glyph.artifact.route.count",
                        unit = FontTelemetryUnit.Count,
                        median = 4,
                        p90 = 4,
                        max = 4,
                        counters = mapOf(
                            "outlineRouteCount" to 2L,
                            "a8RouteCount" to 1L,
                            "sdfRouteCount" to 1L,
                            "atlasCapacityRefusalCount" to 1L,
                            "sdfTransformRefusalCount" to 1L,
                        ),
                    ),
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.a8.time",
                        trendSeriesId = "font.glyph.artifact.a8.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 280_000,
                        p90 = 320_000,
                        max = 360_000,
                        counters = mapOf("sourceMaskHashCount" to 1L),
                    ),
                ),
                diagnostics = listOf("text.glyph.SDF-transform-unsupported", "text.glyph.atlas-capacity-exceeded"),
            ),
            FontTelemetrySample(
                fixtureId = "sdf-default-spread",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-glyph-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.sdf.time",
                        trendSeriesId = "font.glyph.artifact.sdf.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 520_000,
                        p90 = 600_000,
                        max = 680_000,
                        counters = mapOf("sourceMaskHashCount" to 1L, "spreadPx" to 8L, "sourceResolutionPx" to 16L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "colrv0-layered-palette-override",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-color-glyph-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.color.time",
                        trendSeriesId = "font.glyph.artifact.color.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 540_000,
                        p90 = 620_000,
                        max = 700_000,
                        counters = mapOf("colorLayerCount" to 2L, "paletteOverrideCount" to 1L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "cbdt-cblc-png",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-color-glyph-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.bitmap.time",
                        trendSeriesId = "font.glyph.artifact.bitmap.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 330_000,
                        p90 = 390_000,
                        max = 440_000,
                        counters = mapOf("decodedPixelCount" to 2L, "bitmapPlanCount" to 1L),
                    ),
                ),
            ),
            FontTelemetrySample(
                fixtureId = "path-and-basic-shape",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-color-glyph-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.svg.time",
                        trendSeriesId = "font.glyph.artifact.svg.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 410_000,
                        p90 = 480_000,
                        max = 540_000,
                        counters = mapOf("primitiveCount" to 2L, "pathCommandCount" to 2L),
                    ),
                ),
            ),
        ),
        negativeCases = emptyList(),
        nonClaims = glyphTelemetryNonClaims(),
    ).toCanonicalJson()

    fun writeGlyphCacheMetricsJson(): String = FontTelemetryDomainDump(
        dumpId = "glyph-cache-metrics",
        ownerTickets = listOf("KFONT-M12-004"),
        domain = FontTelemetryDomain.GlyphArtifact,
        trendSeriesPrefix = "font.glyph.cache",
        samples = listOf(
            FontTelemetrySample(
                fixtureId = "cold-cache-sample",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "cold-baseline",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Cold,
                fontSourceSetHash = "fontset-glyph-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.atlas-pack.time",
                        trendSeriesId = "font.glyph.cache.atlas-pack.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 600_000,
                        p90 = 800_000,
                        max = 900_000,
                        counters = mapOf("strikeKeyCount" to 3L, "cacheMissCount" to 3L, "evictionCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.cache.bytes",
                        trendSeriesId = "font.glyph.cache.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 352,
                        p90 = 352,
                        max = 352,
                        counters = mapOf("cacheHitCount" to 0L, "cacheMissCount" to 3L, "invalidationTokenChangeCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.upload.bytes",
                        trendSeriesId = "font.glyph.cache.upload.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 768,
                        p90 = 768,
                        max = 768,
                        counters = mapOf("uploadCount" to 1L, "artifactBudgetRefusalCount" to 1L),
                    ),
                ),
                diagnostics = listOf("text.glyph.artifact-budget-exceeded"),
            ),
            FontTelemetrySample(
                fixtureId = "warm-cache-sample",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "warm-repeat",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Warm,
                fontSourceSetHash = "fontset-glyph-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.atlas-pack.time",
                        trendSeriesId = "font.glyph.cache.atlas-pack.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 100_000,
                        p90 = 120_000,
                        max = 130_000,
                        counters = mapOf("strikeKeyCount" to 3L, "cacheHitCount" to 3L, "evictionCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.cache.bytes",
                        trendSeriesId = "font.glyph.cache.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 352,
                        p90 = 352,
                        max = 352,
                        counters = mapOf("cacheHitCount" to 3L, "cacheMissCount" to 0L, "invalidationTokenChangeCount" to 0L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.upload.bytes",
                        trendSeriesId = "font.glyph.cache.upload.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 256,
                        p90 = 256,
                        max = 256,
                        counters = mapOf("uploadCount" to 1L, "artifactBudgetRefusalCount" to 1L),
                    ),
                ),
                diagnostics = listOf("text.glyph.artifact-budget-exceeded"),
            ),
            FontTelemetrySample(
                fixtureId = "stale-generation",
                domain = FontTelemetryDomain.GlyphArtifact,
                measurementPhase = "steady-state",
                sampleCount = 5,
                cacheState = FontTelemetryCacheState.Mixed,
                fontSourceSetHash = "fontset-glyph-telemetry-v1",
                unicodeDataVersion = "16.0.0",
                environment = defaultEnvironment(),
                metrics = listOf(
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.atlas-pack.time",
                        trendSeriesId = "font.glyph.cache.atlas-pack.time",
                        unit = FontTelemetryUnit.Nanoseconds,
                        median = 720_000,
                        p90 = 860_000,
                        max = 980_000,
                        counters = mapOf(
                            "strikeKeyCount" to 3L,
                            "evictionCount" to 2L,
                            "staleGenerationRefusalCount" to 1L,
                            "atlasCapacityRefusalCount" to 1L,
                        ),
                    ),
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.cache.bytes",
                        trendSeriesId = "font.glyph.cache.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 224,
                        p90 = 224,
                        max = 224,
                        counters = mapOf("residentBytesBefore" to 224L, "residentBytesAfter" to 96L, "invalidationTokenChangeCount" to 1L),
                    ),
                    FontTelemetryMetricSeries(
                        name = "glyph-artifact.upload.bytes",
                        trendSeriesId = "font.glyph.cache.upload.bytes",
                        unit = FontTelemetryUnit.Bytes,
                        median = 96,
                        p90 = 96,
                        max = 96,
                        counters = mapOf("uploadCount" to 1L, "keyPreimageHashCount" to 2L),
                    ),
                ),
                diagnostics = listOf("text.glyph.atlas-capacity-exceeded", "text.glyph.atlas-generation-stale"),
            ),
        ),
        negativeCases = emptyList(),
        nonClaims = glyphTelemetryNonClaims(),
    ).toCanonicalJson()

    fun writeGlyphAtlasOccupancyJson(): String = GlyphAtlasOccupancyDump(
        dumpId = "glyph-atlas-occupancy",
        ownerTickets = listOf("KFONT-M12-004"),
        sourceDumps = listOf(
            "font/fixtures/expected/glyph/glyph-atlas.json",
            "font/fixtures/expected/glyph/glyph-cache-inventory.json",
        ),
        atlases = listOf(
            GlyphAtlasOccupancyEntry(
                atlasArtifactId = "2811ca06954ad09572dbc82a74b338559e8b1df2477847ff559c8760d9720354",
                textureFormat = "A8",
                generation = 7,
                invalidationToken = "font-source-v7",
                width = 3,
                height = 3,
                rowStride = 3,
                entryCount = 1,
                occupiedPixelCount = 4,
                totalPixelCount = 9,
                occupancyRatio = 0.444444,
                strikeKeySha256 = listOf("33db747e82cc559bbad2c4b83ffc9873b243ad7b21e2fdbeaa82cf7d830ccf56"),
                keyPreimageSha256 = listOf("33c026d3c2a10f25400ef02380634c1dfa168349cb330a1589c385b9fafd6c63"),
            ),
            GlyphAtlasOccupancyEntry(
                atlasArtifactId = "43eccb8fcc53683f26bf42562370d025bba6e07087a4b6fa87164604461df337",
                textureFormat = "R8Unorm",
                generation = 8,
                invalidationToken = "font-source-v8",
                width = 3,
                height = 3,
                rowStride = 3,
                entryCount = 1,
                occupiedPixelCount = 4,
                totalPixelCount = 9,
                occupancyRatio = 0.444444,
                strikeKeySha256 = listOf("18d6a60bd014e768f1875bce635e5f2298b86bdfc51526856e6a40d8d6252351"),
                keyPreimageSha256 = listOf("62a0d1d6ddb02139d37c0bb66aa10ec45abcebc7149ac8d666bb16d5c884d485"),
            ),
        ),
        nonClaims = glyphTelemetryNonClaims(),
    ).toCanonicalJson()

    private fun commonDimensions(): List<String> = listOf(
        "environmentLabel",
        "runtimeLabel",
        "fontSourceSetHash",
        "unicodeDataVersion",
        "cacheState",
        "fixtureId",
        "sampleCount",
        "measurementPhase",
    )

    private fun shapingDimensions(): List<String> = commonDimensions() + listOf("runId", "textRangeStart", "textRangeEnd")

    private fun paragraphDimensions(): List<String> = commonDimensions() + listOf("paragraphId", "textRangeStart", "textRangeEnd")

    private fun commonNonClaims(): List<String> = listOf(
        "no-complete-target-support-claim",
        "no-performance-release-gate-claim",
        "no-gpu-route-support-claim",
        "no-native-engine-oracle-claim",
    )

    private fun defaultEnvironment(): FontTelemetryEnvironment = FontTelemetryEnvironment(
        environmentLabel = "developer-desktop",
        runtimeLabel = "jdk-25-kotlin-2.3",
    )

    private fun defaultNonClaims(): List<String> = commonNonClaims()

    private fun glyphTelemetryNonClaims(): List<String> = listOf(
        "no-complete-target-support-claim",
        "no-performance-release-gate-claim",
        "no-gpu-route-support-claim",
        "no-dftext-retirement",
    )
}

private fun String.quoted(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun String?.toTelemetryNullableString(): String = this?.quoted() ?: "null"

private fun StringBuilder.appendTelemetryField(name: String, value: String, comma: Boolean) {
    append(name.quoted()).append(": ").append(value.quoted())
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendTelemetryField(name: String, value: Boolean, comma: Boolean) {
    append(name.quoted()).append(": ").append(value)
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendTelemetryField(name: String, value: Int, comma: Boolean) {
    append(name.quoted()).append(": ").append(value)
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendTelemetryField(name: String, value: Long, comma: Boolean) {
    append(name.quoted()).append(": ").append(value)
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendTelemetryField(name: String, value: Double, comma: Boolean) {
    append(name.quoted()).append(": ").append(String.format(Locale.US, "%.6f", value))
    if (comma) {
        append(",")
    }
}

private fun StringBuilder.appendTelemetryStringArray(name: String, values: List<String>, comma: Boolean) {
    append(name.quoted()).append(": ")
    append(values.joinToString(prefix = "[", postfix = "]", separator = ",") { it.quoted() })
    if (comma) {
        append(",")
    }
}

private fun String.isStableTelemetryToken(): Boolean = all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

private fun String.isStableTelemetryDiagnosticCode(): Boolean =
    startsWith("font.telemetry.") && isStableTelemetryToken()

private fun String.isStableDiagnosticToken(): Boolean =
    (startsWith("font.") || startsWith("text.")) && isStableTelemetryToken()
