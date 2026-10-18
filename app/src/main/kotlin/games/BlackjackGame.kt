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
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.stoyko.smessenger.R
import com.stoyko.smessenger.chat.Coins
import com.stoyko.smessenger.ui.Btn
import com.stoyko.smessenger.ui.CoinView
import com.stoyko.smessenger.ui.fitSystemBars
import com.stoyko.smessenger.ui.safeDismiss
import kotlin.math.abs
import kotlin.math.sin

/**
 * Блэкджек (21) за столом с картами «Дурака» (та же колода и рубашка, что выбрана в его
 * настройках). Колода 36 карт (6…туз), в башмаке - 4 колоды, перетасовка, когда осталось
 * меньше 40 карт.
 *
 * Правила: 6-10 - по номиналу, В/Д/К - 10, туз - 1 или 11. Дилер берёт до 17 (и на «мягких»
 * 17 - с тузом за 11). Блэкджек (туз + десятка с раздачи) платит 6 к 5 - честно для колоды
 * без мелких карт; обычная победа - 1 к 1, ничья - ставка возвращается.
 * Можно «Ещё», «Хватит», «Удвоить» (на первых двух картах: ставка ×2 и ровно одна карта)
 * и «Разделить» пару одного достоинства на две руки (тузы получают по одной карте).
 * Ставка 0 - тренировка без монет.
 */
object BlackjackGame {
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("blackjack", Context.MODE_PRIVATE)

    fun played(ctx: Context) = prefs(ctx).getInt("played", 0)
    fun wins(ctx: Context) = prefs(ctx).getInt("wins", 0)
    fun bestWin(ctx: Context) = prefs(ctx).getInt("best", 0)

    val CHIPS = intArrayOf(5, 10, 25, 50, 100)

    /** Очки карты: 0..3 → 6..9, 4..7 → 10 (десятка и картинки), 8 → туз (11). */
    fun points(card: Int): Int = when (val r = card % 9) {
        8 -> 11
        in 4..7 -> 10
        else -> r + 6
    }

    /** Сумма руки и «мягкая» ли она (туз считается за 11). */
    fun total(cards: List<Int>): Pair<Int, Boolean> {
        var t = cards.sumOf { points(it) }
        var aces = cards.count { it % 9 == 8 }
        while (t > 21 && aces > 0) {
            t -= 10
            aces--
        }
        return t to (aces > 0)
    }

    class Hand(var bet: Int) {
        val cards = ArrayList<Int>()
        var done = false
        var doubled = false
        var fromSplit = false
        /** Итог: 0 - ещё играет, 1 - победа, 2 - блэкджек, 3 - ничья, -1 - проигрыш, -2 - перебор. */
        var result = 0
        var payout = 0
        val value get() = total(cards).first
        val blackjack get() = !fromSplit && cards.size == 2 && value == 21
    }

