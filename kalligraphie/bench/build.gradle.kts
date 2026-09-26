plugins {
    id("ygdrasil.conventions.kmp-library")
    alias(libs.plugins.kotlinx.benchmark)
}

kotlin {
    explicitApi()

    jvm {
        val main = compilations.getByName("main")
        compilations.create("benchmark") { associateWith(main) }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.benchmark.runtime)
            implementation(project(":kalligraphie"))
            implementation(project(":kalligraphie:api"))
            implementation(project(":kalligraphie:conformance"))
            implementation(project(":kalligraphie:shaping"))
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:layout"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
        }
        val jvmBenchmark by getting {
            dependencies {
                implementation(project(":kalligraphie"))
                implementation(libs.okio)
            }
            resources.srcDir(rootProject.file("test-fixtures"))
            kotlin.srcDir("src/classpathTest/kotlin")
        }
    }
}

benchmark {
    targets {
        register("jvmBenchmark")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 10
            reportFormat = "json"
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
