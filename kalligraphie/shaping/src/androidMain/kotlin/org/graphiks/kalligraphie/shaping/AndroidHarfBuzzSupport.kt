@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.shaping

import org.graphiks.kalligraphie.api.OpenTypeFeature
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kffi.harfbuzz.HarfBuzz
import org.graphiks.kffi.harfbuzz.HarfBuzzBlob
import org.graphiks.kffi.harfbuzz.HarfBuzzBuffer
import org.graphiks.kffi.harfbuzz.HarfBuzzBufferFlags
import org.graphiks.kffi.harfbuzz.HarfBuzzClusterLevel
import org.graphiks.kffi.harfbuzz.HarfBuzzDirection
import org.graphiks.kffi.harfbuzz.HarfBuzzFace
import org.graphiks.kffi.harfbuzz.HarfBuzzFeature
import org.graphiks.kffi.harfbuzz.HarfBuzzFont
import org.graphiks.kffi.harfbuzz.HarfBuzzTag
import org.graphiks.kffi.harfbuzz.HarfBuzzBindingException as KffiHarfBuzzBindingException
import org.graphiks.kffi.harfbuzz.HarfBuzzBindingFailure as KffiHarfBuzzBindingFailure

/**
 * Opens the real Android HarfBuzz platform binding over the published kffi Android artifact.
 *
 * The Kotlin/Native declarations of the kffi HarfBuzz surface are identical to the JVM ones, so
 * [AndroidHarfBuzzPlatformBinding] mirrors `HarfBuzzBindings.jvm.kt` behaviour exactly: the same
 * design-unit-only scaling, the same total failure mapping and the same font→face→blob ownership
 * order. The matching `androidMain` actual in `HarfBuzzBindings.android.kt` calls this function.
 */
internal fun openAndroidHarfBuzzPlatformBinding(): HarfBuzzPlatformBinding = try {
    AndroidHarfBuzzPlatformBinding(HarfBuzz.open())
} catch (failure: KffiHarfBuzzBindingException) {
    throw HarfBuzzBindingException(failure.failure.toPortableFailure(), failure.message)
}

private class AndroidHarfBuzzPlatformBinding(private val hb: HarfBuzz) : HarfBuzzPlatformBinding {
    override val identity: PlatformBindingIdentity = PlatformBindingIdentity(
        operatingSystem = hb.bindingIdentity.operatingSystem,
        architecture = hb.bindingIdentity.architecture,
        artifactId = hb.bindingIdentity.artifactId,
        artifactSha256 = hb.bindingIdentity.artifactSha256,
        upstreamSourceRevision = hb.bindingIdentity.upstreamSourceRevision,
        buildChainIdentity = hb.bindingIdentity.buildChainIdentity,
    )

    override fun createBuffer(): PlatformHarfBuzzBuffer = AndroidHarfBuzzBuffer(hb, hb.createBuffer())

    override val supportsVariationLocation: Boolean = true

    override fun prepare(fontBytes: ByteArray, faceIndex: Int, variationLocation: FloatArray): PlatformPreparedFont {
        val blob = hb.createBlob(fontBytes)
        var face: HarfBuzzFace? = null
        var font: HarfBuzzFont? = null
        try {
            face = blob.createFace(faceIndex)
            val unitsPerEm = face.unitsPerEm()
            font = face.createFont()
            font.useOpenTypeFunctions()
            font.setScale(unitsPerEm, unitsPerEm)
            if (variationLocation.any { it != 0f }) {
                font.setVarCoordsNormalized(variationLocation.toNormalizedVarCoords())
            }
            face.makeImmutable()
            font.makeImmutable()
            return AndroidPreparedFont(blob, face, font, unitsPerEm)
        } catch (error: Throwable) {
            val failures = buildList {
                runCatching { font?.close() }.exceptionOrNull()?.let(::add)
                runCatching { face?.close() }.exceptionOrNull()?.let(::add)
                runCatching { blob.close() }.exceptionOrNull()?.let(::add)
            }
            aggregateFailures(failures)?.let(error::addSuppressed)
            throw error
        }
    }

    override fun release(prepared: PlatformPreparedFont) {
        val android = prepared as AndroidPreparedFont
        val failures = buildList {
            runCatching { android.font.close() }.exceptionOrNull()?.let(::add)
            runCatching { android.face.close() }.exceptionOrNull()?.let(::add)
            runCatching { android.blob.close() }.exceptionOrNull()?.let(::add)
        }
        aggregateFailures(failures)?.let { throw it }
    }
}

