package com.lmarchery

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Captures the synthetic test screen in the target package's private storage. */
internal fun captureUxScreen(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val directory = File(instrumentation.targetContext.filesDir, "ux-captures").apply { mkdirs() }
    val automation = instrumentation.uiAutomation
    automation.waitForIdle(500, 3_000)
    val bitmap = requireNotNull(automation.takeScreenshot())
    try {
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
        bitmap.recycle()
    }
}
