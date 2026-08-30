package org.graphiks.kalligraphie.shaping

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GdefLigatureCaretFact
import org.graphiks.kalligraphie.api.GdefLigatureCaretState
import org.graphiks.kalligraphie.api.GlyphId
import org.graphiks.kalligraphie.api.LayoutUnit
import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ShapedGlyph
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShaperCluster
import org.graphiks.kalligraphie.api.ShaperClusterToken
import org.graphiks.kalligraphie.api.ShapingBackend
import org.graphiks.kalligraphie.api.ShapingBackendIdentity
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingFeaturePolicy
import org.graphiks.kalligraphie.api.ShapingFeaturePolicyApplication
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.ShapingSafetyFlags
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.toDiagnostic

/**
 * Opens the pinned HarfBuzz reference backend for the current JVM platform.
 *
 * The backend loads only the hash-verified HarfBuzz resource embedded in this module. It is
 * available on Linux and macOS for x64 and arm64 JVMs; other platforms return a typed failure.
 * No JNI type, native handle, or platform dependency escapes through [ShapingBackend].
 * JVM launchers must enable native access with `--enable-native-access=ALL-UNNAMED`.
 */
public object JvmHarfBuzzShapingBackend {
    /**
     * Explicit baseline feature policy implemented by the pinned HarfBuzz reference backend.
     *
     * The policy delegates baseline selection to HarfBuzz 14.3.0 and therefore does not claim
     * to enumerate choices that HarfBuzz derives from font tables or segment properties.
     * Callers must place this policy in every [ShapingRequest] sent to this backend; individual
     * [OpenTypeFeature] values remain explicit request overrides.
     */
    public val pinnedFeaturePolicy: ShapingFeaturePolicy = PINNED_FEATURE_POLICY

    /** Opens and validates the pinned HarfBuzz library before exposing a backend. */
    public fun open(): FontOperationResult<ShapingBackend> =
        when (val loaded = HarfBuzzNativeLoader.load()) {
            is FontOperationResult.Success -> FontOperationResult.Success(HarfBuzzJvmBackend(loaded.value))
            is FontOperationResult.Failure -> loaded
            is FontOperationResult.Cancelled -> loaded
        }
}

private class HarfBuzzJvmBackend(
    private val nativeLibrary: HarfBuzzNativeLibrary,
) : ShapingBackend {
    override val identity: ShapingBackendIdentity = nativeLibrary.identity

    override fun shape(request: ShapingRequest): FontOperationResult<ShapedGlyphRun> {
        if (request.featurePolicy != identity.featurePolicy) {
            return shapingFailure(
                code = "font.shaping-feature-policy-unsupported",
                message = "The requested OpenType feature policy is not implemented by this pinned HarfBuzz backend.",
            )
        }
        if (request.features.any { feature -> feature.tag in NON_DETERMINISTIC_FEATURES }) {
            return shapingFailure(
                code = "font.shaping-feature-not-deterministic",
                message = "The requested OpenType feature is non-deterministic and cannot be shaped reproducibly.",
            )
        }

        val fontData = when (val result = request.font.copyOpenTypeData()) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (fontData.face != request.font.key.face) {
            return shapingFailure(
                code = "font.shaping-font-data-mismatch",
                message = "The OpenType bytes do not identify the requested font instance face.",
            )
        }

        val layoutSize = request.font.key.layoutSize.value
        if (!layoutSize.isFinite() || layoutSize <= 0f) {
            return shapingFailure(
                code = "font.shaping-scale-invalid",
                message = "The font instance layout size must be finite and positive.",
            )
        }

        return try {
            FontOperationResult.Success(
                nativeLibrary.shape(
                    request = request,
                    fontBytes = fontData.copyBytes(),
                    layoutSize = layoutSize,
                ),
            )
        } catch (error: Throwable) {
            shapingFailure(
                code = "font.shaping-native-failure",
                message = "The pinned HarfBuzz backend failed while shaping: ${error.message ?: error::class.simpleName}.",
            )
        }
    }
}

internal data class HarfBuzzPlatform(
    val osName: String,
    val architecture: String,
) {
    internal companion object {
        fun detect(): HarfBuzzPlatform = HarfBuzzPlatform(
            osName = System.getProperty("os.name").orEmpty(),
            architecture = System.getProperty("os.arch").orEmpty(),
        )
    }
}

