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
     * Results follow catalog order for catalogued scenes, then manifest order for
     * stale entries.
     *
     * [deferredSceneIds] names the manifest entries this caller deliberately does not render, so
     * they are not reported as stale. It exists because a platform verifies the scenes its
     * capabilities can serve and no others: the entries it leaves out are declared, never silently
     * dropped, and the caller is expected to derive that set rather than list it. An id that is
     * neither rendered nor deferred is still stale.
     *
     * @throws IllegalArgumentException when [scenes] contains a duplicate id, or when
     * a catalogued scene has no entry in [rendered]. A render refusal is reported as
     * [GoldenRenderOutcome.Refused] by the renderer before verification is attempted.
     */
    public fun verify(
        scenes: List<GoldenScene>,
        rendered: Map<String, GoldenImage>,
        manifest: GoldenManifest,
        deferredSceneIds: Set<String> = emptySet(),
    ): List<GoldenComparison> {
        val ids = scenes.map { scene -> scene.id }
        require(ids.distinct().size == ids.size) { "A golden scene catalog must not contain duplicate ids." }
        val cataloguedIds = ids.toSet()
        require(cataloguedIds.all { id -> rendered.containsKey(id) }) {
            "Every catalogued scene must have a rendered image; refuse renders are reported before verification."
        }

        val results = ArrayList<GoldenComparison>(scenes.size)
        for (scene in scenes) {
            val recorded = manifest.fingerprintOf(scene.id)
            if (recorded == null) {
                results.add(GoldenComparison.MissingInManifest(scene.id))
                continue
            }
            val image = rendered.getValue(scene.id)
            val actual = GoldenFingerprint.of(scene, image)
            val recordMatches = recorded.sha256 == actual.sha256 &&
                recorded.family == actual.family &&
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
            if (entry.sceneId !in cataloguedIds && entry.sceneId !in deferredSceneIds) {
                results.add(GoldenComparison.StaleManifestEntry(entry.sceneId))
            }
        }
        return results
    }
}
