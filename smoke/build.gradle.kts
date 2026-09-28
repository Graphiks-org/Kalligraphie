import java.io.File
import java.util.zip.ZipFile

plugins {
    kotlin("multiplatform") version "2.4.10"
}

/**
 * The published-artifact consumer smoke.
 *
 * Every dependency here is a published coordinate, resolved from `mavenLocal` (after the main build's
 * `publishToMavenLocal`) or from the Central snapshot. Nothing is a `project(...)`, so a broken POM,
 * a missing Gradle module metadata variant or a klib that lost its WebAssembly runtime fails here and
 * nowhere else.
 */
val kalligraphieVersion: String = providers.gradleProperty("kalligraphie.version").getOrElse("1.0.0-SNAPSHOT")
val kffiVersion: String = providers.gradleProperty("kffi.version").getOrElse("1.0.0-SNAPSHOT")

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(25)
    jvm()
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }

    applyDefaultHierarchyTemplate {
        common {
            group("web") {
                withJs()
                withWasmJs()
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.graphiks:kalligraphie:$kalligraphieVersion")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
        }
        val webTest by getting {
            dependencies {
                // The browser adapter has web targets only, so its smoke lives with the web tests.
                implementation("org.graphiks:kalligraphie-platform-browser:$kalligraphieVersion")
            }
        }
    }
}

/**
 * The published klibs carry the authored glue and the Emscripten module, but a consumer's bundler
 * resolves `@JsModule("./kffi-harfbuzz-web.mjs")` relative to the generated module, so the two files
 * are extracted from the published kffi artifacts — the mechanism the library's own build applies,
 * reproduced here because a consumer must apply it too.
 */
val harfBuzzWebRuntimeJs by configurations.creating { isTransitive = false }
val harfBuzzWebRuntimeWasm by configurations.creating { isTransitive = false }

dependencies {
    harfBuzzWebRuntimeJs("org.graphiks:kffi-harfbuzz-js:$kffiVersion")
    harfBuzzWebRuntimeWasm("org.graphiks:kffi-harfbuzz-wasm-js:$kffiVersion")
}

val extractHarfBuzzWebRuntime by tasks.registering {
    group = "harfbuzz"
    description = "Extracts the WebAssembly HarfBuzz runtime the published klibs reference."
    val archives = files(harfBuzzWebRuntimeJs, harfBuzzWebRuntimeWasm)
    inputs.files(archives)
    val outputDirectory = layout.buildDirectory.dir("generated/harfbuzzWebRuntime")
    outputs.dir(outputDirectory)
    doLast {
        val output = outputDirectory.get().asFile.apply { mkdirs() }
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
                "The published kffi runtime is missing $name in ${archives.files}."
            }
        }
    }
}

kotlin {
    sourceSets {
        named("jsMain") { resources.srcDir(extractHarfBuzzWebRuntime) }
        named("wasmJsMain") { resources.srcDir(extractHarfBuzzWebRuntime) }
    }
}
