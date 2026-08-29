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

    public data class Cancelled(
        override val message: String = "Operation cancelled.",
        override val location: FontDiagnosticLocation = FontDiagnosticLocation.Source,
    ) : FontError {
        override val code: String = "font.cancelled"
    }
}

public sealed interface FontOperationResult<out T> {
    public data class Success<T>(
        val value: T,
        val diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<T> {
        public constructor(
            value: T,
            diagnostics: Iterable<FontDiagnostic>,
        ) : this(value, diagnostics.sortedDiagnostics())
    }

    public data class Failure(
        val error: FontError,
        val diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<Nothing> {
        public constructor(
            error: FontError,
            diagnostics: Iterable<FontDiagnostic>,
        ) : this(error, diagnostics.sortedDiagnostics())
    }

    public data class Cancelled(
        val diagnostics: List<FontDiagnostic> = emptyList(),
    ) : FontOperationResult<Nothing> {
        public constructor(diagnostics: Iterable<FontDiagnostic>) : this(diagnostics.sortedDiagnostics())
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

private fun FontDiagnosticLocation.sortKey(): String =
    when (this) {
        FontDiagnosticLocation.Source -> "source"
        is FontDiagnosticLocation.Table -> "table:$tag"
        is FontDiagnosticLocation.Face -> "face:$faceIndex"
        is FontDiagnosticLocation.Glyph -> "glyph:$glyphId"
    }
