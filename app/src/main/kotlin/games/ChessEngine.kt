// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

/**
 * Правила шахмат полностью: все ходы фигур, рокировка (короткая и длинная), взятие на проходе,
 * превращение пешки, шах, мат, пат, ничья по правилу 50 ходов и троекратному повторению.
 * Ходы - в записи «e2e4», превращение - «e7e8q».
 *
 * Клетка 0 = a1, 7 = h1, 56 = a8. Фигура: 0 - пусто, 1..6 - белые (пешка, конь, слон, ладья,
 * ферзь, король), 9..14 - чёрные (то же + 8).
 */
class ChessEngine {
    val board = IntArray(64)
    /** 0 - ходят белые, 1 - чёрные. */
    var side = 0
        private set
    private var castle = 15 // 1 - белые O-O, 2 - белые O-O-O, 4 - чёрные O-O, 8 - чёрные O-O-O
    private var ep = -1
    private var halfmove = 0
    val moves = ArrayList<String>()
    private val positions = HashMap<String, Int>()

    init {
        reset()
    }

    fun reset() {
        val back = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
        board.fill(0)
        for (f in 0 until 8) {
            board[f] = back[f]
            board[8 + f] = PAWN
            board[48 + f] = PAWN + 8
            board[56 + f] = back[f] + 8
        }
        side = 0
        castle = 15
        ep = -1
        halfmove = 0
        moves.clear()
        positions.clear()
        positions[key()] = 1
    }

    data class Move(val from: Int, val to: Int, val promo: Int = 0) {
        fun uci(): String = sq(from) + sq(to) + when (promo) {
            KNIGHT -> "n"
            BISHOP -> "b"
            ROOK -> "r"
            QUEEN -> "q"
            else -> ""
        }
    }

    enum class Status { PLAYING, CHECKMATE, STALEMATE, DRAW_50, DRAW_REPEAT, DRAW_MATERIAL }

    fun colorOf(p: Int) = if (p == 0) -1 else if (p >= 8) 1 else 0
    fun typeOf(p: Int) = p and 7

    private fun pseudo(forSide: Int, out: MutableList<Move>) {
        for (s in 0 until 64) {
            val p = board[s]
            if (p == 0 || colorOf(p) != forSide) continue
            val r = s / 8
            val f = s % 8
            when (typeOf(p)) {
                PAWN -> {
                    val dir = if (forSide == 0) 8 else -8
                    val startRank = if (forSide == 0) 1 else 6
                    val lastRank = if (forSide == 0) 7 else 0
                    val one = s + dir
                    if (one in 0..63 && board[one] == 0) {
                        addPawn(s, one, lastRank, out)
                        val two = s + 2 * dir
                        if (r == startRank && board[two] == 0) out += Move(s, two)
                    }
                    for (df in intArrayOf(-1, 1)) {
                        val nf = f + df
                        if (nf !in 0..7) continue
                        val t = s + dir + df
                        if (t !in 0..63) continue
                        if ((board[t] != 0 && colorOf(board[t]) != forSide) || t == ep) addPawn(s, t, lastRank, out)
                    }
                }
                KNIGHT -> for ((dr, df) in KNIGHT_D) step(s, r + dr, f + df, forSide, out)
                KING -> {
                    for (dr in -1..1) for (df in -1..1) if (dr != 0 || df != 0) step(s, r + dr, f + df, forSide, out)
                    castling(s, forSide, out)
                }
                BISHOP -> slide(s, forSide, DIAG, out)
                ROOK -> slide(s, forSide, ORTHO, out)
                QUEEN -> {
                    slide(s, forSide, DIAG, out)
                    slide(s, forSide, ORTHO, out)
                }
            }
        }
    }

    private fun addPawn(from: Int, to: Int, lastRank: Int, out: MutableList<Move>) {
        if (to / 8 == lastRank) {
            for (pr in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) out += Move(from, to, pr)
        } else {
            out += Move(from, to)
        }
    }

    private fun step(s: Int, r: Int, f: Int, forSide: Int, out: MutableList<Move>) {
        if (r !in 0..7 || f !in 0..7) return
        val t = r * 8 + f
        if (board[t] == 0 || colorOf(board[t]) != forSide) out += Move(s, t)
    }

