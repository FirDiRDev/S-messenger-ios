// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.stats

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.StatChips

/**
 * Профиль и игры:
 *  - [profileBlock] - витрина достижений ровными плитками и ОДНА кнопка «📊 Статистика игр»;
 *  - [show] - шторка со всеми играми: победы, поражения, ничьи, партии, рекорды;
 *  - [pickShowcase] - выбор достижений для витрины галочками (до [GameStats.MAX_SHOWCASE]).
 * Одинаково работает для своего профиля и для профиля друга (по его строке рекордов).
 */
object GameStatsSheet {
    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private fun fancy(ctx: Context) = com.stoyko.smessenger.ui.Kinetics.enabled && !com.stoyko.smessenger.ui.EnergySaver.on

    /**
     * Витрина (плитки по три в ряд) + кнопка статистики. [lead] - плитка перед витриной
     * (монеты друга). [onShowcaseChanged] - только в своём профиле: долгое нажатие на витрину и
     * кнопка в шторке открывают выбор достижений.
     */
    fun profileBlock(
        ctx: Context,
        s: GameStats.Snapshot,
        ids: List<String>,
        title: String?,
        lead: StatChips.Item? = null,
        onShowcaseChanged: (() -> Unit)? = null,
    ): LinearLayout {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val items = listOfNotNull(lead) + GameStats.tiles(ctx, s, ids)
        if (items.isNotEmpty()) {
            val tiles = StatChips.build(ctx, items)
            if (onShowcaseChanged != null) {
                tiles.setOnLongClickListener {
                    pickShowcase(ctx, onShowcaseChanged)
                    true
                }
            }
            box.addView(tiles, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(
            Btn.nice(ctx, ctx.getString(R.string.gamesb_stats_button), intArrayOf(0xFF818CF8.toInt(), 0xFF4F46E5.toInt())) {
                // Своя статистика - свежая на момент открытия.
                show(ctx, if (title == null) GameStats.mine(ctx) else s, title, onShowcaseChanged)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 46)).apply { topMargin = dp(ctx, if (items.isEmpty()) 0 else 8) },
        )
        return box
    }

    // ---------------- шторка статистики ----------------

    /** Шторка «Статистика игр»: [title] - имя друга (null - моя статистика). */
    fun show(ctx: Context, s: GameStats.Snapshot, title: String?, onShowcaseChanged: (() -> Unit)? = null) {
        val d = ctx.resources.displayMetrics.density
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 24))
        }
        col.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.gamesb_stats_title)
            textSize = 20f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(primary)
        })
        col.addView(TextView(ctx).apply {
            text = if (title != null) ctx.getString(R.string.gamesb_stats_of, title) else ctx.getString(R.string.gamesb_stats_mine)
            textSize = 13f
            setTextColor(secondary)
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 10))
        })
        col.addView(hero(ctx, s))

        val na = ctx.getString(R.string.gamesb_stat_na)
        fun v(x: Int) = if (x < 0) na else GameStats.compact(x)
        val wins = ctx.getString(R.string.gamesb_stat_w)
        val losses = ctx.getString(R.string.gamesb_stat_l)
        val draws = ctx.getString(R.string.gamesb_stat_d)
        val record = ctx.getString(R.string.gamesb_stat_record)
        card(ctx, col, "🐍", ctx.getString(R.string.snake_title), intArrayOf(0xFF34D399.toInt(), 0xFF047857.toInt()), listOf(
            ctx.getString(R.string.gamesb_stat_played) to v(s.snakeGames),
            record to v(s.snakeBest),
            ctx.getString(R.string.gamesb_stat_level) to v(s.snakeLevel),
        ), null)
        card(ctx, col, "♞", ctx.getString(R.string.gamesb_game_chess), intArrayOf(0xFF94A3B8.toInt(), 0xFF334155.toInt()), listOf(
            wins to v(s.chessW), draws to v(s.chessD), losses to v(s.chessL),
        ), intArrayOf(s.chessW, s.chessD, s.chessL))
        card(ctx, col, "🤖", ctx.getString(R.string.gamesb_game_chess_bot), intArrayOf(0xFF60A5FA.toInt(), 0xFF1D4ED8.toInt()), listOf(
            wins to v(s.botW), draws to v(s.botD), losses to v(s.botL),
        ), intArrayOf(s.botW, s.botD, s.botL))
        card(ctx, col, "🃏", ctx.getString(R.string.durak_title), intArrayOf(0xFFF87171.toInt(), 0xFF991B1B.toInt()), listOf(
            wins to v(s.durakW), draws to v(s.durakD), losses to v(s.durakL),
        ), intArrayOf(s.durakW, s.durakD, s.durakL))
        card(ctx, col, "🂡", ctx.getString(R.string.bj_title), intArrayOf(0xFF4ADE80.toInt(), 0xFF166534.toInt()), listOf(
            ctx.getString(R.string.gamesb_stat_hands) to v(s.bjPlayed),
            wins to v(s.bjWins),
            ctx.getString(R.string.gamesb_stat_best_win) to v(s.bjBest),
        ), intArrayOf(s.bjWins, s.bjPushes, s.bjLosses))
        card(ctx, col, "💎", ctx.getString(R.string.m3_title), intArrayOf(0xFFF0ABFC.toInt(), 0xFF7E22CE.toInt()), listOf(
            ctx.getString(R.string.gamesb_stat_levels) to v(s.m3Level),
            ctx.getString(R.string.gamesb_stat_stars) to v(s.m3Stars),
            record to v(s.m3Best),
        ), intArrayOf(s.m3Wins, 0, if (s.m3Played >= 0 && s.m3Wins >= 0) (s.m3Played - s.m3Wins).coerceAtLeast(0) else -1))
        card(ctx, col, "🚂", ctx.getString(R.string.runner_title), intArrayOf(0xFFFDBA74.toInt(), 0xFFC2410C.toInt()), listOf(
            ctx.getString(R.string.gamesb_stat_runs) to v(s.runRuns),
            record to v(s.runBest),
            ctx.getString(R.string.gamesb_stat_distance) to (if (s.runDist < 0) na else ctx.getString(R.string.gamesb_stat_meters, GameStats.compact(s.runDist))),
        ), null)

        // Витрина достижений.
        val ids = s.showcase
        if (ids != null || onShowcaseChanged != null) {
            col.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.gamesb_showcase_header)
                textSize = 16f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(primary)
                setPadding(dp(ctx, 2), dp(ctx, 16), 0, dp(ctx, 6))
            })
            val tiles = GameStats.tiles(ctx, s, ids.orEmpty())
            if (tiles.isNotEmpty()) col.addView(StatChips.build(ctx, tiles))
            if (onShowcaseChanged != null) {
                col.addView(
                    Btn.nice(ctx, ctx.getString(R.string.gamesb_showcase_button), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
                        pickShowcase(ctx) {
                            onShowcaseChanged()
                            sheet.dismiss()
                        }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 46)).apply { topMargin = dp(ctx, 8) },
                )
            }
        }
        col.addView(TextView(ctx).apply {
            setText(if (title == null) R.string.gamesb_stats_note else R.string.gamesb_stats_note_peer)
            textSize = 12f
            setTextColor(secondary)
            setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), 0)
        })
        val scroll = ScrollView(ctx).apply {
            setBackgroundResource(R.drawable.bg_sheet)
            addView(col)
        }
        sheet.setContentView(scroll)
        (scroll.parent as? View)?.setBackgroundColor(Color.TRANSPARENT)
        runCatching {
            sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            sheet.behavior.skipCollapsed = true
        }
        com.stoyko.smessenger.ui.DialogTracker.add(sheet)
        sheet.show()
        if (fancy(ctx)) {
            // Карточки выезжают одна за другой (слой на время анимации - плавно и на слабых телефонах).
            for (i in 0 until col.childCount) {
                val c = col.getChildAt(i)
                c.alpha = 0f
                c.translationY = 18 * d
                c.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                c.animate().alpha(1f).translationY(0f).setStartDelay(35L * i.coerceAtMost(12)).setDuration(260)
                    .withEndAction { c.setLayerType(View.LAYER_TYPE_NONE, null) }.start()
            }
        }
    }

    /** Итог по всем играм: кольцо побед / ничьих / поражений и три больших числа. */
    private fun hero(ctx: Context, s: GameStats.Snapshot): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6366F1.toInt(), 0xFF7C3AED.toInt(), 0xFF312E81.toInt())).apply {
                cornerRadius = 20 * d
            }
            elevation = 3 * d
        }
        val w = s.totalWins
        val dr = s.totalDraws
        val l = s.totalLosses
        val ring = Ring(ctx, w, dr, l, fancy(ctx))
        row.addView(ring, LinearLayout.LayoutParams(dp(ctx, 96), dp(ctx, 96)))
        val nums = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), 0, 0, 0)
        }
        fun line(icon: String, value: String, label: String) {
            nums.addView(TextView(ctx).apply {
                text = "$icon  $value"
                textSize = 18f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            nums.addView(TextView(ctx).apply {
                text = label
                textSize = 11.5f
                setTextColor(0xD9FFFFFF.toInt())
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, 0, dp(ctx, 4))
            })
        }
        line("🎮", GameStats.compact(s.totalGames), ctx.getString(R.string.gamesb_stat_games))
        line("🏅", GameStats.compact(w), ctx.getString(R.string.gamesb_stat_wins))
        if (s.coins >= 0) line("🪙", GameStats.compact(s.coins), ctx.getString(R.string.stat_coins))
        row.addView(nums, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /**
     * Карточка игры: цветная плашка со значком и названием, ниже - клетки «число / подпись»
     * одной ширины и (для игр с победами) полоска побед, ничьих и поражений.
     */
    private fun card(ctx: Context, col: LinearLayout, icon: String, name: String, colors: IntArray, cells: List<Pair<String, String>>, wdl: IntArray?) {
        val d = ctx.resources.displayMetrics.density
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = 18 * d }
            elevation = 2 * d
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(TextView(ctx).apply {
            text = icon
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
        }, LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)))
        head.addView(TextView(ctx).apply {
            text = name
            textSize = 16f
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(ctx, 10), 0, 0, 0)
            setShadowLayer(3f, 0f, 1f, 0x55000000)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
        }
        cells.forEachIndexed { i, (label, value) ->
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                background = GradientDrawable().apply {
                    cornerRadius = 12 * d
                    setColor(0x29FFFFFF)
                }
                addView(TextView(ctx).apply {
                    text = value
                    textSize = 17f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.WHITE)
                    androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 10, 17, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 24)))
                addView(TextView(ctx).apply {
                    text = label
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(0xE6FFFFFF.toInt())
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) marginStart = dp(ctx, 6) })
        }
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8) })
        if (wdl != null && wdl.all { it >= 0 } && wdl.sum() > 0) {
            card.addView(WdlBar(ctx, wdl[0], wdl[1], wdl[2]), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 8)).apply { topMargin = dp(ctx, 8) })
            card.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.gamesb_stat_winrate, wdl[0] * 100 / wdl.sum())
                textSize = 11.5f
                setTextColor(0xE6FFFFFF.toInt())
                setPadding(0, dp(ctx, 3), 0, 0)
            })
        }
        col.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 10) })
    }

    /** Полоска «победы / ничьи / поражения» (зелёный, жёлтый, красный). */
    @SuppressLint("ViewConstructor")
    private class WdlBar(ctx: Context, val w: Int, val dr: Int, val l: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val parts = intArrayOf(w, dr, l)
        private val cols = intArrayOf(0xFF86EFAC.toInt(), 0xFFFDE68A.toInt(), 0xFFFCA5A5.toInt())

        override fun onDraw(c: Canvas) {
            val total = (w + dr + l).coerceAtLeast(1)
            val h = height.toFloat()
            val width = width.toFloat()
            p.color = 0x33000000
            r.set(0f, 0f, width, h)
            c.drawRoundRect(r, h / 2, h / 2, p)
            var x = 0f
            for (k in 0..2) {
                if (parts[k] <= 0) continue
                val segW = width * parts[k] / total
                p.color = cols[k]
                r.set(x, 0f, x + segW, h)
                c.drawRoundRect(r, h / 2, h / 2, p)
                x += segW
            }
        }
    }

    /** Кольцо «победы / ничьи / поражения» с процентом побед в центре; один раз «прорисовывается». */
    @SuppressLint("ViewConstructor")
    private class Ring(ctx: Context, val w: Int, val dr: Int, val l: Int, animate: Boolean) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xD9FFFFFF.toInt()
            textAlign = Paint.Align.CENTER
        }
        private val r = RectF()
        private val parts = intArrayOf(w, dr, l)
        private val cols = intArrayOf(0xFF86EFAC.toInt(), 0xFFFDE68A.toInt(), 0xFFFCA5A5.toInt())
        private val total = w + dr + l
        private val pct = if (total > 0) "${w * 100 / total}%" else "—"
        private val sub = ctx.getString(R.string.gamesb_stat_winrate_short)
        private var k = if (animate) 0f else 1f
        private var anim: ValueAnimator? = null

        init {
            if (animate && total > 0) {
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 700
                    startDelay = 150
                    interpolator = android.view.animation.DecelerateInterpolator()
                    addUpdateListener {
                        k = it.animatedValue as Float
                        invalidate()
                    }
                }
            } else {
                k = 1f
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            anim?.start()
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            k = 1f
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(wd: Int, h: Int, oldw: Int, oldh: Int) {
            val sw = minOf(wd, h) * 0.13f
            p.strokeWidth = sw
            r.set(sw / 2 + d, sw / 2 + d, wd - sw / 2 - d, h - sw / 2 - d)
            text.textSize = minOf(wd, h) * 0.22f
            small.textSize = minOf(wd, h) * 0.105f
        }

        override fun onDraw(c: Canvas) {
            p.color = 0x33FFFFFF
            c.drawArc(r, 0f, 360f, false, p)
            if (total > 0) {
                var start = -90f
                for (i in 0..2) {
                    if (parts[i] <= 0) continue
                    val sweep = 360f * parts[i] / total * k
                    p.color = cols[i]
                    c.drawArc(r, start, sweep, false, p)
                    start += sweep
                }
            }
            val cy = height / 2f
            c.drawText(pct, width / 2f, cy + text.textSize * 0.2f, text)
            c.drawText(sub, width / 2f, cy + text.textSize * 0.2f + small.textSize * 1.3f, small)
        }
    }

    // ---------------- выбор витрины ----------------

    /**
     * Выбор достижений для витрины профиля: список с галочками, не больше [GameStats.MAX_SHOWCASE]
     * (как витрина подарков). Порядок на витрине - порядок выбора. [done] - после сохранения.
     */
    fun pickShowcase(ctx: Context, done: () -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val primary = ContextCompat.getColor(ctx, R.color.textPrimary)
        val secondary = ContextCompat.getColor(ctx, R.color.textSecondary)
        val s = GameStats.mine(ctx)
        val chosen = GameStats.showcase(ctx).toMutableList()
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 8))
        }
        val counter = TextView(ctx).apply {
            textSize = 13f
            setTextColor(secondary)
            setPadding(dp(ctx, 4), 0, 0, dp(ctx, 6))
        }
        list.addView(counter)
        val boxes = ArrayList<Pair<String, CheckBox>>()
        fun refresh() {
            counter.text = ctx.getString(R.string.gamesb_showcase_count, chosen.size, GameStats.MAX_SHOWCASE)
            for ((id, cb) in boxes) {
                val on = id in chosen
                (cb.parent as? View)?.isSelected = on
                ((cb.parent as? View)?.background as? GradientDrawable)?.apply {
                    setColor(if (on) 0x2E5B4BDB else 0x0F000000)
                    setStroke(dp(ctx, if (on) 2 else 1), if (on) 0xFF5B4BDB.toInt() else 0x22000000)
                }
            }
        }
        for (a in GameStats.ALL) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                background = GradientDrawable().apply { cornerRadius = 14 * d }
            }
            row.addView(TextView(ctx).apply {
                text = a.icon
                textSize = 20f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(ctx, 36), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply {
                    text = GameStats.compact(a.value(s))
                    textSize = 16f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(primary)
                })
                addView(TextView(ctx).apply {
                    text = GameStats.label(ctx, a, s)
                    textSize = 12f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(secondary)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(ctx, 8) })
            val cb = CheckBox(ctx).apply {
                isChecked = a.id in chosen
                isClickable = false
                isFocusable = false
            }
            row.addView(cb)
            row.setOnClickListener {
                if (a.id in chosen) {
                    chosen -= a.id
                } else {
                    if (chosen.size >= GameStats.MAX_SHOWCASE) {
                        Toast.makeText(ctx, ctx.getString(R.string.gamesb_showcase_max, GameStats.MAX_SHOWCASE), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    chosen += a.id
                }
                cb.isChecked = a.id in chosen
                row.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                refresh()
            }
            boxes += a.id to cb
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 6) })
        }
        refresh()
        val dlg = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.gamesb_showcase_title, GameStats.MAX_SHOWCASE))
            .setView(ScrollView(ctx).apply { addView(list) })
            .setPositiveButton(R.string.gamesb_showcase_save) { _, _ ->
                GameStats.setShowcase(ctx, chosen)
                done()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        com.stoyko.smessenger.ui.DialogTracker.add(dlg).show()
    }
}
