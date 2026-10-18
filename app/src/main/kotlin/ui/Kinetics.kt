// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Пружинные микро-анимации (по мотивам kinetics.colorion.co): «сжимающиеся» кнопки,
 * разлёт частиц, поочерёдное появление списков, упругий прогресс и рисующаяся галочка.
 * Всё - на стандартных средствах Android, без библиотек.
 */
object Kinetics {
    /** Анимации включены (выключаются вместе с анимациями в настройках). */
    @Volatile
    var enabled = true

    // ---------------- Squish Button ----------------

    /**
     * Кнопка «сжимается» при нажатии (до 90%) и пружинит обратно с лёгким перелётом.
     * Сделано через StateListAnimator (состояние «нажато»), поэтому не мешает собственным
     * обработчикам касаний кнопки (запись голосовых, долгое нажатие и т. п.).
     */
    fun squish(view: View, pressedScale: Float = 0.9f) {
        if (!enabled) return
        fun scaleTo(v: Float, ms: Long, interp: android.animation.TimeInterpolator) = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(view, View.SCALE_X, v),
                android.animation.ObjectAnimator.ofFloat(view, View.SCALE_Y, v),
            )
            duration = ms
            interpolator = interp
        }
        view.stateListAnimator = android.animation.StateListAnimator().apply {
            addState(intArrayOf(android.R.attr.state_pressed, android.R.attr.state_enabled), scaleTo(pressedScale, 90, DecelerateInterpolator()))
            addState(intArrayOf(), scaleTo(1f, 420, OvershootInterpolator(3.2f)))
        }
    }

    // ---------------- Shimmer Button (21st.dev) ----------------

    /**
     * «Мерцающая» кнопка: по контуру бежит светлый блик (вращающийся градиент), внутри время
     * от времени проходит мягкая волна света. Для главных кнопок: «Создать», «Добавить», «Далее».
     */
    fun shimmer(view: View) {
        if (!enabled) return
        val d = view.resources.displayMetrics.density
        val drawable = object : Drawable() {
            private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f * d
            }
            private val sweep = Paint(Paint.ANTI_ALIAS_FLAG)
            private val rect = RectF()
            private val start = SystemClock.uptimeMillis()

            private val m = android.graphics.Matrix()
            private val clip = Path()
            private var sg: android.graphics.SweepGradient? = null
            private var sgW = 0
            private var sgH = 0

            override fun draw(canvas: Canvas) {
                val b = bounds
                if (b.isEmpty) return
                // Экономия энергии: без бегущего блика (кнопка остаётся обычной).
                if (EnergySaver.on) return
                val now = SystemClock.uptimeMillis()
                val t = (now - start) / 1000f
                // Блик бежит 2,4 с, потом 3,6 с пауза: экран не перерисовывается постоянно.
                val cycle = t % 6f
                if (cycle > 2.4f) {
                    scheduleSelf(tick, now + ((6f - cycle) * 1000).toLong())
                    return
                }
                val radius = min(b.height() / 2f, 18 * d)
                rect.set(b.left + d, b.top + d, b.right - d, b.bottom - d)
                // Бегущий блик по контуру (градиент создаётся один раз, крутится матрицей).
                if (sg == null || sgW != b.width() || sgH != b.height()) {
                    sgW = b.width()
                    sgH = b.height()
                    sg = android.graphics.SweepGradient(
                        b.exactCenterX(), b.exactCenterY(),
                        intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF),
                        floatArrayOf(0f, 0.7f, 0.85f, 1f),
                    )
                }
                m.setRotate((t * 140f) % 360f, b.exactCenterX(), b.exactCenterY())
                sg!!.setLocalMatrix(m)
                ring.shader = sg
                // Кольцо гаснет к концу пробега - без резкого обрыва.
                ring.alpha = (255 * (1f - ((cycle - 1.9f) / 0.5f).coerceIn(0f, 1f))).toInt()
                canvas.drawRoundRect(rect, radius, radius, ring)
                // Волна света внутри (на слабом телефоне - без неё: обрезка по контуру дорогая).
                val p = cycle / 1.1f
                if (p < 1f && !LiteMode.on) {
                    val w = b.width().toFloat()
                    val x = b.left - w * 0.4f + (w * 1.8f) * p
                    sweep.shader = android.graphics.LinearGradient(
                        x - w * 0.25f, 0f, x + w * 0.25f, b.height().toFloat(),
                        intArrayOf(0x00FFFFFF, 0x33FFFFFF, 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP,
                    )
                    val save = canvas.save()
                    clip.reset()
                    clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
                    canvas.clipPath(clip)
                    canvas.drawRect(b, sweep)
                    canvas.restoreToCount(save)
                }
                // ~30 кадров в секунду достаточно для мягкого блика и бережёт батарею.
                scheduleSelf(tick, now + if (LiteMode.on) 40L else 33L)
            }

            private val tick = Runnable { invalidateSelf() }

            override fun setAlpha(alpha: Int) = Unit
            override fun setColorFilter(colorFilter: ColorFilter?) = Unit

            @Deprecated("Deprecated in Java")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
        view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ -> drawable.setBounds(0, 0, r - l, b - t); v.invalidate() }
        view.overlay.add(drawable)
        drawable.setBounds(0, 0, view.width, view.height)
    }

    // ---------------- Spring entrance / Stagger ----------------

    /** Появление «снизу с пружиной». */
    fun springIn(view: View, delay: Long = 0, dy: Float = 18f) {
        if (!enabled) return
        view.alpha = 0f
        view.translationY = dy * view.resources.displayMetrics.density
        view.scaleX = 0.97f
        view.scaleY = 0.97f
        view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(delay).setDuration(420).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    /** Поочерёдное появление элементов (список участников, лента). */
    fun stagger(views: List<View>, step: Long = 45, max: Int = 14) {
        if (!AnimPrefs.on(AnimPrefs.K.LISTS)) return
        views.forEachIndexed { i, v -> if (i < max) springIn(v, i * step) }
    }

    // ---------------- Like Burst ----------------

    /** Разлёт частиц из центра вида (реакция, «нравится», успешное действие). */
    fun burst(anchor: View, colors: IntArray = intArrayOf(0xFFFF4D6D.toInt(), 0xFFFFB020.toInt(), 0xFF7B6CF6.toInt(), 0xFF00C2A8.toInt())) {
        if (!enabled) return
        val root = anchor.rootView as? ViewGroup ?: return
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        anchor.getLocationInWindow(loc)
        root.getLocationInWindow(rootLoc)
        val cx = loc[0] - rootLoc[0] + anchor.width / 2f
        val cy = loc[1] - rootLoc[1] + anchor.height / 2f
        val d = anchor.resources.displayMetrics.density
        val particles = (0 until 14).map { i ->
            val a = (i / 14.0) * Math.PI * 2 + Random.nextDouble(-0.2, 0.2)
            val speed = (60 + Random.nextFloat() * 70) * d
            Particle(cx, cy, (cos(a) * speed).toFloat(), (sin(a) * speed).toFloat(), (2 + Random.nextFloat() * 3) * d, colors[i % colors.size], 520 + Random.nextLong(0, 200))
        }
        ParticleLayer.attach(root).add(particles)
        anchor.animate().cancel()
        anchor.scaleX = 1.25f
        anchor.scaleY = 1.25f
        anchor.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(4f)).start()
    }

    // ---------------- Частицы (общий слой поверх окна) ----------------

    class Particle(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val color: Int,
        val lifeMs: Long,
        /** Мягкий «пар»: всплывает вверх, колышется и растворяется. */
        val vapor: Boolean = false,
        val delayMs: Long = 0,
    ) {
        var born = -1L
        val phase = Random.nextFloat() * 6.28f
    }

    /**
     * Слой частиц в оверлее корневого вида: не мешает касаниям, рисуется поверх всего окна.
     * Сам отключается, когда частиц не осталось.
     */
    class ParticleLayer private constructor(private val root: ViewGroup) : Drawable() {
        private val items = ArrayList<Particle>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var last = 0L
        private var attached = false

        fun add(list: List<Particle>) {
            val now = SystemClock.uptimeMillis()
            list.forEach { it.born = now + it.delayMs }
            items.addAll(list)
            // Защита от перегрузки: не больше 900 частиц одновременно.
            if (items.size > 900) items.subList(0, items.size - 900).clear()
            if (!attached) {
                setBounds(0, 0, root.width, root.height)
                root.overlay.add(this)
                attached = true
                last = now
            }
            invalidateSelf()
            root.postInvalidateOnAnimation()
        }

        override fun draw(canvas: Canvas) {
            val now = SystemClock.uptimeMillis()
            GlassFrameLayout.holdCapture(120)
            val dt = ((now - last).coerceIn(0, 50)) / 1000f
            last = now
            val iter = items.iterator()
            while (iter.hasNext()) {
                val p = iter.next()
                if (now < p.born) continue
                val age = (now - p.born).toFloat() / p.lifeMs
                if (age >= 1f) {
                    iter.remove()
                    continue
                }
                if (p.vapor) {
                    // Пар: замедляется, всплывает и покачивается на «ветру».
                    p.vx *= 0.94f
                    p.vy = p.vy * 0.94f - 40f * dt
                    p.x += (p.vx + sin(age * 9f + p.phase) * 14f) * dt
                    p.y += p.vy * dt
                } else {
                    p.vy += 220f * dt
                    p.x += p.vx * dt
                    p.y += p.vy * dt
                }
                val fade = 1f - age
                paint.color = p.color
                paint.alpha = (((p.color ushr 24) and 0xFF) * fade * (if (p.vapor) fade else 1f)).toInt().coerceIn(0, 255)
                val r = if (p.vapor) p.radius * (1f + age * 1.6f) else p.radius * (0.4f + fade * 0.6f)
                canvas.drawCircle(p.x, p.y, r, paint)
            }
            if (items.isEmpty()) {
                root.post {
                    if (items.isEmpty() && attached) {
                        root.overlay.remove(this)
                        attached = false
                        layers.remove(root)
                    }
                }
            } else {
                invalidateSelf()
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT

        companion object {
            private val layers = java.util.WeakHashMap<ViewGroup, ParticleLayer>()
            fun attach(root: ViewGroup): ParticleLayer = layers.getOrPut(root) { ParticleLayer(root) }
        }
    }

    fun activityOf(ctx: Context): Activity? {
        var c: Context? = ctx
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}

/**
 * Упругий прогресс (Elastic Progress): полоса плавно догоняет значение с лёгким перелётом,
 * без значения - «бегущая волна» (неопределённый режим).
 */
class ElasticProgressView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x225B4BDB }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val rect = RectF()
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val start = SystemClock.uptimeMillis()

    /** null - неопределённый режим. */
    var progress: Float? = null
        set(value) {
            val target = value?.coerceIn(0f, 1f)
            field = target
            anim?.cancel()
            if (target != null) {
                anim = ValueAnimator.ofFloat(shown, target).apply {
                    duration = 650
                    interpolator = OvershootInterpolator(1.6f)
                    addUpdateListener {
                        shown = (it.animatedValue as Float).coerceIn(0f, 1.04f)
                        invalidate()
                    }
                    start()
                }
            }
            invalidate()
        }

    var colorStart = 0xFF7B6CF6.toInt()
    var colorEnd = 0xFF00C2A8.toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), resolveSize((8 * d).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        fill.shader = android.graphics.LinearGradient(0f, 0f, w, 0f, colorStart, colorEnd, android.graphics.Shader.TileMode.CLAMP)
        val p = progress
        if (p == null) {
            // Бегущий отрезок туда-обратно.
            val t = ((SystemClock.uptimeMillis() - start) % 1400L) / 1400f
            val e = (1 - cos(t * Math.PI * 2).toFloat()) / 2
            val seg = w * 0.32f
            val x = (w - seg) * e
            rect.set(x, 0f, x + seg, h)
            canvas.drawRoundRect(rect, r, r, fill)
            postInvalidateOnAnimation()
        } else {
            rect.set(0f, 0f, max(h, w * min(shown, 1.04f)), h)
            canvas.drawRoundRect(rect, r, r, fill)
            // Блик по верхней половине.
            rect.set(r, h * 0.15f, rect.right - r, h * 0.42f)
            if (rect.width() > 0) canvas.drawRoundRect(rect, h * 0.2f, h * 0.2f, shine)
        }
    }
}

/** «Успех»: сначала рисуется круг, затем галочка (Success Check). */
class SuccessCheckView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4 * d
    }
    var color: Int = 0xFF22C55E.toInt()
        set(v) {
            field = v
            invalidate()
        }
    private var t = 0f
    private val circle = Path()
    private val check = Path()
    private val part = Path()

    fun play(delay: Long = 0) {
        t = 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            duration = 750
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val pad = paint.strokeWidth
        circle.reset()
        circle.addArc(RectF(pad, pad, s - pad, s - pad), -90f, 359.9f)
        check.reset()
        check.moveTo(s * 0.28f, s * 0.53f)
        check.lineTo(s * 0.44f, s * 0.68f)
        check.lineTo(s * 0.73f, s * 0.36f)
        paint.color = color
        val c = (t / 0.55f).coerceIn(0f, 1f)
        val k = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
        fun drawPart(path: Path, f: Float) {
            if (f <= 0f) return
            val pm = PathMeasure(path, false)
            part.reset()
            pm.getSegment(0f, pm.length * f, part, true)
            canvas.drawPath(part, paint)
        }
        drawPart(circle, c)
        drawPart(check, k)
    }
}
