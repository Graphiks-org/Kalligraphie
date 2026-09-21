plugins {
    id("ygdrasil.conventions.kalligraphie-internal-kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:font:sfnt"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest {
            resources.srcDir(rootProject.file("test-fixtures"))
        }
    }
}
