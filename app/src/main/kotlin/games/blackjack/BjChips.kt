// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.blackjack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.SparseArray
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Фишки стола в стиле монеты мессенджера: цветное тело с объёмным градиентом, белые вставки
 * по ребру, золотой ободок как у монеты, внутренний диск с выпуклой («выдавленной») буквой «S»
 * и лентой номинала. Номиналы 5 / 10 / 25 / 50 / 100 - каждый своего цвета. Фишки - это те же
 * монеты за столом.
 *
 * Картинки рисуются программно один раз на (номинал, размер) и кэшируются: в кадре - только
 * готовые битмапы, без выделения памяти.
 */
object BjChips {
    val VALUES = intArrayOf(5, 10, 25, 50, 100)

    /** Цвета тела фишки [светлый, основной, тёмный] по номиналам. */
    private val BODY = arrayOf(
        intArrayOf(0xFFFF9B9B.toInt(), 0xFFE11D48.toInt(), 0xFF7F1D1D.toInt()), // 5 - алая
        intArrayOf(0xFF9CC9FF.toInt(), 0xFF2563EB.toInt(), 0xFF1E3A8A.toInt()), // 10 - синяя
        intArrayOf(0xFF8EF0B4.toInt(), 0xFF16A34A.toInt(), 0xFF14532D.toInt()), // 25 - изумрудная
        intArrayOf(0xFF7B8494.toInt(), 0xFF1F2937.toInt(), 0xFF05070C.toInt()), // 50 - графитовая
        intArrayOf(0xFFE2C2FF.toInt(), 0xFF9333EA.toInt(), 0xFF3B0764.toInt()), // 100 - фиолетовая
    )

    private val cache = SparseArray<Bitmap>()

    fun color(index: Int): Int = BODY[index.coerceIn(0, BODY.size - 1)][1]

    /** Готовая картинка фишки номинала VALUES[index] размером [size] пикселей. */
    fun bitmap(index: Int, size: Int): Bitmap {
        val i = index.coerceIn(0, VALUES.size - 1)
        val sz = size.coerceIn(8, 1024)
        val key = i * 10_000 + sz
        synchronized(cache) { cache.get(key)?.let { return it } }
        val bmp = draw(i, sz)
        synchronized(cache) { cache.put(key, bmp) }
        return bmp
    }

    /** Освободить картинки (экран закрыт). */
    fun clear() = synchronized(cache) { cache.clear() }

