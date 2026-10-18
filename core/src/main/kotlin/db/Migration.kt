// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(
        "ALTER TABLE contacts ADD COLUMN has_unread_messages INTEGER NOT NULL DEFAULT 0",
    )
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(
        "ALTER TABLE messages ADD COLUMN type INTEGER NOT NULL DEFAULT 0",
    )
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS 'file_transfers'")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS 'file_transfers' (
                'id' INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                'public_key' TEXT NOT NULL,
                'file_number' INTEGER NOT NULL,
                'file_kind' INTEGER NOT NULL,
                'file_size' INTEGER NOT NULL,
                'file_name' TEXT NOT NULL,
                'destination' TEXT NOT NULL,
                'outgoing' INTEGER NOT NULL,
                'progress' INTEGER NOT NULL)
            """.trimIndent(),
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) = db.execSQL(
        "ALTER TABLE contacts ADD COLUMN draft_message TEXT NOT NULL DEFAULT ''",
    )
}

// Группы и каналы.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_chats` (`chat_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`is_channel` INTEGER NOT NULL, `role` INTEGER NOT NULL, `last_message` INTEGER NOT NULL, " +
                "`has_unread` INTEGER NOT NULL, `peer_count` INTEGER NOT NULL, PRIMARY KEY(`chat_id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `group_messages` (`chat_id` TEXT NOT NULL, `sender_key` TEXT NOT NULL, " +
                "`sender_name` TEXT NOT NULL, `message` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`outgoing` INTEGER NOT NULL, `file_path` TEXT NOT NULL, `file_name` TEXT NOT NULL, " +
                "`file_size` INTEGER NOT NULL, `progress` INTEGER NOT NULL, `file_id` TEXT NOT NULL, " +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)",
        )
    }
}

// Уникальный ID сообщений группы (синхронизация истории без повторов).
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `group_messages` ADD COLUMN `msg_uid` TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE `group_messages` SET `msg_uid` = 'old' || `id`")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_group_messages_chat_id_msg_uid` " +
                "ON `group_messages` (`chat_id`, `msg_uid`)",
        )
    }
}

val ALL_MIGRATIONS =
    arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
