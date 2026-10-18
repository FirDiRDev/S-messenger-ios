// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * «Дурак» на 2-4 игрока, колода 36 карт (6…туз): подкидной, переводной и простой.
 *
 * Карта - число 0..35: масть = c / 9 (0 ♠, 1 ♣, 2 ♦, 3 ♥), достоинство = c % 9 (0 - шестёрка … 8 - туз).
 *
 * Правила:
 *  - раздача по 6 карт, последняя карта колоды (лежит под ней лицом вверх) - козырь;
 *  - первым ходит тот, у кого младший козырь;
 *  - ходит атакующий, подкидывать могут все, кроме отбивающегося, - карты тех же
 *    достоинств, что уже на столе; на столе не больше 6 атакующих карт и не больше,
 *    чем было карт у отбивающегося в начале хода;
 *  - отбиваются старшей картой той же масти или козырем (козырь бьётся старшим козырем);
 *  - «Беру» - отбивающийся забирает всё со стола и пропускает ход;
 *  - «Бито» - все карты покрыты и подкидывающие пасуют: карты уходят в сброс, отбившийся ходит;
 *  - после хода добирают до 6 из колоды: сначала ходивший, потом подкидывавшие, последним
 *    отбивавшийся;
 *  - кто остался без карт при пустой колоде - вышел; последний с картами - дурак.
 *    Если все вышли одновременно - ничья.
 */
class DurakEngine(val players: Int, mode: Int = PODKIDNOY) {
    /**
     * Режим: [PODKIDNOY] - подкидывают все, кроме отбивающегося; [PEREVODNOY] - то же, плюс
     * отбивающийся может перевести ход, положив карту того же достоинства (пока ничего не
     * побито); [SIMPLE] - подкидывает только тот, кто ходил.
     */
    var mode = mode.coerceIn(0, MODES - 1)
        private set
    val deck = ArrayList<Int>()
    var trump = 0
        private set
    var trumpCard = -1
        private set
    val hands = List(players) { ArrayList<Int>() }

    /** Стол: пары (атака, защита или -1). */
    val table = ArrayList<IntArray>()
    val discard = ArrayList<Int>()
    var attacker = 0
        private set
    var defender = 1
        private set

    /** Кто из подкидывающих сказал «пас» в этом ходе. */
    val passed = HashSet<Int>()
    private var defStart = 6

    /** -1 - игра идёт, -2 - ничья, иначе номер дурака. */
    var durak = -1
        private set

    /** Порядок выхода (кто первым избавился от карт). */
    val out = ArrayList<Int>()

    /** Последнее событие для экрана: "take", "bito", "" . */
    var lastEvent = ""
        private set

    val finished get() = durak != -1

    fun suit(c: Int) = c / 9
    fun rank(c: Int) = c % 9

    fun beats(def: Int, atk: Int): Boolean = when {
        suit(def) == suit(atk) -> rank(def) > rank(atk)
        suit(def) == trump && suit(atk) != trump -> true
        else -> false
    }

    /**
     * Раздать. Колода тасуется честно - криптостойким генератором (SecureRandom, к нему
     * подмешивается [seed]) по алгоритму Фишера-Йетса: все 36! раскладов равновероятны.
     * Онлайн раздаёт только создатель партии и рассылает расклад остальным.
     */
    fun deal(seed: Long) {
        deck.clear()
        for (c in 0 until 36) deck += c
        val rnd = java.security.SecureRandom()
        rnd.setSeed(seed)
        for (i in deck.size - 1 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = deck[i]
            deck[i] = deck[j]
            deck[j] = t
        }
        // deck[0] - низ колоды (козырь), берут с конца.
        trumpCard = deck[0]
        trump = suit(trumpCard)
        hands.forEach { it.clear() }
        table.clear()
        discard.clear()
        out.clear()
        passed.clear()
        durak = -1
        repeat(6) { for (p in 0 until players) draw(p) }
        // Первым ходит младший козырь.
        var best = -1
        var bestRank = 99
        for (p in 0 until players) for (c in hands[p]) if (suit(c) == trump && rank(c) < bestRank) {
            bestRank = rank(c)
            best = p
        }
        attacker = if (best >= 0) best else 0
        defender = nextActive(attacker)
        defStart = hands[defender].size
    }

