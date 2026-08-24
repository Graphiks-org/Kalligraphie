package io.ygdrasil.kalligraphie.text.shaping

import java.security.MessageDigest

/**
 * Stable spec diagnostic emitted when generated or serialized Unicode data uses
 * a version other than the version expected by the fixture or dump.
 */
public const val TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE: String =
    "text.shaping.unicode-data-version-mismatch"

/**
 * Pinned Unicode version value used by the bounded KFONT-M5-001 seed generator.
 */
public data class UnicodeVersion(
    public val value: String,
) {
    init {
        require(UnicodeVersionPattern.matches(value)) { "Unicode version must use major.minor.patch form." }
    }
}

/**
 * One checked-in Unicode source extract supplied to [UnicodeDataGenerator].
 *
 * The content is intentionally supplied by the caller so ordinary tests can
 * stay offline and deterministic.
 */
public data class UcdInputFile(
    public val fileName: String,
    public val unicodeVersion: String,
    public val content: String,
) {
    init {
        require(fileName.isNotBlank()) { "Unicode input fileName must be non-empty." }
        require(!fileName.startsWith("/")) { "Unicode input fileName must be relative." }
        require(".." !in fileName.split('/')) { "Unicode input fileName must not traverse directories." }
        require('\\' !in fileName) { "Unicode input fileName must use forward slashes." }
        require(unicodeVersion.isNotBlank()) { "Unicode input unicodeVersion must be non-empty." }
    }
}

/**
 * Recorded hash for one source extract used by a generated [UnicodeDataSet].
 */
public data class UcdInputFileHash(
    public val fileName: String,
    public val unicodeVersion: String,
    public val sha256: String,
)

/**
 * Provenance manifest for bounded generated Unicode data.
 */
public data class UcdSourceManifest(
    public val unicodeVersion: UnicodeVersion,
    public val inputs: List<UcdInputFileHash>,
    public val generatorVersion: String,
    public val generatorOptions: Map<String, String>,
    public val generatedTableHashes: Map<String, String>,
    public val outputSchemaVersion: Int,
) {
    /**
     * Serializes the manifest as stable JSON for checked-in evidence.
     */
    public fun toCanonicalJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ").append(outputSchemaVersion).append(",\n")
        append("  \"dumpId\": \"unicode-data-manifest\",\n")
        append("  \"ownerTickets\": [\"KFONT-M5-001\", \"KFONT-M5-002\"],\n")
        appendJsonField("unicodeVersion", unicodeVersion.value, comma = true)
        appendJsonField("outputSchemaVersion", outputSchemaVersion, comma = true)
        appendJsonField("generatorVersion", generatorVersion, comma = true)
        append("  \"generatorOptions\": {\n")
        append(generatorOptions.entries.joinToString(",\n") { (key, value) ->
            "    ${jsonString(key)}: ${jsonString(value)}"
        })
        append("\n  },\n")
        append("  \"inputs\": [\n")
        append(inputs.joinToString(",\n") { input ->
            buildString {
                append("    {")
                append(jsonString("fileName")).append(": ").append(jsonString(input.fileName)).append(", ")
                append(jsonString("unicodeVersion")).append(": ").append(jsonString(input.unicodeVersion)).append(", ")
                append(jsonString("sha256")).append(": ").append(jsonString(input.sha256))
                append("}")
            }
        })
        append("\n  ],\n")
        append("  \"generatedTableHashes\": {\n")
        append(generatedTableHashes.entries.joinToString(",\n") { (key, value) ->
            "    ${jsonString(key)}: ${jsonString(value)}"
        })
        append("\n  },\n")
        append("  \"nonClaims\": [\n")
        append(UnicodeDataNonClaims.joinToString(",\n") { nonClaim -> "    ${jsonString(nonClaim)}" })
        append("\n  ]\n")
        append("}\n")
    }
}

/**
 * Deterministic Unicode data generation contract.
 */
public interface UnicodeDataGenerator {
    /**
     * Generates a bounded data set from checked-in source extracts.
     */
    public fun generate(inputs: List<UcdInputFile>): UnicodeDataSet
}

/**
 * One inclusive Unicode scalar range and its generated property value.
 */
public data class UnicodeRange<T>(
    public val start: Int,
    public val endInclusive: Int,
    public val value: T,
) {
    init {
        require(start.isUnicodeScalarValue()) { "Unicode range start must be a scalar value." }
        require(endInclusive.isUnicodeScalarValue()) { "Unicode range end must be a scalar value." }
        require(start <= endInclusive) { "Unicode range start must not exceed end." }
    }
}

/**
 * Compact generated table for a Unicode property.
 */
public data class UnicodeRangeTable<T>(
    public val propertyName: String,
    public val defaultValue: T,
    public val ranges: List<UnicodeRange<T>>,
) {
    init {
        require(propertyName.isNotBlank()) { "Unicode propertyName must be non-empty." }
        require(ranges == ranges.sortedWith(compareBy<UnicodeRange<T>> { it.start }.thenBy { it.endInclusive })) {
            "Unicode ranges must be sorted by scalar range."
        }
        for (index in 1 until ranges.size) {
            val previous = ranges[index - 1]
            val current = ranges[index]
            require(previous.endInclusive < current.start) {
                "Unicode ranges must not overlap or duplicate ranges: " +
                    "${previous.toRangeLabel()} overlaps ${current.toRangeLabel()}."
            }
        }
    }

    /**
     * Returns the generated value for [codePoint], or the table default.
     */
    public fun valueAt(codePoint: Int): T {
        require(codePoint.isUnicodeScalarValue()) { "codePoint must be a Unicode scalar value." }
        return ranges.firstOrNull { codePoint in it.start..it.endInclusive }?.value ?: defaultValue
    }
}

