plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ygdrasil.conventions.kmp-publish")
}

/**
 * The browser font-discovery adapter.
 *
 * This module has web targets only: it is the counterpart of the JVM/native `platform:*` adapters,
 * which read the operating system's font catalog. It never enters `commonMain` of the portable
 * library, so no consumer pays for browser APIs unless it depends on this artifact.
 */
kotlin {
    applyDefaultHierarchyTemplate {
        common {
            group("web") {
                withJs()
                withWasmJs()
            }
        }
    }

    js {
        nodejs()
        // The Local Font Access adapter is the one part of the web surface that needs a DOM, so this
        // module also runs its suite in a real browser: Node proves the typed outcomes, Chrome proves
        // the adapter against an actual `window`.
        browser {
            testTask {
                useKarma {
                    useChromeHeadlessNoSandbox()
                }
            }
        }
    }
    wasmJs {
        nodejs()
        browser {
            testTask {
                useKarma {
                    useChromeHeadlessNoSandbox()
                }
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        val webMain by getting {
            dependencies {
                implementation(libs.kotlinx.browser)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val webTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

/**
 * The browser adapter publishes under the same `kalligraphie-platform-*` coordinate family as the
 * JVM and native adapters; without this the coordinate would have been the bare project name.
 */
mavenPublishing { coordinates(artifactId = "kalligraphie-platform-browser") }
