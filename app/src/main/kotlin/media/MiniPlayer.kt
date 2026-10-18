// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.GlassFrameLayout
import com.stoyko.smessenger.ui.chat.VoicePlayer

/**
 * Мини-плеер: голосовое или «кружок» продолжает играть, когда вы перешли
 * в другой чат, группу или канал. Полоса под шапкой экрана: ▶/⏸, от кого, скорость, ✕.
 */
@SuppressLint("ViewConstructor")
class MiniPlayer(context: Context, private val host: FrameLayout) : GlassFrameLayout(context) {
    companion object {
        private var anchorRef: WeakReference<View>? = null

        /** Шапка текущего экрана: плеер показывается сразу под ней. */
        fun setAnchor(view: View?) {
            anchorRef = view?.let { WeakReference(it) }
            current?.get()?.reposition()
        }

        private var current: WeakReference<MiniPlayer>? = null
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val play = ImageView(context)
    private val title = TextView(context)
    private val speedView = TextView(context)
    private val close = ImageView(context)
    private val progress = View(context)
    private val listener: () -> Unit = { update() }
    private val loc = IntArray(2)
    private val hostLoc = IntArray(2)

    init {
        tintColor = 0xB35B4BDB.toInt()
        fallbackColor = 0xF05B4BDB.toInt()
        cornerRadius = dp(16).toFloat()
        edgeTop = true
        elevation = dp(6).toFloat()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        play.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        play.setPadding(dp(8), dp(8), dp(8), dp(8))
        play.setOnClickListener {
            if (VoicePlayer.isPlaying) VoicePlayer.pause() else VoicePlayer.resume()
        }
        row.addView(play, LinearLayout.LayoutParams(dp(40), dp(40)))
        title.setTextColor(Color.WHITE)
        title.textSize = 14f
        title.maxLines = 1
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        speedView.setTextColor(Color.WHITE)
        speedView.textSize = 13f
        speedView.setPadding(dp(10), dp(6), dp(10), dp(6))
        speedView.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(0x33FFFFFF)
        }
        speedView.setOnClickListener { VoicePlayer.cycleSpeed() }
        row.addView(speedView)
        close.setImageResource(R.drawable.ic_close)
        close.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        close.setPadding(dp(10), dp(10), dp(10), dp(10))
        close.setOnClickListener { VoicePlayer.stop() }
        row.addView(close, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
        progress.setBackgroundColor(0xFF00C2A8.toInt())
        progress.pivotX = 0f
        addView(progress, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2), Gravity.BOTTOM))
        visibility = GONE
    }

    fun install() {
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.TOP)
        lp.leftMargin = dp(8)
        lp.rightMargin = dp(8)
        host.addView(this, lp)
        blurSource = host.getChildAt(0)
        VoicePlayer.addListener(listener)
        current = WeakReference(this)
        update()
    }

    fun uninstall() {
        VoicePlayer.removeListener(listener)
        host.removeView(this)
    }

    fun reposition() {
        val anchor = anchorRef?.get()
        host.getLocationInWindow(hostLoc)
        val y = if (anchor != null && anchor.isAttachedToWindow && anchor.isShown) {
            anchor.getLocationInWindow(loc)
            loc[1] + anchor.height - hostLoc[1] + dp(4)
        } else {
            val res = resources.getIdentifier("status_bar_height", "dimen", "android")
            (if (res > 0) resources.getDimensionPixelSize(res) else dp(24)) + dp(60)
        }
        translationY = y.toFloat()
    }

    private fun update() {
        val key = VoicePlayer.currentKey
        if (key == null) {
            if (visibility == VISIBLE) {
                animate().alpha(0f).translationYBy(-dp(12).toFloat()).setDuration(160).withEndAction {
                    visibility = GONE
                    alpha = 1f
                }.start()
            }
            return
        }
        if (visibility != VISIBLE) {
            reposition()
            visibility = VISIBLE
            alpha = 0f
            val y = translationY
            translationY = y - dp(12)
            animate().alpha(1f).translationY(y).setDuration(180).start()
        }
        play.setImageResource(if (VoicePlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val kind = context.getString(if (VoicePlayer.isVideo) R.string.video_message else R.string.voice_message)
        title.text = if (VoicePlayer.title.isNotEmpty()) "$kind · ${VoicePlayer.title}" else kind
        val s = VoicePlayer.speed
        speedView.text = if (s == 1f) "1x" else if (s == 1.5f) "1.5x" else "2x"
        progress.scaleX = VoicePlayer.progress(key)
    }
}
