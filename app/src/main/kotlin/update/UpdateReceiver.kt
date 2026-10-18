// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Проверка обновлений по системному будильнику - раз в 3 часа, даже если приложение закрыто
 * и фоновая служба выгружена системой. Нашлась новая версия - уведомление в шторке и на экране
 * блокировки (с кнопками «Установить» и «Напомнить позже»); пока не обновитесь - напоминание
 * повторяется.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            // Ждём оплату пака монет: проверка чеков по будильнику (см. CoinShop.scheduleAlarm).
            com.stoyko.smessenger.chat.CoinShop.ACTION_POLL -> {
                val pending = goAsync()
                runCatching { com.stoyko.smessenger.chat.CoinShop.backgroundCheck(app) { runCatching { pending.finish() } } }
                    .onFailure { runCatching { pending.finish() } }
            }
            ACTION_CHECK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                schedule(app)
                // После перезагрузки будильники сброшены - вернуть проверку незавершённой покупки.
                runCatching { com.stoyko.smessenger.chat.CoinShop.scheduleAlarm(app) }
                val pending = goAsync()
                Updater.backgroundCheck(app, fromAlarm = true) { runCatching { pending.finish() } }
            }
            ACTION_LATER -> Updater.snoozeNotification(app)
        }
    }

    companion object {
        const val ACTION_CHECK = "com.stoyko.smessenger.UPDATE_CHECK"
        const val ACTION_LATER = "com.stoyko.smessenger.UPDATE_LATER"
        private const val INTERVAL_MS = 3 * AlarmManager.INTERVAL_HOUR

        /** Ставит повторяющуюся проверку (неточный будильник: бережёт батарею). */
        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pi = PendingIntent.getBroadcast(
                ctx,
                0x5E22,
                Intent(ctx, UpdateReceiver::class.java).setAction(ACTION_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            runCatching {
                am.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 20 * 60_000L,
                    INTERVAL_MS,
                    pi,
                )
            }
        }
    }
}
