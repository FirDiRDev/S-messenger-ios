// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.anim

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import com.stoyko.smessenger.chat.SelectionMenu
import kotlin.math.abs

/**
 * Жест «Назад» с резиновой оттяжкой: экран чата тянется вправо ровно за пальцем, под ним виден
 * прежний экран (список чатов) - чем дальше потянули, тем меньше он размыт и затемнён и тем
 * ближе к своему месту (параллакс). Отпустили раньше половины ширины (и без быстрого броска) -
 * экран возвращается с пружинной «резиновой» оттяжкой; дальше половины - плавно уходит, и
 * открывается прежний экран.
 *
 * Касания получает от активности ([dispatch] вызывается из dispatchTouchEvent): жест
 * перехватывается только при явном движении вправо, иначе всё работает как обычно.
 */
class SwipeBack(
    private val page: View,
    /** Под кем рисовать прежний экран (контейнер, в котором лежит [page]). */
    private val under: View,
    private val onBack: () -> Unit,
    /** Касание началось здесь (экранные координаты) - жест не начинаем (например, поле ввода). */
    private val blocked: (Float, Float) -> Boolean = { _, _ -> false },
) {
    private val ctx = page.context
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = 900 * ctx.resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracking = false
    private var vt: VelocityTracker? = null
    private var backdrop: Backdrop? = null
    private var oldBackground: Drawable? = null

    /** true - событие поглощено жестом. */
    fun dispatch(ev: MotionEvent, cancelOthers: (MotionEvent) -> Unit): Boolean {
        if (!page.isAttachedToWindow || page.width == 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                dragging = false
                tracking = PeekPop.motionOk(ctx) && !blocked(ev.rawX, ev.rawY) && page.translationX == 0f
                vt?.recycle()
                vt = VelocityTracker.obtain().also { it.addMovement(ev) }
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (!dragging) tracking = false
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                vt?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!dragging) {
                    if (dx > slop * 2 && dx > abs(dy) * 2f) {
                        dragging = true
                        start()
                        // Список и кнопки под пальцем не должны получить «нажатие».
                        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        cancelOthers(cancel)
                        cancel.recycle()
                    } else if (abs(dy) > slop * 2 || dx < -slop * 2) {
                        tracking = false
                    }
                    if (!dragging) return false
                }
                move(dx - slop * 2)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) {
                    tracking = false
                    return false
                }
                vt?.addMovement(ev)
                vt?.computeCurrentVelocity(1000)
                val v = vt?.xVelocity ?: 0f
                dragging = false
                tracking = false
                val x = page.translationX
                if (ev.actionMasked == MotionEvent.ACTION_UP && (x > page.width * 0.5f || (v > minFling && x > page.width * 0.12f))) finish() else springBack()
                return true
            }
        }
        return dragging
    }

    private fun start() {
        page.animate().cancel()
        val bmp = Hero.backdrop
        oldBackground = under.background
        val b = Backdrop(bmp)
        backdrop = b
        under.background = b
        // Клавиатура мешает жесту - прячем.
        runCatching {
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(page.windowToken, 0)
        }
        page.elevation = 24 * ctx.resources.displayMetrics.density
        // Прозрачный экран чата не должен «просвечивать» прежним экраном - даём ему фон окна.
        pageBackgroundSet = false
        if (page.background == null) {
            val tv = android.util.TypedValue()
            if (ctx.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)) {
                page.setBackgroundColor(tv.data)
                pageBackgroundSet = true
            }
        }
    }

    private var pageBackgroundSet = false

    private fun move(dx: Float) {
        // Влево за край - «резина»: сопротивление растёт.
        val x = if (dx >= 0) dx else -rubber(-dx, page.width * 0.2f)
        apply(x)
    }

    private fun rubber(d: Float, limit: Float) = limit * (1f - 1f / (d / limit + 1f))

    private fun apply(x: Float) {
        page.translationX = x
        backdrop?.progress = (x / page.width).coerceIn(0f, 1f)
    }

    private fun springBack() {
        page.animate().translationX(0f).setDuration(520).setInterpolator(SelectionMenu.Spring(0.45f, 1.6f))
            .setUpdateListener { backdrop?.progress = (page.translationX / page.width).coerceIn(0f, 1f) }
            .withEndAction { cleanup() }
            .start()
    }

    private fun finish() {
        page.animate().translationX(page.width.toFloat()).setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .setUpdateListener { backdrop?.progress = (page.translationX / page.width).coerceIn(0f, 1f) }
            .withEndAction {
                // Экран уже за краем - уходит без своей анимации, прежний виден сразу.
                page.visibility = View.INVISIBLE
                onBack()
                page.post { cleanup() }
            }
            .start()
    }

    private fun cleanup() {
        page.animate().setUpdateListener(null)
        page.elevation = 0f
        if (pageBackgroundSet) {
            page.background = null
            pageBackgroundSet = false
        }
        if (under.background === backdrop) under.background = oldBackground
        backdrop = null
    }

    /**
     * Прежний экран под чатом: снимок, сдвинутый влево (параллакс), размытый и затемнённый -
     * размытие и затемнение уходят по мере жеста. Размытие - смесь чёткой и заранее размытой
     * (уменьшенной) копии, поэтому кадр дешёвый.
     */
    private class Backdrop(val sharp: Bitmap?) : Drawable() {
        var progress = 0f
            set(v) {
                field = v
                invalidateSelf()
            }
        private val blurred: Bitmap? = sharp?.let { b ->
            runCatching {
                val small = Bitmap.createScaledBitmap(b, (b.width / 10).coerceAtLeast(1), (b.height / 10).coerceAtLeast(1), true)
                Bitmap.createScaledBitmap(small, b.width, b.height, true)
            }.getOrNull()
        }
        private val p = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dim = Paint().apply { color = Color.BLACK }
        private val dst = Rect()

        override fun draw(c: Canvas) {
            val b = bounds
            val shift = (-(1f - progress) * b.width() * 0.3f).toInt()
            dst.set(b.left + shift, b.top, b.right + shift, b.bottom)
            if (sharp == null) {
                c.drawColor(0xFF15131F.toInt())
            } else {
                p.alpha = 255
                c.drawBitmap(sharp, null, dst, p)
                blurred?.let {
                    p.alpha = (255 * (1f - progress)).toInt().coerceIn(0, 255)
                    c.drawBitmap(it, null, dst, p)
                }
            }
            dim.alpha = (120 * (1f - progress)).toInt().coerceIn(0, 255)
            c.drawRect(b, dim)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    companion object {
        /** Активный жест текущего экрана (ставит экран чата, снимает при уходе). */
        @Volatile
        var active: SwipeBack? = null

        /** Касание (экранные координаты) внутри [v]? */
        fun inside(v: View?, x: Float, y: Float): Boolean {
            if (v == null || !v.isShown) return false
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            return x >= loc[0] && x <= loc[0] + v.width && y >= loc[1] && y <= loc[1] + v.height
        }

        /**
         * Включить жест для экрана чата: [page] - корневой вид фрагмента, [onBack] - вернуться,
         * [exclude] - области, где жест не начинается (поле ввода, панели).
         */
        fun enable(page: View, onBack: () -> Unit, vararg exclude: View?) {
            if (com.stoyko.smessenger.ui.TwoPane.active(page.context)) return
            val under = page.parent as? View ?: return
            active = SwipeBack(page, under, onBack) { x, y -> exclude.any { inside(it, x, y) } }
        }

        fun disable(page: View) {
            if (active?.page === page) active = null
        }
    }
}
