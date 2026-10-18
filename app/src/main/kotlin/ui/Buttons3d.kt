// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.google.android.material.button.MaterialButton
import com.stoyko.smessenger.R

/**
 * «3D-кнопки» (Настройки → Персонализация): кнопка как настоящая клавиша - у неё есть
 * «бортик» снизу (тот же цвет, темнее), блик сверху, а при нажатии верх «проваливается»
 * вниз вместе с текстом и пружинисто возвращается. Выключено - обычные плоские кнопки.
 *
 * Применяется автоматически ко всем экранам (заливные кнопки и «пилюли» с градиентом),
 * списки и поля ввода не трогаются. Для окон, собранных в коде, - [applyTree] вручную.
 */
object Buttons3d {
    private const val PREFS = "ui"
    private const val KEY = "buttons3d"

    @Volatile
    private var cached: Boolean? = null

    fun isOn(ctx: Context): Boolean = cached ?: ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY, false).also { cached = it }

    fun set(ctx: Context, on: Boolean) {
        cached = on
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }

    /** Подключить ко всем фрагментам окна (вызывается один раз в MainActivity). */
    fun install(activity: FragmentActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: android.os.Bundle?) {
                    // Один вид кнопок на всех экранах (после разметки - нужны размеры).
                    v.post { Btn.normalizeTree(v) }
                    v.postDelayed({ Btn.normalizeTree(v) }, 700)
                    if (!isOn(v.context)) return
                    // После разметки: нужны реальные размеры, а часть кнопок появляется кодом в onViewCreated.
                    v.post { applyTree(v) }
                    v.postDelayed({ applyTree(v) }, 700)
                }
            },
            true,
        )
    }

    /** Пройтись по дереву и сделать объёмными подходящие кнопки. */
    fun applyTree(root: View?) {
        if (root == null || !isOn(root.context)) return
        walk(root)
    }

    private fun walk(v: View) {
        if (v.visibility == View.GONE) return
        // Списки (чаты, сообщения) не трогаем - там «кнопок» нет, а пузыри сообщений похожи на них.
        if (v is AbsListView || v.javaClass.name.endsWith("RecyclerView")) return
        if (looksLikeButton(v)) apply(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    private fun looksLikeButton(v: View): Boolean {
        if (v.getTag(R.id.tag_buttons3d) != null) return false
        if (v is EditText) return false
        if (v is MaterialButton) {
            if (v.strokeWidth > 0) return false
            val c = v.backgroundTintList?.defaultColor ?: return false
            return (c ushr 24) > 200
        }
        if (v !is TextView || !v.hasOnClickListeners()) return false
        val bg = v.background as? GradientDrawable ?: return false
        val d = v.resources.displayMetrics.density
        // Только «пилюли» и плитки-кнопки, не крупные карточки.
        return v.height in (24 * d).toInt()..(72 * d).toInt() && bg.alpha > 200
    }

    /** Сделать одну кнопку объёмной (если включено). */
    fun apply(v: View, force: Boolean = false) {
        if ((!force && !isOn(v.context)) || v.getTag(R.id.tag_buttons3d) != null) return
        val d = v.resources.displayMetrics.density
        val depth = 4.5f * d
        val face: Drawable
        val side: Drawable
        var insetTop = 0
        var insetBottom = 0
        val radius: Float
        if (v is MaterialButton) {
            val color = v.backgroundTintList?.defaultColor ?: return
            radius = v.cornerRadius.toFloat()
            insetTop = v.insetTop
            insetBottom = v.insetBottom
            face = GradientDrawable().apply {
                cornerRadius = radius
                setColor(color)
            }
            side = GradientDrawable().apply {
                cornerRadius = radius
                setColor(color)
            }
            v.backgroundTintList = null
        } else {
            val bg = v.background ?: return
            // Скругление берём у самой заливки (в т. ч. внутри «волны» касания), иначе блик
            // и бортик выступают за углы кнопки полупрозрачными «скосами».
            val inner = (bg as? android.graphics.drawable.RippleDrawable)
                ?.takeIf { it.numberOfLayers > 0 }?.getDrawable(0) ?: bg
            radius = (inner as? GradientDrawable)
                ?.takeIf { android.os.Build.VERSION.SDK_INT >= 24 }
                ?.cornerRadius?.takeIf { it > 0f } ?: (16 * d)
            face = bg.constantState?.newDrawable(v.resources)?.mutate() ?: return
            side = bg.constantState?.newDrawable(v.resources)?.mutate() ?: return
        }
        side.colorFilter = PorterDuffColorFilter(0x66000000, PorterDuff.Mode.SRC_ATOP)
        v.setTag(R.id.tag_buttons3d, true)
        v.background = KeyDrawable(v, face, side, depth, radius, insetTop, insetBottom)
        // Текст - по центру «крышки», а не всей кнопки с бортиком.
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, v.paddingBottom + depth.toInt())
        if (v.elevation > 0f) v.elevation = 0f
        v.stateListAnimator = null
        v.isHapticFeedbackEnabled = true
    }

    /** Клавиша: бортик (тот же цвет, темнее) + «крышка» с бликом, нажатие опускает крышку. */
    private class KeyDrawable(
        private val view: View,
        private val face: Drawable,
        private val side: Drawable,
        private val depth: Float,
        private val radius: Float,
        private val insetTop: Int,
        private val insetBottom: Int,
    ) : Drawable() {
        private var press = 0f
        private var pressed = false
        private var anim: ValueAnimator? = null
        private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22000000 }
        private val r = RectF()
        private val clip = android.graphics.Path()

        override fun isStateful() = true

        override fun onStateChange(state: IntArray): Boolean {
            val now = state.contains(android.R.attr.state_pressed) && state.contains(android.R.attr.state_enabled)
            if (now == pressed) return false
            pressed = now
            anim?.cancel()
            anim = ValueAnimator.ofFloat(press, if (now) 1f else 0f).apply {
                duration = if (now) 70 else 320
                interpolator = if (now) DecelerateInterpolator() else OvershootInterpolator(2.4f)
                addUpdateListener {
                    press = it.animatedValue as Float
                    // Текст опускается вместе с крышкой (фон при этом стоит на месте).
                    view.scrollY = -(press.coerceAtLeast(0f) * depth * 0.8f).toInt()
                    invalidateSelf()
                }
                start()
            }
            if (now) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            return true
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val top = b.top + insetTop
            val bottom = b.bottom - insetBottom
            if (bottom - top <= depth * 2) {
                face.setBounds(b.left, top, b.right, bottom)
                face.draw(canvas)
                return
            }
            val off = (press * depth * 0.8f).toInt()
            // Мягкая тень под клавишей.
            r.set(b.left + 2f, top + depth * 1.4f, b.right - 2f, bottom.toFloat())
            canvas.drawRoundRect(r, radius.coerceAtMost(r.height() / 2f), radius.coerceAtMost(r.height() / 2f), shadow)
            // Бортик.
            side.setBounds(b.left, (top + depth).toInt(), b.right, bottom)
            side.draw(canvas)
            // Крышка.
            val faceBottom = (bottom - depth).toInt() + off
            face.setBounds(b.left, top + off, b.right, faceBottom)
            face.draw(canvas)
            // Блик сверху крышки - строго внутри её скругления.
            val h = (faceBottom - top - off).toFloat()
            val rr = radius.coerceAtMost(h / 2f)
            clip.reset()
            r.set(b.left.toFloat(), (top + off).toFloat(), b.right.toFloat(), faceBottom.toFloat())
            clip.addRoundRect(r, rr, rr, android.graphics.Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            gloss.shader = LinearGradient(0f, (top + off).toFloat(), 0f, top + off + h * 0.55f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(b.left.toFloat(), (top + off).toFloat(), b.right.toFloat(), top + off + h * 0.55f, gloss)
            canvas.restoreToCount(save)
        }

        override fun setAlpha(alpha: Int) {
            face.alpha = alpha
            side.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            face.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
