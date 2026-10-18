// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R

/**
 * Достижения ровными «плитками» одной ширины: значок, число крупно, подпись мелко.
 * Раньше всё шло одной строкой через «·» и переносилось как попало.
 */
object StatChips {
    class Item(val icon: String, val value: String, val label: String)

    /**
     * Плитки рядами по [cols] штук: все одной ширины, в каждом ряду - одной высоты
     * (подпись в 1-2 строки не делает соседей ниже/выше). Неполный последний ряд
     * добивается пустыми местами, чтобы плитки не растягивались.
     */
    fun build(ctx: Context, items: List<Item>, tint: Int = 0x1A5B4BDB, cols: Int = 3): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val perRow = cols.coerceAtLeast(1)
        fun chip(it: Item) = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(tint)
            }
            addView(TextView(ctx).apply {
                text = it.icon
                textSize = 18f
                gravity = Gravity.CENTER
            })
            addView(TextView(ctx).apply {
                text = it.value
                textSize = 16f
                gravity = Gravity.CENTER
                maxLines = 1
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(primary)
                androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
            addView(TextView(ctx).apply {
                text = it.label
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(secondary)
            })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            items.chunked(perRow).forEachIndexed { r, rowItems ->
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    // Плитки ряда - по самой высокой (MATCH_PARENT по высоте внутри WRAP_CONTENT ряда).
                    isBaselineAligned = false
                }
                for (i in 0 until perRow) {
                    val v = rowItems.getOrNull(i)?.let { chip(it) } ?: android.view.View(ctx)
                    row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(6) })
                }
                addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (r > 0) topMargin = dp(6) })
            }
        }
    }

    /** Плитки по строке рекордов «s:рекорд:уровень;c:победы;d:победы;m:очки:уровни:звёзды;r:рекорд» и числу монет. */
    fun fromStats(ctx: Context, coins: Int, games: String): List<Item> {
        val snake = Regex("s:(\\d+):(\\d+)").find(games)
        val chess = Regex("c:(\\d+)").find(games)
        val durak = Regex("d:(\\d+)").find(games)
        val list = ArrayList<Item>()
        if (coins >= 0) list += Item("🪙", coins.toString(), ctx.getString(R.string.stat_coins))
        snake?.let { list += Item("🐍", it.groupValues[1], ctx.getString(R.string.stat_snake, it.groupValues[2].toInt())) }
        chess?.let { list += Item("♞", it.groupValues[1], ctx.getString(R.string.stat_chess)) }
        durak?.let { list += Item("🃏", it.groupValues[1], ctx.getString(R.string.stat_durak)) }
        Regex("m:(\\d+):(\\d+):(\\d+)").find(games)?.let {
            list += Item("💎", it.groupValues[1], ctx.getString(R.string.stat_match3, it.groupValues[2].toIntOrNull() ?: 1, it.groupValues[3].toIntOrNull() ?: 0))
        }
        Regex("r:(\\d+)").find(games)?.let { list += Item("🏃", it.groupValues[1], ctx.getString(R.string.stat_runner)) }
        return list
    }
}
