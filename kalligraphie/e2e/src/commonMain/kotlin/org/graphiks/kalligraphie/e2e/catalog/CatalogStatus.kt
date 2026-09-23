// CatalogStatus.kt
package org.graphiks.kalligraphie.e2e.catalog

/** What Kalligraphie is expected to do with the technology of one catalog entry. */
public sealed interface CatalogStatus {
    /** Supported and verified by a golden scene. */
    public data class Supported(
        /** Short commit hash that introduced the support. */
        public val sinceCommit: String,
    ) : CatalogStatus {
        init {
            require(sinceCommit.isNotBlank()) { "A Supported entry must record the commit that introduced it." }
        }
    }

    /** Refused on purpose with exactly [code] at [stage]; the refusal must never disappear. */
    public data class ExpectedRejection(
        /** Stage the refusal is observed at. */
        public val stage: CatalogStage,
        /** Stable diagnostic identity, e.g. `font.variation.varc-unsupported`. */
        public val code: String,
    ) : CatalogStatus {
        init {
            require(code.isNotBlank()) { "An ExpectedRejection entry must record its diagnostic code." }
        }
    }

    /**
     * Not supported today. Exactly one of [currentBehavior] and [unpinnedReason] is set:
     * a probe pins today's behaviour when a font is available, otherwise the entry states why
     * nothing can be observed yet.
     */
    public data class NotYet(
        /** Tracking anchor: a GitHub issue (`#95`) or a spec section (`spec:§5 metrics`). */
        public val trackingIssue: String,
        /** Today's observable behaviour, asserted by a probe. */
        public val currentBehavior: PinnedBehavior?,
        /** Why no probe runs yet. */
        public val unpinnedReason: UnpinnedReason?,
    ) : CatalogStatus {
        init {
            require(trackingIssue.isNotBlank()) { "A NotYet entry must reference what tracks it." }
            require((currentBehavior == null) != (unpinnedReason == null)) {
                "A NotYet entry must pin a behaviour or state why it cannot, never both and never neither."
            }
        }
    }

    /** Deliberately outside the supported surface, with a recorded rationale. */
    public data class OutOfScope(
        /** Why this technology will not be supported, in both documentation languages. */
        public val rationale: CatalogText,
    ) : CatalogStatus {
        init {
        }
    }
}

/** The behaviour a probe asserts today for a [CatalogStatus.NotYet] entry. */
public sealed interface PinnedBehavior {
    /** The stage fails with exactly [diagnostic]. */
    public data class RejectedAt(
        /** Stage the failure is observed at. */
        public val stage: CatalogStage,
        /** Diagnostic identity observed today. */
        public val diagnostic: String,
    ) : PinnedBehavior

    /** The stage succeeds; [observation] names the degradation the probe asserts. */
    public data class SucceededWith(
        /** Stage the observation is made at. */
        public val stage: CatalogStage,
        /** Human-readable description of the degraded behaviour. */
        public val observation: String,
    ) : PinnedBehavior
}

/** Why a [CatalogStatus.NotYet] entry has no probe yet. */
public enum class UnpinnedReason {
    /** No real font carrying the technology is publicly known; coverage stays synthetic. */
    NO_REAL_FONT_KNOWN,
    /** A real font exists but is not in the corpus yet. */
    CORPUS_NOT_ACQUIRED,
    /**
     * A committed font carries the technology and the engine does not read it yet.
     *
     * Distinct from the two acquisition reasons: no download closes this gap, only code does, and
     * saying "corpus not acquired" about a table the corpus already carries would send a maintainer
     * looking for a font instead of at the reader.
     */
    READER_NOT_IMPLEMENTED,
}
