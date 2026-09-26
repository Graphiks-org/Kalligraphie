package org.graphiks.kalligraphie.unicode

/**
 * The BCP 47 grammar one analysis needs, without a platform locale parser.
 *
 * [parseLanguageTag] validates a well-formed tag per RFC 5646 and normalizes the case of every
 * subtag — lowercase language, Titlecase script, uppercase region, lowercase variants, extensions
 * and private use — which is the canonical form the contract's language metadata carries. The
 * grandfathered tags RFC 5646 lists are accepted and converted to their regular replacements; the
 * three that have no well-formed replacement are rejected like any other malformed tag.
 *
 * [UnicodeLikelyScript.scriptOf] keys on the bare language subtag, so the parsed tag carries it
 * separately from the whole canonical tag, alongside the tag's own script: a tag that names a
 * script explicitly means that script, not the likely one of its language.
 */

/** One parsed analysis language: its canonical tag, its bare language subtag, and its own script. */
internal class ParsedLanguage(
    /** The BCP 47 tag with the canonical case of every subtag. */
    val canonicalTag: String,
    /** The primary language subtag, lowercase, as the likely-script source keys on it. */
    val primarySubtag: String,
    /** The ISO 15924 script the tag names itself, or null when it names none. */
    val explicitScript: String?,
)

/**
 * Validates [language] as a well-formed BCP 47 tag and returns its canonical form.
 *
 * @throws IllegalArgumentException when [language] is not a well-formed BCP 47 tag.
 */
internal fun parseLanguageTag(language: String): ParsedLanguage {
    val grandfathered = GRANDFATHERED_REPLACEMENTS[language.lowercase()]
    if (grandfathered != null) {
        val subtags = grandfathered.split('-')
        return ParsedLanguage(
            canonicalTag = grandfathered,
            primarySubtag = subtags.first(),
            explicitScript = subtags.getOrNull(1)?.takeIf(::isScriptSubtag),
        )
    }
    return ParsedLanguageParser(language).parse()
}

private class ParsedLanguageParser(private val language: String) {
    fun parse(): ParsedLanguage {
        if (language.isEmpty() || language.first() == '-' || language.last() == '-') {
            throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        }
        val parts = language.split('-')
        if (parts.any(String::isEmpty)) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        if (parts.first().equals("x", ignoreCase = true)) {
            val canonical = ArrayList<String>(parts.size)
            parsePrivateUse(parts, 0, canonical)
            // A private-use tag carries no language, and the canonical form spells the absent
            // language the way the JVM reference parser does: `und`.
            return ParsedLanguage(
                canonicalTag = "und-${canonical.joinToString("-")}",
                primarySubtag = "und",
                explicitScript = null,
            )
        }
        val canonical = ArrayList<String>(parts.size)
        var position = 0

        position += parseLanguage(parts, position, canonical)
        val explicitScript = parseScript(parts, position)?.also {
            canonical += it
            position += 1
        }
        position += parseRegion(parts, position, canonical)
        position += parseVariants(parts, position, canonical)
        position += parseExtensions(parts, position, canonical)
        position += parsePrivateUseTail(parts, position, canonical)
        if (position != parts.size) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)

