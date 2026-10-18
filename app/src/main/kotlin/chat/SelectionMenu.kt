// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.animation.TimeInterpolator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.stoyko.smessenger.ui.GlassFrameLayout
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Всплывающее меню форматирования над выделенным текстом - «жидкое стекло»: размытое
 * содержимое экрана под панелью, светлая кромка, мягкое пружинное появление.
 *
 * Как работает: системная панель «Копировать/Вставить» заменяется своей (её кнопки - тоже
 * здесь). После каждой перерисовки поля проверяется выделение; если оно изменилось - панель
 * встаёт ровно над выделенным словом (координаты из разметки текста), если выделения нет -
 * панель прячется. Кнопки оборачивают выделение строчной разметкой [RichText]:
 * **жирный**, __курсив__, ||спойлер||, ~~зачёркнутый~~.
 */
object SelectionMenu {
    private const val TAG_KEY = "selection_menu"

    fun install(edit: EditText) {
        if (edit.getTag(com.stoyko.smessenger.R.id.tag_selection_menu) == TAG_KEY) return
        edit.setTag(com.stoyko.smessenger.R.id.tag_selection_menu, TAG_KEY)
        val ctl = Controller(edit)
        // Системная панель не нужна - её действия есть в нашем меню.
        edit.customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
        edit.viewTreeObserver.addOnPreDrawListener(ctl)
        edit.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = ctl.dismiss()
        })
        edit.onFocusChangeListener.let { prev ->
            edit.setOnFocusChangeListener { v, has ->
                prev?.onFocusChange(v, has)
                if (!has) ctl.dismiss()
            }
        }
    }

    private class Controller(val edit: EditText) : ViewTreeObserver.OnPreDrawListener {
        private var lastStart = -1
        private var lastEnd = -1
        private var popup: PopupWindow? = null
        private var panel: View? = null
        private val d = edit.resources.displayMetrics.density

        override fun onPreDraw(): Boolean {
            val s = edit.selectionStart
            val e = edit.selectionEnd
            if (s == lastStart && e == lastEnd) return true
            lastStart = s
            lastEnd = e
            if (s < 0 || e <= s || !edit.hasFocus()) {
                dismiss()
            } else {
                edit.post { show(minOf(s, e), maxOf(s, e)) }
            }
            return true
        }

        fun dismiss() {
            runCatching { popup?.dismiss() }
            popup = null
            panel = null
        }

        private fun show(s: Int, e: Int) {
            val layout = edit.layout ?: return
            if (!edit.isAttachedToWindow) return
            val content = panel ?: build().also { panel = it }
            content.measure(
                View.MeasureSpec.makeMeasureSpec(edit.rootView.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val pw = content.measuredWidth
            val ph = content.measuredHeight
            val line = layout.getLineForOffset(s)
            val endLine = layout.getLineForOffset(e)
            val x0 = layout.getPrimaryHorizontal(s)
            val x1 = if (endLine == line) layout.getPrimaryHorizontal(e) else layout.getLineRight(line)
            val loc = IntArray(2)
            edit.getLocationOnScreen(loc)
            val cx = loc[0] + edit.totalPaddingLeft - edit.scrollX + (x0 + x1) / 2f
            val top = loc[1] + edit.totalPaddingTop - edit.scrollY + layout.getLineTop(line)
            val bottom = loc[1] + edit.totalPaddingTop - edit.scrollY + layout.getLineBottom(endLine)
            val screenW = edit.rootView.width
            val x = (cx - pw / 2f).toInt().coerceIn((8 * d).toInt(), (screenW - pw - 8 * d).toInt().coerceAtLeast(0))
            // Над словом; если сверху нет места - под ним.
            var y = top - ph - (10 * d).toInt()
            if (y < (24 * d).toInt()) y = bottom + (12 * d).toInt()
            val p = popup
            if (p != null && p.isShowing) {
                p.update(x, y, -1, -1)
                return
            }
            val win = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                isOutsideTouchable = false
                isClippingEnabled = true
                elevation = 12 * d
                inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            }
            popup = win
            runCatching { win.showAtLocation(edit, Gravity.NO_GRAVITY, x, y) }
            springIn(content, cx - x)
        }

        /** Пружинное появление: из точки над словом, с лёгким «перелётом» и затуханием колебаний. */
        private fun springIn(v: View, pivotX: Float) {
            if (!com.stoyko.smessenger.ui.Kinetics.enabled) return
            v.pivotX = pivotX
            v.pivotY = v.measuredHeight.toFloat()
            v.scaleX = 0.55f
            v.scaleY = 0.55f
            v.alpha = 0f
            v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(520).setInterpolator(Spring(0.48f, 2.4f)).start()
        }

        private fun build(): View {
            val ctx = edit.context
            val glass = GlassFrameLayout(ctx).apply {
                blurSource = edit.rootView
                cornerRadius = 18 * d
                tintColor = 0x8C1C1A33.toInt()
                fallbackColor = 0xE61C1A33.toInt()
                edgeTop = true
                edgeBottom = true
                blurRadius = 4
                liquid = true
                setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun btn(label: String, bold: Boolean = false, italic: Boolean = false, click: () -> Unit) = TextView(ctx).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                setTypeface(typeface, if (bold && italic) Typeface.BOLD_ITALIC else if (bold) Typeface.BOLD else if (italic) Typeface.ITALIC else Typeface.NORMAL)
                gravity = Gravity.CENTER
                minWidth = (40 * d).toInt()
                setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), (9 * d).toInt())
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x40FFFFFF),
                    null,
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(Color.WHITE)
                    },
                )
                setOnClickListener {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    click()
                }
            }
            row.addView(btn(t("Ж", "B"), bold = true) { wrap("**") })
            row.addView(btn(t("К", "I"), italic = true) { wrap("__") })
            row.addView(btn("▒ " + t("Спойлер", "Spoiler")) { wrap("||") })
            row.addView(btn("S̶") { wrap("~~") })
            row.addView(divider(ctx))
            row.addView(btn("⧉") { copy(cut = false) })
            row.addView(btn("✂") { copy(cut = true) })
            glass.addView(row)
            return glass
        }

        private fun divider(ctx: Context) = View(ctx).apply {
            setBackgroundColor(0x40FFFFFF)
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt().coerceAtLeast(1), (22 * d).toInt()).apply {
                marginStart = (4 * d).toInt()
                marginEnd = (4 * d).toInt()
            }
        }

        /** Обернуть выделение маркерами (повторно - снять оформление). */
        private fun wrap(m: String) {
            val s = minOf(edit.selectionStart, edit.selectionEnd)
            val e = maxOf(edit.selectionStart, edit.selectionEnd)
            if (s < 0 || e <= s) return
            val text = edit.text
            val inner = text.subSequence(s, e).toString()
            val before = text.subSequence(maxOf(0, s - m.length), s).toString()
            val after = text.subSequence(e, minOf(text.length, e + m.length)).toString()
            if (before == m && after == m) {
                text.delete(e, e + m.length)
                text.delete(s - m.length, s)
                edit.setSelection(s - m.length, e - m.length)
            } else if (inner.startsWith(m) && inner.endsWith(m) && inner.length >= 2 * m.length) {
                text.replace(s, e, inner.substring(m.length, inner.length - m.length))
                edit.setSelection(s, e - 2 * m.length)
            } else {
                text.replace(s, e, m + inner + m)
                edit.setSelection(s + m.length, e + m.length)
            }
        }

        private fun copy(cut: Boolean) {
            val s = minOf(edit.selectionStart, edit.selectionEnd)
            val e = maxOf(edit.selectionStart, edit.selectionEnd)
            if (s < 0 || e <= s) return
            val cm = edit.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("text", edit.text.subSequence(s, e)))
            if (cut) edit.text.delete(s, e) else edit.setSelection(e)
            dismiss()
        }
    }

    /**
     * Пружина (затухающие колебания): x(t) = 1 - e^(-ζωt)·cos(ω_d·t).
     * [damping] - затухание (меньше - больше «отскок»), [freq] - сколько колебаний за анимацию.
     */
    class Spring(private val damping: Float, private val freq: Float) : TimeInterpolator {
        override fun getInterpolation(t: Float): Float {
            val w = 2.0 * PI * freq
            val wd = w * sqrt(1.0 - (damping * damping).toDouble().coerceAtMost(0.99))
            return (1.0 - exp(-damping * w * t) * cos(wd * t)).toFloat()
        }
    }

    private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)
}
