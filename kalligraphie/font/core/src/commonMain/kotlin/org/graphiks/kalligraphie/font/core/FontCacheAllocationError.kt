@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.graphiks.kalligraphie.font.core

/** Platform allocation exhaustion, caught only where optional cache ownership is recoverable. */
internal expect class FontCacheAllocationError : Error
