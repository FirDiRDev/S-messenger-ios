// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.stoyko.smessenger.MainActivity
import com.stoyko.smessenger.R

/**
 * Фоновые задачи VPN (подбор стратегии обхода DPI, подбор сервера под узлы) - с полосой
 * прогресса в шторке. Окно можно закрыть: подбор идёт дальше, в шторке видно, на каком он шаге,
 * а кнопка «Остановить» завершает его и применяет лучшее из уже найденного.
 */
object BgJobs {
    const val DPI = 7_331
    const val NODES = 7_332
    private const val CHANNEL = "bg_jobs"
    const val ACTION_STOP = "com.stoyko.smessenger.BG_JOB_STOP"
    const val EXTRA_ID = "job"

    private val cancels = ConcurrentHashMap<Int, AtomicBoolean>()
    private val lastPost = ConcurrentHashMap<Int, Long>()
    private val main = Handler(Looper.getMainLooper())

    /** Сведения о ходе задачи для экрана VPN (если окно открыто заново). */
    data class State(val title: String, val text: String, val done: Int, val total: Int, val finished: Boolean)

    private val states = ConcurrentHashMap<Int, State>()
    private val listeners = LinkedHashSet<() -> Unit>()

    fun state(id: Int): State? = states[id]
    fun isRunning(id: Int) = states[id]?.finished == false

    fun listen(l: () -> Unit) = main.post { listeners += l }
    fun unlisten(l: () -> Unit) = main.post { listeners -= l }
    private fun changed() = main.post { listeners.toList().forEach { runCatching { it() } } }

    /** Что сделать сразу при отмене (закрыть сокеты, остановить процесс) - выполняется в фоне. */
    private val hooks = ConcurrentHashMap<Int, () -> Unit>()

    /**
     * Флаг отмены задачи (его ставит кнопка «Остановить» в шторке и на экране VPN).
     * [onCancel] - прервать то, что сейчас идёт (сокеты, процессы), не дожидаясь их тайм-аутов.
     */
    fun begin(id: Int, onCancel: (() -> Unit)? = null): AtomicBoolean = AtomicBoolean(false).also {
        cancels[id] = it
        if (onCancel != null) hooks[id] = onCancel else hooks.remove(id)
    }

    /** Отменить без контекста (только флаг и прерывание). Лучше - [cancel] с контекстом. */
    fun cancel(id: Int) {
        val flag = cancels[id] ?: return
        if (flag.getAndSet(true)) return
        hooks.remove(id)?.let { h -> Thread({ runCatching { h() } }, "bg-job-cancel").apply { isDaemon = true }.start() }
    }

    /**
     * Остановить задачу: флаг отмены, прерывание идущих проверок (в фоне - главный поток не ждёт),
     * и сразу - «Останавливается…» на экране и снятие полосы из шторки. Итог задача покажет сама.
     * Повторный вызов ничего не ломает.
     */
    fun cancel(ctx: Context, id: Int) {
        cancel(id)
        val st = states[id]
        if (st != null && !st.finished) {
            states[id] = st.copy(text = ctx.getString(R.string.bg_jobs_stopping), finished = true)
            changed()
        }
        runCatching { channel(ctx.applicationContext).cancel(id) }
    }

    /** Эта задача - всё ещё текущая и не отменена (иначе её поздние сообщения не показываем). */
    private fun current(id: Int, token: AtomicBoolean?): Boolean =
        if (token == null) cancels[id]?.get() != true else !token.get() && cancels[id] === token

    private fun channel(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.bg_jobs_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        return nm
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Обновить полосу прогресса (не чаще 3 раз в секунду - шторка не любит частые обновления). */
    fun progress(ctx: Context, id: Int, title: String, text: String, done: Int, total: Int, token: AtomicBoolean? = null) {
        // После «Остановить» запоздавшие проверки не должны снова показывать полосу и «идёт».
        if (!current(id, token)) return
        states[id] = State(title, text, done, total, false)
        changed()
        val now = SystemClock.elapsedRealtime()
        if (now - (lastPost[id] ?: 0L) < 330 && done < total) return
        lastPost[id] = now
        val app = ctx.applicationContext
        runCatching {
            val stop = PendingIntent.getBroadcast(
                app, id, Intent(app, BgJobReceiver::class.java).setAction(ACTION_STOP).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setSubText("$done / $total")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setProgress(total.coerceAtLeast(1), done.coerceIn(0, total.coerceAtLeast(1)), total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openApp(app))
                .addAction(0, app.getString(R.string.bg_jobs_stop), stop)
                .build()
            channel(app).notify(id, n)
            // Остановили, пока строили уведомление - убрать его, чтобы полоса не «висела».
            if (!current(id, token)) channel(app).cancel(id)
        }
    }

    /** Уже запущена новая задача с тем же номером - итог старой не показываем. */
    private fun stale(id: Int, token: AtomicBoolean?): Boolean = token != null && cancels[id].let { it != null && it !== token }

    /** Задача отменена пользователем: убрать из шторки и из состояния. */
    fun clear(ctx: Context, id: Int, token: AtomicBoolean? = null) {
        if (stale(id, token)) return
        states.remove(id)
        cancels.remove(id)
        hooks.remove(id)
        changed()
        runCatching { channel(ctx.applicationContext).cancel(id) }
    }

    /** Задача закончилась: итог в шторке (уже без полосы). */
    fun finish(ctx: Context, id: Int, title: String, text: String, token: AtomicBoolean? = null) {
        if (stale(id, token)) return
        states[id] = State(title, text, 1, 1, true)
        cancels.remove(id)
        hooks.remove(id)
        changed()
        val app = ctx.applicationContext
        runCatching {
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openApp(app))
                .setAutoCancel(true)
                .build()
            channel(app).notify(id, n)
        }
    }
}

/** Получатель кнопки «Остановить» в шторке (объявлен в манифесте). */
class BgJobReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BgJobs.ACTION_STOP) BgJobs.cancel(context, intent.getIntExtra(BgJobs.EXTRA_ID, 0))
    }
}
