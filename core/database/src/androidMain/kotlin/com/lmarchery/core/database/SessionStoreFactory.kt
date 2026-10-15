package com.lmarchery.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Prepared for future app integration; Compose does not use this store yet. */
fun openSessionStore(context: Context, name: String = "sessions.db"): RoomSessionStore {
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..") { "A local database file name is required." }
    val app = context.applicationContext
    return RoomSessionStore(Room.databaseBuilder<SessionDatabase>(app, app.getDatabasePath(name).absolutePath)
        .setDriver(BundledSQLiteDriver()).build())
}