/**
 * Emoji-related generated seed tables.
 */
public data class UnicodeEmojiProperties(
    public val emoji: UnicodeRangeTable<Boolean>,
    public val emojiPresentation: UnicodeRangeTable<Boolean>,
    public val emojiModifier: UnicodeRangeTable<Boolean>,
    public val emojiModifierBase: UnicodeRangeTable<Boolean>,
    public val extendedPictographic: UnicodeRangeTable<Boolean>,
)

/**
 * Human-readable sample fact used by the checked-in table fixture.
 */
public data class UnicodeSampleFact(
    public val codePoint: Int,
    public val graphemeClusterBreak: String,
    public val bidiClass: String,
    public val script: String,
    public val scriptExtensions: List<String>,
    public val lineBreak: String,
    public val generalCategory: String,
    public val defaultIgnorable: Boolean,
    public val emoji: Boolean,
    public val emojiPresentation: Boolean,
    public val emojiModifier: Boolean,
    public val emojiModifierBase: Boolean,
    public val extendedPictographic: Boolean,
    public val indicConjunctBreak: String,
    public val variationSelector: Boolean,
)

/**
 * Bounded Unicode data set generated from pinned source extracts.
 *
 * This is seed evidence for future M5/M6 consumers. It does not replace
 * [BasicUnicodeData] and does not claim complete UCD, UAX #9, UAX #14, or
 * UAX #29 coverage.
 */
public data class UnicodeDataSet(
    public val version: UnicodeVersion,
    public val sourceManifest: UcdSourceManifest,
    public val graphemeBreak: UnicodeRangeTable<String>,
    public val bidiClass: UnicodeRangeTable<String>,
    public val script: UnicodeRangeTable<String>,
    public val scriptExtensions: UnicodeRangeTable<List<String>>,
    public val lineBreak: UnicodeRangeTable<String>,
    public val generalCategory: UnicodeRangeTable<String>,
    public val defaultIgnorable: UnicodeRangeTable<Boolean>,
    public val emojiProperties: UnicodeEmojiProperties,
    public val indicConjunctBreak: UnicodeRangeTable<String>,
    public val variationSelector: UnicodeRangeTable<Boolean>,
    public val sampleFacts: List<UnicodeSampleFact>,
) {
    /**
     * Serializes the provenance manifest as stable JSON.
     */
    public fun toManifestJson(): String = sourceManifest.toCanonicalJson()

    /**
     * Serializes the generated bounded table fixture as stable JSON.
     */
    public fun toTablesJson(): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": ").append(sourceManifest.outputSchemaVersion).append(",\n")
        append("  \"dumpId\": \"unicode-data-seed\",\n")
        append("  \"ownerTickets\": [\"KFONT-M5-001\", \"KFONT-M5-002\"],\n")
        appendJsonField("unicodeVersion", version.value, comma = true)
        appendJsonField("kind", "bounded-seed-fixture", comma = true)
        append("  \"defaultValue\": {\n")
        append("    \"bidiClass\": ").append(jsonString(bidiClass.defaultValue)).append(",\n")
        append("    \"defaultIgnorable\": ").append(defaultIgnorable.defaultValue).append(",\n")
        append("    \"emojiProperties\": ")
        appendEmojiDefaultValue(emojiProperties)
        append(",\n")
        append("    \"generalCategory\": ").append(jsonString(generalCategory.defaultValue)).append(",\n")
        append("    \"graphemeClusterBreak\": ").append(jsonString(graphemeBreak.defaultValue)).append(",\n")
        append("    \"indicConjunctBreak\": ").append(jsonString(indicConjunctBreak.defaultValue)).append(",\n")
        append("    \"lineBreak\": ").append(jsonString(lineBreak.defaultValue)).append(",\n")
        append("    \"script\": ").append(jsonString(script.defaultValue)).append(",\n")
        append("    \"scriptExtensions\": ").append(jsonStringList(scriptExtensions.defaultValue)).append(",\n")
        append("    \"variationSelector\": ").append(variationSelector.defaultValue).append("\n")
        append("  },\n")
        append("  \"bidiClass\": ")
        appendInlineStringRangeArray(bidiClass.ranges)
        append(",\n")
        append("  \"defaultIgnorable\": ")
        appendInlineBooleanRangeArray(defaultIgnorable.ranges)
        append(",\n")
        append("  \"emojiProperties\": {\n")
        append("    \"emoji\": ")
        appendInlineBooleanRangeArray(emojiProperties.emoji.ranges)
        append(",\n")
        append("    \"emojiModifier\": ")
        appendInlineBooleanRangeArray(emojiProperties.emojiModifier.ranges)
        append(",\n")
        append("    \"emojiModifierBase\": ")
        appendInlineBooleanRangeArray(emojiProperties.emojiModifierBase.ranges)
        append(",\n")
        append("    \"emojiPresentation\": ")
        appendInlineBooleanRangeArray(emojiProperties.emojiPresentation.ranges)
        append(",\n")
        append("    \"extendedPictographic\": ")
        appendInlineBooleanRangeArray(emojiProperties.extendedPictographic.ranges)
        append("\n  },\n")
        append("  \"generalCategory\": ")
        appendInlineStringRangeArray(generalCategory.ranges)
        append(",\n")
        append("  \"graphemeClusterBreak\": ")
        appendInlineStringRangeArray(graphemeBreak.ranges)
        append(",\n")
        append("  \"indicConjunctBreak\": ")
        appendInlineStringRangeArray(indicConjunctBreak.ranges)
        append(",\n")
        append("  \"lineBreak\": ")
        appendInlineStringRangeArray(lineBreak.ranges)
        append(",\n")
        append("  \"script\": ")
        appendInlineStringRangeArray(script.ranges)
        append(",\n")
        append("  \"scriptExtensions\": ")
        appendInlineStringListRangeArray(scriptExtensions.ranges)
        append(",\n")
        append("  \"variationSelector\": ")
        appendInlineBooleanRangeArray(variationSelector.ranges)
        append(",\n")
        append("  \"sampleFacts\": [\n")
        append(sampleFacts.joinToString(",\n") { fact -> fact.toCanonicalJson().prependIndent("    ") })
        append("\n  ],\n")
        append("  \"tableHashes\": {\n")
        append(sourceManifest.generatedTableHashes.entries.joinToString(",\n") { (key, value) ->
            "    ${jsonString(key)}: ${jsonString(value)}"
        })
        append("\n  },\n")
        append("  \"nonClaims\": [\n")
        append(UnicodeDataNonClaims.joinToString(",\n") { nonClaim -> "    ${jsonString(nonClaim)}" })
        append("\n  ]\n")
        append("}\n")
    }

    /**
     * Serializes one generated table as the canonical preimage used for its
     * manifest SHA-256 hash.
     */
    public fun tableJson(tableName: String): String =
        tableJsonFor(
            tableName = tableName,
            unicodeVersion = version.value,
            graphemeBreak = graphemeBreak,
            bidiClass = bidiClass,
            script = script,
            scriptExtensions = scriptExtensions,
            lineBreak = lineBreak,
            generalCategory = generalCategory,
            defaultIgnorable = defaultIgnorable,
            emojiProperties = emojiProperties,
            indicConjunctBreak = indicConjunctBreak,
            variationSelector = variationSelector,
        )
}

