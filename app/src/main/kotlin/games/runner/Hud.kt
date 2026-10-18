// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.stoyko.smessenger.R
import kotlin.math.ceil
import kotlin.math.sin

/**
 * Поверх 3D: очки, монеты забега, множитель, таймеры усилений, отсчёт 3-2-1 и «Вперёд!»,
 * всплывающие надписи, «!» над путём встречного поезда, красная рамка, когда обходчик рядом,
 * вспышка при ударе. Читает состояние мира (только чтение чисел - без блокировок).
 */
@SuppressLint("ViewConstructor")
internal class HudView(ctx: Context, private val world: World, private val renderer: RunnerRenderer) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(4 * d, 0f, 1.5f * d, 0xAA000000.toInt())
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private var vignette: Shader? = null
    private var popText = ""
    private var popAt = 0L
    private var popColor = Color.WHITE
    private var goAt = 0L
    private var lastState = -1

    /** Верхний отступ (под шапкой с кнопками). */
    var topInset = 0f

    private val powerIcon = arrayOf("🧲", "🚀", "👟", "✖2", "🛹", "🛡", "💰", "⏳")
    private val powerColor = intArrayOf(
        0xFFF43F5E.toInt(), 0xFFF59E0B.toInt(), 0xFF22C55E.toInt(), 0xFFA855F7.toInt(), 0xFF38BDF8.toInt(),
        0xFF67E8F9.toInt(), 0xFFFACC15.toInt(), 0xFF818CF8.toInt(),
    )

    fun pop(s: String, color: Int = Color.WHITE) {
        popText = s
        popColor = color
        popAt = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vignette = RadialGradient(w / 2f, h / 2f, maxOf(w, h) * 0.62f, intArrayOf(0x00000000, 0x00000000, 0xCCDC2626.toInt()), floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val st = world.state
        val now = SystemClock.uptimeMillis()
        if (st == World.RUN && lastState == World.COUNT) goAt = now
        lastState = st
        val w = width.toFloat()
        val h = height.toFloat()
        val live = st == World.COUNT || st == World.RUN || st == World.DYING
        if (st != World.MENU && st != World.OVER) {
            drawStats(c, w)
            if (st == World.RUN || st == World.PAUSE) drawPowers(c)
        }
        if (st == World.RUN) {
            drawWarnings(c, w, h, now)
            if (world.stumbleT > 0f) {
                val a = (world.stumbleT / 6.5f).coerceIn(0f, 1f) * (0.55f + 0.45f * sin(now / 120f))
                fill.shader = vignette
                fill.alpha = (a * 255).toInt().coerceIn(0, 255)
                c.drawRect(0f, 0f, w, h, fill)
                fill.shader = null
                fill.alpha = 255
            }
        }
        if (st == World.COUNT) {
            val t = world.countT
            val n = ceil(t).toInt().coerceIn(1, 3)
            val frac = t - (n - 1)
            val sc = 1f + (frac - 0.6f).coerceAtLeast(0f) * 1.2f
            text.textAlign = Paint.Align.CENTER
            text.textSize = 96 * d * sc
            text.color = 0xFFFDE047.toInt()
            text.alpha = (255 * frac.coerceIn(0.2f, 1f)).toInt()
            c.drawText(n.toString(), w / 2, h * 0.42f + text.textSize * 0.35f, text)
            text.alpha = 255
        }
        if (goAt > 0L && now - goAt < 700L && st == World.RUN) {
            val f = (now - goAt) / 700f
            text.textAlign = Paint.Align.CENTER
            text.textSize = 54 * d * (1f + f * 0.4f)
            text.color = 0xFF4ADE80.toInt()
            text.alpha = (255 * (1f - f)).toInt()
            c.drawText(context.getString(R.string.runner3_go), w / 2, h * 0.42f, text)
            text.alpha = 255
        }
        if (popText.isNotEmpty() && now - popAt < 1600L) {
            val f = (now - popAt) / 1600f
            text.textAlign = Paint.Align.CENTER
            text.textSize = 26 * d * (if (f < 0.12f) 0.7f + f * 2.5f else 1f)
            text.color = popColor
            text.alpha = (255 * (1f - f * f)).toInt()
            c.drawText(popText, w / 2, h * 0.3f - f * 30 * d, text)
            text.alpha = 255
        }
        if (st == World.DYING && world.deathT < 0.3f) {
            fill.color = Color.WHITE
            fill.alpha = ((1f - world.deathT / 0.3f) * 170).toInt().coerceIn(0, 255)
            c.drawRect(0f, 0f, w, h, fill)
            fill.alpha = 255
        }
        if (live || (popText.isNotEmpty() && now - popAt < 1600L)) postInvalidateOnAnimation()
    }

    private fun drawStats(c: Canvas, w: Float) {
        val y0 = topInset + 8 * d
        text.textAlign = Paint.Align.RIGHT
        text.color = Color.WHITE
        text.textSize = 30 * d
        val score = world.score.toString()
        c.drawText(score, w - 14 * d, y0 + 30 * d, text)
        // Монеты забега.
        text.textSize = 20 * d
        text.color = 0xFFFDE047.toInt()
        val cs = world.coins.toString()
        c.drawText(cs, w - 14 * d, y0 + 58 * d, text)
        val cw = text.measureText(cs)
        val cx = w - 14 * d - cw - 13 * d
        val cy = y0 + 51 * d
        fill.color = 0xFFF59E0B.toInt()
        c.drawCircle(cx, cy, 9 * d, fill)
        fill.color = 0xFFFDE047.toInt()
        c.drawCircle(cx, cy, 6.5f * d, fill)
        // Множитель.
        val m = world.mult
        text.textSize = 30 * d
        val sw = text.measureText(score)
        val label = "×$m"
        text.textSize = 17 * d
        val lw = text.measureText(label) + 16 * d
        val right = w - 14 * d - sw - 10 * d
        r.set(right - lw, y0 + 8 * d, right, y0 + 34 * d)
        fill.color = if (world.power[World.P_X2] > 0f) 0xEEA855F7.toInt() else 0xCC1E293B.toInt()
        c.drawRoundRect(r, 13 * d, 13 * d, fill)
        text.color = Color.WHITE
        text.textAlign = Paint.Align.CENTER
        c.drawText(label, r.centerX(), r.bottom - 7 * d, text)
    }

    private fun drawPowers(c: Canvas) {
        var y = topInset + 14 * d
        val x = 12 * d
        for (i in world.power.indices) {
            val left = world.power[i]
            if (left <= 0f) continue
            val f = (left / world.powerTime[i].coerceAtLeast(0.1f)).coerceIn(0f, 1f)
            r.set(x, y, x + 132 * d, y + 30 * d)
            fill.color = 0xAA0F172A.toInt()
            c.drawRoundRect(r, 15 * d, 15 * d, fill)
            r.set(x + 34 * d, y + 11 * d, x + 34 * d + 88 * d * f, y + 19 * d)
            fill.color = powerColor[i]
            // Мигает, когда кончается.
            if (left < 2f && (left * 6).toInt() % 2 == 0) fill.alpha = 110
            c.drawRoundRect(r, 4 * d, 4 * d, fill)
            fill.alpha = 255
            text.textAlign = Paint.Align.CENTER
            text.textSize = 16 * d
            text.color = Color.WHITE
            c.drawText(powerIcon[i], x + 17 * d, y + 21 * d, text)
            y += 36 * d
        }
    }

    private fun drawWarnings(c: Canvas, w: Float, h: Float, now: Long) {
        val lx = renderer.laneScreenX
        for (l in 0..2) {
            val a = world.warn[l]
            if (a < 0.02f) continue
            val blink = 0.55f + 0.45f * sin(now / (70f + 90f * (1f - a)))
            val x = lx[l].coerceIn(0.08f, 0.92f) * w
            val y = h * 0.24f
            val s = (18 + 12 * a) * d
            path.reset()
            path.moveTo(x, y - s)
            path.lineTo(x + s * 1.1f, y + s * 0.85f)
            path.lineTo(x - s * 1.1f, y + s * 0.85f)
            path.close()
            fill.color = 0xFFDC2626.toInt()
            fill.alpha = (255 * (0.35f + 0.65f * a) * blink).toInt().coerceIn(0, 255)
            c.drawPath(path, fill)
            fill.alpha = 255
            text.textAlign = Paint.Align.CENTER
            text.textSize = s * 1.1f
            text.color = Color.WHITE
            c.drawText("!", x, y + s * 0.62f, text)
            text.textSize = 13 * d
            c.drawText("🚆", x, y + s * 0.85f + 18 * d, text)
        }
    }
}
