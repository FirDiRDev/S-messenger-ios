// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Предмет на путях. [s] - ближний к бегуну край (метры вдоль пути), [lane] -1…1. */
internal class RObj(@JvmField val type: Int) {
    @JvmField var lane = 0
    @JvmField var s = 0f
    @JvmField var len = 0f
    @JvmField var x = 0f
    @JvmField var y = 0f

    /** Скорость встречного поезда (0 - стоит). */
    @JvmField var v = 0f
    @JvmField var style = 0
    @JvmField var cars = 1
    @JvmField var kind = 0
    @JvmField var taken = false
    @JvmField var fly = false
    @JvmField var hit = false
    @JvmField var hitT = 0f
    @JvmField var horn = 0

    companion object {
        const val COIN = 0
        const val POWER = 1
        const val HURDLE = 2
        const val BAR = 3
        const val BLOCK = 4
        const val CONES = 5
        const val TRAIN = 6
        const val RAMP = 7
    }
}

/** Кусок пути: вид (0 город, 1 тоннель, 2 мост, 3 станция) и варианты обстановки слева/справа. */
internal class Chunk(@JvmField val kind: Int, @JvmField val left: Int, @JvmField val right: Int, @JvmField val portal: Boolean)

/** Искра/след/пыль (мировые координаты: s вдоль пути, x, y). */
internal class Particle {
    @JvmField var s = 0f
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var vs = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    @JvmField var age = 0f
    @JvmField var life = 1f
    @JvmField var color = 0
    @JvmField var size = 0.3f
}

/**
 * Мир «Бега по рельсам»: бегун, предметы, генератор препятствий (всегда есть проход),
 * встречные поезда (полоса заранее освобождается, затем издали едет поезд с фарами и гудком),
 * преследователь-обходчик с робопсом, усиления, очки, монеты, смена времени суток.
 * Работает в потоке отрисовки; наружу - только события ([onCoin], [onState], [onSound], [onPop]).
 */
internal class World(private val lite: Boolean, seed: Long = System.nanoTime()) {
    companion object {
        const val MENU = 0
        const val COUNT = 1
        const val RUN = 2
        const val PAUSE = 3
        const val DYING = 4
        const val OVER = 5

        const val P_MAGNET = 0
        const val P_JET = 1
        const val P_SNEAKERS = 2
        const val P_X2 = 3
        const val P_BOARD = 4

        /** Щит: принимает на себя одно столкновение или спотыкание. */
        const val P_SHIELD = 5

        /** Монеты ×2: каждая монета считается за две. */
        const val P_COINS = 6

        /** Замедление времени: мир бежит на четверть медленнее - легче увернуться. */
        const val P_SLOW = 7
        const val P_COUNT = 8
        val P_TIME = floatArrayOf(12f, 7.5f, 12f, 15f, 25f, 18f, 15f, 8f)

        const val SND_COIN = 0
        const val SND_JUMP = 1
        const val SND_ROLL = 2
        const val SND_SWITCH = 3
        const val SND_LAND = 4
        const val SND_STUMBLE = 5
        const val SND_CRASH = 6
        const val SND_POWER = 7
        const val SND_HORN = 8
        const val SND_WHISTLE = 9
        const val SND_BARK = 10
        const val SND_TICK = 11
        const val SND_GO = 12
        const val SND_JET = 13
        const val SND_SAVED = 14
        const val SND_CAUGHT = 15
        const val SND_PASS = 16

        const val POP_SAVED = 1
        const val POP_POWER = 2
        const val POP_CHASER = 3
        const val POP_RECORD = 4

        private const val G = 38f
        private const val STEP = 0.6f
        const val ROLL_TIME = 0.62f
        const val CONTINUE_PRICE = 100
    }

    private val rnd = java.util.Random(seed)
    val objs = ArrayList<RObj>()
    val parts = ArrayList<Particle>()
    private val chunks = ArrayList<Chunk>()
    private val stationChunk = Chunk(3, 0, 0, false)

    /** Сколько вариантов боковой обстановки (задаёт рендерер по числу сеток). */
    var sideVariants = 6

    var onCoin: () -> Unit = {}
    var onState: () -> Unit = {}
    var onSound: (Int, Float) -> Unit = { _, _ -> }
    var onPop: (Int, Int) -> Unit = { _, _ -> }

    // ------------------------------------------------------------------ состояние
    @Volatile var state = MENU
        private set
    var showcase = false
    var skin = 0

    // ---- 4.12.0: способность персонажа (см. [Perks]) ----
    private val perk: Int get() = RunnerSkins.all.getOrNull(skin)?.perk ?: 0
    /** Способность «прощение» уже потрачена в этом забеге. */
    private var perkUsed = false
    /** Дробная часть бонусных монет (+25% / +50%). */
    private var coinFrac = 0f

    private fun perkPowerMul(kind: Int): Float = when (perk) {
        Perks.LUCK -> 1.3f
        Perks.TIME -> 1.6f
        Perks.MAGNET -> if (kind == P_MAGNET) 1.6f else 1f
        Perks.DRAGON -> if (kind == P_MAGNET) 1.4f else 1f
        else -> 1f
    }

    /** Сезонная тема (см. [RunnerSeason]): погода, небо, музыка; задаёт экран. */
    @Volatile var season = RunnerSeason.NONE
    val winter: Boolean get() = season == RunnerSeason.WINTER

    /** Длительность бонусов с учётом улучшений из магазина (задаёт экран перед забегом). */
    val powerTime = P_TIME.copyOf()

    /** Доска (см. [RunnerBoards]): 0 - в цвет облика. [previewBoard] - показ в магазине (-1 нет). */
    var board = 0
    var previewBoard = -1
    private var boardFxT = 0f

    /** След за бегуном (см. [Skin.trail]). */
    var trail = 0
    var previewSkin = 0

    var ps = 0f
        private set
    var px = 0f
        private set
    var py = 0f
        private set
    var vy = 0f
        private set
    var targetLane = 0
        private set
    var speed = 0f
        private set
    var runTime = 0f
        private set
    var startS = 0f
        private set
    val dist get() = (ps - startS).coerceAtLeast(0f)

