// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.player

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Кнопка плеера с векторной иконкой, нарисованной кодом (без эмодзи):
 * - стеклянный круг, при нажатии кнопка «сжимается» и пружинит обратно, расходится волна;
 * - «пауза ↔ воспроизведение» плавно перетекают друг в друга (морфинг фигур);
 * - кнопки режимов (повтор, поворот, масштаб) плавно меняют иконку и подсвечиваются, когда включены.
 */
/**
 * Цвета плееров под оформление: фирменные (фиолетовый → бирюзовый), осенью - тёплые
 * (янтарь → оранжевый), зимой - холодные (голубой → синий).
 */
object PlayerTheme {
    fun colors(ctx: Context): IntArray = when (com.stoyko.smessenger.ui.Seasonal.current(ctx)) {
        com.stoyko.smessenger.ui.Seasonal.Kind.AUTUMN -> intArrayOf(0xFFF59E0B.toInt(), 0xFFEA580C.toInt())
        com.stoyko.smessenger.ui.Seasonal.Kind.WINTER -> intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt())
        com.stoyko.smessenger.ui.Seasonal.Kind.SPRING -> intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt())
        com.stoyko.smessenger.ui.Seasonal.Kind.SUMMER -> intArrayOf(0xFF2DD4BF.toInt(), 0xFF0D9488.toInt())
        com.stoyko.smessenger.ui.Seasonal.Kind.NONE -> intArrayOf(0xFF7B6CF6.toInt(), 0xFF00C2A8.toInt())
    }
}

@SuppressLint("ViewConstructor")
class PlayerButton(context: Context, val kind: Kind, private val big: Boolean = false) : View(context) {
    enum class Kind { PLAY_PAUSE, REWIND, FORWARD, BACK, MORE, SPEED, FIT, ROTATE, LOOP, PIP, LOCK, PREV, NEXT }

