plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {}
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
