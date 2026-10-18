// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.domain.av

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Обработка звука звонка поверх системных эффектов (которые на многих телефонах слабые или
 * вовсе отсутствуют):
 *  - срез низких частот (гул, удары по корпусу, постоянная составляющая) - фильтр ~90 Гц;
 *  - эхоподавитель: пока из динамика звучит собеседник, а вы молчите, микрофон приглушается
 *    (его же голос, отражённый от стен и корпуса, не уходит обратно) - на громкой связи сильнее;
 *  - шумовой порог с оценкой фона (вентилятор, улица, шипение микрофона) - между словами тише;
 *  - мягкий ограничитель - без хрипов от перегруза при громкой речи.
 * Усиление меняется плавно по образцам (без щелчков).
 */
object CallDsp {
    // ---------- что сейчас звучит из динамика (опорный сигнал эха) ----------
    private const val FAR_SLOTS = 32
    private val farRms = FloatArray(FAR_SLOTS)
    private val farTime = LongArray(FAR_SLOTS)
    private var farIdx = 0

    @Volatile
    var speakerOn = false

    /** Системный эхоподавитель есть и работает - свой включаем мягче. */
    @Volatile
    var platformAec = false

    /** Звук ушёл в динамик (вызывает проигрыватель). */
    fun onPlayout(frame: ShortArray, n: Int = frame.size) {
        val r = rms(frame, n)
        synchronized(farRms) {
            farIdx = (farIdx + 1) % FAR_SLOTS
            farRms[farIdx] = r
            farTime[farIdx] = System.nanoTime()
        }
    }

    /** Самый громкий звук собеседника за последние ~300 мс (эхо приходит с задержкой). */
    private fun farLevel(): Float {
        val now = System.nanoTime()
        var m = 0f
        synchronized(farRms) {
            for (i in 0 until FAR_SLOTS) if (now - farTime[i] < 300_000_000L) m = max(m, farRms[i])
        }
        return m
    }

    // ---------- состояние микрофона ----------
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f
    private var noiseFloor = 300f
    private var gain = 1f
    private var talkHold = 0
    private var echoHold = 0

    fun reset() {
        hpPrevIn = 0f
        hpPrevOut = 0f
        noiseFloor = 300f
        gain = 1f
        talkHold = 0
        echoHold = 0
        synchronized(farRms) {
            farRms.fill(0f)
            farTime.fill(0L)
        }
    }

    /** Обработать кадр микрофона на месте. [sampleRate] - для фильтра. */
    fun processMic(frame: ShortArray, sampleRate: Int) {
        if (frame.isEmpty()) return
        // 1) Срез ниже ~90 Гц (однополюсный фильтр верхних частот).
        val rc = 1f / (2f * Math.PI.toFloat() * 90f)
        val dt = 1f / sampleRate
        val a = rc / (rc + dt)
        var pi = hpPrevIn
        var po = hpPrevOut
        val buf = FloatArray(frame.size)
        for (i in frame.indices) {
            val x = frame[i].toFloat()
            val y = a * (po + x - pi)
            pi = x
            po = y
            buf[i] = y
        }
        hpPrevIn = pi
        hpPrevOut = po

        // 2) Уровни: свой голос, фон, собеседник.
        var sum = 0.0
        for (v in buf) sum += (v * v).toDouble()
        val near = sqrt(sum / buf.size).toFloat()
        // Фон оценивается по самым тихим кадрам: быстро вниз, медленно вверх.
        noiseFloor = if (near < noiseFloor) {
            noiseFloor * 0.85f + near * 0.15f
        } else {
            min(noiseFloor * 1.004f + 0.5f, near)
        }.coerceIn(30f, 4000f)
        val far = farLevel()

        val speech = near > noiseFloor * 2.6f + 120f
        if (speech) talkHold = 12 else if (talkHold > 0) talkHold-- // ~240 мс после слова не режем

        // 3) Эхо: собеседник звучит громко, а наш сигнал не сильно громче его отражения.
        val echoRatio = if (speakerOn) 0.55f else 0.22f
        val echoLikely = far > 400f && near < far * echoRatio * (if (platformAec) 0.7f else 1f) + noiseFloor * 2f
        val doubleTalk = far > 400f && near > far * (if (speakerOn) 1.1f else 0.6f)
        if (echoLikely && !doubleTalk) echoHold = if (speakerOn) 8 else 4 else if (echoHold > 0) echoHold--

        val target = when {
            echoHold > 0 -> if (speakerOn) 0.06f else 0.25f // -24 дБ / -12 дБ
            talkHold > 0 -> 1f
            else -> 0.32f // пауза между словами: фон тише (-10 дБ), но не «дыра»
        }
        // 4) Плавное изменение усиления (быстрее вниз при эхе, мягче вверх) + ограничитель.
        val stepDown = 1f / (sampleRate * 0.012f)
        val stepUp = 1f / (sampleRate * 0.040f)
        var g = gain
        for (i in buf.indices) {
            g = if (g > target) max(target, g - stepDown) else min(target, g + stepUp)
            var y = buf[i] * g
            // мягкое ограничение выше ~-3 дБ
            val ay = abs(y)
            if (ay > 23000f) {
                val over = (ay - 23000f) / 9767f
                y = (23000f + 9767f * (over / (1f + over))) * (if (y < 0) -1f else 1f)
            }
            frame[i] = y.coerceIn(-32767f, 32767f).toInt().toShort()
        }
        gain = g
    }

    fun rms(frame: ShortArray, n: Int = frame.size): Float {
        if (n <= 0) return 0f
        var s = 0.0
        for (i in 0 until n) {
            val v = frame[i].toDouble()
            s += v * v
        }
        return sqrt(s / n).toFloat()
    }
}
