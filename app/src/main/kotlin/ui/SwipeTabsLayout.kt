// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Контейнер вкладок с перелистыванием пальцем: содержимое едет за пальцем
 * в обе стороны, отпускание дальше четверти ширины (или быстрый жест) - переход на соседнюю вкладку.
 * Работает по всей площади, в т.ч. на пустом месте под списком.
 */
class SwipeTabsLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** Можно ли перейти в сторону dir (+1 - следующая вкладка, свайп влево; -1 - предыдущая). */
    var canSwipe: (Int) -> Boolean = { false }

    /** Переход подтверждён. */
    var onSwipe: (Int) -> Unit = {}

    /**
     * Палец тянет содержимое: доля ширины (>0 - к следующей вкладке, <0 - к предыдущей).
     * Нужна, чтобы полоска под вкладками ехала за пальцем.
     */
    var onDrag: ((Float) -> Unit)? = null

    /** Палец отпустили, не долистав: содержимое и «пилюля» плавно возвращаются на место. */
    var onCancel: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && abs(dx) > touchSlop * 1.5f && abs(dx) > abs(dy) * 1.4f) {
                    dragging = true
                    // Не отдаём жест боковому меню и прокрутке.
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!dragging) {
                velocity?.recycle()
                velocity = null
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Пустое место под списком: берём жест себе, чтобы свайп работал и здесь.
                downX = ev.x
                downY = ev.y
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(ev) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                if (!dragging && abs(dx) > touchSlop * 1.5f && abs(dx) > abs(ev.y - downY) * 1.4f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) move(dx)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val dx = ev.x - downX
                val vt = velocity
                var vx = 0f
                if (vt != null) {
                    vt.addMovement(ev)
                    vt.computeCurrentVelocity(1000)
                    vx = vt.xVelocity
                    vt.recycle()
                }
                velocity = null
                if (dragging) finish(dx, vx, ev.actionMasked == MotionEvent.ACTION_UP)
                dragging = false
                return true
            }
        }
        return true
    }

    private fun direction(dx: Float) = if (dx < 0) 1 else -1

    private fun move(dx: Float) {
        // Если соседней вкладки нет - сильное «сопротивление», как у края списка.
        val shift = if (canSwipe(direction(dx))) dx else dx * 0.2f
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.translationX = shift
            c.alpha = 1f - (abs(shift) / width.coerceAtLeast(1)) * 0.5f
        }
        if (canSwipe(direction(dx))) onDrag?.invoke((-shift / width.coerceAtLeast(1)).coerceIn(-1f, 1f))
    }

    private fun finish(dx: Float, vx: Float, released: Boolean) {
        val dir = direction(dx)
        // Порог меньше (20 % ширины) и учитывается скорость - листается легко.
        val far = abs(dx) > width * 0.2f
        val fast = abs(vx) > minFling && (vx < 0) == (dx < 0)
        if (released && (far || fast) && canSwipe(dir)) {
            val target = -dir * width * 0.6f
            var done = false
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            // Содержимое продолжает движение пальца с того места, где его отпустили:
            // чем меньше осталось ехать, тем короче анимация - без рывка и «перерезания».
            val current = if (childCount > 0) getChildAt(0).translationX else 0f
            val left = (abs(target - current) / width.coerceAtLeast(1)).coerceIn(0f, 1f)
            val dur = (70 + 140 * left).toLong()
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                c.animate().translationX(target).alpha(0f).setDuration(dur)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        if (!done) {
                            done = true
                            for (k in 0 until childCount) {
                                getChildAt(k).translationX = 0f
                                getChildAt(k).alpha = 1f
                            }
                            onSwipe(dir)
                        }
                    }.start()
            }
        } else {
            onCancel?.invoke() ?: onDrag?.invoke(0f)
            for (i in 0 until childCount) {
                getChildAt(i).animate().translationX(0f).alpha(1f).setDuration(220)
                    .setInterpolator(DecelerateInterpolator(1.8f)).start()
            }
        }
    }
}