/**
 * Canonical version mismatch evidence backed by the stable shaping diagnostic.
 */
public data class UnicodeDataVersionMismatchDiagnostic(
    public val expectedUnicodeVersion: String,
    public val actualUnicodeVersion: String,
    public val subject: String,
    public val textRange: IntRange? = null,
) {
    init {
        require(expectedUnicodeVersion.isNotBlank()) { "expectedUnicodeVersion must be non-empty." }
        require(actualUnicodeVersion.isNotBlank()) { "actualUnicodeVersion must be non-empty." }
        require(subject.isNotBlank()) { "subject must be non-empty." }
    }

    /**
     * Converts this mismatch to the existing shaping diagnostic surface.
     */
    public fun toShapingDiagnostic(): ShapingDiagnostic =
        ShapingDiagnostic(
            code = TEXT_SHAPING_UNICODE_DATA_VERSION_MISMATCH_DIAGNOSTIC_CODE,
            message = "Unicode data version mismatch for $subject: " +
                "expected $expectedUnicodeVersion, actual $actualUnicodeVersion.",
            textRange = textRange,
        )

    /**
     * Serializes the mismatch fixture as stable JSON.
     */
    public fun toCanonicalJson(): String {
        val diagnostic = toShapingDiagnostic()
        return buildString {
            append("{\n")
            append("  \"schemaVersion\": 1,\n")
            append("  \"dumpId\": \"unicode-data-version-mismatch-diagnostic\",\n")
            append("  \"ownerTickets\": [\"KFONT-M5-001\", \"KFONT-M5-002\"],\n")
            append("  \"diagnostic\": {\n")
            appendJsonField("code", diagnostic.code, comma = true, indent = "    ")
            appendJsonField("severity", "refusal", comma = true, indent = "    ")
            appendJsonField("subject", subject, comma = true, indent = "    ")
            appendJsonField("textRange", textRange?.toRangeLabel(), comma = true, indent = "    ")
            appendJsonField("expectedUnicodeVersion", expectedUnicodeVersion, comma = true, indent = "    ")
            appendJsonField("actualUnicodeVersion", actualUnicodeVersion, comma = true, indent = "    ")
            appendJsonField("message", diagnostic.message, comma = false, indent = "    ")
            append("  },\n")
            append("  \"nonClaims\": [\n")
            append(UnicodeDataNonClaims.joinToString(",\n") { nonClaim -> "    ${jsonString(nonClaim)}" })
            append("\n  ]\n")
            append("}\n")
        }
    }
}

/**
 * Reproducible generator for the pinned, bounded Unicode 16.0.0 seed fixtures.
 */
