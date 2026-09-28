package org.graphiks.kalligraphie.shaping

/** The bundled HarfBuzz library loads synchronously on this target. */
public actual suspend fun initializeShapingRuntime() = Unit
