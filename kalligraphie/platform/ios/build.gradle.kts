plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ygdrasil.conventions.kmp-publish")
    id("dev.opensavvy.dokka-mkdocs")
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    explicitApi()
    sourceSets {
        iosMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
        }
        iosSimulatorArm64Test.dependencies {
            implementation(kotlin("test"))
        }
    }
}
configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
dokka { dokkaSourceSets.configureEach { reportUndocumented.set(true) } }
mavenPublishing { coordinates(artifactId = "kalligraphie-platform-ios") }
