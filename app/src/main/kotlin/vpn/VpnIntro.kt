// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.vpn

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ai.t

/**
 * Одноразовое знакомство со встроенным VPN при входе: что он умеет и как за три шага
 * включить его для всего телефона прямо из мессенджера. Показывается один раз, когда на
 * экране нет других окон (знакомство, «что нового», советы).
 */
object VpnIntro {
    private const val PREFS = "vpn_intro"
    private const val KEY = "shown_4130"

    fun shouldShow(ctx: Context) = !ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    private fun markShown(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()

    /** Показать один раз, подождав, пока закроются другие окна (до ~40 с). */
    fun showOnceWhenFree(activity: Activity, delayMs: Long = 7000, tries: Int = 8) {
        if (!shouldShow(activity)) return
        activity.window.decorView.postDelayed({
            if (activity.isFinishing || activity.isDestroyed || !shouldShow(activity)) return@postDelayed
            // Окно экрана без фокуса - значит, поверх открыт диалог; подождём.
            if (!activity.hasWindowFocus()) {
                if (tries > 0) showOnceWhenFree(activity, 5000, tries - 1)
                return@postDelayed
            }
            runCatching { show(activity) }
        }, delayMs)
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun show(ctx: Context) {
        markShown(ctx)
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 8))
        }

        // Шапка: светящийся шар VPN «включён» и заголовок.
        val orb = PowerOrb(ctx).apply { state = PowerOrb.State.On }
        root.addView(orb, LinearLayout.LayoutParams(dp(ctx, 96), dp(ctx, 96)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(
            TextView(ctx).apply {
                text = t("Встроенный VPN - уже в мессенджере", "Built-in VPN - right in the messenger")
                textSize = 20f
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setTextColor(primary)
                setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            },
        )
        root.addView(
            TextView(ctx).apply {
                text = t(
                    "Бесплатно, без регистрации и без отдельных приложений. Защищает не только чаты - а весь телефон.",
                    "Free, no sign-up and no extra apps. It protects not just chats - the whole phone.",
                )
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(secondary)
                setPadding(0, 0, 0, dp(ctx, 12))
            },
        )

        fun feature(emoji: String, color: Int, title: String, sub: String) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(ctx, 5), 0, dp(ctx, 5))
            }
            row.addView(VpnUi.badge(ctx, emoji, color, 38))
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(ctx, 12), 0, 0, 0)
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 15f; paint.isFakeBoldText = true; setTextColor(primary) })
            col.addView(TextView(ctx).apply { text = sub; textSize = 13f; setTextColor(secondary) })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(row)
        }
        feature(
            "⚡", VpnUi.AMBER, t("Сам выбирает быстрый сервер", "Picks a fast server itself"),
            t("Проверяет пинг сотен серверов и переключается, если сервер упал.", "Pings hundreds of servers and switches if one goes down."),
        )
        feature(
            "📱", VpnUi.GREEN, t("Для всего телефона", "For the whole phone"),
            t("Браузер, видео, соцсети и игры - весь трафик идёт через VPN.", "Browser, video, social apps and games - all traffic goes through VPN."),
        )
        feature(
            "🔒", VpnUi.ACCENT, t("Чаты под защитой", "Chats stay protected"),
            t("Мессенджер соединяется даже там, где связь режут.", "The messenger connects even where networks are filtered."),
        )

        // Как включить - три шага.
        val steps = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 12))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 16).toFloat()
                setColor((VpnUi.GREEN and 0x00FFFFFF) or 0x1C000000)
            }
        }
        steps.addView(
            TextView(ctx).apply {
                text = t("Как включить для всего телефона", "How to turn it on for the whole phone")
                textSize = 14f
                paint.isFakeBoldText = true
                setTextColor(primary)
                setPadding(0, 0, 0, dp(ctx, 4))
            },
        )
        listOf(
            t("Удерживайте щит 🛡 вверху списка чатов (или Настройки → VPN).", "Long-press the 🛡 shield above the chat list (or Settings → VPN)."),
            t("Выберите «📱 Весь телефон».", "Choose “📱 Whole phone”."),
            t("Нажмите большую кнопку и разрешите подключение в окне системы. Значок ключа 🔑 вверху экрана - телефон защищён.", "Tap the big button and allow the connection in the system prompt. A 🔑 key icon in the status bar means the phone is protected."),
        ).forEachIndexed { i, s ->
            steps.addView(
                TextView(ctx).apply {
                    text = "${i + 1}. $s"
                    textSize = 13.5f
                    setTextColor(primary)
                    setPadding(0, dp(ctx, 3), 0, dp(ctx, 3))
                },
            )
        }
        steps.addView(
            TextView(ctx).apply {
                text = t("Короткое нажатие на щит 🛡 - включить или выключить VPN в одно касание.", "A short tap on the 🛡 shield turns VPN on or off in one touch.")
                textSize = 12.5f
                setTextColor(secondary)
                setPadding(0, dp(ctx, 6), 0, 0)
            },
        )
        root.addView(steps, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 10) })

        val dialog = AlertDialog.Builder(ctx)
            .setView(ScrollView(ctx).apply { addView(root) })
            .create()

        val btn = TextView(ctx).apply {
            text = t("📱 Включить для всего телефона", "📱 Turn on for the whole phone")
            textSize = 15.5f
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(ctx, 12), dp(ctx, 13), dp(ctx, 12), dp(ctx, 13))
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF00C2A8.toInt(), 0xFF0E9F6E.toInt())).apply {
                cornerRadius = dp(ctx, 16).toFloat()
            }
            com.stoyko.smessenger.ui.Kinetics.squish(this, 0.96f)
            setOnClickListener {
                dialog.dismiss()
                runCatching { VpnStore(ctx).fullTunnel = true }
                runCatching { VpnScreen(ctx).show() }
            }
        }
        root.addView(btn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 16) })
        root.addView(
            TextView(ctx).apply {
                text = t("Позже", "Later")
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(secondary)
                setPadding(0, dp(ctx, 12), 0, dp(ctx, 6))
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        com.stoyko.smessenger.ui.DialogTracker.add(dialog).show()
        if (com.stoyko.smessenger.ui.Kinetics.enabled) {
            orb.scaleX = 0.5f
            orb.scaleY = 0.5f
            orb.alpha = 0f
            orb.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.8f)).start()
        }
        orb.visibility = View.VISIBLE
    }
}
