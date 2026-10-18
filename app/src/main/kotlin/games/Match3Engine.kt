// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import org.json.JSONArray
import org.json.JSONObject

/**
 * «Три в ряд» - логика без интерфейса: поле 8×8 (у нарисованных уровней - с дырами) из 5-6 видов кристаллов.
 *
 * Что собирается:
 *  - ряд из 3 по горизонтали или вертикали;
 *  - квадрат 2×2 (4 кристалла) → бомба; прямоугольник 2×3 и больше (6+) → радужный шар;
 *  - ряд из 4 → линия (взрывает ряд или столбец); «уголок» / «Т» / «+» → бомба;
 *  - ряд из 5 и больше → радужный шар (убирает все кристаллы одного цвета).
 * Бонус срабатывает, если собрать его в ряд, ИЛИ просто поменять местами с любым соседом
 * (или дважды коснуться его). Два бонуса, поменянные друг с другом, дают комбинацию.
 *
 * Помехи (все слои хранятся по клеткам и считаются целями ровно по тому, что лежит на поле):
 *  - листья на клетках (снимаются, когда на клетке лопается кристалл; бывают двойные);
 *  - лёд на кристаллах (замёрзший кристалл нельзя двигать, и он не падает - лёд держит его
 *    на месте, остальные кристаллы пролетают мимо; фигура с ним или взрыв разбивает слой льда);
 *  - золотые залежи - каменные глыбы с золотом вместо кристалла (1-3 удара). Удар - фигура
 *    или лопнувший кристалл по соседству либо взрыв бонуса прямо по залежи. Разбитая залежь
 *    освобождает клетку, и туда падают кристаллы;
 *  - дыры (клеток нет): кристаллы пролетают их насквозь, как и залежи.
 *
 * Ход разбит на шаги, чтобы экран мог анимировать каждый: обмен → [resolve] → [gravity]
 * → снова [resolve], пока поле не успокоится.
 */
class Match3Engine(val level: Int, seed: Long = System.nanoTime()) {
    val rows = 8
    val cols = 8
    private val spec: Match3Levels.Spec? = Match3Levels.spec(level)

    /** Уровень нарисован вручную (101+). */
    val handmade: Boolean get() = spec != null
    val kinds = spec?.kinds ?: if (level < 3) 5 else 6
    val gem = IntArray(rows * cols) { -1 }
    val special = IntArray(rows * cols)
    val leaf = IntArray(rows * cols)

    /** Лёд на кристалле (слоёв): кристалл вмёрз в клетку и не двигается, пока лёд не разбит. */
    val ice = IntArray(rows * cols)

    /** Золотая залежь (сколько ударов осталось): клетка без кристалла, стоит на месте. */
    val gold = IntArray(rows * cols)

    /** Дыра: клетки нет вовсе. */
    val hole = BooleanArray(rows * cols)
    private val rnd = java.util.Random(seed)

    // ----- уровень -----
    var moves = spec?.moves ?: movesFor(level)
    var score = 0

    /** Ходов на уровне изначально (от них считаются звёзды). */
    val totalMoves: Int = moves

    /** Докуплено ходов после проигрыша (с докупкой - не больше одной звезды). */
    var extraMoves = 0

    /** Сколько кристаллов собрано за уровень: чем больше - тем дороже каждый следующий и больше наград. */
    var gemsCollected = 0

    /** Цели: вид кристалла (0..5), LEAF_GOAL, ICE_GOAL, GOLD_GOAL или SCORE_GOAL - и сколько. */
    val goalKind = ArrayList<Int>()
    val goalNeed = ArrayList<Int>()
    val goalHave = ArrayList<Int>()
    val star1: Int
    val star2: Int
    val star3: Int

    /** Что есть на уровне (для вступительных подсказок): см. F_*. */
    val features = HashSet<String>()

    /** Счётчик каскада в текущем ходе (каждый следующий каскад дороже). */
    var cascade = 0
        private set

    /** Бонус за оставшиеся ходы (начисляется один раз при победе). */
    var endBonus = 0
        private set
    var finished = false
        private set

    init {
        if (spec != null) initHandmade(spec) else initGenerated()
        sanitize()
        // Цель по помехам - ровно столько, сколько их реально можно выбить на этом поле.
        for (g in goalKind.indices) {
            val real = when (goalKind[g]) {
                LEAF_GOAL -> leaf.indices.sumOf { if (hole[it]) 0 else leaf[it] }
                ICE_GOAL -> ice.indices.sumOf { if (hole[it] || gold[it] > 0) 0 else ice[it] }
                GOLD_GOAL -> gold.sum()
                else -> continue
            }
            if (real in 1 until goalNeed[g]) goalNeed[g] = real
        }
        val stars = spec?.stars ?: Match3Levels.generatedStars(level)
        star1 = stars[0]
        star2 = stars[1]
        star3 = stars[2]
        if (hole.any { it }) features += F_HOLES
        if (leaf.any { it > 0 }) features += F_LEAF
        if (leaf.any { it > 1 }) features += F_LEAF2
        if (ice.any { it > 0 }) features += F_ICE
        if (ice.any { it > 1 }) features += F_ICE2
        if (gold.any { it > 0 }) features += F_GOLD
        if (gold.any { it > 1 }) features += F_GOLD2
        if (SCORE_GOAL in goalKind) features += F_SCORE
        syncGoals()
        if (findMove() == null) shuffle()
    }

    /**
     * Помехи только там, где их можно выбить: в дыре нет ни листьев, ни льда; лёд - только на
     * клетке с кристаллом (не на залежи). Иначе цель «листья/лёд» могла застрять на последнем
     * невидимом слое - счётчик показывал «осталось 1», а на поле ничего не было.
     */
    private fun sanitize() {
        for (i in gem.indices) {
            if (hole[i]) {
                leaf[i] = 0
                ice[i] = 0
                gold[i] = 0
            }
            if (gold[i] > 0) ice[i] = 0
        }
    }

