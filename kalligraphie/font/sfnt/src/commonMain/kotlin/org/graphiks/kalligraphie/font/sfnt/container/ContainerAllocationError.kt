@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.graphiks.kalligraphie.font.sfnt.container

/**
 * Platform allocation exhaustion, caught only at the container decoders' defensive boundary.
 *
 * The JVM, Android and native actuals alias this to `OutOfMemoryError`, which their runtimes can
 * throw and the decoders can convert into a typed limit failure. Neither the JS engine nor the Wasm
 * runtime exposes a recoverable allocation error, so the web actual is a dedicated type the boundary
 * can only ever miss — the same honest mapping `:kalligraphie:font:core` uses for its cache.
 */
internal expect class ContainerAllocationError : Error
