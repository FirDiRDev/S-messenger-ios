// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.blackjack

import org.json.JSONArray
import org.json.JSONObject

/**
 * Онлайн-блэкджек: стол, где дилер - живой человек (место 0), игроки - места 1..4.
 *
 * Правила - как в одиночной игре: колода 36 карт (6…туз), в башмаке 4 колоды; 6-10 по
 * номиналу, картинки - 10, туз 1 или 11. Дилер берёт до 17 и на «мягких» 17. Блэкджек платит
 * 6 к 5, победа 1 к 1, ничья - ставка назад. «Удвоить» - на первых двух картах, «Разделить» -
 * пару одного достоинства на две руки (один раз; тузы получают по одной карте).
 *
 * Раунд: ставки ([BETS]) → дилер принимает ставки, которые может покрыть ([READY]) → дилер
 * раздаёт ([PLAY], игроки ходят по очереди) → дилер вскрывается и добирает ([DEALER]) → расчёт
 * ([DONE]). Монеты: ставка игрока списывается у него, дилер при принятии ставки резервирует
 * (списывает в банк стола) покрытие наибольшего выигрыша; при удвоении/разделении - ещё.
 * По итогу дилер получает банк + проигранные ставки минус выплаты (см. [dealerCredit]).
 *
 * Всё состояние стола - у дилера (хозяина), игрокам рассылается [publicJson] без башмака и
 * без закрытой карты дилера.
 */
class BjEngine(val seats: Int) {
    class Hand(var bet: Int) {
        val cards = ArrayList<Int>()
        var done = false
        var doubled = false
        var fromSplit = false

        /** 0 - играет, 1 - победа, 2 - блэкджек, 3 - ничья, -1 - проигрыш, -2 - перебор, -3 - сдался (половина ставки назад). */
        var result = 0

        /** Сколько монет возвращается игроку за эту руку (ставка + выигрыш). */
        var ret = 0
        val value get() = total(cards).first
        val blackjack get() = !fromSplit && cards.size == 2 && value == 21

        /** Рука сдана (игрок ушёл из-за стола до раздачи): без карт, ставка проиграна. */
        val forfeited get() = cards.isEmpty() && result == -1
    }

    var round = 1
    var phase = BETS
    val bet = IntArray(seats)
    val betSt = IntArray(seats)

    /** Резерв дилера под каждое место (монеты уже списаны у дилера в банк стола). */
    val reserve = IntArray(seats)
    val hands = List(seats) { ArrayList<Hand>() }

    /** Карты дилера; [HIDDEN] - закрытая карта (у игроков, пока дилер не вскрылся). */
    val dealer = ArrayList<Int>()
    var holeOpen = false
    var active = -1
    var activeHand = 0

    /** Башмак - только у дилера. */
    val shoe = ArrayList<Int>()

    /** История по местам: [раунд, ставка, возврат] - последние раунды с участием места. */
    val hist = List(seats) { ArrayList<IntArray>() }

    /** Последнее событие для экрана: "" / "bank" (дилеру нечем покрыть) / "limit" / "skip" / "time" / "shuf". */
    var note = ""

    /** Наибольшая ставка за этим столом (дилер выбирает перед раундом). */
    var maxBet = MAX_BET

    /**
     * Таймер хода (часы этого устройства, мс): игроку - [TURN_MS] на решение, потом ход
     * переходит дальше («Хватит»); дилеру - столько же на шаг, потом он доигрывает сам.
     * 0 - таймер не идёт. Игрокам уходит остаток ("tl"), они считают от момента приёма.
     */
    var deadline = 0L

    fun timeLeft(now: Long = System.currentTimeMillis()): Long = if (deadline <= 0) -1 else (deadline - now).coerceAtLeast(0)
    fun expired(now: Long = System.currentTimeMillis()) = deadline > 0 && now >= deadline
    private fun startTimer() {
        deadline = System.currentTimeMillis() + TURN_MS
    }

    /** Дилер торопит игрока: на решение остаётся не больше 5 секунд. */
    fun nudge(): Boolean {
        if (phase != PLAY || active <= 0 || deadline <= 0) return false
        deadline = minOf(deadline, System.currentTimeMillis() + NUDGE_MS)
        return true
    }

