// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context

/**
 * Отдельные анимации, которые можно выключить на слабых телефонах (Настройки → Оформление →
 * «Анимации по отдельности»). Общий выключатель анимаций и облегчённый режим работают как
 * раньше; здесь - тонкая настройка: оставить красивое и убрать то, что тормозит.
 */
object AnimPrefs {
    enum class K(val key: String, val ru: String, val en: String, val hintRu: String, val hintEn: String) {
        MESSAGES("a_msg", "Появление сообщений", "Message appear", "Своё вылетает из поля ввода, входящее «надувается»", "Yours flies from the input, incoming inflates"),
        SCREENS("a_screens", "Переходы между экранами", "Screen transitions", "Аватарка и имя перелетают в чат, фон раскрывается кругом", "Avatar and name fly into the chat, circular reveal"),
        LISTS("a_lists", "Появление списков лесенкой", "Staggered lists", "Строки и карточки появляются по очереди", "Rows and cards appear one by one"),
        LIVE("a_live", "Автозапуск живых сообщений", "Autoplay live messages", "Новое живое сообщение «/» оживает само", "The newest live “/” message starts by itself"),
        SPARKS("a_sparks", "Искры спойлеров", "Spoiler sparkles", "Мерцание искр на скрытых фото, видео и тексте", "Twinkling sparkles on hidden photos, videos and text"),
    }

    private const val PREFS = "ui"
    private val cache = HashMap<K, Boolean>()
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Слабый телефон (определяется по модели): тяжёлые эффекты по умолчанию выключены.
        val weak = runCatching { DeviceProfile.lite(ctx) }.getOrDefault(false)
        synchronized(cache) {
            K.entries.forEach { k ->
                val def = !(weak && (k == K.LIVE || k == K.SPARKS || k == K.LISTS))
                cache[k] = p.getBoolean(k.key, def)
            }
        }
        loaded = true
    }

    /** Включена ли анимация (по умолчанию - да). До загрузки настроек - тоже да. */
    fun on(k: K): Boolean = if (!loaded) true else synchronized(cache) { cache[k] ?: true }

    fun set(ctx: Context, k: K, v: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(k.key, v).apply()
        synchronized(cache) { cache[k] = v }
        loaded = true
    }

    fun title(k: K) = if (java.util.Locale.getDefault().language == "ru") k.ru else k.en
    fun hint(k: K) = if (java.util.Locale.getDefault().language == "ru") k.hintRu else k.hintEn
}