    private val d = resources.displayMetrics.density
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d }
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()
    private val theme = PlayerTheme.colors(context)

    /** 0 - пауза (идёт воспроизведение), 1 - треугольник «играть». */
    private var morph = 1f
    private var morphAnim: ValueAnimator? = null

    /** Режим кнопки (повтор вкл., ориентация 0/1/2, масштаб 0/1/2) - меняет иконку. */
    var mode: Int = 0
        set(v) {
            if (field == v) return
            field = v
            // Иконка меняется с поворотом и «пружиной».
            animate().cancel()
            rotation = -40f
            alpha = 0.4f
            animate().rotation(0f).alpha(1f).setDuration(320).setInterpolator(OvershootInterpolator(2.4f)).start()
            invalidate()
        }

    /** Подсветка «включено» (повтор, блокировка). */
    var active: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    /** Текст на кнопке скорости. */
    var label: String = ""
        set(v) {
            field = v
            invalidate()
        }

    private var waveT = -1f

    init {
        isClickable = true
        isFocusable = true
        // Тень-свечение большой кнопки рисуется только программно.
        if (big) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    /** Состояние «играет» - показываем паузу (с морфингом). */
    fun setPlaying(playing: Boolean, animate: Boolean = true) {
        val target = if (playing) 0f else 1f
        morphAnim?.cancel()
        if (!animate) {
            morph = target
            invalidate()
            return
        }
        morphAnim = ValueAnimator.ofFloat(morph, target).apply {
            duration = 280
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                morph = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.86f).scaleY(0.86f).setDuration(90).setInterpolator(DecelerateInterpolator()).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3.5f)).start()
                if (e.actionMasked == MotionEvent.ACTION_UP) {
                    ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 450
                        addUpdateListener {
                            waveT = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                }
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = s / 2f - 2 * d
        // Фон: «стеклянный» круг; большая кнопка - фирменный градиент со свечением.
        if (big) {
            bg.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, theme[0], theme[1], Shader.TileMode.CLAMP)
            bg.setShadowLayer(14 * d, 0f, 3 * d, (theme[0] and 0x00FFFFFF) or 0x88000000.toInt())
        } else {
            bg.shader = null
            bg.color = if (active) (theme[0] and 0x00FFFFFF) or 0x66000000 else 0x33FFFFFF
        }
        canvas.drawCircle(cx, cy, r, bg)
        rim.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r, 0x80FFFFFF.toInt(), 0x10FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r - rim.strokeWidth / 2, rim)
        // Волна после нажатия.
        if (waveT in 0f..1f) {
            wave.color = ((0x88 * (1 - waveT)).toInt() shl 24) or 0xFFFFFF
            canvas.drawCircle(cx, cy, r * (1f + 0.45f * waveT), wave)
            if (waveT >= 1f) waveT = -1f
        }
        // Иконка в квадрате 24×24 «единиц», вписанном в круг.
        val box = s * (if (big) 0.42f else 0.5f)
        val u = box / 24f
        canvas.save()
        canvas.translate(cx - box / 2, cy - box / 2)
        fg.strokeWidth = (if (big) 2.6f else 2.1f) * d
        when (kind) {
            Kind.PLAY_PAUSE -> drawPlayPause(canvas, u)
            Kind.REWIND -> drawSkip(canvas, u, back = true)
            Kind.FORWARD -> drawSkip(canvas, u, back = false)
            Kind.BACK -> {
                path.reset()
                path.moveTo(15 * u, 5 * u)
                path.lineTo(8 * u, 12 * u)
                path.lineTo(15 * u, 19 * u)
                canvas.drawPath(path, fg)
            }
            Kind.MORE -> for (i in 0..2) canvas.drawCircle(12 * u, (6 + i * 6) * u, 1.8f * u, fill)
            Kind.SPEED -> {
                text.textSize = 11.5f * u
                canvas.drawText(label, 12 * u, 12 * u - (text.descent() + text.ascent()) / 2, text)
            }
            Kind.FIT -> drawFit(canvas, u)
            Kind.ROTATE -> drawRotate(canvas, u)
            Kind.LOOP -> drawLoop(canvas, u)
            Kind.PIP -> {
                val rr = RectF(3 * u, 5 * u, 21 * u, 19 * u)
                canvas.drawRoundRect(rr, 2.5f * u, 2.5f * u, fg)
                canvas.drawRoundRect(RectF(12 * u, 12 * u, 19 * u, 17 * u), 1.5f * u, 1.5f * u, fill)
            }
            Kind.PREV, Kind.NEXT -> {
                // Предыдущий / следующий: треугольник и полоска.
                canvas.save()
                if (kind == Kind.PREV) canvas.scale(-1f, 1f, 12 * u, 12 * u)
                path.reset()
                path.moveTo(5 * u, 5 * u)
                path.lineTo(15.5f * u, 12 * u)
                path.lineTo(5 * u, 19 * u)
                path.close()
                fill.pathEffect = android.graphics.CornerPathEffect(1.4f * u)
                canvas.drawPath(path, fill)
                fill.pathEffect = null
                canvas.drawRoundRect(RectF(16.5f * u, 5 * u, 19.5f * u, 19 * u), 1.2f * u, 1.2f * u, fill)
                canvas.restore()
            }
            Kind.LOCK -> {
                canvas.drawRoundRect(RectF(6 * u, 11 * u, 18 * u, 20 * u), 2.5f * u, 2.5f * u, fg)
                path.reset()
                path.moveTo(8.5f * u, 11 * u)
                path.lineTo(8.5f * u, 8 * u)
                path.cubicTo(8.5f * u, 3.5f * u, 15.5f * u, 3.5f * u, 15.5f * u, 8 * u)
                if (active) path.lineTo(15.5f * u, 11 * u)
                canvas.drawPath(path, fg)
            }
        }
        canvas.restore()
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Пауза (две полосы) ↔ треугольник: вершины плавно переходят друг в друга. */
    private fun drawPlayPause(c: Canvas, u: Float) {
        val t = morph
        val pauseL = floatArrayOf(6f, 4.5f, 10f, 4.5f, 10f, 19.5f, 6f, 19.5f)
        val pauseR = floatArrayOf(14f, 4.5f, 18f, 4.5f, 18f, 19.5f, 14f, 19.5f)
        val playL = floatArrayOf(7.5f, 4f, 13.8f, 7.9f, 13.8f, 16.1f, 7.5f, 20f)
        val playR = floatArrayOf(13.8f, 7.9f, 20f, 12f, 20f, 12f, 13.8f, 16.1f)
        for ((a, b) in listOf(pauseL to playL, pauseR to playR)) {
            path.reset()
            for (i in 0 until 4) {
                val x = lerp(a[i * 2], b[i * 2], t) * u
                val y = lerp(a[i * 2 + 1], b[i * 2 + 1], t) * u
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            fill.pathEffect = android.graphics.CornerPathEffect(1.6f * u)
            c.drawPath(path, fill)
            fill.pathEffect = null
        }
    }

    /** Круговая стрелка с числом 10 (назад/вперёд). */
    private fun drawSkip(c: Canvas, u: Float, back: Boolean) {
        val oval = RectF(3.5f * u, 3.5f * u, 20.5f * u, 20.5f * u)
        c.save()
        if (back) c.scale(-1f, 1f, 12 * u, 12 * u)
        c.drawArc(oval, -70f, 300f, false, fg)
        // Наконечник стрелки в начале дуги.
        val a = Math.toRadians(-70.0)
        val px = (12 + 8.5 * cos(a)).toFloat() * u
        val py = (12 + 8.5 * sin(a)).toFloat() * u
        path.reset()
        path.moveTo(px - 3.2f * u, py - 2.4f * u)
        path.lineTo(px + 0.4f * u, py)
        path.lineTo(px - 2.6f * u, py + 3.2f * u)
        c.drawPath(path, fg)
        c.restore()
        text.textSize = 7.5f * u
        c.drawText("10", 12 * u, 12 * u - (text.descent() + text.ascent()) / 2, text)
    }

    /** Масштаб: 0 - вписать (рамка), 1 - заполнить (стрелки наружу), 2 - растянуть (↔). */
    private fun drawFit(c: Canvas, u: Float) {
        when (mode) {
            0 -> {
                c.drawRoundRect(RectF(3 * u, 6 * u, 21 * u, 18 * u), 2.5f * u, 2.5f * u, fg)
                c.drawRoundRect(RectF(7 * u, 9 * u, 17 * u, 15 * u), 1.5f * u, 1.5f * u, fill)
            }
            1 -> {
                for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                    val x = 12 + sx * 8
                    val y = 12 + sy * 7
                    path.reset()
                    path.moveTo(x * u, (y - sy * 4) * u)
                    path.lineTo(x * u, y * u)
                    path.lineTo((x - sx * 4) * u, y * u)
                    c.drawPath(path, fg)
                }
            }
            else -> {
                c.drawLine(4 * u, 12 * u, 20 * u, 12 * u, fg)
                path.reset()
                path.moveTo(7.5f * u, 8.5f * u); path.lineTo(4 * u, 12 * u); path.lineTo(7.5f * u, 15.5f * u)
                path.moveTo(16.5f * u, 8.5f * u); path.lineTo(20 * u, 12 * u); path.lineTo(16.5f * u, 15.5f * u)
                c.drawPath(path, fg)
            }
        }
    }

    /** Поворот: 0 - авто (телефон со стрелкой), 1 - горизонтально, 2 - вертикально. */
    private fun drawRotate(c: Canvas, u: Float) {
        when (mode) {
            1 -> c.drawRoundRect(RectF(3 * u, 7 * u, 21 * u, 17 * u), 2.5f * u, 2.5f * u, fg)
            2 -> c.drawRoundRect(RectF(7 * u, 3 * u, 17 * u, 21 * u), 2.5f * u, 2.5f * u, fg)
            else -> {
                c.drawRoundRect(RectF(8 * u, 5 * u, 16 * u, 19 * u), 2 * u, 2 * u, fg)
                c.drawArc(RectF(2.5f * u, 2.5f * u, 21.5f * u, 21.5f * u), 200f, 60f, false, fg)
                c.drawArc(RectF(2.5f * u, 2.5f * u, 21.5f * u, 21.5f * u), 20f, 60f, false, fg)
            }
        }
        if (mode != 0) {
            // Маленький замок - ориентация зафиксирована.
            c.drawCircle(12 * u, 12 * u, 1.7f * u, fill)
        }
    }

    /** Повтор: две стрелки по кругу; выключен - перечёркнут. */
    private fun drawLoop(c: Canvas, u: Float) {
        val rr = RectF(4 * u, 7 * u, 20 * u, 17 * u)
        c.drawArc(rr, 200f, 140f, false, fg)
        c.drawArc(rr, 20f, 140f, false, fg)
        path.reset()
        path.moveTo(15 * u, 4.5f * u); path.lineTo(18 * u, 7 * u); path.lineTo(15 * u, 9.5f * u)
        path.moveTo(9 * u, 14.5f * u); path.lineTo(6 * u, 17 * u); path.lineTo(9 * u, 19.5f * u)
        c.drawPath(path, fg)
        if (!active) c.drawLine(4 * u, 20 * u, 20 * u, 4 * u, fg)
    }
}

