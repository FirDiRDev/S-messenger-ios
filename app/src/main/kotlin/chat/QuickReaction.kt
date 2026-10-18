// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import com.stoyko.smessenger.ui.Kinetics
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Быстрая реакция двойным касанием: по умолчанию ❤️, можно сменить
 * (последняя выбранная реакция становится быстрой). Анимация: эмодзи «выпрыгивает» в точке
 * касания, взлетает и тает, вокруг разлетаются искры.
 */
object QuickReaction {
    private const val PREFS = "quick_reaction"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("e", null)?.ifBlank { null } ?: "❤️"

    fun set(ctx: Context, emoji: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("e", emoji).apply()

    /** Анимация в точке (x, y) внутри [inside]. */
    fun play(inside: View, x: Float, y: Float, emoji: String) {
        val root = inside.rootView as? ViewGroup ?: return
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        inside.getLocationInWindow(loc)
        root.getLocationInWindow(rootLoc)
        val cx = loc[0] - rootLoc[0] + x
        val cy = loc[1] - rootLoc[1] + y
        val d = inside.resources.displayMetrics.density
        val tv = TextView(inside.context).apply {
            text = emoji
            textSize = 54f
            measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            layout(0, 0, measuredWidth, measuredHeight)
            translationX = cx - measuredWidth / 2f
            translationY = cy - measuredHeight / 2f
            scaleX = 0f
            scaleY = 0f
        }
        root.overlay.add(tv)
        tv.animate().scaleX(1.25f).scaleY(1.25f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).withEndAction {
            tv.animate().translationYBy(-70 * d).alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(520)
                .withEndAction { root.overlay.remove(tv) }.start()
        }.start()
        if (!Kinetics.enabled) return
        val colors = intArrayOf(0xFFFF4D6D.toInt(), 0xFFFF8FA3.toInt(), 0xFFFFC2D1.toInt(), 0xFFFFB020.toInt())
        val parts = (0 until 16).map { i ->
            val a = i / 16.0 * Math.PI * 2 + Random.nextDouble(-0.2, 0.2)
            val sp = (70 + Random.nextFloat() * 80) * d
            Kinetics.Particle(cx, cy, (cos(a) * sp).toFloat(), (sin(a) * sp).toFloat(), (2f + Random.nextFloat() * 2.5f) * d, colors[i % colors.size], 560, delayMs = 120)
        }
        Kinetics.ParticleLayer.attach(root).add(parts)
    }
}
