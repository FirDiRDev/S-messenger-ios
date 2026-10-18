// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.anim

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import com.stoyko.smessenger.chat.SelectionMenu
import com.stoyko.smessenger.ui.GlassFrameLayout

/**
 * «Peek & Pop» - предпросмотр чата долгим нажатием на строку списка.
 *
 *  1. Вдавливание: как только палец задержался на строке, поверх экрана появляется слой
 *     «стекла», и размытие всего интерфейса плавно нарастает до момента срабатывания; сама
 *     строка остаётся чёткой поверх размытия и уходит вглубь (scale 0.95).
 *  2. Выпрыгивание: срабатывает долгое нажатие - из строки «вылетает» окно предпросмотра
 *     (последние сообщения) с мягким пружинным отскоком; фон полностью размыт.
 *  3. Домино-меню: кнопки действий под окном появляются по очереди с микро-задержкой.
 * Отпустили палец раньше - размытие плавно уходит, строка возвращается, нажатие работает как обычно.
 */
object PeekPop {
    class Action(val label: String, val danger: Boolean = false, val run: () -> Unit)

    private val main = Handler(Looper.getMainLooper())

    /** Можно ли сейчас анимировать (анимации включены, нет «меньше движения»). */
    fun motionOk(ctx: Context): Boolean =
        com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.Accessibility.reduceMotion(ctx)

    /** Слой фазы 1 (стекло + чёткая копия строки), который фаза 2 превращает в предпросмотр. */
    private class Layer(val dialog: Dialog, val root: FrameLayout, val glass: GlassFrameLayout, val rowCopy: ImageView, val rowLeft: Int, val rowTop: Int, val rowW: Int, val rowH: Int) {
        /** Долгое нажатие сработало - слой ждёт окно предпросмотра (палец можно отпустить). */
        var popped = false

        /** Окно предпросмотра уже показано на этом слое. */
        var shown = false
    }

    private var layer: Layer? = null

    // ------------------------------------------------------------------ фаза 1: вдавливание

    /**
     * Следить за удержанием строки [list]. [eligible] - строки, у которых есть предпросмотр.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun installPress(list: ListView, eligible: (Int) -> Boolean) {
        val longPress = ViewConfiguration.getLongPressTimeout().toLong()
        val slop = ViewConfiguration.get(list.context).scaledTouchSlop
        var row: View? = null
        var downX = 0f
        var downY = 0f
        val start = Runnable {
            val r = row ?: return@Runnable
            if (!motionOk(list.context) || !list.isAttachedToWindow) return@Runnable
            begin(list, r, (longPress - 110).coerceAtLeast(150))
        }
        fun release() {
            main.removeCallbacks(start)
            row = null
            val l = layer
            if (l != null && !l.popped) end(l)
        }
        list.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    release()
                    val pos = list.pointToPosition(ev.x.toInt(), ev.y.toInt())
                    if (pos != ListView.INVALID_POSITION && eligible(pos)) {
                        row = list.getChildAt(pos - list.firstVisiblePosition)
                        downX = ev.x
                        downY = ev.y
                        main.postDelayed(start, 110)
                    }
                }
                MotionEvent.ACTION_MOVE -> if (row != null && (kotlin.math.abs(ev.x - downX) > slop || kotlin.math.abs(ev.y - downY) > slop)) release()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            }
            false
        }
        list.setTag(com.stoyko.smessenger.R.id.tag_peek, Runnable { release() })
    }

    /**
     * Долгое нажатие сработало: слой фазы 1 остаётся и ждёт окно предпросмотра (сообщения
     * загружаются в фоне). Если окно так и не пришло - через 2,5 с слой сам уходит.
     */
    fun resetPress(list: ListView) {
        val l = layer
        l?.popped = true
        (list.getTag(com.stoyko.smessenger.R.id.tag_peek) as? Runnable)?.run()
        if (l != null) main.postDelayed({ if (!l.shown && layer === l) end(l) }, 2500)
    }

