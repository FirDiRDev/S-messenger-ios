// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Эффекты скинов Змейки поверх (и под) телом: неоновое свечение, огненный хвост, звёздная пыль,
 * чешуя, россыпь галактики, молнии, пузырьки яда, угольки, блеск самоцвета, иней.
 *
 * Один объект на вид (поле игры или предпросмотр в магазине): кисти, путь и массивы точек
 * создаются один раз и переиспользуются - в onDraw ничего не выделяется, 60 кадров/с держатся
 * и на слабых телефонах. В режиме экономии ([lite]) частиц вдвое меньше.
 *
 * Точки тела передаются от головы (0) к хвосту: [begin], затем [pt] для каждой.
 */
class SnakeSkinFx {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()
    private var xs = FloatArray(64)
    private var ys = FloatArray(64)
    private var n = 0

    fun begin(count: Int) {
        if (xs.size < count) {
            xs = FloatArray(count * 2)
            ys = FloatArray(count * 2)
        }
        n = count
    }

    fun pt(i: Int, x: Float, y: Float) {
        xs[i] = x
        ys[i] = y
    }

    /** Эффекты под телом (свечение). [w] - толщина тела у головы, [t] - время, мс. */
    fun under(c: Canvas, skin: SnakeGame.Skin, w: Float, t: Long, lite: Boolean) {
        if (n < 2) return
        when (skin.fx) {
            FX_GLOW -> glow(c, skin, w, t)
        }
    }

    /** Эффекты поверх тела (до головы). */
    fun over(c: Canvas, skin: SnakeGame.Skin, w: Float, t: Long, lite: Boolean) {
        if (n < 2) return
        when (skin.fx) {
            FX_FLAME -> flame(c, w, t, lite)
            FX_SPARKLE -> sparkle(c, w, t, lite)
            FX_SCALES -> scales(c, w)
            FX_DOTS -> dots(c, w, t, lite)
            FX_ZAP -> zap(c, skin, w, t)
            FX_BUBBLES -> rising(c, skin, w, t, lite, embers = false)
            FX_EMBERS -> rising(c, skin, w, t, lite, embers = true)
            FX_SHINE -> shine(c, w, t)
            FX_SNOW -> snow(c, w, t, lite)
        }
    }

    // ---------------- эффекты ----------------

