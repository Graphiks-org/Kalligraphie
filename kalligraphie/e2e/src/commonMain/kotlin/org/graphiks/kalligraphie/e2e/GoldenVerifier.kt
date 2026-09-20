package org.graphiks.kalligraphie.e2e

/**
 * Pure comparison between a scene catalog, the rendered images, and the manifest.
 *
 * Fails closed: a scene without a manifest entry and a manifest entry without a
 * scene are both surfaced, never silently accepted.
 */
public object GoldenVerifier {
    /**
     * Compares [scenes] and their [rendered] images against [manifest].
     *
     * @throws IllegalArgumentException when [scenes] contains a duplicate id.
     */
    public fun verify(
        scenes: List<GoldenScene>,
        rendered: Map<String, GoldenImage>,
        manifest: GoldenManifest,
    ): List<GoldenComparison> {
        val ids = scenes.map { scene -> scene.id }
        require(ids.distinct().size == ids.size) { "A golden scene catalog must not contain duplicate ids." }
        val cataloguedIds = ids.toSet()

        val results = ArrayList<GoldenComparison>(scenes.size)
        for (scene in scenes) {
            val image = rendered[scene.id]
            if (image == null) {
                results.add(GoldenComparison.StaleManifestEntry(scene.id))
                continue
            }
            val recorded = manifest.fingerprintOf(scene.id)
            if (recorded == null) {
                results.add(GoldenComparison.MissingInManifest(scene.id))
                continue
            }
            val actual = GoldenFingerprint.of(scene, image)
            val recordMatches = recorded.sha256 == actual.sha256 &&
                recorded.width == actual.width &&
                recorded.height == actual.height &&
                recorded.format == actual.format
            val frameMatches = image.width == scene.width && image.height == scene.height
            results.add(
                if (recordMatches && frameMatches) {
                    GoldenComparison.Matched(scene.id)
                } else {
                    GoldenComparison.Mismatch(
                        sceneId = scene.id,
                        expectedSha256 = recorded.sha256,
                        actualSha256 = actual.sha256,
                        expectedWidth = scene.width,
                        expectedHeight = scene.height,
                        actualWidth = actual.width,
                        actualHeight = actual.height,
                    )
                },
            )
        }

        for (entry in manifest.entries) {
            if (entry.sceneId !in cataloguedIds) {
                results.add(GoldenComparison.StaleManifestEntry(entry.sceneId))
            }
        }
        return results
    }
}
