package io.ygdrasil.kalligraphie.font

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import java.nio.file.Files
import java.nio.file.Path

class FontCoreSurfaceTest {
    @Test
    fun fontTaxonomyUsesNeutralTextRefusalAuthority() {
        assertEquals(
            "unsupported.text.artifact_unregistered",
            FontTextRefusalCodes.ARTIFACT_UNREGISTERED,
        )
        assertTrue(
            defaultFontDiagnosticTaxonomy().codes
                .any { entry -> entry.code == FontTextRefusalCodes.ARTIFACT_UNREGISTERED },
        )
    }

    @Test
    fun exposesCoreFontValueObjectsAndContracts() {
        val sourceUuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440000")
        val typefaceUuid = Uuid.parse("550e8400-e29b-41d4-a716-446655440001")
        val sourceId = FontSourceID(sourceUuid)
        val typefaceId = TypefaceID(typefaceUuid)
        val source = FontSource(
            id = sourceId,
            kind = FontSourceKind.SYSTEM,
            displayName = "Inter Regular",
            bytes = ByteArray(0),
        )
        val diagnostic = FontSourceDiagnostic(
            sourceId = sourceId,
            message = "not parsed yet",
        )
        val data = TypefaceData(
            id = typefaceId,
            source = source,
            familyName = "Inter",
            styleName = "Regular",
            diagnostics = listOf(diagnostic),
        )
        val face = FontFace(typeface = data)
        val collection = FontCollection(faces = listOf(face))
        val request = FallbackRequest(
            text = "Hello",
            locale = "en-US",
            preferredFamilies = listOf("Inter"),
        )
        val run = ResolvedFontRun(
            start = 0,
            end = 5,
            face = face,
        )
        val catalog = FallbackCatalog(families = mapOf("Inter" to collection))

        assertEquals(sourceUuid, sourceId.value)
        assertEquals(typefaceUuid, typefaceId.value)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", sourceId.value.toHexDashString())
        assertEquals("550e8400-e29b-41d4-a716-446655440001", typefaceId.value.toHexDashString())
        assertEquals(FontSourceKind.SYSTEM, source.kind)
        assertEquals(sourceId, diagnostic.sourceId)
        assertEquals(typefaceId, data.id)
        assertEquals(collection, catalog.families.getValue("Inter"))
        assertEquals(face, run.face)
        assertTrue(request.preferredFamilies.contains("Inter"))
    }

