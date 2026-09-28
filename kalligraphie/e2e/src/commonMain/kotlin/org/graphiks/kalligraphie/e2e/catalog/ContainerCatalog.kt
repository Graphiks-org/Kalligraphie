// ContainerCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/**
 * Container expectations: a real IBMPlexSans face reached through its WOFF 1.0 and WOFF 2.0
 * wrappers, decoded by the portable facade before the outline route runs.
 */
public object ContainerCatalog {
    /** Declared container expectations. */
    public val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "container.woff2",
            axis = CatalogAxis.CONTAINER,
            technology = CatalogText("WOFF 2.0 container wrapping", "Encapsulation dans un conteneur WOFF 2.0"),
            font = CorpusKeys.WOFF_IBM_PLEX,
            status = CatalogStatus.Supported(sinceCommit = "02aaa1a7"),
            tags = setOf("container:woff2", "auto-sized"),
            tables = setOf("cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "post", "OS/2"),
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            sceneId = "glyph.outline.woff2-ibm-plex.A.64",
            frame = SceneFramePolicy.AutoSized(padding = 1),
            route = CatalogRoute.PORTABLE_GLYPH,
        ),
        CatalogEntry(
            id = "container.woff",
            axis = CatalogAxis.CONTAINER,
            technology = CatalogText("WOFF 1.0 container wrapping", "Encapsulation dans un conteneur WOFF 1.0"),
            font = CorpusKeys.WOFF_IBM_PLEX,
            status = CatalogStatus.Supported(sinceCommit = "02aaa1a7"),
            tags = setOf("container:woff", "auto-sized"),
            tables = setOf("cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "post", "OS/2"),
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            sceneId = "glyph.outline.woff-ibm-plex.A.64",
            frame = SceneFramePolicy.AutoSized(padding = 1),
            route = CatalogRoute.PORTABLE_GLYPH,
        ),
    )
}
