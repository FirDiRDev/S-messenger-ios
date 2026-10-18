// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.stoyko.smessenger.R
import com.stoyko.smessenger.ui.EnergySaver
import com.stoyko.smessenger.ui.Kinetics
import com.stoyko.smessenger.ui.LiteMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Поле «Три в ряд» с анимациями: обмен кристаллов, неудачный обмен «туда-обратно»,
 * лопание с осколками и искрами, лучи линий, ударная волна бомбы, радужные молнии,
 * падение с отскоком, новые кристаллы сверху, подсказка хода, всплывающие очки и
 * похвала за каскады. В покое кристаллы «живут»: переливаются бликами, бонусы светятся.
 */
@SuppressLint("ViewConstructor")
internal class Match3Board(ctx: Context, private val e: Match3Engine) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val art = Match3Game.art(ctx)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val dst = RectF()
    private val tmp = RectF()
    private val path = Path()

    private var cell = 0f
    private var ox = 0f
    private var oy = 0f
    private val frameRect = RectF()

    // Смещения кристаллов (в пикселях) и скорости падения, масштаб появления.
    private val offX = FloatArray(64)
    private val offY = FloatArray(64)
    private val vel = FloatArray(64)
    private val appear = FloatArray(64) { 1f }

    var busy = false
        private set
    var boostMode = -1
    var onBoostUsed: (Int) -> Unit = {}
    var onChanged: () -> Unit = {}
    var onSettled: () -> Unit = {}

    private val live: Boolean get() = Kinetics.enabled && !EnergySaver.on

    /** «Живые» украшения в покое (дыхание, блики, тряска) - не в лёгком режиме. */
    private val fancy: Boolean get() = live && !LiteMode.on

    /** Когда по клетке пришёлся удар (лёд, залежь) - для вспышки трещин. */
    private val hitAt = LongArray(64)

    /** Когда кристалл приземлился (для «пружинки» сплющивания). */
    private val landAt = LongArray(64)

    /** Волна по полю в простое: когда пошла и из какой клетки. */
    private var waveAt = 0L
    private var waveFrom = 27
    private var idleSince = 0L

    private fun sfx(s: GameSfx.S, rate: Float = 1f, vol: Float = 1f) = GameSfx.play(context, s, rate, vol)

    // ---------- анимации ----------
    private enum class Phase { IDLE, SWAP, SWAP_BACK, CLEAR, FALL, SHUFFLE }

    private var phase = Phase.IDLE
    private var phaseAt = 0L
    private var swapA = -1
    private var swapB = -1
    private var selected = -1
    private var hint: IntArray? = null
    private var lastAction = SystemClock.uptimeMillis()

    /** Исчезающие кристаллы: клетка, вид, бонус, начало. */
    private class Dying(val cell: Int, val kind: Int, val sp: Int, val at: Long)
    private val dying = ArrayList<Dying>()

    /** Частицы: x, y, vx, vy, рождение, цвет, размер, тип (0 искра, 1 осколок). */
    private val parts = ArrayList<FloatArray>()

    /** Эффекты бонусов: клетка, вид бонуса, начало. */
    private class Beam(val cell: Int, val sp: Int, val at: Long)
    private val beams = ArrayList<Beam>()

    /** Всплывающие тексты: текст, x, y, начало, крупный. */
    private class Pop(val text: String, val x: Float, val y: Float, val at: Long, val big: Boolean)
    private val pops = ArrayList<Pop>()

    private var banner = ""
    private var bannerAt = 0L

    // ---------- дополнительные эффекты (4.1.0) ----------
    /** Тряска поля от бомбы / радуги / большого каскада. */
    private var shakeAt = 0L
    private var shakeMag = 0f

    /** Ударные волны: x, y, начало, цвет, радиус. */
    private val rings = ArrayList<FloatArray>()

    /** Подсветка краёв поля при каскаде x3 и больше. */
    private var comboAt = 0L
    private var comboLevel = 0

    /** Искорки, которые сами вспыхивают на кристаллах, пока игрок думает. */
    private var twinkleCell = -1
    private var twinkleAt = 0L
    private val twRnd = java.util.Random()

    private fun shake(mag: Float) {
        // Трясём только от сильных взрывов, и слабо - иначе доска дёргается.
        if (!fancy || mag < 3f) return
        shakeAt = SystemClock.uptimeMillis()
        shakeMag = (maxOf(shakeMag / d * 0.5f, mag) * 0.45f).coerceAtMost(2.6f) * d
    }

    private fun ring(x: Float, y: Float, color: Int, r: Float) {
        rings += floatArrayOf(x, y, SystemClock.uptimeMillis().toFloat(), Float.fromBits(color), r)
        if (rings.size > 12) rings.removeAt(0)
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float, alpha: Int) {
        fx.shader = null
        fx.color = 0xFFFFFFFF.toInt()
        fx.alpha = alpha
        path.reset()
        path.moveTo(x, y - r)
        path.quadTo(x, y, x + r, y)
        path.quadTo(x, y, x, y + r)
        path.quadTo(x, y, x - r, y)
        path.quadTo(x, y, x, y - r)
        path.close()
        c.drawPath(path, fx)
        fx.alpha = 255
    }

    fun flash(text: String) {
        banner = text
        bannerAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun stop() {
        phase = Phase.IDLE
        busy = false
    }

    /** Вступление: кристаллы падают сверху волной. */
    fun intro() {
        for (i in 0 until 64) {
            offY[i] = -(e.row(i) + 2 + (i % 8) * 0.35f) * (if (cell > 0) cell else 40 * d)
            vel[i] = 0f
        }
        introPending = cell <= 0
        phase = Phase.FALL
        busy = true
        phaseAt = SystemClock.uptimeMillis()
        invalidate()
    }
    private var introPending = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Рамка из макета: внутренняя область (клетки) - с такими отступами от краёв картинки.
        val fl = 74f / 1229
        val fr = 71f / 1229
        val ft = 96f / 1262
        val fb = 57f / 1262
        val maxBoardW = w / (1 + (fl + fr) * 1.03f)
        val maxBoardH = h / (1 + (ft + fb) * 1.03f)
        val board = min(maxBoardW, maxBoardH)
        cell = board / 8
        val fw = board / (1 - fl - fr)
        val fh = board / (1 - ft - fb)
        ox = (w - board) / 2f
        oy = (h - board) / 2f + (ft - fb) * fh / 2f
        frameRect.set(ox - fl * fw, oy - ft * fh, ox + board + fr * fw, oy + board + fb * fh)
        if (introPending) {
            introPending = false
            for (i in 0 until 64) offY[i] = -(e.row(i) + 2 + (i % 8) * 0.35f) * cell
        }
    }

    private fun cx(i: Int) = ox + (e.col(i) + 0.5f) * cell
    private fun cy(i: Int) = oy + (e.row(i) + 0.5f) * cell

    // ---------- рисование ----------

    override fun onDraw(c: Canvas) {
        if (cell <= 0f) return
        val now = SystemClock.uptimeMillis()
        var anim = step(now)
        // Тряска: быстро затухающее смещение всего поля.
        c.save()
        val shAge = (now - shakeAt) / 260f
        if (shakeMag > 0f && shAge < 1f) {
            // Мягкая короткая дрожь (раньше - резкая и частая: доска «дёргалась»).
            val k = (1 - shAge) * (1 - shAge) * shakeMag
            c.translate(sin(now / 38f) * k, cos(now / 47f) * k * 0.5f)
            anim = true
        }
        // Клетки: тёплое дерево «шахматкой», тонкие тёмные швы, листья. Дыр нет - там виден лес.
        fx.shader = null
        for (i in 0 until 64) {
            if (e.hole[i]) continue
            val x = ox + e.col(i) * cell
            val y = oy + e.row(i) * cell
            fx.color = 0xFF4A2E22.toInt()
            c.drawRect(x - 1.5f * d, y - 1.5f * d, x + cell + 1.5f * d, y + cell + 1.5f * d, fx)
        }
        for (i in 0 until 64) {
            if (e.hole[i]) continue
            val x = ox + e.col(i) * cell
            val y = oy + e.row(i) * cell
            val dark = (e.row(i) + e.col(i)) % 2 == 0
            // Все клетки - дерево; листья рисуются настоящими листьями поверх (не сливаются с полем).
            fx.color = if (dark) 0xFF7E5542.toInt() else 0xFF74503D.toInt()
            tmp.set(x + 1.2f * d, y + 1.2f * d, x + cell - 1.2f * d, y + cell - 1.2f * d)
            c.drawRoundRect(tmp, 5 * d, 5 * d, fx)
            if (e.leaf[i] > 0 && !e.blocked(i)) {
                // Большой лист под кристаллом - та же картинка, что в целях уровня (как в дизайне).
                val ls = cell * (if (e.leaf[i] >= 2) 1.02f else 0.96f)
                dst.set(x + cell / 2 - ls / 2, y + cell / 2 - ls / 2, x + cell / 2 + ls / 2, y + cell / 2 + ls / 2)
                if (e.leaf[i] >= 2) {
                    // Двойной слой - второй лист чуть повёрнут под первым.
                    c.save()
                    c.rotate(-70f, x + cell / 2, y + cell / 2)
                    c.drawBitmap(art.leaf, null, dst, p)
                    c.restore()
                }
                c.drawBitmap(art.leaf, null, dst, p)
            }
        }
        // Подсветка выбранной клетки и подсказка.
        if (selected >= 0) {
            val pulse = 0.5f + 0.5f * sin(now / 160f)
            fx.color = 0x55FFF3B0
            fx.alpha = (60 + 60 * pulse).toInt()
            tmp.set(ox + e.col(selected) * cell, oy + e.row(selected) * cell, ox + (e.col(selected) + 1) * cell, oy + (e.row(selected) + 1) * cell)
            c.drawRoundRect(tmp, 8 * d, 8 * d, fx)
            anim = true
        }
        val h = hint
        if (h != null && phase == Phase.IDLE) {
            val pulse = 0.5f + 0.5f * sin(now / 200f)
            for (i in h) {
                fx.shader = RadialGradient(cx(i), cy(i), cell * 0.6f, (0xFFFFF59D.toInt() and 0x00FFFFFF) or ((150 * pulse).toInt() shl 24), 0x00FFF59D, Shader.TileMode.CLAMP)
                c.drawCircle(cx(i), cy(i), cell * 0.6f, fx)
            }
            fx.shader = null
            anim = true
        }
        // Кристаллы (падающие сверху появляются из-под рамки).
        val sw = ((now - phaseAt) / 170f).coerceIn(0f, 1f)
        c.save()
        c.clipRect(ox - cell * 0.3f, oy - cell * 0.08f, ox + cell * 8.3f, oy + cell * 8.3f)
        for (i in 0 until 64) {
            val g = e.gem[i]
            if (g < 0) continue
            var x = cx(i) + offX[i]
            var y = cy(i) + offY[i]
            if (phase == Phase.SWAP_BACK && (i == swapA || i == swapB)) {
                val other = if (i == swapA) swapB else swapA
                val k = if (sw < 0.5f) sw * 2 else (1 - sw) * 2
                x += (cx(other) - cx(i)) * k * 0.55f
                y += (cy(other) - cy(i)) * k * 0.55f
            }
            if (y < oy - cell) continue
            var scale = appear[i]
            if (i == selected) scale *= 1.08f + 0.04f * sin(now / 120f)
            if (h != null && i in h && phase == Phase.IDLE) scale *= 1f + 0.06f * sin(now / 200f)
            drawGem(c, g, e.special[i], x, y, scale, now, i)
            if (e.ice[i] > 0) drawIce(c, cx(i), cy(i), e.ice[i], now, i)
        }
        // Листья поверх кристаллов: светлая рамка клетки и значок-листик в углу
        // (у двойных - два листика и цифра), чтобы листья не прятались за кристаллами.
        for (i in 0 until 64) if (e.leaf[i] > 0 && !e.blocked(i)) drawLeafMark(c, i, e.leaf[i], now)
        // Золотые залежи - поверх всего (падающие кристаллы пролетают «за» ними).
        for (i in 0 until 64) if (e.gold[i] > 0 && !e.hole[i]) drawGold(c, i, e.gold[i], now)
        c.restore()
        // Лопающиеся.
        val di = dying.iterator()
        while (di.hasNext()) {
            val dd = di.next()
            val age = (now - dd.at) / 240f
            if (age >= 1f) {
                di.remove()
                continue
            }
            anim = true
            if (age < 0f) {
                // Волна от бонуса ещё не дошла - кристалл на месте.
                drawGem(c, dd.kind, dd.sp, cx(dd.cell), cy(dd.cell), 1f, now, dd.cell)
                continue
            }
            val s = if (age < 0.3f) 1f + age * 0.8f else (1.24f * (1 - (age - 0.3f) / 0.7f))
            p.alpha = (255 * (1 - age * 0.6f)).toInt().coerceIn(0, 255)
            drawGem(c, dd.kind, dd.sp, cx(dd.cell), cy(dd.cell), s, now, dd.cell)
            // Кристалл вспыхивает белым перед тем, как лопнуть.
            if (dd.sp == 0 && dd.kind in art.glints.indices && age < 0.5f) {
                val gs = cell * 0.86f * s
                dst.set(cx(dd.cell) - gs / 2, cy(dd.cell) - gs / 2, cx(dd.cell) + gs / 2, cy(dd.cell) + gs / 2)
                p.alpha = (200 * (1 - age * 2)).toInt().coerceIn(0, 255)
                c.drawBitmap(art.glints[dd.kind], null, dst, p)
            }
            p.alpha = 255
            // Вспышка.
            fx.shader = RadialGradient(cx(dd.cell), cy(dd.cell), cell * 0.7f, (0xFFFFFFFF.toInt() and 0x00FFFFFF) or ((180 * (1 - age)).toInt() shl 24), 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawCircle(cx(dd.cell), cy(dd.cell), cell * 0.7f, fx)
            fx.shader = null
            anim = true
        }
        // Лучи бонусов.
        val bi = beams.iterator()
        while (bi.hasNext()) {
            val b = bi.next()
            val age = (now - b.at) / 420f
            if (age >= 1f) {
                bi.remove()
                continue
            }
            drawBeam(c, b, age)
            anim = true
        }
        // Ударные волны (бомба, радуга, новые бонусы).
        if (rings.isNotEmpty()) {
            val ri = rings.iterator()
            while (ri.hasNext()) {
                val r = ri.next()
                val age = (now - r[2].toLong()) / 520f
                if (age >= 1f) {
                    ri.remove()
                    continue
                }
                val rad = r[4] * (0.15f + 0.85f * (1 - (1 - age) * (1 - age)))
                fx.shader = null
                fx.style = Paint.Style.STROKE
                fx.strokeWidth = cell * 0.22f * (1 - age)
                fx.color = r[3].toRawBits()
                fx.alpha = (200 * (1 - age)).toInt()
                c.drawCircle(r[0], r[1], rad, fx)
                fx.strokeWidth = cell * 0.08f * (1 - age)
                fx.color = 0xFFFFFFFF.toInt()
                fx.alpha = (230 * (1 - age)).toInt()
                c.drawCircle(r[0], r[1], rad * 0.92f, fx)
                fx.style = Paint.Style.FILL
                fx.alpha = 255
            }
            anim = true
        }
        // Искорка на случайном кристалле, пока поле спокойно.
        if (fancy && phase == Phase.IDLE) {
            if (now - twinkleAt > 1300) {
                twinkleAt = now
                twinkleCell = twRnd.nextInt(64)
            }
            val tAge = (now - twinkleAt) / 600f
            val tc = twinkleCell
            if (tc in 0 until 64 && tAge < 1f && e.gem[tc] >= 0 && !e.blocked(tc)) {
                val a = (255 * sin(tAge * Math.PI.toFloat())).toInt()
                star(c, cx(tc) + cell * 0.18f, cy(tc) - cell * 0.2f, cell * 0.2f * (0.6f + 0.4f * sin(tAge * 3.14f)), a)
            }
        }
        // Рамка из макета поверх краёв поля.
        c.drawBitmap(art.frame, null, frameRect, p)
        // Каскад x3+: края поля переливаются цветами.
        val cAge = (now - comboAt) / 900f
        if (comboLevel >= 3 && cAge < 1f) {
            val hue = (now / 4f) % 360f
            val col = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 1f))
            fx.shader = null
            fx.style = Paint.Style.STROKE
            fx.strokeWidth = cell * 0.18f * (1 - cAge)
            fx.color = col
            fx.alpha = (180 * (1 - cAge)).toInt()
            tmp.set(ox - cell * 0.05f, oy - cell * 0.05f, ox + cell * 8.05f, oy + cell * 8.05f)
            c.drawRoundRect(tmp, 10 * d, 10 * d, fx)
            fx.style = Paint.Style.FILL
            fx.alpha = 255
            anim = true
        }
        // Частицы.
        if (parts.isNotEmpty()) {
            val pi = parts.iterator()
            while (pi.hasNext()) {
                val q = pi.next()
                val age = now - q[4]
                val life = if (q[7] == 1f) 650f else 520f
                if (age > life) {
                    pi.remove()
                    continue
                }
                val px = q[0] + q[2] * age
                val py = q[1] + q[3] * age + 0.0009f * d * age * age
                fx.color = q[5].toInt()
                fx.alpha = (255 * (1 - age / life)).toInt()
                if (q[7] == 1f) {
                    // Осколок - маленький ромбик, кувыркается.
                    c.save()
                    c.rotate(age * 0.6f + q[6] * 50, px, py)
                    val s = q[6] * d
                    path.reset()
                    path.moveTo(px, py - s)
                    path.lineTo(px + s * 0.6f, py)
                    path.lineTo(px, py + s)
                    path.lineTo(px - s * 0.6f, py)
                    path.close()
                    c.drawPath(path, fx)
                    c.restore()
                } else {
                    c.drawCircle(px, py, q[6] * d * (1 - age / life * 0.6f), fx)
                }
            }
            anim = true
        }
        // Очки и похвала.
        val oi = pops.iterator()
        while (oi.hasNext()) {
            val o = oi.next()
            val age = (now - o.at) / (if (o.big) 1300f else 900f)
            if (age >= 1f) {
                oi.remove()
                continue
            }
            val a = (255 * (1 - age * age)).toInt()
            txt.textSize = (if (o.big) 34f else 17f) * d * (if (o.big) (1f + 0.3f * sin(min(age * 4f, 1f) * Math.PI.toFloat() / 2)) else 1f)
            txt.shader = LinearGradient(0f, o.y - txt.textSize, 0f, o.y, intArrayOf(0xFFFFF7C2.toInt(), 0xFFFBBF24.toInt(), 0xFFEA580C.toInt()), null, Shader.TileMode.CLAMP)
            txt.alpha = a
            txt.setShadowLayer(4 * d, 0f, 2 * d, 0xAA3B1F0A.toInt())
            c.drawText(o.text, o.x, o.y - age * cell * (if (o.big) 0.6f else 1.2f), txt)
            txt.shader = null
            txt.clearShadowLayer()
            anim = true
        }
        txt.alpha = 255
        val bAge = now - bannerAt
        if (banner.isNotEmpty() && bAge < 1600) {
            val a = (255 * (1 - bAge / 1600f)).toInt().coerceIn(0, 255)
            txt.textSize = 16 * d
            val w = txt.measureText(banner) / 2 + 16 * d
            fx.color = 0xCC3B2210.toInt()
            fx.alpha = a * 4 / 5
            tmp.set(width / 2f - w, oy + 6 * d, width / 2f + w, oy + 36 * d)
            c.drawRoundRect(tmp, 15 * d, 15 * d, fx)
            txt.color = 0xFFFDE7B0.toInt()
            txt.alpha = a
            c.drawText(banner, width / 2f, oy + 27 * d, txt)
            txt.alpha = 255
            anim = true
        }
        // Подсказка после 5 секунд без хода.
        if (phase == Phase.IDLE && hint == null && boostMode < 0 && now - lastAction > 5000) {
            hint = e.findMove()
            anim = true
        }
        c.restore()
        when {
            anim || phase != Phase.IDLE -> postInvalidateOnAnimation()
            fancy && isShown -> postInvalidateDelayed(LiteMode.liveFrameMs)
            phase == Phase.IDLE && hint == null && isShown -> postInvalidateDelayed(1000)
        }
    }

    private fun drawGem(c: Canvas, g: Int, sp: Int, x: Float, y: Float, scale: Float, now: Long, i: Int) {
        if (scale <= 0.01f) return
        val base = cell * 0.86f * scale
        when (sp) {
            Match3Engine.RAINBOW -> {
                // Радужный шар: вращается и переливается.
                val s = base * (1.02f + 0.04f * sin(now / 260f + i))
                fx.shader = RadialGradient(x, y, s * 0.75f, intArrayOf(0x88FFFFFF.toInt(), 0x44F0ABFC, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, s * 0.75f, fx)
                fx.shader = null
                c.save()
                c.rotate((now / 12L % 360).toFloat(), x, y)
                dst.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
                c.drawBitmap(art.rainbow, null, dst, p)
                c.restore()
                return
            }
            Match3Engine.BOMB -> {
                val s = base * (1f + 0.05f * sin(now / 140f + i))
                dst.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
                // Под бомбой - цвет её «вида» мягким кругом.
                fx.shader = RadialGradient(x, y, s * 0.62f, (Match3Game.SPARK[g.coerceIn(0, 5)] and 0x00FFFFFF) or 0x77000000, 0x00000000, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, s * 0.62f, fx)
                fx.shader = null
                c.drawBitmap(art.bomb, null, dst, p)
                // Искра на фитиле.
                if (fancy) {
                    val fl = 0.5f + 0.5f * sin(now / 50f + i)
                    fx.color = 0xFFFFE08A.toInt()
                    fx.alpha = (150 + 100 * fl).toInt()
                    c.drawCircle(x + s * 0.27f, y - s * 0.4f, s * (0.06f + 0.04f * fl), fx)
                    fx.alpha = 255
                }
                return
            }
        }
        if (g !in art.gems.indices) return
        // Живые кристаллы - спокойно, без перекосов: чуть «дышат», при приземлении мягко
        // пружинят, в быстром падении слегка вытягиваются.
        val live = fancy
        val breathe = if (live) 1f + 0.012f * sin(now / 650f + i * 0.9f) else 1f
        var sx = 1f
        var sy = 1f
        val landAge = now - landAt[i]
        if (landAge in 0..360) {
            val k = kotlin.math.exp(-landAge / 90f) * cos(landAge / 50f)
            sx += 0.07f * k
            sy -= 0.07f * k
        }
        if (vel[i] > 0f && offY[i] < 0f) {
            val st = (vel[i] / cell * 0.5f).coerceAtMost(0.05f)
            sy += st
            sx -= st * 0.5f
        }
        // Волна: пока игрок думает, раз в несколько секунд по полю прокатывается мягкая волна -
        // кристаллы по очереди подпрыгивают и чуть увеличиваются.
        var wy = 0f
        var wave = 1f
        if (live && waveAt > 0L) {
            val wt = now - waveAt
            if (wt in 0..1500) {
                val dr = (e.row(i) - waveFrom / 8).toFloat()
                val dc = (e.col(i) - waveFrom % 8).toFloat()
                val dist = kotlin.math.sqrt(dr * dr + dc * dc)
                val front = wt / 120f
                val k = kotlin.math.exp(-(dist - front) * (dist - front) * 1.1f)
                wave = 1f + 0.05f * k
                wy = -cell * 0.05f * k
            }
        }
        val s = base * breathe * wave
        c.save()
        c.translate(x, y + wy)
        c.scale(sx, sy)
        dst.set(-s / 2, -s / 2, s / 2, s / 2)
        c.drawBitmap(art.gems[g], null, dst, p)
        // Блеск: раз в несколько секунд кристалл вспыхивает (у каждого - в своё время).
        if (live) {
            val ph = (now + i * 733L) % 5200L
            if (ph < 420L) {
                p.alpha = (110 * sin(Math.PI * ph / 420.0)).toInt().coerceIn(0, 255)
                c.drawBitmap(art.glints[g], null, dst, p)
                p.alpha = 255
            }
        }
        c.restore()
        if (sp == Match3Engine.H || sp == Match3Engine.V) {
            // Линия: светящаяся полоса и бегущие стрелки по направлению взрыва.
            val horiz = sp == Match3Engine.H
            val pulse = 0.5f + 0.5f * sin(now / 160f + i)
            fx.color = 0xFFFFFFFF.toInt()
            fx.alpha = (120 + 100 * pulse).toInt()
            if (horiz) tmp.set(x - s * 0.5f, y - s * 0.07f, x + s * 0.5f, y + s * 0.07f) else tmp.set(x - s * 0.07f, y - s * 0.5f, x + s * 0.07f, y + s * 0.5f)
            c.drawRoundRect(tmp, s * 0.07f, s * 0.07f, fx)
            fx.alpha = 255
            fx.color = 0xFFFFF59D.toInt()
            val run = ((now / 600f + i * 0.1f) % 1f) * s * 0.18f
            for (sgn in intArrayOf(-1, 1)) {
                path.reset()
                val tip = s * 0.52f + run
                if (horiz) {
                    path.moveTo(x + sgn * tip, y)
                    path.lineTo(x + sgn * (tip - s * 0.16f), y - s * 0.13f)
                    path.lineTo(x + sgn * (tip - s * 0.16f), y + s * 0.13f)
                } else {
                    path.moveTo(x, y + sgn * tip)
                    path.lineTo(x - s * 0.13f, y + sgn * (tip - s * 0.16f))
                    path.lineTo(x + s * 0.13f, y + sgn * (tip - s * 0.16f))
                }
                path.close()
                c.drawPath(path, fx)
            }
        } else if (fancy) {
            // Блик: время от времени по кристаллу пробегает звёздочка.
            val ph = ((now + i * 733L) % 4200L) / 4200f
            if (ph < 0.12f) {
                val k = ph / 0.12f
                val a = sin(k * Math.PI.toFloat())
                val sx = x - s * 0.18f + s * 0.1f * k
                val sy = y - s * 0.2f
                val r = s * 0.16f * a
                fx.color = 0xFFFFFFFF.toInt()
                fx.alpha = (240 * a).toInt()
                fx.strokeWidth = maxOf(1f, s * 0.03f)
                c.drawLine(sx - r, sy, sx + r, sy, fx)
                c.drawLine(sx, sy - r, sx, sy + r, fx)
                c.drawCircle(sx, sy, fx.strokeWidth, fx)
                fx.alpha = 255
            }
        }
    }

    /** Значок листьев поверх клетки: тёмно-зелёный кружок со светлым ободком и листиком в правом нижнем углу (без рамки). */
    private fun drawLeafMark(c: Canvas, i: Int, layers: Int, now: Long) {
        val x = ox + e.col(i) * cell
        val y = oy + e.row(i) * cell
        val r = cell * 0.17f
        val bx = x + cell - r - 2.5f * d
        val by = y + cell - r - 2.5f * d
        fx.shader = null
        fx.style = Paint.Style.FILL
        fx.color = 0x66000000
        c.drawCircle(bx, by + 1f * d, r, fx)
        fx.shader = RadialGradient(bx - r * 0.3f, by - r * 0.3f, r * 1.3f, 0xFF2F7D3A.toInt(), 0xFF14532D.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(bx, by, r, fx)
        fx.shader = null
        fx.style = Paint.Style.STROKE
        fx.strokeWidth = 1.5f * d
        fx.color = 0xFFC8E6C9.toInt()
        c.drawCircle(bx, by, r, fx)
        fx.style = Paint.Style.FILL
        val li = r * 1.25f
        dst.set(bx - li / 2, by - li / 2, bx + li / 2, by + li / 2)
        c.drawBitmap(art.leaf, null, dst, p)
        if (layers >= 2) badge(c, x + cell * 0.2f, y + cell * 0.2f, layers, 0xFF166534.toInt())
    }

    /** Кружок с цифрой (сколько слоёв / ударов осталось). */
    private fun badge(c: Canvas, bx: Float, by: Float, n: Int, color: Int) {
        val r = cell * 0.15f
        fx.shader = null
        fx.style = Paint.Style.FILL
        fx.color = color
        c.drawCircle(bx, by, r, fx)
        fx.style = Paint.Style.STROKE
        fx.strokeWidth = 1.2f * d
        fx.color = 0xFFFFFFFF.toInt()
        c.drawCircle(bx, by, r, fx)
        fx.style = Paint.Style.FILL
        txt.shader = null
        txt.clearShadowLayer()
        txt.alpha = 255
        txt.color = 0xFFFFFFFF.toInt()
        txt.textSize = r * 1.35f
        c.drawText(n.toString(), bx, by + txt.textSize * 0.36f, txt)
    }

    /**
     * Лёд как в дизайне: прозрачная голубоватая стеклянная плитка на всю клетку - кристалл
     * хорошо виден сквозь неё; светлая кромка, мягкий блик сверху слева. Двойной лёд - плотнее
     * (белее) и с цифрой «2». После удара плитка вспыхивает.
     */
    private fun drawIce(c: Canvas, x: Float, y: Float, layers: Int, now: Long, i: Int) {
        val s = cell * 0.47f
        val thick = layers >= 2
        val r = cell * 0.12f
        tmp.set(x - s, y - s, x + s, y + s)
        fx.style = Paint.Style.FILL
        fx.shader = LinearGradient(
            tmp.left, tmp.top, tmp.right, tmp.bottom,
            if (thick) {
                intArrayOf(0xB3F2FAFF.toInt(), 0x8CD6ECFA.toInt(), 0x99B9DCF2.toInt())
            } else {
                intArrayOf(0x99F2FAFF.toInt(), 0x66CFE8F7, 0x80AFD5EE.toInt())
            },
            null, Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(tmp, r, r, fx)
        fx.shader = null
        // Блик: светлая полоса по верхнему левому краю.
        fx.shader = LinearGradient(tmp.left, tmp.top, tmp.left + s * 0.9f, tmp.top + s * 0.9f, 0x99FFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
        tmp.inset(2f * d, 2f * d)
        c.drawRoundRect(tmp, r * 0.8f, r * 0.8f, fx)
        tmp.inset(-2f * d, -2f * d)
        fx.shader = null
        // Кромка: светлая, внутри - тонкая голубая.
        fx.style = Paint.Style.STROKE
        fx.strokeWidth = 2f * d
        fx.color = if (thick) 0xFFFFFFFF.toInt() else 0xE6F5FBFF.toInt()
        c.drawRoundRect(tmp, r, r, fx)
        fx.strokeWidth = 1f * d
        fx.color = 0x8090C8E8.toInt()
        tmp.inset(2.2f * d, 2.2f * d)
        c.drawRoundRect(tmp, r * 0.75f, r * 0.75f, fx)
        tmp.inset(-2.2f * d, -2.2f * d)
        fx.style = Paint.Style.FILL
        val flash = ((now - hitAt[i]) / 380f).let { if (it in 0f..1f) 1f - it else 0f }
        if (flash > 0f) {
            fx.color = 0xFFFFFFFF.toInt()
            fx.alpha = (150 * flash).toInt()
            c.drawRoundRect(tmp, r, r, fx)
            fx.alpha = 255
        }
        if (thick) badge(c, x - s * 0.62f, y - s * 0.62f, layers, 0xFF0284C7.toInt())
    }

    /**
     * Золотая залежь: тёмная каменная глыба с золотыми жилами и самородками. Чем меньше
     * ударов осталось, тем больше трещин и меньше камня; в углу - цифра ударов.
     */
    private fun drawGold(c: Canvas, i: Int, hits: Int, now: Long) {
        val x = ox + e.col(i) * cell
        val y = oy + e.row(i) * cell
        val cxp = x + cell / 2
        val cyp = y + cell / 2
        val flash = ((now - hitAt[i]) / 380f).let { if (it in 0f..1f) 1f - it else 0f }
        val jig = if (flash > 0f && fancy) sin(now / 18f) * 2f * d * flash else 0f
        val s = cell * 0.47f
        tmp.set(cxp - s + jig, cyp - s, cxp + s + jig, cyp + s)
        fx.style = Paint.Style.FILL
        fx.shader = LinearGradient(
            0f, tmp.top, 0f, tmp.bottom,
            when (hits) {
                1 -> intArrayOf(0xFF8D7B62.toInt(), 0xFF6B5A45.toInt(), 0xFF4A3D2E.toInt())
                2 -> intArrayOf(0xFF7A6A55.toInt(), 0xFF57483A.toInt(), 0xFF3A2F25.toInt())
                else -> intArrayOf(0xFF6B5E50.toInt(), 0xFF463B31.toInt(), 0xFF2A221C.toInt())
            },
            null, Shader.TileMode.CLAMP,
        )
        c.drawRoundRect(tmp, cell * 0.16f, cell * 0.16f, fx)
        fx.shader = null
        fx.style = Paint.Style.STROKE
        fx.strokeWidth = 2f * d
        fx.color = 0xFFE9B949.toInt()
        c.drawRoundRect(tmp, cell * 0.16f, cell * 0.16f, fx)
        // Золотые жилы.
        fx.strokeWidth = 2.2f * d
        fx.color = 0xFFFACC15.toInt()
        path.reset()
        path.moveTo(tmp.left + s * 0.15f, tmp.top + s * 0.55f)
        path.lineTo(cxp + jig - s * 0.1f, cyp - s * 0.05f)
        path.lineTo(cxp + jig + s * 0.25f, cyp + s * 0.15f)
        path.lineTo(tmp.right - s * 0.1f, tmp.bottom - s * 0.5f)
        c.drawPath(path, fx)
        // Трещины: на 2 ударах - одна, на 1 ударе - сеть.
        if (hits <= 2) {
            fx.strokeWidth = (1.3f + 1.5f * flash) * d
            fx.color = if (flash > 0f) 0xFFFFFFFF.toInt() else 0xFF1C1712.toInt()
            path.reset()
            path.moveTo(cxp + jig - s * 0.05f, tmp.top + 2 * d)
            path.lineTo(cxp + jig + s * 0.1f, cyp - s * 0.2f)
            path.lineTo(cxp + jig - s * 0.15f, cyp + s * 0.35f)
            if (hits <= 1) {
                path.moveTo(tmp.left + 2 * d, cyp + s * 0.2f)
                path.lineTo(cxp + jig - s * 0.15f, cyp + s * 0.35f)
                path.lineTo(cxp + jig + s * 0.2f, tmp.bottom - 2 * d)
                path.moveTo(cxp + jig + s * 0.1f, cyp - s * 0.2f)
                path.lineTo(tmp.right - 2 * d, cyp - s * 0.35f)
            }
            c.drawPath(path, fx)
        }
        fx.style = Paint.Style.FILL
        // Самородки (по одному на удар).
        val spots = arrayOf(floatArrayOf(-0.35f, -0.4f), floatArrayOf(0.4f, -0.3f), floatArrayOf(0.05f, 0.42f))
        for (k in 0 until hits.coerceIn(1, 3)) {
            val nx = cxp + jig + spots[k][0] * s
            val ny = cyp + spots[k][1] * s
            val nr = s * 0.2f
            fx.shader = RadialGradient(nx - nr * 0.3f, ny - nr * 0.3f, nr, intArrayOf(0xFFFFF7C2.toInt(), 0xFFFACC15.toInt(), 0xFFB45309.toInt()), null, Shader.TileMode.CLAMP)
            c.drawCircle(nx, ny, nr, fx)
        }
        fx.shader = null
        if (fancy && ((now + i * 389L) % 2600L) < 200L) star(c, cxp + s * 0.4f, cyp - s * 0.3f, s * 0.22f, 230)
        if (flash > 0f) {
            fx.color = 0xFFFFF7C2.toInt()
            fx.alpha = (150 * flash).toInt()
            c.drawRoundRect(tmp, cell * 0.16f, cell * 0.16f, fx)
            fx.alpha = 255
        }
        badge(c, x + cell - cell * 0.2f, y + cell - cell * 0.2f, hits, 0xFF92400E.toInt())
    }

    private fun goldBurst(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis().toFloat()
        val rnd = java.util.Random()
        repeat(LiteMode.particles(10)) {
            val a = rnd.nextFloat() * 6.283f
            val sp = (0.05f + rnd.nextFloat() * 0.15f) * d
            parts += floatArrayOf(x, y, cos(a) * sp, sin(a) * sp - 0.1f * d, now, (if (it % 3 == 0) 0xFF6B5A45.toInt() else 0xFFFACC15.toInt()).toFloat(), 4f + rnd.nextFloat() * 3f, 1f)
        }
    }

    private fun iceBurst(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis().toFloat()
        val rnd = java.util.Random()
        repeat(LiteMode.particles(8)) {
            val a = rnd.nextFloat() * 6.283f
            val sp = (0.06f + rnd.nextFloat() * 0.16f) * d
            parts += floatArrayOf(x, y, cos(a) * sp, sin(a) * sp - 0.1f * d, now, (if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF7DD3FC.toInt()).toFloat(), 5f + rnd.nextFloat() * 3f, 1f)
        }
    }

    private fun drawBeam(c: Canvas, b: Beam, age: Float) {
        val x = cx(b.cell)
        val y = cy(b.cell)
        val a = 1f - age
        when (b.sp) {
            Match3Engine.H, Match3Engine.V -> {
                val horiz = b.sp == Match3Engine.H
                val thick = cell * (0.25f + 0.55f * a)
                val reach = cell * 9 * min(1f, age * 4f)
                fx.shader = if (horiz) {
                    LinearGradient(0f, y - thick, 0f, y + thick, intArrayOf(0x00FFFFFF, (0xFFFFF7C2.toInt() and 0x00FFFFFF) or ((220 * a).toInt() shl 24), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                } else {
                    LinearGradient(x - thick, 0f, x + thick, 0f, intArrayOf(0x00FFFFFF, (0xFFFFF7C2.toInt() and 0x00FFFFFF) or ((220 * a).toInt() shl 24), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                }
                if (horiz) c.drawRect(maxOf(ox, x - reach), y - thick, min(ox + cell * 8, x + reach), y + thick, fx) else c.drawRect(x - thick, maxOf(oy, y - reach), x + thick, min(oy + cell * 8, y + reach), fx)
                fx.shader = null
            }
            Match3Engine.BOMB -> {
                // Ударная волна.
                fx.style = Paint.Style.STROKE
                fx.strokeWidth = cell * 0.3f * a
                fx.color = 0xFFFFC56B.toInt()
                fx.alpha = (230 * a).toInt()
                c.drawCircle(x, y, cell * (0.4f + 2.2f * age), fx)
                fx.style = Paint.Style.FILL
                fx.shader = RadialGradient(x, y, cell * 1.8f, (0xFFFFE08A.toInt() and 0x00FFFFFF) or ((160 * a).toInt() shl 24), 0x00FFE08A, Shader.TileMode.CLAMP)
                c.drawCircle(x, y, cell * 1.8f, fx)
                fx.shader = null
                fx.alpha = 255
            }
            Match3Engine.RAINBOW -> {
                // Радужные «молнии» к поражённым клеткам.
                val targets = rainbowTargets
                fx.strokeWidth = 3 * d * a + 1
                for ((n, t) in targets.withIndex()) {
                    fx.color = android.graphics.Color.HSVToColor(((n * 40 + (age * 360).toInt()) % 360).toFloat().let { floatArrayOf(it, 0.6f, 1f) })
                    fx.alpha = (230 * a).toInt()
                    val tx = cx(t)
                    val ty = cy(t)
                    val mx = (x + tx) / 2 + sin(n + age * 20) * cell * 0.3f
                    val my = (y + ty) / 2 + cos(n + age * 20) * cell * 0.3f
                    c.drawLine(x, y, mx, my, fx)
                    c.drawLine(mx, my, tx, ty, fx)
                }
                fx.alpha = 255
            }
        }
    }
    private var rainbowTargets: List<Int> = emptyList()

    // ---------- шаги анимации ----------

    /** Продвинуть анимацию; true - что-то движется. */
    private var lastStepAt = 0L

    /** В простое (никто не двигает) раз в ~7 с запускаем волну по полю. */
    private fun idleWave(now: Long) {
        if (!fancy || phase != Phase.IDLE || busy) {
            idleSince = now
            return
        }
        if (idleSince == 0L) idleSince = now
        if (now - idleSince > 7000L && now - waveAt > 7000L) {
            waveAt = now
            waveFrom = (0 until 64).filter { !e.hole[it] }.randomOrNull() ?: 27
            idleSince = now
        }
    }

    private fun step(now: Long): Boolean {
        idleWave(now)
        val t = now - phaseAt
        val dt = if (lastStepAt == 0L) 16f else (now - lastStepAt).coerceIn(1L, 40L).toFloat()
        lastStepAt = now
        when (phase) {
            Phase.IDLE -> return false
            Phase.SWAP -> {
                val k = (t / 170f).coerceIn(0f, 1f)
                val ease = 1 - (1 - k) * (1 - k)
                // Кристаллы уже обменены в движке: тянем их от старых мест к новым.
                offX[swapA] = (cx(swapB) - cx(swapA)) * (1 - ease)
                offY[swapA] = (cy(swapB) - cy(swapA)) * (1 - ease)
                offX[swapB] = (cx(swapA) - cx(swapB)) * (1 - ease)
                offY[swapB] = (cy(swapA) - cy(swapB)) * (1 - ease)
                if (k >= 1f) {
                    offX[swapA] = 0f
                    offY[swapA] = 0f
                    offX[swapB] = 0f
                    offY[swapB] = 0f
                    resolveNext()
                }
            }
            Phase.SWAP_BACK -> if (t > 170) {
                phase = Phase.IDLE
                busy = false
            }
            Phase.CLEAR -> if (now >= clearUntil) {
                val (moved, spawned) = e.gravity()
                for (m in moved) {
                    offY[m[1]] = (e.row(m[0]) - e.row(m[1])) * cell
                    offX[m[1]] = 0f
                    vel[m[1]] = 0f
                }
                for (s in spawned) {
                    offY[s[0]] = -s[1] * cell - cell * 0.3f
                    vel[s[0]] = 0f
                    appear[s[0]] = 1f
                }
                phase = Phase.FALL
                phaseAt = now
            }
            Phase.FALL -> {
                var moving = false
                var landed = 0
                val g = 0.0045f * cell
                // По реальному времени кадра (а не «16 мс на кадр»): на 60/90/120 Гц и при
                // пропущенных кадрах кристаллы падают с одной скоростью, без рывков.
                val k = (dt / 16f).coerceIn(0.25f, 2f)
                for (i in 0 until 64) {
                    if (offY[i] == 0f && vel[i] == 0f) continue
                    vel[i] += g * 16f * k
                    offY[i] += vel[i] * k
                    if (offY[i] >= 0f) {
                        offY[i] = 0f
                        if (vel[i] > cell * 0.05f) landAt[i] = now
                        if (vel[i] > cell * 0.14f) {
                            landed++
                            vel[i] = -vel[i] * 0.16f
                        } else {
                            vel[i] = 0f
                        }
                    }
                    if (offY[i] != 0f || vel[i] != 0f) moving = true
                }
                if (landed > 0 && now - lastLandSfx > 70) {
                    lastLandSfx = now
                    sfx(GameSfx.S.LAND, 0.9f + (landed % 4) * 0.08f, 0.35f)
                }
                if (!moving) {
                    if (introOnly) {
                        introOnly = false
                        phase = Phase.IDLE
                        busy = false
                    } else {
                        resolveNext()
                    }
                }
            }
            Phase.SHUFFLE -> {
                val k = (t / 520f).coerceIn(0f, 1f)
                for (i in 0 until 64) {
                    appear[i] = if (k < 0.5f) 1f - k * 2 else (k - 0.5f) * 2
                }
                if (k >= 0.5f && !shuffled) {
                    shuffled = true
                    e.shuffle()
                }
                if (k >= 1f) {
                    for (i in 0 until 64) appear[i] = 1f
                    if (e.hasMatches()) {
                        // После перемешивания случайно сложилась фигура - сразу убрать её,
                        // иначе любой обмен считался бы удачным.
                        resolveNext()
                    } else {
                        phase = Phase.IDLE
                        busy = false
                        onChanged()
                    }
                }
            }
        }
        return true
    }
    private var shuffled = false
    private var introOnly = true
    private var clearUntil = 0L
    private var lastLandSfx = 0L
    private var lastTapAt = 0L
    private var lastTapCell = -1

    private val praise = intArrayOf(R.string.m3_good, R.string.m3_great, R.string.m3_super, R.string.m3_amazing)
    private val SHAPES = intArrayOf(0, R.string.m3_shape_square, R.string.m3_shape_big_square, R.string.m3_shape_corner, R.string.m3_shape_line, R.string.m3_shape_rainbow)

    /** Следующий шаг хода: снова ряды/бонусы - или ход окончен. */
    private fun resolveNext() {
        val now = SystemClock.uptimeMillis()
        val clear = e.resolveBooster() ?: e.resolve()
        if (clear == null) {
            phase = Phase.IDLE
            busy = false
            onChanged()
            // Ходов на поле не осталось - перемешиваем.
            if (e.moves > 0 && !e.goalsDone && e.findMove() == null) {
                flash(context.getString(R.string.m3_no_moves))
                doShuffle(free = true)
                return
            }
            onSettled()
            return
        }
        var maxDelay = 0L
        for ((n, cl) in clear.cells.withIndex()) {
            val k = clear.kindsOf[n]
            // От сработавшего бонуса кристаллы лопаются волной.
            val delay = if (clear.fired.isNotEmpty()) ((abs(e.col(cl) - e.col(clear.fired[0].first)) + abs(e.row(cl) - e.row(clear.fired[0].first))) * 18L).coerceAtMost(160L) else 0L
            maxDelay = maxOf(maxDelay, delay)
            dying += Dying(cl, k, clear.specialsOf.getOrElse(n) { 0 }, now + delay)
            burst(cx(cl), cy(cl), Match3Game.SPARK[k.coerceIn(0, 5)])
        }
        clearUntil = now + 210 + maxDelay
        for (cl in clear.leaves) leafBurst(cx(cl), cy(cl))
        for (cl in clear.iceBroken) {
            iceBurst(cx(cl), cy(cl))
            hitAt[cl] = now
        }
        for (cl in clear.goldHit) {
            goldBurst(cx(cl), cy(cl))
            hitAt[cl] = now
        }
        // Звуки: кристальный звон (каждый каскад - выше), бонусы, лёд.
        if (clear.cells.isNotEmpty()) sfx(GameSfx.S.POP, (1f + (e.cascade - 1) * 0.12f).coerceAtMost(1.9f))
        if (clear.iceBroken.isNotEmpty()) sfx(GameSfx.S.DEAL, 1.6f, 0.8f)
        if (clear.goldHit.isNotEmpty()) sfx(GameSfx.S.DEAL, 0.8f, 0.9f)
        when {
            clear.fired.any { it.second == Match3Engine.RAINBOW } -> sfx(GameSfx.S.RAINBOW)
            clear.fired.any { it.second == Match3Engine.BOMB } -> sfx(GameSfx.S.BOMB)
            clear.fired.isNotEmpty() -> sfx(GameSfx.S.LINE)
        }
        if (clear.created.isNotEmpty()) postDelayed({ sfx(GameSfx.S.SPECIAL) }, 120)
        for ((cl, sp) in clear.fired) {
            if (sp == Match3Engine.RAINBOW) rainbowTargets = clear.cells.take(LiteMode.particles(24))
            beams += Beam(cl, sp, now)
            // Новое: ударная волна и тряска поля.
            when (sp) {
                Match3Engine.BOMB -> {
                    ring(cx(cl), cy(cl), 0xFFFB923C.toInt(), cell * 2.6f)
                    shake(5f)
                }
                Match3Engine.RAINBOW -> {
                    ring(cx(cl), cy(cl), 0xFFC084FC.toInt(), cell * 5f)
                    ring(cx(cl), cy(cl), 0xFF67E8F9.toInt(), cell * 3.2f)
                    shake(6f)
                }
                else -> shake(2.2f)
            }
        }
        for ((cl, _) in clear.created) ring(cx(cl), cy(cl), 0xFFFDE047.toInt(), cell * 1.3f)
        if (e.cascade >= 3) {
            comboAt = now
            comboLevel = e.cascade
            shake(1.5f + e.cascade * 0.6f)
        }
        // Бонус: каскад x4 - ещё один ход в подарок (начислен движком).
        if (clear.bonusMove) {
            pops += Pop(context.getString(R.string.m3_bonus_move), ox + cell * 4, oy + cell * 5.2f, now, true)
            postDelayed({ sfx(GameSfx.S.BONUS) }, 250)
        }
        for ((cl, _) in clear.created) {
            appear[cl] = 0.2f
            beams += Beam(cl, Match3Engine.BOMB, now)
        }
        // Появление бонусов - «вырастают» из вспышки.
        if (clear.created.isNotEmpty()) {
            val created = clear.created.keys.toList()
            postDelayed({ for (cl in created) appear[cl] = 1f }, 160)
        }
        // Очки за шаг - ровно столько, сколько прибавилось к счёту.
        val touched = clear.cells + clear.iceBroken + clear.goldHit + clear.created.keys
        if (clear.gained > 0 && touched.isNotEmpty()) {
            val mx = touched.map { cx(it) }.average().toFloat()
            val my = touched.map { cy(it) }.average().toFloat()
            pops += Pop("+${clear.gained}", mx, my, now, false)
        }
        if (clear.shape > 0 && e.cascade <= 1) {
            // Собрали фигуру: квадрат, уголок, линию…
            pops += Pop(context.getString(SHAPES[clear.shape]), ox + cell * 4, oy + cell * 3.2f, now, true)
        }
        if (e.cascade >= 2 || clear.fired.size >= 2) {
            val lvl = (maxOf(e.cascade, clear.fired.size) - 2).coerceIn(0, praise.size - 1)
            pops += Pop(context.getString(praise[lvl]), ox + cell * 4, oy + cell * 4, now, true)
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else if (clear.fired.isNotEmpty()) {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
        onChanged()
        phase = Phase.CLEAR
        phaseAt = now
        busy = true
    }

    private fun burst(x: Float, y: Float, color: Int) {
        val now = SystemClock.uptimeMillis().toFloat()
        val rnd = java.util.Random()
        val n = LiteMode.particles(if (live) 9 else 4)
        repeat(n) {
            val a = rnd.nextFloat() * 6.283f
            val sp = (0.06f + rnd.nextFloat() * 0.2f) * d
            val shard = it % 3 == 0
            parts += floatArrayOf(x, y, cos(a) * sp, sin(a) * sp - 0.08f * d, now, (if (it % 4 == 0) 0xFFFFF7C2.toInt() else color).toFloat(), if (shard) 5f + rnd.nextFloat() * 3f else 1.6f + rnd.nextFloat() * 1.8f, if (shard) 1f else 0f)
        }
        val cap = if (LiteMode.on) 180 else 400
        if (parts.size > cap) parts.subList(0, parts.size - cap).clear()
    }

    private fun leafBurst(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis().toFloat()
        val rnd = java.util.Random()
        repeat(LiteMode.particles(6)) {
            val a = rnd.nextFloat() * 6.283f
            val sp = (0.05f + rnd.nextFloat() * 0.12f) * d
            parts += floatArrayOf(x, y, cos(a) * sp, sin(a) * sp - 0.1f * d, now, (if (it % 2 == 0) 0xFF4ADE80.toInt() else 0xFF16A34A.toInt()).toFloat(), 6f, 1f)
        }
    }

    // ---------- ввод ----------

    private var downCell = -1
    private var downX = 0f
    private var downY = 0f

    private fun cellAt(x: Float, y: Float): Int {
        if (cell <= 0f || x < ox || y < oy) return -1
        val c = ((x - ox) / cell).toInt()
        val r = ((y - oy) / cell).toInt()
        if (c !in 0 until 8 || r !in 0 until 8) return -1
        val i = e.idx(r, c)
        return if (e.hole[i]) -1 else i
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (busy) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downCell = cellAt(ev.x, ev.y)
                downX = ev.x
                downY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (downCell < 0 || boostMode >= 0) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > cell * 0.35f || abs(dy) > cell * 0.35f) {
                    val r = e.row(downCell)
                    val c = e.col(downCell)
                    val target = if (abs(dx) > abs(dy)) {
                        if (dx > 0 && c < 7) downCell + 1 else if (dx < 0 && c > 0) downCell - 1 else -1
                    } else {
                        if (dy > 0 && r < 7) downCell + 8 else if (dy < 0 && r > 0) downCell - 8 else -1
                    }
                    val from = downCell
                    downCell = -1
                    if (target >= 0 && !e.blocked(target) && !e.blocked(from)) trySwap(from, target)
                }
            }
            MotionEvent.ACTION_UP -> {
                val cl = downCell
                downCell = -1
                if (cl < 0) return true
                if (boostMode >= 0) {
                    useBoost(cl)
                    return true
                }
                // Залежь не выбирается и не двигается.
                if (e.blocked(cl)) {
                    select(-1)
                    return true
                }
                // Двойное касание по бонусу - взорвать его на месте.
                val now = SystemClock.uptimeMillis()
                if (cl == lastTapCell && now - lastTapAt < 330 && e.special[cl] != 0 && e.activate(cl)) {
                    lastTapCell = -1
                    select(-1)
                    hint = null
                    lastAction = now
                    busy = true
                    onChanged()
                    resolveNext()
                    invalidate()
                    return true
                }
                lastTapCell = cl
                lastTapAt = now
                when {
                    selected < 0 -> select(cl)
                    selected == cl -> select(-1)
                    e.adjacent(selected, cl) -> {
                        val a = selected
                        select(-1)
                        trySwap(a, cl)
                    }
                    else -> select(cl)
                }
            }
        }
        return true
    }

    private fun select(i: Int) {
        selected = i
        if (i >= 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    private fun trySwap(a: Int, b: Int) {
        select(-1)
        if (e.blocked(a) || e.blocked(b)) return
        hint = null
        lastAction = SystemClock.uptimeMillis()
        swapA = a
        swapB = b
        phaseAt = SystemClock.uptimeMillis()
        busy = true
        if (e.ice[a] > 0 || e.ice[b] > 0) {
            // Замёрзший кристалл не двигается.
            busy = false
            flash(context.getString(R.string.m3_frozen))
            sfx(GameSfx.S.BAD)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
            return
        }
        if (e.swap(a, b)) {
            phase = Phase.SWAP
            sfx(GameSfx.S.SWAP)
            onChanged()
        } else {
            phase = Phase.SWAP_BACK
            sfx(GameSfx.S.BAD)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        invalidate()
    }

    private fun useBoost(cl: Int) {
        val b = boostMode
        // Бустер не тратится на клетку, где ему нечего делать (залежь для палочки).
        val ok = when (b) {
            Match3Game.B_HAMMER, Match3Game.B_BOMB -> e.boostable(cl)
            Match3Game.B_WAND -> !e.blocked(cl) && e.gem[cl] >= 0
            else -> false
        }
        if (!ok) {
            sfx(GameSfx.S.BAD)
            return
        }
        boostMode = -1
        hint = null
        lastAction = SystemClock.uptimeMillis()
        when (b) {
            Match3Game.B_HAMMER -> e.hammer(cl)
            Match3Game.B_BOMB -> e.bomb(cl)
            Match3Game.B_WAND -> {
                if (e.ice[cl] > 0) {
                    iceBurst(cx(cl), cy(cl))
                    hitAt[cl] = SystemClock.uptimeMillis()
                }
                e.wand(cl)
                onChanged()
                sfx(GameSfx.S.SPECIAL)
                appear[cl] = 0.2f
                beams += Beam(cl, Match3Engine.BOMB, SystemClock.uptimeMillis())
                postDelayed({ appear[cl] = 1f; invalidate() }, 160)
                onBoostUsed(b)
                invalidate()
                return
            }
            else -> return
        }
        onBoostUsed(b)
        sfx(if (b == Match3Game.B_BOMB) GameSfx.S.BOMB else GameSfx.S.PLACE)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        busy = true
        resolveNext()
        invalidate()
    }

    /** Победа: оставшиеся ходы превратились в очки - большая надпись над полем. */
    fun showEndBonus(points: Int) {
        if (points <= 0) return
        val now = SystemClock.uptimeMillis()
        pops += Pop(context.getString(R.string.m3v2_end_bonus, points), ox + cell * 4, oy + cell * 4, now, true)
        sfx(GameSfx.S.BONUS)
        invalidate()
    }

    /** Докупили ходы: если на поле нечего двигать - сразу перемешать. */
    fun movesAdded() {
        hint = null
        lastAction = SystemClock.uptimeMillis()
        if (!busy && e.findMove() == null) {
            flash(context.getString(R.string.m3_no_moves))
            doShuffle(free = true)
        }
        invalidate()
    }

    /** Перемешать поле (бустер или ходов нет). */
    fun doShuffle(free: Boolean = false) {
        sfx(GameSfx.S.SHUFFLE, 1.3f)
        hint = null
        shuffled = false
        phase = Phase.SHUFFLE
        phaseAt = SystemClock.uptimeMillis()
        busy = true
        invalidate()
    }
}
