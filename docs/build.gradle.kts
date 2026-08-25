import org.gradle.api.tasks.Sync

plugins {
    id("dev.opensavvy.dokka-mkdocs")
}

val fontModules = listOf(
    ":font",
    ":font:core",
    ":font:sfnt",
    ":font:colr",
    ":font:scaler",
    ":font:text",
    ":font:glyph",
).map { project(it) }

val copyFontDokkaIntoMkDocs = tasks.register<Sync>("copyFontDokkaIntoMkDocs") {
    dependsOn(fontModules.map { it.tasks.named("dokkaGenerateModuleMkdocs") })
    dependsOn(tasks.named("dokkaCopyIntoMkDocs"))

    fontModules.forEach { fontModule ->
        from(fontModule.layout.buildDirectory.dir("dokka-module/mkdocs/module")) {
            into("api/${fontModule.path.removePrefix(":").replace(':', '/')}")
        }
    }
}

tasks.named("generateMkDocsNavigation") {
    dependsOn(copyFontDokkaIntoMkDocs)
}
