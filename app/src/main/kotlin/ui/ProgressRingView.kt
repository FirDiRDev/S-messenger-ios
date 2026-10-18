// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Круглый индикатор загрузки: полупрозрачный круг, крутящаяся дуга прогресса
 * и значок в центре (крестик - отменить, стрелка - скачать, ▶ - открыть).
 * Прогресс меняется плавно; пока размер неизвестен - дуга просто вращается.
 */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    enum class Icon { None, Cancel, Download, Play, Pause, Done, Retry }

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.6f * density
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2.4f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()
    private val path = android.graphics.Path()

    var icon: Icon = Icon.Cancel
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Цвет подложки (по умолчанию - тёмная полупрозрачная). */
    var backdropColor: Int
        get() = bgPaint.color
        set(value) {
            bgPaint.color = value
            invalidate()
        }

    var ringColor: Int
        get() = arcPaint.color
        set(value) {
            arcPaint.color = value
            iconPaint.color = value
            fillPaint.color = value
            invalidate()
        }

    /** Показывать ли дугу (выключено - только кнопка). */
    var showRing = true
        set(value) {
            field = value
            updateSpin()
            invalidate()
        }

    private var shown = 0f // плавный прогресс для отрисовки
    private var target = 0f
    private var indeterminate = true
    private var rotationAngle = 0f

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            rotationAngle = it.animatedValue as Float
            invalidate()
        }
    }
    private var smooth: ValueAnimator? = null

    /** fraction 0..1, или отрицательное - «неизвестно» (крутится без прогресса). */
    fun setProgress(fraction: Float, animate: Boolean = true) {
        if (fraction < 0f) {
            indeterminate = true
            updateSpin()
            invalidate()
            return
        }
        indeterminate = false
        val f = fraction.coerceIn(0f, 1f)
        if (f == target) return
        target = f
        smooth?.cancel()
        if (!animate || !isAttachedToWindow) {
            shown = f
            invalidate()
        } else {
            smooth = ValueAnimator.ofFloat(shown, f).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    shown = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        updateSpin()
    }

    private fun updateSpin() {
        val need = showRing && visibility == VISIBLE && isAttachedToWindow
        if (need && !spinner.isStarted) spinner.start()
        if (!need && spinner.isStarted) spinner.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpin()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        smooth?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateSpin()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        if (r <= 0f) return
        canvas.drawCircle(cx, cy, r, bgPaint)

        if (showRing) {
            val inset = arcPaint.strokeWidth * 1.6f
            rect.set(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset)
            if (indeterminate) {
                canvas.drawArc(rect, rotationAngle - 90f, 100f, false, arcPaint)
            } else {
                // Дуга растёт с прогрессом и при этом медленно вращается.
                val sweep = max(8f, shown * 360f)
                canvas.drawArc(rect, rotationAngle - 90f, sweep, false, arcPaint)
            }
        }

        val s = r * 0.34f
        when (icon) {
            Icon.None -> Unit
            Icon.Cancel -> {
                canvas.drawLine(cx - s, cy - s, cx + s, cy + s, iconPaint)
                canvas.drawLine(cx + s, cy - s, cx - s, cy + s, iconPaint)
            }
            Icon.Download -> {
                canvas.drawLine(cx, cy - s * 1.2f, cx, cy + s * 1.1f, iconPaint)
                canvas.drawLine(cx - s * 0.9f, cy + s * 0.2f, cx, cy + s * 1.1f, iconPaint)
                canvas.drawLine(cx + s * 0.9f, cy + s * 0.2f, cx, cy + s * 1.1f, iconPaint)
            }
            Icon.Play -> {
                path.reset()
                path.moveTo(cx - s * 0.7f, cy - s * 1.1f)
                path.lineTo(cx + s * 1.2f, cy)
                path.lineTo(cx - s * 0.7f, cy + s * 1.1f)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            Icon.Pause -> {
                val w = s * 0.45f
                canvas.drawRect(cx - s * 0.8f, cy - s, cx - s * 0.8f + w, cy + s, fillPaint)
                canvas.drawRect(cx + s * 0.8f - w, cy - s, cx + s * 0.8f, cy + s, fillPaint)
            }
            Icon.Done -> {
                canvas.drawLine(cx - s, cy, cx - s * 0.2f, cy + s * 0.8f, iconPaint)
                canvas.drawLine(cx - s * 0.2f, cy + s * 0.8f, cx + s * 1.1f, cy - s * 0.7f, iconPaint)
            }
            Icon.Retry -> {
                rect.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawArc(rect, -60f, 300f, false, iconPaint)
                canvas.drawLine(cx + s * 0.5f, cy - s * 0.87f, cx + s * 0.5f + s * 0.6f, cy - s * 0.87f, iconPaint)
                canvas.drawLine(cx + s * 0.5f, cy - s * 0.87f, cx + s * 0.5f, cy - s * 0.87f - s * 0.6f, iconPaint)
            }
        }
    }
}