    private fun draw(p: Int) {
        if (deck.isNotEmpty()) hands[p] += deck.removeAt(deck.size - 1)
    }

    private fun active(p: Int) = p !in out

    fun nextActive(from: Int): Int {
        var p = from
        repeat(players) {
            p = (p + 1) % players
            if (active(p)) return p
        }
        return from
    }

    fun ranksOnTable(): Set<Int> = table.flatMap { listOfNotNull(rank(it[0]), if (it[1] >= 0) rank(it[1]) else null) }.toSet()

    fun uncovered(): List<Int> = table.indices.filter { table[it][1] < 0 }

    private fun limit() = minOf(6, defStart)

    /** Может ли игрок [p] сейчас положить [card] атакой/подкидыванием. */
    fun canAttack(p: Int, card: Int): Boolean {
        if (finished || p == defender || !active(p) || card !in hands[p]) return false
        if (table.size >= limit()) return false
        // Нельзя подкинуть больше, чем отбивающийся ещё может покрыть.
        if (uncovered().size >= hands[defender].size) return false
        if (table.isEmpty()) return p == attacker
        if (mode == SIMPLE && p != attacker) return false
        return rank(card) in ranksOnTable()
    }

    /**
     * Переводной: может ли отбивающийся [p] перевести картой [card] - ничего ещё не побито,
     * все карты на столе одного достоинства с ней, а у следующего хватит карт отбиваться.
     */
    fun canTransfer(p: Int, card: Int): Boolean {
        if (mode != PEREVODNOY || finished || p != defender || card !in hands[p] || table.isEmpty()) return false
        if (table.any { it[1] >= 0 }) return false
        if (table.any { rank(it[0]) != rank(card) }) return false
        val next = nextActive(defender)
        if (next == defender) return false
        return table.size + 1 <= minOf(6, hands[next].size)
    }

    /** Перевести: карта ложится на стол, отбиваться теперь следующему. */
    fun transfer(p: Int, card: Int): Boolean {
        if (!canTransfer(p, card)) return false
        hands[p].remove(card)
        table += intArrayOf(card, -1)
        attacker = defender
        defender = nextActive(defender)
        defStart = hands[defender].size
        passed.clear()
        lastEvent = "transfer"
        return true
    }

    fun attack(p: Int, card: Int): Boolean {
        if (!canAttack(p, card)) return false
        hands[p].remove(card)
        table += intArrayOf(card, -1)
        passed.remove(p)
        lastEvent = ""
        return true
    }

    fun canDefend(p: Int, idx: Int, card: Int): Boolean =
        !finished && p == defender && card in hands[p] && idx in table.indices && table[idx][1] < 0 && beats(card, table[idx][0])

    fun defend(p: Int, idx: Int, card: Int): Boolean {
        if (!canDefend(p, idx, card)) return false
        hands[p].remove(card)
        table[idx][1] = card
        // Покрыли новую карту - подкидывающие снова могут подкинуть.
        passed.clear()
        lastEvent = ""
        autoEnd()
        return true
    }

    /** Отбивающийся забирает карты. */
    fun take(p: Int): Boolean {
        if (finished || p != defender || table.isEmpty()) return false
        for (pair in table) {
            hands[p] += pair[0]
            if (pair[1] >= 0) hands[p] += pair[1]
        }
        table.clear()
        lastEvent = "take"
        endRound(defenderTook = true)
        return true
    }

    /** Подкидывающий больше не подкидывает. */
    fun pass(p: Int): Boolean {
        if (finished || p == defender || !active(p) || table.isEmpty()) return false
        passed += p
        autoEnd()
        return true
    }

    /** Может ли игрок [p] сейчас сказать «пас»/«бито». */
    fun canPass(p: Int) = !finished && p != defender && active(p) && table.isNotEmpty() && p !in passed