    @Volatile var score = 0
        private set
    private var scoreF = 0f
    @Volatile var coins = 0
        private set
    @Volatile var mult = 1
        private set
    val power = FloatArray(P_COUNT)
    var invul = 0f
        private set
    @Volatile var stumbleT = 0f
        private set
    @Volatile var countT = 0f
        private set
    var continued = false
        private set

    /** Номер забега (растёт с каждым новым). */
    @Volatile var runNo = 0
        private set
    var deathKind = 0
        private set
    var deathT = 0f
        private set

    /** Тревога «встречный поезд» по путям (0…1) - для подсказки на экране. */
    val warn = FloatArray(3)

    // Для анимации.
    var runPhase = 0f
        private set
    var latVel = 0f
        private set
    var rollT = 0f
        private set
    var airborne = false
        private set
    var landT = 0f
        private set
    var stumbleAnim = 0f
        private set
    var bumpT = 0f
        private set
    var bumpDir = 0
        private set
    var idleT = 0f
        private set
    var boardBreakT = 0f
        private set

    /** Щит только что лопнул (для вспышки). */
    var shieldPopT = 0f
        private set

    // Обходчик.
    var chaserGap = 2.6f
        private set
    var chaserX = 0f
        private set

    /** Высота и «пригнулся» обходчика: он бежит ровно по следу бегуна (через те же прыжки и подкаты). */
    var chaserY = 0f
        private set
    var chaserDuck = 0f
        private set

    /** Робопёс - тоже по следу, чуть впереди хозяина. */
    var dogX = 0.8f
        private set
    var dogY = 0f
        private set

    // След бегуна (кольцевой буфер): путь, по которому бежит обходчик, не насквозь через препятствия.
    private val pathN = 900
    private val pathS = FloatArray(pathN)
    private val pathX = FloatArray(pathN)
    private val pathY = FloatArray(pathN)
    private val pathR = FloatArray(pathN)
    private var pathHead = 0
    private var pathCount = 0
    private val pathTmp = FloatArray(3)

    private fun pathClear() {
        pathHead = 0
        pathCount = 0
    }

    private fun pathRecord() {
        val i = pathHead % pathN
        if (pathCount > 0) {
            val prev = (pathHead - 1 + pathN) % pathN
            if (ps - pathS[prev] < 0.05f) return
        }
        pathS[i] = ps
        pathX[i] = px
        pathY[i] = py
        pathR[i] = if (rolling) 1f else 0f
        pathHead = (pathHead + 1) % pathN
        if (pathCount < pathN) pathCount++
    }

    /** Где был бегун на отметке [s]: x, y, пригнулся. false - так далеко след не помнит. */
    private fun pathAt(s: Float, out: FloatArray): Boolean {
        if (pathCount < 2) return false
        var k = 0
        var newer = (pathHead - 1 + pathN) % pathN
        if (s >= pathS[newer]) {
            out[0] = pathX[newer]; out[1] = pathY[newer]; out[2] = pathR[newer]
            return true
        }
        while (k < pathCount - 1) {
            val older = (newer - 1 + pathN) % pathN
            if (pathS[older] <= s) {
                val span = (pathS[newer] - pathS[older]).coerceAtLeast(1e-4f)
                val f = ((s - pathS[older]) / span).coerceIn(0f, 1f)
                out[0] = pathX[older] + (pathX[newer] - pathX[older]) * f
                out[1] = pathY[older] + (pathY[newer] - pathY[older]) * f
                out[2] = pathR[older] + (pathR[newer] - pathR[older]) * f
                return true
            }
            newer = older
            k++
        }
        return false
    }

    /** Обходчик и пёс - на след бегуна; нет следа (начало) - плавно к его дорожке. */
    private fun followPath(dt: Float) {
        if (pathAt(ps - chaserGap, pathTmp)) {
            chaserX = pathTmp[0]
            chaserY = pathTmp[1]
            chaserDuck += (pathTmp[2] - chaserDuck) * min(1f, dt * 12f)
        } else {
            chaserX += (px - chaserX) * min(1f, dt * 4f)
            chaserY += (0f - chaserY) * min(1f, dt * 6f)
            chaserDuck = 0f
        }
        val dogGap = (chaserGap - 0.5f).coerceAtLeast(0.3f)
        if (pathAt(ps - dogGap, pathTmp)) {
            // Пёс - по краю той же дорожки (не в соседнюю, где может стоять поезд).
            val side = if (pathTmp[0] > 0.5f) -0.42f else 0.42f
            dogX += (pathTmp[0] + side - dogX) * min(1f, dt * 10f)
            dogY = pathTmp[1]
        } else {
            dogX += (chaserX + 0.8f - dogX) * min(1f, dt * 4f)
            dogY = chaserY
        }
    }
    var chaserPhase = 0f
        private set
    var chaserSpeed = 0f
        private set

    /** Время суток 0…1 (0 - утро, 0.3 - день, 0.55 - закат, 0.75 - ночь). */
    var tod = 0.12f
        private set

    private var prevPs = 0f
    private var nextS = 0f
    private val blockedUntil = FloatArray(3)
    private var reserved = false
    private var reserveLane = 0
    private var reserveFrom = 0f
    private var lastMovingS = -1000f
    private var jumpBuf = 0f
    private var sinceGround = 0f
    private var slowT = 0f
    private var airTime = 0f
    private var trailT = 0f
    private var lastCount = 0
    private var whistled = false
    private var barkT = 0f
    private var bestAtStart = 0

    val viewDist = if (lite) 80f else 125f
    private val spawnAhead get() = viewDist + 12f

    val rolling get() = rollT > 0f
    private val collisionLane get() = (px / RW.LW).roundToInt().coerceIn(-1, 1)

    // ------------------------------------------------------------------ куски пути

    fun chunk(k: Int): Chunk {
        if (k < 0) return stationChunk
        while (chunks.size <= k) genChunks()
        return chunks[k]
    }

