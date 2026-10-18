// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import com.stoyko.smessenger.R
import java.text.DateFormat
import java.util.Date

/**
 * Отключение уведомлений чата: на 1 час, 8 часов, 1 день, 7 дней или навсегда.
 * Сообщения приходят как обычно (и помечаются непрочитанными), просто без звука и всплывания.
 */
object ChatMute {
    private const val PREFS = "chat_mute"
    private const val FOREVER = Long.MAX_VALUE

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun until(ctx: Context, id: String): Long {
        val v = prefs(ctx).getLong(id, 0L)
        if (v != 0L && v != FOREVER && v < System.currentTimeMillis()) {
            prefs(ctx).edit().remove(id).apply()
            return 0L
        }
        return v
    }

    fun isMuted(ctx: Context, id: String) = until(ctx, id) != 0L

    fun muteFor(ctx: Context, id: String, ms: Long) =
        prefs(ctx).edit().putLong(id, if (ms == FOREVER) FOREVER else System.currentTimeMillis() + ms).apply()

    fun unmute(ctx: Context, id: String) = prefs(ctx).edit().remove(id).apply()

    /** Текст состояния: «Уведомления выключены до 18:30» / «навсегда». */
    fun status(ctx: Context, id: String): String? {
        val u = until(ctx, id)
        return when (u) {
            0L -> null
            FOREVER -> ctx.getString(R.string.mute_forever_status)
            else -> ctx.getString(R.string.mute_until_status, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(u)))
        }
    }

    /** Окно выбора: на сколько выключить (или включить обратно). */
    fun showDialog(ctx: Context, id: String, done: () -> Unit = {}) {
        val hour = 3600_000L
        val options = listOf(
            R.string.mute_1h to hour,
            R.string.mute_8h to 8 * hour,
            R.string.mute_1d to 24 * hour,
            R.string.mute_7d to 7 * 24 * hour,
            R.string.mute_forever to FOREVER,
        )
        val labels = ArrayList<CharSequence>()
        if (isMuted(ctx, id)) labels += ctx.getString(R.string.mute_off)
        options.forEach { labels += ctx.getString(it.first) }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(status(ctx, id) ?: ctx.getString(R.string.mute_title))
            .setItems(labels.toTypedArray()) { _, which ->
                val shift = if (isMuted(ctx, id)) 1 else 0
                if (which < shift) unmute(ctx, id) else muteFor(ctx, id, options[which - shift].second)
                done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
