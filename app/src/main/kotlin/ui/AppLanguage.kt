// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Язык приложения: как в системе, русский или английский (Настройки → Оформление → Язык).
 * Меняется всё: экраны, уведомления, шторка VPN.
 */
object AppLanguage {
    private const val PREFS = "ui"
    private const val KEY = "app_lang"

    /** "" - как в системе, "ru", "en". */
    fun get(ctx: Context): String = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }.getOrDefault("")

    fun set(ctx: Context, lang: String) {
        // commit: сразу после выбора приложение перезапускается.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang).commit()
    }

    /** Применить язык к конфигурации (если выбран не системный). true - что-то поменяли. */
    fun apply(ctx: Context, override: Configuration): Boolean {
        val lang = get(ctx)
        if (lang.isEmpty()) return false
        val loc = Locale(lang)
        Locale.setDefault(loc)
        override.setLocale(loc)
        override.setLayoutDirection(loc)
        return true
    }

    /** Для Application: тексты уведомлений и служб - тоже на выбранном языке. */
    fun wrap(base: Context): Context {
        val c = Configuration()
        if (!apply(base, c)) return base
        return runCatching { base.createConfigurationContext(c) }.getOrDefault(base)
    }
}
