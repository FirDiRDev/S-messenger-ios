// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * Рисованные сезонные элементы (векторные, без эмодзи): кленовый и овальный листья с
 * прожилками и переливом цвета, снежинка, сосульки с бликом, сугробы по углам экрана,
 * новогодняя шапочка поверх круглого значка.
 */
object SeasonArt {
    private val mapleR = floatArrayOf(
        0f, -1f, 0.12f, -0.62f, 0.32f, -0.74f, 0.27f, -0.36f, 0.62f, -0.52f, 0.53f, -0.3f, 0.88f, -0.3f,
        0.67f, -0.1f, 0.98f, 0.1f, 0.56f, 0.16f, 0.62f, 0.38f, 0.22f, 0.26f, 0.08f, 0.46f,
    )
    private val maple = Path().apply {
        moveTo(mapleR[0], mapleR[1])
        var i = 2
        while (i < mapleR.size) {
            lineTo(mapleR[i], mapleR[i + 1])
            i += 2
        }
        i = mapleR.size - 2
        while (i >= 2) {
            lineTo(-mapleR[i], mapleR[i + 1])
            i -= 2
        }
        close()
    }
    private val oval = Path().apply {
        moveTo(0f, -1f)
        cubicTo(0.62f, -0.6f, 0.62f, 0.5f, 0f, 0.92f)
        cubicTo(-0.62f, 0.5f, -0.62f, -0.6f, 0f, -1f)
        close()
    }
    private val tmp = Path()

    // Градиенты листьев в собственных координатах листа - одни на все кадры (без мусора в памяти).
    private val leafShaders = HashMap<IntArray, LinearGradient>()

    /** Палитра осенних листьев: (светлый, тёмный). */
    val LEAF_COLORS = arrayOf(
        intArrayOf(0xFFFDE047.toInt(), 0xFFEA580C.toInt()),
        intArrayOf(0xFFFB923C.toInt(), 0xFFB91C1C.toInt()),
        intArrayOf(0xFFF87171.toInt(), 0xFF7F1D1D.toInt()),
        intArrayOf(0xFFFACC15.toInt(), 0xFFA16207.toInt()),
        intArrayOf(0xFFBEF264.toInt(), 0xFFCA8A04.toInt()),
    )

