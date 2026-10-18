// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Кристаллы «Три в ряд» - рисуются кодом, один раз (в картинки), каждый своей огранкой:
 *  0 красный - «сердце» (рубин), 1 синий - квадратная огранка (сапфир), 2 зелёный - изумрудная
 *  (восьмиугольник), 3 жёлтый - круглый бриллиант (цитрин), 4 фиолетовый - «капля» (аметист),
 *  5 бирюзовый - треугольник (топаз).
 * У каждого: внешний контур, «площадка» в центре, грани между ними светлые/тёмные по кругу,
 * глянцевый блик, искра и мягкая тень. Плюс белый силуэт - для живого «блеска» на поле.
 */
object GemArt {
    /** Основной, светлый и тёмный цвет каждого вида. */
    private val COLORS = arrayOf(
        intArrayOf(0xFFEF233C.toInt(), 0xFFFF8FA3.toInt(), 0xFF7A0019.toInt()),
        intArrayOf(0xFF2F6BFF.toInt(), 0xFF9CC3FF.toInt(), 0xFF0B2A80.toInt()),
        intArrayOf(0xFF1FBF5C.toInt(), 0xFF8EF5B4.toInt(), 0xFF0A5A2A.toInt()),
        intArrayOf(0xFFFFC21A.toInt(), 0xFFFFF1A8.toInt(), 0xFF9A5B00.toInt()),
        intArrayOf(0xFFA34BFF.toInt(), 0xFFE0B8FF.toInt(), 0xFF4B0C8F.toInt()),
        intArrayOf(0xFF14C8C8.toInt(), 0xFF9BF6F0.toInt(), 0xFF07595F.toInt()),
    )