    fun stake(s: Int) = hands[s].sumOf { it.bet }
    fun ret(s: Int) = hands[s].sumOf { it.ret }
    fun bank() = reserve.sum()
    fun inRound(s: Int) = betSt[s] == B_OK
    fun current(): Hand? = if (phase == PLAY && active > 0) hands[active].getOrNull(activeHand) else null

    /** Сколько нужно зарезервировать дилеру, чтобы принять все ждущие ставки. */
    fun pendingReserve(): Int = (1 until seats).filter { betSt[it] == B_PENDING }.sumOf { reserveFor(bet[it]) }

    fun pendingCount() = (1 until seats).count { betSt[it] == B_PENDING }

    /** Итог места за раунд (+ выиграл / - проиграл) - только после расчёта. */
    fun net(s: Int): Int = if (phase == DONE && inRound(s)) ret(s) - stake(s) else 0

    // ---------------- ставки ----------------

    fun placeBet(seat: Int, amount: Int): Boolean {
        if (phase != BETS || seat <= 0 || seat >= seats || betSt[seat] != B_NONE || amount !in MIN_BET..maxBet.coerceIn(MIN_BET, MAX_BET)) return false
        bet[seat] = amount
        betSt[seat] = B_PENDING
        return true
    }

    /**
     * Дилер принимает ставки по порядку мест: каждую - только если смог зарезервировать
     * покрытие ([reserveFn] (место, сколько) списывает монеты у дилера). Непокрытые ставки
     * отклоняются (игрокам они вернутся). Ни одной не принято - раунд закрывается без игры.
     * Возвращает число принятых ставок, -1 - сейчас нельзя.
     */
    fun acceptBets(reserveFn: (Int, Int) -> Boolean): Int {
        if (phase != BETS || pendingCount() == 0) return -1
        var n = 0
        var rejected = 0
        for (s in 1 until seats) {
            if (betSt[s] != B_PENDING) continue
            val need = reserveFor(bet[s])
            if (reserveFn(s, need)) {
                betSt[s] = B_OK
                reserve[s] = need
                n++
            } else {
                betSt[s] = B_REJECT
                rejected++
            }
        }
        if (rejected == 0) note = ""
        if (n > 0) {
            phase = READY
        } else {
            // Никого не взяли: раунд закрыт без игры, ставки вернутся игрокам по истории.
            for (s in 1 until seats) if (betSt[s] == B_REJECT) pushHist(s, 0, 0)
            resetRound()
        }
        return n
    }

    // ---------------- раздача и ходы ----------------

    private fun refill(rnd: java.util.Random) {
        shoe.clear()
        repeat(DECKS) { for (c in 0 until 36) shoe += c }
        for (i in shoe.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = shoe[i]
            shoe[i] = shoe[j]
            shoe[j] = t
        }
    }

    private var rng: java.util.Random? = null

    private fun draw(): Int {
        if (shoe.isEmpty()) refill(rng ?: java.security.SecureRandom().also { rng = it })
        return shoe.removeAt(shoe.size - 1)
    }

    /** Раздать (башмак перетасовывается, если карт мало). true - тасовали. */
    fun deal(rnd: java.util.Random = java.security.SecureRandom()): Boolean {
        if (phase != READY) return false
        rng = rnd
        val shuffled = shoe.size < 40
        if (shuffled) refill(rnd)
        dealer.clear()
        holeOpen = false
        for (s in 0 until seats) {
            val gone = hands[s].firstOrNull()?.forfeited == true
            hands[s].clear()
            if (s > 0 && betSt[s] == B_OK) hands[s] += Hand(bet[s]).apply { if (gone) { done = true; result = -1 } }
        }
        // Как у крупье: по карте каждому игроку, дилеру; затем вторая карта (у дилера - закрытая).
        repeat(2) {
            for (s in 1 until seats) if (betSt[s] == B_OK && !hands[s][0].forfeited) hands[s][0].cards += draw()
            dealer += draw()
        }
        phase = PLAY
        note = ""
        for (s in 1 until seats) for (h in hands[s]) if (h.blackjack) h.done = true
        if (total(dealer).first == 21) {
            // Блэкджек у дилера - ходить игрокам незачем, дилер вскрывается.
            for (s in 1 until seats) for (h in hands[s]) h.done = true
            active = -1
            phase = DEALER
            startTimer()
        } else {
            active = 0
            activeHand = 0
            advance()
        }
        return shuffled
    }

