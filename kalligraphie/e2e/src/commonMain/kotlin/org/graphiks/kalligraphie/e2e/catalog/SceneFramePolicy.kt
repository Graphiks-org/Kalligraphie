// SceneFramePolicy.kt
package org.graphiks.kalligraphie.e2e.catalog

/** How a supported entry's golden frame is decided. */
public sealed interface SceneFramePolicy {
    /** The ink box measured at render time, plus [padding] on every side. */
    public data class AutoSized(
        /** Margin kept around the measured ink box, in pixels. */
        public val padding: Int = 1,
    ) : SceneFramePolicy {
        init {
            require(padding >= 0) { "A padding must not be negative." }
        }
    }

    /** A hand-pinned frame, preserved verbatim from the pre-catalog registry. */
    public data class Pinned(
        /** Frame width in pixels. */
        public val width: Int,
        /** Frame height in pixels. */
        public val height: Int,
    ) : SceneFramePolicy {
        init {
            require(width > 0 && height > 0) { "A pinned frame must be positive." }
        }
    }
}
