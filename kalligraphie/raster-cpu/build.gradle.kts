import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension

plugins {
    id("ygdrasil.conventions.kalligraphie-internal-kmp-library")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

extensions.configure<KotlinMultiplatformAndroidComponentsExtension> {
    finalizeDsl(
        org.gradle.api.Action<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension> {
            // The Gradle path segment `raster-cpu` is not a Java identifier, so the path-derived
            // namespace of the kmp-library convention would publish an AAR that every consumer
            // build rejects; the module's Kotlin package is `org.graphiks.kalligraphie.raster`,
            // and the namespace mirrors it. This action runs after the convention's, so the
            // explicit value wins.
            namespace = "org.graphiks.kalligraphie.raster"
        }
    )
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
        // Mirrors the web-capable half of `jvmTest.dependencies`. `:kalligraphie` has no web target
        // yet (Task 1.4), so a web test source set cannot reference it.
        webTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val logoDumpClass = "org.graphiks.kalligraphie.raster.logo.KalligraphieLogoDumpTest"
val rasterJvmTestTask = tasks.named<Test>("jvmTest")

rasterJvmTestTask.configure {
    filter.excludeTestsMatching(logoDumpClass)
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/assets")).withPropertyName("logoAssets")
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
