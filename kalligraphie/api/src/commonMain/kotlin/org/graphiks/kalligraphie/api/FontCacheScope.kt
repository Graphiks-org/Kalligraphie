@file:OptIn(KalligraphieInternalApi::class)

package org.graphiks.kalligraphie.api

/**
 * Cross-module ownership bridge assembled by the Kalligraphie facade.
 * Applications cannot supply a custom cache implementation through this protocol.
 * @suppress
 */
@KalligraphieInternalApi
public interface FontCacheScopeBackend {
    /** Disables retention and reports already known incomplete cleanup. */
    public fun close(): FontOperationResult<Unit>
}

/**
 * Caller-owned retention domain shared explicitly by independently captured font catalogs.
 *
 * Local policies still apply. Closing this domain disables caching, drains cache references,
 * and leaves catalogs, new acquisitions and independently owned render assets usable uncached.
 * Drainage may be proportional to retained entries: close outside the rendering critical path.
 * A close result reports known cleanup faults, including faults learned since an earlier close;
 * it does not certify completion of concurrent cleanup and never retries a partial release.
 */
public class FontCacheScope
/**
 * Assembles the concrete facade backend; applications create scopes through Kalligraphie.
 * @suppress
 */
@KalligraphieInternalApi constructor(
    /**
     * Cross-module assembly bridge, not a supported application extension point.
     * @suppress
     */
    @KalligraphieInternalApi public val backend: FontCacheScopeBackend,
) {
    /** Disables retention without closing consumer owners; repeated calls update known faults. */
    public fun close(): FontOperationResult<Unit> = backend.close()
}
