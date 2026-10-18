// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView

/**
 * Ползунок быстрой прокрутки чата. Системный ползунок ListView из-за отступов под «стеклянными»
 * шапкой и панелью ввода срабатывал только с середины экрана и показывал большую голубую дату.
 * Этот - тонкая полоска справа на всю высоту видимой части чата: появляется при прокрутке,
 * за неё можно тянуть с любого места, пропадает сам. Без голубой даты.
 */
@SuppressLint("ViewConstructor")
class ChatScrollThumb(context: Context, private val list: AbsListView) : View(context) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x995B4BDB.toInt() }
    private val activePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5B4BDB.toInt() }
    private val rect = RectF()
    private var fraction = 0f
    private var dragging = false
    private val thumbH = 44 * d
    private val thumbW = 5 * d
    private val hide = Runnable { if (!dragging) animate().alpha(0f).setDuration(250).start() }

    /** Сколько места сверху/снизу занимают шапка и панель ввода. */
    var insetTop: () -> Int = { list.paddingTop }
    var insetBottom: () -> Int = { list.paddingBottom }

    /** Вызывается при прокрутке (сообщает, когда тянут ползунок - чтобы показать маленькую дату). */
    var onDrag: ((firstVisible: Int) -> Unit)? = null

    // Ползунок появляется только когда чат листает пользователь (пальцем или броском после касания),
    // а не когда список сдвинулся сам: новое сообщение, загрузка файла, возврат на место чтения.
    private var userScrolling = false

    init {
        alpha = 0f
    }

    /** Из OnScrollListener.onScrollStateChanged. */
    fun onScrollState(state: Int) {
        when (state) {
            AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL -> userScrolling = true
            AbsListView.OnScrollListener.SCROLL_STATE_IDLE -> userScrolling = false
        }
    }

    fun attach(parent: ViewGroup) {
        parent.addView(this, ViewGroup.LayoutParams((28 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        list.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val lp = layoutParams
            if (lp.height != b - t) {
                lp.height = b - t
                layoutParams = lp
            }
            x = list.x + (r - l) - lp.width
            y = list.y
        }
    }

    /** Из OnScrollListener списка. */
    fun onScroll(first: Int, visible: Int, total: Int) {
        if (dragging) return
        val range = total - visible
        if (range <= 0 || total < 30) {
            // Короткий чат - ползунок не нужен.
            removeCallbacks(hide)
            alpha = 0f
            return
        }
        fraction = (first.toFloat() / range).coerceIn(0f, 1f)
        if (!userScrolling) {
            invalidate()
            return
        }
        if (alpha < 1f) animate().alpha(1f).setDuration(120).start()
        removeCallbacks(hide)
        postDelayed(hide, 1300)
        invalidate()
    }

    private fun trackTop() = insetTop().toFloat() + 6 * d
    private fun trackBottom() = height - insetBottom().toFloat() - 6 * d

    override fun onDraw(canvas: Canvas) {
        val top = trackTop()
        val span = (trackBottom() - top - thumbH).coerceAtLeast(0f)
        val y = top + span * fraction
        rect.set(width - thumbW - 4 * d, y, width - 4 * d, y + thumbH)
        canvas.drawRoundRect(rect, thumbW, thumbW, if (dragging) activePaint else paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Невидимый ползунок касания не перехватывает - они идут в чат.
        if (!dragging && alpha < 0.5f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.y < trackTop() - 20 * d || e.y > trackBottom() + 20 * d) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                removeCallbacks(hide)
                animate().cancel()
                alpha = 1f
                moveTo(e.y)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) moveTo(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                postDelayed(hide, 1300)
            }
        }
        return true
    }

    private fun moveTo(y: Float) {
        val top = trackTop() + thumbH / 2
        val bottom = trackBottom() - thumbH / 2
        fraction = ((y - top) / (bottom - top).coerceAtLeast(1f)).coerceIn(0f, 1f)
        val total = list.adapter?.count ?: 0
        if (total > 0) {
            val visible = (list.lastVisiblePosition - list.firstVisiblePosition + 1).coerceAtLeast(1)
            val pos = (fraction * (total - visible).coerceAtLeast(0)).toInt().coerceIn(0, total - 1)
            list.setSelection(pos)
            onDrag?.invoke(pos)
        }
        invalidate()
    }
}
