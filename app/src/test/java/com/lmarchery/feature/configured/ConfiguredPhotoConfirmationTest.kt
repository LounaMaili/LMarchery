package com.lmarchery.feature.configured

import com.lmarchery.backup.V3TestData as f
import com.lmarchery.core.database.ConfiguredWriteRequest
import com.lmarchery.core.model.NormalizedPoint
import com.lmarchery.core.scoring.profiles.WorldArcheryIndoorTargetProfiles
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

class ConfiguredPhotoConfirmationTest {
    @Test fun `photo batch uses effective profile and retains old impacts in one acknowledged revision`() {
        EditorHarness().use { editor ->
            val controller = editor.c
            controller.create()
            controller.applySuggestion(editor.token, SessionSuggestion.INDOOR_COMPOUND)
            controller.createAndStart(editor.token)
            editor.tap(0f)
            val before = assertNotNull(editor.ui.saved)
            val context = editor.ctx
            val points = linkedMapOf(f.id(901) to NormalizedPoint(.075f, 0f), f.id(902) to NormalizedPoint(0f, .25f))
            val acknowledgement = CompletableDeferred<Unit>()
            editor.hold = acknowledgement

            assertTrue(controller.confirmPhotoImpacts(context, points))
            assertFalse(controller.confirmPhotoImpacts(context, points))
            assertEquals(before, editor.ui.saved)
            val request = assertIs<ConfiguredWriteRequest.Update>(editor.requests.last())
            assertEquals(before, request.expected)
            val end = request.proposed.ends.single()
            assertEquals(WorldArcheryIndoorTargetProfiles.compound40, end.configuration.targetType)
            assertEquals(before.ends.single().end.impacts.single(), end.end.impacts.first())
            assertEquals(points.keys.toList(), end.end.impacts.drop(1).map { it.uuid })
            assertEquals(points.values.toList(), end.end.impacts.drop(1).map { it.point })
            assertEquals(9, end.end.impacts[1].scoreCalculated)

            acknowledgement.complete(Unit)
            assertEquals(request.proposed, editor.ui.saved)
            assertFalse(controller.confirmPhotoImpacts(editor.ctx, points))
            assertEquals(3, editor.requests.size)
        }
    }

    @Test fun `invalid stale full or validated submissions never request an update`() {
        EditorHarness().use { editor ->
            editor.editing()
            val controller = editor.c
            val point = mapOf(f.id(901) to NormalizedPoint(0f, 0f))
            assertFalse(controller.confirmPhotoImpacts(editor.ctx, mapOf(f.id(901) to NormalizedPoint(1f, 1f))))
            val oldContext = editor.ctx
            editor.nextEnd()
            val count = editor.requests.size
            assertFalse(controller.confirmPhotoImpacts(oldContext, point))
            assertFalse(controller.confirmPhotoImpacts(editor.ctx, (901..904).associate { f.id(it) to NormalizedPoint(0f, 0f) }))
            assertEquals(count, editor.requests.size)
            editor.tap(0f)
            controller.validateEnd()
            val afterValidation = editor.requests.size
            assertFalse(controller.confirmPhotoImpacts(editor.ctx, point))
            assertEquals(afterValidation, editor.requests.size)
        }
    }
}