        return ParsedLanguage(
            canonicalTag = canonical.joinToString("-"),
            primarySubtag = canonical.first(),
            explicitScript = explicitScript,
        )
    }

    private fun parseLanguage(parts: List<String>, position: Int, canonical: MutableList<String>): Int {
        val language = parts.getOrNull(position) ?: throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        if (!language.isAlpha() || language.length !in 2..8) {
            throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        }
        canonical += language.lowercase()
        var consumed = 1
        if (language.length in 2..3) {
            while (consumed <= 3 && position + consumed < parts.size &&
                parts[position + consumed].let(String::isAlpha) && parts[position + consumed].length == 3
            ) {
                canonical += parts[position + consumed].lowercase()
                consumed += 1
            }
        }
        return consumed
    }

    private fun parseScript(parts: List<String>, position: Int): String? {
        val script = parts.getOrNull(position) ?: return null
        if (!isScriptSubtag(script)) return null
        return script.lowercase().replaceFirstChar(Char::uppercaseChar)
    }

    private fun parseRegion(parts: List<String>, position: Int, canonical: MutableList<String>): Int {
        val region = parts.getOrNull(position) ?: return 0
        when {
            region.isAlpha() && region.length == 2 -> canonical += region.uppercase()
            region.isDigits() && region.length == 3 -> canonical += region
            else -> return 0
        }
        return 1
    }

    private fun parseVariants(parts: List<String>, position: Int, canonical: MutableList<String>): Int {
        val variants = LinkedHashSet<String>()
        var consumed = 0
        while (position + consumed < parts.size) {
            val variant = parts[position + consumed]
            if (!isVariantSubtag(variant)) break
            if (!variants.add(variant.lowercase())) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
            consumed += 1
        }
        // The canonical form sorts the variants among themselves, which is what the JVM
        // reference parser does with them.
        canonical += variants.sorted()
        return consumed
    }

    private fun parseExtensions(parts: List<String>, position: Int, canonical: MutableList<String>): Int {
        val singletons = HashSet<String>()
        var consumed = 0
        while (position + consumed < parts.size) {
            val singleton = parts[position + consumed]
            if (singleton.length != 1 || !singleton[0].isAsciiAlphanumeric() ||
                singleton[0].equals('x', ignoreCase = true)
            ) {
                break
            }
            val normalizedSingleton = singleton.lowercase()
            if (!singletons.add(normalizedSingleton)) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
            canonical += normalizedSingleton
            var elements = 0
            while (position + consumed + 1 + elements < parts.size) {
                val element = parts[position + consumed + 1 + elements]
                if (element.length !in 2..8 || !element.isAsciiAlphanumeric()) break
                canonical += element.lowercase()
                elements += 1
            }
            if (elements == 0) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
            consumed += 1 + elements
        }
        return consumed
    }

    private fun parsePrivateUseTail(parts: List<String>, position: Int, canonical: MutableList<String>): Int {
        if (position >= parts.size) return 0
        if (!parts[position].equals("x", ignoreCase = true)) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        return parsePrivateUse(parts, position, canonical)
    }

    private fun parsePrivateUse(parts: List<String>, from: Int, canonical: MutableList<String>): Int {
        if (from >= parts.size) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        if (!parts[from].equals("x", ignoreCase = true)) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        canonical += "x"
        var consumed = 1
        if (from + consumed >= parts.size) throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
        while (from + consumed < parts.size) {
            val element = parts[from + consumed]
            if (element.length !in 1..8 || !element.isAsciiAlphanumeric()) {
                throw IllegalArgumentException(INVALID_LANGUAGE_MESSAGE)
            }
            canonical += element.lowercase()
            consumed += 1
        }
        return consumed
    }
}

/** The script subtag position of a BCP 47 tag: exactly four letters. */
private fun isScriptSubtag(subtag: String): Boolean = subtag.isAlpha() && subtag.length == 4

/** A variant subtag: 5–8 alphanumeric characters, or a digit followed by exactly three. */
private fun isVariantSubtag(subtag: String): Boolean = when {
    subtag.isAsciiAlphanumeric() && subtag.length in 5..8 -> true
    subtag.length == 4 && subtag[0].isAsciiDigit() && subtag.substring(1).isAsciiAlphanumeric() -> true
    else -> false
}

private fun String.isAlpha(): Boolean = isNotEmpty() && all(Char::isAsciiLetter)
private fun String.isDigits(): Boolean = isNotEmpty() && all(Char::isAsciiDigit)
private fun String.isAsciiAlphanumeric(): Boolean = isNotEmpty() && all(Char::isAsciiAlphanumeric)
private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
private fun Char.isAsciiAlphanumeric(): Boolean = isAsciiLetter() || isAsciiDigit()

/**
 * The grandfathered tags RFC 5646 lists, keyed on their lowercase spelling, to the well-formed tag
 * they are replaced by. The three with no well-formed replacement (`i-default`, `i-enochian`,
 * `i-mingo`) are absent, which rejects them like any other malformed tag.
 */
private val GRANDFATHERED_REPLACEMENTS: Map<String, String> = mapOf(
    "en-gb-oed" to "en-GB-x-oed",
    "i-ami" to "ami",
    "i-default" to "en-x-i-default",
    "i-enochian" to "und-x-i-enochian",
    "i-mingo" to "see-x-i-mingo",
    "i-bnn" to "bnn",
    "i-hak" to "hak",
    "i-klingon" to "tlh",
    "i-lux" to "lb",
    "i-navajo" to "nv",
    "i-pwn" to "pwn",
    "i-tao" to "tao",
    "i-tay" to "tay",
    "i-tsu" to "tsu",
    "sgn-be-fr" to "sfb",
    "sgn-be-nl" to "vgt",
    "sgn-ch-de" to "sgg",
    "zh-guoyu" to "cmn",
    "zh-hakka" to "hak",
    "zh-min-nan" to "nan",
    "zh-xiang" to "hs",
    "art-lojban" to "jbo",
    "cel-gaulish" to "xtg-x-cel-gaulish",
    "no-bok" to "nb",
    "no-nyn" to "nn",
    "zh-min" to "nan",
)

private const val INVALID_LANGUAGE_MESSAGE: String = "Language must be a well-formed BCP 47 tag."
