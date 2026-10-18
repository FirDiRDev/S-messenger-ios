// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Уникальные анимации легендарных подарков. У каждого легендарного подарка - своя пара
 * слоёв: «сцена» позади подарка (северное сияние, магический круг, галактика, портал,
 * молнии, пламя, кристаллы, варп-звёзды, калейдоскоп, прожекторы, соты, эквалайзер…) и
 * «частицы» поверх (салют, искры, орбита самоцветов, золотая пыль, светлячки, падающие
 * звёзды, пузыри, монеты, вихрь лепестков, сердца, электричество, блики). 16 × 12 пар -
 * ни одна не повторяется; цвета берутся из самого подарка, темп - из его номера.
 *
 * Всё рисуется без выделения памяти в кадре (пути и кисти переиспользуются).
 */
internal class LegendFx(private val gift: GiftCatalog.Gift) {
    private val back: Int
    private val front: Int
    private val speed: Float
    private val c0 = gift.colors[0]
    private val c1 = gift.colors[1]
    private val gold = 0xFFFDE047.toInt()

    init {
        val idx = indexOf(gift)
        back = idx % BACKS
        front = (idx / BACKS + idx) % FRONTS
        speed = 0.85f + ((gift.id * 31) % 7) / 20f
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val path2 = Path()
    private val oval = RectF()
    private val rnd = java.util.Random(gift.id * 40503L + 7)
    private val rx = FloatArray(48) { rnd.nextFloat() }
    private val ry = FloatArray(48) { rnd.nextFloat() }
    private val rp = FloatArray(48) { rnd.nextFloat() * 6.2832f }
    private val rs = FloatArray(48) { 0.5f + rnd.nextFloat() }

    private fun a(col: Int, alpha: Float): Int = (col and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun mix(x: Int, y: Int, k: Float): Int {
        fun ch(sh: Int) = (((x shr sh) and 0xFF) * (1 - k) + ((y shr sh) and 0xFF) * k).toInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun light(col: Int) = mix(col, 0xFFFFFFFF.toInt(), 0.55f)

    /** Слой позади подарка. */
    fun drawBack(c: Canvas, w: Float, h: Float, t0: Float, lite: Boolean) {
        val t = t0 * speed
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) / 2
        s.strokeWidth = r * 0.02f
        when (back) {
            0 -> aurora(c, w, h, t)
            1 -> magicCircle(c, cx, cy, r, t)
            2 -> galaxy(c, cx, cy, r, t, lite)
            3 -> portal(c, cx, cy, r, t)
            4 -> sunburst(c, cx, cy, r, t)
            5 -> ripples(c, cx, cy, r, t)
            6 -> nebula(c, w, h, t)
            7 -> lightning(c, cx, cy, r, t)
            8 -> orbits(c, cx, cy, r, t, false)
            9 -> flames(c, w, h, t)
            10 -> shards(c, cx, cy, r, t)
            11 -> warp(c, cx, cy, r, t, lite)
            12 -> kaleido(c, cx, cy, r, t)
            13 -> beams(c, w, h, t)
            14 -> hexes(c, w, h, r, t)
            else -> equalizer(c, cx, cy, r, t)
        }
    }

    /** Слой поверх подарка. */
    fun drawFront(c: Canvas, w: Float, h: Float, t0: Float, lite: Boolean) {
        val t = t0 * speed
        val cx = w / 2
        val cy = h / 2
        val r = minOf(w, h) / 2
        val n = if (lite) 8 else 16
        when (front) {
            0 -> fireworks(c, w, h, r, t)
            1 -> embers(c, w, h, r, t, n)
            2 -> orbits(c, cx, cy, r, t, true)
            3 -> glitter(c, w, h, r, t, n)
            4 -> fireflies(c, w, h, r, t, n)
            5 -> shootingStars(c, w, h, r, t)
            6 -> bubbles(c, w, h, r, t, n)
            7 -> coins(c, w, h, r, t, n)
            8 -> petals(c, cx, cy, r, t, n)
            9 -> hearts(c, cx, cy, r, t)
            10 -> sparks(c, w, h, r, t)
            else -> flare(c, w, h, r, t)
        }
    }

    /**
     * Движение самого подарка: медленный «3D-поворот», парение и раз в несколько секунд -
     * вспышка силы (подарок набухает и отдаёт волну света).
     */
    fun motion(c: Canvas, w: Float, h: Float, t0: Float) {
        val t = t0 * speed
        val turn = 1f - 0.07f * (0.5f + 0.5f * sin(t * 0.9f))
        val fly = h * 0.025f * sin(t * 1.5f)
        val ph = (t * 0.25f) % 1f
        val pow = if (ph < 0.1f) sin(ph / 0.1f * PI.toFloat()) else 0f
        val k = 1f + 0.06f * pow
        c.translate(0f, fly)
        c.scale(turn * k, k, w / 2, h / 2)
        c.rotate(1.6f * sin(t * 0.7f), w / 2, h / 2)
    }

    /** Волна света при «вспышке силы» (рисуется поверх). */
    fun powerWave(c: Canvas, w: Float, h: Float, t0: Float) {
        val t = t0 * speed
        val ph = (t * 0.25f) % 1f
        if (ph > 0.35f) return
        val k = ph / 0.35f
        val r = minOf(w, h) / 2
        s.strokeWidth = r * 0.06f * (1 - k)
        s.color = a(light(c0), 0.8f * (1 - k))
        c.drawCircle(w / 2, h / 2, r * (0.3f + 0.9f * k), s)
    }

    // ---------------------------------------------------------------- сцены

    private fun aurora(c: Canvas, w: Float, h: Float, t: Float) {
        val cols = intArrayOf(c0, c1, 0xFF34D399.toInt())
        for (b in 0 until 3) {
            path.reset()
            val base = h * (0.25f + b * 0.17f)
            val amp = h * 0.07f
            path.moveTo(0f, base)
            var x = 0f
            val stepX = w / 14
            while (x <= w + 1) {
                path.lineTo(x, base + amp * sin(x / w * 6.2832f * 1.3f + t * (0.8f + b * 0.3f) + b))
                x += stepX
            }
            x = w
            while (x >= -1) {
                path.lineTo(x, base + h * 0.16f + amp * 0.6f * sin(x / w * 6.2832f + t * 1.1f + b * 2))
                x -= stepX
            }
            path.close()
            p.color = a(cols[b], 0.22f + 0.1f * sin(t * 1.3f + b))
            c.drawPath(path, p)
        }
    }

    private fun magicCircle(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val col = light(c0)
        s.color = a(col, 0.55f)
        c.drawCircle(cx, cy, r * 0.86f, s)
        c.drawCircle(cx, cy, r * 0.74f, s)
        // Руны - чёрточки между кругами, вращаются.
        c.save()
        c.rotate(t * 20f, cx, cy)
        for (i in 0 until 24) {
            val ang = i * 15f * PI.toFloat() / 180f
            val l = if (i % 3 == 0) 0.1f else 0.05f
            c.drawLine(cx + cos(ang) * r * 0.75f, cy + sin(ang) * r * 0.75f, cx + cos(ang) * r * (0.75f + l), cy + sin(ang) * r * (0.75f + l), s)
        }
        c.restore()
        // Гексаграмма вращается навстречу.
        c.save()
        c.rotate(-t * 32f, cx, cy)
        s.color = a(gold, 0.5f + 0.2f * sin(t * 3))
        for (tri in 0 until 2) {
            path.reset()
            for (i in 0 until 3) {
                val ang = (i * 120f + tri * 60f - 90f) * PI.toFloat() / 180f
                val x = cx + cos(ang) * r * 0.72f
                val y = cy + sin(ang) * r * 0.72f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, s)
        }
        c.restore()
    }

    private fun galaxy(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, lite: Boolean) {
        val n = if (lite) 30 else 48
        for (i in 0 until n) {
            val arm = i % 3
            val d = rx[i]
            val ang = arm * 2.094f + d * 4.2f + t * 0.6f
            val rr = r * (0.12f + d * 0.85f)
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr * 0.62f
            p.color = a(if (i % 4 == 0) 0xFFFFFFFF.toInt() else if (arm == 0) light(c0) else light(c1), 0.35f + 0.5f * (1 - d))
            c.drawCircle(x, y, r * 0.02f * rs[i] * (1.4f - d), p)
        }
        p.color = a(light(c0), 0.35f)
        c.drawCircle(cx, cy, r * 0.18f, p)
    }

    private fun portal(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        for (i in 0 until 6) {
            val k = ((i / 6f) + t * 0.35f) % 1f
            val rr = r * (1.05f - k)
            s.strokeWidth = r * 0.035f * (1 - k * 0.6f)
            s.color = a(mix(c0, c1, k), 0.15f + 0.5f * k)
            c.save()
            c.rotate(k * 120f + i * 17f, cx, cy)
            oval.set(cx - rr, cy - rr * 0.92f, cx + rr, cy + rr * 0.92f)
            c.drawRoundRect(oval, rr * 0.45f, rr * 0.45f, s)
            c.restore()
        }
    }

    private fun sunburst(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        c.save()
        c.rotate(t * 14f, cx, cy)
        val n = 14
        val pulse = 0.85f + 0.15f * sin(t * 2.4f)
        for (i in 0 until n) {
            val a0 = (i * 360f / n) * PI.toFloat() / 180f
            val a1 = a0 + PI.toFloat() / n * 0.75f
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + cos(a0) * r * 1.5f * pulse, cy + sin(a0) * r * 1.5f * pulse)
            path.lineTo(cx + cos(a1) * r * 1.5f * pulse, cy + sin(a1) * r * 1.5f * pulse)
            path.close()
            p.color = a(if (i % 2 == 0) gold else light(c0), 0.2f)
            c.drawPath(path, p)
        }
        c.restore()
    }

    private fun ripples(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        for (i in 0 until 4) {
            val k = ((i / 4f) + t * 0.3f) % 1f
            s.strokeWidth = r * 0.03f * (1 - k) + 1f
            s.color = a(light(c0), 0.6f * (1 - k))
            c.drawCircle(cx, cy, r * (0.2f + k * 0.95f), s)
        }
        // Каустика - мерцающие пятна света.
        for (i in 0 until 10) {
            val x = cx + (rx[i] - 0.5f) * r * 1.6f + sin(t + rp[i]) * r * 0.05f
            val y = cy + (ry[i] - 0.5f) * r * 1.6f + cos(t * 1.2f + rp[i]) * r * 0.05f
            p.color = a(0xFFFFFFFF.toInt(), 0.12f + 0.12f * sin(t * 2.5f + rp[i]))
            c.drawCircle(x, y, r * 0.07f * rs[i], p)
        }
    }

    private fun nebula(c: Canvas, w: Float, h: Float, t: Float) {
        for (i in 0 until 6) {
            val x = w * (0.2f + 0.6f * rx[i]) + sin(t * 0.4f + rp[i]) * w * 0.08f
            val y = h * (0.2f + 0.6f * ry[i]) + cos(t * 0.35f + rp[i]) * h * 0.08f
            val rr = w * (0.18f + 0.1f * rs[i])
            val col = if (i % 2 == 0) c0 else c1
            // Мягкое облако - три слоя полупрозрачных кругов.
            for (l in 0 until 3) {
                p.color = a(light(col), 0.07f)
                c.drawCircle(x, y, rr * (1f - l * 0.28f), p)
            }
        }
        for (i in 10 until 22) {
            p.color = a(0xFFFFFFFF.toInt(), 0.3f + 0.5f * abs(sin(t * 1.7f + rp[i])))
            c.drawCircle(w * rx[i], h * ry[i], w * 0.006f * rs[i] + 0.8f, p)
        }
    }

    private fun lightning(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val frame = floor(t * 7f).toInt()
        val flicker = (frame * 2654435761L).toInt()
        val rr = java.util.Random(gift.id * 31L + frame)
        val bolts = 3
        for (b in 0 until bolts) {
            if ((flicker shr b) and 3 == 0) continue
            val ang = rr.nextFloat() * 6.2832f
            path.reset()
            var x = cx + cos(ang) * r * 1.1f
            var y = cy + sin(ang) * r * 1.1f
            path.moveTo(x, y)
            for (seg in 1..6) {
                val k = seg / 6f
                val tx = cx + cos(ang) * r * 1.1f * (1 - k) + (rr.nextFloat() - 0.5f) * r * 0.25f * (1 - k)
                val ty = cy + sin(ang) * r * 1.1f * (1 - k) + (rr.nextFloat() - 0.5f) * r * 0.25f * (1 - k)
                x = tx
                y = ty
                path.lineTo(x, y)
            }
            s.strokeWidth = r * 0.05f
            s.color = a(light(c0), 0.25f)
            c.drawPath(path, s)
            s.strokeWidth = r * 0.016f
            s.color = a(0xFFFFFFFF.toInt(), 0.85f)
            c.drawPath(path, s)
        }
        p.color = a(light(c1), 0.12f + 0.1f * ((flicker and 7) / 7f))
        c.drawCircle(cx, cy, r * 0.5f, p)
    }

    private fun orbits(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, gems: Boolean) {
        val tilt = if (gems) -18f else 22f
        for (o in 0 until 2) {
            c.save()
            c.rotate(tilt + o * 60f, cx, cy)
            val rw = r * (0.82f - o * 0.12f)
            val rh = rw * 0.32f
            if (!gems) {
                s.strokeWidth = r * 0.014f
                s.color = a(light(if (o == 0) c0 else c1), 0.5f)
                oval.set(cx - rw, cy - rh, cx + rw, cy + rh)
                c.drawOval(oval, s)
            }
            val cnt = if (gems) 2 else 1
            for (k in 0 until cnt) {
                val ang = t * (1.3f + o * 0.4f) + k * PI.toFloat() + o
                val x = cx + cos(ang) * rw
                val y = cy + sin(ang) * rh
                val depth = 0.5f + 0.5f * sin(ang)
                if (gems) {
                    diamond(c, x, y, r * (0.05f + 0.04f * depth), if (k == 0) light(c0) else gold, 0.5f + 0.5f * depth)
                } else {
                    // Комета с хвостом.
                    for (tail in 0 until 6) {
                        val ta = ang - tail * 0.09f
                        p.color = a(0xFFFFFFFF.toInt(), (0.9f - tail * 0.15f) * (0.4f + 0.6f * depth))
                        c.drawCircle(cx + cos(ta) * rw, cy + sin(ta) * rh, r * (0.035f - tail * 0.004f), p)
                    }
                }
            }
            c.restore()
        }
    }

    private fun flames(c: Canvas, w: Float, h: Float, t: Float) {
        val n = 7
        for (i in 0 until n) {
            val x = w * (i + 0.5f) / n
            val fl = 0.55f + 0.45f * abs(sin(t * (3f + rs[i]) + rp[i]))
            val fh = h * 0.42f * fl
            val fw = w / n * 0.9f
            for (layer in 0 until 2) {
                val k = if (layer == 0) 1f else 0.55f
                path.reset()
                path.moveTo(x - fw * k / 2, h)
                path.quadTo(x - fw * k * 0.6f, h - fh * k * 0.5f, x + sin(t * 4 + i) * fw * 0.2f, h - fh * k)
                path.quadTo(x + fw * k * 0.6f, h - fh * k * 0.5f, x + fw * k / 2, h)
                path.close()
                p.color = if (layer == 0) a(mix(c0, 0xFFFF5A1F.toInt(), 0.5f), 0.4f) else a(gold, 0.45f)
                c.drawPath(path, p)
            }
        }
    }

    private fun shards(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        for (i in 0 until 8) {
            val ang = i * 0.785f + t * 0.5f
            val rr = r * (0.72f + 0.08f * sin(t * 1.5f + i))
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr
            c.save()
            c.rotate(ang * 57.3f + t * 60f, x, y)
            path.reset()
            val sz = r * 0.12f * rs[i]
            path.moveTo(x, y - sz)
            path.lineTo(x + sz * 0.45f, y + sz * 0.6f)
            path.lineTo(x - sz * 0.45f, y + sz * 0.6f)
            path.close()
            p.color = a(light(if (i % 2 == 0) c0 else c1), 0.45f)
            c.drawPath(path, p)
            s.strokeWidth = r * 0.012f
            s.color = a(RAINBOW[(i + (t * 3).toInt()) % RAINBOW.size], 0.8f)
            c.drawPath(path, s)
            c.restore()
        }
    }

    private fun warp(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, lite: Boolean) {
        val n = if (lite) 20 else 36
        s.strokeWidth = r * 0.015f
        for (i in 0 until n) {
            val k = (rx[i] + t * 0.45f * rs[i]) % 1f
            val ang = rp[i]
            val r0 = r * 1.3f * k * k
            val r1 = r * 1.3f * (k * k + 0.08f * k)
            s.color = a(if (i % 3 == 0) light(c0) else 0xFFFFFFFF.toInt(), k)
            c.drawLine(cx + cos(ang) * r0, cy + sin(ang) * r0, cx + cos(ang) * r1, cy + sin(ang) * r1, s)
        }
    }

    private fun kaleido(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        c.save()
        c.rotate(t * 18f, cx, cy)
        for (i in 0 until 6) {
            c.save()
            c.rotate(i * 60f, cx, cy)
            val open = 0.7f + 0.3f * sin(t * 1.6f)
            path.reset()
            path.moveTo(cx, cy)
            path.quadTo(cx + r * 0.35f * open, cy - r * 0.45f, cx, cy - r * 0.95f)
            path.quadTo(cx - r * 0.35f * open, cy - r * 0.45f, cx, cy)
            p.color = a(light(if (i % 2 == 0) c0 else c1), 0.2f)
            c.drawPath(path, p)
            s.strokeWidth = r * 0.01f
            s.color = a(gold, 0.45f)
            c.drawPath(path, s)
            c.restore()
        }
        c.restore()
    }

    private fun beams(c: Canvas, w: Float, h: Float, t: Float) {
        for (i in 0 until 3) {
            val ox = w * (0.2f + i * 0.3f)
            val ang = (-90f + 28f * sin(t * (0.7f + i * 0.2f) + i * 2)) * PI.toFloat() / 180f
            val len = h * 1.3f
            val spread = 0.16f
            path.reset()
            path.moveTo(ox, h)
            path.lineTo(ox + cos(ang - spread) * len, h + sin(ang - spread) * len)
            path.lineTo(ox + cos(ang + spread) * len, h + sin(ang + spread) * len)
            path.close()
            p.color = a(light(if (i == 1) c1 else c0), 0.16f)
            c.drawPath(path, p)
        }
    }

    private fun hexes(c: Canvas, w: Float, h: Float, r: Float, t: Float) {
        val sz = r * 0.2f
        val dx = sz * 1.732f
        val dy = sz * 1.5f
        var row = 0
        var y = 0f
        s.strokeWidth = r * 0.012f
        while (y < h + sz) {
            var x = if (row % 2 == 0) 0f else dx / 2
            while (x < w + sz) {
                val dist = sqrt((x - w / 2) * (x - w / 2) + (y - h / 2) * (y - h / 2)) / r
                val wave = 0.5f + 0.5f * sin(t * 3f - dist * 4f)
                path.reset()
                for (k in 0 until 6) {
                    val ang = (60f * k - 30f) * PI.toFloat() / 180f
                    val px = x + cos(ang) * sz * 0.9f
                    val py = y + sin(ang) * sz * 0.9f
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                s.color = a(light(c0), 0.1f + 0.45f * wave)
                c.drawPath(path, s)
                x += dx
            }
            y += dy
            row++
        }
    }

    private fun equalizer(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val n = 28
        s.strokeWidth = r * 0.045f
        for (i in 0 until n) {
            val ang = i * 6.2832f / n
            val v = 0.3f + 0.7f * abs(sin(t * (2.2f + (i % 5) * 0.4f) + i * 0.7f))
            val r0 = r * 0.62f
            val r1 = r0 + r * 0.3f * v
            s.color = a(mix(c0, gold, i / n.toFloat()), 0.55f)
            c.drawLine(cx + cos(ang) * r0, cy + sin(ang) * r0, cx + cos(ang) * r1, cy + sin(ang) * r1, s)
        }
    }

    // ---------------------------------------------------------------- частицы

    private fun fireworks(c: Canvas, w: Float, h: Float, r: Float, t: Float) {
        for (b in 0 until 2) {
            val ph = (t * 0.5f + b * 0.5f) % 1f
            val cycle = floor(t * 0.5f + b * 0.5f).toInt()
            val bx = w * (0.25f + 0.5f * (((cycle * 7 + b * 3 + gift.id) % 10) / 10f))
            val by = h * (0.22f + 0.2f * (((cycle * 3 + b) % 5) / 5f))
            val col = if ((cycle + b) % 2 == 0) light(c0) else gold
            if (ph < 0.15f) {
                // Взлёт.
                val k = ph / 0.15f
                p.color = a(0xFFFFFFFF.toInt(), 0.9f)
                c.drawCircle(bx, h - (h - by) * k, r * 0.02f, p)
                continue
            }
            val k = (ph - 0.15f) / 0.85f
            for (i in 0 until 14) {
                val ang = i * 6.2832f / 14 + b
                val dist = r * 0.45f * (1 - (1 - k) * (1 - k))
                val x = bx + cos(ang) * dist
                val y = by + sin(ang) * dist + r * 0.25f * k * k
                p.color = a(col, 1f - k)
                c.drawCircle(x, y, r * 0.022f * (1 - k * 0.5f), p)
            }
        }
    }

    private fun embers(c: Canvas, w: Float, h: Float, r: Float, t: Float, n: Int) {
        for (i in 0 until n) {
            val k = (ry[i] + t * 0.25f * rs[i]) % 1f
            val x = w * rx[i] + sin(t * 2f + rp[i]) * w * 0.04f
            val y = h * (1.05f - k * 1.1f)
            p.color = a(if (i % 3 == 0) gold else mix(c0, 0xFFFF7A1A.toInt(), 0.5f), (1 - k) * 0.95f)
            c.drawCircle(x, y, r * 0.022f * rs[i] * (1 - k * 0.5f), p)
        }
    }

    private fun glitter(c: Canvas, w: Float, h: Float, r: Float, t: Float, n: Int) {
        for (i in 0 until n) {
            val k = (ry[i] + t * 0.18f * rs[i]) % 1f
            val x = w * rx[i] + sin(t * 1.5f + rp[i]) * w * 0.05f
            val y = h * (-0.05f + k * 1.1f)
            val spin = cos(t * 5f * rs[i] + rp[i])
            oval.set(x - r * 0.03f * abs(spin), y - r * 0.02f, x + r * 0.03f * abs(spin), y + r * 0.02f)
            p.color = a(if (spin > 0) gold else light(gold), 0.9f)
            c.drawOval(oval, p)
        }
    }

    private fun fireflies(c: Canvas, w: Float, h: Float, r: Float, t: Float, n: Int) {
        for (i in 0 until n) {
            val x = w * (0.5f + 0.42f * sin(t * 0.5f * rs[i] + rp[i]) * cos(t * 0.31f + i))
            val y = h * (0.5f + 0.42f * cos(t * 0.43f * rs[i] + rp[i] * 2))
            val g = 0.5f + 0.5f * sin(t * 3f + rp[i] * 3)
            p.color = a(light(c1), 0.18f * g)
            c.drawCircle(x, y, r * 0.08f, p)
            p.color = a(0xFFFFF7AE.toInt(), 0.5f + 0.5f * g)
            c.drawCircle(x, y, r * 0.022f, p)
        }
    }

    private fun shootingStars(c: Canvas, w: Float, h: Float, r: Float, t: Float) {
        s.strokeWidth = r * 0.02f
        for (i in 0 until 3) {
            val ph = (t * 0.45f + i / 3f) % 1f
            if (ph > 0.4f) continue
            val k = ph / 0.4f
            val sx = w * (0.1f + rx[i] * 0.8f) + w * 0.6f * k
            val sy = h * (0.05f + ry[i] * 0.4f) + h * 0.4f * k
            for (tail in 0 until 5) {
                val tk = tail * 0.06f
                s.color = a(0xFFFFFFFF.toInt(), (1 - k) * (1 - tail / 5f))
                c.drawLine(sx - w * 0.6f * tk, sy - h * 0.4f * tk, sx - w * 0.6f * (tk + 0.06f), sy - h * 0.4f * (tk + 0.06f), s)
            }
            star(c, sx, sy, r * 0.05f, a(gold, 1 - k))
        }
    }

    private fun bubbles(c: Canvas, w: Float, h: Float, r: Float, t: Float, n: Int) {
        s.strokeWidth = r * 0.01f
        for (i in 0 until n) {
            val k = (ry[i] + t * 0.2f * rs[i]) % 1f
            val x = w * rx[i] + sin(t * 2.4f + rp[i]) * w * 0.03f
            val y = h * (1.05f - k * 1.15f)
            val rr = r * 0.05f * rs[i]
            s.color = a(light(c0), 0.7f * (1 - k * 0.5f))
            c.drawCircle(x, y, rr, s)
            p.color = a(0xFFFFFFFF.toInt(), 0.7f)
            c.drawCircle(x - rr * 0.35f, y - rr * 0.35f, rr * 0.22f, p)
        }
    }

    private fun coins(c: Canvas, w: Float, h: Float, r: Float, t: Float, n: Int) {
        for (i in 0 until n / 2 + 2) {
            val k = (ry[i] + t * 0.3f * rs[i]) % 1f
            val x = w * rx[i]
            val y = h * (-0.08f + k * 1.16f)
            val spin = cos(t * 4f + rp[i])
            val rw = r * 0.06f * abs(spin) + 1f
            oval.set(x - rw, y - r * 0.06f, x + rw, y + r * 0.06f)
            p.color = if (spin > 0) 0xFFFACC15.toInt() else 0xFFD97706.toInt()
            c.drawOval(oval, p)
            s.strokeWidth = r * 0.008f
            s.color = 0xFFFFF7AE.toInt()
            c.drawOval(oval, s)
        }
    }

    private fun petals(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, n: Int) {
        for (i in 0 until n) {
            val k = (rx[i] + t * 0.12f) % 1f
            val ang = rp[i] + t * (0.8f + rs[i] * 0.3f)
            val rr = r * (0.25f + 0.75f * k)
            val x = cx + cos(ang) * rr
            val y = cy + sin(ang) * rr * 0.8f
            c.save()
            c.rotate(ang * 57.3f + t * 90f, x, y)
            oval.set(x - r * 0.05f, y - r * 0.025f, x + r * 0.05f, y + r * 0.025f)
            p.color = a(light(if (i % 2 == 0) c0 else c1), 0.85f * (1 - k * 0.6f))
            c.drawOval(oval, p)
            c.restore()
        }
    }

    private fun hearts(c: Canvas, cx: Float, cy: Float, r: Float, t: Float) {
        val beat = 1f + 0.15f * abs(sin(t * 3.2f))
        for (i in 0 until 6) {
            val ang = i * 1.047f + t * 0.7f
            val x = cx + cos(ang) * r * 0.78f
            val y = cy + sin(ang) * r * 0.78f
            heart(c, x, y, r * 0.07f * beat, if (i % 2 == 0) 0xFFF43F5E.toInt() else light(c0))
        }
    }

    private fun sparks(c: Canvas, w: Float, h: Float, r: Float, t: Float) {
        val frame = floor(t * 10f).toInt()
        val rr = java.util.Random(gift.id * 97L + frame)
        s.strokeWidth = r * 0.014f
        for (i in 0 until 4) {
            // Искра бежит по краю рамки.
            val e = (rx[i] + t * 0.3f * rs[i]) % 1f
            val (x, y) = edge(w, h, e)
            path.reset()
            path.moveTo(x, y)
            var px = x
            var py = y
            for (k in 0 until 3) {
                px += (rr.nextFloat() - 0.5f) * r * 0.18f
                py += (rr.nextFloat() - 0.5f) * r * 0.18f
                path.lineTo(px, py)
            }
            s.color = a(light(c1), 0.9f)
            c.drawPath(path, s)
            p.color = a(0xFFFFFFFF.toInt(), 0.9f)
            c.drawCircle(x, y, r * 0.025f, p)
        }
    }

    private fun flare(c: Canvas, w: Float, h: Float, r: Float, t: Float) {
        val ph = (t * 0.3f) % 1f
        val lx = w * (-0.2f + 1.4f * ph)
        val ly = h * (0.2f + 0.1f * sin(t))
        p.color = a(0xFFFFFFFF.toInt(), 0.5f)
        c.drawCircle(lx, ly, r * 0.07f, p)
        // Призрачные блики на линии через центр.
        for (i in 1..4) {
            val k = i * 0.45f
            val fx = lx + (w / 2 - lx) * k * 1.4f
            val fy = ly + (h / 2 - ly) * k * 1.4f
            path.reset()
            for (j in 0 until 6) {
                val ang = j * 1.047f
                val px = fx + cos(ang) * r * 0.05f * i * 0.6f
                val py = fy + sin(ang) * r * 0.05f * i * 0.6f
                if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close()
            p.color = a(RAINBOW[i % RAINBOW.size], 0.18f)
            c.drawPath(path, p)
        }
        for (i in 0 until 6) {
            val tw = abs(sin(t * 2.5f + rp[i]))
            star(c, w * rx[i], h * ry[i], r * 0.05f * tw, a(0xFFFFFFFF.toInt(), tw))
        }
    }

    // ---------------------------------------------------------------- формы

    private fun edge(w: Float, h: Float, e: Float): Pair<Float, Float> {
        val per = e * 4
        val m = w * 0.06f
        return when {
            per < 1 -> m + (w - 2 * m) * per to m
            per < 2 -> w - m to m + (h - 2 * m) * (per - 1)
            per < 3 -> w - m - (w - 2 * m) * (per - 2) to h - m
            else -> m to h - m - (h - 2 * m) * (per - 3)
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, sz: Float, col: Int) {
        if (sz <= 0.3f) return
        path2.reset()
        path2.moveTo(x, y - sz)
        path2.quadTo(x, y, x + sz, y)
        path2.quadTo(x, y, x, y + sz)
        path2.quadTo(x, y, x - sz, y)
        path2.quadTo(x, y, x, y - sz)
        p.color = col
        c.drawPath(path2, p)
    }

    private fun diamond(c: Canvas, x: Float, y: Float, sz: Float, col: Int, alpha: Float) {
        path2.reset()
        path2.moveTo(x, y - sz)
        path2.lineTo(x + sz * 0.7f, y)
        path2.lineTo(x, y + sz)
        path2.lineTo(x - sz * 0.7f, y)
        path2.close()
        p.color = a(col, alpha)
        c.drawPath(path2, p)
        p.color = a(0xFFFFFFFF.toInt(), alpha * 0.8f)
        c.drawCircle(x - sz * 0.15f, y - sz * 0.3f, sz * 0.15f, p)
    }

    private fun heart(c: Canvas, x: Float, y: Float, sz: Float, col: Int) {
        path2.reset()
        path2.moveTo(x, y + sz)
        path2.cubicTo(x - sz * 1.6f, y - sz * 0.2f, x - sz * 0.6f, y - sz * 1.3f, x, y - sz * 0.4f)
        path2.cubicTo(x + sz * 0.6f, y - sz * 1.3f, x + sz * 1.6f, y - sz * 0.2f, x, y + sz)
        p.color = col
        c.drawPath(path2, p)
    }

    companion object {
        const val BACKS = 16
        const val FRONTS = 12
        private val RAINBOW = intArrayOf(0xFFF43F5E.toInt(), 0xFFFB923C.toInt(), 0xFFFDE047.toInt(), 0xFF4ADE80.toInt(), 0xFF22D3EE.toInt(), 0xFFA78BFA.toInt())

        @Volatile private var order: Map<Int, Int>? = null

        /** Порядковый номер среди легендарных - по нему у каждого своя пара слоёв. */
        private fun indexOf(g: GiftCatalog.Gift): Int {
            val m = order ?: runCatching {
                GiftCatalog.of(GiftCatalog.Rarity.LEGENDARY).map { it.id }.sorted().withIndex().associate { it.value to it.index }
            }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) order = it }
            return m[g.id] ?: ((g.id * 7) and 0x7FFFFFFF)
        }
    }
}
