package org.graphiks.kalligraphie.layout

import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.FallbackUnit
import org.graphiks.kalligraphie.api.FontAccessRequirementsSnapshot
import org.graphiks.kalligraphie.api.FontCatalogSnapshot
import org.graphiks.kalligraphie.api.FontDiagnostic
import org.graphiks.kalligraphie.api.FontDiagnosticLocation
import org.graphiks.kalligraphie.api.FontDiagnosticSeverity
import org.graphiks.kalligraphie.api.FontError
import org.graphiks.kalligraphie.api.FontFaceCapabilities
import org.graphiks.kalligraphie.api.FontFaceId
import org.graphiks.kalligraphie.api.FontFaceRecord
import org.graphiks.kalligraphie.api.FontFallbackResolution
import org.graphiks.kalligraphie.api.FontGlyphRequest
import org.graphiks.kalligraphie.api.FontInstance
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontResolutionPolicySnapshot
import org.graphiks.kalligraphie.api.GlyphRepresentation
import org.graphiks.kalligraphie.api.MultiFontEditableLineRequest
import org.graphiks.kalligraphie.api.OutlineProfile
import org.graphiks.kalligraphie.api.ShapedGlyphRun
import org.graphiks.kalligraphie.api.ShapingDirection
import org.graphiks.kalligraphie.api.ShapingRequest
import org.graphiks.kalligraphie.api.TextRange
import org.graphiks.kalligraphie.api.toDiagnostic

/** Resolves one captured line input into shaped runs without leaking temporary assets. */
internal object FontFallbackResolver {
    fun resolve(request: MultiFontEditableLineRequest): FontOperationResult<FontFallbackResolution> {
        val units = fallbackUnits(request)
        if (units.isEmpty()) return FontOperationResult.Success(FontFallbackResolution(emptyList(), emptyList(), emptyList()))

        val requirements = requirementsFor(request.materialization)
        val records = request.fontCatalog.faces.associateBy(FontFaceRecord::id)
        val blacklist = mutableSetOf<RejectedCandidate>()
        val instances = mutableMapOf<FontFaceId, FontInstance>()
        val shapedGroups = mutableMapOf<GroupSignature, ShapedGlyphRun>()
        val diagnostics = mutableListOf<FontDiagnostic>()
        var assignments = units.map { unit ->
            when (
                val selection = selectCandidate(
                    unit,
                    request.fontCatalog,
                    request.resolutionPolicy,
                    records,
                    requirements,
                    request,
                    instances,
                    blacklist,
                    diagnostics,
                )
            ) {
                is CandidateSelection.Selected -> selection.assigned
                CandidateSelection.Exhausted -> return unresolved(unit, diagnostics)
                CandidateSelection.Cancelled -> return FontOperationResult.Cancelled()
            }
        }

        while (true) {
            if (request.cancellationToken.isCancellationRequested()) return FontOperationResult.Cancelled()
            val shaped = mutableListOf<ShapedGlyphRun>()
            var rejected: List<AssignedUnit>? = null
            contiguousGroups(assignments).forEach { group ->
                if (rejected != null) return@forEach
                val signature = GroupSignature.from(group)
                val cached = shapedGroups[signature]
                if (cached != null) {
                    shaped += cached
                    return@forEach
                }
                when (val attempted = shapeAndValidate(group, request)) {
                    is Attempt.Success -> {
                        shapedGroups[signature] = attempted.run
                        shaped += attempted.run
                    }
                    is Attempt.Rejected -> {
                        diagnostics += attempted.diagnostics
                        rejected = group
                    }

                    Attempt.Cancelled -> return FontOperationResult.Cancelled()
                }
            }
            val rejectedGroup = rejected
            if (rejectedGroup == null) {
                assignments.filter { it.record.id == request.resolutionPolicy.lastResortFace }.forEach { assigned ->
                    diagnostics += FontDiagnostic(
                        code = "font.fallback-last-resort",
                        severity = FontDiagnosticSeverity.WARNING,
                        location = FontDiagnosticLocation.FaceId(assigned.record.id),
                        message = "The explicitly declared last-resort face ${assigned.record.id} was selected for an indivisible fallback unit.",
                    )
                }
                return FontOperationResult.Success(
                    FontFallbackResolution(
                        units = units,
                        shapedRuns = shaped,
                        instances = assignments.map(AssignedUnit::instance).distinctBy(FontInstance::key),
                        diagnostics = diagnostics,
                    ),
                )
            }

            rejectedGroup.forEach { assigned ->
                blacklist += RejectedCandidate(assigned.unit.range, assigned.record.id, requirements.outlineProfile)
                diagnostics += rejectedCandidateDiagnostic(
                    assigned.record.id,
                    "Shaping or final glyph materialization rejected the complete fallback unit.",
                )
                if (assigned.record.id == request.resolutionPolicy.lastResortFace) {
                    diagnostics += rejectedLastResortDiagnostic(assigned.record.id)
                }
            }
            assignments = assignments.map { assigned ->
                if (assigned in rejectedGroup) {
                    when (
                        val selection = selectCandidate(
                            assigned.unit,
                            request.fontCatalog,
                            request.resolutionPolicy,
                            records,
                            requirements,
                            request,
                            instances,
                            blacklist,
                            diagnostics,
                        )
                    ) {
                        is CandidateSelection.Selected -> selection.assigned
                        CandidateSelection.Exhausted -> return unresolved(assigned.unit, diagnostics)
                        CandidateSelection.Cancelled -> return FontOperationResult.Cancelled()
                    }
                } else {
                    assigned
                }
            }
        }
    }

