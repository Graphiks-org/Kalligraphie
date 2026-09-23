plugins {
    id("ygdrasil.conventions.kmp-library")
}

kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies {
            api(project(":kalligraphie:api"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project(":kalligraphie"))
            implementation(project(":kalligraphie:conformance"))
            implementation(project(":kalligraphie:raster-cpu"))
            implementation(project(":kalligraphie:layout"))
            implementation(project(":kalligraphie:shaping"))
            implementation(project(":kalligraphie:unicode"))
            implementation(project(":kalligraphie:font:core"))
            implementation(project(":kalligraphie:font:sfnt"))
            implementation(kotlin("test"))
        }
        jvmTest {
            resources.srcDir(rootProject.file("test-fixtures"))
            // The harness is shared, not duplicated: one copy of the portable scenes, of the
            // verification and of the ratchets compiles into every test target that can run it.
            kotlin.srcDir("src/sharedTest/kotlin")
            // The class-path fixture reader is the JVM family's implementation of the corpus seam.
            kotlin.srcDir("src/classpathTest/kotlin")
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val updateClass = "org.graphiks.kalligraphie.e2e.golden.GoldenUpdateRunnerTest"
val dumpClass = "org.graphiks.kalligraphie.e2e.golden.GoldenDumpRunnerTest"
val matrixClass = "org.graphiks.kalligraphie.e2e.catalog.CatalogMatrixRunnerTest"
val claimsClass = "org.graphiks.kalligraphie.e2e.catalog.CatalogClaimsRunnerTest"
val e2eJvmTestTask = tasks.named<Test>("jvmTest")

e2eJvmTestTask.configure {
    filter.excludeTestsMatching(updateClass)
    filter.excludeTestsMatching(dumpClass)
    // Only the writers are excluded: the freshness tests must keep running under `check`.
    filter.excludeTestsMatching("$matrixClass.writesTheMatrixOnlyWhenExplicitlyEnabled")
    filter.excludeTestsMatching("$claimsClass.writesTheClaimsOnlyWhenExplicitlyEnabled")
}

tasks.register<Test>("updateE2eGolden") {
    group = "verification"
    description = "Regenerates the committed golden fingerprint manifest, the catalog matrix and the table claims from the scene catalog."
    testClassesDirs = e2eJvmTestTask.get().testClassesDirs
    classpath = e2eJvmTestTask.get().classpath
    filter.includeTestsMatching("$updateClass.writesTheManifestOnlyWhenExplicitlyEnabled")
    filter.includeTestsMatching("$matrixClass.writesTheMatrixOnlyWhenExplicitlyEnabled")
    filter.includeTestsMatching("$claimsClass.writesTheClaimsOnlyWhenExplicitlyEnabled")
    environment("KALLIGRAPHIE_E2E_UPDATE", "true")
    environment("KALLIGRAPHIE_E2E_MATRIX", "true")
    environment("KALLIGRAPHIE_E2E_CLAIMS", "true")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("e2eGoldenDumps") {
    group = "verification"
    description = "Writes opt-in golden inspection dumps outside the repository."
    testClassesDirs = e2eJvmTestTask.get().testClassesDirs
    classpath = e2eJvmTestTask.get().classpath
    filter.includeTestsMatching("$dumpClass.writesDumpsOnlyWhenExplicitlyEnabled")
    environment("KALLIGRAPHIE_E2E_DUMPS", "true")
    outputs.upToDateWhen { false }
}
