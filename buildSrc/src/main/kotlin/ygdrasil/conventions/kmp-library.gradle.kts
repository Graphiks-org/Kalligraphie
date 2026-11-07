@file:Suppress("UnstableApiUsage")
package ygdrasil.conventions

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(25)

    android {}

    jvm()

    iosArm64()
    iosSimulatorArm64()

    // Every library in this repository targets the web too, so the convention carries the two Node
    // runtimes instead of a parallel `*-web-library` family: a module whose own dependencies cannot
    // compile for the web is the only case that may declare its targets separately, as the
    // platform-specific adapters under `:kalligraphie:platform` do.
    js {
        nodejs()
    }
    wasmJs {
        nodejs()
    }
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    finalizeDsl(
        org.gradle.api.Action<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension> {
            // One namespace per module, mirroring its Kotlin packages exactly: the generated R
            // class lands in this package, and a namespace shared by several modules would
            // collide the day one of them declares an Android resource.
            namespace = "org.graphiks" + project.path.replace(':', '.')
            compileSdk = 36
            minSdk = 28
        }
    )
}
