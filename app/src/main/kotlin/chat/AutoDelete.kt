// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.repository.MessageRepository
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.feature.ChatManager
import com.stoyko.smessenger.domain.feature.FileTransferManager

private const val TAG = "AutoDelete"
private const val CHECK_INTERVAL_MS = 15_000L

// Служебное сообщение о смене таймера: невидимый символ + метка. Собеседник с нашим приложением
// применяет таймер у себя, в чате оно показывается строкой «⏱ ... включил автоудаление».
private const val MARKER = "⁣⏱AD:"

/** Варианты таймера (секунды), 0 - выключено. */
val AUTO_DELETE_OPTIONS = longArrayOf(0, 30, 5 * 60, 60 * 60, 24 * 60 * 60, 7 * 24 * 60 * 60, 30L * 24 * 60 * 60)

/**
 * Автоудаление сообщений в личном чате по таймеру:
 * таймер общий для обоих собеседников, сообщения (и файлы) удаляются у обоих
 * через заданное время после доставки.
 */
@Singleton
class AutoDelete @Inject constructor(
    private val context: Context,
    private val messageRepository: MessageRepository,
    private val chatManager: ChatManager,
    private val fileTransferManager: FileTransferManager,
    private val scope: CoroutineScope,
) {
    private val prefs = context.getSharedPreferences("auto_delete", Context.MODE_PRIVATE)
    private var job: Job? = null

    private val _changes = MutableStateFlow(0)

    /** Меняется при любой смене таймера - экраны обновляют подпись. */
    val changes: StateFlow<Int> = _changes

    fun seconds(pk: String): Long = prefs.getLong(pk, 0L)

    /** Пользователь выбрал таймер в чате: применяем и сообщаем собеседнику. */
    fun setByUser(pk: PublicKey, seconds: Long) {
        if (seconds(pk.string()) == seconds) return
        store(pk.string(), seconds)
        chatManager.sendMessage(pk, MARKER + seconds, MessageType.Normal)
    }

    /** Пришло сообщение от собеседника: если это смена таймера - применяем. true - служебное. */
    fun onIncoming(pk: String, text: String): Boolean {
        val s = parse(text) ?: return false
        store(pk, s)
        return true
    }

    private fun store(pk: String, seconds: Long) {
        prefs.edit().apply {
            if (seconds <= 0) remove(pk) else putLong(pk, seconds)
        }.apply()
        _changes.value = _changes.value + 1
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    sweep()
                } catch (e: Exception) {
                    Log.e(TAG, "sweep", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun sweep() {
        val now = System.currentTimeMillis()
        for ((pk, value) in prefs.all) {
            val ttl = (value as? Long) ?: continue
            if (ttl <= 0) continue
            val messages = messageRepository.get(pk).first()
            val lastMarker = messages.lastOrNull { isMarker(it.message) }?.id
            for (m in messages) {
                // Время считаем от доставки; неотправленные не трогаем. Последнюю отметку о таймере оставляем.
                if (m.timestamp <= 0 || now - m.timestamp < ttl * 1000 || m.id == lastMarker) continue
                if (m.type == MessageType.FileTransfer) {
                    runCatching { fileTransferManager.delete(m.correlationId) }
                }
                messageRepository.deleteMessage(m.id)
            }
        }
    }

    companion object {
        fun isMarker(text: String) = text.startsWith(MARKER)

        fun parse(text: String): Long? =
            if (isMarker(text)) text.removePrefix(MARKER).trim().toLongOrNull()?.coerceAtLeast(0) else null

        /** Короткая подпись: «30 с», «5 мин», «1 ч», «1 д», «1 нед», «1 мес». */
        fun label(context: Context, seconds: Long): String = when {
            seconds <= 0 -> context.getString(R.string.autodelete_off)
            seconds < 60 -> context.getString(R.string.autodelete_seconds, seconds)
            seconds < 3600 -> context.getString(R.string.autodelete_minutes, seconds / 60)
            seconds < 86_400 -> context.getString(R.string.autodelete_hours, seconds / 3600)
            seconds < 7 * 86_400 -> context.getString(R.string.autodelete_days, seconds / 86_400)
            seconds < 30 * 86_400 -> context.getString(R.string.autodelete_weeks, seconds / (7 * 86_400))
            else -> context.getString(R.string.autodelete_months, seconds / (30 * 86_400))
        }

        /** Текст служебной строки для чата / списка чатов. */
        fun systemText(context: Context, text: String, outgoing: Boolean): String {
            val s = parse(text) ?: return text
            return if (s <= 0) {
                context.getString(if (outgoing) R.string.autodelete_you_off else R.string.autodelete_peer_off)
            } else {
                context.getString(
                    if (outgoing) R.string.autodelete_you_on else R.string.autodelete_peer_on,
                    label(context, s),
                )
            }
        }
    }
}
