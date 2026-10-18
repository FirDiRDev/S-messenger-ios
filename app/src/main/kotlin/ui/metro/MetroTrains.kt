// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.ui.metro

import java.util.Calendar

/**
 * Модель движения поездов метро - как на картах с автобусами, только без интернета.
 *
 * Живых данных о поездах в открытом доступе нет, поэтому движение считается по расписанию:
 * интервал зависит от времени суток (час пик - 2 мин, день - 3 мин, вечер - 4-5 мин, ночью
 * с 1:00 до 5:30 метро закрыто), время в пути между станциями - из схемы, стоянка - 25 с,
 * разворот на конечной - 2 мин. Получается правдоподобная картина «где сейчас поезда»
 * и «через сколько придёт следующий» (с точностью до минуты-двух).
 */
object MetroTrains {
    private const val DWELL = 25
    private const val TURN = 120

    /** Интервал движения (с) сейчас; null - метро закрыто. */
    fun headway(cal: Calendar = Calendar.getInstance()): Int? {
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val weekend = cal.get(Calendar.DAY_OF_WEEK).let { it == Calendar.SATURDAY || it == Calendar.SUNDAY }
        return when {
            m in 60 until 330 -> null
            m < 60 -> 360
            m < 420 -> 300
            !weekend && (m in 420 until 600 || m in 1020 until 1200) -> 120
            m < 1320 -> if (weekend) 240 else 180
            else -> 300
        }
    }

