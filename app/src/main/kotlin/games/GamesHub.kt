// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.CoinView
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * «Дзынь» монетки - синтезированный металлический звон (без файлов): негармонические
 * обертоны как у настоящей монеты, мгновенная атака и звонкое затухание.
 */
object CoinSound {
    private const val RATE = 44100

    /** Один удар монеты в буфер [buf] с момента [at] секунд, основной тон [f]. */
    private fun ding(buf: FloatArray, at: Double, f: Double, gain: Double) {
        // Обертоны металлического диска (соотношения ~1 : 2.76 : 5.40 : 8.93).
        val parts = doubleArrayOf(1.0, 2.76, 5.40, 8.93)
        val amps = doubleArrayOf(1.0, 0.28, 0.1, 0.03)
        val decay = doubleArrayOf(7.0, 11.0, 17.0, 26.0)
        val start = (at * RATE).toInt()
        val len = (RATE * 0.6).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx >= buf.size) break
            val t = i.toDouble() / RATE
            var v = 0.0
            for (k in parts.indices) v += amps[k] * sin(2 * PI * f * parts[k] * t + k) * exp(-t * decay[k])
            // Мягкая атака (без резкого щелчка - звук не «бьёт по ушам»).
            v *= (i / (RATE * 0.004)).coerceAtMost(1.0)
            buf[idx] += (v * gain).toFloat()
        }
    }

    private fun playBuf(buf: FloatArray) {
        Thread {
            runCatching {
                val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1f)
                val norm = 0.3f / peak
                val pcm = ShortArray(buf.size) { (buf[it] * norm * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort() }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 100)
                track.release()
            }
        }.start()
    }

    /** Одна монета: «дзынь». */
    fun play() {
        val buf = FloatArray((RATE * 0.7).toInt())
        ding(buf, 0.0, 1318.5, 1.0)
        ding(buf, 0.075, 1760.0, 0.7)
        playBuf(buf)
    }

    /** Горсть монет (перевод, награда): звон нескольких монет подряд, выше и выше. */
    fun jackpot(n: Int) {
        val hits = n.coerceIn(3, 9)
        val buf = FloatArray((RATE * (0.7 + hits * 0.085)).toInt())
        for (i in 0 until hits) {
            val f = 1174.7 * Math.pow(1.06, i.toDouble()) * (0.99 + Math.random() * 0.02)
            ding(buf, i * 0.085, f, 0.85 + 0.15 * Math.random())
        }
        ding(buf, hits * 0.085, 2093.0, 0.8)
        playBuf(buf)
    }
}

/**
 * Раздел «Игры»: монеты и ежедневная награда, «Змейка», «Шахматы» с контактами на монеты
 * (и на одном телефоне), список партий - продолжить с того же места.
 */
