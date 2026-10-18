// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.vo.GroupChat
import com.stoyko.smessenger.core.vo.GroupMessage

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfMissing(group: GroupChat)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(group: GroupChat)

    @Query("SELECT * FROM group_chats ORDER BY last_message DESC")
    fun loadAll(): Flow<List<GroupChat>>

    @Query("SELECT * FROM group_chats WHERE chat_id = :chatId")
    fun load(chatId: String): Flow<GroupChat?>

    @Query("SELECT * FROM group_chats WHERE chat_id = :chatId")
    fun get(chatId: String): GroupChat?

    @Query("UPDATE group_chats SET name = :name WHERE chat_id = :chatId AND :name != ''")
    fun setName(chatId: String, name: String)

    @Query("UPDATE group_chats SET is_channel = :isChannel WHERE chat_id = :chatId")
    fun setChannel(chatId: String, isChannel: Boolean)

    @Query("UPDATE group_chats SET role = :role, peer_count = :peerCount WHERE chat_id = :chatId")
    fun setState(chatId: String, role: Int, peerCount: Int)

    @Query("UPDATE group_chats SET last_message = :time, has_unread = :unread WHERE chat_id = :chatId")
    fun setLastMessage(chatId: String, time: Long, unread: Boolean)

    @Query("UPDATE group_chats SET has_unread = 0 WHERE chat_id = :chatId")
    fun markRead(chatId: String)

    @Query("DELETE FROM group_chats WHERE chat_id = :chatId")
    fun delete(chatId: String)

    /** -1, если такое сообщение уже есть (повтор при синхронизации). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertMessage(message: GroupMessage): Long

    /** Последние текстовые сообщения - для отправки истории новому/вернувшемуся участнику. */
    @Query(
        "SELECT * FROM group_messages WHERE chat_id = :chatId AND file_name = '' AND timestamp > :since " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    fun recentTexts(chatId: String, since: Long, limit: Int): List<GroupMessage>

    /** Лента новостей: свежие посты всех каналов (и технической группы). */
    @Query(
        "SELECT gm.* FROM group_messages gm INNER JOIN group_chats gc ON gm.chat_id = gc.chat_id " +
            "WHERE gc.is_channel = 1 OR gm.chat_id = :extraChatId ORDER BY gm.timestamp DESC LIMIT :limit",
    )
    fun feed(extraChatId: String, limit: Int): Flow<List<GroupMessage>>

    @Query("SELECT MAX(timestamp) FROM group_messages WHERE chat_id = :chatId")
    fun lastTimestamp(chatId: String): Long?

    // По времени отправки: сообщения, пришедшие позже из истории, встают на своё место.
    @Query("SELECT * FROM group_messages WHERE chat_id = :chatId ORDER BY timestamp, id")
    fun loadMessages(chatId: String): Flow<List<GroupMessage>>

    @Query("SELECT * FROM group_messages WHERE chat_id = :chatId AND file_id = :fileId LIMIT 1")
    fun findFileMessage(chatId: String, fileId: String): GroupMessage?

    @Query("UPDATE group_messages SET progress = :progress WHERE id = :id")
    fun setProgress(id: Long, progress: Long)

    @Query("DELETE FROM group_messages WHERE chat_id = :chatId")
    fun deleteMessages(chatId: String)

    @Query("SELECT * FROM group_messages WHERE chat_id = :chatId AND msg_uid = :uid LIMIT 1")
    fun findByUid(chatId: String, uid: String): GroupMessage?

    @Query("UPDATE group_messages SET message = :text WHERE chat_id = :chatId AND msg_uid = :uid")
    fun updateText(chatId: String, uid: String, text: String)

    @Query("DELETE FROM group_messages WHERE chat_id = :chatId AND msg_uid = :uid")
    fun deleteByUid(chatId: String, uid: String)

    @Query("SELECT * FROM group_messages WHERE chat_id = :chatId AND message LIKE :pattern ORDER BY timestamp")
    fun findLike(chatId: String, pattern: String): List<GroupMessage>

    /** Общий поиск по группам и каналам: текст сообщения или имя файла. */
    @Query(
        "SELECT * FROM group_messages WHERE message LIKE '%' || :q || '%' OR file_name LIKE '%' || :q || '%' " +
            "ORDER BY timestamp DESC LIMIT :limit",
    )
    fun search(q: String, limit: Int): List<GroupMessage>
}
