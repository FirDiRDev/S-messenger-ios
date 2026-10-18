// SPDX-FileCopyrightText: 2026 StoykoMessenger
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.tox

import android.content.Context
import java.util.Calendar
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.stoyko.smessenger.R
import com.stoyko.smessenger.core.repository.ContactRepository
import com.stoyko.smessenger.core.vo.ConnectionStatus
import com.stoyko.smessenger.domain.tox.Tox

/** Друг пропал - показываем «не в сети», только если он не вернулся за это время (без мигания). */
private const val OFFLINE_GRACE_MS = 15_000L

/** Пропала наша собственная связь - через столько считаем, что никого из друзей не видим. */
private const val SELF_OFFLINE_GRACE_MS = 20_000L

private const val PREFS = "presence_last_seen"
private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Единый источник статуса «в сети» для контактов.
 *
 * События ядра о подключении друзей проходят здесь и попадают в таблицу контактов
 * (её читают список чатов, шапка чата, профиль, звонки и игры - больше статус нигде не хранится):
 * - «в сети» показывается сразу;
 * - «не в сети» - только если друг не вернулся за [OFFLINE_GRACE_MS]: короткие обрывы при смене
 *   TCP/UDP, ретранслятора или сети больше не дают мигания «в сети / не в сети»;
 * - при уходе из сети запоминается время для «был(а) в сети …» ([lastSeenText]);
 * - пропала наша собственная связь дольше [SELF_OFFLINE_GRACE_MS] - все, кроме соседей по
 *   локальной сети (прямое UDP-соединение), показываются «не в сети» (ядро само сообщает об
 *   этом лишь через 30+ с, а раньше контакт мог так и остаться «в сети»);
 * - связь вернулась - известные статусы применяются заново, и ядро опрашивается для сверки.
 *
 * Вызывается из потока цикла ядра (через [EventListenerCallbacks]).
 */
@Singleton
class Presence @Inject constructor(
    private val ctx: Context,
    private val contactRepository: ContactRepository,
    private val tox: Tox,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            com.stoyko.smessenger.domain.Diagnostics.coroutineHandler("статус в сети"),
    )
    private val lock = Any()

    /** Что сообщило ядро. */
    private val raw = ConcurrentHashMap<String, ConnectionStatus>()

    /** Что сейчас записано в базу (видит интерфейс). */
    private val shown = ConcurrentHashMap<String, ConnectionStatus>()

    /** Отложенные «не в сети» (ключ -> задача и время ухода). */
    private val offlineJobs = HashMap<String, Job>()

    private var selfJob: Job? = null
    private var selfLost = false

    /** Новый запуск ядра: всё, что знали о прошлом сеансе, недостоверно (база сбрасывается в Tox.start). */
    fun reset() = synchronized(lock) {
        offlineJobs.values.forEach { it.cancel() }
        offlineJobs.clear()
        selfJob?.cancel()
        selfJob = null
        selfLost = false
        raw.clear()
        shown.clear()
    }

    /** Ядро сообщило о подключении друга (или сверка нашла расхождение). */
    fun onFriend(publicKey: String, status: ConnectionStatus) = synchronized(lock) {
        raw[publicKey] = status
        if (status != ConnectionStatus.None) {
            offlineJobs.remove(publicKey)?.cancel()
            show(publicKey, status)
        } else if (shown[publicKey].isOnline() && !offlineJobs.containsKey(publicKey)) {
            val since = System.currentTimeMillis()
            offlineJobs[publicKey] = scope.launch {
                delay(OFFLINE_GRACE_MS)
                val me = coroutineContext[Job]
                synchronized(lock) {
                    if (offlineJobs[publicKey] !== me) return@launch
                    offlineJobs.remove(publicKey)
                    if (raw[publicKey] == ConnectionStatus.None) markOffline(publicKey, since)
                }
            }
        } else if (!shown[publicKey].isOnline() && !offlineJobs.containsKey(publicKey)) {
            show(publicKey, ConnectionStatus.None)
        }
    }

    /** Наша собственная связь с сетью Tox. */
    fun onSelf(status: ConnectionStatus) = synchronized(lock) {
        selfJob?.cancel()
        selfJob = null
        if (status != ConnectionStatus.None) {
            if (selfLost) {
                selfLost = false
                // Ядро могло не прислать событие (для него друг и не пропадал) - возвращаем как было.
                raw.forEach { (pk, s) -> if (s != ConnectionStatus.None) show(pk, s) }
            }
            // Связь вернулась - сверяем всех с ядром на ближайшем проходе цикла.
            tox.requestPresenceSync()
        } else {
            val since = System.currentTimeMillis()
            selfJob = scope.launch {
                delay(SELF_OFFLINE_GRACE_MS)
                val me = coroutineContext[Job]
                synchronized(lock) {
                    if (selfJob !== me) return@launch
                    selfJob = null
                    selfLost = true
                    shown.filter { it.value.isOnline() }.keys.forEach { pk ->
                        // Прямое соединение (локальная сеть без интернета) работает и без нашей связи с DHT.
                        if (raw[pk] != ConnectionStatus.UDP) {
                            offlineJobs.remove(pk)?.cancel()
                            markOffline(pk, since)
                        }
                    }
                }
            }
        }
    }

    private fun show(publicKey: String, status: ConnectionStatus) {
        if (shown[publicKey] == status) return
        shown[publicKey] = status
        contactRepository.setConnectionStatus(publicKey, status)
    }

    private fun markOffline(publicKey: String, since: Long) {
        if (shown[publicKey].isOnline()) setLastSeen(ctx, publicKey, since)
        show(publicKey, ConnectionStatus.None)
    }

    private fun ConnectionStatus?.isOnline() = this != null && this != ConnectionStatus.None

    companion object {
        private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun setLastSeen(ctx: Context, publicKey: String, time: Long) {
            runCatching { prefs(ctx).edit().putLong(publicKey, time).apply() }
        }

        /** Когда контакт последний раз был в сети (0 - неизвестно). */
        fun lastSeen(ctx: Context, publicKey: String): Long =
            runCatching { prefs(ctx).getLong(publicKey, 0L) }.getOrDefault(0L)

        /** «был(а) в сети 5 мин. назад», «… вчера в 21:40», «… 3 окт. 2026 г.»; null - время неизвестно. */
        fun lastSeenText(ctx: Context, publicKey: String, now: Long = System.currentTimeMillis()): String? {
            val t = lastSeen(ctx, publicKey)
            if (t <= 0L) return null
            val diff = now - t
            val time = android.text.format.DateFormat.getTimeFormat(ctx).format(Date(t))
            return when {
                diff < 60_000L -> ctx.getString(R.string.presence_last_seen_just_now)
                diff < 60 * 60_000L -> ctx.getString(R.string.presence_last_seen_minutes, (diff / 60_000L).toInt())
                sameDay(t, now) -> ctx.getString(R.string.presence_last_seen_today, time)
                sameDay(t, now - DAY_MS) -> ctx.getString(R.string.presence_last_seen_yesterday, time)
                else -> ctx.getString(
                    R.string.presence_last_seen_date,
                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(t)),
                )
            }
        }

        private fun sameDay(a: Long, b: Long): Boolean {
            val ca = Calendar.getInstance().apply { timeInMillis = a }
            val cb = Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
        }
    }
}