    fun show(ctx: Context) {
        GameSfx.prepare(ctx)
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val main = Handler(Looper.getMainLooper())
        val dialog = com.stoyko.smessenger.ui.DialogTracker.add(Dialog(ctx, R.style.AppTheme_FullDialog))
        val table = BlackjackTable(ctx)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF06281F.toInt())
        }
        // Шапка: назад, название, правила, кошелёк.
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(12), dp(4))
        }
        bar.addView(ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = null
            setOnClickListener { dialog.safeDismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(TextView(ctx).apply {
            setText(R.string.bj_title)
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.DEFAULT_BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(ctx).apply {
            text = "?"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x33FFFFFF)
            }
            setOnClickListener {
                // Правила - вместе с разделом про онлайн-стол (дилер - живой человек).
                com.stoyko.smessenger.games.blackjack.BjOnline.showRules(ctx)
            }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        val coin = CoinView(ctx)
        bar.addView(coin, LinearLayout.LayoutParams(dp(26), dp(26)))
        val wallet = TextView(ctx).apply {
            textSize = 16f
            setTextColor(0xFFFDE047.toInt())
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(dp(6), 0, 0, 0)
        }
        bar.addView(wallet)
        // Кошелёк - в магазин, сразу к фишкам (фишки = монеты).
        coin.setOnClickListener { com.stoyko.smessenger.chat.CoinShop.show(ctx, com.stoyko.smessenger.games.blackjack.BjOnline.CHIPS_FOCUS) }
        wallet.setOnClickListener { com.stoyko.smessenger.chat.CoinShop.show(ctx, com.stoyko.smessenger.games.blackjack.BjOnline.CHIPS_FOCUS) }
        root.addView(bar)
        root.addView(table, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val status = TextView(ctx).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(2))
            maxLines = 2
        }
        root.addView(status)

        // ---------- состояние ----------
        val shoe = ArrayList<Int>()
        var bet = prefs(ctx).getInt("last_bet", 10)
        val hands = ArrayList<Hand>()
        val dealer = ArrayList<Int>()
        var active = 0
        /** 0 - ставка, 1 - ход игрока, 2 - ход дилера, 3 - итог. */
        var phase = 0
        var holeOpen = false

        fun refill() {
            shoe.clear()
            repeat(4) { for (c in 0 until 36) shoe += c }
            val rnd = java.security.SecureRandom()
            for (i in shoe.size - 1 downTo 1) {
                val j = rnd.nextInt(i + 1)
                val t = shoe[i]
                shoe[i] = shoe[j]
                shoe[j] = t
            }
            GameSfx.play(ctx, GameSfx.S.SHUFFLE)
        }
        refill()
        fun draw(): Int {
            if (shoe.isEmpty()) refill()
            return shoe.removeAt(shoe.size - 1)
        }

        // ---------- кнопки ----------
        val controls = FrameLayout(ctx)
        val betRow = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val playRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(12))
            isMotionEventSplittingEnabled = false
        }
        val chipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        betRow.addView(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = true
            addView(chipsRow)
        })
        val betButtons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(12))
        }
        betRow.addView(betButtons)
        controls.addView(betRow)
        controls.addView(playRow)
        root.addView(controls)

        lateinit var update: () -> Unit
        lateinit var dealerTurn: () -> Unit

        fun record(h: Hand) {
            val p = prefs(ctx)
            val e = p.edit().putInt("played", p.getInt("played", 0) + 1)
            if (h.result == 1 || h.result == 2) {
                e.putInt("wins", p.getInt("wins", 0) + 1)
                val profit = h.payout - h.bet
                if (profit > p.getInt("best", 0)) e.putInt("best", profit)
            }
            e.apply()
        }

        /** Расчёт всех рук после хода дилера. */
        fun settle() {
            if (phase == 3) return
            val dv = total(dealer).first
            val dealerBj = dealer.size == 2 && dv == 21
            var won = 0
            var anyWin = false
            var anyLose = false
            for (h in hands) {
                if (h.result == 0) {
                    h.result = when {
                        h.value > 21 -> -2
                        h.blackjack && !dealerBj -> 2
                        dealerBj && !h.blackjack -> -1
                        dv > 21 || h.value > dv -> 1
                        h.value == dv -> 3
                        else -> -1
                    }
                }
                h.payout = when (h.result) {
                    2 -> h.bet + h.bet * 6 / 5
                    1 -> h.bet * 2
                    3 -> h.bet
                    else -> 0
                }
                if (h.payout > 0) Coins.add(ctx, h.payout)
                won += h.payout - h.bet
                if (h.result == 1 || h.result == 2) anyWin = true
                if (h.result < 0) anyLose = true
                record(h)
            }
            phase = 3
            when {
                anyWin && won > 0 -> {
                    GameSfx.play(ctx, GameSfx.S.WIN)
                    table.postDelayed({ CoinSound.jackpot((won / 5).coerceIn(2, 10)) }, 350)
                    table.banner(ctx.getString(if (hands.any { it.result == 2 }) R.string.bj_banner_bj else R.string.bj_banner_win, won), 0xFFFDE047.toInt())
                }
                anyWin -> {
                    GameSfx.play(ctx, GameSfx.S.WIN)
                    table.banner(ctx.getString(R.string.bj_banner_win_free), 0xFFFDE047.toInt())
                }
                anyLose && hands.all { it.result < 0 } -> {
                    GameSfx.play(ctx, GameSfx.S.LOSE)
                    table.banner(ctx.getString(if (dv > 21) R.string.bj_banner_lose else R.string.bj_banner_dealer, dv), 0xFFFB7185.toInt())
                }
                else -> table.banner(ctx.getString(R.string.bj_banner_push), Color.WHITE)
            }
            update()
        }

        fun nextHand() {
            while (active < hands.size && hands[active].done) active++
            if (active >= hands.size) {
                phase = 2
                update()
                // Все руки сгорели - дилеру добирать незачем, только открыть карту.
                main.postDelayed({ dealerTurn() }, 450)
            } else {
                update()
            }
        }

        fun checkHand(h: Hand) {
            val v = h.value
            if (v > 21) {
                h.done = true
                h.result = -2
                GameSfx.play(ctx, GameSfx.S.BAD, 0.9f, 0.8f)
                table.handFlash(hands.indexOf(h), ctx.getString(R.string.bj_bust))
            } else if (v == 21 || h.doubled || (h.fromSplit && h.cards[0] % 9 == 8)) {
                h.done = true
            }
        }

        dealerTurn = {
            if (!holeOpen) {
                holeOpen = true
                table.setState(dealer, hands, active, holeOpen, phase)
                GameSfx.play(ctx, GameSfx.S.PLACE, 1.05f)
            }
            val alive = hands.any { it.result == 0 && it.value <= 21 && !it.blackjack }
            val (dv, soft) = total(dealer)
            if (alive && (dv < 17 || (dv == 17 && soft))) {
                main.postDelayed({
                    dealer += draw()
                    table.setState(dealer, hands, active, holeOpen, phase)
                    dealerTurn()
                }, 650)
            } else {
                main.postDelayed({ settle() }, 600)
            }
        }

        fun startRound() {
            if (phase == 1 || phase == 2) return
            val b = bet.coerceAtMost(Coins.balance(ctx))
            if (bet > 0 && !Coins.spend(ctx, bet)) {
                status.text = ctx.getString(R.string.coins_not_enough, bet, Coins.balance(ctx))
                return
            }
            if (b != bet) bet = b
            prefs(ctx).edit().putInt("last_bet", bet).apply()
            if (shoe.size < 40) refill()
            hands.clear()
            dealer.clear()
            active = 0
            holeOpen = false
            val h = Hand(bet)
            hands += h
            table.clearRound()
            // Раздача по правилам: игрок, дилер (открытая), игрок, дилер (закрытая).
            h.cards += draw()
            dealer += draw()
            h.cards += draw()
            dealer += draw()
            phase = 1
            table.setState(dealer, hands, active, holeOpen, phase)
            val dealerBj = total(dealer).first == 21
            if (h.blackjack || dealerBj) {
                // Блэкджек у кого-то с раздачи - сразу вскрываемся.
                h.done = true
                phase = 2
                main.postDelayed({
                    holeOpen = true
                    table.setState(dealer, hands, active, holeOpen, phase)
                    main.postDelayed({ settle() }, 700)
                }, 1500)
            }
            update()
        }

        // Фишки ставки - в стиле монеты мессенджера («S», золотой ободок, вставки по ребру).
        fun chip(value: Int, index: Int): View = com.stoyko.smessenger.games.blackjack.BjChips.ChipView(ctx, index).apply {
            setOnClickListener {
                if (phase == 1 || phase == 2) return@setOnClickListener
                if (bet + value > Coins.balance(ctx)) {
                    status.text = ctx.getString(R.string.coins_not_enough, bet + value, Coins.balance(ctx))
                    table.shakeChips()
                    return@setOnClickListener
                }
                bet = (bet + value).coerceAtMost(500)
                GameSfx.play(ctx, GameSfx.S.TICK, 1.2f, 0.7f)
                pop()
                if (phase == 3) {
                    phase = 0
                    table.clearRound()
                }
                update()
            }
        }
        val chipDp = if (ctx.resources.displayMetrics.widthPixels / d < 360) 46 else 52
        CHIPS.forEachIndexed { i, v ->
            chipsRow.addView(chip(v, i), LinearLayout.LayoutParams(dp(chipDp), dp(chipDp)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
        }
        // «Докупить фишки»: фишки за столом - это монеты, открываем магазин монет.
        chipsRow.addView(
            com.stoyko.smessenger.games.blackjack.BjOnline.buyChipsPill(ctx),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(8) },
        )
        val clearBtn = Btn.nice(ctx, ctx.getString(R.string.bj_clear), Btn.PALETTE[1]) {
            if (phase == 1 || phase == 2) return@nice
            bet = 0
            if (phase == 3) {
                phase = 0
                table.clearRound()
            }
            update()
        }
        val dealBtn = Btn.nice(ctx, ctx.getString(R.string.bj_deal), intArrayOf(0xFFFDE047.toInt(), 0xFFD97706.toInt())) { startRound() }
        betButtons.addView(clearBtn, LinearLayout.LayoutParams(0, dp(50), 1f))
        betButtons.addView(dealBtn, LinearLayout.LayoutParams(0, dp(50), 1.6f).apply { marginStart = dp(10) })

        val hit = Btn.nice(ctx, ctx.getString(R.string.bj_hit), intArrayOf(0xFF34D399.toInt(), 0xFF059669.toInt())) {
            if (phase != 1 || table.busy) return@nice
            val h = hands.getOrNull(active) ?: return@nice
            h.cards += draw()
            checkHand(h)
            table.setState(dealer, hands, active, holeOpen, phase)
            if (h.done) nextHand() else update()
        }
        val stand = Btn.nice(ctx, ctx.getString(R.string.bj_stand), intArrayOf(0xFFFB7185.toInt(), 0xFFE11D48.toInt())) {
            if (phase != 1 || table.busy) return@nice
            hands.getOrNull(active)?.done = true
            nextHand()
            table.setState(dealer, hands, active, holeOpen, phase)
        }
        val double = Btn.nice(ctx, ctx.getString(R.string.bj_double), intArrayOf(0xFF60A5FA.toInt(), 0xFF2563EB.toInt())) {
            if (phase != 1 || table.busy) return@nice
            val h = hands.getOrNull(active) ?: return@nice
            if (h.cards.size != 2) return@nice
            if (h.bet > 0 && !Coins.spend(ctx, h.bet)) {
                status.text = ctx.getString(R.string.coins_not_enough, h.bet, Coins.balance(ctx))
                return@nice
            }
            h.bet *= 2
            h.doubled = true
            h.cards += draw()
            checkHand(h)
            h.done = true
            table.setState(dealer, hands, active, holeOpen, phase)
            nextHand()
        }
        val split = Btn.nice(ctx, ctx.getString(R.string.bj_split), intArrayOf(0xFFC084FC.toInt(), 0xFF9333EA.toInt())) {
            if (phase != 1 || table.busy || hands.size > 1) return@nice
            val h = hands[0]
            if (h.cards.size != 2 || h.cards[0] % 9 != h.cards[1] % 9) return@nice
            if (h.bet > 0 && !Coins.spend(ctx, h.bet)) {
                status.text = ctx.getString(R.string.coins_not_enough, h.bet, Coins.balance(ctx))
                return@nice
            }
            val second = Hand(h.bet).apply { fromSplit = true }
            h.fromSplit = true
            second.cards += h.cards.removeAt(1)
            hands += second
            h.cards += draw()
            second.cards += draw()
            GameSfx.play(ctx, GameSfx.S.PLACE, 1.1f)
            checkHand(h)
            checkHand(second)
            active = 0
            table.setState(dealer, hands, active, holeOpen, phase)
            nextHand()
        }
        playRow.addView(hit, LinearLayout.LayoutParams(0, dp(52), 1f))
        playRow.addView(stand, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        playRow.addView(double, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })
        playRow.addView(split, LinearLayout.LayoutParams(0, dp(52), 1f).apply { marginStart = dp(8) })

        update = {
            wallet.text = Coins.balance(ctx).toString()
            val playing = phase == 1
            betRow.visibility = if (phase == 0 || phase == 3) View.VISIBLE else View.GONE
            playRow.visibility = if (phase == 1 || phase == 2) View.VISIBLE else View.GONE
            val h = hands.getOrNull(active)
            val enough = h != null && (h.bet == 0 || Coins.balance(ctx) >= h.bet)
            fun en(v: View, on: Boolean) {
                v.isEnabled = on
                v.alpha = if (on) 1f else 0.4f
            }
            en(hit, playing && h != null && !h.done)
            en(stand, playing && h != null && !h.done)
            en(double, playing && h != null && !h.done && h.cards.size == 2 && enough)
            split.visibility = if (playing && hands.size == 1 && h != null && h.cards.size == 2 && h.cards[0] % 9 == h.cards[1] % 9) View.VISIBLE else View.GONE
            en(split, enough)
            dealBtn.text = if (phase == 3) ctx.getString(R.string.bj_again, bet) else if (bet > 0) ctx.getString(R.string.bj_deal_bet, bet) else ctx.getString(R.string.bj_deal_free)
            table.bet = if (phase == 0 || phase == 3) bet else hands.sumOf { it.bet }
            status.text = when (phase) {
                0 -> if (bet > 0) ctx.getString(R.string.bj_st_bet, bet) else ctx.getString(R.string.bj_st_pick)
                1 -> if (hands.size > 1) ctx.getString(R.string.bj_st_hand, active + 1, h?.value ?: 0) else ctx.getString(R.string.bj_st_turn, h?.value ?: 0)
                2 -> ctx.getString(R.string.bj_st_dealer)
                else -> ctx.getString(R.string.bj_st_stats, played(ctx), wins(ctx))
            }
            table.setState(dealer, hands, active, holeOpen, phase)
        }

        val coinsL: () -> Unit = { wallet.text = Coins.balance(ctx).toString() }
        Coins.listen(coinsL)
        root.fitSystemBars()
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            Coins.unlisten(coinsL)
            main.removeCallbacksAndMessages(null)
            // Вышли посреди раздачи - руки доигрываются «Хватит», дилер добирает, расчёт честный.
            if (phase == 1 || phase == 2) {
                for (h in hands) h.done = true
                holeOpen = true
                val alive = hands.any { it.result == 0 && it.value <= 21 && !it.blackjack }
                while (alive) {
                    val (dv, soft) = total(dealer)
                    if (dv < 17 || (dv == 17 && soft)) dealer += draw() else break
                }
                runCatching { settle() }
            }
            CardArt.clear()
            com.stoyko.smessenger.games.blackjack.BjChips.clear()
        }
        dialog.show()
        update()
    }
}

