// SPDX-FileCopyrightText: 2019-2020 aTox contributors
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.core.repository

import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import com.stoyko.smessenger.core.db.MessageDao
import com.stoyko.smessenger.core.vo.Message

@Singleton
class MessageRepository @Inject internal constructor(
    private val messageDao: MessageDao,
    private val contactRepository: ContactRepository,
) {
    fun add(message: Message) {
        messageDao.save(message)
        contactRepository.setLastMessage(message.publicKey, Date().time)
    }

    /**
     * Строка файла в чате - не больше одной на передачу: повторный вызов для того же
     * correlationId (докачка, повторное предложение файла) вторую строку не добавляет.
     * false - строка уже была.
     */
    fun addFileMessageOnce(message: Message): Boolean {
        if (messageDao.countFileMessages(message.publicKey, message.correlationId) > 0) return false
        add(message)
        return true
    }

    /** Время строки файла в чате (null - строки нет). */
    fun fileMessageTime(conversation: String, correlationId: Int): Long? =
        messageDao.fileMessageTime(conversation, correlationId)

    fun get(conversation: String): Flow<List<Message>> = messageDao.load(conversation)

    fun getPending(conversation: String): List<Message> = messageDao.loadPending(conversation)

    fun setCorrelationId(id: Long, correlationId: Int) = messageDao.setCorrelationId(id, correlationId)

    fun delete(conversation: String) = messageDao.delete(conversation)

    fun deleteMessage(id: Long) = messageDao.deleteMessage(id)

    fun updateText(id: Long, text: String) = messageDao.updateText(id, text)

    /** Все сообщения чата сразу (не поток) - для поиска при изменении/удалении. */
    fun getNow(conversation: String): List<Message> = messageDao.loadNow(conversation)

    /** Общий поиск по тексту всех личных чатов. */
    fun search(q: String, limit: Int = 300): List<Message> = messageDao.search(q, limit)

    /** Для синхронизации устройств: доставленные сообщения новее [since]. */
    fun since(since: Long, limit: Int = 5000): List<Message> = messageDao.since(since, limit)

    /**
     * Добавить сообщение с другого своего устройства, если такого ещё нет. Время последнего
     * сообщения в чате обновляется по самому сообщению (а не «сейчас»). true - добавлено.
     */
    fun addSynced(message: Message): Boolean {
        if (messageDao.countSame(message.publicKey, message.timestamp, message.sender.ordinal, message.message) > 0) return false
        messageDao.save(message)
        contactRepository.bumpLastMessage(message.publicKey, message.timestamp)
        return true
    }

    fun setReceipt(conversation: String, correlationId: Int, timestamp: Long) =
        messageDao.setReceipt(conversation, correlationId, timestamp)
}
