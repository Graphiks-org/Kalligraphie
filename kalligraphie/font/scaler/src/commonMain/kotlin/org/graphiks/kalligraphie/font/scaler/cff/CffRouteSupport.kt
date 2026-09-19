@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.font.sfnt.FontFlavor
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.slice

/**
 * Whether a parsed face exposes the portable CFF1 outline route.
 *
 * True only when the face flavour is CFF and its `CFF ` table parses
 * structurally. The catalog uses this to advertise the `outline` capability
 * without reaching into the decoder's internal types.
 */
@org.graphiks.kalligraphie.api.KalligraphieInternalApi
public fun supportsCffOutlineRoute(sourceBytes: ByteArray, parsedFont: ParsedTrueTypeFont): Boolean = when (parsedFont.flavor) {
    FontFlavor.CFF -> {
        val record = parsedFont.tableRecords["CFF "] ?: return false
        val table = slice(sourceBytes, record) ?: return false
        CffTable.read(table, 0) is FontOperationResult.Success
    }

    FontFlavor.CFF2 -> {
        val record = parsedFont.tableRecords["CFF2"] ?: return false
        val table = slice(sourceBytes, record) ?: return false
        Cff2Table.read(table, 0) is FontOperationResult.Success
    }

    FontFlavor.TRUETYPE -> false
}
