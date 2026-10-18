// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.media.MusicPlayer
import com.stoyko.smessenger.ui.fitSystemBars

/**
 * Музыкальный плеер: обложка-«пластинка», время слева/справа, перемотка ползунком,
 * ⏮ / большая ⏯ / ⏭, ±10 с, скорость, повтор, сохранить, стоп. Музыка продолжает играть
 * в фоне - управление есть в шторке уведомлений и на экране блокировки.
 */
class AudioPlayerDialog(private val context: Context, private val uri: Uri, private val name: String) {
    private val d = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * d).toInt()

    @Suppress("DEPRECATION")
    private val accent = context.resources.getColor(R.color.colorPrimary)

    @Suppress("DEPRECATION")
    private val textPrimary = context.resources.getColor(R.color.textPrimary)

    @Suppress("DEPRECATION")
    private val textSecondary = context.resources.getColor(R.color.textSecondary)

    // Цвета плеера - под сезонное оформление (осень / зима) или фирменные.
    private val theme = com.stoyko.smessenger.ui.player.PlayerTheme.colors(context)
    private val soft = (theme[0] and 0x00FFFFFF) or 0x22000000

    private fun circleButton(sizeDp: Int, filled: Boolean, content: View, click: () -> Unit): FrameLayout =
        FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (filled) {
                    colors = intArrayOf(theme[0], theme[1])
                    orientation = GradientDrawable.Orientation.TL_BR
                } else {
                    setColor(soft)
                }
            }
            elevation = if (filled) 8 * d else 0f
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(px(sizeDp), px(sizeDp)).apply { setMargins(px(8), 0, px(8), 0) }
            setOnClickListener {
                animate().scaleX(0.86f).scaleY(0.86f).setDuration(70).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
                click()
            }
        }

    private fun glyph(text: String, size: Float, color: Int = textPrimary) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun chip(text: String, click: (TextView) -> Unit) = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(textPrimary)
        gravity = Gravity.CENTER
        setPadding(px(12), px(6), px(12), px(6))
        background = GradientDrawable().apply {
            cornerRadius = 16 * d
            setColor(soft)
        }
        setOnClickListener {
            animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
            click(this)
        }
    }

    private fun fmt(ms: Int): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    fun show() {
        MusicPlayer.play(context, uri, name)
        val sheet = BottomSheetDialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(12), px(24), px(16))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(28 * d, 28 * d, 28 * d, 28 * d, 0f, 0f, 0f, 0f)
                @Suppress("DEPRECATION")
                setColor(context.resources.getColor(R.color.colorSurface))
            }
        }
        // Ручка шторки
        root.addView(
            View(context).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 3 * d
                    setColor(0x40888888)
                }
            },
            LinearLayout.LayoutParams(px(40), px(5)).apply { bottomMargin = px(14) },
        )

        // Обложка-пластинка: крутится, пока играет.
        val disc = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(theme[0], theme[1], 0xFF111827.toInt())).apply {
                shape = GradientDrawable.OVAL
            }
            elevation = 12 * d
            addView(
                View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x33000000)
                        setStroke(px(2), 0x22FFFFFF)
                    }
                },
                FrameLayout.LayoutParams(px(120), px(120), Gravity.CENTER),
            )
            addView(glyph("♪", 44f, Color.WHITE), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        root.addView(disc, LinearLayout.LayoutParams(px(190), px(190)))
        val spin = ObjectAnimator.ofFloat(disc, View.ROTATION, 0f, 360f).apply {
            duration = 9000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        val title = TextView(context).apply {
            textSize = 18f
            setTextColor(textPrimary)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, px(18), 0, px(2))
        }
        val subtitle = TextView(context).apply {
            textSize = 13f
            setTextColor(textSecondary)
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(subtitle)

        val seek = SeekBar(context).apply {
            setPadding(px(4), px(14), px(4), px(4))
            progressTintList = android.content.res.ColorStateList.valueOf(theme[0])
            thumbTintList = android.content.res.ColorStateList.valueOf(theme[1])
        }
        root.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val times = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val cur = TextView(context).apply {
            textSize = 12f
            setTextColor(textSecondary)
        }
        val left = TextView(context).apply {
            textSize = 12f
            setTextColor(textSecondary)
            gravity = Gravity.END
        }
        times.addView(cur, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        times.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(times, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = px(12)
            rightMargin = px(12)
        })

        // Основные кнопки
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, px(14), 0, px(6))
        }
        val playIcon = ImageView(context).apply {
            setColorFilter(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(px(34), px(34))
        }
        controls.addView(circleButton(44, false, glyph("−10", 12f)) { MusicPlayer.seekBy(-10_000) })
        controls.addView(circleButton(52, false, glyph("⏮", 20f)) { MusicPlayer.prev() })
        controls.addView(circleButton(72, true, playIcon) { MusicPlayer.toggle() })
        controls.addView(circleButton(52, false, glyph("⏭", 20f)) { MusicPlayer.next() })
        controls.addView(circleButton(44, false, glyph("+10", 12f)) { MusicPlayer.seekBy(10_000) })
        root.addView(controls)

        // Дополнительно: скорость, повтор, сохранить, стоп
        val extras = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, px(8), 0, 0)
        }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(px(5), 0, px(5), 0) }
        val speed = chip(speedLabel()) { it.text = MusicPlayer.cycleSpeed().let { s -> label(s) } }
        val repeat = chip("🔁 " + context.getString(R.string.music_repeat)) { MusicPlayer.toggleRepeat() }
        extras.addView(speed, lp())
        extras.addView(repeat, lp())
        // Из чата с запретом пересылки и сохранения - без «Сохранить».
        if (!com.stoyko.smessenger.chat.ScreenGuard.blocked) {
            extras.addView(chip("⬇ " + context.getString(R.string.music_save)) { MusicPlayer.current?.let { (u, n) -> FileTypes.saveToPublic(context, u, n) } }, lp())
        }
        extras.addView(chip("⏹") {
            MusicPlayer.stop()
            sheet.dismiss()
        }, lp())
        root.addView(extras)

        var userSeeking = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) cur.text = fmt(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
                userSeeking = false
                MusicPlayer.seekTo(seek.progress)
            }
        })

        fun render() {
            val (_, n) = MusicPlayer.current ?: (uri to name)
            title.text = n.substringBeforeLast('.')
            subtitle.text = if (MusicPlayer.queue.size > 1) {
                context.getString(R.string.music_track_of, MusicPlayer.index + 1, MusicPlayer.queue.size)
            } else {
                context.getString(R.string.music_from_chat)
            }
            val playing = MusicPlayer.isPlaying
            playIcon.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            if (playing && !spin.isStarted) spin.start() else if (!playing && spin.isStarted) spin.pause()
            if (playing && spin.isPaused) spin.resume()
            repeat.alpha = if (MusicPlayer.repeat) 1f else 0.5f
            speed.text = speedLabel()
            val dur = MusicPlayer.duration
            seek.max = dur.coerceAtLeast(1)
            if (!userSeeking) {
                seek.progress = MusicPlayer.position
                cur.text = fmt(MusicPlayer.position)
            }
            left.text = if (dur > 0) "-" + fmt(dur - MusicPlayer.position) else "--:--"
        }

        val main = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                render()
                main.postDelayed(this, 250)
            }
        }
        val listener: () -> Unit = { main.post { render() } }
        MusicPlayer.addListener(listener)
        root.fitSystemBars(top = false)
        sheet.setContentView(root)
        (root.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.setOnDismissListener {
            main.removeCallbacksAndMessages(null)
            MusicPlayer.removeListener(listener)
            spin.cancel()
            if (MusicPlayer.isPlaying) android.widget.Toast.makeText(context, R.string.music_background, android.widget.Toast.LENGTH_SHORT).show()
        }
        com.stoyko.smessenger.chat.ScreenGuard.secureIfActive(sheet.window)
        sheet.show()
        disc.scaleX = 0.6f
        disc.scaleY = 0.6f
        disc.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2f)).start()
        main.post(tick)
    }

    private fun label(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"
    private fun speedLabel() = "⚡ " + label(MusicPlayer.speed)
}
