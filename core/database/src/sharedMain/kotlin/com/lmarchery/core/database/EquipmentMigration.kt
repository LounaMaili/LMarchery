package com.lmarchery.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection

/** Additive only: V1 rows, identity kinds and opaque session references are never rewritten. */
internal val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(connection: SQLiteConnection) {
        connection.prepare("""
            CREATE TABLE `bows` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `type` TEXT,
                `drawWeightLbsBits` INTEGER, `brand` TEXT, `model` TEXT, `notes` TEXT,
                `created_epochSecond` INTEGER, `created_nano` INTEGER,
                `updated_epochSecond` INTEGER, `updated_nano` INTEGER,
                PRIMARY KEY(`uuid`), FOREIGN KEY(`uuid`) REFERENCES `entity_identities`(`uuid`)
                ON UPDATE NO ACTION ON DELETE RESTRICT)
        """.trimIndent()).use { it.step() }
        connection.prepare("""
            CREATE TABLE `arrow_sets` (`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `spine` TEXT,
                `length` TEXT, `pointWeight` TEXT, `nock` TEXT, `vanes` TEXT, `material` TEXT, `notes` TEXT,
                `created_epochSecond` INTEGER, `created_nano` INTEGER,
                `updated_epochSecond` INTEGER, `updated_nano` INTEGER,
                PRIMARY KEY(`uuid`), FOREIGN KEY(`uuid`) REFERENCES `entity_identities`(`uuid`)
                ON UPDATE NO ACTION ON DELETE RESTRICT)
        """.trimIndent()).use { it.step() }
    }
}
