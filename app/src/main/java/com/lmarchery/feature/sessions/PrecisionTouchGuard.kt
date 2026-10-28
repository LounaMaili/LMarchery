package com.lmarchery.feature.sessions

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.lmarchery.feature.scoring.AimMultiTouchGuard

/** Observe before children, including a second finger over controls outside the target. */
fun Modifier.precisionTouchGuard(controller: SessionController): Modifier = preciseTouchGuard(controller,
    { controller.state.value.precisionAimEnabled }, { controller.state.value.activeAim?.token }, controller::cancelPreciseAim)

fun Modifier.preciseTouchGuard(owner: Any, enabled: () -> Boolean, token: () -> Long?, cancel: (Long) -> Unit): Modifier = pointerInput(owner) {
    val guard = AimMultiTouchGuard()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val rejected = guard.reject(event.changes.count { it.pressed || it.previousPressed }, event.changes.any { it.pressed })
            if (enabled() && rejected) {
                token()?.let(cancel)
                event.changes.forEach { it.consume() }
            }
        }
    }
}