public object PinnedUnicodeDataGenerator : UnicodeDataGenerator {
    public const val PinnedUnicodeVersion: String = "16.0.0"
    public const val GeneratorVersion: String = "kanvas-unicode-seed-generator-1"
    public const val OutputSchemaVersion: Int = 1

    override fun generate(inputs: List<UcdInputFile>): UnicodeDataSet {
        val orderedInputs = validateInputs(inputs)
        val inputByName = orderedInputs.associateBy { it.fileName }
        val unicodeVersion = UnicodeVersion(PinnedUnicodeVersion)

        val unicodeData = parseUnicodeData(inputByName.getValue("UnicodeData.txt"))
        val graphemeBreak = UnicodeRangeTable(
            propertyName = "Grapheme_Cluster_Break",
            defaultValue = "Other",
            ranges = parseStringPropertyRanges(inputByName.getValue("GraphemeBreakProperty.txt")),
        )
        val bidiClass = UnicodeRangeTable(
            propertyName = "Bidi_Class",
            defaultValue = "L",
            ranges = unicodeData.bidiClass,
        )
        val script = UnicodeRangeTable(
            propertyName = "Script",
            defaultValue = "Zyyy",
            ranges = parseScriptRanges(inputByName.getValue("Scripts.txt")),
        )
        val scriptExtensions = UnicodeRangeTable(
            propertyName = "Script_Extensions",
            defaultValue = emptyList(),
            ranges = parseScriptExtensionRanges(inputByName.getValue("ScriptExtensions.txt")),
        )
        val lineBreak = UnicodeRangeTable(
            propertyName = "Line_Break",
            defaultValue = "XX",
            ranges = parseStringPropertyRanges(inputByName.getValue("LineBreak.txt")),
        )
        val generalCategory = UnicodeRangeTable(
            propertyName = "General_Category",
            defaultValue = "Cn",
            ranges = unicodeData.generalCategory,
        )
        val defaultIgnorable = UnicodeRangeTable(
            propertyName = "Default_Ignorable_Code_Point",
            defaultValue = false,
            ranges = parseBooleanPropertyRanges(
                input = inputByName.getValue("DerivedCoreProperties.txt"),
                propertyName = "Default_Ignorable_Code_Point",
            ),
        )
        val emojiProperties = parseEmojiProperties(inputByName.getValue("emoji/emoji-data.txt"))
        val indicConjunctBreak = UnicodeRangeTable(
            propertyName = "Indic_Conjunct_Break",
            defaultValue = "None",
            ranges = parseIndicConjunctBreakRanges(inputByName.getValue("DerivedCoreProperties.txt")),
        )
        val variationSelector = UnicodeRangeTable(
            propertyName = "Variation_Selector",
            defaultValue = false,
            ranges = parseBooleanPropertyRanges(
                input = inputByName.getValue("PropList.txt"),
                propertyName = "Variation_Selector",
            ),
        )
        val sampleFacts = sampleFacts(
            graphemeBreak = graphemeBreak,
            bidiClass = bidiClass,
            script = script,
            scriptExtensions = scriptExtensions,
            lineBreak = lineBreak,
            generalCategory = generalCategory,
            defaultIgnorable = defaultIgnorable,
            emojiProperties = emojiProperties,
            indicConjunctBreak = indicConjunctBreak,
            variationSelector = variationSelector,
        )
        val tableHashes = generatedTableHashes(
            unicodeVersion = unicodeVersion.value,
            graphemeBreak = graphemeBreak,
            bidiClass = bidiClass,
            script = script,
            scriptExtensions = scriptExtensions,
            lineBreak = lineBreak,
            generalCategory = generalCategory,
            defaultIgnorable = defaultIgnorable,
            emojiProperties = emojiProperties,
            indicConjunctBreak = indicConjunctBreak,
            variationSelector = variationSelector,
        )
        val manifest = UcdSourceManifest(
            unicodeVersion = unicodeVersion,
            inputs = orderedInputs.map { input ->
                UcdInputFileHash(
                    fileName = input.fileName,
                    unicodeVersion = input.unicodeVersion,
                    sha256 = input.content.toByteArray(Charsets.UTF_8).sha256Hex(),
                )
            },
            generatorVersion = GeneratorVersion,
            generatorOptions = linkedMapOf(
                "mode" to "bounded-seed-fixture",
                "ordinaryValidationPolicy" to "offline",
                "sourceCoverage" to "bounded-kfont-m5-002-fixture-matrix",
            ),
            generatedTableHashes = tableHashes,
            outputSchemaVersion = OutputSchemaVersion,
        )

        return UnicodeDataSet(
            version = unicodeVersion,
            sourceManifest = manifest,
            graphemeBreak = graphemeBreak,
            bidiClass = bidiClass,
            script = script,
            scriptExtensions = scriptExtensions,
            lineBreak = lineBreak,
            generalCategory = generalCategory,
            defaultIgnorable = defaultIgnorable,
            emojiProperties = emojiProperties,
            indicConjunctBreak = indicConjunctBreak,
            variationSelector = variationSelector,
            sampleFacts = sampleFacts,
        )
    }

