// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.vo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Группа или канал. chatId - постоянный 64-символьный ID, по нему в группу можно войти. */
@Entity(tableName = "group_chats")
data class GroupChat(
    @PrimaryKey
    @ColumnInfo(name = "chat_id")
    val chatId: String,

    @ColumnInfo(name = "name")
    var name: String = "",

    /** Канал: пишет только владелец (и модераторы), остальные читают. */
    @ColumnInfo(name = "is_channel")
    var isChannel: Boolean = false,

    /** Моя роль: 0 владелец, 1 модератор, 2 участник, 3 читатель. */
    @ColumnInfo(name = "role")
    var role: Int = 2,

    @ColumnInfo(name = "last_message")
    var lastMessage: Long = 0,

    @ColumnInfo(name = "has_unread")
    var hasUnread: Boolean = false,

    @ColumnInfo(name = "peer_count")
    var peerCount: Int = 0,
) {
    // Функции, а не свойства: Room не должен принимать их за столбцы.
    fun canPost(): Boolean = !isChannel || role <= 1
}

@Entity(
    tableName = "group_messages",
    indices = [Index(value = ["chat_id", "msg_uid"], unique = true)],
)
data class GroupMessage(
    @ColumnInfo(name = "chat_id")
    val chatId: String,

    @ColumnInfo(name = "sender_key")
    val senderKey: String,

    @ColumnInfo(name = "sender_name")
    val senderName: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "outgoing")
    val outgoing: Boolean,

    /** Для файлов: путь к файлу на устройстве (пусто - обычное сообщение). */
    @ColumnInfo(name = "file_path")
    val filePath: String = "",

    @ColumnInfo(name = "file_name")
    val fileName: String = "",

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,

    /** Сколько байт файла уже получено/отправлено; -1 - ошибка. */
    @ColumnInfo(name = "progress")
    val progress: Long = 0,

    /** ID файла в протоколе группы (hex), чтобы находить сообщение при приёме кусков. */
    @ColumnInfo(name = "file_id")
    val fileId: String = "",

    /** Уникальный ID сообщения (16 hex) - по нему отбрасываются повторы при синхронизации истории. */
    @ColumnInfo(name = "msg_uid")
    val msgUid: String = "",
) {
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0

    fun isFile(): Boolean = fileName.isNotEmpty()
    fun isComplete(): Boolean = isFile() && progress >= fileSize
}
