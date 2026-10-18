// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Весёлая фоновая музыка «Бега по рельсам» - синтезируется один раз в фоне (без файлов):
 * мелодия «чиптюн» с лёгким эхом, прыгающий бас, арпеджио аккордов и простые барабаны
 * (бочка, малый, хэт). 16 тактов, 140 ударов в минуту, бесшовная петля. Играется потоком
 * через AudioTrack; пауза - на паузе игры, конце забега и закрытии окна.
 * Звучит, только если включены звуки игр ([GameSfx.enabled]) и сама музыка (🎵).
 */
object RunnerMusic {
    private const val RATE = 22050
    private const val BPM = 140
    private const val STEP = RATE * 60 / BPM / 4 // сэмплов на шестнадцатую

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("runner", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("music", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("music", v).apply()

    /** Музыка должна звучать: включены и звуки игр, и музыка. */
    fun on(ctx: Context) = enabled(ctx) && GameSfx.enabled(ctx)

    @Volatile private var pcm: ShortArray? = null
    private val lock = Any()
    private var worker: Worker? = null

    /** Заранее синтезировать петлю (в фоне), чтобы музыка началась сразу со стартом. */
    fun prepare() {
        if (pcm != null) return
        Thread { runCatching { data() } }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    /** Сезонная тема с музыкой: вместо чиптюна - трек сезона ([com.stoyko.smessenger.games.runner.SeasonMusic]). */
    @Volatile var seasonal = false

    /** Играть (или продолжить с того же места). */
    fun play(ctx: Context) {
        if (!on(ctx)) {
            pause()
            return
        }
        if (seasonal) {
            pauseClassic()
            com.stoyko.smessenger.games.runner.SeasonMusic.play(ctx)
            return
        }
        com.stoyko.smessenger.games.runner.SeasonMusic.pause()
        val w = worker?.takeIf { it.alive } ?: Worker().also {
            worker = it
            it.start()
        }
        w.want = true
        w.track?.let { t -> runCatching { if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play() } }
    }

    fun pause() {
        com.stoyko.smessenger.games.runner.SeasonMusic.pause()
        pauseClassic()
    }

    private fun pauseClassic() {
        val w = worker ?: return
        w.want = false
        w.track?.let { t -> runCatching { t.pause() } }
    }

    /** Остановить и освободить звук (закрытие игры). */
    fun release() {
        com.stoyko.smessenger.games.runner.SeasonMusic.release()
        val w = worker ?: return
        worker = null
        w.alive = false
        w.want = false
        w.track?.let { t ->
            runCatching {
                t.pause()
                t.flush()
                t.stop()
            }
        }
        w.interrupt()
    }

    private class Worker : Thread() {
        @Volatile var alive = true
        @Volatile var want = false
        @Volatile var track: AudioTrack? = null

        init {
            isDaemon = true
            name = "runner-music"
        }

        override fun run() {
            val data = runCatching { data() }.getOrNull()
            if (data == null || !alive) {
                alive = false
                return
            }
            val t = runCatching { newTrack() }.getOrNull()
            if (t == null || t.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { t?.release() }
                alive = false
                return
            }
            track = t
            try {
                var pos = 0
                while (alive) {
                    if (!want) {
                        if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.pause()
                        Thread.sleep(30)
                        continue
                    }
                    if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
                    val n = minOf(2048, data.size - pos)
                    val wr = t.write(data, pos, n)
                    if (wr <= 0) {
                        if (!alive) break
                        Thread.sleep(15)
                        continue
                    }
                    pos += wr
                    if (pos >= data.size) pos = 0
                }
            } catch (e: InterruptedException) {
                // закрыли игру
            } catch (e: Throwable) {
                // звук недоступен - играем молча
            } finally {
                alive = false
                track = null
                runCatching { t.stop() }
                runCatching { t.release() }
            }
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val size = min.coerceAtLeast(4096) * 2
        return if (Build.VERSION.SDK_INT >= 23) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(size)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            legacyTrack(size)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyTrack(size: Int) =
        AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM)

    private fun data(): ShortArray = synchronized(lock) { pcm ?: build().also { pcm = it } }

    // ---------------- сочинение ----------------
    //
    // Мелодия - восьмыми от до 4-й октавы: число - нота (полутоны), -1 - тянуть, -2 - пауза.
    // Часть A (C G Am F C G F G) и часть B (Am F C G Am F G G), вместе - бесшовная петля.

    private val MELODY = intArrayOf(
        12, -1, 7, -1, 4, 7, 12, 14,
        14, -1, 11, -1, 7, 11, 14, 16,
        16, -1, 12, -1, 9, 12, 16, 19,
        17, 16, 14, 12, 14, -1, 12, -2,
        12, -1, 7, -1, 4, 7, 12, 14,
        14, -1, 11, -1, 7, 11, 14, 16,
        17, -1, 21, -1, 19, 17, 16, 14,
        14, 12, 11, 7, 11, -1, -2, -2,
        9, -1, 12, -1, 16, -1, 12, 9,
        9, -1, 12, -1, 17, 16, 14, 12,
        7, -1, 12, -1, 16, -1, 19, -1,
        19, 17, 16, 14, 11, -1, 7, -2,
        9, 12, 16, 12, 9, 12, 16, 19,
        21, -1, 19, 17, 16, -1, 14, 12,
        14, -1, 14, 16, 17, -1, 16, 14,
        11, -1, 14, -1, 19, -1, -2, -2,
    )

    /** Аккорды тактов: 0 - C, 1 - G, 2 - Am, 3 - F. */
    private val CHORDS = intArrayOf(0, 1, 2, 3, 0, 1, 3, 1, 2, 3, 0, 1, 2, 3, 1, 1)
    private val ROOT = intArrayOf(48, 55, 57, 53)
    private val ARP = arrayOf(intArrayOf(0, 4, 7, 12), intArrayOf(-1, 2, 7, 11), intArrayOf(0, 4, 9, 12), intArrayOf(0, 5, 9, 12))
    private val BASS = intArrayOf(0, 12, 0, 12, 0, 12, 7, 12)

    private fun freq(m: Int) = 440.0 * Math.pow(2.0, (m - 69) / 12.0)

    private fun build(): ShortArray {
        val bars = CHORDS.size
        val n = STEP * 16 * bars
        val mix = FloatArray(n)
        val lead = FloatArray(n)
        // Мелодия.
        var i = 0
        while (i < MELODY.size) {
            val m = MELODY[i]
            if (m < 0) {
                i++
                continue
            }
            var len = 1
            while (i + len < MELODY.size && MELODY[i + len] == -1) len++
            voice(lead, i * 2 * STEP, (len * 2 * STEP * 0.88).toInt(), freq(60 + m), 0.5)
            i += len
        }
        // Лёгкое эхо мелодии (через 3/16).
        val echo = 3 * STEP
        for (s in 0 until n) mix[s] += lead[s] + 0.28f * lead[(s - echo + n) % n]
        val rnd = java.util.Random(2026)
        for (bar in 0 until bars) {
            val ch = CHORDS[bar]
            val b0 = bar * 16 * STEP
            for (e in 0 until 8) bass(mix, b0 + e * 2 * STEP, (2 * STEP * 0.8).toInt(), freq(ROOT[ch] + BASS[e]), 0.4)
            for (s16 in 0 until 16) {
                val at = b0 + s16 * STEP
                pluck(mix, at, freq(60 + ARP[ch][s16 % 4]), if (s16 % 4 == 0) 0.12 else 0.08)
                if (s16 == 0 || s16 == 8 || (s16 == 10 && bar % 2 == 1)) kick(mix, at, 0.62)
                val fill = bar % 8 == 7 && s16 >= 13
                if (s16 == 4 || s16 == 12 || fill) snare(mix, at, if (fill) 0.26 else 0.36, rnd)
                if (s16 % 2 == 0) hat(mix, at, if (s16 % 4 == 2) 0.2 else 0.11, rnd)
            }
        }
        // Мягкий фильтр верхов (~6 кГц), с «прогревом» по концу петли - без щелчка на стыке.
        val a = (1 - exp(-2 * PI * 6000.0 / RATE)).toFloat()
        var y = 0f
        for (s in n - 4000 until n) y += a * (mix[s] - y)
        for (s in 0 until n) {
            y += a * (mix[s] - y)
            mix[s] = y
        }
        var peak = 1e-4f
        for (v in mix) peak = maxOf(peak, abs(v))
        val norm = 0.26f / peak
        return ShortArray(n) { (mix[it] * norm * 32767).toInt().coerceIn(-32767, 32767).toShort() }
    }

    /** Ведущий голос: «квадрат» из нечётных гармоник (мягко), лёгкое вибрато. */
    private fun voice(b: FloatArray, at: Int, len: Int, f: Double, gain: Double) {
        val rel = (RATE * 0.04).toInt()
        val total = len + rel
        var ph = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            val vib = if (t > 0.12) 1.0 + 0.004 * sin(2 * PI * 5.5 * t) else 1.0
            ph += 2 * PI * f * vib / RATE
            val v = sin(ph) + 0.26 * sin(3 * ph) + 0.12 * sin(5 * ph) + 0.05 * sin(7 * ph)
            val att = (t / 0.005).coerceAtMost(1.0)
            val dec = 0.62 + 0.38 * exp(-t * 9)
            val r = if (i < len) 1.0 else 1.0 - (i - len).toDouble() / rel
            b[(at + i) % b.size] += (v * att * dec * r * gain).toFloat()
        }
    }

    /** Бас: тёплый «треугольник» с быстрым спадом. */
    private fun bass(b: FloatArray, at: Int, len: Int, f: Double, gain: Double) {
        val rel = (RATE * 0.02).toInt()
        val total = len + rel
        var ph = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            ph += 2 * PI * f / RATE
            val v = sin(ph) + 0.25 * sin(2 * ph) - 0.1 * sin(3 * ph)
            val att = (t / 0.004).coerceAtMost(1.0)
            val r = if (i < len) 1.0 else 1.0 - (i - len).toDouble() / rel
            b[(at + i) % b.size] += (v * att * exp(-t * 4) * r * gain).toFloat()
        }
    }

    /** Арпеджио: короткий «щипок». */
    private fun pluck(b: FloatArray, at: Int, f: Double, gain: Double) {
        val total = (RATE * 0.18).toInt()
        var ph = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            ph += 2 * PI * f / RATE
            val v = sin(ph) + 0.25 * sin(2 * ph) * exp(-t * 20)
            val att = (t / 0.003).coerceAtMost(1.0)
            val tail = 1.0 - i.toDouble() / total
            b[(at + i) % b.size] += (v * att * exp(-t * 16) * tail * gain).toFloat()
        }
    }

    private fun kick(b: FloatArray, at: Int, gain: Double) {
        val total = (RATE * 0.2).toInt()
        var ph = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            ph += 2 * PI * (55 + 95 * exp(-t * 30)) / RATE
            val att = (t / 0.002).coerceAtMost(1.0)
            b[(at + i) % b.size] += (sin(ph) * att * exp(-t * 14) * gain).toFloat()
        }
    }

    private fun snare(b: FloatArray, at: Int, gain: Double, rnd: java.util.Random) {
        val total = (RATE * 0.16).toInt()
        var y = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            y += 0.55 * (rnd.nextDouble() * 2 - 1 - y)
            val att = (t / 0.002).coerceAtMost(1.0)
            val v = y * exp(-t * 22) + 0.5 * sin(2 * PI * 190 * t) * exp(-t * 30)
            b[(at + i) % b.size] += (v * att * gain).toFloat()
        }
    }

    private fun hat(b: FloatArray, at: Int, gain: Double, rnd: java.util.Random) {
        val total = (RATE * 0.05).toInt()
        var prev = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / RATE
            val nz = rnd.nextDouble() * 2 - 1
            val hp = nz - prev
            prev = nz
            b[(at + i) % b.size] += (hp * 0.5 * exp(-t * 70) * gain).toFloat()
        }
    }
}
