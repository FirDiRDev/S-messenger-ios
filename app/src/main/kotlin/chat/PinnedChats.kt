// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context

/**
 * Закреплённые чаты, группы и каналы - без ограничения количества. Закреплённые всегда сверху списка,
 * в порядке закрепления (последний закреплённый - выше).
 */
object PinnedChats {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("pinned_chats", Context.MODE_PRIVATE)

    /** id -> время закрепления. */
    fun all(ctx: Context): Map<String, Long> = prefs(ctx).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    fun isPinned(ctx: Context, id: String) = prefs(ctx).contains(id)

    fun toggle(ctx: Context, id: String): Boolean {
        val p = prefs(ctx)
        return if (p.contains(id)) {
            p.edit().remove(id).apply()
            false
        } else {
            p.edit().putLong(id, System.currentTimeMillis()).apply()
            true
        }
    }

    /** Закреплённые - первыми (свежезакреплённые выше), остальные - в прежнем порядке. */
    fun <T> sort(ctx: Context, items: List<T>, id: (T) -> String): List<T> {
        val pins = all(ctx)
        if (pins.isEmpty()) return items
        val (pinned, rest) = items.partition { id(it) in pins }
        return pinned.sortedByDescending { pins[id(it)] ?: 0L } + rest
    }
}
