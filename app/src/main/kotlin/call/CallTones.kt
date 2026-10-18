// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.call

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Звуки звонка, синтезированные в приложении (без файлов):
 *  - [startRingback] - мягкие гудки, пока у собеседника звонит телефон: тёплый аккорд
 *    (425 Гц + квинта, как привычный гудок, но без резкости) с плавным нарастанием и
 *    затуханием, 1,1 с звука - 3 с тишины, по кругу;
 *  - [connected] - короткий приятный двузвучный сигнал, когда собеседник ответил.
 * Звук идёт тем же путём, что и разговор (у уха или громкая связь).
 */
object CallTones {
    private const val RATE = 22_050

    @Volatile
    private var ringback: AudioTrack? = null

    private fun attrs(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun track(pcm: ShortArray, loop: Boolean): AudioTrack? = runCatching {
        val t = AudioTrack(
            attrs(),
            AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            pcm.size * 2,
            AudioTrack.MODE_STATIC,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        t.write(pcm, 0, pcm.size)
        if (loop) t.setLoopPoints(0, pcm.size, -1)
        t
    }.getOrNull()

    /** Мягкий тон: основной + квинта и тихая октава, огибающая без щелчков. */
    private fun tone(out: ShortArray, from: Int, seconds: Float, freq: Double, volume: Float) {
        val n = (seconds * RATE).toInt()
        val attack = (0.08f * RATE).toInt()
        val release = (0.35f * RATE).toInt()
        for (i in 0 until min(n, out.size - from)) {
            val t = i.toDouble() / RATE
            val env = when {
                i < attack -> i.toFloat() / attack
                i > n - release -> (n - i).toFloat() / release
                else -> 1f
            }
            // Лёгкое «дыхание» громкости - звук живой, а не механический.
            val breath = 0.92 + 0.08 * sin(2 * PI * 2.0 * t)
            val v = (
                0.62 * sin(2 * PI * freq * t) +
                    0.28 * sin(2 * PI * freq * 1.5 * t) +
                    0.10 * sin(2 * PI * freq * 2 * t)
                ) * env * breath * volume
            // Ноты складываются (вторая может начаться, пока звучит хвост первой) - без щелчков.
            out[from + i] = (out[from + i] + v * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /** Гудки «Вызов…». Повторный вызов ничего не делает. */
    @Synchronized
    fun startRingback() {
        if (ringback != null) return
        val cycle = ShortArray((4.1f * RATE).toInt())
        tone(cycle, 0, 1.1f, 425.0, 0.38f)
        val t = track(cycle, loop = true) ?: return
        ringback = t
        runCatching { t.play() }
    }

    @Synchronized
    fun stopRingback() {
        val t = ringback ?: return
        ringback = null
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    /** «Дзынь» при ответе собеседника: две мягкие восходящие ноты. */
    fun connected() {
        val pcm = ShortArray((0.62f * RATE).toInt())
        tone(pcm, 0, 0.26f, 660.0, 0.30f)
        tone(pcm, (0.22f * RATE).toInt(), 0.40f, 880.0, 0.28f)
        // Плавный хвост, чтобы не было щелчка в конце.
        for (i in pcm.indices) {
            val k = exp(-3.0 * i / pcm.size)
            pcm[i] = (pcm[i] * (0.6 + 0.4 * k)).toInt().toShort()
        }
        val t = track(pcm, loop = false) ?: return
        runCatching { t.play() }
        Thread {
            Thread.sleep(900)
            runCatching { t.release() }
        }.start()
    }
}
