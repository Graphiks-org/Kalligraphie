plugins {
    id("ygdrasil.conventions.kalligraphie-internal-kmp-library")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Copy>("jvmTestProcessResources") {
    from(project(":kalligraphie").layout.projectDirectory.dir("src/jvmTest/resources"))
}
