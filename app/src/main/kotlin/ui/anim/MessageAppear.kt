// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.anim

import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.stoyko.smessenger.chat.SelectionMenu
import com.stoyko.smessenger.ui.LiteMode

/**
 * Появление новых сообщений - плавно и пружинисто.
 *
 *  - Своё сообщение «вылетает» из поля ввода: стартует у нижнего края списка, чуть сжатым,
 *    и поднимается на место на пружине; угол пузыря (справа снизу) ведёт за собой остальное.
 *  - Входящее «надувается» из нижнего левого угла: масштаб 0.6 → 1 с лёгким перелётом,
 *    одновременно проявляясь.
 *  - Если сообщений приходит пачка, каждое следующее стартует чуть позже (лесенка).
 *
 * Анимируются только свойства View (translation/scale/alpha) - это делается на RenderThread,
 * без перерисовки содержимого, поэтому плавно даже на 120 Гц. В облегчённом режиме и при
 * «меньше движения» - простое короткое проявление.
 */
object MessageAppear {
    private var lastAt = 0L
    private var chain = 0

    private val running = java.util.WeakHashMap<View, Boolean>()

    /** Переиспользованная строка показывает другое (старое) сообщение - снять недоигранное появление. */
    fun reset(view: View) {
        if (running.remove(view) != null) {
            view.animate().cancel()
            view.animate().setStartDelay(0)
            view.alpha = 1f
        }
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.rotationX = 0f
    }

    fun play(view: View, outgoing: Boolean, parent: ViewGroup?) {
        view.animate().cancel()
        running[view] = true
        val ctx = view.context
        val d = ctx.resources.displayMetrics.density
        if (!PeekPop.motionOk(ctx)) {
            view.alpha = 0f
            view.translationY = 12 * d
            view.animate().alpha(1f).translationY(0f).setDuration(160).setStartDelay(0).setInterpolator(DecelerateInterpolator()).start()
            return
        }
        if (LiteMode.on) {
            // 4.12.1: на слабом телефоне - тоже живое появление, но лёгкое: короткий подъём с
            // небольшим «надуванием» на аппаратном слое (без пружины и лесенки).
            view.alpha = 0f
            view.translationY = 16 * d
            view.scaleX = 0.94f
            view.scaleY = 0.94f
            view.pivotX = if (outgoing) (view.width.takeIf { it > 0 } ?: view.measuredWidth).toFloat() else 0f
            view.pivotY = (view.height.takeIf { it > 0 } ?: view.measuredHeight).toFloat()
            view.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(220).setStartDelay(0)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                .withLayer()
                .start()
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        chain = if (now - lastAt < 120) (chain + 1).coerceAtMost(5) else 0
        lastAt = now
        val delay = chain * 45L
        val ready = Runnable {
            val w = view.width.takeIf { it > 0 } ?: view.measuredWidth
            val h = view.height.takeIf { it > 0 } ?: view.measuredHeight
            view.pivotX = if (outgoing) w.toFloat() else 0f
            view.pivotY = h.toFloat()
        }
        if (view.width > 0) ready.run() else view.post(ready)
        if (outgoing) {
            // Расстояние до низа списка (там поле ввода); новое сообщение обычно и так внизу -
            // тогда берём высоту самого пузыря, чтобы подъём был заметен.
            val rise = parent?.let { p -> (p.height - view.top).toFloat().coerceIn(56 * d, 220 * d) } ?: (72 * d)
            view.alpha = 0f
            view.translationY = rise
            view.translationX = 0f
            view.scaleX = 0.82f
            view.scaleY = 0.82f
            view.animate().alpha(1f).setDuration(140).setStartDelay(delay).setInterpolator(DecelerateInterpolator()).start()
            view.animate().translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay)
                .setDuration(460)
                .setInterpolator(SelectionMenu.Spring(0.62f, 1.9f))
                .withLayer()
                .start()
        } else {
            view.alpha = 0f
            view.translationY = 10 * d
            view.translationX = -14 * d
            view.scaleX = 0.6f
            view.scaleY = 0.6f
            view.animate().alpha(1f).translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(delay)
                .setDuration(420)
                .setInterpolator(SelectionMenu.Spring(0.55f, 2.1f))
                .withLayer()
                .start()
        }
    }
}
