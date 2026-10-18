// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.AnimatedEmoji

/**
 * Знакомство с приложением после регистрации: что где находится и как включить главное
 * (скрытые чаты, поиск, реакции, VPN, узлы, память...). Страницы листаются свайпом или кнопками,
 * у каждой - анимированный эмодзи. Показывается один раз; повторить - Настройки → О приложении.
 */
object Onboarding {
    private const val PREFS = "onboarding"
    private const val VERSION = 4

    private data class Page(val emoji: String, val title: Int, val text: Int, val color: Int)

    private val pages = listOf(
        Page("👋", R.string.ob_welcome_title, R.string.ob_welcome_text, 0xFF5B4BDB.toInt()),
        Page("➕", R.string.ob_contacts_title, R.string.ob_contacts_text, 0xFF0EA5E9.toInt()),
        Page("💬", R.string.ob_chats_title, R.string.ob_chats_text, 0xFF8B5CF6.toInt()),
        Page("😍", R.string.ob_reactions_title, R.string.ob_reactions_text, 0xFFDB2777.toInt()),
        Page("🔒", R.string.ob_hidden_title, R.string.ob_hidden_text, 0xFF111827.toInt()),
        Page("⭐", R.string.ob_saved_title, R.string.ob_saved_text, 0xFFF59E0B.toInt()),
        Page("🎥", R.string.ob_media_title, R.string.ob_media_text, 0xFFE5484D.toInt()),
        Page("🛡️", R.string.ob_vpn_title, R.string.ob_vpn_text, 0xFF00967F.toInt()),
        Page("🎬", R.string.ob_player_title, R.string.ob_player_text, 0xFFBE185D.toInt()),
        Page("😺", R.string.ob_stickers_title, R.string.ob_stickers_text, 0xFFEA580C.toInt()),
        Page("👥", R.string.ob_groups_title, R.string.ob_groups_text, 0xFF0891B2.toInt()),
        Page("📰", R.string.ob_feed_title, R.string.ob_feed_text, 0xFF4F46E5.toInt()),
        Page("🔐", R.string.ob_e2e_title, R.string.ob_e2e_text, 0xFF047857.toInt()),
        Page("🤖", R.string.ob_ai_title, R.string.ob_ai_text, 0xFF2563EB.toInt()),
        Page("🧠", R.string.ob_ai_howto_title, R.string.ob_ai_howto_text, 0xFF1D4ED8.toInt()),
        Page("🌐", R.string.ob_browser_title, R.string.ob_browser_text, 0xFF0F766E.toInt()),
        Page("📌", R.string.ob_pins_title, R.string.ob_pins_text, 0xFFDB2777.toInt()),
        Page("☰", R.string.ob_menu_title, R.string.ob_menu_text, 0xFF6D28D9.toInt()),
        Page("⚙️", R.string.ob_settings_title, R.string.ob_settings_text, 0xFF334155.toInt()),
        Page("🔔", R.string.ob_notify_title, R.string.ob_notify_text, 0xFFB45309.toInt()),
        Page("🔄", R.string.ob_restart_title, R.string.ob_restart_text, 0xFF7C3AED.toInt()),
        Page("🔋", R.string.ob_battery_title, R.string.ob_battery_text, 0xFF65A30D.toInt()),
        Page("👓", R.string.ob_a11y_title, R.string.ob_a11y_text, 0xFF0F766E.toInt()),
        Page("🎉", R.string.ob_fun_title, R.string.ob_fun_text, 0xFFDB2777.toInt()),
        Page("🎮", R.string.ob_games_title, R.string.ob_games_text, 0xFF7C3AED.toInt()),
        Page("🪙", R.string.ob_coins_title, R.string.ob_coins_text, 0xFFD97706.toInt()),
        Page("📡", R.string.ob_tv_title, R.string.ob_tv_text, 0xFF0369A1.toInt()),
        Page("💎", R.string.ob_m3_title, R.string.ob_m3_text, 0xFF0F766E.toInt()),
        Page("🏃", R.string.runner_title, R.string.ob_runner_text, 0xFFEA580C.toInt()),
        Page("🛒", R.string.ob_shop_title, R.string.ob_shop_text, 0xFFB45309.toInt()),
        Page("↪️", R.string.ob_forward_title, R.string.ob_forward_text, 0xFF4338CA.toInt()),
        Page("🚇", R.string.ob_metro_title, R.string.ob_metro_text, 0xFFDC2626.toInt()),
        Page("💾", R.string.ob_export_title, R.string.ob_export_text, 0xFF475569.toInt()),
        Page("⬆️", R.string.ob_update_title, R.string.ob_update_text, 0xFF059669.toInt()),
        Page("🚀", R.string.ob_done_title, R.string.ob_done_text, 0xFF5B4BDB.toInt()),
    )

