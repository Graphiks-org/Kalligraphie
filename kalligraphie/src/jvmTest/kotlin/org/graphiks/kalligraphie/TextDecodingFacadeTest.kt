package org.graphiks.kalligraphie

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.graphiks.kalligraphie.api.CancellationToken
import org.graphiks.kalligraphie.api.TextDecodingLimit
import org.graphiks.kalligraphie.api.TextDecodingOutcome
import org.graphiks.kalligraphie.api.TextDecodingProfile
import org.graphiks.kalligraphie.api.TextSlice
import org.graphiks.kalligraphie.api.TextVersion

class TextDecodingFacadeTest {
    @Test
    fun cancellation_after_valid_prefix_discards_the_incomplete_unicode_snapshot() {
        var checks = 0

        val result = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A\uD83D\uDE00B".encodeToByteArray())),
            TextDecodingProfile(cancellationCheckInterval = 1),
            CancellationToken { checks++ >= 2 },
        )

        assertIs<TextDecodingOutcome.Cancelled>(result)
    }

    @Test
    fun scalar_budget_rejects_a_complete_source_without_publishing_a_truncated_snapshot() {
        val result = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A\uD83D\uDE00".encodeToByteArray())),
            TextDecodingProfile(maxScalars = 1),
        )

        val failure = assertIs<TextDecodingOutcome.LimitExceeded>(result)
        assertEquals(TextDecodingLimit.SCALARS, failure.limit)
        assertEquals(2L, failure.observed)
    }

    @Test
    fun source_unit_budget_rejects_utf8_before_a_joined_decoder_buffer_is_published() {
        val result = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            listOf(TextSlice.Utf8("A\uD83D\uDE00".encodeToByteArray())),
            TextDecodingProfile(maxSourceUnits = 4),
        )

        val failure = assertIs<TextDecodingOutcome.LimitExceeded>(result)
        assertEquals(TextDecodingLimit.SOURCE_UNITS, failure.limit)
        assertEquals(5L, failure.observed)
    }

    @Test
    fun cancellation_callback_cannot_expand_the_captured_utf8_source_after_budget_validation() {
        val slices = mutableListOf(TextSlice.Utf8("A".encodeToByteArray()))
        var appended = false

        val result = Kalligraphie.decodeUtf8(
            TextVersion.create(),
            slices,
            TextDecodingProfile(maxSourceUnits = 1),
            CancellationToken {
                if (!appended) {
                    appended = true
                    slices += TextSlice.Utf8("B".encodeToByteArray())
                }
                false
            },
        )

        val success = assertIs<TextDecodingOutcome.Success>(result)
        assertEquals(listOf(0x41), success.value.snapshot.scalars)
    }

    @Test
    fun bounded_utf16_decoding_preserves_a_complete_unicode_snapshot() {
        val result = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            listOf(TextSlice.Utf16("A\uD83D\uDE00B".toCharArray())),
            TextDecodingProfile(maxSourceUnits = 4, maxScalars = 3),
        )

        val success = assertIs<TextDecodingOutcome.Success>(result)
        assertEquals(listOf(0x41, 0x1F600, 0x42), success.value.snapshot.scalars)
    }

    @Test
    fun empty_utf16_source_succeeds_with_zero_budgets() {
        val result = Kalligraphie.decodeUtf16(
            TextVersion.create(),
            emptyList(),
            TextDecodingProfile(maxSourceUnits = 0, maxScalars = 0),
        )

        val success = assertIs<TextDecodingOutcome.Success>(result)
        assertEquals(emptyList(), success.value.snapshot.scalars)
    }
}