    private fun polyline() {
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until n) path.lineTo(xs[i], ys[i])
    }

    /** Неон: два мягких слоя свечения вокруг тела, пульсирует. */
    private fun glow(c: Canvas, skin: SnakeGame.Skin, w: Float, t: Long) {
        val col = if (skin.c3 != 0) skin.c3 else skin.from
        val pulse = 0.75f + 0.25f * sin(t / 300f)
        polyline()
        line.color = col
        line.alpha = (55 * pulse).toInt()
        line.strokeWidth = w * 2.1f
        c.drawPath(path, line)
        line.alpha = (95 * pulse).toInt()
        line.strokeWidth = w * 1.45f
        c.drawPath(path, line)
        line.alpha = 255
    }

    /** Огненный хвост: языки пламени срываются с кончика хвоста и гаснут. */
    private fun flame(c: Canvas, w: Float, t: Long, lite: Boolean) {
        val tx = xs[n - 1]
        val ty = ys[n - 1]
        var dx = tx - xs[n - 2]
        var dy = ty - ys[n - 2]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.01f) {
            dx = 0f
            dy = -1f
        } else {
            dx /= len
            dy /= len
        }
        val px = -dy
        val py = dx
        val count = if (lite) 4 else 8
        val sec = t / 1000f
        for (k in 0 until count) {
            val ph = (sec * 2.6f + k.toFloat() / count) % 1f
            val dist = ph * w * 2.4f
            val wig = sin(sec * 11f + k * 2.1f) * w * 0.28f * ph
            // Пламя чуть поднимается вверх - как настоящее.
            val x = tx + dx * dist + px * wig
            val y = ty + dy * dist + py * wig - ph * w * 0.6f
            val r = w * 0.42f * (1f - ph * 0.75f)
            fill.color = if (ph < 0.5f) mix(0xFFFEF08A.toInt(), 0xFFF97316.toInt(), ph * 2f) else mix(0xFFF97316.toInt(), 0xFFDC2626.toInt(), (ph - 0.5f) * 2f)
            fill.alpha = ((1f - ph) * 220).toInt()
            c.drawCircle(x, y, r, fill)
        }
        // Раскалённый кончик хвоста.
        fill.color = 0xFFFFF7AE.toInt()
        fill.alpha = (170 + 60 * sin(t / 70f)).toInt().coerceIn(0, 255)
        c.drawCircle(tx, ty, w * 0.22f, fill)
        fill.alpha = 255
    }

    /** Звёздная пыль: четырёхлучевые звёздочки вспыхивают и гаснут вдоль тела. */
    private fun sparkle(c: Canvas, w: Float, t: Long, lite: Boolean) {
        val step = if (lite) 4 else 2
        line.color = 0xFFFFFFFF.toInt()
        fill.color = 0xFFFFFFFF.toInt()
        var i = 1
        while (i < n) {
            val h = hash(i * 7919)
            val phase = (h and 1023) / 1023f * 6.283f
            val a = sin(t / 260f + phase)
            if (a > 0.2f) {
                val k = (a - 0.2f) / 0.8f
                val (ox, oy) = perp(i, ((hash(i * 31) and 255) / 255f - 0.5f) * w * 0.55f)
                val x = xs[i] + ox
                val y = ys[i] + oy
                val s = w * 0.28f * k
                line.alpha = (235 * k).toInt()
                line.strokeWidth = w * 0.06f
                c.drawLine(x - s, y, x + s, y, line)
                c.drawLine(x, y - s, x, y + s, line)
                fill.alpha = (255 * k).toInt()
                c.drawCircle(x, y, w * 0.06f, fill)
            }
            i += step
        }
        line.alpha = 255
        fill.alpha = 255
    }

    /** Чешуя: полукруглые чешуйки, обращённые к хвосту. */
    private fun scales(c: Canvas, w: Float) {
        line.color = 0x66000000
        line.strokeWidth = w * 0.07f
        val r = w * 0.28f
        for (i in 1 until n) {
            val a = Math.toDegrees(kotlin.math.atan2((ys[i - 1] - ys[i]).toDouble(), (xs[i - 1] - xs[i]).toDouble())).toFloat()
            val sz = r * (1f - 0.3f * i / n)
            oval.set(xs[i] - sz, ys[i] - sz, xs[i] + sz, ys[i] + sz)
            // Дуга - со стороны хвоста, концами к голове.
            c.drawArc(oval, a + 110f, 140f, false, line)
        }
        line.color = 0x40FFFFFF
        line.strokeWidth = w * 0.045f
        for (i in 1 until n step 2) {
            val sz = r * 0.55f * (1f - 0.3f * i / n)
            val a = Math.toDegrees(kotlin.math.atan2((ys[i - 1] - ys[i]).toDouble(), (xs[i - 1] - xs[i]).toDouble())).toFloat()
            oval.set(xs[i] - sz, ys[i] - sz, xs[i] + sz, ys[i] + sz)
            c.drawArc(oval, a + 130f, 100f, false, line)
        }
        line.alpha = 255
    }

    /** Галактика: разноцветные звёзды-точки мерцают по всему телу. */
    private fun dots(c: Canvas, w: Float, t: Long, lite: Boolean) {
        val per = if (lite) 1 else 2
        for (i in 1 until n) {
            for (j in 0 until per) {
                val h = hash(i * 131 + j * 977)
                val off = ((h and 255) / 255f - 0.5f) * w * 0.7f
                val (ox, oy) = perp(i, off)
                val tw = 0.45f + 0.55f * (0.5f + 0.5f * sin(t / 380f + ((h ushr 8) and 255) / 40f))
                fill.color = DOT_COLORS[(h ushr 16 and 0x7FFF) % DOT_COLORS.size]
                fill.alpha = (255 * tw).toInt()
                c.drawCircle(xs[i] + ox, ys[i] + oy, w * (0.05f + 0.05f * ((h ushr 4) and 3) / 3f), fill)
            }
        }
        fill.alpha = 255
    }

    /** Электро: каждые полсекунды по телу пробегает ломаная молния. */
    private fun zap(c: Canvas, skin: SnakeGame.Skin, w: Float, t: Long) {
        val period = t / 520
        if (t % 520 > 170 || n < 3) return
        val seed = hash(period.toInt() * 2654435)
        val span = minOf(7, n - 1)
        val i0 = ((seed ushr 1) % (n - span).coerceAtLeast(1)).coerceAtLeast(0)
        path.reset()
        for (j in 0..span) {
            val i = (i0 + j).coerceAtMost(n - 1)
            val off = if (j == 0 || j == span) 0f else (((hash(seed + j * 31) and 255) / 255f) - 0.5f) * w * 0.9f
            val (ox, oy) = perp(i, off)
            if (j == 0) path.moveTo(xs[i] + ox, ys[i] + oy) else path.lineTo(xs[i] + ox, ys[i] + oy)
        }
        val k = 1f - (t % 520) / 170f
        line.color = if (skin.c3 != 0) skin.c3 else 0xFFE0F2FE.toInt()
        line.alpha = (90 * k).toInt()
        line.strokeWidth = w * 0.3f
        c.drawPath(path, line)
        line.color = 0xFFFFFFFF.toInt()
        line.alpha = (255 * k).toInt()
        line.strokeWidth = w * 0.08f
        c.drawPath(path, line)
        line.alpha = 255
    }

    /** Пузырьки яда (кольца) или угольки (искры) поднимаются от тела и гаснут. */
    private fun rising(c: Canvas, skin: SnakeGame.Skin, w: Float, t: Long, lite: Boolean, embers: Boolean) {
        val count = if (lite) 3 else 7
        val sec = t / 1400f
        for (k in 0 until count) {
            val life = sec + k.toFloat() / count
            val cycle = life.toInt()
            val ph = life - cycle
            val idx = (hash(cycle * 131 + k * 7) ushr 1) % n
            val x = xs[idx] + sin(ph * 6f + k) * w * 0.25f
            val y = ys[idx] - ph * w * 2.6f
            if (embers) {
                fill.color = if (k % 2 == 0) 0xFFFDE047.toInt() else 0xFFF97316.toInt()
                fill.alpha = ((1f - ph) * 240).toInt()
                c.drawCircle(x, y, w * 0.09f * (1f - ph * 0.6f), fill)
            } else {
                line.color = if (skin.c3 != 0) skin.c3 else 0xFFBEF264.toInt()
                line.alpha = ((1f - ph) * 220).toInt()
                line.strokeWidth = w * 0.06f
                c.drawCircle(x, y, w * (0.1f + 0.1f * ph), line)
            }
        }
        fill.alpha = 255
        line.alpha = 255
    }

    /** Самоцвет: глянцевый блик вдоль тела и бегущая искра. */
    private fun shine(c: Canvas, w: Float, t: Long) {
        path.reset()
        val sh = w * 0.17f
        path.moveTo(xs[0] - sh, ys[0] - sh)
        for (i in 1 until n) path.lineTo(xs[i] - sh * (1f - 0.3f * i / n), ys[i] - sh * (1f - 0.3f * i / n))
        line.color = 0xFFFFFFFF.toInt()
        line.alpha = 70
        line.strokeWidth = w * 0.16f
        c.drawPath(path, line)
        line.alpha = 255
        // Искра бежит от головы к хвосту.
        val pos = ((t / 38) % (n + 14)).toInt() - 4
        if (pos in 1 until n) {
            fill.color = 0xFFFFFFFF.toInt()
            fill.alpha = 210
            c.drawCircle(xs[pos] - sh, ys[pos] - sh, w * 0.16f, fill)
            fill.alpha = 90
            c.drawCircle(xs[pos] - sh, ys[pos] - sh, w * 0.32f, fill)
            fill.alpha = 255
        }
    }

    /** Иней: шестилучевые снежинки медленно вращаются на теле. */
    private fun snow(c: Canvas, w: Float, t: Long, lite: Boolean) {
        val step = if (lite) 6 else 3
        line.color = 0xFFFFFFFF.toInt()
        line.alpha = 210
        line.strokeWidth = w * 0.045f
        var i = 2
        while (i < n) {
            val r = w * 0.2f
            val a0 = t / 1500f + i
            for (k in 0 until 3) {
                val a = a0 + k * 1.0472f
                val dx = cos(a) * r
                val dy = sin(a) * r
                c.drawLine(xs[i] - dx, ys[i] - dy, xs[i] + dx, ys[i] + dy, line)
            }
            i += step
        }
        line.alpha = 255
    }

    // ---------------- помощники ----------------

    /** Смещение на [off] поперёк тела в точке [i] (пара без выделения памяти). */
    private fun perp(i: Int, off: Float): Pair2 {
        val j = if (i > 0) i - 1 else 1.coerceAtMost(n - 1)
        var dx = xs[i] - xs[j]
        var dy = ys[i] - ys[j]
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) {
            dx = 1f
            dy = 0f
        } else {
            dx /= len
            dy /= len
        }
        return Pair2(-dy * off, dx * off)
    }

    /** Пара чисел без выделения памяти (value class). */
    @JvmInline
    value class Pair2(private val packed: Long) {
        constructor(a: Float, b: Float) : this((a.toRawBits().toLong() shl 32) or (b.toRawBits().toLong() and 0xFFFFFFFFL))

        operator fun component1(): Float = Float.fromBits((packed ushr 32).toInt())
        operator fun component2(): Float = Float.fromBits(packed.toInt())
    }

    companion object {
        const val FX_NONE = 0
        const val FX_GLOW = 1
        const val FX_FLAME = 2
        const val FX_SPARKLE = 3
        const val FX_SCALES = 4
        const val FX_DOTS = 5
        const val FX_ZAP = 6
        const val FX_BUBBLES = 7
        const val FX_EMBERS = 8
        const val FX_SHINE = 9
        const val FX_SNOW = 10

        private val DOT_COLORS = intArrayOf(0xFFFFFFFF.toInt(), 0xFFA5B4FC.toInt(), 0xFFF0ABFC.toInt(), 0xFF67E8F9.toInt(), 0xFFFDE68A.toInt())

        /** Быстрый целочисленный хеш (стабильный «случай» для каждого сегмента). */
        fun hash(x: Int): Int {
            var h = x * -0x61c88647
            h = h xor (h ushr 15)
            h *= 0x2c1b3c6d
            h = h xor (h ushr 12)
            return h and 0x7FFFFFFF
        }

        fun mix(a: Int, b: Int, k: Float): Int {
            val q = k.coerceIn(0f, 1f)
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - q) + ((b shr s) and 0xFF) * q).toInt() shl s
            return 0xFF000000.toInt() or ch(16) or ch(8) or ch(0)
        }
    }
}
