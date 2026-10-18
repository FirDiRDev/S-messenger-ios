// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss

/**
 * Партия в шахматы с контактом (или на одном телефоне - [local]).
 * Доска в цветах мессенджера, подсветка возможных ходов, последнего хода и шаха,
 * плавное перемещение фигур, чат соперников под доской, «Сдаться», «Сохранить и выйти».
 * При открытии онлайн-партии - анимация «Соединение… → 🔒 Подключено, защищено».
 */
object ChessGame {
    /** [bot] 1..3 - партия против компьютера (вы - белыми), 0 - обычная. */
    fun show(ctx: Context, id: String?, local: Boolean = false, bot: Int = 0) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val save: GameNet.ChessSave? = if (local) null else id?.let { GameNet.load(ctx, it) }
        if (!local && save == null) return
        botThinking = false
        botGen++
        val engine = ChessEngine()
        val localKey = if (bot > 0) "bot" else "local"
        // Партия с ботом: номер партии (награда за победу - один раз на партию) и уровень, на котором
        // она идёт (продолжили на уровне сложнее - награда всё равно по более лёгкому).
        var botLevel = bot
        if (local) {
            val prefs = ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE)
            prefs.getString(localKey, null)?.split(',')?.filter { it.isNotBlank() }?.let { engine.load(it) }
            if (bot > 0) {
                botLevel = if (engine.moves.isEmpty()) bot else minOf(bot, prefs.getInt("bot_level", bot).coerceIn(1, BOT_PRIZE.size))
                val e = prefs.edit().putInt("bot_level", botLevel)
                if (!prefs.contains("bot_gid")) e.putInt("bot_gid", 1)
                e.apply()
            }
        } else {
            engine.load(save!!.moves)
        }
        val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // Космический фон в стиле карт и подарков: туманность и мерцающие звёзды.
            background = Nebula(d)
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(6))
        }
        bar.addView(
            ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = null
                setOnClickListener { dialog.safeDismiss() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44)),
        )
        val title = TextView(ctx).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (save != null && save.stake > 0) {
            bar.addView(com.stoyko.smessenger.ui.CoinView(ctx), LinearLayout.LayoutParams(dp(26), dp(26)))
            bar.addView(TextView(ctx).apply {
                text = " ×2 = " + save.stake * 2
                setTextColor(0xFFFDE047.toInt())
                textSize = 14f
                setTypeface(Typeface.DEFAULT_BOLD)
            })
        }
        root.addView(bar)
        val status = TextView(ctx).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xDDFFFFFF.toInt())
            setPadding(dp(8), 0, dp(8), dp(6))
        }
        root.addView(status)
        val stage = FrameLayout(ctx)
        val board = Board(ctx, engine, if (bot > 0) 0 else if (local) -1 else save!!.color)
        stage.addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { setMargins(dp(8), 0, dp(8), 0) })

        // Чат соперников.
        val chatBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val chatScroll = ScrollView(ctx).apply { addView(chatBox) }
        val chatInput = EditText(ctx).apply {
            setHint(R.string.chess_chat_hint)
            setSingleLine()
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(0x22FFFFFF)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        if (!local) {
            root.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)).apply { setMargins(dp(12), dp(6), dp(12), 0) })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(12), 0)
            }
            row.addView(chatInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                Btn.nice(ctx, "➤", Btn.PALETTE[0]) {
                    val t = chatInput.text.toString().trim()
                    if (t.isNotEmpty() && save != null) {
                        GameNet.chat(ctx, save, t)
                        chatInput.setText("")
                    }
                },
                LinearLayout.LayoutParams(dp(56), dp(46)).apply { marginStart = dp(8) },
            )
            root.addView(row)
        }
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val resign = Btn.nice(ctx, ctx.getString(if (local) R.string.chess_new_game else R.string.chess_resign), intArrayOf(0xFFFB7185.toInt(), 0xFFE11D48.toInt())) {
            if (local) {
                engine.reset()
                val prefs = ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE)
                prefs.edit().remove(localKey).apply {
                    // Новая партия с ботом - новый номер (за неё снова можно получить награду).
                    if (bot > 0) {
                        botLevel = bot
                        putInt("bot_level", bot).putInt("bot_gid", prefs.getInt("bot_gid", 1) + 1)
                    }
                }.apply()
                botGen++
                botThinking = false
                renderRef?.invoke()
                return@nice
            }
            val s = save ?: return@nice
            if (s.result != null) return@nice
            when (s.state) {
                // Соперник ещё не ответил - не «сдаться» (ставок ещё нет), а отозвать приглашение.
                "invited" -> AlertDialog.Builder(ctx)
                    .setMessage(R.string.gamesb_chess_cancel_invite)
                    .setPositiveButton(R.string.gamesb_chess_cancel_invite_yes) { _, _ ->
                        runCatching { GameNet.deleteByUser(ctx, s) }
                        dialog.safeDismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                "playing" -> AlertDialog.Builder(ctx)
                    .setMessage(ctx.getString(R.string.chess_resign_confirm, s.stake))
                    .setPositiveButton(R.string.chess_resign) { _, _ ->
                        GameNet.resign(ctx, s)
                        // Итог - на экране сразу, не дожидаясь слушателя.
                        renderRef?.invoke()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        val exit = Btn.nice(ctx, ctx.getString(R.string.chess_save_exit), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { dialog.safeDismiss() }
        actions.addView(resign, LinearLayout.LayoutParams(0, dp(50), 1f))
        actions.addView(exit, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(10) })
        root.addView(actions)

        fun render() {
            val st = engine.status()
            if (bot > 0 && local) {
                title.text = ctx.getString(R.string.chess_vs_bot, ctx.getString(BOT_LEVELS[bot - 1]))
                status.text = when {
                    st == ChessEngine.Status.CHECKMATE -> ctx.getString(if (engine.side == 1) R.string.chess_bot_you_won else R.string.chess_bot_you_lost)
                    st != ChessEngine.Status.PLAYING -> ctx.getString(R.string.chess_draw_plain)
                    botThinking -> ctx.getString(R.string.chess_bot_thinking)
                    else -> ctx.getString(R.string.chess_your_turn) + if (engine.inCheck()) " · " + ctx.getString(R.string.chess_check) else ""
                }
                board.myTurn = st == ChessEngine.Status.PLAYING && engine.side == 0 && !botThinking
                board.invalidate()
                return
            }
            val s = save
            title.text = if (local) ctx.getString(R.string.chess_local) else ctx.getString(R.string.chess_vs, s!!.oppName.ifBlank { "?" })
            status.text = when {
                s != null && s.state == "invited" -> ctx.getString(R.string.chess_waiting)
                s != null && s.state == "declined" -> ctx.getString(R.string.chess_declined)
                s != null && s.result != null -> ctx.getString(
                    when (s.result) {
                        1 -> R.string.chess_won
                        -1 -> R.string.chess_lost
                        else -> R.string.chess_draw
                    },
                    s.stake * (if (s.result == 1) 2 else if (s.result == 0) 1 else 0),
                ) + reasonText(ctx, s.reason)
                st == ChessEngine.Status.CHECKMATE -> ctx.getString(R.string.chess_mate)
                st != ChessEngine.Status.PLAYING -> ctx.getString(R.string.chess_draw_plain)
                local -> ctx.getString(if (engine.side == 0) R.string.chess_turn_white else R.string.chess_turn_black) +
                    if (engine.inCheck()) " · " + ctx.getString(R.string.chess_check) else ""
                engine.side == s!!.color -> ctx.getString(R.string.chess_your_turn) + if (engine.inCheck()) " · " + ctx.getString(R.string.chess_check) else ""
                else -> ctx.getString(R.string.chess_their_turn)
            }
            board.myTurn = when {
                local -> st == ChessEngine.Status.PLAYING
                s == null -> false
                else -> s.state == "playing" && s.result == null && engine.side == s.color && st == ChessEngine.Status.PLAYING
            }
            // «Сдаться» - только пока партия идёт (или «отозвать», пока соперник не ответил).
            if (s != null) {
                val active = s.result == null && (s.state == "playing" || s.state == "invited")
                resign.alpha = if (active) 1f else 0.4f
                resign.isEnabled = active
            }
            if (s != null) {
                chatBox.removeAllViews()
                for (line in s.chat.takeLast(30)) {
                    val mine = line.startsWith("me:")
                    val sys = line.startsWith("sys:")
                    chatBox.addView(TextView(ctx).apply {
                        text = line.substringAfter(':')
                        textSize = 13f
                        setTextColor(if (sys) 0xFFFDE047.toInt() else Color.WHITE)
                        gravity = if (mine) Gravity.END else Gravity.START
                        setPadding(dp(4), dp(2), dp(4), dp(2))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            }
            board.invalidate()
        }
        renderRef = { render() }
        // Партия с ботом закончилась: статистика и награда за победу - ровно один раз на партию.
        fun botEnded() {
            if (bot <= 0) return
            val st = engine.status()
            if (st == ChessEngine.Status.PLAYING) return
            val prefs = ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE)
            val gid = prefs.getInt("bot_gid", 1)
            if (prefs.getInt("bot_done_gid", 0) == gid) return
            prefs.edit().putInt("bot_done_gid", gid).commit()
            // Мат при ходе бота (чёрные) - победа.
            val won = st == ChessEngine.Status.CHECKMATE && engine.side == 1
            val lost = st == ChessEngine.Status.CHECKMATE && engine.side == 0
            runCatching { com.stoyko.smessenger.games.stats.GameStats.chessBotEnd(ctx, if (won) 1 else if (lost) -1 else 0) }
            if (won) {
                val prize = BOT_PRIZE[(botLevel - 1).coerceIn(0, BOT_PRIZE.size - 1)]
                com.stoyko.smessenger.chat.Coins.add(ctx, prize)
                Toast.makeText(ctx, ctx.getString(R.string.chess_bot_prize, prize), Toast.LENGTH_LONG).show()
            }
        }
        // Ход бота - в фоне (расчёт до нескольких секунд), потом - на доске с анимацией.
        fun botTurn() {
            if (bot <= 0 || engine.side != 1 || engine.status() != ChessEngine.Status.PLAYING) return
            botThinking = true
            render()
            val my = ++botGen
            val copy = ChessEngine().apply { load(engine.moves.toList()) }
            Thread {
                val m = runCatching { copy.bestMove(bot) }.getOrNull()
                board.post {
                    if (my != botGen || !dialog.isShowing) return@post
                    botThinking = false
                    if (m != null && engine.play(m.uci())) {
                        ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE).edit().putString(localKey, engine.moves.joinToString(",")).apply()
                        board.animateLast()
                        board.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                        // Бот поставил мат (или пат/ничья) - итог партии. Победа игрока сюда не
                        // попадает: она считается сразу после его хода (см. onMove).
                        botEnded()
                    }
                    render()
                }
            }.apply { isDaemon = true; name = "chess-bot" }.start()
        }
        board.onMove = { uci ->
            if (local) {
                engine.play(uci)
                ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE).edit().putString(localKey, engine.moves.joinToString(",")).apply()
                // Мат боту - награда (ровно один раз за партию), пат/ничья - в статистику.
                botEnded()
                board.post { botTurn() }
            } else {
                val s = save!!
                engine.play(uci)
                GameNet.move(ctx, s, uci)
                GameNet.checkEnd(ctx, s, engine)
            }
            render()
        }
        // Ход соперника / чат / итог - пришло по сети.
        val listener: (GameNet.ChessSave) -> Unit = { upd ->
            // Своё же сохранение (ход/сообщение отсюда) - только перерисовать. Раньше список
            // сообщений очищался и копировался сам в себя - весь чат партии пропадал.
            if (save != null && upd === save) {
                render()
            } else if (save != null && upd.id == save.id) {
                save.state = upd.state
                save.result = upd.result
                save.reason = upd.reason
                // Расчёт ставок уже сделан - старая копия экрана не должна «забыть» об этом.
                if (upd.settled) save.settled = true
                if (upd.noStake) save.noStake = true
                // Чат не теряем: берём более полный список (сообщения только добавляются).
                if (upd.chat.size >= save.chat.size) {
                    val copy = upd.chat.toList()
                    save.chat.clear()
                    save.chat.addAll(copy)
                }
                if (upd.moves.size > engine.moves.size) {
                    val before = engine.moves.size
                    for (m in upd.moves.drop(before)) engine.play(m)
                    save.moves.clear()
                    save.moves.addAll(engine.moves)
                    board.animateLast()
                    board.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                }
                render()
            }
        }
        GameNet.listen(listener)
        com.stoyko.smessenger.ui.BackgroundSaver.register(listener) {
            if (local) ctx.getSharedPreferences("chess_local", Context.MODE_PRIVATE).edit().putString(localKey, engine.moves.joinToString(",")).commit()
            save?.let { GameNet.save(ctx, it) }
        }
        dialog.setOnDismissListener {
            botGen++
            botThinking = false
            GameNet.unlisten(listener)
            com.stoyko.smessenger.ui.BackgroundSaver.unregister(listener)
            save?.let { GameNet.save(ctx, it) }
        }
        render()
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.show()
        // Партия с ботом сохранилась на его ходе - пусть походит.
        board.post { botTurn() }
        if (save != null) {
            GameNet.sync(save)
            GameNet.here(ctx, save)
            showConnect(stage)
        }
    }

    private var botGen = 0
    private var botThinking = false
    private var renderRef: (() -> Unit)? = null
    val BOT_LEVELS = intArrayOf(R.string.chess_bot_easy, R.string.chess_bot_medium, R.string.chess_bot_hard)
    private val BOT_PRIZE = intArrayOf(20, 60, 150)

    /** Выбор уровня бота. */
    fun pickBot(ctx: Context) {
        val names = BOT_LEVELS.map { ctx.getString(it) }.toTypedArray<CharSequence>()
        com.stoyko.smessenger.ui.DialogTracker.add(
            AlertDialog.Builder(ctx).setTitle(R.string.chess_bot)
                .setItems(names) { _, i -> show(ctx, null, local = true, bot = i + 1) }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }

    private fun reasonText(ctx: Context, r: String): String = when (r) {
        "resign" -> " · " + ctx.getString(R.string.chess_by_resign)
        "mate" -> " · " + ctx.getString(R.string.chess_by_mate)
        "" -> ""
        else -> " · " + ctx.getString(R.string.chess_by_draw)
    }

    /** «Соединение… → 🔒 Подключено, защищено» поверх доски при входе в онлайн-партию. */
    internal fun showConnect(stage: FrameLayout) {
        val ctx = stage.context
        val d = ctx.resources.displayMetrics.density
        val ov = ConnectOverlay(ctx)
        stage.addView(ov, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ov.postDelayed({ ov.animate().alpha(0f).setDuration(350).withEndAction { stage.removeView(ov) }.start() }, 2300)
        ov.elevation = 10 * d
    }

    @SuppressLint("ViewConstructor")
    private class ConnectOverlay(ctx: Context) : View(ctx) {
        private val t0 = SystemClock.uptimeMillis()
        private val d = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(c: Canvas) {
            val t = (SystemClock.uptimeMillis() - t0) / 1000f
            val w = width.toFloat()
            val h = height.toFloat()
            p.color = 0xD90F0D24.toInt()
            c.drawRect(0f, 0f, w, h, p)
            val cy = h / 2
            val lx = w * 0.25f
            val rx = w * 0.75f
            val done = t > 1.3f
            // Два телефона и «мостик» между ними.
            p.color = 0xFF5B4BDB.toInt()
            c.drawCircle(lx, cy, 30 * d, p)
            c.drawCircle(rx, cy, 30 * d, p)
            text.textSize = 26 * d
            c.drawText("📱", lx, cy + 9 * d, text)
            c.drawText("📱", rx, cy + 9 * d, text)
            p.color = if (done) 0xFF34D399.toInt() else 0x667B6CF6
            p.strokeWidth = 4 * d
            c.drawLine(lx + 32 * d, cy, rx - 32 * d, cy, p)
            if (!done) {
                for (i in 0 until 3) {
                    val k = ((t * 1.2f + i / 3f) % 1f)
                    p.color = 0xFF38BDF8.toInt()
                    c.drawCircle(lx + 32 * d + (rx - lx - 64 * d) * k, cy, 5 * d, p)
                }
            } else {
                val s = ((t - 1.3f) / 0.3f).coerceIn(0f, 1f)
                text.textSize = 30 * d * (0.6f + 0.4f * s)
                c.drawText("🔒", w / 2, cy - 18 * d, text)
            }
            text.textSize = 16 * d
            c.drawText(
                context.getString(if (done) R.string.chess_connected else R.string.chess_connecting),
                w / 2, cy + 70 * d, text,
            )
            if (isAttachedToWindow) postInvalidateOnAnimation()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent) = true
    }

    /** Доска. [myColor]: 0 - я белые, 1 - чёрные (доска перевёрнута), -1 - игра на одном телефоне. */
    @SuppressLint("ViewConstructor")
    /** Фон партии: тёмно-синяя туманность с фиолетовыми и золотыми облаками и звёздами. */
    private class Nebula(private val d: Float) : android.graphics.drawable.Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rnd = java.util.Random(7)
        private val stars = Array(70) { floatArrayOf(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 1.6f, rnd.nextFloat() * 6.28f) }
        private val start = SystemClock.uptimeMillis()
        private var bg: android.graphics.Shader? = null
        private var lastH = -1

        override fun draw(c: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (bg == null || lastH != b.height()) {
                bg = LinearGradient(0f, 0f, w * 0.3f, h, intArrayOf(0xFF0B0A26.toInt(), 0xFF1E1452.toInt(), 0xFF120C33.toInt()), null, Shader.TileMode.CLAMP)
                lastH = b.height()
            }
            p.shader = bg
            c.drawRect(b, p)
            // Облака туманности.
            p.shader = RadialGradient(w * 0.15f, h * 0.2f, w * 0.7f, 0x557C3AED, 0x007C3AED, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = RadialGradient(w * 0.9f, h * 0.75f, w * 0.75f, 0x44F59E0B, 0x00F59E0B, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = RadialGradient(w * 0.6f, h * 0.05f, w * 0.5f, 0x3322D3EE, 0x0022D3EE, Shader.TileMode.CLAMP)
            c.drawRect(b, p)
            p.shader = null
            val live = com.stoyko.smessenger.ui.Kinetics.enabled
            val t = if (live) (SystemClock.uptimeMillis() - start) / 1000f else 0f
            for (s in stars) {
                val a = 0.35f + 0.65f * (0.5f + 0.5f * kotlin.math.sin(t * 1.4f + s[3]))
                p.color = Color.WHITE
                p.alpha = (a * 200).toInt()
                c.drawCircle(b.left + s[0] * w, b.top + s[1] * h, s[2] * d * 0.8f, p)
            }
            p.alpha = 255
            // Звёзды мерцают медленно (5 кадров/с) - почти не тратит батарею.
            if (live) scheduleSelf({ invalidateSelf() }, SystemClock.uptimeMillis() + if (com.stoyko.smessenger.ui.EnergySaver.on) 500L else 200L)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /**
     * Доска в стиле карт и подарков S-messenger: золотая рама с гравировкой, клетки -
     * слоновая кость и ночной аметист, фигуры - золото и обсидиан с золотой каймой,
     * подсветка ходов звёздочками, взятие - вспышка искр.
     */
    private class Board(ctx: Context, private val e: ChessEngine, private val myColor: Int) : View(ctx) {
        private val d = resources.displayMetrics.density
        var myTurn = false
        var onMove: (String) -> Unit = {}
        private var sel = -1
        private var targets: List<ChessEngine.Move> = emptyList()
        private var animFrom = -1
        private var animTo = -1
        private var animAt = 0L
        private val light = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG)
        private val hl = Paint(Paint.ANTI_ALIAS_FLAG)
        private val piece = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val coord = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        private val r = RectF()
        private var cell = 0f
        private var ox = 0f
        private var oy = 0f

        /** Искры взятия: x, y, vx, vy, рождение, цвет. */
        private val sparks = ArrayList<FloatArray>()
        private var lastCount = -1

        private fun flipped() = myColor == 1

        private fun screenOf(sq: Int): Pair<Float, Float> {
            var f = sq % 8
            var rk = sq / 8
            if (flipped()) {
                f = 7 - f
                rk = 7 - rk
            }
            return (ox + f * cell) to (oy + (7 - rk) * cell)
        }

        private fun squareAt(x: Float, y: Float): Int {
            var f = ((x - ox) / cell).toInt()
            var rk = 7 - ((y - oy) / cell).toInt()
            if (x < ox || y < oy || f !in 0..7 || rk !in 0..7) return -1
            if (flipped()) {
                f = 7 - f
                rk = 7 - rk
            }
            return rk * 8 + f
        }

        private fun pieceCount() = e.board.count { it != 0 }

        fun animateLast() {
            val last = e.moves.lastOrNull() ?: return
            animFrom = (last[1] - '1') * 8 + (last[0] - 'a')
            animTo = (last[3] - '1') * 8 + (last[2] - 'a')
            animAt = SystemClock.uptimeMillis()
            checkCapture()
            invalidate()
        }

        /** Фигур стало меньше - было взятие: вспышка искр на клетке. */
        private fun checkCapture() {
            val n = pieceCount()
            if (lastCount >= 0 && n < lastCount && animTo >= 0 && cell > 0) {
                val (x, y) = screenOf(animTo)
                val now = SystemClock.uptimeMillis().toFloat()
                val rnd = java.util.Random()
                repeat(if (com.stoyko.smessenger.ui.EnergySaver.on) 8 else if (com.stoyko.smessenger.ui.LiteMode.on) 12 else 18) {
                    val a = rnd.nextFloat() * 6.283f
                    val sp = (0.05f + rnd.nextFloat() * 0.18f) * d
                    sparks += floatArrayOf(x + cell / 2, y + cell / 2, kotlin.math.cos(a) * sp, kotlin.math.sin(a) * sp, now, Float.fromBits(if (it % 3 == 0) 0xFFFFFFFF.toInt() else 0xFFFBBF24.toInt()))
                }
                GameSfx.play(context, GameSfx.S.TAKE, 1.2f, 0.8f)
            } else if (lastCount >= 0 && animTo >= 0) {
                GameSfx.play(context, GameSfx.S.PLACE, 1.1f, 0.7f)
            }
            lastCount = n
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            // Место под раму: доска чуть меньше экрана.
            val frame = 14 * d
            cell = (minOf(w, h) - frame * 2) / 8f
            ox = (w - cell * 8) / 2
            oy = (h - cell * 8) / 2
            piece.textSize = cell * 0.8f
            outline.textSize = cell * 0.8f
            outline.strokeWidth = cell * 0.06f
            coord.textSize = cell * 0.17f
            light.shader = LinearGradient(ox, oy, ox + cell * 8, oy + cell * 8, 0xFFFBF3DC.toInt(), 0xFFE8D5A6.toInt(), Shader.TileMode.CLAMP)
            dark.shader = LinearGradient(ox, oy, ox + cell * 8, oy + cell * 8, 0xFF4C3796.toInt(), 0xFF22195A.toInt(), Shader.TileMode.CLAMP)
            if (lastCount < 0) lastCount = pieceCount()
        }

        override fun onDraw(c: Canvas) {
            if (cell <= 0f) return
            val now = SystemClock.uptimeMillis()
            var anim = false
            // Рама: тень, золото с бликом, тонкая гравировка внутри.
            val fr = 12 * d
            r.set(ox - fr, oy - fr, ox + cell * 8 + fr, oy + cell * 8 + fr)
            hl.shader = null
            hl.color = 0x66000000
            c.drawRoundRect(r.left + 3 * d, r.top + 5 * d, r.right + 3 * d, r.bottom + 5 * d, 16 * d, 16 * d, hl)
            hl.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
                intArrayOf(0xFFFFF1B8.toInt(), 0xFFE8A317.toInt(), 0xFFFFD86B.toInt(), 0xFFB7791F.toInt(), 0xFFFFE9A8.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 16 * d, 16 * d, hl)
            hl.shader = null
            hl.style = Paint.Style.STROKE
            hl.strokeWidth = 1.2f * d
            hl.color = 0xAA7A4A0E.toInt()
            r.inset(fr * 0.45f, fr * 0.45f)
            c.drawRoundRect(r, 10 * d, 10 * d, hl)
            hl.style = Paint.Style.FILL
            // Клетки.
            for (sq in 0 until 64) {
                val (x, y) = screenOf(sq)
                val isLight = (sq / 8 + sq % 8) % 2 == 1
                c.drawRect(x, y, x + cell, y + cell, if (isLight) light else dark)
            }
            // Мягкий блик по всей доске.
            hl.shader = RadialGradient(ox + cell * 2, oy + cell * 1.5f, cell * 7, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(ox, oy, ox + cell * 8, oy + cell * 8, hl)
            hl.shader = null
            // Последний ход - золотая подсветка.
            e.moves.lastOrNull()?.let { m ->
                for (s in intArrayOf((m[1] - '1') * 8 + (m[0] - 'a'), (m[3] - '1') * 8 + (m[2] - 'a'))) {
                    val (x, y) = screenOf(s)
                    hl.color = 0x66FBBF24
                    c.drawRect(x, y, x + cell, y + cell, hl)
                }
            }
            // Шах - пульсирующее красное свечение под королём.
            if (e.inCheck()) {
                val k = e.kingSquare(e.side)
                if (k >= 0) {
                    val (x, y) = screenOf(k)
                    val pulse = 0.75f + 0.25f * kotlin.math.sin(now / 160f)
                    hl.shader = RadialGradient(x + cell / 2, y + cell / 2, cell * 0.75f * pulse, 0xDDFF3B5C.toInt(), 0x00FF3B5C, Shader.TileMode.CLAMP)
                    c.drawRect(x - cell * 0.2f, y - cell * 0.2f, x + cell * 1.2f, y + cell * 1.2f, hl)
                    hl.shader = null
                    anim = true
                }
            }
            if (sel >= 0) {
                val (x, y) = screenOf(sel)
                hl.shader = RadialGradient(x + cell / 2, y + cell / 2, cell * 0.7f, 0xAA67E8F9.toInt(), 0x0067E8F9, Shader.TileMode.CLAMP)
                c.drawRect(x, y, x + cell, y + cell, hl)
                hl.shader = null
                val pulse = 0.85f + 0.15f * kotlin.math.sin(now / 180f)
                for (m in targets) {
                    val (tx, ty) = screenOf(m.to)
                    if (e.board[m.to] != 0) {
                        hl.style = Paint.Style.STROKE
                        hl.strokeWidth = cell * 0.07f
                        hl.color = 0xEEFBBF24.toInt()
                        c.drawCircle(tx + cell / 2, ty + cell / 2, cell * 0.43f, hl)
                        hl.style = Paint.Style.FILL
                    } else {
                        hl.shader = RadialGradient(tx + cell / 2, ty + cell / 2, cell * 0.22f * pulse, 0xFFFFE9A8.toInt(), 0x00F59E0B, Shader.TileMode.CLAMP)
                        c.drawCircle(tx + cell / 2, ty + cell / 2, cell * 0.22f * pulse, hl)
                        hl.shader = null
                        hl.color = 0xFFF6C344.toInt()
                        c.drawCircle(tx + cell / 2, ty + cell / 2, cell * 0.08f, hl)
                    }
                }
                anim = true
            }
            // Координаты - золотом на раме.
            coord.color = 0xFF5B3A0A.toInt()
            coord.textAlign = Paint.Align.CENTER
            for (i in 0 until 8) {
                val f = if (flipped()) 7 - i else i
                c.drawText(('a' + f).toString(), ox + i * cell + cell / 2, oy + cell * 8 + fr * 0.82f, coord)
                val rk = if (flipped()) i + 1 else 8 - i
                c.drawText(rk.toString(), ox - fr * 0.5f, oy + i * cell + cell / 2 + coord.textSize * 0.35f, coord)
            }
            // Фигуры (последняя ходившая - плавно «доезжает» и чуть подпрыгивает).
            val k = ((now - animAt) / 260f).coerceIn(0f, 1f)
            val ease = 1 - (1 - k) * (1 - k)
            for (sq in 0 until 64) {
                val p = e.board[sq]
                if (p == 0) continue
                var (x, y) = screenOf(sq)
                var lift = 0f
                if (sq == animTo && k < 1f && animFrom >= 0) {
                    val (fx, fy) = screenOf(animFrom)
                    x = fx + (x - fx) * ease
                    y = fy + (y - fy) * ease
                    lift = kotlin.math.sin(k * Math.PI.toFloat()) * cell * 0.18f
                }
                drawPiece(c, p, x, y - lift, sq == sel)
            }
            if (k < 1f) anim = true
            // Искры взятия.
            if (sparks.isNotEmpty()) {
                val it = sparks.iterator()
                while (it.hasNext()) {
                    val q = it.next()
                    val age = now - q[4]
                    if (age > 600) {
                        it.remove()
                        continue
                    }
                    hl.color = q[5].toRawBits()
                    hl.alpha = (255 * (1 - age / 600f)).toInt()
                    c.drawCircle(q[0] + q[2] * age, q[1] + q[3] * age + 0.0006f * d * age * age, 2.2f * d * (1 - age / 900f), hl)
                }
                hl.alpha = 255
                anim = true
            }
            if (anim && com.stoyko.smessenger.ui.Kinetics.enabled) postInvalidateOnAnimation()
        }

        private fun drawPiece(c: Canvas, p: Int, x: Float, y: Float, selected: Boolean) {
            // «\uFE0E» - текстовый вариант символа: на части телефонов ♟ иначе рисуется эмодзи.
            val glyph = when (e.typeOf(p)) {
                ChessEngine.PAWN -> "♟"
                ChessEngine.KNIGHT -> "♞"
                ChessEngine.BISHOP -> "♝"
                ChessEngine.ROOK -> "♜"
                ChessEngine.QUEEN -> "♛"
                else -> "♚"
            } + "\uFE0E"
            val white = e.colorOf(p) == 0
            val bx = x + cell / 2
            val by = y + cell * 0.79f
            // Мягкая тень на клетке.
            hl.shader = RadialGradient(bx, y + cell * 0.86f, cell * 0.36f, 0x55000000, 0x00000000, Shader.TileMode.CLAMP)
            c.drawOval(bx - cell * 0.36f, y + cell * 0.76f, bx + cell * 0.36f, y + cell * 0.96f, hl)
            hl.shader = null
            if (selected) {
                hl.shader = RadialGradient(bx, y + cell / 2, cell * 0.55f, 0x88FDE047.toInt(), 0x00FDE047, Shader.TileMode.CLAMP)
                c.drawCircle(bx, y + cell / 2, cell * 0.55f, hl)
                hl.shader = null
            }
            // Кайма: у светлых - тёмная бронза, у тёмных - золото.
            outline.color = if (white) 0xFF6B3F0C.toInt() else 0xFFF6C344.toInt()
            c.drawText(glyph, bx, by, outline)
            piece.shader = if (white) {
                LinearGradient(0f, y + cell * 0.1f, 0f, y + cell * 0.85f, intArrayOf(0xFFFFFDF2.toInt(), 0xFFFDE68A.toInt(), 0xFFE8A317.toInt()), null, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, y + cell * 0.1f, 0f, y + cell * 0.85f, intArrayOf(0xFF6D5BD0.toInt(), 0xFF2A1F66.toInt(), 0xFF0E0A24.toInt()), null, Shader.TileMode.CLAMP)
            }
            c.drawText(glyph, bx, by, piece)
            piece.shader = null
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked != MotionEvent.ACTION_UP) return true
            if (!myTurn) return true
            val sq = squareAt(ev.x, ev.y)
            if (sq < 0) return true
            val mine = myColor == -1 || e.colorOf(e.board[sq]) == myColor
            val move = targets.filter { it.to == sq }
            when {
                move.isNotEmpty() -> {
                    val promo = move.size > 1
                    if (promo) {
                        val names = arrayOf(context.getString(R.string.chess_queen), context.getString(R.string.chess_rook), context.getString(R.string.chess_bishop), context.getString(R.string.chess_knight))
                        val kinds = intArrayOf(ChessEngine.QUEEN, ChessEngine.ROOK, ChessEngine.BISHOP, ChessEngine.KNIGHT)
                        AlertDialog.Builder(context).setTitle(R.string.chess_promote)
                            .setItems(names) { _, i -> commit(move.first { it.promo == kinds[i] }) }
                            .show()
                    } else {
                        commit(move[0])
                    }
                }
                e.board[sq] != 0 && e.colorOf(e.board[sq]) == e.side && mine -> {
                    sel = sq
                    targets = e.legal().filter { it.from == sq }
                    performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                }
                else -> {
                    sel = -1
                    targets = emptyList()
                }
            }
            invalidate()
            return true
        }

        private fun commit(m: ChessEngine.Move) {
            animFrom = m.from
            animTo = m.to
            animAt = SystemClock.uptimeMillis()
            sel = -1
            targets = emptyList()
            onMove(m.uci())
            checkCapture()
            invalidate()
        }
    }
}
