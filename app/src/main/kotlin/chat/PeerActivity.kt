// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.stoyko.smessenger.core.vo.PublicKey
import com.stoyko.smessenger.domain.tox.Tox
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Что сейчас делает собеседник, кроме «печатает»: отправляет файл / фото / видео, записывает аудио.
 *
 * Передаётся «невидимым» пакетом ядра (lossless packet, как проверка шифрования): в переписку
 * ничего не попадает, у старых версий ничего лишнего не появляется. Пока действие идёт, пакет
 * повторяется каждые 3 с; если повторы прекратились (связь пропала), статус гаснет сам через 8 с.
 */
object PeerActivity {
    /** Номер пакета: свободный из диапазона пользовательских пакетов ядра (0xA0-0xBF). */
    private const val PACKET: Byte = 0xA9.toByte()
    private const val TTL_MS = 8_000L
    private const val REPEAT_MS = 3_000L

    enum class Kind(val code: Int) { NONE(0), FILE(1), PHOTO(2), VIDEO(3), VOICE(4), CIRCLE(5) }

    private val main = Handler(Looper.getMainLooper())
    private val peers = ConcurrentHashMap<String, Pair<Kind, Long>>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Подписка; повторная подписка того же слушателя ничего не делает (без задвоений). */
    fun listen(l: () -> Unit) = listeners.addIfAbsent(l)
    fun unlisten(l: () -> Unit) = listeners.remove(l)
    private fun changed() = main.post { listeners.forEach { runCatching { it() } } }

    /** Что делает собеседник прямо сейчас (null - ничего, кроме, может быть, «печатает»). */
    fun of(pk: String): Kind? {
        val (k, at) = peers[pk.uppercase()] ?: return null
        if (SystemClock.uptimeMillis() - at > TTL_MS) {
            peers.remove(pk.uppercase())
            return null
        }
        return k
    }

    /** Входящий пакет. true - это был наш пакет. */
    fun handle(pk: String, data: ByteArray): Boolean {
        if (data.size < 2 || data[0] != PACKET) return false
        val kind = Kind.values().firstOrNull { it.code == data[1].toInt() } ?: Kind.NONE
        val key = pk.uppercase()
        if (kind == Kind.NONE) peers.remove(key) else peers[key] = kind to SystemClock.uptimeMillis()
        changed()
        // Статус погаснет сам, если повторы перестанут приходить.
        main.postDelayed({ if (of(key) == null) changed() }, TTL_MS + 200)
        return true
    }

    // ---------------- отправка ----------------

    private val active = ConcurrentHashMap<String, Kind>()

    private fun sendNow(tox: Tox, pk: String, kind: Kind) {
        runCatching { tox.sendLosslessPacket(PublicKey(pk), byteArrayOf(PACKET, kind.code.toByte())) }
    }

    /** Начать показывать собеседнику действие [kind] (повторяется, пока не вызван [stop]). */
    fun start(tox: Tox, pk: String, kind: Kind) {
        active[pk] = kind
        sendNow(tox, pk, kind)
        val tick = object : Runnable {
            override fun run() {
                if (active[pk] != kind) return
                sendNow(tox, pk, kind)
                main.postDelayed(this, REPEAT_MS)
            }
        }
        main.postDelayed(tick, REPEAT_MS)
    }

    fun stop(tox: Tox, pk: String) {
        if (active.remove(pk) != null) sendNow(tox, pk, Kind.NONE)
    }

    /** Короткое действие (файл ушёл в отправку): показывается несколько секунд и гаснет. */
    fun pulse(tox: Tox, pk: String, kind: Kind, ms: Long = 6_000L) {
        start(tox, pk, kind)
        main.postDelayed({ if (active[pk] == kind) stop(tox, pk) }, ms)
    }
}
