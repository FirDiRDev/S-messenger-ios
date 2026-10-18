// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport.bt

import android.content.Context
import android.content.SharedPreferences
import java.util.Collections

/**
 * Какие сообщения в истории прошли по Bluetooth (значок «🔵» у времени).
 * Отправленные - по номеру сообщения, принятые - по чату и тексту (номер строки базы
 * назначается позже, в общем обработчике входящих). Последние 500 штук, без записи на каждый кадр.
 */
object BtMarks {
    private const val MAX = 500
    private var prefs: SharedPreferences? = null
    private val sent: MutableSet<Long> = Collections.synchronizedSet(LinkedHashSet())
    private val received: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    @Volatile
    private var empty = true

    @Synchronized
    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("transport_bt_marks", Context.MODE_PRIVATE)
        prefs = p
        p.getString("sent", "")?.split(',')?.mapNotNull { it.toLongOrNull() }?.let { sent.addAll(it) }
        p.getString("recv", "")?.split(',')?.filter { it.isNotEmpty() }?.let { received.addAll(it) }
        empty = sent.isEmpty() && received.isEmpty()
    }

    private fun recvKey(pk: String, text: String) = pk.take(16).uppercase() + ":" + Integer.toHexString(text.hashCode())

    fun markSent(id: Long) {
        sent.add(id)
        trim(sent)
        empty = false
        save()
    }

    fun markReceived(pk: String, text: String) {
        received.add(recvKey(pk, text))
        trim(received)
        empty = false
        save()
    }

    /** Префикс для строки времени: «🔵 » или пусто. Быстро (вызывается при каждой отрисовке). */
    fun tag(pk: String, id: Long, text: String, outgoing: Boolean): String {
        if (empty) return ""
        val hit = if (outgoing) id in sent else recvKey(pk, text) in received
        return if (hit) "🔵 " else ""
    }

    private fun <T> trim(s: MutableSet<T>) = synchronized(s) {
        val it = s.iterator()
        while (s.size > MAX && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private fun save() {
        val p = prefs ?: return
        val a = synchronized(sent) { sent.joinToString(",") }
        val b = synchronized(received) { received.joinToString(",") }
        p.edit().putString("sent", a).putString("recv", b).apply()
    }
}
