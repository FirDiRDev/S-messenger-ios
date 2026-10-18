// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context

/**
 * Архив чатов: архивные чаты, группы и каналы убираются из основного списка
 * и лежат в разделе «Архив», который всегда виден первой строкой списка.
 * Хранится только на этом устройстве. Ключ - публичный ключ контакта или ID группы.
 */
object Archive {
    private const val KEY = "ids"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("chat_archive", Context.MODE_PRIVATE)

    fun ids(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isArchived(ctx: Context, id: String) = id in ids(ctx)

    fun set(ctx: Context, id: String, archived: Boolean) {
        val now = ids(ctx).toMutableSet()
        if (archived) now.add(id) else now.remove(id)
        prefs(ctx).edit().putStringSet(KEY, now).apply()
    }
}
