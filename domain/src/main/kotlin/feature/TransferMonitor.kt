// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.feature

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/**
 * Живая статистика передач файлов (: «1,2 / 5,4 МБ · 350 КБ/с»).
 * Хранится только в памяти и обновляется на каждом куске без записи в базу -
 * экран читает её сам раз в ~200 мс, поэтому список сообщений не перестраивается постоянно.
 *
 * Ключи: "ft:<id>" - личные чаты, "g:<id сообщения>" - группы и каналы.
 */
object TransferMonitor {
    /** [label] - имя файла, [peer] - ключ собеседника (пусто - группа/канал): для уведомления в шторке. */
    class Live(val total: Long, val outgoing: Boolean, val label: String = "", val peer: String = "") {
        @Volatile
        var done: Long = 0

        /** Байт в секунду (сглаженная). */
        @Volatile
        var speed: Double = 0.0

        @Volatile
        var paused = false

        internal var windowStart = SystemClock.elapsedRealtime()
        internal var windowBytes = 0L
        val startedAt = SystemClock.elapsedRealtime()

        val fraction: Float get() = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f

        /** Оставшееся время, секунды (или -1, если скорость неизвестна). */
        val etaSeconds: Long get() = if (speed > 1.0) ((total - done) / speed).toLong() else -1
    }

    private val live = ConcurrentHashMap<String, Live>()

    fun key(ftId: Int) = "ft:$ftId"
    fun groupKey(messageId: Long) = "g:$messageId"

    /** Есть активные передачи - ядро крутится чаще (выше скорость). */
    val busy: Boolean get() = live.isNotEmpty()

    fun start(key: String, total: Long, outgoing: Boolean, alreadyDone: Long = 0, label: String = "", peer: String = "") {
        val l = live[key]
        if (l != null && l.total == total) return
        live[key] = Live(total, outgoing, label, peer).apply { done = alreadyDone.coerceAtLeast(0) }
        runCatching { onStart?.invoke() }
    }

    // ---------- для уведомления о передаче в шторке ----------

    /** Началась передача - приложение запускает уведомление с прогрессом. */
    @Volatile
    var onStart: (() -> Unit)? = null

    /** Закончившаяся передача: удачно ли, в какую сторону, имя, собеседник. */
    class Done(val key: String, val ok: Boolean, val outgoing: Boolean, val label: String, val peer: String)

    private val finished = java.util.concurrent.ConcurrentLinkedQueue<Done>()

    /** Забрать закончившиеся с прошлого раза. */
    fun drainFinished(): List<Done> {
        val out = ArrayList<Done>()
        while (true) out += finished.poll() ?: break
        return out
    }

    /** Отмена по ключу (кнопка «Отменить» в шторке): обработчики по префиксу ключа ("ft:", "g:"). */
    val cancelHandlers = ConcurrentHashMap<String, (String) -> Unit>()

    fun canCancel(key: String): Boolean = cancelHandlers.keys.any { key.startsWith(it) }

    fun cancel(key: String) {
        cancelHandlers.entries.firstOrNull { key.startsWith(it.key) }?.value?.let { h -> runCatching { h(key) } }
    }

    /** Все идущие передачи (снимок). */
    fun snapshot(): Map<String, Live> = HashMap(live)

    /** Прибавить переданные байты. */
    fun add(key: String, bytes: Int) {
        val l = live[key] ?: return
        update(l, l.done + bytes)
    }

    /** Установить общее число переданных байт. */
    fun set(key: String, done: Long) {
        val l = live[key] ?: return
        update(l, done)
    }

    private fun update(l: Live, done: Long) {
        val delta = (done - l.done).coerceAtLeast(0)
        l.done = done.coerceAtMost(l.total)
        l.windowBytes += delta
        val now = SystemClock.elapsedRealtime()
        val dt = now - l.windowStart
        if (dt >= 400) {
            val instant = l.windowBytes * 1000.0 / dt
            l.speed = if (l.speed <= 0.0) instant else l.speed * 0.6 + instant * 0.4
            l.windowStart = now
            l.windowBytes = 0
        }
    }

    fun finish(key: String) {
        val l = live.remove(key) ?: return
        if (finished.size < 64) finished.add(Done(key, l.total > 0 && l.done >= l.total, l.outgoing, l.label, l.peer))
    }

    fun get(key: String): Live? = live[key]

    /** «1,2 МБ» - размер для людей (по-русски). */
    fun size(bytes: Long, ru: Boolean = true): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b < 1024 -> "$b " + if (ru) "Б" else "B"
            b < 1024 * 1024 -> "%.1f ".format(b / 1024.0) + if (ru) "КБ" else "KB"
            b < 1024L * 1024 * 1024 -> "%.1f ".format(b / 1024.0 / 1024.0) + if (ru) "МБ" else "MB"
            else -> "%.2f ".format(b / 1024.0 / 1024.0 / 1024.0) + if (ru) "ГБ" else "GB"
        }
    }

    /** «1,2 / 5,4 МБ · 350 КБ/с» для строки под файлом. */
    fun describe(l: Live, ru: Boolean = true): String {
        val pct = if (l.total > 0) "${(l.done * 100 / l.total).coerceIn(0, 100)}% · " else ""
        val speed = if (l.speed > 0) " · " + size(l.speed.toLong(), ru) + if (ru) "/с" else "/s" else ""
        // Сколько осталось (если скорость уже известна).
        val left = l.total - l.done
        val eta = if (l.speed > 1 && left > 0) {
            val sec = (left / l.speed).toLong()
            if (sec in 1..359_999) " · ~" + (if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)) else ""
        } else {
            ""
        }
        return pct + size(l.done, ru) + " / " + size(l.total, ru) + speed + eta
    }
}