    /** Следующая рука, которой нужно ходить; никого - очередь дилера. */
    private fun advance() {
        var s = active.coerceAtLeast(1)
        var hi = if (active >= 1) activeHand else 0
        while (s < seats) {
            val list = hands[s]
            while (hi < list.size) {
                if (!list[hi].done) {
                    if (active != s || activeHand != hi || deadline <= 0) startTimer()
                    active = s
                    activeHand = hi
                    return
                }
                hi++
            }
            s++
            hi = 0
        }
        active = -1
        activeHand = 0
        phase = DEALER
        startTimer()
    }

    private fun check(h: Hand) {
        val v = h.value
        if (v > 21) {
            h.done = true
            h.result = -2
        } else if (v == 21 || h.doubled || (h.fromSplit && h.cards[0] % 9 == 8)) {
            h.done = true
        }
    }

    /**
     * Ход относится к текущей руке: место, номер руки и число карт совпадают (защита от
     * двойного нажатия и опоздавших пакетов - «Ещё» не уйдёт во вторую руку).
     */
    fun matches(seat: Int, hand: Int, cards: Int): Boolean {
        val h = current() ?: return false
        return seat == active && hand == activeHand && cards == h.cards.size && !h.done
    }

    fun hit(seat: Int): Boolean {
        val h = current() ?: return false
        if (seat != active || h.done) return false
        h.cards += draw()
        check(h)
        if (h.done) advance() else startTimer()
        return true
    }

    fun stand(seat: Int): Boolean {
        val h = current() ?: return false
        if (seat != active) return false
        h.done = true
        advance()
        return true
    }

    /** Дилер пропускает ход игрока (тот не отвечает) - как «Хватит». */
    fun skip(): Boolean {
        val ok = stand(active)
        if (ok) note = "skip"
        return ok
    }

    /** Время на ход вышло - как «Хватит». */
    fun timeout(): Boolean {
        if (phase != PLAY || !expired()) return false
        val ok = stand(active)
        if (ok) note = "time"
        return ok
    }

    /** «Сдаться»: на первых двух картах (не после разделения) - половина ставки назад. */
    fun canSurrender(seat: Int): Boolean {
        val h = current() ?: return false
        return seat == active && !h.done && h.cards.size == 2 && !h.fromSplit && hands[seat].size == 1
    }

    fun surrender(seat: Int): Boolean {
        if (!canSurrender(seat)) return false
        val h = current()!!
        h.result = -3
        h.done = true
        note = ""
        advance()
        return true
    }

    fun canDouble(seat: Int): Boolean {
        val h = current() ?: return false
        return seat == active && !h.done && h.cards.size == 2
    }

    fun double(seat: Int, reserveFn: (Int, Int) -> Boolean): Boolean {
        if (!canDouble(seat)) return false
        val h = current()!!
        if (!reserveFn(seat, h.bet)) return false
        reserve[seat] += h.bet
        h.bet *= 2
        h.doubled = true
        h.cards += draw()
        check(h)
        h.done = true
        note = ""
        advance()
        return true
    }

    fun canSplit(seat: Int): Boolean {
        val h = current() ?: return false
        return seat == active && !h.done && hands[seat].size == 1 && h.cards.size == 2 && h.cards[0] % 9 == h.cards[1] % 9
    }

    fun split(seat: Int, reserveFn: (Int, Int) -> Boolean): Boolean {
        if (!canSplit(seat)) return false
        val h = current()!!
        if (!reserveFn(seat, h.bet)) return false
        reserve[seat] += h.bet
        val second = Hand(h.bet).apply { fromSplit = true }
        h.fromSplit = true
        second.cards += h.cards.removeAt(1)
        hands[seat] += second
        h.cards += draw()
        second.cards += draw()
        check(h)
        check(second)
        note = ""
        activeHand = 0
        if (h.done) advance() else startTimer()
        return true
    }