object GamesHub {
    private var refreshAllRef: (() -> Unit)? = null
    fun show(ctx: Context, onShop: (() -> Unit)? = null) {
        GameNet.init(ctx)
        GameNet.resendCoins(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val frame = FrameLayout(ctx).apply { setBackgroundColor(0xFF0F0D24.toInt()) }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(6), dp(14), dp(20))
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { dialog.safeDismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(TextView(ctx).apply {
            setText(R.string.games_title)
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val coin = CoinView(ctx)
        bar.addView(coin, LinearLayout.LayoutParams(dp(34), dp(34)))
        val wallet = TextView(ctx).apply {
            textSize = 19f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        bar.addView(wallet)
        col.addView(bar)

        fun card(title: String, sub: String, colors: IntArray): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = 20 * d }
            addView(TextView(ctx).apply {
                text = title
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            addView(TextView(ctx).apply {
                text = sub
                textSize = 13f
                setTextColor(0xDDFFFFFF.toInt())
                setPadding(0, dp(4), 0, dp(10))
            })
        }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) } }

        /** Удаление сохранения - с подтверждением. */
        fun refreshAll() = refreshAllRef?.invoke()

        fun confirmDelete(what: String, action: () -> Unit) {
            com.stoyko.smessenger.ui.DialogTracker.add(
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.games_delete_save)
                    .setMessage(ctx.getString(R.string.games_delete_save_q, what))
                    .setPositiveButton(R.string.games_delete) { _, _ -> action() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .create(),
            ).show()
        }

        /** Строка сохранённой партии: открыть по нажатию, 🗑 - удалить. */
        fun saveRow(label: String, onOpen: () -> Unit, onDelete: () -> Unit): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 12 * d
                setColor(0x26FFFFFF)
            }
            addView(TextView(ctx).apply {
                text = label
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(6), dp(10))
                setOnClickListener { onOpen() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(ctx).apply {
                text = "🗑"
                textSize = 17f
                gravity = Gravity.CENTER
                contentDescription = ctx.getString(R.string.games_delete_save)
                setOnClickListener { confirmDelete(label) { onDelete() } }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
        }

        /** Маленькая кнопка «🗑 Удалить сохранение» под игрой. */
        fun deleteLink(text: String, onDelete: () -> Unit) = TextView(ctx).apply {
            this.text = "🗑 " + text
            textSize = 13.5f
            setTextColor(0xEEFFFFFF.toInt())
            setPadding(dp(6), dp(10), dp(6), dp(2))
            setOnClickListener { confirmDelete(text) { onDelete() } }
        }

        // Ежедневная награда.
        val daily = card(ctx.getString(R.string.daily_title_short), "", intArrayOf(0xFF7C5CFF.toInt(), 0xFF3B2F8F.toInt()))
        val dailyBtn = Btn.nice(ctx, "", intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {}
        daily.addView(dailyBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        col.addView(daily, lp())

        // Магазин монет (за рубли) и «на жизнь проекта».
        val shop = card("🛒 " + ctx.getString(R.string.shop_title), ctx.getString(R.string.shop_hub_sub), intArrayOf(0xFFF59E0B.toInt(), 0xFF9A3412.toInt()))
        shop.addView(Btn.row(ctx, listOf(
            Btn.nice(ctx, ctx.getString(R.string.shop_open), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) { com.stoyko.smessenger.chat.CoinShop.show(ctx) },
            com.stoyko.smessenger.chat.CoinShop.donateButton(ctx),
        ), height = 52, gap = 10))
        col.addView(shop, lp())
        wallet.setOnClickListener { com.stoyko.smessenger.chat.CoinShop.show(ctx) }
        coin.setOnClickListener { com.stoyko.smessenger.chat.CoinShop.show(ctx) }

        // Змейка.
        val snake = card("🐍 " + ctx.getString(R.string.snake_title), "", intArrayOf(0xFF10B981.toInt(), 0xFF065F46.toInt()))
        snake.addView(Btn.nice(ctx, ctx.getString(R.string.games_play), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) {
            SnakeGame.show(ctx, onShop)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        val snakeDel = deleteLink(ctx.getString(R.string.games_delete_snake)) {
            Coins.saveGame(ctx, null)
            refreshAll()
        }
        snake.addView(snakeDel)
        col.addView(snake, lp())

        // Бег по рельсам.
        val runner = card("🏃 " + ctx.getString(R.string.runner_title), ctx.getString(R.string.runner_hub_sub, RunnerGame.best(ctx)), intArrayOf(0xFFF97316.toInt(), 0xFF7C2D12.toInt()))
        runner.addView(Btn.nice(ctx, ctx.getString(R.string.games_play), intArrayOf(0xFFFDE047.toInt(), 0xFFEA580C.toInt())) {
            RunnerGame.show(ctx)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        col.addView(runner, lp())

        // Три в ряд.
        val m3 = card("💎 " + ctx.getString(R.string.m3_title), "", intArrayOf(0xFF14B8A6.toInt(), 0xFF134E4A.toInt()))
        m3.addView(Btn.nice(ctx, ctx.getString(R.string.games_play), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
            Match3Game.show(ctx)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        val m3Del = deleteLink(ctx.getString(R.string.games_delete_m3)) {
            Match3Game.deleteSave(ctx)
            refreshAll()
        }
        m3.addView(m3Del)
        val m3Reset = deleteLink(ctx.getString(R.string.m3_reset)) {
            Match3Game.resetProgress(ctx)
            refreshAll()
        }
        m3.addView(m3Reset)
        col.addView(m3, lp())

        // Шахматы.
        val chess = card("♞ " + ctx.getString(R.string.chess_title), ctx.getString(R.string.chess_hub_sub), intArrayOf(0xFF8B7CFF.toInt(), 0xFF312E81.toInt()))
        chess.addView(Btn.row(ctx, listOf(
            Btn.nice(ctx, ctx.getString(R.string.chess_vs_friend), intArrayOf(0xFFF472B6.toInt(), 0xFFDB2777.toInt())) { pickOpponent(ctx) },
            Btn.nice(ctx, ctx.getString(R.string.chess_local), Btn.PALETTE[1]) { ChessGame.show(ctx, null, local = true) },
        ), height = 52, gap = 10))
        chess.addView(Btn.nice(ctx, "🤖 " + ctx.getString(R.string.chess_bot), intArrayOf(0xFF38BDF8.toInt(), 0xFF2563EB.toInt())) { ChessGame.pickBot(ctx) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (52 * ctx.resources.displayMetrics.density).toInt()).apply { topMargin = (10 * ctx.resources.displayMetrics.density).toInt() })
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        chess.addView(list)
        val chessLocalDel = deleteLink(ctx.getString(R.string.games_delete_chess_local)) {
            ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE).edit().clear().commit()
            refreshAll()
        }
        chess.addView(chessLocalDel)
        col.addView(chess, lp())

        // Видимость монет, рекордов и подарков для друзей.
        col.addView(androidx.appcompat.widget.SwitchCompat(ctx).apply {
            setText(R.string.games_share_stats)
            setTextColor(Color.WHITE)
            isChecked = Coins.shareStats(ctx)
            setOnCheckedChangeListener { _, v -> Coins.setShareStats(ctx, v) }
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }, lp())

        // Карты: «Дурак» с друзьями (2-4 игрока, ставки) или с ботами.
        val durak = card("🃏 " + ctx.getString(R.string.durak_title), ctx.getString(R.string.durak_hub_sub), intArrayOf(0xFFFB923C.toInt(), 0xFF9A3412.toInt()))
        durak.addView(Btn.row(ctx, listOf(
            Btn.nice(ctx, ctx.getString(R.string.durak_with_friends), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) { pickDurak(ctx) },
            Btn.nice(ctx, ctx.getString(R.string.durak_with_bots), Btn.PALETTE[1]) { pickBots(ctx) },
        ), height = 52, gap = 10))
        val durakList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        durak.addView(durakList)
        val durakLocalDel = deleteLink(ctx.getString(R.string.games_delete_durak_bots)) {
            ctx.getSharedPreferences("durak_local", Context.MODE_PRIVATE).edit().remove("game").commit()
            refreshAll()
        }
        durak.addView(durakLocalDel)
        col.addView(durak, lp())

        // Блэкджек: стол с той же колодой, ставки монетами.
        val bj = card("♠ " + ctx.getString(R.string.bj_title), ctx.getString(R.string.bj_hub_sub, BlackjackGame.played(ctx), BlackjackGame.wins(ctx)), intArrayOf(0xFF22C55E.toInt(), 0xFF14532D.toInt()))
        bj.addView(Btn.nice(ctx, ctx.getString(R.string.games_play), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
            BlackjackGame.show(ctx)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        // Онлайн: я - дилер, друзья - игроки (кнопка и список столов).
        com.stoyko.smessenger.games.blackjack.BjOnline.attachHub(ctx, bj)
        col.addView(bj, lp())

        fun refresh() {
            wallet.text = Coins.balance(ctx).toString()
            val avail = Coins.dailyAvailable(ctx)
            val next = Coins.dailyNextDay(ctx)
            (daily.getChildAt(1) as TextView).text = ctx.getString(R.string.daily_sub, if (avail) next else (if (next >= 7) 1 else next + 1))
            dailyBtn.text = if (avail) ctx.getString(R.string.daily_claim, next) else ctx.getString(R.string.daily_tomorrow, if (next >= 7) 1 else next + 1)
            dailyBtn.alpha = if (avail) 1f else 0.55f
            (snake.getChildAt(1) as TextView).text = ctx.getString(R.string.snake_hub_sub, Coins.best(ctx), Coins.bestLevel(ctx))
            (runner.getChildAt(1) as TextView).text = ctx.getString(R.string.runner_hub_sub, RunnerGame.best(ctx))
            (bj.getChildAt(1) as TextView).text = ctx.getString(R.string.bj_hub_sub, BlackjackGame.played(ctx), BlackjackGame.wins(ctx))
            snakeDel.visibility = if (Coins.loadGame(ctx) != null) View.VISIBLE else View.GONE
            (m3.getChildAt(1) as TextView).text = ctx.getString(R.string.m3_hub_sub, Match3Game.unlocked(ctx), Match3Game.totalStars(ctx))
            m3Del.visibility = if (Match3Game.saved(ctx) != null) View.VISIBLE else View.GONE
            m3Reset.visibility = if (Match3Game.unlocked(ctx) > 1) View.VISIBLE else View.GONE
            val cl = ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE)
            chessLocalDel.visibility = if (cl.getString("local", null).isNullOrEmpty() && cl.getString("bot", null).isNullOrEmpty()) View.GONE else View.VISIBLE
            durakLocalDel.visibility = if (ctx.getSharedPreferences("durak_local", Context.MODE_PRIVATE).getString("game", null) != null) View.VISIBLE else View.GONE
            list.removeAllViews()
            for (g in GameNet.all(ctx).filter { it.state != "declined" }.take(8)) {
                val label = ctx.getString(R.string.chess_vs, g.oppName.ifBlank { "?" }) + " · " + when {
                    g.state == "incoming" -> ctx.getString(R.string.chess_state_incoming)
                    g.state == "invited" -> ctx.getString(R.string.chess_state_invited)
                    g.result == 1 -> ctx.getString(R.string.chess_state_won)
                    g.result == -1 -> ctx.getString(R.string.chess_state_lost)
                    g.result == 0 -> ctx.getString(R.string.chess_state_draw)
                    else -> ctx.getString(R.string.chess_state_playing)
                } + (if (g.stake > 0) " · 🪙 ${g.stake}" else "") +
                    if (g.delBy.isNotEmpty()) " · " + ctx.getString(R.string.gamesave_deleted_mark) else ""
                list.addView(saveRow(label, { if (g.state == "incoming") askInvite(ctx, g) else ChessGame.show(ctx, g.id) }) {
                    // Незаконченная партия на ставку: удалить = сдаться (иначе монеты зависнут);
                    // сопернику уходит «удалить и у вас?».
                    runCatching { GameNet.deleteByUser(ctx, g) }
                    refreshAll()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
        fun refreshDurak() {
            durakList.removeAllViews()
            for (g in DurakNet.all(ctx).filter { it.state != "declined" && it.state != "canceled" }.take(6)) {
                val e = g.engineOrNull()
                val label = g.names.filterIndexed { i, _ -> i != g.mySeat }.joinToString(", ").ifBlank { "?" } + " · " + when {
                    g.state == "incoming" -> ctx.getString(R.string.chess_state_incoming)
                    g.state == "waiting" -> ctx.getString(R.string.durak_waiting_players, g.accepted.size, g.players)
                    g.state == "finished" && e?.durak == -2 -> ctx.getString(R.string.chess_state_draw)
                    g.state == "finished" && e?.durak == g.mySeat -> ctx.getString(R.string.durak_you_durak_short)
                    g.state == "finished" -> ctx.getString(R.string.chess_state_won)
                    else -> ctx.getString(R.string.chess_state_playing)
                } + (if (g.stake > 0) " · 🪙 ${g.stake}" else "") +
                    if (g.gone.isNotEmpty()) " · " + ctx.getString(R.string.gamesave_deleted_mark) else ""
                durakList.addView(saveRow(label, { if (g.state == "incoming") askDurak(ctx, g) else DurakGame.showOnline(ctx, g.id) }) {
                    // Незаконченная партия: удалить = выйти (сдаться); остальным - «удалить и у вас?».
                    runCatching { DurakNet.deleteByUser(ctx, g) }
                    refreshAll()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
        refreshAllRef = {
            refresh()
            refreshDurak()
        }
        val durakL: (DurakNet.Save) -> Unit = { refreshDurak() }
        DurakNet.listen(durakL)
        refreshDurak()
        dailyBtn.setOnClickListener {
            val n = Coins.claimDaily(ctx)
            if (n > 0) {
                CoinSound.jackpot(n)
                burst(frame, dailyBtn, coin, n)
                refresh()
            }
        }
        val coinsL: () -> Unit = { refresh() }
        val gamesL: (GameNet.ChessSave) -> Unit = { refresh() }
        Coins.listen(coinsL)
        GameNet.listen(gamesL)
        dialog.setOnDismissListener {
            Coins.unlisten(coinsL)
            GameNet.unlisten(gamesL)
            DurakNet.unlisten(durakL)
        }
        refresh()
        frame.addView(ScrollView(ctx).apply { addView(col) })
        frame.fitSystemBars()
        dialog.setContentView(frame)
        dialog.show()
    }

    /** Монетки разлетаются от кнопки и слетаются в кошелёк. */
    private fun burst(frame: FrameLayout, from: View, to: CoinView, n: Int) {
        val d = frame.resources.displayMetrics.density
        val a = IntArray(2)
        val b = IntArray(2)
        val f = IntArray(2)
        from.getLocationInWindow(a)
        to.getLocationInWindow(b)
        frame.getLocationInWindow(f)
        val size = (28 * d).toInt()
        repeat(n.coerceIn(3, 10)) { i ->
            val c = CoinView(frame.context).apply { period = 0.6f }
            frame.addView(c, FrameLayout.LayoutParams(size, size))
            val sx = a[0] - f[0] + from.width / 2f - size / 2
            val sy = a[1] - f[1] + from.height / 2f - size / 2
            c.x = sx
            c.y = sy
            val ang = Math.random() * 2 * PI
            val r = (40 + Math.random() * 60) * d
            c.animate().x((sx + kotlin.math.cos(ang) * r).toFloat()).y((sy + kotlin.math.sin(ang) * r).toFloat())
                .setDuration(260).setStartDelay(i * 40L).withEndAction {
                    c.animate().x(b[0] - f[0].toFloat()).y(b[1] - f[1].toFloat()).scaleX(0.6f).scaleY(0.6f)
                        .setDuration(420).withEndAction {
                            frame.removeView(c)
                            to.pulse()
                        }.start()
                }.start()
        }
    }

    /** Приглашение в партию: принять (ставка списывается) или отклонить. */
    fun askInvite(ctx: Context, s: GameNet.ChessSave) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.chess_invite_title)
            .setMessage(ctx.getString(R.string.chess_invite_text, s.oppName.ifBlank { "?" }, s.stake) + "\n\n" + ctx.getString(R.string.chess_invite_rules))
            .setPositiveButton(R.string.chess_accept) { _, _ ->
                if (GameNet.accept(ctx, s)) {
                    ChessGame.show(ctx, s.id)
                } else {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.coins_not_enough, s.stake, Coins.balance(ctx)), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.chess_decline) { _, _ -> GameNet.decline(ctx, s) }
            .show()
    }

    /** Приглашение в «Дурака»: принять (ставка списывается) или отклонить. */
    fun askDurak(ctx: Context, s: DurakNet.Save) {
        if (s.state != "incoming") {
            DurakGame.showOnline(ctx, s.id)
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.durak_invite_title)
            .setMessage(ctx.getString(R.string.durak_invite_text, s.names.getOrElse(0) { "?" }, s.players, s.stake) + "\n" + DurakGame.modeName(ctx, s.mode) + "\n\n" + ctx.getString(R.string.durak_invite_rules))
            .setPositiveButton(R.string.chess_accept) { _, _ ->
                if (DurakNet.accept(ctx, s)) {
                    DurakGame.showOnline(ctx, s.id)
                } else {
                    android.widget.Toast.makeText(ctx, ctx.getString(R.string.coins_not_enough, s.stake, Coins.balance(ctx)), android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.chess_decline) { _, _ -> DurakNet.decline(ctx, s) }
            .show()
    }

    /** С ботами: сколько соперников. Незаконченная партия - продолжить или начать заново. */
    private fun pickBots(ctx: Context) {
        val saved = ctx.getSharedPreferences("durak_local", Context.MODE_PRIVATE).getString("game", null)
            ?.let { runCatching { DurakEngine.fromJson(org.json.JSONObject(it)) }.getOrNull() }
        val labels = ArrayList<String>()
        if (saved != null && !saved.finished) labels += ctx.getString(R.string.durak_continue)
        labels += (1..3).map { ctx.getString(R.string.durak_bots_n, it) }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.durak_with_bots)
            .setItems(labels.toTypedArray()) { _, i ->
                val off = if (saved != null && !saved.finished) 1 else 0
                if (i < off) DurakGame.showLocal(ctx) else pickDurakMode(ctx, DurakGame.lastMode(ctx)) { m -> DurakGame.showLocal(ctx, bots = i - off + 1, fresh = true, mode = m) }
            }
            .show()
    }

    /** Режим «Дурака»: подкидной, переводной или простой - с коротким описанием правил. */
    fun pickDurakMode(ctx: Context, current: Int, then: (Int) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }
        var dlg: AlertDialog? = null
        val icons = arrayOf("🃏", "↪", "⚔")
        for (m in 0 until DurakEngine.MODES) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF4C3FB0.toInt(), 0xFF2A2170.toInt())).apply {
                    cornerRadius = 16 * d
                    if (m == current) setStroke(dp(2), 0xFFFDE047.toInt())
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dlg?.dismiss()
                    then(m)
                }
            }
            row.addView(TextView(ctx).apply {
                text = icons[m]
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
            val texts = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, 0, 0)
            }
            texts.addView(TextView(ctx).apply {
                text = DurakGame.modeName(ctx, m)
                textSize = 16f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            texts.addView(TextView(ctx).apply {
                setText(DurakGame.MODE_DESCS[m])
                textSize = 12.5f
                setTextColor(0xCCFFFFFF.toInt())
            })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        dlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.durak_pick_mode)
            .setView(ScrollView(ctx).apply { addView(box) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** «Дурак» с друзьями: 1-3 соперника из контактов в сети и ставка. */
    private fun pickDurak(ctx: Context) {
        val app = ctx.applicationContext as App
        val contacts = runCatching { runBlocking { app.component.contactRepository().getAll().first() } }.getOrDefault(emptyList())
            .filter { it.connectionStatus != com.stoyko.smessenger.core.vo.ConnectionStatus.None }
        if (contacts.isEmpty()) {
            android.widget.Toast.makeText(ctx, R.string.chess_no_online, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val names = contacts.map { it.name.ifBlank { it.publicKey.take(8) } }.toTypedArray()
        val checked = BooleanArray(names.size)
        val dlg = AlertDialog.Builder(ctx)
            .setTitle(R.string.durak_pick_players)
            .setMultiChoiceItems(names, checked) { di, which, on ->
                // Не больше трёх соперников (за столом до 4 игроков).
                if (on && checked.count { it } > 3) {
                    checked[which] = false
                    (di as AlertDialog).listView.setItemChecked(which, false)
                    android.widget.Toast.makeText(ctx, R.string.durak_max_players, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    checked[which] = on
                }
            }
            .setPositiveButton(R.string.durak_next) { _, _ ->
                val picked = contacts.indices.filter { checked[it] }.map { contacts[it].publicKey to names[it] }
                if (picked.isEmpty()) return@setPositiveButton
                pickDurakMode(ctx, DurakGame.lastMode(ctx)) { mode ->
                val stakes = intArrayOf(0, 5, 10, 20, 50, 100).filter { it <= Coins.balance(ctx) }
                val labels = stakes.map { if (it == 0) ctx.getString(R.string.chess_no_stake_btn) else "🪙 $it" }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.chess_pick_stake, Coins.balance(ctx)))
                    .setItems(labels) { _, j ->
                        val s = DurakNet.create(ctx, picked, stakes[j], mode)
                        if (s != null) {
                            android.widget.Toast.makeText(ctx, R.string.chess_invite_sent, android.widget.Toast.LENGTH_SHORT).show()
                            DurakGame.showOnline(ctx, s.id)
                        }
                    }
                    .show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dlg.show()
    }

    /** Выбор соперника из контактов и ставки. */
    private fun pickOpponent(ctx: Context) {
        val app = ctx.applicationContext as App
        val contacts = runCatching { runBlocking { app.component.contactRepository().getAll().first() } }.getOrDefault(emptyList())
            .filter { it.connectionStatus != com.stoyko.smessenger.core.vo.ConnectionStatus.None }
        if (contacts.isEmpty()) {
            android.widget.Toast.makeText(ctx, R.string.chess_no_online, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val names = contacts.map { it.name.ifBlank { it.publicKey.take(8) } }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.chess_pick_opp)
            .setItems(names) { _, i ->
                val c = contacts[i]
                val stakes = intArrayOf(0, 5, 10, 20, 50, 100).filter { it <= Coins.balance(ctx) }
                val labels = stakes.map { if (it == 0) ctx.getString(R.string.chess_no_stake_btn) else "🪙 $it" }.toTypedArray()
                AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.chess_pick_stake, Coins.balance(ctx)))
                    .setItems(labels) { _, j ->
                        // Своё имя не шлём: соперник видит нас под тем именем, что у него в контактах.
                        val s = GameNet.invite(ctx, c.publicKey, names[i], stakes[j], "")
                        if (s != null) {
                            android.widget.Toast.makeText(ctx, R.string.chess_invite_sent, android.widget.Toast.LENGTH_SHORT).show()
                            ChessGame.show(ctx, s.id)
                        }
                    }
                    .show()
            }
            .show()
    }
}
