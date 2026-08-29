plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

group = "org.graphiks.kalligraphie"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
