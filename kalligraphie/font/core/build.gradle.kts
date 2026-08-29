plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(project(":kalligraphie:font:scaler"))
            implementation(project(":kalligraphie:font:glyph"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