    // ---------------- дилер и расчёт ----------------

    /** Есть ли руки, против которых дилеру нужно добирать (не перебор, не блэкджек, не сдались). */
    private fun alive() = (1 until seats).any { s -> hands[s].any { it.result == 0 && it.value <= 21 && !it.blackjack } }

    /** По правилам дилер обязан взять карту: меньше 17 или «мягкие» 17. */
    fun dealerMustHit(): Boolean {
        if (phase != DEALER || !holeOpen || !alive()) return false
        val (dv, soft) = total(dealer)
        return dv < 17 || (dv == 17 && soft)
    }

    /** Дилер по шагам: 1) вскрыть закрытую карту. */
    fun dealerReveal(): Boolean {
        if (phase != DEALER || holeOpen) return false
        holeOpen = true
        startTimer()
        return true
    }

    /** 2) взять карту (только когда правила велят брать). */
    fun dealerHit(): Boolean {
        if (!dealerMustHit()) return false
        dealer += draw()
        startTimer()
        return true
    }

    /** 3) остановиться и рассчитаться (только когда брать больше не нужно). */
    fun dealerFinish(): Boolean {
        if (phase != DEALER || !holeOpen || dealerMustHit()) return false
        settle()
        return true
    }

    /** Дилер вскрывается, добирает по правилам (до 17 и на мягких 17) и рассчитывает все руки - сразу. */
    fun dealerPlay(): Boolean {
        if (phase != DEALER) return false
        holeOpen = true
        while (dealerMustHit()) dealer += draw()
        settle()
        return true
    }

    /** Дилер перемешивает башмак между раундами. */
    fun shuffleShoe(rnd: java.util.Random = java.security.SecureRandom()): Boolean {
        if (phase != BETS && phase != DONE) return false
        refill(rnd)
        note = "shuf"
        return true
    }

    /** Лимит ставки на стол - только пока нет ждущих ставок. */
    fun setLimit(v: Int): Boolean {
        if (phase != BETS || pendingCount() > 0) return false
        maxBet = v.coerceIn(MIN_BET, MAX_BET)
        return true
    }

    private fun settle() {
        val dv = total(dealer).first
        val dealerBj = dealer.size == 2 && dv == 21
        for (s in 1 until seats) {
            if (!inRound(s)) continue
            for (h in hands[s]) {
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
                h.ret = payout(h)
            }
            pushHist(s, stake(s), ret(s))
        }
        phase = DONE
        active = -1
        deadline = 0
    }

    private fun pushHist(s: Int, stake: Int, ret: Int) {
        val list = hist[s]
        list.removeAll { it[0] == round }
        list += intArrayOf(round, stake, ret)
        while (list.size > HIST) list.removeAt(0)
    }

    fun histFor(s: Int, r: Int): IntArray? = hist.getOrNull(s)?.firstOrNull { it[0] == r }

    /**
     * Что получает дилер по итогу раунда: весь банк (резерв) + проигранные ставки - выплаты.
     * Не бывает меньше нуля: резерв покрывает наибольший выигрыш каждой ставки.
     */
    fun dealerCredit(): Int = (1 until seats).filter { inRound(it) }.sumOf { (reserve[it] + stake(it) - ret(it)).coerceAtLeast(0) }

    /** Итог дилера за раунд (+ выиграл / - проиграл). */
    fun dealerNet(): Int = (1 until seats).filter { inRound(it) }.sumOf { stake(it) - ret(it) }

    fun newRound(): Boolean {
        if (phase != DONE) return false
        round++
        resetRound()
        return true
    }

    private fun resetRound() {
        if (phase != DONE && phase != BETS) return
        for (s in 0 until seats) {
            bet[s] = 0
            betSt[s] = B_NONE
            reserve[s] = 0
            hands[s].clear()
        }
        if (phase == BETS) round++
        dealer.clear()
        holeOpen = false
        active = -1
        activeHand = 0
        phase = BETS
        deadline = 0
    }