    /** Секунды с начала суток. */
    fun nowSec(cal: Calendar = Calendar.getInstance()): Int =
        cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)

    private fun seg(l: MetroScreen.Line, k: Int) = l.times[k] + DWELL

    private fun total(l: MetroScreen.Line) = l.times.indices.sumOf { seg(l, it) }

    private fun cycle(l: MetroScreen.Line) = if (l.ring) total(l) else 2 * total(l) + 2 * TURN

    /** Поезд на линии: между станциями a и b (индексы в схеме), доля пути f, направление dir (1/-1). */
    class Train(val a: Int, val b: Int, val f: Float, val dir: Int)

    /**
     * Буфер поездов без выделения памяти в каждом кадре: массивы растут один раз и потом
     * переиспользуются (раньше на каждый кадр создавались сотни объектов [Train] и списков).
     */
    class Buf {
        var n = 0
            private set
        var a = IntArray(64)
            private set
        var b = IntArray(64)
            private set
        var f = FloatArray(64)
            private set
        var dir = IntArray(64)
            private set

        fun clear() {
            n = 0
        }

        fun add(sa: Int, sb: Int, frac: Float, d: Int) {
            if (n == a.size) {
                val cap = n * 2
                a = a.copyOf(cap)
                b = b.copyOf(cap)
                f = f.copyOf(cap)
                dir = dir.copyOf(cap)
            }
            a[n] = sa
            b[n] = sb
            f[n] = frac
            dir[n] = d
            n++
        }
    }

    /** Где идёт поезд через tau секунд после выхода с первой станции (вперёд по массиву [ids]). */
    private fun along(l: MetroScreen.Line, ids: IntArray, times: IntArray, tau0: Int, dir: Int, extra: Float, out: Buf) {
        var tau = tau0
        for (k in times.indices) {
            val s = times[k] + DWELL
            if (tau < s) {
                val a = ids[k]
                val b = ids[(k + 1) % ids.size]
                if (tau < DWELL) out.add(a, b, 0f, dir) else out.add(a, b, ((tau - DWELL + extra) / times[k].coerceAtLeast(1)).coerceAtMost(1f), dir)
                return
            }
            tau -= s
        }
        val last = ids[if (l.ring) 0 else ids.size - 1]
        out.add(last, last, 0f, dir)
    }

    /** Развёрнутые массивы линии (обратный путь) - считаются один раз, а не в каждом кадре. */
    private class Prep(val revIds: IntArray, val revTimes: IntArray, val ringRev: IntArray, val cyc: Int, val tot: Int)

    private val prep = java.util.WeakHashMap<MetroScreen.Line, Prep>()

    private fun prep(l: MetroScreen.Line): Prep = synchronized(prep) {
        prep.getOrPut(l) {
            val ids = l.stations
            val revIds = ids.reversedArray()
            // Обратный путь: те же перегоны в обратном порядке.
            val revTimes = if (l.ring) {
                IntArray(l.times.size) { k -> l.times[(l.times.size - 1 - k + l.times.size) % l.times.size] }
            } else {
                l.times.reversedArray()
            }
            val ringRev = if (l.ring) IntArray(ids.size) { k -> ids[(ids.size - k) % ids.size] } else revIds
            Prep(revIds, revTimes, ringRev, cycle(l), total(l))
        }
    }

    /**
     * Все поезда линии прямо сейчас - дописываются в [out] (он не очищается). [frac] - доля
     * текущей секунды (0..1): поезда едут плавно, а не «прыгают» раз в секунду.
     */
    fun collect(l: MetroScreen.Line, now: Int, h: Int, frac: Float, out: Buf) {
        if (l.planned != null || l.stations.size < 2 || l.times.isEmpty() || h <= 0) return
        val pr = prep(l)
        val cyc = pr.cyc
        if (cyc <= 0) return
        val n = (cyc + h - 1) / h
        val ids = l.stations
        val tot = pr.tot
        for (j in 0 until n) {
            val phase = ((now + j * h) % cyc + cyc) % cyc
            if (l.ring) {
                along(l, ids, l.times, phase, 1, frac, out)
                along(l, pr.ringRev, pr.revTimes, phase, -1, frac, out)
            } else when {
                phase < tot -> along(l, ids, l.times, phase, 1, frac, out)
                phase < tot + TURN -> { val e = ids.last(); out.add(e, e, 0f, 1) }
                phase < 2 * tot + TURN -> along(l, pr.revIds, pr.revTimes, phase - tot - TURN, -1, frac, out)
                else -> { val e = ids.first(); out.add(e, e, 0f, -1) }
            }
        }
    }

    /** То же, что [collect], но списком (для редких вызовов вне отрисовки). */
    fun trains(l: MetroScreen.Line, now: Int, h: Int, frac: Float = 0f): List<Train> {
        val buf = Buf()
        collect(l, now, h, frac, buf)
        return List(buf.n) { Train(buf.a[it], buf.b[it], buf.f[it], buf.dir[it]) }
    }

    /**
     * Через сколько секунд придут ближайшие поезда на станцию [station] линии [l]:
     * пары (куда идёт поезд - конечная или «по кольцу», секунд до прибытия).
     */
    fun arrivals(l: MetroScreen.Line, station: Int, now: Int, h: Int): List<Pair<Int, Int>> {
        if (l.planned != null || h <= 0 || l.stations.size < 2 || l.times.isEmpty()) return emptyList()
        val i = l.stations.indexOf(station)
        if (i < 0) return emptyList()
        val pr = prep(l)
        val cyc = pr.cyc
        val tot = pr.tot
        val out = ArrayList<Pair<Int, Int>>()
        fun next(arrAt: Int): Int {
            // Поезд j проходит станцию в момент arrAt - j*h (по модулю цикла); ищем ближайший впереди.
            var best = Int.MAX_VALUE
            val n = (cyc + h - 1) / h
            for (j in 0 until n) {
                val t = (((arrAt - j * h - now) % cyc) + cyc) % cyc
                if (t < best) best = t
            }
            return best
        }
        val fwd = (0 until i).sumOf { seg(l, it) }
        if (l.ring) {
            out += 1 to next(fwd)
            val back = (0 until (l.stations.size - i) % l.stations.size).sumOf { seg(l, (l.times.size - 1 - it + l.times.size) % l.times.size) }
            out += -1 to next(back)
        } else {
            if (i < l.stations.size - 1) out += 1 to next(fwd)
            if (i > 0) out += -1 to next(tot + TURN + (i + 1 until l.stations.size).sumOf { seg(l, it - 1) })
        }
        return out
    }
}
