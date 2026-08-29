package org.graphiks.kalligraphie.api

public enum class FontDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR,
}

public sealed interface FontDiagnosticLocation {
    public data object Source : FontDiagnosticLocation

    public data class Table(public val tag: String) : FontDiagnosticLocation {
        init {
            require(tag.length == 4) { "Table tag must be exactly four characters." }
        }
    }

    public data class Face(public val faceIndex: Int) : FontDiagnosticLocation {
        init {
            require(faceIndex >= 0) { "faceIndex must be non-negative." }
        }
    }

    public data class Glyph(public val glyphId: Int) : FontDiagnosticLocation {
        init {
            require(glyphId >= 0) { "glyphId must be non-negative." }
        }
    }
}

public data class FontDiagnostic(
    public val code: String,
    public val severity: FontDiagnosticSeverity,
    public val location: FontDiagnosticLocation,
    public val message: String,
)

public sealed interface FontError {
    public val code: String
    public val message: String
    public val location: FontDiagnosticLocation

    public data class InvalidFontData(
        override val message: String,
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.invalid-font-data"
    }

    public data class UnsupportedContainer(
        override val message: String,
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.unsupported-container"
    }

    public data class MissingRequiredTable(
        public val tag: String,
        override val message: String = "Missing required table: $tag",
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Table(tag),
    ) : FontError {
        override val code: String = "font.missing-required-table"
    }

    public data class OutOfBounds(
        override val message: String,
        override val location: FontDiagnosticLocation,
    ) : FontError {
        override val code: String = "font.out-of-bounds"
    }

    public data class ResourceLimitExceeded(
        override val message: String,
        override val location: FontDiagnosticLocation,
    ) : FontError {
        override val code: String = "font.resource-limit-exceeded"
    }

    public data class ResourceClosed(
        override val message: String,
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.resource-closed"
    }

    public data class UnsupportedRepresentationProfile(
        override val message: String,
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.unsupported-representation-profile"
    }

    public data class GlyphOutOfRange(
        public val glyphId: Int,
        override val message: String = "Glyph $glyphId is out of range.",
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Glyph(glyphId),
    ) : FontError {
        override val code: String = "font.glyph-out-of-range"
    }

    public data class GeometryOverflow(
        override val message: String,
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.geometry-overflow"
    }

    public data class FontDataFailure(
        override val code: String,
        override val message: String,
        override val location: FontDiagnosticLocation,
    ) : FontError {
        init {
            require(code.startsWith("font.")) { "Font data failure code must start with font." }
            require(code.isNotBlank()) { "Font data failure code must not be blank." }
            require(message.isNotBlank()) { "Font data failure message must not be blank." }
        }
    }

    public data class Cancelled(
        override val message: String = "Operation cancelled.",
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.cancelled"
    }
}

public sealed interface FontOperationResult<out T> {
    public class Success<T>(
        public val value: T,
        diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<T> {
        public val diagnostics: List<FontDiagnostic> = diagnostics.canonicalDiagnostics()

        public constructor(value: T, diagnostics: Iterable<FontDiagnostic>) : this(value, diagnostics.toList())

        public operator fun component1(): T = value

        public operator fun component2(): List<FontDiagnostic> = diagnostics

        public fun copy(
            value: T = this.value,
            diagnostics: List<FontDiagnostic> = this.diagnostics,
        ): Success<T> = Success(value, diagnostics)

        override fun equals(other: Any?): Boolean =
            this === other || other is Success<*> && value == other.value && diagnostics == other.diagnostics

        override fun hashCode(): Int = 31 * (value?.hashCode() ?: 0) + diagnostics.hashCode()

        override fun toString(): String = "Success(value=$value, diagnostics=$diagnostics)"
    }

    public class Failure(
        public val error: FontError,
        diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<Nothing> {
        public val diagnostics: List<FontDiagnostic> = diagnostics.canonicalDiagnostics()

        public constructor(error: FontError, diagnostics: Iterable<FontDiagnostic>) : this(error, diagnostics.toList())

        public operator fun component1(): FontError = error

        public operator fun component2(): List<FontDiagnostic> = diagnostics

        public fun copy(
            error: FontError = this.error,
            diagnostics: List<FontDiagnostic> = this.diagnostics,
        ): Failure = Failure(error, diagnostics)

        override fun equals(other: Any?): Boolean =
            this === other || other is Failure && error == other.error && diagnostics == other.diagnostics

        override fun hashCode(): Int = 31 * error.hashCode() + diagnostics.hashCode()

        override fun toString(): String = "Failure(error=$error, diagnostics=$diagnostics)"
    }

    public class Cancelled(
        diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<Nothing> {
        public val diagnostics: List<FontDiagnostic> = diagnostics.canonicalDiagnostics()

        public constructor(diagnostics: Iterable<FontDiagnostic>) : this(diagnostics.toList())

        public operator fun component1(): List<FontDiagnostic> = diagnostics

        public fun copy(
            diagnostics: List<FontDiagnostic> = this.diagnostics,
        ): Cancelled = Cancelled(diagnostics)

        override fun equals(other: Any?): Boolean =
            this === other || other is Cancelled && diagnostics == other.diagnostics

        override fun hashCode(): Int = diagnostics.hashCode()

        override fun toString(): String = "Cancelled(diagnostics=$diagnostics)"
    }
}

public fun FontError.toDiagnostic(): FontDiagnostic =
    FontDiagnostic(
        code = code,
        severity = FontDiagnosticSeverity.ERROR,
        location = location,
        message = message,
    )

public fun Iterable<FontDiagnostic>.sortedDiagnostics(): List<FontDiagnostic> =
    toList().sortedWith(
        compareBy<FontDiagnostic>(
            { it.code },
            { it.severity.ordinal },
            { it.location.sortKey() },
            { it.message },
        ),
    )

private fun List<FontDiagnostic>.canonicalDiagnostics(): List<FontDiagnostic> = toList().sortedDiagnostics()

private fun FontDiagnosticLocation.sortKey(): String =
    when (this) {
        FontDiagnosticLocation.Source -> "source"
        is FontDiagnosticLocation.Table -> "table:$tag"
        is FontDiagnosticLocation.Face -> "face:$faceIndex"
        is FontDiagnosticLocation.Glyph -> "glyph:$glyphId"
    }
