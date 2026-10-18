// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.hypot
import kotlin.math.max

/**
 * «Эффект воды» при нажатии на пункты меню: от пальца по пункту расходятся мягкие круги,
 * как по воде, - два-три кольца с «бликом», которые плавно гаснут. Выключается в
 * Настройках → Персонализация.
 */
object WaterRipple {
    private const val PREFS = "ui"
    private const val KEY = "water_fx"

    fun isOn(ctx: Context) = runCatching {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)
    }.getOrDefault(true)

    fun set(ctx: Context, on: Boolean) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()

    /** Фон пункта меню (у каждого пункта - своя копия). */
    fun drawable(ctx: Context, color: Int = 0xFF5B4BDB.toInt()): Drawable = WaterDrawable(ctx.resources.displayMetrics.density, color)

    private class WaterDrawable(private val d: Float, private val color: Int) : Drawable() {
        private var hx = -1f
        private var hy = -1f
        private var start = -1L
        private var pressed = false
        private var checked = false
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val check = Paint(Paint.ANTI_ALIAS_FLAG)
        private val clip = Path()
        private val rect = RectF()
        private val tick = Runnable { invalidateSelf() }

        override fun isStateful() = true

        override fun setHotspot(x: Float, y: Float) {
            hx = x
            hy = y
        }

        override fun onStateChange(state: IntArray): Boolean {
            val p = state.contains(android.R.attr.state_pressed)
            val c = state.contains(android.R.attr.state_checked)
            var changed = false
            if (p && !pressed && Kinetics.enabled && !EnergySaver.on) {
                start = SystemClock.uptimeMillis()
                changed = true
            }
            if (c != checked) changed = true
            pressed = p
            checked = c
            if (changed) invalidateSelf()
            return changed
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.isEmpty) return
            val r = 14 * d
            rect.set(b.left + 6 * d, b.top + 2 * d, b.right - 6 * d, b.bottom - 2 * d)
            if (checked) {
                check.color = (color and 0x00FFFFFF) or 0x1F000000
                canvas.drawRoundRect(rect, r, r, check)
            }
            if (start < 0) return
            val t = (SystemClock.uptimeMillis() - start) / 900f
            if (t >= 1f) {
                start = -1
                return
            }
            val cx = if (hx >= 0) hx else b.exactCenterX()
            val cy = if (hy >= 0) hy else b.exactCenterY()
            val maxR = max(hypot(cx - b.left, cy - b.top), hypot(b.right - cx, b.bottom - cy)) * 1.1f
            val save = canvas.save()
            clip.reset()
            clip.addRoundRect(rect, r, r, Path.Direction.CW)
            canvas.clipPath(clip)
            // Мягкая «лужица» под пальцем.
            val fr = maxR * ease(t.coerceAtMost(0.6f) / 0.6f)
            fill.shader = RadialGradient(
                cx, cy, fr.coerceAtLeast(1f),
                intArrayOf((color and 0x00FFFFFF) or (((1f - t) * 0x30).toInt() shl 24), color and 0x00FFFFFF),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, fr.coerceAtLeast(1f), fill)
            // Три кольца-волны одно за другим.
            for (i in 0 until 3) {
                val k = t - i * 0.16f
                if (k <= 0f) continue
                val e = ease(k.coerceAtMost(1f))
                ring.strokeWidth = (3.2f - i * 0.8f) * d * (1f - e * 0.6f)
                ring.color = color
                ring.alpha = ((1f - k).coerceIn(0f, 1f) * (150 - i * 35)).toInt()
                canvas.drawCircle(cx, cy, maxR * e, ring)
            }
            canvas.restoreToCount(save)
            scheduleSelf(tick, SystemClock.uptimeMillis() + 16)
        }

        private fun ease(x: Float): Float = 1f - (1f - x) * (1f - x) * (1f - x)

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        override fun getConstantState(): ConstantState = State(d, color)

        private class State(val d: Float, val color: Int) : ConstantState() {
            override fun newDrawable(): Drawable = WaterDrawable(d, color)
            override fun getChangingConfigurations() = 0
        }
    }
}
