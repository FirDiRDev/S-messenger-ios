// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.shared.games

import kotlin.random.Random

/**
 * Блэкджек (общая логика для iOS; в Android правила те же): 6 колод, дилер берёт до 17
 * (на мягких 17 стоит), блэкджек платит 3:2, удвоение - на первых двух картах.
 * Карта - число 0..51: масть = c / 13 (0 ♠, 1 ♣, 2 ♦, 3 ♥), достоинство = c % 13 (0 - туз … 12 - король).
 */
class Blackjack(seed: Long, private val decks: Int = 6) {
    enum class Phase { BETTING, PLAYER, DEALER, DONE }
    enum class Result { NONE, WIN, BLACKJACK, LOSE, PUSH, BUST }

    private val rnd = Random(seed)
    private val shoe = ArrayList<Int>()
    val player = ArrayList<Int>()
    val dealer = ArrayList<Int>()
    var bet = 0
        private set
    var phase = Phase.BETTING
        private set
    var result = Result.NONE
        private set

    fun rankLabel(c: Int) = when (val r = c % 13) {
        0 -> "Т"
        10 -> "В"
        11 -> "Д"
        12 -> "К"
        else -> "${r + 1}"
    }

    fun suit(c: Int) = c / 13

    /** Сумма очков и «мягкая» ли (туз считается за 11). */
    fun score(hand: List<Int>): Pair<Int, Boolean> {
        var sum = 0
        var aces = 0
        for (c in hand) {
            val r = c % 13
            sum += when {
                r == 0 -> { aces++; 1 }
                r >= 9 -> 10
                else -> r + 1
            }
        }
        val soft = aces > 0 && sum + 10 <= 21
        return (if (soft) sum + 10 else sum) to soft
    }

    private fun draw(): Int {
        if (shoe.size < 20) {
            shoe.clear()
            repeat(decks) { for (c in 0 until 52) shoe += c }
            shoe.shuffle(rnd)
        }
        return shoe.removeAt(shoe.size - 1)
    }

    fun deal(bet: Int) {
        this.bet = bet
        player.clear()
        dealer.clear()
        result = Result.NONE
        player += draw(); dealer += draw(); player += draw(); dealer += draw()
        phase = Phase.PLAYER
        val pBj = score(player).first == 21
        val dBj = score(dealer).first == 21
        if (pBj || dBj) {
            phase = Phase.DONE
            result = when {
                pBj && dBj -> Result.PUSH
                pBj -> Result.BLACKJACK
                else -> Result.LOSE
            }
        }
    }

    val canDouble get() = phase == Phase.PLAYER && player.size == 2

    fun hit() {
        if (phase != Phase.PLAYER) return
        player += draw()
        val s = score(player).first
        if (s > 21) { phase = Phase.DONE; result = Result.BUST }
        else if (s == 21) stand()
    }

    fun double() {
        if (!canDouble) return
        bet *= 2
        player += draw()
        if (score(player).first > 21) { phase = Phase.DONE; result = Result.BUST } else stand()
    }

    fun stand() {
        if (phase != Phase.PLAYER) return
        phase = Phase.DEALER
    }

    /** Дилер берёт одну карту; false - дилер закончил (итог подсчитан). */
    fun dealerStep(): Boolean {
        if (phase != Phase.DEALER) return false
        val (d, _) = score(dealer)
        if (d < 17) {
            dealer += draw()
            return true
        }
        val p = score(player).first
        result = when {
            d > 21 || p > d -> Result.WIN
            p == d -> Result.PUSH
            else -> Result.LOSE
        }
        phase = Phase.DONE
        return false
    }

    /** Сколько фишек вернуть игроку (ставка уже снята при раздаче). */
    fun payout(): Int = when (result) {
        Result.BLACKJACK -> bet + bet * 3 / 2
        Result.WIN -> bet * 2
        Result.PUSH -> bet
        else -> 0
    }
}

/** Змейка: поле [w]×[h], стенки по краям, яблоко, рост и ускорение. */
class Snake(val w: Int = 18, val h: Int = 24, seed: Long) {
    private val rnd = Random(seed)
    val body = ArrayDeque<Int>()
    var dir = 1 // 0 вверх, 1 вправо, 2 вниз, 3 влево
        private set
    private var pending = 1
    var apple = -1
        private set
    var score = 0
        private set
    var dead = false
        private set

    init {
        val c = (h / 2) * w + w / 2
        body.addFirst(c - 2); body.addFirst(c - 1); body.addFirst(c)
        placeApple()
    }

    fun turn(d: Int) {
        if ((d + 2) % 4 != dir) pending = d
    }

    /** Один шаг; true - съели яблоко. */
    fun step(): Boolean {
        if (dead) return false
        dir = pending
        val head = body.first()
        val x = head % w
        val y = head / w
        val nx = x + when (dir) { 1 -> 1; 3 -> -1; else -> 0 }
        val ny = y + when (dir) { 0 -> -1; 2 -> 1; else -> 0 }
        if (nx !in 0 until w || ny !in 0 until h) { dead = true; return false }
        val n = ny * w + nx
        val eat = n == apple
        if (!eat) body.removeLast()
        if (n in body) { dead = true; return false }
        body.addFirst(n)
        if (eat) { score++; placeApple() }
        return eat
    }

    /** Пауза между шагами: быстрее с каждым яблоком. */
    fun delayMs(): Long = (170 - score * 4).coerceAtLeast(70).toLong()

    private fun placeApple() {
        val free = (0 until w * h).filter { it !in body }
        apple = if (free.isEmpty()) -1 else free[rnd.nextInt(free.size)]
    }
}
