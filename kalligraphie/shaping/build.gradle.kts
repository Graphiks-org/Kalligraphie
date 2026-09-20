plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-library")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        jvmMain.dependencies {
            implementation(libs.kffi.harfbuzz.jvm)
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(kotlin("test"))
        }
        jvmTest {
            resources.srcDir(rootProject.file("test-fixtures"))
        }
    }
}

configurations.configureEach {
    resolutionStrategy.cacheChangingModulesFor(0, "seconds")
}
