// SPDX-FileCopyrightText: 2019-2025 Robin Lindén <dev@robinlinden.eu>
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import android.util.Log
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.repository.MessageRepository
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.Message
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.core.vo.Sender
import com.stoyko.smessenger.domain.tox.MAX_MESSAGE_LENGTH
import com.stoyko.smessenger.domain.tox.Tox

private const val TAG = "ChatManager"

private fun String.chunked(chunkSizeInBytes: Int): MutableList<String> {
    val encoder = StandardCharsets.UTF_8.newEncoder()
    val tmp = ByteBuffer.allocate(chunkSizeInBytes - 1)
    val input = CharBuffer.wrap(this)
    val chunks: MutableList<String> = ArrayList()
    var currentIdx = 0

    do {
        val res = encoder.encode(input, tmp, true)
        val nextIdx = this.length - input.length
        chunks.add(this.substring(currentIdx, nextIdx))
        currentIdx = nextIdx
        tmp.rewind()
    } while (res.isOverflow)

    return chunks
}

@Singleton
class ChatManager @Inject constructor(
    private val scope: CoroutineScope,
    private val contactRepository: ContactRepository,
    private val messageRepository: MessageRepository,
    private val tox: Tox,
) {
    var activeChat = ""
        set(value) {
            field = value
            if (value.isNotEmpty()) {
                scope.launch {
                    contactRepository.setHasUnreadMessages(value, false)
                }
            }
        }

    fun messagesFor(publicKey: PublicKey) = messageRepository.get(publicKey.string())

    fun sendMessage(publicKey: PublicKey, message: String, type: MessageType = MessageType.Normal) = scope.launch {
        if ((contactRepository.get(publicKey.string()).first()?.connectionStatus ?: ConnectionStatus.None) == ConnectionStatus.None) {
            queueMessage(publicKey, message, type)
            return@launch
        }

        val msgs = message.chunked(MAX_MESSAGE_LENGTH)
        while (msgs.size > 1) {
            tox.sendMessage(publicKey, msgs.removeAt(0), type)
        }

        messageRepository.add(
            Message(
                publicKey.string(),
                message,
                Sender.Sent,
                type,
                tox.sendMessage(publicKey, msgs.first(), type),
            ),
        )
    }

    /**
     * Служебное сообщение (например, «прочитано»): уходит, только если собеседник в сети,
     * и не сохраняется в истории.
     */
    fun sendControl(publicKey: PublicKey, message: String) = scope.launch {
        val online = (contactRepository.get(publicKey.string()).first()?.connectionStatus ?: ConnectionStatus.None) !=
            ConnectionStatus.None
        if (online) tox.sendMessage(publicKey, message, MessageType.Normal)
    }

    private fun queueMessage(publicKey: PublicKey, message: String, type: MessageType) =
        messageRepository.add(Message(publicKey.string(), message, Sender.Sent, type, Int.MIN_VALUE))

    // Сообщения, которые сейчас переотправляются - второй вызов их не трогает.
    private val resending = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Long, Boolean>())

    /**
     * Отправить ожидающие сообщения. Сразу после «появился в сети» ядро ещё может не принять
     * сообщение (соединение не готово) - раньше оно так и висело «уйдёт, когда будет в сети»
     * до перезапуска. Теперь неудачные попытки повторяются сами (через 2, 5, 10, 20, 40 с).
     */
    fun resend(messages: List<Message>) = scope.launch {
        var left = messages
        val delays = longArrayOf(0, 2_000, 5_000, 10_000, 20_000, 40_000)
        for (wait in delays) {
            if (left.isEmpty()) break
            if (wait > 0) kotlinx.coroutines.delay(wait)
            val failed = ArrayList<Message>()
            for (message in left) {
                if (!resending.add(message.id)) continue
                try {
                    if (!resendOne(message)) failed += message
                } catch (e: Exception) {
                    Log.w(TAG, "resend: $e")
                    failed += message
                } finally {
                    resending.remove(message.id)
                }
            }
            left = failed
        }
    }

    /** true - ядро приняло сообщение (дальше его доставку гарантирует само ядро). */
    private fun resendOne(message: Message): Boolean {
        val msgs = message.message.chunked(MAX_MESSAGE_LENGTH).toMutableList()
        while (msgs.size > 1) {
            if (tox.sendMessage(PublicKey(message.publicKey), msgs.removeAt(0), message.type) < 0) return false
        }
        val id = tox.sendMessage(PublicKey(message.publicKey), msgs.first(), message.type)
        messageRepository.setCorrelationId(message.id, id)
        return id >= 0
    }

    /**
     * Изменить своё сообщение у обоих: [newStored] - новый текст для хранения (с меткой «изменено»),
     * [control] - служебное сообщение собеседнику. Если он не в сети - уйдёт при подключении.
     */
    fun editMessage(publicKey: PublicKey, id: Long, newStored: String, control: String) = scope.launch {
        messageRepository.updateText(id, newStored)
        sendMessage(publicKey, control)
    }

    /** Удалить сообщение у обоих ([control] - служебное сообщение собеседнику). */
    fun deleteForAll(publicKey: PublicKey, id: Long, control: String) = scope.launch {
        messageRepository.deleteMessage(id)
        sendMessage(publicKey, control)
    }

    fun deleteMessage(id: Long) = scope.launch {
        messageRepository.deleteMessage(id)
    }

    fun clearHistory(publicKey: PublicKey) = scope.launch {
        messageRepository.delete(publicKey.string())
        contactRepository.setLastMessage(publicKey.string(), 0)
    }

    fun setTyping(publicKey: PublicKey, typing: Boolean) = scope.launch {
        tox.setTyping(publicKey, typing)
    }
}