    /**
     * Игрок ушёл из-за стола (удалил сохранение): его ставка в этом раунде проиграна, руки
     * закрываются, игра идёт дальше. Возвращает ждущую (ещё не принятую) ставку, которую
     * игрок оставил на столе: она достаётся дилеру как сданная (0 - такой нет).
     */
    fun forfeit(seat: Int): Int {
        if (seat <= 0 || seat >= seats) return 0
        if (phase == BETS) {
            if (betSt[seat] != B_PENDING) return 0
            val n = bet[seat]
            betSt[seat] = B_NONE
            bet[seat] = 0
            return n
        }
        if (!inRound(seat) || phase == DONE) return 0
        if (phase == READY) {
            // Карты ещё не розданы: рука «сдана» без карт.
            hands[seat].clear()
            hands[seat] += Hand(bet[seat]).apply { done = true; result = -1 }
            return 0
        }
        for (h in hands[seat]) {
            h.done = true
            if (h.result == 0) h.result = -1
        }
        if (phase == PLAY && active == seat) advance()
        return 0
    }

    // ---------------- сохранение ----------------

    private fun enc(list: List<Int>) = list.joinToString("") { if (it < 0) "-" else it.toString(36) }
    private fun dec(s: String) = s.map { if (it == '-') HIDDEN else it.toString().toIntOrNull(36) ?: HIDDEN }

    private fun encHands(s: Int) = hands[s].joinToString(";") { h ->
        val f = (if (h.done) 1 else 0) or (if (h.doubled) 2 else 0) or (if (h.fromSplit) 4 else 0)
        "${enc(h.cards)},${h.bet},$f,${h.result},${h.ret}"
    }

    private fun encHist(s: Int) = hist[s].joinToString("|") { "${it[0]}:${it[1]}:${it[2]}" }

    /** Полное состояние (только у дилера): с башмаком и закрытой картой. */
    fun toJson(): JSONObject = base(-1, full = true)

    /** Для игрока [seat]: без башмака, закрытая карта дилера скрыта, история - только его. */
    fun publicJson(seat: Int): JSONObject = base(seat, full = false)

    private fun base(seat: Int, full: Boolean): JSONObject {
        val o = JSONObject()
            .put("n", seats).put("r", round).put("ph", phase)
            .put("b", JSONArray(bet.toList())).put("bs", betSt.joinToString(""))
            .put("rs", JSONArray(reserve.toList()))
            .put("h", JSONArray((0 until seats).map { encHands(it) }))
            .put("ho", holeOpen).put("a", active).put("ah", activeHand).put("nt", note)
            .put("mb", maxBet).put("tl", timeLeft())
        val shownDealer = if (full || holeOpen) dealer.toList() else dealer.mapIndexed { i, c -> if (i == 1) HIDDEN else c }
        o.put("d", enc(shownDealer))
        if (full) {
            o.put("dl", deadline)
            o.put("sh", enc(shoe))
            o.put("hi", JSONArray((0 until seats).map { encHist(it) }))
        } else {
            o.put("hi", JSONArray((0 until seats).map { if (it == seat) encHist(it) else "" }))
        }
        return o
    }

