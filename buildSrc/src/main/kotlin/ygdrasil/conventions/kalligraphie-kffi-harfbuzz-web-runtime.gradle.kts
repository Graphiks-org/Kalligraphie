package ygdrasil.conventions

import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.kotlin.dsl.getByType

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

/**
 * Extracts the bundled WebAssembly HarfBuzz runtime from the published kffi klibs.
 *
 * The authored glue and the Emscripten module travel inside the `kffi-harfbuzz` js/wasmJs klibs, but
 * a consumer's bundler resolves `@JsModule("./kffi-harfbuzz-web.mjs")` relative to the generated
 * module, so the two files must be extracted from the artifact and placed where the bundler looks —
 * the same mechanism the Compose Gradle plugin applies to the Skiko runtime. Until this ships as a
 * kffi Gradle plugin, consumers apply this convention.
 */
val kffiVersion = extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("kffi")
    .get()
    .requiredVersion

val harfBuzzWebRuntimeJs by configurations.creating { isTransitive = false }
val harfBuzzWebRuntimeWasm by configurations.creating { isTransitive = false }

dependencies {
    harfBuzzWebRuntimeJs("org.graphiks:kffi-harfbuzz-js:$kffiVersion")
    harfBuzzWebRuntimeWasm("org.graphiks:kffi-harfbuzz-wasm-js:$kffiVersion")
}

abstract class ExtractHarfBuzzWebRuntimeTask : DefaultTask() {
    @get:org.gradle.api.tasks.InputFiles
    abstract val archives: ConfigurableFileCollection

    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @org.gradle.api.tasks.TaskAction
    fun extract() {
        val output = outputDirectory.get().asFile
        output.mkdirs()
        val expected = listOf("hb.mjs", "kffi-harfbuzz-web.mjs")
        archives.files.forEach { archive ->
            ZipFile(archive).use { zip ->
                expected.forEach { name ->
                    val entry = zip.getEntry(name) ?: return@forEach
                    zip.getInputStream(entry).use { input ->
                        File(output, name).outputStream().use { target -> input.copyTo(target) }
                    }
                }
            }
        }
        expected.forEach { name ->
            check(File(output, name).isFile) {
                "The HarfBuzz web runtime entry $name is missing from ${archives.files}."
            }
        }
    }
}

val extractHarfBuzzWebRuntime by tasks.registering(ExtractHarfBuzzWebRuntimeTask::class) {
    group = "harfbuzz"
    description = "Extracts the WebAssembly HarfBuzz runtime for the web bundler."
    archives.from(harfBuzzWebRuntimeJs, harfBuzzWebRuntimeWasm)
    outputDirectory.set(layout.buildDirectory.dir("generated/harfbuzzWebRuntime"))
}

kotlin {
    sourceSets {
        named("jsMain") { resources.srcDir(extractHarfBuzzWebRuntime) }
        named("wasmJsMain") { resources.srcDir(extractHarfBuzzWebRuntime) }
    }
}
