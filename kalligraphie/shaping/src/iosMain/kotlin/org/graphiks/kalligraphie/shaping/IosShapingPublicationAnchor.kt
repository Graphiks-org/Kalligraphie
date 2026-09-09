package org.graphiks.kalligraphie.shaping

/**
 * Ensures the iOS publication produces its KLIB for Gradle module metadata.
 *
 * The shaping backend is JVM-specific, but this internal declaration keeps the
 * multiplatform dependency artifact available to iOS consumers.
 */
internal object IosShapingPublicationAnchor
