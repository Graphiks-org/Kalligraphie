pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

/**
 * The consumer smoke build resolves the published Kalligraphie and kffi coordinates, never a project
 * of the main build: it is the only check that fails when a published POM, a Gradle module metadata
 * file or a klib resource is wrong. `mavenLocal` serves the artifacts a local `publishToMavenLocal`
 * wrote; the Central snapshot serves the kffi web runtime the published klibs reference.
 */
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist/") {
                    name = "Node Distributions at $url"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("org.nodejs", "node") }
                }
            }
            filter { includeGroup("org.nodejs") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn Distributions at $url"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("com.yarnpkg", "yarn") }
                }
            }
            filter { includeGroup("com.yarnpkg") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                    name = "Binaryen Distributions at $url"
                    patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("com.github.webassembly", "binaryen") }
                }
            }
            filter { includeGroup("com.github.webassembly") }
        }
        maven {
            name = "KffiSnapshots"
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            content {
                includeModule("org.graphiks", "kffi-coretext")
                includeModule("org.graphiks", "kffi-coretext-jvm")
                includeModule("org.graphiks", "kffi-fontconfig")
                includeModule("org.graphiks", "kffi-fontconfig-jvm")
                includeModule("org.graphiks", "kffi-directwrite")
                includeModule("org.graphiks", "kffi-directwrite-jvm")
                includeModule("org.graphiks", "kffi-harfbuzz")
                includeModule("org.graphiks", "kffi-harfbuzz-jvm")
                includeModule("org.graphiks", "kffi-harfbuzz-android")
                includeModule("org.graphiks", "kffi-harfbuzz-android-native")
                includeModule("org.graphiks", "kffi-harfbuzz-iosarm64")
                includeModule("org.graphiks", "kffi-harfbuzz-iossimulatorarm64")
                includeModule("org.graphiks", "kffi-harfbuzz-js")
                includeModule("org.graphiks", "kffi-harfbuzz-wasm-js")
                includeModule("org.graphiks", "kffi-android")
                includeModule("org.graphiks", "kffi-android-native")
                includeModule("org.graphiks", "kffi-iosarm64")
                includeModule("org.graphiks", "kffi-iossimulatorarm64")
                includeModule("org.graphiks", "kffi")
                includeModule("org.graphiks", "kffi-jvm")
            }
        }
    }
}

rootProject.name = "kalligraphie-consumer-smoke"
