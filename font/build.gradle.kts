plugins {
    id("ygdrasil.conventions.jvm-library")
}

dependencies {
    api(project(":font:core"))
    api(project(":font:sfnt"))
    api(project(":font:colr"))
    api(project(":font:scaler"))
    api(project(":font:text"))
    api(project(":font:glyph"))
    testImplementation(kotlin("test"))
}

tasks.register("fontTest") {
    group = "verification"
    dependsOn(
        ":font:core:test",
        ":font:sfnt:test",
        ":font:colr:test",
        ":font:scaler:test",
        ":font:text:test",
        ":font:glyph:test",
        ":font:test",
    )
}
