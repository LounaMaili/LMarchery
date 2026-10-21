package com.lmarchery.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver

/** Open once per application container, never from a composable or an Activity recreation. */
fun openSessionStore(context: Context, name: String = "sessions.db"): RoomSessionStore {
    require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "." && name != "..") { "A local database file name is required." }
    val app = context.applicationContext
    return RoomSessionStore(Room.databaseBuilder<SessionDatabase>(app, app.getDatabasePath(name).absolutePath)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .setDriver(BundledSQLiteDriver()).build())
}
