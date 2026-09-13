package org.graphiks.kalligraphie

import org.graphiks.kalligraphie.api.FontCacheBudget
import org.graphiks.kalligraphie.api.FontMaterializationCachePolicy

internal fun materializationCachePolicies(): List<FontMaterializationCachePolicy> = listOf(
    FontMaterializationCachePolicy.disabled,
    FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1L),
    FontMaterializationCachePolicy(maxEvictableBytesPerFace = 1_000_000L),
    FontMaterializationCachePolicy(
        perFace = FontCacheBudget(1_000_000L, 0L, 0L, 0L),
        perCatalog = FontCacheBudget(1_000_000L, 0L, 0L, 0L),
    ),
    FontMaterializationCachePolicy(
        perFace = FontCacheBudget(1_000_000L, 169L, 0L, 0L),
        perCatalog = FontCacheBudget(1L, 169L, 0L, 0L),
    ),
    FontMaterializationCachePolicy(
        perFace = FontCacheBudget(1_000_000L, 169L, 0L, 0L),
        perCatalog = FontCacheBudget(1_000_000L, 168L, 0L, 0L),
    ),
    FontMaterializationCachePolicy(
        perFace = FontCacheBudget(1_000_000L, 168L, 0L, 0L),
        perCatalog = FontCacheBudget(2_000_000L, 169L, 0L, 0L),
    ),
    FontMaterializationCachePolicy(
        perFace = FontCacheBudget(1_000_000L, 169L, 0L, 0L),
        perCatalog = FontCacheBudget(1_000_000L, 169L, 0L, 0L),
    ),
    FontMaterializationCachePolicy(
        perFace = FontCacheBudget(1_000_000L, 169L, 0L, 0L),
        perCatalog = FontCacheBudget(8_192L, 169L, 0L, 0L),
    ),
    FontMaterializationCachePolicy(
        perFace = FontCacheBudget(8_192L, 169L, 0L, 0L),
        perCatalog = FontCacheBudget(1_000_000L, 1_000_000L, 0L, 0L),
    ),
)