internal object HarfBuzzNativeLoader {
    private val cache: MutableMap<HarfBuzzPlatform, FontOperationResult<HarfBuzzNativeLibrary>> = mutableMapOf()

    fun load(platform: HarfBuzzPlatform = HarfBuzzPlatform.detect()): FontOperationResult<HarfBuzzNativeLibrary> = synchronized(cache) {
        cache.getOrPut(platform) { loadUncached(platform) }
    }

    private fun loadUncached(platform: HarfBuzzPlatform): FontOperationResult<HarfBuzzNativeLibrary> {
        val target = nativeTargetFor(platform) ?: return shapingFailure(
            code = "font.shaping-native-platform-unsupported",
            message = "The pinned HarfBuzz backend supports only Linux or macOS on x64 or arm64; received ${platform.osName}/${platform.architecture}.",
        )
        return try {
            val bytes = HarfBuzzNativeLoader::class.java.getResourceAsStream(target.resourcePath)
                ?.use { stream -> stream.readBytes() }
                ?: return shapingFailure(
                    code = "font.shaping-native-resource-missing",
                    message = "The pinned HarfBuzz resource ${target.resourcePath} is missing.",
                )
            if (bytes.sha256Hex() != target.librarySha256) {
                return shapingFailure(
                    code = "font.shaping-native-resource-corrupt",
                    message = "The pinned HarfBuzz resource failed its SHA-256 verification.",
                )
            }

            val libraryPath = materializeLibrary(target, bytes)
            System.load(libraryPath.absolutePathString())
            val lookup = SymbolLookup.libraryLookup(libraryPath, Arena.global())
            val nativeLibrary = HarfBuzzNativeLibrary(lookup, target)
            if (nativeLibrary.versionString() != HARFBUZZ_VERSION) {
                return shapingFailure(
                    code = "font.shaping-native-version-mismatch",
                    message = "The loaded HarfBuzz library does not report the pinned version $HARFBUZZ_VERSION.",
                )
            }
            FontOperationResult.Success(nativeLibrary)
        } catch (error: Throwable) {
            shapingFailure(
                code = "font.shaping-native-load-failed",
                message = "The pinned HarfBuzz library could not be loaded: ${error.message ?: error::class.simpleName}.",
            )
        }
    }

    private fun materializeLibrary(target: HarfBuzzNativeTarget, bytes: ByteArray): Path {
        val directory = Path.of(System.getProperty("java.io.tmpdir"), "kalligraphie-harfbuzz", target.librarySha256)
        Files.createDirectories(directory)
        val destination = directory.resolve(target.fileName)
        if (Files.isRegularFile(destination) && Files.readAllBytes(destination).sha256Hex() == target.librarySha256) {
            return destination
        }

        val temporary = Files.createTempFile(directory, "libharfbuzz-", ".part")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        check(Files.readAllBytes(destination).sha256Hex() == target.librarySha256) {
            "The extracted HarfBuzz library did not preserve its verified digest."
        }
        return destination
    }
}

internal data class HarfBuzzNativeTarget(
    val resourcePath: String,
    val fileName: String,
    val nativeArtifactId: String,
    val librarySha256: String,
)

private fun nativeTargetFor(platform: HarfBuzzPlatform): HarfBuzzNativeTarget? = when (
    platform.osName.lowercase() to platform.architecture.lowercase()
) {
    "linux" to "amd64",
    "linux" to "x86_64",
    -> HarfBuzzNativeTarget(
        resourcePath = "/kalligraphie/harfbuzz/linux/x64/libharfbuzz.so",
        fileName = "libharfbuzz.so",
        nativeArtifactId = "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux/libharfbuzz.so",
        librarySha256 = "9a5e3576912c2f8c8b2533d4a264fec1eac9667adfd64f7e71e80179ba118614",
    )

    "linux" to "aarch64",
    "linux" to "arm64",
    -> HarfBuzzNativeTarget(
        resourcePath = "/kalligraphie/harfbuzz/linux/arm64/libharfbuzz.so",
        fileName = "libharfbuzz.so",
        nativeArtifactId = "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-linux-arm64/libharfbuzz.so",
        librarySha256 = "b1c7c67034297763e0ce46f3749c4da33a4bb4064929868446cb5a3d81dc26bc",
    )

    "mac os x" to "x86_64",
    "mac os x" to "amd64",
    -> HarfBuzzNativeTarget(
        resourcePath = "/kalligraphie/harfbuzz/macos/x64/libharfbuzz.dylib",
        fileName = "libharfbuzz.dylib",
        nativeArtifactId = "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-macos/libharfbuzz.dylib",
        librarySha256 = "4f83ffccaf2a92e4658db8353ac7d529c52d5e4d34027a92cf9870487e1bc68b",
    )

    "mac os x" to "aarch64",
    "mac os x" to "arm64",
    -> HarfBuzzNativeTarget(
        resourcePath = "/kalligraphie/harfbuzz/macos/arm64/libharfbuzz.dylib",
        fileName = "libharfbuzz.dylib",
        nativeArtifactId = "org.lwjgl:lwjgl-harfbuzz:3.4.3:natives-macos-arm64/libharfbuzz.dylib",
        librarySha256 = "302418f6ec10fee5e69fbe8b79f3b47e008f081ee88c912d19d2a9d820e7b9da",
    )

    else -> null
}

