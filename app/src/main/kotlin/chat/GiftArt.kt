// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.stoyko.smessenger.ui.EnergySaver
import com.stoyko.smessenger.ui.Kinetics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Картинка подарка с анимацией: живой эмодзи (Noto Animated) на фоне цвета подарка и
 * эффект по редкости. Анимация идёт, пока подарок на экране (и реже - в энергосбережении).
 *
 * @param playEmoji - анимировать сам эмодзи (в больших карточках); в сетке выбора - нет,
 *                    там только лёгкий эффект фона, чтобы не грузить телефон.
 */
@SuppressLint("ViewConstructor")
class GiftArt(
    ctx: Context,
    val gift: GiftCatalog.Gift,
    private val playEmoji: Boolean = true,
    private val animateFx: Boolean = true,
) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val start = SystemClock.uptimeMillis() - (gift.id * 137L % 5000)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val clip = Path()
    private val m = Matrix()
    private val emojiText = TextView(ctx).apply {
        text = gift.emoji
        gravity = Gravity.CENTER
        includeFontPadding = false
        // Мягкая тень - эмодзи не «плоский», а лежит поверх медальона.
        setShadowLayer(6f * ctx.resources.displayMetrics.density, 0f, 2f * ctx.resources.displayMetrics.density, 0x55000000)
    }
    // Живой эмодзи - всегда квадратный и по центру: на части телефонов (неквадратные ячейки,
    // нестандартная плотность экрана) анимация растягивалась на всю ширину ячейки.
    private val emojiAnim = object : ImageView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            val side = minOf(measuredWidth, measuredHeight)
            setMeasuredDimension(side, side)
        }
    }.apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    /** Эмодзи нужен только эмодзи-подаркам (не нарисованным и не картинкам). */
    private val usesEmoji = gift.drawn == null && gift.image == null

    // ---- подарок-картинка (новый дизайн) ----
    private val art: android.graphics.Bitmap? = gift.image?.let { GiftImages.get(ctx, it) }
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artDst = RectF()
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shineShader: LinearGradient? = null
    private var lastW = 0
    private var topGlow: Shader? = null
    private var centerGlow: Shader? = null
    private var sweep: SweepGradient? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        // Нарисованные подарки рисуются целиком на холсте - эмодзи им не нужен.
        if (usesEmoji) {
            addView(emojiText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(emojiAnim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val s = minOf(w, h)
        val inner = (s * 0.58f).toInt()
        // У нарисованных подарков эмодзи нет (не добавлен в разметку) - его параметров тоже нет.
        // Раньше здесь было падение при каждом показе такого подарка в чате: экран чата зависал.
        if (usesEmoji && inner > 0) {
            emojiText.textSize = inner / d * 0.78f
            (emojiAnim.layoutParams as? LayoutParams)?.let { lp ->
                if (lp.width != inner || lp.height != inner || lp.gravity != Gravity.CENTER) {
                    lp.width = inner
                    lp.height = inner
                    lp.gravity = Gravity.CENTER
                    // Внутри onSizeChanged нельзя сразу запрашивать новую разметку - откладываем.
                    emojiAnim.post { emojiAnim.requestLayout() }
                }
            }
        }
        // Всё рисуется в квадрате по центру: в неквадратной ячейке подарок больше не сплющивается.
        val side = minOf(w, h)
        if (side != lastW && side > 0) {
            lastW = side
            haloShader = null
            shineShader = null
            shimmer = null
            val fw = side.toFloat()
            val fh = side.toFloat()
            bg.shader = if (gift.drawn != null) {
                // Под нарисованным подарком - тёмный фон его цвета, чтобы он «светился».
                LinearGradient(0f, 0f, fw, fh, shade(gift.colors[1], 0.45f), shade(gift.colors[1], 0.22f), Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, 0f, fw, fh, gift.colors[0], gift.colors[1], Shader.TileMode.CLAMP)
            }
            if (gift.drawn != null) {
                val s = minOf(fw, fh) * 0.62f
                // Объём: светлый верх -> цвет подарка -> тень снизу (раньше - плоская заливка в 2 цвета).
                shapeFill.shader = LinearGradient(
                    0f, fh / 2 - s / 2, 0f, fh / 2 + s / 2,
                    intArrayOf(lighten(gift.colors[0]), gift.colors[0], shade(gift.colors[1], 0.72f)),
                    floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
                )
                innerLight = RadialGradient(
                    fw / 2 - s * 0.18f, fh / 2 - s * 0.26f, s * 0.75f,
                    intArrayOf(0x88FFFFFF.toInt(), 0x22FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
                )
                backGlow = RadialGradient(
                    fw / 2, fh / 2, s * 0.85f,
                    (gift.colors[0] and 0x00FFFFFF) or 0x77000000, gift.colors[0] and 0x00FFFFFF, Shader.TileMode.CLAMP,
                )
            }
            if (usesEmoji) {
                // Эмодзи-подарок: светящийся «медальон» за эмодзи и мягкая тень под ним.
                medal = RadialGradient(
                    fw / 2, fh * 0.46f, fw * 0.42f,
                    intArrayOf(0xCCFFFFFF.toInt(), (lighten(gift.colors[0]) and 0x00FFFFFF) or 0x55000000, 0x00FFFFFF),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
                )
            }
            // Градиенты создаются один раз; в кадре меняется только матрица (без лишней памяти).
            topGlow = RadialGradient(fw * 0.3f, fh * 0.2f, fw * 0.6f, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            centerGlow = RadialGradient(fw / 2, fh / 2, fw * 0.55f, if (gift.rarity == GiftCatalog.Rarity.LEGENDARY) 0xAAFFFFFF.toInt() else 0x88FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            val colors = if (gift.rarity == GiftCatalog.Rarity.LEGENDARY) {
                intArrayOf(0xFFFDE047.toInt(), 0xFFF472B6.toInt(), 0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFFDE047.toInt())
            } else {
                intArrayOf(0x40FFFFFF, 0xFFFFFFFF.toInt(), 0x40FFFFFF, 0x40FFFFFF, 0x40FFFFFF)
            }
            sweep = SweepGradient(fw / 2, fh / 2, colors, null)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playEmoji && usesEmoji) {
            AnimatedEmoji.play(emojiAnim, AnimatedEmoji.code(gift.emoji), loop = -1) { emojiText.visibility = View.INVISIBLE }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        if (playEmoji && usesEmoji) AnimatedEmoji.stop(emojiAnim)
        super.onDetachedFromWindow()
    }

    private fun anim() = animateFx && Kinetics.enabled

    /** Режим для слабых устройств (настройка или «Авто») - анимация подарков 30 кадров/с. */
    private val lite: Boolean get() = com.stoyko.smessenger.ui.LiteMode.on

    override fun dispatchDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        // Квадрат по центру вью: пропорции подарка сохраняются при любой форме ячейки.
        val w = minOf(width, height).toFloat()
        val h = w
        val outer = canvas.save()
        canvas.translate((width - w) / 2f, (height - h) / 2f)
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val r = minOf(w, h) * 0.26f
        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, bg)
        // Легендарные: своя сцена позади подарка (у каждого - уникальная).
        val leg = legend
        if (leg != null && anim()) leg.drawBack(canvas, w, h, t, lite || !playEmoji)
        val pic = art
        if (pic != null && !pic.isRecycled) {
            // Подарок-картинка: у каждого своё движение (дыхание, покачивание, парение, пульс
            // сердца, наклон) и своя «подпись» категории поверх - анимация не повторяется.
            val br = if (anim()) 1f + 0.018f * sin(t * 1.6f * speedK) else 1f
            val inset = minOf(w, h) * 0.035f
            // Пропорции картинки сохраняются всегда (вписываем, а не растягиваем).
            val bw = pic.width.toFloat().coerceAtLeast(1f)
            val bh = pic.height.toFloat().coerceAtLeast(1f)
            val box = minOf(w, h) - inset * 2
            val k = minOf(box / bw, box / bh)
            val dw = bw * k
            val dh = bh * k
            artDst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
            canvas.save()
            if (anim()) { if (leg != null) leg.motion(canvas, w, h, t) else motion(canvas, w, h, t) } else canvas.scale(br, br, w / 2, h / 2)
            canvas.drawBitmap(pic, null, artDst, artPaint)
            canvas.restore()
            if (gift.rarity != GiftCatalog.Rarity.COMMON) drawSparkles(canvas, w, h, t)
            if (anim()) drawSignature(canvas, w, h, t)
            if (anim()) drawShine(canvas, w, h, t)
        } else {
            when (gift.rarity) {
                GiftCatalog.Rarity.COMMON -> drawBubbles(canvas, w, h, t)
                GiftCatalog.Rarity.RARE -> drawSparkles(canvas, w, h, t)
                GiftCatalog.Rarity.LEGENDARY -> if (leg == null || !anim()) drawRays(canvas, w, h, t) else glow(canvas, w, h, 0.75f + 0.15f * sin(t * 2.4f))
            }
            if (usesEmoji) drawMedal(canvas, w, h, t)
            if (anim()) drawSignature(canvas, w, h, t)
            // Блик пробегает и по эмодзи- и нарисованным подаркам (раньше - только по картинкам).
            if (anim()) drawShine(canvas, w, h, t)
        }
        canvas.restoreToCount(save)
        // Частицы легендарного подарка - поверх него (эмодзи-вью рисуются позже, поэтому для
        // них частицы - в onDrawForeground-подобном проходе ниже).
        // Лёгкое «покачивание» эмодзи.
        if (anim() && leg != null && usesEmoji) {
            // Легендарный эмодзи «поворачивается» в 3D, парит и раз в несколько секунд вспыхивает.
            val tt = t
            val ry = 16f * sin(tt * 0.9f)
            val bob = h * 0.03f * sin(tt * 1.5f)
            val ph = (tt * 0.25f) % 1f
            val pw = 1f + 0.08f * (if (ph < 0.1f) sin(ph / 0.1f * PI.toFloat()) else 0f)
            for (v in arrayOf<View>(emojiText, emojiAnim)) {
                v.rotationY = ry
                v.translationY = bob
                v.scaleX = pw
                v.scaleY = pw
            }
        } else if (anim()) {
            val bob = sin(t * 2.2f) * h * (if (gift.rarity == GiftCatalog.Rarity.LEGENDARY) 0.035f else 0.02f)
            emojiText.translationY = bob
            emojiAnim.translationY = bob
            val sc = 1f + 0.035f * sin(t * 1.9f + 0.7f)
            emojiText.scaleX = sc
            emojiText.scaleY = sc
            emojiAnim.scaleX = sc
            emojiAnim.scaleY = sc
        }
        gift.drawn?.let { drawShape(canvas, w, h, t, it) }
        canvas.restoreToCount(outer)
        super.dispatchDraw(canvas)
        canvas.save()
        canvas.translate((width - w) / 2f, (height - h) / 2f)
        if (leg != null && anim()) {
            val sv = canvas.save()
            canvas.clipPath(clip)
            leg.drawFront(canvas, w, h, t, lite || !playEmoji)
            leg.powerWave(canvas, w, h, t)
            canvas.restoreToCount(sv)
        }
        drawFrame(canvas, w, h, r, t)
        canvas.restore()
        if (anim() && isShown) {
            // Оптимизация: в сетках (много подарков сразу) - 30 кадров/с, этого хватает для
            // мягкой анимации и вдвое меньше нагрузки; в больших карточках - полная плавность.
            when {
                EnergySaver.on -> postInvalidateDelayed(66)
                com.stoyko.smessenger.ui.LiteMode.on -> postInvalidateDelayed(40)
                !playEmoji || lite -> postInvalidateDelayed(33)
                // Экраны 90-144 Гц (POCO, Samsung, Xiaomi): подарку хватает 60 кадров/с.
                else -> postInvalidateDelayed(16)
            }
        }
    }

    // ---------------- уникальная анимация ----------------

    /** Уникальная анимация легендарного подарка (у обычных и редких - нет). */
    private val legend: LegendFx? = if (gift.rarity == GiftCatalog.Rarity.LEGENDARY) LegendFx(gift) else null

    /** Категория подарка - от неё «подпись» анимации (сердечки, ноты, пузырьки…). */
    private val cat: GiftCatalog.Cat = runCatching { GiftCatalog.catOf(gift) }.getOrDefault(GiftCatalog.Cat.OTHER)

    /** Своё движение и свой темп у каждого подарка (по номеру): двух одинаковых нет. */
    private val variant = ((gift.id * 7 + 3) % 5 + 5) % 5
    private val speedK = 0.8f + ((gift.id * 37) % 9) / 20f
    private val sigRnd = java.util.Random(gift.id * 2654435761L)
    private val sx = FloatArray(8) { 0.12f + sigRnd.nextFloat() * 0.76f }
    private val sy = FloatArray(8) { 0.12f + sigRnd.nextFloat() * 0.76f }
    private val sp = FloatArray(8) { sigRnd.nextFloat() * 6.28f }
    private val sig = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val confetti = intArrayOf(0xFFF43F5E.toInt(), 0xFFFDE047.toInt(), 0xFF22D3EE.toInt(), 0xFFA3E635.toInt(), 0xFFC084FC.toInt())

    /** Движение картинки: 0 дыхание, 1 покачивание, 2 парение, 3 пульс сердца, 4 наклон. */
    private fun motion(c: Canvas, w: Float, h: Float, t: Float) {
        val tt = t * speedK
        when (variant) {
            0 -> {
                val k = 1f + 0.022f * sin(tt * 1.6f)
                c.scale(k, k, w / 2, h / 2)
            }
            1 -> {
                c.rotate(2.6f * sin(tt * 1.3f), w / 2, h * 0.9f)
                c.scale(1.01f, 1.01f, w / 2, h / 2)
            }
            2 -> {
                c.translate(0f, h * 0.022f * sin(tt * 1.7f))
                c.scale(1.015f, 1.015f, w / 2, h / 2)
            }
            3 -> {
                // «Тук-тук»: двойной толчок, потом пауза.
                val ph = (tt * 0.9f) % 1f
                val beat = if (ph < 0.12f) sin(ph / 0.12f * PI.toFloat()) else if (ph in 0.2f..0.32f) 0.6f * sin((ph - 0.2f) / 0.12f * PI.toFloat()) else 0f
                val k = 1f + 0.04f * beat
                c.scale(k, k, w / 2, h / 2)
            }
            else -> {
                // Наклон как у голограммы: по очереди сжимается по ширине и высоте.
                val a = sin(tt * 1.1f)
                c.scale(1f - 0.025f * a, 1f + 0.012f * a, w / 2, h / 2)
            }
        }
    }

    /** «Подпись» категории поверх подарка: у любви сердечки, у моря пузырьки, у космоса звёзды… */
    private fun drawSignature(c: Canvas, w: Float, h: Float, t: Float) {
        val n = if (lite || !playEmoji) 4 else 7
        val tt = t * speedK
        val accent = gift.colors[0]
        when (cat) {
            GiftCatalog.Cat.LOVE -> {
                glyph.textSize = h * 0.11f
                for (i in 0 until n) {
                    val life = ((tt * 0.35f + sp[i] / 6.28f) % 1f)
                    val x = sx[i] * w + sin(tt * 2f + sp[i]) * w * 0.03f
                    val y = h * (0.95f - life * 0.8f)
                    glyph.color = if (i % 2 == 0) 0xFFF43F5E.toInt() else 0xFFF9A8D4.toInt()
                    glyph.alpha = (255 * sin(life * PI.toFloat())).toInt().coerceIn(0, 255)
                    c.drawText("♥", x, y, glyph)
                }
            }
            GiftCatalog.Cat.MAGIC -> {
                for (i in 0 until n) {
                    val a = tt * (0.9f + i * 0.13f) + sp[i]
                    val r = w * (0.28f + 0.12f * sx[i])
                    val x = w / 2 + cos(a) * r
                    val y = h / 2 + sin(a) * r * 0.55f
                    sig.color = if (i % 2 == 0) 0xFFE9D5FF.toInt() else accent
                    sig.alpha = (150 + 100 * sin(a * 2f)).toInt().coerceIn(0, 255)
                    c.drawCircle(x, y, w * 0.018f, sig)
                    sig.alpha = sig.alpha / 3
                    c.drawCircle(x - cos(a) * w * 0.03f, y - sin(a) * w * 0.017f, w * 0.012f, sig)
                }
            }
            GiftCatalog.Cat.TREASURE, GiftCatalog.Cat.STYLE -> {
                fx.shader = null
                for (i in 0 until n) {
                    val tw = sin(tt * 2.4f + sp[i] * 3f)
                    if (tw <= 0f) continue
                    fx.color = if (cat == GiftCatalog.Cat.STYLE) 0xFFFBCFE8.toInt() else 0xFFFFF7C2.toInt()
                    fx.alpha = (255 * tw).toInt()
                    star(c, sx[i] * w, sy[i] * h, w * 0.022f * tw)
                }
            }
            GiftCatalog.Cat.CREATURES -> {
                for (i in 0 until n) {
                    val life = ((tt * 0.4f + sp[i] / 6.28f) % 1f)
                    sig.color = if (i % 3 == 0) 0xFFFDE047.toInt() else 0xFFFB923C.toInt()
                    sig.alpha = (220 * (1f - life)).toInt()
                    c.drawCircle(sx[i] * w + sin(tt * 3f + sp[i]) * w * 0.04f, h * (1f - life * 0.9f), w * 0.012f * (1.4f - life), sig)
                }
            }
            GiftCatalog.Cat.NATURE -> {
                for (i in 0 until n.coerceAtMost(5)) {
                    val life = ((tt * 0.25f + sp[i] / 6.28f) % 1f)
                    val x = sx[i] * w + sin(tt * 1.6f + sp[i]) * w * 0.08f
                    val y = h * (life * 1.1f - 0.05f)
                    sig.color = if (i % 2 == 0) 0xFF4ADE80.toInt() else 0xFFA3E635.toInt()
                    sig.alpha = 210
                    c.save()
                    c.rotate(sin(tt * 2f + sp[i]) * 50f, x, y)
                    rect.set(x - w * 0.028f, y - w * 0.012f, x + w * 0.028f, y + w * 0.012f)
                    c.drawOval(rect, sig)
                    c.restore()
                }
                rect.set(0f, 0f, w, h)
            }
            GiftCatalog.Cat.SPACE -> {
                for (i in 0 until n) {
                    val tw = 0.5f + 0.5f * sin(tt * 3f + sp[i] * 4f)
                    sig.color = 0xFFFFFFFF.toInt()
                    sig.alpha = (60 + 195 * tw).toInt()
                    c.drawCircle(sx[i] * w, sy[i] * h, w * 0.008f * (0.6f + tw), sig)
                }
                // Комета по орбите.
                val a = tt * 1.2f
                val x = w / 2 + cos(a) * w * 0.4f
                val y = h / 2 + sin(a) * h * 0.18f
                sig.color = 0xFFBAE6FD.toInt()
                for (k in 0 until 5) {
                    sig.alpha = 200 - k * 38
                    c.drawCircle(x - cos(a - 1.57f) * k * w * 0.012f, y - sin(a - 1.57f) * k * h * 0.005f, w * (0.016f - k * 0.0025f), sig)
                }
            }
            GiftCatalog.Cat.SEA -> {
                sig.style = Paint.Style.STROKE
                sig.strokeWidth = w * 0.006f
                for (i in 0 until n) {
                    val life = ((tt * 0.33f + sp[i] / 6.28f) % 1f)
                    sig.color = 0xFFE0F2FE.toInt()
                    sig.alpha = (200 * sin(life * PI.toFloat())).toInt().coerceIn(0, 255)
                    c.drawCircle(sx[i] * w + sin(tt * 2.5f + sp[i]) * w * 0.025f, h * (1f - life), w * (0.012f + 0.02f * sy[i]), sig)
                }
                sig.style = Paint.Style.FILL
            }
            GiftCatalog.Cat.WINTER -> {
                for (i in 0 until n) {
                    val life = ((tt * 0.2f + sp[i] / 6.28f) % 1f)
                    sig.color = 0xFFFFFFFF.toInt()
                    sig.alpha = 230
                    c.drawCircle(sx[i] * w + sin(tt * 1.4f + sp[i]) * w * 0.05f, h * life, w * (0.01f + 0.012f * sy[i]), sig)
                }
            }
            GiftCatalog.Cat.TECH -> {
                // Луч сканера и электрические искры.
                val y = h * ((tt * 0.45f) % 1f)
                sig.shader = LinearGradient(0f, y - h * 0.06f, 0f, y + h * 0.02f, 0x0022D3EE, 0x6622D3EE, Shader.TileMode.CLAMP)
                c.drawRect(0f, y - h * 0.06f, w, y + h * 0.02f, sig)
                sig.shader = null
                for (i in 0 until 3) {
                    if (sin(tt * 7f + sp[i] * 5f) < 0.75f) continue
                    sig.color = 0xFFE0F2FE.toInt()
                    sig.alpha = 255
                    c.drawCircle(sx[i] * w, sy[i] * h, w * 0.016f, sig)
                }
            }
            GiftCatalog.Cat.ART -> {
                glyph.textSize = h * 0.12f
                for (i in 0 until n.coerceAtMost(4)) {
                    val life = ((tt * 0.3f + sp[i] / 6.28f) % 1f)
                    glyph.color = if (i % 2 == 0) 0xFFFDE68A.toInt() else 0xFFE9D5FF.toInt()
                    glyph.alpha = (255 * sin(life * PI.toFloat())).toInt().coerceIn(0, 255)
                    c.drawText(if (i % 2 == 0) "♪" else "♫", sx[i] * w + sin(tt * 2f + sp[i]) * w * 0.05f, h * (0.95f - life * 0.75f), glyph)
                }
            }
            GiftCatalog.Cat.FOOD -> {
                sig.style = Paint.Style.STROKE
                sig.strokeWidth = w * 0.012f
                sig.strokeCap = Paint.Cap.ROUND
                for (i in 0 until 3) {
                    val life = ((tt * 0.3f + i / 3f) % 1f)
                    val x0 = w * (0.4f + i * 0.1f)
                    starPath.reset()
                    for (k in 0..8) {
                        val yy = h * (0.55f - k * 0.04f - life * 0.2f)
                        val xx = x0 + sin(k * 0.8f + tt * 2f + i) * w * 0.025f
                        if (k == 0) starPath.moveTo(xx, yy) else starPath.lineTo(xx, yy)
                    }
                    sig.color = 0xFFFFFFFF.toInt()
                    sig.alpha = (110 * sin(life * PI.toFloat())).toInt().coerceIn(0, 255)
                    c.drawPath(starPath, sig)
                }
                sig.style = Paint.Style.FILL
            }
            GiftCatalog.Cat.TRAVEL -> {
                for (i in 0 until 2) {
                    val x = ((tt * 0.08f * (1 + i) + sx[i]) % 1.4f - 0.2f) * w
                    val y = h * (0.18f + i * 0.12f)
                    sig.color = 0xFFFFFFFF.toInt()
                    sig.alpha = 120
                    c.drawCircle(x, y, w * 0.05f, sig)
                    c.drawCircle(x + w * 0.05f, y + h * 0.01f, w * 0.04f, sig)
                    c.drawCircle(x - w * 0.045f, y + h * 0.012f, w * 0.035f, sig)
                }
            }
            GiftCatalog.Cat.FUN -> {
                for (i in 0 until n + 1) {
                    val life = ((tt * 0.35f + sp[i % 8] / 6.28f + i * 0.07f) % 1f)
                    val x = sx[i % 8] * w + sin(tt * 2f + i) * w * 0.04f
                    val y = h * (life * 1.1f - 0.05f)
                    sig.color = confetti[i % confetti.size]
                    sig.alpha = 230
                    c.save()
                    c.rotate(tt * 220f + i * 40f, x, y)
                    c.drawRect(x - w * 0.012f, y - w * 0.02f, x + w * 0.012f, y + w * 0.02f, sig)
                    c.restore()
                }
            }
            else -> {
                fx.shader = null
                for (i in 0 until 4) {
                    val a = tt * 0.8f + i * 1.57f
                    fx.color = 0xFFFFFFFF.toInt()
                    fx.alpha = (120 + 100 * sin(tt * 3f + i)).toInt().coerceIn(0, 255)
                    star(c, w / 2 + cos(a) * w * 0.38f, h / 2 + sin(a) * h * 0.38f, w * 0.015f)
                }
            }
        }
    }

    // ---------------- нарисованные подарки ----------------

    private val shapeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val pat = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val tmp = Path()
    private var haloShader: RadialGradient? = null
    private var innerLight: RadialGradient? = null
    private var backGlow: RadialGradient? = null
    private var medal: RadialGradient? = null
    private var shimmer: LinearGradient? = null
    private val glowMatrix = Matrix()
    private val haloMatrix = Matrix()

    private fun shade(c: Int, f: Float): Int = (0xFF shl 24) or
        (((c shr 16 and 0xFF) * f).toInt() shl 16) or (((c shr 8 and 0xFF) * f).toInt() shl 8) or ((c and 0xFF) * f).toInt()

    private fun lighten(c: Int): Int {
        fun l(v: Int) = (v + (255 - v) * 0.55f).toInt()
        return (0xFF shl 24) or (l(c shr 16 and 0xFF) shl 16) or (l(c shr 8 and 0xFF) shl 8) or l(c and 0xFF)
    }

    /** Контур формы с центром (cx, cy) и размером s. Снежинка - линиями (stroke = true). */
    private fun buildShape(kind: String, cx: Float, cy: Float, s: Float): Boolean {
        shape.reset()
        fun p(x: Float, y: Float) = floatArrayOf(cx + x * s, cy + y * s)
        fun poly(vararg pts: Float) {
            shape.moveTo(cx + pts[0] * s, cy + pts[1] * s)
            var i = 2
            while (i < pts.size) {
                shape.lineTo(cx + pts[i] * s, cy + pts[i + 1] * s)
                i += 2
            }
            shape.close()
        }
        when (kind) {
            "gem" -> poly(-0.42f, -0.14f, -0.22f, -0.38f, 0.22f, -0.38f, 0.42f, -0.14f, 0f, 0.42f)
            "crystal" -> poly(0f, -0.5f, 0.25f, -0.24f, 0.25f, 0.24f, 0f, 0.5f, -0.25f, 0.24f, -0.25f, -0.24f)
            "star" -> {
                for (i in 0 until 10) {
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val rr = if (i % 2 == 0) 0.48f else 0.2f
                    val x = cx + cos(a).toFloat() * rr * s
                    val y = cy + sin(a).toFloat() * rr * s + 0.03f * s
                    if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                }
                shape.close()
            }
            "heart" -> {
                shape.moveTo(cx, cy + 0.42f * s)
                shape.cubicTo(cx - 0.62f * s, cy + 0.02f * s, cx - 0.36f * s, cy - 0.5f * s, cx, cy - 0.2f * s)
                shape.cubicTo(cx + 0.36f * s, cy - 0.5f * s, cx + 0.62f * s, cy + 0.02f * s, cx, cy + 0.42f * s)
                shape.close()
            }
            "orb" -> shape.addCircle(cx, cy, 0.4f * s, Path.Direction.CW)
            "planet" -> shape.addCircle(cx, cy, 0.3f * s, Path.Direction.CW)
            "flower" -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0)
                    shape.addCircle(cx + cos(a).toFloat() * 0.24f * s, cy + sin(a).toFloat() * 0.24f * s, 0.17f * s, Path.Direction.CW)
                }
                shape.addCircle(cx, cy, 0.16f * s, Path.Direction.CW)
            }
            "rune" -> {
                rect.set(cx - 0.3f * s, cy - 0.42f * s, cx + 0.3f * s, cy + 0.42f * s)
                shape.addRoundRect(rect, 0.16f * s, 0.16f * s, Path.Direction.CW)
            }
            "shield" -> {
                shape.moveTo(cx, cy - 0.46f * s)
                shape.lineTo(cx + 0.38f * s, cy - 0.32f * s)
                shape.cubicTo(cx + 0.38f * s, cy + 0.1f * s, cx + 0.2f * s, cy + 0.34f * s, cx, cy + 0.48f * s)
                shape.cubicTo(cx - 0.2f * s, cy + 0.34f * s, cx - 0.38f * s, cy + 0.1f * s, cx - 0.38f * s, cy - 0.32f * s)
                shape.close()
            }
            "crown" -> poly(-0.44f, 0.3f, -0.44f, -0.26f, -0.22f, -0.02f, 0f, -0.36f, 0.22f, -0.02f, 0.44f, -0.26f, 0.44f, 0.3f)
            "moon" -> {
                shape.addCircle(cx, cy, 0.42f * s, Path.Direction.CW)
                tmp.reset()
                tmp.addCircle(cx + 0.2f * s, cy - 0.1f * s, 0.36f * s, Path.Direction.CW)
                shape.op(tmp, Path.Op.DIFFERENCE)
            }
            "snow" -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians(i * 60.0 - 90.0)
                    val ex = cx + cos(a).toFloat() * 0.46f * s
                    val ey = cy + sin(a).toFloat() * 0.46f * s
                    shape.moveTo(cx, cy)
                    shape.lineTo(ex, ey)
                    // Веточки.
                    for (k in listOf(0.26f, 0.38f)) {
                        val bx = cx + cos(a).toFloat() * k * s
                        val by = cy + sin(a).toFloat() * k * s
                        for (side in listOf(-1, 1)) {
                            val b = a + side * Math.toRadians(40.0)
                            shape.moveTo(bx, by)
                            shape.lineTo(bx + cos(b).toFloat() * 0.12f * s, by + sin(b).toFloat() * 0.12f * s)
                        }
                    }
                }
                return true
            }
            // ---- новые формы (3.3.1) ----
            "bolt" -> poly(0.1f, -0.5f, -0.28f, 0.06f, -0.03f, 0.06f, -0.12f, 0.5f, 0.28f, -0.08f, 0.03f, -0.08f)
            "drop" -> {
                shape.moveTo(cx, cy - 0.48f * s)
                shape.cubicTo(cx + 0.38f * s, cy - 0.04f * s, cx + 0.34f * s, cy + 0.42f * s, cx, cy + 0.42f * s)
                shape.cubicTo(cx - 0.34f * s, cy + 0.42f * s, cx - 0.38f * s, cy - 0.04f * s, cx, cy - 0.48f * s)
                shape.close()
            }
            "clover" -> {
                for (i in 0 until 4) {
                    val a = Math.toRadians(45.0 + i * 90.0)
                    shape.addCircle(cx + cos(a).toFloat() * 0.17f * s, cy - 0.06f * s + sin(a).toFloat() * 0.17f * s, 0.16f * s, Path.Direction.CW)
                }
                rect.set(cx - 0.03f * s, cy + 0.05f * s, cx + 0.03f * s, cy + 0.48f * s)
                shape.addRoundRect(rect, 0.03f * s, 0.03f * s, Path.Direction.CW)
            }
            "hex" -> {
                for (i in 0 until 6) {
                    val a = Math.toRadians(-90.0 + i * 60.0)
                    val x = cx + cos(a).toFloat() * 0.45f * s
                    val y = cy + sin(a).toFloat() * 0.45f * s
                    if (i == 0) shape.moveTo(x, y) else shape.lineTo(x, y)
                }
                shape.close()
            }
            "butterfly" -> {
                rect.set(cx - 0.47f * s, cy - 0.4f * s, cx - 0.02f * s, cy + 0.04f * s)
                shape.addOval(rect, Path.Direction.CW)
                rect.set(cx + 0.02f * s, cy - 0.4f * s, cx + 0.47f * s, cy + 0.04f * s)
                shape.addOval(rect, Path.Direction.CW)
                rect.set(cx - 0.36f * s, cy - 0.02f * s, cx - 0.03f * s, cy + 0.38f * s)
                shape.addOval(rect, Path.Direction.CW)
                rect.set(cx + 0.03f * s, cy - 0.02f * s, cx + 0.36f * s, cy + 0.38f * s)
                shape.addOval(rect, Path.Direction.CW)
                rect.set(cx - 0.04f * s, cy - 0.3f * s, cx + 0.04f * s, cy + 0.34f * s)
                shape.addRoundRect(rect, 0.04f * s, 0.04f * s, Path.Direction.CW)
            }
            else -> shape.addCircle(cx, cy, 0.4f * s, Path.Direction.CW)
        }
        return false
    }

    private fun drawShape(c: Canvas, w: Float, h: Float, t: Float, spec: String) {
        val kind = spec.substringBefore(':')
        val pattern = spec.substringAfter(':', "solid")
        val s = minOf(w, h) * 0.62f
        val moving = anim()
        val bob = if (moving) sin(t * 2.2f) * h * 0.025f else 0f
        val cx = w / 2
        val cy = h / 2 + bob
        val save = c.save()
        // Лёгкое покачивание/вращение формы.
        val spin = when {
            !moving -> 0f
            kind == "snow" || kind == "star" -> t * 25f
            kind == "butterfly" || kind == "bolt" -> sin(t * 1.4f) * 4f
            else -> sin(t * 1.4f) * 6f
        }
        c.rotate(spin, cx, cy)
        // Бабочка машет крыльями, молния «вспыхивает».
        if (moving && kind == "butterfly") c.scale(0.55f + 0.45f * kotlin.math.abs(cos(t * 3.2f)), 1f, cx, cy)
        if (moving && kind == "bolt") {
            val flash = 1f + 0.06f * kotlin.math.max(0f, sin(t * 5f))
            c.scale(flash, flash, cx, cy)
        }
        // Пульсирующее свечение цвета подарка позади формы.
        backGlow?.let { g ->
            val pulse = if (moving) 0.9f + 0.1f * sin(t * 2.1f) else 1f
            glowMatrix.setScale(pulse, pulse, w / 2, h / 2)
            glowMatrix.postTranslate(0f, bob)
            g.setLocalMatrix(glowMatrix)
            pat.shader = g
            pat.style = Paint.Style.FILL
            c.drawCircle(cx, cy, s * 0.85f * pulse, pat)
            pat.shader = null
        }
        val lines = buildShape(kind, cx, cy, s)
        // Мягкая тень под формой - подарок «парит» над фоном.
        if (!lines) {
            c.save()
            c.translate(0f, s * 0.06f)
            pat.shader = null
            pat.style = Paint.Style.FILL
            pat.color = 0x55000000
            c.drawPath(shape, pat)
            c.restore()
        }
        // Ореол позади формы.
        if (pattern == "halo") {
            val pulse = if (moving) 0.85f + 0.15f * sin(t * 2.6f) else 1f
            // Градиент ореола создаётся один раз, в кадре меняется только масштаб (без лишней памяти).
            val hs = haloShader ?: RadialGradient(0f, 0f, s * 0.62f, (gift.colors[0] and 0x00FFFFFF) or 0x99000000.toInt(), gift.colors[0] and 0x00FFFFFF, Shader.TileMode.CLAMP)
                .also { haloShader = it }
            haloMatrix.setScale(pulse, pulse)
            haloMatrix.postTranslate(cx, cy)
            hs.setLocalMatrix(haloMatrix)
            pat.shader = hs
            c.drawCircle(cx, cy, s * 0.62f * pulse, pat)
            pat.shader = null
        }
        if (lines) {
            shapeStroke.shader = shapeFill.shader
            shapeStroke.strokeWidth = s * 0.075f
            c.drawPath(shape, shapeStroke)
            shapeStroke.shader = null
        } else {
            c.drawPath(shape, shapeFill)
            if (kind == "planet") {
                // Кольцо планеты.
                shapeStroke.color = lighten(gift.colors[1])
                shapeStroke.strokeWidth = s * 0.05f
                rect.set(cx - 0.5f * s, cy - 0.12f * s, cx + 0.5f * s, cy + 0.12f * s)
                c.save()
                c.rotate(-18f, cx, cy)
                c.drawOval(rect, shapeStroke)
                c.restore()
            }
            // Узор - внутри формы.
            val inner = c.save()
            c.clipPath(shape)
            pat.shader = null
            pat.style = Paint.Style.FILL
            when (pattern) {
                "stripes" -> {
                    pat.color = 0x40FFFFFF
                    pat.strokeWidth = s * 0.07f
                    var x = cx - s
                    while (x < cx + s) {
                        c.drawLine(x, cy - s, x + s, cy + s, pat)
                        x += s * 0.18f
                    }
                }
                "dots" -> {
                    pat.color = 0x55FFFFFF
                    var y = cy - s / 2
                    var row = 0
                    while (y < cy + s / 2) {
                        var x = cx - s / 2 + if (row % 2 == 0) 0f else s * 0.07f
                        while (x < cx + s / 2) {
                            c.drawCircle(x, y, s * 0.03f, pat)
                            x += s * 0.14f
                        }
                        y += s * 0.12f
                        row++
                    }
                }
                "sparkle" -> {
                    pat.color = Color.WHITE
                    for (i in 0 until 7) {
                        val seed = (gift.id * 37 + i * 61) % 100 / 100f
                        val tw = if (moving) (sin(t * (2f + seed * 2f) + i) + 1f) / 2f else 0.7f
                        pat.alpha = (tw * 230).toInt()
                        val x = cx + (((i * 0.31f + seed) % 0.8f) - 0.4f) * s
                        val y = cy + (((i * 0.47f + seed * 1.7f) % 0.8f) - 0.4f) * s
                        c.drawCircle(x, y, s * 0.025f * (0.5f + tw), pat)
                    }
                }
                "facets" -> {
                    pat.color = 0x33FFFFFF
                    pat.style = Paint.Style.STROKE
                    pat.strokeWidth = s * 0.02f
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0 + 22.5)
                        c.drawLine(cx, cy - s * 0.05f, cx + cos(a).toFloat() * s, cy + sin(a).toFloat() * s, pat)
                    }
                    pat.color = 0x26FFFFFF
                    pat.style = Paint.Style.FILL
                    c.drawRect(cx - s, cy - s, cx, cy + s, pat)
                }
                "swirl" -> {
                    pat.color = 0x44FFFFFF
                    pat.style = Paint.Style.STROKE
                    pat.strokeWidth = s * 0.04f
                    val rot = if (moving) t * 60f else 0f
                    for (i in 0 until 3) {
                        rect.set(cx - s * (0.12f + i * 0.12f), cy - s * (0.12f + i * 0.12f), cx + s * (0.12f + i * 0.12f), cy + s * (0.12f + i * 0.12f))
                        c.drawArc(rect, rot + i * 90f, 200f, false, pat)
                    }
                    pat.style = Paint.Style.FILL
                }
                "duo" -> {
                    pat.color = (gift.colors[1] and 0x00FFFFFF) or 0xAA000000.toInt()
                    c.drawRect(cx, cy - s, cx + s, cy + s, pat)
                }
            }
            // Объёмный свет изнутри и глянцевый блик сверху-слева.
            innerLight?.let { il ->
                glowMatrix.setTranslate(0f, bob)
                il.setLocalMatrix(glowMatrix)
                pat.shader = il
                c.drawRect(cx - s, cy - s, cx + s, cy + s, pat)
                pat.shader = null
            }
            pat.style = Paint.Style.FILL
            pat.color = 0x66FFFFFF
            rect.set(cx - s * 0.3f, cy - s * 0.36f, cx - s * 0.02f, cy - s * 0.2f)
            c.save()
            c.rotate(-25f, rect.centerX(), rect.centerY())
            c.drawOval(rect, pat)
            c.restore()
            pat.color = 0xCCFFFFFF.toInt()
            c.drawCircle(cx - s * 0.2f, cy - s * 0.3f, s * 0.035f, pat)
            // Перелив: светлая полоса проходит по форме раз в несколько секунд.
            if (moving) {
                val period = if (gift.rarity == GiftCatalog.Rarity.LEGENDARY) 2.4f else 3.6f
                val p = ((t + gift.id % 7 * 0.3f) % period) / period
                if (p < 0.5f) {
                    val x = cx - s + p / 0.5f * s * 2f
                    val sh = shimmer ?: LinearGradient(-s * 0.25f, -s, s * 0.25f, s, intArrayOf(0x00FFFFFF, 0x77FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                        .also { shimmer = it }
                    glowMatrix.setTranslate(x, cy)
                    sh.setLocalMatrix(glowMatrix)
                    pat.shader = sh
                    c.drawRect(cx - s, cy - s, cx + s, cy + s, pat)
                    pat.shader = null
                }
            }
            c.restoreToCount(inner)
            // Двойной контур: тёмный ободок и светлая кромка - форма читается на любом фоне.
            shapeStroke.shader = null
            shapeStroke.color = (shade(gift.colors[1], 0.45f) and 0x00FFFFFF) or 0xDD000000.toInt()
            shapeStroke.strokeWidth = s * 0.04f
            c.drawPath(shape, shapeStroke)
            shapeStroke.color = (lighten(gift.colors[0]) and 0x00FFFFFF) or 0xCC000000.toInt()
            shapeStroke.strokeWidth = s * 0.016f
            c.drawPath(shape, shapeStroke)
            // Драгоценные формы - с гранями, корона - с камнями на зубцах.
            drawDetails(c, kind, cx, cy, s, t, moving)
        }
        c.restoreToCount(save)
        // Искры кружат вокруг редких и легендарных форм.
        if (moving && gift.rarity != GiftCatalog.Rarity.COMMON) {
            fx.shader = null
            val n = if (gift.rarity == GiftCatalog.Rarity.LEGENDARY) 6 else 3
            for (i in 0 until n) {
                val a = t * 1.3f + i * (2 * PI.toFloat() / n)
                val rr = s * 0.62f
                fx.color = if (i % 2 == 0) 0xFFFFF3B0.toInt() else Color.WHITE
                fx.alpha = (160 + 90 * sin(t * 4f + i)).toInt().coerceIn(0, 255)
                star(c, cx + cos(a) * rr, cy + sin(a) * rr * 0.8f, (1.6f + (i % 2)) * d)
            }
        }
    }

    /** Грани у самоцвета и кристалла, камни на зубцах короны. */
    private fun drawDetails(c: Canvas, kind: String, cx: Float, cy: Float, s: Float, t: Float, moving: Boolean) {
        pat.shader = null
        when (kind) {
            "gem" -> {
                tmp.reset()
                tmp.moveTo(cx - 0.22f * s, cy - 0.38f * s)
                tmp.lineTo(cx + 0.22f * s, cy - 0.38f * s)
                tmp.lineTo(cx + 0.12f * s, cy - 0.14f * s)
                tmp.lineTo(cx - 0.12f * s, cy - 0.14f * s)
                tmp.close()
                pat.style = Paint.Style.FILL
                pat.color = 0x55FFFFFF
                c.drawPath(tmp, pat)
                pat.style = Paint.Style.STROKE
                pat.strokeWidth = s * 0.012f
                pat.color = 0x66FFFFFF
                c.drawLine(cx - 0.42f * s, cy - 0.14f * s, cx + 0.42f * s, cy - 0.14f * s, pat)
                c.drawLine(cx - 0.12f * s, cy - 0.14f * s, cx, cy + 0.42f * s, pat)
                c.drawLine(cx + 0.12f * s, cy - 0.14f * s, cx, cy + 0.42f * s, pat)
            }
            "crystal" -> {
                pat.style = Paint.Style.FILL
                pat.color = 0x33FFFFFF
                tmp.reset()
                tmp.moveTo(cx, cy - 0.5f * s)
                tmp.lineTo(cx - 0.25f * s, cy - 0.24f * s)
                tmp.lineTo(cx - 0.25f * s, cy + 0.24f * s)
                tmp.lineTo(cx, cy + 0.5f * s)
                tmp.close()
                c.drawPath(tmp, pat)
                pat.style = Paint.Style.STROKE
                pat.strokeWidth = s * 0.012f
                pat.color = 0x55FFFFFF
                c.drawLine(cx, cy - 0.5f * s, cx, cy + 0.5f * s, pat)
            }
            "crown" -> {
                pat.style = Paint.Style.FILL
                val tips = floatArrayOf(-0.44f, -0.26f, 0f, -0.36f, 0.44f, -0.26f)
                for (i in 0 until 3) {
                    val tw = if (moving) 0.75f + 0.25f * sin(t * 3f + i) else 1f
                    pat.color = if (i == 1) 0xFFFF5D8F.toInt() else 0xFF7DD3FC.toInt()
                    c.drawCircle(cx + tips[i * 2] * s, cy + tips[i * 2 + 1] * s, s * 0.055f * tw, pat)
                    pat.color = 0xCCFFFFFF.toInt()
                    c.drawCircle(cx + tips[i * 2] * s - s * 0.015f, cy + tips[i * 2 + 1] * s - s * 0.015f, s * 0.016f, pat)
                }
                pat.color = 0x44FFFFFF
                rect.set(cx - 0.44f * s, cy + 0.16f * s, cx + 0.44f * s, cy + 0.3f * s)
                c.drawRect(rect, pat)
            }
        }
        pat.style = Paint.Style.FILL
    }

    /** Эмодзи-подарок: светящийся медальон и мягкая тень под эмодзи. */
    private fun drawMedal(c: Canvas, w: Float, h: Float, t: Float) {
        val m0 = medal ?: return
        val pulse = if (anim()) 0.92f + 0.08f * sin(t * 2.3f) else 1f
        glowMatrix.setScale(pulse, pulse, w / 2, h * 0.46f)
        m0.setLocalMatrix(glowMatrix)
        fx.shader = m0
        fx.alpha = 255
        c.drawRect(0f, 0f, w, h, fx)
        fx.shader = null
        fx.color = 0x33000000
        rect.set(w * 0.3f, h * 0.8f, w * 0.7f, h * 0.88f)
        c.drawOval(rect, fx)
    }

    /** Обычные: пузырьки поднимаются со дна. */
    private fun drawBubbles(c: Canvas, w: Float, h: Float, t: Float) {
        fx.shader = null
        for (i in 0 until 7) {
            val seed = (gift.id * 31 + i * 97) % 100 / 100f
            val speed = 0.12f + seed * 0.1f
            val p = ((t * speed + seed) % 1f)
            val x = w * (0.1f + ((i * 0.37f + seed) % 0.8f))
            val y = h * (1.1f - p * 1.3f)
            val rr = (2.5f + seed * 4f) * d
            fx.color = Color.WHITE
            fx.alpha = ((1f - p) * 90).toInt()
            c.drawCircle(x + sin(t * 1.5f + i) * 3 * d, y, rr, fx)
        }
        // Мягкий блик сверху.
        fx.shader = topGlow
        fx.alpha = 255
        c.drawRect(0f, 0f, w, h, fx)
        fx.shader = null
    }

    private fun glow(c: Canvas, w: Float, h: Float, scale: Float) {
        val s = centerGlow ?: return
        m.setScale(scale, scale, w / 2, h / 2)
        s.setLocalMatrix(m)
        fx.shader = s
        fx.alpha = 255
        c.drawRect(0f, 0f, w, h, fx)
        fx.shader = null
    }

    /** Редкие: сияние в центре и мерцающие звёздочки. */
    private fun drawSparkles(c: Canvas, w: Float, h: Float, t: Float) {
        glow(c, w, h, 0.75f + 0.25f * sin(t * 2f))
        fx.color = Color.WHITE
        for (i in 0 until 9) {
            val seed = (gift.id * 53 + i * 71) % 100 / 100f
            val tw = ((sin(t * (1.6f + seed * 2f) + i * 1.7f) + 1f) / 2f)
            val x = w * (0.08f + ((i * 0.41f + seed) % 0.84f))
            val y = h * (0.08f + ((i * 0.29f + seed * 1.3f) % 0.84f))
            val s = (2f + seed * 4f) * d * tw
            fx.alpha = (tw * 230).toInt()
            star(c, x, y, s)
        }
    }

    private val starPath = Path()
    private fun star(c: Canvas, x: Float, y: Float, s: Float) {
        if (s < 0.5f) return
        starPath.reset()
        starPath.moveTo(x, y - s * 2)
        starPath.quadTo(x, y, x + s * 2, y)
        starPath.quadTo(x, y, x, y + s * 2)
        starPath.quadTo(x, y, x - s * 2, y)
        starPath.quadTo(x, y, x, y - s * 2)
        c.drawPath(starPath, fx)
    }

    /** Легендарные: вращающиеся лучи, сияние и частицы по орбите. */
    private fun drawRays(c: Canvas, w: Float, h: Float, t: Float) {
        val cx = w / 2
        val cy = h / 2
        val rays = 12
        val rot = t * 18f
        fx.shader = null
        fx.color = Color.WHITE
        for (i in 0 until rays) {
            val a0 = Math.toRadians((rot + i * 360.0 / rays)).toFloat()
            val a1 = a0 + (PI / rays * 0.55).toFloat()
            starPath.reset()
            starPath.moveTo(cx, cy)
            starPath.lineTo(cx + cos(a0) * w, cy + sin(a0) * w)
            starPath.lineTo(cx + cos(a1) * w, cy + sin(a1) * w)
            starPath.close()
            fx.alpha = 34
            c.drawPath(starPath, fx)
        }
        glow(c, w, h, (0.8f + 0.2f * sin(t * 2.4f)) * 0.9f)
        for (i in 0 until 8) {
            val a = t * (0.9f + (i % 3) * 0.25f) + i * (2 * PI.toFloat() / 8)
            val rr = w * (0.34f + (i % 2) * 0.06f)
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr * 0.92f
            fx.color = if (i % 2 == 0) 0xFFFFF7AE.toInt() else Color.WHITE
            fx.alpha = 220
            c.drawCircle(x, y, (1.6f + (i % 3)) * d, fx)
        }
    }

    /** Рамка: у редких по рамке бежит блик, у легендарных - переливается цветами. */
    /** Блик, наискосок пробегающий по картинке раз в несколько секунд (у легендарных - чаще). */
    private fun drawShine(c: Canvas, w: Float, h: Float, t: Float) {
        val period = if (gift.rarity == GiftCatalog.Rarity.LEGENDARY) 2.6f else 4.5f
        val p = (t % period) / period
        if (p > 0.45f) return
        val sh = shineShader ?: LinearGradient(0f, 0f, w * 0.35f, h * 0.35f, intArrayOf(0x00FFFFFF, 0x66FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            .also { shineShader = it }
        val x = -w * 0.5f + p / 0.45f * w * 1.6f
        m.setTranslate(x, x)
        sh.setLocalMatrix(m)
        shine.shader = sh
        c.drawRect(0f, 0f, w, h, shine)
    }

    private fun drawFrame(c: Canvas, w: Float, h: Float, r: Float, t: Float) {
        if (art != null) {
            // Рамка цвета редкости, как на карточках подарков; у редких и легендарных - переливается.
            val sw = 2.2f * d
            frame.strokeWidth = sw
            val i = sw / 2
            rect.set(i, i, w - i, h - i)
            if (gift.rarity == GiftCatalog.Rarity.COMMON || sweep == null) {
                frame.shader = null
                frame.color = (gift.rarity.color and 0x00FFFFFF) or 0xCC000000.toInt()
            } else {
                m.setRotate(t * 90f % 360f, w / 2, h / 2)
                sweep?.setLocalMatrix(m)
                frame.shader = sweep
            }
            c.drawRoundRect(rect, r - i, r - i, frame)
            return
        }
        val inset = when (gift.rarity) {
            GiftCatalog.Rarity.COMMON -> return
            GiftCatalog.Rarity.RARE -> 1.5f * d
            GiftCatalog.Rarity.LEGENDARY -> 2.2f * d
        }
        frame.strokeWidth = inset * 2
        val sw = sweep ?: return
        m.setRotate(t * 90f % 360f, w / 2, h / 2)
        sw.setLocalMatrix(m)
        frame.shader = sw
        rect.set(inset, inset, w - inset, h - inset)
        c.drawRoundRect(rect, r - inset, r - inset, frame)
    }
}

/** Картинки подарков из assets/gifts - декодируются один раз и хранятся в памяти (их немного и они маленькие). */
object GiftImages {
    // Кэш - 1/8 доступной памяти (обычно 32 МБ): все подарки помещаются, сетка листается
    // без повторного декодирования (раньше 16 МБ не хватало - картинки декодировались заново).
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceIn(16 * 1024 * 1024, 64 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    fun get(ctx: Context, name: String): android.graphics.Bitmap? {
        cache.get(name)?.let { return it }
        val b = runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inScaled = false
            }
            ctx.assets.open("gifts/$name").use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull() ?: return null
        // Мип-карты: в маленьких ячейках картинка уменьшается чётко, без «лесенки» и мыла.
        b.setHasMipMap(true)
        b.prepareToDraw()
        cache.put(name, b)
        return b
    }
}
