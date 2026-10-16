package com.lmarchery.feature.sessions

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.lmarchery.feature.scoring.AimMultiTouchGuard

/** Observe before children, including a second finger over controls outside the target. */
fun Modifier.precisionTouchGuard(controller: SessionController): Modifier = pointerInput(controller) {
    val guard = AimMultiTouchGuard()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val rejected = guard.reject(event.changes.count { it.pressed || it.previousPressed }, event.changes.any { it.pressed })
            if (controller.state.value.precisionAimEnabled && rejected) {
                controller.state.value.activeAim?.token?.let(controller::cancelPreciseAim)
                event.changes.forEach { it.consume() }
            }
        }
    }
}