    /** Всё покрыто и все подкидывающие спасовали (или подкидывать уже нельзя) - бито. */
    private fun autoEnd() {
        if (table.isEmpty() || uncovered().isNotEmpty()) return
        val full = table.size >= limit() || hands[defender].isEmpty()
        val attackers = (0 until players).filter { it != defender && active(it) }
        val allPassed = attackers.all { it in passed || hands[it].isEmpty() }
        if (full || allPassed) {
            for (pair in table) {
                discard += pair[0]
                discard += pair[1]
            }
            table.clear()
            lastEvent = "bito"
            endRound(defenderTook = false)
        }
    }

    private fun endRound(defenderTook: Boolean) {
        passed.clear()
        // Добор: ходивший, остальные по кругу, отбивавшийся последним.
        val order = ArrayList<Int>()
        var p = attacker
        repeat(players) {
            if (p != defender && active(p)) order += p
            p = (p + 1) % players
        }
        order += defender
        for (q in order) while (hands[q].size < 6 && deck.isNotEmpty()) draw(q)
        // Вышли: без карт при пустой колоде.
        if (deck.isEmpty()) for (q in 0 until players) if (active(q) && hands[q].isEmpty()) out += q
        val left = (0 until players).filter { active(it) }
        if (left.size <= 1) {
            durak = left.firstOrNull() ?: -2
            return
        }
        attacker = if (defenderTook) nextActive(defender) else (if (active(defender)) defender else nextActive(defender))
        defender = nextActive(attacker)
        defStart = hands[defender].size
    }

    // ---------------- сохранение ----------------

    private fun enc(list: List<Int>) = list.joinToString("") { it.toString(36) }
    private fun dec(s: String) = s.map { it.toString().toInt(36) }

    fun toJson(): JSONObject = JSONObject()
        .put("n", players)
        .put("d", enc(deck))
        .put("tc", trumpCard)
        .put("h", JSONArray(hands.map { enc(it) }))
        .put("t", table.joinToString(",") { "${it[0].toString(36)}${if (it[1] >= 0) it[1].toString(36) else "-"}" })
        .put("x", enc(discard))
        .put("a", attacker)
        .put("df", defender)
        .put("p", enc(passed.toList()))
        .put("ds", defStart)
        .put("dk", durak)
        .put("o", enc(out))
        .put("e", lastEvent)
        .put("m", mode)

    companion object {
        const val PODKIDNOY = 0
        const val PEREVODNOY = 1
        const val SIMPLE = 2
        const val MODES = 3

        fun fromJson(o: JSONObject): DurakEngine {
            val e = DurakEngine(o.getInt("n").coerceIn(2, 4), o.optInt("m", 0))
            e.load(o)
            return e
        }
    }

    fun load(o: JSONObject) {
        deck.clear()
        deck += dec(o.optString("d"))
        trumpCard = o.optInt("tc", -1)
        trump = if (trumpCard >= 0) suit(trumpCard) else 0
        val h = o.getJSONArray("h")
        for (i in 0 until players) {
            hands[i].clear()
            hands[i] += dec(h.optString(i))
        }
        table.clear()
        o.optString("t").split(',').filter { it.isNotBlank() }.forEach { p ->
            table += intArrayOf(p[0].toString().toInt(36), if (p.length > 1 && p[1] != '-') p[1].toString().toInt(36) else -1)
        }
        discard.clear()
        discard += dec(o.optString("x"))
        attacker = o.optInt("a")
        defender = o.optInt("df", 1)
        passed.clear()
        passed += dec(o.optString("p"))
        defStart = o.optInt("ds", 6)
        durak = o.optInt("dk", -1)
        out.clear()
        out += dec(o.optString("o"))
        lastEvent = o.optString("e")
        mode = o.optInt("m", 0).coerceIn(0, MODES - 1)
    }
}

/** Простой соперник для игры с ботами. */
object DurakBot {
    /** «Цена» карты: козыри дороже любой простой, внутри - по старшинству. */
    private fun cost(e: DurakEngine, c: Int) = e.rank(c) + if (e.suit(c) == e.trump) 10 else 0