    /** Коды эмодзи страниц - чтобы скачать анимации заранее, ещё до показа знакомства. */
    fun emojiCodes(): List<String> = pages.map { AnimatedEmoji.code(it.emoji) }

    fun shouldShow(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("seen", 0) < VERSION

    private fun markSeen(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("seen", VERSION).apply()

    fun showIfNeeded(ctx: Context, onDone: (() -> Unit)? = null) {
        if (shouldShow(ctx)) show(ctx, onDone) else onDone?.invoke()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(ctx: Context, onDone: (() -> Unit)? = null) {
        val d = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen))
        // Экран - колонка: сверху прокручиваемый текст, снизу закреплённые точки и кнопки.
        // Текст больше никогда не заезжает под «Пропустить» / «Далее»: если не влез - листается.
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(pages[0].color, 0xFF0B1026.toInt()))
        root.background = bg
        val dm = ctx.resources.displayMetrics
        val hDp = dm.heightPixels / d
        val wDp = dm.widthPixels / d
        // Невысокий экран (или крупный шрифт) - значок и текст меньше.
        val compact = hDp < 700 || ctx.resources.configuration.fontScale > 1.15f
        val iconDp = when {
            hDp < 600 -> 96
            compact -> 124
            else -> 170
        }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val side = if (wDp > 600) ((wDp - 520) / 2).toInt().coerceAtLeast(28) else 24
            setPadding(px(side), px(if (compact) 16 else 40), px(side), px(16))
        }
        val iconBox = FrameLayout(ctx)
        val iconText = TextView(ctx).apply {
            textSize = iconDp * 0.48f
            gravity = Gravity.CENTER
        }
        val iconAnim = ImageView(ctx).apply { visibility = View.GONE }
        iconBox.addView(iconText, FrameLayout.LayoutParams(px(iconDp), px(iconDp), Gravity.CENTER))
        iconBox.addView(iconAnim, FrameLayout.LayoutParams(px(iconDp), px(iconDp), Gravity.CENTER))
        content.addView(iconBox, LinearLayout.LayoutParams(px(iconDp + 20), px(iconDp + 20)).apply { topMargin = px(if (compact) 4 else 20) })
        val title = TextView(ctx).apply {
            textSize = if (compact) 22f else 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            paint.isFakeBoldText = true
            setPadding(0, px(if (compact) 10 else 18), 0, px(10))
        }
        content.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val text = TextView(ctx).apply {
            textSize = if (compact) 15f else 16f
            setTextColor(0xE6FFFFFF.toInt())
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.15f)
        }
        content.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scroll = android.widget.ScrollView(ctx).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bottom = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(20), px(8), px(20), px(if (compact) 16 else 28))
        }
        val dots = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        pages.forEach { _ ->
            dots.addView(View(ctx), LinearLayout.LayoutParams(px(8), px(8)).apply { setMargins(px(4), 0, px(4), 0) })
        }
        bottom.addView(dots)
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(18), 0, 0)
        }
        fun button(label: Int, filled: Boolean) = TextView(ctx).apply {
            setText(label)
            textSize = 16f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 11, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
            gravity = Gravity.CENTER
            setTextColor(if (filled) 0xFF1E1B3A.toInt() else Color.WHITE)
            paint.isFakeBoldText = filled
            setPadding(px(14), 0, px(14), 0)
            background = GradientDrawable().apply {
                cornerRadius = 26 * d
                if (filled) setColor(Color.WHITE) else setStroke(px(1), 0x80FFFFFF.toInt())
            }
        }
        val skip = button(R.string.ob_skip, false)
        val next = button(R.string.ob_next, true)
        buttons.addView(skip, LinearLayout.LayoutParams(0, px(50), 1f).apply { rightMargin = px(10) })
        buttons.addView(next, LinearLayout.LayoutParams(0, px(50), 1.4f))
        bottom.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Все анимации страниц качаем сразу и параллельно - к моменту листания они уже готовы.
        AnimatedEmoji.prefetchNow(ctx, pages.map { AnimatedEmoji.code(it.emoji) })
        // Пока анимация грузится, эмодзи «живой» сразу: покачивается и «дышит».
        val idle = android.animation.ObjectAnimator.ofPropertyValuesHolder(
            iconText,
            android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, -10 * d, 0f),
            android.animation.PropertyValuesHolder.ofFloat(View.ROTATION, -6f, 6f, -6f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f),
            android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1f),
        ).apply {
            duration = 1400
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }

        var index = 0
        fun render(i: Int, forward: Boolean) {
            val p = pages[i]
            val w = ctx.resources.displayMetrics.widthPixels.toFloat()
            content.animate().cancel()
            content.translationX = if (forward) w * 0.25f else -w * 0.25f
            content.alpha = 0f
            scroll.scrollTo(0, 0)
            content.animate().translationX(0f).alpha(1f).setDuration(320).setInterpolator(OvershootInterpolator(0.8f)).start()
            iconText.text = p.emoji
            iconText.visibility = View.VISIBLE
            AnimatedEmoji.stop(iconAnim)
            AnimatedEmoji.play(iconAnim, AnimatedEmoji.code(p.emoji), loop = -1) { iconText.visibility = View.INVISIBLE }
            iconBox.scaleX = 0.3f
            iconBox.scaleY = 0.3f
            iconBox.rotation = if (forward) -25f else 25f
            iconBox.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(80).setDuration(520)
                .setInterpolator(OvershootInterpolator(2.2f)).start()
            title.setText(p.title)
            text.setText(p.text)
            bg.colors = intArrayOf(p.color, 0xFF0B1026.toInt())
            for (k in 0 until dots.childCount) {
                val dot = dots.getChildAt(k)
                dot.background = GradientDrawable().apply {
                    cornerRadius = 4 * d
                    setColor(if (k == i) Color.WHITE else 0x55FFFFFF)
                }
                val lp = dot.layoutParams as LinearLayout.LayoutParams
                lp.width = if (k == i) px(22) else px(8)
                dot.layoutParams = lp
            }
            next.setText(if (i == pages.size - 1) R.string.ob_start else R.string.ob_next)
            skip.visibility = if (i == pages.size - 1) View.INVISIBLE else View.VISIBLE
        }
        fun go(delta: Int) {
            val target = index + delta
            if (target >= pages.size) {
                dialog.safeDismiss()
                return
            }
            if (target < 0) return
            index = target
            render(index, delta > 0)
        }
        next.setOnClickListener { if (Btn.guard()) go(1) }
        skip.setOnClickListener { dialog.safeDismiss() }
        val gestures = GestureDetector(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    val dx = e2.x - (e1?.x ?: e2.x)
                    if (kotlin.math.abs(dx) < px(60)) return false
                    go(if (dx < 0) 1 else -1)
                    return true
                }
            },
        )
        root.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
        // Свайп страниц работает и поверх прокручиваемого текста.
        scroll.setOnTouchListener { _, e ->
            gestures.onTouchEvent(e)
            false
        }
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            idle.cancel()
            markSeen(ctx)
            AnimatedEmoji.stop(iconAnim)
            onDone?.invoke()
        }
        dialog.show()
        render(0, true)
    }
}