    /** Фаза 1: слой стекла поверх экрана (размытие нарастает) и чёткая копия строки, уходящая вглубь. */
    private fun begin(list: ListView, row: View, ms: Long) {
        layer?.let { runCatching { it.dialog.dismiss() } }
        com.stoyko.smessenger.ui.LiteMode.quiet(4000)
        val ctx = list.context
        val w = row.width
        val h = row.height
        if (w <= 0 || h <= 0) return
        val bmp = runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b)
                // Фон строки: без него копия была бы прозрачной поверх стекла.
                // Копия фона окна: сам фон окна трогать нельзя - раньше ему меняли размер на
                // размер строки, и после предпросмотра весь список «размазывался» (у окна
                // оставался фон размером с одну строку).
                val bg = list.rootView.background?.constantState?.newDrawable(list.resources)?.mutate()
                if (bg != null) {
                    bg.setBounds(0, 0, w, h)
                    bg.draw(c)
                } else {
                    c.drawColor(0xFF1C1A33.toInt())
                }
                row.draw(c)
            }
        }.getOrNull() ?: return
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = FrameLayout(ctx)
        val glass = GlassFrameLayout(ctx).apply {
            blurSource = list.rootView
            tintColor = 0x66000000
            fallbackColor = 0xCC000000.toInt()
            blurRadius = 6
            edgeTop = false
            alpha = 0f
        }
        root.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val copy = ImageView(ctx).apply {
            setImageBitmap(bmp)
            elevation = 12 * ctx.resources.displayMetrics.density
            outlineProvider = android.view.ViewOutlineProvider.BOUNDS
        }
        root.addView(copy, FrameLayout.LayoutParams(w, h).apply {
            leftMargin = loc[0]
            topMargin = loc[1]
        })
        dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            // Пока палец держит строку - окно не перехватывает касания (они идут списку).
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            setWindowAnimations(0)
        }
        val l = Layer(dialog, root, glass, copy, loc[0], loc[1], w, h)
        layer = l
        runCatching { dialog.show() }.onFailure {
            layer = null
            return
        }
        // Размытие плавно нарастает, строка уходит вглубь.
        glass.animate().alpha(1f).setDuration(ms).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        copy.animate().scaleX(0.95f).scaleY(0.95f).setDuration(ms).start()
        dialog.setOnDismissListener { if (layer === l) layer = null }
    }

    /** Отпустили раньше срабатывания - размытие уходит, строка возвращается. */
    private fun end(l: Layer) {
        l.glass.animate().cancel()
        l.rowCopy.animate().cancel()
        l.glass.animate().alpha(0f).setDuration(160).start()
        l.rowCopy.animate().scaleX(1f).scaleY(1f).setDuration(160).withEndAction {
            runCatching { l.dialog.dismiss() }
        }.start()
        if (layer === l) layer = null
    }

    // ------------------------------------------------------------------ фазы 2-3: окно и меню

    /**
     * Показать предпросмотр (фаза 2-3). Если идёт фаза 1 - окно «вылетает» из удерживаемой строки
     * на том же размытом слое; иначе - открывается сразу. [avatar] - аватарка, [lines] -
     * последние сообщения (текст и «моё»), [actions] - кнопки «домино».
     */
    fun show(ctx: Context, blurSource: View, avatar: Drawable?, name: String, lines: List<Pair<CharSequence, Boolean>>, actions: List<Action>) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        com.stoyko.smessenger.ui.LiteMode.quiet(3000)
        val reuse = layer
        val dialog: Dialog
        val root: FrameLayout
        val glass: GlassFrameLayout
        if (reuse != null) {
            reuse.popped = true
            reuse.shown = true
            dialog = reuse.dialog
            root = reuse.root
            glass = reuse.glass
            dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            glass.animate().cancel()
            glass.animate().alpha(1f).setDuration(120).start()
        } else {
            dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            root = FrameLayout(ctx)
            glass = GlassFrameLayout(ctx).apply {
                this.blurSource = blurSource
                tintColor = 0x66000000
                fallbackColor = 0xCC000000.toInt()
                blurRadius = 6
                edgeTop = false
            }
            root.addView(glass, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dialog.setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            dialog.window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                setWindowAnimations(0)
            }
        }
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(40), dp(18), dp(24))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                colors = intArrayOf(0xF22A2650.toInt(), 0xF21C1A33.toInt())
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            elevation = dp(16).toFloat()
            setPadding(dp(14), dp(12), dp(14), dp(12))
            clipToOutline = true
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (avatar != null) {
            head.addView(ImageView(ctx).apply { setImageDrawable(avatar) }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        }
        head.addView(
            TextView(ctx).apply {
                text = name
                textSize = 17f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                maxLines = 1
            },
        )
        card.addView(head)
        val msgs = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (lines.isEmpty()) {
            msgs.addView(TextView(ctx).apply {
                text = com.stoyko.smessenger.ai.t("Сообщений пока нет", "No messages yet")
                setTextColor(0xB3FFFFFF.toInt())
                setPadding(0, dp(12), 0, dp(6))
            })
        }
        for ((text, mine) in lines) {
            msgs.addView(
                TextView(ctx).apply {
                    this.text = text
                    textSize = 14f
                    maxLines = 4
                    setTextColor(Color.WHITE)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(14).toFloat()
                        setColor(if (mine) 0xFF5B4CE0.toInt() else 0x33FFFFFF)
                    }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (mine) Gravity.END else Gravity.START
                    topMargin = dp(6)
                    if (mine) marginStart = dp(40) else marginEnd = dp(40)
                },
            )
        }
        card.addView(ScrollView(ctx).apply { addView(msgs) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.45f).toInt()
        column.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.post { if (card.height > maxH) card.layoutParams = card.layoutParams.apply { height = maxH } }

        val menu = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF2222036.toInt())
            }
            clipToOutline = true
        }
        val buttons = actions.mapIndexed { i, a ->
            TextView(ctx).apply {
                text = a.label
                textSize = 16f
                setTextColor(if (a.danger) 0xFFFF6B6B.toInt() else Color.WHITE)
                setPadding(dp(18), dp(13), dp(18), dp(13))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
                setOnClickListener {
                    dialog.dismiss()
                    a.run()
                }
                if (i > 0) {
                    menu.addView(View(ctx).apply { setBackgroundColor(0x1FFFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                }
                menu.addView(this)
            }
        }
        column.addView(menu, LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            gravity = Gravity.END
        })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        glass.setOnClickListener { dialog.dismiss() }
        root.setOnClickListener { dialog.dismiss() }
        card.setOnClickListener { actions.firstOrNull()?.let { a -> dialog.dismiss(); a.run() } }
        if (reuse == null) runCatching { dialog.show() }
        dialog.setOnDismissListener { if (layer?.dialog === dialog) layer = null }

        // Фаза 2: окно «вылетает» из строки с пружиной; фаза 3: кнопки - по очереди.
        if (motionOk(ctx)) {
            if (reuse != null) {
                // Копия строки растворяется, окно вылетает из её места.
                reuse.rowCopy.animate().alpha(0f).scaleX(1.04f).scaleY(1.04f).setDuration(200).start()
                card.alpha = 0f
                card.post {
                    val loc = IntArray(2)
                    card.getLocationOnScreen(loc)
                    // Окно стартует ровно на месте строки (по центру, той же ширины) и плавно
                    // переезжает на своё место - без двойного смещения от точки опоры.
                    card.pivotX = card.width / 2f
                    card.pivotY = card.height / 2f
                    card.translationY = (reuse.rowTop + reuse.rowH / 2f) - (loc[1] + card.height / 2f)
                    val s0 = (reuse.rowH.toFloat() / card.height.coerceAtLeast(1)).coerceIn(0.35f, 0.9f)
                    card.scaleX = 0.92f
                    card.scaleY = s0
                    card.alpha = 0.4f
                    card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(420)
                        .setInterpolator(SelectionMenu.Spring(0.78f, 1.6f)).withLayer().start()
                }
            } else {
                glass.alpha = 0f
                glass.animate().alpha(1f).setDuration(180).start()
                card.scaleX = 0.9f
                card.scaleY = 0.9f
                card.translationY = dp(16).toFloat()
                card.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(420).setInterpolator(SelectionMenu.Spring(0.78f, 1.6f)).withLayer().start()
            }
            buttons.forEachIndexed { i, b ->
                b.alpha = 0f
                b.translationY = dp(14).toFloat()
                b.animate().alpha(1f).translationY(0f).setStartDelay(140L + i * 35L).setDuration(260)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
            }
        } else {
            reuse?.rowCopy?.visibility = View.GONE
        }
        glass.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }
}
