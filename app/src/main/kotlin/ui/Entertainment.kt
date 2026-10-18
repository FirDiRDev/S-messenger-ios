// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.vpn.VpnState

/**
 * «Развлечения» - одним пунктом меню: YouTube Shorts, YouTube и Pinterest во встроенном
 * плеере/браузере. Многие из них без VPN не открываются или тормозят - поэтому сверху
 * подсказка и кнопка «Включить VPN» (последний выбранный режим, в т.ч. обход DPI без сервера).
 * Встроенный браузер сам ходит через VPN приложения, а если VPN включён на телефоне -
 * через него.
 */
object Entertainment {
    fun show(ctx: Context, enableVpn: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val cText = ContextCompat.getColor(ctx, R.color.textPrimary)
        val cSub = ContextCompat.getColor(ctx, R.color.textSecondary)
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_sheet)
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }
        root.addView(
            View(ctx).apply { setBackgroundResource(R.drawable.bg_handle); alpha = 0.35f },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) },
        )
        root.addView(
            TextView(ctx).apply {
                setText(R.string.fun_title)
                textSize = 20f
                paint.isFakeBoldText = true
                setTextColor(cText)
            },
        )
        root.addView(
            TextView(ctx).apply {
                setText(R.string.fun_subtitle)
                textSize = 13f
                setTextColor(cSub)
                setPadding(0, dp(2), 0, dp(10))
            },
        )
        // Подсказка про VPN: выключен - предлагаем включить одним нажатием.
        if (VpnState.status != VpnState.Status.On && !systemVpnOn(ctx)) {
            val banner = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = 16 * d
                    setColor(0x2210B981)
                    setStroke(dp(1), 0x6610B981)
                }
            }
            banner.addView(
                TextView(ctx).apply {
                    setText(R.string.fun_vpn_hint)
                    textSize = 13f
                    setTextColor(cText)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val on = Btn.primary(ctx, ctx.getString(R.string.fun_vpn_on), intArrayOf(0xFF22C55E.toInt(), 0xFF0E9F6E.toInt())) {
                enableVpn()
                banner.animate().alpha(0f).setDuration(200).withEndAction { banner.visibility = View.GONE }.start()
            }
            on.textSize = 13f
            on.maxLines = 2
            banner.addView(on, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
            on.minHeight = dp(40)
            root.addView(banner, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
        }
        class Item(val emoji: String, val title: Int, val sub: Int, val c1: Int, val c2: Int, val open: () -> Unit)
        val items = listOf(
            Item("▶️", R.string.youtube_shorts, R.string.fun_shorts_sub, 0xFFFF4D4D.toInt(), 0xFFB91C1C.toInt()) {
                com.stoyko.smessenger.media.YouTube.browse(ctx, shorts = true)
            },
            Item("📺", R.string.youtube, R.string.fun_youtube_sub, 0xFFF97316.toInt(), 0xFFDC2626.toInt()) {
                com.stoyko.smessenger.media.YouTube.browse(ctx, shorts = false)
            },
            Item("📌", R.string.pinterest_title, R.string.fun_pinterest_sub, 0xFFE11D48.toInt(), 0xFF9F1239.toInt()) {
                com.stoyko.smessenger.browser.Browser.open(ctx, "https://www.pinterest.com/")
            },
            // ТВ-каналы (iptv-org) и фильмы (archive.org) - во встроенном плеере.
            Item("📡", R.string.tv_title, R.string.fun_tv_sub, 0xFF0EA5E9.toInt(), 0xFF1D4ED8.toInt()) {
                Iptv.show(ctx)
            },
            Item("🎬", R.string.movies_title, R.string.fun_movies_sub, 0xFFA855F7.toInt(), 0xFF6D28D9.toInt()) {
                Movies.show(ctx)
            },
            // Скачать видео YouTube / Pinterest в галерею.
            Item("⬇️", R.string.vdl_title, R.string.fun_vdl_sub, 0xFF22C55E.toInt(), 0xFF0E7490.toInt()) {
                com.stoyko.smessenger.media.VideoDownloader.show(ctx)
            },
        )
        items.forEachIndexed { i, it ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                minimumHeight = dp(76)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(it.c1, it.c2)).apply { cornerRadius = 18 * d }
                isClickable = true
                setOnClickListener { _ ->
                    sheet.dismiss()
                    it.open()
                }
            }
            card.addView(
                TextView(ctx).apply {
                    text = it.emoji
                    textSize = 26f
                    gravity = Gravity.CENTER
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x33FFFFFF)
                    }
                },
                LinearLayout.LayoutParams(dp(52), dp(52)),
            )
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), 0, 0, 0)
            }
            texts.addView(TextView(ctx).apply { setText(it.title); textSize = 17f; paint.isFakeBoldText = true; setTextColor(Color.WHITE) })
            texts.addView(TextView(ctx).apply { setText(it.sub); textSize = 12f; setTextColor(0xDDFFFFFF.toInt()) })
            card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(TextView(ctx).apply { text = "›"; textSize = 26f; setTextColor(Color.WHITE) })
            Kinetics.squish(card, 0.96f)
            root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = if (i == 0) 0 else dp(10) })
            Kinetics.springIn(card, delay = 60L * i)
        }
        // Прокрутка: на вытянутых экранах (TECNO, Infinix, 20:9) и с крупным шрифтом пункты
        // «ТВ» и «Кино» раньше сжимались по высоте и не нажимались.
        val scroll = androidx.core.widget.NestedScrollView(ctx).apply {
            isFillViewport = true
            setBackgroundResource(R.drawable.bg_sheet)
            addView(root.apply { background = null }, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        sheet.setContentView(scroll)
        (scroll.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.setOnShowListener {
            sheet.behavior.skipCollapsed = true
            sheet.behavior.peekHeight = ctx.resources.displayMetrics.heightPixels
            sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
        sheet.show()
    }

    /** VPN включён на телефоне другим приложением. */
    private fun systemVpnOn(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        if (android.os.Build.VERSION.SDK_INT < 23) return false
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
    }.getOrDefault(false)
}