    fun load(o: JSONObject) {
        round = o.optInt("r", 1).coerceAtLeast(1)
        phase = o.optInt("ph", BETS).coerceIn(BETS, DONE)
        o.optJSONArray("b")?.let { a -> for (i in 0 until seats) bet[i] = a.optInt(i).coerceIn(0, MAX_BET) }
        val bs = o.optString("bs")
        for (i in 0 until seats) betSt[i] = bs.getOrNull(i)?.digitToIntOrNull()?.coerceIn(0, 3) ?: B_NONE
        o.optJSONArray("rs")?.let { a -> for (i in 0 until seats) reserve[i] = a.optInt(i).coerceIn(0, MAX_BET * 8) }
        val hs = o.optJSONArray("h")
        for (s in 0 until seats) {
            hands[s].clear()
            val txt = hs?.optString(s).orEmpty()
            if (txt.isEmpty()) continue
            for (part in txt.split(';').take(2)) {
                val f = part.split(',')
                if (f.size < 5) continue
                val h = Hand((f[1].toIntOrNull() ?: 0).coerceIn(0, MAX_BET * 2))
                h.cards += dec(f[0]).take(16).map { it.coerceIn(HIDDEN, 35) }
                val fl = f[2].toIntOrNull() ?: 0
                h.done = fl and 1 != 0
                h.doubled = fl and 2 != 0
                h.fromSplit = fl and 4 != 0
                h.result = (f[3].toIntOrNull() ?: 0).coerceIn(-3, 3)
                h.ret = (f[4].toIntOrNull() ?: 0).coerceIn(0, MAX_BET * 5)
                hands[s] += h
            }
        }
        dealer.clear()
        dealer += dec(o.optString("d")).take(16).map { it.coerceIn(HIDDEN, 35) }
        holeOpen = o.optBoolean("ho")
        active = o.optInt("a", -1).coerceIn(-1, seats - 1)
        activeHand = o.optInt("ah").coerceIn(0, 1)
        note = o.optString("nt").take(8)
        maxBet = o.optInt("mb", MAX_BET).coerceIn(MIN_BET, MAX_BET)
        // Таймер: у дилера - его собственный срок, у игрока - остаток от момента приёма.
        deadline = when {
            o.has("dl") -> o.optLong("dl")
            o.optLong("tl", -1) >= 0 -> o.optLong("rx", System.currentTimeMillis()) + o.optLong("tl")
            else -> 0L
        }
        shoe.clear()
        shoe += dec(o.optString("sh")).filter { it in 0..35 }
        val hi = o.optJSONArray("hi")
        for (s in 0 until seats) {
            hist[s].clear()
            val txt = hi?.optString(s).orEmpty()
            if (txt.isEmpty()) continue
            for (part in txt.split('|').takeLast(HIST)) {
                val f = part.split(':').mapNotNull { it.toIntOrNull() }
                if (f.size == 3) hist[s] += intArrayOf(f[0], f[1].coerceIn(0, MAX_BET * 2 * 2), f[2].coerceAtLeast(0))
            }
        }
    }

    companion object {
        const val BETS = 0
        const val READY = 1
        const val PLAY = 2
        const val DEALER = 3
        const val DONE = 4

        const val B_NONE = 0
        const val B_PENDING = 1
        const val B_OK = 2
        const val B_REJECT = 3

        const val HIDDEN = -1
        const val MIN_BET = 1
        const val MAX_BET = 500
        const val MAX_PLAYERS = 4
        const val DECKS = 4
        const val HIST = 10

        /** Время на ход (игрока и шаг дилера). */
        const val TURN_MS = 15_000L

        /** «Поторопить»: столько остаётся после нажатия дилера. */
        const val NUDGE_MS = 5_000L

        /** Резерв дилера под ставку: наибольший выигрыш с раздачи - блэкджек 6 к 5. */
        fun reserveFor(bet: Int) = bet * 6 / 5

        /** Возврат игроку за руку: блэкджек 6:5, победа 1:1, ничья - ставка. */
        fun payout(h: Hand): Int = when (h.result) {
            2 -> h.bet + h.bet * 6 / 5
            1 -> h.bet * 2
            3 -> h.bet
            -3 -> h.bet / 2
            else -> 0
        }

        /** Очки карты: 0..3 → 6..9, 4..7 → 10 (десятка и картинки), 8 → туз (11). */
        fun points(card: Int): Int = if (card < 0) 0 else when (val r = card % 9) {
            8 -> 11
            in 4..7 -> 10
            else -> r + 6
        }

        /** Сумма руки и «мягкая» ли она (туз считается за 11). Закрытые карты не считаются. */
        fun total(cards: List<Int>): Pair<Int, Boolean> {
            var t = 0
            var aces = 0
            for (c in cards) {
                if (c < 0) continue
                t += points(c)
                if (c % 9 == 8) aces++
            }
            while (t > 21 && aces > 0) {
                t -= 10
                aces--
            }
            return t to (aces > 0)
        }

        fun fromJson(o: JSONObject): BjEngine {
            val e = BjEngine(o.optInt("n", 2).coerceIn(2, MAX_PLAYERS + 1))
            e.load(o)
            return e
        }
    }
}
