// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/**
 * Живые схемы для обучения «Сервер для узлов»:
 *  - [Route]: телефон → VPN-сервер → узлы сети; прямой путь перечёркнут (провайдер блокирует),
 *    пакеты бегут через сервер;
 *  - [Testing]: серверы проверяются один за другим - полоски задержки растут, мёртвые
 *    перечёркиваются, лучший получает корону;
 *  - [Result]: приходит уведомление «Найден лучший VPN для узлов», переключатель включает сам человек.
 */
object VpnScenes {
    fun route(ctx: Context): View = Route(ctx)
    fun testing(ctx: Context): View = Testing(ctx)
    fun result(ctx: Context): View = Result(ctx)

    private abstract class Scene(ctx: Context) : View(ctx) {
        protected val d = resources.displayMetrics.density
        private val t0 = SystemClock.uptimeMillis()
        protected val t: Float get() = (SystemClock.uptimeMillis() - t0) / 1000f
        protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        protected val r = RectF()

        abstract fun paint(c: Canvas, w: Float, h: Float)

        override fun onDraw(c: Canvas) {
            if (width <= 0) return
            paint(c, width.toFloat(), height.toFloat())
            if (isShown) postInvalidateOnAnimation()
        }

        /** Кружок-«узел» с подписью-эмодзи. */
        protected fun node(c: Canvas, x: Float, y: Float, rad: Float, color: Int, label: String) {
            fill.color = color
            c.drawCircle(x, y, rad, fill)
            text.textSize = rad * 0.95f
            c.drawText(label, x, y + rad * 0.34f, text)
        }
    }

