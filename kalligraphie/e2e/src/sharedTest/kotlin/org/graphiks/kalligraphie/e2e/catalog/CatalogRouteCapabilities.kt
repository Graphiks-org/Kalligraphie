package org.graphiks.kalligraphie.e2e.catalog

import org.graphiks.kalligraphie.conformance.PortableCapability

/**
 * The portable capabilities a catalogued scene's route needs.
 *
 * This is the bridge between the catalog and `:kalligraphie:conformance`: the catalogue names the
 * route a scene takes, the conformance module declares which capabilities a platform actually has,
 * and the harness compares them instead of keeping a third list of platforms here. The paragraph
 * route rests on the glyph-representation one, which is why declaring it names both.
 */
internal fun CatalogRoute.capabilities(): Set<PortableCapability> = when (this) {
    CatalogRoute.PORTABLE_GLYPH -> setOf(PortableCapability.GLYPH_REPRESENTATION_VARIANTS)
    CatalogRoute.PARAGRAPH_LAYOUT -> setOf(
        PortableCapability.GLYPH_REPRESENTATION_VARIANTS,
        PortableCapability.END_TO_END_LAYOUT,
    )
}

/**
 * Ids of the supported entries a platform declaring [capabilities] is expected to verify.
 *
 * An entry is implied when every capability its route needs is present. An entry the platform does
 * not imply is not skipped silently: it is the ones the platform's own declaration excuses, and the
 * ratchet requires the registry to match this set exactly in both directions.
 */
internal fun supportedEntriesFor(capabilities: Set<PortableCapability>): Set<String> =
    ExpectationCatalog.entries
        .filter { entry -> entry.status is CatalogStatus.Supported }
        .filter { entry -> entry.route?.capabilities()?.all { capability -> capability in capabilities } == true }
        .map { entry -> entry.id }
        .toSet()

/** Every portable capability the conformance module knows about. */
internal val everyPortableCapability: Set<PortableCapability> = PortableCapability.entries.toSet()

/**
 * Ids of the supported entries a platform declaring [capabilities] leaves to another platform.
 *
 * Derived, never listed: a scene is deferred exactly when the platform's declaration cannot serve
 * its route. The golden verifier receives this set so a deferred entry is not reported as a stale
 * manifest entry, and the capability ratchet proves the same set is the one the registry omits —
 * so a scene can be deferred *and* explained on every platform, never merely absent.
 */
internal fun deferredSceneIdsFor(capabilities: Set<PortableCapability>): Set<String> {
    val served = supportedEntriesFor(capabilities)
    return ExpectationCatalog.entries
        .filter { entry -> entry.status is CatalogStatus.Supported }
        .filter { entry -> entry.id !in served }
        .map { entry -> entry.sceneId ?: entry.id }
        .toSet()
}
