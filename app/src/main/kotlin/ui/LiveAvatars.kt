// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Анимация идёт, только пока аватар виден и украшения не выключены. */
private fun View.live() = Kinetics.enabled && !EnergySaver.on && isShown

/**
 * Живая аватарка «Избранного»: круг с переливом, золотая звезда покачивается и «дышит»,
 * по ней пробегает блик, вокруг вспыхивают искры.
 */
class SavedAvatarView(ctx: Context) : View(ctx) {
    private val start = SystemClock.uptimeMillis()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val star = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val path = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF38BDF8.toInt(), 0xFF5B4BDB.toInt(), Shader.TileMode.CLAMP)
        star.shader = LinearGradient(0f, h * 0.2f, 0f, h * 0.8f, 0xFFFFE680.toInt(), 0xFFF59E0B.toInt(), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val cx = w / 2
        val cy = h / 2
        c.drawCircle(cx, cy, minOf(w, h) / 2, bg)
        // Звезда: покачивается и чуть «дышит».
        val r = minOf(w, h) * 0.3f * (1f + 0.06f * sin(t * 2.4f))
        path.reset()
        for (i in 0 until 10) {
            val a = Math.toRadians(-90.0 + i * 36.0)
            val rr = if (i % 2 == 0) r else r * 0.45f
            val x = cx + (cos(a) * rr).toFloat()
            val y = cy + (sin(a) * rr).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.save()
        c.rotate(sin(t * 1.6f) * 10f, cx, cy)
        c.drawPath(path, star)
        // Блик пробегает по звезде раз в 3 с.
        val p = (t % 3f) / 3f
        if (p < 0.4f) {
            val x = cx - r * 1.5f + p / 0.4f * r * 3f
            shine.shader = LinearGradient(x - r * 0.3f, 0f, x + r * 0.3f, 0f, intArrayOf(0x00FFFFFF, 0xAAFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.save()
            c.clipPath(path)
            c.drawRect(0f, 0f, w, h, shine)
            c.restore()
        }
        c.restore()
        // Искры по кругу.
        for (i in 0 until 3) {
            val life = (t * 0.8f + i / 3f) % 1f
            val a = (i * 2.1f + (t * 0.8f + i / 3f).toInt() * 1.3f)
            val x = cx + cos(a) * w * 0.36f
            val y = cy + sin(a) * h * 0.36f
            spark.alpha = (sin(life * 3.14f) * 230).toInt().coerceIn(0, 255)
            c.drawCircle(x, y, w * 0.035f * sin(life * 3.14f), spark)
        }
        // 30 кадров/с - живо и экономно (аватарки видны в списке чатов постоянно).
        if (live()) postInvalidateDelayed(LiteMode.liveFrameMs) else if (isAttachedToWindow) postInvalidateDelayed(800)
    }
}

/**
 * Живая аватарка «Архива»: коробка-архив. Раз в несколько секунд крышка плавно приоткрывается,
 * сверху в коробку мягко «влетает» письмо, крышка захлопывается с лёгким пружинящим отскоком,
 * а коробка чуть приседает (как будто письмо легло внутрь). В паузах коробка едва покачивается,
 * по крышке пробегает блик. По нажатию на строку «Архив» анимация запускается сразу ([poke]).
 */
class ArchiveAvatarView(ctx: Context) : View(ctx) {
    private var start = SystemClock.uptimeMillis()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lid = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7A4E1E.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFF7E8.toInt() }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8A5A24.toInt() }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** Цвета писем по кругу - каждый раз «прилетает» новое. */
    private val stamps = intArrayOf(0xFF6366F1.toInt(), 0xFFEC4899.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt())

    /** Длина цикла, с: письмо прилетает раз в CYCLE секунд. */
    private val cycle = 5.2f

    /** Запустить «прилёт письма» прямо сейчас (по нажатию). */
    fun poke() {
        // Сдвигаем начало так, чтобы цикл оказался в фазе «крышка начинает открываться».
        start = SystemClock.uptimeMillis() - (cycle * 0.6f * 1000).toLong()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF94A3B8.toInt(), 0xFF5B6478.toInt(), Shader.TileMode.CLAMP)
        body.shader = LinearGradient(0f, h * 0.45f, 0f, h * 0.8f, 0xFFF0C27E.toInt(), 0xFFD4943F.toInt(), Shader.TileMode.CLAMP)
        lid.shader = LinearGradient(0f, h * 0.3f, 0f, h * 0.45f, 0xFFF8D49A.toInt(), 0xFFE2A957.toInt(), Shader.TileMode.CLAMP)
    }

    /** Плавные кривые: без рывков в начале и в конце движения. */
    private fun smooth(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return v * v * (3 - 2 * v)
    }

    /** Пружинка: быстро к 1, лёгкий перелёт и затухающие колебания. */
    private fun spring(x: Float): Float {
        val v = x.coerceIn(0f, 1f)
        return 1f - (exp(-6.5f * v) * cos(v * 13f))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val n = (t / cycle).toInt()
        val p = (t % cycle) / cycle
        val cx = w / 2
        val cy = h / 2
        c.drawCircle(cx, cy, minOf(w, h) / 2, bg)

        // Фазы цикла (доли): 0.60-0.70 открытие, 0.66-0.86 письмо падает, 0.84-1.0 крышка закрывается.
        val open = when {
            p < 0.60f -> 0f
            p < 0.70f -> smooth((p - 0.60f) / 0.10f)
            p < 0.84f -> 1f
            else -> 1f - spring((p - 0.84f) / 0.16f)
        }
        val drop = ((p - 0.66f) / 0.20f).coerceIn(0f, 1f)
        // Коробка «приседает», когда письмо легло, и когда хлопнула крышка.
        val land = when {
            p in 0.84f..0.92f -> sin((p - 0.84f) / 0.08f * PI_F) * 0.05f
            p > 0.86f -> sin(((p - 0.86f) / 0.14f).coerceIn(0f, 1f) * PI_F) * 0.035f
            else -> 0f
        }
        val idle = sin(t * 1.7f) * h * 0.008f

        val bw = w * 0.54f
        val bh = h * 0.27f
        val left = cx - bw / 2
        val bottom = cy + h * 0.25f + idle
        // Приседание: шире и ниже, низ стоит на месте.
        val sx = 1f + land * 0.6f
        val sy = 1f - land
        c.save()
        c.scale(sx, sy, cx, bottom)

        val top = bottom - bh
        // Мягкая тень под коробкой.
        rect.set(left + bw * 0.06f, bottom - h * 0.01f, left + bw * 0.94f, bottom + h * 0.035f)
        c.drawOval(rect, shadow)

        // Тёмная «внутренность», видная при открытой крышке.
        if (open > 0.02f) {
            rect.set(left + bw * 0.04f, top - h * 0.03f * open, left + bw * 0.96f, top + bh * 0.25f)
            c.drawRoundRect(rect, w * 0.02f, w * 0.02f, inside)
        }

        // Письмо: падает сверху с лёгким покачиванием и уходит за переднюю стенку.
        if (drop > 0f && drop < 1f) {
            val e = drop * drop
            val lw = bw * 0.6f
            val lh = bh * 0.72f
            val ly = (cy - h * 0.48f) + (top + bh * 0.35f - (cy - h * 0.48f)) * e
            val rot = sin(drop * PI_F * 2f) * 12f * (1f - drop)
            c.save()
            c.rotate(rot, cx, ly + lh / 2)
            rect.set(cx - lw / 2, ly, cx + lw / 2, ly + lh)
            c.drawRoundRect(rect, w * 0.015f, w * 0.015f, letter)
            ink.color = stamps[n % stamps.size]
            ink.strokeWidth = maxOf(1f, w * 0.02f)
            // Клапан конверта.
            path.reset()
            path.moveTo(cx - lw / 2 + w * 0.01f, ly + w * 0.01f)
            path.lineTo(cx, ly + lh * 0.55f)
            path.lineTo(cx + lw / 2 - w * 0.01f, ly + w * 0.01f)
            c.drawPath(path, ink)
            c.restore()
        }

        // Передняя стенка коробки.
        rect.set(left, top, left + bw, bottom)
        c.drawRoundRect(rect, w * 0.04f, w * 0.04f, body)
        // Этикетка и ручка-прорезь.
        rect.set(cx - bw * 0.22f, top + bh * 0.22f, cx + bw * 0.22f, top + bh * 0.5f)
        c.drawRoundRect(rect, w * 0.012f, w * 0.012f, label)
        rect.set(cx - bw * 0.13f, top + bh * 0.64f, cx + bw * 0.13f, top + bh * 0.76f)
        c.drawRoundRect(rect, bh * 0.06f, bh * 0.06f, handle)

        // Крышка: откидывается назад на петле у правого края.
        val lidH = h * 0.085f
        c.save()
        c.rotate(-34f * open, left + bw * 1.03f, top)
        rect.set(left - bw * 0.04f, top - lidH, left + bw * 1.04f, top + lidH * 0.15f)
        c.drawRoundRect(rect, w * 0.03f, w * 0.03f, lid)
        // Блик по крышке раз в цикл, пока коробка закрыта.
        if (p < 0.35f) {
            val g = p / 0.35f
            val x = rect.left + rect.width() * (g * 1.4f - 0.2f)
            shine.shader = LinearGradient(
                x - w * 0.08f, 0f, x + w * 0.08f, 0f,
                intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP,
            )
            c.drawRoundRect(rect, w * 0.03f, w * 0.03f, shine)
        }
        c.restore()
        c.restore()
        // 30 кадров/с - живо и экономно (аватарки видны в списке чатов постоянно).
        if (live()) postInvalidateDelayed(LiteMode.liveFrameMs) else if (isAttachedToWindow) postInvalidateDelayed(800)
    }

    private companion object {
        const val PI_F = 3.1415927f
    }
}
