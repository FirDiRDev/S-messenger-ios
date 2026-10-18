// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.content.res.Configuration

/**
 * Подстройка под экран телефона (Xiaomi/POCO/Redmi, Huawei/Honor, OnePlus, Samsung и др.).
 *
 * - Очень крупный системный шрифт (на Xiaomi и Huawei бывает 1.5-2.0) ломал кнопки и окна:
 *   ограничиваем масштаб шрифта 1.3 - текст всё равно крупный, но вёрстка не разваливается.
 * - Узкие экраны и «крупный масштаб интерфейса» (меньше 360 dp по короткой стороне): слегка
 *   уменьшаем плотность, чтобы экран был не уже 360 dp - под эту ширину рассчитаны все окна.
 *
 * Меняются только масштаб шрифта и плотность; всё остальное - как в системе.
 */
object ScreenFit {
    private const val MAX_FONT_SCALE = 1.3f
    private const val MIN_WIDTH_DP = 360

    private const val PREFS = "screen_fit"

    /** Масштаб интерфейса из настроек: 0 - авто, иначе 0.85…1.2. */
    fun userScale(ctx: Context): Float = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("scale", 0f)
    }.getOrDefault(0f)

    fun setUserScale(ctx: Context, v: Float) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("scale", v).apply()

    val SCALES = floatArrayOf(0f, 0.85f, 0.9f, 1f, 1.1f, 1.2f)

    fun wrap(base: Context): Context {
        val cur = base.resources.configuration
        // Предел шрифта - по модели: на бюджетных 720p и «раскладушках» ниже, на планшетах выше.
        val maxFont = runCatching { DeviceProfile.maxFontScale(base) }.getOrDefault(MAX_FONT_SCALE)
        val override = Configuration()
        var changed = false
        val user = userScale(base)
        // На старых Android пустая Configuration() содержит fontScale = 1 - задаём его всегда явно.
        override.fontScale = if (cur.fontScale > 0f) minOf(cur.fontScale, maxFont) else 1f
        if (cur.fontScale > maxFont) changed = true
        // Режим для слабовидящих: шрифт крупнее, но в пределах MAX_FONT_SCALE - иначе подписи
        // не помещаются в кнопки и шапку. Если системный шрифт уже крупный - оставляем его.
        val a11y = Accessibility.isOn(base)
        if (a11y) {
            // Уровень («Крупный» / «Очень крупный» / «Максимальный») и предел - по классу устройства.
            val a11yMax = runCatching { Accessibility.maxFontScale(base) }.getOrDefault(maxFont)
            val a11yScale = runCatching { Accessibility.fontScale(base) }.getOrDefault(Accessibility.FONT_SCALE)
            override.fontScale = minOf(maxOf(override.fontScale, a11yScale), maxOf(maxFont, a11yMax))
            // Жирнее (Android 12+): читается лучше, а места текст занимает почти столько же.
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val curW = cur.fontWeightAdjustment
                override.fontWeightAdjustment =
                    if (curW in 1..1000) maxOf(curW, Accessibility.FONT_WEIGHT_BOOST) else Accessibility.FONT_WEIGHT_BOOST
            }
            changed = true
        }
        val sw = cur.smallestScreenWidthDp
        if (user > 0f && cur.densityDpi > 0) {
            // Свой масштаб из настроек: «больше» - крупнее элементы, «меньше» - больше помещается.
            override.densityDpi = (cur.densityDpi * user).toInt().coerceIn(120, 640)
            changed = true
        } else if (sw in 200 until MIN_WIDTH_DP && cur.densityDpi > 0) {
            override.densityDpi = (cur.densityDpi * sw / MIN_WIDTH_DP).coerceAtLeast(120)
            changed = true
        } else if (cur.densityDpi > 0 && runCatching { DeviceProfile.densityBoost(base) }.getOrDefault(1f) > 1f) {
            // Планшеты и складные (по модели и экрану): элементы чуть крупнее, чтобы интерфейс не казался «мелким».
            override.densityDpi = (cur.densityDpi * DeviceProfile.densityBoost(base)).toInt()
            changed = true
        }
        // Экран не должен становиться уже 360 dp (под эту ширину свёрстаны все окна): иначе
        // при «крупном» масштабе шапка, профиль и кнопки чатов уезжают за край.
        if (override.densityDpi > 0 && sw >= MIN_WIDTH_DP && cur.densityDpi > 0) {
            val maxDpi = cur.densityDpi * sw / MIN_WIDTH_DP
            if (override.densityDpi > maxDpi) override.densityDpi = maxDpi
        }
        // Язык приложения (Настройки → Оформление → Язык).
        if (AppLanguage.apply(base, override)) changed = true
        return if (changed) runCatching { base.createConfigurationContext(override) }.getOrDefault(base) else base
    }
}
