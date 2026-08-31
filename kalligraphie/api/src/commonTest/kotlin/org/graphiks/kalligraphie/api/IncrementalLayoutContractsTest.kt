package org.graphiks.kalligraphie.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class IncrementalLayoutContractsTest {
    @Test
    fun textChangeSetNormalizesAdjacentSourceEditsAndCountsDecodedTargetScalars() {
        val source = decode("abX")
        val target = decode("a😀e")

        val result = TextChangeSet.create(
            source,
            target,
            listOf(
                TextChange(range(source, 1, 2), range(target, 1, 2)),
                TextChange(range(source, 2, 3), range(target, 2, 3)),
            ),
        )

        val changeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(result).value
        assertEquals(1, changeSet.changes.size)
        assertEquals(range(source, 1, 3), changeSet.changes.single().sourceRange)
        assertEquals(range(target, 1, 3), changeSet.changes.single().insertedTargetRange)
        assertEquals(2, changeSet.changes.single().insertedScalarCount)
    }

    @Test
    fun versionOrCrossSpaceDeltaIsRejectedAsATypedError() {
        val source = decode("abc")
        val target = decode("adc")
        val other = decode("adc")

        val result = TextChangeSet.create(
            source,
            target,
            listOf(TextChange(range(source, 1, 2), range(other, 1, 2))),
        )

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.VersionMismatch>(it.error)
        }
    }

    @Test
    fun targetContiguousInsertsAtOneSourceBoundaryBecomeOneInsert() {
        val source = decode("ab")
        val target = decode("aXYb")

        val result = TextChangeSet.create(
            source,
            target,
            listOf(
                TextChange(range(source, 1, 1), range(target, 1, 2)),
                TextChange(range(source, 1, 1), range(target, 2, 3)),
            ),
        )

        val change = assertIs<LayoutContractResult.Success<TextChangeSet>>(result).value.changes.single()
        assertEquals(range(source, 1, 1), change.sourceRange)
        assertEquals(range(target, 1, 3), change.insertedTargetRange)
        assertEquals(2, change.insertedScalarCount)
    }

    @Test
    fun overlappingSourceRangesFailWithATypedError() {
        val source = decode("abcd")
        val target = decode("aXd")

        val result = TextChangeSet.create(
            source,
            target,
            listOf(
                TextChange(range(source, 1, 3), range(target, 1, 2)),
                TextChange(range(source, 2, 3), range(target, 2, 2)),
            ),
        )

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.OverlappingRanges>(it.error)
        }
    }

    @Test
    fun targetRangesMustAccountForUnchangedTextBetweenSourceEdits() {
        val source = decode("abc")
        val target = decode("axc")

        val result = TextChangeSet.create(
            source,
            target,
            listOf(TextChange(range(source, 1, 2), range(target, 2, 3))),
        )

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.InvalidTextChange>(it.error)
        }
    }

    @Test
    fun targetAccountingRejectsAnUnmarkedScalarChange() {
        val source = decode("abc")
        val target = decode("xYc")

        val result = TextChangeSet.create(
            source,
            target,
            listOf(TextChange(range(source, 1, 2), range(target, 1, 2))),
        )

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.InvalidTextChange>(it.error)
        }
    }

    @Test
    fun provenRangeChangeIsDerivedFromTheAuthoritativeTextChangeSet() {
        val source = decode("abc")
        val target = decode("aXYc")
        val changeSet = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source,
                target,
                listOf(TextChange(range(source, 1, 2), range(target, 1, 3))),
            ),
        ).value

        val rangeChange = RangeChange.from(changeSet)

        assertEquals(listOf(range(source, 1, 2)), rangeChange.sourceRanges)
        assertEquals(listOf(range(target, 1, 3)), rangeChange.targetRanges)
    }

    @Test
    fun fontPolicyDeltaWithoutProvenRangesRequiresFullInvalidation() {
        val sourcePolicy = policy(version = "1")
        val targetPolicy = policy(version = "2")

        val delta = FontResolutionPolicyDelta(sourcePolicy, targetPolicy)

        assertSame(RangeChange.FullInvalidation, delta.rangeChange)
    }

    private fun decode(value: String): TextSnapshot {
        val version = TextVersion.create()
        val scalars = mutableListOf<Int>()
        val sourceRanges = mutableListOf<SourceRange>()
        var offset = 0
        while (offset < value.length) {
            val first = value[offset]
            val scalar: Int
            val width: Int
            if (first.isHighSurrogate() && offset + 1 < value.length && value[offset + 1].isLowSurrogate()) {
                scalar = 0x10000 + ((first.code - 0xD800) shl 10) + (value[offset + 1].code - 0xDC00)
                width = 2
            } else {
                scalar = first.code
                width = 1
            }
            scalars += scalar
            sourceRanges += SourceRange(
                SourceOffset(version, SourceEncoding.UTF16, offset),
                SourceOffset(version, SourceEncoding.UTF16, offset + width),
            )
            offset += width
        }
        return TextSnapshot(version, SourceEncoding.UTF16, scalars, sourceRanges)
    }

    private fun range(snapshot: TextSnapshot, start: Int, endExclusive: Int): TextRange =
        TextRange(
            snapshot.textIndexAtScalarBoundary(start),
            snapshot.textIndexAtScalarBoundary(endExclusive),
        )

    private fun policy(version: String): FontResolutionPolicySnapshot {
        val face = FontFaceId(
            source = FontSourceId.Opaque("tests", "generation", "face"),
            faceIndex = 0,
        )
        return FontResolutionPolicySnapshot(
            generation = FontCatalogGeneration("generation"),
            policyId = "tests",
            version = version,
            candidates = listOf(FontResolutionCandidate(face)),
            lastResortFace = face,
        )
    }
}
