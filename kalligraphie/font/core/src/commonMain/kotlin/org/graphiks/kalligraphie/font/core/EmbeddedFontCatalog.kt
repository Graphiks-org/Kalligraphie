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
import org.graphiks.kalligraphie.font.scaler.PreparedTrueTypeFont
import org.graphiks.kalligraphie.font.sfnt.ParsedTrueTypeFont
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Catalog implementation for an immutable embedded TrueType source.
 *
 * The catalog keeps the parsed snapshot and an explicit shared resource
 * owner. Faces and instances are immutable views of that owner; they do not
 * own an asset lease or expose its byte buffers. Resolver and render-asset
 * handles acquire independent leases, and a detached asset never stores a
 * reference to this catalog.
 *
 * @param source captured source bytes and provenance used to build the catalog.
 * @param parsedFont validated metadata describing [source].
 */
public class EmbeddedFontCatalog(
    source: FontSource,
    parsedFont: ParsedTrueTypeFont,
) : FontCatalogSnapshot {
    private val resource = PreparedFontResource(PreparedTrueTypeFont(source, parsedFont))
    private val catalogSourceId: FontSourceId = source.id

    private val face: TrueTypeFace by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TrueTypeFace(
            sourceId = catalogSourceId,
            parsedFont = parsedFont,
            resource = resource,
        )
    }

    /** Identifier of the embedded source. */
    override val sourceId: FontSourceId = catalogSourceId

    /** Opens a resolver backed by the embedded source. */
    override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> =
        FontOperationResult.Success(EmbeddedFontAssetResolver(catalogSourceId, resource))

    /** Resolves face zero when the requested access profile is supported. */
    override fun resolveFace(
        request: FontFaceRequest,
        requirements: FontAccessRequirementsSnapshot,
    ): FontOperationResult<FontFace> {
        if (request.faceIndex != 0) {
            return failure(
                FontError.InvalidFontData(
                    message = "Only face index 0 is supported by this embedded TrueType catalog.",
                    location = FontDiagnosticLocation.Face(request.faceIndex),
                ),
            )
        }
        if (!requirements.isSupportedForEmbeddedTrueType()) {
            return failure(
                FontError.UnsupportedRepresentationProfile(
                    message = "Unsupported font access requirements for this embedded TrueType catalog.",
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
        return FontOperationResult.Success(face)
    }

    private fun FontAccessRequirementsSnapshot.isSupportedForEmbeddedTrueType(): Boolean =
        when (mode) {
            FontAccessRequirementsSnapshot.Mode.LAYOUT_ONLY -> true
            FontAccessRequirementsSnapshot.Mode.RENDERABLE -> outlineProfile?.schemaVersion == 1
        }

    private fun failure(error: FontError, diagnostics: List<FontDiagnostic> = listOf(error.toDiagnostic())): FontOperationResult.Failure =
        FontOperationResult.Failure(error, diagnostics.sortedDiagnostics())
}

internal class EmbeddedFontAssetResolver(
    override val sourceId: FontSourceId,
    resource: PreparedFontResource,
) : FontAssetResolverHandle {
    private var resourceLease: PreparedFontResourceLease? = resource.acquireLease()
    private val lifecycle = FontHandleLifecycle(::releaseResourceLease)

    val isClosed: Boolean
        get() = !lifecycle.isOpenForNewOperations()

    fun acquireAssetLease(): PreparedFontResourceLease? {
        val operationLease = lifecycle.acquireLease() ?: return null
        return try {
            resourceLease?.resource?.acquireLease()
        } finally {
            operationLease.release()
        }
    }

    override fun close(): FontOperationResult<Unit> {
        lifecycle.close()
        return FontOperationResult.Success(Unit)
    }

    private fun releaseResourceLease() {
        resourceLease?.release()
        resourceLease = null
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class FontHandleLifecycle(
    private val onDrained: () -> Unit = {},
) {
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
                if (current == 0) onDrained()
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
                if (next == Int.MIN_VALUE) onDrained()
                return
            }
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class PreparedFontResource(
    internal val preparedFont: PreparedTrueTypeFont,
) {
    private val leaseCount = AtomicInt(0)

    internal fun acquireLease(): PreparedFontResourceLease {
        while (true) {
            val current = leaseCount.load()
            check(current < Int.MAX_VALUE) { "Prepared font resource lease count overflowed." }
            if (leaseCount.compareAndSet(current, current + 1)) {
                return PreparedFontResourceLease(this)
            }
        }
    }

    internal fun releaseLease() {
        while (true) {
            val current = leaseCount.load()
            check(current > 0) { "Prepared font resource lease released more than once." }
            if (leaseCount.compareAndSet(current, current - 1)) return
        }
    }
}

@OptIn(ExperimentalAtomicApi::class)
internal class PreparedFontResourceLease(
    internal val resource: PreparedFontResource,
) {
    private val released = AtomicInt(0)

    internal val preparedFont: PreparedTrueTypeFont
        get() = resource.preparedFont

    internal fun release() {
        if (released.compareAndSet(0, 1)) resource.releaseLease()
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
