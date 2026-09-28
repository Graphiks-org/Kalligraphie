plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-web-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {}
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
