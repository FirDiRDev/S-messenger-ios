// SPDX-FileCopyrightText: 2019-2021 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.vo.Message

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun save(message: Message)

    @Query("SELECT * FROM messages WHERE conversation == :conversation")
    fun load(conversation: String): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversation == :conversation AND timestamp == 0")
    fun loadPending(conversation: String): List<Message>

    @Query("UPDATE messages SET correlation_id = :correlationId WHERE id == :id")
    fun setCorrelationId(id: Long, correlationId: Int)

    @Query("DELETE FROM messages WHERE conversation == :conversation")
    fun delete(conversation: String)

    @Suppress("ktlint:standard:max-line-length")
    @Query(
        "UPDATE messages SET timestamp = :timestamp WHERE conversation == :conversation AND correlation_id == :correlationId AND timestamp == 0",
    )
    fun setReceipt(conversation: String, correlationId: Int, timestamp: Long)

    /** Сколько строк чата уже показывают передачу файла [correlationId] (type 2 - FileTransfer). */
    @Query("SELECT COUNT(*) FROM messages WHERE conversation == :conversation AND type = 2 AND correlation_id == :correlationId")
    fun countFileMessages(conversation: String, correlationId: Int): Int

    /** Время строки чата для передачи файла [correlationId] (null - строки нет). */
    @Query(
        "SELECT timestamp FROM messages WHERE conversation == :conversation AND type = 2 AND correlation_id == :correlationId " +
            "ORDER BY id LIMIT 1",
    )
    fun fileMessageTime(conversation: String, correlationId: Int): Long?

    /** Синхронизация устройств (4.10.0): доставленные сообщения новее [since], кроме строк файлов. */
    @Query("SELECT * FROM messages WHERE timestamp > :since AND type != 2 ORDER BY timestamp LIMIT :limit")
    fun since(since: Long, limit: Int): List<Message>

    /** Есть ли уже такое сообщение (синхронизация не создаёт повторов). */
    @Query("SELECT COUNT(*) FROM messages WHERE conversation == :conversation AND timestamp == :timestamp AND sender == :sender AND message == :message")
    fun countSame(conversation: String, timestamp: Long, sender: Int, message: String): Int

    @Query("DELETE FROM messages WHERE id = :id")
    fun deleteMessage(id: Long)

    @Query("UPDATE messages SET message = :text WHERE id = :id")
    fun updateText(id: Long, text: String)

    @Query("SELECT * FROM messages WHERE conversation == :conversation")
    fun loadNow(conversation: String): List<Message>

    /** Общий поиск: сообщения личных чатов, где встречается текст (новые первыми). */
    @Query("SELECT * FROM messages WHERE message LIKE '%' || :q || '%' ORDER BY timestamp DESC LIMIT :limit")
    fun search(q: String, limit: Int): List<Message>
}
