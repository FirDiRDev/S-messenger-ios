// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * Подстройка под планшеты, раскладушки и альбомную ориентацию: сетки получают больше колонок
 * (ячейка остаётся привычного размера, а не растягивается на пол-экрана), а длинные экраны
 * (VPN, настройки) - не шире удобной для чтения ширины и по центру.
 */
object Adaptive {
    /** Ширина окна в dp (на раскладушках и в режиме двух окон - реальная ширина окна). */
    fun widthDp(ctx: Context): Int = ctx.resources.configuration.screenWidthDp.takeIf { it > 0 }
        ?: (ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density).toInt()

    fun wide(ctx: Context): Boolean = widthDp(ctx) >= 700

    /** Колонки сетки: ячейка не уже [cellDp]; на телефоне - [min], на планшете - до [max]. */
    fun columns(ctx: Context, cellDp: Int = 92, min: Int = 4, max: Int = 8, paddingDp: Int = 28): Int =
        ((widthDp(ctx) - paddingDp) / cellDp).coerceIn(min, max)

    /** Наибольшая ширина картинки в ленте чата (пикселей): на планшете - не во весь экран. */
    fun mediaWidthPx(ctx: Context, ratio: Float): Int {
        val dm = ctx.resources.displayMetrics
        return minOf((dm.widthPixels * ratio).toInt(), (460 * dm.density).toInt())
    }

    /**
     * Обернуть экран: на широком окне содержимое не шире [maxDp] и по центру (по бокам - фон
     * экрана), на телефоне возвращается как есть.
     */
    fun centered(ctx: Context, content: View, maxDp: Int = 760, bg: Int? = null): View {
        if (widthDp(ctx) <= maxDp + 40) return content
        val d = ctx.resources.displayMetrics.density
        return FrameLayout(ctx).apply {
            if (bg != null) setBackgroundColor(bg)
            addView(content, FrameLayout.LayoutParams((maxDp * d).toInt(), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL))
        }
    }
}