    private fun selectCandidate(
        unit: FallbackUnit,
        catalog: FontCatalogSnapshot,
        policy: FontResolutionPolicySnapshot,
        records: Map<FontFaceId, FontFaceRecord>,
        requirements: FontAccessRequirementsSnapshot,
        request: MultiFontEditableLineRequest,
        instances: MutableMap<FontFaceId, FontInstance>,
        blacklist: MutableSet<RejectedCandidate>,
        diagnostics: MutableList<FontDiagnostic>,
    ): CandidateSelection {
        policy.candidates.forEach { candidate ->
            val record = records.getValue(candidate.faceId)
            val rejected = RejectedCandidate(unit.range, record.id, requirements.outlineProfile)
            if (rejected in blacklist || !supports(record.capabilities, requirements)) return@forEach
            val instance = instances[record.id] ?: run {
                val face = when (val resolved = catalog.resolveFace(record.id, requirements)) {
                    is FontOperationResult.Success -> resolved.value
                    is FontOperationResult.Failure -> {
                        blacklist += rejected
                        diagnostics += rejectedCandidateDiagnostic(record.id, "Face resolution did not meet the required capabilities.")
                        if (record.id == policy.lastResortFace) diagnostics += rejectedLastResortDiagnostic(record.id)
                        return@forEach
                    }

                    is FontOperationResult.Cancelled -> return CandidateSelection.Cancelled
                }
                when (val instantiated = face.instantiate(request.fontInstanceDescriptor)) {
                    is FontOperationResult.Success -> instantiated.value.also { instances[record.id] = it }
                    is FontOperationResult.Failure -> {
                        blacklist += rejected
                        diagnostics += rejectedCandidateDiagnostic(record.id, "Face instantiation failed for the requested instance descriptor.")
                        if (record.id == policy.lastResortFace) diagnostics += rejectedLastResortDiagnostic(record.id)
                        return@forEach
                    }

                    is FontOperationResult.Cancelled -> return CandidateSelection.Cancelled
                }
            }
            when (mapsAllRequiredScalars(unit, request, instance)) {
                ScalarMapping.Supported -> return CandidateSelection.Selected(AssignedUnit(unit, record, instance))
                ScalarMapping.Cancelled -> return CandidateSelection.Cancelled
                ScalarMapping.Unsupported -> Unit
            }
            blacklist += rejected
            diagnostics += rejectedCandidateDiagnostic(record.id, "The complete fallback unit is not covered by the candidate character mapping.")
            if (record.id == policy.lastResortFace) diagnostics += rejectedLastResortDiagnostic(record.id)
        }
        return CandidateSelection.Exhausted
    }

