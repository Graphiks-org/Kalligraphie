// BitmapCatalog.kt
package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.e2e.GoldenSceneFamily

/** Embedded-bitmap expectations: the migrated monochrome strike, then the auto-sized colour ones. */
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
        CatalogEntry(
            id = "bitmap.cbdt-png.u1f600.16",
            axis = CatalogAxis.BITMAP,
            technology = "CBLC/CBDT PNG colour strike",
            font = CorpusKeys.SKIA_CBDT,
            status = CatalogStatus.Supported(sinceCommit = "009d010e"),
            tags = setOf("bitmap:cbdt", "auto-sized"),
            tables = setOf("CBLC", "CBDT"),
            family = GoldenSceneFamily.GLYPH_BITMAP,
            frame = SceneFramePolicy.AutoSized(padding = 1),
        ),
        CatalogEntry(
            id = "bitmap.sbix-png.u1f600.16",
            axis = CatalogAxis.BITMAP,
            technology = "sbix PNG strike",
            font = CorpusKeys.SKIA_SBIX,
            status = CatalogStatus.Supported(sinceCommit = "009d010e"),
            tags = setOf("bitmap:sbix", "auto-sized"),
            tables = setOf("sbix"),
            family = GoldenSceneFamily.GLYPH_BITMAP,
            frame = SceneFramePolicy.AutoSized(padding = 1),
        ),
    )
}
