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

    @Test
    fun requestRejectsChangedTextCheckpointWithoutATextDelta() {
        val source = decode("abc")
        val target = decode("abc")
        val typography = typography(TypographyVersion.create())
        val input = LayoutInput(target, typography)
        val previousState = state(source, typography.version)

        val result = request(input, previousState, delta = null)

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.VersionMismatch>(it.error)
        }
    }

    @Test
    fun requestRejectsChangedTypographyCheckpointWithoutATypographyDelta() {
        val text = decode("abc")
        val sourceTypography = TypographyVersion.create()
        val targetTypography = typography(TypographyVersion.create())
        val input = LayoutInput(text, targetTypography)
        val previousState = state(text, sourceTypography)

        val result = request(input, previousState, delta = null)

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.VersionMismatch>(it.error)
        }
    }

    @Test
    fun requestRejectsCoverageWhoseRangeUsesAnotherTextVersion() {
        val text = decode("abc")
        val foreign = decode("abc")
        val typography = typography(TypographyVersion.create())
        val input = LayoutInput(text, typography)
        val previousState = LayoutStateHandle(
            identity = "foreign-coverage",
            checkpoint = LayoutCheckpoint(text.version, typography.version),
            coverage = LayoutCoverage(text.version, foreign.range, isComplete = true),
        )

        val result = request(input, previousState, delta = null)

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.VersionMismatch>(it.error)
        }
    }

    @Test
    fun requestAcceptsSameVersionCheckpointWithoutADelta() {
        val text = decode("abc")
        val typography = typography(TypographyVersion.create())
        val input = LayoutInput(text, typography)

        val result = request(input, state(text, typography.version), delta = null)

        assertIs<LayoutContractResult.Success<IncrementalLayoutRequest>>(result)
    }

    @Test
    fun requestAcceptsDeltasThatProveBothVersionTransitions() {
        val source = decode("abc")
        val target = decode("adc")
        val sourceTypography = TypographyVersion.create()
        val targetTypography = typography(TypographyVersion.create())
        val textDelta = assertIs<LayoutContractResult.Success<TextChangeSet>>(
            TextChangeSet.create(
                source,
                target,
                listOf(TextChange(range(source, 1, 2), range(target, 1, 2))),
            ),
        ).value
        val typographyDelta = TypographyDelta(sourceTypography, targetTypography.version)
        val input = LayoutInput(target, targetTypography)

        val result = request(
            input,
            state(source, sourceTypography),
            LayoutDelta(textDelta, typographyDelta),
        )

        assertIs<LayoutContractResult.Success<IncrementalLayoutRequest>>(result)
    }

    @Test
    fun layoutCoverageFactoryRejectsARangeFromAnotherVersion() {
        val text = decode("abc")
        val foreign = decode("abc")

        val result = LayoutCoverage.create(text.version, foreign.range, isComplete = true)

        assertIs<LayoutContractResult.Failure>(result).also {
            assertIs<IncrementalLayoutError.VersionMismatch>(it.error)
        }
    }

    @Test
    fun layoutCoverageFactoryAcceptsARangeFromTheDeclaredVersion() {
        val text = decode("abc")

        val result = LayoutCoverage.create(text.version, text.range, isComplete = true)

        assertIs<LayoutContractResult.Success<LayoutCoverage>>(result)
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

    private fun typography(version: TypographyVersion): TypographySnapshot {
        val generation = FontCatalogGeneration("generation")
        val face = FontFaceId(
            source = FontSourceId.Opaque("tests", "generation", "face"),
            faceIndex = 0,
        )
        val faceRecord = FontFaceRecord(
            id = face,
            metadata = FontFaceMetadata("Test", "Regular", unitsPerEm = 1_000, glyphCount = 1),
            capabilities = FontFaceCapabilities(characterMapping = true, shaping = true, outline = true),
        )
        val catalog = object : FontCatalogSnapshot {
            override val generation: FontCatalogGeneration = generation
            override val faces: List<FontFaceRecord> = listOf(faceRecord)

            override fun openAssetResolver(): FontOperationResult<FontAssetResolverHandle> = error("Not used")

            override fun resolveFace(
                faceId: FontFaceId,
                requirements: FontAccessRequirementsSnapshot,
            ): FontOperationResult<FontFace> = error("Not used")
        }
        return TypographySnapshot(
            version = version,
            fontCatalog = catalog,
            resolutionPolicy = FontResolutionPolicySnapshot(
                generation = generation,
                policyId = "tests",
                version = "1",
                candidates = listOf(FontResolutionCandidate(face)),
                lastResortFace = face,
            ),
            fontInstanceDescriptor = FontInstanceDescriptor(),
        )
    }

    private fun state(text: TextSnapshot, typographyVersion: TypographyVersion): LayoutStateHandle =
        LayoutStateHandle(
            identity = "previous",
            checkpoint = LayoutCheckpoint(text.version, typographyVersion),
            coverage = assertIs<LayoutContractResult.Success<LayoutCoverage>>(
                LayoutCoverage.create(text.version, text.range, isComplete = true),
            ).value,
        )

    private fun request(
        input: LayoutInput,
        previousState: LayoutStateHandle,
        delta: LayoutDelta?,
    ): LayoutContractResult<IncrementalLayoutRequest> = createIncrementalLayoutRequest(
        input = input,
        requestedRange = input.text.range,
        constraints = HorizontalParagraphConstraints(
            region = LayoutRect(LayoutUnit(0f), LayoutUnit(0f), LayoutUnit(100f), LayoutUnit(100f)),
            lineMetrics = LineVerticalMetrics(LayoutUnit(10f), LayoutUnit(2f)),
        ),
        overscan = LineOverscan(1),
        previousState = previousState,
        delta = delta,
        cancellationToken = CancellationToken.none,
    )
}