    private fun mapsAllRequiredScalars(
        unit: FallbackUnit,
        request: MultiFontEditableLineRequest,
        instance: FontInstance,
    ): ScalarMapping {
        var precedingScalar: Int? = null
        request.snapshot.scalarValues(unit.range).forEach { scalar ->
            if (request.cancellationToken.isCancellationRequested()) return ScalarMapping.Cancelled
            if (scalar.isVariationSelector()) {
                val base = precedingScalar ?: return ScalarMapping.Unsupported
                when (val result = instance.resolveGlyph(base, scalar)) {
                    is FontOperationResult.Success -> if (result.value.glyphId.value == 0) return ScalarMapping.Unsupported
                    is FontOperationResult.Failure -> return ScalarMapping.Unsupported
                    is FontOperationResult.Cancelled -> return ScalarMapping.Cancelled
                }
                precedingScalar = null
            } else {
                if (scalar !in IGNORED_MAPPING_SCALARS) {
                    when (val result = instance.resolveGlyph(scalar)) {
                        is FontOperationResult.Success -> if (result.value.glyphId.value == 0) return ScalarMapping.Unsupported
                        is FontOperationResult.Failure -> return ScalarMapping.Unsupported
                        is FontOperationResult.Cancelled -> return ScalarMapping.Cancelled
                    }
                }
                precedingScalar = scalar.takeUnless { it in IGNORED_MAPPING_SCALARS }
            }
        }
        return ScalarMapping.Supported
    }

    private fun shapeAndValidate(group: List<AssignedUnit>, request: MultiFontEditableLineRequest): Attempt {
        val first = group.first()
        val last = group.last()
        val range = TextRange(first.unit.range.start, last.unit.range.endExclusive)
        val shaped = when (
            val result = request.shapingBackend.shape(
                ShapingRequest(
                    snapshot = request.snapshot,
                    range = range,
                    font = first.instance,
                    direction = if (first.unit.bidiLevel % 2 == 0) ShapingDirection.LEFT_TO_RIGHT else ShapingDirection.RIGHT_TO_LEFT,
                    script = first.unit.script,
                    language = first.unit.language,
                    bidiLevel = first.unit.bidiLevel,
                    bot = range.start == request.snapshot.range.start,
                    eot = range.endExclusive == request.snapshot.range.endExclusive,
                    featurePolicy = request.shapingBackend.identity.featurePolicy,
                    features = request.features,
                    graphemeClusters = request.unicodeAnalysis.graphemeClusters.filter { grapheme -> contains(range, grapheme) },
                ),
            )
        ) {
            is FontOperationResult.Success -> result.value
            is FontOperationResult.Failure -> return Attempt.Rejected(result.diagnostics)
            is FontOperationResult.Cancelled -> return Attempt.Cancelled
        }
        if (shaped.glyphs.any { it.glyphId.value == 0 }) {
            return Attempt.Rejected(listOf(rejectionDiagnostic("Shaping produced the missing-glyph identifier for a complete fallback unit.")))
        }
        val materialization = request.materialization
        if (materialization is EditableLineMaterialization.Renderable) {
            when (val validation = validateOutlines(shaped, first.instance, materialization, request)) {
                Validation.Valid -> Unit
                is Validation.Rejected -> return Attempt.Rejected(validation.diagnostics)
                Validation.Cancelled -> return Attempt.Cancelled
            }
        }
        return Attempt.Success(shaped)
    }