internal class HarfBuzzNativeLibrary(
    lookup: SymbolLookup,
    target: HarfBuzzNativeTarget,
) {
    val identity: ShapingBackendIdentity = ShapingBackendIdentity(
        backendId = "harfbuzz-jvm",
        nativeVersion = HARFBUZZ_VERSION,
        nativeSourceRevision = HARFBUZZ_SOURCE_REVISION,
        nativeArtifactId = target.nativeArtifactId,
        nativeArtifactSha256 = target.librarySha256,
        featurePolicy = PINNED_FEATURE_POLICY,
        configurationFingerprint = CONFIGURATION_FINGERPRINT,
    )

    private val linker: Linker = Linker.nativeLinker()
    private val versionString: MethodHandle = handle(lookup, "hb_version_string", FunctionDescriptor.of(ValueLayout.ADDRESS))
    private val blobCreate: MethodHandle = handle(
        lookup,
        "hb_blob_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val blobDestroy: MethodHandle = handle(lookup, "hb_blob_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val faceCreate: MethodHandle = handle(
        lookup,
        "hb_face_create",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val faceDestroy: MethodHandle = handle(lookup, "hb_face_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val faceGetUpem: MethodHandle = handle(lookup, "hb_face_get_upem", FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS))
    private val fontCreate: MethodHandle = handle(lookup, "hb_font_create", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
    private val fontDestroy: MethodHandle = handle(lookup, "hb_font_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val otFontSetFuncs: MethodHandle = handle(lookup, "hb_ot_font_set_funcs", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val fontSetScale: MethodHandle = handle(
        lookup,
        "hb_font_set_scale",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val bufferCreate: MethodHandle = handle(lookup, "hb_buffer_create", FunctionDescriptor.of(ValueLayout.ADDRESS))
    private val bufferDestroy: MethodHandle = handle(lookup, "hb_buffer_destroy", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
    private val bufferSetDirection: MethodHandle = handle(
        lookup,
        "hb_buffer_set_direction",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferSetScript: MethodHandle = handle(
        lookup,
        "hb_buffer_set_script",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferSetLanguage: MethodHandle = handle(
        lookup,
        "hb_buffer_set_language",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val bufferSetClusterLevel: MethodHandle = handle(
        lookup,
        "hb_buffer_set_cluster_level",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferSetFlags: MethodHandle = handle(
        lookup,
        "hb_buffer_set_flags",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferAdd: MethodHandle = handle(
        lookup,
        "hb_buffer_add",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val languageFromString: MethodHandle = handle(
        lookup,
        "hb_language_from_string",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val scriptFromString: MethodHandle = handle(
        lookup,
        "hb_script_from_string",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val shape: MethodHandle = handle(
        lookup,
        "hb_shape",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val bufferGetLength: MethodHandle = handle(
        lookup,
        "hb_buffer_get_length",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    private val bufferGetGlyphInfos: MethodHandle = handle(
        lookup,
        "hb_buffer_get_glyph_infos",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val bufferGetGlyphPositions: MethodHandle = handle(
        lookup,
        "hb_buffer_get_glyph_positions",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val glyphInfoGetGlyphFlags: MethodHandle = handle(
        lookup,
        "hb_glyph_info_get_glyph_flags",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    private val ligatureCarets: MethodHandle = handle(
        lookup,
        "hb_ot_layout_get_ligature_carets",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
        ),
    )

    fun versionString(): String = address(versionString).reinterpret(MAX_VERSION_BYTES).getString(0)

    fun shape(request: ShapingRequest, fontBytes: ByteArray, layoutSize: Float): ShapedGlyphRun = Arena.ofConfined().use { arena ->
        val copiedFont = arena.allocate(fontBytes.size.toLong(), 1)
        copiedFont.copyFrom(MemorySegment.ofArray(fontBytes))
        val blob = requireNativeHandle(address(blobCreate, copiedFont, fontBytes.size, HB_MEMORY_MODE_READONLY, MemorySegment.NULL, MemorySegment.NULL), "blob")
        try {
            val face = requireNativeHandle(address(faceCreate, blob, 0), "face")
            try {
                val designToLayout = DesignToLayoutScale.create(layoutSize, int(faceGetUpem, face))
                val font = requireNativeHandle(address(fontCreate, face), "font")
                try {
                    callVoid(otFontSetFuncs, font)
                    callVoid(fontSetScale, font, designToLayout.unitsPerEm, designToLayout.unitsPerEm)
                    val buffer = requireNativeHandle(address(bufferCreate), "buffer")
                    try {
                        configureBuffer(arena, buffer, request)
                        val scalarRanges = request.snapshot.scalarRanges(request.range)
                        request.snapshot.scalarValues(request.range).forEachIndexed { tokenValue, scalar ->
                            callVoid(bufferAdd, buffer, scalar, tokenValue)
                        }
                        val features = featureArray(arena, request.features)
                        callVoid(shape, font, buffer, features, request.features.size)
                        shapedRun(arena, request, font, buffer, scalarRanges, designToLayout)
                    } finally {
                        callVoid(bufferDestroy, buffer)
                    }
                } finally {
                    callVoid(fontDestroy, font)
                }
            } finally {
                callVoid(faceDestroy, face)
            }
        } finally {
            callVoid(blobDestroy, blob)
        }
    }

    private fun configureBuffer(arena: Arena, buffer: MemorySegment, request: ShapingRequest) {
        callVoid(bufferSetDirection, buffer, request.direction.toHarfBuzzDirection())
        val script = arena.allocateFrom(request.script.value)
        callVoid(bufferSetScript, buffer, int(scriptFromString, script, -1))
        val language = arena.allocateFrom(request.language)
        callVoid(bufferSetLanguage, buffer, address(languageFromString, language, -1))
        callVoid(bufferSetClusterLevel, buffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS)
        val flags = HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT or
            (if (request.bot) HB_BUFFER_FLAG_BOT else 0) or
            (if (request.eot) HB_BUFFER_FLAG_EOT else 0)
        callVoid(bufferSetFlags, buffer, flags)
    }

    private fun featureArray(arena: Arena, features: List<OpenTypeFeature>): MemorySegment {
        if (features.isEmpty()) return MemorySegment.NULL
        val result = arena.allocate(FEATURE_BYTES * features.size, ValueLayout.JAVA_INT.byteAlignment())
        features.forEachIndexed { index, feature ->
            val offset = index.toLong() * FEATURE_BYTES
            result.set(ValueLayout.JAVA_INT, offset, openTypeTag(feature.tag))
            result.set(ValueLayout.JAVA_INT, offset + 4, feature.value)
            result.set(ValueLayout.JAVA_INT, offset + 8, 0)
            result.set(ValueLayout.JAVA_INT, offset + 12, -1)
        }
        return result
    }

    private fun shapedRun(
        arena: Arena,
        request: ShapingRequest,
        font: MemorySegment,
        buffer: MemorySegment,
        scalarRanges: List<TextRange>,
        designToLayout: DesignToLayoutScale,
    ): ShapedGlyphRun {
        val glyphCount = int(bufferGetLength, buffer)
        val infos = address(bufferGetGlyphInfos, buffer, MemorySegment.NULL).reinterpret(glyphCount.toLong() * GLYPH_INFO_BYTES)
        val positions = address(bufferGetGlyphPositions, buffer, MemorySegment.NULL).reinterpret(glyphCount.toLong() * GLYPH_POSITION_BYTES)
        val glyphRecords = List(glyphCount) { glyphIndex ->
            val infoOffset = glyphIndex.toLong() * GLYPH_INFO_BYTES
            val positionOffset = glyphIndex.toLong() * GLYPH_POSITION_BYTES
            val tokenValue = infos.get(ValueLayout.JAVA_INT, infoOffset + 8)
            require(tokenValue in scalarRanges.indices) { "HarfBuzz returned a cluster token outside this shaping request." }
            NativeGlyphRecord(
                glyphId = infos.get(ValueLayout.JAVA_INT, infoOffset),
                tokenValue = tokenValue,
                safetyMask = int(glyphInfoGetGlyphFlags, infos.asSlice(infoOffset, GLYPH_INFO_BYTES)),
                xAdvance = positions.get(ValueLayout.JAVA_INT, positionOffset),
                yAdvance = positions.get(ValueLayout.JAVA_INT, positionOffset + 4),
                xOffset = positions.get(ValueLayout.JAVA_INT, positionOffset + 8),
                yOffset = positions.get(ValueLayout.JAVA_INT, positionOffset + 12),
            )
        }
        val clusters = buildClusters(request, scalarRanges, glyphRecords)
        val clustersByToken = clusters.associateBy { cluster -> cluster.token }
        val glyphs = glyphRecords.map { record ->
            val flags = ShapingSafetyFlags(
                unsafeToBreak = record.safetyMask and HB_GLYPH_FLAG_UNSAFE_TO_BREAK != 0,
                unsafeToConcat = record.safetyMask and HB_GLYPH_FLAG_UNSAFE_TO_CONCAT != 0,
            )
            ShapedGlyph(
                glyphId = GlyphId(record.glyphId),
                xAdvance = designToLayout.convert(record.xAdvance),
                yAdvance = designToLayout.convert(record.yAdvance),
                xOffset = designToLayout.convert(record.xOffset),
                yOffset = designToLayout.convert(record.yOffset),
                safetyFlags = flags,
                clusterTokens = listOf(ShaperClusterToken(record.tokenValue)),
            )
        }
        val caretFacts = glyphRecords.mapIndexedNotNull { glyphIndex, record ->
            val cluster = clustersByToken.getValue(ShaperClusterToken(record.tokenValue))
            if (cluster.internalAdmissibleGraphemeBoundaries().isEmpty()) {
                null
            } else {
                ligatureCaretFact(
                    arena,
                    font,
                    request.direction,
                    record.glyphId,
                    glyphIndex,
                    cluster,
                    designToLayout,
                )
            }
        }
        return ShapedGlyphRun(
            range = request.range,
            fontInstanceKey = request.font.key,
            backendIdentity = identity,
            direction = request.direction,
            script = request.script,
            language = request.language,
            bidiLevel = request.bidiLevel,
            bot = request.bot,
            eot = request.eot,
            featurePolicy = request.featurePolicy,
            features = request.features,
            graphemeClusters = request.graphemeClusters,
            glyphs = glyphs,
            clusters = clusters,
            ligatureCaretFacts = caretFacts,
        )
    }

    private fun buildClusters(
        request: ShapingRequest,
        scalarRanges: List<TextRange>,
        glyphs: List<NativeGlyphRecord>,
    ): List<ShaperCluster> {
        if (scalarRanges.isEmpty()) return emptyList()
        val observedTokens = glyphs.map(NativeGlyphRecord::tokenValue).distinct().sorted()
        if (observedTokens.isEmpty()) return emptyList()
        val requestBoundaries = request.graphemeClusters
            .flatMap { grapheme -> listOf(grapheme.start, grapheme.endExclusive) }
            .distinct()
        return observedTokens.mapIndexed { index, tokenValue ->
            val endTokenExclusive = observedTokens.getOrNull(index + 1) ?: scalarRanges.size
            val sourceScalars = scalarRanges.subList(tokenValue, endTokenExclusive)
            val sourceRange = TextRange(sourceScalars.first().start, sourceScalars.last().endExclusive)
            val admissibleBoundaries = requestBoundaries.filter { boundary ->
                boundary.sharesVersionWith(sourceRange.start) &&
                    boundary.compareTo(sourceRange.start) >= 0 &&
                    boundary.compareTo(sourceRange.endExclusive) <= 0
            }
            ShaperCluster(
                token = ShaperClusterToken(tokenValue),
                sourceRange = sourceRange,
                scalarRanges = sourceScalars,
                admissibleGraphemeBoundaries = admissibleBoundaries,
            )
        }
    }

    private fun ligatureCaretFact(
        arena: Arena,
        font: MemorySegment,
        direction: ShapingDirection,
        glyphId: Int,
        glyphIndex: Int,
        cluster: ShaperCluster,
        designToLayout: DesignToLayoutScale,
    ): GdefLigatureCaretFact {
        val expectedCaretCount = cluster.internalAdmissibleGraphemeBoundaries().size
        val count = arena.allocateFrom(ValueLayout.JAVA_INT, expectedCaretCount)
        val positions = arena.allocate(ValueLayout.JAVA_INT, expectedCaretCount.toLong())
        val totalCount = int(
            ligatureCarets,
            font,
            direction.toHarfBuzzDirection(),
            glyphId,
            0,
            count,
            positions,
        )
        val copiedCount = count.get(ValueLayout.JAVA_INT, 0)
        val safelyReadableCount = copiedCount.coerceIn(0, expectedCaretCount)
        return LigatureCaretFactInterpreter.fromNativeResponse(
            glyphIndex = glyphIndex,
            direction = direction,
            cluster = cluster,
            response = NativeLigatureCaretResponse(
                totalCount = totalCount,
                copiedCount = copiedCount,
                positions = List(safelyReadableCount) { index ->
                    designToLayout.convert(positions.getAtIndex(ValueLayout.JAVA_INT, index.toLong()))
                },
            ),
        )
    }

    private fun handle(lookup: SymbolLookup, name: String, descriptor: FunctionDescriptor): MethodHandle =
        linker.downcallHandle(lookup.findOrThrow(name), descriptor)
}

private data class NativeGlyphRecord(
    val glyphId: Int,
    val tokenValue: Int,
    val safetyMask: Int,
    val xAdvance: Int,
    val yAdvance: Int,
    val xOffset: Int,
    val yOffset: Int,
)

/**
 * Direct result of the native `hb_ot_layout_get_ligature_carets` ABI call.
 *
 * [totalCount] is the function return value and [copiedCount] is the in/out count after the
 * call. They are deliberately retained separately so the portable interpretation can reject a
 * truncated or otherwise inconsistent native response. Tests may construct this value to audit
 * the ABI boundary without using a second live HarfBuzz invocation as an oracle.
 */
internal data class NativeLigatureCaretResponse(
    val totalCount: Int,
    val copiedCount: Int,
    val positions: List<LayoutUnit>,
)

/** Interprets an audited native GDEF response against a cluster's editable grapheme boundaries. */
internal object LigatureCaretFactInterpreter {
    /**
     * Returns a fact whose boundaries are logical-source ordered, independently of glyph output
     * order. HarfBuzz exposes GDEF carets in increasing glyph-coordinate order; that order is
     * reversed for right-to-left source text while each signed position remains relative to the
     * same glyph origin and baseline.
     */
    fun fromNativeResponse(
        glyphIndex: Int,
        direction: ShapingDirection,
        cluster: ShaperCluster,
        response: NativeLigatureCaretResponse,
    ): GdefLigatureCaretFact {
        val logicalSourceBoundaries = cluster.internalAdmissibleGraphemeBoundaries()
        require(logicalSourceBoundaries.isNotEmpty()) {
            "GDEF caret interpretation requires an editable internal grapheme boundary."
        }
        val expectedCount = logicalSourceBoundaries.size
        return when {
            response.totalCount == 0 && response.copiedCount == 0 ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.ABSENT,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                )

            response.totalCount != expectedCount ||
                response.copiedCount != expectedCount ||
                response.positions.size != expectedCount ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.INCONSISTENT,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                )

            else ->
                GdefLigatureCaretFact(
                    glyphIndex = glyphIndex,
                    state = GdefLigatureCaretState.AVAILABLE,
                    logicalSourceBoundaries = logicalSourceBoundaries,
                    positions = if (direction == ShapingDirection.RIGHT_TO_LEFT) {
                        response.positions.asReversed()
                    } else {
                        response.positions
                    },
                )
        }
    }
}

private fun ShaperCluster.internalAdmissibleGraphemeBoundaries() = admissibleGraphemeBoundaries.filter { boundary ->
    boundary.compareTo(sourceRange.start) > 0 && boundary.compareTo(sourceRange.endExclusive) < 0
}

private class DesignToLayoutScale private constructor(
    private val layoutSize: Double,
    val unitsPerEm: Int,
) {
    fun convert(designUnit: Int): LayoutUnit {
        val converted = designUnit.toDouble() * layoutSize / unitsPerEm.toDouble()
        val narrowed = converted.toFloat()
        require(converted.isFinite() && narrowed.isFinite()) {
            "A HarfBuzz design-unit value cannot be represented as a finite layout unit."
        }
        return LayoutUnit(narrowed)
    }

    companion object {
        fun create(layoutSize: Float, unitsPerEm: Int): DesignToLayoutScale {
            require(layoutSize.isFinite() && layoutSize > 0f) {
                "The layout size must be finite and positive."
            }
            require(unitsPerEm > 0) { "HarfBuzz returned a non-positive face units-per-em value." }
            return DesignToLayoutScale(layoutSize.toDouble(), unitsPerEm)
        }
    }
}

private fun ShapingDirection.toHarfBuzzDirection(): Int = when (this) {
    ShapingDirection.LEFT_TO_RIGHT -> HB_DIRECTION_LTR
    ShapingDirection.RIGHT_TO_LEFT -> HB_DIRECTION_RTL
}

private fun openTypeTag(tag: String): Int =
    (tag[0].code shl 24) or (tag[1].code shl 16) or (tag[2].code shl 8) or tag[3].code

private fun requireNativeHandle(handle: MemorySegment, label: String): MemorySegment {
    require(handle != MemorySegment.NULL) { "HarfBuzz could not create a native $label." }
    return handle
}

private fun address(handle: MethodHandle, vararg arguments: Any): MemorySegment =
    handle.invokeWithArguments(*arguments) as MemorySegment

private fun int(handle: MethodHandle, vararg arguments: Any): Int =
    handle.invokeWithArguments(*arguments) as Int

private fun callVoid(handle: MethodHandle, vararg arguments: Any) {
    handle.invokeWithArguments(*arguments)
}

private fun ByteArray.sha256Hex(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun shapingFailure(code: String, message: String): FontOperationResult.Failure {
    val error = FontError.FontDataFailure(code, message, FontDiagnosticLocation.Source)
    return FontOperationResult.Failure(error, listOf(error.toDiagnostic()))
}

private const val HARFBUZZ_VERSION: String = "14.3.0"
private const val HARFBUZZ_SOURCE_REVISION: String = "9f2f03173b7fee860cc00d999857d09fa4a362e2"
private val PINNED_FEATURE_POLICY: ShapingFeaturePolicy = ShapingFeaturePolicy(
    policyId = "harfbuzz-defaults",
    version = HARFBUZZ_VERSION,
    application = ShapingFeaturePolicyApplication.PINNED_BACKEND_DEFAULTS,
)
private const val CONFIGURATION_FINGERPRINT: String =
    "harfbuzz-14.3.0;ot-font-funcs;scale=face-upem;layout-conversion=layout-size-over-upem;explicit-direction-script-language-bot-eot;" +
        "cluster-level=monotone-characters;flags=produce-unsafe-to-concat;feature-policy=harfbuzz-defaults@14.3.0;feature-overrides=explicit"
private val NON_DETERMINISTIC_FEATURES: Set<String> = setOf("rand")
private const val HB_MEMORY_MODE_READONLY: Int = 1
private const val HB_DIRECTION_LTR: Int = 4
private const val HB_DIRECTION_RTL: Int = 5
private const val HB_BUFFER_FLAG_BOT: Int = 0x00000001
private const val HB_BUFFER_FLAG_EOT: Int = 0x00000002
private const val HB_BUFFER_FLAG_PRODUCE_UNSAFE_TO_CONCAT: Int = 0x00000040
private const val HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS: Int = 1
private const val HB_GLYPH_FLAG_UNSAFE_TO_BREAK: Int = 0x00000001
private const val HB_GLYPH_FLAG_UNSAFE_TO_CONCAT: Int = 0x00000002
private const val FEATURE_BYTES: Long = 16L
private const val GLYPH_INFO_BYTES: Long = 20L
private const val GLYPH_POSITION_BYTES: Long = 20L
private const val MAX_VERSION_BYTES: Long = 32L
