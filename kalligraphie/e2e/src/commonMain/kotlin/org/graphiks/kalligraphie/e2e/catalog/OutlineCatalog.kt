// OutlineCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Outline expectations: the migrated TrueType scene, then the auto-sized CFF 1 and CFF 2 ones. */
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
        CatalogEntry(
            id = "outline.cff1-static",
            axis = CatalogAxis.OUTLINE,
            technology = "Static CFF 1 Type 2 charstrings",
            font = CorpusKeys.CFF_LIBERATION,
            status = CatalogStatus.Supported(sinceCommit = "009d010e"),
            tags = setOf("outline:cff1", "auto-sized"),
            tables = setOf("CFF ", "cmap"),
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            frame = SceneFramePolicy.AutoSized(padding = 1),
        ),
        CatalogEntry(
            id = "outline.cff2-static",
            axis = CatalogAxis.OUTLINE,
            technology = "Static CFF 2 charstrings without variation deltas",
            font = CorpusKeys.CFF2_LIBERATION,
            status = CatalogStatus.Supported(sinceCommit = "009d010e"),
            tags = setOf("outline:cff2", "auto-sized"),
            tables = setOf("CFF2", "cmap"),
            family = GoldenSceneFamily.GLYPH_OUTLINE,
            frame = SceneFramePolicy.AutoSized(padding = 1),
        ),
    )
}
