package com.lmarchery.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path

/** The caller chooses a durable location; no temporary/default directory is silently selected. */
fun openSessionStore(path: Path): RoomSessionStore = RoomSessionStore(
    Room.databaseBuilder<SessionDatabase>(path.toAbsolutePath().toString())
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .setDriver(BundledSQLiteDriver())
        .build(),
)
