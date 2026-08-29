package org.graphiks.kalligraphie.font.core

import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontAssetResolverHandle
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFace
import org.graphiks.kalligraphie.api.FontFaceRequest
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontSource
import org.graphiks.kalligraphie.api.FontSourceId
import org.graphiks.kalligraphie.api.sortedDiagnostics
import org.graphiks.kalligraphie.api.toDiagnostic
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont

public class EmbeddedFontCatalog(
    private val source: FontSource,
    private val parsedFont: ParsedTrueTypeFont,
) : FontCatalogSnapshot {
    override val sourceId: FontSourceId = source.id

    override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> =
        FontOperationResult.Success(EmbeddedFontAssetResolver(source.id))

    override fun resolveFace(
        request: FontFaceRequest,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontFace> {
        if (request.faceIndex != 0) {
            return failure(
                FontError.InvalidFontData(
                    message = "Only face index 0 is supported for J1.",
                    location = FontDiagnosticLocation.Face(request.faceIndex),
                ),
            )
        }
        if (!requirements.isSupportedForJ1()) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    message = "Unsupported font access requirements for J1.",
                    location = FontDiagnosticLocation.Face(request.faceIndex),
                ),
                diagnostics = listOf(
                    FontDiagnostic(
                        code = "font.unsupported-representation-profile",
                        severity = FontDiagnosticSeverity.ERROR,
                        location = FontDiagnosticLocation.Face(request.faceIndex),
                        message = "Only LAYOUT_ONLY and schemaVersion=1 renderable outline profiles are supported.",
                    ),
                ),
            )
        }
        return FontOperationResult.Success(TrueTypeFace(source.id, source.copyBytes(), parsedFont))
    }

    private fun FontAccessRequirementsSnapshot.isSupportedForJ1(): Boolean =
        when (mode) {
            FontAccessRequirementsSnapshot.Mode.LAYOUT_ONLY -> true
            FontAccessRequirementsSnapshot.Mode.RENDERABLE -> outlineProfile?.schemaVersion == 1
        }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
}

internal class EmbeddedFontAssetResolver(
    override val sourceId: FontSourceId,
) : FontAssetResolverHandle {
    private var closed: Boolean = false
    val isClosed: Boolean
        get() = closed

    override fun close(): FontOperationResult<Unit> {
        closed = true
        return FontOperationResult.Success(Unit)
    }
}
