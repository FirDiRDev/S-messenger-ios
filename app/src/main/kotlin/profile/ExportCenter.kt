// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.profile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.LiteMode

/**
 * Меню экспорта: два режима на анимированных карточках и выбор, что именно выгрузить.
 *
 *  - «Полный перенос» - всё в один `.smsave` для другого телефона (ключи, база, настройки,
 *    по желанию - файлы из чатов);
 *  - «Архив для чтения» - ZIP с перепиской (HTML/TXT/JSON), файлами, контактами, подарками,
 *    скинами, монетами, статистикой, подключениями и группами (см. [DataExport]).
 */
object ExportCenter {
    private class Choice(val cat: DataExport.Cat, val emoji: String, val ru: String, val en: String)

    private val CHOICES = listOf(
        Choice(DataExport.Cat.MESSAGES, "💬", "Сообщения и тексты", "Messages & texts"),
        Choice(DataExport.Cat.MEDIA, "🖼", "Медиа и файлы", "Media & files"),
        Choice(DataExport.Cat.CONTACTS, "👤", "Контакты", "Contacts"),
        Choice(DataExport.Cat.GROUPS, "👥", "Группы и каналы", "Groups & channels"),
        Choice(DataExport.Cat.GIFTS, "🎁", "Подарки", "Gifts"),
        Choice(DataExport.Cat.SKINS, "🎨", "Скины и оформление", "Skins & looks"),
        Choice(DataExport.Cat.MONEY, "🪙", "Монеты и покупки", "Coins & purchases"),
        Choice(DataExport.Cat.STATS, "📊", "Статистика игр", "Game stats"),
        Choice(DataExport.Cat.CONNECTIONS, "🌐", "Подключения: узлы, прокси, VPN", "Connections: nodes, proxies, VPN"),
    )

    private fun t(ru: String, en: String) = if (java.util.Locale.getDefault().language == "ru") ru else en