    private fun slide(s: Int, forSide: Int, dirs: Array<IntArray>, out: MutableList<Move>) {
        for (d in dirs) {
            var r = s / 8 + d[0]
            var f = s % 8 + d[1]
            while (r in 0..7 && f in 0..7) {
                val t = r * 8 + f
                if (board[t] == 0) {
                    out += Move(s, t)
                } else {
                    if (colorOf(board[t]) != forSide) out += Move(s, t)
                    break
                }
                r += d[0]
                f += d[1]
            }
        }
    }

    private fun castling(s: Int, forSide: Int, out: MutableList<Move>) {
        val home = if (forSide == 0) 4 else 60
        if (s != home || attacked(home, 1 - forSide)) return
        val (kBit, qBit) = if (forSide == 0) 1 to 2 else 4 to 8
        if (castle and kBit != 0 && board[home + 1] == 0 && board[home + 2] == 0 &&
            typeOf(board[home + 3]) == ROOK && colorOf(board[home + 3]) == forSide &&
            !attacked(home + 1, 1 - forSide) && !attacked(home + 2, 1 - forSide)
        ) {
            out += Move(home, home + 2)
        }
        if (castle and qBit != 0 && board[home - 1] == 0 && board[home - 2] == 0 && board[home - 3] == 0 &&
            typeOf(board[home - 4]) == ROOK && colorOf(board[home - 4]) == forSide &&
            !attacked(home - 1, 1 - forSide) && !attacked(home - 2, 1 - forSide)
        ) {
            out += Move(home, home - 2)
        }
    }

    /** Атакована ли клетка [sq] фигурами стороны [by]. */
    fun attacked(sq: Int, by: Int): Boolean {
        val r = sq / 8
        val f = sq % 8
        // Пешки.
        val pr = if (by == 0) r - 1 else r + 1
        for (df in intArrayOf(-1, 1)) {
            val nf = f + df
            if (pr in 0..7 && nf in 0..7) {
                val p = board[pr * 8 + nf]
                if (p != 0 && colorOf(p) == by && typeOf(p) == PAWN) return true
            }
        }
        for ((dr, df) in KNIGHT_D) {
            val nr = r + dr
            val nf = f + df
            if (nr in 0..7 && nf in 0..7) {
                val p = board[nr * 8 + nf]
                if (p != 0 && colorOf(p) == by && typeOf(p) == KNIGHT) return true
            }
        }
        for (dr in -1..1) for (df in -1..1) {
            if (dr == 0 && df == 0) continue
            val nr = r + dr
            val nf = f + df
            if (nr in 0..7 && nf in 0..7) {
                val p = board[nr * 8 + nf]
                if (p != 0 && colorOf(p) == by && typeOf(p) == KING) return true
            }
        }
        if (ray(sq, by, DIAG, BISHOP)) return true
        if (ray(sq, by, ORTHO, ROOK)) return true
        return false
    }

    private fun ray(sq: Int, by: Int, dirs: Array<IntArray>, kind: Int): Boolean {
        for (d in dirs) {
            var r = sq / 8 + d[0]
            var f = sq % 8 + d[1]
            while (r in 0..7 && f in 0..7) {
                val p = board[r * 8 + f]
                if (p != 0) {
                    if (colorOf(p) == by && (typeOf(p) == kind || typeOf(p) == QUEEN)) return true
                    break
                }
                r += d[0]
                f += d[1]
            }
        }
        return false
    }

    fun kingSquare(s: Int): Int = board.indexOfFirst { it != 0 && colorOf(it) == s && typeOf(it) == KING }

    fun inCheck(s: Int = side): Boolean {
        val k = kingSquare(s)
        return k >= 0 && attacked(k, 1 - s)
    }

    /** Все разрешённые ходы стороны, которая ходит. */
    fun legal(): List<Move> {
        val ps = ArrayList<Move>(48)
        pseudo(side, ps)
        return ps.filter { m ->
            val u = make(m)
            val ok = !inCheckRaw(1 - side)
            unmake(u)
            ok
        }
    }

    private fun inCheckRaw(s: Int): Boolean {
        val k = kingSquare(s)
        return k >= 0 && attacked(k, 1 - s)
    }

    private class Undo(val m: Move, val moved: Int, val captured: Int, val capSq: Int, val castle: Int, val ep: Int, val half: Int, val rookFrom: Int, val rookTo: Int)

