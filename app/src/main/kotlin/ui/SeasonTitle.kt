// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar

/**
 * Надпись «S-messenger» в главной шапке в сезонном стиле:
 *  - зима: буквы-«карамельки» в бело-красную полоску, которая медленно бежит, и над
 *    буквами мигают разноцветные лампочки гирлянды;
 *  - осень: золотисто-оранжевые буквы с бегущим бликом и парой кружащихся листиков.
 */
object SeasonTitle {
    fun apply(toolbar: Toolbar) {
        val kind = Seasonal.current(toolbar.context)
        if (kind == Seasonal.Kind.NONE) return
        toolbar.post {
            val title = toolbar.title?.toString() ?: return@post
            val tv = (0 until toolbar.childCount).map { toolbar.getChildAt(it) }
                .filterIsInstance<TextView>().firstOrNull { it.text?.toString() == title } ?: return@post
            style(tv, kind)
        }
    }

    private fun style(tv: TextView, kind: Seasonal.Kind) {
        if (tv.getTag(com.stoyko.smessenger.R.id.tag_season) == "title") return
        tv.setTag(com.stoyko.smessenger.R.id.tag_season, "title")
        val d = tv.resources.displayMetrics.density
        tv.paint.isFakeBoldText = true
        tv.setShadowLayer(3 * d, 0f, 1.5f * d, 0x66000000)
        val h = tv.textSize
        val shader = if (kind == Seasonal.Kind.WINTER) {
            // Карамельная трость: косые красно-белые полосы.
            LinearGradient(
                0f, 0f, h * 0.55f, h * 0.55f,
                intArrayOf(0xFFEF4444.toInt(), 0xFFEF4444.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
                floatArrayOf(0f, 0.5f, 0.5f, 1f), Shader.TileMode.REPEAT,
            )
        } else if (kind == Seasonal.Kind.SPRING) {
            // Весна: нежно-розовые буквы сакуры с белым бликом.
            LinearGradient(
                0f, 0f, h * 4f, 0f,
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFFBCFE8.toInt(), 0xFFF472B6.toInt(), 0xFFFFF1F7.toInt(), 0xFFFFFFFF.toInt()),
                null, Shader.TileMode.REPEAT,
            )
        } else if (kind == Seasonal.Kind.SUMMER) {
            // Лето: солнечно-жёлтые с морским бирюзовым переливом.
            LinearGradient(
                0f, 0f, h * 4f, 0f,
                intArrayOf(0xFFFEF08A.toInt(), 0xFFFBBF24.toInt(), 0xFFFFFFFF.toInt(), 0xFF5EEAD4.toInt(), 0xFFFEF08A.toInt()),
                null, Shader.TileMode.REPEAT,
            )
        } else {
            LinearGradient(
                0f, 0f, h * 4f, 0f,
                intArrayOf(0xFFFDE047.toInt(), 0xFFFB923C.toInt(), 0xFFFFF7ED.toInt(), 0xFFEA580C.toInt(), 0xFFFDE047.toInt()),
                null, Shader.TileMode.REPEAT,
            )
        }
        tv.paint.shader = shader
        val m = Matrix()
        var lastShine = 0L
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (kind == Seasonal.Kind.WINTER) 2600 else 3400
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (!Kinetics.enabled) return@addUpdateListener
                // Экономия: блик по буквам обновляется ~6 раз в секунду, а не каждый кадр.
                val nowMs = SystemClock.uptimeMillis()
                if (EnergySaver.on && nowMs - lastShine < 160) return@addUpdateListener
                lastShine = nowMs
                val f = it.animatedValue as Float
                m.setTranslate(f * (if (kind == Seasonal.Kind.WINTER) h * 1.1f else h * 4f), 0f)
                shader.setLocalMatrix(m)
                tv.invalidate()
            }
        }
        tv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = anim.start()
            override fun onViewDetachedFromWindow(v: View) = anim.cancel()
        })
        if (tv.isAttachedToWindow) anim.start()
        if (android.os.Build.VERSION.SDK_INT >= 23) tv.foreground = Lights(kind, d)
    }

    /** Лампочки над буквами (зима) или кружащиеся листики (осень). */
    private class Lights(private val kind: Seasonal.Kind, private val d: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val start = SystemClock.uptimeMillis()
        private val bulbs = intArrayOf(0xFFEF4444.toInt(), 0xFFFDE047.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFEC4899.toInt())
        private val tick = Runnable { invalidateSelf() }

        override fun draw(c: Canvas) {
            val b = bounds
            if (b.width() <= 0) return
            val frozen = !Kinetics.enabled
            val lite = EnergySaver.on
            val t = if (frozen) 1f else (SystemClock.uptimeMillis() - start) / 1000f
            if (kind == Seasonal.Kind.WINTER) {
                val n = (b.width() / (14 * d)).toInt().coerceAtLeast(4)
                p.strokeWidth = 1f * d
                for (i in 0 until n) {
                    val x = b.left + b.width() * (i + 0.5f) / n
                    val y = b.top + 3 * d + if (i % 2 == 0) 0f else 2 * d
                    val on = frozen || ((t * 3 + i * 0.8f) % 2f) < 1.3f
                    val col = bulbs[(i + (t * 1.5f).toInt()) % bulbs.size]
                    if (on) {
                        p.color = (col and 0x00FFFFFF) or 0x30000000
                        c.drawCircle(x, y, 5.5f * d, p)
                        p.color = (col and 0x00FFFFFF) or 0x55000000
                        c.drawCircle(x, y, 3.8f * d, p)
                    }
                    p.color = if (on) col else (col and 0x00FFFFFF) or 0x66000000
                    c.drawCircle(x, y, 2.3f * d, p)
                }
            } else if (kind == Seasonal.Kind.SPRING || kind == Seasonal.Kind.SUMMER) {
                for (k in 0 until 2) {
                    val x = b.left + b.width() * (if (k == 0) 0.06f else 0.96f)
                    val y = b.top + b.height() * (0.25f + 0.1f * kotlin.math.sin(t * 1.5f + k))
                    if (kind == Seasonal.Kind.SPRING) {
                        SeasonArt.petal(c, p, x, y, 5f * d, kotlin.math.sin(t * 1.6f + k * 2) * 60f, kotlin.math.cos(t * 2f + k), SeasonArt.PETAL_COLORS[k + 1], 255)
                    } else {
                        SeasonArt.butterfly(c, p, x, y, 5f * d, kotlin.math.sin(t + k) * 20f, 0.5f + 0.5f * kotlin.math.sin(t * 12f + k * 3), SeasonArt.BUTTERFLY_COLORS[k], 255)
                    }
                }
            } else {
                for (k in 0 until 2) {
                    val x = b.left + b.width() * (if (k == 0) 0.06f else 0.96f)
                    val y = b.top + b.height() * (0.25f + 0.1f * kotlin.math.sin(t * 1.5f + k))
                    SeasonArt.leaf(c, p, x, y, 5.5f * d, kotlin.math.sin(t * 1.8f + k * 2) * 35f, kotlin.math.cos(t * 2f + k), k, SeasonArt.LEAF_COLORS[k + 1], 255)
                }
            }
            if (!frozen) scheduleSelf(tick, SystemClock.uptimeMillis() + if (lite) 140 else 60)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