    @Test
    fun scansExplicitFontRootsCatalogsFilesAndReportsDiagnostics() {
        val root = Files.createTempDirectory("kanvas-font-core-scan")
        val missing = root.resolveSibling("${root.fileName}-missing")
        try {
            val nested = Files.createDirectories(root.resolve("nested"))
            val regular = Files.write(root.resolve("AlphaSans-Regular.ttf"), byteArrayOf(1, 2, 3))
            val bold = Files.write(nested.resolve("AlphaSans-Bold.OTF"), byteArrayOf(4, 5, 6))
            val collection = Files.write(nested.resolve("Symbols.ttc"), byteArrayOf(7, 8, 9))
            Files.write(nested.resolve("ignored.txt"), byteArrayOf(10))

            val scan = FontFileScanner.scanRoots(listOf(missing, root))

            assertEquals(
                listOf(
                    regular.toRealPath(),
                    bold.toRealPath(),
                    collection.toRealPath(),
                ).sortedBy { it.toString() },
                scan.files,
            )
            assertEquals(listOf("font.scan.root-missing"), scan.diagnostics.map { it.code })
            assertTrue(scan.dumpDiagnostics().contains("font.scan.root-missing"))
            assertTrue(scan.dumpDiagnostics().contains(missing.toAbsolutePath().normalize().toString()))

            val catalog = FontFileCatalog.fromScan(scan)

            assertEquals(scan.files, catalog.entries.map { it.path })
            assertEquals(
                listOf("AlphaSans-Bold.OTF", "AlphaSans-Regular.ttf", "Symbols.ttc").sorted(),
                catalog.entries.map { it.displayName }.sorted(),
            )
            assertEquals(listOf("otf", "ttc", "ttf").sorted(), catalog.entries.map { it.extension }.sorted())
            assertEquals(scan.dumpDiagnostics(), catalog.dumpDiagnostics())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun catalogsExplicitLiberationFixturePathsInDeterministicFamilyOrder() {
        val fixturePaths = listOf(
            fixturePath("font/fixtures/fonts/liberation/LiberationSans-Regular.ttf"),
            fixturePath("font/fixtures/fonts/liberation/LiberationSerif-Regular.ttf"),
            fixturePath("font/fixtures/fonts/liberation/LiberationMono-Regular.ttf"),
        )
        val expectedDump = fixturePath("font/fixtures/expected/font-source/liberation-scan-root.json")
        val faces = fixturePaths.mapIndexed { index, path ->
            fixtureFace(
                uuid = "550e8400-e29b-41d4-a716-44665544100$index",
                path = path,
                familyName = when (path.fileName.toString()) {
                    "LiberationMono-Regular.ttf" -> "Liberation Mono"
                    "LiberationSans-Regular.ttf" -> "Liberation Sans"
                    "LiberationSerif-Regular.ttf" -> "Liberation Serif"
                    else -> error("Unexpected fixture path: $path")
                },
            )
        }

        val catalog = FallbackCatalog.fromFaces(faces)

        assertEquals(
            listOf("Liberation Mono", "Liberation Sans", "Liberation Serif"),
            catalog.availableFamilyNames(),
        )
        assertEquals(3, fixturePaths.count { Files.isRegularFile(it) })
        assertTrue(Files.readString(expectedDump).contains("\"hostDependent\": false"))
    }

    @Test
    fun scanRootsCanReportSkippedUnsupportedFilesDeterministically() {
        val root = Files.createTempDirectory("kanvas-font-core-skip")
        try {
            val skipped = Files.write(root.resolve("README.txt"), byteArrayOf(10))

            val scan = FontFileScanner.scanRoots(listOf(root), reportSkippedFiles = true)

            assertEquals(emptyList(), scan.files)
            assertEquals(listOf("font.scan.file-skipped"), scan.diagnostics.map { it.code })
            assertEquals(root.toAbsolutePath().normalize(), scan.diagnostics.single().root)
            assertEquals(skipped.toAbsolutePath().normalize(), scan.diagnostics.single().path)
            assertEquals(
                "font.scan.file-skipped root=${root.toAbsolutePath().normalize()} " +
                    "path=${skipped.toAbsolutePath().normalize()} message=\"Unsupported font file extension.\"",
                scan.dumpDiagnostics(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun dumpsSourceProvenanceEvidenceDeterministically() {
        val sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440100"))
        val source = FontSource(
            id = sourceId,
            kind = FontSourceKind.MEMORY,
            displayName = "Fixture Sans",
            bytes = byteArrayOf(1, 2, 3),
        )
        val diagnostic = FontSourceDiagnostic(
            sourceId = sourceId,
            message = "missing hhea",
            causeCode = "font.sfnt.required-table-missing",
            causeMessage = "hhea",
        )

        val evidence = source.provenanceEvidence(
            faceCount = 1,
            tableTags = listOf("name", "cmap", "head", "cmap"),
            diagnostics = listOf(diagnostic),
        )

        assertEquals("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81", evidence.contentSha256)
        assertFalse(evidence.hostDependent)
        assertEquals(listOf("cmap", "head", "name"), evidence.tableTags)
        assertEquals(
            "sourceId=550e8400-e29b-41d4-a716-446655440100 kind=MEMORY displayName=\"Fixture Sans\" " +
                "contentSha256=039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81 " +
                "hostDependent=false faceCount=1 tableTags=[cmap,head,name] " +
                "diagnostics=[font.sfnt.required-table-missing sourceId=550e8400-e29b-41d4-a716-446655440100 " +
                "message=\"missing hhea\" causeMessage=\"hhea\"]",
            evidence.dump(),
        )

        val systemEvidence = source.copy(kind = FontSourceKind.SYSTEM, bytes = ByteArray(0)).provenanceEvidence(faceCount = 0)

        assertTrue(systemEvidence.hostDependent)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", systemEvidence.contentSha256)
        assertTrue(systemEvidence.dump().contains("hostDependent=true"))
    }

    @Test
    fun fontSourceEvidenceRejectsUnnormalizedAndUnstableTableTags() {
        val sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440101"))

        val unnormalized = assertFailsWith<IllegalArgumentException> {
            FontSourceEvidence(
                sourceId = sourceId,
                kind = FontSourceKind.MEMORY,
                displayName = "Fixture Sans",
                contentSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hostDependent = false,
                faceCount = 1,
                tableTags = listOf("name", "cmap", "name"),
            )
        }
        assertTrue(unnormalized.message.orEmpty().contains("sorted and deduplicated"))

        val malformed = assertFailsWith<IllegalArgumentException> {
            FontSourceEvidence(
                sourceId = sourceId,
                kind = FontSourceKind.MEMORY,
                displayName = "Fixture Sans",
                contentSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                hostDependent = false,
                faceCount = 1,
                tableTags = listOf("cm\np"),
            )
        }
        assertTrue(malformed.message.orEmpty().contains("printable ASCII"))
    }

    @Test
    fun fontSourceEvidenceRejectsUnstableHashesAndDiagnosticCodes() {
        val sourceId = FontSourceID(Uuid.parse("550e8400-e29b-41d4-a716-446655440102"))

        val malformedHash = assertFailsWith<IllegalArgumentException> {
            FontSourceEvidence(
                sourceId = sourceId,
                kind = FontSourceKind.MEMORY,
                displayName = "Fixture Sans",
                contentSha256 = "not-a-sha256\n",
                hostDependent = false,
                faceCount = 1,
            )
        }
        assertTrue(malformedHash.message.orEmpty().contains("lowercase hexadecimal SHA-256"))

        val malformedCode = assertFailsWith<IllegalArgumentException> {
            FontSourceDiagnostic(
                sourceId = sourceId,
                message = "bad code",
                causeCode = "font.source\nbad",
            )
        }
        assertTrue(malformedCode.message.orEmpty().contains("stable one-line diagnostic code"))
    }

    @Test
    fun plansPortableFallbackFamiliesFromGenericLocaleScriptAndEmojiPolicy() {
        val availableFamilies = listOf(
            "Liberation Sans",
            "Noto Sans CJK JP",
            "Noto Sans CJK SC",
            "Noto Color Emoji",
            "DejaVu Serif",
        )
        val policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf(
                "sans-serif" to listOf("Liberation Sans"),
                "serif" to listOf("DejaVu Serif"),
                "monospace" to emptyList(),
            ),
            scriptFallbackChains = mapOf(
                "han" to listOf("Noto Sans CJK JP"),
                "emoji" to listOf("Noto Color Emoji"),
            ),
            localeFallbackChains = mapOf(
                "zh" to listOf("Noto Sans CJK SC", "Noto Sans CJK JP"),
                "ja" to listOf("Noto Sans CJK JP", "Noto Sans CJK SC"),
            ),
            emojiPreferredFamilies = listOf("Noto Color Emoji"),
        )

        val zhPlan = policy.planFamilyNames(
            availableFamilyNames = availableFamilies,
            requestedFamily = null,
            locales = listOf("zh-Hans-CN"),
            codePoint = 0x5203,
        )
        val jaPlan = policy.planFamilyNames(
            availableFamilyNames = availableFamilies,
            requestedFamily = null,
            locales = listOf("ja-JP"),
            codePoint = 0x5203,
        )
        val emojiPlan = policy.planFamilyNames(
            availableFamilyNames = availableFamilies,
            requestedFamily = "sans serif",
            locales = listOf("en-US"),
            codePoint = 0x1F600,
        )

        assertEquals("Noto Sans CJK SC", zhPlan.orderedFamilies.first())
        assertEquals("Noto Sans CJK JP", jaPlan.orderedFamilies.first())
        assertEquals("Noto Color Emoji", emojiPlan.orderedFamilies.first())
        assertEquals("emoji", emojiPlan.script)
        assertTrue(emojiPlan.dump().contains("script=emoji"))
        assertTrue(emojiPlan.dump().contains("Noto Color Emoji"))
    }

    @Test
    fun plansGenericFallbackBeforeLatinScriptFallbackForCommonDigits() {
        val policy = FontFallbackPolicy.Default.copy(
            genericFallbackChains = mapOf(
                "serif" to listOf("DejaVu Serif"),
            ),
            scriptFallbackChains = mapOf(
                "latin" to listOf("Liberation Sans"),
            ),
        )

        val plan = policy.planFamilyNames(
            availableFamilyNames = listOf("Liberation Sans", "DejaVu Serif"),
            requestedFamily = "serif",
            codePoint = '1'.code,
        )

        assertEquals("default", plan.script)
        assertEquals("DejaVu Serif", plan.orderedFamilies.first())
    }

    @Test
    fun resolvesSurrogatePairToCoveredEmojiFace() {
        val latin = testFace("550e8400-e29b-41d4-a716-446655440010", "Alpha Sans")
        val emoji = testFace("550e8400-e29b-41d4-a716-446655440011", "Noto Color Emoji")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(
                families = mapOf(
                    "Alpha Sans" to FontCollection(listOf(latin)),
                    "Noto Color Emoji" to FontCollection(listOf(emoji)),
                ),
            ),
            policy = FontFallbackPolicy.Default.copy(
                emojiPreferredFamilies = listOf("Noto Color Emoji"),
            ),
            coverage = testCoverage(
                latin.typeface.id to setOf('A'.code),
                emoji.typeface.id to setOf(0x1F600),
            ),
        )

        val runs = resolver.resolve(FallbackRequest(text = "A\uD83D\uDE00", preferredFamilies = listOf("Alpha Sans")))

        assertEquals(
            listOf(
                ResolvedFontRun(start = 0, end = 1, face = latin),
                ResolvedFontRun(start = 1, end = 3, face = emoji),
            ),
            runs,
        )
    }

    @Test
    fun groupsContiguousCodePointsResolvedToSameFace() {
        val latin = testFace("550e8400-e29b-41d4-a716-446655440020", "Alpha Sans")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(families = mapOf("Alpha Sans" to FontCollection(listOf(latin)))),
            policy = FontFallbackPolicy.Default,
            coverage = testCoverage(latin.typeface.id to setOf('a'.code, 'b'.code, 'c'.code)),
        )

        val runs = resolver.resolve(FallbackRequest(text = "abc", preferredFamilies = listOf("Alpha Sans")))

        assertEquals(listOf(ResolvedFontRun(start = 0, end = 3, face = latin)), runs)
    }

    @Test
    fun resolvesRequestedGenericThroughPolicyFallbackOrder() {
        val requested = testFace("550e8400-e29b-41d4-a716-446655440030", "Requested Sans")
        val serif = testFace("550e8400-e29b-41d4-a716-446655440031", "Fallback Serif")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(
                families = mapOf(
                    "Requested Sans" to FontCollection(listOf(requested)),
                    "Fallback Serif" to FontCollection(listOf(serif)),
                ),
            ),
            policy = FontFallbackPolicy.Default.copy(
                genericFallbackChains = mapOf(
                    "serif" to listOf("Fallback Serif"),
                    "sans-serif" to listOf("Requested Sans"),
                ),
            ),
            coverage = testCoverage(serif.typeface.id to setOf('x'.code)),
        )

        val runs = resolver.resolve(FallbackRequest(text = "x", preferredFamilies = listOf("serif")))

        assertEquals(listOf(ResolvedFontRun(start = 0, end = 1, face = serif)), runs)
    }

    @Test
    fun resolvesLaterPreferredFamilyBeforeGenericOrCatalogFallback() {
        val first = testFace("550e8400-e29b-41d4-a716-446655440050", "First")
        val second = testFace("550e8400-e29b-41d4-a716-446655440051", "Second")
        val fallback = testFace("550e8400-e29b-41d4-a716-446655440052", "Fallback")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(
                families = mapOf(
                    "First" to FontCollection(listOf(first)),
                    "Second" to FontCollection(listOf(second)),
                    "Fallback" to FontCollection(listOf(fallback)),
                ),
            ),
            policy = FontFallbackPolicy.Default.copy(
                genericFallbackChains = mapOf(
                    "sans-serif" to listOf("Fallback"),
                ),
                scriptFallbackChains = emptyMap(),
                localeFallbackChains = emptyMap(),
                emojiPreferredFamilies = emptyList(),
            ),
            coverage = testCoverage(
                second.typeface.id to setOf('x'.code),
                fallback.typeface.id to setOf('x'.code),
            ),
        )

        val runs = resolver.resolve(FallbackRequest(text = "x", preferredFamilies = listOf("Missing", "Second")))

        assertEquals(listOf(ResolvedFontRun(start = 0, end = 1, face = second)), runs)
    }

    @Test
    fun tracesFallbackDecisionWithStableCandidateOrderAndSelectedFace() {
        val requested = testFace("550e8400-e29b-41d4-a716-446655440080", "Requested Sans")
        val fallback = testFace("550e8400-e29b-41d4-a716-446655440081", "Fallback Sans")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(
                families = mapOf(
                    "Requested Sans" to FontCollection(listOf(requested)),
                    "Fallback Sans" to FontCollection(listOf(fallback)),
                ),
            ),
            policy = FontFallbackPolicy.Default.copy(
                genericFallbackChains = mapOf(
                    "sans-serif" to listOf("Fallback Sans"),
                ),
                scriptFallbackChains = emptyMap(),
                localeFallbackChains = emptyMap(),
                emojiPreferredFamilies = emptyList(),
            ),
            coverage = testCoverage(fallback.typeface.id to setOf('x'.code)),
        )

        val trace = resolver.trace(FallbackRequest(text = "x", preferredFamilies = listOf("Requested Sans")))

        assertEquals(
            "start=0 end=1 codePoint=U+0078 requestedFamilies=[Requested Sans] " +
                "genericFamily=sans-serif script=latin locales=[] " +
                "candidateFamilies=[Requested Sans,Fallback Sans] selectedFamily=\"Fallback Sans\" " +
                "selectedTypefaceId=550e8400-e29b-41d4-a716-446655440081 covered=true diagnostic=none",
            trace.dump(),
        )
        assertFalse(trace.dump().contains("Sk"))
        assertFalse(trace.dump().contains("@"))
    }