    private fun initHandmade(s: Match3Levels.Spec) {
        val map = s.map ?: return
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = idx(r, c)
            when (map.getOrNull(r)?.getOrNull(c) ?: '.') {
                '#' -> hole[i] = true
                'l' -> leaf[i] = 1
                'L' -> leaf[i] = 2
                'i' -> ice[i] = 1
                'I' -> ice[i] = 2
                'j' -> {
                    ice[i] = 1
                    leaf[i] = 1
                }
                'k' -> {
                    ice[i] = 2
                    leaf[i] = 1
                }
                '1' -> gold[i] = 1
                '2' -> gold[i] = 2
                '3' -> gold[i] = 3
            }
        }
        if (leaf.sum() > 0) addGoal(LEAF_GOAL, leaf.sum())
        for ((k, n) in s.colors) addGoal(k.coerceIn(0, kinds - 1), n)
        if (ice.sum() > 0) addGoal(ICE_GOAL, ice.sum())
        if (gold.sum() > 0) addGoal(GOLD_GOAL, gold.sum())
        if (s.scoreGoal > 0) addGoal(SCORE_GOAL, s.scoreGoal)
        fillFresh()
    }

    /**
     * Прежний генератор (уровни 1-100 и всё после нарисованных). Порядок обращений к
     * генератору случайных чисел не менять - иначе изменятся уже пройденные уровни.
     */
    private fun initGenerated() {
        val lr = java.util.Random(level * 7919L + 13)
        // С 18-го уровня цели были непосильны (по прогонам бота - 5-50 % побед при 21-28
        // ходах): меньше листьев и двойных листьев, меньше кристаллов в целях.
        val calm = level >= REBALANCE_FROM
        // После нарисованных уровней поле фигурное - помех ещё чуть меньше.
        val post = level > Match3Levels.LAST
        // После нарисованных уровней - фигурные поля.
        if (level >= SHAPES_FROM) {
            val pat = if (level > Match3Levels.LAST) HOLE_PATTERNS[level % HOLE_PATTERNS.size]
            else MID_SHAPES[(level - SHAPES_FROM) % MID_SHAPES.size]
            for (r in 0 until rows) for (c in 0 until cols) if (pat[r][c] == '#') hole[idx(r, c)] = true
        }
        // Листья - с 3-го уровня, больше и толще с ростом.
        val leaves = if (level < 3) 0 else (3 + (level * 3) % 5 + level / 15).coerceAtMost(if (post) 5 else if (calm) 5 else 9)
        if (leaves > 0) {
            var placed = 0
            var guard = 0
            while (placed < leaves && guard++ < 500) {
                val r = 1 + lr.nextInt(rows - 1)
                val c = lr.nextInt(cols)
                val i = r * cols + c
                if (leaf[i] == 0 && !hole[i]) {
                    leaf[i] = if (level >= 8 && lr.nextInt(100) < (level * 3).coerceAtMost(if (calm) 30 else 60)) 2 else 1
                    placed++
                }
            }
            // Симметрия - красивее.
            for (r in 0 until rows) for (c in 0 until cols / 2) {
                val a = r * cols + c
                val b = r * cols + (cols - 1 - c)
                if (leaf[a] > 0 || leaf[b] > 0) {
                    val v = maxOf(leaf[a], leaf[b])
                    if (!hole[a]) leaf[a] = v
                    if (!hole[b]) leaf[b] = v
                }
            }
            addGoal(LEAF_GOAL, leaf.sum())
        }
        val k1 = lr.nextInt(kinds)
        addGoal(k1, (14 + level / 2 + (level * 7) % 5).coerceAtMost(if (post) 20 else if (calm) 20 else 30))
        if (level >= 4 && level % 2 == 0) {
            var k2 = lr.nextInt(kinds)
            if (k2 == k1) k2 = (k2 + 1) % kinds
            addGoal(k2, (10 + level / 3).coerceAtMost(if (post) 14 else if (calm) 14 else 24))
        }
        fillFresh()
        // Лёд - с 6-го уровня (с 15-го бывает в два слоя).
        val iceN = if (level < 6) 0 else (3 + level / 7).coerceAtMost(if (post) 4 else if (calm) 5 else 8)
        if (iceN > 0) {
            var placed = 0
            var guard = 0
            while (placed < iceN && guard++ < 500) {
                val i = lr.nextInt(gem.size)
                if (ice[i] == 0 && row(i) >= 2 && !hole[i]) {
                    ice[i] = if (level >= 15 && lr.nextInt(3) == 0) 2 else 1
                    placed++
                }
            }
            addGoal(ICE_GOAL, ice.sum())
        }
        // Золотые залежи - с 30-го уровня на каждом втором (парами, симметрично).
        if (level >= 30 && level % 2 == 1) {
            val pairs = (1 + (level - 30) / 25).coerceAtMost(if (post) 2 else 3)
            var placed = 0
            var guard = 0
            while (placed < pairs && guard++ < 300) {
                val r = 2 + lr.nextInt(rows - 2)
                val c = lr.nextInt(cols / 2)
                val a = idx(r, c)
                val b = idx(r, cols - 1 - c)
                if (listOf(a, b).all { gold[it] == 0 && leaf[it] == 0 && ice[it] == 0 && !hole[it] }) {
                    val hits = 1 + (if (level >= 50 && lr.nextInt(2) == 0) 1 else 0) + (if (level >= 80 && lr.nextInt(3) == 0) 1 else 0)
                    gold[a] = hits
                    gold[b] = hits
                    gem[a] = -1
                    gem[b] = -1
                    special[a] = 0
                    special[b] = 0
                    placed++
                }
            }
            if (gold.sum() > 0) addGoal(GOLD_GOAL, gold.sum())
        }
        for (i in gem.indices) if (hole[i]) gem[i] = -1
    }

    private fun addGoal(kind: Int, need: Int) {
        goalKind += kind
        goalNeed += need
        goalHave += 0
    }

    fun idx(r: Int, c: Int) = r * cols + c
    fun row(i: Int) = i / cols
    fun col(i: Int) = i % cols

    /** В клетке не может быть кристалла (дыра или залежь). */
    fun blocked(i: Int) = hole[i] || gold[i] > 0

    private fun randomGem(): Int = rnd.nextInt(kinds)

    /** Новое поле без готовых рядов/квадратов и с хотя бы одним возможным ходом. */
    fun fillFresh() {
        var tries = 0
        do {
            for (i in gem.indices) {
                special[i] = 0
                if (blocked(i)) {
                    gem[i] = -1
                    continue
                }
                var guard = 0
                do {
                    gem[i] = randomGem()
                } while (matchAt(i) && guard++ < 30)
            }
            tries++
        } while ((findMove() == null || hasMatches()) && tries < 50)
    }

    private fun matchAt(i: Int): Boolean {
        val r = row(i)
        val c = col(i)
        val g = color(i)
        if (g < 0) return false
        if (c >= 2 && color(i - 1) == g && color(i - 2) == g) return true
        if (r >= 2 && color(i - cols) == g && color(i - 2 * cols) == g) return true
        if (r >= 1 && c >= 1 && color(i - 1) == g && color(i - cols) == g && color(i - cols - 1) == g) return true
        return false
    }

    /** Цвет для фигур: радужный шар - «без цвета» и в ряды не собирается. */
    private fun color(i: Int): Int = if (special[i] == RAINBOW || blocked(i)) -1 else gem[i]

    fun adjacent(a: Int, b: Int) = (row(a) == row(b) && kotlin.math.abs(col(a) - col(b)) == 1) || (col(a) == col(b) && kotlin.math.abs(row(a) - row(b)) == 1)

    /** Можно ли двигать кристалл (замёрзший - нельзя). */
    fun movable(i: Int) = i in gem.indices && gem[i] >= 0 && ice[i] == 0 && !blocked(i)

    private fun neighbors(i: Int): IntArray {
        val r = row(i)
        val c = col(i)
        val out = ArrayList<Int>(4)
        if (r > 0) out += i - cols
        if (r < rows - 1) out += i + cols
        if (c > 0) out += i - 1
        if (c < cols - 1) out += i + 1
        return out.toIntArray()
    }

    // ----- поиск фигур -----

    class Clear(
        /** Клетки, которые исчезают, и вид кристалла в каждой (для частиц и целей). */
        val cells: List<Int>,
        val kindsOf: List<Int>,
        /** Новые бонусы: клетка → (вид, бонус). */
        val created: Map<Int, Pair<Int, Int>>,
        /** Сработавшие бонусы (для эффектов: линия, взрыв, радуга). */
        val fired: List<Pair<Int, Int>>,
        /** Очки за этот шаг (уже с множителем каскада). */
        val gained: Int,
        /** Где снят слой листьев. */
        val leaves: List<Int>,
        /** Где треснул слой льда. */
        val iceBroken: List<Int> = emptyList(),
        /** Самая крупная собранная фигура (для подписи): 0 ряд, 1 квадрат, 2 большой квадрат, 3 уголок, 4 линия 4, 5 линия 5. */
        val shape: Int = 0,
        /** Какой бонус был в каждой исчезающей клетке (чтобы он красиво лопнул, а не «стал» кристаллом). */
        val specialsOf: List<Int> = emptyList(),
        /** По каким залежам пришёлся удар. */
        val goldHit: List<Int> = emptyList(),
        /** Каскад x4 подарил ход. */
        val bonusMove: Boolean = false,
    )

    /** Ряды по 3+: (клетки, горизонтальный). */
    private fun runs(): List<Pair<List<Int>, Boolean>> {
        val out = ArrayList<Pair<List<Int>, Boolean>>()
        for (r in 0 until rows) {
            var c = 0
            while (c < cols) {
                val g = color(idx(r, c))
                var e = c + 1
                while (e < cols && g >= 0 && color(idx(r, e)) == g) e++
                if (g >= 0 && e - c >= 3) out += (c until e).map { idx(r, it) } to true
                c = e
            }
        }
        for (c in 0 until cols) {
            var r = 0
            while (r < rows) {
                val g = color(idx(r, c))
                var e = r + 1
                while (e < rows && g >= 0 && color(idx(e, c)) == g) e++
                if (g >= 0 && e - r >= 3) out += (r until e).map { idx(it, c) } to false
                r = e
            }
        }
        return out
    }

    /** Квадраты 2×2 одного цвета (левая верхняя клетка). */
    private fun squares(): List<Int> {
        val out = ArrayList<Int>()
        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
            val i = idx(r, c)
            val g = color(i)
            if (g >= 0 && color(i + 1) == g && color(i + cols) == g && color(i + cols + 1) == g) out += i
        }
        return out
    }

    fun hasMatches() = runs().isNotEmpty() || squares().isNotEmpty()

    /** Клетки, которые задевает сработавший бонус. */
    private fun area(i: Int, sp: Int, out: MutableSet<Int>, rainbowKind: Int = -1) {
        val r = row(i)
        val c = col(i)
        when (sp) {
            H -> for (x in 0 until cols) out += idx(r, x)
            V -> for (y in 0 until rows) out += idx(y, c)
            BOMB -> for (y in r - 1..r + 1) for (x in c - 1..c + 1) if (y in 0 until rows && x in 0 until cols) out += idx(y, x)
            RAINBOW -> {
                val k = if (rainbowKind >= 0) rainbowKind else mostCommonKind()
                for (j in gem.indices) if (!blocked(j) && gem[j] == k && special[j] != RAINBOW) out += j
                out += i
            }
        }
    }

    private fun mostCommonKind(): Int {
        val cnt = IntArray(kinds)
        for (j in gem.indices) if (!blocked(j) && gem[j] in 0 until kinds && special[j] != RAINBOW) cnt[gem[j]]++
        return cnt.indices.maxByOrNull { cnt[it] } ?: 0
    }

    /**
     * Обмен двух соседних клеток. true - ход принят (будет фигура, сработает бонус или
     * комбинация бонусов); false - поле не изменилось.
     */
    fun swap(a: Int, b: Int): Boolean {
        if (finished || moves <= 0) return false
        if (!adjacent(a, b) || !movable(a) || !movable(b)) return false
        val sa = special[a]
        val sb = special[b]
        cascade = 0
        if (sa == RAINBOW || sb == RAINBOW || (sa != 0 && sb != 0)) {
            exchange(a, b)
            moves--
            pendingCombo = a to b
            lastSwap = IntArray(0)
            return true
        }
        exchange(a, b)
        if (sa != 0 || sb != 0) {
            // Бонус поменяли с обычным кристаллом - он срабатывает на новом месте.
            moves--
            pendingFire = if (sa != 0) b else a
            lastSwap = intArrayOf(a, b)
            return true
        }
        if (!hasMatches()) {
            exchange(a, b)
            return false
        }
        moves--
        lastSwap = intArrayOf(a, b)
        return true
    }

    /** Двойное касание по бонусу - взорвать его (тратит ход). */
    fun activate(i: Int): Boolean {
        if (finished || moves <= 0) return false
        if (i !in gem.indices || special[i] == 0 || !movable(i)) return false
        moves--
        cascade = 0
        pendingFire = i
        lastSwap = IntArray(0)
        return true
    }

    private fun exchange(a: Int, b: Int) {
        val g = gem[a]
        gem[a] = gem[b]
        gem[b] = g
        val s = special[a]
        special[a] = special[b]
        special[b] = s
        val ic = ice[a]
        ice[a] = ice[b]
        ice[b] = ic
    }

    private var lastSwap = IntArray(0)
    private var pendingCombo: Pair<Int, Int>? = null
    private var pendingFire = -1

    /** Один шаг: что исчезает (фигуры, бонусы, комбинации). null - поле спокойно. */
    fun resolve(): Clear? {
        val gone = LinkedHashSet<Int>()
        val created = HashMap<Int, Pair<Int, Int>>()
        val fired = ArrayList<Pair<Int, Int>>()
        var shape = 0
        // Бонусы, которые уже отработали в комбинации (второй раз не взрываются).
        val done = HashSet<Int>()
        val combo = pendingCombo
        pendingCombo = null
        val fire = pendingFire
        pendingFire = -1
        if (combo != null) {
            val (a, b) = combo
            val sa = special[a]
            val sb = special[b]
            when {
                sa == RAINBOW && sb == RAINBOW -> {
                    // Всё поле, включая залежи.
                    gone.addAll(gem.indices.filter { !hole[it] })
                    fired += a to RAINBOW
                    done += a
                    done += b
                }
                sa == RAINBOW || sb == RAINBOW -> {
                    val rb = if (sa == RAINBOW) a else b
                    val other = if (rb == a) b else a
                    val k = gem[other]
                    val os = special[other]
                    fired += rb to RAINBOW
                    gone += rb
                    // Сам шар отработал и второй раз не срабатывает.
                    done += rb
                    if (os != 0) {
                        // Радуга + бонус: все кристаллы этого цвета становятся бонусами и срабатывают.
                        for (j in gem.indices) if (j != rb && !blocked(j) && gem[j] == k && special[j] != RAINBOW) {
                            special[j] = if (os == H || os == V) (if (rnd.nextBoolean()) H else V) else os
                            gone += j
                        }
                    } else {
                        for (j in gem.indices) if (j != rb && !blocked(j) && gem[j] == k && special[j] != RAINBOW) gone += j
                    }
                }
                (sa == H || sa == V) && (sb == H || sb == V) -> {
                    area(b, H, gone)
                    area(b, V, gone)
                    fired += b to H
                    fired += b to V
                    done += a
                    done += b
                    gone += a
                    gone += b
                }
                sa == BOMB && sb == BOMB -> {
                    val r = row(b)
                    val c = col(b)
                    for (y in r - 2..r + 2) for (x in c - 2..c + 2) if (y in 0 until rows && x in 0 until cols) gone += idx(y, x)
                    fired += b to BOMB
                    done += a
                    done += b
                    gone += a
                    gone += b
                }
                else -> {
                    // Линия + бомба: три ряда и три столбца.
                    val r = row(b)
                    val c = col(b)
                    for (y in r - 1..r + 1) if (y in 0 until rows) for (x in 0 until cols) gone += idx(y, x)
                    for (x in c - 1..c + 1) if (x in 0 until cols) for (y in 0 until rows) gone += idx(y, x)
                    fired += b to BOMB
                    fired += b to H
                    fired += b to V
                    done += a
                    done += b
                    gone += a
                    gone += b
                }
            }
        } else {
            if (fire >= 0 && special[fire] != 0) gone += fire
            shape = findShapes(gone, created)
            if (gone.isEmpty()) return null
            lastSwap = IntArray(0)
        }
        // Цепная реакция: бонусы среди исчезающих тоже срабатывают.
        // Замёрзший бонус не взрывается - сначала трескается лёд.
        val queue = ArrayDeque(gone.filter { special[it] != 0 && it !in created.keys && it !in done })
        while (queue.isNotEmpty()) {
            val j = queue.removeFirst()
            if (!done.add(j)) continue
            val sp = special[j]
            if (sp == 0 || ice[j] > 0 || blocked(j)) continue
            fired += j to sp
            val more = LinkedHashSet<Int>()
            area(j, sp, more)
            for (m in more) if (gone.add(m) && special[m] != 0 && m !in created.keys && m !in done) queue += m
        }
        cascade++
        return apply(gone, created, fired, shape, bonusPerShape = true)
    }

    /**
     * Найти фигуры: ряды, квадраты, уголки. Клетки - в [gone], новые бонусы - в [created].
     * Возвращает самую ценную фигуру.
     */
    private fun findShapes(gone: MutableSet<Int>, created: MutableMap<Int, Pair<Int, Int>>): Int {
        val rs = runs()
        val sq = squares()
        if (rs.isEmpty() && sq.isEmpty()) return 0
        // Объединяем клетки в группы (один цвет, связаны рядами или квадратами).
        val parent = IntArray(gem.size) { it }
        fun find(x: Int): Int {
            var a = x
            while (parent[a] != a) {
                parent[a] = parent[parent[a]]
                a = parent[a]
            }
            return a
        }
        fun union(a: Int, b: Int) {
            val x = find(a)
            val y = find(b)
            if (x != y) parent[x] = y
        }
        val inAny = BooleanArray(gem.size)
        val hRun = IntArray(gem.size)
        val vRun = IntArray(gem.size)
        val inSquare = BooleanArray(gem.size)
        for ((cells, horiz) in rs) {
            for (k in cells.indices) {
                inAny[cells[k]] = true
                if (horiz) hRun[cells[k]] = cells.size else vRun[cells[k]] = cells.size
                if (k > 0) union(cells[k - 1], cells[k])
            }
        }
        for (s in sq) {
            val four = intArrayOf(s, s + 1, s + cols, s + cols + 1)
            for (c in four) {
                inAny[c] = true
                inSquare[c] = true
                union(four[0], c)
            }
        }
        val groups = HashMap<Int, MutableList<Int>>()
        for (i in gem.indices) if (inAny[i]) groups.getOrPut(find(i)) { ArrayList() } += i
        var best = 0
        for (cells in groups.values) {
            gone.addAll(cells)
            val k = gem[cells[0]]
            val maxRun = cells.maxOf { maxOf(hRun[it], vRun[it]) }
            val cross = cells.any { hRun[it] >= 3 && vRun[it] >= 3 }
            val squareCells = cells.count { inSquare[it] }
            // Новый бонус - в клетке, куда передвинули кристалл; иначе - на пересечении
            // уголка или в середине ряда. Только в свободной клетке: не на месте другого
            // бонуса и не подо льдом.
            val free = cells.filter { special[it] == 0 && ice[it] == 0 }
            val pivot = free.firstOrNull { it in lastSwap }
                ?: free.firstOrNull { hRun[it] >= 3 && vRun[it] >= 3 }
                ?: free.getOrNull(free.size / 2)
            val (sp, sh) = when {
                maxRun >= 5 -> RAINBOW to 5
                squareCells >= 6 -> RAINBOW to 2
                cross -> BOMB to 3
                squareCells >= 4 -> BOMB to 1
                maxRun == 4 -> (if (cells.all { row(it) == row(cells[0]) }) H else V) to 4
                else -> 0 to 0
            }
            if (sp != 0 && pivot != null && created.size < 6) created[pivot] = k to sp
            best = maxOf(best, sh)
        }
        return best
    }

    /** Убрать клетки [gone]: лёд, листья, залежи, цели, очки; положить новые бонусы. */
    private fun apply(gone: Set<Int>, created: Map<Int, Pair<Int, Int>>, fired: List<Pair<Int, Int>>, shape: Int, bonusPerShape: Boolean): Clear {
        val cells = ArrayList<Int>()
        val iceHit = ArrayList<Int>()
        val goldHit = LinkedHashSet<Int>()
        for (cl in gone) {
            if (hole[cl]) continue
            if (gold[cl] > 0) {
                // Взрыв прямо по залежи.
                goldHit += cl
                continue
            }
            if (gem[cl] < 0 || cl in created.keys) continue
            if (ice[cl] > 0) {
                // Лёд разбивается, кристалл остаётся.
                ice[cl]--
                iceHit += cl
                continue
            }
            cells += cl
        }
        // Залежи рядом с лопнувшим кристаллом, новым бонусом или треснувшим льдом - тоже удар
        // (не больше одного удара за шаг).
        for (a in cells.asSequence() + created.keys.asSequence() + iceHit.asSequence()) {
            for (nb in neighbors(a)) if (gold[nb] > 0) goldHit += nb
        }
        for (g in goldHit) gold[g]--
        val kindsOf = cells.map { gem[it] }
        val specialsOf = cells.map { special[it] }
        // Листья: слой снимается под каждой задетой клеткой (кристалл лопнул, ушёл в новый
        // бонус или треснул лёд на нём). Залежи и дыры листьев не имеют.
        val leavesHit = ArrayList<Int>()
        for (cl in gone) if (leaf[cl] > 0 && !blocked(cl)) {
            leaf[cl]--
            leavesHit += cl
        }
        for (g in goalKind.indices) {
            val k = goalKind[g]
            if (k in 0 until 6) {
                val add = cells.indices.count { kindsOf[it] == k && specialsOf[it] != RAINBOW }
                goalHave[g] = (goalHave[g] + add).coerceAtMost(goalNeed[g])
            }
        }
        val shapeBonus = if (bonusPerShape) SHAPE_SCORE[shape.coerceIn(0, 5)] else 0
        gemsCollected += cells.size
        // Чем больше кристаллов уже собрано, тем дороже каждый новый (+5 очков за каждые 40, до +60),
        // а крупная очистка (5+ за раз) приносит ещё по 20 за кристалл.
        val gemValue = GEM_SCORE + (gemsCollected / 40 * 5).coerceAtMost(60) + (if (cells.size >= 5) 20 else 0)
        val base = cells.size * gemValue + iceHit.size * ICE_SCORE + leavesHit.size * LEAF_SCORE + goldHit.size * GOLD_SCORE +
            created.size * CREATE_SCORE + fired.size * FIRE_SCORE + shapeBonus
        val gained = base * cascade.coerceIn(1, MAX_MULT)
        score += gained
        for (cl in cells) {
            gem[cl] = -1
            special[cl] = 0
        }
        for ((cl, v) in created) {
            gem[cl] = v.first
            special[cl] = v.second
        }
        // Каскад x4 - ещё один ход в подарок (один раз за ход).
        var bonusMove = false
        if (cascade == 4 && moves > 0) {
            moves++
            bonusMove = true
        }
        syncGoals()
        return Clear(cells, kindsOf, created, fired, gained, leavesHit, iceHit, shape, specialsOf, goldHit.toList(), bonusMove)
    }

    /**
     * Цели по помехам и очкам считаются прямо по полю: «снято» = было в начале − осталось
     * сейчас. Так счётчик всегда совпадает с тем, что видно (и после бустеров, и после загрузки).
     */
    fun syncGoals() {
        for (g in goalKind.indices) {
            val left = when (goalKind[g]) {
                LEAF_GOAL -> leaf.indices.sumOf { if (hole[it]) 0 else leaf[it] }
                ICE_GOAL -> ice.indices.sumOf { if (hole[it] || gold[it] > 0) 0 else ice[it] }
                GOLD_GOAL -> gold.sum()
                SCORE_GOAL -> {
                    goalHave[g] = score.coerceIn(0, goalNeed[g])
                    continue
                }
                else -> continue
            }
            goalHave[g] = (goalNeed[g] - left).coerceIn(0, goalNeed[g])
        }
    }

    /** Сколько ещё осталось по цели [g]. */
    fun goalLeft(g: Int) = (goalNeed[g] - goalHave[g]).coerceAtLeast(0)

    /**
     * Падение: (откуда, куда) для сдвинутых кристаллов и список новых (клетка, на сколько
     * рядов выше своего места появляется). Дыры, залежи и вмёрзшие в лёд кристаллы стоят на
     * месте - остальные пролетают их насквозь, поэтому лёд всегда там, где его нарисовали.
     */
    fun gravity(): Pair<List<IntArray>, List<IntArray>> {
        val moved = ArrayList<IntArray>()
        val spawned = ArrayList<IntArray>()
        for (c in 0 until cols) {
            // Клетки со льдом стоят на месте всегда: если кристалл под льдом пропал (бустер),
            // новый появляется прямо в нём - лёд не исчезает и не «уезжает».
            for (r in 0 until rows) {
                val f = idx(r, c)
                if (ice[f] > 0 && !blocked(f) && gem[f] < 0) {
                    gem[f] = randomGem()
                    special[f] = 0
                    spawned += intArrayOf(f, 0)
                }
            }
            val open = (rows - 1 downTo 0).map { idx(it, c) }.filter { !blocked(it) && ice[it] == 0 }
            if (open.isEmpty()) continue
            val have = open.filter { gem[it] >= 0 }
            val g = have.map { gem[it] }
            val s = have.map { special[it] }
            for (n in have.indices) {
                val t = open[n]
                val from = have[n]
                if (from != t) moved += intArrayOf(from, t)
            }
            for (cl in open) {
                gem[cl] = -1
                special[cl] = 0
            }
            for (n in have.indices) {
                val t = open[n]
                gem[t] = g[n]
                special[t] = s[n]
            }
            if (have.size < open.size) {
                // Новые кристаллы: стопкой над полем, нижний - сразу над верхним краем.
                val lowest = row(open[have.size])
                for (n in have.size until open.size) {
                    val t = open[n]
                    gem[t] = randomGem()
                    special[t] = 0
                    spawned += intArrayOf(t, lowest + 1)
                }
            }
        }
        return moved to spawned
    }

    /** Возможный ход (для подсказки) или null: сначала фигуры, потом бонусы. */
    fun findMove(): IntArray? {
        for (i in gem.indices) {
            if (!movable(i)) continue
            for (j in intArrayOf(i + 1, i + cols)) {
                if (j >= gem.size || !adjacent(i, j) || !movable(j)) continue
                if (special[i] != 0 || special[j] != 0) continue
                exchange(i, j)
                val ok = hasMatches()
                exchange(i, j)
                if (ok) return intArrayOf(i, j)
            }
        }
        for (i in gem.indices) {
            if (special[i] == 0 || !movable(i)) continue
            for (j in intArrayOf(i + 1, i - 1, i + cols, i - cols)) {
                if (j in gem.indices && adjacent(i, j) && movable(j)) return intArrayOf(i, j)
            }
        }
        return null
    }

    /** Все допустимые ходы (для бота-проверки уровней). */
    fun allMoves(): List<IntArray> {
        val out = ArrayList<IntArray>()
        for (i in gem.indices) {
            if (!movable(i)) continue
            for (j in intArrayOf(i + 1, i + cols)) {
                if (j >= gem.size || !adjacent(i, j) || !movable(j)) continue
                if (special[i] != 0 || special[j] != 0) {
                    out += intArrayOf(i, j)
                    continue
                }
                exchange(i, j)
                val ok = hasMatches()
                exchange(i, j)
                if (ok) out += intArrayOf(i, j)
            }
        }
        return out
    }

    /** Перемешать (ходов не осталось или бустер). Бонусы, лёд и залежи остаются на местах. */
    fun shuffle() {
        cascade = 0
        val idxs = gem.indices.filter { gem[it] >= 0 && !blocked(it) && special[it] == 0 && ice[it] == 0 }
        var tries = 0
        do {
            val pool = idxs.map { gem[it] }.shuffled(rnd)
            idxs.forEachIndexed { n, i -> gem[i] = pool[n] }
            tries++
        } while ((hasMatches() || findMove() == null) && tries < 80)
        // Не получилось - новые кристаллы (бонусы и лёд на местах). matchAt смотрит только
        // влево и вверх, а неподвижные клетки бывают и справа/снизу - поэтому проверяем всё поле.
        var more = 0
        while ((hasMatches() || findMove() == null) && more++ < 60) {
            for (i in idxs) {
                var guard = 0
                do {
                    gem[i] = randomGem()
                } while (matchAt(i) && guard++ < 20)
            }
        }
    }

    // ----- бустеры -----

    /** Подходит ли клетка для бустера (дыра - нет). */
    fun boostable(i: Int) = i in gem.indices && !hole[i] && (gem[i] >= 0 || gold[i] > 0)

    /** Молот: разбить один кристалл (бонус в нём срабатывает), слой льда или удар по залежи. */
    fun hammer(i: Int) {
        if (!boostable(i)) return
        pendingSingle = setOf(i)
        boosterCenter = i
    }

    /** Бомба-бустер: взрыв 3×3. */
    fun bomb(i: Int) {
        if (i !in gem.indices || hole[i]) return
        val s = LinkedHashSet<Int>()
        area(i, BOMB, s)
        pendingSingle = s
        boosterCenter = i
    }

    /** Волшебная палочка: кристалл становится радужным шаром (лёд с него снимается и засчитывается). */
    fun wand(i: Int): Boolean {
        if (i !in gem.indices || blocked(i) || gem[i] < 0) return false
        special[i] = RAINBOW
        ice[i] = 0
        syncGoals()
        return true
    }

    private var pendingSingle: Set<Int>? = null
    private var boosterCenter = -1

    /** Шаг для молота/бомбы - как [resolve], но клетки заданы. */
    fun resolveBooster(): Clear? {
        val set = pendingSingle ?: return null
        pendingSingle = null
        cascade = 1
        lastSwap = IntArray(0)
        val gone = LinkedHashSet(set)
        val fired = ArrayList<Pair<Int, Int>>()
        if (set.size > 1) fired += (if (boosterCenter in gem.indices) boosterCenter else set.elementAt(set.size / 2)) to BOMB
        val queue = ArrayDeque(gone.filter { special[it] != 0 })
        val done = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val j = queue.removeFirst()
            if (!done.add(j)) continue
            val sp = special[j]
            if (sp == 0 || ice[j] > 0 || blocked(j)) continue
            fired += j to sp
            val more = LinkedHashSet<Int>()
            area(j, sp, more)
            for (m in more) if (gone.add(m) && special[m] != 0) queue += m
        }
        return apply(gone, emptyMap(), fired, 0, bonusPerShape = false)
    }

    /** Довести поле до покоя без анимаций (для бота). */
    fun settle() {
        var shuffles = 0
        while (true) {
            while (true) {
                resolveBooster() ?: resolve() ?: break
                gravity()
            }
            if (moves > 0 && !goalsDone && findMove() == null && shuffles++ < 5) shuffle() else break
        }
    }

    // ----- итог -----

    val goalsDone: Boolean get() = goalKind.indices.all { goalHave[it] >= goalNeed[it] }

    /**
     * Звёзды - за экономию ходов: чем меньше ходов ушло на победу, тем больше звёзд.
     * Осталось ≥ 25 % ходов - 3 звезды, ≥ 10 % - 2, иначе 1. С докупленными ходами - 1 звезда.
     */
    fun starsForLeft(left: Int): Int {
        if (extraMoves > 0) return 1
        val f = left.coerceIn(0, totalMoves).toFloat() / totalMoves.coerceAtLeast(1)
        return when {
            f >= STAR3_LEFT -> 3
            f >= STAR2_LEFT -> 2
            else -> 1
        }
    }

    /** Сколько звёзд ещё можно получить, если выполнить цели прямо сейчас. */
    val potentialStars: Int get() = starsForLeft(moves)

    /** Ходов должно остаться для 2-й и 3-й звезды. */
    fun movesLeftFor(star: Int): Int = kotlin.math.ceil(totalMoves * (if (star >= 3) STAR3_LEFT else STAR2_LEFT)).toInt()

    val stars: Int get() = if (!goalsDone) 0 else potentialStars

    /**
     * Победа: каждый оставшийся ход превращается в очки (один раз). Возвращает бонус.
     */
    fun finish(): Int {
        if (finished || !goalsDone) return 0
        finished = true
        endBonus = moves.coerceAtLeast(0) * MOVE_BONUS
        score += endBonus
        syncGoals()
        return endBonus
    }

    /** Копия поля (для оценки ходов). */
    fun copy(): Match3Engine {
        val e = Match3Engine(level, rnd.nextLong())
        System.arraycopy(gem, 0, e.gem, 0, gem.size)
        System.arraycopy(special, 0, e.special, 0, gem.size)
        System.arraycopy(leaf, 0, e.leaf, 0, gem.size)
        System.arraycopy(ice, 0, e.ice, 0, gem.size)
        System.arraycopy(gold, 0, e.gold, 0, gem.size)
        e.moves = moves
        e.score = score
        e.extraMoves = extraMoves
        e.gemsCollected = gemsCollected
        for (i in goalHave.indices) if (i < e.goalHave.size) e.goalHave[i] = goalHave[i]
        e.syncGoals()
        return e
    }

    fun toJson(): JSONObject = JSONObject()
        .put("level", level).put("moves", moves).put("score", score)
        .put("gem", JSONArray(gem.toList())).put("sp", JSONArray(special.toList())).put("leaf", JSONArray(leaf.toList()))
        .put("ice", JSONArray(ice.toList()))
        .put("gold", JSONArray(gold.toList()))
        .put("have", JSONArray(goalHave.toList()))
        .put("xm", extraMoves).put("gc", gemsCollected)
        .put("v", SAVE_VERSION)

    companion object {
        const val H = 1
        const val V = 2
        const val BOMB = 3
        const val RAINBOW = 4
        const val LEAF_GOAL = 100
        const val ICE_GOAL = 101
        const val GOLD_GOAL = 102
        const val SCORE_GOAL = 103

        const val F_HOLES = "holes"
        const val F_LEAF = "leaf"
        const val F_LEAF2 = "leaf2"
        const val F_ICE = "ice"
        const val F_ICE2 = "ice2"
        const val F_GOLD = "gold"
        const val F_GOLD2 = "gold2"
        const val F_SCORE = "score"

        // Очки (за шаг всё умножается на номер каскада, не больше x5).
        const val GEM_SCORE = 60
        const val ICE_SCORE = 80
        const val LEAF_SCORE = 100
        const val GOLD_SCORE = 150
        const val CREATE_SCORE = 120
        const val FIRE_SCORE = 80
        const val MAX_MULT = 5

        /** Очки за каждый оставшийся ход при победе. */
        const val MOVE_BONUS = 250
        private val SHAPE_SCORE = intArrayOf(0, 200, 500, 300, 150, 600)
        private const val SAVE_VERSION = 4

        /** Доля оставшихся ходов для 3-й и 2-й звезды. */
        const val STAR3_LEFT = 0.25f
        const val STAR2_LEFT = 0.10f

        /** С этого уровня поле меняет форму (дыры, перегородки, островки). */
        const val SHAPES_FROM = 20

        /** С этого уровня сгенерированные цели облегчены (см. initGenerated). */
        private const val REBALANCE_FROM = 18

        /** Фигурные поля для уровней после нарисованных. */
        private val HOLE_PATTERNS = listOf(
            listOf("........", "........", "........", "........", "........", "........", "........", "........"),
            listOf("#......#", "........", "........", "...##...", "...##...", "........", "........", "#......#"),
            listOf("##....##", "#......#", "........", "........", "........", "........", "#......#", "##....##"),
            listOf("........", ".#....#.", "........", "........", "........", "........", ".#....#.", "........"),
            listOf("........", "........", "#......#", "##....##", "##....##", "#......#", "........", "........"),
        )

        /**
         * Формы поля для уровней 20-100: каждая следующая - другая конфигурация.
         * Все симметричные и связные, дыр немного - чтобы кристаллам было куда падать.
         */
        private val MID_SHAPES = listOf(
            listOf("#......#", "........", "........", "........", "........", "........", "........", "#......#"),
            listOf("........", "........", "........", "...##...", "...##...", "........", "........", "........"),
            listOf("##....##", "#......#", "........", "........", "........", "........", "........", "........"),
            listOf("........", "........", "#......#", "#......#", "#......#", "#......#", "........", "........"),
            listOf("........", ".#....#.", "........", "........", "........", "........", ".#....#.", "........"),
            listOf("###..###", "##....##", "#......#", "........", "........", "........", "........", "........"),
            listOf("........", "........", "........", "##....##", "##....##", "........", "........", "........"),
            listOf("#......#", "#......#", "........", "........", "........", "........", "#......#", "#......#"),
            listOf("........", "...##...", "........", "........", "........", "........", "...##...", "........"),
            listOf("##....##", "#......#", "........", "...##...", "...##...", "........", "#......#", "##....##"),
            listOf("........", "........", "#......#", "##....##", "##....##", "#......#", "........", "........"),
            listOf("#..##..#", "........", "........", "........", "........", "........", "........", "#......#"),
        )

        /** Ходов на уровне (у сгенерированных - из таблицы, см. [Match3Levels.generatedMoves]). */
        fun movesFor(level: Int) = Match3Levels.spec(level)?.moves ?: Match3Levels.generatedMoves(level) ?: 25

        fun fromJson(o: JSONObject): Match3Engine? = runCatching {
            val v = o.optInt("v", 1)
            // Сохранения старого формата (до льда и квадратов) не подходят - уровень заново.
            if (v < 4) return@runCatching null
            val e = Match3Engine(o.getInt("level"))
            // Сохранение до залежей и нарисованных уровней - только если поле с тех пор не изменилось.
            if (v < 3 && (e.handmade || e.gold.any { it > 0 } || e.level >= REBALANCE_FROM)) return@runCatching null
            val g = o.getJSONArray("gem")
            val s = o.getJSONArray("sp")
            val l = o.getJSONArray("leaf")
            val ic = o.optJSONArray("ice")
            val gd = o.optJSONArray("gold")
            if (g.length() != e.gem.size) return@runCatching null
            e.moves = o.getInt("moves")
            e.score = o.getInt("score")
            e.extraMoves = o.optInt("xm", 0)
            e.gemsCollected = o.optInt("gc", 0)
            for (i in e.gem.indices) {
                e.gem[i] = g.getInt(i)
                e.special[i] = s.getInt(i)
                e.leaf[i] = l.getInt(i)
                e.ice[i] = ic?.getInt(i) ?: 0
                e.gold[i] = gd?.optInt(i, 0) ?: 0
                if (e.hole[i]) {
                    e.gem[i] = -1
                    e.special[i] = 0
                    e.leaf[i] = 0
                    e.ice[i] = 0
                    e.gold[i] = 0
                }
                if (e.gold[i] > 0) {
                    e.gem[i] = -1
                    e.special[i] = 0
                    e.ice[i] = 0
                }
                if (e.gem[i] >= e.kinds) e.gem[i] = e.gem[i] % e.kinds
            }
            e.sanitize()
            // Пустая клетка (не должно быть) - заполняем.
            for (i in e.gem.indices) if (!e.blocked(i) && e.gem[i] < 0) e.gem[i] = e.randomGem()
            val h = o.getJSONArray("have")
            for (i in 0 until minOf(h.length(), e.goalHave.size)) {
                if (e.goalKind[i] in 0 until 6) e.goalHave[i] = h.getInt(i).coerceIn(0, e.goalNeed[i])
            }
            e.syncGoals()
            if (e.findMove() == null) e.shuffle()
            e
        }.getOrNull()
    }
}
