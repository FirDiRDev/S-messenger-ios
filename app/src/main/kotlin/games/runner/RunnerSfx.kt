// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games.runner

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.stoyko.smessenger.games.GameSfx
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Звуки «Бега по рельсам» - синтезируются при первом запуске (без файлов в приложении), как
 * [GameSfx]: гудок встречного поезда (аккорд «язычков»), свисток обходчика с трелью, лай
 * робопса, «вжух» смены пути и проносящегося поезда, прыжок, кувырок, приземление,
 * спотыкание, удар, гул ранца и звон спасения доской. Мягкий фильтр верхов - не режет уши.
 * Выключаются общей настройкой звуков игр ([GameSfx.enabled]).
 */
internal object RunnerSfx {
    private const val RATE = 22050
    private const val VERSION = 2

    enum class S { HORN, WHISTLE, BARK, WHOOSH, PASS, JUMP, ROLL, LAND, STUMBLE, CRASH, JET, SAVED, COIN }

    @Volatile private var pool: SoundPool? = null
    private val ids = HashMap<S, Int>()
    @Volatile private var preparing = false

    fun prepare(ctx: Context) {
        if (pool != null || preparing) return
        preparing = true
        val app = ctx.applicationContext
        Thread {
            runCatching {
                File(app.cacheDir, "sfx_runner").listFiles()?.forEach { if (it.name != "v$VERSION") it.deleteRecursively() }
                val dir = File(app.cacheDir, "sfx_runner/v$VERSION").apply { mkdirs() }
                val sp = SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .build()
                for (s in S.values()) {
                    val f = File(dir, s.name.lowercase() + ".wav")
                    // Монета - звонкая: без смягчающего фильтра верхов.
                    if (!f.exists() || f.length() < 100) writeWav(f, synth(s).also { if (s != S.COIN) soften(it) }, level(s))
                    synchronized(ids) { ids[s] = sp.load(f.path, 1) }
                }
                pool = sp
            }
            preparing = false
        }.apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
    }

