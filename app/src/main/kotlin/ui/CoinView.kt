// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Монета S-messenger: золотая, с буквой «S» (логотип мессенджера) на ОБЕИХ сторонах.
 * Медленно вращается вокруг вертикальной оси - видно ребро монеты, по золоту бегут блики.
 * Остановленная ([spinning] = false) просто слегка покачивается.
 */
class CoinView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var spinning = true
        set(v) {
            field = v
            invalidate()
        }

    /** Оборот за столько секунд. */
    var period = 3.2f

    private val start = SystemClock.uptimeMillis()
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val oval = RectF()

    /** Вспышка - например, когда монеты начислены. */
    fun pulse() {
        animate().cancel()
        scaleX = 1.35f
        scaleY = 1.35f
        animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) / 2 * 0.92f
        // Угол поворота: при вращении - полный оборот, иначе лёгкое покачивание.
        val ang = if (spinning && Kinetics.enabled) (t / period) * 2 * Math.PI.toFloat() else sin(t * 1.5f) * 0.35f
        val k = cos(ang)
        val sx = abs(k).coerceAtLeast(0.06f)
        val thick = r * 0.16f * sin(ang).let { abs(it) }
        val dir = if (sin(ang) >= 0) 1f else -1f
        // Ребро монеты (видно, когда она повёрнута).
        if (thick > 0.5f) {
            edge.shader = LinearGradient(cx - r, 0f, cx + r, 0f, intArrayOf(0xFF8A5A00.toInt(), 0xFFD9A21B.toInt(), 0xFF8A5A00.toInt()), null, Shader.TileMode.CLAMP)
            oval.set(cx - r * sx + dir * thick, cy - r, cx + r * sx + dir * thick, cy + r)
            c.drawOval(oval, edge)
            c.drawRect(cx - 0.5f + minOf(0f, dir * thick), cy - r, cx + 0.5f + maxOf(0f, dir * thick), cy + r, edge)
        }
        // Лицевая сторона (обе стороны одинаковые - «S»).
        oval.set(cx - r * sx, cy - r, cx + r * sx, cy + r)
        val bright = 0.75f + 0.25f * abs(k)
        face.shader = RadialGradient(
            cx - r * sx * 0.35f, cy - r * 0.4f, r * 1.4f,
            intArrayOf(shade(0xFFFFF4B0.toInt(), bright), shade(0xFFFCC531.toInt(), bright), shade(0xFFC98A06.toInt(), bright)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawOval(oval, face)
        // Ободок и внутреннее кольцо.
        rim.strokeWidth = r * 0.09f
        rim.color = shade(0xFFB07800.toInt(), bright)
        oval.inset(rim.strokeWidth / 2 * sx, rim.strokeWidth / 2)
        c.drawOval(oval, rim)
        rim.strokeWidth = r * 0.035f
        rim.color = shade(0xFFFFE27A.toInt(), bright)
        oval.set(cx - r * 0.74f * sx, cy - r * 0.74f, cx + r * 0.74f * sx, cy + r * 0.74f)
        c.drawOval(oval, rim)
        // Буква «S» - сжимается вместе с монетой (так выглядит поворот).
        c.save()
        c.scale(sx, 1f, cx, cy)
        letter.textSize = r * 1.15f
        letter.color = shade(0xFF8A5A00.toInt(), bright)
        c.drawText("S", cx + r * 0.03f, cy + r * 0.42f + r * 0.03f, letter)
        letter.color = shade(0xFFFFF8D6.toInt(), bright)
        c.drawText("S", cx, cy + r * 0.42f, letter)
        c.restore()
        // Блик пробегает по монете.
        oval.set(cx - r * sx, cy - r, cx + r * sx, cy + r)
        clip.reset()
        clip.addOval(oval, Path.Direction.CW)
        val p = (t % 2.4f) / 2.4f
        val x = cx - r * 1.6f + p * r * 3.2f
        shine.shader = LinearGradient(x - r * 0.3f, cy - r, x + r * 0.3f, cy + r, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.save()
        c.clipPath(clip)
        c.drawRect(oval, shine)
        c.restore()
        // Экономия батареи: невидимая монета не перерисовывается вовсе (снова видна -
        // анимация продолжится сама), в режиме экономии - 8 кадров в секунду.
        if (isShown && Kinetics.enabled) postInvalidateDelayed(if (EnergySaver.on) 125 else com.stoyko.smessenger.ui.LiteMode.liveFrameMs)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    private fun shade(c: Int, k: Float): Int {
        fun ch(s: Int) = (((c shr s) and 0xFF) * k).toInt().coerceIn(0, 255) shl s
        return (c and 0xFF000000.toInt()) or ch(16) or ch(8) or ch(0)
    }
}
