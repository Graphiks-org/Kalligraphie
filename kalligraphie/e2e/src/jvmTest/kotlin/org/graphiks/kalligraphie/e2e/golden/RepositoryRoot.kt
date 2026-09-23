package org.graphiks.kalligraphie.e2e.golden

import java.nio.file.Files
import java.nio.file.Path

/**
 * Locates the repository root by walking up from the test working directory.
 *
 * Only the tooling needs it: the golden verification reads its committed record through the fixture
 * corpus, while the writer that regenerates that record has to name the file it overwrites.
 */
internal fun repositoryRoot(): Path {
    var candidate: Path? = Path.of("").toAbsolutePath().normalize()
    while (candidate != null) {
        if (Files.exists(candidate.resolve(".git"))) return candidate
        candidate = candidate.parent
    }
    error("Could not locate the repository root from the test working directory.")
}
