plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
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
configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
mavenPublishing { coordinates(artifactId = "kalligraphie-platform-android") }
