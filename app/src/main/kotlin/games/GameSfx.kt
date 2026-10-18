// SPDX-FileCopyrightText: 2026 Стойко Андрей (StoykoMessenger)
//
// SPDX-License-Identifier: GPL-3.0-only

package com.stoyko.smessenger.games

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Звуки игр («Три в ряд», карты, Змейка) - синтезируются при первом запуске (без файлов
 * в приложении): кристальный звон, «вжух» обмена, удар бомбы, лучи, шелест и шлепок карт,
 * «ам» змейки и т. д. Сохраняются в кэш как WAV и играются через SoundPool - мгновенно и
 * по нескольку сразу. Выключаются одной настройкой (🔊 в играх).
 */
object GameSfx {
    private const val RATE = 22050
    private const val VERSION = 3

    enum class S {
        SWAP, BAD, POP, LINE, BOMB, RAINBOW, LAND, SPECIAL, WIN, LOSE, STAR, BOOST,
        DEAL, PLACE, SHUFFLE, TAKE, BITO,
        EAT, BONUS, LEVEL, DIE, TICK,
    }

    @Volatile private var pool: SoundPool? = null
    private val ids = HashMap<S, Int>()
    @Volatile private var preparing = false

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("games_ui", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("sfx", true)
    fun setEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("sfx", v).apply()

    /** Подготовить звуки заранее (в фоне), чтобы первый звук не опаздывал. */
    fun prepare(ctx: Context) {
        if (pool != null || preparing) return
        preparing = true
        val app = ctx.applicationContext
        Thread {
            runCatching {
                // Старые (резкие) версии звуков - удаляем.
                File(app.cacheDir, "sfx").listFiles()?.forEach { if (it.name != "v$VERSION") it.deleteRecursively() }
                val dir = File(app.cacheDir, "sfx/v$VERSION").apply { mkdirs() }
                val sp = SoundPool.Builder()
                    .setMaxStreams(8)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .build()
                for (s in S.values()) {
                    val f = File(dir, s.name.lowercase() + ".wav")
                    if (!f.exists() || f.length() < 100) writeWav(f, synth(s).also { soften(it) }, level(s))
                    synchronized(ids) { ids[s] = sp.load(f.path, 1) }
                }
                pool = sp
            }
            preparing = false
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Сыграть звук; [rate] 0.5…2 - высота (каскады звучат всё выше), [vol] 0…1. */
    fun play(ctx: Context, s: S, rate: Float = 1f, vol: Float = 1f) {
        if (!enabled(ctx)) return
        val sp = pool
        if (sp == null) {
            prepare(ctx)
            return
        }
        val id = synchronized(ids) { ids[s] } ?: return
        runCatching { sp.play(id, vol, vol, 1, 0, rate.coerceIn(0.5f, 2f)) }
    }

    // ---------------- синтез ----------------
    //
    // С 4.1.0 звуки мягкие («не бьют по ушам»): тёплые тона как у калимбы и маримбы, плавная
    // атака без щелчков, ноты пентатоники (C-D-E-G-A) в среднем регистре, без пилы и резкого
    // шума; всё проходит через мягкий фильтр верхов, и каждый звук тише предыдущей версии.

    private fun buf(sec: Double) = FloatArray((RATE * sec).toInt())

    // Ноты: до-ре-ми-соль-ля (пентатоника) в 4-6 октавах.
    private const val C4 = 261.63
    private const val E4 = 329.63
    private const val G4 = 392.0
    private const val A4 = 440.0
    private const val C5 = 523.25
    private const val D5 = 587.33
    private const val E5 = 659.25
    private const val G5 = 783.99
    private const val A5 = 880.0
    private const val C6 = 1046.5
    private const val D6 = 1174.7
    private const val E6 = 1318.5

    /** Тёплый тон (калимба): основной + немного 2-й и 3-й гармоник, мягкая атака. */
    private fun tone(b: FloatArray, at: Double, f: Double, len: Double, gain: Double, decay: Double = 7.0, attack: Double = 0.008, harm: Double = 0.22) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / RATE
            val a = (t / attack).coerceAtMost(1.0)
            val env = a * a * (3 - 2 * a) * exp(-t * decay) * (1.0 - (i.toDouble() / n).let { it * it * it })
            val v = sin(2 * PI * f * t) + harm * sin(4 * PI * f * t) * exp(-t * 6) + harm * 0.35 * sin(6 * PI * f * t) * exp(-t * 12)
            b[idx] += (v * env * gain).toFloat()
        }
    }

    /** Стеклянный колокольчик - мягкий (обертоны тихие и быстро гаснут). */
    private fun bell(b: FloatArray, at: Double, f: Double, gain: Double, len: Double = 0.45) {
        val parts = doubleArrayOf(1.0, 2.0, 2.76)
        val amps = doubleArrayOf(1.0, 0.18, 0.06)
        val dec = doubleArrayOf(6.5, 12.0, 18.0)
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / RATE
            var v = 0.0
            for (k in parts.indices) v += amps[k] * sin(2 * PI * f * parts[k] * t) * exp(-t * dec[k])
            val a = (t / 0.006).coerceAtMost(1.0)
            b[idx] += (v * gain * a * (1.0 - i.toDouble() / n)).toFloat()
        }
    }