    private fun make(m: Move): Undo {
        val p = board[m.from]
        var captured = board[m.to]
        var capSq = m.to
        // Взятие на проходе.
        if (typeOf(p) == PAWN && m.to == ep && captured == 0) {
            capSq = if (colorOf(p) == 0) m.to - 8 else m.to + 8
            captured = board[capSq]
            board[capSq] = 0
        }
        var rf = -1
        var rt = -1
        if (typeOf(p) == KING && kotlin.math.abs(m.to - m.from) == 2) {
            if (m.to > m.from) {
                rf = m.from + 3
                rt = m.from + 1
            } else {
                rf = m.from - 4
                rt = m.from - 1
            }
            board[rt] = board[rf]
            board[rf] = 0
        }
        val u = Undo(m, p, captured, capSq, castle, ep, halfmove, rf, rt)
        board[m.to] = if (m.promo != 0) m.promo + (if (colorOf(p) == 1) 8 else 0) else p
        board[m.from] = 0
        // Права на рокировку.
        if (typeOf(p) == KING) castle = castle and (if (colorOf(p) == 0) 0b1100 else 0b0011)
        for (s in intArrayOf(m.from, m.to)) {
            when (s) {
                0 -> castle = castle and 2.inv()
                7 -> castle = castle and 1.inv()
                56 -> castle = castle and 8.inv()
                63 -> castle = castle and 4.inv()
            }
        }
        ep = if (typeOf(p) == PAWN && kotlin.math.abs(m.to - m.from) == 16) (m.from + m.to) / 2 else -1
        halfmove = if (typeOf(p) == PAWN || captured != 0) 0 else halfmove + 1
        side = 1 - side
        return u
    }

    private fun unmake(u: Undo) {
        side = 1 - side
        val m = u.m
        board[m.from] = u.moved
        board[m.to] = 0
        if (u.capSq != m.to) board[u.capSq] = u.captured else board[m.to] = u.captured
        if (u.rookFrom >= 0) {
            board[u.rookFrom] = board[u.rookTo]
            board[u.rookTo] = 0
        }
        castle = u.castle
        ep = u.ep
        halfmove = u.half
    }

    /** Сделать ход в записи «e2e4» (только разрешённый). true - получилось. */
    fun play(uci: String): Boolean {
        val m = legal().firstOrNull { it.uci() == uci } ?: return false
        make(m)
        moves += uci
        val k = key()
        positions[k] = (positions[k] ?: 0) + 1
        return true
    }

    fun status(): Status {
        if (legal().isEmpty()) return if (inCheck()) Status.CHECKMATE else Status.STALEMATE
        if (halfmove >= 100) return Status.DRAW_50
        if ((positions[key()] ?: 0) >= 3) return Status.DRAW_REPEAT
        if (insufficient()) return Status.DRAW_MATERIAL
        return Status.PLAYING
    }

