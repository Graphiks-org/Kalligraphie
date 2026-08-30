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