    @Test
    fun tracesFallbackRefusalsWithoutHidingNotdefOrEmptyCatalogPolicy() {
        val requested = testFace("550e8400-e29b-41d4-a716-446655440082", "Requested Sans")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(families = mapOf("Requested Sans" to FontCollection(listOf(requested)))),
            policy = FontFallbackPolicy.Default,
            coverage = testCoverage(),
        )

        val missingGlyphTrace = resolver.trace(FallbackRequest(text = "x", preferredFamilies = listOf("Requested Sans")))

        assertEquals(
            "start=0 end=1 codePoint=U+0078 requestedFamilies=[Requested Sans] " +
                "genericFamily=sans-serif script=latin locales=[] " +
                "candidateFamilies=[Requested Sans] selectedFamily=\"Requested Sans\" " +
                "selectedTypefaceId=550e8400-e29b-41d4-a716-446655440082 covered=false " +
                "diagnostic=font.fallback-glyph-unavailable",
            missingGlyphTrace.dump(),
        )
        assertEquals(listOf(ResolvedFontRun(start = 0, end = 1, face = requested)), resolver.resolve(FallbackRequest(text = "x", preferredFamilies = listOf("Requested Sans"))))

        val emptyResolver = CatalogFontResolver(
            catalog = FallbackCatalog(),
            policy = FontFallbackPolicy.Default,
            coverage = testCoverage(),
        )
        val emptyCatalogTrace = emptyResolver.trace(FallbackRequest(text = "x", preferredFamilies = listOf("Missing Sans")))

