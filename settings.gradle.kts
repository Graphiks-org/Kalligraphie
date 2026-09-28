pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // KT-68533: the Kotlin Gradle plugin adds Node/Yarn/Binaryen ivy repos to project.repositories at
    // task-execution time, which FAIL_ON_PROJECT_REPOS rejects. PREFER_SETTINGS relaxes the repository
    // guard build-wide, not only for those tool repositories: it makes Gradle ignore every
    // project-level repository in favour of the settings-level equivalents declared below. The three
    // expected declarers are org.nodejs, com.yarnpkg, and com.github.webassembly. The exclusiveContent
    // blocks only pin which groups resolve from which repository; they do not scope the relaxed mode.
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

rootProject.name = "Kalligraphie"
include(":docs")
include(":kalligraphie")
include(":kalligraphie:api")
include(":kalligraphie:unicode")
include(":kalligraphie:shaping")
include(":kalligraphie:layout")
include(":kalligraphie:conformance")
include(":kalligraphie:bench")
include(":kalligraphie:e2e")
include(":kalligraphie:font:core")
include(":kalligraphie:font:sfnt")
include(":kalligraphie:font:scaler")
include(":kalligraphie:font:glyph")
include(":kalligraphie:platform:apple")
include(":kalligraphie:platform:linux")
include(":kalligraphie:platform:windows")
include(":kalligraphie:platform:android")
include(":kalligraphie:platform:ios")
include(":kalligraphie:platform:browser")
include(":kalligraphie:raster-cpu")
