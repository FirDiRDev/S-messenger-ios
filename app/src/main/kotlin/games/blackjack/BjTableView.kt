// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.blackjack

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.stoyko.smessenger.R
import com.stoyko.smessenger.games.CardArt
import com.stoyko.smessenger.games.GameSfx
import com.stoyko.smessenger.ui.EnergySaver
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Стол онлайн-блэкджека: дилер сверху (башмак справа), места игроков дугой снизу - у каждого
 * карты, стопка фишек ставки, имя и очки. Каждая карта - «спрайт», который летит по ВРЕМЕНИ
 * (не по кадрам) по дуге с мягкой посадкой: раздача из башмака по очереди, переворот закрытой
 * карты дилера, разделение руки, сбор карт в сброс в начале нового раунда. При расчёте фишки
 * проигравших улетают к дилеру, выигрыш - от дилера к игрокам.
 *
 * В кадре ничего не создаётся: подписи, стопки фишек и картинки готовятся заранее. Когда всё
 * легло, а ждём хода игрока - подсветка «дышит» реже (и не дышит в режиме экономии).
 */
@SuppressLint("ViewConstructor")
class BjTableView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var cw = 0
    private var ch = 0

    private class Spr(var key: Int, var owner: Int, var hand: Int, var slot: Int, var card: Int) {
        var x = 0f
        var y = 0f
        var rot = 0f
        var scale = 1f
        var face = 0f
        var fx = 0f
        var fy = 0f
        var frot = 0f
        var fscale = 1f
        var fface = 0f
        var tx = Float.NaN
        var ty = 0f
        var trot = 0f
        var tscale = 1f
        var tface = 0f
        var ax = 0f
        var ay = 0f
        var t0 = 0L
        var dur = 0L
        var land = true
        var sound = false
        var landed = false
        var landAt = 0L
        var leaving = false
        var z = 0
    }

    /** Полёт стопки фишек при расчёте (от места к дилеру или обратно). */
    private class Flight(val idx: IntArray, val n: Int, val fx: Float, val fy: Float, val tx: Float, val ty: Float, val t0: Long, val dur: Long)

    private val sprites = ArrayList<Spr>()
    private val flights = ArrayList<Flight>()
    private var spriteRound = -1
    private var engine: BjEngine? = null
    private var me = 0

    // Места: позиция и масштаб карт (пересчитываются при смене размера / числа мест).
    private var seatX = FloatArray(0)
    private var seatY = FloatArray(0)
    private var seatSc = FloatArray(0)
    private var seatLabel: Array<String> = emptyArray()
    private var seatBet: Array<String> = emptyArray()
    private var seatPending = BooleanArray(0)
    private var handLabel: Array<Array<String>> = emptyArray()
    private var handColor: Array<IntArray> = emptyArray()
    private var seatRes: Array<String> = emptyArray()
    private var seatResColor = IntArray(0)
    private var stackIdx: Array<IntArray> = emptyArray()
    private var stackN = IntArray(0)
    private var stackAmt = IntArray(0)
    private var stackAt = LongArray(0)

    /** При расчёте прежняя стопка (ставка) видна, пока её фишки не улетят / не прилетит выигрыш. */
    private var oldIdx: Array<IntArray> = emptyArray()
    private var oldN = IntArray(0)
    private var holdUntil = LongArray(0)
    private var dealerLabel = ""
    private var bankLabel = ""
    private var resultsAt = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val felt = Paint()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3FDE68A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        letterSpacing = 0.12f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tmp = RectF()
    private val arc = Path()
    private val faceReady: () -> Unit = { invalidate() }
    private var lastSound = 0L
    private var felt1 = ""
    private var felt2 = ""

    /** Фишки стопок - готовые картинки нужного размера (без поиска в кэше в кадре). */
    private var chipBmp: Array<Bitmap?> = arrayOfNulls(BjChips.VALUES.size)
    private var chipSize = 0

    private var bannerText = ""
    private var bannerColor = Color.WHITE
    private var bannerAt = 0L

    fun banner(msg: String, color: Int) {
        bannerText = msg
        bannerColor = color
        bannerAt = SystemClock.uptimeMillis() + 450
        invalidate()
    }

    /** Карты или фишки ещё летят (кнопки действий ждут). */
    val busy: Boolean
        get() {
            val now = SystemClock.uptimeMillis()
            for (i in sprites.indices) {
                val s = sprites[i]
                if (now < s.t0 + s.dur) return true
            }
            return false
        }

    // ---------------- состояние ----------------

    fun setState(e: BjEngine, mySeat: Int, names: List<String>, gone: Set<Int>) {
        val prev = engine
        engine = e
        me = mySeat
        val n = e.seats
        val now = SystemClock.uptimeMillis()
        if (seatLabel.size != n) {
            seatX = FloatArray(n)
            seatY = FloatArray(n)
            seatSc = FloatArray(n)
            stackIdx = Array(n) { IntArray(10) }
            stackN = IntArray(n)
            stackAmt = IntArray(n)
            stackAt = LongArray(n)
            seatResColor = IntArray(n)
            seatPending = BooleanArray(n)
            oldIdx = Array(n) { IntArray(10) }
            oldN = IntArray(n)
            holdUntil = LongArray(n)
        }
        // Подписи - здесь, а не в кадре.
        seatLabel = Array(n) { i ->
            val nm = names.getOrElse(i) { "?" }.take(12)
            when {
                i in gone -> "$nm ✕"
                i == mySeat -> context.getString(R.string.bjack_you)
                else -> nm
            }
        }
        seatBet = Array(n) { i ->
            when (e.betSt[i]) {
                BjEngine.B_PENDING -> "⏳ ${e.bet[i]}"
                BjEngine.B_OK -> "S ${if (e.hands[i].isEmpty()) e.bet[i] else e.stake(i)}"
                BjEngine.B_REJECT -> context.getString(R.string.bjack_rejected_short)
                else -> ""
            }
        }
        for (i in 0 until n) seatPending[i] = e.betSt[i] == BjEngine.B_PENDING
        handLabel = Array(n) { i ->
            Array(e.hands[i].size) { hi ->
                val h = e.hands[i][hi]
                val (v, soft) = BjEngine.total(h.cards)
                val res = when (h.result) {
                    2 -> " ★"
                    1 -> " ✓"
                    3 -> " ="
                    -1, -2 -> " ✗"
                    else -> ""
                }
                if (h.forfeited) "✗" else (if (soft && v < 21) "${v - 10}/$v" else "$v") + res
            }
        }
        handColor = Array(n) { i ->
            IntArray(e.hands[i].size) { hi ->
                val h = e.hands[i][hi]
                when {
                    h.result == 1 || h.result == 2 -> 0xEE15803D.toInt()
                    h.result < 0 -> 0xEEBE123C.toInt()
                    h.value == 21 -> 0xEEB45309.toInt()
                    else -> 0xCC111827.toInt()
                }
            }
        }
        seatRes = Array(n) { i ->
            val net = e.net(i)
            if (e.phase != BjEngine.DONE || !e.inRound(i)) "" else if (net > 0) "+$net" else if (net < 0) "−${-net}" else "±0"
        }
        val doneNow = e.phase == BjEngine.DONE && prev != null && prev.phase != BjEngine.DONE && prev.round == e.round
        for (i in 0 until n) {
            val net = e.net(i)
            seatResColor[i] = if (net > 0) 0xFF4ADE80.toInt() else if (net < 0) 0xFFFB7185.toInt() else Color.WHITE
            val amt = if (e.betSt[i] == BjEngine.B_OK || e.betSt[i] == BjEngine.B_PENDING) {
                if (e.phase == BjEngine.DONE) e.ret(i) else if (e.hands[i].isEmpty()) e.bet[i] else e.stake(i)
            } else 0
            if (amt != stackAmt[i]) {
                // Стопка выросла - фишки «падают» на стол; при расчёте - без падения (летят).
                if (amt > stackAmt[i] && !doneNow) stackAt[i] = now
                if (doneNow) {
                    stackIdx[i].copyInto(oldIdx[i])
                    oldN[i] = stackN[i]
                } else {
                    holdUntil[i] = 0L
                }
                stackAmt[i] = amt
                stackN[i] = if (amt > 0) BjChips.stack(amt, 8, stackIdx[i]) else 0
            }
        }
        val shown = if (e.holeOpen) e.dealer else e.dealer.filter { it >= 0 }
        val (dv, dsoft) = BjEngine.total(shown)
        dealerLabel = context.getString(R.string.bj_dealer) + " · " + names.getOrElse(0) { "?" }.take(12) +
            if (e.dealer.isNotEmpty()) "  " + (if (dsoft && dv < 21 && e.holeOpen) "${dv - 10}/$dv" else "$dv") else ""
        bankLabel = if (e.bank() > 0 && e.phase != BjEngine.DONE) context.getString(R.string.bjack_bank_label, e.bank()) else ""
        if (e.phase == BjEngine.DONE && prev?.phase != BjEngine.DONE) resultsAt = now + 500
        if (e.phase != BjEngine.DONE) resultsAt = 0L
        layoutSeats()
        syncSprites(e)
        if (doneNow) planFlights(prev!!, e, now)
        invalidate()
    }

    /** Расчёт: ставки проигравших летят к дилеру, выигрыш - от дилера к игроку. */
    private fun planFlights(prev: BjEngine, e: BjEngine, now: Long) {
        if (width == 0 || cw == 0) return
        flights.clear()
        val dx = width * 0.5f
        val dy = seatY.getOrElse(0) { 0f } + ch * 0.55f
        var k = 0
        for (s in 1 until e.seats) {
            if (!e.inRound(s)) continue
            val stake = e.stake(s)
            val ret = e.ret(s)
            val sx = seatX[s] - cw * seatSc[s] * 0.75f
            val sy = seatY[s] + ch * seatSc[s] * 0.5f + 12 * d
            val t0 = now + 900 + k * 160L
            if (ret < stake) {
                val idx = IntArray(8)
                val n = BjChips.stack(stake - ret, 6, idx)
                flights += Flight(idx, n, sx, sy, dx, dy, t0, 620)
                holdUntil[s] = t0
            } else if (ret > stake) {
                val idx = IntArray(8)
                val n = BjChips.stack(ret - stake, 6, idx)
                flights += Flight(idx, n, dx, dy, sx, sy, t0, 620)
                holdUntil[s] = t0 + 620
            } else {
                holdUntil[s] = 0L
            }
            k++
        }
        if (prev.round != e.round) flights.clear()
    }

    /** Места дугой: моё (если я игрок) - по центру и крупнее. */
    private fun layoutSeats() {
        val e = engine ?: return
        if (width == 0 || cw == 0) return
        val n = e.seats - 1
        if (n <= 0) return
        // Порядок мест слева направо: своё - в середине.
        val order = IntArray(n) { it + 1 }
        if (me > 0) {
            val mid = (n - 1) / 2
            val at = me - 1
            order[at] = order[mid]
            order[mid] = me
        }
        for (k in 0 until n) {
            val s = order[k]
            val t = (k + 1f) / (n + 1f)
            val bow = 1f - (2f * t - 1f) * (2f * t - 1f)
            val sc = when {
                s == me -> if (n >= 4) 0.86f else 1f
                n >= 3 -> 0.7f
                else -> 0.84f
            }
            seatSc[s] = sc
            seatX[s] = width * t
            val bottom = height - (ch * sc * 0.5f + 48 * d)
            seatY[s] = bottom - bow * height * 0.06f
        }
        seatX[0] = width * 0.5f
        seatY[0] = ch * 0.5f + 34 * d
        seatSc[0] = 0.86f
    }

    private fun keyOf(owner: Int, hand: Int, slot: Int) = (owner + 1) * 1000 + hand * 100 + slot

    private fun shoeX() = width - cw * 0.55f - 6 * d
    private fun shoeY() = ch * 0.45f + 8 * d
    private fun discardX() = cw * 0.55f + 6 * d

    private fun syncSprites(e: BjEngine) {
        val now = SystemClock.uptimeMillis()
        // Нужные карты: (владелец, рука, место, карта); владелец -1 - дилер.
        val want = ArrayList<IntArray>()
        for ((i, c) in e.dealer.withIndex()) want += intArrayOf(-1, 0, i, c)
        for (s in 1 until e.seats) for ((hi, h) in e.hands[s].withIndex()) for ((i, c) in h.cards.withIndex()) want += intArrayOf(s, hi, i, c)
        val wantKeys = HashSet<Int>()
        for (w in want) wantKeys += keyOf(w[0], w[1], w[2])
        // Новый раунд: всё со стола - в сброс (по одной), затем раздача заново по очереди.
        val newRound = e.round != spriteRound
        spriteRound = e.round
        val fresh = want.size >= 3 && (newRound || sprites.none { !it.leaving })
        var leaveK = 0
        val held = HashSet<Int>()
        for (s in sprites) if (!s.leaving) held += s.key
        val claimed = HashSet<Int>()
        for (s in sprites) {
            if (s.leaving) continue
            if (!newRound && s.key in wantKeys) continue
            // Карта перешла в другую руку того же игрока (разделение) - летит туда, а не в сброс.
            val m = if (!newRound && s.owner >= 0) want.firstOrNull { w ->
                val k2 = keyOf(w[0], w[1], w[2])
                w[0] == s.owner && w[3] == s.card && k2 !in held && k2 !in claimed
            } else null
            if (m != null) {
                val k2 = keyOf(m[0], m[1], m[2])
                claimed += k2
                s.key = k2
                s.hand = m[1]
                s.slot = m[2]
                s.tx = Float.NaN
                continue
            }
            s.leaving = true
            fly(s, now + leaveK * 45L, 520L, discardX(), shoeY() + ch * 0.1f, if (s.card % 2 == 0) 160f else -150f, 0.55f, 0f, land = false, arcK = 0.14f)
            leaveK++
        }
        // Раздача с начала - по очереди: игрокам по карте, дилеру; потом вторая.
        val dealSeq = ArrayList<Int>()
        if (fresh) {
            for (slot in 0..1) {
                for (s in 1 until e.seats) for (hi in e.hands[s].indices) if (e.hands[s][hi].cards.size > slot) dealSeq += keyOf(s, hi, slot)
                if (e.dealer.size > slot) dealSeq += keyOf(-1, 0, slot)
            }
        }
        var k = 0
        val base = if (leaveK > 0) 300L + leaveK * 45L else 0L
        for (w in want) {
            val key = keyOf(w[0], w[1], w[2])
            val s = sprites.firstOrNull { !it.leaving && it.key == key }
            if (s == null) {
                val ns = Spr(key, w[0], w[1], w[2], w[3])
                ns.x = shoeX(); ns.y = shoeY(); ns.rot = -18f; ns.face = 0f; ns.scale = 0.8f
                val order = if (fresh) dealSeq.indexOf(key).let { if (it < 0) dealSeq.size + k++ else it } else k++
                ns.t0 = now + base + order * 240L + 60L
                ns.dur = 440L
                ns.sound = true
                ns.fx = ns.x; ns.fy = ns.y; ns.frot = ns.rot; ns.fscale = ns.scale; ns.fface = 0f
                sprites += ns
            } else if (s.card != w[3]) {
                // Закрытая карта дилера открылась - переворот на месте.
                s.card = w[3]
                s.tx = Float.NaN
                s.face = 0f
                s.fface = 0f
            }
        }
        layoutTargets(now)
    }

    /** Запустить полёт спрайта к цели. */
    private fun fly(s: Spr, t0: Long, dur: Long, x: Float, y: Float, rot: Float, scale: Float, face: Float, land: Boolean, arcK: Float) {
        s.fx = s.x; s.fy = s.y; s.frot = s.rot; s.fscale = s.scale; s.fface = s.face
        s.tx = x; s.ty = y; s.trot = rot; s.tscale = scale; s.tface = face
        s.t0 = t0
        s.dur = dur
        s.land = land
        s.landed = false
        computeArc(s, arcK)
    }

    private fun layoutTargets(now: Long) {
        val e = engine ?: return
        if (width == 0 || cw == 0) return
        for (s in sprites) {
            if (s.leaving) continue
            val tx: Float
            val ty: Float
            val rot: Float
            val sc: Float
            val face: Float
            if (s.owner < 0) {
                val cnt = e.dealer.size
                val step = minOf(cw * 0.62f, (width - cw * 2.8f) / maxOf(1, cnt - 1))
                tx = width * 0.5f + (s.slot - (cnt - 1) / 2f) * step
                ty = seatY[0]
                rot = (s.slot - (cnt - 1) / 2f) * 1.5f
                sc = seatSc[0]
                face = if (s.card < 0) 0f else 1f
            } else {
                val hands = e.hands.getOrNull(s.owner) ?: continue
                val nh = hands.size.coerceAtLeast(1)
                val cnt = hands.getOrNull(s.hand)?.cards?.size ?: 1
                val base = seatSc.getOrElse(s.owner) { 0.8f }
                sc = if (nh > 1) base * 0.84f else base
                val spread = (width / (e.seats + 0.5f)) * 0.55f
                val cx = seatX[s.owner] + if (nh > 1) (if (s.hand == 0) -spread * 0.5f else spread * 0.5f) else 0f
                val stepK = if (cnt > 4) 0.2f else 0.3f
                val step = cw * sc * stepK
                tx = cx + (s.slot - (cnt - 1) / 2f) * step
                ty = seatY[s.owner] - s.slot * 3 * d
                rot = (s.slot - (cnt - 1) / 2f) * 4f
                face = 1f
            }
            s.z = if (s.owner < 0) s.slot else 100 + s.owner * 30 + s.hand * 12 + s.slot
            if (s.tx.isNaN()) {
                if (now < s.t0) {
                    // Новая карта: цель задаём, старт - по расписанию раздачи (уже назначен).
                    s.tx = tx; s.ty = ty; s.trot = rot; s.tscale = sc; s.tface = face
                    computeArc(s, 0.1f)
                    s.land = true
                } else {
                    fly(s, now, 420L, tx, ty, rot, sc, face, land = true, arcK = 0.08f)
                }
                continue
            }
            if (s.tx != tx || s.ty != ty || s.trot != rot || s.tscale != sc || s.tface != face) {
                if (now >= s.t0 + s.dur) {
                    fly(s, now, 380L, tx, ty, rot, sc, face, land = true, arcK = 0.05f)
                } else {
                    s.tx = tx; s.ty = ty; s.trot = rot; s.tscale = sc; s.tface = face
                }
            }
        }
        postInvalidateOnAnimation()
    }

    private fun computeArc(s: Spr, k: Float) {
        val dx = s.tx - s.fx
        val dy = s.ty - s.fy
        val dist = sqrt(dx * dx + dy * dy)
        if (dist < 1f || k <= 0f) {
            s.ax = 0f; s.ay = 0f
            return
        }
        var nx = -dy / dist
        var ny = dx / dist
        if (ny > 0f) {
            nx = -nx
            ny = -ny
        }
        s.ax = nx * dist * k
        s.ay = ny * dist * k
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // Высокие карты (руны) - уже, чтобы по высоте помещались так же (4.10.0).
        var big = minOf(w / 5.3f, 96 * d, h / 5.4f * (1.48f / CardArt.ratio(context)))
        if (CardArt.art(context)) big = minOf(big, CardArt.ART_W.toFloat())
        cw = big.toInt().coerceAtLeast((34 * d).toInt())
        ch = (cw * CardArt.ratio(context)).toInt()
        CardArt.prewarm(context, cw, ch)
        felt.shader = RadialGradient(w / 2f, h * 0.4f, maxOf(w, h) * 0.8f, intArrayOf(0xFF15803D.toInt(), 0xFF0B4D2E.toInt(), 0xFF052016.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        rim.strokeWidth = 3 * d
        rim.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(0xFFB45309.toInt(), 0xFFFDE68A.toInt(), 0xFFB45309.toInt()), null, Shader.TileMode.CLAMP)
        glow.strokeWidth = 2.5f * d
        spot.strokeWidth = 1.5f * d
        spot.color = 0x55FDE68A
        arc.reset()
        tmp.set(w * 0.08f, h * 0.14f, w * 0.92f, h * 0.84f)
        arc.addArc(tmp, 160f, -140f)
        felt1 = context.getString(R.string.bj_felt_1)
        felt2 = context.getString(R.string.bjack_felt_online)
        // Фишки стопок - заранее (в кадре только готовые картинки).
        chipSize = (if (w < 360 * d) 18 else 21).let { (it * d).toInt() }
        chipBmp = Array(BjChips.VALUES.size) { BjChips.bitmap(it, chipSize) }
        layoutSeats()
        // Карты на новые места - без полёта.
        for (s in sprites) if (!s.leaving) s.tx = Float.NaN
        val now = SystemClock.uptimeMillis()
        layoutTargets(now)
        for (s in sprites) if (!s.leaving && now >= s.t0) {
            s.x = s.tx; s.y = s.ty; s.rot = s.trot; s.scale = s.tscale; s.face = s.tface; s.dur = 0L
        }
    }

    // ---------------- кадр ----------------

    private fun ease(p: Float, land: Boolean): Float = if (land) {
        val c1 = 0.55f
        val q = p - 1f
        1f + (c1 + 1f) * q * q * q + c1 * q * q
    } else {
        if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).let { it * it * it } / 2f
    }

    private fun drawStack(c: Canvas, idx: IntArray, n: Int, x: Float, y: Float, step: Float) {
        val half = chipSize / 2f
        for (k in 0 until n) {
            val b = chipBmp.getOrNull(idx[k]) ?: continue
            c.drawBitmap(b, x - half, y - half - k * step, paint)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), felt)
        val e = engine ?: return
        if (cw == 0) return
        val now = SystemClock.uptimeMillis()
        var moving = false
        var ambient = false
        // Борт и надписи на сукне.
        tmp.set(-width * 0.25f, -height * 0.5f, width * 1.25f, height * 0.95f)
        c.drawArc(tmp, 20f, 140f, false, rim)
        arcText.textSize = 12 * d
        c.drawTextOnPath(felt1, arc, 0f, 0f, arcText)
        arcText.textSize = 10.5f * d
        c.drawTextOnPath(felt2, arc, 0f, 15 * d, arcText)
        // Башмак.
        val back = CardArt.back(context, cw, ch)
        val sx = shoeX()
        val sy = shoeY()
        for (i in 0 until 4) {
            c.save()
            c.translate(sx + i * 1.2f * d, sy - i * 1.2f * d)
            c.rotate(-18f)
            c.scale(0.8f, 0.8f)
            c.drawBitmap(back, -cw / 2f, -ch / 2f, paint)
            c.restore()
        }
        // Подпись дилера и банк.
        text.textSize = 13 * d
        val dy = seatY[0] - ch * seatSc[0] * 0.5f - 10 * d
        drawPill(c, dealerLabel, width * 0.5f, dy, 0xCC111827.toInt(), e.phase == BjEngine.DEALER, now)
        if (e.phase == BjEngine.DEALER) ambient = true
        if (bankLabel.isNotEmpty()) {
            text.textSize = 11.5f * d
            drawPill(c, bankLabel, discardX() + cw * 0.25f, seatY[0] + ch * seatSc[0] * 0.5f + 6 * d, 0x99B45309.toInt(), false, now)
        }
        // Места игроков: круг ставки, подсветка хода, фишки, подписи.
        for (s in 1 until e.seats) {
            val x = seatX[s]
            val y = seatY[s]
            val sc = seatSc[s]
            val cy = y + ch * sc * 0.5f + 14 * d
            val bx = x - cw * sc * 0.75f
            c.drawCircle(bx, cy - 2 * d, chipSize * 0.62f, spot)
            if (s == e.active && e.phase == BjEngine.PLAY) {
                val pulse = if (EnergySaver.on) 1f else 0.5f + 0.5f * sin(now / 260f)
                glow.color = 0xFFFDE047.toInt()
                glow.alpha = (120 + 120 * pulse).toInt()
                glow.strokeWidth = (2f + 2f * pulse) * d
                tmp.set(x - cw * sc * 1.05f, y - ch * sc * 0.62f, x + cw * sc * 1.05f, y + ch * sc * 0.62f)
                c.drawOval(tmp, glow)
                ambient = true
            }
            if (now < holdUntil[s]) {
                moving = true
                if (oldN[s] > 0) drawStack(c, oldIdx[s], oldN[s], bx, cy - 2 * d, 2.6f * d)
            } else if (stackN[s] > 0) {
                // Только что поставили - стопка падает на стол с лёгким отскоком.
                val age = now - stackAt[s]
                val drop = if (age in 0 until 360) {
                    moving = true
                    val p = age / 360f
                    -(1f - ease(p, true)) * 26 * d
                } else 0f
                drawStack(c, stackIdx[s], stackN[s], bx, cy - 2 * d + drop, 2.6f * d)
            }
            text.textSize = (if (s == me) 13f else 11.5f) * d
            drawPill(c, seatLabel[s], x, cy + 6 * d, if (s == me) 0xCC4338CA.toInt() else 0x88000000.toInt(), false, now)
            if (seatBet[s].isNotEmpty()) {
                text.textSize = 11 * d
                drawPill(c, seatBet[s], x, cy + 24 * d, if (seatPending[s]) 0xAAB45309.toInt() else 0x66000000, seatPending[s], now)
                if (seatPending[s]) ambient = true
            }
        }
        // Карты по слоям (вставками, без объектов).
        val n = sprites.size
        if (order.size < n) order = IntArray(n + 8)
        for (i in 0 until n) order[i] = i
        for (i in 1 until n) {
            val v = order[i]
            var j = i - 1
            while (j >= 0 && zOf(sprites[order[j]], now) > zOf(sprites[v], now)) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
        for (oi in 0 until n) {
            val s = sprites[order[oi]]
            if (s.tx.isNaN()) continue
            if (now < s.t0) {
                moving = true
                continue
            }
            val flying = now < s.t0 + s.dur
            if (flying) {
                moving = true
                if (s.sound) {
                    s.sound = false
                    if (now - lastSound > 60) {
                        lastSound = now
                        GameSfx.play(context, GameSfx.S.DEAL, 0.95f + (abs(s.card) % 5) * 0.05f, 0.8f)
                    }
                }
                val p = ((now - s.t0).toFloat() / s.dur).coerceIn(0f, 1f)
                val k = ease(p, s.land)
                val inv = 1f - k
                val mx = (s.fx + s.tx) * 0.5f + s.ax
                val my = (s.fy + s.ty) * 0.5f + s.ay
                s.x = inv * inv * s.fx + 2f * inv * k * mx + k * k * s.tx
                s.y = inv * inv * s.fy + 2f * inv * k * my + k * k * s.ty
                s.rot = s.frot + (s.trot - s.frot) * k
                s.scale = s.fscale + (s.tscale - s.fscale) * k.coerceAtMost(1f)
                s.face = s.fface + (s.tface - s.fface) * ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
            } else {
                if (!s.landed) {
                    s.landed = true
                    s.landAt = now
                    if (!s.leaving) GameSfx.play(context, GameSfx.S.PLACE, 0.95f + (abs(s.card) % 4) * 0.05f, 0.65f)
                }
                s.x = s.tx; s.y = s.ty; s.rot = s.trot; s.scale = s.tscale; s.face = s.tface
            }
            if (s.leaving && !flying) continue
            val lift = if (flying) sin(((now - s.t0).toFloat() / s.dur).coerceIn(0f, 1f) * Math.PI.toFloat()) else 0f
            val la = now - s.landAt
            var settle = 0f
            if (!flying && s.landAt > 0 && la < 300) {
                settle = exp(-la / 80f) * sin(la / 36f)
                moving = true
            }
            val flip = abs(1f - 2f * s.face).coerceAtLeast(0.03f)
            val ready = if (s.face >= 0.5f && s.card >= 0) CardArt.faceOrNull(context, s.card % 36, cw, ch, faceReady) else null
            if (s.face >= 0.5f && s.card >= 0 && ready == null) moving = true
            val bmp = ready ?: back
            val sc = s.scale * (1f + 0.08f * lift - 0.03f * settle)
            c.save()
            c.translate(s.x, s.y - lift * 8 * d)
            c.rotate(s.rot + settle)
            c.scale(sc * flip, sc)
            val so = (3 + 8 * lift) * d
            shadow.alpha = 70
            tmp.set(-cw / 2f + so * 0.6f, -ch / 2f + so, cw / 2f + so * 0.6f, ch / 2f + so)
            c.drawRoundRect(tmp, cw * 0.09f, cw * 0.09f, shadow)
            c.drawBitmap(bmp, -cw / 2f, -ch / 2f, paint)
            c.restore()
        }
        // Убрать улетевшие в сброс (с конца - без итераторов).
        var i = sprites.size - 1
        while (i >= 0) {
            val s = sprites[i]
            if (s.leaving && now >= s.t0 + s.dur) sprites.removeAt(i)
            i--
        }
        val settled = !moving
        // Очки рук (когда карты легли).
        if (settled) {
            for (s in 1 until e.seats) {
                val labels = handLabel.getOrNull(s) ?: continue
                val nh = labels.size
                val sc = seatSc[s]
                val spread = (width / (e.seats + 0.5f)) * 0.55f
                for (hi in 0 until nh) {
                    val cx = seatX[s] + if (nh > 1) (if (hi == 0) -spread * 0.5f else spread * 0.5f) else 0f
                    text.textSize = 12.5f * d
                    val hiAct = e.phase == BjEngine.PLAY && e.active == s && e.activeHand == hi && nh > 1
                    drawPill(c, labels[hi], cx, seatY[s] - ch * sc * (if (nh > 1) 0.42f else 0.5f) - 8 * d, handColor[s][hi], hiAct, now)
                }
            }
        }
        // Полёты фишек при расчёте.
        var f = flights.size - 1
        while (f >= 0) {
            val fl = flights[f]
            if (now >= fl.t0 + fl.dur) {
                flights.removeAt(f)
                f--
                continue
            }
            moving = true
            if (now >= fl.t0) {
                val p = ease(((now - fl.t0).toFloat() / fl.dur).coerceIn(0f, 1f), false)
                val x = fl.fx + (fl.tx - fl.fx) * p
                val y = fl.fy + (fl.ty - fl.fy) * p - sin(p * Math.PI.toFloat()) * 30 * d
                drawStack(c, fl.idx, fl.n, x, y, 2.6f * d)
            }
            f--
        }
        // Итоги раунда над местами («+20» / «−10»).
        if (resultsAt > 0 && now >= resultsAt && settled) {
            val age = now - resultsAt
            val a = (age / 300f).coerceIn(0f, 1f)
            for (s in 1 until e.seats) {
                val r = seatRes.getOrNull(s) ?: continue
                if (r.isEmpty()) continue
                text.textSize = 18 * d
                text.color = seatResColor[s]
                text.alpha = (255 * a).toInt()
                c.drawText(r, seatX[s], seatY[s] - ch * seatSc[s] * 0.5f - 30 * d - 8 * d * a, text)
                text.color = Color.WHITE
                text.alpha = 255
            }
            if (a < 1f) moving = true
        } else if (resultsAt > 0) {
            moving = true
        }
        // Крупная плашка (итог для меня).
        val bAge = now - bannerAt
        if (bannerText.isNotEmpty() && bAge >= 0) {
            val a = if (bAge < 2400) 1f else (1f - (bAge - 2400) / 600f).coerceIn(0f, 1f)
            if (a > 0f) {
                val pop = if (bAge < 220) 0.7f + 0.3f * (bAge / 220f) else 1f
                text.textSize = 21 * d * pop
                val w = text.measureText(bannerText) / 2 + 18 * d
                fill.color = Color.argb((200 * a).toInt(), 0, 0, 0)
                val y = height * 0.46f
                tmp.set(width / 2f - w, y - 27 * d, width / 2f + w, y + 11 * d)
                c.drawRoundRect(tmp, 19 * d, 19 * d, fill)
                text.color = bannerColor
                text.alpha = (255 * a).toInt()
                c.drawText(bannerText, width / 2f, y, text)
                text.color = Color.WHITE
                text.alpha = 255
                moving = true
            } else {
                bannerText = ""
            }
        } else if (bannerText.isNotEmpty()) {
            moving = true
        }
        if (moving) {
            postInvalidateOnAnimation()
        } else if (ambient && !EnergySaver.on && isShown) {
            // Ждём хода - подсветка «дышит», но не чаще 20 кадров в секунду.
            postInvalidateDelayed(50)
        }
    }

    private var order = IntArray(32)

    private fun zOf(s: Spr, now: Long): Int = s.z + (if (now >= s.t0 && now < s.t0 + s.dur) 1000 else 0) + (if (s.leaving) 2000 else 0)

    /** Плашка с текстом (размер шрифта - уже в [text]). */
    private fun drawPill(c: Canvas, label: String, x: Float, y: Float, color: Int, hi: Boolean, now: Long) {
        if (label.isEmpty()) return
        val w = text.measureText(label) / 2 + 9 * d
        val h = text.textSize
        fill.color = color
        tmp.set(x - w, y - h * 0.95f, x + w, y + h * 0.4f)
        c.drawRoundRect(tmp, h * 0.7f, h * 0.7f, fill)
        if (hi) {
            glow.color = 0xFFFDE047.toInt()
            glow.alpha = if (EnergySaver.on) 220 else (160 + 90 * sin(now / 220f)).toInt().coerceIn(0, 255)
            glow.strokeWidth = 2 * d
            c.drawRoundRect(tmp, h * 0.7f, h * 0.7f, glow)
        }
        c.drawText(label, x, y, text)
    }
}
