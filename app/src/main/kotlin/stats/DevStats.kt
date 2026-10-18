// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ai.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Экран «📊 Статистика» - только для аккаунта разработчика: в сети сейчас, не в сети,
 * сколько устройств поставили приложение, создали профиль, скачали APK, активность за неделю,
 * версии и типы телефонов. «В сети» обновляется само каждые 30 с, пока окно открыто.
 */
object DevStats {
    private val io = Executors.newSingleThreadExecutor { Thread(it, "dev-stats").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun fmt(n: Long?): String = when {
        n == null -> "—"
        n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000.0)
        n >= 10_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
        else -> n.toString()
    }

    /** Кнопка «📊 Статистика» (под золотой плашкой разработчика). */
    fun button(ctx: Context): View = TextView(ctx).apply {
        tag = "dev_stats_btn"
        text = t("📊  Статистика приложения", "📊  App statistics")
        textSize = 15.5f
        paint.isFakeBoldText = true
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        minHeight = dp(ctx, 52)
        setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF4F46E5.toInt(), 0xFF7C3AED.toInt(), 0xFFDB2777.toInt())).apply {
            cornerRadius = dp(ctx, 18).toFloat()
        }
        elevation = dp(ctx, 4).toFloat()
        com.stoyko.smessenger.ui.Kinetics.squish(this, 0.95f)
        setOnClickListener { show(ctx) }
    }

    fun show(ctx: Context) {
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 8))
        }
        root.addView(
            TextView(ctx).apply {
                text = t("📊 Статистика S-messenger", "📊 S-messenger statistics")
                textSize = 20f
                paint.isFakeBoldText = true
                setTextColor(primary)
            },
        )
        val updated = TextView(ctx).apply {
            text = t("Загрузка…", "Loading…")
            textSize = 12.5f
            setTextColor(secondary)
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 10))
        }
        root.addView(updated)

        // Главное: в сети сейчас.
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF10B981.toInt(), 0xFF047857.toInt())).apply {
                cornerRadius = dp(ctx, 20).toFloat()
            }
        }
        val dot = View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        }
        hero.addView(dot, LinearLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12)))
        val heroCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 14), 0, 0, 0) }
        val onlineValue = TextView(ctx).apply { text = "—"; textSize = 34f; paint.isFakeBoldText = true; setTextColor(Color.WHITE) }
        heroCol.addView(onlineValue)
        heroCol.addView(TextView(ctx).apply { text = t("в сети сейчас (за 10–20 мин)", "online now (last 10–20 min)"); textSize = 13f; setTextColor(0xE6FFFFFF.toInt()) })
        hero.addView(heroCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(hero)
        if (com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on) {
            dot.animate().alpha(0.25f).setDuration(700).withEndAction(object : Runnable {
                override fun run() {
                    if (!dot.isAttachedToWindow) return
                    dot.animate().alpha(if (dot.alpha < 0.5f) 1f else 0.25f).setDuration(700).withEndAction(this).start()
                }
            }).start()
        }

        // Плитки 2 в ряд.
        data class Tile(val emoji: String, val label: String, val color: Int, val value: TextView)
        fun valueView() = TextView(ctx).apply { text = "—"; textSize = 24f; paint.isFakeBoldText = true; setTextColor(primary) }
        val tiles = listOf(
            Tile("⚫", t("Не в сети", "Offline"), 0xFF64748B.toInt(), valueView()),
            Tile("📱", t("Устройств с приложением", "Devices with the app"), 0xFF0EA5E9.toInt(), valueView()),
            Tile("👤", t("Создали профиль", "Created a profile"), 0xFF8B5CF6.toInt(), valueView()),
            Tile("📅", t("Активны сегодня", "Active today"), 0xFFF59E0B.toInt(), valueView()),
            Tile("⬇️", t("Скачано APK всего", "APK downloads total"), 0xFF22C55E.toInt(), valueView()),
            Tile("🆕", t("Скачано последней версии", "Latest version downloads"), 0xFFDB2777.toInt(), valueView()),
        )
        val latestLabel = TextView(ctx)
        tiles.chunked(2).forEach { pair ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, tl ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(ctx, 16).toFloat()
                        setColor((tl.color and 0x00FFFFFF) or 0x1F000000)
                    }
                }
                card.addView(TextView(ctx).apply { text = tl.emoji; textSize = 18f })
                card.addView(tl.value)
                card.addView(TextView(ctx).apply { text = tl.label; textSize = 12f; setTextColor(secondary); maxLines = 2 })
                if (tl.emoji == "🆕") card.addView(latestLabel.apply { textSize = 11f; setTextColor(secondary) })
                row.addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i == 0) marginEnd = dp(ctx, 8) })
            }
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8) })
        }

        fun section(s: String) = TextView(ctx).apply {
            text = s.uppercase()
            textSize = 11.5f
            letterSpacing = 0.06f
            paint.isFakeBoldText = true
            setTextColor(secondary)
            setPadding(dp(ctx, 2), dp(ctx, 16), 0, dp(ctx, 6))
        }
        root.addView(section(t("Активные устройства за неделю", "Active devices this week")))
        val chart = WeekBars(ctx, primary, secondary)
        root.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 130)))
        root.addView(section(t("Версии приложения", "App versions")))
        val versions = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(versions)
        root.addView(section(t("Типы телефонов", "Phone types")))
        val kinds = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(kinds)
        root.addView(
            TextView(ctx).apply {
                text = t(
                    "Счётчики обезличены: устройства присылают только «+1», без ID, имён и адресов. Учёт идёт с версии 4.13.1 - устройства на старых версиях появятся после обновления. «Устройств» - установки (переустановка считается заново).",
                    "Counters are anonymous: devices only send “+1”, no IDs, names or addresses. Counting started with 4.13.1 - devices on older versions appear after updating. “Devices” are installs (a reinstall counts again).",
                )
                textSize = 11.5f
                setTextColor(secondary)
                setPadding(dp(ctx, 2), dp(ctx, 14), dp(ctx, 2), 0)
            },
        )

        fun bars(box: LinearLayout, rows: List<Pair<String, Long>>, color: Int) {
            box.removeAllViews()
            if (rows.isEmpty()) {
                box.addView(TextView(ctx).apply { text = t("Пока нет данных", "No data yet"); textSize = 13f; setTextColor(secondary) })
                return
            }
            val max = rows.maxOf { it.second }.coerceAtLeast(1)
            for ((label, v) in rows) {
                val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(ctx, 3), 0, dp(ctx, 3)) }
                line.addView(TextView(ctx).apply { text = label; textSize = 13f; setTextColor(primary) }, LinearLayout.LayoutParams(dp(ctx, 96), ViewGroup.LayoutParams.WRAP_CONTENT))
                val track = FrameLayout(ctx).apply { background = GradientDrawable().apply { cornerRadius = dp(ctx, 6).toFloat(); setColor(0x14808080) } }
                val fill = View(ctx).apply { background = GradientDrawable().apply { cornerRadius = dp(ctx, 6).toFloat(); setColor(color) } }
                track.addView(fill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
                line.addView(track, LinearLayout.LayoutParams(0, dp(ctx, 12), 1f))
                line.addView(TextView(ctx).apply { text = fmt(v); textSize = 13f; paint.isFakeBoldText = true; setTextColor(primary); gravity = Gravity.END }, LinearLayout.LayoutParams(dp(ctx, 52), ViewGroup.LayoutParams.WRAP_CONTENT))
                track.post { fill.layoutParams = fill.layoutParams.apply { width = (track.width * v / max.toFloat()).toInt().coerceAtLeast(dp(ctx, 4)) } }
                box.addView(line)
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setView(ScrollView(ctx).apply { addView(root) })
            .setPositiveButton(t("Закрыть", "Close"), null)
            .setNeutralButton(t("Обновить", "Refresh"), null)
            .create()

        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        var loading = false
        fun full() {
            if (loading) return
            loading = true
            updated.text = t("Обновление…", "Refreshing…")
            io.execute {
                val s = runCatching { Analytics.load() }.getOrNull()
                main.post {
                    loading = false
                    if (!dialog.isShowing) return@post
                    if (s == null) {
                        updated.text = t("Нет связи со счётчиками - попробуйте позже", "Counters unreachable - try later")
                        return@post
                    }
                    onlineValue.text = fmt(s.onlineNow)
                    tiles[0].value.text = fmt(s.offline)
                    tiles[1].value.text = fmt(s.installs)
                    tiles[2].value.text = fmt(s.registered)
                    tiles[3].value.text = fmt(s.todayActive)
                    tiles[4].value.text = fmt(s.downloads)
                    tiles[5].value.text = fmt(s.latestDownloads)
                    latestLabel.text = s.latestTag.orEmpty()
                    chart.set(s.week.map { (d, v) -> d.takeLast(2) + "." + d.substring(4, 6) to (v ?: 0L) })
                    bars(versions, s.versions.map { (v, n) -> ("v$v" + (if (v == com.stoyko.smessenger.BuildConfig.VERSION_CODE) " ★" else "")) to n }, 0xFF6366F1.toInt())
                    val names = mapOf(
                        "budget" to t("Бюджетные", "Budget"), "phone" to t("Обычные", "Regular"), "flagship" to t("Флагманы", "Flagship"),
                        "flip" to t("Раскладушки", "Flip"), "fold" to t("Складные", "Fold"), "tablet" to t("Планшеты", "Tablets"),
                    )
                    bars(kinds, s.kinds.map { (k, n) -> (names[k] ?: k) to n }, 0xFF0EA5E9.toInt())
                    updated.text = t("Обновлено в ", "Updated at ") + time.format(Date(s.at))
                }
            }
        }
        // «В сети» - каждые 30 с, пока окно открыто.
        val tickOnline = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                io.execute {
                    val on = runCatching { Analytics.onlineNow() }.getOrNull()
                    main.post {
                        if (on != null && dialog.isShowing) {
                            onlineValue.text = fmt(on)
                            updated.text = t("Обновлено в ", "Updated at ") + time.format(Date())
                        }
                    }
                }
                main.postDelayed(this, 30_000)
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { full() }
            full()
            main.postDelayed(tickOnline, 30_000)
        }
        dialog.setOnDismissListener { main.removeCallbacks(tickOnline) }
        com.stoyko.smessenger.ui.DialogTracker.add(dialog).show()
    }

    /** Столбики активности за 7 дней. */
    private class WeekBars(ctx: Context, private val text: Int, private val sub: Int) : View(ctx) {
        private var data: List<Pair<String, Long>> = emptyList()
        private val d = resources.displayMetrics.density
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 10.5f * d }
        private val value = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 11.5f * d; isFakeBoldText = true }
        private val r = RectF()

        fun set(v: List<Pair<String, Long>>) {
            data = v
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            if (data.isEmpty()) return
            val n = data.size
            val w = width.toFloat() / n
            val top = 18 * d
            val bottom = height - 18 * d
            val max = data.maxOf { it.second }.coerceAtLeast(1)
            label.color = sub
            value.color = text
            for ((i, p) in data.withIndex()) {
                val cx = w * i + w / 2
                val h = (bottom - top) * p.second / max
                val bw = w * 0.5f
                r.set(cx - bw / 2, bottom - h.coerceAtLeast(3 * d), cx + bw / 2, bottom)
                bar.color = if (i == n - 1) 0xFFF59E0B.toInt() else 0xFF6366F1.toInt()
                c.drawRoundRect(r, 6 * d, 6 * d, bar)
                c.drawText(fmt(p.second), cx, r.top - 4 * d, value)
                c.drawText(p.first, cx, height - 4 * d, label)
            }
        }
    }
}
