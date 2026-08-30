import org.gradle.api.tasks.Sync

plugins {
    id("dev.opensavvy.dokka-mkdocs")
}

val kalligraphieModules = listOf(
    ":kalligraphie",
    ":kalligraphie:api",
    ":kalligraphie:unicode",
    ":kalligraphie:font:core",
    ":kalligraphie:font:sfnt",
    ":kalligraphie:font:scaler",
    ":kalligraphie:font:glyph",
).map { project(it) }

val copyKalligraphieDokkaIntoMkDocs = tasks.register<Sync>("copyKalligraphieDokkaIntoMkDocs") {
    dependsOn(kalligraphieModules.map { it.tasks.named("dokkaGenerateModuleMkdocs") })
    dependsOn(tasks.named("dokkaCopyIntoMkDocs"))

    into(layout.projectDirectory.dir("docs/api"))

    kalligraphieModules.forEach { kalligraphieModule ->
        from(kalligraphieModule.layout.buildDirectory.dir("dokka-module/mkdocs/module")) {
            into(kalligraphieModule.path.removePrefix(":").replace(':', '/'))
        }
    }
}

tasks.named("generateMkDocsNavigation") {
    dependsOn(copyKalligraphieDokkaIntoMkDocs)
}
