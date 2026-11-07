group = "org.graphiks.kalligraphie"
version = (project.findProperty("releaseVersion") as? String)
    ?.takeIf { it.isNotBlank() }
    ?: "1.0.0-SNAPSHOT"
