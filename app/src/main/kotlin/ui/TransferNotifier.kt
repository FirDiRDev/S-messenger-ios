// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import com.stoyko.smessenger.R
import com.stoyko.smessenger.domain.feature.TransferMonitor

private const val TAG = "TransferNotifier"

/**
 * Прогресс передачи файлов в шторке: одно тихое уведомление на все отправки и одно - на все
 * загрузки (фото, видео, голосовые, любые файлы; личные чаты, группы и каналы).
 * Процент, «1,2 / 5,4 МБ», скорость и сколько осталось; кнопка «Отменить».
 * Закончилось - на 4 секунды «Отправлено»/«Загружено», затем убирается само.
 * Обновляется не чаще 2 раз в секунду (в экономии энергии - раз в секунду) и только пока есть передачи.
 */
object TransferNotifier {
    private const val CHANNEL = "S-messenger transfers"
    private val IDS = intArrayOf(0x7E51, 0x7E52) // 0 - отправка, 1 - загрузка
    private const val ACTION_CANCEL = "com.stoyko.smessenger.TRANSFER_CANCEL"
    private const val EXTRA_DIR = "dir"

    /** Короткие передачи (стикер, голосовое) в шторку не выводим - только те, что идут дольше. */
    private const val SHOW_AFTER_MS = 1_200L

    @Volatile
    private var app: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private val shown = BooleanArray(2)
    private val okCount = IntArray(2)
    private val okLabel = arrayOfNulls<String>(2)
    private val lastText = arrayOfNulls<String>(2)

