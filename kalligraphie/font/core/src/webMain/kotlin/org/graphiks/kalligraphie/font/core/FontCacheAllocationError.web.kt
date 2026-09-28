@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.graphiks.kalligraphie.font.core

/**
 * Web actual for [FontCacheAllocationError].
 *
 * JVM, Android and native alias this to `OutOfMemoryError`, which their runtimes can throw and the
 * cache can catch to shed optional ownership. Neither the JS engine nor the Wasm runtime exposes a
 * recoverable allocation error: exhaustion surfaces as an uncatchable engine fault. The actual is
 * therefore a dedicated type the cache's recoverable path can only ever miss, which is the honest
 * mapping. It is deliberately **not** `kotlin.Error`.
 */
internal actual class FontCacheAllocationError : Error("Font cache allocation exhausted.")
