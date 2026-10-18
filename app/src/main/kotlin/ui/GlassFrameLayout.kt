// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * «Стекло», как на iPhone: панель показывает размытое содержимое, которое под ней прокручивается,
 * с лёгким оттенком и светлой кромкой сверху.
 *
 * Как работает: перед каждым кадром содержимое-«источник» ([blurSource]) рисуется в маленькую
 * картинку (в 1/8 размера) ровно под панелью, картинка размывается и рисуется фоном панели.
 * Маленький размер делает это дешёвым. На слабых телефонах (или если эффект выключен в настройках)
 * рисуется просто полупрозрачная «матовая» подложка без размытия.
 */
open class GlassFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val TAG = "Glass"
        private const val DOWNSCALE = 8f
        private const val MIN_FRAME_MS = 16L

        /** Размытие включено (задаёт приложение из настроек; на слабых устройствах выключено). */
        @Volatile
        var blurEnabled = true

        /** Анимация «жидкого стекла» (блики); выключается вместе с анимациями в настройках. */
        @Volatile
        var liquidEnabled = true

        private const val SHEEN_MS = 1400L
        private const val SHEEN_PERIOD_MS = 7000L

        // Во время снимка источника сами панели не рисуются (иначе стекло попало бы само в себя).
        private var capturing = false

        @Volatile
        private var holdUntil = 0L

        /**
         * Не пересъёмать фон под стеклом [ms] миллисекунд: пока идут лёгкие анимации поверх
         * (частицы, появление букв), содержимое под стеклом не меняется, а пересъёмка каждого
         * кадра делала анимацию рывками.
         */
        fun holdCapture(ms: Long) {
            holdUntil = SystemClock.uptimeMillis() + ms
        }
    }

    private val density = resources.displayMetrics.density

    /** Что размывать: обычно корневой вид экрана или прокручиваемый список. */
    var blurSource: View? = null
        set(value) {
            if (field === value) return
            detachSource()
            field = value
            if (isAttachedToWindow) attachSource()
            invalidate()
        }

    /** Оттенок стекла (цвет с прозрачностью). */
    var tintColor: Int = 0xB31C1A33.toInt()
        set(value) {
            field = value
            tintPaint.color = value
            invalidate()
        }

    /** Цвет, если размытие недоступно (плотнее, чтобы текст читался). */
    var fallbackColor: Int = 0xE61C1A33.toInt()

    var cornerRadius: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var blurRadius: Int = 3

    /** Светлая кромка («блик») по верхнему/нижнему краю. */
    var edgeTop = true
    var edgeBottom = false

    /** Прозрачность самого стекла (дочерние элементы не затрагивает) - для «проявления» при прокрутке. */
    var glassAlpha: Float = 1f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field == v) return
            field = v
            invalidate()
        }

    /**
     * «Жидкое стекло»: объёмная кромка (свет сверху, тень снизу) и мягкий блик, который время
     * от времени проходит по панели, как отражение на стекле iOS 26.
     */
    var liquid: Boolean = liquidEnabled
        set(value) {
            field = value
            scheduleSheen(2500)
            invalidate()
        }

    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sheenStart = -1L
    private val sheenRunnable = Runnable {
        if (liquid && isShown && liquidEnabled && !EnergySaver.on && !LiteMode.on) {
            sheenStart = SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        } else {
            scheduleSheen(SHEEN_PERIOD_MS)
        }
    }

    private fun scheduleSheen(delay: Long) {
        removeCallbacks(sheenRunnable)
        if (liquid && isAttachedToWindow) postDelayed(sheenRunnable, delay)
    }

    /** Короткая «волна» блика - например, при нажатии или открытии панели. */
    fun shimmer() {
        if (!liquid) return
        sheenStart = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tintColor }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1f }
    private val rect = RectF()
    private val clip = Path()

    private var bitmap: Bitmap? = null
    private var bitmapCanvas: Canvas? = null
    private var pixels = IntArray(0)
    private var tmp = IntArray(0)
    private var failed = false
    private var lastCapture = 0L
    private val srcLoc = IntArray(2)
    private val myLoc = IntArray(2)

    private val preDraw = ViewTreeObserver.OnPreDrawListener {
        if (isShown && width > 0 && height > 0) capture()
        true
    }

    init {
        setWillNotDraw(false)
        // Дочерние элементы рисуются поверх стекла.
        clipChildren = false
    }

    private fun attachSource() {
        val src = blurSource ?: return
        runCatching { src.viewTreeObserver.addOnPreDrawListener(preDraw) }
    }

    private fun detachSource() {
        val src = blurSource ?: return
        runCatching { src.viewTreeObserver.removeOnPreDrawListener(preDraw) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachSource()
        scheduleSheen(1200 + (Math.random() * 2000).toLong())
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(sheenRunnable)
        detachSource()
        bitmap?.recycle()
        bitmap = null
        bitmapCanvas = null
        super.onDetachedFromWindow()
    }

    private fun usable() = blurEnabled && !failed && blurSource != null

    private fun capture() {
        if (!usable() || capturing) return
        val now = SystemClock.uptimeMillis()
        if (now < holdUntil && bitmap != null) return
        // Экономия энергии - стекло обновляется вдвое реже (на глаз почти незаметно).
        if (now - lastCapture < (if (EnergySaver.on || com.stoyko.smessenger.ui.LiteMode.on) MIN_FRAME_MS * 2 else MIN_FRAME_MS)) return
        lastCapture = now
        val src = blurSource ?: return
        val bw = (width / DOWNSCALE).toInt().coerceAtLeast(1)
        val bh = (height / DOWNSCALE).toInt().coerceAtLeast(1)
        var bmp = bitmap
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap = bmp
            bitmapCanvas = Canvas(bmp)
        }
        val c = bitmapCanvas ?: return
        src.getLocationInWindow(srcLoc)
        getLocationInWindow(myLoc)
        capturing = true
        try {
            bmp.eraseColor(fallbackColor)
            val save = c.save()
            c.scale(1f / DOWNSCALE, 1f / DOWNSCALE)
            c.translate((srcLoc[0] - myLoc[0]).toFloat(), (srcLoc[1] - myLoc[1]).toFloat())
            src.draw(c)
            c.restoreToCount(save)
            // Содержимое под стеклом не изменилось - не перерисовываемся (иначе кадры шли бы
            // бесконечно и расходовали батарею).
            if (!blurIfChanged(bmp, blurRadius)) return
        } catch (e: Throwable) {
            // Например, «аппаратные» картинки нельзя рисовать программно - тогда без размытия.
            Log.w(TAG, "glass disabled: $e")
            failed = true
        } finally {
            capturing = false
        }
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        // Во время снимка для другого стекла себя не рисуем.
        if (capturing) return
        super.draw(canvas)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (glassAlpha <= 0f) {
            super.onDraw(canvas)
            return
        }
        val save = if (glassAlpha < 1f) {
            @Suppress("DEPRECATION")
            canvas.saveLayerAlpha(rect, (glassAlpha * 255).toInt())
        } else {
            canvas.save()
        }
        if (cornerRadius > 0f) {
            clip.reset()
            clip.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clip)
        }
        val bmp = bitmap
        if (usable() && bmp != null && !bmp.isRecycled) {
            if (liquid && liquidEnabled && !LiteMode.on && refraction > 0f) {
                drawRefracted(canvas, bmp)
            } else {
                canvas.save()
                canvas.scale(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
                canvas.restore()
            }
            canvas.drawRect(rect, tintPaint)
        } else {
            tintPaint.color = fallbackColor
            canvas.drawRect(rect, tintPaint)
            tintPaint.color = tintColor
        }
        // Блик: тонкая светлая линия и мягкий градиент, как у стеклянных панелей iOS.
        if (edgeTop) {
            edgePaint.shader = null
            edgePaint.color = 0x33FFFFFF
            canvas.drawLine(0f, 0.5f, width.toFloat(), 0.5f, edgePaint)
            edgePaint.shader = LinearGradient(0f, 0f, 0f, 14 * density, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), 14 * density, edgePaint)
            edgePaint.shader = null
        }
        if (edgeBottom) {
            edgePaint.shader = null
            edgePaint.color = 0x22FFFFFF
            canvas.drawLine(0f, height - 0.5f, width.toFloat(), height - 0.5f, edgePaint)
        }
        if (liquid && liquidEnabled && !LiteMode.on) drawLiquid(canvas)
        canvas.restoreToCount(save)
        super.onDraw(canvas)
    }

    // ---------------- преломление (как в liquid-glass: линза с «губой» у края) ----------------

    /** Сила преломления у края (0 - плоское стекло). */
    var refraction: Float = 0.55f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Ширина полосы преломления у края - доля от меньшей стороны (как bendWidth). */
    var bendWidth: Float = 0.42f

    /** Выпуклость середины (лёгкое увеличение в центре). */
    var curvature: Float = 0.06f

    private val meshW = 18
    private val meshH = 6
    private val verts = FloatArray((meshW + 1) * (meshH + 1) * 2)

    /**
     * Размытая копия фона рисуется через сетку (drawBitmapMesh): у краёв узлы сетки выталкиваются
     * наружу по нормали к контуру - фон «затекает» за кромку, как в настоящей линзе; в середине -
     * лёгкое выпуклое увеличение. Сетка маленькая, поэтому это дёшево даже на старых телефонах.
     */
    private fun drawRefracted(canvas: Canvas, bmp: Bitmap) {
        val w = width.toFloat()
        val h = height.toFloat()
        val band = (minOf(w, h) * bendWidth).coerceAtLeast(1f)
        val push = band * 0.5f * refraction
        val cx = w / 2
        val cy = h / 2
        var i = 0
        for (yi in 0..meshH) {
            val y = h * yi / meshH
            for (xi in 0..meshW) {
                val x = w * xi / meshW
                var dx = 0f
                var dy = 0f
                // Расстояние до ближайшего края и нормаль наружу.
                val dl = x
                val dr = w - x
                val dt = y
                val db = h - y
                val ex = if (dl < dr) dl else dr
                val ey = if (dt < db) dt else db
                if (ex < band) {
                    val k = 1f - ex / band
                    dx += (if (dl < dr) -1f else 1f) * push * k * k
                }
                if (ey < band) {
                    val k = 1f - ey / band
                    dy += (if (dt < db) -1f else 1f) * push * k * k
                }
                // Выпуклая середина: точки немного разъезжаются от центра.
                val nx = (x - cx) / cx.coerceAtLeast(1f)
                val ny = (y - cy) / cy.coerceAtLeast(1f)
                val dome = curvature * (1f - (nx * nx + ny * ny).coerceAtMost(1f))
                dx += (x - cx) * dome
                dy += (y - cy) * dome
                verts[i++] = x + dx
                verts[i++] = y + dy
            }
        }
        canvas.drawBitmapMesh(bmp, meshW, meshH, verts, 0, null, 0, bitmapPaint)
    }

    /** Объём и бегущий блик «жидкого стекла». */
    private fun drawLiquid(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Направленная кромка (sheen): свет собирается к верхнему левому углу, по краю -
        // тонкая цветная «дисперсия» (голубой сверху, розовый снизу), как у стекла с преломлением.
        edgePaint.style = Paint.Style.STROKE
        edgePaint.strokeWidth = 1.2f * density
        edgePaint.shader = LinearGradient(0f, 0f, w * 0.6f, h, 0x66FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP)
        val inset = edgePaint.strokeWidth / 2
        rect.set(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, edgePaint)
        edgePaint.strokeWidth = 1f * density
        edgePaint.shader = LinearGradient(0f, 0f, 0f, 3 * density, 0x3066E0FF, 0x0066E0FF, Shader.TileMode.CLAMP)
        rect.set(inset + density, inset + density, w - inset - density, h - inset - density)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, edgePaint)
        edgePaint.shader = LinearGradient(0f, h - 3 * density, 0f, h, 0x00FF66CC, 0x26FF66CC, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, edgePaint)
        edgePaint.shader = null
        edgePaint.style = Paint.Style.FILL
        rect.set(0f, 0f, w, h)
        // Толщина стекла: светлее у верхнего края, чуть темнее у нижнего.
        val rim = minOf(h * 0.5f, 22 * density)
        sheenPaint.shader = LinearGradient(0f, 0f, 0f, rim, 0x1FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, rim, sheenPaint)
        sheenPaint.shader = LinearGradient(0f, h - rim, 0f, h, 0x00000000, 0x14000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, h - rim, w, h, sheenPaint)
        // Бегущий диагональный блик.
        if (sheenStart >= 0) {
            val t = (SystemClock.uptimeMillis() - sheenStart).toFloat() / SHEEN_MS
            if (t >= 1f) {
                sheenStart = -1
                scheduleSheen(SHEEN_PERIOD_MS + (Math.random() * 4000).toLong())
            } else {
                val e = t * t * (3 - 2 * t)
                val band = maxOf(w, h) * 0.35f
                val x = -band + (w + band * 2) * e
                sheenPaint.shader = LinearGradient(
                    x - band, 0f, x + band, h * 0.6f,
                    intArrayOf(0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawRect(0f, 0f, w, h, sheenPaint)
                postInvalidateOnAnimation()
            }
        }
        sheenPaint.shader = null
    }

    private var lastHash = 0L
    private var blurred = false

    /** Быстрое размытие: три прохода «скользящего среднего» по строкам и столбцам ≈ гауссово. */
    private fun blurIfChanged(bmp: Bitmap, radius: Int): Boolean {
        val w = bmp.width
        val h = bmp.height
        val n = w * h
        if (pixels.size < n) {
            pixels = IntArray(n)
            tmp = IntArray(n)
        }
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var hash = 1125899906842597L
        for (i in 0 until n) hash = 31 * hash + pixels[i]
        hash = 31 * hash + w * 131 + h
        if (blurred && hash == lastHash) {
            // Картинка под стеклом прежняя: восстанавливаем размытую версию из прошлого кадра.
            bmp.setPixels(blurredPixels, 0, w, 0, 0, w, h)
            return false
        }
        lastHash = hash
        repeat(3) {
            boxH(pixels, tmp, w, h, radius)
            boxV(tmp, pixels, w, h, radius)
        }
        bmp.setPixels(pixels, 0, w, 0, 0, w, h)
        if (blurredPixels.size < n) blurredPixels = IntArray(n)
        System.arraycopy(pixels, 0, blurredPixels, 0, n)
        blurred = true
        return true
    }

    private var blurredPixels = IntArray(0)

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inn = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = r * 2 + 1
        for (x in 0 until w) {
            var sa = 0
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sa += p ushr 24
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inn = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sa += (inn ushr 24) - (out ushr 24)
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }
}