    private fun validateInputs(inputs: List<UcdInputFile>): List<UcdInputFile> {
        val byName = linkedMapOf<String, UcdInputFile>()
        for (input in inputs) {
            require(input.fileName in RequiredInputFileNames) { "unexpected Unicode input: ${input.fileName}" }
            require(input.unicodeVersion == PinnedUnicodeVersion) {
                "Unicode input ${input.fileName} expected Unicode $PinnedUnicodeVersion but was ${input.unicodeVersion}."
            }
            require(input.content.isNotBlank()) { "missing Unicode input content: ${input.fileName}" }
            require(byName.put(input.fileName, input) == null) { "duplicate Unicode input: ${input.fileName}" }
        }
        for (required in RequiredInputFileNames) {
            require(required in byName) { "missing required Unicode input: $required" }
        }
        return RequiredInputFileNames.map { byName.getValue(it) }
    }
}

private data class ParsedUnicodeData(
    val bidiClass: List<UnicodeRange<String>>,
    val generalCategory: List<UnicodeRange<String>>,
)

private val UnicodeVersionPattern = Regex("""\d+\.\d+\.\d+""")
private val RequiredInputFileNames = listOf(
    "DerivedCoreProperties.txt",
    "GraphemeBreakProperty.txt",
    "LineBreak.txt",
    "PropList.txt",
    "ScriptExtensions.txt",
    "Scripts.txt",
    "UnicodeData.txt",
    "emoji/emoji-data.txt",
)
private val OrderedTableNames = listOf(
    "bidiClass",
    "defaultIgnorable",
    "emojiProperties",
    "generalCategory",
    "graphemeClusterBreak",
    "indicConjunctBreak",
    "lineBreak",
    "script",
    "scriptExtensions",
    "variationSelector",
)
private val SampleCodePoints = listOf(
    0x0041,
    0x0061,
    0x0301,
    0x05D0,
    0x0640,
    0x0915,
    0x094D,
    0x1F3FB,
    0x1F466,
    0x1F600,
    0xFE0F,
)
private val UnicodeDataNonClaims = listOf(
    "bounded-seed-fixture-only",
    "no-complete-ucd-claim",
    "no-uax9-conformance-claim",
    "no-uax14-conformance-claim",
    "no-complete-uax29-claim",
    "no-bidi-or-script-itemizer-replacement-claim",
    "no-shaping-support-promotion",
    "no-paragraph-support-claim",
    "no-gpu-text-route-claim",
)
private val ScriptAliases = mapOf(
    "Arab" to "Arab",
    "Arabic" to "Arab",
    "Bopo" to "Bopo",
    "Common" to "Zyyy",
    "Devanagari" to "Deva",
    "Georgian" to "Geor",
    "Hang" to "Hang",
    "Han" to "Hani",
    "Hani" to "Hani",
    "Hebrew" to "Hebr",
    "Hebr" to "Hebr",
    "Hira" to "Hira",
    "Inherited" to "Zinh",
    "Kana" to "Kana",
    "Greek" to "Grek",
    "Latin" to "Latn",
    "Latn" to "Latn",
    "Mand" to "Mand",
    "Syrc" to "Syrc",
    "Thai" to "Thai",
    "Zinh" to "Zinh",
    "Zyyy" to "Zyyy",
)

private fun parseUnicodeData(input: UcdInputFile): ParsedUnicodeData {
    val bidi = mutableListOf<UnicodeRange<String>>()
    val generalCategory = mutableListOf<UnicodeRange<String>>()
    for ((lineNumber, line) in input.content.lineSequence().withIndex()) {
        val payload = line.withoutUcdComment()
        if (payload.isEmpty()) continue
        val parts = payload.split(';')
        require(parts.size >= 5) { "${input.fileName}:${lineNumber + 1} must contain UnicodeData fields." }
        val codePoint = parts[0].trim().toInt(16)
        generalCategory += UnicodeRange(codePoint, codePoint, parts[2].trim())
        bidi += UnicodeRange(codePoint, codePoint, parts[4].trim())
    }
    return ParsedUnicodeData(
        bidiClass = bidi.sortedByRange(),
        generalCategory = generalCategory.sortedByRange(),
    )
}

private fun parseStringPropertyRanges(input: UcdInputFile): List<UnicodeRange<String>> =
    parsePropertyRows(input).map { row ->
        UnicodeRange(row.start, row.endInclusive, row.value)
    }.sortedByRange()

private fun parseScriptRanges(input: UcdInputFile): List<UnicodeRange<String>> =
    parsePropertyRows(input).map { row ->
        UnicodeRange(row.start, row.endInclusive, row.value.toScriptTag())
    }.sortedByRange()

private fun parseScriptExtensionRanges(input: UcdInputFile): List<UnicodeRange<List<String>>> =
    parsePropertyRows(input).map { row ->
        val scripts = row.value.split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
            .map { script -> script.toScriptTag() }
            .sorted()
        UnicodeRange(row.start, row.endInclusive, scripts)
    }.sortedByRange()

private fun parseBooleanPropertyRanges(input: UcdInputFile, propertyName: String): List<UnicodeRange<Boolean>> =
    parsePropertyRows(input)
        .filter { row -> row.value == propertyName }
        .map { row -> UnicodeRange(row.start, row.endInclusive, true) }
        .sortedByRange()