    private fun genChunks() {
        if (chunks.isEmpty()) {
            repeat(3) { chunks.add(stationChunk) }
            return
        }
        val town = 4 + rnd.nextInt(6)
        repeat(town) { chunks.add(Chunk(0, rnd.nextInt(sideVariants), rnd.nextInt(sideVariants), false)) }
        val r = rnd.nextFloat()
        when {
            r < 0.36f -> {
                val n = 5 + rnd.nextInt(4)
                for (i in 0 until n) chunks.add(Chunk(1, 0, 0, i == 0))
            }
            r < 0.66f -> repeat(3 + rnd.nextInt(3)) { chunks.add(Chunk(2, 0, 0, false)) }
            else -> repeat(2 + rnd.nextInt(2)) { chunks.add(Chunk(3, 0, 0, false)) }
        }
    }

    /** Освещённость в точке пути: в тоннеле темнее (плавно у порталов). */
    fun lightAt(s: Float): Float {
        val k = kotlin.math.floor(s / RW.CH).toInt()
        val c = chunk(k).kind
        if (c != 1) {
            // Перед порталом - немного тени.
            return 1f
        }
        val prev = chunk(k - 1).kind == 1
        val next = chunk(k + 1).kind == 1
        val f = s / RW.CH - k
        var v = 0.55f
        if (!prev && f < 0.35f) v = 1f - (0.45f * f / 0.35f)
        if (!next && f > 0.75f) v = 0.55f + 0.45f * ((f - 0.75f) / 0.25f)
        return v
    }

    // ------------------------------------------------------------------ управление

    /** Новый забег: отсчёт 3-2-1, затем бег. [best] - рекорд (для «Новый рекорд!»). */
    fun newRun(best: Int) {
        runNo++
        objs.clear()
        parts.clear()
        chunks.clear()
        ps = 0f
        prevPs = 0f
        startS = 0f
        px = 0f
        py = 0f
        vy = 0f
        targetLane = 0
        speed = 0f
        runTime = 0f
        score = 0
        scoreF = 0f
        coins = 0
        mult = 1
        power.fill(0f)
        perkUsed = false
        coinFrac = 0f
        // Способности старта: щит и/или очки ×2.
        if (perk == Perks.SHIELD || perk == Perks.LORD) power[P_SHIELD] = powerTime[P_SHIELD]
        if (perk == Perks.X2START) power[P_X2] = 20f
        if (perk == Perks.LORD) power[P_X2] = powerTime[P_X2]
        invul = 0f
        stumbleT = 0f
        continued = false
        deathT = 0f
        rollT = 0f
        landT = 0f
        stumbleAnim = 0f
        bumpT = 0f
        boardBreakT = 0f
        shieldPopT = 0f
        jetFall = false
        chaserGap = 2.6f
        chaserX = 0f
        chaserY = 0f
        chaserDuck = 0f
        pathClear()
        nextS = 45f
        blockedUntil.fill(0f)
        reserved = false
        lastMovingS = -1000f
        jumpBuf = 0f
        slowT = 0f
        whistled = false
        bestAtStart = best
        tod = 0.08f + rnd.nextFloat() * 0.25f
        warn.fill(0f)
        // Стартовая дорожка монет.
        coinLine(0, 18f, 8, 0.9f)
        startCount()
    }

    private fun startCount() {
        state = COUNT
        countT = 3f
        lastCount = 4
        onState()
    }

    fun pause() {
        if (state == RUN || state == COUNT) {
            state = PAUSE
            onState()
        }
    }

    fun resume() {
        if (state == PAUSE) startCount()
    }

    fun toMenu() {
        state = MENU
        objs.clear()
        parts.clear()
        speed = 0f
        px = 0f
        py = 0f
        targetLane = 0
        chaserGap = 2.6f
        onState()
    }

    /** Продолжить после аварии (один раз за забег, монеты списывает экран). */
    fun continueRun() {
        if (state != OVER || continued) return
        continued = true
        jetFall = false
        objs.removeAll { it.type != RObj.COIN && it.type != RObj.POWER && it.s + it.len > ps - 4f && it.s < ps + 50f }
        objs.removeAll { it.type == RObj.TRAIN && it.v > 0f }
        reserved = false
        nextS = max(nextS, ps + 55f)
        stumbleT = 0f
        chaserGap = 10f
        pathClear()
        invul = 3f
        py = 0f
        vy = 0f
        rollT = 0f
        deathT = 0f
        speed = 0f
        startCount()
    }

    fun move(dir: Int) {
        if (state != RUN) return
        val nl = (targetLane + dir).coerceIn(-1, 1)
        if (nl == targetLane) {
            bumpT = 0.18f
            bumpDir = dir
            return
        }
        if (invul <= 0f && power[P_JET] <= 0f && sideBlocked(nl)) {
            bumpT = 0.3f
            bumpDir = dir
            stumble()
            return
        }
        targetLane = nl
        onSound(SND_SWITCH, 1f)
    }

    fun jump() {
        if (state != RUN) return
        if (power[P_JET] > 0f) return
        val grounded = !airborne || sinceGround < 0.1f
        if (grounded) {
            val h = (if (power[P_SNEAKERS] > 0f) 4.1f else 1.45f) * (if (perk == Perks.JUMP) 1.2f else 1f)
            vy = sqrt(2f * G * h)
            airborne = true
            sinceGround = 1f
            rollT = 0f
            jumpBuf = 0f
            onSound(SND_JUMP, if (power[P_SNEAKERS] > 0f) 0.8f else 1f)
        } else {
            jumpBuf = 0.18f
        }
    }

    fun roll() {
        if (state != RUN) return
        if (power[P_JET] > 0f) return
        if (airborne) vy = min(vy, -24f)
        rollT = ROLL_TIME
        jumpBuf = 0f
        onSound(SND_ROLL, 1f)
    }

    private fun overlaps(o: RObj, a: Float, b: Float): Boolean = r1(o) >= a && r0(o) <= b