/**
 * Подсказка жестов по центру экрана: иконка (солнце, динамик, перемотка, лупа), уровень
 * полоской и подпись. Появляется мягко и исчезает сама.
 */
class GestureHud(context: Context) : View(context) {
    enum class Icon { NONE, SUN, VOLUME, SEEK_BACK, SEEK_FWD, ZOOM }

    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA101018.toInt() }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15 * d
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()
    private val theme = PlayerTheme.colors(context)
    private var icon = Icon.NONE
    private var level = -1f
    private var label = ""
    private val hide = Runnable { animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(220).start() }

    init {
        alpha = 0f
    }

    fun show(icon: Icon, label: String, level: Float = -1f) {
        this.icon = icon
        this.label = label
        this.level = level
        removeCallbacks(hide)
        requestLayout()
        if (alpha < 1f) {
            animate().cancel()
            scaleX = 0.9f
            scaleY = 0.9f
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(2f)).start()
        }
        invalidate()
        postDelayed(hide, 900)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((150 * d).toInt(), (if (level >= 0f || icon != Icon.NONE) 124 * d else 56 * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(RectF(0f, 0f, w, h), 22 * d, 22 * d, bg)
        val cx = w / 2
        var y = 18 * d
        if (icon != Icon.NONE) {
            val u = 1.6f * d
            canvas.save()
            canvas.translate(cx - 12 * u, y)
            when (icon) {
                Icon.SUN -> {
                    canvas.drawCircle(12 * u, 12 * u, 4.5f * u, fill)
                    for (i in 0 until 8) {
                        val a = i * Math.PI / 4
                        canvas.drawLine(
                            (12 + 7 * cos(a)).toFloat() * u, (12 + 7 * sin(a)).toFloat() * u,
                            (12 + 10 * cos(a)).toFloat() * u, (12 + 10 * sin(a)).toFloat() * u, fg,
                        )
                    }
                }
                Icon.VOLUME -> {
                    path.reset()
                    path.moveTo(3 * u, 9 * u); path.lineTo(7 * u, 9 * u); path.lineTo(12 * u, 4.5f * u)
                    path.lineTo(12 * u, 19.5f * u); path.lineTo(7 * u, 15 * u); path.lineTo(3 * u, 15 * u); path.close()
                    canvas.drawPath(path, fill)
                    if (level > 0.05f) canvas.drawArc(RectF(10 * u, 8 * u, 18 * u, 16 * u), -50f, 100f, false, fg)
                    if (level > 0.5f) canvas.drawArc(RectF(9 * u, 4.5f * u, 22 * u, 19.5f * u), -50f, 100f, false, fg)
                }
                Icon.SEEK_BACK, Icon.SEEK_FWD -> {
                    canvas.save()
                    if (icon == Icon.SEEK_BACK) canvas.scale(-1f, 1f, 12 * u, 12 * u)
                    for (k in 0..1) {
                        path.reset()
                        val x0 = 3 + k * 9f
                        path.moveTo(x0 * u, 6 * u); path.lineTo((x0 + 9) * u, 12 * u); path.lineTo(x0 * u, 18 * u); path.close()
                        canvas.drawPath(path, fill)
                    }
                    canvas.restore()
                }
                Icon.ZOOM -> {
                    canvas.drawCircle(10 * u, 10 * u, 6 * u, fg)
                    canvas.drawLine(14.5f * u, 14.5f * u, 20 * u, 20 * u, fg)
                }
                Icon.NONE -> Unit
            }
            canvas.restore()
            y += 24 * 1.6f * d + 10 * d
        }
        canvas.drawText(label, cx, y + 12 * d, text)
        y += 24 * d
        if (level >= 0f) {
            val left = 18 * d
            val right = w - 18 * d
            val r = RectF(left, y, right, y + 6 * d)
            canvas.drawRoundRect(r, 3 * d, 3 * d, track)
            bar.shader = LinearGradient(left, 0f, right, 0f, theme[0], theme[1], Shader.TileMode.CLAMP)
            canvas.drawRoundRect(RectF(left, y, left + (right - left) * level.coerceIn(0f, 1f), y + 6 * d), 3 * d, 3 * d, bar)
        }
    }
}
