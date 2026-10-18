// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.blackjack

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.stoyko.smessenger.App
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.games.CardArt
import com.stoyko.smessenger.games.CoinSound
import com.stoyko.smessenger.games.GameSfx
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.CoinView
import com.stoyko.smessenger.ui.DialogTracker
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Экран онлайн-блэкджека и его место в «Играх»: стол с дилером-человеком, ставки фишками,
 * ходы по очереди, чат игроков и дилера. Всё состояние - в [BjNet] (SavePlayer.save).
 */
object BjOnline {

    /** Открыть стол: приглашение - сначала спросить «Принять / Отклонить». */
    fun open(ctx: Context, s: BjNet.Save) {
        if (s.state == "incoming") ask(ctx, s) else show(ctx, s.id)
    }

    fun ask(ctx: Context, s: BjNet.Save) {
        DialogTracker.add(
            AlertDialog.Builder(ctx)
                .setTitle(R.string.bjack_invite_title)
                .setMessage(ctx.getString(R.string.bjack_invite_text, s.names.getOrElse(0) { "?" }) + "\n\n" + ctx.getString(R.string.bjack_invite_rules))
                .setPositiveButton(R.string.chess_accept) { _, _ ->
                    BjNet.accept(ctx, s)
                    show(ctx, s.id)
                }
                .setNegativeButton(R.string.chess_decline) { _, _ -> BjNet.decline(ctx, s) }
                .create(),
        ).show()
    }

    // ---------------- карточка приглашения в чате (GameInvite) ----------------

    /** Подпись карточки, «пригласили» ли меня и можно ли открыть стол; null - стола нет. */
    fun cardState(ctx: Context, id: String): Triple<String, Boolean, Boolean>? {
        val s = BjNet.load(ctx, id) ?: return null
        val st = when (s.state) {
            "incoming" -> ctx.getString(R.string.game_card_incoming)
            "declined" -> ctx.getString(R.string.chess_state_declined)
            "closed" -> ctx.getString(R.string.bjack_closed)
            else -> s.engineOrNull()?.let { ctx.getString(R.string.bjack_card_open, it.round) } ?: ctx.getString(R.string.chess_state_playing)
        }
        return Triple(st, s.state == "incoming", s.state == "playing" || s.state == "closed")
    }

    fun acceptFromCard(ctx: Context, id: String) {
        val s = BjNet.load(ctx, id) ?: return
        if (s.state != "incoming") {
            open(ctx, s)
            return
        }
        BjNet.accept(ctx, s)
        show(ctx, s.id)
    }

    fun declineFromCard(ctx: Context, id: String) {
        val s = BjNet.load(ctx, id) ?: return
        if (s.state == "incoming") BjNet.decline(ctx, s)
    }

    /** Правила («?»): общие правила блэкджека + как устроен онлайн-стол. */
    fun rules(ctx: Context): String = ctx.getString(R.string.bj_rules) + "\n\n" + ctx.getString(R.string.bjack_rules_online) + ctx.getString(R.string.bjack_rules_extra)

    fun showRules(ctx: Context) {
        DialogTracker.add(
            AlertDialog.Builder(ctx)
                .setTitle(R.string.bj_rules_title)
                .setMessage(rules(ctx))
                .setPositiveButton(android.R.string.ok, null)
                .create(),
        ).show()
    }

    // ---------------- «Игры»: кнопка и список столов ----------------

