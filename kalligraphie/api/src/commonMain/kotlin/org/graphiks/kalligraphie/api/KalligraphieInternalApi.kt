package org.graphiks.kalligraphie.api

/**
 * @suppress
 *
 * Marks implementation APIs used to assemble Kalligraphie's supported public facade.
 *
 * These declarations are not supported consumer contracts. Their visibility is retained only
 * where Kotlin module boundaries require it; ordinary applications must use the public font
 * catalog, face, instance, representation, and facade contracts instead.
 */
@RequiresOptIn(
    message = "This is a Kalligraphie implementation API. Use the public Kalligraphie facade and font contracts instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.TYPEALIAS,
)
public annotation class KalligraphieInternalApi