    /** Подключить (один раз, при создании помощника уведомлений). */
    fun attach(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        runCatching {
            NotificationManagerCompat.from(a).createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(a.getString(R.string.chatcore_tr_channel))
                    .setShowBadge(false)
                    .setVibrationEnabled(false)
                    .build(),
            )
        }
        runCatching {
            ContextCompat.registerReceiver(
                a,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action != ACTION_CANCEL) return
                        val dir = intent.getIntExtra(EXTRA_DIR, -1)
                        cancelAll(dir)
                    }
                },
                IntentFilter(ACTION_CANCEL),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { Log.w(TAG, "receiver: $it") }
        TransferMonitor.onStart = { handler.post { kick() } }
        if (TransferMonitor.busy) handler.post { kick() }
    }

    private fun kick() {
        if (running) return
        running = true
        handler.postDelayed(tick, 300)
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            val live = TransferMonitor.snapshot()
            val finished = TransferMonitor.drainFinished()
            runCatching { render(ctx, live, finished) }.onFailure { Log.w(TAG, "render: $it") }
            if (live.isNotEmpty() || shown[0] || shown[1]) {
                handler.postDelayed(this, if (EnergySaver.on) 1_000 else 500)
            } else {
                running = false
            }
        }
    }

    private fun cancelAll(dir: Int) {
        val out = dir == 0
        Thread({
            TransferMonitor.snapshot().filter { it.value.outgoing == out && TransferMonitor.canCancel(it.key) }
                .keys.forEach { TransferMonitor.cancel(it) }
        }, "transfer-cancel").start()
        okCount[dir.coerceIn(0, 1)] = 0
    }

    private fun canPost(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return runCatching { NotificationManagerCompat.from(ctx).areNotificationsEnabled() }.getOrDefault(false)
    }

    /** Имя файла для шторки; в скрытых и защищённых чатах - просто «Файл». */
    private fun label(ctx: Context, l: TransferMonitor.Live): String {
        val secret = l.peer.isNotEmpty() && (
            runCatching { com.stoyko.smessenger.chat.HiddenChats.isHidden(ctx, l.peer) }.getOrDefault(false) ||
                com.stoyko.smessenger.chat.ScreenGuard.isOn(ctx, l.peer)
            )
        return if (secret || l.label.isBlank()) ctx.getString(R.string.chatcore_tr_file) else l.label
    }

    private fun render(ctx: Context, live: Map<String, TransferMonitor.Live>, finished: List<TransferMonitor.Done>) {
        val nm = NotificationManagerCompat.from(ctx)
        val allowed = canPost(ctx)
        val ru = Locale.getDefault().language == "ru"
        val now = SystemClock.elapsedRealtime()
        for (f in finished) {
            val dir = if (f.outgoing) 0 else 1
            if (f.ok && shown[dir]) {
                okCount[dir]++
                okLabel[dir] = f.label
            }
        }
        for (dir in 0..1) {
            val list = live.values.filter { it.outgoing == (dir == 0) && now - it.startedAt >= SHOW_AFTER_MS }
            if (list.isEmpty()) {
                if (live.values.none { it.outgoing == (dir == 0) }) finishDir(ctx, nm, dir, allowed)
                continue
            }
            if (!allowed) continue
            val total = list.sumOf { it.total.coerceAtLeast(0) }
            val done = list.sumOf { it.done.coerceAtLeast(0) }
            val single = list.singleOrNull()
            val title = if (single != null) {
                ctx.getString(if (dir == 0) R.string.chatcore_tr_sending else R.string.chatcore_tr_receiving, label(ctx, single))
            } else {
                ctx.getString(if (dir == 0) R.string.chatcore_tr_sending_many else R.string.chatcore_tr_receiving_many, list.size)
            }
            val text = if (single != null) {
                TransferMonitor.describe(single, ru)
            } else {
                val agg = TransferMonitor.Live(total, dir == 0).apply {
                    this.done = done
                    speed = list.sumOf { it.speed }
                }
                TransferMonitor.describe(agg, ru)
            }
            val pct = if (total > 0) (done * 1000 / total).toInt().coerceIn(0, 1000) else 0
            val key = "$title|$text|$pct"
            if (key == lastText[dir] && shown[dir]) continue
            lastText[dir] = key
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(if (dir == 0) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setProgress(1000, pct, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            if (single == null) {
                val style = NotificationCompat.InboxStyle()
                list.sortedByDescending { it.total }.take(5).forEach { l ->
                    val p = if (l.total > 0) (l.done * 100 / l.total).coerceIn(0, 100) else 0
                    style.addLine("${label(ctx, l)} — $p%")
                }
                if (list.size > 5) style.setSummaryText("+${list.size - 5}")
                b.setStyle(style)
            }
            openApp(ctx)?.let { b.setContentIntent(it) }
            val keys = live.filter { it.value.outgoing == (dir == 0) }.keys
            if (keys.any { TransferMonitor.canCancel(it) }) {
                val cancel = PendingIntent.getBroadcast(
                    ctx,
                    IDS[dir],
                    Intent(ACTION_CANCEL).setPackage(ctx.packageName).putExtra(EXTRA_DIR, dir),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.addAction(0, ctx.getString(R.string.chatcore_tr_cancel), cancel)
            }
            runCatching { nm.notify(IDS[dir], b.build()) }.onFailure { Log.w(TAG, "notify: $it") }
            shown[dir] = true
        }
    }

    /** Все передачи в эту сторону закончились: итог на 4 секунды (если что-то дошло) или убрать. */
    private fun finishDir(ctx: Context, nm: NotificationManagerCompat, dir: Int, allowed: Boolean) {
        if (!shown[dir]) return
        shown[dir] = false
        lastText[dir] = null
        val n = okCount[dir]
        val name = okLabel[dir]
        okCount[dir] = 0
        okLabel[dir] = null
        if (n <= 0 || !allowed) {
            runCatching { nm.cancel(IDS[dir]) }
            return
        }
        val title = if (n == 1) {
            ctx.getString(
                if (dir == 0) R.string.chatcore_tr_sent else R.string.chatcore_tr_received,
                name?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.chatcore_tr_file),
            )
        } else {
            ctx.getString(if (dir == 0) R.string.chatcore_tr_sent_many else R.string.chatcore_tr_received_many, n)
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(if (dir == 0) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setSilent(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter(4_000)
        openApp(ctx)?.let { b.setContentIntent(it) }
        runCatching { nm.notify(IDS[dir], b.build()) }
        // До Android 8 таймаута у уведомлений нет - убираем сами.
        val id = IDS[dir]
        handler.postDelayed({ if (!shown[dir]) runCatching { nm.cancel(id) } }, 4_000)
    }

    private fun openApp(ctx: Context): PendingIntent? = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        PendingIntent.getActivity(ctx, 0x7E50, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }.getOrNull()
}
