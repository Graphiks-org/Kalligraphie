package org.graphiks.kalligraphie.coroutines

import kotlinx.coroutines.test.runTest
import org.graphiks.kalligraphie.JvmEditableLineFacade
import org.graphiks.kalligraphie.api.EditableLineMaterialization
import org.graphiks.kalligraphie.api.EditableLineResult
import org.graphiks.kalligraphie.api.FontOperationResult
import org.graphiks.kalligraphie.api.FontRenderAssetHandle
import org.graphiks.kalligraphie.api.FontRenderVariantKey
import org.graphiks.kalligraphie.api.GlyphMaterializationRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class MaterializationSuspendTest {
    private fun renderableMaterialization(fixture: LineFixture): EditableLineMaterialization =
        EditableLineMaterialization.Renderable(
            resolver = fixture.resolver,
            variant = FontRenderVariantKey.default,
            outlineProfile = COROUTINES_OUTLINE_PROFILE,
        )

    @Test
    fun suspendRenderableLineMatchesTheSynchronousLine() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture, materialization = renderableMaterialization(fixture))

            val synchronous = assertIs<EditableLineResult.Success>(JvmEditableLineFacade.layout(request))
            val suspended = assertIs<EditableLineResult.Success>(KalligraphieCoroutines.layout(request))

            val synchronousGlyph = synchronous.line.positionedGlyphRuns.single().glyphs.single()
            val suspendedGlyph = suspended.line.positionedGlyphRuns.single().glyphs.single()
            assertEquals(synchronousGlyph.shapedGlyph.glyphId, suspendedGlyph.shapedGlyph.glyphId)
            assertEquals(GlyphMaterializationRoute.OUTLINE, suspendedGlyph.materializationCertificate?.route)
            assertSame(suspendedGlyph.renderAssetKey, suspendedGlyph.materializationCertificate?.assetKey)
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }

    @Test
    fun theBorrowedResolverRemainsOperationalAfterTheSuspendCall() = runTest {
        val fixture = lineFixture("A")
        try {
            val request = lineRequest(fixture, materialization = renderableMaterialization(fixture))

            val suspended = assertIs<EditableLineResult.Success>(KalligraphieCoroutines.layout(request))
            val assetKey = assertNotNull(suspended.line.positionedGlyphRuns.single().glyphs.single().renderAssetKey)

            // The facade must not have closed a resolver it does not own: reopening must still work.
            val reopened = assertIs<FontOperationResult.Success<FontRenderAssetHandle>>(
                fixture.resolver.reopen(assetKey),
            ).value
            reopened.close()
        } finally {
            assertClosed(fixture.resolver.close())
        }
    }
}