    /** Плавный «скольжение» тона (синус) с колоколообразной огибающей - без резких краёв. */
    private fun glide(b: FloatArray, at: Double, f0: Double, f1: Double, len: Double, gain: Double) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val k = i.toDouble() / n
            val f = f0 * Math.pow(f1 / f0, k)
            ph += 2 * PI * f / RATE
            val env = sin(PI * k).let { it * it }
            b[idx] += (sin(ph) * env * gain).toFloat()
        }
    }

    /** Мягкий «шорох» (сильно приглушённый шум, медленная атака) - для карт и обмена. */
    private fun puff(b: FloatArray, at: Double, len: Double, gain: Double, lp: Double = 0.12) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var y = 0.0
        var y2 = 0.0
        val rnd = java.util.Random(s0.toLong() * 31 + n)
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            y += lp * (rnd.nextDouble() * 2 - 1 - y)
            y2 += lp * (y - y2)
            val k = i.toDouble() / n
            val env = sin(PI * k.coerceAtMost(1.0)).let { if (k < 0.3) it else it * it }
            b[idx] += (y2 * env * gain * 3).toFloat()
        }
    }

    /** Глухой мягкий удар (бомба, карта на стол): низкий синус с быстрым спадом высоты. */
    private fun thump(b: FloatArray, at: Double, f0: Double, len: Double, gain: Double) {
        val s0 = (at * RATE).toInt()
        val n = (len * RATE).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= b.size) break
            val t = i.toDouble() / RATE
            val f = f0 * (0.55 + 0.45 * exp(-t * 18))
            ph += 2 * PI * f / RATE
            val a = (t / 0.004).coerceAtMost(1.0)
            b[idx] += (sin(ph) * a * exp(-t * 9) * gain).toFloat()
        }
    }

    /** Громкость звука (доля от максимума) - частые звуки тише. */
    private fun level(s: S): Float = when (s) {
        S.TICK, S.LAND -> 0.22f
        S.SWAP, S.DEAL, S.PLACE, S.EAT -> 0.34f
        S.POP, S.SHUFFLE, S.TAKE, S.BITO, S.BAD -> 0.4f
        S.LINE, S.STAR, S.SPECIAL, S.BOOST, S.BONUS -> 0.46f
        S.BOMB, S.RAINBOW, S.WIN, S.LOSE, S.LEVEL, S.DIE -> 0.52f
    }

    private fun synth(s: S): FloatArray = when (s) {
        S.SWAP -> buf(0.2).also { puff(it, 0.0, 0.16, 0.35, 0.1); glide(it, 0.0, G4, C5, 0.14, 0.35) }
        S.BAD -> buf(0.34).also { tone(it, 0.0, E4, 0.16, 0.6, 10.0); tone(it, 0.14, C4, 0.2, 0.6, 9.0) }
        S.POP -> buf(0.42).also { tone(it, 0.0, E5, 0.4, 0.7, 9.0); bell(it, 0.0, E6, 0.12, 0.25) }
        S.LINE -> buf(0.6).also { b ->
            doubleArrayOf(C5, D5, E5, G5, A5).forEachIndexed { i, f -> tone(b, i * 0.045, f, 0.35, 0.5, 9.0) }
            glide(b, 0.0, C5, C6, 0.3, 0.12)
        }
        S.BOMB -> buf(0.9).also { b ->
            thump(b, 0.0, 120.0, 0.6, 1.0)
            puff(b, 0.0, 0.5, 0.35, 0.06)
            tone(b, 0.06, C4, 0.7, 0.35, 4.0)
            tone(b, 0.06, G4, 0.7, 0.22, 4.5)
        }
        S.RAINBOW -> buf(1.1).also { b ->
            doubleArrayOf(C5, E5, G5, A5, C6, D6, E6).forEachIndexed { i, f -> bell(b, i * 0.07, f, 0.42, 0.6) }
            tone(b, 0.0, C4, 1.0, 0.18, 2.5)
        }
        S.LAND -> buf(0.08).also { tone(it, 0.0, G4, 0.07, 0.5, 40.0, attack = 0.003, harm = 0.1) }
        S.SPECIAL -> buf(0.6).also { glide(it, 0.0, C5, G5, 0.22, 0.3); bell(it, 0.16, C6, 0.45, 0.45) }
        S.WIN -> buf(1.4).also { b ->
            doubleArrayOf(C5, E5, G5, C6).forEachIndexed { i, f -> tone(b, i * 0.13, f, 0.7, 0.6, 4.0) }
            bell(b, 0.52, E6, 0.25, 0.8)
            tone(b, 0.52, C4, 0.9, 0.25, 2.5)
        }
        S.LOSE -> buf(1.1).also { b -> doubleArrayOf(G4, E4, C4).forEachIndexed { i, f -> tone(b, i * 0.24, f, 0.5, 0.55, 3.5) } }
        S.STAR -> buf(0.55).also { bell(it, 0.0, A5, 0.6, 0.5); bell(it, 0.06, E6, 0.2, 0.4) }
        S.BOOST -> buf(0.45).also { glide(it, 0.0, G4, G5, 0.3, 0.35); bell(it, 0.22, C6, 0.3, 0.3) }
        S.DEAL -> buf(0.1).also { puff(it, 0.0, 0.08, 0.5, 0.22) }
        S.PLACE -> buf(0.16).also { thump(it, 0.0, 260.0, 0.12, 0.5); puff(it, 0.0, 0.06, 0.25, 0.15) }
        S.SHUFFLE -> buf(0.8).also { b -> for (i in 0 until 10) puff(b, i * 0.065, 0.07, 0.4 + (i % 3) * 0.06, 0.2) }
        S.TAKE -> buf(0.4).also { puff(it, 0.0, 0.32, 0.5, 0.1); glide(it, 0.0, E5, C5, 0.3, 0.15) }
        S.BITO -> buf(0.5).also { b -> for (i in 0 until 3) puff(b, i * 0.06, 0.07, 0.4, 0.2); bell(b, 0.2, G5, 0.3, 0.3) }
        S.EAT -> buf(0.16).also { glide(it, 0.0, C5, G5, 0.12, 0.6) }
        S.BONUS -> buf(0.7).also { b -> doubleArrayOf(G5, A5, C6, E6).forEachIndexed { i, f -> bell(b, i * 0.07, f, 0.42, 0.45) } }
        S.LEVEL -> buf(1.0).also { b -> doubleArrayOf(E5, G5, C6).forEachIndexed { i, f -> tone(b, i * 0.11, f, 0.6, 0.6, 4.5) } }
        S.DIE -> buf(0.8).also { glide(it, 0.0, G4, C4, 0.55, 0.5); tone(it, 0.3, C4, 0.45, 0.3, 4.0) }
        S.TICK -> buf(0.04).also { tone(it, 0.0, A5, 0.035, 0.4, 60.0, attack = 0.002, harm = 0.0) }
    }

    /** Мягкий фильтр верхов (~3.5 кГц) и затухание хвоста без щелчка. */
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

    /** 16-бит моно WAV. */
    private fun writeWav(f: File, data: FloatArray, target: Float) {
        val peak = data.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1e-4f) ?: 1f
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
