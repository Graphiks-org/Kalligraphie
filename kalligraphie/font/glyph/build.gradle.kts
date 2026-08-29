plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:scaler"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
