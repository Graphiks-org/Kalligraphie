plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

group = "org.graphiks.kalligraphie"

kotlin {
    sourceSets {
        commonMain.dependencies {}
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
