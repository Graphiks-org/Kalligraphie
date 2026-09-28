plugins {
    id("ygdrasil.conventions.kalligraphie-kmp-web-library")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:unicode"))
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:shaping"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(kotlin("test"))
        }
        jvmTest {
            resources.srcDir(rootProject.file("test-fixtures"))
        }
        // Mirrors the web-capable half of `jvmTest.dependencies`. `:kalligraphie` and
        // `:kalligraphie:font:core` are deliberately absent: neither has web targets yet (Tasks 1.2
        // and 1.4), so a web test source set cannot reference them.
        webTest.dependencies {
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:shaping"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(kotlin("test"))
        }
    }
}