private fun parseEmojiProperties(input: UcdInputFile): UnicodeEmojiProperties =
    UnicodeEmojiProperties(
        emoji = UnicodeRangeTable(
            propertyName = "Emoji",
            defaultValue = false,
            ranges = parseBooleanPropertyRanges(input, "Emoji"),
        ),
        emojiPresentation = UnicodeRangeTable(
            propertyName = "Emoji_Presentation",
            defaultValue = false,
            ranges = parseBooleanPropertyRanges(input, "Emoji_Presentation"),
        ),
        emojiModifier = UnicodeRangeTable(
            propertyName = "Emoji_Modifier",
            defaultValue = false,
            ranges = parseBooleanPropertyRanges(input, "Emoji_Modifier"),
        ),
        emojiModifierBase = UnicodeRangeTable(
            propertyName = "Emoji_Modifier_Base",
            defaultValue = false,
            ranges = parseBooleanPropertyRanges(input, "Emoji_Modifier_Base"),
        ),
        extendedPictographic = UnicodeRangeTable(
            propertyName = "Extended_Pictographic",
            defaultValue = false,
            ranges = parseBooleanPropertyRanges(input, "Extended_Pictographic"),
        ),
    )

private fun parseIndicConjunctBreakRanges(input: UcdInputFile): List<UnicodeRange<String>> =
    input.content.lineSequence().mapIndexedNotNull { index, line ->
        val payload = line.withoutUcdComment()
        if (payload.isEmpty()) {
            null
        } else {
            val parts = payload.split(';').map { it.trim() }
            if (parts.size >= 3 && parts[1] == "InCB") {
                val (start, endInclusive) = parseCodePointRange(parts[0], input.fileName, index + 1)
                UnicodeRange(start, endInclusive, parts[2])
            } else {
                null
            }
        }
    }.toList().sortedByRange()

private data class PropertyRow(
    val start: Int,
    val endInclusive: Int,
    val value: String,
)

private fun parsePropertyRows(input: UcdInputFile): List<PropertyRow> =
    input.content.lineSequence().mapIndexedNotNull { index, line ->
        val payload = line.withoutUcdComment()
        if (payload.isEmpty()) {
            null
        } else {
            val parts = payload.split(';').map { it.trim() }
            require(parts.size >= 2) { "${input.fileName}:${index + 1} must contain range and property." }
            val (start, endInclusive) = parseCodePointRange(parts[0], input.fileName, index + 1)
            PropertyRow(start = start, endInclusive = endInclusive, value = parts[1])
        }
    }.toList()

private fun parseCodePointRange(token: String, fileName: String, lineNumber: Int): Pair<Int, Int> {
    val parts = token.split("..")
    require(parts.size == 1 || parts.size == 2) { "$fileName:$lineNumber has malformed code point range." }
    val start = parts[0].toInt(16)
    val endInclusive = if (parts.size == 2) parts[1].toInt(16) else start
    return start to endInclusive
}

private fun sampleFacts(
    graphemeBreak: UnicodeRangeTable<String>,
    bidiClass: UnicodeRangeTable<String>,
    script: UnicodeRangeTable<String>,
    scriptExtensions: UnicodeRangeTable<List<String>>,
    lineBreak: UnicodeRangeTable<String>,
    generalCategory: UnicodeRangeTable<String>,
    defaultIgnorable: UnicodeRangeTable<Boolean>,
    emojiProperties: UnicodeEmojiProperties,
    indicConjunctBreak: UnicodeRangeTable<String>,
    variationSelector: UnicodeRangeTable<Boolean>,
): List<UnicodeSampleFact> =
    SampleCodePoints.map { codePoint ->
        UnicodeSampleFact(
            codePoint = codePoint,
            graphemeClusterBreak = graphemeBreak.valueAt(codePoint),
            bidiClass = bidiClass.valueAt(codePoint),
            script = script.valueAt(codePoint),
            scriptExtensions = scriptExtensions.valueAt(codePoint),
            lineBreak = lineBreak.valueAt(codePoint),
            generalCategory = generalCategory.valueAt(codePoint),
            defaultIgnorable = defaultIgnorable.valueAt(codePoint),
            emoji = emojiProperties.emoji.valueAt(codePoint),
            emojiPresentation = emojiProperties.emojiPresentation.valueAt(codePoint),
            emojiModifier = emojiProperties.emojiModifier.valueAt(codePoint),
            emojiModifierBase = emojiProperties.emojiModifierBase.valueAt(codePoint),
            extendedPictographic = emojiProperties.extendedPictographic.valueAt(codePoint),
            indicConjunctBreak = indicConjunctBreak.valueAt(codePoint),
            variationSelector = variationSelector.valueAt(codePoint),
        )
    }

