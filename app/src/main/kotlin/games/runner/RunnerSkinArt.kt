// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * Плоский живой портрет облика бегуна для магазина (4.10.0): фигура в цветах облика бежит на
 * месте, светящиеся облики - с ореолом, со следом - с частицами (иней, пламя, звёзды, неон).
 * Рисуется на Canvas: магазин не поднимает 3D ради витрины.
 */
internal class RunnerSkinArt(ctx: Context, private val sk: Skin) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val start = SystemClock.uptimeMillis()
    private val vis = android.graphics.Rect()
    private val live get() = com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val t = if (live) (SystemClock.uptimeMillis() - start) / 1000f else 0f
        val u = h / 100f
        val cx = w / 2f
        val bob = sin(t * 12f) * 1.5f * u
        val swing = sin(t * 12f)
        // Ореол светящихся обликов.
        if (sk.glow != 0) {
            p.shader = RadialGradient(cx, h * 0.5f, h * 0.55f, (sk.glow and 0x00FFFFFF) or 0x66000000, sk.glow and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx, h * 0.5f, h * 0.55f, p)
            p.shader = null
        }
        // След: частицы уходят назад-вниз.
        if (sk.trail != 0) {
            val col = when (sk.trail) {
                2 -> 0xFFFF8A1F.toInt()
                3 -> 0xFFE0F2FE.toInt()
                4 -> sk.glow.takeIf { it != 0 } ?: 0xFF22D3EE.toInt()
                else -> 0xFFFDE047.toInt()
            }
            for (i in 0 until 7) {
                val ph = ((t * 0.9f + i / 7f) % 1f)
                p.color = col
                p.alpha = (220 * (1f - ph)).toInt()
                c.drawCircle(cx - 8 * u - ph * 30 * u, h * 0.62f + (i % 3) * 6 * u + ph * 8 * u, (2.4f - ph * 1.6f) * u, p)
            }
            p.alpha = 255
        }
        // Аура легендарных: огоньки кружат вокруг фигуры.
        if (sk.legendary && sk.aura != 0) {
            p.shader = RadialGradient(cx, h * 0.5f, h * 0.5f, (sk.aura and 0x00FFFFFF) or 0x55000000, sk.aura and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx, h * 0.5f, h * 0.5f, p)
            p.shader = null
            for (i in 0 until 8) {
                val a = t * 1.6f + i * 0.785f
                p.color = sk.aura
                p.alpha = (150 + 100 * sin(t * 4f + i)).toInt().coerceIn(0, 255)
                c.drawCircle(cx + kotlin.math.cos(a) * 30 * u, 50 * u + sin(a) * 34 * u, 2.2f * u, p)
            }
            p.alpha = 255
        }
        // Доска под ногами.
        p.color = sk.board
        r.set(cx - 22 * u, 92 * u, cx + 22 * u, 96 * u)
        c.drawRoundRect(r, 2 * u, 2 * u, p)
        // Телосложение персонажа: шире/уже, выше/ниже (от ступней).
        c.save()
        c.scale(sk.bulk, sk.tall, cx, 92 * u)
        fun has(bit: Int) = (sk.parts and bit) != 0
        // За спиной: крылья, плащ, хвост.
        if (has(Parts.WINGS)) {
            val flap = sin(t * 6f) * 10f
            p.color = sk.partColor
            for (side in intArrayOf(-1, 1)) {
                c.save()
                c.rotate(side * (-20f - flap), cx + side * 4 * u, 38 * u + bob)
                for (f in 0 until 3) {
                    r.set(cx + side * (6 + f * 3) * u - 13 * u, (30 + f * 6) * u + bob, cx + side * (6 + f * 3) * u + 13 * u, (36 + f * 6) * u + bob)
                    r.offset(side * 12 * u, 0f)
                    c.drawOval(r, p)
                }
                c.restore()
            }
        }
        if (has(Parts.CAPE)) {
            p.color = sk.partColor
            val sway = sin(t * 8f) * 3 * u
            val path = android.graphics.Path().apply {
                moveTo(cx - 9 * u, 34 * u + bob)
                lineTo(cx + 9 * u, 34 * u + bob)
                lineTo(cx + 14 * u - sway, 76 * u + bob)
                lineTo(cx - 14 * u - sway, 76 * u + bob)
                close()
            }
            c.drawPath(path, p)
        }
        if (has(Parts.TAIL)) {
            p.color = sk.partColor
            val wag = sin(t * 9f) * 4 * u
            val rr = if (has(Parts.BUSHY)) 4.5f else 2.2f
            for (i in 0 until 6) {
                val f = i / 5f
                c.drawCircle(cx - 8 * u - f * 12 * u, 60 * u - f * f * 14 * u + bob + wag * f, (rr - (if (has(Parts.BUSHY)) 0f else f)) * u * (if (has(Parts.BUSHY)) 0.6f + sin(f * 3.14f) * 0.6f else 1f), p)
            }
        }
        val legSkin = sk.shorts
        // Ноги (шагают).
        for (side in intArrayOf(-1, 1)) {
            val k = swing * side
            val x = cx + side * 5 * u + k * 5 * u
            p.color = sk.pants
            r.set(x - 4 * u, 56 * u + bob, x + 4 * u, (if (legSkin) 70 else 84) * u + bob)
            c.drawRoundRect(r, 3 * u, 3 * u, p)
            if (legSkin) {
                p.color = sk.skin
                r.set(x - 3.2f * u, 70 * u + bob, x + 3.2f * u, 84 * u + bob)
                c.drawRoundRect(r, 3 * u, 3 * u, p)
            }
            p.color = sk.shoes
            r.set(x - 5 * u, 83 * u + bob, x + 7 * u, 90 * u + bob)
            c.drawRoundRect(r, 3 * u, 3 * u, p)
            p.color = sk.sole
            r.set(x - 5 * u, 88.5f * u + bob, x + 7 * u, 90.5f * u + bob)
            c.drawRect(r, p)
        }
        // Руки (машут в такт).
        for (side in intArrayOf(-1, 1)) {
            val k = -swing * side
            p.color = if (sk.sleeves) sk.top else sk.skin
            c.save()
            c.rotate(k * 25f, cx + side * 11 * u, 36 * u + bob)
            r.set(cx + side * 11 * u - 3.5f * u, 35 * u + bob, cx + side * 11 * u + 3.5f * u, 56 * u + bob)
            c.drawRoundRect(r, 3 * u, 3 * u, p)
            p.color = sk.skin
            c.drawCircle(cx + side * 11 * u, 57 * u + bob, 3.3f * u, p)
            c.restore()
        }
        // Туловище с полосой акцента.
        p.color = sk.top
        r.set(cx - 9 * u, 33 * u + bob, cx + 9 * u, 60 * u + bob)
        c.drawRoundRect(r, 5 * u, 5 * u, p)
        p.color = sk.accent
        r.set(cx - 9 * u, 44 * u + bob, cx + 9 * u, 48 * u + bob)
        c.drawRect(r, p)
        if (sk.extra != 0) {
            // Аксессуар: шарф/цепь/рюкзак - пятно цвета аксессуара у шеи.
            p.color = sk.extraColor
            r.set(cx - 8 * u, 32 * u + bob, cx + 8 * u, 37 * u + bob)
            c.drawRoundRect(r, 2.5f * u, 2.5f * u, p)
        }
        // Голова, волосы, головной убор.
        p.color = sk.skin
        c.drawCircle(cx, 24 * u + bob, 9 * u, p)
        p.color = sk.hairColor
        r.set(cx - 9.5f * u, 14 * u + bob, cx + 9.5f * u, 30 * u + bob)
        c.drawArc(r, 180f, 180f, true, p)
        if (sk.hair == 4) {
            // Ирокез / шипы.
            for (i in -1..1) c.drawCircle(cx + i * 4 * u, 13 * u + bob, 3 * u, p)
        }
        if (sk.hat != 0) {
            p.color = sk.hatColor
            r.set(cx - 10 * u, 12 * u + bob, cx + 10 * u, 25 * u + bob)
            c.drawArc(r, 180f, 180f, true, p)
            r.set(cx - 2 * u, 17 * u + bob, cx + 14 * u, 20 * u + bob)
            c.drawRoundRect(r, 1.5f * u, 1.5f * u, p)
        }
        // Части головы.
        if (has(Parts.EARS)) {
            for (side in intArrayOf(-1, 1)) {
                val ex = cx + side * 6 * u
                val path = android.graphics.Path().apply {
                    moveTo(ex - 4 * u, 17 * u + bob)
                    lineTo(ex + side * 2 * u, 7 * u + bob)
                    lineTo(ex + 4 * u, 17 * u + bob)
                    close()
                }
                p.color = sk.partColor
                c.drawPath(path, p)
            }
        }
        if (has(Parts.HORNS)) {
            p.color = sk.partColor
            for (side in intArrayOf(-1, 1)) {
                val hx = cx + side * 6 * u
                val path = android.graphics.Path().apply {
                    moveTo(hx - 2.2f * u, 16 * u + bob)
                    quadTo(hx + side * 3 * u, 9 * u + bob, hx + side * 6 * u, 7 * u + bob)
                    lineTo(hx + 2.2f * u, 16 * u + bob)
                    close()
                }
                c.drawPath(path, p)
            }
        }
        if (has(Parts.CROWN)) {
            p.color = sk.partColor
            val path = android.graphics.Path().apply {
                moveTo(cx - 8 * u, 16 * u + bob)
                lineTo(cx - 8 * u, 9 * u + bob)
                lineTo(cx - 4 * u, 13 * u + bob)
                lineTo(cx, 7 * u + bob)
                lineTo(cx + 4 * u, 13 * u + bob)
                lineTo(cx + 8 * u, 9 * u + bob)
                lineTo(cx + 8 * u, 16 * u + bob)
                close()
            }
            c.drawPath(path, p)
            p.color = sk.part2Color
            c.drawCircle(cx, 13.5f * u + bob, 1.4f * u, p)
        }
        if (has(Parts.ANTENNA)) {
            p.color = sk.partColor
            p.strokeWidth = 1.2f * u
            for (side in intArrayOf(-1, 1)) c.drawLine(cx + side * 4 * u, 16 * u + bob, cx + side * 7 * u, 6 * u + bob, p)
            p.color = sk.part2Color
            for (side in intArrayOf(-1, 1)) c.drawCircle(cx + side * 7 * u, 6 * u + bob, 2 * u, p)
        }
        if (has(Parts.HALO)) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.8f * u
            p.color = sk.part2Color
            r.set(cx - 8 * u, 5 * u + bob + sin(t * 3f) * u, cx + 8 * u, 9 * u + bob + sin(t * 3f) * u)
            c.drawOval(r, p)
            p.style = Paint.Style.FILL
        }
        if (has(Parts.PADS)) {
            p.color = sk.partColor
            for (side in intArrayOf(-1, 1)) {
                r.set(cx + side * 11 * u - 5 * u, 31 * u + bob, cx + side * 11 * u + 5 * u, 38 * u + bob)
                c.drawArc(r, 180f, 180f, true, p)
            }
        }
        // Глаз (смотрит вперёд-вправо).
        p.color = 0xFF111827.toInt()
        c.drawCircle(cx + 4 * u, 24 * u + bob, 1.3f * u, p)
        c.restore()
        // Анимируем, только если плитка действительно на экране (в магазине их десятки в
        // прокрутке - раньше все перерисовывались 25 раз в секунду), на слабых - реже.
        if (live && isShown && getGlobalVisibleRect(vis)) {
            postInvalidateDelayed(if (com.stoyko.smessenger.ui.LiteMode.on) 50L else 40L)
        } else if (live && isShown) {
            postInvalidateDelayed(500L)
        }
    }
}
