package org.graphiks.kalligraphie.platform.ios

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreText.CTFontCollectionCreateFromAvailableFonts
import platform.CoreText.CTFontCollectionCreateMatchingFontDescriptors
import platform.CoreText.CTFontCopyTable
import platform.CoreText.CTFontCreateWithFontDescriptor
import platform.CoreText.CTFontRef
import platform.CoreText.kCTFontTableOptionNoOptions

/**
 * The OpenType table tags copied from a CoreText font. A tag the font does not
 * carry yields no table; the set covers the standard TrueType, CFF and OpenType
 * layout/colour/variation tables that form a portable container.
 */
private val CORE_TEXT_TABLE_TAGS = listOf(
    "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post",
    "glyf", "loca", "cvt ", "fpgm", "prep", "gasp", "kern", "hdmx", "LTSH", "VDMX", "PCLT",
    "CFF ", "CFF2", "VORG", "vhea", "vmtx", "DSIG",
    "GDEF", "GPOS", "GSUB", "BASE", "JSTF", "MATH",
    "fvar", "gvar", "avar", "cvar", "HVAR", "VVAR", "MVAR", "STAT",
    "COLR", "CPAL", "SVG ", "sbix", "CBDT", "CBLC", "EBDT", "EBLC",
    "morx", "mort", "feat", "prop", "meta", "ltag", "Zapf", "TSI0", "TSI1", "TSI2", "TSI3", "TSI5",
)

/**
 * Registry backed by CoreText.
 *
 * Enumerates the fonts CoreText makes available to the process and rebuilds each
 * one's tables into a standalone SFNT container. iOS does not hand out system
 * font file paths to an app, so the tables are the supported capture route.
 */
@OptIn(ExperimentalForeignApi::class)
public class CoreTextFontRegistry : IosFontRegistry {
    override fun availableFonts(): List<IosRegisteredFont> {
        val collection = CTFontCollectionCreateFromAvailableFonts(null) ?: return emptyList()
        return try {
            val descriptors = CTFontCollectionCreateMatchingFontDescriptors(collection) ?: return emptyList()
            try {
                val count = CFArrayGetCount(descriptors).toInt()
                val fonts = ArrayList<IosRegisteredFont>(count)
                for (index in 0 until count) {
                    val descriptor = CFArrayGetValueAtIndex(descriptors, index.toLong()) ?: continue
                    val font = CTFontCreateWithFontDescriptor(descriptor.reinterpret(), 12.0, null) ?: continue
                    try {
                        val tables = copyTables(font)
                        if (tables.isNotEmpty()) fonts += IosRegisteredFont(assembleSfnt(tables))
                    } finally {
                        CFRelease(font)
                    }
                }
                fonts
            } finally {
                CFRelease(descriptors)
            }
        } finally {
            CFRelease(collection)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun copyTables(font: CTFontRef?): List<SfntTable> {
    val tables = ArrayList<SfntTable>()
    for (tag in CORE_TEXT_TABLE_TAGS) {
        val data = CTFontCopyTable(font, tagToUInt(tag), kCTFontTableOptionNoOptions) ?: continue
        try {
            val length = CFDataGetLength(data).toInt()
            val pointer = CFDataGetBytePtr(data) ?: continue
            tables += SfntTable(tag, pointer.reinterpret<ByteVar>().readBytes(length))
        } finally {
            CFRelease(data)
        }
    }
    return tables
}