    /** Ближний край предмета для столкновений. */
    private fun r0(o: RObj): Float = when (o.type) {
        RObj.HURDLE, RObj.BAR -> o.s - 0.12f
        RObj.BLOCK, RObj.CONES -> o.s - 0.3f
        RObj.TRAIN, RObj.RAMP -> o.s
        else -> o.s - 0.4f
    }

    /** Дальний край предмета. */
    private fun r1(o: RObj): Float = when (o.type) {
        RObj.HURDLE, RObj.BAR -> o.s + 0.12f
        RObj.BLOCK -> o.s + 0.9f
        RObj.CONES -> o.s + 0.3f
        RObj.TRAIN -> o.s + o.len
        RObj.RAMP -> o.s + RW.RAMP_LEN
        else -> o.s + 0.4f
    }

    private fun sideBlocked(lane: Int): Boolean {
        for (o in objs) {
            if (o.lane != lane || o.hit) continue
            if (!overlaps(o, ps - 0.6f, ps + 0.6f)) continue
            when (o.type) {
                RObj.TRAIN -> if (py < RW.TRAIN_H - STEP) return true
                RObj.BLOCK -> if (py < 2.25f) return true
                RObj.BAR -> if (!rolling && py < 2.4f) return true
            }
        }
        return false
    }

    /** Высота опоры под бегуном (земля, рампа или крыша вагона) - для тени. */
    fun groundY(): Float = support(collisionLane, ps, py)

    /** Опора под обходчиком (крыша вагона или земля) - для тени и прыжков. */
    fun groundAtChaser(): Float {
        val lane = (chaserX / RW.LW).roundToInt().coerceIn(-1, 1)
        return runCatching { support(lane, ps - chaserGap, chaserY) }.getOrDefault(0f)
    }

    private fun support(lane: Int, s: Float, y: Float): Float {
        var g = 0f
        for (o in objs) {
            if (o.lane != lane) continue
            when (o.type) {
                RObj.TRAIN -> if (s >= o.s - 0.25f && s <= o.s + o.len + 0.25f && y >= RW.TRAIN_H - STEP) g = max(g, RW.TRAIN_H)
                RObj.RAMP -> if (s >= o.s && s <= o.s + RW.RAMP_LEN) g = max(g, RW.TRAIN_H * ((s - o.s) / RW.RAMP_LEN).coerceIn(0f, 1f))
            }
        }
        return g
    }

    // ------------------------------------------------------------------ шаг

    fun update(dtRaw: Float) {
        val dt = dtRaw.coerceIn(0f, 0.05f)
        idleT += dt
        if (state != PAUSE) updateParticles(dt)
        when (state) {
            MENU -> {
                chaserGap = 2.6f
                chaserPhase += dt * 1.5f
            }
            COUNT -> {
                countT -= dt
                val n = kotlin.math.ceil(countT).toInt()
                if (n != lastCount && n in 1..3) onSound(SND_TICK, 1f)
                lastCount = n
                chaserPhase += dt * 1.5f
                if (!whistled && runTime == 0f && countT < 2.2f) {
                    whistled = true
                    onSound(SND_WHISTLE, 1f)
                }
                if (countT <= 0f) {
                    state = RUN
                    onSound(SND_GO, 1f)
                    if (runTime == 0f) onSound(SND_BARK, 1f)
                    onState()
                }
            }
            RUN -> step(dt)
            DYING -> {
                deathT += dt
                speed = max(0f, speed - 40f * dt)
                ps += speed * dt
                val target = if (deathKind == 1) 0.75f else 1.0f
                // Обходчик подбегает быстро, откуда бы ни был.
                if (chaserGap > target) chaserGap = max(target, chaserGap - max(9f, (chaserGap - target) * 2.2f) * dt)
                chaserSpeed = if (chaserGap > target + 0.01f) 9f else 0f
                chaserPhase += dt * (if (chaserSpeed > 0f) 11f else 1.5f)
                followPath(dt)
                if (py > 0f && deathKind == 0) {
                    val g = support(collisionLane, ps, py)
                    vy -= G * dt
                    py = max(g, py + vy * dt)
                }
                if (deathT > 1.9f) {
                    state = OVER
                    onState()
                }
            }
        }
    }

    private fun baseSpeed() = min(26f, 11.5f + runTime * 0.085f)

    /** Падение после ранца (доска и кеды ещё не тратятся). */
    private var jetFall = false

    private fun dustColor(): Int = when (season) {
        RunnerSeason.WINTER -> 0xFFF1F5F9.toInt()
        RunnerSeason.SPRING -> 0xFFFBCFE8.toInt()
        RunnerSeason.AUTUMN -> 0xFFFDBA74.toInt()
        else -> 0xFFD6CFC4.toInt()
    }

