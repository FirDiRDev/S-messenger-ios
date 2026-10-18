// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ListView
import kotlin.math.abs
import kotlin.math.min

/**
 * Список сообщений со «свайпом для ответа»: потяните сообщение влево -
 * справа появится стрелка, отпустите за порогом - начнётся ответ на это сообщение.
 */
class SwipeReplyListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListView(context, attrs) {
    private val density = resources.displayMetrics.density

    /** Можно ли отвечать на сообщение в этой позиции. */
    var canSwipe: (Int) -> Boolean = { true }

    /** Ответ на сообщение в позиции. */
    var onSwipeReply: ((Int) -> Unit)? = null

    /** Двойное касание сообщения (быстрая реакция): позиция и точка касания. */
    var onDoubleTap: ((position: Int, x: Float, y: Float) -> Unit)? = null

    private val doubleTap = android.view.GestureDetector(
        context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val cb = onDoubleTap ?: return false
                val pos = pointToPosition(e.x.toInt(), e.y.toInt())
                if (pos == INVALID_POSITION) return false
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                // Одиночное нажатие (оно ждёт) отменяем - сработает только двойное.
                pendingClick?.let { removeCallbacks(it) }
                pendingClick = null
                cb(pos, e.x, e.y)
                return true
            }
        },
    )

    // Кольцо вокруг стрелки заполняется по мере свайпа, на пороге стрелка «прыгает».
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private var pop = 1f
    private var pendingClick: Runnable? = null

    // ---------------- режим выбора ----------------

    /** Режим выбора включён. */
    var selectionActive: () -> Boolean = { false }

    /** Выбрано ли сообщение в позиции (для кружков-галочек). */
    var isPositionSelected: (Int) -> Boolean = { false }

    /** Касание по сообщению в режиме выбора - в любом месте сообщения, даже по фото/файлу. */
    var onSelectTap: ((Int) -> Unit)? = null

    private var selDownX = 0f
    private var selDownY = 0f
    private var selDownTime = 0L
    private var selMoved = false
    private var selAppear = 0f
    private var wasSelecting = false
    private val checkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00C2A8.toInt() }
    private val checkRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = 0xB3FFFFFF.toInt()
    }
    private val checkShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33000000 }
    private val checkTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val selTint = Paint().apply { color = 0x2E5B4BDB }
    private val tickPath = android.graphics.Path()

    /** Позиция, по которой «выстрелить» пружину галочки (только что отмеченное сообщение). */
    private var popPos = INVALID_POSITION
    private var popScale = 1f

    /** Анимация галочки у только что отмеченного сообщения. */
    fun animateCheck(position: Int) {
        popPos = position
        android.animation.ValueAnimator.ofFloat(0.4f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.OvershootInterpolator(3f)
            addUpdateListener {
                popScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** Кружки появляются/исчезают плавно при входе и выходе из режима выбора. */
    private fun checkSelectionChanged() {
        val now = selectionActive()
        if (now == wasSelecting) return
        wasSelecting = now
        android.animation.ValueAnimator.ofFloat(if (now) 0f else 1f, if (now) 1f else 0f).apply {
            duration = 220
            addUpdateListener {
                selAppear = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Когда включено двойное касание, обычное нажатие на сообщение срабатывает с короткой
     * задержкой - чтобы двойное касание не открывало заодно окно реакций.
     */
    override fun performItemClick(view: View?, position: Int, id: Long): Boolean {
        // В режиме выбора нажатия обрабатывает onSelectTap (иначе сообщение отмечалось бы дважды).
        if (selectionActive() && onSelectTap != null) return true
        if (onDoubleTap == null) return super.performItemClick(view, position, id)
        pendingClick?.let { removeCallbacks(it) }
        val r = Runnable {
            pendingClick = null
            super.performItemClick(view, position, id)
        }
        pendingClick = r
        postDelayed(r, ViewConfiguration.getDoubleTapTimeout().toLong())
        return true
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val threshold = 64 * density
    private val maxShift = 96 * density
    private var downX = 0f
    private var downY = 0f
    private var position = INVALID_POSITION
    private var dragging = false
    private var passed = false
    private var target: View? = null
    private var shift = 0f
    private val icon: Drawable? = try {
        context.getDrawable(com.stoyko.smessenger.R.drawable.ic_reply)?.mutate()
    } catch (_: Exception) {
        null
    }
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x805B4BDB.toInt() }

    private fun begin(ev: MotionEvent) {
        downX = ev.x
        downY = ev.y
        position = pointToPosition(ev.x.toInt(), ev.y.toInt())
        dragging = false
        passed = false
    }

    /** true - начали тянуть сообщение. */
    private fun check(ev: MotionEvent): Boolean {
        if (dragging) return true
        if (onSwipeReply == null || position == INVALID_POSITION) return false
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (dx < -slop * 1.2f && abs(dx) > abs(dy) * 1.6f && canSwipe(position)) {
            target = getChildAt(position - firstVisiblePosition) ?: return false
            dragging = true
            parent?.requestDisallowInterceptTouchEvent(true)
            // Прокрутку и долгое нажатие списка отменяем.
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.onTouchEvent(cancel)
            cancel.recycle()
            return true
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Режим выбора: все касания забирает список - отметить можно, нажав в любом месте
        // сообщения (раньше фото, файлы и кнопки внутри сообщения «съедали» нажатие).
        if (selectionActive() && onSelectTap != null) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) super.onInterceptTouchEvent(ev)
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> if (check(ev)) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (selectionActive() && onSelectTap != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selDownX = ev.x
                    selDownY = ev.y
                    selDownTime = ev.eventTime
                    selMoved = false
                }
                MotionEvent.ACTION_MOVE -> if (abs(ev.x - selDownX) > slop || abs(ev.y - selDownY) > slop) selMoved = true
                MotionEvent.ACTION_UP -> if (!selMoved && ev.eventTime - selDownTime < ViewConfiguration.getLongPressTimeout()) {
                    val pos = pointToPosition(ev.x.toInt(), ev.y.toInt())
                    if (pos != INVALID_POSITION) {
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onSelectTap?.invoke(pos)
                    }
                }
            }
            // Прокрутка списка работает как обычно; клики списка в этом режиме не нужны.
            return super.onTouchEvent(ev)
        }
        if (!dragging && onDoubleTap != null) doubleTap.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                if (dragging || check(ev)) {
                    drag(ev.x - downX)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                release(ev.actionMasked == MotionEvent.ACTION_UP)
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun drag(dx: Float) {
        val t = target ?: return
        // Чем дальше - тем туже, как резинка.
        val raw = min(0f, dx)
        shift = -min(maxShift, -raw * 0.7f)
        t.translationX = shift
        val now = -shift >= threshold
        if (now && !passed) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            // «Прыжок» стрелки при достижении порога.
            android.animation.ValueAnimator.ofFloat(1.35f, 1f).apply {
                duration = 260
                interpolator = android.view.animation.OvershootInterpolator(3f)
                addUpdateListener {
                    pop = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        passed = now
        invalidate()
    }

    private fun release(up: Boolean) {
        val t = target
        val pos = position
        val fire = up && passed
        dragging = false
        target = null
        // Пружиной обратно (с лёгким перелётом).
        t?.animate()?.translationX(0f)?.setDuration(320)?.setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
            ?.setUpdateListener { shift = t.translationX; invalidate() }
            ?.withEndAction {
                t.animate().setUpdateListener(null)
                shift = 0f
                invalidate()
            }?.start()
        if (fire && pos != INVALID_POSITION) onSwipeReply?.invoke(pos)
    }

    override fun dispatchDraw(canvas: Canvas) {
        checkSelectionChanged()
        // Подсветка выбранных - под сообщениями, на всю ширину.
        if (selAppear > 0f) {
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (isPositionSelected(firstVisiblePosition + i)) {
                    selTint.alpha = (0x2E * selAppear).toInt()
                    canvas.drawRect(0f, c.top.toFloat(), width.toFloat(), c.bottom.toFloat(), selTint)
                }
            }
        }
        super.dispatchDraw(canvas)
        // Кружки-галочки рисует сам список - в одном и том же месте слева, они больше не «съезжают»
        // вместе с сообщением.
        if (selAppear > 0f) {
            val r = 11 * density * selAppear
            val cx = 18 * density
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val pos = firstVisiblePosition + i
                val cy = c.top + c.height / 2f
                val checked = isPositionSelected(pos)
                val rr = if (checked && pos == popPos) r * popScale else r
                canvas.drawCircle(cx, cy, rr + 1.5f * density, checkShadow)
                if (checked) {
                    canvas.drawCircle(cx, cy, rr, checkFill)
                    tickPath.reset()
                    tickPath.moveTo(cx - rr * 0.45f, cy + rr * 0.02f)
                    tickPath.lineTo(cx - rr * 0.1f, cy + rr * 0.38f)
                    tickPath.lineTo(cx + rr * 0.5f, cy - rr * 0.32f)
                    canvas.drawPath(tickPath, checkTick)
                } else {
                    canvas.drawCircle(cx, cy, rr, checkRing)
                }
            }
        }
        val t = target ?: return
        if (shift >= 0f) return
        val progress = (-shift / threshold).coerceIn(0f, 1f)
        val r = 16 * density * (0.5f + progress * 0.5f)
        val cx = width - 28 * density
        val cy = t.top + t.height / 2f
        circle.alpha = (progress * 200).toInt()
        val rr = r * (if (passed) pop else 1f)
        canvas.drawCircle(cx, cy, rr, circle)
        ring.strokeWidth = 2.2f * density
        ring.alpha = (progress * 230).toInt()
        canvas.drawArc(cx - rr - 3 * density, cy - rr - 3 * density, cx + rr + 3 * density, cy + rr + 3 * density, -90f, 360f * progress, false, ring)
        icon?.let {
            val s = (rr * 1.1f).toInt()
            it.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            it.alpha = (progress * 255).toInt()
            it.setTint(0xFFFFFFFF.toInt())
            it.draw(canvas)
        }
    }
}