    /**
     * Добавить в карточку блэкджека в «Играх» кнопку «С друзьями - я дилер» и список столов.
     * Слушатель снимается сам, когда карточка уходит с экрана.
     */
    fun attachHub(ctx: Context, card: LinearLayout) {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        card.addView(
            Btn.nice(ctx, ctx.getString(R.string.bjack_with_friends), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { pickPlayers(ctx) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(10) },
        )
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(list)
        fun refresh() {
            list.removeAllViews()
            for (g in BjNet.all(ctx).filter { it.state != "declined" }.take(6)) {
                val e = g.engineOrNull()
                val who = if (g.isDealer) {
                    ctx.getString(R.string.bjack_row_dealer, g.names.drop(1).joinToString(", ").ifBlank { "?" })
                } else {
                    ctx.getString(R.string.bjack_row_player, g.names.getOrElse(0) { "?" })
                }
                val st = when {
                    g.state == "incoming" -> ctx.getString(R.string.chess_state_incoming)
                    g.state == "closed" -> ctx.getString(R.string.bjack_closed)
                    e != null -> ctx.getString(R.string.bjack_round_n, e.round)
                    else -> ""
                }
                val label = "$who · $st" + if (g.net != 0) " · 🪙 " + (if (g.net > 0) "+" else "−") + kotlin.math.abs(g.net) else ""
                list.addView(row(ctx, label, { open(ctx, g) }) {
                    confirmDelete(ctx, g) { runCatching { BjNet.deleteByUser(ctx, g) } }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
        val l: (BjNet.Save) -> Unit = { refresh() }
        list.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                BjNet.listen(l)
                refresh()
            }

            override fun onViewDetachedFromWindow(v: View) {
                BjNet.unlisten(l)
            }
        })
        refresh()
    }

    private fun row(ctx: Context, label: String, onOpen: () -> Unit, onDelete: () -> Unit): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(ctx).apply {
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
                setOnClickListener { onDelete() }
            }, LinearLayout.LayoutParams(dp(44), dp(40)))
        }
    }

    private fun confirmDelete(ctx: Context, s0: BjNet.Save, then: () -> Unit) {
        val s = BjNet.load(ctx, s0.id) ?: s0
        val stake = BjNet.atStake(s)
        val msg = when {
            s.isDealer && s.state == "playing" -> ctx.getString(R.string.bjack_delete_dealer)
            !s.isDealer && s.state == "playing" && stake > 0 -> ctx.getString(R.string.bjack_delete_player_stake, stake)
            !s.isDealer && s.state == "playing" -> ctx.getString(R.string.bjack_delete_player)
            else -> ctx.getString(R.string.games_delete_save)
        }
        DialogTracker.add(
            AlertDialog.Builder(ctx)
                .setTitle(R.string.games_delete_save)
                .setMessage(msg)
                .setPositiveButton(R.string.gamesave_delete) { _, _ -> then() }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }

    /** Пригласить 1-4 друзей из контактов в сети: я - дилер. */
    fun pickPlayers(ctx: Context) {
        val app = ctx.applicationContext as App
        val contacts = runCatching { runBlocking { app.component.contactRepository().getAll().first() } }.getOrDefault(emptyList())
            .filter { it.connectionStatus != com.stoyko.smessenger.core.vo.ConnectionStatus.None }
        if (contacts.isEmpty()) {
            Toast.makeText(ctx, R.string.chess_no_online, Toast.LENGTH_LONG).show()
            return
        }
        val names = contacts.map { it.name.ifBlank { it.publicKey.take(8) } }.toTypedArray()
        val checked = BooleanArray(names.size)
        DialogTracker.add(
            AlertDialog.Builder(ctx)
                .setTitle(R.string.bjack_pick_players)
                .setMultiChoiceItems(names, checked) { di, which, on ->
                    if (on && checked.count { it } >= BjEngine.MAX_PLAYERS) {
                        checked[which] = false
                        (di as AlertDialog).listView.setItemChecked(which, false)
                        Toast.makeText(ctx, R.string.bjack_max_players, Toast.LENGTH_SHORT).show()
                    } else {
                        checked[which] = on
                    }
                }
                .setPositiveButton(R.string.bjack_invite_btn) { _, _ ->
                    val picked = contacts.indices.filter { checked[it] }.map { contacts[it].publicKey to names[it] }
                    if (picked.isEmpty()) return@setPositiveButton
                    val s = BjNet.create(ctx, picked)
                    if (s != null) {
                        Toast.makeText(ctx, R.string.bjack_invite_sent, Toast.LENGTH_SHORT).show()
                        show(ctx, s.id)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create(),
        ).show()
    }

    // ---------------- экран стола ----------------

    fun show(ctx: Context, id: String) {
        val s0 = BjNet.load(ctx, id) ?: return
        GameSfx.prepare(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val main = Handler(Looper.getMainLooper())
        val dialog = DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val table = BjTableView(ctx)
        val prefs = ctx.getSharedPreferences("blackjack", Context.MODE_PRIVATE)
        var bet = prefs.getInt("last_online_bet", 10).coerceIn(0, BjEngine.MAX_BET)
        var cur = s0
        var lastPhase = -1
        var lastRound = -1
        var lastStateAt = SystemClock.uptimeMillis()
        // Ход отправлен - кнопки ждут ответа дилера (новая версия стола или 5 секунд).
        var waitVersion = -1
        var waitUntil = 0L

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF06281F.toInt())
        }
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(10), dp(4))
        }
        bar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            contentDescription = ctx.getString(android.R.string.cancel)
            setOnClickListener { dialog.safeDismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(TextView(ctx).apply {
            text = if (s0.isDealer) ctx.getString(R.string.bjack_title_dealer) else ctx.getString(R.string.bjack_title_player, s0.names.getOrElse(0) { "?" })
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun roundBtn(label: String, click: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener { click() }
        }
        val chatBtn = roundBtn("💬") {}
        chatBtn.contentDescription = ctx.getString(R.string.bjack_chat)
        val chatWrap = FrameLayout(ctx)
        chatWrap.addView(chatBtn, FrameLayout.LayoutParams(dp(34), dp(34)))
        val unreadBadge = TextView(ctx).apply {
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 9 * d
                setColor(0xFFE11D48.toInt())
            }
            setPadding(dp(4), 0, dp(4), 0)
            visibility = View.GONE
        }
        chatWrap.addView(unreadBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP or Gravity.END).apply { setMargins(0, -dp(4), -dp(4), 0) })
        chatWrap.clipChildren = false
        bar.clipChildren = false
        bar.addView(chatWrap, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) })
        bar.addView(roundBtn("?") { showRules(ctx) }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        val coin = CoinView(ctx)
        bar.addView(coin, LinearLayout.LayoutParams(dp(26), dp(26)))
        val wallet = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        bar.addView(wallet)
        // Кошелёк - сразу в магазин монет (фишки = монеты).
        coin.setOnClickListener { com.stoyko.smessenger.chat.CoinShop.show(ctx, CHIPS_FOCUS) }
        wallet.setOnClickListener { com.stoyko.smessenger.chat.CoinShop.show(ctx, CHIPS_FOCUS) }
        root.addView(bar)

        val stage = FrameLayout(ctx)
        stage.addView(table, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Новое сообщение, пока чат свёрнут, - всплывает пузырём поверх стола.
        val bubble = TextView(ctx).apply {
            textSize = 13.5f
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(0xE61E1B4B.toInt())
                setStroke(dp(1), 0x55FDE68A)
            }
            elevation = 6 * d
            visibility = View.GONE
        }
        stage.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(8), dp(70), 0) })
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val status = TextView(ctx).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(2))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        root.addView(status)
        // Таймер хода: подпись и полоса, которая тает за 15 секунд.
        val timerText = TextView(ctx).apply {
            textSize = 12.5f
            gravity = Gravity.CENTER
            setTextColor(0xFFFDE68A.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
        }
        val timerBar = TimerBar(ctx)
        val timerBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(2), dp(24), dp(2))
            visibility = View.GONE
            addView(timerText)
            addView(timerBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(3) })
        }
        root.addView(timerBox)

        // ---------- чат (сворачивается, чтобы на маленьких экранах хватало стола) ----------
        val chatPanel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val chatBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val chatScroll = ScrollView(ctx).apply { addView(chatBox) }
        chatPanel.addView(chatScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(if (ctx.resources.displayMetrics.heightPixels / d < 640) 76 else 110)).apply { setMargins(dp(12), dp(2), dp(12), 0) })
        val input = EditText(ctx).apply {
            setHint(R.string.bjack_chat_hint)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEND
            textSize = 14f
            setTextColor(Color.WHITE)
            setHintTextColor(0x88FFFFFF.toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(0x22FFFFFF)
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            filters = arrayOf(android.text.InputFilter.LengthFilter(300))
        }
        val chatRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        fun sendChat() {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return
            BjNet.load(ctx, id)?.let { BjNet.chat(ctx, it, t) }
            input.setText("")
        }
        input.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_SEND) {
                sendChat()
                true
            } else {
                false
            }
        }
        chatRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        chatRow.addView(Btn.nice(ctx, "➤", Btn.PALETTE[0]) { sendChat() }, LinearLayout.LayoutParams(dp(56), dp(44)).apply { marginStart = dp(8) })
        chatPanel.addView(chatRow)
        root.addView(chatPanel)
        var lastChat = -1
        var seenChat = s0.chat.size
        fun setUnread(n: Int) {
            unreadBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
            unreadBadge.text = if (n > 9) "9+" else n.toString()
        }
        chatBtn.setOnClickListener {
            val open = chatPanel.visibility != View.VISIBLE
            chatPanel.visibility = if (open) View.VISIBLE else View.GONE
            if (open) {
                seenChat = cur.chat.size
                setUnread(0)
                bubble.visibility = View.GONE
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            } else {
                runCatching {
                    (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
                }
            }
        }
        val hideBubble = Runnable { bubble.animate().alpha(0f).setDuration(250).withEndAction { bubble.visibility = View.GONE }.start() }

        // ---------- панель ставки игрока ----------
        lateinit var update: (BjNet.Save) -> Unit
        val betPanel = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val chipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(2))
        }
        betPanel.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(chipsRow)
        })
        // Фишки: на узких экранах - мельче, чтобы все номиналы и «Докупить» помещались.
        val chipDp = if (ctx.resources.displayMetrics.widthPixels / d < 360) 46 else 52
        BjChips.VALUES.forEachIndexed { i, v ->
            val chip = BjChips.ChipView(ctx, i)
            chip.setOnClickListener {
                val e = cur.engineOrNull() ?: return@setOnClickListener
                if (e.phase != BjEngine.BETS || e.betSt[cur.mySeat] != BjEngine.B_NONE) return@setOnClickListener
                val bal = Coins.balance(ctx)
                if (bet + v > bal) {
                    status.text = ctx.getString(R.string.bjack_not_enough, bet + v, bal)
                    GameSfx.play(ctx, GameSfx.S.BAD, 1f, 0.6f)
                    return@setOnClickListener
                }
                if (bet + v > e.maxBet) {
                    status.text = ctx.getString(R.string.bjack_max_bet, e.maxBet)
                    return@setOnClickListener
                }
                bet += v
                GameSfx.play(ctx, GameSfx.S.TICK, 1.2f, 0.7f)
                chip.pop()
                update(cur)
            }
            chipsRow.addView(chip, LinearLayout.LayoutParams(dp(chipDp), dp(chipDp)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
        }
        chipsRow.addView(buyChipsPill(ctx), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) })
        val betButtons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(12))
        }
        val clearBtn = Btn.nice(ctx, ctx.getString(R.string.bj_clear), Btn.PALETTE[1]) {
            bet = 0
            update(cur)
        }
        val placeBtn = Btn.nice(ctx, "", intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
            val s = BjNet.load(ctx, id) ?: return@nice
            if (bet <= 0) {
                status.setText(R.string.bjack_pick_chips)
                return@nice
            }
            prefs.edit().putInt("last_online_bet", bet).apply()
            when (BjNet.bet(ctx, s, bet)) {
                BjNet.OK -> {
                    GameSfx.play(ctx, GameSfx.S.PLACE, 1.1f)
                    waitVersion = s.version
                    waitUntil = SystemClock.uptimeMillis() + 5000
                }
                BjNet.NO_COINS -> status.text = ctx.getString(R.string.bjack_not_enough, bet, Coins.balance(ctx))
                BjNet.OFFLINE -> status.setText(R.string.bjack_dealer_offline)
                BjNet.LIMIT -> status.text = ctx.getString(R.string.bjack_limit, BjLedger.PER_PEER_DAY)
            }
        }
        betButtons.addView(clearBtn, LinearLayout.LayoutParams(0, dp(50), 1f))
        betButtons.addView(placeBtn, LinearLayout.LayoutParams(0, dp(50), 1.7f).apply { marginStart = dp(10) })
        betPanel.addView(betButtons)
        root.addView(betPanel)

        // ---------- ходы игрока ----------
        val playRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(12))
            isMotionEventSplittingEnabled = false
        }
        fun act(a: String) {
            if (table.busy) return
            if (SystemClock.uptimeMillis() < waitUntil && cur.version == waitVersion) return
            val s = BjNet.load(ctx, id) ?: return
            when (BjNet.act(ctx, s, a)) {
                BjNet.OK -> {
                    GameSfx.play(ctx, GameSfx.S.TICK, 1.0f, 0.6f)
                    waitVersion = s.version
                    waitUntil = SystemClock.uptimeMillis() + 5000
                    update(cur)
                }
                BjNet.NO_COINS -> status.text = ctx.getString(R.string.bjack_not_enough, s.engineOrNull()?.current()?.bet ?: 0, Coins.balance(ctx))
                BjNet.OFFLINE -> status.setText(R.string.bjack_dealer_offline)
                BjNet.LIMIT -> status.text = ctx.getString(R.string.bjack_limit, BjLedger.PER_PEER_DAY)
            }
        }
        val hit = Btn.nice(ctx, ctx.getString(R.string.bj_hit), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) { act("hit") }
        val stand = Btn.nice(ctx, ctx.getString(R.string.bj_stand), intArrayOf(0xFFFB7185.toInt(), 0xFFE11D48.toInt())) { act("stand") }
        val dbl = Btn.nice(ctx, ctx.getString(R.string.bj_double), intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())) { act("dbl") }
        val split = Btn.nice(ctx, ctx.getString(R.string.bj_split), intArrayOf(0xFFC084FC.toInt(), 0xFF9333EA.toInt())) { act("split") }
        for (b in listOf(hit, stand, dbl, split)) {
            b.maxLines = 1
            b.ellipsize = TextUtils.TruncateAt.END
        }
        playRow.addView(hit, LinearLayout.LayoutParams(0, dp(52), 1f))
        playRow.addView(stand, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        playRow.addView(dbl, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        playRow.addView(split, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(6) })
        root.addView(playRow)

        // ---------- кнопки дилера ----------
        val dealerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(12))
            isMotionEventSplittingEnabled = false
        }
        val dealerMain = Btn.nice(ctx, "", intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) {
            if (table.busy) return@nice
            val s = BjNet.load(ctx, id) ?: return@nice
            val e = s.engineOrNull() ?: return@nice
            when (e.phase) {
                BjEngine.BETS -> {
                    val need = e.pendingReserve()
                    val (n, rej) = BjNet.acceptBets(ctx, s)
                    if (rej > 0) {
                        val note = BjNet.load(ctx, id)?.engineOrNull()?.note
                        val msg = if (note == "limit") ctx.getString(R.string.bjack_limit_rejected)
                        else ctx.getString(R.string.bjack_bank_rejected, rej, need, Coins.balance(ctx))
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                    }
                    if (n > 0) GameSfx.play(ctx, GameSfx.S.PLACE, 0.9f)
                }
                BjEngine.READY -> if (BjNet.deal(ctx, s)) GameSfx.play(ctx, GameSfx.S.SHUFFLE)
                BjEngine.DEALER -> {
                    GameSfx.play(ctx, GameSfx.S.PLACE, 1.05f)
                    when {
                        !e.holeOpen -> BjNet.dealerStep(ctx, s, "open")
                        e.dealerMustHit() -> BjNet.dealerStep(ctx, s, "hit")
                        else -> BjNet.dealerStep(ctx, s, "stand")
                    }
                }
                BjEngine.DONE -> BjNet.newRound(ctx, s)
            }
        }
        val dealerAlt = Btn.nice(ctx, "", Btn.PALETTE[1]) {
            if (table.busy) return@nice
            val s = BjNet.load(ctx, id) ?: return@nice
            val e = s.engineOrNull() ?: return@nice
            if (e.phase == BjEngine.DEALER) {
                GameSfx.play(ctx, GameSfx.S.PLACE, 1.05f)
                BjNet.reveal(ctx, s)
            } else {
                BjNet.skip(ctx, s)
            }
        }
        dealerAlt.maxLines = 1
        dealerAlt.ellipsize = TextUtils.TruncateAt.END
        dealerRow.addView(dealerAlt, LinearLayout.LayoutParams(0, dp(50), 1f))
        dealerRow.addView(dealerMain, LinearLayout.LayoutParams(0, dp(50), 1.6f).apply { marginStart = dp(10) })
        root.addView(dealerRow)

        // ---------- доп. действия и быстрые фразы (и дилеру, и игрокам) ----------
        val pills = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        val pillsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(pills)
            visibility = View.GONE
        }
        root.addView(pillsScroll)
        fun pill(label: String, accent: Boolean = false, click: () -> Unit) = TextView(ctx).apply {
            text = label
            textSize = 13f
            maxLines = 1
            gravity = Gravity.CENTER
            setTextColor(if (accent) 0xFF3B2600.toInt() else Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(12), 0, dp(12), 0)
            background = if (accent) {
                GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFF1A8.toInt(), 0xFFF5B82E.toInt())).apply { cornerRadius = 18 * d }
            } else {
                GradientDrawable().apply {
                    cornerRadius = 18 * d
                    setColor(0x2EFFFFFF)
                    setStroke(dp(1), 0x33FFFFFF)
                }
            }
            setOnClickListener {
                if (table.busy) return@setOnClickListener
                it.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction { it.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
                click()
            }
        }
        fun say(resId: Int) {
            BjNet.load(ctx, id)?.let { BjNet.chat(ctx, it, ctx.getString(resId)) }
        }
        val limits = intArrayOf(10, 25, 50, 100, 250, 500)
        var pillsKey = ""
        fun fillPills(s: BjNet.Save, e: BjEngine) {
            val me = s.mySeat
            val canSur = !s.isDealer && e.phase == BjEngine.PLAY && e.active == me && e.canSurrender(me)
            val key = "${s.isDealer}|${e.phase}|${e.maxBet}|$canSur|${e.active}"
            if (key == pillsKey) return
            pillsKey = key
            pills.removeAllViews()
            val items = ArrayList<View>()
            if (s.isDealer) {
                when (e.phase) {
                    BjEngine.BETS -> {
                        items += pill(ctx.getString(R.string.bjack_shuffle)) {
                            BjNet.load(ctx, id)?.let { if (BjNet.shuffle(ctx, it)) GameSfx.play(ctx, GameSfx.S.SHUFFLE) }
                        }
                        items += pill(ctx.getString(R.string.bjack_limit_btn, e.maxBet), accent = true) {
                            val sv = BjNet.load(ctx, id) ?: return@pill
                            val cur0 = sv.engineOrNull()?.maxBet ?: BjEngine.MAX_BET
                            val next = limits.firstOrNull { it > cur0 } ?: limits[0]
                            if (!BjNet.setLimit(ctx, sv, next)) Toast.makeText(ctx, R.string.bjack_limit_wait, Toast.LENGTH_SHORT).show()
                        }
                        items += pill(ctx.getString(R.string.bjack_ph_bets)) { say(R.string.bjack_ph_bets) }
                        items += pill(ctx.getString(R.string.bjack_ph_closed)) { say(R.string.bjack_ph_closed) }
                    }
                    BjEngine.READY -> items += pill(ctx.getString(R.string.bjack_ph_luck)) { say(R.string.bjack_ph_luck) }
                    BjEngine.PLAY -> {
                        items += pill(ctx.getString(R.string.bjack_nudge), accent = true) {
                            BjNet.load(ctx, id)?.let { if (BjNet.nudge(ctx, it)) GameSfx.play(ctx, GameSfx.S.TICK, 1.4f, 0.8f) }
                        }
                        items += pill(ctx.getString(R.string.bjack_ph_luck)) { say(R.string.bjack_ph_luck) }
                    }
                    BjEngine.DEALER -> items += pill(ctx.getString(R.string.bjack_ph_wait)) { say(R.string.bjack_ph_wait) }
                    else -> {
                        items += pill(ctx.getString(R.string.bjack_ph_congrats)) { say(R.string.bjack_ph_congrats) }
                        items += pill(ctx.getString(R.string.bjack_ph_more)) { say(R.string.bjack_ph_more) }
                        items += pill(ctx.getString(R.string.bjack_ph_thanks)) { say(R.string.bjack_ph_thanks) }
                    }
                }
            } else {
                if (canSur) items += pill(ctx.getString(R.string.bj_surrender), accent = true) { act("sur") }
                if (e.phase == BjEngine.PLAY && e.active == me) items += pill(ctx.getString(R.string.bjack_ph_wait)) { say(R.string.bjack_ph_wait) }
                items += pill(ctx.getString(R.string.bjack_ph_luck)) { say(R.string.bjack_ph_luck) }
                items += pill(ctx.getString(R.string.bjack_ph_nice)) { say(R.string.bjack_ph_nice) }
                if (e.phase == BjEngine.DONE) items += pill(ctx.getString(R.string.bjack_ph_more)) { say(R.string.bjack_ph_more) }
            }
            items.forEachIndexed { i, v ->
                pills.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { if (i > 0) marginStart = dp(8) })
                // Появляются по очереди.
                v.alpha = 0f
                v.translationY = dp(8).toFloat()
                v.animate().alpha(1f).translationY(0f).setStartDelay(i * 40L).setDuration(220).start()
            }
        }
        var lastTimerSec = -1
        fun showTimer(s: BjNet.Save, e: BjEngine?) {
            val left = e?.timeLeft() ?: -1
            val on = s.state == "playing" && e != null && left >= 0 && (e.phase == BjEngine.PLAY || e.phase == BjEngine.DEALER)
            timerBox.visibility = if (on) View.VISIBLE else View.GONE
            if (!on || e == null) {
                lastTimerSec = -1
                return
            }
            val sec = ((left + 999) / 1000).toInt()
            timerBar.fraction = (left.toFloat() / BjEngine.TURN_MS).coerceIn(0f, 1f)
            val mine = !s.isDealer && e.phase == BjEngine.PLAY && e.active == s.mySeat
            timerText.text = when {
                e.phase == BjEngine.DEALER -> ctx.getString(R.string.bjack_timer_dealer, sec)
                mine -> ctx.getString(R.string.bjack_timer_me, sec)
                else -> ctx.getString(R.string.bjack_timer, s.names.getOrElse(e.active) { "?" }.take(14), sec)
            }
            timerText.setTextColor(if (sec <= 5) 0xFFFB7185.toInt() else 0xFFFDE68A.toInt())
            // Последние 5 секунд своего хода - тихий тик.
            if (mine && sec != lastTimerSec && sec in 1..5) GameSfx.play(ctx, GameSfx.S.TICK, 1.5f, 0.35f)
            lastTimerSec = sec
        }

        fun en(v: View, on: Boolean) {
            v.isEnabled = on
            v.alpha = if (on) 1f else 0.4f
        }

        update = { s ->
            if (s.version != cur.version || s.engine !== cur.engine) lastStateAt = SystemClock.uptimeMillis()
            cur = s
            val bal = Coins.balance(ctx)
            wallet.text = bal.toString()
            val e = s.engineOrNull()
            if (e != null) table.setState(e, s.mySeat, s.names, s.gone)
            val me = s.mySeat
            val live = s.state == "playing" && e != null
            val waiting = SystemClock.uptimeMillis() < waitUntil && s.version == waitVersion
            betPanel.visibility = View.GONE
            playRow.visibility = View.GONE
            dealerRow.visibility = View.GONE
            fun name(i: Int) = s.names.getOrElse(i) { "?" }
            status.text = when {
                s.state == "incoming" -> ctx.getString(R.string.game_card_incoming)
                s.state == "closed" -> ctx.getString(R.string.bjack_closed)
                s.state == "declined" -> ctx.getString(R.string.chess_state_declined)
                e == null -> ""
                s.isDealer -> when (e.phase) {
                    BjEngine.BETS -> {
                        val k = e.pendingCount()
                        val seated = s.accepted.count { it > 0 && it !in s.gone }
                        when {
                            seated == 0 -> ctx.getString(R.string.bjack_waiting_accept)
                            k == 0 -> ctx.getString(R.string.bjack_d_wait_bets, seated, s.players - 1)
                            else -> ctx.getString(R.string.bjack_d_bets, k, e.pendingReserve(), bal)
                        }
                    }
                    BjEngine.READY -> ctx.getString(R.string.bjack_d_ready)
                    BjEngine.PLAY -> ctx.getString(R.string.bjack_turn_of, name(e.active))
                    BjEngine.DEALER -> ctx.getString(R.string.bjack_d_reveal)
                    else -> e.dealerNet().let { if (it >= 0) ctx.getString(R.string.bjack_d_done_plus, it) else ctx.getString(R.string.bjack_d_done_minus, -it) }
                }
                else -> when (e.phase) {
                    BjEngine.BETS -> when (e.betSt[me]) {
                        BjEngine.B_PENDING -> ctx.getString(R.string.bjack_p_pending, e.bet[me])
                        BjEngine.B_REJECT -> ctx.getString(R.string.bjack_p_rejected)
                        else -> if (bet > 0) ctx.getString(R.string.bjack_st_bet, bet) else ctx.getString(R.string.bjack_pick_chips)
                    }
                    BjEngine.READY -> ctx.getString(if (e.inRound(me)) R.string.bjack_p_wait_deal else R.string.bjack_p_next_round)
                    BjEngine.PLAY -> {
                        val h = e.current()
                        if (e.active == me && h != null) {
                            if (e.hands[me].size > 1) ctx.getString(R.string.bj_st_hand, e.activeHand + 1, h.value) else ctx.getString(R.string.bj_st_turn, h.value)
                        } else if (!e.inRound(me)) {
                            ctx.getString(R.string.bjack_p_next_round)
                        } else {
                            ctx.getString(R.string.bjack_turn_of, name(e.active))
                        }
                    }
                    BjEngine.DEALER -> ctx.getString(R.string.bj_st_dealer)
                    else -> if (e.inRound(me)) {
                        val net = e.net(me)
                        if (net > 0) ctx.getString(R.string.bj_banner_win, net) else if (net < 0) ctx.getString(R.string.bjack_lost, -net) else ctx.getString(R.string.bj_banner_push)
                    } else {
                        ctx.getString(R.string.bjack_p_wait_round)
                    }
                }
            }
            if (live && e != null) {
                if (s.isDealer) {
                    dealerRow.visibility = View.VISIBLE
                    val skipping = e.phase == BjEngine.PLAY && e.active > 0
                    dealerAlt.visibility = if (skipping || e.phase == BjEngine.DEALER) View.VISIBLE else View.GONE
                    dealerAlt.text = if (e.phase == BjEngine.DEALER) ctx.getString(R.string.bjack_d_auto) else ctx.getString(R.string.bjack_skip, name(e.active).take(12))
                    dealerMain.visibility = if (e.phase == BjEngine.PLAY) View.GONE else View.VISIBLE
                    dealerMain.text = when (e.phase) {
                        BjEngine.BETS -> ctx.getString(R.string.bjack_accept_bets, e.pendingCount())
                        BjEngine.READY -> ctx.getString(R.string.bj_deal)
                        BjEngine.DEALER -> {
                            val dv = BjEngine.total(e.dealer).first
                            when {
                                !e.holeOpen -> ctx.getString(R.string.bjack_d_open)
                                e.dealerMustHit() -> ctx.getString(R.string.bjack_d_hit, dv)
                                else -> ctx.getString(R.string.bjack_d_stand, dv)
                            }
                        }
                        else -> ctx.getString(R.string.bjack_new_round)
                    }
                    en(dealerMain, e.phase != BjEngine.BETS || e.pendingCount() > 0)
                } else if (me in s.accepted && me !in s.gone) {
                    if (e.phase == BjEngine.BETS && e.betSt[me] == BjEngine.B_NONE) {
                        betPanel.visibility = View.VISIBLE
                        placeBtn.text = if (bet > 0) ctx.getString(R.string.bjack_place_bet, bet) else ctx.getString(R.string.bjack_place_bet_empty)
                        en(placeBtn, bet > 0 && !waiting)
                        en(clearBtn, bet > 0)
                    }
                    if (e.phase == BjEngine.PLAY && e.active == me) {
                        playRow.visibility = View.VISIBLE
                        val h = e.current()
                        val enough = h != null && bal >= h.bet
                        val can = h != null && !h.done && !waiting
                        en(hit, can)
                        en(stand, can)
                        en(dbl, can && e.canDouble(me) && enough)
                        split.visibility = if (e.canSplit(me)) View.VISIBLE else View.GONE
                        en(split, can && enough)
                    }
                }
            }
            if (live && e != null && (s.isDealer || (me in s.accepted && me !in s.gone))) {
                pillsScroll.visibility = View.VISIBLE
                fillPills(s, e)
            } else {
                pillsScroll.visibility = View.GONE
                pillsKey = ""
            }
            showTimer(s, e)
            // Итог раунда - один раз при переходе: звук, монетки, плашка.
            if (e != null && e.phase == BjEngine.DONE && lastPhase in 0 until BjEngine.DONE && lastRound == e.round) {
                val inGame = s.isDealer || e.inRound(me)
                val net = if (s.isDealer) e.dealerNet() else e.net(me)
                if (inGame) {
                    when {
                        net > 0 -> {
                            GameSfx.play(ctx, GameSfx.S.WIN)
                            table.postDelayed({ CoinSound.jackpot((net / 5).coerceIn(2, 10)) }, 1400)
                            val bj = !s.isDealer && e.hands[me].any { it.result == 2 }
                            table.banner(ctx.getString(if (bj) R.string.bj_banner_bj else R.string.bj_banner_win, net), 0xFFFDE047.toInt())
                        }
                        net < 0 -> {
                            GameSfx.play(ctx, GameSfx.S.LOSE)
                            table.banner(ctx.getString(R.string.bjack_lost, -net), 0xFFFB7185.toInt())
                        }
                        else -> table.banner(ctx.getString(R.string.bj_banner_push), Color.WHITE)
                    }
                }
            }
            if (e != null) {
                if (!s.isDealer && lastPhase == BjEngine.BETS && lastRound == e.round && e.betSt[me] == BjEngine.B_REJECT) {
                    table.banner(ctx.getString(R.string.bjack_p_rejected_banner), 0xFFFB7185.toInt())
                }
                if (!s.isDealer && e.phase == BjEngine.PLAY && e.active == me && !(lastPhase == BjEngine.PLAY && lastRound == e.round && table.tag == true)) {
                    // Мой ход начался - короткий сигнал.
                    GameSfx.play(ctx, GameSfx.S.STAR, 1.1f, 0.5f)
                }
                table.tag = e.phase == BjEngine.PLAY && e.active == me
                lastPhase = e.phase
                lastRound = e.round
            }
            if (s.chat.size != lastChat) {
                val grew = lastChat >= 0 && s.chat.size > lastChat
                lastChat = s.chat.size
                chatBox.removeAllViews()
                for (line in s.chat.takeLast(40)) {
                    val who = line.substringBefore(':')
                    val sys = who == "sys"
                    val seat = who.toIntOrNull()
                    chatBox.addView(TextView(ctx).apply {
                        val nm = if (seat == 0) "🎩 " + s.names.getOrElse(0) { "?" } else s.names.getOrElse(seat ?: 0) { "?" }
                        text = if (sys) line.substringAfter(':') else (if (seat == s.mySeat) "" else "$nm: ") + line.substringAfter(':')
                        textSize = 13f
                        setTextColor(if (sys) 0xFFFDE047.toInt() else Color.WHITE)
                        gravity = if (seat == s.mySeat) Gravity.END else Gravity.START
                        setPadding(dp(4), dp(1), dp(4), dp(1))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                if (chatPanel.visibility == View.VISIBLE) {
                    seenChat = s.chat.size
                } else {
                    val fromOthers = s.chat.drop(seenChat.coerceAtMost(s.chat.size)).count { !it.startsWith("${s.mySeat}:") }
                    setUnread(fromOthers)
                    val last = s.chat.lastOrNull()
                    if (grew && last != null && !last.startsWith("${s.mySeat}:")) {
                        val who = last.substringBefore(':')
                        val seat = who.toIntOrNull()
                        bubble.text = if (who == "sys") last.substringAfter(':') else (if (seat == 0) "🎩 " else "") + s.names.getOrElse(seat ?: 0) { "?" } + ": " + last.substringAfter(':')
                        bubble.alpha = 1f
                        bubble.visibility = View.VISIBLE
                        main.removeCallbacks(hideBubble)
                        main.postDelayed(hideBubble, 3500)
                    }
                }
            }
        }

        val listener: (BjNet.Save) -> Unit = { s -> if (s.id == id && dialog.isShowing) update(s) }
        val coinsL: () -> Unit = { if (dialog.isShowing) update(cur) }
        // Связь: дилер повторяет неподтверждённое состояние, игрок просит свежее, если давно не было.
        val tick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val s = BjNet.load(ctx, id)
                if (s != null) {
                    if (s.isDealer) {
                        BjNet.resendUnacked(ctx, id)
                    } else if (s.state == "playing" && SystemClock.uptimeMillis() - lastStateAt > 9000) {
                        BjNet.sync(ctx, s)
                        lastStateAt = SystemClock.uptimeMillis()
                    }
                }
                // Ожидание ответа дилера вышло - кнопки снова доступны.
                if (waitUntil > 0 && SystemClock.uptimeMillis() >= waitUntil) {
                    waitUntil = 0
                    update(cur)
                }
                main.postDelayed(this, 2500)
            }
        }
        // Таймер хода: обновляем полосу 5 раз в секунду; дилер по истечении сам двигает ход.
        val timerTick = object : Runnable {
            override fun run() {
                if (!dialog.isShowing) return
                val e = cur.engineOrNull()
                if (cur.isDealer && e != null && e.expired() && !table.busy) {
                    BjNet.load(ctx, id)?.let { if (BjNet.timeout(ctx, it)) GameSfx.play(ctx, GameSfx.S.TICK, 0.8f, 0.6f) }
                }
                showTimer(cur, e)
                main.postDelayed(this, 200)
            }
        }
        main.postDelayed(timerTick, 200)
        BjNet.listen(listener)
        Coins.listen(coinsL)
        BjNet.openTable = id
        root.fitSystemBars()
        dialog.setContentView(root)
        runCatching { dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) }
        dialog.setOnDismissListener {
            BjNet.unlisten(listener)
            Coins.unlisten(coinsL)
            if (BjNet.openTable == id) BjNet.openTable = null
            main.removeCallbacksAndMessages(null)
            CardArt.clear()
            BjChips.clear()
        }
        dialog.show()
        update(s0)
        com.stoyko.smessenger.games.ChessGame.showConnect(stage)
        BjNet.sync(ctx, s0)
        BjNet.here(ctx, s0)
        main.postDelayed(tick, 2500)
        // Уведомления этого стола больше не нужны - человек уже здесь.
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            for (t in listOf("i", "b", "t")) nm.cancel(("bj$t$id").hashCode())
        }
    }

    /** Магазин открывается сразу на «Фишках для Блэкджека». */
    const val CHIPS_FOCUS = "bj"

    /** «Докупить фишки» - фишки за столом это монеты, открываем магазин монет. */
    fun buyChipsPill(ctx: Context): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = ctx.getString(R.string.bjack_buy_chips)
            textSize = 13f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(0xFF3B2600.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFFFFF1A8.toInt(), 0xFFF5B82E.toInt())).apply {
                cornerRadius = 20 * d
                setStroke((1.5f * d).toInt(), 0xFFB07800.toInt())
            }
            elevation = 3 * d
            setOnClickListener { com.stoyko.smessenger.chat.CoinShop.show(ctx, CHIPS_FOCUS) }
        }
    }

    /** Полоса таймера: тает слева направо, к концу краснеет. */
    private class TimerBar(ctx: Context) : View(ctx) {
        var fraction = 1f
            set(v) {
                if (field != v) {
                    field = v
                    invalidate()
                }
            }
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val r = android.graphics.RectF()

        override fun onDraw(c: android.graphics.Canvas) {
            val h = height.toFloat()
            r.set(0f, 0f, width.toFloat(), h)
            p.color = 0x26FFFFFF
            c.drawRoundRect(r, h / 2, h / 2, p)
            r.right = width * fraction
            p.color = when {
                fraction > 0.5f -> 0xFF34D399.toInt()
                fraction > 0.33f -> 0xFFFBBF24.toInt()
                else -> 0xFFFB7185.toInt()
            }
            if (r.right > 0f) c.drawRoundRect(r, h / 2, h / 2, p)
        }
    }
}
