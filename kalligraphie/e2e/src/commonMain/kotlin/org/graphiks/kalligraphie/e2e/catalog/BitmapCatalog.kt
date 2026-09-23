// BitmapCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Embedded-bitmap expectations carried by the migrated golden scenes. */
public object BitmapCatalog {
    /** Declared bitmap expectations. */
    public val entries: List<CatalogEntry> = listOf(
        CatalogEntry(
            id = "bitmap.ebdt-format1",
            axis = CatalogAxis.BITMAP,
            technology = "EBLC/EBDT embedded bitmap strikes, format 1",
            font = CorpusKeys.SKIA_EBDT_FORMAT1,
            status = CatalogStatus.Supported(sinceCommit = "fa405247"),
            tags = setOf("scripts:emoji"),
            tables = setOf("EBLC", "EBDT"),
            family = GoldenSceneFamily.GLYPH_BITMAP,
            frame = SceneFramePolicy.Pinned(width = 13, height = 13),
        ),
    )
}