    private fun validateOutlines(
        shaped: ShapedGlyphRun,
        instance: FontInstance,
        materialization: EditableLineMaterialization.Renderable,
        request: MultiFontEditableLineRequest,
    ): Validation {
        val asset = when (
            val acquired = instance.acquireRenderAsset(
                resolver = materialization.resolver,
                variant = materialization.variant,
                requirements = FontAccessRequirementsSnapshot.renderable(materialization.outlineProfile),
            )
        ) {
            is FontOperationResult.Success -> acquired.value
            is FontOperationResult.Failure -> return Validation.Rejected(acquired.diagnostics)
            is FontOperationResult.Cancelled -> return Validation.Cancelled
        }
        var validation: Validation = Validation.Valid
        try {
            if (asset.key.fontInstanceKey != instance.key || asset.key.generation != materialization.resolver.generation) {
                validation = Validation.Rejected(listOf(rejectionDiagnostic("Acquired render asset does not identify the shaped instance and generation.")))
            } else {
                shaped.glyphs.forEach { glyph ->
                    if (validation != Validation.Valid) return@forEach
                    when (val resolved = asset.resolveGlyph(FontGlyphRequest(glyph.glyphId), request.cancellationToken)) {
                        is FontOperationResult.Success -> when (val representation = resolved.value) {
                            GlyphRepresentation.Empty -> Unit
                            is GlyphRepresentation.Outline -> if (representation.outline.glyphId != glyph.glyphId.value) {
                                validation = Validation.Rejected(listOf(rejectionDiagnostic("Resolved outline does not match the final shaped glyph identifier.")))
                            }
                        }

                        is FontOperationResult.Failure -> validation = Validation.Rejected(resolved.diagnostics)
                        is FontOperationResult.Cancelled -> validation = Validation.Cancelled
                    }
                }
            }
        } finally {
            when (val closed = asset.close()) {
                is FontOperationResult.Failure -> if (validation == Validation.Valid) validation = Validation.Rejected(closed.diagnostics)
                is FontOperationResult.Cancelled -> if (validation == Validation.Valid) validation = Validation.Cancelled
                is FontOperationResult.Success -> Unit
            }
        }
        return validation
    }

    private fun fallbackUnits(request: MultiFontEditableLineRequest): List<FallbackUnit> {
        val graphemeUnits = request.unicodeAnalysis.graphemeClusters.map { cluster ->
            val script = request.unicodeAnalysis.scriptLanguageRuns.firstOrNull { contains(it.range, cluster) }
                ?: request.unicodeAnalysis.scriptLanguageRuns.first { overlaps(it.range, cluster) }
            val bidi = request.unicodeAnalysis.logicalBidiRuns.firstOrNull { contains(it.range, cluster) }
                ?: request.unicodeAnalysis.logicalBidiRuns.first { overlaps(it.range, cluster) }
            FallbackUnit(cluster, org.graphiks.kalligraphie.api.OpenTypeScript(script.script), script.language, bidi.level)
        }
        return graphemeUnits
    }

    private fun contiguousGroups(assignments: List<AssignedUnit>): List<List<AssignedUnit>> {
        val groups = mutableListOf<MutableList<AssignedUnit>>()
        assignments.forEach { assigned ->
            val previous = groups.lastOrNull()?.lastOrNull()
            if (
                previous != null &&
                previous.record.id == assigned.record.id &&
                previous.unit.script == assigned.unit.script &&
                previous.unit.language == assigned.unit.language &&
                previous.unit.bidiLevel == assigned.unit.bidiLevel &&
                previous.unit.range.endExclusive == assigned.unit.range.start
            ) {
                groups.last() += assigned
            } else {
                groups += mutableListOf(assigned)
            }
        }
        return groups
    }

    private fun supports(capabilities: FontFaceCapabilities, requirements: FontAccessRequirementsSnapshot): Boolean =
        capabilities.characterMapping && capabilities.shaping &&
            (requirements.mode != FontAccessRequirementsSnapshot.Mode.RENDERABLE || capabilities.outline)

