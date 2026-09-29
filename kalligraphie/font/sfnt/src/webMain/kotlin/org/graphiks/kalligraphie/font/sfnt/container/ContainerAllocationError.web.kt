@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.graphiks.kalligraphie.font.sfnt.container

/**
 * Web actual for [ContainerAllocationError].
 *
 * Neither the JS engine nor the Wasm runtime exposes a recoverable allocation error: exhaustion
 * surfaces as an uncatchable engine fault. This is therefore a dedicated type the decoders'
 * defensive boundary can only ever miss, deliberately **not** an alias of `OutOfMemoryError`.
 */
internal actual class ContainerAllocationError : Error("The container decode exhausted available memory.")
