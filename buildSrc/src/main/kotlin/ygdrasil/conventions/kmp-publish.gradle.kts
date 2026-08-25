package ygdrasil.conventions

plugins {
    id("com.vanniktech.maven.publish")
}

group = "io.ygdrasil.shared"
version = (project.findProperty("releaseVersion") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0-SNAPSHOT"

val isPublishing = project.findProperty("signingInMemoryKey")?.toString()?.isNotBlank() == true
    || project.findProperty("signing.keyId")?.toString()?.isNotBlank() == true

mavenPublishing {
    if (isPublishing) {
        publishToMavenCentral()
        signAllPublications()
    }
    coordinates(group.toString(), "shared", version.toString())

    pom {
        name.set("KMP Starter Pack Shared Library")
        description.set("Shared library logic for KMP Starter Pack")
        url.set("https://github.com/ygdrasil-io/project-template")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("ygdrasil-io")
                name.set("Ygdrasil team")
                email.set("contact@ygdrasil.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/ygdrasil-io/project-template.git")
            developerConnection.set("scm:git:ssh://github.com/ygdrasil-io/project-template.git")
            url.set("https://github.com/ygdrasil-io/project-template")
        }
    }
}
