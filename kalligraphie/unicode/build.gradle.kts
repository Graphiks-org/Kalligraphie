plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        jvmMain.dependencies {
            implementation(libs.icu4j)
        }
        commonTest.dependencies {
            implementation(project(":kalligraphie:api"))
            implementation(kotlin("test"))
        }
    }
}
