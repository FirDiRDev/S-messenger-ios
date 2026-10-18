// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.anim

import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.widget.AbsListView
import android.widget.ListView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sign

/**
 * «Желейный» скролл сообщений: чем быстрее прокрутка, тем сильнее пузыри вытягиваются по
 * вертикали и сужаются по горизонтали (как желе); при резкой остановке или ударе о край
 * списка - затухающие колебания обратно к обычной форме.
 *
 * Скорость считается по сдвигу строк между кадрами; деформация ограничена (до 5% по высоте),
 * чтобы текст оставался читаемым. В режиме для слабых устройств, при «меньше движения» и
 * выключенных анимациях эффект не работает.
 */
class JellyScroll private constructor(private val list: ListView) : AbsListView.OnScrollListener {
    private var lastFirst = -1
    private var lastTop = 0
    private var lastTime = 0L
    private var velocity = 0f // пикселей в мс, знак - направление
    private var stretch = 0f // текущая деформация (-1..1 от максимума)
    private var wobbleFrom = 0f
    private var wobbleStart = 0L
    private var wobbling = false
    private var scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE
    private val maxStretch = 0.05f
    private var wrapped: AbsListView.OnScrollListener? = null

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!wobbling || !list.isAttachedToWindow) return
            val t = (SystemClock.uptimeMillis() - wobbleStart) / 1000f
            // Затухающие колебания: s(t) = s0 · e^(−ζωt) · cos(ω_d·t).
            val w = 2f * Math.PI.toFloat() * 3.2f
            val zeta = 0.32f
            val s = wobbleFrom * exp(-zeta * w * t) * cos(w * kotlin.math.sqrt(1f - zeta * zeta) * t)
            if (t > 1.2f || abs(s) < 0.002f) {
                wobbling = false
                apply(0f)
                return
            }
            apply(s)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun enabled() = PeekPop.motionOk(list.context) && !com.stoyko.smessenger.ui.LiteMode.on

    override fun onScrollStateChanged(view: AbsListView, state: Int) {
        wrapped?.onScrollStateChanged(view, state)
        scrollState = state
        if (state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) wobble(stretch)
    }

    override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
        wrapped?.onScroll(view, first, visible, total)
        if (!enabled() || list.childCount == 0) return
        val now = SystemClock.uptimeMillis()
        val top = list.getChildAt(0)?.top ?: 0
        if (lastFirst >= 0 && now > lastTime) {
            val dy: Int = when {
                first == lastFirst -> top - lastTop
                first > lastFirst -> -(list.getChildAt(0)?.height ?: 0) + top - lastTop
                else -> (list.getChildAt(0)?.height ?: 0) + top - lastTop
            }
            val dt = (now - lastTime).coerceIn(1L, 64L).toFloat()
            velocity = velocity * 0.6f + (dy / dt) * 0.4f
            if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                wobbling = false
                val target = (velocity / 6f).coerceIn(-1f, 1f) * maxStretch
                stretch += (target - stretch) * 0.35f
                apply(stretch)
                // Удар о край: прокрутка упёрлась - отскок колебаниями.
                val atTop = first == 0 && top >= list.paddingTop
                val atBottom = first + visible >= total && (list.getChildAt(list.childCount - 1)?.bottom ?: 0) <= list.height - list.paddingBottom
                if ((atTop && velocity > 0.5f) || (atBottom && velocity < -0.5f)) {
                    wobble(sign(velocity) * maxStretch * 0.9f)
                    velocity = 0f
                }
            }
        }
        lastFirst = first
        lastTop = top
        lastTime = now
    }

    private fun wobble(from: Float) {
        if (!enabled() || abs(from) < 0.004f) {
            apply(0f)
            return
        }
        wobbleFrom = from
        wobbleStart = SystemClock.uptimeMillis()
        stretch = 0f
        velocity = 0f
        if (!wobbling) {
            wobbling = true
            Choreographer.getInstance().postFrameCallback(frame)
        }
    }

    /**
     * Деформация видимых строк: вытягиваются по Y и чуть сжимаются по X от края, к которому
     * движется список. Без наклона (rotationX) - он выглядел «криво».
     */
    private fun apply(s: Float) {
        val down = s > 0
        for (i in 0 until list.childCount) {
            val c: View = list.getChildAt(i)
            if (s == 0f) {
                clear(c)
                continue
            }
            c.pivotX = c.width / 2f
            c.pivotY = if (down) 0f else c.height.toFloat()
            c.scaleY = 1f + abs(s)
            c.scaleX = 1f - abs(s) * 0.3f
        }
    }

    private fun clear(c: View) {
        c.scaleX = 1f
        c.scaleY = 1f
        c.rotationX = 0f
    }

    companion object {
        /** Подключить к списку; [existing] - прежний слушатель прокрутки (если был). */
        fun attach(list: ListView, existing: AbsListView.OnScrollListener? = null): JellyScroll {
            (list.getTag(com.stoyko.smessenger.R.id.tag_jelly) as? JellyScroll)?.let { return it }
            val j = JellyScroll(list)
            j.wrapped = existing
            list.setOnScrollListener(j)
            // Строка ушла в запас для переиспользования - снимаем деформацию, иначе она
            // появлялась потом «поплывшей» у другого сообщения.
            list.setRecyclerListener { v -> j.clear(v) }
            list.setTag(com.stoyko.smessenger.R.id.tag_jelly, j)
            return j
        }
    }
}
