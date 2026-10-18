// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Число непрочитанных на значке приложения.
 *
 * Android сам показывает число/точку по уведомлениям (setNumber), но многие оболочки берут его
 * по-своему - поэтому дополнительно сообщаем число напрямую:
 *  - Xiaomi / Redmi / POCO (MIUI, HyperOS) - поле extraNotification.messageCount у уведомления;
 *  - Huawei / Honor (EMUI, MagicOS) - провайдер лаунчера change_badge;
 *  - Samsung (старые One UI), LG, Sony и др. - широковещательное BADGE_COUNT_UPDATE.
 * Счётчики по чатам хранятся здесь же: открыли чат - его число уходит из общего.
 */
object AppBadge {
    private const val TAG = "AppBadge"
    private const val PREFS = "badge_counts"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** +1 непрочитанное в чате; возвращает число непрочитанных этого чата. */
    fun bump(ctx: Context, key: String): Int {
        val p = prefs(ctx)
        val n = p.getInt(key, 0) + 1
        p.edit().putInt(key, n).apply()
        apply(ctx, total(ctx, extraKey = key, extraValue = n))
        return n
    }

    fun clear(ctx: Context, key: String) {
        val p = prefs(ctx)
        if (!p.contains(key)) return
        p.edit().remove(key).apply()
        apply(ctx, total(ctx, skipKey = key))
    }

    fun total(ctx: Context, extraKey: String? = null, extraValue: Int = 0, skipKey: String? = null): Int =
        prefs(ctx).all.entries.sumOf { (k, v) ->
            when (k) {
                skipKey -> 0
                extraKey -> extraValue
                else -> (v as? Int) ?: 0
            }
        }

    /** Число для MIUI - кладётся в само уведомление перед показом. */
    fun decorate(notification: Notification, count: Int) {
        // Только Xiaomi до Android 14: на HyperOS/Android 14+ скрытое поле закрыто системой -
        // попытка давала ошибку «hiddenapi … extraNotification … denied» в журнале (4.10.0).
        // Там число и так берётся из setNumber уведомления.
        if (!isXiaomi || android.os.Build.VERSION.SDK_INT >= 34) return
        runCatching {
            val field = notification.javaClass.getDeclaredField("extraNotification")
            val extra = field.get(notification) ?: return
            extra.javaClass.getDeclaredMethod("setMessageCount", Int::class.javaPrimitiveType).invoke(extra, count)
        }
    }

    private val maker get() = (android.os.Build.MANUFACTURER ?: "").lowercase() + " " + (android.os.Build.BRAND ?: "").lowercase()
    private val isXiaomi get() = maker.let { "xiaomi" in it || "redmi" in it || "poco" in it }
    private val isHuawei get() = maker.let { "huawei" in it || "honor" in it }

    /** Сообщить число лаунчерам Huawei/Honor и Samsung/LG/Sony (на других ничего не делает). */
    fun apply(ctx: Context, count: Int) {
        val launcher = runCatching {
            ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component?.className
        }.getOrNull() ?: return
        // Huawei / Honor - только на них (на других провайдера нет: «Failed to find provider info»).
        if (isHuawei) runCatching {
            val extra = Bundle().apply {
                putString("package", ctx.packageName)
                putString("class", launcher)
                putInt("badgenumber", count)
            }
            ctx.contentResolver.call(
                android.net.Uri.parse("content://com.huawei.android.launcher.settings/badge/"),
                "change_badge",
                null,
                extra,
            )
        }
        // Samsung (старые), LG, HTC, Sony и совместимые лаунчеры
        runCatching {
            ctx.sendBroadcast(
                Intent("android.intent.action.BADGE_COUNT_UPDATE")
                    .putExtra("badge_count", count)
                    .putExtra("badge_count_package_name", ctx.packageName)
                    .putExtra("badge_count_class_name", launcher),
            )
        }.onFailure { Log.d(TAG, "badge: $it") }
    }
}