    private class Route(ctx: Context) : Scene(ctx) {
        override fun paint(c: Canvas, w: Float, h: Float) {
            val y = h * 0.5f
            val px = w * 0.13f
            val sx = w * 0.5f
            val nx = w * 0.85f
            val rad = h * 0.2f
            // Прямой путь (снизу) - заблокирован провайдером.
            stroke.color = 0x66FF5C7A
            stroke.strokeWidth = 3 * d
            stroke.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * d, 8 * d), 0f)
            c.drawLine(px, y + rad * 1.3f, nx, y + rad * 1.3f, stroke)
            stroke.pathEffect = null
            val bx = (px + nx) / 2
            val by = y + rad * 1.3f
            val pulse = 1f + 0.15f * sin(t * 6f)
            stroke.color = 0xFFFF5C7A.toInt()
            stroke.strokeWidth = 4 * d
            val k = 9 * d * pulse
            c.drawLine(bx - k, by - k, bx + k, by + k, stroke)
            c.drawLine(bx - k, by + k, bx + k, by - k, stroke)
            // Путь через VPN-сервер.
            stroke.color = 0x667B6CF6
            stroke.strokeWidth = 4 * d
            c.drawLine(px, y, nx, y, stroke)
            // Пакеты бегут: телефон → сервер → узлы.
            for (i in 0 until 4) {
                val p = ((t * 0.45f + i * 0.25f) % 1f)
                val x = px + (nx - px) * p
                fill.color = if (x < sx) 0xFF38BDF8.toInt() else 0xFF34D399.toInt()
                c.drawCircle(x, y, 5 * d, fill)
            }
            node(c, px, y, rad, 0xFF5B4BDB.toInt(), "📱")
            node(c, sx, y, rad * (1f + 0.05f * sin(t * 3f)), 0xFF0E9488.toInt(), "🛡")
            node(c, nx, y, rad, 0xFF059669.toInt(), "🌐")
            text.textSize = 11 * d
            text.color = 0xCCFFFFFF.toInt()
            c.drawText("VPN", sx, y - rad - 6 * d, text)
            text.color = Color.WHITE
        }
    }

    private class Testing(ctx: Context) : Scene(ctx) {
        // Задержки (с); -1 - сервер не пропускает к узлам.
        private val ms = floatArrayOf(0.9f, -1f, 0.35f, 1.4f, -1f)

        override fun paint(c: Canvas, w: Float, h: Float) {
            val n = ms.size
            val period = 6f
            val tt = t % period
            val rowH = h / n
            val best = ms.indices.filter { ms[it] > 0 }.minByOrNull { ms[it] } ?: -1
            for (i in 0 until n) {
                val y = rowH * (i + 0.5f)
                val start = i * 0.8f
                val p = ((tt - start) / 0.8f).coerceIn(0f, 1f)
                // Название сервера.
                text.textSize = rowH * 0.42f
                text.textAlign = Paint.Align.LEFT
                text.color = 0xDDFFFFFF.toInt()
                c.drawText("🌐 #" + (i + 1), w * 0.04f, y + rowH * 0.15f, text)
                // Дорожка и полоса задержки.
                r.set(w * 0.28f, y - rowH * 0.18f, w * 0.82f, y + rowH * 0.18f)
                fill.color = 0x22FFFFFF
                c.drawRoundRect(r, rowH * 0.18f, rowH * 0.18f, fill)
                val dead = ms[i] < 0
                val full = if (dead) 0.25f else (1f - ms[i] / 1.6f).coerceIn(0.15f, 1f)
                r.right = r.left + (r.width()) * full * p
                fill.color = when {
                    dead -> 0xFFFF5C7A.toInt()
                    i == best && tt > n * 0.8f -> 0xFFFDE047.toInt()
                    else -> 0xFF34D399.toInt()
                }
                if (p > 0f) c.drawRoundRect(r, rowH * 0.18f, rowH * 0.18f, fill)
                // Итог строки.
                if (p >= 1f) {
                    text.textAlign = Paint.Align.RIGHT
                    text.color = if (dead) 0xFFFF8FA3.toInt() else Color.WHITE
                    c.drawText(if (dead) "✕" else "%.1f ".format(ms[i]) + com.stoyko.smessenger.ai.t("с", "s"), w * 0.98f, y + rowH * 0.15f, text)
                }
                // Корона - лучшему, когда все проверены.
                if (i == best && tt > n * 0.8f) {
                    val bounce = abs(sin(t * 4f)) * rowH * 0.12f
                    text.textAlign = Paint.Align.CENTER
                    text.textSize = rowH * 0.5f
                    c.drawText("👑", w * 0.24f, y + rowH * 0.18f - bounce, text)
                }
            }
            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
        }
    }

    private class Result(ctx: Context) : Scene(ctx) {
        @SuppressLint("DefaultLocale")
        override fun paint(c: Canvas, w: Float, h: Float) {
            val period = 5f
            val tt = t % period
            // Карточка-уведомление выезжает сверху.
            val slide = (tt / 0.6f).coerceIn(0f, 1f)
            val e = 1f - (1f - slide) * (1f - slide)
            val top = -h * 0.5f + e * (h * 0.5f + h * 0.06f)
            r.set(w * 0.05f, top, w * 0.95f, top + h * 0.42f)
            fill.color = 0xFF2A2460.toInt()
            c.drawRoundRect(r, 16 * d, 16 * d, fill)
            text.textAlign = Paint.Align.LEFT
            text.textSize = h * 0.11f
            text.color = Color.WHITE
            c.drawText("🛡 " + com.stoyko.smessenger.ai.t("Лучший VPN для узлов найден", "Best VPN for nodes found"), r.left + 12 * d, r.top + h * 0.16f, text)
            text.textSize = h * 0.09f
            text.color = 0xCCFFFFFF.toInt()
            c.drawText(com.stoyko.smessenger.ai.t("Выбран по умолчанию · VPN не включали", "Set as default · VPN not turned on"), r.left + 12 * d, r.top + h * 0.31f, text)
            // Переключатель: включает человек (палец «нажимает» через 2 с).
            val on = tt > 2.6f
            val sw = RectF(w * 0.38f, h * 0.62f, w * 0.62f, h * 0.9f)
            fill.color = if (on) 0xFF22C55E.toInt() else 0x55FFFFFF
            c.drawRoundRect(sw, sw.height() / 2, sw.height() / 2, fill)
            val knobX = if (on) sw.right - sw.height() / 2 else sw.left + sw.height() / 2
            fill.color = Color.WHITE
            c.drawCircle(knobX, sw.centerY(), sw.height() * 0.4f, fill)
            if (tt in 1.8f..2.8f) {
                text.textAlign = Paint.Align.CENTER
                text.textSize = h * 0.2f
                val press = if (tt > 2.4f) 4 * d else 0f
                c.drawText("👆", sw.centerX() + sw.width() * 0.2f, sw.bottom + h * 0.1f + press, text)
            }
            text.textAlign = Paint.Align.CENTER
            text.color = Color.WHITE
        }
    }
}