        assertEquals(
            "start=0 end=1 codePoint=U+0078 requestedFamilies=[Missing Sans] " +
                "genericFamily=sans-serif script=latin locales=[] " +
                "candidateFamilies=[] selectedFamily=none selectedTypefaceId=none covered=false " +
                "diagnostic=font.fallback-family-unavailable",
            emptyCatalogTrace.dump(),
        )
        assertEquals(emptyList(), emptyResolver.resolve(FallbackRequest(text = "x", preferredFamilies = listOf("Missing Sans"))))
    }

    @Test
    fun resolvesRequestedStyleWithinFamilyBeforeCatalogOrder() {
        val regular = testFace("550e8400-e29b-41d4-a716-446655440070", "Alpha Sans", "Regular")
        val bold = testFace("550e8400-e29b-41d4-a716-446655440071", "Alpha Sans", "Bold")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog.fromFaces(listOf(regular, bold)),
            policy = FontFallbackPolicy.Default,
            coverage = testCoverage(
                regular.typeface.id to setOf('A'.code),
                bold.typeface.id to setOf('A'.code),
            ),
        )

        val runs = resolver.resolve(
            FallbackRequest(
                text = "A",
                preferredFamilies = listOf("Alpha Sans"),
                style = FontStyle(weight = 700),
            ),
        )

        assertEquals(listOf(ResolvedFontRun(start = 0, end = 1, face = bold)), runs)
    }

    @Test
    fun returnsEmptyRunsForEmptyCatalog() {
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(),
            policy = FontFallbackPolicy.Default,
            coverage = testCoverage(),
        )

        assertEquals(emptyList(), resolver.resolve(FallbackRequest(text = "x")))
    }

    @Test
    fun usesFirstPlannedFaceForMissingCoverageSoNotdefCanSurface() {
        val first = testFace("550e8400-e29b-41d4-a716-446655440040", "First Sans")
        val second = testFace("550e8400-e29b-41d4-a716-446655440041", "Second Sans")
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(
                families = mapOf(
                    "First Sans" to FontCollection(listOf(first)),
                    "Second Sans" to FontCollection(listOf(second)),
                ),
            ),
            policy = FontFallbackPolicy.Default,
            coverage = testCoverage(),
        )

        val runs = resolver.resolve(FallbackRequest(text = "\u0378", preferredFamilies = listOf("First Sans")))

        assertEquals(listOf(ResolvedFontRun(start = 0, end = 1, face = first)), runs)
    }

    @Test
    fun prefersCoveredCandidateWithRequestedVariationAxisOverCoveredStaticPreferredFamily() {
        val staticRequested = testFace(
            uuid = "550e8400-e29b-41d4-a716-446655440083",
            familyName = "Requested Sans",
        )
        val variableFallback = testFace(
            uuid = "550e8400-e29b-41d4-a716-446655440084",
            familyName = "Variable Sans",
            variationAxes = listOf(
                TypefaceVariationAxisRange(
                    axisTag = "wght",
                    minimum = 100.0,
                    defaultValue = 400.0,
                    maximum = 900.0,
                ),
            ),
        )
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(
                families = mapOf(
                    "Requested Sans" to FontCollection(listOf(staticRequested)),
                    "Variable Sans" to FontCollection(listOf(variableFallback)),
                ),
            ),
            policy = FontFallbackPolicy.Default.copy(
                genericFallbackChains = mapOf("sans-serif" to listOf("Variable Sans")),
                scriptFallbackChains = emptyMap(),
                localeFallbackChains = emptyMap(),
                emojiPreferredFamilies = emptyList(),
            ),
            coverage = testCoverage(
                staticRequested.typeface.id to setOf('x'.code),
                variableFallback.typeface.id to setOf('x'.code),
            ),
        )

        val runs = resolver.resolve(
            FallbackRequest(
                text = "x",
                preferredFamilies = listOf("Requested Sans"),
                variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 650.0)),
            ),
        )
        val trace = resolver.trace(
            FallbackRequest(
                text = "x",
                preferredFamilies = listOf("Requested Sans"),
                variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 650.0)),
            ),
        )
        val decisionJson = trace.decisions.single().toCanonicalJson(clusterStart = 0)

        assertEquals(1, runs.size)
        assertEquals("Variable Sans", runs.single().face.typeface.familyName)
        assertContains(decisionJson, """"selectedFamily":"Variable Sans"""")
        assertContains(decisionJson, """"selectedVariationCoordinates":[{"axisTag":"wght","value":650.0}]""")
        assertContains(decisionJson, """"requestedVariationCoordinates":[{"axisTag":"wght","value":650.0}]""")
    }

    @Test
    fun prefersCoveredCandidateWithRequestedNamedInstanceOverAxisOnlyCandidate() {
        val axisOnly = testFace(
            uuid = "550e8400-e29b-41d4-a716-446655440085",
            familyName = "Axis Only Sans",
            variationAxes = listOf(
                TypefaceVariationAxisRange(axisTag = "wdth", minimum = 75.0, defaultValue = 100.0, maximum = 125.0),
                TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
            ),
        )
        val namedInstance = testFace(
            uuid = "550e8400-e29b-41d4-a716-446655440086",
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
            coverage = testCoverage(
                axisOnly.typeface.id to setOf('x'.code),
                namedInstance.typeface.id to setOf('x'.code),
            ),
        )

        val request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Missing Sans"),
            namedInstance = "Condensed Bold",
        )
        val runs = resolver.resolve(request)
        val trace = resolver.trace(request)
        val decisionJson = trace.decisions.single().toCanonicalJson(clusterStart = 0)

        assertEquals(1, runs.size)
        assertEquals("Named Instance Sans", runs.single().face.typeface.familyName)
        assertEquals(
            listOf(
                TypefaceVariationCoordinate(axisTag = "wdth", value = 80.0),
                TypefaceVariationCoordinate(axisTag = "wght", value = 700.0),
            ),
            runs.single().face.typeface.variationCoordinates,
        )
        assertContains(decisionJson, """"selectedFamily":"Named Instance Sans"""")
        assertContains(decisionJson, """"selectedNamedInstance":"Condensed Bold"""")
        assertContains(decisionJson, """"selectedVariationCoordinates":[{"axisTag":"wdth","value":80.0},{"axisTag":"wght","value":700.0}]""")
    }

    @Test
    fun prefersCoveredCandidateWithRequestedMultiAxisSupportOverSingleAxisCandidate() {
        val weightOnly = testFace(
            uuid = "550e8400-e29b-41d4-a716-446655440087",
            familyName = "Weight Only Sans",
            variationAxes = listOf(
                TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
            ),
        )
        val multiAxis = testFace(
            uuid = "550e8400-e29b-41d4-a716-446655440088",
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
            coverage = testCoverage(
                weightOnly.typeface.id to setOf('x'.code),
                multiAxis.typeface.id to setOf('x'.code),
            ),
        )

        val request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Missing Sans"),
            variationCoordinates = listOf(
                TypefaceVariationCoordinate(axisTag = "wdth", value = 90.0),
                TypefaceVariationCoordinate(axisTag = "wght", value = 650.0),
            ),
        )
        val runs = resolver.resolve(request)
        val trace = resolver.trace(request)
        val decisionJson = trace.decisions.single().toCanonicalJson(clusterStart = 0)

        assertEquals(1, runs.size)
        assertEquals("Multi Axis Sans", runs.single().face.typeface.familyName)
        assertContains(decisionJson, """"selectedFamily":"Multi Axis Sans"""")
        assertContains(decisionJson, """"requestedVariationCoordinates":[{"axisTag":"wdth","value":90.0},{"axisTag":"wght","value":650.0}]""")
        assertContains(decisionJson, """"selectedVariationCoordinates":[{"axisTag":"wdth","value":90.0},{"axisTag":"wght","value":650.0}]""")
    }

    @Test
    fun prefersMetricsSupportingCandidateWhenAxisFactsTie() {
        val missingMetrics = testFace(
            uuid = "550e8400-e29b-41d4-a716-446655440089",
            familyName = "Variable Sans Missing Metrics",
            variationAxes = listOf(
                TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
            ),
            variationMetricsSupported = false,
        )
        val metricsReady = testFace(
            uuid = "550e8400-e29b-41d4-a716-44665544008a",
            familyName = "Variable Sans Metrics Ready",
            variationAxes = listOf(
                TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
            ),
        )
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(
                families = mapOf(
                    "Variable Sans Missing Metrics" to FontCollection(listOf(missingMetrics)),
                    "Variable Sans Metrics Ready" to FontCollection(listOf(metricsReady)),
                ),
            ),
            policy = FontFallbackPolicy.Default.copy(
                genericFallbackChains = mapOf(
                    "sans-serif" to listOf("Variable Sans Missing Metrics", "Variable Sans Metrics Ready"),
                ),
                scriptFallbackChains = emptyMap(),
                localeFallbackChains = emptyMap(),
                emojiPreferredFamilies = emptyList(),
            ),
            coverage = testCoverage(
                missingMetrics.typeface.id to setOf('x'.code),
                metricsReady.typeface.id to setOf('x'.code),
            ),
        )

        val request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Missing Sans"),
            variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 650.0)),
        )
        val runs = resolver.resolve(request)
        val decisionJson = resolver.trace(request).decisions.single().toCanonicalJson(clusterStart = 0)

        assertEquals("Variable Sans Metrics Ready", runs.single().face.typeface.familyName)
        assertContains(decisionJson, """"selectedFamily":"Variable Sans Metrics Ready"""")
        assertFalse(decisionJson.contains("font.metrics-variation-unavailable"))
    }

    @Test
    fun omitsSelectedNamedInstanceWhenAxisOverridesLeaveNamedInstanceCoordinates() {
        val namedInstance = testFace(
            uuid = "550e8400-e29b-41d4-a716-44665544008b",
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
            catalog = FallbackCatalog(families = mapOf("Named Instance Sans" to FontCollection(listOf(namedInstance)))),
            policy = FontFallbackPolicy.Default.copy(
                genericFallbackChains = mapOf("sans-serif" to listOf("Named Instance Sans")),
                scriptFallbackChains = emptyMap(),
                localeFallbackChains = emptyMap(),
                emojiPreferredFamilies = emptyList(),
            ),
            coverage = testCoverage(namedInstance.typeface.id to setOf('x'.code)),
        )

        val request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Missing Sans"),
            namedInstance = "Condensed Bold",
            variationCoordinates = listOf(TypefaceVariationCoordinate(axisTag = "wght", value = 900.0)),
        )
        val runs = resolver.resolve(request)
        val decisionJson = resolver.trace(request).decisions.single().toCanonicalJson(clusterStart = 0)

        assertEquals(
            listOf(
                TypefaceVariationCoordinate(axisTag = "wdth", value = 80.0),
                TypefaceVariationCoordinate(axisTag = "wght", value = 900.0),
            ),
            runs.single().face.typeface.variationCoordinates,
        )
        assertContains(decisionJson, """"selectedVariationCoordinates":[{"axisTag":"wdth","value":80.0},{"axisTag":"wght","value":900.0}]""")
        assertFalse(decisionJson.contains("selectedNamedInstance"))
    }

    @Test
    fun emitsStableDiagnosticWhenNamedInstanceCannotBeHonored() {
        val axisOnly = testFace(
            uuid = "550e8400-e29b-41d4-a716-44665544008c",
            familyName = "Axis Only Sans",
            variationAxes = listOf(
                TypefaceVariationAxisRange(axisTag = "wdth", minimum = 75.0, defaultValue = 100.0, maximum = 125.0),
                TypefaceVariationAxisRange(axisTag = "wght", minimum = 100.0, defaultValue = 400.0, maximum = 900.0),
            ),
        )
        val resolver = CatalogFontResolver(
            catalog = FallbackCatalog(families = mapOf("Axis Only Sans" to FontCollection(listOf(axisOnly)))),
            policy = FontFallbackPolicy.Default.copy(
                genericFallbackChains = mapOf("sans-serif" to listOf("Axis Only Sans")),
                scriptFallbackChains = emptyMap(),
                localeFallbackChains = emptyMap(),
                emojiPreferredFamilies = emptyList(),
            ),
            coverage = testCoverage(axisOnly.typeface.id to setOf('x'.code)),
        )

        val request = FallbackRequest(
            text = "x",
            preferredFamilies = listOf("Missing Sans"),
            namedInstance = "Condensed Bold",
        )
        val decisionJson = resolver.trace(request).decisions.single().toCanonicalJson(clusterStart = 0)

        assertContains(decisionJson, """"selectedFamily":"Axis Only Sans"""")
        assertContains(decisionJson, """"diagnosticCode":"text.fallback.variation-defaulted"""")
        assertFalse(decisionJson.contains("selectedNamedInstance"))
    }

    @Test
    fun buildsFallbackCatalogFromParsedFacesInDeterministicFamilyOrder() {
        val regular = testFace("550e8400-e29b-41d4-a716-446655440060", "Beta Sans", "Regular")
        val bold = testFace("550e8400-e29b-41d4-a716-446655440061", "Beta Sans", "Bold")
        val italicCaseAlias = testFace("550e8400-e29b-41d4-a716-446655440064", " beta sans ", "Italic")
        val alpha = testFace("550e8400-e29b-41d4-a716-446655440062", "Alpha Serif", "Regular")
        val blank = testFace("550e8400-e29b-41d4-a716-446655440063", "   ", "Regular")

        val catalog = FallbackCatalog.fromFaces(listOf(regular, blank, bold, alpha, italicCaseAlias, regular))

        assertEquals(listOf("Alpha Serif", "Beta Sans"), catalog.availableFamilyNames())
        assertEquals(listOf(alpha), catalog.families.getValue("Alpha Serif").faces)
        assertEquals(listOf(regular, bold, italicCaseAlias), catalog.families.getValue("Beta Sans").faces)
    }

    private fun testCoverage(vararg entries: Pair<TypefaceID, Set<Int>>): FontCoverageProvider {
        val supported = entries.toMap()
        return FontCoverageProvider { typefaceId, codePoint ->
            supported[typefaceId]?.contains(codePoint) == true
        }
    }

    private fun testFace(
        uuid: String,
        familyName: String,
        styleName: String = "Regular",
        variationAxes: List<TypefaceVariationAxisRange> = emptyList(),
        variationCoordinates: List<TypefaceVariationCoordinate> = emptyList(),
        namedInstances: List<TypefaceNamedInstance> = emptyList(),
        variationMetricsSupported: Boolean = true,
    ): FontFace {
        val sourceId = FontSourceID(Uuid.parse(uuid.replaceRange(uuid.length - 1, uuid.length, "0")))
        val typefaceId = TypefaceID(Uuid.parse(uuid))
        val identityPreimage = if (familyName.isBlank()) {
            null
        } else {
            typefaceIdentityPreimage(
                sourceId = sourceId,
                collectionIndex = 0,
                postScriptName = "$familyName-$styleName",
                familyName = familyName,
                styleName = styleName,
                outlineFormat = TypefaceOutlineFormat.TRUE_TYPE_GLYF,
                selectedCMap = TypefaceCMapSelection(
                    platformId = 3,
                    encodingId = 10,
                    format = 12,
                    language = 0,
                    unicode = true,
                ),
                scalerMode = TypefaceScalerMode.OUTLINE,
                variationCoordinates = variationCoordinates,
                tableTags = listOf("cmap", "fvar", "glyf", "head", "name"),
            )
        }
        return FontFace(
            typeface = TypefaceData(
                id = typefaceId,
                source = FontSource(
                    id = sourceId,
                    kind = FontSourceKind.MEMORY,
                    displayName = "$familyName Regular",
                    bytes = ByteArray(0),
                ),
                familyName = familyName,
                styleName = styleName,
                variationAxes = variationAxes,
                variationCoordinates = variationCoordinates,
                namedInstances = namedInstances,
                variationMetricsSupported = variationMetricsSupported,
                identityPreimage = identityPreimage,
            ),
        )
    }

    private fun fixtureFace(uuid: String, path: Path, familyName: String): FontFace {
        val sourceId = FontSourceID(Uuid.parse(uuid.replaceRange(uuid.length - 1, uuid.length, "0")))
        val typefaceId = TypefaceID(Uuid.parse(uuid))
        return FontFace(
            typeface = TypefaceData(
                id = typefaceId,
                source = FontSource(
                    id = sourceId,
                    kind = FontSourceKind.FILE,
                    displayName = path.fileName.toString(),
                    bytes = Files.readAllBytes(path),
                ),
                familyName = familyName,
                styleName = "Regular",
            ),
        )
    }

    private fun fixturePath(relativePath: String): Path =
        projectRoot().resolve(relativePath).normalize()

    private fun projectRoot(): Path {
        var current = Path.of("").toAbsolutePath().normalize()
        while (current.parent != null && !Files.isDirectory(current.resolve("font/fixtures"))) {
            current = current.parent
        }
        return current
    }
}
