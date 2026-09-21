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
        jvmTest {
            resources.srcDir(rootProject.file("test-fixtures"))
        }
    }
}

val rasterDumpClass = "org.graphiks.kalligraphie.raster.RasterDumpRunnerTest"
val logoDumpClass = "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoDumpTest"
val rasterJvmTestTask = tasks.named<Test>("jvmTest")

rasterJvmTestTask.configure {
    filter.excludeTestsMatching(rasterDumpClass)
    filter.excludeTestsMatching(logoDumpClass)
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/assets")).withPropertyName("logoAssets")
}

tasks.register<Test>("rasterDumps") {
    group = "verification"
    description = "Writes opt-in raster demonstration dumps outside the functional test suite."
    testClassesDirs = rasterJvmTestTask.get().testClassesDirs
    classpath = rasterJvmTestTask.get().classpath
    filter.includeTestsMatching("$rasterDumpClass.writesDeterministicDumpsOnlyWhenExplicitlyEnabled")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("renderLogo") {
    group = "verification"
    description = "Regenerates the README logo assets in docs/assets from the deterministic CPU rasterizer."
    testClassesDirs = rasterJvmTestTask.get().testClassesDirs
    classpath = rasterJvmTestTask.get().classpath
    filter.includeTestsMatching("$logoDumpClass.writesTheLogoAssetsOnlyWhenExplicitlyEnabled")
    environment("KALLIGRAPHIE_LOGO", "true")
    outputs.upToDateWhen { false }
}