private fun generatedTableHashes(
    unicodeVersion: String,
    graphemeBreak: UnicodeRangeTable<String>,
    bidiClass: UnicodeRangeTable<String>,
    script: UnicodeRangeTable<String>,
    scriptExtensions: UnicodeRangeTable<List<String>>,
    lineBreak: UnicodeRangeTable<String>,
    generalCategory: UnicodeRangeTable<String>,
    defaultIgnorable: UnicodeRangeTable<Boolean>,
    emojiProperties: UnicodeEmojiProperties,
    indicConjunctBreak: UnicodeRangeTable<String>,
    variationSelector: UnicodeRangeTable<Boolean>,
): Map<String, String> {
    val hashes = linkedMapOf<String, String>()
    for (tableName in OrderedTableNames) {
        hashes[tableName] = tableJsonFor(
            tableName = tableName,
            unicodeVersion = unicodeVersion,
            graphemeBreak = graphemeBreak,
            bidiClass = bidiClass,
            script = script,
            scriptExtensions = scriptExtensions,
            lineBreak = lineBreak,
            generalCategory = generalCategory,
            defaultIgnorable = defaultIgnorable,
            emojiProperties = emojiProperties,
            indicConjunctBreak = indicConjunctBreak,
            variationSelector = variationSelector,
        ).toByteArray(Charsets.UTF_8).sha256Hex()
    }
    return hashes
}