    private fun step(dt: Float) {
        runTime += dt
        tod = (tod + dt / 210f) % 1f
        val target = baseSpeed() * (if (slowT > 0f) 0.82f else 1f) * (if (power[P_SLOW] > 0f) 0.74f else 1f)
        speed = if (speed < target) min(target, speed + 22f * dt) else target
        prevPs = ps
        ps += speed * dt

        // В бок.
        val tx = targetLane * RW.LW
        val oldX = px
        px += (tx - px) * min(1f, dt * 17f)
        if (abs(tx - px) < 0.01f) px = tx
        latVel = (px - oldX) / dt.coerceAtLeast(1e-4f)

        for (o in objs) if (o.type == RObj.TRAIN && o.v > 0f) o.s -= o.v * dt

        // Пока несёт ранец (и пока падаешь после него) - доска и кеды не тратятся: в воздухе
        // ими всё равно не пользуешься.
        val flying = power[P_JET] > 0f || jetFall
        for (i in power.indices) {
            if (power[i] > 0f) {
                if (flying && (i == P_BOARD || i == P_SNEAKERS)) continue
                power[i] -= dt
                if (power[i] <= 0f) {
                    power[i] = 0f
                    if (i == P_JET) {
                        invul = max(invul, 1.6f)
                        vy = 0f
                        jetFall = true
                    }
                }
            }
        }
        if (invul > 0f) invul -= dt
        if (stumbleT > 0f) stumbleT -= dt
        if (slowT > 0f) slowT -= dt
        if (stumbleAnim > 0f) stumbleAnim -= dt
        if (bumpT > 0f) bumpT -= dt
        if (landT > 0f) landT -= dt
        if (rollT > 0f) rollT -= dt
        if (jumpBuf > 0f) jumpBuf -= dt
        if (boardBreakT > 0f) boardBreakT -= dt
        if (shieldPopT > 0f) shieldPopT -= dt

        // Вверх-вниз.
        val cl = collisionLane
        if (power[P_JET] > 0f) {
            py += (RW.JET_Y - py) * min(1f, dt * 3.5f)
            vy = 0f
            airborne = true
            airTime = 0f
        } else {
            val g = support(cl, ps, py)
            if (py > g + 0.001f || vy > 0f) {
                vy -= G * (if (rolling) 1.7f else 1f) * dt
                py += vy * dt
                airborne = true
                airTime += dt
                sinceGround += dt
                if (py <= g) {
                    py = g
                    vy = 0f
                    jetFall = false
                    if (airTime > 0.15f) {
                        landT = 0.22f
                        onSound(SND_LAND, 1f)
                        // Высокое приземление - облачко пыли (снег зимой, лепестки весной).
                        if (airTime > 0.45f && !lite) burst(ps, px, py + 0.1f, dustColor(), 6)
                    }
                    airborne = false
                    airTime = 0f
                    sinceGround = 0f
                    if (jumpBuf > 0f) jump()
                }
            } else {
                py = g
                vy = 0f
                airborne = false
                airTime = 0f
                sinceGround = 0f
                jetFall = false
            }
        }
        if (!airborne) runPhase += speed * dt * 0.78f
        pathRecord()
        // Эффект доски из магазина (искры, пламя, иней, неон) - из-под колёс.
        if (power[P_BOARD] > 0f && boardFx != 0) {
            boardFxT -= dt
            if (boardFxT <= 0f) {
                boardFxT = if (lite) 0.07f else 0.03f
                emitTrail(boardFx, low = true)
            }
        }

        // Столкновения.
        if (invul <= 0f && power[P_JET] <= 0f) collide(cl)
        if (state != RUN) return

        pickups(dt)
        if (wantAir) {
            // После обхода списка (добавлять во время обхода нельзя).
            wantAir = false
            airCoins()
        }
        chaser(dt)

        // Генерация.
        while (nextS < ps + spawnAhead) gen(nextS)
        spawnMoving()
        objs.removeAll { r1(it) < ps - 25f || it.taken }

        // Очки, тревоги, гудки.
        mult = (1 + (dist / 600f).toInt().coerceAtMost(9)) * (if (power[P_X2] > 0f) 2 else 1)
        scoreF += speed * dt * 0.5f * mult
        val sc = scoreF.toInt()
        if (bestAtStart > 0 && score <= bestAtStart && sc > bestAtStart) onPop(POP_RECORD, 0)
        score = sc
        warn.fill(0f)
        for (o in objs) {
            if (o.type != RObj.TRAIN || o.v <= 0f) continue
            val d = o.s - ps
            if (d > 0f && d < 130f) warn[o.lane + 1] = max(warn[o.lane + 1], 1f - d / 130f)
            if (o.horn == 0 && d < 95f && d > 0f) {
                o.horn = 1
                onSound(SND_HORN, 1f)
            }
            if (o.horn == 1 && d < 2f && d > -o.len) {
                o.horn = 2
                if (o.lane != cl) onSound(SND_PASS, 1f)
            }
        }
        // След особого облика.
        if (trail != 0) {
            trailT -= dt
            if (trailT <= 0f) {
                trailT = if (lite) 0.06f else 0.028f
                emitTrail(trail)
            }
        }
    }

    private fun collide(cl: Int) {
        val a = prevPs - 0.3f
        val b = ps + 0.3f
        for (o in objs) {
            if (o.lane != cl || o.hit) continue
            when (o.type) {
                RObj.HURDLE -> if (overlaps(o, a, b) && py < 0.95f) {
                    knock(o)
                    stumble()
                }
                RObj.CONES -> if (overlaps(o, a, b) && py < 0.55f) {
                    knock(o)
                    stumble()
                }
                RObj.BAR -> if (overlaps(o, a, b) && !rolling && py < 2.4f) crash(o)
                RObj.BLOCK -> if (overlaps(o, a, b) && py < 2.25f) crash(o)
                RObj.TRAIN -> if (overlaps(o, a, b) && py < RW.TRAIN_H - STEP) crash(o)
            }
            if (state != RUN) return
        }
    }

    private fun knock(o: RObj) {
        o.hit = true
        o.hitT = 0f
        burst(o.s, o.lane * RW.LW, 0.6f, 0xFFFFFFFF.toInt(), 6)
    }

    private fun stumble() {
        if (invul > 0f || state != RUN) return
        if (power[P_SHIELD] > 0f) {
            power[P_SHIELD] = 0f
            invul = 1f
            shieldPopT = 0.6f
            onSound(SND_SAVED, 1f)
            burst(ps, px, py + 1f, 0xFF67E8F9.toInt(), 12)
            return
        }
        // Лис / Кибер-самурай: первая запинка за забег прощается.
        if ((perk == Perks.CHASE || perk == Perks.TIME) && !perkUsed) {
            perkUsed = true
            invul = 1f
            shieldPopT = 0.6f
            onSound(SND_SAVED, 1f)
            burst(ps, px, py + 1f, 0xFFFDBA74.toInt(), 10)
            return
        }
        if (stumbleT > 0f) {
            // Второй раз подряд - обходчик настиг.
            deathKind = 1
            die()
            onSound(SND_CAUGHT, 1f)
            return
        }
        stumbleT = 6.5f
        slowT = 0.7f
        stumbleAnim = 0.5f
        onSound(SND_STUMBLE, 1f)
        onSound(SND_WHISTLE, 1.1f)
        onPop(POP_CHASER, 0)
        barkT = 0.35f
    }

