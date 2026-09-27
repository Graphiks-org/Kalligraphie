package org.graphiks.kalligraphie.unicode

import org.graphiks.kalligraphie.api.HyphenationMinimums
import org.graphiks.kalligraphie.api.HyphenationService
import org.graphiks.kalligraphie.api.HyphenationServiceIdentity

/**
 * Portable provider of the pinned American-English hyphenation pattern data.
 *
 * The pattern set is the same `hyph-utf8` American-English set the JVM provider serves from its
 * resource; here it arrives from [EmbeddedHyphenationPatterns], the generated Kotlin constant,
 * so the Liang service builds identically on every platform. The pattern text was verified
 * against its pinned SHA-256 when the source was generated, which is the equivalent of the JVM
 * provider's runtime digest verification.
 */
public object PortablePatternHyphenationService {
    /** Versioned identity of the pinned American-English pattern set. */
    public val englishIdentity: HyphenationServiceIdentity = HyphenationServiceIdentity(
        providerId = "hyph-utf8-patterns",
        dataRevision = "hyph-en-us@2005-05-30",
        languages = listOf("en", "en-US"),
    )

    /** Creates the pinned American-English [HyphenationService] from the embedded pattern set. */
    public fun english(): HyphenationService = PatternHyphenationService(
        patterns = EmbeddedHyphenationPatterns.englishPatterns.lineSequence().toList(),
        identity = englishIdentity,
        minimums = HyphenationMinimums(2, 3),
    )
}
