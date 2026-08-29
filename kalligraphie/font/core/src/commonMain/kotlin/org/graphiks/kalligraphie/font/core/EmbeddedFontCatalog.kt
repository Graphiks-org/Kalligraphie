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
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

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
    private val lifecycle = FontHandleLifecycle()

    val isClosed: Boolean
        get() = !lifecycle.isOpenForNewOperations()

    fun acquireLease(): FontHandleLease? = lifecycle.acquireLease()

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class FontHandleLifecycle {
    private val state = AtomicInt(0)

    fun isOpenForNewOperations(): Boolean = state.load() >= 0

    fun acquireLease(): FontHandleLease? {
        while (true) {
            val current = state.load()
            if (current < 0) {
                return null
            }
            if (state.compareAndSet(current, current + 1)) {
                return FontHandleLease(this)
            }
        }
    }

    fun close() {
        while (true) {
            val current = state.load()
            if (current < 0) {
                return
            }
            val next = Int.MIN_VALUE + current
            if (state.compareAndSet(current, next)) {
                return
            }
        }
    }

    internal fun releaseLease() {
        while (true) {
            val current = state.load()
            val next = when {
                current > 0 -> current - 1
                current > Int.MIN_VALUE -> current - 1
                else -> return
            }
            if (state.compareAndSet(current, next)) {
                return
            }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class FontHandleLease(
    private val lifecycle: FontHandleLifecycle,
) {
    private val released = AtomicInt(0)

    fun release() {
        if (released.compareAndSet(0, 1)) {
            lifecycle.releaseLease()
        }
    }
}