    fun play(ctx: Context, s: S, rate: Float = 1f, vol: Float = 1f) {
        if (!GameSfx.enabled(ctx)) return
        val sp = pool ?: return
        val id = synchronized(ids) { ids[s] } ?: return
        runCatching { sp.play(id, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f)) }
    }

    fun release() {
        val sp = pool ?: return
        pool = null
        synchronized(ids) { ids.clear() }
        runCatching { sp.release() }
    }

    // ---------------- синтез

    private fun buf(sec: Double) = FloatArray((RATE * sec).toInt())

    /** «Язычковый» тон (гудок): гармоники 1/n, лёгкое вибрато, плавные края. */
    private fun reed(b: FloatArray, at: Double, f: Double, len: Double, gain: Double) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / RATE
            val vib = 1.0 + 0.004 * sin(2 * PI * 5.5 * t)
            ph += 2 * PI * f * vib / RATE
            var v = 0.0
            for (k in 1..7) v += sin(ph * k) / k
            val a = (t / 0.05).coerceAtMost(1.0)
            val r = ((len - t) / 0.12).coerceIn(0.0, 1.0)
            b[idx] += (v * a * r * gain).toFloat()
        }
    }

    private fun tone(b: FloatArray, at: Double, f: Double, len: Double, gain: Double, decay: Double = 6.0) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / RATE
            val a = (t / 0.006).coerceAtMost(1.0)
            b[idx] += (sin(2 * PI * f * t) * a * exp(-t * decay) * gain).toFloat()
        }
    }

    private fun glide(b: FloatArray, at: Double, f0: Double, f1: Double, len: Double, gain: Double, odd: Boolean = false) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val k = i.toDouble() / n
            ph += 2 * PI * (f0 * Math.pow(f1 / f0, k)) / RATE
            val env = sin(PI * k).let { it * it }
            val v = if (odd) sin(ph) + sin(3 * ph) / 3 + sin(5 * ph) / 6 else sin(ph)
            b[idx] += (v * env * gain).toFloat()
        }
    }

    /** Шум через двойной фильтр: [lp0] → [lp1] - «вжух» с меняющимся тембром. */
    private fun noise(b: FloatArray, at: Double, len: Double, gain: Double, lp0: Double, lp1: Double, shape: Int = 0) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var y = 0.0
        var y2 = 0.0
        val rnd = java.util.Random(s0.toLong() * 7 + n)
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val k = i.toDouble() / n
            val lp = lp0 + (lp1 - lp0) * k
            y += lp * (rnd.nextDouble() * 2 - 1 - y)
            y2 += lp * (y - y2)
            val env = when (shape) {
                1 -> (1 - k) * (1 - k) // удар
                2 -> sin(PI * k).let { it * it * it } // проносится мимо
                else -> sin(PI * k)
            }
            b[idx] += (y2 * env * gain * 4).toFloat()
        }
    }

    private fun thump(b: FloatArray, at: Double, f0: Double, len: Double, gain: Double) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / RATE
            ph += 2 * PI * f0 * (0.55 + 0.45 * exp(-t * 16)) / RATE
            val a = (t / 0.003).coerceAtMost(1.0)
            b[idx] += (sin(ph) * a * exp(-t * 10) * gain).toFloat()
        }
    }

    /** Свисток с трелью (горошина внутри - частотная модуляция). */
    private fun whistle(b: FloatArray, at: Double, len: Double, gain: Double) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / RATE
            val f = 2050.0 + 110.0 * sin(2 * PI * 27 * t)
            ph += 2 * PI * f / RATE
            val a = (t / 0.015).coerceAtMost(1.0) * ((len - t) / 0.03).coerceIn(0.0, 1.0)
            b[idx] += ((sin(ph) + 0.15 * sin(2 * ph)) * a * gain).toFloat()
        }
        noise(b, at, len, gain * 0.25, 0.25, 0.25)
    }

    private fun level(s: S): Float = when (s) {
        S.LAND, S.WHOOSH, S.ROLL -> 0.26f
        S.JUMP -> 0.3f
        S.BARK, S.WHISTLE -> 0.42f
        S.PASS, S.JET, S.STUMBLE, S.SAVED -> 0.45f
        S.HORN, S.CRASH -> 0.55f
        S.COIN -> 0.4f
    }

    private fun synth(s: S): FloatArray = when (s) {
        S.HORN -> buf(1.35).also { b ->
            // Два гудка аккордом (си-бемоль - ре - фа).
            for (at in doubleArrayOf(0.0, 0.58)) {
                val len = if (at == 0.0) 0.42 else 0.72
                reed(b, at, 233.1, len, 0.5)
                reed(b, at, 293.7, len, 0.42)
                reed(b, at, 349.2, len, 0.36)
            }
        }
        S.WHISTLE -> buf(0.62).also { b ->
            whistle(b, 0.0, 0.16, 0.6)
            whistle(b, 0.22, 0.38, 0.6)
        }
        S.BARK -> buf(0.42).also { b ->
            glide(b, 0.0, 720.0, 380.0, 0.1, 0.6, odd = true)
            glide(b, 0.17, 760.0, 360.0, 0.12, 0.6, odd = true)
            tone(b, 0.0, 1400.0, 0.05, 0.12, 40.0)
            tone(b, 0.17, 1500.0, 0.05, 0.12, 40.0)
        }
        S.WHOOSH -> buf(0.2).also { noise(it, 0.0, 0.18, 0.5, 0.05, 0.22) }
        S.PASS -> buf(1.3).also { b ->
            noise(b, 0.0, 1.25, 0.7, 0.2, 0.04, shape = 2)
            glide(b, 0.0, 330.0, 240.0, 1.2, 0.15)
        }
        S.JUMP -> buf(0.18).also { b ->
            glide(b, 0.0, 280.0, 620.0, 0.14, 0.45)
            noise(b, 0.0, 0.1, 0.25, 0.1, 0.2)
        }
        S.ROLL -> buf(0.32).also { b ->
            noise(b, 0.0, 0.3, 0.6, 0.04, 0.09)
            thump(b, 0.0, 110.0, 0.15, 0.3)
        }
        S.LAND -> buf(0.14).also { thump(it, 0.0, 150.0, 0.12, 0.7) }
        S.STUMBLE -> buf(0.45).also { b ->
            thump(b, 0.0, 95.0, 0.3, 0.9)
            noise(b, 0.0, 0.2, 0.35, 0.1, 0.05, shape = 1)
            glide(b, 0.05, 420.0, 240.0, 0.28, 0.3)
        }
        S.CRASH -> buf(1.0).also { b ->
            thump(b, 0.0, 70.0, 0.8, 1.0)
            noise(b, 0.0, 0.6, 0.6, 0.18, 0.03, shape = 1)
            // Металлический звон удара.
            val parts = doubleArrayOf(1.0, 2.76, 5.4)
            for ((i, p) in parts.withIndex()) tone(b, 0.0, 420.0 * p, 0.8, 0.18 / (i + 1), 5.0 + i * 3)
        }
        S.JET -> buf(1.1).also { b ->
            noise(b, 0.0, 1.05, 0.8, 0.03, 0.12)
            glide(b, 0.0, 120.0, 260.0, 1.0, 0.25)
        }
        S.COIN -> buf(0.32).also { b ->
            // Звонкое «дзынь-дзинь»: две ноты (си и ми третьей октавы) с колокольными обертонами.
            for ((at, f) in listOf(0.0 to 1975.5, 0.055 to 2637.0)) {
                tone(b, at, f, 0.26, 0.5, 14.0)
                tone(b, at, f * 2.76, 0.12, 0.18, 30.0)
            }
        }
        S.SAVED -> buf(0.7).also { b ->
            glide(b, 0.0, 400.0, 900.0, 0.3, 0.3)
            tone(b, 0.18, 1046.5, 0.5, 0.35, 6.0)
            tone(b, 0.26, 1318.5, 0.45, 0.3, 6.0)
        }
    }

    private fun soften(d: FloatArray) {
        val a = (1 - exp(-2 * PI * 3500.0 / RATE)).toFloat()
        var y = 0f
        for (i in d.indices) {
            y += a * (d[i] - y)
            d[i] = y
        }
        val fade = (RATE * 0.012).toInt().coerceAtMost(d.size)
        for (i in 0 until fade) d[d.size - 1 - i] *= i.toFloat() / fade
    }

    private fun writeWav(f: File, data: FloatArray, target: Float) {
        val peak = data.maxOfOrNull { abs(it) }?.coerceAtLeast(1e-4f) ?: 1f
        val norm = target / peak
        val pcm = ByteArray(data.size * 2)
        for (i in data.indices) {
            val v = (data[i] * norm * 32767).toInt().coerceIn(-32767, 32767)
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val tmp = File(f.path + ".tmp")
        RandomAccessFile(tmp, "rw").use { r ->
            r.setLength(0)
            fun i32(v: Int) = r.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()))
            fun i16(v: Int) = r.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
            r.write("RIFF".toByteArray())
            i32(36 + pcm.size)
            r.write("WAVEfmt ".toByteArray())
            i32(16)
            i16(1)
            i16(1)
            i32(RATE)
            i32(RATE * 2)
            i16(2)
            i16(16)
            r.write("data".toByteArray())
            i32(pcm.size)
            r.write(pcm)
        }
        tmp.renameTo(f)
    }
}