private fun tableJsonFor(
    tableName: String,
    unicodeVersion: String,
    graphemeBreak: UnicodeRangeTable<String>,
    bidiClass: UnicodeRangeTable<String>,
    script: UnicodeRangeTable<String>,
    scriptExtensions: UnicodeRangeTable<List<String>>,
    lineBreak: UnicodeRangeTable<String>,
    generalCategory: UnicodeRangeTable<String>,
    defaultIgnorable: UnicodeRangeTable<Boolean>,
    emojiProperties: UnicodeEmojiProperties,
    indicConjunctBreak: UnicodeRangeTable<String>,
    variationSelector: UnicodeRangeTable<Boolean>,
): String = buildString {
    append("{\n")
    append("  \"schemaVersion\": 1,\n")
    appendJsonField("unicodeVersion", unicodeVersion, comma = true)
    appendJsonField("table", tableName, comma = true)
    when (tableName) {
        "bidiClass" -> {
            appendJsonField("defaultValue", bidiClass.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineStringRangeArray(bidiClass.ranges)
        }
        "defaultIgnorable" -> {
            appendBooleanJsonField("defaultValue", defaultIgnorable.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineBooleanRangeArray(defaultIgnorable.ranges)
        }
        "emojiProperties" -> {
            append("  \"defaultValue\": ")
            appendEmojiDefaultValue(emojiProperties)
            append(",\n")
            append("  \"emoji\": ")
            appendInlineBooleanRangeArray(emojiProperties.emoji.ranges)
            append(",\n")
            append("  \"emojiModifier\": ")
            appendInlineBooleanRangeArray(emojiProperties.emojiModifier.ranges)
            append(",\n")
            append("  \"emojiModifierBase\": ")
            appendInlineBooleanRangeArray(emojiProperties.emojiModifierBase.ranges)
            append(",\n")
            append("  \"emojiPresentation\": ")
            appendInlineBooleanRangeArray(emojiProperties.emojiPresentation.ranges)
            append(",\n")
            append("  \"extendedPictographic\": ")
            appendInlineBooleanRangeArray(emojiProperties.extendedPictographic.ranges)
        }
        "generalCategory" -> {
            appendJsonField("defaultValue", generalCategory.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineStringRangeArray(generalCategory.ranges)
        }
        "graphemeClusterBreak" -> {
            appendJsonField("defaultValue", graphemeBreak.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineStringRangeArray(graphemeBreak.ranges)
        }
        "indicConjunctBreak" -> {
            appendJsonField("defaultValue", indicConjunctBreak.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineStringRangeArray(indicConjunctBreak.ranges)
        }
        "lineBreak" -> {
            appendJsonField("defaultValue", lineBreak.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineStringRangeArray(lineBreak.ranges)
        }
        "script" -> {
            appendJsonField("defaultValue", script.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineStringRangeArray(script.ranges)
        }
        "scriptExtensions" -> {
            append("  \"defaultValue\": ").append(jsonStringList(scriptExtensions.defaultValue)).append(",\n")
            append("  \"ranges\": ")
            appendInlineStringListRangeArray(scriptExtensions.ranges)
        }
        "variationSelector" -> {
            appendBooleanJsonField("defaultValue", variationSelector.defaultValue, comma = true)
            append("  \"ranges\": ")
            appendInlineBooleanRangeArray(variationSelector.ranges)
        }
        else -> error("Unknown Unicode generated table: $tableName")
    }
    append("\n")
    append("}\n")
}

private fun UnicodeSampleFact.toCanonicalJson(): String = buildString {
    append("{")
    append(jsonString("codePoint")).append(": ").append(jsonString(codePoint.toCodePointLabel())).append(", ")
    append(jsonString("codePointValue")).append(": ").append(codePoint).append(", ")
    append(jsonString("graphemeClusterBreak")).append(": ").append(jsonString(graphemeClusterBreak)).append(", ")
    append(jsonString("bidiClass")).append(": ").append(jsonString(bidiClass)).append(", ")
    append(jsonString("script")).append(": ").append(jsonString(script)).append(", ")
    append(jsonString("scriptExtensions")).append(": ").append(jsonStringList(scriptExtensions)).append(", ")
    append(jsonString("lineBreak")).append(": ").append(jsonString(lineBreak)).append(", ")
    append(jsonString("generalCategory")).append(": ").append(jsonString(generalCategory)).append(", ")
    append(jsonString("defaultIgnorable")).append(": ").append(defaultIgnorable).append(", ")
    append(jsonString("emoji")).append(": ").append(emoji).append(", ")
    append(jsonString("emojiPresentation")).append(": ").append(emojiPresentation).append(", ")
    append(jsonString("emojiModifier")).append(": ").append(emojiModifier).append(", ")
    append(jsonString("emojiModifierBase")).append(": ").append(emojiModifierBase).append(", ")
    append(jsonString("extendedPictographic")).append(": ").append(extendedPictographic).append(", ")
    append(jsonString("indicConjunctBreak")).append(": ").append(jsonString(indicConjunctBreak)).append(", ")
    append(jsonString("variationSelector")).append(": ").append(variationSelector)
    append("}")
}

private fun String.withoutUcdComment(): String =
    substringBefore('#').trim()

private fun String.toScriptTag(): String =
    ScriptAliases[this] ?: error("Unknown script alias in bounded Unicode seed: $this")

private fun <T> List<UnicodeRange<T>>.sortedByRange(): List<UnicodeRange<T>> =
    sortedWith(compareBy<UnicodeRange<T>> { it.start }.thenBy { it.endInclusive })

private fun StringBuilder.appendInlineStringRangeArray(ranges: List<UnicodeRange<String>>) {
    append("[")
    if (ranges.isNotEmpty()) {
        append("\n")
        append(ranges.joinToString(",\n") { range ->
            "    ${range.toCanonicalJson { value -> jsonString(value) }}"
        })
        append("\n  ")
    }
    append("]")
}

private fun StringBuilder.appendInlineBooleanRangeArray(ranges: List<UnicodeRange<Boolean>>) {
    append("[")
    if (ranges.isNotEmpty()) {
        append("\n")
        append(ranges.joinToString(",\n") { range ->
            "    ${range.toCanonicalJson { value -> value.toString() }}"
        })
        append("\n  ")
    }
    append("]")
}

private fun StringBuilder.appendInlineStringListRangeArray(ranges: List<UnicodeRange<List<String>>>) {
    append("[")
    if (ranges.isNotEmpty()) {
        append("\n")
        append(ranges.joinToString(",\n") { range ->
            "    ${range.toCanonicalJson { value -> jsonStringList(value) }}"
        })
        append("\n  ")
    }
    append("]")
}

private fun StringBuilder.appendEmojiDefaultValue(emojiProperties: UnicodeEmojiProperties) {
    append("{")
    append(jsonString("emoji")).append(": ").append(emojiProperties.emoji.defaultValue).append(", ")
    append(jsonString("emojiModifier")).append(": ").append(emojiProperties.emojiModifier.defaultValue).append(", ")
    append(jsonString("emojiModifierBase")).append(": ").append(emojiProperties.emojiModifierBase.defaultValue).append(", ")
    append(jsonString("emojiPresentation")).append(": ").append(emojiProperties.emojiPresentation.defaultValue).append(", ")
    append(jsonString("extendedPictographic")).append(": ")
        .append(emojiProperties.extendedPictographic.defaultValue)
    append("}")
}

private fun <T> UnicodeRange<T>.toCanonicalJson(valueJson: (T) -> String): String = buildString {
    append("{")
    append(jsonString("range")).append(": ").append(jsonString(toRangeLabel())).append(", ")
    append(jsonString("start")).append(": ").append(jsonString(start.toCodePointLabel())).append(", ")
    append(jsonString("end")).append(": ").append(jsonString(endInclusive.toCodePointLabel())).append(", ")
    append(jsonString("value")).append(": ").append(valueJson(value))
    append("}")
}

private fun UnicodeRange<*>.toRangeLabel(): String =
    if (start == endInclusive) {
        start.toCodePointLabel()
    } else {
        "${start.toCodePointLabel()}..${endInclusive.toCodePointLabel()}"
    }

private fun Int.toCodePointLabel(): String {
    val hex = toString(16).uppercase()
    return "U+${hex.padStart(maxOf(4, hex.length), '0')}"
}

private fun IntRange.toRangeLabel(): String =
    "$first..$last"

private fun Int.isUnicodeScalarValue(): Boolean =
    this in 0x0000..0x10FFFF && this !in 0xD800..0xDFFF

private fun StringBuilder.appendJsonField(
    name: String,
    value: String?,
    comma: Boolean,
    indent: String = "  ",
) {
    append(indent)
    append(jsonString(name))
    append(": ")
    append(value?.let { jsonString(it) } ?: "null")
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendJsonField(
    name: String,
    value: Int,
    comma: Boolean,
    indent: String = "  ",
) {
    append(indent)
    append(jsonString(name))
    append(": ")
    append(value)
    if (comma) append(",")
    append("\n")
}

private fun StringBuilder.appendBooleanJsonField(
    name: String,
    value: Boolean,
    comma: Boolean,
    indent: String = "  ",
) {
    append(indent)
    append(jsonString(name))
    append(": ")
    append(value)
    if (comma) append(",")
    append("\n")
}

private fun jsonStringList(values: List<String>): String =
    values.joinToString(prefix = "[", postfix = "]") { value -> jsonString(value) }

private fun jsonString(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
        "%02x".format(byte.toInt() and 0xFF)
    }