private class AndroidPreparedFont(
    val blob: HarfBuzzBlob,
    val face: HarfBuzzFace,
    val font: HarfBuzzFont,
    override val unitsPerEm: Int,
) : PlatformPreparedFont {
    override fun horizontalAdvance(glyphId: Int): Int = font.glyphHorizontalAdvance(glyphId)

    override fun ligatureCarets(
        direction: ShapingDirection,
        glyphId: Int,
        offset: Int,
        count: Int,
    ): PlatformLigatureCarets {
        val carets = font.ligatureCarets(direction.toKffiDirection(), glyphId, offset, count)
        return PlatformLigatureCarets(
            totalCount = carets.totalCount,
            copiedCount = carets.copiedCount,
            positions = carets.positions.toList(),
        )
    }
}

private class AndroidHarfBuzzBuffer(
    private val hb: HarfBuzz,
    private val buffer: HarfBuzzBuffer,
) : PlatformHarfBuzzBuffer {
    override fun setDirection(direction: ShapingDirection) {
        buffer.setDirection(direction.toKffiDirection())
    }

    override fun setScript(scriptValue: String) {
        buffer.setScript(hb.parseScript(scriptValue))
    }

    override fun setLanguage(language: String) {
        buffer.setLanguage(hb.parseLanguage(language))
    }

    override fun setClusterLevelMonotoneCharacters() {
        buffer.setClusterLevel(HarfBuzzClusterLevel.MONOTONE_CHARACTERS)
    }

    override fun setFlags(beginningOfText: Boolean, endOfText: Boolean, produceUnsafeToConcat: Boolean) {
        buffer.setFlags(
            HarfBuzzBufferFlags(
                beginningOfText = beginningOfText,
                endOfText = endOfText,
                produceUnsafeToConcat = produceUnsafeToConcat,
            ),
        )
    }

    override fun addUtf32(text: IntArray, offset: Int, length: Int) {
        buffer.addUtf32(text, offset, length)
    }

    override fun shape(prepared: PlatformPreparedFont, features: List<OpenTypeFeature>): Boolean {
        val android = prepared as AndroidPreparedFont
        val kffiFeatures = features.map { HarfBuzzFeature(HarfBuzzTag.of(it.tag), it.value) }
        return buffer.shape(android.font, kffiFeatures)
    }

    override fun glyphCount(): Int = buffer.glyphCount()

    override fun glyphInfos(): List<PlatformGlyphInfo> = buffer.glyphInfos().map { info ->
        PlatformGlyphInfo(
            glyphId = info.glyphId,
            cluster = info.cluster,
            unsafeToBreak = info.flags.unsafeToBreak,
            unsafeToConcat = info.flags.unsafeToConcat,
        )
    }

    override fun glyphPositions(): List<PlatformGlyphPosition> = buffer.glyphPositions().map { position ->
        PlatformGlyphPosition(
            xAdvance = position.xAdvance,
            yAdvance = position.yAdvance,
            xOffset = position.xOffset,
            yOffset = position.yOffset,
        )
    }

    override fun close() {
        buffer.close()
    }
}

/**
 * Total mapping from the kffi failure taxonomy to the portable one.
 *
 * This intentionally has no `else` branch: a new kffi variant becomes a compile error here instead
 * of silently collapsing into the wrong portable diagnostic. It duplicates the JVM actual's
 * private helper because that helper is not visible across source sets.
 */
private fun KffiHarfBuzzBindingFailure.toPortableFailure(): HarfBuzzBindingFailure = when (this) {
    KffiHarfBuzzBindingFailure.UNSUPPORTED_PLATFORM -> HarfBuzzBindingFailure.UNSUPPORTED_PLATFORM
    KffiHarfBuzzBindingFailure.RESOURCE_MISSING -> HarfBuzzBindingFailure.RESOURCE_MISSING
    KffiHarfBuzzBindingFailure.RESOURCE_CORRUPT -> HarfBuzzBindingFailure.RESOURCE_CORRUPT
    KffiHarfBuzzBindingFailure.LIBRARY_LOAD -> HarfBuzzBindingFailure.LIBRARY_LOAD
    KffiHarfBuzzBindingFailure.SYMBOL_RESOLUTION -> HarfBuzzBindingFailure.SYMBOL_RESOLUTION
    KffiHarfBuzzBindingFailure.VERSION_MISMATCH -> HarfBuzzBindingFailure.VERSION_MISMATCH
    KffiHarfBuzzBindingFailure.NATIVE_OPERATION -> HarfBuzzBindingFailure.NATIVE_OPERATION
}

private fun ShapingDirection.toKffiDirection(): HarfBuzzDirection = when (this) {
    ShapingDirection.LEFT_TO_RIGHT -> HarfBuzzDirection.LEFT_TO_RIGHT
    ShapingDirection.RIGHT_TO_LEFT -> HarfBuzzDirection.RIGHT_TO_LEFT
    ShapingDirection.TOP_TO_BOTTOM -> HarfBuzzDirection.TOP_TO_BOTTOM
}

