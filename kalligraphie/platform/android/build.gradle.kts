import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("ygdrasil.conventions.kmp-publish")
    id("dev.opensavvy.dokka-mkdocs")
}

kotlin {
    jvmToolchain(25)

    android {
        withHostTest {}
    }

    sourceSets {
        val androidMain by getting {
            dependencies {
                api(project(":kalligraphie:api"))
                implementation(project(":kalligraphie:font:core"))
                implementation(project(":kalligraphie:font:sfnt"))
            }
        }
        val androidHostTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
            resources.srcDir(rootProject.file("test-fixtures"))
        }
    }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    finalizeDsl(
        org.gradle.api.Action<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension> {
            namespace = "io.ygdrasil.shared"
            compileSdk = 36
            minSdk = 28
        }
    )
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
dokka { dokkaSourceSets.configureEach { reportUndocumented.set(true) } }
mavenPublishing { coordinates(artifactId = "kalligraphie-platform-android") }