    /**
     * Лист: [kind] 0 - кленовый, 1 - овальный; [flip] - поворот «вокруг себя» (−1…1, как
     * лист переворачивается в полёте); [alpha] - прозрачность.
     */
    fun leaf(c: Canvas, p: Paint, x: Float, y: Float, size: Float, angle: Float, flip: Float, kind: Int, colors: IntArray, alpha: Int) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        c.scale(size * (0.25f + 0.75f * kotlin.math.abs(flip)), size)
        val shape = if (kind == 0) maple else oval
        p.style = Paint.Style.FILL
        p.shader = leafShaders.getOrPut(colors) { LinearGradient(-0.6f, -1f, 0.6f, 1f, colors[0], colors[1], Shader.TileMode.CLAMP) }
        p.alpha = alpha
        c.drawPath(shape, p)
        p.shader = null
        // Прожилки и черешок.
        p.style = Paint.Style.STROKE
        p.strokeWidth = 0.05f
        p.color = colors[1]
        p.alpha = (alpha * 0.8f).toInt()
        c.drawLine(0f, 1f, 0f, -0.85f, p)
        if (kind == 0) {
            c.drawLine(0f, 0.3f, 0.8f, -0.25f, p)
            c.drawLine(0f, 0.3f, -0.8f, -0.25f, p)
            c.drawLine(0f, 0.35f, 0.85f, 0.1f, p)
            c.drawLine(0f, 0.35f, -0.85f, 0.1f, p)
        } else {
            for (k in 0 until 3) {
                val yy = -0.5f + k * 0.45f
                c.drawLine(0f, yy + 0.2f, 0.38f, yy - 0.05f, p)
                c.drawLine(0f, yy + 0.2f, -0.38f, yy - 0.05f, p)
            }
        }
        p.style = Paint.Style.FILL
        c.restore()
    }

    /** Снежинка из шести лучей с веточками. */
    fun snowflake(c: Canvas, p: Paint, x: Float, y: Float, r: Float, rot: Float, alpha: Int) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = (r * 0.14f).coerceAtLeast(1f)
        p.color = 0xFFFFFFFF.toInt()
        p.alpha = alpha
        for (k in 0 until 6) {
            val a = Math.toRadians((k * 60 + rot).toDouble())
            val ex = x + (r * cos(a)).toFloat()
            val ey = y + (r * sin(a)).toFloat()
            c.drawLine(x, y, ex, ey, p)
            val bx = x + (r * 0.55f * cos(a)).toFloat()
            val by = y + (r * 0.55f * sin(a)).toFloat()
            for (s in intArrayOf(-1, 1)) {
                val b = a + s * Math.toRadians(40.0)
                c.drawLine(bx, by, bx + (r * 0.3f * cos(b)).toFloat(), by + (r * 0.3f * sin(b)).toFloat(), p)
            }
        }
        p.style = Paint.Style.FILL
    }

    /** Ряд сосулек вдоль верхнего края: разной длины, прозрачно-голубые с бликом, подрагивают. */
    fun icicles(c: Canvas, p: Paint, left: Float, top: Float, width: Float, d: Float, t: Float) {
        // Снежная кромка сверху.
        p.shader = null
        p.color = 0xF2FFFFFF.toInt()
        tmp.reset()
        tmp.moveTo(left, top)
        tmp.lineTo(left + width, top)
        tmp.lineTo(left + width, top + 10 * d)
        var x = left + width
        val step = 22 * d
        var k = 0
        while (x > left) {
            val nx = (x - step).coerceAtLeast(left)
            tmp.quadTo((x + nx) / 2, top + (if (k % 2 == 0) 18 else 13) * d, nx, top + 10 * d)
            x = nx
            k++
        }
        tmp.close()
        c.drawPath(tmp, p)
        // Сосульки.
        var ix = left + 8 * d
        var i = 0
        while (ix < left + width - 6 * d) {
            val len = (16 + (i * 37 % 7) * 6) * d
            val w = (5 + (i * 13 % 3) * 2) * d
            val wob = sin(t * 2f + i) * 0.6f * d
            tmp.reset()
            tmp.moveTo(ix - w / 2, top + 12 * d)
            tmp.quadTo(ix - w * 0.15f + wob, top + 12 * d + len * 0.6f, ix + wob, top + 12 * d + len)
            tmp.quadTo(ix + w * 0.15f + wob, top + 12 * d + len * 0.6f, ix + w / 2, top + 12 * d)
            tmp.close()
            p.shader = LinearGradient(ix - w / 2, 0f, ix + w / 2, 0f, intArrayOf(0xDDE0F2FE.toInt(), 0xFFFFFFFF.toInt(), 0xAA7DD3FC.toInt()), null, Shader.TileMode.CLAMP)
            c.drawPath(tmp, p)
            p.shader = null
            // Капля на кончике иногда блестит.
            val shine = ((t * 0.7f + i * 0.37f) % 1f)
            if (shine < 0.15f) {
                p.color = 0xFFFFFFFF.toInt()
                p.alpha = ((0.15f - shine) / 0.15f * 255).toInt()
                c.drawCircle(ix + wob, top + 12 * d + len, 2.2f * d, p)
                p.alpha = 255
            }
            ix += (18 + (i * 29 % 5) * 5) * d
            i++
        }
    }

    /** Сугробы в нижних углах экрана с голубой тенью и искрами. */
    fun cornerDrifts(c: Canvas, p: Paint, w: Float, h: Float, d: Float, t: Float) {
        fun drift(cx: Float, rx: Float, ry: Float) {
            p.shader = RadialGradient(cx, h, rx, 0xFFFFFFFF.toInt(), 0xFFDBEAFE.toInt(), Shader.TileMode.CLAMP)
            c.drawOval(cx - rx, h - ry, cx + rx, h + ry, p)
            p.shader = null
        }
        drift(0f, w * 0.42f, h * 0.11f)
        drift(w * 0.18f, w * 0.22f, h * 0.075f)
        drift(w, w * 0.45f, h * 0.12f)
        drift(w * 0.8f, w * 0.2f, h * 0.07f)
        p.color = 0xFFFFFFFF.toInt()
        for (k in 0 until 8) {
            val sx = if (k < 4) w * (0.04f + k * 0.07f) else w * (0.7f + (k - 4) * 0.07f)
            val sy = h - h * (0.03f + (k % 3) * 0.025f)
            val a = ((sin(t * 3f + k * 1.3f) + 1) / 2 * 255).toInt()
            p.alpha = a
            c.drawCircle(sx, sy, 1.8f * d, p)
        }
        p.alpha = 255
    }

    /**
     * Новогодняя шапочка, «надетая» на круглый значок с центром (cx, cy) и радиусом r:
     * опушка идёт по дуге верха значка, колпак свисает вправо, на конце - помпон.
     */
    fun santaHat(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
        fun pt(a: Float, rr: Float): Pair<Float, Float> {
            val rad = Math.toRadians(a.toDouble())
            return (cx + rr * cos(rad).toFloat()) to (cy + rr * sin(rad).toFloat())
        }
        val (lx, ly) = pt(205f, r * 0.92f)
        val (rx, ry) = pt(328f, r * 0.92f)
        val tipX = cx + r * 1.05f
        val tipY = cy - r * 0.95f
        tmp.reset()
        tmp.moveTo(lx, ly)
        tmp.cubicTo(cx - r * 0.55f, cy - r * 1.6f, cx + r * 0.6f, cy - r * 1.85f, tipX, tipY)
        tmp.cubicTo(cx + r * 0.78f, cy - r * 1.22f, cx + r * 0.62f, cy - r * 1.0f, rx, ry)
        var a = 328f
        while (a >= 205f) {
            val (x, y) = pt(a, r * 0.92f)
            tmp.lineTo(x, y)
            a -= 4f
        }
        tmp.close()
        p.shader = LinearGradient(cx - r, cy - r * 1.8f, cx + r, cy - r * 0.4f, 0xFFEF4444.toInt(), 0xFF991B1B.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(tmp, p)
        p.shader = null
        // Опушка: толстая белая дуга с лёгкой тенью.
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * 0.32f
        p.color = 0x33000000
        c.drawArc(cx - r * 0.9f, cy - r * 0.9f + r * 0.05f, cx + r * 0.9f, cy + r * 0.9f + r * 0.05f, 202f, 132f, false, p)
        p.shader = LinearGradient(0f, cy - r, 0f, cy - r * 0.6f, 0xFFFFFFFF.toInt(), 0xFFE2E8F0.toInt(), Shader.TileMode.CLAMP)
        c.drawArc(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f, 202f, 132f, false, p)
        p.shader = null
        p.style = Paint.Style.FILL
        // Помпон.
        p.shader = RadialGradient(tipX - r * 0.06f, tipY - r * 0.06f, r * 0.22f, 0xFFFFFFFF.toInt(), 0xFFCBD5E1.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(tipX, tipY, r * 0.2f, p)
        p.shader = null
    }

    // ---------------- весна и лето (4.2.0) ----------------

    /** Палитра лепестков сакуры: (светлый, тёмный). */
    val PETAL_COLORS = arrayOf(
        intArrayOf(0xFFFFF1F7.toInt(), 0xFFF9A8D4.toInt()),
        intArrayOf(0xFFFCE7F3.toInt(), 0xFFF472B6.toInt()),
        intArrayOf(0xFFFFFFFF.toInt(), 0xFFFBCFE8.toInt()),
        intArrayOf(0xFFFDF2F8.toInt(), 0xFFEC4899.toInt()),
    )

    private val petal = Path().apply {
        // Лепесток сакуры: округлый, с вырезом-сердечком на конце.
        moveTo(0f, 1f)
        cubicTo(0.75f, 0.55f, 0.7f, -0.6f, 0.22f, -0.95f)
        lineTo(0f, -0.72f)
        lineTo(-0.22f, -0.95f)
        cubicTo(-0.7f, -0.6f, -0.75f, 0.55f, 0f, 1f)
        close()
    }
    private val petalShaders = HashMap<IntArray, LinearGradient>()

    /** Лепесток сакуры: кружится и «переворачивается» ([flip] −1…1), как в полёте. */
    fun petal(c: Canvas, p: Paint, x: Float, y: Float, size: Float, angle: Float, flip: Float, colors: IntArray, alpha: Int) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        c.scale(size * (0.3f + 0.7f * kotlin.math.abs(flip)), size)
        p.style = Paint.Style.FILL
        p.shader = petalShaders.getOrPut(colors) { LinearGradient(0f, 1f, 0f, -1f, colors[0], colors[1], Shader.TileMode.CLAMP) }
        p.alpha = alpha
        c.drawPath(petal, p)
        p.shader = null
        c.restore()
    }

    /** Цвета бабочек: (крылья, кромка). */
    val BUTTERFLY_COLORS = arrayOf(
        intArrayOf(0xFFFDE047.toInt(), 0xFFEA580C.toInt()),
        intArrayOf(0xFF7DD3FC.toInt(), 0xFF1D4ED8.toInt()),
        intArrayOf(0xFFF9A8D4.toInt(), 0xFFBE185D.toInt()),
        intArrayOf(0xFFBBF7D0.toInt(), 0xFF15803D.toInt()),
    )

    /** Бабочка: крылья машут ([flap] 0…1 - раскрытие). */
    fun butterfly(c: Canvas, p: Paint, x: Float, y: Float, size: Float, angle: Float, flap: Float, colors: IntArray, alpha: Int) {
        c.save()
        c.translate(x, y)
        c.rotate(angle)
        val open = 0.25f + 0.75f * flap
        p.style = Paint.Style.FILL
        for (sgn in intArrayOf(-1, 1)) {
            c.save()
            c.scale(sgn * open, 1f)
            p.color = colors[0]
            p.alpha = alpha
            c.drawOval(0.05f * size, -size, size * 1.05f, size * 0.15f, p)
            c.drawOval(0.05f * size, -size * 0.05f, size * 0.8f, size * 0.75f, p)
            p.color = colors[1]
            p.alpha = (alpha * 0.85f).toInt()
            c.drawCircle(size * 0.7f, -size * 0.5f, size * 0.16f, p)
            c.restore()
        }
        p.color = 0xFF1F2937.toInt()
        p.alpha = alpha
        c.drawRoundRect(-size * 0.07f, -size * 0.75f, size * 0.07f, size * 0.7f, size * 0.07f, size * 0.07f, p)
        c.restore()
    }

    /** Светлячок / солнечный блик: тёплая точка со свечением. */
    fun firefly(c: Canvas, p: Paint, x: Float, y: Float, r: Float, glow: Float, alpha: Int) {
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(x, y, r * 4f, ((0xAA * glow).toInt().coerceIn(0, 255) shl 24) or 0xFDE68A, 0x00FDE68A, Shader.TileMode.CLAMP)
        p.alpha = alpha
        c.drawCircle(x, y, r * 4f, p)
        p.shader = null
        p.color = 0xFFFFFBEB.toInt()
        p.alpha = alpha
        c.drawCircle(x, y, r, p)
    }

    /** Цветок (заставка весной): пять лепестков вокруг серединки. */
    fun flower(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, rot: Float) {
        for (k in 0 until 5) {
            val a = Math.toRadians((rot + k * 72).toDouble())
            petal(c, p, cx + (kotlin.math.cos(a) * r * 0.55f).toFloat(), cy + (kotlin.math.sin(a) * r * 0.55f).toFloat(), r * 0.5f, rot + k * 72 + 90, 1f, PETAL_COLORS[k % PETAL_COLORS.size], 255)
        }
        p.color = 0xFFFACC15.toInt()
        p.alpha = 255
        c.drawCircle(cx, cy, r * 0.25f, p)
    }

    /** Солнышко (заставка летом): круг с лучами. */
    fun sun(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float, rot: Float) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = r * 0.16f
        p.color = 0xFFFBBF24.toInt()
        p.alpha = 255
        for (k in 0 until 10) {
            val a = Math.toRadians((rot + k * 36).toDouble())
            c.drawLine(cx + (kotlin.math.cos(a) * r * 0.75f).toFloat(), cy + (kotlin.math.sin(a) * r * 0.75f).toFloat(), cx + (kotlin.math.cos(a) * r * 1.05f).toFloat(), cy + (kotlin.math.sin(a) * r * 1.05f).toFloat(), p)
        }
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - r * 0.15f, cy - r * 0.15f, r * 0.6f, 0xFFFEF3C7.toInt(), 0xFFF59E0B.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.55f, p)
        p.shader = null
    }
}
