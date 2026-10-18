// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.stoyko.smessenger.R

/**
 * Персонализация чатов (Настройки → Персонализация): фон, форма пузырей, анимация набора,
 * автопроигрывание голосовых, превью ссылок, кнопка «вниз».
 */
class ChatLook(context: Context) {
    private val prefs = context.getSharedPreferences("chat_look", Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density

    var background: Int
        get() = prefs.getInt("bg", 0)
        set(v) = prefs.edit().putInt("bg", v).apply()

    var bubbleRadius: Int
        get() = prefs.getInt("radius", 18)
        set(v) = prefs.edit().putInt("radius", v).apply()

    var typingAnimation: Boolean
        get() = prefs.getBoolean("typing_anim", true)
        set(v) = prefs.edit().putBoolean("typing_anim", v).apply()

    var autoplayVoices: Boolean
        get() = prefs.getBoolean("autoplay_voices", true)
        set(v) = prefs.edit().putBoolean("autoplay_voices", v).apply()

    var linkPreviews: Boolean
        get() = prefs.getBoolean("link_previews", true)
        set(v) = prefs.edit().putBoolean("link_previews", v).apply()

    /** Анимированные эмодзи и стикеры (Noto Animated Emoji, грузятся по сети). */
    var animatedEmoji: Boolean
        get() = prefs.getBoolean("animated_emoji", true)
        set(v) = prefs.edit().putBoolean("animated_emoji", v).apply()

    var scrollDownButton: Boolean
        get() = prefs.getBoolean("scroll_down", true)
        set(v) = prefs.edit().putBoolean("scroll_down", v).apply()

    /** Фон под сообщениями (null - как в теме). */
    fun backgroundDrawable(): Drawable? = when (background) {
        1 -> gradient(0xFF2E2A6B.toInt(), 0xFF5B4BDB.toInt(), 0xFF00C2A8.toInt())
        2 -> gradient(0xFF003B46.toInt(), 0xFF07575B.toInt(), 0xFF66A5AD.toInt())
        3 -> gradient(0xFF0B1026.toInt(), 0xFF1B2A4A.toInt(), 0xFF2D1B4E.toInt())
        4 -> gradient(0xFFFF7E5F.toInt(), 0xFFFEB47B.toInt(), 0xFF86A8E7.toInt())
        5 -> gradient(0xFF134E5E.toInt(), 0xFF71B280.toInt(), 0xFF134E5E.toInt())
        6 -> pattern(0xFF1E1B3A.toInt(), 0x22FFFFFF)
        7 -> pattern(0xFFEFF3F8.toInt(), 0x225B4BDB)
        else -> null
    }

    fun applyBackground(view: View) {
        backgroundDrawable()?.let { view.background = it }
    }

    /** Скругление пузыря (с «хвостиком»: у своих - справа сверху, у чужих - слева сверху). */
    fun shapeBubble(view: View, outgoing: Boolean) {
        val g = view.background?.mutate() as? GradientDrawable ?: return
        val r = bubbleRadius * density
        val s = minOf(r, 4 * density)
        g.cornerRadii = if (outgoing) {
            floatArrayOf(r, r, s, s, r, r, r, r)
        } else {
            floatArrayOf(s, s, r, r, r, r, r, r)
        }
        com.stoyko.smessenger.ui.SeasonTint.bubble(view, outgoing)
    }

    private fun gradient(a: Int, b: Int, c: Int) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b, c))

    /** Спокойный узор из точек. */
    private fun pattern(bg: Int, dot: Int): Drawable {
        val size = (28 * density).toInt().coerceAtLeast(8)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot }
        c.drawCircle(size * 0.25f, size * 0.25f, 2 * density, p)
        c.drawCircle(size * 0.75f, size * 0.75f, 1.5f * density, p)
        return BitmapDrawable(null as android.content.res.Resources?, bmp).apply {
            setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }

    companion object {
        val BACKGROUND_NAMES = intArrayOf(
            R.string.bg_default, R.string.bg_violet, R.string.bg_ocean, R.string.bg_night,
            R.string.bg_sunset, R.string.bg_forest, R.string.bg_pattern_dark, R.string.bg_pattern_light,
        )
    }
}