    private fun crash(o: RObj) {
        if (power[P_BOARD] <= 0f && power[P_SHIELD] > 0f) {
            // Щит лопается и спасает (как доска, но без подброса на крышу).
            power[P_SHIELD] = 0f
            invul = 1.6f
            shieldPopT = 0.6f
            onSound(SND_SAVED, 1f)
            onPop(POP_SAVED, 0)
            burst(ps, px, py + 1f, 0xFF67E8F9.toInt(), 16)
            if (o.type == RObj.TRAIN) {
                py = RW.TRAIN_H + 0.05f
                vy = 5f
                airborne = true
            } else {
                knock(o)
            }
            return
        }
        if (power[P_BOARD] > 0f) {
            power[P_BOARD] = 0f
            invul = 1.6f
            boardBreakT = 0.6f
            onSound(SND_SAVED, 1f)
            onPop(POP_SAVED, 0)
            burst(ps, px, py + 0.3f, 0xFF38BDF8.toInt(), 14)
            if (o.type == RObj.TRAIN) {
                // Доска подбрасывает на крышу.
                py = RW.TRAIN_H + 0.05f
                vy = 5f
                airborne = true
            } else {
                knock(o)
            }
            return
        }
        // Феникс: одно столкновение за забег прощается - возрождение в огне.
        if (perk == Perks.REVIVE && !perkUsed) {
            perkUsed = true
            invul = 2f
            shieldPopT = 0.6f
            onSound(SND_SAVED, 1f)
            onPop(POP_SAVED, 0)
            burst(ps, px, py + 1f, 0xFFFF8A1F.toInt(), 22)
            if (o.type == RObj.TRAIN) {
                py = RW.TRAIN_H + 0.05f
                vy = 6f
                airborne = true
            } else {
                knock(o)
            }
            return
        }
        deathKind = 0
        if (o.type == RObj.TRAIN && o.v <= 0f) ps = min(ps, o.s - 0.35f)
        die()
        onSound(SND_CRASH, 1f)
    }

    private fun die() {
        state = DYING
        deathT = 0f
        rollT = 0f
        power[P_JET] = 0f
        burst(ps, px, py + 1f, 0xFFFDE68A.toInt(), 10)
        onState()
    }

    private fun pickups(dt: Float) {
        val a = prevPs - 0.7f
        val b = ps + 0.7f
        val hy = py + (if (rolling) 0.45f else 0.9f)
        val magnet = power[P_MAGNET] > 0f
        for (o in objs) {
            if (o.taken) continue
            if (o.type == RObj.COIN) {
                if (magnet && !o.fly && o.s - ps < (if (perk == Perks.MAGNET) 36f else 26f) && o.s > ps - 1f) {
                    o.fly = true
                    o.hitT = 0f
                }
                var grab = false
                if (o.fly) {
                    // 4.10.0: монета летит в координатах бегуна. Раньше она догоняла точку перед
                    // бегуном «вдогонку» и на большой скорости отставала на скорость/11 метров -
                    // не попадала в зону сбора, висела перед камерой гигантской монетой и
                    // ехала вместе с бегуном даже после конца магнита.
                    o.hitT += dt
                    val k = min(1f, dt * 12f)
                    var rel = o.s - ps
                    rel += (0.3f - rel) * k
                    o.s = ps + rel
                    o.x += (px - o.x) * k
                    o.y += (hy - o.y) * k
                    // Долетела (или летит дольше 0,7 с) - собрана, где бы ни была.
                    grab = (abs(rel - 0.3f) < 1.2f && abs(o.x - px) < 1.2f && abs(o.y - hy) < 1.4f) || o.hitT > 0.7f
                }
                if (grab || (o.s in a..b && abs(o.x - px) < 1.05f && abs(o.y - hy) < 1.25f)) {
                    o.taken = true
                    o.fly = false
                    coins += if (power[P_COINS] > 0f) 2 else 1
                    // Чертёнок +25%, Дракон +50%.
                    coinFrac += when (perk) {
                        Perks.COINS -> 0.25f
                        Perks.DRAGON -> 0.5f
                        else -> 0f
                    }
                    if (coinFrac >= 1f) {
                        coins += coinFrac.toInt()
                        coinFrac -= coinFrac.toInt()
                    }
                    scoreF += 5f * mult
                    onSound(SND_COIN, 1f + (coins % 8) * 0.035f)
                    onCoin()
                    if (!lite) burst(ps + 0.5f, o.x, o.y, 0xFFFFD84D.toInt(), 3)
                }
            } else if (o.type == RObj.POWER) {
                if (o.s in a..b && abs(o.x - px) < 1.1f && abs(o.y - hy) < 1.4f) {
                    o.taken = true
                    power[o.kind] = powerTime[o.kind] * perkPowerMul(o.kind)
                    if (o.kind == P_JET) {
                        rollT = 0f
                        wantAir = true
                        onSound(SND_JET, 1f)
                    } else {
                        onSound(SND_POWER, 1f)
                    }
                    onPop(POP_POWER, o.kind)
                    burst(o.s, o.x, o.y, 0xFFFFFFFF.toInt(), 10)
                }
            }
        }
    }

    private var wantAir = false

    /** Дорожка монет в воздухе на время полёта на ранце. */
    private fun airCoins() {
        var lane = collisionLane
        val len = max(speed, 12f) * powerTime[P_JET]
        var s = ps + 12f
        var k = 0
        while (s < ps + len) {
            add(RObj.COIN, lane, s).y = RW.JET_Y + 0.9f
            k++
            if (k % 7 == 0) lane = (lane + if (rnd.nextBoolean()) 1 else -1).coerceIn(-1, 1)
            s += 3f
        }
    }

