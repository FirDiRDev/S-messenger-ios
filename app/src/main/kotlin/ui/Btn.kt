// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Единый вид кнопок во всём приложении - чтобы они не шли «вразнобой»:
 *  - [primary] - главная кнопка экрана (градиент, 52 dp, белый жирный текст);
 *  - [tonal] - обычная кнопка (мягкая заливка цветом акцента, 44 dp);
 *  - [tile] - плитка с эмодзи и подписью (сетки действий, все плитки одной высоты);
 *  - [row] / [grid] - ряды из кнопок одинаковой ширины с одинаковыми отступами.
 * У всех - одинаковые скругления, «пружинное» нажатие и волна касания.
 */
object Btn {
    const val ACCENT = 0xFF5B4BDB.toInt()
    const val ACCENT_LIGHT = 0xFF7B6CF6.toInt()
    private const val RADIUS = 16f

    private fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density)
    private fun dpi(ctx: Context, v: Int) = dp(ctx, v.toFloat()).toInt()

    private fun ripple(ctx: Context, content: GradientDrawable, light: Boolean): RippleDrawable {
        val mask = GradientDrawable().apply {
            cornerRadius = dp(ctx, RADIUS)
            setColor(Color.WHITE)
        }
        return RippleDrawable(ColorStateList.valueOf(if (light) 0x40FFFFFF else 0x335B4BDB), content, mask)
    }

    /**
     * Одно нажатие - одно действие: два касания подряд в пределах [GUARD_MS] (второй палец,
     * «дребезг» касания, касание соседней кнопки в том же ряду) второе действие не запускают.
     */
    private const val GUARD_MS = 450L
    private var lastClickAt = 0L

    fun guard(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastClickAt < GUARD_MS) return false
        lastClickAt = now
        return true
    }

    /** Обработчик нажатия с защитой от двойного срабатывания. */
    fun View.onTap(click: () -> Unit) = setOnClickListener {
        if (!guard()) return@setOnClickListener
        it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        click()
    }

    private fun base(ctx: Context, text: CharSequence, click: () -> Unit) = TextView(ctx).apply {
        this.text = text
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        paint.isFakeBoldText = true
        setPadding(dpi(ctx, 12), 0, dpi(ctx, 12), 0)
        isClickable = true
        isFocusable = true
        onTap(click)
        Kinetics.squish(this, 0.94f)
    }

    /** Главная кнопка: градиент акцента. */
    fun primary(ctx: Context, text: CharSequence, colors: IntArray = Seasonal.accent(ctx) ?: intArrayOf(ACCENT_LIGHT, ACCENT), click: () -> Unit): TextView =
        base(ctx, text, click).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            val bg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply { cornerRadius = dp(ctx, RADIUS) }
            background = ripple(ctx, bg, light = true)
            elevation = dp(ctx, 3f)
            minimumHeight = dpi(ctx, 52)
        }

    /**
     * Объёмная кнопка-клавиша: ровные скругления, «бортик» снизу темнее, сверху лёгкий блик.
     * При нажатии крышка опускается на бортик (без полупрозрачных краёв).
     */
    fun key3d(ctx: Context, text: CharSequence, colors: IntArray = Seasonal.accent(ctx) ?: intArrayOf(ACCENT_LIGHT, ACCENT), click: () -> Unit): TextView {
        val r = dp(ctx, RADIUS)
        val depth = dpi(ctx, 5)
        fun darker(c: Int): Int {
            val f = 0.68f
            return (c and 0xFF000000.toInt()) or
                (((c shr 16 and 0xFF) * f).toInt() shl 16) or
                (((c shr 8 and 0xFF) * f).toInt() shl 8) or
                ((c and 0xFF) * f).toInt()
        }
        fun side() = GradientDrawable().apply {
            cornerRadius = r
            setColor(darker(colors.last()))
        }
        fun face() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply { cornerRadius = r }
        fun shine() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x40FFFFFF, 0x00FFFFFF)).apply { cornerRadius = r }
        val normal = android.graphics.drawable.LayerDrawable(arrayOf(side(), face(), shine())).apply {
            setLayerInset(1, 0, 0, 0, depth)
            setLayerInset(2, dpi(ctx, 2), dpi(ctx, 2), dpi(ctx, 2), depth + dpi(ctx, 14))
        }
        val pressed = android.graphics.drawable.LayerDrawable(arrayOf(side(), face(), shine())).apply {
            setLayerInset(1, 0, depth - dpi(ctx, 1), 0, dpi(ctx, 1))
            setLayerInset(2, dpi(ctx, 2), depth + dpi(ctx, 1), dpi(ctx, 2), dpi(ctx, 15))
        }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
            setExitFadeDuration(120)
        }
        return TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            paint.isFakeBoldText = true
            textSize = 16f
            setTextColor(Color.WHITE)
            setShadowLayer(dp(ctx, 1.5f), 0f, dp(ctx, 1f), 0x40000000)
            background = states
            setPadding(dpi(ctx, 12), 0, dpi(ctx, 12), depth)
            isClickable = true
            isFocusable = true
            // Текст опускается вместе с крышкой: сдвигаем только отрисовку (scrollY), без
            // перекладки - иначе при касании пересчитывался весь ряд кнопок.
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> v.scrollTo(0, -(depth - dpi(ctx, 2)))
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.scrollTo(0, 0)
                }
                false
            }
            onTap(click)
            setTag(com.stoyko.smessenger.R.id.tag_buttons3d, true)
            setTag(com.stoyko.smessenger.R.id.tag_btn_norm, true)
        }
    }

    /** Обычная кнопка: мягкая заливка цветом акцента. */
    fun tonal(ctx: Context, text: CharSequence, color: Int = ACCENT, click: () -> Unit): TextView =
        base(ctx, text, click).apply {
            textSize = 14f
            setTextColor(color)
            val bg = GradientDrawable().apply {
                cornerRadius = dp(ctx, RADIUS)
                setColor((color and 0x00FFFFFF) or 0x1F000000)
            }
            background = ripple(ctx, bg, light = false)
            minimumHeight = dpi(ctx, 44)
        }

    /** Палитра «красивых» кнопок: у каждой своё мягкое сочетание цветов (верх → низ). */
    val PALETTE = listOf(
        intArrayOf(0xFF8B7CFF.toInt(), 0xFF5B4BDB.toInt()),
        intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt()),
        intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt()),
        intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt()),
        intArrayOf(0xFFFBBF24.toInt(), 0xFFD97706.toInt()),
        intArrayOf(0xFF2DD4BF.toInt(), 0xFF0D9488.toInt()),
        intArrayOf(0xFFC084FC.toInt(), 0xFF9333EA.toInt()),
        intArrayOf(0xFFFB923C.toInt(), 0xFFEA580C.toInt()),
    )

    /**
     * Красивая кнопка без значков: объёмная клавиша с градиентом, текст до двух строк
     * (сам уменьшается, не обрезается). Для меню групп, каналов и «Написать разработчику».
     */
    fun nice(ctx: Context, text: CharSequence, colors: IntArray = Seasonal.accent(ctx) ?: PALETTE[0], click: () -> Unit): TextView =
        key3d(ctx, text, colors, click).apply {
            textSize = 14f
            maxLines = 2
            ellipsize = null
            setLineSpacing(0f, 0.95f)
            setPadding(dpi(ctx, 10), paddingTop, dpi(ctx, 10), paddingBottom)
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                this, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
        }

    /** Плитка: эмодзи сверху, подпись снизу (одна строка). */
    fun tile(ctx: Context, emoji: String, label: CharSequence, color: Int = ACCENT, click: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpi(ctx, 6), dpi(ctx, 8), dpi(ctx, 6), dpi(ctx, 8))
            val bg = GradientDrawable().apply {
                cornerRadius = dp(ctx, RADIUS)
                setColor((color and 0x00FFFFFF) or 0x1A000000)
            }
            background = ripple(ctx, bg, light = false)
            isClickable = true
            isFocusable = true
            addView(
                TextView(ctx).apply {
                    text = emoji
                    textSize = 22f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    tag = "tile_icon"
                },
            )
            addView(
                TextView(ctx).apply {
                    text = label
                    textSize = 12f
                    gravity = Gravity.CENTER
                    // До двух строк: длинная подпись переносится и уменьшается, а не обрезается.
                    maxLines = 2
                    setLineSpacing(0f, 0.9f)
                    setTextColor(color)
                    paint.isFakeBoldText = true
                    tag = "tile_label"
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                        this, 9, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP,
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(ctx, 30)).apply { topMargin = dpi(ctx, 4) },
            )
            setOnClickListener {
                if (!guard()) return@setOnClickListener
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                // Иконка подпрыгивает при нажатии.
                findViewWithTag<View>("tile_icon")?.let { ic ->
                    ic.animate().cancel()
                    ic.scaleX = 0.7f
                    ic.scaleY = 0.7f
                    ic.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }
                click()
            }
            Kinetics.squish(this, 0.93f)
        }

    /** Подпись плитки (например, счётчик фото поменялся). */
    fun setTileLabel(tile: View, label: CharSequence) {
        tile.findViewWithTag<TextView>("tile_label")?.text = label
    }

    /** Ряд кнопок одинаковой ширины. */
    fun row(ctx: Context, views: List<View>, height: Int = 44, gap: Int = 8): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // Касание достаётся только одной кнопке ряда (второй палец соседнюю не нажмёт).
            isMotionEventSplittingEnabled = false
            // Кнопки - ровно в одну линию, даже если у одной подпись в две строки (раньше она
            // «проваливалась» вниз по базовой линии текста - например, на POCO X7 Pro).
            isBaselineAligned = false
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { i, v ->
                addView(
                    v,
                    LinearLayout.LayoutParams(0, dpi(ctx, height), 1f).apply { if (i > 0) marginStart = dpi(ctx, gap) },
                )
            }
        }

    /** Сетка плиток: [cols] в ряд, все одной высоты; неполный ряд дополняется пустыми местами. */
    fun grid(ctx: Context, views: List<View>, cols: Int = 3, height: Int = 76, gap: Int = 8): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            views.chunked(cols).forEachIndexed { r, chunk ->
                val filled = chunk + List(cols - chunk.size) { View(ctx) }
                addView(
                    // Плитке нужно не меньше 84 dp: значок + подпись в две строки.
                    row(ctx, filled, maxOf(height, 84), gap),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { if (r > 0) topMargin = dpi(ctx, gap) },
                )
            }
        }

    /** Плавное появление кнопок одна за другой. */
    fun animateIn(views: List<View>) {
        if (!Kinetics.enabled || EnergySaver.on) return
        Kinetics.stagger(views, step = 35, max = 16)
    }

    /**
     * Привести к одному виду кнопки, собранные в разных местах по-разному: одинаковое
     * скругление, текст по центру в одну строку, жирный. Трогает только «пилюли» и плитки
     * (кликабельные TextView с заливкой высотой 32-64 dp); круглые значки, списки, пузыри
     * сообщений и поля ввода не меняются.
     */
    fun normalizeTree(root: View?) {
        if (root == null || android.os.Build.VERSION.SDK_INT < 24) return
        walk(root)
    }

    private fun walk(v: View) {
        if (v is android.widget.AbsListView || v.javaClass.name.endsWith("RecyclerView")) return
        if (v is TextView && v !is android.widget.EditText && v.hasOnClickListeners()) {
            normalize(v)
            fitText(v)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }

    /**
     * 4.10.0, адаптив: подпись кнопки не влезла и обрезана «…» (узкий экран 720 px, крупный
     * шрифт, длинный перевод) - шрифт кнопки уменьшается ровно настолько, чтобы текст влез
     * целиком (не мельче 10 sp). Только у кнопок с заданной шириной и высотой: у «по содержимому»
     * обрезки и так нет.
     */
    fun fitText(v: TextView) {
        if (v.getTag(com.stoyko.smessenger.R.id.tag_btn_fit) != null) return
        val lp = v.layoutParams ?: return
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT || lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) return
        val layout = v.layout ?: return
        if (layout.lineCount == 0 || layout.getEllipsisCount(layout.lineCount - 1) <= 0) return
        val sp = v.textSize / v.resources.displayMetrics.scaledDensity
        if (sp <= 10.5f) return
        v.setTag(com.stoyko.smessenger.R.id.tag_btn_fit, true)
        runCatching {
            v.ellipsize = null
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                v, 10, sp.toInt().coerceAtLeast(11), 1, android.util.TypedValue.COMPLEX_UNIT_SP,
            )
            v.requestLayout()
        }
    }

    private fun normalize(v: TextView) {
        if (v.getTag(com.stoyko.smessenger.R.id.tag_btn_norm) != null) return
        if (v is com.google.android.material.button.MaterialButton) return
        val bg = v.background as? GradientDrawable ?: return
        if (android.os.Build.VERSION.SDK_INT < 24 || bg.shape != GradientDrawable.RECTANGLE) return
        val d = v.resources.displayMetrics.density
        val h = v.height
        val w = v.width
        if (h == 0 || h < 32 * d || h > 64 * d) return
        // Квадратные/круглые значки-кнопки оставляем как есть.
        if (w in (h - 4 * d).toInt()..(h + 4 * d).toInt()) return
        v.setTag(com.stoyko.smessenger.R.id.tag_btn_norm, true)
        val nb = bg.mutate() as GradientDrawable
        nb.cornerRadius = RADIUS * d
        v.background = nb
        v.gravity = Gravity.CENTER
        v.paint.isFakeBoldText = true
        if ('\n' !in v.text && (v.maxLines == Int.MAX_VALUE || v.maxLines > 1)) {
            v.maxLines = 1
            v.ellipsize = TextUtils.TruncateAt.END
        }
        if (v.stateListAnimator == null) Kinetics.squish(v, 0.94f)
    }
}
