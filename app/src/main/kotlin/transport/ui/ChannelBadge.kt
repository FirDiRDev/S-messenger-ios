// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.transport.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import com.stoyko.smessenger.R
import com.stoyko.smessenger.transport.Channel
import com.stoyko.smessenger.transport.TransportManager

/**
 * Значок текущего канала в шапке (🌐 Tox / 🧅 Tor / 📶 Wi-Fi / 🔵 Bluetooth / ⚪ нет связи).
 * Сам подписывается на изменения, пока виден, и отписывается при уходе с экрана.
 * Нажатие - «Каналы связи».
 */
class ChannelBadge(ctx: Context, private val friend: String? = null, private val compact: Boolean = false) : TextView(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bg = GradientDrawable().apply { cornerRadius = 12 * d }
    private val listener: () -> Unit = { update() }
    private var shown: Channel? = null

    init {
        if (!compact) id = R.id.transportBadge
        textSize = if (compact) 9f else 13f
        gravity = Gravity.CENTER
        includeFontPadding = false
        val ph = if (compact) 0 else (7 * d).toInt()
        val pv = if (compact) 0 else (3 * d).toInt()
        setPadding(ph, pv, ph, pv)
        if (compact) bg.shape = GradientDrawable.OVAL
        background = bg
        setOnClickListener { ChannelsScreen(context).show() }
        com.stoyko.smessenger.ui.Kinetics.squish(this, 0.85f)
        update()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        TransportManager.listen(listener)
        update()
    }

    override fun onDetachedFromWindow() {
        TransportManager.unlisten(listener)
        super.onDetachedFromWindow()
    }

    private fun update() {
        // В чате: есть Bluetooth-сеанс именно с этим другом - показываем Bluetooth.
        val viaBt = friend != null && com.stoyko.smessenger.transport.bt.BtLink.connectedFriends().any { it.equals(friend, ignoreCase = true) }
        val ch = if (viaBt) Channel.BLUETOOTH else TransportManager.channel()
        val live = viaBt || TransportManager.connected
        if (ch == shown && (alpha == 1f) == live) return
        shown = ch
        text = ch.emoji
        // Нет связи по каналу - полупрозрачный (идёт подключение).
        alpha = if (live) 1f else 0.6f
        bg.setColor(if (compact) 0xFFFFFFFF.toInt() else 0x33FFFFFF)
        val name = context.getString(
            when (ch) {
                Channel.TOX -> R.string.transport_ch_tox
                Channel.TOR -> R.string.transport_ch_tor
                Channel.WIFI -> R.string.transport_ch_wifi
                Channel.BLUETOOTH -> R.string.transport_ch_bt
                Channel.NONE -> R.string.transport_ch_none
            },
        )
        contentDescription = context.getString(R.string.transport_badge_desc, name)
    }

    companion object {
        /**
         * Маленький значок в углу аватара собеседника (шапка чата): в шапке чата справа меню,
         * а слева - имя на всю ширину, поэтому значок - на аватаре, как точка «в сети».
         */
        fun attachToAvatar(avatar: View?, friendKey: String) {
            val parent = avatar?.parent as? android.widget.RelativeLayout ?: return
            if (parent.findViewWithTag<View>("transportBadge") != null) return
            runCatching {
                val d = avatar.resources.displayMetrics.density
                val badge = ChannelBadge(avatar.context, friendKey, compact = true).apply { tag = "transportBadge" }
                val lp = android.widget.RelativeLayout.LayoutParams((16 * d).toInt(), (16 * d).toInt()).apply {
                    addRule(android.widget.RelativeLayout.ALIGN_START, avatar.id)
                    addRule(android.widget.RelativeLayout.ALIGN_BOTTOM, avatar.id)
                    marginStart = (-3 * d).toInt()
                    bottomMargin = (-3 * d).toInt()
                }
                parent.addView(badge, lp)
            }
        }

        /** Добавить значок в шапку (один раз; повторный вызов ничего не делает). */
        fun attach(toolbar: Toolbar?) {
            if (toolbar == null || toolbar.findViewById<View>(R.id.transportBadge) != null) return
            runCatching {
                val d = toolbar.resources.displayMetrics.density
                toolbar.addView(
                    ChannelBadge(toolbar.context),
                    Toolbar.LayoutParams(Toolbar.LayoutParams.WRAP_CONTENT, (26 * d).toInt(), Gravity.END or Gravity.CENTER_VERTICAL)
                        .apply { marginEnd = (4 * d).toInt() },
                )
            }
        }
    }
}