    private fun chaser(dt: Float) {
        val target = when {
            stumbleT > 0f -> 1.7f
            runTime < 2.6f -> 2.4f
            else -> 40f
        }
        val old = chaserGap
        chaserGap = if (chaserGap > target) max(target, chaserGap - 8f * dt) else min(target, chaserGap + 3.5f * dt)
        chaserSpeed = speed - (chaserGap - old) / dt.coerceAtLeast(1e-4f)
        chaserPhase += dt * chaserSpeed * 0.7f
        followPath(dt)
        if (barkT > 0f) {
            barkT -= dt
            if (barkT <= 0f) onSound(SND_BARK, 1f)
        }
    }

    // ------------------------------------------------------------------ частицы

    private fun particle(): Particle? {
        val cap = if (lite) 50 else 160
        if (parts.size >= cap) return null
        return Particle().also { parts.add(it) }
    }

    private fun burst(s: Float, x: Float, y: Float, color: Int, n: Int) {
        repeat(if (lite) n / 2 else n) {
            val p = particle() ?: return
            p.s = s
            p.x = x
            p.y = y
            p.vs = (rnd.nextFloat() - 0.3f) * 6f
            p.vx = (rnd.nextFloat() - 0.5f) * 6f
            p.vy = 2f + rnd.nextFloat() * 5f
            p.life = 0.5f + rnd.nextFloat() * 0.4f
            p.color = color
            p.size = 0.18f + rnd.nextFloat() * 0.2f
        }
    }

    /** Эффект выбранной доски (0 - нет); задаёт экран. */
    var boardFx = 0

    private fun emitTrail(trail: Int, low: Boolean = false) {
        val p = particle() ?: return
        p.s = ps + (if (low) 0.6f else 0.15f)
        p.x = px + (rnd.nextFloat() - 0.5f) * (if (low) 0.55f else 0.25f)
        p.y = if (low) py + 0.05f + rnd.nextFloat() * 0.12f else py + (if (rolling) 0.4f else 0.25f + rnd.nextFloat() * 0.9f)
        p.vs = -1f
        p.vx = (rnd.nextFloat() - 0.5f) * 0.6f
        p.vy = when (trail) {
            2 -> 1.5f + rnd.nextFloat()
            3 -> -0.4f
            else -> 0.3f
        }
        p.life = if (trail == 2) 0.45f else 0.7f
        p.color = when (trail) {
            1 -> if (rnd.nextBoolean()) 0xFFFDE047.toInt() else 0xFFF0ABFC.toInt()
            2 -> if (rnd.nextBoolean()) 0xFFFF7A1A.toInt() else 0xFFFDE047.toInt()
            3 -> if (rnd.nextBoolean()) 0xFFBFE9FF.toInt() else 0xFFFFFFFF.toInt()
            else -> if (rnd.nextBoolean()) 0xFF22D3EE.toInt() else 0xFFA78BFA.toInt()
        }
        p.size = 0.22f + rnd.nextFloat() * 0.16f
    }

