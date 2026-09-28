plugins {
    id("ygdrasil.conventions.kalligraphie-internal-kmp-web-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        webTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
