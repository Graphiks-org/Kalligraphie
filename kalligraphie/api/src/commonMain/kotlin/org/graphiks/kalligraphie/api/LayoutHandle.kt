package org.graphiks.kalligraphie.api

/**
 * Owns the font assets certified by a resource-free layout value.
 *
 * Retention and closure are thread-safe and linearizable. A retention admitted before closure
 * protects the roots until it finishes; later retentions return [FontError.ResourceClosed].
 * The caller closes each independently retained renderer handle.
 */
public interface LayoutHandle<out Layout> {
    /** Layout value, which remains readable after this handle closes. */
    public val layout: Layout

    /**
     * Returns an independent renderer owner for a complete certificate published by [layout].
     * An open handle rejects other certificates with [FontError.CertificateNotInLayout].
     * After closure, [FontError.ResourceClosed] takes precedence over certificate membership.
     */
    public fun retainFontAsset(
        certificate: GlyphMaterializationCertificate,
    ): FontOperationResult<FontRenderAssetHandle>

    /**
     * Idempotently rejects new retentions and releases the roots when admitted retentions finish.
     * Independently retained renderer handles remain valid. When cleanup is deferred, its
     * diagnostics are appended to the last admitted retention without invalidating its success.
     */
    public fun close(): FontOperationResult<Unit>
}
