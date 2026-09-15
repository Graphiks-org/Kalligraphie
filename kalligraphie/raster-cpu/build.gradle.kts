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

val rasterDumpClass = "org.graphiks.kalligraphie.raster.RasterDumpRunnerTest"
val rasterJvmTestTask = tasks.named<Test>("jvmTest")

rasterJvmTestTask.configure {
    filter.excludeTestsMatching(rasterDumpClass)
}

tasks.register<Test>("rasterDumps") {
    group = "verification"
    description = "Writes opt-in raster demonstration dumps outside the functional test suite."
    testClassesDirs = rasterJvmTestTask.get().testClassesDirs
    classpath = rasterJvmTestTask.get().classpath
    filter.includeTestsMatching("$rasterDumpClass.writesDeterministicDumpsOnlyWhenExplicitlyEnabled")
    outputs.upToDateWhen { false }
}
