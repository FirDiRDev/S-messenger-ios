// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport.bt

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.firstOrNull
import com.stoyko.smessenger.App
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.core.vo.MessageType
import com.stoyko.smessenger.core.vo.Sender

private const val TAG = "BtChat"

/**
 * Сообщения через Bluetooth, когда Tox недоступен.
 *
 * Отправка: берём из истории те сообщения другу, которые ядро Tox так и не приняло
 * (висят «🕓», correlationId < 0) - значит, по Tox они точно не уходили, и дубля не будет.
 * После подтверждения от друга сообщение помечается доставленным (✓ и 🔵).
 * Приём: текст проходит через обычный обработчик входящих сообщений (уведомления, подписи,
 * защита от дублей) - как будто пришёл по Tox; номер сообщения запоминается, повтор не показывается.
 */
object BtChat {
    private const val T_TEXT: Byte = 1
    private const val T_ACK: Byte = 2
    private const val MAX_TEXT_BYTES = 24_000

    private lateinit var app: Context

    private val acks = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "bt-ack").apply { isDaemon = true } }

    /** Уже принятые сообщения: «ключ друга:номер» (защита от повтора после обрыва). */
    private val seen = ConcurrentHashMap<String, Long>()

    /** Отправлено и ждёт подтверждения: номер сообщения → время. */
    private val inflight = ConcurrentHashMap<Long, Long>()

    @Volatile
    private var seenLoaded = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (!seenLoaded) {
            seenLoaded = true
            app.getSharedPreferences("transport_bt_seen", Context.MODE_PRIVATE).getString("seen", "")
                ?.split(',')?.filter { it.isNotEmpty() }?.forEach { seen[it] = 0L }
        }
    }

    private fun component() = (app as App).component

    /** Сеанс с другом установлен - отправляем всё, что ждёт. Вызывается в фоновом потоке. */
    fun flush(pk: String, session: BtSession) {
        if (!::app.isInitialized) return
        val c = component()
        // Друг на связи по Tox - Bluetooth не нужен (Tox сам дошлёт, без дублей).
        val online = runCatching {
            kotlinx.coroutines.runBlocking { c.contactRepository().get(pk).firstOrNull() }?.connectionStatus
        }.getOrNull()
        if (online != null && online != ConnectionStatus.None) return
        val pending = runCatching { c.messageRepository().getPending(pk) }.getOrDefault(emptyList())
            .filter { it.sender == Sender.Sent && it.type == MessageType.Normal && it.correlationId < 0 && it.timestamp == 0L }
        val now = System.currentTimeMillis()
        // Один поток отправки на сеанс: иначе два параллельных вызова отправили бы сообщение дважды.
        synchronized(session) { sendPending(pending, now, session) }
    }

    private fun sendPending(pending: List<com.stoyko.smessenger.core.vo.Message>, now: Long, session: BtSession) {
        for (m in pending) {
            val t = inflight[m.id]
            if (t != null && now - t < 15_000) continue
            val body = m.message.toByteArray(Charsets.UTF_8)
            if (body.isEmpty() || body.size > MAX_TEXT_BYTES) continue
            val frame = ByteArray(9 + body.size)
            frame[0] = T_TEXT
            BtSession.putLong(frame, 1, m.id)
            System.arraycopy(body, 0, frame, 9, body.size)
            session.send(frame)
            inflight[m.id] = now
        }
    }

    /** Кадр от друга. Ответ (подтверждение) отправляется сразу. */
    fun onFrame(pk: String, frame: ByteArray, session: BtSession) {
        if (frame.size < 9) return
        val id = BtSession.getLong(frame, 1)
        when (frame[0]) {
            T_TEXT -> {
                val key = pk.take(16) + ":" + id
                if (seen.putIfAbsent(key, System.currentTimeMillis()) == null) {
                    val text = String(frame, 9, frame.size - 9, Charsets.UTF_8)
                    BtMarks.markReceived(pk, text)
                    com.stoyko.smessenger.transport.TransportHooks.deliverIncoming(pk, text)
                    saveSeen()
                }
                val ack = ByteArray(9)
                ack[0] = T_ACK
                BtSession.putLong(ack, 1, id)
                // Подтверждение - из отдельного потока: поток чтения никогда не ждёт записи
                // (иначе две стороны, одновременно отправляющие много, могли бы зависнуть друг на друге).
                acks.execute { runCatching { session.send(ack) } }
            }
            T_ACK -> {
                inflight.remove(id)
                runCatching {
                    val repo = component().messageRepository()
                    // Отдельный отрицательный номер: строка перестаёт быть «ожидающей» и не уйдёт повторно по Tox.
                    val corr = -(1_000_000_000 + (id % 1_000_000_000L).toInt())
                    repo.setCorrelationId(id, corr)
                    repo.setReceipt(pk, corr, System.currentTimeMillis())
                    BtMarks.markSent(id)
                }.onFailure { Log.w(TAG, "ack: $it") }
            }
        }
    }

    private fun saveSeen() {
        while (seen.size > 1000) {
            val oldest = seen.entries.minByOrNull { it.value }?.key ?: break
            seen.remove(oldest)
        }
        app.getSharedPreferences("transport_bt_seen", Context.MODE_PRIVATE).edit()
            .putString("seen", seen.keys.joinToString(",")).apply()
    }

    /** Сколько сообщений другу ждут отправки (для решения, стоит ли искать его по Bluetooth). */
    fun pendingCount(pk: String): Int = runCatching {
        component().messageRepository().getPending(pk).count { it.sender == Sender.Sent && it.correlationId < 0 && it.type == MessageType.Normal }
    }.getOrDefault(0)
}