    fun render(kind: Int, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val cx = size / 2f
        val cy = size / 2f
        val r = size * 0.42f
        val outer = outline(kind, cx, cy, r)
        val table = outline(kind, cx, cy - r * 0.04f, r * 0.52f)
        val col = COLORS[kind.coerceIn(0, COLORS.lastIndex)]
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Тень.
        p.color = 0x66000000
        p.maskFilter = BlurMaskFilter(size * 0.04f, BlurMaskFilter.Blur.NORMAL)
        c.save()
        c.translate(size * 0.015f, size * 0.035f)
        c.drawPath(path(outer), p)
        c.restore()
        p.maskFilter = null
        // Основа.
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.5f, intArrayOf(col[1], col[0], col[2]), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path(outer), p)
        p.shader = null
        // Грани: от каждой стороны контура к соответствующей стороне площадки.
        val n = outer.size / 2
        for (k in 0 until n) {
            val k2 = (k + 1) % n
            val f = Path().apply {
                moveTo(outer[k * 2], outer[k * 2 + 1])
                lineTo(outer[k2 * 2], outer[k2 * 2 + 1])
                lineTo(table[k2 * 2], table[k2 * 2 + 1])
                lineTo(table[k * 2], table[k * 2 + 1])
                close()
            }
            // Свет сверху-слева: грани, смотрящие туда, светлее.
            val mx = (outer[k * 2] + outer[k2 * 2]) / 2 - cx
            val my = (outer[k * 2 + 1] + outer[k2 * 2 + 1]) / 2 - cy
            val light = (-(mx + my) / (r * 1.4f)).coerceIn(-1f, 1f)
            p.color = if (light > 0) blend(col[0], col[1], light * 0.75f) else blend(col[0], col[2], -light * 0.7f)
            p.alpha = 235
            c.drawPath(f, p)
            p.alpha = 255
        }
        // Площадка.
        p.shader = LinearGradient(cx - r * 0.5f, cy - r * 0.5f, cx + r * 0.5f, cy + r * 0.5f, intArrayOf(blend(col[1], Color.WHITE, 0.35f), col[0], blend(col[0], col[2], 0.3f)), null, Shader.TileMode.CLAMP)
        c.drawPath(path(table), p)
        p.shader = null
        // Рёбра.
        p.style = Paint.Style.STROKE
        p.strokeWidth = size * 0.012f
        p.color = 0x66FFFFFF
        for (k in 0 until n) c.drawLine(outer[k * 2], outer[k * 2 + 1], table[k * 2], table[k * 2 + 1], p)
        c.drawPath(path(table), p)
        p.strokeWidth = size * 0.022f
        p.color = col[2]
        c.drawPath(path(outer), p)
        p.style = Paint.Style.FILL
        // Глянцевый блик и искра.
        p.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.4f, r * 0.42f, 0xCCFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.save()
        c.clipPath(path(outer))
        c.drawOval(cx - r * 0.75f, cy - r * 0.8f, cx + r * 0.1f, cy - r * 0.05f, p)
        c.restore()
        p.shader = null
        p.color = Color.WHITE
        star(c, p, cx - r * 0.28f, cy - r * 0.34f, r * 0.16f)
        return b
    }

    /** Белый силуэт кристалла - для вспышки «блеска». */
    fun silhouette(src: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN) }
        Canvas(b).drawBitmap(src, 0f, 0f, p)
        return b
    }

    private fun path(pts: FloatArray): Path = Path().apply {
        moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            lineTo(pts[i], pts[i + 1])
            i += 2
        }
        close()
    }

    /** Контур огранки (x, y, x, y, ...), по часовой стрелке. */
    private fun outline(kind: Int, cx: Float, cy: Float, r: Float): FloatArray = when (kind) {
        0 -> heart(cx, cy, r)
        1 -> rect(cx, cy, r * 0.9f, r * 0.9f, r * 0.16f)
        2 -> rect(cx, cy, r * 0.68f, r * 0.98f, r * 0.3f)
        3 -> poly(cx, cy, r, 12, 0.0)
        4 -> drop(cx, cy, r)
        else -> poly(cx, cy + r * 0.12f, r * 1.08f, 3, -90.0, rounded = true)
    }

    private fun poly(cx: Float, cy: Float, r: Float, n: Int, startDeg: Double, squash: Float = 1f, square: Boolean = false, rounded: Boolean = false): FloatArray {
        val out = ArrayList<Float>()
        for (k in 0 until n) {
            val a = (startDeg + k * 360.0 / n) * PI / 180
            var x = cos(a).toFloat()
            var y = sin(a).toFloat()
            if (square) {
                // Квадрат со срезанными углами.
                val m = maxOf(kotlin.math.abs(x), kotlin.math.abs(y))
                x = x / m * 0.92f
                y = y / m * 0.92f
            }
            out += cx + x * r
            out += cy + y * r * squash
            if (rounded) {
                // Срезанные вершины треугольника - не колется.
                val a2 = (startDeg + (k + 0.5) * 360.0 / n) * PI / 180
                out += cx + cos(a2).toFloat() * r * 0.62f
                out += cy + sin(a2).toFloat() * r * 0.62f
            }
        }
        return out.toFloatArray()
    }

    /** Прямоугольник со срезанными углами (квадратная и изумрудная огранки). */
    private fun rect(cx: Float, cy: Float, hw: Float, hh: Float, ch: Float): FloatArray = floatArrayOf(
        cx - hw + ch, cy - hh, cx + hw - ch, cy - hh, cx + hw, cy - hh + ch, cx + hw, cy + hh - ch,
        cx + hw - ch, cy + hh, cx - hw + ch, cy + hh, cx - hw, cy + hh - ch, cx - hw, cy - hh + ch,
    )

    private fun heart(cx: Float, cy: Float, r: Float): FloatArray {
        val pts = ArrayList<Float>()
        val n = 18
        for (k in 0 until n) {
            val t = 2 * PI * k / n
            // Классическая кривая сердца, нормированная к радиусу.
            val x = 16 * sin(t).let { it * it * it }
            val y = -(13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))
            pts += cx + (x / 17.0 * r).toFloat()
            pts += cy + (y / 17.0 * r).toFloat() - r * 0.06f
        }
        return pts.toFloatArray()
    }

    private fun drop(cx: Float, cy: Float, r: Float): FloatArray {
        val pts = ArrayList<Float>()
        val n = 9
        for (k in 0 until n) {
            val t = -PI / 2 + 2 * PI * k / n
            val w = if (k == 0) 0.0 else 1.0
            val rr = r * (0.82 + 0.18 * w)
            val x = cos(t) * rr * (if (k == 0) 0.0 else 0.86)
            val y = sin(t) * rr + (if (k == 0) -r * 0.28 else r * 0.1)
            pts += cx + x.toFloat()
            pts += cy + y.toFloat()
        }
        return pts.toFloatArray()
    }

    private fun star(c: Canvas, p: Paint, x: Float, y: Float, r: Float) {
        val s = Path()
        for (k in 0 until 8) {
            val a = k * PI / 4
            val rr = if (k % 2 == 0) r else r * 0.28f
            val px = (x + rr * cos(a)).toFloat()
            val py = (y + rr * sin(a)).toFloat()
            if (k == 0) s.moveTo(px, py) else s.lineTo(px, py)
        }
        s.close()
        c.drawPath(s, p)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * u).toInt().coerceIn(0, 255)
        return Color.argb(255, ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
}
