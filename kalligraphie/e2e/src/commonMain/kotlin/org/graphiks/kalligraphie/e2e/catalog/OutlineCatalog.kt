// OutlineCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Outline expectations carried by the migrated golden scenes. */
public object OutlineCatalog {
    /** Declared outline expectations. */
    public val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "outline.glyf-simple-composite",
            axis = CatalogAxis.OUTLINE,
            technology = "TrueType glyf simple and composite outlines",
            font = CorpusKeys.LIBERATION,
            status = CatalogStatus.Supported(sinceCommit = "35c4422c"),
            tags = setOf("smoke", "scripts:latin"),
            tables = setOf("cmap", "glyf", "head", "hhea", "hmtx", "loca", "maxp", "post", "OS/2"),
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            frame = SceneFramePolicy.Pinned(width = 43, height = 45),
        ),
    )
}
