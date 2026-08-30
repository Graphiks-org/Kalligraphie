pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Kalligraphie"
include(":docs")
include(":kalligraphie")
include(":kalligraphie:api")
include(":kalligraphie:font:core")
include(":kalligraphie:font:sfnt")
include(":kalligraphie:font:scaler")
include(":kalligraphie:font:glyph")