    private fun updateParticles(dt: Float) {
        val it = parts.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.age += dt
            if (p.age >= p.life) {
                it.remove()
                continue
            }
            p.s += p.vs * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy -= 4f * dt
        }
        for (o in objs) if (o.hit) o.hitT += dt
    }

    // ------------------------------------------------------------------ генератор

    private fun ok(before: BooleanArray, after: BooleanArray): Boolean {
        if (after[0] && after[1] && after[2]) return false
        for (f in 0..2) {
            if (before[f]) continue
            var lo = f
            while (lo > 0 && !before[lo - 1]) lo--
            var hi = f
            while (hi < 2 && !before[hi + 1]) hi++
            var any = false
            for (i in lo..hi) if (!after[i]) any = true
            if (!any) return false
        }
        return true
    }

    private fun add(type: Int, lane: Int, s: Float): RObj {
        val o = RObj(type)
        o.lane = lane
        o.s = s
        o.x = lane * RW.LW
        objs.add(o)
        return o
    }

    private fun coinLine(lane: Int, s: Float, n: Int, y: Float, step: Float = 2.5f) {
        for (i in 0 until n) add(RObj.COIN, lane, s + i * step).y = y
    }

    private fun coinArc(lane: Int, center: Float, peak: Float = 1.6f, base: Float = 0.9f) {
        for (i in -3..3) {
            val f = i / 3.5f
            add(RObj.COIN, lane, center + i * 1.4f).y = base + peak * (1 - f * f)
        }
    }

    private fun powerKind(): Int {
        val r = rnd.nextFloat()
        return when {
            r < 0.2f -> P_MAGNET
            r < 0.33f -> P_JET
            r < 0.46f -> P_SNEAKERS
            r < 0.59f -> P_X2
            r < 0.72f -> P_BOARD
            r < 0.82f -> P_SHIELD
            r < 0.92f -> P_COINS
            else -> P_SLOW
        }
    }

    private fun gen(S: Float) {
        val sp = baseSpeed()
        val gap = 15f + sp * 0.85f
        val diff = (dist / 1600f).coerceIn(0f, 1f)
        val before = BooleanArray(3) { blockedUntil[it] > S || (reserved && it - 1 == reserveLane) }
        val freeLanes = (0..2).filter { !before[it] }
        nextS = S + gap * (0.85f + rnd.nextFloat() * 0.35f)
        if (freeLanes.isEmpty()) return

        // Освободить путь под будущий встречный поезд.
        if (!reserved && dist > 200f && S - lastMovingS > 170f && rnd.nextFloat() < 0.2f + 0.15f * diff) {
            for (cand in freeLanes.shuffled(rnd)) {
                val a = before.copyOf()
                a[cand] = true
                if (ok(before, a)) {
                    reserved = true
                    reserveLane = cand - 1
                    reserveFrom = S
                    lastMovingS = S
                    before[cand] = true
                    break
                }
            }
        }
        val free = (0..2).filter { !before[it] }
        if (free.isEmpty()) return

        if (power[P_JET] > 0f) {
            // В полёте - монеты в воздухе.
            val l = free[rnd.nextInt(free.size)] - 1
            coinLine(l, S, 7, RW.JET_Y + 0.9f)
        }

        val r = rnd.nextFloat()
        val trains = dist > 60f && r < 0.32f + 0.18f * diff
        if (trains && genTrains(S, before, free, diff)) return
        if (r < 0.88f) genBarriers(S, before, free, diff) else genCoins(S, free)
    }

    private fun genTrains(S: Float, before: BooleanArray, free: List<Int>, diff: Float): Boolean {
        val shuffled = free.shuffled(rnd)
        val want = if (shuffled.size >= 2 && rnd.nextFloat() < 0.35f + 0.3f * diff) 2 else 1
        var chosen: List<Int> = emptyList()
        for (n in want downTo 1) {
            val pick = shuffled.take(n)
            val a = before.copyOf()
            for (i in pick) a[i] = true
            if (ok(before, a)) {
                chosen = pick
                break
            }
        }
        if (chosen.isEmpty()) return false
        var rampDone = false
        for (i in chosen) {
            val lane = i - 1
            val cars = 1 + rnd.nextInt(if (diff > 0.4f) 4 else 3)
            val ramp = !rampDone && rnd.nextFloat() < 0.5f
            var s0 = S
            if (ramp) {
                rampDone = true
                add(RObj.RAMP, lane, S).len = RW.RAMP_LEN
                s0 = S + RW.RAMP_LEN
                for (k in 0 until 3) add(RObj.COIN, lane, S + 1.5f + k * 2f).y = RW.TRAIN_H * ((1.5f + k * 2f) / RW.RAMP_LEN) + 0.9f
            }
            val t = add(RObj.TRAIN, lane, s0)
            t.cars = cars
            t.len = RW.trainLen(cars)
            t.style = rnd.nextInt(5)
            blockedUntil[i] = s0 + t.len + 9f
            if (ramp || rnd.nextFloat() < 0.3f) {
                val n = ((t.len - 2f) / 2.5f).toInt().coerceAtMost(12)
                coinLine(lane, s0 + 1f, n, RW.TRAIN_H + 0.9f)
                if (rnd.nextFloat() < 0.18f) {
                    val p = add(RObj.POWER, lane, s0 + t.len * 0.6f)
                    p.kind = powerKind()
                    p.y = RW.TRAIN_H + 1f
                }
            }
        }
        // На свободных путях - монеты или барьер.
        for (i in free) {
            if (i in chosen) continue
            val lane = i - 1
            val q = rnd.nextFloat()
            when {
                q < 0.45f -> coinLine(lane, S + 2f, 6, 0.9f)
                q < 0.7f -> {
                    add(if (rnd.nextBoolean()) RObj.HURDLE else RObj.BAR, lane, S + 8f)
                }
            }
        }
        return true
    }

    private fun genBarriers(S: Float, before: BooleanArray, free: List<Int>, diff: Float) {
        val types = IntArray(3) { -1 }
        val after = before.copyOf()
        for (i in free) {
            val q = rnd.nextFloat()
            types[i] = when {
                q < 0.32f - 0.12f * diff -> -1
                q < 0.56f -> RObj.HURDLE
                q < 0.76f -> RObj.BAR
                q < 0.84f -> RObj.CONES
                else -> RObj.BLOCK
            }
            if (types[i] == RObj.BLOCK) after[i] = true
        }
        if (!ok(before, after)) {
            for (i in free) if (types[i] == RObj.BLOCK) {
                types[i] = RObj.HURDLE
                after[i] = false
                if (ok(before, after)) break
            }
        }
        if (free.all { types[it] == -1 }) types[free[rnd.nextInt(free.size)]] = RObj.HURDLE
        var coinsDone = false
        var powerDone = false
        for (i in free.shuffled(rnd)) {
            val lane = i - 1
            val t = types[i]
            if (t >= 0) add(t, lane, S)
            if (!coinsDone) {
                when (t) {
                    RObj.HURDLE, RObj.CONES -> {
                        coinArc(lane, S)
                        coinsDone = true
                    }
                    RObj.BAR -> {
                        coinLine(lane, S - 4f, 4, 0.5f, 2f)
                        coinsDone = true
                    }
                    -1 -> {
                        coinLine(lane, S - 6f, 6, 0.9f)
                        coinsDone = true
                    }
                }
            } else if (!powerDone && t == -1 && rnd.nextFloat() < 0.35f) {
                val p = add(RObj.POWER, lane, S)
                p.kind = powerKind()
                p.y = 1.0f
                powerDone = true
            }
        }
        if (!powerDone && rnd.nextFloat() < 0.07f) {
            val i = free[rnd.nextInt(free.size)]
            if (types[i] == -1 || types[i] == RObj.HURDLE) {
                val p = add(RObj.POWER, i - 1, S + if (types[i] == -1) 0f else 6f)
                p.kind = powerKind()
                p.y = 1.0f
            }
        }
    }

    private fun genCoins(S: Float, free: List<Int>) {
        var lane = free[rnd.nextInt(free.size)] - 1
        for (k in 0 until 7) {
            add(RObj.COIN, lane, S + k * 2.5f).y = 0.9f
            if (k == 3 && rnd.nextBoolean()) {
                val nl = lane + if (rnd.nextBoolean()) 1 else -1
                if (nl in -1..1 && (nl + 1) in free) lane = nl
            }
        }
    }

    private fun spawnMoving() {
        if (!reserved) return
        val l = reserveLane
        if (ps <= reserveFrom + 2f) return
        for (o in objs) {
            if (o.lane == l && o.type != RObj.COIN && r1(o) > ps - 1f) return
        }
        val cars = 2 + rnd.nextInt(3)
        val t = add(RObj.TRAIN, l, min(ps + viewDist - 5f, nextS - 1f))
        t.cars = cars
        t.len = RW.trainLen(cars)
        t.style = rnd.nextInt(4)
        val diff = (dist / 1600f).coerceIn(0f, 1f)
        t.v = 8f + rnd.nextFloat() * 4f + diff * 5f
        objs.removeAll { it.type == RObj.COIN && it.lane == l && it.s > ps && it.s < t.s + t.len }
        blockedUntil[l + 1] = max(blockedUntil[l + 1], t.s + t.len + 4f)
        reserved = false
    }
}
