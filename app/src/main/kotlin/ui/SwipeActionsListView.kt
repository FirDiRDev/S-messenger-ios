// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.OvershootInterpolator
import android.widget.ListView
import kotlin.math.abs

/**
 * Список чатов со свайпами:
 *  - потяните чат влево - «В архив» (фиолетовый фон, коробка);
 *  - вправо - «Закрепить/Открепить» (зелёный фон, булавка).
 * Фон с иконкой проявляется по мере свайпа, на пороге иконка «прыгает» и даётся отклик вибрацией.
 */
class SwipeActionsListView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ListView(context, attrs) {
    /** Можно ли свайпать строку (только чаты, не служебные строки). */
    var canSwipe: (Int) -> Boolean = { false }

    /** Действие: позиция и направление (true - влево). */
    var onSwipe: ((position: Int, left: Boolean) -> Unit)? = null

    /** Подписи/иконки действий для позиции: (иконка влево, подпись влево, иконка вправо, подпись вправо). */
    var labels: (Int) -> Array<String> = { arrayOf("🗄", "", "📌", "") }

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 96 * d
    private var downX = 0f
    private var downY = 0f
    private var pos = INVALID_POSITION
    private var dragging = false
    private var passed = false
    private var target: View? = null
    private var shift = 0f
    private var iconPop = 1f
    private var savedBg: android.graphics.drawable.Drawable? = null

    /** Цвет строки во время свайпа (фон экрана). */
    var rowColor: Int = androidx.core.content.ContextCompat.getColor(context, com.stoyko.smessenger.R.color.colorBackground)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 24 * d }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12 * d
        color = 0xFFFFFFFF.toInt()
        isFakeBoldText = true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                pos = pointToPosition(ev.x.toInt(), ev.y.toInt())
                dragging = false
                passed = false
            }
            MotionEvent.ACTION_MOVE -> if (startDrag(ev)) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun startDrag(ev: MotionEvent): Boolean {
        if (dragging) return true
        if (onSwipe == null || pos == INVALID_POSITION || !canSwipe(pos)) return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) > slop * 1.3f && abs(dx) > abs(dy) * 1.8f) {
            target = getChildAt(pos - firstVisiblePosition) ?: return false
            // Строка непрозрачная на время свайпа - под ней виден цветной фон действия.
            savedBg = target?.background
            target?.setBackgroundColor(rowColor)
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                pos = pointToPosition(ev.x.toInt(), ev.y.toInt())
            }
            MotionEvent.ACTION_MOVE -> if (dragging || startDrag(ev)) {
                drag(ev.x - downX)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                release(ev.actionMasked == MotionEvent.ACTION_UP)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun drag(dx: Float) {
        val t = target ?: return
        // Дальше порога - туже (резинка).
        shift = if (abs(dx) < threshold) dx else Math.signum(dx) * (threshold + (abs(dx) - threshold) * 0.35f)
        t.translationX = shift
        val now = abs(shift) >= threshold
        if (now && !passed) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            android.animation.ValueAnimator.ofFloat(1.5f, 1f).apply {
                duration = 300
                interpolator = OvershootInterpolator(3f)
                addUpdateListener {
                    iconPop = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        passed = now
        invalidate()
    }

    private fun release(up: Boolean) {
        val t = target
        val p = pos
        val fire = up && passed
        val left = shift < 0
        dragging = false
        target = null
        t?.animate()?.translationX(0f)?.setDuration(340)?.setInterpolator(OvershootInterpolator(1.4f))
            ?.setUpdateListener { shift = t.translationX; invalidate() }
            ?.withEndAction {
                t.animate().setUpdateListener(null)
                t.background = savedBg
                savedBg = null
                shift = 0f
                invalidate()
            }?.start()
        if (fire && p != INVALID_POSITION) onSwipe?.invoke(p, left)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val t = target
        if (t != null && shift != 0f) {
            val left = shift < 0
            val progress = (abs(shift) / threshold).coerceIn(0f, 1f)
            bg.color = if (left) 0xFF7B6CF6.toInt() else 0xFF22C55E.toInt()
            bg.alpha = (90 + 165 * progress).toInt()
            canvas.drawRect(0f, t.top.toFloat(), width.toFloat(), t.bottom.toFloat(), bg)
            val l = labels(pos)
            val icon = if (left) l[0] else l[2]
            val label = if (left) l[1] else l[3]
            val cx = if (left) width - 44 * d else 44 * d
            val cy = (t.top + t.bottom) / 2f
            val s = (0.6f + 0.4f * progress) * (if (passed) iconPop else 1f)
            canvas.save()
            canvas.scale(s, s, cx, cy)
            canvas.drawText(icon, cx, cy + 4 * d, iconPaint)
            canvas.restore()
            labelPaint.alpha = (255 * progress).toInt()
            canvas.drawText(label, cx, cy + 22 * d, labelPaint)
        }
        super.dispatchDraw(canvas)
    }
}