    private fun draw(idx: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val col = BODY[idx]
        val cx = size / 2f
        val cy = size / 2f
        val r = size / 2f - maxOf(1f, size * 0.03f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // Мягкая тень под фишкой (внутри картинки).
        p.color = 0x50000000
        c.drawCircle(cx + r * 0.02f, cy + r * 0.05f, r, p)
        // Тело: объёмный градиент от светлого края к тёмному.
        p.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.4f, r * 1.55f, intArrayOf(col[0], col[1], col[2]), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        // Белые вставки по ребру (6 полос), как у настоящих фишек.
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = r * 0.2f
        p.color = 0xF2FFFFFF.toInt()
        val band = RectF(cx - r * 0.84f, cy - r * 0.84f, cx + r * 0.84f, cy + r * 0.84f)
        for (k in 0 until 6) c.drawArc(band, k * 60f - 10f, 20f, false, p)
        // Тонкие цветные риски внутри вставок.
        p.strokeWidth = maxOf(1f, r * 0.05f)
        p.color = (col[1] and 0x00FFFFFF) or 0xAA000000.toInt()
        for (k in 0 until 6) {
            val a = Math.toRadians(k * 60.0)
            c.drawLine(
                cx + (r * 0.75f * cos(a)).toFloat(), cy + (r * 0.75f * sin(a)).toFloat(),
                cx + (r * 0.93f * cos(a)).toFloat(), cy + (r * 0.93f * sin(a)).toFloat(), p,
            )
        }
        // Золотой ободок - как у монеты мессенджера.
        p.strokeWidth = r * 0.08f
        p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, intArrayOf(0xFFFFF4B0.toInt(), 0xFFFCC531.toInt(), 0xFFFFE27A.toInt(), 0xFFB07800.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r - p.strokeWidth / 2, p)
        // Внутреннее кольцо (пунктир) и золотой диск, как лицо монеты.
        p.shader = null
        p.strokeWidth = maxOf(1f, r * 0.035f)
        p.color = 0xCCFFFFFF.toInt()
        val ring = RectF(cx - r * 0.66f, cy - r * 0.66f, cx + r * 0.66f, cy + r * 0.66f)
        for (k in 0 until 24) c.drawArc(ring, k * 15f, 9f, false, p)
        p.style = Paint.Style.FILL
        val inner = r * 0.6f
        p.shader = RadialGradient(cx - inner * 0.35f, cy - inner * 0.4f, inner * 1.5f, intArrayOf(0xFFFFF4B0.toInt(), 0xFFFCC531.toInt(), 0xFFC98A06.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, inner, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = maxOf(1f, inner * 0.07f)
        p.color = 0xFFB07800.toInt()
        c.drawCircle(cx, cy, inner - p.strokeWidth / 2, p)
        p.style = Paint.Style.FILL
        // Выпуклая «S»: тёмная тень снизу-справа, светлый блик сверху-слева, золото поверх.
        val big = size >= 28
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = inner * (if (big) 1.32f else 1.55f)
        }
        val sy = if (big) cy + inner * 0.3f else cy + s.textSize * 0.36f
        val off = maxOf(0.7f, inner * 0.05f)
        s.color = 0xFF8A5A00.toInt()
        c.drawText("S", cx + off, sy + off, s)
        s.color = 0xFFFFFBEA.toInt()
        c.drawText("S", cx - off * 0.6f, sy - off * 0.6f, s)
        s.shader = LinearGradient(0f, cy - inner * 0.7f, 0f, cy + inner * 0.4f, 0xFFFFE89A.toInt(), 0xFFD9A21B.toInt(), Shader.TileMode.CLAMP)
        c.drawText("S", cx, sy, s)
        // Лента номинала по низу диска (на крупных фишках) - читается на любом цвете.
        if (big) {
            val clip = Path().apply { addCircle(cx, cy, inner * 0.94f, Path.Direction.CW) }
            c.save()
            c.clipPath(clip)
            p.color = (col[2] and 0x00FFFFFF) or 0xE6000000.toInt()
            c.drawRect(cx - inner, cy + inner * 0.36f, cx + inner, cy + inner, p)
            c.restore()
            val label = VALUES[idx].toString()
            val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
                textSize = inner * (if (label.length >= 3) 0.42f else 0.5f)
                color = 0xFFFFFFFF.toInt()
            }
            c.drawText(label, cx, cy + inner * 0.36f + (inner * 0.6f + t.textSize * 0.7f) / 2f, t)
        }
        // Блик сверху.
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.55f, r * 0.75f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        return bmp
    }

    /**
     * Разложить сумму на фишки (крупные - внизу стопки). В [out] - индексы номиналов снизу
     * вверх; возвращается число фишек (не больше [max]).
     */
    fun stack(amount: Int, max: Int, out: IntArray): Int {
        var left = amount
        var k = 0
        val lim = minOf(max, out.size)
        for (i in VALUES.indices.reversed()) {
            val v = VALUES[i]
            while (left >= v && k < lim) {
                left -= v
                out[k++] = i
            }
        }
        // Остаток меньше 5 (нечётные ставки после 6:5) - ещё одна мелкая фишка.
        if (left > 0 && k < lim) out[k++] = 0
        return k
    }

    /** Кнопка-фишка для выбора ставки: нажатие - фишка «вдавливается» и пружинит обратно. */
    @SuppressLint("ViewConstructor")
    class ChipView(ctx: Context, val index: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var bmp: Bitmap? = null

        init {
            isClickable = true
            isFocusable = true
            contentDescription = "S " + VALUES[index.coerceIn(0, VALUES.size - 1)]
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            bmp = if (w > 0 && h > 0) bitmap(index, minOf(w, h)) else null
        }

        override fun onDraw(c: Canvas) {
            val b = bmp ?: return
            c.drawBitmap(b, (width - b.width) / 2f, (height - b.height) / 2f, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isEnabled && com.stoyko.smessenger.ui.Kinetics.enabled) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }
            }
            return super.onTouchEvent(event)
        }

        /** Фишка «подпрыгивает» (ставку добавили). */
        fun pop() {
            if (!com.stoyko.smessenger.ui.Kinetics.enabled) return
            animate().cancel()
            scaleX = 1.18f
            scaleY = 1.18f
            animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(2.5f)).start()
        }
    }

    /**
     * Значок для магазина: несколько стопок фишек, верхние фишки чуть «дышат». Картинки
     * готовятся один раз; без анимации (экономия батареи) - просто стоят.
     */
    @SuppressLint("ViewConstructor")
    class StackIcon(ctx: Context, private val variant: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val start = SystemClock.uptimeMillis()
        private var chip = 0
        private val cols: Array<IntArray> = when (variant % 3) {
            0 -> arrayOf(intArrayOf(0, 0, 1), intArrayOf(1, 1, 2, 2))
            1 -> arrayOf(intArrayOf(1, 2, 2), intArrayOf(2, 3, 3, 3), intArrayOf(0, 1, 1))
            else -> arrayOf(intArrayOf(3, 3, 4), intArrayOf(4, 4, 4, 4, 4), intArrayOf(2, 3, 3, 4))
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            chip = (minOf(w, h) * 0.5f).toInt().coerceAtLeast(8)
        }

        override fun onDraw(c: Canvas) {
            if (chip <= 0) return
            val anim = com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on
            val t = (SystemClock.uptimeMillis() - start) / 1000f
            val n = cols.size
            val step = chip * 0.17f
            val span = width - chip
            for (k in 0 until n) {
                val x = if (n == 1) (width - chip) / 2f else span * k / (n - 1).toFloat()
                val list = cols[k]
                val baseY = height - chip * 1.02f - (if (k == n / 2 && n > 2) 0f else chip * 0.04f)
                for (j in list.indices) {
                    val top = j == list.size - 1
                    val bob = if (anim && top) sin(t * 3f + k * 1.7f + variant) * chip * 0.06f else 0f
                    c.drawBitmap(bitmap(list[j], chip), x, baseY - j * step + bob, paint)
                }
            }
            if (anim && isShown) postInvalidateDelayed(com.stoyko.smessenger.ui.LiteMode.liveFrameMs)
        }
    }
}
