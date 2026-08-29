group = "org.graphiks"
version = (project.findProperty("releaseVersion") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0-SNAPSHOT"
