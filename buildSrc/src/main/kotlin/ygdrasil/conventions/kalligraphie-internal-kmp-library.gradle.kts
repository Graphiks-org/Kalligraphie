package ygdrasil.conventions

plugins {
    id("ygdrasil.conventions.kmp-library")
    id("ygdrasil.conventions.kmp-publish")
}

/**
 * Publishes implementation-only runtime dependencies without presenting them as consumer APIs.
 *
 * Kalligraphie's public facade has runtime dependencies on these modules, so they must remain
 * resolvable from a repository. They intentionally do not apply Dokka and are omitted from the
 * published API documentation.
 */
mavenPublishing {
    pom {
        name.set("${project.name} internal implementation")
        description.set("Internal runtime implementation of Kalligraphie; not a supported consumer artifact or extension API.")
    }
}
