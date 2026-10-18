// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context

/**
 * Сохранение прогресса при сворачивании, выключении экрана, уходе в фон и закрытии из
 * «последних приложений»: открытые экраны (игры, S-AI, черновики) регистрируют здесь, как
 * сохранить своё состояние, а приложение вызывает это, как только окно перестаёт быть видимым.
 * Система может выгрузить приложение в фоне без предупреждения - к этому моменту всё уже записано.
 */
object BackgroundSaver {
    private val savers = LinkedHashMap<Any, () -> Unit>()

    /** [owner] - ключ (обычно сам экран); повторная регистрация заменяет прежнюю. */
    @Synchronized
    fun register(owner: Any, save: () -> Unit) {
        savers[owner] = save
    }

    @Synchronized
    fun unregister(owner: Any) {
        savers.remove(owner)
    }

    /** Сохранить всё сейчас (главный поток). */
    fun saveAll() {
        val list = synchronized(this) { savers.values.toList() }
        for (s in list) runCatching { s() }
    }

    // ---------- черновики без своей базы (группы, S-AI) ----------

    private fun drafts(ctx: Context) = ctx.applicationContext.getSharedPreferences("drafts", Context.MODE_PRIVATE)

    fun saveDraft(ctx: Context, key: String, text: String) {
        // commit: запись на диск сразу - приложение могут выгрузить сразу после сворачивания.
        drafts(ctx).edit().apply { if (text.isBlank()) remove(key) else putString(key, text) }.commit()
    }

    fun draft(ctx: Context, key: String): String = drafts(ctx).getString(key, "").orEmpty()
}
