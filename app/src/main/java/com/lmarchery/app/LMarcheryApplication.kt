package com.lmarchery.app

import android.app.Application
import com.lmarchery.core.database.openSessionStore
import com.lmarchery.data.RoomSessionRepository

class LMarcheryApplication : Application() {
    // One store for this process, using only the application Context. The OS releases it on process exit.
    val sessionRepository: RoomSessionRepository by lazy { RoomSessionRepository(openSessionStore(this)) }
}