    fun show(ctx: Context, onFull: (withMedia: Boolean) -> Unit, onData: (Set<DataExport.Cat>) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val lite = LiteMode.on
        val text = ContextCompat.getColor(ctx, R.color.textPrimary)
        val sheet = BottomSheetDialog(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        // Шапка: вращающееся кольцо вокруг коробки.
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Ring(ctx, lite), LinearLayout.LayoutParams(dp(56), dp(56)))
        head.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(ctx).apply {
                this.text = t("Экспорт данных", "Export data")
                textSize = 21f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(text)
            })
            addView(TextView(ctx).apply {
                this.text = t("Всё проверяется по контрольным суммам - без повреждений", "Everything is checked by checksums - no corruption")
                textSize = 12.5f
                setTextColor(0xFF8B85A8.toInt())
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        var mode = 0 // 0 - полный перенос, 1 - архив для чтения
        var withMedia = false
        val picked = linkedSetOf(DataExport.Cat.MESSAGES, DataExport.Cat.CONTACTS, DataExport.Cat.GROUPS, DataExport.Cat.GIFTS, DataExport.Cat.MONEY, DataExport.Cat.STATS)
        val media = FullBackup.mediaSize(ctx)
        val mediaText = Formatter.formatShortFileSize(ctx, media)

        fun card(emoji: String, title: String, sub: String, colors: IntArray): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            tag = colors
            addView(TextView(ctx).apply {
                this.text = emoji
                textSize = 26f
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(TextView(ctx).apply {
                    this.text = title
                    textSize = 16f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.WHITE)
                })
                addView(TextView(ctx).apply {
                    this.text = sub
                    textSize = 12.5f
                    setTextColor(0xDDFFFFFF.toInt())
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val full = card("📦", t("Полный перенос (.smsave)", "Full transfer (.smsave)"), t("Ключи, переписка, настройки - для нового телефона", "Keys, chats, settings - for a new phone"), intArrayOf(0xFF6D5DF5.toInt(), 0xFF4338CA.toInt()))
        val data = card("🗂", t("Архив для чтения (.zip)", "Readable archive (.zip)"), t("HTML, TXT и JSON - откроется на любом компьютере", "HTML, TXT and JSON - opens on any computer"), intArrayOf(0xFF0EA5E9.toInt(), 0xFF0F766E.toInt()))
        root.addView(full, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        root.addView(data, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })

        // Настройки полного переноса.
        val fullOpts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val mediaChip = chip(ctx, "🖼", t("С файлами из чатов · $mediaText", "With chat files · $mediaText"), false)
        fullOpts.addView(mediaChip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
        mediaChip.setOnClickListener {
            withMedia = !withMedia
            setChip(mediaChip, withMedia, lite)
        }
        // Что положить в архив для чтения.
        val grid = GridLayout(ctx).apply { columnCount = 2 }
        val chips = ArrayList<Pair<TextView, DataExport.Cat>>()
        CHOICES.forEachIndexed { i, c ->
            val label = if (c.cat == DataExport.Cat.MEDIA) t(c.ru, c.en) + " · " + mediaText else t(c.ru, c.en)
            val ch = chip(ctx, c.emoji, label, c.cat in picked)
            ch.setOnClickListener {
                if (!picked.remove(c.cat)) picked += c.cat
                setChip(ch, c.cat in picked, lite)
            }
            chips += ch to c.cat
            val lp = GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0
                height = dp(52)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            grid.addView(ch, lp)
        }
        val dataOpts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            addView(grid)
        }
        val optsBox = FrameLayout(ctx)
        optsBox.addView(fullOpts)
        optsBox.addView(dataOpts)
        root.addView(optsBox)

        val go = TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            this.text = t("Экспортировать", "Export")
        }
        root.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(16) })

        fun paint(v: View, colors: IntArray, on: Boolean) {
            v.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, if (on) colors else intArrayOf(0xFF3A3552.toInt(), 0xFF2A2540.toInt())).apply {
                cornerRadius = 18 * d
                if (on) setStroke(dp(2), 0x88FFFFFF.toInt())
            }
        }
        fun render(animate: Boolean) {
            paint(full, full.tag as IntArray, mode == 0)
            paint(data, data.tag as IntArray, mode == 1)
            val show = if (mode == 0) fullOpts else dataOpts
            val hide = if (mode == 0) dataOpts else fullOpts
            hide.visibility = View.GONE
            show.visibility = View.VISIBLE
            if (animate && !lite) {
                show.alpha = 0f
                show.translationY = 18 * d
                show.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
                if (show === dataOpts) chips.forEachIndexed { i, (ch, _) ->
                    ch.scaleX = 0.85f
                    ch.scaleY = 0.85f
                    ch.alpha = 0f
                    ch.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(40L + i * 28L).setDuration(240).setInterpolator(OvershootInterpolator(1.6f)).start()
                }
                val card = if (mode == 0) full else data
                card.animate().scaleX(1.03f).scaleY(1.03f).setDuration(110).withEndAction { card.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
            }
            go.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, (if (mode == 0) full.tag else data.tag) as IntArray).apply { cornerRadius = 26 * d }
        }
        full.setOnClickListener { if (mode != 0) { mode = 0; render(true) } }
        data.setOnClickListener { if (mode != 1) { mode = 1; render(true) } }
        render(false)
        setChip(mediaChip, false, true)
        chips.forEach { (ch, c) -> setChip(ch, c in picked, true) }

        var sent = false
        go.setOnClickListener {
            if (sent) return@setOnClickListener
            if (mode == 1 && picked.isEmpty()) {
                if (!lite) go.animate().translationX(10 * d).setDuration(60).withEndAction { go.animate().translationX(0f).setInterpolator(OvershootInterpolator(4f)).setDuration(220).start() }.start()
                return@setOnClickListener
            }
            sent = true
            sheet.dismiss()
            if (mode == 0) onFull(withMedia) else onData(picked.toSet())
        }

        val scroll = ScrollView(ctx).apply { addView(root) }
        sheet.setContentView(scroll)
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.show()
        // Появление лесенкой.
        if (!lite) {
            for (i in 0 until root.childCount) {
                val v = root.getChildAt(i)
                v.alpha = 0f
                v.translationY = 24 * d
                v.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * 55L).setDuration(320).setInterpolator(DecelerateInterpolator(1.6f)).start()
            }
        }
    }

    private fun chip(ctx: Context, emoji: String, label: String, on: Boolean): TextView = TextView(ctx).apply {
        text = "$emoji  $label"
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 2
        val d = resources.displayMetrics.density
        setPadding((12 * d).toInt(), 0, (10 * d).toInt(), 0)
        tag = on
    }

    private fun setChip(v: TextView, on: Boolean, instant: Boolean) {
        val d = v.resources.displayMetrics.density
        v.background = GradientDrawable().apply {
            cornerRadius = 14 * d
            if (on) {
                colors = intArrayOf(0xFF7C6CF6.toInt(), 0xFF5B4BDB.toInt())
                orientation = GradientDrawable.Orientation.TL_BR
            } else {
                setColor(0x1F8B85A8)
                setStroke((1 * d).toInt(), 0x558B85A8)
            }
        }
        v.setTextColor(if (on) Color.WHITE else ContextCompat.getColor(v.context, R.color.textPrimary))
        if (!instant) {
            v.animate().cancel()
            v.scaleX = 0.92f
            v.scaleY = 0.92f
            v.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
    }

    /** Кольцо с бегущим градиентом и коробкой в центре. */
    private class Ring(ctx: Context, val lite: Boolean) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t0 = SystemClock.uptimeMillis()
        private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val d = resources.displayMetrics.density
            val t = if (lite) 0f else (SystemClock.uptimeMillis() - t0) / 1000f
            c.save()
            c.rotate(t * 120f, w / 2, h / 2)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4 * d
            p.strokeCap = Paint.Cap.ROUND
            p.shader = SweepGradient(w / 2, h / 2, intArrayOf(0x00A78BFA, 0xFFA78BFA.toInt(), 0xFF38BDF8.toInt(), 0x00A78BFA), floatArrayOf(0f, 0.5f, 0.85f, 1f))
            c.drawArc(RectF(4 * d, 4 * d, w - 4 * d, h - 4 * d), 0f, 360f, false, p)
            c.restore()
            p.shader = LinearGradient(0f, 0f, w, h, 0x334F46E5, 0x220EA5E9, Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL
            c.drawCircle(w / 2, h / 2, w / 2 - 8 * d, p)
            p.shader = null
            val bob = if (lite) 0f else kotlin.math.sin(t * 3f) * 2 * d
            box.textSize = h * 0.42f
            c.drawText("📦", w / 2, h / 2 + box.textSize * 0.35f + bob, box)
            if (!lite) postInvalidateOnAnimation()
        }
    }
}
