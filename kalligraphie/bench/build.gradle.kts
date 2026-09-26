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
        getByName("jvmBenchmark") {
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
            warmups = 3
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            reportFormat = "json"
        }
    }
}

// kotlinx-benchmark 0.5.0 runs its own runner (not jmh.Main) and exposes no profiler option — its
// accepted advanced options are nativeFork, nativeGCAfterIteration, jvmForks, jsUseBridge, wasmFork.
// Allocation therefore comes from the per-thread probe inside the benchmark method, and the
// observations path travels through the environment: JMH's forked JVMs inherit it, so each
// scenario's TearDown can append its counters to the same run file instead of them dying with the
// fork.
tasks.withType<JavaExec>().configureEach {
    if (name != "jvmBenchmarkBenchmark") return@configureEach
    environment(
        "KALLIGRAPHIE_BENCH_OBSERVATIONS",
        layout.buildDirectory.file("bench/observations.jsonl").get().asFile.absolutePath,
    )
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
