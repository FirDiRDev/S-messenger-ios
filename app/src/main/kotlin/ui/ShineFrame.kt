// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.widget.FrameLayout

/** Рамка, по содержимому которой раз в 3 секунды пробегает мягкий блик (для главных кнопок). */
class ShineFrame(ctx: Context) : FrameLayout(ctx) {
    private val start = SystemClock.uptimeMillis()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val clip = Path()
    private val rect = RectF()
    private var shader: LinearGradient? = null

    init {
        setWillNotDraw(false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        val r = 18 * resources.displayMetrics.density
        rect.set(0f, 0f, w.toFloat(), h - 5 * resources.displayMetrics.density)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val sh = shader ?: return
        if (!Kinetics.enabled || EnergySaver.on) return
        val t = (SystemClock.uptimeMillis() - start) / 1000f
        val p = (t % 3f) / 3f
        if (p < 0.45f) {
            m.setTranslate(-width * 0.4f + p / 0.45f * width * 1.5f, 0f)
            sh.setLocalMatrix(m)
            paint.shader = sh
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(rect, paint)
            canvas.restore()
        }
        if (isShown) postInvalidateDelayed(if (p < 0.45f) 16 else 400)
    }
}
