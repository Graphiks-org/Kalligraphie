package org.graphiks.kalligraphie.platform.apple

import org.graphiks.kalligraphie.api.*
import java.io.DataOutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.Locale

/** Immutable supported OS/kernel/architecture domain, captured once for one catalogue. */
internal class CoreTextRuntimeIdentity(val route: NativeFontRouteIdentity) {
    val profile = NativeHandleProfile("coretext", "1", 1, "org.graphiks.kalligraphie.coretext")
    fun context(key: FontRenderAssetKey): NativeFontAssetContext {
        val hash = MessageDigest.getInstance("SHA-256")
        DataOutputStream(DigestOutputStream(java.io.OutputStream.nullOutputStream(), hash)).use { output ->
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); output.writeInt(bytes.size); output.write(bytes) }
            text("CoreTextFontAsset:1")
            text(key.generation.provider.value); text(key.generation.value)
            val instance = key.fontInstanceKey
            when (val source = instance.face.source) {
                is FontSourceId.Portable -> { text("portable"); text(source.contentDigest.value) }
                is FontSourceId.Opaque -> { text("opaque"); text(source.providerId); text(source.catalogGeneration); text(source.sourceToken) }
            }
            output.writeInt(instance.face.faceIndex)
            text(instance.interpretation.pipelineId); text(instance.interpretation.version)
            output.writeInt(instance.layoutSize.value.toBits())
            output.writeInt(instance.geometry.normalizedAxes.size)
            instance.geometry.normalizedAxes.forEach { text(it.tag); output.writeInt(it.value.toBits()) }
            output.writeBoolean(instance.geometry.syntheticBold); output.writeBoolean(instance.geometry.syntheticItalic)
            text(key.variant.value)
            val variant = key.variantSnapshot ?: FontRenderVariantSnapshot.default
            output.writeInt(variant.cpalPaletteIndex ?: -1)
            output.writeBoolean(variant.foregroundColor != null)
            variant.foregroundColor?.let { output.writeInt(it.red); output.writeInt(it.green); output.writeInt(it.blue); output.writeInt(it.alpha) }
            val profile = key.representationProfile as NativeHandleProfile
            text(profile.bridgeKind); text(profile.bridgeId); text(profile.bridgeVersion); output.writeInt(profile.schemaVersion)
            text(route.bridgeKind); text(route.bridgeId); text(route.bridgeVersion); text(route.runtimeInterpretationId)
        }
        return NativeFontAssetContext(route, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
    }
    companion object {
        fun checkPlatform(): Pair<String, String> {
            val os = System.getProperty("os.name", "")
            val version = System.getProperty("os.version", "")
            val architecture = when (System.getProperty("os.arch", "").lowercase(Locale.ROOT)) {
                "amd64", "x86_64" -> "x64"
                "aarch64", "arm64" -> "arm64"
                else -> "unsupported"
            }
            if (!os.startsWith("Mac") || (version.substringBefore('.').toIntOrNull() ?: 0) < 15 || architecture == "unsupported") {
                nativeFailure("font.native-platform-unsupported", "CoreText access requires macOS 15 or later on x64 or arm64 JVM.")
            }
            return version to architecture
        }
        fun capture(platform: Pair<String, String>, bindings: CoreTextBindings): CoreTextRuntimeIdentity = CoreTextRuntimeIdentity(
            NativeFontRouteIdentity("coretext", "org.graphiks.kalligraphie.coretext", "1", "macOS:${platform.first};build:${bindings.kernelBuild()};arch:${platform.second}"),
        )
    }
}