    /**
     * Чем отбиться от всех открытых карт: самые сильные атаки закрываем первыми, каждой -
     * самой дешёвой подходящей картой (одна карта - один раз). null - отбиться нельзя.
     */
    private fun cover(e: DurakEngine, hand: List<Int>, open: List<Int>): Map<Int, Int>? {
        val left = hand.sortedBy { cost(e, it) }.toMutableList()
        val res = HashMap<Int, Int>()
        for (idx in open.sortedByDescending { cost(e, e.table[it][0]) }) {
            val atk = e.table[idx][0]
            val card = left.firstOrNull { e.beats(it, atk) } ?: return null
            left.remove(card)
            res[idx] = card
        }
        return res
    }

    /** Сделать один ход за игрока [p]. true - что-то сделал. */
    fun act(e: DurakEngine, p: Int): Boolean {
        if (e.finished) return false
        val hand = e.hands[p].sortedWith(compareBy({ if (e.suit(it) == e.trump) 1 else 0 }, { e.rank(it) }))
        val late = e.deck.isEmpty()
        if (p == e.defender) {
            val open = e.uncovered()
            if (open.isEmpty()) return false
            // Переводной: перевести дешёвой картой выгоднее, чем отбиваться (козырь - только
            // в конце игры или если иначе пришлось бы брать).
            if (e.mode == DurakEngine.PEREVODNOY) {
                val tr = hand.filter { e.canTransfer(p, it) }.minByOrNull { cost(e, it) }
                if (tr != null && (e.suit(tr) != e.trump || late || cover(e, hand, open) == null)) return e.transfer(p, tr)
            }
            val plan = cover(e, hand, open) ?: return e.take(p)
            // Сколько козырей и старших карт уйдёт на защиту.
            val trumps = plan.values.count { e.suit(it) == e.trump }
            val bigTrumps = plan.values.count { e.suit(it) == e.trump && e.rank(it) >= 6 }
            val onTable = e.table.size
            // В начале игры не жжём старшие козыри на мелочь - выгоднее взять 1-2 карты.
            // Чем больше карт на столе, тем дороже «взять» - тогда отбиваемся.
            val tooExpensive = !late && onTable <= 2 && (bigTrumps > 0 || (trumps > 0 && e.deck.size > 18))
            if (tooExpensive) return e.take(p)
            val (idx, card) = plan.entries.first()
            return e.defend(p, idx, card)
        }
        if (e.table.isEmpty()) {
            if (p != e.attacker) return false
            // Заход: младшие простые карты, причём лучше той масти-ранга, где есть пара -
            // тогда можно подкинуть ещё. В конце игры - сначала то, что сложнее побить.
            val plain = hand.filter { e.suit(it) != e.trump }
            val pool = plain.ifEmpty { hand }
            if (pool.isEmpty()) return false
            val counts = pool.groupingBy { e.rank(it) }.eachCount()
            val card = if (late) {
                pool.maxWithOrNull(compareBy({ counts[e.rank(it)] ?: 0 }, { -cost(e, it) })) ?: pool.first()
            } else {
                pool.minWithOrNull(compareBy({ e.rank(it) - 2 * ((counts[e.rank(it)] ?: 1) - 1) }, { cost(e, it) })) ?: pool.first()
            }
            return e.attack(p, card)
        }
        if (!e.canPass(p)) return false
        // Подкинуть: пока колода есть - только мелкие простые карты; в конце - всё, кроме
        // последнего старшего козыря (его бережём на защиту).
        val add = hand.filter { e.canAttack(p, it) }.minByOrNull { cost(e, it) }?.takeIf {
            if (late) cost(e, it) < 16 || hand.size <= 2 else e.suit(it) != e.trump && e.rank(it) < 6
        }
        if (add != null) return e.attack(p, add)
        // Ждём, пока отобьётся.
        if (e.uncovered().isNotEmpty()) return false
        return e.pass(p)
    }
}
