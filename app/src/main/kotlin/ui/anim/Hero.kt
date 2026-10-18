// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.anim

import android.animation.Animator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.transition.ChangeBounds
import android.transition.ChangeClipBounds
import android.transition.ChangeTransform
import android.transition.Transition
import android.transition.TransitionSet
import android.transition.TransitionValues
import android.util.TypedValue
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import kotlin.math.hypot

/**
 * Переливание экрана (Hero / Shared Element): при открытии чата аватарка и имя не исчезают,
 * а «перелетают» из строки списка в шапку чата, плавно меняя размер (круг остаётся кругом),
 * а фон нового экрана разворачивается кругом из точки нажатия.
 */
object Hero {
    const val AVATAR = "hero_avatar"
    const val NAME = "hero_name"
    const val ARG_HERO = "hero"
    const val ARG_X = "hero_x"
    const val ARG_Y = "hero_y"

    private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

    /** Переход общих элементов: границы, масштаб, обрезка и размер шрифта имени. */
    fun transition(): Transition = TransitionSet().apply {
        ordering = TransitionSet.ORDERING_TOGETHER
        addTransition(ChangeBounds())
        addTransition(ChangeTransform())
        addTransition(ChangeClipBounds())
        addTransition(TextSize())
        duration = 380
        interpolator = EASE
    }

    /** Аргументы перехода: точка нажатия (координаты экрана). */
    fun args(target: Bundle, x: Float, y: Float) {
        target.putBoolean(ARG_HERO, true)
        target.putFloat(ARG_X, x)
        target.putFloat(ARG_Y, y)
    }

    /** Новый экран разворачивается кругом из точки нажатия. */
    fun reveal(root: View, args: Bundle?) {
        if (args?.getBoolean(ARG_HERO) != true) return
        val sx = args.getFloat(ARG_X, -1f)
        val sy = args.getFloat(ARG_Y, -1f)
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                if (!root.isAttachedToWindow || root.width == 0) return true
                val loc = IntArray(2)
                root.getLocationOnScreen(loc)
                val cx = if (sx >= 0) (sx - loc[0]).toInt() else root.width / 2
                val cy = if (sy >= 0) (sy - loc[1]).toInt() else root.height / 3
                val r = hypot(maxOf(cx, root.width - cx).toFloat(), maxOf(cy, root.height - cy).toFloat())
                runCatching {
                    ViewAnimationUtils.createCircularReveal(root, cx, cy, 24f * root.resources.displayMetrics.density, r).apply {
                        duration = 420
                        interpolator = EASE
                        start()
                    }
                }
                // Лёгкое «раскрытие» масштабом вместе с кругом (Ripple + Scale).
                root.scaleX = 0.96f
                root.scaleY = 0.96f
                root.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(EASE).start()
                return true
            }
        })
    }

    /** Размер шрифта имени меняется плавно (в списке - мельче, в шапке - крупнее). */
    private class TextSize : Transition() {
        private val key = "stoyko:hero:textSize"

        override fun captureStartValues(v: TransitionValues) = capture(v)
        override fun captureEndValues(v: TransitionValues) = capture(v)

        private fun capture(v: TransitionValues) {
            (v.view as? TextView)?.let { v.values[key] = it.textSize }
        }

        override fun createAnimator(root: ViewGroup, start: TransitionValues?, end: TransitionValues?): Animator? {
            val from = start?.values?.get(key) as? Float ?: return null
            val to = end?.values?.get(key) as? Float ?: return null
            val tv = end.view as? TextView ?: return null
            if (from == to) return null
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, from)
            return ValueAnimator.ofFloat(from, to).apply {
                addUpdateListener { tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.animatedValue as Float) }
            }
        }
    }

    // ------------------------------------------------------------------ снимок прежнего экрана

    /** Снимок списка чатов (для свайпа назад: он виден под уходящим чатом). */
    @Volatile
    var backdrop: Bitmap? = null
        private set

    /** Сделать снимок экрана [v] в половинном размере (меньше памяти). */
    fun snapshot(v: View) {
        if (v.width <= 0 || v.height <= 0) return
        runCatching {
            val bmp = Bitmap.createBitmap(v.width / 2, v.height / 2, Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(0.5f, 0.5f)
            v.draw(c)
            // Прежний снимок не освобождаем вручную - его может ещё рисовать свайп назад.
            backdrop = bmp
        }
    }
}
