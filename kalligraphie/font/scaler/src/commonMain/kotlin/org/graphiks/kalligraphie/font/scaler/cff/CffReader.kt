@file:OptIn(org.graphiks.kalligraphie.api.KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.font.scaler.cff

import kotlin.math.ceil
import kotlin.math.floor
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.DesignBounds
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.GlyphContour
import org.graphiks.kalligraphie.api.GlyphOutlineCommand
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.font.scaler.ScalerGlyphOutline

/**
 * Adapts a parsed CFF1 table and a Type 2 charstring into the scaler's
 * [ScalerGlyphOutline].
 *
 * The outline route is portable and shares the TrueType `glyf` contract. The
 * deprecated `seac` accent form is composed from its StandardEncoding base and
 * accent glyphs. CID-keyed faces are refused with a precise typed error rather
 * than decoded approximately. CFF2 has its own entry point.
 */
internal object CffReader {
    private val location: FontDiagnosticLocation = FontDiagnosticLocation.Table("CFF ")

    /**
     * Decodes glyph [glyphId] from [table] into a bounded design-unit outline.
     *
     * @param bytes the same byte array the [table] was read from.
     * @param unitsPerEm design units per em from the face `head` table.
     * @param profile contour/point budgets enforced while interpreting.
     */
    fun readGlyphOutline(
        bytes: ByteArray,
        table: CffTable,
        glyphId: Int,
        unitsPerEm: Int,
        profile: OutlineProfile,
        cancellationToken: CancellationToken = CancellationToken.none,
    ): FontOperationResult<ScalerGlyphOutline> {
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        if (glyphId < 0 || glyphId >= table.glyphCount) return FontOperationResult.Failure(FontError.GlyphOutOfRange(glyphId))

        val localSubrs: CffIndex?
        val nominalWidthX: Int
        val defaultWidthX: Int
        if (table.isCidKeyed) {
            val fdIndex = table.fdSelect?.getOrNull(glyphId) ?: 0
            val fontDict = table.fontDicts?.getOrNull(fdIndex)
                ?: return Failure("font.cff.cid-missing-font-dict", "CFF FDSelect references an undefined Font DICT.")
            localSubrs = fontDict.localSubrs
            nominalWidthX = fontDict.privateData?.integer(CffTable.NOMINAL_WIDTH_X_OP) ?: 0
            defaultWidthX = fontDict.privateData?.integer(CffTable.DEFAULT_WIDTH_X_OP) ?: 0
        } else {
            localSubrs = table.localSubrs
            nominalWidthX = table.nominalWidthX
            defaultWidthX = table.defaultWidthX
        }

        val decoded = when (
            val result = decodeGlyph(bytes, table, glyphId, localSubrs, nominalWidthX, defaultWidthX, profile)
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
        val seac = decoded.seac
        if (table.isCidKeyed && seac != null) {
            return Failure("font.cff.seac-in-cid-unsupported", "The CFF endchar accent form is not defined for CID-keyed faces.")
        }
        val outline = if (seac == null) {
            decoded
        } else {
            when (val result = composeSeac(bytes, table, seac, profile)) {
                is FontOperationResult.Success -> result.value
                is FontOperationResult.Failure -> return result
                is FontOperationResult.Cancelled -> return result
            }
        }
        if (outline.seac != null) {
            return FontOperationResult.Failure(
                FontError.FontDataFailure(
                    code = "font.cff.seac-nested-unsupported",
                    message = "Nested CFF endchar accent composition is not supported.",
                    location = location,
                ),
            )
        }
        return FontOperationResult.Success(
            ScalerGlyphOutline(
                glyphId = glyphId,
                unitsPerEm = unitsPerEm,
                bounds = outline.bounds,
                contours = outline.contours,
                pointCount = outline.pointCount,
                components = emptyList(),
            ),
        )
    }

    private fun decodeGlyph(
        bytes: ByteArray,
        table: CffTable,
        glyphId: Int,
        localSubrs: CffIndex?,
        nominalWidthX: Int,
        defaultWidthX: Int,
        profile: OutlineProfile,
    ): FontOperationResult<Type2Outline> {
        val charString = table.charStringsIndex.item(glyphId)
        return Type2CharstringInterpreter.interpret(
            charString = charString,
            globalSubrs = table.globalSubrIndex.toItems(),
            localSubrs = localSubrs.toItems(),
            nominalWidthX = nominalWidthX,
            defaultWidthX = defaultWidthX,
            maxPoints = profile.maxPoints,
            maxContours = profile.maxContours,
        )
    }

    private fun composeSeac(
        bytes: ByteArray,
        table: CffTable,
        seac: Type2Seac,
        profile: OutlineProfile,
    ): FontOperationResult<Type2Outline> {
        val baseGlyph = glyphForCode(table, seac.baseCharacter)
            ?: return Failure("font.cff.seac-unresolved", "CFF seac base glyph could not be resolved.")
        val accentGlyph = glyphForCode(table, seac.accentCharacter)
            ?: return Failure("font.cff.seac-unresolved", "CFF seac accent glyph could not be resolved.")
        val base = when (val result = decodeGlyph(bytes, table, baseGlyph, table.localSubrs, table.nominalWidthX, table.defaultWidthX, profile)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        val accent = when (val result = decodeGlyph(bytes, table, accentGlyph, table.localSubrs, table.nominalWidthX, table.defaultWidthX, profile)) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return result
            is FontOperationResult.Cancelled -> return result
        }
        if (base.seac != null || accent.seac != null) {
            return Failure("font.cff.seac-nested-unsupported", "Nested CFF endchar accent composition is not supported.")
        }
        val dx = seac.accentOffsetX.toDouble()
        val dy = seac.accentOffsetY.toDouble()
        val contours = base.contours + translate(accent.contours, dx, dy)
        return FontOperationResult.Success(
            Type2Outline(
                contours = contours,
                pointCount = base.pointCount + accent.pointCount,
                bounds = boundsOf(contours),
                width = base.width,
                seac = null,
            ),
        )
    }

    private fun glyphForCode(table: CffTable, code: Int): Int? =
        glyphForSid(table, CffStandardEncoding.sidForCode(code))

    private fun glyphForSid(table: CffTable, sid: Int): Int? {
        val candidate = when (val charset = table.charset) {
            is CffCharset.Explicit -> charset.sids.indexOf(sid).takeIf { it >= 0 }?.plus(1)
            is CffCharset.Predefined ->
                if (charset.offset == CffCharsetReader.ISO_ADOBE && sid in 1..228) sid else null
        } ?: return null
        return candidate.takeIf { it in 1 until table.glyphCount }
    }

    private fun translate(contours: List<GlyphContour>, dx: Double, dy: Double): List<GlyphContour> =
        contours.map { contour ->
            GlyphContour(
                contour.commands.map { command ->
                    when (command) {
                        is GlyphOutlineCommand.MoveTo -> GlyphOutlineCommand.MoveTo(command.x + dx, command.y + dy)
                        is GlyphOutlineCommand.LineTo -> GlyphOutlineCommand.LineTo(command.x + dx, command.y + dy)
                        is GlyphOutlineCommand.QuadraticTo -> GlyphOutlineCommand.QuadraticTo(
                            command.controlX + dx, command.controlY + dy, command.endX + dx, command.endY + dy,
                        )

                        is GlyphOutlineCommand.CubicTo -> GlyphOutlineCommand.CubicTo(
                            command.control1X + dx, command.control1Y + dy,
                            command.control2X + dx, command.control2Y + dy,
                            command.endX + dx, command.endY + dy,
                        )

                        GlyphOutlineCommand.Close -> GlyphOutlineCommand.Close
                    }
                },
            )
        }

    private fun boundsOf(contours: List<GlyphContour>): DesignBounds {
        var hasBounds = false
        var minX = 0.0
        var minY = 0.0
        var maxX = 0.0
        var maxY = 0.0
        fun track(px: Double, py: Double) {
            if (!hasBounds) {
                minX = px; minY = py; maxX = px; maxY = py; hasBounds = true
            } else {
                if (px < minX) minX = px
                if (py < minY) minY = py
                if (px > maxX) maxX = px
                if (py > maxY) maxY = py
            }
        }
        for (contour in contours) {
            for (command in contour.commands) {
                when (command) {
                    is GlyphOutlineCommand.MoveTo -> track(command.x, command.y)
                    is GlyphOutlineCommand.LineTo -> track(command.x, command.y)
                    is GlyphOutlineCommand.QuadraticTo -> {
                        track(command.controlX, command.controlY); track(command.endX, command.endY)
                    }

                    is GlyphOutlineCommand.CubicTo -> {
                        track(command.control1X, command.control1Y)
                        track(command.control2X, command.control2Y)
                        track(command.endX, command.endY)
                    }

                    GlyphOutlineCommand.Close -> Unit
                }
            }
        }
        return if (hasBounds) {
            DesignBounds(floor(minX).toInt(), floor(minY).toInt(), ceil(maxX).toInt(), ceil(maxY).toInt())
        } else {
            DesignBounds.empty
        }
    }

    private fun <T> Failure(code: String, message: String): FontOperationResult<T> =
        FontOperationResult.Failure(FontError.FontDataFailure(code, message, location))

    private fun CffIndex?.toItems(): List<ByteArray> =
        if (this == null) emptyList() else (0 until itemCount).map { item(it) }
}
