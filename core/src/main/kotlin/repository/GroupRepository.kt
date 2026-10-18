// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.repository

import javax.inject.Inject
import javax.inject.Singleton
import com.stoyko.smessenger.core.db.GroupDao
import com.stoyko.smessenger.core.vo.GroupChat
import com.stoyko.smessenger.core.vo.GroupMessage

@Singleton
class GroupRepository @Inject internal constructor(private val dao: GroupDao) {
    fun getAll() = dao.loadAll()
    fun get(chatId: String) = dao.load(chatId)
    fun find(chatId: String) = dao.get(chatId)
    fun ensure(chatId: String, name: String) {
        dao.insertIfMissing(GroupChat(chatId, name, lastMessage = System.currentTimeMillis()))
        dao.setName(chatId, name)
    }
    fun setName(chatId: String, name: String) = dao.setName(chatId, name)
    fun save(group: GroupChat) = dao.save(group)
    fun setChannel(chatId: String, isChannel: Boolean) = dao.setChannel(chatId, isChannel)
    fun setState(chatId: String, role: Int, peerCount: Int) = dao.setState(chatId, role, peerCount)
    fun markRead(chatId: String) = dao.markRead(chatId)
    fun delete(chatId: String) {
        dao.deleteMessages(chatId)
        dao.delete(chatId)
    }

    fun messages(chatId: String) = dao.loadMessages(chatId)
    fun feed(extraChatId: String, limit: Int = 300) = dao.feed(extraChatId, limit)
    /** Возвращает id или -1, если это повтор (тогда ничего не обновляем и не уведомляем). */
    fun addMessage(message: GroupMessage, unread: Boolean): Long {
        val id = dao.insertMessage(message)
        if (id >= 0) dao.setLastMessage(message.chatId, System.currentTimeMillis(), unread)
        return id
    }
    fun recentTexts(chatId: String, since: Long, limit: Int) = dao.recentTexts(chatId, since, limit)
    fun lastTimestamp(chatId: String): Long = dao.lastTimestamp(chatId) ?: 0L
    fun findFileMessage(chatId: String, fileId: String) = dao.findFileMessage(chatId, fileId)
    fun setProgress(id: Long, progress: Long) = dao.setProgress(id, progress)
    fun findByUid(chatId: String, uid: String) = dao.findByUid(chatId, uid)
    fun updateText(chatId: String, uid: String, text: String) = dao.updateText(chatId, uid, text)
    fun deleteByUid(chatId: String, uid: String) = dao.deleteByUid(chatId, uid)
    fun findLike(chatId: String, pattern: String) = dao.findLike(chatId, pattern)

    /** Общий поиск по всем группам и каналам. */
    fun search(q: String, limit: Int = 300) = dao.search(q, limit)
}
