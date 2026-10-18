// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.util.Log

/**
 * Защита от вылетов в украшениях сообщений (кнопки, опросы, HTML-пузыри, анимации): ошибка
 * в них не должна ронять весь чат. Ошибка пишется в журнал и в «последние события»
 * диагностики (видна в отчёте), а сообщение просто показывается без украшения.
 */
object Safe {
    private val reported = HashSet<String>()

    inline fun run(where: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is OutOfMemoryError || e is StackOverflowError) throw e
            report(where, e)
        }
    }

    fun report(where: String, e: Throwable) {
        Log.e("Safe", "$where: $e", e)
        // Одна и та же ошибка не забивает журнал событий.
        val key = where + ":" + e.javaClass.name + ":" + e.message?.take(60)
        if (synchronized(reported) { reported.add(key) }) {
            runCatching { com.stoyko.smessenger.domain.Diagnostics.crumb("ошибка ($where): ${e.javaClass.simpleName}: ${e.message?.take(120)}") }
        }
    }
}
