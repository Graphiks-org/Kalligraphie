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
            implementation(project(":kalligraphie:raster-cpu"))
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Temporary bridge until P2 consolidates the shared font set into test-fixtures/.
tasks.named<Copy>("jvmTestProcessResources") {
    from(project(":kalligraphie").layout.projectDirectory.dir("src/jvmTest/resources"))
}

val updateClass = "org.graphiks.kalligraphie.e2e.golden.GoldenUpdateRunnerTest"
val dumpClass = "org.graphiks.kalligraphie.e2e.golden.GoldenDumpRunnerTest"
val e2eJvmTestTask = tasks.named<Test>("jvmTest")

e2eJvmTestTask.configure {
    filter.excludeTestsMatching(updateClass)
    filter.excludeTestsMatching(dumpClass)
}

tasks.register<Test>("updateE2eGolden") {
    group = "verification"
    description = "Regenerates the committed golden fingerprint manifest from the scene catalog."
    testClassesDirs = e2eJvmTestTask.get().testClassesDirs
    classpath = e2eJvmTestTask.get().classpath
    filter.includeTestsMatching("$updateClass.writesTheManifestOnlyWhenExplicitlyEnabled")
    environment("KALLIGRAPHIE_E2E_UPDATE", "true")
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
