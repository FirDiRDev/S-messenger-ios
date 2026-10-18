// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * Сезонное оформление (Настройки → Персонализация):
 *  - 🍁 Осень: тёплые оранжевые цвета шапки, падающие листья в шапке и на фоне чатов;
 *  - ❄️ Новый год: холодные цвета, мигающая разноцветная гирлянда вдоль шапки, снег
 *    в шапке, на фоне чатов и в шапке профиля;
 *  - 🌸 Весна: розовые цвета сакуры, кружатся лепестки;
 *  - ☀️ Лето: бирюзовое море, бабочки и солнечные искорки;
 *  - «Авто»: весна - март…май, лето - июнь…август, осень - сентябрь…ноябрь, зима - декабрь…февраль.
 * Всё отключается одной настройкой. В режиме экономии энергии частицы замирают.
 */
object Seasonal {
    enum class Kind { NONE, AUTUMN, WINTER, SPRING, SUMMER }

    private const val PREFS = "ui"
    private const val KEY = "season"

    /** 0 - выкл, 1 - авто, 2 - осень, 3 - зима (новый год), 4 - весна, 5 - лето. */
    /** По умолчанию - «Авто»: приложение само смотрит дату и включает сезон. */
    fun mode(ctx: Context): Int = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY, 1) }.getOrDefault(1)

    /** Акцентный цвет кнопок на время сезона (null - обычный фирменный). */
    fun accent(ctx: Context): IntArray? = when (current(ctx)) {
        Kind.AUTUMN -> intArrayOf(0xFFFB923C.toInt(), 0xFFEA580C.toInt())
        Kind.WINTER -> intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt())
        Kind.SPRING -> intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt())
        Kind.SUMMER -> intArrayOf(0xFF2DD4BF.toInt(), 0xFF0D9488.toInt())
        Kind.NONE -> null
    }

    fun setMode(ctx: Context, m: Int) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY, m).commit()

    fun current(ctx: Context): Kind = when (mode(ctx)) {
        2 -> Kind.AUTUMN
        3 -> Kind.WINTER
        4 -> Kind.SPRING
        5 -> Kind.SUMMER
        1 -> {
            // «Авто» - круглый год: весна (март-май), лето (июнь-август), осень (сентябрь-
            // ноябрь), зима (декабрь-февраль).
            val c = java.util.Calendar.getInstance()
            when (c.get(java.util.Calendar.MONTH)) {
                java.util.Calendar.MARCH, java.util.Calendar.APRIL, java.util.Calendar.MAY -> Kind.SPRING
                java.util.Calendar.JUNE, java.util.Calendar.JULY, java.util.Calendar.AUGUST -> Kind.SUMMER
                java.util.Calendar.SEPTEMBER, java.util.Calendar.OCTOBER, java.util.Calendar.NOVEMBER -> Kind.AUTUMN
                else -> Kind.WINTER
            }
        }
        else -> Kind.NONE
    }

    /** Цвета шапки для сезона (сверху вниз / слева направо). */
    fun headerColors(k: Kind): IntArray? = when (k) {
        Kind.AUTUMN -> intArrayOf(0xFFF97316.toInt(), 0xFFC2410C.toInt())
        Kind.WINTER -> intArrayOf(0xFF38BDF8.toInt(), 0xFF1E3A8A.toInt())
        Kind.SPRING -> intArrayOf(0xFFF9A8D4.toInt(), 0xFFBE185D.toInt())
        Kind.SUMMER -> intArrayOf(0xFF22D3EE.toInt(), 0xFF0F766E.toInt())
        Kind.NONE -> null
    }

    /** Шапка (панель с названием, вкладки): сезонные цвета + частицы (+ гирлянда зимой). */
    fun decorateHeader(v: View?, garland: Boolean = true) {
        if (v == null) return
        val k = current(v.context)
        val colors = headerColors(k) ?: return
        val grad = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
        v.background = LayerDrawable(arrayOf(grad, SeasonDrawable(v.context, k, header = true, garland = garland)))
    }

    /** Общее время для «единого поля» частиц: шапка и чат показывают одни и те же листья. */
    val EPOCH = SystemClock.uptimeMillis()

    /**
     * Фон: поверх обоев чата / шапки, но под сообщениями и кнопками. [shared] - частицы в
     * координатах окна: листья и снег непрерывно летят из шапки в чат, без «обрыва» на границе.
     */
    fun decorateForeground(v: View?, shared: Boolean = false, scene: Boolean = false) {
        if (v == null || android.os.Build.VERSION.SDK_INT < 23) return
        val k = current(v.context)
        if (k == Kind.NONE) {
            if (v.foreground is SeasonDrawable) v.foreground = null
            return
        }
        v.foreground = SeasonDrawable(v.context, k, header = false, garland = false, shared = shared, scene = scene)
    }

    /** Снег / листья ПОД содержимым (например, за аватаркой в профиле), без гирлянды. */
    fun decorateBackground(v: View?) {
        if (v == null) return
        val k = current(v.context)
        if (k == Kind.NONE) return
        val base = v.background ?: android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        if (base is LayerDrawable && (0 until base.numberOfLayers).any { base.getDrawable(it) is SeasonDrawable }) return
        v.background = LayerDrawable(arrayOf(base, SeasonDrawable(v.context, k, header = false, garland = false)))
    }

    /**
     * Частицы: листья или снег. Каждая частица падает, покачиваясь; зимой вдоль верха шапки -
     * гирлянда из лампочек, которые мигают разными цветами по очереди.
     */
    class SeasonDrawable(ctx: Context, private val kind: Kind, private val header: Boolean, private val garland: Boolean, private val shared: Boolean = false, private val scene: Boolean = false) : Drawable() {
        private val d = ctx.resources.displayMetrics.density
        private val start = if (shared) EPOCH else SystemClock.uptimeMillis()
        private val loc = IntArray(2)
        private val rnd = java.util.Random(7)
        // Слабые телефоны (720p, мало памяти) - меньше частиц: плавно и без нагрева.
        private val count = (if (header) 14 else if (shared) 34 else 26).let { n ->
            if (LiteMode.on) n * 3 / 5 else n
        }
        // x (0..1), скорость падения (доли высоты в секунду), размер, фаза качания, тип листа.
        private val px = FloatArray(count) { rnd.nextFloat() }
        private val speed = FloatArray(count) { 0.05f + rnd.nextFloat() * 0.08f }
        private val size = FloatArray(count) { 0.6f + rnd.nextFloat() * 0.8f }
        private val phase = FloatArray(count) { rnd.nextFloat() * 6.28f }
        private val offset = FloatArray(count) { rnd.nextFloat() }
        private val type = IntArray(count) { rnd.nextInt(3) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xAA1F2937.toInt()
        }
        private val leaves = arrayOf("🍁", "🍂", "🌿")
        private val bulbs = intArrayOf(0xFFEF4444.toInt(), 0xFFFDE047.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFEC4899.toInt())
        private var alphaMul = 255

        private val tick = Runnable { invalidateSelf() }

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            // Листья/снег не «застывают» в режиме экономии - просто кадров меньше (10 в секунду
            // вместо 25). Стоят на месте только при выключенных анимациях.
            val frozen = !Kinetics.enabled
            val lite = EnergySaver.on
            val t = if (frozen) 3f else (SystemClock.uptimeMillis() - start) / 1000f
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            // Зимняя шапка: сосульки сверху, сугроб снизу, мерцающие звёздочки.
            if (header && kind == Kind.WINTER) drawWinterHeader(c, b.left.toFloat(), b.top.toFloat(), w, h, t)
            // Фон чата: мягкая сезонная дымка и сцена внизу (сугробы, листва, трава с цветами, волны).
            if (scene) drawScene(c, b.left.toFloat(), b.top.toFloat(), w, h, t)
            // Единое поле: размеры и сдвиг - по окну, частица рисуется там, где она сейчас в окне.
            var fw = w
            var fh = h
            var shiftX = 0f
            var shiftY = 0f
            val host = callback as? View
            if (shared && host != null) {
                val root = host.rootView
                host.getLocationInWindow(loc)
                fw = root.width.toFloat().coerceAtLeast(w)
                fh = root.height.toFloat().coerceAtLeast(h)
                shiftX = loc[0].toFloat()
                shiftY = loc[1].toFloat()
                c.save()
                c.clipRect(b)
            }
            for (i in 0 until count) {
                val y = ((offset[i] + t * speed[i] * (if (header) 2.2f else 1f)) % 1.1f - 0.05f) * fh - shiftY
                val x = (px[i] * fw + sin(t * 1.3f + phase[i]) * 14 * d).toFloat() - shiftX
                if (shared && (y < -30 * d || y > h + 30 * d)) continue
                if (kind == Kind.SPRING) {
                    // Лепестки сакуры: кружатся и переворачиваются.
                    val alpha = (if (header) 230 else 150) * alphaMul / 255
                    SeasonArt.petal(
                        c, dot, b.left + x, b.top + y, (if (header) 5f else 7f) * d * size[i],
                        sin(t * 1.5f + phase[i]) * 70f, kotlin.math.cos(t * 2.2f + phase[i]),
                        SeasonArt.PETAL_COLORS[i % SeasonArt.PETAL_COLORS.size], alpha,
                    )
                } else if (kind == Kind.SUMMER) {
                    // Лето: бабочки порхают, остальное - тёплые солнечные искорки (летний пух).
                    val yy = y
                    if (i % 5 == 0) {
                        SeasonArt.butterfly(
                            c, dot, b.left + x, b.top + yy, (if (header) 5f else 7f) * d * size[i],
                            sin(t * 0.9f + phase[i]) * 25f, (0.5f + 0.5f * sin(t * 14f + phase[i] * 3)),
                            SeasonArt.BUTTERFLY_COLORS[i % SeasonArt.BUTTERFLY_COLORS.size], (if (header) 240 else 170) * alphaMul / 255,
                        )
                    } else {
                        SeasonArt.firefly(c, dot, b.left + x, b.top + yy, (if (header) 1.6f else 2.2f) * d * size[i], 0.5f + 0.5f * sin(t * 3f + phase[i]), (if (header) 230 else 160) * alphaMul / 255)
                    }
                } else if (kind == Kind.AUTUMN) {
                    // Рисованные листья (клён и овальный) с прожилками, кружатся и переворачиваются.
                    val alpha = (if (header) 220 else 130) * alphaMul / 255
                    SeasonArt.leaf(
                        c, dot, b.left + x, b.top + y, (if (header) 6.5f else 9f) * d * size[i],
                        sin(t * 1.7f + phase[i]) * 50f, kotlin.math.cos(t * 2f + phase[i]), type[i] % 2,
                        SeasonArt.LEAF_COLORS[i % SeasonArt.LEAF_COLORS.size], alpha,
                    )
                } else {
                    val r = (if (header) 2.2f else 3f) * d * size[i]
                    dot.shader = null
                    dot.color = Color.WHITE
                    dot.alpha = (if (header) 230 else 170) * alphaMul / 255
                    c.drawCircle(b.left + x, b.top + y, r, dot)
                }
            }
            if (shared && host != null) c.restore()
            if (garland && kind == Kind.WINTER) {
                // Провод - провисающая дуга, лампочки мигают по очереди разными цветами.
                val n = (w / (26 * d)).toInt().coerceAtLeast(6)
                wire.strokeWidth = 1.3f * d
                val sag = 6 * d
                var prevX = b.left.toFloat()
                var prevY = b.top + 3 * d
                for (i in 0..n) {
                    val x = b.left + w * i / n
                    val y = b.top + 3 * d + if (i % 2 == 1) sag else 0f
                    c.drawLine(prevX, prevY, x, y, wire)
                    prevX = x
                    prevY = y
                    val step = ((t * 2.5f).toInt() + i) % bulbs.size
                    val on = frozen || ((t * 3 + i * 0.7f) % 2f) < 1.4f
                    val col = bulbs[step]
                    val r = 4.2f * d
                    dot.shader = null
                    if (on) {
                        // Свечение - два полупрозрачных круга (без создания градиентов в каждом кадре).
                        dot.color = (col and 0x00FFFFFF) or 0x26000000
                        c.drawCircle(x, y + r, r * 2.5f, dot)
                        dot.color = (col and 0x00FFFFFF) or 0x44000000
                        c.drawCircle(x, y + r, r * 1.7f, dot)
                    }
                    dot.color = if (on) col else (col and 0x00FFFFFF) or 0x66000000
                    c.drawCircle(x, y + r, r, dot)
                }
            }
            if (!frozen) scheduleSelf(tick, SystemClock.uptimeMillis() + if (lite) 100 else 40)
        }

        private val scenePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val scenePath = android.graphics.Path()
        private var washShader: android.graphics.Shader? = null
        private var washH = 0f
        private val sceneRnd = java.util.Random(11)
        private val sceneX = FloatArray(14) { sceneRnd.nextFloat() }
        private val sceneS = FloatArray(14) { 0.7f + sceneRnd.nextFloat() * 0.6f }

        /**
         * Сцена сезона внизу чата, под сообщениями: зимой - сугробы с искрами, осенью - ворох
         * листьев, весной - трава и цветы, летом - тёплые волны с бликами солнца.
         * Сверху - лёгкая дымка в цветах сезона (чат «окрашен» темой, текст читается как раньше).
         */
        private fun drawScene(c: Canvas, l: Float, tp: Float, w: Float, h: Float, t: Float) {
            val p = scenePaint
            p.style = Paint.Style.FILL
            if (washShader == null || washH != h) {
                washH = h
                val col = when (kind) {
                    Kind.WINTER -> 0x2238BDF8
                    Kind.AUTUMN -> 0x22F97316
                    Kind.SPRING -> 0x22F472B6
                    Kind.SUMMER -> 0x2214B8A6
                    Kind.NONE -> 0
                }
                washShader = android.graphics.LinearGradient(0f, tp, 0f, tp + h, intArrayOf(col, 0x00000000, col), floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP)
            }
            p.shader = washShader
            c.drawRect(l, tp, l + w, tp + h, p)
            p.shader = null
            val base = tp + h
            val sh = 70 * d
            when (kind) {
                Kind.WINTER -> {
                    c.save()
                    c.translate(l, tp)
                    p.alpha = 255
                    SeasonArt.cornerDrifts(c, p, w, h, d, t)
                    c.restore()
                    // Посередине - низкий сугроб волной.
                    scenePath.reset()
                    scenePath.moveTo(l, base)
                    var x = 0f
                    while (x <= w) {
                        scenePath.lineTo(l + x, base - sh * 0.35f - sin(x / w * 9f) * 6 * d)
                        x += 12 * d
                    }
                    scenePath.lineTo(l + w, base)
                    scenePath.close()
                    p.color = 0xCCF8FAFC.toInt()
                    c.drawPath(scenePath, p)
                }
                Kind.AUTUMN -> {
                    for (i in sceneX.indices) {
                        val x = l + sceneX[i] * w
                        val y = base - (6 + (i % 4) * 7) * d
                        SeasonArt.leaf(c, p, x, y, 11 * d * sceneS[i], (i * 47f) % 360f + sin(t * 0.8f + i) * 6f, 1f, i % 2, SeasonArt.LEAF_COLORS[i % SeasonArt.LEAF_COLORS.size], 200)
                    }
                }
                Kind.SPRING -> {
                    p.color = 0x9922C55E.toInt()
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeWidth = 2.2f * d
                    p.style = Paint.Style.STROKE
                    var x = 0f
                    var k = 0
                    while (x < w) {
                        val hh = (14 + (k * 37 % 17)) * d
                        val sway = sin(t * 1.4f + k * 0.7f) * 3 * d
                        c.drawLine(l + x, base, l + x + sway, base - hh, p)
                        x += 7 * d
                        k++
                    }
                    p.style = Paint.Style.FILL
                    for (i in 0 until 7) {
                        SeasonArt.flower(c, p, l + sceneX[i] * w, base - (18 + (i % 3) * 6) * d, 9 * d * sceneS[i], t * 10f + i * 30f)
                    }
                }
                Kind.SUMMER -> {
                    for (layer in 0 until 2) {
                        scenePath.reset()
                        scenePath.moveTo(l, base)
                        var x = 0f
                        val amp = (5 + layer * 3) * d
                        val y0 = base - sh * (0.42f - layer * 0.16f)
                        while (x <= w + 8 * d) {
                            scenePath.lineTo(l + x, y0 + sin(x / (40 * d) + t * (1.2f + layer * 0.5f)) * amp)
                            x += 8 * d
                        }
                        scenePath.lineTo(l + w, base)
                        scenePath.close()
                        p.color = if (layer == 0) 0x5514B8A6 else 0x770EA5E9
                        c.drawPath(scenePath, p)
                    }
                    for (i in 0 until 6) {
                        val a = (0.5f + 0.5f * sin(t * 3f + i * 1.7f))
                        SeasonArt.firefly(c, p, l + sceneX[i] * w, base - (10 + (i % 3) * 9) * d, 1.6f * d, a, (160 * a).toInt())
                    }
                }
                Kind.NONE -> Unit
            }
        }

        private val icePath = android.graphics.Path()
        private var iceShader: android.graphics.Shader? = null
        private var iceTop = Float.NaN
        private val snowP = Paint(Paint.ANTI_ALIAS_FLAG)

        private fun drawWinterHeader(c: Canvas, l: Float, tp: Float, w: Float, h: Float, t: Float) {
            // Сугроб по нижнему краю (у панели с гирляндой; у вкладок - только сосульки).
            snowP.shader = null
            if (garland) {
            snowP.color = 0xF2FFFFFF.toInt()
            icePath.reset()
            icePath.moveTo(l, tp + h)
            var x = l
            val bump = 18 * d
            icePath.lineTo(l, tp + h - 4 * d)
            while (x < l + w) {
                val nx = x + bump
                val up = if (((x / bump).toInt()) % 2 == 0) 7 * d else 4 * d
                icePath.quadTo(x + bump / 2, tp + h - 4 * d - up, nx, tp + h - 4 * d)
                x = nx
            }
            icePath.lineTo(l + w, tp + h)
            icePath.close()
            c.drawPath(icePath, snowP)
            }
            // Сосульки сверху (если нет гирлянды - она сама висит сверху).
            if (!garland) {
                if (iceShader == null || iceTop != tp) {
                    iceTop = tp
                    iceShader = android.graphics.LinearGradient(0f, tp, 0f, tp + 14 * d, 0xEEFFFFFF.toInt(), 0x66BAE6FD, android.graphics.Shader.TileMode.CLAMP)
                }
                snowP.shader = iceShader
                var ix = l + 6 * d
                var k = 0
                while (ix < l + w) {
                    val len = (6 + (k * 37 % 9)) * d
                    icePath.reset()
                    icePath.moveTo(ix, tp)
                    icePath.lineTo(ix + 3 * d, tp + len)
                    icePath.lineTo(ix + 6 * d, tp)
                    icePath.close()
                    c.drawPath(icePath, snowP)
                    ix += (14 + (k * 13 % 10)) * d
                    k++
                }
                snowP.shader = null
            }
            // Мерцающие звёздочки.
            for (k in 0 until 6) {
                val sx = l + w * ((k * 0.173f + 0.05f) % 1f)
                val sy = tp + h * (0.2f + (k % 3) * 0.2f)
                val a = ((kotlin.math.sin(t * 2.4f + k * 1.7f) + 1) / 2 * 200).toInt()
                snowP.color = (a shl 24) or 0xFFFFFF
                c.drawCircle(sx, sy, 1.6f * d, snowP)
                c.drawLine(sx - 4 * d, sy, sx + 4 * d, sy, snowP)
                c.drawLine(sx, sy - 4 * d, sx, sy + 4 * d, snowP)
            }
        }

        override fun setAlpha(alpha: Int) {
            alphaMul = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