/** Стол блэкджека: сукно, надписи по дуге, башмак, карты дилера и игрока с плавной раздачей. */
class BlackjackTable(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var cw = 0
    private var ch = 0

    /** Карта на столе: какую карту показываем и как летит. */
    private class Sprite(val card: Int, val owner: Int, val slot: Int) {
        var x = 0f
        var y = 0f
        var rot = 0f
        var face = 0f
        var scale = 1f
        var fx = 0f
        var fy = 0f
        var frot = 0f
        var fface = 0f
        var fscale = 1f
        var tx = Float.NaN
        var ty = 0f
        var trot = 0f
        var tface = 0f
        var tscale = 1f
        var t0 = 0L
        var dur = 0L
        var sound = false
        var landed = false
    }

    /** owner: -1 - дилер, 0.. - руки игрока. */
    private val sprites = ArrayList<Sprite>()
    private var dealer: List<Int> = emptyList()
    private var hands: List<BlackjackGame.Hand> = emptyList()
    private var active = 0
    private var holeOpen = false
    private var phase = 0
    var bet = 0
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val felt = Paint()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FDE68A
    }
    private val arcText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFDE68A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFDE047.toInt()
    }
    private val tmp = RectF()
    private val arc = Path()
    private var lastSound = 0L

    /** Фишки стопки ставки: готовые картинки и разложение суммы (пересчёт - только при смене ставки). */
    private var chipPx = 0
    private var chipBmp: Array<android.graphics.Bitmap?> = emptyArray()
    private val stackIdx = IntArray(10)
    private var stackN = 0
    private var stackFor = -1
    private val faceReady: () -> Unit = { invalidate() }

    private var bannerText = ""
    private var bannerColor = Color.WHITE
    private var bannerAt = 0L
    private val flashes = HashMap<Int, Pair<String, Long>>()
    private var chipShakeAt = 0L

    fun banner(msg: String, color: Int) {
        bannerText = msg
        bannerColor = color
        bannerAt = SystemClock.uptimeMillis() + 400
        invalidate()
    }

    fun handFlash(hand: Int, msg: String) {
        flashes[hand] = msg to SystemClock.uptimeMillis()
        invalidate()
    }

    fun shakeChips() {
        chipShakeAt = SystemClock.uptimeMillis()
        invalidate()
    }

    val busy: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            return sprites.any { now < it.t0 + it.dur }
        }

    /** Новая раздача: старые карты улетают в сброс (просто исчезают со стола). */
    fun clearRound() {
        sprites.clear()
        flashes.clear()
        bannerText = ""
        dealOrder = 0
        invalidate()
    }

    private var dealOrder = 0

    fun setState(dealer: List<Int>, hands: List<BlackjackGame.Hand>, active: Int, holeOpen: Boolean, phase: Int) {
        this.dealer = dealer
        this.hands = hands
        this.active = active
        this.holeOpen = holeOpen
        this.phase = phase
        // Карты, которых ещё нет на столе, - новые спрайты (вылетят из башмака).
        val want = ArrayList<Triple<Int, Int, Int>>()
        dealer.forEachIndexed { i, c -> want += Triple(c, -1, i) }
        hands.forEachIndexed { hi, h -> h.cards.forEachIndexed { i, c -> want += Triple(c, hi, i) } }
        // После «Разделить» вторая карта переходит в другую руку - переназначаем по месту.
        val keep = ArrayList<Sprite>()
        val pool = ArrayList(sprites)
        val now = SystemClock.uptimeMillis()
        // Раздача с начала - по очереди, как у крупье: игрок, дилер, игрок, дилер.
        val fresh = sprites.isEmpty() && want.size >= 4
        val order = if (fresh) listOf(want.indexOfFirst { it.second == 0 && it.third == 0 }, want.indexOfFirst { it.second == -1 && it.third == 0 },
            want.indexOfFirst { it.second == 0 && it.third == 1 }, want.indexOfFirst { it.second == -1 && it.third == 1 }) else emptyList()
        for ((idx, w) in want.withIndex()) {
            val (card, owner, slot) = w
            val old = pool.firstOrNull { it.card == card && it.owner == owner && it.slot == slot }
                ?: pool.firstOrNull { it.card == card && (it.owner == owner || (owner >= 0 && it.owner >= 0)) }
            if (old != null) {
                pool.remove(old)
                val moved = Sprite(card, owner, slot).also { s ->
                    s.x = old.x; s.y = old.y; s.rot = old.rot; s.face = old.face; s.scale = old.scale
                    s.tx = old.tx; s.ty = old.ty; s.trot = old.trot; s.tface = old.tface; s.tscale = old.tscale
                    s.fx = old.fx; s.fy = old.fy; s.frot = old.frot; s.fface = old.fface; s.fscale = old.fscale
                    s.t0 = old.t0; s.dur = old.dur; s.landed = old.landed; s.sound = old.sound
                }
                keep += moved
            } else {
                val s = Sprite(card, owner, slot)
                val (sx, sy) = shoePos()
                s.x = sx; s.y = sy; s.rot = -18f; s.face = 0f; s.scale = 0.8f
                s.fx = sx; s.fy = sy; s.frot = -18f; s.fface = 0f; s.fscale = 0.8f
                val k = if (fresh) order.indexOf(idx).coerceAtLeast(0) else dealOrder
                s.t0 = now + k * 300L + 60L
                s.dur = 420L
                s.sound = true
                if (!fresh) dealOrder++
                keep += s
            }
        }
        if (!fresh) dealOrder = 0
        sprites.clear()
        sprites += keep
        layoutTargets()
        invalidate()
    }

    private fun shoePos() = (width - cw * 0.62f) to (ch * 0.55f + 6 * d)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Высокие карты (руны) - уже, чтобы по высоте помещались так же (4.10.0).
        var big = minOf(w / 4.1f, 128 * d, h / 5.4f * (1.48f / CardArt.ratio(context)))
        if (CardArt.art(context)) big = minOf(big, CardArt.ART_W.toFloat())
        cw = big.toInt().coerceAtLeast((40 * d).toInt())
        ch = (cw * CardArt.ratio(context)).toInt()
        CardArt.prewarm(context, cw, ch)
        felt.shader = RadialGradient(w / 2f, h * 0.38f, maxOf(w, h) * 0.8f, intArrayOf(0xFF15803D.toInt(), 0xFF0B4D2E.toInt(), 0xFF052016.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = 3 * d
        rim.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(0xFFB45309.toInt(), 0xFFFDE68A.toInt(), 0xFFB45309.toInt()), null, Shader.TileMode.CLAMP)
        glow.strokeWidth = 2.5f * d
        ring.strokeWidth = 1.5f * d
        chipPx = (40 * d).toInt()
        chipBmp = Array(com.stoyko.smessenger.games.blackjack.BjChips.VALUES.size) { com.stoyko.smessenger.games.blackjack.BjChips.bitmap(it, chipPx) }
        layoutTargets()
    }

    private fun layoutTargets() {
        if (width == 0) return
        val now = SystemClock.uptimeMillis()
        val n = hands.size.coerceAtLeast(1)
        for (s in sprites) {
            val tx: Float
            val ty: Float
            val rot: Float
            val scale: Float
            val face: Float
            if (s.owner < 0) {
                val cnt = dealer.size
                val step = minOf(cw * 0.62f, (width - cw * 2.6f) / maxOf(1, cnt - 1))
                tx = width * 0.42f + (s.slot - (cnt - 1) / 2f) * step
                ty = height * 0.24f
                rot = (s.slot - (cnt - 1) / 2f) * 1.5f
                scale = 0.9f
                face = if (s.slot == 1 && !holeOpen) 0f else 1f
            } else {
                val h = hands.getOrNull(s.owner)
                val cnt = h?.cards?.size ?: 1
                val sc = if (n > 1) 0.8f else 1f
                val cx = if (n > 1) width * (if (s.owner == 0) 0.27f else 0.73f) else width / 2f
                val step = minOf(cw * 0.36f * sc, (width / n - cw * sc) / maxOf(1, cnt - 1))
                tx = cx + (s.slot - (cnt - 1) / 2f) * step
                ty = height * 0.66f - s.slot * 5 * d
                rot = (s.slot - (cnt - 1) / 2f) * 4f
                scale = sc
                face = 1f
            }
            if (s.tx.isNaN()) {
                // Новая карта: цель задаём, старт - по расписанию раздачи.
                s.tx = tx; s.ty = ty; s.trot = rot; s.tscale = scale; s.tface = face
                continue
            }
            if (s.tx != tx || s.ty != ty || s.trot != rot || s.tscale != scale || s.tface != face) {
                // Цель сменилась (разделили руку, открыли карту дилера) - летим с текущего места.
                if (now >= s.t0 + s.dur) {
                    s.fx = s.x; s.fy = s.y; s.frot = s.rot; s.fface = s.face; s.fscale = s.scale
                    s.t0 = now
                    s.dur = 380L
                }
                s.tx = tx; s.ty = ty; s.trot = rot; s.tscale = scale; s.tface = face
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), felt)
        if (cw == 0) return
        val now = SystemClock.uptimeMillis()
        var moving = false
        // Полукруглый борт и надписи стола.
        tmp.set(-width * 0.25f, -height * 0.55f, width * 1.25f, height * 0.94f)
        c.drawArc(tmp, 20f, 140f, false, rim)
        arc.reset()
        tmp.set(width * 0.06f, height * 0.06f, width * 0.94f, height * 0.88f)
        arc.addArc(tmp, 160f, -140f)
        arcText.textSize = 13 * d
        c.drawTextOnPath(context.getString(R.string.bj_felt_1), arc, 0f, -ch * 0.2f, arcText)
        arcText.textSize = 11 * d
        c.drawTextOnPath(context.getString(R.string.bj_felt_2), arc, 0f, -ch * 0.2f + 16 * d, arcText)
        // Башмак с колодой (рубашками) в правом верхнем углу.
        val back = CardArt.back(context, cw, ch)
        val (sx, sy) = shoePos()
        for (i in 0 until 4) {
            c.save()
            c.translate(sx + i * 1.2f * d, sy - i * 1.2f * d)
            c.rotate(-18f)
            c.scale(0.8f, 0.8f)
            c.drawBitmap(back, -cw / 2f, -ch / 2f, paint)
            c.restore()
        }
        chip.color = 0xAA3F1D0B.toInt()
        tmp.set(sx - cw * 0.5f, sy + ch * 0.18f, sx + cw * 0.5f, sy + ch * 0.48f)
        c.drawRoundRect(tmp, 8 * d, 8 * d, chip)
        // Ставка: стопка фишек в круге перед игроком.
        val bx = width / 2f
        val by = height * 0.66f + ch * 0.62f
        val shakeX = if (now - chipShakeAt < 400) sin((now - chipShakeAt) / 25f) * 6 * d * (1 - (now - chipShakeAt) / 400f) else 0f
        if (now - chipShakeAt < 400) moving = true
        c.drawCircle(bx + shakeX, by, 26 * d, ring)
        if (bet > 0) {
            // Стопка фишек (те же картинки, что на кнопках; готовятся один раз на размер).
            if (stackFor != bet) {
                stackFor = bet
                stackN = com.stoyko.smessenger.games.blackjack.BjChips.stack(bet, 8, stackIdx)
            }
            val half = chipPx / 2f
            for (k in 0 until stackN) {
                val b = chipBmp.getOrNull(stackIdx[k]) ?: continue
                c.drawBitmap(b, bx + shakeX - half, by - half - k * 3.5f * d, paint)
            }
            text.textSize = 13 * d
            c.drawText("🪙 $bet", bx + shakeX, by + 40 * d, text)
        }

        // Карты.
        for (s in sprites) {
            if (now < s.t0) {
                moving = true
                continue
            }
            if (now < s.t0 + s.dur) {
                moving = true
                if (s.sound) {
                    s.sound = false
                    if (now - lastSound > 60) {
                        lastSound = now
                        GameSfx.play(context, GameSfx.S.DEAL, 0.95f + (s.card % 5) * 0.05f, 0.8f)
                    }
                }
                val p = ((now - s.t0).toFloat() / s.dur).coerceIn(0f, 1f)
                val e = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).let { it * it * it } / 2f
                s.x = s.fx + (s.tx - s.fx) * e
                s.y = s.fy + (s.ty - s.fy) * e
                s.rot = s.frot + (s.trot - s.frot) * e
                s.scale = s.fscale + (s.tscale - s.fscale) * e
                s.face = s.fface + (s.tface - s.fface) * ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
            } else {
                if (!s.landed) {
                    s.landed = true
                    GameSfx.play(context, GameSfx.S.PLACE, 0.95f + (s.card % 4) * 0.05f, 0.7f)
                }
                if (s.tx.isNaN()) continue
                s.x = s.tx; s.y = s.ty; s.rot = s.trot; s.scale = s.tscale; s.face = s.tface
            }
            val flying = now < s.t0 + s.dur
            val lift = if (flying) sin(((now - s.t0).toFloat() / s.dur).coerceIn(0f, 1f) * Math.PI.toFloat()) else 0f
            val flip = abs(1f - 2f * s.face).coerceAtLeast(0.03f)
            val ready = if (s.face >= 0.5f) CardArt.faceOrNull(context, s.card % 36, cw, ch, faceReady) else null
            if (s.face >= 0.5f && ready == null) moving = true
            val bmp = ready ?: back
            c.save()
            c.translate(s.x, s.y - lift * 8 * d)
            c.rotate(s.rot)
            val sc = s.scale * (1f + 0.08f * lift)
            c.scale(sc * flip, sc)
            val so = (3 + 8 * lift) * d
            shadow.alpha = 70
            tmp.set(-cw / 2f + so * 0.6f, -ch / 2f + so, cw / 2f + so * 0.6f, ch / 2f + so)
            c.drawRoundRect(tmp, cw * 0.09f, cw * 0.09f, shadow)
            c.drawBitmap(bmp, -cw / 2f, -ch / 2f, paint)
            c.restore()
        }

        // Очки рук.
        fun badge(x: Float, y: Float, label: String, color: Int, hi: Boolean) {
            text.textSize = 14 * d
            val w = text.measureText(label) / 2 + 10 * d
            chip.color = color
            tmp.set(x - w, y - 15 * d, x + w, y + 7 * d)
            c.drawRoundRect(tmp, 11 * d, 11 * d, chip)
            if (hi) c.drawRoundRect(tmp, 11 * d, 11 * d, glow)
            c.drawText(label, x, y, text)
        }
        val settled = sprites.none { now < it.t0 + it.dur }
        if (dealer.isNotEmpty() && settled) {
            val shown = if (holeOpen) dealer else dealer.take(1)
            val (v, soft) = BlackjackGame.total(shown)
            val label = context.getString(R.string.bj_dealer) + "  " + (if (soft && v < 21 && holeOpen) "${v - 10}/$v" else "$v")
            badge(width * 0.42f, height * 0.24f - ch * 0.45f - 8 * d, label, 0xCC111827.toInt(), false)
        }
        if (settled) {
            hands.forEachIndexed { hi, h ->
                if (h.cards.isEmpty()) return@forEachIndexed
                val n = hands.size
                val cx = if (n > 1) width * (if (hi == 0) 0.27f else 0.73f) else width / 2f
                val (v, soft) = BlackjackGame.total(h.cards)
                val res = when (h.result) {
                    2 -> " ★ " + context.getString(R.string.bj_res_bj)
                    1 -> " ✓"
                    3 -> " ="
                    -1, -2 -> " ✗"
                    else -> ""
                }
                val label = (if (soft && v < 21) "${v - 10}/$v" else "$v") + res
                val col = when {
                    h.result == 1 || h.result == 2 -> 0xEE15803D.toInt()
                    h.result < 0 -> 0xEEBE123C.toInt()
                    v == 21 -> 0xEEB45309.toInt()
                    else -> 0xCC111827.toInt()
                }
                badge(cx, height * 0.66f - ch * (if (n > 1) 0.4f else 0.5f) - 12 * d, label, col, phase == 1 && hi == active && n > 1)
            }
        }
        // Вспышки над руками («Перебор!»).
        for ((hi, f) in flashes) {
            val age = now - f.second
            if (age > 1400) continue
            moving = true
            val n = hands.size
            val cx = if (n > 1) width * (if (hi == 0) 0.27f else 0.73f) else width / 2f
            text.textSize = 20 * d
            text.alpha = (255 * (1 - age / 1400f)).toInt().coerceIn(0, 255)
            text.color = 0xFFFB7185.toInt()
            text.alpha = (255 * (1 - age / 1400f)).toInt().coerceIn(0, 255)
            c.drawText(f.first, cx, height * 0.5f - age * 0.02f * d, text)
            text.color = Color.WHITE
        }
        // Итог раздачи - крупная плашка.
        val bAge = now - bannerAt
        if (bannerText.isNotEmpty() && bAge >= 0) {
            val a = if (bAge < 2600) 1f else (1f - (bAge - 2600) / 600f).coerceIn(0f, 1f)
            if (a > 0f) {
                val pop = if (bAge < 220) 0.7f + 0.3f * (bAge / 220f) else 1f
                text.textSize = 22 * d * pop
                val w = text.measureText(bannerText) / 2 + 20 * d
                chip.color = Color.argb((200 * a).toInt(), 0, 0, 0)
                val y = height * 0.45f
                tmp.set(width / 2f - w, y - 28 * d, width / 2f + w, y + 12 * d)
                c.drawRoundRect(tmp, 20 * d, 20 * d, chip)
                text.color = bannerColor
                text.alpha = (255 * a).toInt()
                c.drawText(bannerText, width / 2f, y, text)
                text.color = Color.WHITE
                moving = true
            }
        } else if (bannerText.isNotEmpty()) {
            moving = true
        }
        if (moving) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