    private fun requirementsFor(materialization: EditableLineMaterialization): FontAccessRequirementsSnapshot = when (materialization) {
        EditableLineMaterialization.LayoutOnly -> FontAccessRequirementsSnapshot.layoutOnly()
        is EditableLineMaterialization.Renderable -> FontAccessRequirementsSnapshot.renderable(materialization.outlineProfile)
    }

    private fun unresolved(
        unit: FallbackUnit,
        diagnostics: List<FontDiagnostic>,
    ): FontOperationResult.Failure {
        val error = FontError.UnrenderableFontResolution(
            message = "No policy candidate can shape and materialize the complete fallback unit.",
            location = FontDiagnosticLocation.Source,
        )
        return FontOperationResult.Failure(error, diagnostics + error.toDiagnostic())
    }

    private fun contains(owner: TextRange, item: TextRange): Boolean =
        item.start >= owner.start && item.endExclusive <= owner.endExclusive

    private fun overlaps(left: TextRange, right: TextRange): Boolean =
        left.start < right.endExclusive && right.start < left.endExclusive

    private fun rejectionDiagnostic(message: String): FontDiagnostic = FontDiagnostic(
        code = "font.fallback-shaping-rejected",
        severity = FontDiagnosticSeverity.WARNING,
        location = FontDiagnosticLocation.Source,
        message = message,
    )

    private fun rejectedCandidateDiagnostic(faceId: FontFaceId, reason: String): FontDiagnostic = FontDiagnostic(
        code = "font.fallback-candidate-rejected",
        severity = FontDiagnosticSeverity.WARNING,
        location = FontDiagnosticLocation.FaceId(faceId),
        message = "Candidate $faceId was rejected: $reason",
    )

    private fun rejectedLastResortDiagnostic(faceId: FontFaceId): FontDiagnostic = FontDiagnostic(
        code = "font.fallback-last-resort-rejected",
        severity = FontDiagnosticSeverity.WARNING,
        location = FontDiagnosticLocation.FaceId(faceId),
        message = "The explicitly declared last-resort face $faceId was rejected.",
    )

    private data class AssignedUnit(
        val unit: FallbackUnit,
        val record: FontFaceRecord,
        val instance: FontInstance,
    )

    private data class RejectedCandidate(
        val range: TextRange,
        val faceId: FontFaceId,
        val profile: OutlineProfile?,
    )

    private data class GroupSignature(
        val assignments: List<GroupAssignment>,
    ) {
        companion object {
            fun from(group: List<AssignedUnit>): GroupSignature = GroupSignature(
                group.map { assigned -> GroupAssignment(assigned.unit.range, assigned.record.id, assigned.instance.key) },
            )
        }
    }

    private data class GroupAssignment(
        val range: TextRange,
        val faceId: FontFaceId,
        val instanceKey: org.graphiks.kalligraphie.api.FontInstanceKey,
    )

    private sealed interface CandidateSelection {
        data class Selected(val assigned: AssignedUnit) : CandidateSelection
        data object Exhausted : CandidateSelection
        data object Cancelled : CandidateSelection
    }

    private enum class ScalarMapping {
        Supported,
        Unsupported,
        Cancelled,
    }

    private sealed interface Attempt {
        data class Success(val run: ShapedGlyphRun) : Attempt
        data class Rejected(val diagnostics: List<FontDiagnostic>) : Attempt
        data object Cancelled : Attempt
    }

    private sealed interface Validation {
        data object Valid : Validation
        data class Rejected(val diagnostics: List<FontDiagnostic>) : Validation
        data object Cancelled : Validation
    }

    private val IGNORED_MAPPING_SCALARS: Set<Int> = buildSet {
        add(0x200D)
    }

    private fun Int.isVariationSelector(): Boolean = this in 0xFE00..0xFE0F || this in 0xE0100..0xE01EF
}
