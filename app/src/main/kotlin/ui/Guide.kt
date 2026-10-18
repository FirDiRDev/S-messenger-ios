// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

private fun t(ru: String, en: String) = com.stoyko.smessenger.ai.t(ru, en)

/**
 * Обучающие страницы (как «Как вести канал»): крупный значок, заголовок, текст, точки,
 * «Назад/Далее», свайп. Используется для обучения VPN и других разделов.
 */
object Guide {
    /** [scene] - живая анимированная картинка вместо эмодзи (например, схема подбора сервера). */
    class Page(val emoji: String, val title: String, val body: String, val action: String? = null, val scene: ((Context) -> View)? = null)

    fun show(ctx: Context, list: List<Page>, onAction: (() -> Unit)? = null) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        var index = 0
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        val root = FrameLayout(ctx).apply { setBackgroundColor(0xB3000000.toInt()) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A2460.toInt(), 0xFF1B1840.toInt())).apply {
                cornerRadius = dp(26).toFloat()
            }
            elevation = dp(12).toFloat()
        }
        val emoji = TextView(ctx).apply {
            textSize = 54f
            gravity = Gravity.CENTER
        }
        val title = TextView(ctx).apply {
            textSize = 21f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(10))
        }
        val body = TextView(ctx).apply {
            textSize = 15f
            setTextColor(0xE6FFFFFF.toInt())
            setLineSpacing(0f, 1.18f)
        }
        val bodyScroll = ScrollView(ctx).apply { addView(body) }
        val action = com.stoyko.smessenger.ui.Btn.primary(ctx, "", intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt())) {}
        val dots = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
        }
        val back = com.stoyko.smessenger.ui.Btn.tonal(ctx, t("Назад", "Back")) {}
        val next = com.stoyko.smessenger.ui.Btn.primary(ctx, t("Далее", "Next")) {}
        card.addView(emoji)
        val sceneBox = FrameLayout(ctx)
        card.addView(sceneBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)))
        card.addView(title)
        card.addView(bodyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        card.addView(action, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(10) })
        card.addView(dots)
        card.addView(com.stoyko.smessenger.ui.Btn.row(ctx, listOf(back, next), height = 48))
        root.addView(
            card,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.72f).toInt(), Gravity.CENTER).apply {
                setMargins(dp(18), 0, dp(18), 0)
            },
        )

        fun render(dir: Int) {
            val p = list[index]
            emoji.text = p.emoji
            // Анимированная схема - вместо большого эмодзи.
            sceneBox.removeAllViews()
            val scene = p.scene
            if (scene != null) {
                sceneBox.visibility = View.VISIBLE
                emoji.visibility = View.GONE
                sceneBox.addView(scene(ctx), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                sceneBox.visibility = View.GONE
                emoji.visibility = View.VISIBLE
            }
            title.text = p.title
            body.text = p.body
            bodyScroll.scrollTo(0, 0)
            action.visibility = if (p.action != null && onAction != null) View.VISIBLE else View.GONE
            action.text = p.action.orEmpty()
            back.visibility = if (index == 0) View.INVISIBLE else View.VISIBLE
            next.text = if (index == list.lastIndex) t("Готово", "Done") else t("Далее", "Next")
            dots.removeAllViews()
            for (i in list.indices) {
                dots.addView(
                    View(ctx).apply {
                        background = GradientDrawable().apply {
                            cornerRadius = dp(4).toFloat()
                            setColor(if (i == index) 0xFF7B6CF6.toInt() else 0x55FFFFFF)
                        }
                    },
                    LinearLayout.LayoutParams(if (i == index) dp(20) else dp(8), dp(8)).apply { marginStart = dp(3); marginEnd = dp(3) },
                )
            }
            if (dir != 0 && com.stoyko.smessenger.ui.Kinetics.enabled) {
                for (v in listOf(title, bodyScroll)) {
                    v.alpha = 0f
                    v.translationX = dp(40) * dir.toFloat()
                    v.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            emoji.scaleX = 0.4f
            emoji.scaleY = 0.4f
            emoji.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(3f)).start()
        }

        fun go(delta: Int) {
            val n = index + delta
            if (n < 0) return
            if (n > list.lastIndex) {
                dialog.safeDismiss()
                return
            }
            index = n
            render(delta)
        }
        back.setOnClickListener { go(-1) }
        next.setOnClickListener { go(1) }
        action.setOnClickListener {
            dialog.safeDismiss()
            onAction?.invoke()
        }
        // Свайп влево/вправо листает страницы.
        val gd = GestureDetector(
            ctx,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (abs(vx) < abs(vy) || abs(vx) < 600) return false
                    go(if (vx < 0) 1 else -1)
                    return true
                }
            },
        )
        card.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
        bodyScroll.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            false
        }
        dialog.setContentView(root)
        render(0)
        dialog.show()
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.4f)).start()
    }
}
