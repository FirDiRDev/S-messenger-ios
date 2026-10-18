// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Плашка загрузки снизу экрана: заголовок, проценты и полоска, которая плавно «ползёт»
 * (по ней бежит блик). В конце - зелёная галочка «Готово» и плашка уезжает вниз.
 * Используется для историй (и других долгих отправок).
 */
class UploadBar private constructor(private val activity: Activity, title: String) {
    private val d = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val host: ViewGroup = activity.findViewById(android.R.id.content)!!
    private val card = LinearLayout(activity)
    private val titleView = TextView(activity)
    private val percent = TextView(activity)
    private val icon = TextView(activity)
    private val bar = ProgressLine(activity)
    private var shown = 0f
    private var anim: ValueAnimator? = null

    init {
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(16), dp(12), dp(16), dp(14))
        card.background = GradientDrawable().apply {
            cornerRadius = 20 * d
            setColor(0xF21E1B4B.toInt())
        }
        card.elevation = 12 * d
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        icon.text = "⬆"
        icon.textSize = 16f
        icon.gravity = Gravity.CENTER
        icon.setTextColor(Color.WHITE)
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF5B4BDB.toInt())
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(30), dp(30)))
        titleView.text = title
        titleView.textSize = 14f
        titleView.setTextColor(Color.WHITE)
        titleView.paint.isFakeBoldText = true
        titleView.setPadding(dp(10), 0, dp(8), 0)
        titleView.maxLines = 2
        row.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        percent.textSize = 13f
        percent.setTextColor(0xCCFFFFFF.toInt())
        percent.text = "0%"
        row.addView(percent)
        card.addView(row)
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(10) })
    }

    private fun attach() {
        val bottomInset = androidx.core.view.ViewCompat.getRootWindowInsets(host)
            ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
        host.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(dp(12), 0, dp(12), dp(16) + bottomInset)
            },
        )
        card.translationY = dp(140).toFloat()
        card.alpha = 0f
        card.animate().translationY(0f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(1.2f)).start()
    }

    /** Прогресс 0..1 - полоска доезжает плавно. */
    fun set(p: Float) = activity.runOnUiThread {
        val target = p.coerceIn(0f, 1f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                shown = it.animatedValue as Float
                bar.progress = shown
                percent.text = "${(shown * 100).toInt()}%"
            }
            start()
        }
    }

    fun title(text: String) = activity.runOnUiThread { titleView.text = text }

    /** Готово: галочка, зелёная полоска и плашка уезжает. */
    fun done(message: String) = activity.runOnUiThread {
        anim?.cancel()
        bar.progress = 1f
        bar.success = true
        percent.text = "100%"
        titleView.text = message
        icon.text = "✓"
        (icon.background as? GradientDrawable)?.setColor(0xFF22C55E.toInt())
        icon.scaleX = 0.4f
        icon.scaleY = 0.4f
        icon.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
        card.postDelayed({ dismiss() }, 2600)
    }

    fun fail(message: String) = activity.runOnUiThread {
        anim?.cancel()
        titleView.text = message
        icon.text = "!"
        (icon.background as? GradientDrawable)?.setColor(0xFFE5484D.toInt())
        card.postDelayed({ dismiss() }, 3200)
    }

    private fun dismiss() {
        card.animate().translationY(dp(160).toFloat()).alpha(0f).setDuration(300).withEndAction {
            (card.parent as? ViewGroup)?.removeView(card)
        }.start()
    }

    /** Полоска с плавным градиентом и бегущим бликом. */
    private class ProgressLine(ctx: Context) : View(ctx) {
        var progress = 0f
            set(v) {
                field = v
                invalidate()
            }
        var success = false
            set(v) {
                field = v
                invalidate()
            }
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val m = Matrix()
        private var shineShader: LinearGradient? = null

        override fun onDraw(c: Canvas) {
            val h = height.toFloat()
            val w = width.toFloat()
            r.set(0f, 0f, w, h)
            c.drawRoundRect(r, h / 2, h / 2, track)
            val pw = (w * progress).coerceAtLeast(h)
            r.set(0f, 0f, pw, h)
            fill.shader = LinearGradient(
                0f, 0f, w, 0f,
                if (success) intArrayOf(0xFF4ADE80.toInt(), 0xFF16A34A.toInt()) else intArrayOf(0xFF7B6CF6.toInt(), 0xFFEC4899.toInt()),
                null, Shader.TileMode.CLAMP,
            )
            c.drawRoundRect(r, h / 2, h / 2, fill)
            if (!success && progress < 1f && Kinetics.enabled) {
                val sh = shineShader ?: LinearGradient(0f, 0f, w * 0.25f, 0f, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                    .also { shineShader = it }
                val t = (android.os.SystemClock.uptimeMillis() % 1200L) / 1200f
                m.setTranslate(-w * 0.25f + t * (pw + w * 0.25f), 0f)
                sh.setLocalMatrix(m)
                shine.shader = sh
                val save = c.save()
                c.clipRect(0f, 0f, pw, h)
                c.drawRoundRect(r, h / 2, h / 2, shine)
                c.restoreToCount(save)
                postInvalidateOnAnimation()
            }
        }
    }

    companion object {
        /** Показать плашку (null - экран уже закрыт). */
        fun show(ctx: Context, title: String): UploadBar? {
            val a = Kinetics.activityOf(ctx) ?: return null
            if (a.isFinishing) return null
            return UploadBar(a, title).also { it.attach() }
        }
    }
}