    /**
     * Мата не поставить никому: король против короля; король и лёгкая фигура против короля;
     * у обоих только слоны, и все они на полях одного цвета.
     */
    private fun insufficient(): Boolean {
        var minors = 0
        var knights = 0
        var bishopColors = 0 // биты: 1 - слон на светлом поле, 2 - на тёмном
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0) continue
            when (typeOf(p)) {
                KING -> Unit
                KNIGHT -> { minors++; knights++ }
                BISHOP -> { minors++; bishopColors = bishopColors or (if ((sq / 8 + sq % 8) % 2 == 0) 2 else 1) }
                else -> return false
            }
        }
        if (minors <= 1) return true
        return knights == 0 && bishopColors != 3
    }

    // ---------------- бот ----------------

    private fun value(t: Int) = when (t) {
        PAWN -> 100
        KNIGHT -> 320
        BISHOP -> 330
        ROOK -> 500
        QUEEN -> 900
        KING -> 20000
        else -> 0
    }

    /** Оценка позиции с точки зрения белых: материал + положение фигур (таблицы полей). */
    private fun evaluate(): Int {
        var score = 0
        var bishopsW = 0
        var bishopsB = 0
        var material = 0
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0 || typeOf(p) == KING) continue
            material += value(typeOf(p))
        }
        val endgame = material < 2600
        for (sq in 0 until 64) {
            val p = board[sq]
            if (p == 0) continue
            val t = typeOf(p)
            val white = colorOf(p) == 0
            // Таблицы записаны для белых (a8..h8 первой строкой); для чёрных - зеркально.
            val idx = if (white) (7 - sq / 8) * 8 + sq % 8 else sq
            val pst = when (t) {
                PAWN -> PST_PAWN[idx]
                KNIGHT -> PST_KNIGHT[idx]
                BISHOP -> PST_BISHOP[idx]
                ROOK -> PST_ROOK[idx]
                QUEEN -> PST_QUEEN[idx]
                KING -> if (endgame) PST_KING_END[idx] else PST_KING_MID[idx]
                else -> 0
            }
            if (t == BISHOP) if (white) bishopsW++ else bishopsB++
            val v = value(t) + pst
            score += if (white) v else -v
        }
        // Пара слонов - небольшое преимущество.
        if (bishopsW >= 2) score += 30
        if (bishopsB >= 2) score -= 30
        return score
    }

    private fun pseudoLegal(): ArrayList<Move> {
        val ps = ArrayList<Move>(48)
        pseudo(side, ps)
        return ps
    }

    /** Порядок ходов: сначала взятия ценной фигуры дешёвой (MVV-LVA), превращения. */
    private fun order(list: MutableList<Move>, best: Move?) {
        list.sortByDescending { m ->
            if (m == best) 1_000_000 else {
                val victim = board[m.to]
                (if (victim != 0) 10 * value(typeOf(victim)) - value(typeOf(board[m.from])) / 10 + 10_000 else 0) +
                    (if (m.promo != 0) 9_000 else 0)
            }
        }
    }

    private var nodes = 0L
    private var deadline = 0L
    private var aborted = false

    private fun timeUp(): Boolean {
        if ((++nodes and 1023L) == 0L && System.currentTimeMillis() > deadline) aborted = true
        return aborted
    }

    /** Только взятия - чтобы не обрывать расчёт посреди размена. */
    private fun quiesce(alpha0: Int, beta: Int, depth: Int): Int {
        var alpha = alpha0
        val stand = if (side == 0) evaluate() else -evaluate()
        if (stand >= beta) return beta
        if (alpha < stand) alpha = stand
        if (depth <= 0 || timeUp()) return alpha
        val list = pseudoLegal().filter { board[it.to] != 0 || it.promo != 0 }.toMutableList()
        order(list, null)
        for (m in list) {
            val u = make(m)
            if (inCheckRaw(1 - side)) {
                unmake(u)
                continue
            }
            val sc = -quiesce(-beta, -alpha, depth - 1)
            unmake(u)
            if (sc >= beta) return beta
            if (sc > alpha) alpha = sc
        }
        return alpha
    }

    private fun negamax(depth: Int, alpha0: Int, beta: Int, ply: Int): Int {
        if (depth <= 0) return quiesce(alpha0, beta, 6)
        if (timeUp()) return 0
        if (halfmove >= 100) return 0
        var alpha = alpha0
        val list = pseudoLegal()
        order(list, null)
        var legalCount = 0
        for (m in list) {
            val u = make(m)
            if (inCheckRaw(1 - side)) {
                unmake(u)
                continue
            }
            legalCount++
            val sc = -negamax(depth - 1, -beta, -alpha, ply + 1)
            unmake(u)
            if (aborted) return 0
            if (sc >= beta) return beta
            if (sc > alpha) alpha = sc
        }
        if (legalCount == 0) return if (inCheckRaw(side)) -MATE + ply else 0
        return alpha
    }

    /**
     * Лучший ход для стороны, которая ходит: углубление по итерациям (1, 2, 3…) с альфа-бета
     * отсечением и расчётом разменов до «тихой» позиции. [level] 1..3 - сила бота: глубина и
     * время на ход; на лёгком уровне бот иногда выбирает не лучший, а один из хороших ходов.
     */
    fun bestMove(level: Int = 2, rnd: java.util.Random = java.security.SecureRandom()): Move? {
        val root = legal()
        if (root.isEmpty()) return null
        val maxDepth = when (level) { 1 -> 3; 2 -> 4; else -> 6 }
        val ms = when (level) { 1 -> 400L; 2 -> 1500L; else -> 3500L }
        nodes = 0
        aborted = false
        deadline = System.currentTimeMillis() + ms
        var best: Move = root[rnd.nextInt(root.size)]
        var scored: List<Pair<Move, Int>> = emptyList()
        val list = root.toMutableList()
        for (depth in 1..maxDepth) {
            order(list, best)
            val res = ArrayList<Pair<Move, Int>>()
            var alpha = -INF
            for (m in list) {
                val u = make(m)
                val sc = -negamax(depth - 1, -INF, -alpha + (if (level == 1) 60 else 0), 1)
                unmake(u)
                if (aborted) break
                res += m to sc
                if (sc > alpha) alpha = sc
            }
            if (aborted && res.isEmpty()) break
            if (res.isNotEmpty()) {
                scored = res.sortedByDescending { it.second }
                best = scored[0].first
            }
            if (aborted || scored.firstOrNull()?.second?.let { it > MATE - 100 } == true) break
        }
        // Лёгкий бот: случайный ход из почти равных лучшему (в пределах 0.6 пешки).
        if (level == 1 && scored.size > 1) {
            val top = scored[0].second
            val near = scored.filter { it.second >= top - 60 }
            best = near[rnd.nextInt(near.size)].first
        }
        return best
    }

    /** Восстановить партию по списку ходов. */
    fun load(list: List<String>): Boolean {
        reset()
        for (m in list) if (!play(m)) return false
        return true
    }

    private fun key(): String = board.joinToString(",") + "|$side|$castle|$ep"

    companion object {
        const val PAWN = 1
        const val KNIGHT = 2
        const val BISHOP = 3
        const val ROOK = 4
        const val QUEEN = 5
        const val KING = 6
        private val KNIGHT_D = listOf(2 to 1, 2 to -1, -2 to 1, -2 to -1, 1 to 2, 1 to -2, -1 to 2, -1 to -2)
        private val DIAG = arrayOf(intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
        private val ORTHO = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
        fun sq(i: Int): String = "${'a' + i % 8}${1 + i / 8}"
        private const val INF = 1_000_000
        private const val MATE = 100_000

        // Таблицы полей (взгляд белых, первая строка - 8-я горизонталь).
        private val PST_PAWN = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            50, 50, 50, 50, 50, 50, 50, 50,
            10, 10, 20, 30, 30, 20, 10, 10,
            5, 5, 10, 25, 25, 10, 5, 5,
            0, 0, 0, 20, 20, 0, 0, 0,
            5, -5, -10, 0, 0, -10, -5, 5,
            5, 10, 10, -20, -20, 10, 10, 5,
            0, 0, 0, 0, 0, 0, 0, 0,
        )
        private val PST_KNIGHT = intArrayOf(
            -50, -40, -30, -30, -30, -30, -40, -50,
            -40, -20, 0, 0, 0, 0, -20, -40,
            -30, 0, 10, 15, 15, 10, 0, -30,
            -30, 5, 15, 20, 20, 15, 5, -30,
            -30, 0, 15, 20, 20, 15, 0, -30,
            -30, 5, 10, 15, 15, 10, 5, -30,
            -40, -20, 0, 5, 5, 0, -20, -40,
            -50, -40, -30, -30, -30, -30, -40, -50,
        )
        private val PST_BISHOP = intArrayOf(
            -20, -10, -10, -10, -10, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 10, 10, 5, 0, -10,
            -10, 5, 5, 10, 10, 5, 5, -10,
            -10, 0, 10, 10, 10, 10, 0, -10,
            -10, 10, 10, 10, 10, 10, 10, -10,
            -10, 5, 0, 0, 0, 0, 5, -10,
            -20, -10, -10, -10, -10, -10, -10, -20,
        )
        private val PST_ROOK = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            5, 10, 10, 10, 10, 10, 10, 5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            -5, 0, 0, 0, 0, 0, 0, -5,
            0, 0, 0, 5, 5, 0, 0, 0,
        )
        private val PST_QUEEN = intArrayOf(
            -20, -10, -10, -5, -5, -10, -10, -20,
            -10, 0, 0, 0, 0, 0, 0, -10,
            -10, 0, 5, 5, 5, 5, 0, -10,
            -5, 0, 5, 5, 5, 5, 0, -5,
            0, 0, 5, 5, 5, 5, 0, -5,
            -10, 5, 5, 5, 5, 5, 0, -10,
            -10, 0, 5, 0, 0, 0, 0, -10,
            -20, -10, -10, -5, -5, -10, -10, -20,
        )
        private val PST_KING_MID = intArrayOf(
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -30, -40, -40, -50, -50, -40, -40, -30,
            -20, -30, -30, -40, -40, -30, -30, -20,
            -10, -20, -20, -20, -20, -20, -20, -10,
            20, 20, 0, 0, 0, 0, 20, 20,
            20, 30, 10, 0, 0, 10, 30, 20,
        )
        private val PST_KING_END = intArrayOf(
            -50, -40, -30, -20, -20, -30, -40, -50,
            -30, -20, -10, 0, 0, -10, -20, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 30, 40, 40, 30, -10, -30,
            -30, -10, 20, 30, 30, 20, -10, -30,
            -30, -30, 0, 0, 0, 0, -30, -30,
            -50, -30, -30, -30, -30, -30, -30, -50,
        )
    }
}
